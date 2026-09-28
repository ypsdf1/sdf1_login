package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.scoreboard.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import org.bukkit.entity.Egg;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PVP竞技场管理器 - 独立世界模式（完整主世界地形）
 *
 * 流程（修正后）：
 * 1. 玩家传送进入PVP世界（/pvp join）
 * 2. 标记状态，先打开装备选择GUI（玩家仍持有原背包）
 * 3. 玩家确认装备选择 / 一键全套
 * 4. ★ 此时才：备份原背包 → 清空 → 发放PVP装备
 * 5. 战斗阶段（死亡/击杀）
 * 6. 退场（任何方式离开PVP世界）：
 *    - 立即回收PVP装备
 *    - 还原入场时的背包数据
 *    - 从竞技场状态移除
 *
 * 退场触发条件：
 * - /pvp leave 命令
 * - 传送到其他世界（PlayerChangedWorldEvent）
 * - 死亡（PlayerDeathEvent）→ 复活后立即还原并传走
 * - 掉线/被踢（PlayerQuitEvent）→ 标记待还原，下次上线时处理
 */
public class PVPArenaManager implements Listener {

    private final Main plugin;
    private final DatabaseManager db;

    // PVP世界名称
    private static final String PVP_WORLD_NAME = "pvp_arena";
    /**
     * 当前PVP世界的实际名字（每次创建时随机生成 pvp_arena_<随机数>，彻底绕开旧目录残留冲突）。
     * 历史写死 PVP_WORLD_NAME 的创建/获取/卸载/玩家移动判定统一改用本字段，确保指向同一个随机世界。
     */
    private String pvpWorldName = PVP_WORLD_NAME;

    // PVP世界空场冷却时间（毫秒）：所有玩家退场后世界继续保留此时长，
    // 冷却内若有玩家重新进入则取消删除；冷却结束且仍无人则删除世界，下次进入随机重新生成地形。
    // 目的：①战斗破坏的地形/被砍的树随世界删除而完全复原；②每次地形随机刷新，防止背图玩家单方面碾压。
    private static final long PVP_WORLD_IDLE_DELETE_MS = 5 * 60 * 1000L; // 5分钟（可按需调整）

    // 玩家在PVP世界中的状态
    private final Set<String> inPVPArena = ConcurrentHashMap.newKeySet();

    // 已完成装备选择的玩家（备份+清空+发装备已执行完毕）
    private final Set<String> equipmentConfirmed = ConcurrentHashMap.newKeySet();

    // ★ 永久确认玩家（选装一次即锁定，退出再进入也不能重新选装，仅调整食物/盾牌/弓弩）
    private final Set<String> permanentlyConfirmed = ConcurrentHashMap.newKeySet();

    // ★ 永久确认玩家的选装结果（跨次保留，供调整GUI使用）
    private static class SavedSelection {
        int weaponTier, armorTier, rangedWeapon, food;
        boolean enchant, shield;
        SavedSelection(int w, int a, int r, boolean e, boolean sh, int f) {
            weaponTier=w; armorTier=a; rangedWeapon=r; enchant=e; shield=sh; food=f;
        }
    }
    private final Map<String, SavedSelection> savedSelections = new ConcurrentHashMap<>();

    // 程序内重开装备GUI（如切换档位）时，旧GUI关闭事件需忽略，避免误触发遣返
    private final Set<String> guiReopening = ConcurrentHashMap.newKeySet();

    // ★ GUI打开时间戳（用于宽限期机制：GUI刚打开后短时间内的关闭事件视为过渡误触，忽略遣返）
    private final Map<String, Long> guiOpenedMillis = new ConcurrentHashMap<>();

    // 玩家背包备份缓存 (玩家名 -> 备份数据)
    private final Map<String, InventoryBackup> inventoryBackups = new ConcurrentHashMap<>();

    // ★ PVP装备选择超时定时器（玩家名 -> BukkitTask）：进入PVP后N秒未确认则自动遣返
    //   防止玩家进入后一直开着GUI不操作也不退出（卡死/挂机/忘记关游戏）
    private final Map<String, BukkitTask> kickTimeoutTasks = new ConcurrentHashMap<>();

    /** 装备选择超时时间（秒）：进入PVP后此时间内必须确认，否则自动遣返 */
    private static final long EQUIPMENT_SELECT_TIMEOUT_SECONDS = 60L;

    /** 装备GUI打开后的宽限期（毫秒）：此窗口内的关闭事件视为世界加载/过渡导致的瞬时误触，
     *  忽略遣返并在玩家当前未打开任何GUI时自动重开，让其能正常选装备（避免地图加载即被踢）。 */
    private static final long EQUIPMENT_SELECT_GRACE_MILLIS = 3000L;

    /** 宽限期内自动重开次数上限（防极端情况下世界持续强制关闭GUI导致死循环） */
    private final Map<String, Integer> graceReopenCount = new ConcurrentHashMap<>();

    // 待执行的"空场冷却删除世界"定时任务（有人重新进入时取消）
    private BukkitTask pendingDeleteTask = null;

    // 随机地形种子生成器（每次创建世界用新种子 → 地形不同）
    private final Random worldSeedRandom = new Random();

    // PVP装备列表 (管理员可配置) — 当前默认用于向后兼容，实际由档位系统驱动
    private final List<ItemStack> pvpEquipment = new ArrayList<>();

    // ★ PVP战绩榜（记分板侧边栏）：记录本局竞技场玩家的击杀/死亡数
    private Scoreboard pvpScoreboard = null;
    private final Map<String, Integer> pvpKills = new ConcurrentHashMap<>();
    private final Map<String, Integer> pvpDeaths = new ConcurrentHashMap<>();

    // ★ 当前竞技场会话ID（持久化到DB）
    private int currentSessionId = -1;

    // 连杀播报（移植自 PVPManager，全服播报）
    private final Map<String, PVPManager.KillSession>
            arenaKillSessions =
            new ConcurrentHashMap<>();
    private final Set<String> arenaTripleAnnounced =
            ConcurrentHashMap.newKeySet();
    private static final String ARENA_LABEL = "PVP竞技场";

    // ★ PVP特殊道具常量
    private static final String FIRE_CHARGE_NAME = "§c§lPVP火球";
    private static final String BRIDGE_EGG_NAME = "§a§lPVP搭桥蛋";
    private static final Material BRIDGE_BLOCK = Material.GLASS;

    // 搭桥蛋追踪：egg实体ID -> 跟随任务的ID
    private final Map<Integer, Integer> bridgeEggTrackers = new ConcurrentHashMap<>();
    // 搭桥蛋已跳过的tick数（前2tick跳过，避免生成在玩家身上）
    private final Map<Integer, Integer> bridgeEggSkipTicks = new ConcurrentHashMap<>();
    // 搭桥蛋上一tick的位置（方块放这里，避免蛋撞自己刚放的方块）
    private final Map<Integer, Location> bridgeLastLoc = new ConcurrentHashMap<>();

    // 记分板条目缓存：玩家名 -> 侧边栏条目字符串（用于精确清除）
    private final Map<String, String> scoreEntries = new ConcurrentHashMap<>();

    // 装备选择GUI标题
    private static final String EQUIPMENT_GUI_TITLE = "§6§l选择PVP装备";

    // ★ 新装备模型（2026-07-10 改版）：
    //   主武器 = 同材质剑 + 斧（两者必发），材质可选：铁/金/钻石/下界合金；
    //   护甲 = 同材质全套 4 件，材质可选：铁/金/钻石/下界合金；
    //   附魔 = 锋利V + 击退II（作用于主武器）；盾牌可选（副手）；补血普通食物 = 熟牛肉（数量可调）。
    //   ★ 公平锁定：首个进入PVP世界的玩家定死"武器/护甲/附魔"三档，后续加入者不可更改（保证公平）。
    //     盾牌与熟牛肉数量对所有玩家始终可选。
    private final Map<String, Boolean> selEnchant = new ConcurrentHashMap<>(); // 该玩家是否要附魔主武器
    private final Map<String, Boolean> selShield = new ConcurrentHashMap<>();  // 该玩家是否拿盾牌
    private final Map<String, Integer> selFood = new ConcurrentHashMap<>();    // 熟牛肉数量
    private final Map<String, Integer> selWeaponTier = new ConcurrentHashMap<>(); // 主武器材质档 0铁1金2钻3合金
    private final Map<String, Integer> selRangedWeapon = new ConcurrentHashMap<>(); // 远程武器 0弓(默认) 1弩
    private final Map<String, Integer> selArmorTier = new ConcurrentHashMap<>();  // 护甲材质档 0铁1金2钻3合金

    private static final int FOOD_MIN = 1;
    private static final int FOOD_MAX = 64;
    private static final int FOOD_DEFAULT = 16;

    private static final Material[] SWORD_TIERS = {
            Material.IRON_SWORD, Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD };
    private static final Material[] AXE_TIERS = {
            Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE };
    private static final Material[][] ARMOR_SETS = {
            { Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS },
            { Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS },
            { Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS },
            { Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS } };
    private static final String[] WEAPON_TIER_NAMES = { "§7铁", "§e金", "§b钻石", "§8下界合金" };
    private static final String[] ARMOR_TIER_NAMES = { "§7铁", "§e金", "§b钻石", "§8下界合金" };

    // ★ 公平锁定状态：首个进入本局PVP世界的玩家定死武器/护甲/附魔
    private String lockedEnchantOwner = null;
    private Boolean lockedEnchant = null;
    private String lockedWeaponOwner = null;
    private Integer lockedWeaponTier = null;
    private String lockedArmorOwner = null;
    private Integer lockedArmorTier = null;

    // 玩家是否已在装备GUI中发生过交互（点击任意功能按钮），用于 onInventoryClose 区分"已选/ESC跳过"
    private final Set<String> equipInteracted = ConcurrentHashMap.newKeySet();

    // PVP专属物品标记（写入 lore，isPVPEquipment 据此精准识别，避免误删玩家自带同材质物品）
    private static final String PVP_ITEM_MARKER = "§8§o[PVP竞技场专属]";

    /**
     * 背包备份数据类
     */
    public static class InventoryBackup {
        public ItemStack[] contents;
        public ItemStack[] armor;
        public ItemStack offHand;
        public int expLevel;
        public float exp;

        public InventoryBackup(ItemStack[] contents, ItemStack[] armor, ItemStack offHand, int expLevel, float exp) {
            this.contents = contents != null ? contents.clone() : new ItemStack[36];
            this.armor = armor != null ? armor.clone() : new ItemStack[4];
            this.offHand = offHand;
            this.expLevel = expLevel;
            this.exp = exp;
        }
    }

    public PVPArenaManager(Main plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();

        // 初始化默认PVP装备
        initDefaultEquipment();

        // 加载配置
        loadConfig();

        // ★ 观赛身份强制巡视（每2秒）：已登记为观赛者且仍在PVP世界的玩家，
        //   若模式被外部改成生存/冒险等 → 立即强制恢复观察者并打印诊断日志。
        //   战斗拦截另有"登记身份"双保险（见 isProtectedSpectator），二者共同保证
        //   观赛者以任何形式都无法攻击玩家或被玩家攻击。
        Bukkit.getScheduler().runTaskTimer(plugin, this::enforceSpectatorModes, 40L, 40L);
    }

    /**
     * 初始化默认PVP装备
     */
    private void initDefaultEquipment() {
        pvpEquipment.clear();

        // 铁甲套装
        pvpEquipment.add(new ItemStack(Material.IRON_HELMET));
        pvpEquipment.add(new ItemStack(Material.IRON_CHESTPLATE));
        pvpEquipment.add(new ItemStack(Material.IRON_LEGGINGS));
        pvpEquipment.add(new ItemStack(Material.IRON_BOOTS));

        // 钻石剑
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        if (swordMeta != null) {
            swordMeta.setDisplayName("§b§lPVP钻石剑");
            sword.setItemMeta(swordMeta);
        }
        pvpEquipment.add(sword);

        // 弓
        pvpEquipment.add(new ItemStack(Material.BOW));

        // 箭 x 64
        pvpEquipment.add(new ItemStack(Material.ARROW, 64));

        // 金苹果 x 8
        pvpEquipment.add(new ItemStack(Material.GOLDEN_APPLE, 8));

        // 药水（带真实治疗效果 NBT）
        ItemStack potion = new ItemStack(Material.POTION, 3);
        ItemMeta potionMeta = potion.getItemMeta();
        if (potionMeta instanceof PotionMeta) {
            PotionMeta pMeta = (PotionMeta) potionMeta;
            pMeta.setDisplayName("§a§l瞬间治疗药水");
            pMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.INSTANT_HEALTH,
                    1, 0, false, false), true);
            pMeta.setBasePotionType(org.bukkit.potion.PotionType.HEALING);
            potion.setItemMeta(pMeta);
        }
        pvpEquipment.add(potion);
    }

    /**
     * 加载配置
     */
    private void loadConfig() {
        // TODO: 从配置文件加载PVP装备列表
    }

    /**
     * 保存配置
     */
    public void saveConfig() {
        // TODO: 保存PVP装备列表到配置文件
    }

    /**
     * 检查并确保PVP世界可用。
     *
     * ★ 2026-07-08 改为"懒创建"模型（按用户要求）：
     *   - 插件不干预玩家出生点（不再 setSpawnLocation 锁定 0,0）；
     *   - 世界仅在玩家 /pvp join 时按需创建；
     *   - 启动阶段无玩家，不预创建世界（符合"没人了就删除"的语义）。
     *   真正的创建/复用/删除逻辑在 joinArena() 中按"有无玩家在场"判断。
     */
    public void ensurePVPWorldExists() {
        cancelPendingDeletion();
        // ★ 启动时顺手清理历史回收站目录，避免磁盘无限增长
        cleanupOldTrashWorlds();
        // ★ 启动时清理历史残留的 pvp_arena* 世界目录（新布局 world/dimensions/minecraft/ 下的也一并清）
        cleanupStalePVPArenaWorlds();
        World w = Bukkit.getWorld(pvpWorldName);
        if (w != null && !w.getPlayers().isEmpty()) {
            plugin.getLogger().info("[PVP] 检查：PVP世界已有玩家在场，保持现状");
        } else {
            plugin.getLogger().info("[PVP] 检查：暂不创建PVP世界（懒创建，首次 /pvp join 时生成）");
        }
    }

    /**
     * 创建全新PVP世界 — 纯 NORMAL 主世界地形，插件全程不插手地形生成、不干预出生点。
     *
     * ★ 2026-07-08（按用户明确要求，2026-07-08 晚修正）：
     *   1) 每次随机种子 + 每次随机世界名 —— 即便 MC/Paper 忽略 seed，全新世界名也保证地形必为全新生成；
     *   2) 不调用 setSpawnLocation —— 完全保留 MC 自然出生点（不再锁定 0,0，也不做任何"就近迁移"）；
     *   3) 仅设游戏规则（常昼/无天气/PVP 开启）并预生成出生点周边区块（确保地形已就绪）；
     *   4) 出生点是否合格由 joinArena() 做"唯一一次"脚下方块检查（水→上移该玩家，非水→直接放行）。
     */
    /**
     * 创建PVP竞技场世界（随机种子，防背图）。
     *
     * 设计：玩家全部离开后世界被删除，下次加入时重新随机种子生成【全新地形】，
     * 避免熟悉地图的玩家背图追杀新手。
     *
     * ★ 卡服根因修复（2026-07-08）：原实现用 WorldType.NORMAL + Bukkit.createWorld 在主线程
     *   同步"搜索出生点 + 生成自然地形"，耗时 13s 触发 Paper 看门狗（Chunk wait）导致卡服/报错。
     *   现改回 WorldType.NORMAL 真实自然地形：地形由 Minecraft 自然生成（丘陵/水域/洞穴/树木），不再人造。
     *   ★ 重要：Bukkit.createWorld 内部会【同步】触发 WorldInitEvent/WorldLoadEvent，Paper 只允许主线程触发，
     *     故 createWorld 必须在主线程执行（异步会抛 IllegalStateException 崩溃，第22轮已踩坑）。
     *   ★ 为消除原 13s 卡服：① 移除"水→删除重建换种子"循环（改为 forceSafeSpawn 兜底平台，createWorld 只跑一次）；
     *     ② 周边区块由 preGenerateSpawnChunksAsync 同步分批预生成（每 tick 一行，不冻服）。主线程仅承担单次 createWorld(NORMAL)。
     */
    private World createPVPWorld() {
        long t0 = System.currentTimeMillis();

        // 随机新世界名（彻底绕开旧目录残留冲突）
        deleteWorldFolderIfExists(pvpWorldName);
        this.pvpWorldName = PVP_WORLD_NAME + "_" + Long.toUnsignedString(worldSeedRandom.nextLong());

        long seed = worldSeedRandom.nextLong();
        WorldCreator c = new WorldCreator(pvpWorldName);
        c.environment(World.Environment.NORMAL);
        // ★ 真实自然地形：丘陵/水域/洞穴/树木由 Minecraft 生成（非人造）
        c.type(WorldType.NORMAL);
        c.seed(seed);
        // ★ 关键性能：关闭村庄/神庙/矿洞等结构生成。结构生成是每次 createWorld 出生点搜索时
        //   单个区块被拖到十几秒卡死的主因。hill/water/cave/tree 等自然地形特征不受影响，
        //   仍保持"真实自然地形"，只是不再生成人造结构。
        c.generateStructures(false);
        // 注意：Paper 26.x 已移除 WorldCreator.keepSpawnInMemory(boolean)，改由 reapplyWorldRules 的 setKeepSpawnInMemory(false) 控制

        plugin.getLogger().info("[PVP] 生成PVP竞技场世界 " + pvpWorldName + " 种子=" + seed + " (NORMAL真实自然地形, 主线程同步createWorld)");
        World pvpWorld = Bukkit.createWorld(c);
        long dt = System.currentTimeMillis() - t0;
        if (pvpWorld == null) {
            plugin.getLogger().severe("[PVP] 无法创建PVP竞技场世界! (Bukkit.createWorld返回null, 耗时" + dt + "ms)");
            return null;
        }

        // ★ 固化安全出生点：中心半径找实体非流体方块；找不到兜底在 (0,24,0) 搭平台（不再"水→删除重建"导致多次 createWorld）
        forceSafeSpawn(pvpWorld);
        // 同步分批预生成出生点周边区块（半径2=5×5=25个，每 tick 一行，均摊不冻服）
        preGenerateSpawnChunksAsync(pvpWorld, 2);
        reapplyWorldRules(pvpWorld);
        // ★ 创建DB会话记录（持久化战绩）
        currentSessionId = db.createArenaSession(pvpWorldName);
        if (currentSessionId > 0) {
            plugin.getLogger().info("[PVP] 创建竞技场会话 #" + currentSessionId + " (world=" + pvpWorldName + ")");
        }

        plugin.getLogger().info("[PVP] PVP竞技场世界就绪 种子=" + seed + " 耗时=" + dt + "ms（NORMAL真实自然地形, 出生点已固化）");
        return pvpWorld;
    }

    /**
     * 固化PVP世界出生点：仅检查 Minecraft 出生点搜索已确定的出生列（位于已生成的 (0,0) 区块内，
     * 不会触发任何额外区块生成），安全则保留天然地形并抬高 1 格；仅当该列不安全时才在出生点
     * 原地搭 3×3 小平台。★ 绝不再做半径 8 的广域扫描（旧实现会触发 289 列同步区块生成，是卡服主因之一）。
     */
    private void forceSafeSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX();
        int z = spawn.getBlockZ();
        int top = getTopSolidY(world, x, z);
        Material m = world.getBlockAt(x, top, z).getType();
        if (m.isSolid() && !isFluid(m)) {
            // 出生列已安全，保留天然地形，仅把出生点设在方块顶部上方 1 格
            world.setSpawnLocation(x, top + 1, z);
            return;
        }
        // 兜底：仅在出生点原地搭 3×3 安全平台（只动这一处，O(1)，零额外区块生成）
        int sy = Math.max(top + 1, 64);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = sy - 3; y <= sy; y++) {
                    Block b = world.getBlockAt(x + dx, y, z + dz);
                    b.setType(y == sy ? Material.GRASS_BLOCK : Material.STONE);
                }
            }
        }
        for (int y = sy + 1; y <= sy + 4; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
        world.setSpawnLocation(x, sy + 1, z);
    }

    /** 是否为实体（固体）方块：玩家可站立的地面。水/空气/岩浆等非固体返回 false */
    private boolean isSolidBlock(Material m) {
        if (m == null || m.isAir() || m == Material.WATER || m == Material.LAVA) return false;
        return m.isSolid() && !m.name().contains("WATER") && !m.name().contains("LAVA");
    }

    /**
     * 同步分批预生成出生点周边区块（radius 个区块半径）。
     *
     * ★ 2026-07-08 修复：原实现用 runTaskAsynchronously + world.loadChunk() 被 Paper AsyncCatcher
     *   全部拦截（chunk 操作必须在主线程），导致 81 个区块全部预生成失败，
     *   玩家进入时实时生成造成 ~16 秒卡顿。
     *   现改为 runTaskTimer 同步分批：每 tick 生成一行（2*radius+1 个 chunk），
     *   共 (2*radius+1) tick 完成，均摊到各 tick 不冻结主线程。
     */
    private void preGenerateSpawnChunksAsync(final World world, final int radius) {
        final int cx = world.getSpawnLocation().getBlockX() >> 4;
        final int cz = world.getSpawnLocation().getBlockZ() >> 4;
        final AtomicInteger row = new AtomicInteger(-radius);

        // 用数组持有任务引用，供 lambda 内部自取消（Bukkit 无 getCurrentTask）
        final BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, (Runnable) () -> {
            int r = row.getAndIncrement();
            if (r > radius) {
                taskHolder[0].cancel();
                return;
            }
            for (int dz = -radius; dz <= radius; dz++) {
                try {
                    world.loadChunk(cx + r, cz + dz, true);
                } catch (Exception e) {
                    // 单个 chunk 失败不阻断整体
                }
            }
        }, 1L, 1L); // 延迟 1tick 启动（给世界加载留缓冲），每 1tick 一行
    }

    // ==================== PVP 战绩榜（记分板侧边栏）====================

    private Scoreboard getOrCreatePVPScoreboard() {
        if (pvpScoreboard == null) {
            pvpScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            // paper-api 1.21.4: registerNewObjective 需要 (name, criteria, displayName) 三参数，setCriteria 已废弃
            Objective obj = pvpScoreboard.registerNewObjective("pvp_stats", Criteria.DUMMY, "§e§lPVP 战绩榜");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        return pvpScoreboard;
    }

    /** 玩家进入PVP世界时登记战绩并显示侧边栏 */
    private void registerPlayerStats(Player p) {
        pvpKills.putIfAbsent(p.getName(), 0);
        pvpDeaths.putIfAbsent(p.getName(), 0);
        p.setScoreboard(getOrCreatePVPScoreboard());
        refreshPVPScoreboard();
    }

    /** 刷新战绩榜：每个在场玩家一行（按击杀数降序，条目前缀显示死亡数） */
    private void refreshPVPScoreboard() {
        if (pvpScoreboard == null) return;
        Objective obj = pvpScoreboard.getObjective("pvp_stats");
        if (obj == null) return;
        // 清除上一次所有条目（避免残留/重复）
        for (String entry : scoreEntries.values()) {
            pvpScoreboard.resetScores(entry);
        }
        scoreEntries.clear();
        // 按击杀数降序排列（手动选择排序，不使用 Comparator/lambda，规避 JDK26 javac 类型推断内部崩溃）
        // ★ 显示所有曾踏入本世界的参与者（即使已离开但世界未销毁，战绩仍保留在死亡榜上）
        List<String> sorted = new ArrayList<String>(pvpKills.keySet());
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                if (pvpKills.getOrDefault(sorted.get(j), 0) > pvpKills.getOrDefault(sorted.get(i), 0)) {
                    String tmp = sorted.get(i);
                    sorted.set(i, sorted.get(j));
                    sorted.set(j, tmp);
                }
            }
        }
        for (String name : sorted) {
            int k = pvpKills.getOrDefault(name, 0);
            int d = pvpDeaths.getOrDefault(name, 0);
            String entry = "§c" + d + "死 §a" + name;
            scoreEntries.put(name, entry);
            obj.getScore(entry).setScore(k);
        }
    }

    /** 玩家离开PVP世界时仅将其个人记分板切回主世界；其战绩(击杀/死亡)保留在死亡榜上，
     *  直到 PVP 世界被真正销毁(deletePVPWorld) 才统一清空。
     *  ★ 修复：之前无论世界是否销毁都会删除该玩家战绩，导致“世界不销毁却清空战机”。 */
    private void cleanupPlayerStats(Player p) {
        if (p == null) return;
        String name = p.getName();
        // 仅将离场玩家个人记分板切回主世界（其战绩行仍保留在PVP记分板供其余玩家查看）
        if (p.isOnline()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        // 世界未被销毁 → 保留所有参与者战绩，仅刷新榜面（含已离开玩家）
        if (pvpScoreboard != null) {
            refreshPVPScoreboard();
        }
    }


    /**
     * 判断出生点是否落在“实体方块”之上（玩家可站立、非水非空气）。
     * ★ 这是插件对 PVP 世界唯一的“干预判定”：只有返回 false 时才由 ensurePVPWorldExists 删除重生。
     */
    private boolean isSpawnOnSolidGround(World world) {
        Location s = world.getSpawnLocation();
        Block under = world.getBlockAt(s.getBlockX(), s.getBlockY() - 1, s.getBlockZ());
        Block feet  = world.getBlockAt(s.getBlockX(), s.getBlockY(),     s.getBlockZ());
        Material u = under.getType();
        Material f = feet.getType();
        boolean solidUnder = !u.isAir() && u != Material.WATER && u != Material.LAVA;
        boolean notInFluid = f != Material.WATER && f != Material.LAVA;
        boolean ok = solidUnder && notInFluid;
        plugin.getLogger().info("[PVP] isSpawnOnSolidGround: 出生点=(" + s.getBlockX() + "," + s.getBlockY() + "," + s.getBlockZ()
                + "), 脚下方块=" + u.name() + ", 脚部方块=" + f.name()
                + " => " + (ok ? "实体地面(放行不重建)" : "非实体(需重建)"));
        return ok;
    }

    private void reapplyWorldRules(World w) {
        w.setPVP(true);
        // ★ 和平模式：排除敌对生物干扰PVP
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setTime(6000);
        // ★ 不常驻出生点区块（与 WorldCreator.keepSpawnInMemory(false) 一致），避免同步加载区块卡主线程
        w.setKeepSpawnInMemory(false);
    }

    /**
     * 在FLAT空世界上手动生成随机PVP竞技场地形
     *
     * 使用多层叠加噪声模拟自然地形：
     * - 基底: y=0~3 (石头+基岩)
     * - 地形层: y=4~terrainHeight (草地方块/沙子/水)
     * - 特征: 随机丘陵、偶尔湖泊、树木散布
     *
     * @param world FLAT空世界
     * @param seed  地形种子
     */
    private void generatePVPTerrain(World world, long seed) {
        Random rng = new Random(seed);
        final int RADIUS = 48; // 生成半径（96x96格）
        final int BASE_Y = 4;   // FLAT石基底顶层
        final int CX = 0, CZ = 0; // 以世界原点为中心

        // ★ 噪声网格：用低频+高频两层叠加产生自然感
        int gridSize = 8;  // 控制点网格间距
        int gridCount = (RADIUS * 2 / gridSize) + 2;
        float[][] heightMap = new float[gridCount][gridCount];

        // 生成控制点高度
        for (int gx = 0; gx < gridCount; gx++) {
            for (int gz = 0; gz < gridCount; gz++) {
                // 双八度噪声: 大尺度起伏(10~28) + 小尺度细节(-3~3)
                float large = 10 + rng.nextFloat() * 18;
                float small = (rng.nextFloat() - 0.5f) * 6;
                heightMap[gx][gz] = large + small;
            }
        }

        // ★ 双线性插值填充每个方块的高度
        for (int x = -RADIUS; x <= RADIUS; x++) {
            for (int z = -RADIUS; z <= RADIUS; z++) {
                // 距离衰减：边缘逐渐降低高度形成自然边界
                double distFromCenter = Math.sqrt(x * x + z * z);
                double edgeFalloff = Math.max(0, 1.0 - distFromCenter / (RADIUS + 5));

                if (edgeFalloff <= 0) continue; // 超出范围不生成

                // 双线性插值
                float gx_f = (x + RADIUS) / (float) gridSize;
                float gz_f = (z + RADIUS) / (float) gridSize;
                int gx0 = Math.min((int) gx_f, gridCount - 2);
                int gz0 = Math.min((int) gz_f, gridCount - 2);
                float fx = gx_f - gx0, fz = gz_f - gz0;

                float h00 = heightMap[gx0][gz0], h01 = heightMap[gx0][gz0 + 1];
                float h10 = heightMap[gx0 + 1][gz0], h11 = heightMap[gx0 + 1][gz0 + 1];
                float height = h00 * (1 - fx) * (1 - fz) + h01 * (1 - fx) * fz
                        + h10 * fx * (1 - fz) + h11 * fx * fz;

                // 边缘衰减应用
                height *= edgeFalloff;

                int terrainHeight = BASE_Y + Math.max(2, (int) height); // 至少2格高

                // 决定表面方块类型
                Material surface = Material.GRASS_BLOCK;
                Material below = Material.DIRT;
                boolean isWater = false;

                // ~8% 概率生成水域（低洼处更容易是水）
                float waterChance = 0.08f + (1.0f - height / 28f) * 0.12f;
                if (rng.nextFloat() < waterChance && height < 14) {
                    isWater = true;
                }

                // ★ 填充柱子：从BASE_Y往上堆叠到地形高度
                for (int y = BASE_Y; y < terrainHeight; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    if (y == terrainHeight - 1) {
                        b.setType(isWater ? Material.WATER : surface);
                    } else if (y >= terrainHeight - 4) {
                        b.setType(isWater ? Material.WATER : below);
                    } else {
                        b.setType(Material.STONE);
                    }
                }

                // 水域填充到基准面
                if (isWater) {
                    for (int y = terrainHeight; y < BASE_Y + 14; y++) {
                        world.getBlockAt(x, y, z).setType(Material.WATER);
                    }
                }
            }
        }

        // ★ 散布一些树（5%概率在每个高地点）
        for (int tx = -RADIUS + 3; tx < RADIUS - 3; tx += rng.nextInt(4) + 3) {
            for (int tz = -RADIUS + 3; tz < RADIUS - 3; tz += rng.nextInt(4) + 3) {
                double d = Math.sqrt(tx * tx + tz * tz);
                if (d > RADIUS - 8 || rng.nextFloat() > 0.055) continue;

                // 找地面高度
                int groundY = -1;
                for (int y = BASE_Y + 30; y >= BASE_Y; y--) {
                    Material m = world.getBlockAt(tx, y, tz).getType();
                    if (!m.isAir() && m != Material.WATER) { groundY = y; break; }
                }
                if (groundY < 0 || groundY < BASE_Y + 5) continue;

                // 生成简易树干+树叶
                Material log = rng.nextBoolean() ? Material.OAK_LOG : Material.BIRCH_LOG;
                Material leaf = rng.nextBoolean() ? Material.OAK_LEAVES : Material.BIRCH_LEAVES;
                int treeH = 4 + rng.nextInt(4);

                for (int ty = 1; ty <= treeH; ty++) {
                    Block b = world.getBlockAt(tx, groundY + ty, tz);
                    if (b.getType().isAir()) b.setType(log);
                }
                // 树冠（球形近似）
                int topY = groundY + treeH;
                for (int lx = -2; lx <= 2; lx++) {
                    for (int lz = -2; lz <= 2; lz++) {
                        for (int ly = -1; ly <= 2; ly++) {
                            if (Math.abs(lx) + Math.abs(lz) + Math.abs(ly) > 4) continue;
                            Block lb = world.getBlockAt(tx + lx, topY + ly, tz + lz);
                            if (lb.getType().isAir()) lb.setType(leaf);
                        }
                    }
                }
            }
        }

        // 强制加载并保存中心区块（确保地形持久化）
        for (int cx = -3; cx <= 3; cx++) {
            for (int cz = -3; cz <= 3; cz++) {
                try {
                    world.loadChunk(cx, cz, true);
                } catch (Exception ignored) {}
            }
        }

        plugin.getLogger().info("[PVP] 手动地形生成完成: 半径=" + RADIUS + " 格, 种子=" + seed);
    }

    /**
     * 递归删除世界目录
     */
    // ==================== 世界目录解析（兼容 1.20.4+ / 26.x 新存储布局） ====================
    //
    // MC 1.20.4 起多世界不再存放在 Bukkit.getWorldContainer()（服务器根目录），
    // 而是存放在 <主世界目录>/dimensions/<命名空间>/<世界名>/（本服即 world/dimensions/minecraft/pvp_arena_xxx）。
    // 旧实现一律 new File(Bukkit.getWorldContainer(), name) → exists() 恒 false →
    // 删除/回收站/残留清理全部静默 no-op，磁盘上因此堆出 20+ 个 pvp_arena_* 残留目录。
    // 以下方法统一按"已加载世界 → 根目录老布局 → 新布局 dimensions/<ns>/<名字>"兜底解析。

    /** 世界根目录候选：服务器根目录 + 其下每个一级子目录（主世界目录） */
    private static File[] worldRootCandidates() {
        List<File> roots = new ArrayList<>();
        File container = Bukkit.getWorldContainer();
        if (container == null) return roots.toArray(new File[0]);
        roots.add(container);
        File[] kids = container.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.isDirectory()) roots.add(k);
            }
        }
        return roots.toArray(new File[0]);
    }

    /**
     * 按世界名解析其磁盘目录。
     * 找不到时返回"老布局"路径（<根目录>/<世界名>），由调用方用 exists() 判断。
     */
    public static File resolveWorldFolder(String worldName) {
        if (worldName == null || worldName.isEmpty()) return null;
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            File f = loaded.getWorldFolder();
            if (f != null && f.exists()) return f;
        }
        File container = Bukkit.getWorldContainer();
        if (container == null) return null;
        File direct = new File(container, worldName);
        if (direct.exists()) return direct;
        for (File root : worldRootCandidates()) {
            File dims = new File(root, "dimensions");
            File[] namespaces = dims.listFiles();
            if (namespaces == null) continue;
            for (File ns : namespaces) {
                if (!ns.isDirectory()) continue;
                File cand = new File(ns, worldName);
                if (cand.exists()) return cand;
            }
        }
        return direct;
    }

    /**
     * 列出所有名字以 prefix 开头的世界目录（新旧布局都扫，按绝对路径去重）。
     * 供清理类逻辑使用——旧实现只扫根目录，新布局下的残留永远扫不到。
     */
    public static List<File> listWorldFolders(String prefix) {
        Map<String, File> found = new LinkedHashMap<>();
        for (File root : worldRootCandidates()) {
            collectByPrefix(root, prefix, found);
            File dims = new File(root, "dimensions");
            File[] namespaces = dims.listFiles();
            if (namespaces == null) continue;
            for (File ns : namespaces) {
                if (ns.isDirectory()) collectByPrefix(ns, prefix, found);
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void collectByPrefix(File dir, String prefix, Map<String, File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith(prefix)) {
                out.put(k.getAbsolutePath(), k);
            }
        }
    }

    /** 统计目录规模：返回 [文件数, 总字节数]（用于删除前后日志对比） */
    private static long[] dirStats(File dir) {
        long[] s = {0L, 0L};
        if (dir == null || !dir.exists()) return s;
        File[] kids = dir.listFiles();
        if (kids == null) return s;
        for (File k : kids) {
            if (k.isDirectory()) {
                long[] r = dirStats(k);
                s[0] += r[0];
                s[1] += r[1];
            } else {
                s[0] += 1L;
                s[1] += k.length();
            }
        }
        return s;
    }

    /** 人类可读大小 */
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return (bytes / (1024.0 * 1024 * 1024)) + "GB";
        if (bytes >= 1024L * 1024L) return (bytes / (1024.0 * 1024)) + "MB";
        if (bytes >= 1024L) return (bytes / 1024.0) + "KB";
        return bytes + "B";
    }

    /**
     * 递归删除目录，失败路径收集进 failed（供入口打印明细）。
     * ★ 旧实现忽略 File.delete() 返回值 → Windows 文件锁下静默失败、
     *   上层却照常打印"删除成功"，导致"日志说删了、世界还躺在磁盘上"。
     * @return true = 已彻底删干净
     */
    private boolean deleteWorldFolder(File folder, List<String> failed) {
        if (folder == null || !folder.exists()) return true;
        boolean ok = true;
        File[] children = folder.listFiles();
        if (children != null) {
            for (File c : children) {
                if (!deleteWorldFolder(c, failed)) ok = false;
            }
        }
        if (!folder.delete() && folder.exists()) {
            ok = false;
            if (failed != null) failed.add(folder.getAbsolutePath());
        }
        return ok && !folder.exists();
    }

    /** 递归删除目录（带失败统计日志），返回是否彻底删干净 */
    private boolean deleteWorldFolder(File folder) {
        List<String> failed = new ArrayList<>();
        boolean ok = deleteWorldFolder(folder, failed);
        if (!ok) {
            plugin.getLogger().warning("[PVP] 递归删除未彻底：失败 " + failed.size()
                    + " 项，首个失败路径=" + (failed.isEmpty() ? "(无)" : failed.get(0)));
        }
        return ok;
    }

    /**
     * 将旧世界目录"重命名"移入回收站，随后【立即尝试把回收站也删掉】，全程打完整日志。
     * ★ 原因：Windows 上 unloadWorld 后 region 文件可能仍被 OS 锁定，
     *   File.delete() 会静默失败导致旧世界残留；File.renameTo() 移动目录在文件锁定时
     *   仍常能成功。移走后 createWorld() 因目录不存在而生成全新 NORMAL 主世界地形。
     * ★ 2026-09-28：旧实现只移进回收站、从不验证磁盘结果，回收站目录要等【重启】才清理
     *   （cleanupOldTrashWorlds 仅在启动时跑）→ 玩家看到"日志说删了，世界还在磁盘上"。
     *   现改为：删除/重命名每步打日志 → 立即删回收站 → 失败自动重试（5s/10s/20s/40s/80s）
     *   → 最后以磁盘 exists() 复核为准打印结论。
     * @return true = 原目录已从磁盘消失
     */
    private boolean moveWorldToTrash(File folder) {
        if (folder == null || !folder.exists()) return true;
        long[] st = dirStats(folder);
        plugin.getLogger().info("[PVP] 删除世界目录: " + folder.getAbsolutePath()
                + "（文件数=" + st[0] + "，大小=" + fmtSize(st[1]) + "）");

        File parent = folder.getParentFile();
        String base = folder.getName();
        long ts = System.currentTimeMillis();
        File trash = new File(parent, base + "_trash_" + ts);
        int i = 0;
        while (trash.exists()) {
            i++;
            trash = new File(parent, base + "_trash_" + ts + "_" + i);
        }

        File target; // 最终还需要从磁盘上弄消失的目录（重命名成功=回收站，失败=原目录）
        if (folder.renameTo(trash)) {
            plugin.getLogger().info("[PVP] 步骤A 重命名进回收站成功(规避Windows删除锁): "
                    + trash.getAbsolutePath());
            target = trash;
            // ★ 立即尝试彻底删除回收站（锁已随卸载释放时可直接删干净）
            List<String> failed = new ArrayList<>();
            if (deleteWorldFolder(trash, failed) && !trash.exists()) {
                plugin.getLogger().info("[PVP] 步骤B ✓ 回收站已立即彻底删除，磁盘无残留: "
                        + trash.getName());
                return !folder.exists();
            }
            plugin.getLogger().info("[PVP] 步骤B 回收站暂未删净（文件仍被占用，失败 "
                    + failed.size() + " 项），已安排自动重试: " + trash.getName());
        } else {
            plugin.getLogger().warning("[PVP] 步骤A 重命名失败(被占用?)，退回直接递归删除: " + base);
            target = folder;
            List<String> failed = new ArrayList<>();
            if (deleteWorldFolder(folder, failed) && !folder.exists()) {
                plugin.getLogger().info("[PVP] 步骤B ✓ 直接递归删除成功，磁盘无残留: " + base);
                return true;
            }
            plugin.getLogger().info("[PVP] 步骤B 直接删除未删净（失败 " + failed.size()
                    + " 项），已安排自动重试: " + base);
        }

        boolean gone = !folder.exists();
        if (gone) {
            plugin.getLogger().info("[PVP] ✓ 原目录已从磁盘移除: " + base);
        } else {
            plugin.getLogger().warning("[PVP] ⚠ 原目录仍在磁盘(等待重试清除): "
                    + folder.getAbsolutePath());
        }
        scheduleDirRemovalRetry(target, 1);
        return gone;
    }

    /**
     * 世界目录删除失败后的自动重试（5s/10s/20s/40s/80s 共5次），每次打印磁盘现状。
     * ★ 供 moveWorldToTrash / deletePVPWorld 在"日志说删了但磁盘还有"时兜底，
     *   确保最终要么删干净、要么留下明确的失败日志（不再出现无凭据的"删除成功"）。
     */
    private void scheduleDirRemovalRetry(final File dir, final int attempt) {
        if (dir == null || attempt > 5) {
            if (dir != null && dir.exists()) {
                plugin.getLogger().warning("[PVP] ⚠ 重试5次后目录仍残留于磁盘（可能被进程锁定）: "
                        + dir.getAbsolutePath());
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!dir.exists()) {
                plugin.getLogger().info("[PVP] 重试删除(" + attempt + "/5): " + dir.getName()
                        + " → 已不存在，✓ 删除成功");
                return;
            }
            long[] st = dirStats(dir);
            plugin.getLogger().info("[PVP] 重试删除(" + attempt + "/5): " + dir.getAbsolutePath()
                    + "（文件数=" + st[0] + "，大小=" + fmtSize(st[1]) + "）");
            List<String> failed = new ArrayList<>();
            deleteWorldFolder(dir, failed);
            if (!dir.exists()) {
                plugin.getLogger().info("[PVP] ✓ 第" + attempt + "次重试删除成功，磁盘无残留: "
                        + dir.getName());
            } else {
                plugin.getLogger().warning("[PVP] 第" + attempt + "次重试后仍残留（失败 "
                        + failed.size() + " 项）: " + dir.getAbsolutePath());
                scheduleDirRemovalRetry(dir, attempt + 1);
            }
        }, 100L * attempt);
    }

    /**
     * 清理历史回收站目录（pvp_arena_trash_*），避免磁盘无限增长。
     * best-effort，失败不阻断主流程。
     */
    private void cleanupOldTrashWorlds() {
        int cleaned = 0, left = 0;
        for (File f : listWorldFolders(PVP_WORLD_NAME + "_")) {
            if (!f.getName().contains("_trash_")) continue;
            long[] st = dirStats(f);
            plugin.getLogger().info("[PVP] 清理回收站目录: " + f.getAbsolutePath()
                    + "（文件数=" + st[0] + "，大小=" + fmtSize(st[1]) + "）");
            deleteWorldFolder(f);
            if (f.exists()) {
                left++;
                plugin.getLogger().warning("[PVP] ⚠ 回收站清理失败(目录仍被占用): "
                        + f.getAbsolutePath() + "，已安排自动重试");
                scheduleDirRemovalRetry(f, 1);
            } else {
                cleaned++;
                plugin.getLogger().info("[PVP] ✓ 回收站已删除: " + f.getName());
            }
        }
        if (cleaned > 0 || left > 0) {
            plugin.getLogger().info("[PVP] 回收站清理汇总: 成功=" + cleaned + "，待重试=" + left);
        }
    }

    /**
     * ★ 启动时清理历史残留的 PVP 世界目录（含新布局 world/dimensions/minecraft/ 下的）。
     * 旧实现只扫服务器根目录，1.20.4+ 新布局根本扫不到 → 磁盘上堆出 20+ 个 pvp_arena_* 目录。
     * 规则：凡是【未被加载】的 pvp_arena* 目录都视为残留 → 移入回收站（Windows 删除锁友好）。
     * world/dimensions/minecraft/ 下的 pvp_arena_* 全部属于一次性随机命名世界，删掉不影响任何在局对局。
     */
    private void cleanupStalePVPArenaWorlds() {
        int n = 0, left = 0;
        for (File f : listWorldFolders(PVP_WORLD_NAME)) {
            String name = f.getName();
            if (name.contains("_trash_")) continue;   // 回收站交给 cleanupOldTrashWorlds()
            if (Bukkit.getWorld(name) != null) continue; // 世界已加载 → 正在使用，跳过
            long[] st = dirStats(f);
            plugin.getLogger().info("[PVP] 清理残留PVP世界目录: " + f.getAbsolutePath()
                    + "（文件数=" + st[0] + "，大小=" + fmtSize(st[1]) + "）");
            // 启动阶段这些世界都未加载（上面已跳过已加载的），直接递归删除即可立即释放磁盘；
            // 若个别文件被占用删不掉，退回重命名移入回收站
            deleteWorldFolder(f);
            if (f.exists()) moveWorldToTrash(f);
            if (f.exists()) {
                left++;
                plugin.getLogger().warning("[PVP] ⚠ 残留目录未能删除，已安排自动重试: "
                        + f.getAbsolutePath());
                scheduleDirRemovalRetry(f, 1);
            }
            n++;
        }
        if (n > 0) {
            plugin.getLogger().info("[PVP] 共清理 " + n + " 个残留PVP世界目录（历史清理失效遗留），"
                    + "其中仍需重试=" + left);
        }
    }

    /**
     * 取消待执行的"空场冷却删除世界"任务（有人重新进入时调用）
     */
    private void cancelPendingDeletion() {
        if (pendingDeleteTask != null) {
            pendingDeleteTask.cancel();
            pendingDeleteTask = null;
        }
    }

    /**
     * 当竞技场世界内已无任何玩家时立即删除世界（"没人了就删除"）。
     * 下次 /pvp join 会随机重新生成地形，破坏/砍树的痕迹随删除完全复原，杜绝背图玩家单方面碾压。
     * ★ 2026-07-08：改为"无人即删"，取消原先的 5 分钟空场冷却（冷却期仍可被复用，与用户要求的
     *   "有玩家就复用、没人就删除"语义不一致）。
     * ★ 2026-09-28 修复"2人全下线却仍提示还有玩家驻场"：
     *   PlayerQuitEvent 处理过程中，world.getPlayers() 里【仍包含正在退出的玩家】，
     *   当场判定必然误报"仍有玩家在场" → 世界永远删不掉、日志天天喊"驻场"。
     *   现改为延后 1 tick（退出玩家已彻底移出世界）再判定，并把"到底还剩谁"打出来。
     */
    private void scheduleWorldDeletionIfEmpty() {
        // 延后一 tick 判定：断线/换世界要等事件处理完，玩家才真正从 world.getPlayers() 消失
        Bukkit.getScheduler().runTask(plugin, this::checkWorldEmptyAndDelete);
    }

    /** 空场判定实体（延后一 tick 执行）：仍有人 → 打印剩余名单并保留世界；真的没人 → 立即删除 */
    private void checkWorldEmptyAndDelete() {
        World w = Bukkit.getWorld(pvpWorldName);
        if (w == null) return;
        List<String> names = new ArrayList<>();
        for (Player p : w.getPlayers()) names.add(p.getName());
        // 仍有玩家滞留于 PVP 世界内 → 保留世界（多人保护，后续进入直接加入战斗）
        if (!names.isEmpty()) {
            plugin.getLogger().info("[PVP] PVP世界仍有玩家在场（" + String.join(", ", names)
                    + "），保留世界（后续进入直接加入战斗）");
            return;
        }
        // ★ 世界已空但登记表还有人 → 说明是断线漏清理的"幽灵在场"，顺手清掉，
        //   否则下次 /pvp join 会误判"已有其他玩家在场"而复用一个空局。
        if (!inPVPArena.isEmpty()) {
            plugin.getLogger().warning("[PVP] ⚠ 世界已空但登记表仍有残留: " + inPVPArena
                    + "，已清理幽灵登记");
            inPVPArena.clear();
        }
        plugin.getLogger().info("[PVP] PVP世界已无玩家，立即删除世界以释放资源（下次进入随机重生）");
        deletePVPWorld(w);
    }

    /**
     * 卸载并删除PVP世界（含磁盘目录），下次进入将随机重新生成地形
     */
    private void deletePVPWorld(World world) {
        deletePVPWorld(world, true);
    }

    /**
     * 卸载并删除PVP世界（含磁盘目录），下次进入将随机重新生成地形。
     * ★ 2026-09-28 全链路日志加固：三步（卸载 → 目录清除 → 磁盘复核）逐步打印，
     *   每步以真实返回值/exists() 为准；卸载失败先清人并延迟重试，绝不再出现
     *   "日志说删除成功、世界却安安稳稳躺在磁盘上"的假成功。
     *
     * @param allowUnloadRetry 卸载失败时是否允许5秒后重试一次（防递归套娃，仅一层）
     */
    private void deletePVPWorld(World world, boolean allowUnloadRetry) {
        if (world == null) return;
        String worldName = world.getName();
        // ★ 世界销毁：清空本局所有玩家战绩(击杀/死亡)与死亡榜（仅此时才清战机）
        pvpKills.clear();
        pvpDeaths.clear();
        scoreEntries.clear();
        pvpScoreboard = null;

        // 兜底：把仍在世界内的玩家送回主世界。
        // ★ 若取到的落点本身还在本世界内（复活点被锁在竞技场），必须强制改用主世界出生点，
        //   否则玩家传了等于没传 → unloadWorld 必失败 → 目录删不掉 → 假"删除成功"。
        World main = Bukkit.getWorlds().get(0);
        for (Player p : new ArrayList<>(world.getPlayers())) {
            Location dest = getBedOrSpawnLocation(p);
            if (dest == null || dest.getWorld() == null || dest.getWorld().equals(world)) {
                dest = main.getSpawnLocation();
            }
            p.teleport(dest);
            p.sendMessage("§e[PVP] 竞技场已关闭，你被传回主世界");
        }

        // ★ 卸载前先取真实世界目录：unload 后 Bukkit.getWorld() 返回 null，
        //   而新布局下根目录根本不存在该文件夹（旧实现取根目录路径 → exists() 恒 false → 从不删除）
        File worldFolder = world.getWorldFolder();
        long[] preStats = (worldFolder != null && worldFolder.exists()) ? dirStats(worldFolder) : null;
        plugin.getLogger().info("[PVP] ==== 开始删除PVP世界 " + worldName
                + " | 目录=" + (worldFolder == null ? "(未知)" : worldFolder.getAbsolutePath())
                + (preStats == null ? "" : " | 文件数=" + preStats[0] + " 大小=" + fmtSize(preStats[1])));

        // 步骤1：卸载世界（★ 旧实现忽略返回值，卸载失败也照样往下"删"）
        boolean unloaded = Bukkit.unloadWorld(world, false); // false=不保存（即将删除，避免残留破坏的地形）
        if (unloaded) {
            plugin.getLogger().info("[PVP] 步骤1/3 卸载世界成功: " + worldName);
        } else {
            List<String> still = new ArrayList<>();
            for (Player q : world.getPlayers()) still.add(q.getName());
            plugin.getLogger().warning("[PVP] 步骤1/3 ⚠ 卸载世界失败: " + worldName
                    + "，仍在场玩家=" + still + " → 暂停删除（目录属于运行中的世界，强删会出脏数据）");
            if (allowUnloadRetry) {
                // 5秒后复核：世界空了就重试完整删除；已卸载就直接清目录；还有人则放弃等下次空场
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    World again = Bukkit.getWorld(worldName);
                    if (again == null) {
                        plugin.getLogger().info("[PVP] 延迟复核: 世界 " + worldName + " 已卸载，继续清理其目录");
                        File f = resolveWorldFolder(worldName);
                        if (f != null && f.exists()) moveWorldToTrash(f);
                    } else if (again.getPlayers().isEmpty()) {
                        plugin.getLogger().info("[PVP] 延迟复核: 世界 " + worldName + " 已无人，重试删除");
                        deletePVPWorld(again, false);
                    } else {
                        plugin.getLogger().warning("[PVP] 延迟复核: 世界 " + worldName
                                + " 仍有玩家 " + again.getPlayers().size() + " 人，放弃删除（下次空场时再删）");
                    }
                }, 100L);
            }
            resetMatchLock();
            return;
        }

        // 步骤2：定位并清除目录（unload 后 getWorldFolder 可能失效 → resolveWorldFolder 兜底）
        if (worldFolder == null || !worldFolder.exists()) {
            File resolved = resolveWorldFolder(worldName);
            plugin.getLogger().info("[PVP] 步骤2/3 getWorldFolder 不可用，改用 resolveWorldFolder 解析: "
                    + (resolved == null ? "(null)" : resolved.getAbsolutePath()));
            worldFolder = resolved;
        }
        if (worldFolder == null || !worldFolder.exists()) {
            plugin.getLogger().info("[PVP] 步骤2/3 目标目录不存在，无需删除: " + worldName);
        } else {
            moveWorldToTrash(worldFolder); // 内部含：统计 → 重命名/删除 → 回收站即删 → 失败自动重试
        }

        // ★ 世界销毁 → 重置本局公平锁定，下一局首位玩家重新定死附魔
        resetMatchLock();

        // 步骤3：磁盘复核——结论以 exists() 为唯一依据，不再无凭据打印"已删除"
        boolean stillThere = worldFolder != null && worldFolder.exists();
        if (!stillThere) {
            plugin.getLogger().info("[PVP] 步骤3/3 ✓ 磁盘复核通过：PVP世界目录已彻底删除（"
                    + worldName + "），下次进入将随机重新生成地形");
        } else {
            plugin.getLogger().warning("[PVP] 步骤3/3 ⚠ 磁盘复核未通过：目录仍在磁盘上！"
                    + worldFolder.getAbsolutePath() + "（已安排自动重试删除，删不掉会持续告警）");
        }
    }

    /**
     * 在创建全新 PVP 世界前，彻底删除磁盘上可能残留的旧世界目录。
     * ★ 必要性：旧版本曾对出生点执行 setSpawnLocation 并对周边强制造陆（小岛），
     *   若上次删除因 Windows 文件锁而失败，createWorld() 会误加载到该旧世界
     *   （出生点被锁定为 0,0、地形被切成小岛）。这里用重命名+重试确保目录真正消失，
     *   保证每次进入都是 MC 自然生成的全新主世界，自然出生点完全由 MC 决定。
     */
    /**
     * best-effort 清理指定名字的世界目录：先移入回收站（绕开 Windows 删除锁），失败不抛异常。
     * ★ 即便清理失败（如文件锁），调用方也已改用全新随机世界名，不影响本次生成。
     */
    private void deleteWorldFolderIfExists(String name) {
        if (name == null) return;
        File folder = resolveWorldFolder(name);
        if (folder == null || !folder.exists()) {
            plugin.getLogger().info("[PVP] best-effort 清理上一次世界目录: " + name
                    + " → 目录不存在（" + (folder == null ? "无法解析路径" : folder.getAbsolutePath()) + "），跳过");
            return;
        }
        long[] st = dirStats(folder);
        plugin.getLogger().info("[PVP] best-effort 清理上一次世界目录: " + folder.getAbsolutePath()
                + "（文件数=" + st[0] + "，大小=" + fmtSize(st[1]) + "）");
        moveWorldToTrash(folder);
        if (folder.exists()) {
            plugin.getLogger().warning("[PVP] ⚠ 上一次世界目录仍残留（已安排自动重试）: "
                    + folder.getAbsolutePath());
        } else {
            plugin.getLogger().info("[PVP] ✓ 上一次世界目录已清除: " + name);
        }
    }

    /**
     * 异步非阻塞预生成出生点周边区块（半径 radius 个区块）。
     * ★ 关键修复（2026-07-08）：原实现在主线程同步 loadChunk 81 个区块，
     *   导致单次创建世界总阻塞主线程 >10s，触发 Paper Watchdog 超时（"The server has not responded for 10 seconds"）。
     *   改为通过 Paper 的 getChunkAtAsync 全部异步派发，主线程瞬间返回，区块在后台线程生成，
     *   既不卡死服务器也保留了"出生点周边地形就绪"的意图。
     *   出生点所在区块已由 Bukkit.createWorld 加载（即 createWorld 耗时约 8s 的由来），
     *   因此 joinArena 的出生点脚下检查不会触发额外同步加载。
     */
    private void preGenerateSpawnChunks(World world, int radius) {
        int cx = world.getSpawnLocation().getBlockX() >> 4;
        int cz = world.getSpawnLocation().getBlockZ() >> 4;
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int x = cx + dx, z = cz + dz;
                try {
                    // 异步非阻塞生成：true=强制生成；回调仅用于异常兜底，绝不阻塞主线程
                    world.getChunkAtAsync(x, z, true, chunk -> {});
                    n++;
                } catch (Exception e) {
                    plugin.getLogger().warning("[PVP] 触发异步生成区块失败: " + x + "," + z);
                }
            }
        }
        plugin.getLogger().info("[PVP] 已派发 " + n + " 个区块异步预生成（后台进行，不阻塞主线程，避免 Watchdog 超时）");
    }



    /**
     * 判断方块是否为流体（水/熔岩），流体上不适合作为安全出生地面
     */
    private boolean isFluid(Material type) {
        return type == Material.WATER || type == Material.LAVA;
    }


    /**
     * 获取指定列从顶部向下的第一个非空气、非流体方块 Y 坐标（用于确定出生点地形高度）
     */
    private int getTopSolidY(World world, int x, int z) {
        for (int y = 255; y >= 1; y--) {
            Material t = world.getBlockAt(x, y, z).getType();
            if (!t.isAir() && t != Material.WATER && t != Material.LAVA) return y;
        }
        return 64;
    }


    // ==================== GUI关闭事件（防止ESC跳过装备选择）====================

    /**
     * ★ 关键安全补丁：装备选择GUI关闭事件
     *
     * 漏洞：玩家打开装备GUI后按ESC关闭 → 跳过confirmEquipment() →
     *       原背包未被备份/清空 → 玩家带着自己的神装进PVP战斗
     *
     * 修复：检测到装备GUI被关闭时，
     *   - 若玩家已确认过装备 → 正常（可能是其他背包操作）
     *   - 若未确认 → 立即遣返回主世界 + 清理状态（不给作弊机会）
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();

        if (!EQUIPMENT_GUI_TITLE.equals(title)) return;
        if (!inPVPArena.contains(player.getName())) return;

        // 程序内重开装备GUI（切换档位）导致的旧GUI关闭，忽略，不遣返
        if (guiReopening.remove(player.getName())) return;

        // ★ 宽限期：GUI 刚打开后短时间内（世界加载/过渡导致的瞬时关闭）视为误触，
        //   忽略遣返，并在玩家当前未打开任何 GUI 时延迟自动重开，让其能正常选装备。
        Long openedAt = guiOpenedMillis.get(player.getName());
        if (openedAt != null
                && System.currentTimeMillis() - openedAt < EQUIPMENT_SELECT_GRACE_MILLIS) {
            plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                    + " 装备GUI在宽限期内关闭，疑似过渡误触，延迟自动重开");
            int cnt = graceReopenCount.getOrDefault(player.getName(), 0);
            if (cnt < 4) {
                graceReopenCount.put(player.getName(), cnt + 1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (inPVPArena.contains(player.getName())
                            && !equipmentConfirmed.contains(player.getName())
                            && player.isOnline()
                            && (player.getOpenInventory() == null
                                || player.getOpenInventory().getType()
                                    == org.bukkit.event.inventory.InventoryType.CRAFTING)) {
                        openEquipmentSelection(player);
                    }
                }, 5L);
            }
            return;
        }

        // ★★★ 核心改进：基于确定性状态判断，而非猜测关闭原因 ★★★
        //
        // 方案：检查玩家是否已在装备GUI中操作过（equipInteracted 有值）
        //   - 已操作过 → 玩家在浏览/切换，自动重开 GUI（不遣返）
        //   - 从未操作 → 玩家直接跳过了（ESC/未交互），执行遣返
        //
        // 同时配合超时安全网（onPlayerEnterPVPWorld 中注册60秒定时器），
        // 防止玩家一直开着 GUI 不操作也不退出

        // ★ 关键修复：确认过装备（equipmentConfirmed 有值）也绝不能遣返。
        if (equipmentConfirmed.contains(player.getName())) {
            plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                    + " 已确认装备但GUI关闭，忽略（不遣返）");
            return;
        }

        // 玩家已在GUI中操作过（点过附魔/盾牌/食物/确认）→ 自动重开GUI让其继续或点确认
        if (equipInteracted.contains(player.getName())) {
            plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                    + " 已操作装备GUI但未确认就关了，自动重开");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (inPVPArena.contains(player.getName())
                        && !equipmentConfirmed.contains(player.getName())
                        && player.isOnline()) {
                    openEquipmentSelection(player);
                    player.sendMessage("§e[PVP] §7请点击 §a确认按钮 §7完成装备选择");
                }
            }, 5L); // 5 tick (~250ms) 延迟确保关闭事件完成
            return;
        }

        // 从未选择任何档位 → 视为跳过/ESC，遣返
        plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                + " 未选择任何装备就关闭了PVP装备GUI，执行遣返");

        // 还原原背包（进入时已清空）
        restoreInventory(player);

        // 清理状态并取消超时定时器
        inPVPArena.remove(player.getName());
        cancelKickTimeout(player.getName());

        // ★ 关键修复：遣返前必须清除PVP战绩榜，否则玩家回到主世界
        //   仍携带榜单（onPlayerChangedWorld 触发 forceLeaveArena 时会因
        //   inPVPArena 已移除而提前 return，导致 cleanupPlayerStats 不被调用）。
        cleanupPlayerStats(player);

        // 延迟一帧传回主世界（避免与关闭事件冲突）
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(getBedOrSpawnLocation(player));
                player.sendMessage("§c§l[PVP] §c你未确认装备选择，已被遣返回主世界");
                player.sendMessage("§7提示：进入PVP后必须选择并确认装备才能开始战斗");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            }
        });
    }

    // ==================== 进入流程 ====================

    /**
     * 玩家进入PVP世界（由 PlayerChangedWorldEvent 触发 或 /pvp join 命令调用）
     *
     * 新流程：
     * 1. 标记进入状态
     * 2. 先打开装备选择GUI（此时玩家仍持有原背包！）
     * 3. 待玩家确认后才备份+清空+发装备
     */
    public void onPlayerEnterPVPWorld(Player player) {
        String playerName = player.getName();

        if (inPVPArena.contains(playerName)) {
            return; // 已经在流程中，防止重复触发
        }

        // 标记进入PVP世界
        inPVPArena.add(playerName);
        registerPlayerStats(player);
        equipmentConfirmed.remove(playerName);

        // ★ 进入竞技场立即备份并清空原背包，防止玩家用自带装备战斗
        backupInventory(player);
        clearInventory(player);

        // 打开装备选择GUI
        openEquipmentSelection(player);

        // ★ 注册超时安全网：60秒内必须确认，否则自动遣返
        scheduleKickTimeout(playerName);

        player.sendMessage("§a§l欢迎来到PVP竞技场!");
        player.sendMessage("§7请先选择你的装备套装，确认后将发放PVP装备");
        player.sendMessage("§e§l★ 选装期间免疫一切伤害，请放心选择");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 进入PVP竞技场（已备份清空原背包，等待选装备）");
    }

    /**
     * 执行"确认装备"步骤：发放PVP装备
     * 由装备GUI的确认按钮和一键装备按钮触发
     */
    public void confirmEquipment(Player player) {
        String playerName = player.getName();
        if (!inPVPArena.contains(playerName)) return;
        if (equipmentConfirmed.contains(playerName)) return;

        // 此时背包已在进入时清空，直接发放PVP装备
        equipFullSet(player);

        // 标记已完成装备确认 + 取消超时定时器
        equipmentConfirmed.add(playerName);
        cancelKickTimeout(playerName);

        // ★ 永久锁定：记录确认状态和选装结果，退出再进入也不能重新选装
        permanentlyConfirmed.add(playerName);
        savedSelections.put(playerName, new SavedSelection(
                selWeaponTier.getOrDefault(playerName, 0),
                selArmorTier.getOrDefault(playerName, 0),
                selRangedWeapon.getOrDefault(playerName, 0),
                selEnchant.getOrDefault(playerName, false),
                selShield.getOrDefault(playerName, true),
                selFood.getOrDefault(playerName, FOOD_DEFAULT)
        ));

        player.sendMessage("§a§l装备已就绪，开始战斗!");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 装备确认完毕");
    }

    // ==================== 退出流程（统一出口）====================

    /**
     * 强制离开竞技场 — 所有退场的唯一入口
     *
     * 无论通过什么方式离开（命令、传送、死亡、掉线），都调用此方法：
     * 1. 回收所有PVP专属装备
     * 2. 还原入场时的背包数据
     * 3. 清理状态标记

    // ==================== 超时安全网 ====================

    /**
     * 注册装备选择超时定时器：玩家进入PVP后N秒未确认则自动遣返
     */
    private void scheduleKickTimeout(String playerName) {
        cancelKickTimeout(playerName); // 先取消已有的（防止重复注册）
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (inPVPArena.contains(playerName) && !equipmentConfirmed.contains(playerName)) {
                Player p = Bukkit.getPlayerExact(playerName);
                plugin.getLogger().info("[PVP] 超时遣返: " + playerName
                        + " 进入PVP超过 " + EQUIPMENT_SELECT_TIMEOUT_SECONDS + "秒未确认装备");
                kickTimeoutTasks.remove(playerName);
                // 超时未确认：还原原背包并送走
                if (p != null && p.isOnline()) {
                    restoreInventory(p);
                }
                inPVPArena.remove(playerName);
                cleanupPlayerStats(p);
                guiReopening.remove(playerName);
                guiOpenedMillis.remove(playerName);
                graceReopenCount.remove(playerName);
                selEnchant.remove(playerName);
                selShield.remove(playerName);
                selFood.remove(playerName);
                selWeaponTier.remove(playerName);
                selRangedWeapon.remove(playerName);
                selArmorTier.remove(playerName);
                equipInteracted.remove(playerName);
                if (p != null && p.isOnline()) {
                    p.closeInventory();
                    p.teleport(getBedOrSpawnLocation(p));
                    p.sendMessage("§c§l[PVP] §c装备选择超时（" + EQUIPMENT_SELECT_TIMEOUT_SECONDS + "秒），已被自动遣返");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                }
            }
        }, EQUIPMENT_SELECT_TIMEOUT_SECONDS * 20L); // convert seconds to ticks
        kickTimeoutTasks.put(playerName, task);
    }

    /**
     * 取消玩家的装备选择超时定时器（确认/正常退出时调用）
     */
    private void cancelKickTimeout(String playerName) {
        BukkitTask task = kickTimeoutTasks.remove(playerName);
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    /**
     * 强制离开竞技场 — 所有退场的唯一入口
     *
     * @param player       玩家对象（可能在线也可能离线——离线时只清理状态+存DB标记）
     * @param isDisconnect 是否为掉线场景（不掉线时直接还原；掉线时仅存DB等待下次登录还原）
     */
    public void forceLeaveArena(Player player, boolean isDisconnect) {
        String playerName = player.getName();

        if (!inPVPArena.contains(playerName)) return;

        // 回收PVP装备
        if (!isDisconnect && player.isOnline()) {
            回收PVPEquipment(player);
        }

        // 还原背包（在线且非断开连接时立即还原）
        if (!isDisconnect && player.isOnline()) {
            restoreInventory(player);
            player.sendMessage("§e§l你已离开PVP竞技场，背包已恢复");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        } else if (isDisconnect) {
            // 断线时确保备份已在DB中（下次onPlayerJoin时还原）
            plugin.getLogger().info("[PVP] 玩家 " + playerName + " 在PVP中断线，备份已存DB待还原");
        }

        // ★ 还原观赛前的游戏模式（避免把观察者模式一路带到主世界）
        GameMode backMode = spectatorBackups.remove(playerName);
        if (backMode != null && !isDisconnect && player.isOnline()
                && player.getGameMode() == GameMode.SPECTATOR) {
            // ★ 兜底：还原目标为观察者或创造时一律回生存（退出观赛绝不回创造，防止作弊）
            GameMode restore = (backMode == GameMode.SPECTATOR
                    || backMode == GameMode.CREATIVE) ? GameMode.SURVIVAL : backMode;
            player.setGameMode(restore);
        }

        // 清理所有状态（含取消超时定时器）
        inPVPArena.remove(playerName);
        equipmentConfirmed.remove(playerName);
        inventoryBackups.remove(playerName);
        guiReopening.remove(playerName);
        guiOpenedMillis.remove(playerName);
        graceReopenCount.remove(playerName);
        selEnchant.remove(playerName);
        selShield.remove(playerName);
        selFood.remove(playerName);
        selWeaponTier.remove(playerName);
        selArmorTier.remove(playerName);
        equipInteracted.remove(playerName);
        cancelKickTimeout(playerName);
        cleanupPlayerStats(player);

        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 离开PVP竞技场"
                + (isDisconnect ? "（断线）" : ""));

        // 注：世界"无人即删"的判定已统一移至 onPlayerChangedWorld / onPlayerQuit 中、
        //     在玩家【实际离开世界后】再调用 scheduleWorldDeletionIfEmpty()，
        //     避免死亡/传送尚未完成时就误判"仍在场"而漏删。
    }

    /**
     * 玩家退出PVP世界（由 PlayerChangedWorldEvent 触发）
     */
    public void onPlayerExitPVPWorld(Player player) {
        // 强制离开（内部已统一触发空场冷却删除逻辑）
        forceLeaveArena(player, false);
    }

    /**
     * 玩家切换世界事件 — 进入/离开PVP世界时触发装备选择GUI与背包还原
     */
    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String from = event.getFrom().getName();
        String to = player.getWorld().getName();

        if (to.equals(pvpWorldName) && !from.equals(pvpWorldName)) {
            // 进入PVP世界 → 打开装备选择GUI（此时玩家仍持有原背包）
            onPlayerEnterPVPWorld(player);
        } else if (from.equals(pvpWorldName) && !to.equals(pvpWorldName)) {
            // 离开PVP世界 → 回收装备并还原背包
            onPlayerExitPVPWorld(player);
            // ★ 玩家已实际离开 PVP 世界，检查世界是否空了 → 空了立即删除（"没人了就删除"）
            scheduleWorldDeletionIfEmpty();
        }
    }

    // ==================== 事件处理器 ====================

    /**
     * PVP火球 — 右键火焰弹发射SmallFireball，造成伤害
     */
    @EventHandler
    public void onFireBallUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!inPVPArena.contains(player.getName()) || !equipmentConfirmed.contains(player.getName())) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.FIRE_CHARGE) return;

        event.setCancelled(true);

        // 消耗1个火焰弹
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItemInMainHand(null);
        }

        // 发射火焰弹（落点爆炸 + 点燃方块）
        Vector dir = player.getLocation().getDirection().normalize().multiply(2.0);
        SmallFireball fb = player.launchProjectile(SmallFireball.class, dir);
        fb.setYield(1.5f);       // 爆炸威力约1.5格半径
        fb.setIsIncendiary(true); // 点燃爆炸范围内方块
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.5f, 1.2f);
    }

    /**
     * 搭桥蛋 — 右键扔出蛋后，每隔1tick在蛋当前位置放置方块，形成桥梁
     */
    @EventHandler
    public void onBridgeEggLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Egg egg)) return;
        if (!(egg.getShooter() instanceof Player player)) return;
        if (!inPVPArena.contains(player.getName())) return;

        int eid = egg.getEntityId();
        int taskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (egg.isDead() || !egg.isValid()) {
                Integer tid = bridgeEggTrackers.remove(eid);
                if (tid != null) Bukkit.getScheduler().cancelTask(tid);
                bridgeEggSkipTicks.remove(eid);
                bridgeLastLoc.remove(eid);
                return;
            }
            int skip = bridgeEggSkipTicks.merge(eid, 1, Integer::sum);
            // 前2tick只记录位置不放置（蛋在玩家体内/附近），保证 skip=3 起桥位已就绪
            if (skip <= 2) {
                bridgeLastLoc.put(eid, egg.getLocation().clone());
                return;
            }
            // 方块放在蛋的上一tick位置（蛋已离开，不会撞自己放的桥）
            Location prevLoc = bridgeLastLoc.get(eid);
            if (prevLoc != null) {
                Block b = prevLoc.getBlock();
                if (b.getType().isAir()) {
                    b.setType(BRIDGE_BLOCK);
                }
            }
            // 记录当前位置供下一tick使用
            bridgeLastLoc.put(eid, egg.getLocation().clone());
        }, 1L, 1L).getTaskId();

        bridgeEggTrackers.put(eid, taskId);
    }

    /**
     * 搭桥蛋命中 — 停止方块追踪
     */
    @EventHandler
    public void onBridgeEggHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Egg)) return;
        int eid = event.getEntity().getEntityId();
        Integer taskId = bridgeEggTrackers.remove(eid);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        bridgeEggSkipTicks.remove(eid);
        bridgeLastLoc.remove(eid);
    }

    /**
     * ★ 选装前无敌：玩家在PVP世界中未完成装备选择时，免疫一切伤害
     */
    @EventHandler
    public void onEntityDamage(org.bukkit.event.entity.EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        String name = player.getName();
        // 在PVP世界中但未确认装备 → 取消所有伤害
        if (inPVPArena.contains(name) && !equipmentConfirmed.contains(name)) {
            event.setCancelled(true);
        }
    }

    /**
     * 玩家死亡事件 — 死亡后强制离开PVP
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!inPVPArena.contains(player.getName())) return;
        // paper-api 1.21.4: getKiller() 已移除，改用 DamageSource.getCausingEntity()
        org.bukkit.entity.Entity killer = event.getDamageSource().getCausingEntity();
        // ★ 死亡状态留痕（2026-09-28）：把双方"是不是观赛者"直接打进日志。
        //   下次再出现"观赛者被人击杀了"的反馈，日志一句话给出答案：
        //   受害者当时的模式 / 是否登记观赛身份 / 保护判定，不用再靠回忆对时间线。
        Player killerPlayer = killer != null ? resolveDamageActor(killer) : null;
        plugin.getLogger().info("[PVP] 竞技场死亡状态：受害者 " + describePlayerState(player)
                + "，观赛保护=" + (isProtectedSpectator(player) ? "应拦截却死亡⚠(严重异常)" : "不保护(正常可死)")
                + "；来源=" + (killerPlayer != null ? describePlayerState(killerPlayer)
                : (killer != null ? killer.getType().name() : "环境/其他"))
                + (killerPlayer != null ? "，观赛保护=" + (isProtectedSpectator(killerPlayer)
                ? "应拦截却出手⚠(严重异常)" : "不保护(正常可出手)") : ""));
        if (killer instanceof Player && inPVPArena.contains(killer.getName())) {
            String kName = killer.getName();
            String vName = player.getName();
            // 总榜击杀数
            pvpKills.put(kName, pvpKills.getOrDefault(kName, 0) + 1);

            // ★ 持久化到DB
            if (currentSessionId > 0) {
                db.recordArenaKill(currentSessionId, ((Player) killer).getUniqueId().toString(), kName);
                db.recordArenaDeath(currentSessionId, player.getUniqueId().toString(), vName);
            }

            // ★ 击杀奖励：5杀一个轮回
            //   2杀：16熟食补血 + 1火焰弹
            //   5杀：16箭 + 1搭桥蛋 + 1火焰弹 + 替换全身装备(头盔胸甲裤子靴子)
            int newKills = pvpKills.get(kName);
            int cyclePos = newKills % 5;

            if (cyclePos == 2 && newKills > 0) {
                // ▼ 2杀奖励 ▼
                Player killerP = (Player) killer;
                // 16熟食
                ItemStack food = new ItemStack(Material.COOKED_BEEF, 16);
                ItemMeta foodMeta = food.getItemMeta();
                if (foodMeta != null) {
                    foodMeta.setDisplayName("§c§lPVP熟食补血");
                    food.setItemMeta(foodMeta);
                }
                Map<Integer, ItemStack> leftover = killerP.getInventory().addItem(food);
                int givenFood = 16;
                for (ItemStack rem : leftover.values()) givenFood -= rem.getAmount();

                // 1火焰弹
                ItemStack fc = new ItemStack(Material.FIRE_CHARGE, 1);
                ItemMeta fcMeta = fc.getItemMeta();
                if (fcMeta != null) {
                    fcMeta.setDisplayName(FIRE_CHARGE_NAME);
                    fc.setItemMeta(fcMeta);
                }
                killerP.getInventory().addItem(fc);

                if (givenFood > 0) {
                    killerP.sendMessage("§a§l[PVP] §f击杀达 §e2 §f次！获得 §6" + givenFood + " 个熟食§7(补血) §c+ 火焰弹§7×1");
                    killerP.playSound(killerP.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                }
            }

            if (cyclePos == 0 && newKills > 0) {
                // ▼ 5杀奖励 ▼
                Player killerP = (Player) killer;

                // 16箭
                ItemStack arrows = new ItemStack(Material.ARROW, 16);
                ItemMeta aMeta = arrows.getItemMeta();
                if (aMeta != null) {
                    aMeta.setDisplayName("§b§lPVP箭矢");
                    arrows.setItemMeta(aMeta);
                }
                killerP.getInventory().addItem(arrows);

                // 1搭桥蛋
                ItemStack bridgeEgg = new ItemStack(Material.EGG, 1);
                ItemMeta eMeta = bridgeEgg.getItemMeta();
                if (eMeta != null) {
                    eMeta.setDisplayName(BRIDGE_EGG_NAME);
                    eMeta.setLore(Arrays.asList("§7右键发射，沿抛物线生成放置方块"));
                    bridgeEgg.setItemMeta(eMeta);
                }
                killerP.getInventory().addItem(bridgeEgg);

                // 1火焰弹
                ItemStack fc5 = new ItemStack(Material.FIRE_CHARGE, 1);
                ItemMeta fc5Meta = fc5.getItemMeta();
                if (fc5Meta != null) {
                    fc5Meta.setDisplayName(FIRE_CHARGE_NAME);
                    fc5.setItemMeta(fc5Meta);
                }
                killerP.getInventory().addItem(fc5);

                // 替换全身装备（用玩家当前选中的护甲档位，无选中默认铁）
                int tier = selArmorTier.getOrDefault(killerP.getName(), 0);
                Material[] armorSet = ARMOR_SETS[tier];
                killerP.getInventory().setHelmet(new ItemStack(armorSet[0]));
                killerP.getInventory().setChestplate(new ItemStack(armorSet[1]));
                killerP.getInventory().setLeggings(new ItemStack(armorSet[2]));
                killerP.getInventory().setBoots(new ItemStack(armorSet[3]));

                killerP.sendMessage("§a§l[PVP] §f击杀达 §e5 §f次！获得 §b箭矢×16 §a+ 搭桥蛋 §c+ 火焰弹 §f+ 全新" + ARMOR_TIER_NAMES[tier] + "护甲");
                killerP.playSound(killerP.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }

            // 击杀者连杀会话
            PVPManager.KillSession ks = arenaKillSessions.get(kName);
            if (ks == null
                    || System.currentTimeMillis() - ks.lastKill
                    > 5 * 60 * 1000) {
                arenaTripleAnnounced.remove(kName);
                PVPManager.KillSession ns =
                        new PVPManager.KillSession();
                ns.region = ARENA_LABEL;
                ns.count = 1;
                ns.firstKill = System.currentTimeMillis();
                ns.lastKill = System.currentTimeMillis();
                arenaKillSessions.put(kName, ns);
            } else {
                ks.count++;
                ks.lastKill = System.currentTimeMillis();
            }

            // 终结检测：受害者连杀 >= 3 被终结
            PVPManager.KillSession victimSession =
                    arenaKillSessions.get(vName);
            if (victimSession != null
                    && victimSession.count >= 3) {
                String endMsg = "§b§l" + kName + " §e终结了 "
                        + vName + " §e的 " + victimSession.count
                        + "连杀！§a恭喜终结者 " + kName
                        + "§e！§7快来挑战！";
                arenaKillSessions.remove(vName);
                broadcastWithSound(endMsg,
                        org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL);
            }
            // 无论如何，死者连杀清零
            arenaKillSessions.remove(vName);

            // 多杀通报（全服播报）
            PVPManager.KillSession killerSession =
                    arenaKillSessions.get(kName);
            if (killerSession != null) {
                checkArenaMultiKill((Player) killer,
                        killerSession);
            }

            ((Player) killer).sendMessage("\u00a7a\u00a7l击杀 " + vName + "\u00a7a 当前击杀: " + pvpKills.get(kName));
        }
        pvpDeaths.put(player.getName(), pvpDeaths.getOrDefault(player.getName(), 0) + 1);
        // ★ 非击杀死亡也持久化（如摔死/溺水）
        if (currentSessionId > 0) {
            db.recordArenaDeath(currentSessionId, player.getUniqueId().toString(), player.getName());
        }
        refreshPVPScoreboard();

        // 死亡时保留经验（避免丢失）
        event.setKeepLevel(true);
        event.setKeepInventory(true); // 不掉落PVP装备（因为要回收）
        // 复活处理统一交给 onPlayerRespawn，避免1秒空窗期被利用
    }

    /**
     * 玩家复活事件 — 在PVP竞技场阵亡后立即还原背包并送回主世界
     */
    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!inPVPArena.contains(player.getName())) return;

        // ★ 优先床/重生点，兜底主世界出生点
        Location bed = player.getRespawnLocation();
        if (bed != null && !bed.getWorld().getName().equals(pvpWorldName)) {
            event.setRespawnLocation(bed);
        } else {
            event.setRespawnLocation(Bukkit.getWorlds().get(0).getSpawnLocation());
        }

        if (!equipmentConfirmed.contains(player.getName())) {
            // 未确认装备：身上仍是原背包，只需清状态
            cleanupPlayerStats(player);
            inPVPArena.remove(player.getName());
            equipmentConfirmed.remove(player.getName());
            guiReopening.remove(player.getName());
            guiOpenedMillis.remove(player.getName());
            graceReopenCount.remove(player.getName());
            selEnchant.remove(player.getName());
            selShield.remove(player.getName());
            selFood.remove(player.getName());
            selWeaponTier.remove(player.getName());
            selArmorTier.remove(player.getName());
            equipInteracted.remove(player.getName());
            cancelKickTimeout(player.getName());
            player.sendMessage("§c§l你在PVP中阵亡，已自动退出");
            return;
        }

        // 已确认装备：回收PVP装备并还原原背包
        forceLeaveArena(player, false);
        player.sendMessage("§c§l你在PVP中阵亡，已自动退出并还原背包");
    }

    // ===== 全服击杀播报（移植自 PVPManager） =====
    private void broadcastWithSound(String msg,
                                    org.bukkit.Sound sound) {
        Bukkit.broadcastMessage(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound,
                    1.0f, 1.0f);
        }
    }

    private void checkArenaMultiKill(Player killer,
                                     PVPManager.KillSession session) {
        int count = session.count;
        String kName = killer.getName();
        String msg = null;
        if (count >= 5) {
            msg = "§c§l" + kName + " §e在「" + ARENA_LABEL
                    + "」达成 " + count + "连杀！§7§l快来挑战！";
        } else if (count == 3) {
            if (arenaTripleAnnounced.add(kName)) {
                msg = "§6§l三杀！" + kName
                        + " 势不可挡！§7快来挑战！";
            }
        }
        if (msg != null) {
            if (count >= 5) {
                broadcastWithSound(msg,
                        org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL);
            } else {
                broadcastWithSound(msg,
                        org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN);
            }
        }
    }

    /**
     * 玩家加入事件 — 如果上次在PVP中断线，还原背包并送回主世界
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 检查是否有待还原的PVP背包备份
        String[] data = db.getPvpInventoryBackup(player.getName());
        if (data != null && !data[0].isEmpty()) {
            plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                    + " 上次在PVP中断线，正在还原背包...");
            // 延迟2 tick 让玩家完全加载
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                InventoryBackup backup = new InventoryBackup(
                        deserializeItems(data[0]),
                        deserializeItems(data[1]),
                        deserializeItem(data[2]),
                        Integer.parseInt(data[3]),
                        Float.parseFloat(data[4])
                );
                inventoryBackups.put(player.getName(), backup);
                restoreInventory(player);
                db.deletePvpInventoryBackup(player.getName());
                player.sendMessage("§a§l检测到你上次在PVP中断线，背包已自动恢复");

                // ★ 上次以观赛观察者身份断线 → 重连强制恢复为生存（避免在主世界仍是观察者）
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    // ★ 先注销观赛身份再改模式：否则留着身份残留，会被战斗拦截/强制巡视误伤
                    spectatorBackups.remove(player.getName());
                    player.setGameMode(GameMode.SURVIVAL);
                    player.sendMessage("§a§l[PVP观赛] §f已退出观赛观察者模式");
                }

                // 若仍在竞技场世界，送回主世界
                if (player.getWorld().getName().equals(pvpWorldName)) {
                    player.teleport(getBedOrSpawnLocation(player));
                    cleanupPlayerStats(player);
                    inPVPArena.remove(player.getName());
                    player.sendMessage("§e[PVP] 已送你回主世界");
                }
            }, 2L);
            return;
        }

        // 防止玩家登录时就在PVP世界（异常情况兜底）
        if (player.getWorld().getName().equals(pvpWorldName)) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.teleport(getBedOrSpawnLocation(player));
                cleanupPlayerStats(player);
                inPVPArena.remove(player.getName());
                // ★ 观赛观察者断线残留 → 强制恢复为生存（先注销观赛身份，避免身份残留）
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    spectatorBackups.remove(player.getName());
                    player.setGameMode(GameMode.SURVIVAL);
                }
                player.sendMessage("§e[PVP] 检测到你在竞技场非正常断线，已送你回主世界");
            }, 5L);
        }
    }

    /**
     * 玩家退出事件 — 断线处理
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // ★ 先记录"退出时人在哪/在不在登记表"：forceLeaveArena 之后登记会被清掉，事后就判断不出来了
        boolean quitInArenaWorld = player.getWorld() != null
                && player.getWorld().getName().equals(pvpWorldName);
        boolean inArenaRegistry = inPVPArena.contains(player.getName());
        if (inArenaRegistry) {
            // 断线前最后备份一次当前状态
            if (equipmentConfirmed.contains(player.getName())) {
                // 已装备确认过 → 当前身上是PVP装备，需要用之前的备份来还原
                // 备份已经在进入时做过，这里只需标记
                forceLeaveArena(player, true); // isDisconnect=true
            } else {
                // 还没确认装备 → 进入时已清空，必须还原原背包
                restoreInventory(player);
                inPVPArena.remove(player.getName());
                equipmentConfirmed.remove(player.getName());
                guiReopening.remove(player.getName());
                guiOpenedMillis.remove(player.getName());
                graceReopenCount.remove(player.getName());
                selEnchant.remove(player.getName());
                selShield.remove(player.getName());
                selFood.remove(player.getName());
                selWeaponTier.remove(player.getName());
                selRangedWeapon.remove(player.getName());
                selArmorTier.remove(player.getName());
                equipInteracted.remove(player.getName());
                cancelKickTimeout(player.getName());
                cleanupPlayerStats(player);
            }
        }
        // 断线后若世界已无玩家（含本玩家），立即删除世界（"没人了就删除"）
        // ★ 无论有没有登记，只要退出时人在 PVP 世界都要复核一次：未登记的旁观者/误入者
        //   下线后同样该触发空场删除，否则世界会卡在"没人却删不掉"的状态。
        //   判定本身已延后 1 tick（见 scheduleWorldDeletionIfEmpty），此时退出玩家才算真正离场。
        if (inArenaRegistry || quitInArenaWorld) {
            scheduleWorldDeletionIfEmpty();
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 备份玩家背包
     */
    private void backupInventory(Player player) {
        String playerName = player.getName();

        InventoryBackup backup = new InventoryBackup(
                player.getInventory().getContents(),
                player.getInventory().getArmorContents(),
                player.getInventory().getItemInOffHand(),
                player.getLevel(),
                player.getExp()
        );

        inventoryBackups.put(playerName, backup);

        // 同时保存到数据库（用于断线还原）
        db.savePvpInventoryBackup(
                playerName,
                serializeItems(backup.contents),
                serializeItems(backup.armor),
                serializeItem(backup.offHand),
                backup.expLevel,
                backup.exp
        );

        plugin.getLogger().info("[PVP] 已备份玩家 " + playerName + " 的背包");
    }

    /**
     * 恢复玩家背包
     */
    private void restoreInventory(Player player) {
        String playerName = player.getName();

        // 先从缓存获取
        InventoryBackup backup = inventoryBackups.remove(playerName);

        // 缓存没有则从数据库获取
        if (backup == null) {
            String[] data = db.getPvpInventoryBackup(playerName);
            if (data != null) {
                backup = new InventoryBackup(
                        deserializeItems(data[0]),
                        deserializeItems(data[1]),
                        deserializeItem(data[2]),
                        Integer.parseInt(data[3]),
                        Float.parseFloat(data[4])
                );
                // 删除数据库备份
                db.deletePvpInventoryBackup(playerName);
            }
        }

        if (backup != null) {
            player.getInventory().setContents(backup.contents);
            player.getInventory().setArmorContents(backup.armor);
            player.getInventory().setItemInOffHand(backup.offHand);
            player.setLevel(backup.expLevel);
            player.setExp(backup.exp);
            // ★ 无论备份来自缓存还是数据库，还原后必须清理数据库备份，
            //   否则断线重连时 onPlayerJoin 会再次覆盖玩家当前背包（序列化生效后的隐藏坑）
            db.deletePvpInventoryBackup(playerName);
            plugin.getLogger().info("[PVP] 已还原玩家 " + playerName + " 的背包");
        } else {
            plugin.getLogger().warning("[PVP] 未找到玩家 " + playerName + " 的背包备份");
        }
    }

    /**
     * 清空玩家背包
     */
    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setLevel(0);
        player.setExp(0.0f);
    }

    /**
     * 回收PVP装备
     */
    private void 回收PVPEquipment(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && isPVPEquipment(contents[i])) {
                contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);

        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && isPVPEquipment(armor[i])) {
                armor[i] = null;
            }
        }
        player.getInventory().setArmorContents(armor);

        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && isPVPEquipment(offHand)) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    /**
     * 检查物品是否是PVP装备（覆盖所有档位）
     */
    private boolean isPVPEquipment(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        List<String> lore = meta.getLore();
        if (lore == null) return false;
        for (String line : lore) {
            if (line != null && line.contains("PVP竞技场专属")) return true;
        }
        return false;
    }

    /**
     * 打开装备选择GUI
     */
    public void openEquipmentSelection(Player player) {
        String name = player.getName();

        // ★ 永久确认：选装一次即锁定，退出再进入也不能重新选装
        if (permanentlyConfirmed.contains(name)) {
            // 恢复之前保存的选装状态到临时map（供 equipFullSet 和 openAdjustmentGUI 读取）
            SavedSelection saved = savedSelections.get(name);
            if (saved != null) {
                selWeaponTier.put(name, saved.weaponTier);
                selArmorTier.put(name, saved.armorTier);
                selRangedWeapon.put(name, saved.rangedWeapon);
                selEnchant.put(name, saved.enchant);
                selShield.put(name, saved.shield);
                selFood.put(name, saved.food);
            }
            // ★ 关键：背包已在 onPlayerEnterPVPWorld 中清空，必须重新发放锁定装备
            equipFullSet(player);
            // 标记本次进入已确认（超时遣返不触发）
            equipmentConfirmed.add(name);
            cancelKickTimeout(name);
            openAdjustmentGUI(player);
            player.sendMessage("§a§l装备已就绪（沿用上次选择）");
            return;
        }

        // ★ 已确认装备的玩家（同一次进入内）→ 打开调整GUI（仅食物/盾牌/弓弩，其他锁定）
        if (equipmentConfirmed.contains(name)) {
            openAdjustmentGUI(player);
            return;
        }

        // ★ 记录GUI打开时间（宽限期机制：此后短时间内内的关闭事件不触发遣返）
        guiOpenedMillis.put(name, System.currentTimeMillis());
        graceReopenCount.put(name, 0);

        // ★ 公平锁定：本局首位进入PVP世界的玩家定死"武器材质/护甲材质/附魔"三档，后续加入者不可更改
        boolean isFirst = (lockedWeaponOwner == null);
        if (isFirst) {
            lockedWeaponOwner = name;
            lockedWeaponTier = 0;
            lockedArmorOwner = name;
            lockedArmorTier = 0;
            lockedEnchantOwner = name;
            lockedEnchant = false;
        }
        // 为所有玩家初始化默认值（后续玩家材质默认跟随首人定锁值）
        if (!selWeaponTier.containsKey(name)) selWeaponTier.put(name, lockedWeaponTier != null ? lockedWeaponTier : 0);
        if (!selArmorTier.containsKey(name)) selArmorTier.put(name, lockedArmorTier != null ? lockedArmorTier : 0);
        if (!selEnchant.containsKey(name)) selEnchant.put(name, lockedEnchant != null ? lockedEnchant : false);
        if (!selShield.containsKey(name)) selShield.put(name, true);
        if (!selFood.containsKey(name)) selFood.put(name, FOOD_DEFAULT);
        if (!selRangedWeapon.containsKey(name)) selRangedWeapon.put(name, 0); // 0=弓(默认)

        boolean weaponLocked = (lockedWeaponOwner != null && !lockedWeaponOwner.equals(name));
        boolean armorLocked = (lockedArmorOwner != null && !lockedArmorOwner.equals(name));
        boolean enchantLocked = (lockedEnchantOwner != null && !lockedEnchantOwner.equals(name));

        int myWeapon = selWeaponTier.getOrDefault(name, 0);
        int myArmor = selArmorTier.getOrDefault(name, 0);
        boolean myEnchant = selEnchant.getOrDefault(name, false);
        boolean myShield = selShield.getOrDefault(name, true);
        int myFood = selFood.getOrDefault(name, FOOD_DEFAULT);
        int myRanged = selRangedWeapon.getOrDefault(name, 0); // 0弓 1弩

        Inventory gui = Bukkit.createInventory(null, 54, EQUIPMENT_GUI_TITLE);

        // ★ 顶部预览（0-8）：当前所选装备
        gui.setItem(0, previewItem(SWORD_TIERS[myWeapon], "§b§l" + WEAPON_TIER_NAMES[myWeapon] + "§l剑", "§7主武器·必发"));
        gui.setItem(1, previewItem(AXE_TIERS[myWeapon], "§b§l" + WEAPON_TIER_NAMES[myWeapon] + "§l斧", "§7主武器·必发"));
        gui.setItem(2, previewItem(ARMOR_SETS[myArmor][0], "§b§l" + ARMOR_TIER_NAMES[myArmor] + "§l盔", "§7护甲·全套4件"));
        gui.setItem(3, previewItem(ARMOR_SETS[myArmor][1], "§b§l" + ARMOR_TIER_NAMES[myArmor] + "§l胸甲", "§7护甲·全套4件"));
        gui.setItem(4, previewItem(ARMOR_SETS[myArmor][2], "§b§l" + ARMOR_TIER_NAMES[myArmor] + "§l腿甲", "§7护甲·全套4件"));
        gui.setItem(5, previewItem(ARMOR_SETS[myArmor][3], "§b§l" + ARMOR_TIER_NAMES[myArmor] + "§l靴", "§7护甲·全套4件"));
        if (myShield) gui.setItem(6, previewItem(Material.SHIELD, "§a§l盾牌 ✓", "§7已选·副手装备"));
        else gui.setItem(6, grayPreview("§7盾牌 ✗", "§7未选（可开启）"));
        gui.setItem(7, previewItem(Material.COOKED_BEEF, "§c§l熟牛肉 x" + myFood, "§7补血普通食物"));

        // 信息面板（8）
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoM = info.getItemMeta();
        if (infoM != null) {
            infoM.setDisplayName("§6§lPVP装备选择");
            List<String> lore = new ArrayList<>();
            lore.add("§7主武器: " + WEAPON_TIER_NAMES[myWeapon] + "§7剑 + " + WEAPON_TIER_NAMES[myWeapon] + "§7斧（必发）");
            lore.add("§7护甲: " + ARMOR_TIER_NAMES[myArmor] + "§7全套4件");
            lore.add("§7附魔: §d锋利V + 击退II");
            lore.add("");
            lore.add("§7你的附魔: " + (myEnchant ? "§a已开启" : "§c未开启"));
            lore.add("§7你的盾牌: " + (myShield ? "§a已装备" : "§c未装备"));
            lore.add("§7远程武器: " + (myRanged == 1 ? "§b弩" : "§b弓") + "§7（默认装备）");
            lore.add("§7熟牛肉: §a" + myFood + " §7个");
            if (weaponLocked || armorLocked || enchantLocked) {
                lore.add("");
                lore.add("§e⚠ 本局平衡已锁定（首人: " + lockedWeaponOwner + "）");
                if (weaponLocked) lore.add("§7  武器材质: " + WEAPON_TIER_NAMES[lockedWeaponTier != null ? lockedWeaponTier : 0]);
                if (armorLocked) lore.add("§7  护甲材质: " + ARMOR_TIER_NAMES[lockedArmorTier != null ? lockedArmorTier : 0]);
                if (enchantLocked) lore.add("§7  附魔: " + (lockedEnchant ? "开启" : "关闭"));
            }
            infoM.setLore(lore);
            info.setItemMeta(infoM);
        }
        gui.setItem(8, info);

        // 分隔线（36-44，跳过40）
        for (int i = 36; i < 45; i++) {
            if (i == 40) continue;
            gui.setItem(i, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        }

        // 分组标签
        gui.setItem(9, labelItem("§6§l主武器材质", "§7点击直接选择，自动切换"));
        gui.setItem(18, labelItem("§6§l护甲材质", "§7点击直接选择，自动切换"));
        gui.setItem(27, labelItem("§6§l其他选项", "§7附魔 / 盾牌 / 食物"));

        // 武器材质 4 档（10-13）：铁/金/钻/合金
        for (int t = 0; t < 4; t++) {
            gui.setItem(10 + t, makeTierButton(SWORD_TIERS[t], WEAPON_TIER_NAMES[t] + "§l剑", t, myWeapon, weaponLocked, lockedWeaponTier));
        }
        // 护甲材质 4 档（19-22）：铁/金/钻/合金
        for (int t = 0; t < 4; t++) {
            gui.setItem(19 + t, makeTierButton(ARMOR_SETS[t][0], ARMOR_TIER_NAMES[t] + "§l盔", t, myArmor, armorLocked, lockedArmorTier));
        }

        // 附魔开关（28）— 仅首位玩家可切换，后续玩家灰显锁定
        ItemStack enchBtn = new ItemStack(
                enchantLocked ? Material.GRAY_STAINED_GLASS_PANE
                        : (myEnchant ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE));
        ItemMeta em = enchBtn.getItemMeta();
        if (em != null) {
            em.setDisplayName(enchantLocked ? "§7§l附魔 (已锁定)"
                    : (myEnchant ? "§a§l附魔: 开" : "§c§l附魔: 关"));
            List<String> lore = new ArrayList<>();
            lore.add("§7锋利V + 击退II（作用于主武器）");
            if (enchantLocked) {
                lore.add("§e由首位玩家设定: " + (lockedEnchant ? "开启" : "关闭"));
                lore.add("§7本局不可更改");
            } else {
                lore.add(myEnchant ? "§a点击关闭" : "§a点击开启");
            }
            em.setLore(lore);
            enchBtn.setItemMeta(em);
        }
        gui.setItem(28, enchBtn);

        // 盾牌开关（30）— 所有玩家可切换
        ItemStack shBtn = new ItemStack(myShield ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta sm = shBtn.getItemMeta();
        if (sm != null) {
            sm.setDisplayName(myShield ? "§a§l盾牌: 装备" : "§c§l盾牌: 不装备");
            sm.setLore(Arrays.asList("§7副手装备，可随时开关", myShield ? "§a点击取消" : "§a点击装备"));
            shBtn.setItemMeta(sm);
        }
        gui.setItem(30, shBtn);

        // 远程武器切换（29）— 弓/弩二选一，默认弓，属默认装备
        ItemStack rwBtn = new ItemStack(myRanged == 1 ? Material.CROSSBOW : Material.BOW);
        ItemMeta rwm = rwBtn.getItemMeta();
        if (rwm != null) {
            rwm.setDisplayName(myRanged == 1 ? "§b§l远程武器: 弩" : "§a§l远程武器: 弓");
            rwm.setLore(Arrays.asList("§7点击在 弓 / 弩 之间切换", "§e当前: " + (myRanged == 1 ? "弩 (默认装备)" : "弓 (默认装备)"), "§7会同时发放对应箭矢"));
            rwBtn.setItemMeta(rwm);
        }
        gui.setItem(29, rwBtn);

        // 熟牛肉快捷数量（32-35 = 8/16/32/64，点哪个直接设为哪个）
        int[] foodOpts = {8, 16, 32, 64};
        for (int i = 0; i < 4; i++) {
            gui.setItem(32 + i, makeFoodButton(foodOpts[i], myFood));
        }

        // 确认（49）
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§a§l确认装备（将备份你的原背包）");
            List<String> lore = new ArrayList<>();
            lore.add(WEAPON_TIER_NAMES[myWeapon] + "§7剑 + " + WEAPON_TIER_NAMES[myWeapon] + "§7斧"
                    + (myEnchant ? "（§d附魔§7）" : "（§7未附魔§7）"));
            lore.add(ARMOR_TIER_NAMES[myArmor] + "§7全套护甲");
            lore.add(myShield ? "§7含 §a盾牌" : "§7不含盾牌");
            lore.add("§7远程武器: " + (myRanged == 1 ? "§b弩" : "§b弓") + "（默认发放）");
            lore.add("§7熟牛肉 x" + myFood);
            confirmMeta.setLore(lore);
            confirm.setItemMeta(confirmMeta);
        }
        gui.setItem(49, confirm);

        player.openInventory(gui);
    }

    /**
     * 已确认装备玩家的调整GUI：武器/护甲/附魔只读锁定，仅可调食物/盾牌/弓弩
     */
    private void openAdjustmentGUI(Player player) {
        String name = player.getName();

        int myWeapon = selWeaponTier.getOrDefault(name, 0);
        int myArmor = selArmorTier.getOrDefault(name, 0);
        boolean myEnchant = selEnchant.getOrDefault(name, false);
        boolean myShield = selShield.getOrDefault(name, true);
        int myFood = selFood.getOrDefault(name, FOOD_DEFAULT);
        int myRanged = selRangedWeapon.getOrDefault(name, 0);

        Inventory gui = Bukkit.createInventory(null, 54, EQUIPMENT_GUI_TITLE);

        // 顶部预览（0-7）：锁定状态的装备显示
        gui.setItem(0, previewItem(SWORD_TIERS[myWeapon], "§7§l" + WEAPON_TIER_NAMES[myWeapon] + "§l剑 §c🔒", "§7已锁定·不可更改"));
        gui.setItem(1, previewItem(AXE_TIERS[myWeapon], "§7§l" + WEAPON_TIER_NAMES[myWeapon] + "§l斧 §c🔒", "§7已锁定·不可更改"));
        gui.setItem(2, previewItem(ARMOR_SETS[myArmor][0], "§7§l" + ARMOR_TIER_NAMES[myArmor] + "§l盔 §c🔒", "§7已锁定·不可更改"));
        gui.setItem(3, previewItem(ARMOR_SETS[myArmor][1], "§7§l" + ARMOR_TIER_NAMES[myArmor] + "§l胸甲 §c🔒", "§7已锁定·不可更改"));
        gui.setItem(4, previewItem(ARMOR_SETS[myArmor][2], "§7§l" + ARMOR_TIER_NAMES[myArmor] + "§l腿甲 §c🔒", "§7已锁定·不可更改"));
        gui.setItem(5, previewItem(ARMOR_SETS[myArmor][3], "§7§l" + ARMOR_TIER_NAMES[myArmor] + "§l靴 §c🔒", "§7已锁定·不可更改"));
        if (myShield) gui.setItem(6, previewItem(Material.SHIELD, "§a§l盾牌 ✓", "§7已选·点击可停用"));
        else gui.setItem(6, grayPreview("§7盾牌 ✗", "§7未选·点击可启用"));
        gui.setItem(7, previewItem(Material.COOKED_BEEF, "§c§l熟牛肉 x" + myFood, "§7可调整数量"));

        // 信息面板（8）
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoM = info.getItemMeta();
        if (infoM != null) {
            infoM.setDisplayName("§6§lPVP装备调整");
            List<String> lore = new ArrayList<>();
            lore.add("§7装备已锁定，仅可调整以下选项：");
            lore.add("");
            lore.add("§e⚡ 当前装备状态");
            lore.add("§7  主武器: " + WEAPON_TIER_NAMES[myWeapon] + "§7剑 + " + WEAPON_TIER_NAMES[myWeapon] + "§7斧");
            lore.add("§7  护甲: " + ARMOR_TIER_NAMES[myArmor] + "§7全套4件");
            lore.add("§7  附魔: " + (myEnchant ? "§a已开启" : "§c未开启"));
            lore.add("");
            lore.add("§a↕ 可调整项:");
            lore.add("§7  - 食物补给数量");
            lore.add("§7  - 盾牌启用/停用");
            lore.add("§7  - 弓/弩切换");
            infoM.setLore(lore);
            info.setItemMeta(infoM);
        }
        gui.setItem(8, info);

        // 分隔线（36-44，跳过40）
        for (int i = 36; i < 45; i++) {
            if (i == 40) continue;
            gui.setItem(i, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        }

        // 分组标签：武器/护甲标为已锁定
        gui.setItem(9, labelItem("§7§l主武器材质 §c(已锁定)", "§7首次选装后不可更改"));
        gui.setItem(18, labelItem("§7§l护甲材质 §c(已锁定)", "§7首次选装后不可更改"));
        gui.setItem(27, labelItem("§6§l可调整选项", "§7点击直接切换/选择"));

        // 武器材质（10-13）：全部只读灰显
        for (int t = 0; t < 4; t++) {
            ItemStack btn;
            if (t == myWeapon) {
                btn = new ItemStack(SWORD_TIERS[t]);
                ItemMeta bm = btn.getItemMeta();
                if (bm != null) {
                    bm.setDisplayName("§7" + WEAPON_TIER_NAMES[t] + "剑 §a✔");
                    bm.setLore(Arrays.asList("§7已锁定"));
                    btn.setItemMeta(bm);
                }
            } else {
                btn = grayPreview("§7" + WEAPON_TIER_NAMES[t] + "剑", "§7已锁定");
            }
            gui.setItem(10 + t, btn);
        }

        // 护甲材质（19-22）：全部只读灰显
        for (int t = 0; t < 4; t++) {
            ItemStack btn;
            if (t == myArmor) {
                btn = new ItemStack(ARMOR_SETS[t][0]);
                ItemMeta bm = btn.getItemMeta();
                if (bm != null) {
                    bm.setDisplayName("§7" + ARMOR_TIER_NAMES[t] + "盔 §a✔");
                    bm.setLore(Arrays.asList("§7已锁定"));
                    btn.setItemMeta(bm);
                }
            } else {
                btn = grayPreview("§7" + ARMOR_TIER_NAMES[t] + "盔", "§7已锁定");
            }
            gui.setItem(19 + t, btn);
        }

        // 附魔（28）：只读灰显
        ItemStack enchBtn = grayPreview(
                myEnchant ? "§7附魔: 开 §c(已锁定)" : "§7附魔: 关 §c(已锁定)",
                "§7首次选装后不可更改");
        gui.setItem(28, enchBtn);

        // 盾牌开关（30）— 可调整
        ItemStack shBtn = new ItemStack(myShield ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta sm = shBtn.getItemMeta();
        if (sm != null) {
            sm.setDisplayName(myShield ? "§a§l盾牌: 装备" : "§c§l盾牌: 不装备");
            sm.setLore(Arrays.asList("§7副手装备", myShield ? "§a点击取消" : "§a点击装备"));
            shBtn.setItemMeta(sm);
        }
        gui.setItem(30, shBtn);

        // 远程武器切换（29）— 可调整
        ItemStack rwBtn = new ItemStack(myRanged == 1 ? Material.CROSSBOW : Material.BOW);
        ItemMeta rwm = rwBtn.getItemMeta();
        if (rwm != null) {
            rwm.setDisplayName(myRanged == 1 ? "§b§l远程武器: 弩" : "§a§l远程武器: 弓");
            rwm.setLore(Arrays.asList("§7点击在 弓 / 弩 之间切换",
                    "§e当前: " + (myRanged == 1 ? "弩" : "弓"),
                    "§7调整后立即生效"));
            rwBtn.setItemMeta(rwm);
        }
        gui.setItem(29, rwBtn);

        // 熟牛肉快捷数量（32-35 = 8/16/32/64）
        int[] foodOpts = {8, 16, 32, 64};
        for (int i = 0; i < 4; i++) {
            gui.setItem(32 + i, makeFoodButton(foodOpts[i], myFood));
        }

        // 关闭按钮（49）
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cm = close.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("§c§l关闭");
            cm.setLore(Arrays.asList("§7关闭装备调整界面"));
            close.setItemMeta(cm);
        }
        gui.setItem(49, close);

        player.openInventory(gui);
    }

    private ItemStack previewItem(Material mat, String name, String desc) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(Arrays.asList(desc));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack grayPreview(String name, String desc) {
        return previewItem(Material.GRAY_STAINED_GLASS_PANE, name, desc);
    }

    private ItemStack labelItem(String name, String desc) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(Arrays.asList(desc));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack makeTierButton(Material mat, String label, int tier, int selectedTier, boolean locked, Integer lockedTier) {
        boolean selected = (tier == selectedTier);
        ItemStack it;
        if (locked && !selected) {
            it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        } else {
            it = new ItemStack(mat);
        }
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            String mark = selected ? " §a✔" : "";
            m.setDisplayName((locked && !selected ? "§7§l" : "") + label + mark);
            List<String> lore = new ArrayList<>();
            if (locked) {
                lore.add("§e本局已由首位玩家锁定");
                int lt = (lockedTier != null) ? lockedTier : 0;
                lore.add("§7锁定材质: " + (lt >= 0 && lt < WEAPON_TIER_NAMES.length ? WEAPON_TIER_NAMES[lt] : "?"));
                lore.add("§7不可更改");
            } else if (selected) {
                lore.add("§a当前选择，点击取消");
            } else {
                lore.add("§a点击选择此材质");
            }
            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack makeFoodButton(int foodVal, int currentFood) {
        boolean selected = (foodVal == currentFood);
        ItemStack it = selected ? new ItemStack(Material.LIME_STAINED_GLASS_PANE) : new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName((selected ? "§a§l" : "§e§l") + "熟牛肉 x" + foodVal + (selected ? " ✔" : ""));
            m.setLore(Arrays.asList(selected ? "§a当前数量" : "§a点击设为 " + foodVal + " 个"));
            it.setItemMeta(m);
        }
        return it;
    }

    // ★ 旧5档护甲系统已废弃（2026-07-09 改版为铁剑+铁斧+可选盾牌+可选附魔+可调熟牛肉）
    //   原 buildTierEquipment(EquipmentTier) 与 makePotion 整体移除，装备由 equipFullSet 按新模型直接构建。

    /**
     * 创建带PVP标记的箭矢堆
     */
    private ItemStack makeArrowStack(int amount) {
        ItemStack arrows = new ItemStack(Material.ARROW, amount);
        ItemMeta m = arrows.getItemMeta();
        if (m != null) {
            m.setDisplayName("§b§lPVP箭矢");
            m.setLore(Arrays.asList("§7远程武器弹药", PVP_ITEM_MARKER));
            arrows.setItemMeta(m);
        }
        return arrows;
    }

    private ItemStack makeWeapon(Material mat, String name, boolean enchant) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> lore = new ArrayList<>();
            lore.add("§7PVP竞技场主武器");
            if (enchant) {
                // ★ 新附魔：锋利V + 击退II（取代旧 SHARPNESS4/UNBREAKING3/MENDING1）
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2, true);
                lore.add("§d附魔: 锋利V + 击退II");
            } else {
                lore.add("§7未附魔");
            }
            lore.add(PVP_ITEM_MARKER);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 处理装备选择GUI点击
     */
    public boolean handleEquipmentClick(Player player, int slot) {
        if (!player.getOpenInventory().getTitle().equals(EQUIPMENT_GUI_TITLE)) {
            return false;
        }

        if (!inPVPArena.contains(player.getName())) {
            return false;
        }

        String name = player.getName();

        // ★ 已确认装备 → 调整模式：仅允许调整食物/盾牌/弓弩，其余全部锁定
        boolean confirmed = equipmentConfirmed.contains(name);
        if (confirmed) {
            // 武器材质（10-13）— 锁定
            if (slot >= 10 && slot <= 13) {
                player.sendMessage("§e[PVP] §7装备已锁定，不可更改武器材质");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            // 护甲材质（19-22）— 锁定
            if (slot >= 19 && slot <= 22) {
                player.sendMessage("§e[PVP] §7装备已锁定，不可更改护甲材质");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            // 附魔（28）— 锁定
            if (slot == 28) {
                player.sendMessage("§e[PVP] §7附魔已锁定，不可更改");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            // 盾牌（30）— 可调整
            if (slot == 30) {
                boolean cur = selShield.getOrDefault(name, true);
                selShield.put(name, !cur);
                reEquipAdjustments(player);
                guiReopening.add(name);
                openAdjustmentGUI(player);
                player.sendMessage("§a[PVP] 盾牌已" + (!cur ? "§a装备" : "§c取消"));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return true;
            }
            // 远程武器（29）— 可调整
            if (slot == 29) {
                int cur = selRangedWeapon.getOrDefault(name, 0);
                selRangedWeapon.put(name, cur == 0 ? 1 : 0);
                reEquipAdjustments(player);
                guiReopening.add(name);
                openAdjustmentGUI(player);
                player.sendMessage("§a[PVP] 远程武器已切换为 " + (cur == 0 ? "§b弩" : "§b弓"));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return true;
            }
            // 食物数量（32-35）— 可调整
            if (slot >= 32 && slot <= 35) {
                int[] foodOpts = {8, 16, 32, 64};
                int food = foodOpts[slot - 32];
                selFood.put(name, food);
                reEquipAdjustments(player);
                guiReopening.add(name);
                openAdjustmentGUI(player);
                player.sendMessage("§a[PVP] 熟牛肉数量已设为 " + food);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                return true;
            }
            // 关闭（49）
            if (slot == 49) {
                player.closeInventory();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                return true;
            }
            // 预览区/标签（0-8, 9, 18, 27）：仅音效
            if ((slot >= 0 && slot <= 8) || slot == 9 || slot == 18 || slot == 27) {
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                return true;
            }
            return false;
        }

        boolean weaponLocked = (lockedWeaponOwner != null && !lockedWeaponOwner.equals(name));
        boolean armorLocked = (lockedArmorOwner != null && !lockedArmorOwner.equals(name));
        boolean enchantLocked = (lockedEnchantOwner != null && !lockedEnchantOwner.equals(name));

        // ★ 武器材质直选（10-13）：铁/金/钻/合金，点哪个直接切换
        if (slot >= 10 && slot <= 13) {
            int tier = slot - 10;
            if (weaponLocked) {
                int lt = (lockedWeaponTier != null) ? lockedWeaponTier : 0;
                if (tier == lt) { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); return true; }
                player.sendMessage("§e[PVP] §7本局武器材质已由首位玩家锁定，不可更改");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            selWeaponTier.put(name, tier);
            lockedWeaponTier = tier; // 首人实时定锁
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.sendMessage("§a[PVP] 主武器材质已选: " + WEAPON_TIER_NAMES[tier] + "（" + WEAPON_TIER_NAMES[tier] + "§l剑 + " + WEAPON_TIER_NAMES[tier] + "§l斧）");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return true;
        }

        // ★ 护甲材质直选（19-22）：铁/金/钻/合金，点哪个直接切换
        if (slot >= 19 && slot <= 22) {
            int tier = slot - 19;
            if (armorLocked) {
                int lt = (lockedArmorTier != null) ? lockedArmorTier : 0;
                if (tier == lt) { player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f); return true; }
                player.sendMessage("§e[PVP] §7本局护甲材质已由首位玩家锁定，不可更改");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            selArmorTier.put(name, tier);
            lockedArmorTier = tier;
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.sendMessage("§a[PVP] 护甲材质已选: " + ARMOR_TIER_NAMES[tier] + "（全套4件）");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return true;
        }

        // 附魔开关（28）— 仅首位玩家可切换，后续玩家锁定不可改
        if (slot == 28) {
            if (enchantLocked) {
                player.sendMessage("§e[PVP] §7本局附魔已由首位玩家锁定，不可更改");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                return true;
            }
            boolean cur = selEnchant.getOrDefault(name, false);
            selEnchant.put(name, !cur);
            lockedEnchant = !cur; // 首人实时定锁
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.sendMessage("§a[PVP] 主武器附魔已" + (!cur ? "§d开启（锋利V+击退II）" : "§7关闭"));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return true;
        }

        // 盾牌开关（30）— 所有玩家可切换
        if (slot == 30) {
            boolean cur = selShield.getOrDefault(name, true);
            selShield.put(name, !cur);
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.sendMessage("§a[PVP] 盾牌已" + (!cur ? "§a装备" : "§c取消"));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return true;
        }

        // 远程武器切换（29）— 弓/弩二选一，默认弓
        if (slot == 29) {
            int cur = selRangedWeapon.getOrDefault(name, 0);
            selRangedWeapon.put(name, cur == 0 ? 1 : 0);
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.sendMessage("§a[PVP] 远程武器已切换为 " + (cur == 0 ? "§b弩" : "§b弓") + "§a（默认装备）");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            return true;
        }

        // 熟牛肉快捷数量（32-35 = 8/16/32/64），点哪个直接设为哪个
        if (slot >= 32 && slot <= 35) {
            int[] foodOpts = {8, 16, 32, 64};
            int food = foodOpts[slot - 32];
            selFood.put(name, food);
            equipInteracted.add(name);
            guiReopening.add(name);
            openEquipmentSelection(player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return true;
        }

        // 确认（49）→ 备份+清空+发装备
        if (slot == 49) {
            equipInteracted.add(name);
            guiReopening.add(name);
            player.closeInventory();
            confirmEquipment(player);
            return true;
        }

        // 预览区 / 标签（0-8, 9, 18, 27）：仅音效，不操作
        if ((slot >= 0 && slot <= 8) || slot == 9 || slot == 18 || slot == 27) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return true;
        }

        return false;
    }

    /**
     * 装备全套PVP装备到玩家背包（按新模型：铁剑+铁斧+可选盾牌+可选附魔+可调熟牛肉）
     */
    private void equipFullSet(Player player) {
        String name = player.getName();

        // ★ 公平锁定：非首人玩家强制使用首人定死的附魔值；首人使用自身选择
        boolean enchant;
        if (lockedEnchantOwner != null && !lockedEnchantOwner.equals(name)) {
            enchant = (lockedEnchant != null) ? lockedEnchant : false;
        } else {
            enchant = selEnchant.getOrDefault(name, false);
        }
        int wTier = selWeaponTier.getOrDefault(name, 0);
        int aTier = selArmorTier.getOrDefault(name, 0);
        boolean shield = selShield.getOrDefault(name, true);
        int food = selFood.getOrDefault(name, FOOD_DEFAULT);
        int rw = selRangedWeapon.getOrDefault(name, 0); // 0弓 1弩

        // 主武器：同材质剑 + 斧（两者都发，附魔作用于两者）
        player.getInventory().addItem(makeWeapon(SWORD_TIERS[wTier], "§l" + WEAPON_TIER_NAMES[wTier] + "§l剑", enchant));
        player.getInventory().addItem(makeWeapon(AXE_TIERS[wTier], "§l" + WEAPON_TIER_NAMES[wTier] + "§l斧", enchant));

        // 护甲：同材质全套 4 件（带 PVP 标记，便于回收；不附魔）
        // ★ ARMOR_SETS 顺序: [头盔, 胸甲, 护腿, 靴子]
        //   setArmorContents 顺序: [靴子, 护腿, 胸甲, 头盔] — 必须反转
        ItemStack[] armorPieces = new ItemStack[4];
        for (int i = 0; i < ARMOR_SETS[aTier].length; i++) {
            Material armorMat = ARMOR_SETS[aTier][i];
            ItemStack a = new ItemStack(armorMat);
            ItemMeta m = a.getItemMeta();
            if (m != null) {
                m.setDisplayName("§l" + ARMOR_TIER_NAMES[aTier] + "§l护甲");
                m.setLore(Arrays.asList("§7PVP竞技场护甲", PVP_ITEM_MARKER));
                a.setItemMeta(m);
            }
            armorPieces[3 - i] = a; // 反转: armorPieces[0]=靴子, armorPieces[3]=头盔
        }
        player.getInventory().setArmorContents(armorPieces);

        // 盾牌（可选，副手）
        if (shield) {
            ItemStack sh = new ItemStack(Material.SHIELD);
            ItemMeta m = sh.getItemMeta();
            if (m != null) {
                m.setDisplayName("§a§lPVP盾牌");
                m.setLore(Arrays.asList("§7格挡近战攻击", PVP_ITEM_MARKER));
                sh.setItemMeta(m);
            }
            player.getInventory().setItemInOffHand(sh);
        }

        // 远程武器（默认装备）：弓/弩二选一，默认弓，统一发放对应弹药（箭）
        if (rw == 1) {
            ItemStack crossbow = new ItemStack(Material.CROSSBOW);
            ItemMeta cm = crossbow.getItemMeta();
            if (cm != null) {
                cm.setDisplayName("§b§lPVP弩");
                cm.setLore(Arrays.asList("§7远程武器（默认装备）", PVP_ITEM_MARKER));
                crossbow.setItemMeta(cm);
            }
            player.getInventory().addItem(crossbow);
            player.getInventory().addItem(makeArrowStack(64));
        } else {
            ItemStack bow = new ItemStack(Material.BOW);
            ItemMeta bm = bow.getItemMeta();
            if (bm != null) {
                bm.setDisplayName("§b§lPVP弓");
                bm.setLore(Arrays.asList("§7远程武器（默认装备）", PVP_ITEM_MARKER));
                bow.setItemMeta(bm);
            }
            player.getInventory().addItem(bow);
            player.getInventory().addItem(makeArrowStack(64));
        }

        // 补血普通食物：熟牛肉（数量可调）
        if (food > 0) {
            ItemStack beef = new ItemStack(Material.COOKED_BEEF, food);
            ItemMeta m = beef.getItemMeta();
            if (m != null) {
                m.setDisplayName("§c§lPVP熟牛肉");
                m.setLore(Arrays.asList("§7补血普通食物 x" + food, PVP_ITEM_MARKER));
                beef.setItemMeta(m);
            }
            player.getInventory().addItem(beef);
        }

        plugin.getLogger().info("[PVP] 玩家 " + name + " 发放装备: "
                + WEAPON_TIER_NAMES[wTier] + "剑+斧 / " + ARMOR_TIER_NAMES[aTier] + "护甲"
                + (enchant ? "(附魔)" : "(未附魔)") + (shield ? " +盾牌" : "")
                + " 远程:" + (rw == 1 ? "弩" : "弓") + " 熟牛肉x" + food);
    }

    /**
     * 移除可调整类的PVP物品（食物/弓/弩/箭/盾牌），保留武器+护甲不动
     */
    private void removePVPAdjustables(Player player) {
        // 副手盾牌
        ItemStack off = player.getInventory().getItemInOffHand();
        if (off != null && off.getType() == Material.SHIELD) {
            player.getInventory().setItemInOffHand(null);
        }
        // 背包中带PVP标记的食物/弓/弩/箭
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || !item.hasItemMeta()) continue;
            ItemMeta m = item.getItemMeta();
            if (m == null || !m.hasLore()) continue;
            List<String> lore = m.getLore();
            if (lore == null || !lore.contains(PVP_ITEM_MARKER)) continue;
            Material t = item.getType();
            if (t == Material.COOKED_BEEF || t == Material.BOW || t == Material.CROSSBOW || t == Material.ARROW) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    /**
     * 为已确认装备的玩家重新应用食物/盾牌/远程武器调整
     * （武器材质/护甲材质/附魔已锁定，不做变动）
     */
    private void reEquipAdjustments(Player player) {
        String name = player.getName();
        if (!equipmentConfirmed.contains(name)) return;

        removePVPAdjustables(player);

        boolean shield = selShield.getOrDefault(name, true);
        int food = selFood.getOrDefault(name, FOOD_DEFAULT);
        int rw = selRangedWeapon.getOrDefault(name, 0);

        // 盾牌（副手）
        if (shield) {
            ItemStack sh = new ItemStack(Material.SHIELD);
            ItemMeta sm = sh.getItemMeta();
            if (sm != null) {
                sm.setDisplayName("§a§lPVP盾牌");
                sm.setLore(Arrays.asList("§7格挡近战攻击", PVP_ITEM_MARKER));
                sh.setItemMeta(sm);
            }
            player.getInventory().setItemInOffHand(sh);
        }

        // 远程武器 + 箭矢
        if (rw == 1) {
            ItemStack crossbow = new ItemStack(Material.CROSSBOW);
            ItemMeta cm = crossbow.getItemMeta();
            if (cm != null) {
                cm.setDisplayName("§b§lPVP弩");
                cm.setLore(Arrays.asList("§7远程武器（默认装备）", PVP_ITEM_MARKER));
                crossbow.setItemMeta(cm);
            }
            player.getInventory().addItem(crossbow);
        } else {
            ItemStack bow = new ItemStack(Material.BOW);
            ItemMeta bm = bow.getItemMeta();
            if (bm != null) {
                bm.setDisplayName("§b§lPVP弓");
                bm.setLore(Arrays.asList("§7远程武器（默认装备）", PVP_ITEM_MARKER));
                bow.setItemMeta(bm);
            }
            player.getInventory().addItem(bow);
        }
        player.getInventory().addItem(makeArrowStack(64));

        // 食物
        if (food > 0) {
            ItemStack beef = new ItemStack(Material.COOKED_BEEF, food);
            ItemMeta bm = beef.getItemMeta();
            if (bm != null) {
                bm.setDisplayName("§c§lPVP熟牛肉");
                bm.setLore(Arrays.asList("§7补血普通食物 x" + food, PVP_ITEM_MARKER));
                beef.setItemMeta(bm);
            }
            player.getInventory().addItem(beef);
        }

        player.sendMessage("§a§l[PVP] §7装备调整已应用");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
    }

    // ==================== 命令入口 ====================

    /**
     * 玩家进入PVP竞技场（/pvp join）
     *
     * ★ 2026-07-08 生命周期（按用户要求）：
     *   - 世界存在且仍有其他玩家在场 → 直接加入当前对局（不删除、不重建、不干预出生点）；
     *   - 世界不存在 或 已无人 → 删除旧世界（内存中或磁盘残留）后重新生成全新主世界；
     *   - 传送至 MC 自然出生点；只做一次"脚下方块是否水"的检查：
     *       非水 → 直接放行（不管出生点在天上还是地下）；
     *       是水 → 仅上移【该玩家】至地表空气处（不删除世界、不改世界出生点），并打日志打印 seed 便于排查。
     */
    public void joinArena(Player player) {
        World pvpWorld = Bukkit.getWorld(pvpWorldName);

        // ★ 多人保护：世界中仍有其他玩家在战斗 → 复用同一局，直接加入（不删除、不重建、不干预出生点）
        if (pvpWorld != null && !pvpWorld.getPlayers().isEmpty()) {
            plugin.getLogger().info("[PVP] PVP世界已有其他玩家在场，玩家 " + player.getName() + " 直接加入当前对局（不删除/不重建）");
            finalizeJoin(player, pvpWorld);
            return;
        }

        // ★ 玩家全离开后世界已删除（见 scheduleWorldDeletionIfEmpty），此处重新生成全新随机地形：
        //   createPVPWorld() 在主线程同步执行（Bukkit.createWorld 触发 WorldInitEvent 必须主线程，异步会抛 IllegalStateException 崩溃）。
        //   为消除原 13s 卡服：① 移除"水→删除重建换种子"循环（forceSafeSpawn 兜底平台，createWorld 只跑一次）；
        //   ② 周边区块由 preGenerateSpawnChunksAsync 异步预生成。主线程仅承担单次 createWorld(NORMAL) 的真实地形生成。
        //   若出生点脚下是水，finalizeJoin 仅上移该玩家至地表（不删除世界），彻底绕开"水→重建"卡服链路。
        if (pvpWorld != null) {
            plugin.getLogger().info("[PVP] PVP世界当前无人，主线程卸载旧世界并重新生成全新主世界");
            deletePVPWorld(pvpWorld);
        } else {
            File folder = resolveWorldFolder(pvpWorldName);
            if (folder != null && folder.exists()) {
                plugin.getLogger().info("[PVP] 检测到磁盘残留PVP世界目录（无人在场），移入回收站后重新生成: "
                        + folder.getAbsolutePath());
                moveWorldToTrash(folder);
            }
        }

        player.sendMessage("§a§l正在生成PVP竞技场世界，请稍候...");
        // ★ 主线程异步任务（下一tick执行）：createWorld(NORMAL) 真实地形生成需 ~12s，
        //   放入独立 runTask 让 /pvp join 命令所在 tick 立即返回（"正在生成"提示先送达），
        //   不再冻结命令执行线程。注意 createWorld 仍必须在主线程（触发 WorldInitEvent），
        //   故用 runTask 而非 runTaskAsynchronously；自然地形生成的 Watchdog 提示为 Paper 已知良性提示。
        final Player fp = player;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!fp.isOnline()) return;
            World w = createPVPWorld();
            if (w == null) {
                fp.sendMessage("§c§l[PVP] 竞技场世界加载失败，请联系管理员");
                return;
            }
            finalizeJoin(fp, w);
        });
    }

    /**
     * 传送玩家至PVP世界并安排打开装备GUI（必须在主线程调用）
     *
     * 从 joinArena 抽出，便于「复用现有世界」与「异步创建后回主线程」两种路径共用同一套传送+GUI逻辑。
     */
    private void finalizeJoin(Player player, World pvpWorld) {
        if (pvpWorld == null) {
            player.sendMessage("§c§l[PVP] 竞技场世界加载失败，请联系管理员");
            return;
        }
        if (!player.isOnline()) return;
        if (inPVPArena.contains(player.getName())) {
            player.sendMessage("§e你已在PVP竞技场中，请选择装备或输入 /pvp leave 离开");
            return;
        }

        // 传送至世界自然出生点（插件不干预出生点，由 MC 自行决定）
        Location spawn = pvpWorld.getSpawnLocation();

        // ★ 只做一次检查：脚下方块是水 → 需要上移到地表；不是水 → 直接放行（不管天上地下）
        Block feet = pvpWorld.getBlockAt(spawn.getBlockX(), spawn.getBlockY() - 1, spawn.getBlockZ());
        if (isFluid(feet.getType())) {
            // 失败种子日志：出生点脚下是水，记录 MC 种子便于排查
            plugin.getLogger().warning("[PVP] ⚠ 失败种子 seed=" + pvpWorld.getSeed()
                    + " 自然出生点脚下为水 loc=(" + spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ()
                    + ")，仅上移该玩家至地表（不删除世界、不改世界出生点）");
            int topY = getTopSolidY(pvpWorld, spawn.getBlockX(), spawn.getBlockZ());
            spawn = new Location(pvpWorld, spawn.getBlockX(), topY + 1, spawn.getBlockZ());
        } else {
            plugin.getLogger().info("[PVP] 出生点脚下非水（" + feet.getType().name() + "），直接放行（不干预出生点）");
        }

        player.teleport(spawn);

        // ★ 装备 GUI 由 PlayerChangedWorldEvent 在玩家【抵达 pvp_arena 世界后】打开，
        //   避免传送完成前于主世界打开 GUI 被跨世界事件强制关闭而误遣返。
        //   兜底：若玩家本就已在 pvp_arena 世界内（如重连边界场景，不触发跨世界事件），延迟任务在其所在世界内打开。
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!inPVPArena.contains(player.getName())
                    && player.isOnline()
                    && player.getWorld().getName().equals(pvpWorldName)) {
                onPlayerEnterPVPWorld(player);
            }
        }, 5L);

        player.sendMessage("§a§l正在前往PVP竞技场...");
    }

    /**
     * 玩家离开PVP竞技场（/pvp leave）
     */
    public void leaveArena(Player player) {
        if (!inPVPArena.contains(player.getName())) {
            player.sendMessage("§e你不在PVP竞技场中");
            return;
        }
        // 传回主世界（触发 PlayerChangedWorldEvent → forceLeaveArena），优先床
        player.teleport(getBedOrSpawnLocation(player));
        player.sendMessage("§a§l正在离开PVP竞技场...");
    }

    // ==================== PVP 观赛（/pvp see 观察者模式）====================


    /** 玩家名 → 进入观察者前的游戏模式（离场/重连时还原，避免把观察者模式带到主世界） */
    private final Map<String, GameMode> spectatorBackups = new ConcurrentHashMap<>();

    /** 是否 PVP 世界（pvp_arena* 竞技场 / pvp_test 测试场）——观赛功能仅在这些世界生效 */
    public boolean isPVPWorld(World w) {
        if (w == null) return false;
        return w.getName().startsWith("pvp_");
    }

    /**
     * /pvp see —— PVP 观赛模式（仅 PVP 世界生效）
     *
     * 三种调用形式：
     *   /pvp see       → 切换（在观察者⇄普通模式之间来回切，mode=null）
     *   /pvp join see  → 强制进入观察者（mode="enter"）
     *   /pvp leave see → 强制退出观察者（mode="exit"）
     *
     * ★ v2 设计变更：原版【观察者模式下物品栏会被客户端自动隐藏替换】，
     *   雪球发了也看不见、无法右键，故已彻底移除雪球方案，只保留命令切换。
     *   观察者不能伤害玩家，玩家也不能伤害观察者（见 onSpectatorCombat）。
     */
    public void handleSeeCommand(Player p) {
        handleSeeCommand(p, null);
    }

    /**
     * @param mode null=切换  "enter"=强制进观察者  "exit"=强制退观察者
     */
    public void handleSeeCommand(Player p, String mode) {
        boolean already = p.getGameMode() == GameMode.SPECTATOR;

        // ★ 退出观察者在任何世界都放行（防止玩家被带离PVP世界后卡在观察者回不来）
        if ("exit".equals(mode)) {
            if (!already) {
                // ★ 状态漂移兜底：身份仍登记为观赛者但模式已被外部改走 → 清掉身份残留
                if (spectatorBackups.remove(p.getName()) != null) {
                    p.sendMessage("§e§l[PVP观赛] §f你已不在观察者模式，已清理观赛身份残留");
                    plugin.getLogger().info("[PVP观赛] 玩家 " + p.getName()
                            + " 用 /pvp leave see 清理了观赛身份残留（模式已是 "
                            + p.getGameMode().name() + "）");
                    return;
                }
                p.sendMessage("§e§l[PVP观赛] §f你当前不在观察者模式");
                return;
            }
            exitSpectator(p);
            return;
        }

        if (!isPVPWorld(p.getWorld())) {
            p.sendMessage("§c§l[PVP观赛] §f仅在PVP世界可用（/pvp join 进入竞技场后再使用）");
            return;
        }

        if ("enter".equals(mode)) {
            if (already) {
                p.sendMessage("§e§l[PVP观赛] §f你已经在观察者模式了");
                return;
            }
            enterSpectator(p);
            return;
        }

        // mode == null → 切换
        if (already) {
            exitSpectator(p);
        } else {
            enterSpectator(p);
        }
    }

    /** 进入观察者观赛：回收PVP装备(不退自己装备) → 记录原模式 → 进入原版观察者 */
    private void enterSpectator(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        // 1) 回收PVP专属装备；★ 玩家自己的装备【不发还】，仍留在备份中，离场时统一还原
        回收PVPEquipment(p);

        // 2) 记录原模式并进入 MC 原版观察者
        //    ★ 必须【先记再切】：观察者模式下 addItem 会掉地上，故不再发放任何物品
        //    ★ 绝不把【创造模式】当作还原目标：以创造进入观赛的，退出一律回生存（防止退出观赛变成创造作弊）
        GameMode before = p.getGameMode();
        if (before == GameMode.CREATIVE || before == GameMode.SPECTATOR) {
            before = GameMode.SURVIVAL;
        }
        spectatorBackups.put(p.getName(), before);
        p.setGameMode(GameMode.SPECTATOR);

        p.sendMessage("§a§l[PVP观赛] §f已进入观察者模式，可穿墙自由观赛");
        p.sendMessage("§7  §f/pvp see §7→ 切换回普通模式");
        p.sendMessage("§7  §f/pvp leave see §7→ 退出观察者");
        p.sendMessage("§7  旁观状态下你与他人互不伤害，可放心观战");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.getLogger().info("[PVP] 玩家 " + p.getName() + " 进入观赛观察者模式");
    }

    /** 退出观察者 → 还原进入前的游戏模式（默认生存），含安全落点与缓降保护 */
    private void exitSpectator(Player p) {
        GameMode back = spectatorBackups.remove(p.getName());

        // ★ 状态漂移兜底：身份登记为观赛者但模式已被外部改走（领地强制模式/其他插件等）
        //   → 只清掉观赛身份即可，不再动模式（避免把别人的设置覆盖掉）
        if (p.getGameMode() != GameMode.SPECTATOR) {
            if (back != null) {
                plugin.getLogger().info("[PVP观赛] 玩家 " + p.getName() + " 退出观赛"
                        + "（模式已被外部改为 " + p.getGameMode().name() + "，仅清理观赛身份）");
                p.sendMessage("§a§l[PVP观赛] §f已退出观赛身份");
            }
            return;
        }

        // ★ 兜底：备份缺失/观察者/创造 一律还原为生存（退出观赛绝不回创造，防止作弊）
        if (back == null || back == GameMode.SPECTATOR || back == GameMode.CREATIVE) {
            back = GameMode.SURVIVAL;
        }

        // 观察者常悬在高空或钻进方块里 → 切回可玩模式前先挪到可站立的空气格
        safeRecoverPosition(p);
        // 先给缓降再切模式，落地不摔死
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOW_FALLING, 20 * 8, 0, false, true));
        p.setGameMode(back);

        p.sendMessage("§a§l[PVP观赛] §f已退出观察者，恢复为" + gameModeName(back)
                + "模式（8秒缓降，地面观赛可能被战斗波及）");
        p.sendMessage("§c§l⚠ 从现在起你是生存模式：§f可以被攻击、也会攻击到别人 §c（再输 /pvp join see 可重新观赛）");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.getLogger().info("[PVP] 玩家 " + p.getName() + " 退出观赛观察者模式 → " + back.name());
    }

    /** 观察者常卡在方块内部/高空 → 切回可玩模式前，把玩家挪到最近的可站立空气格 */
    private void safeRecoverPosition(Player p) {
        Location loc = p.getLocation();
        World w = loc.getWorld();
        if (w == null) return;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        if (w.getBlockAt(x, y, z).isPassable()) return;
        for (int dy = 1; dy <= 8; dy++) {
            Block up = w.getBlockAt(x, y + dy, z);
            if (up.isPassable()) {
                p.teleport(new Location(w, x + 0.5, y + dy, z + 0.5, loc.getYaw(), loc.getPitch()));
                return;
            }
        }
    }

    private String gameModeName(GameMode gm) {
        switch (gm) {
            case CREATIVE: return "创造";
            case ADVENTURE: return "冒险";
            case SPECTATOR: return "观察者";
            default: return "生存";
        }
    }

    /** 观赛者模式漂移诊断日志限频：玩家名 → 上次打印时间戳（10秒内不重复刷屏） */
    private final Map<String, Long> spectatorDriftLogAt = new ConcurrentHashMap<>();

    /** ★ 观赛拦截留痕限频：key → 上次打印时间戳（5秒内不重复刷屏）——每次拦截都必须留下证据 */
    private final Map<String, Long> spectatorBlockLogAt = new ConcurrentHashMap<>();

    /** 观赛名册巡视日志上次打印时间戳（30秒一条，用于确认"身份到底登记上没有"） */
    private long spectatorRosterLogAt = 0L;

    /**
     * ★ 观赛身份判定（战斗保护唯一依据，2026-09-28 重构 / 同日二次加固）：
     *   - 原版观察者模式（GameMode.SPECTATOR）→ 是观赛者（任何世界）；
     *   - 已在 spectatorBackups 登记为观赛者 → 是观赛者，【不再限定 PVP 世界】
     *     （即使模式被领地强制模式/其他插件改成生存、甚至被带出竞技场也照样算，
     *       杜绝"观赛者+生存"能互殴；所有正常退出流程都会先注销登记，不会误伤）。
     * 只要判定为观赛者，攻与被攻一律拦截：不看世界、不看来源类型、不看伤害对象是不是玩家。
     */
    private boolean isProtectedSpectator(Player p) {
        if (p == null) return false;
        if (p.getGameMode() == GameMode.SPECTATOR) return true;
        return spectatorBackups.containsKey(p.getName());
    }

    /** 观赛者状态漂移诊断日志（限频：同一key 10秒最多一条），用于定位"谁把观赛者改成了生存" */
    private void logSpectatorDrift(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = spectatorDriftLogAt.get(key);
        if (last != null && now - last < 10_000L) return;
        spectatorDriftLogAt.put(key, now);
        plugin.getLogger().warning("[PVP观赛] " + msg);
    }

    /**
     * ★ 观赛拦截留痕日志（限频：同一key 5秒最多一条）。
     * 旧版只在"身份与模式不一致"时才打印，正常拦截是静默的 —— 结果"到底拦没拦住"完全无从查证。
     * 现在每一次拦截都打印，日志里能直接看到拦截证据。
     */
    private void logSpectatorBlocked(String key, String msg) {
        long now = System.currentTimeMillis();
        Long last = spectatorBlockLogAt.get(key);
        if (last != null && now - last < 5_000L) return;
        spectatorBlockLogAt.put(key, now);
        plugin.getLogger().info("[PVP观赛] " + msg);
    }

    /** 玩家观赛状态描述（日志用）：名字 / 当前模式 / 是否登记观赛身份 / 所在世界 */
    private String describePlayerState(Player p) {
        return p.getName() + "(模式=" + p.getGameMode().name()
                + ",登记=" + (spectatorBackups.containsKey(p.getName()) ? "是" : "否")
                + ",world=" + p.getWorld().getName() + ")";
    }

    /**
     * 解析伤害来源中的玩家（玩家直击 / 弹射物 shooter），非玩家来源返回 null。
     */
    private Player resolveDamageActor(org.bukkit.entity.Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof org.bukkit.entity.Projectile) {
            org.bukkit.projectiles.ProjectileSource src =
                    ((org.bukkit.entity.Projectile) damager).getShooter();
            if (src instanceof Player) return (Player) src;
        }
        return null;
    }

    /** 伤害来源描述（日志用） */
    private String describeDamager(org.bukkit.entity.Entity damager) {
        Player a = resolveDamageActor(damager);
        if (a != null) return "玩家 " + a.getName();
        return damager.getType().name();
    }

    /**
     * 每2秒巡视（2026-09-28 二次加固）：
     *   ① PVP世界里出现"观察者模式但没登记"的玩家 → 自动补登记，
     *      堵住"不是走 enterSpectator 进的观察者 → 身份没登记 → 被改成生存后彻底没人保护"的漏洞；
     *   ② 已登记观赛者但模式不是观察者 → 强制恢复观察者（PVP世界内）；
     *      在 PVP 世界之外则保留身份并告警（战斗照样拦截，避免把人硬锁在观察者里）；
     *   ③ 每30秒打印一次观赛名册，用来确认"身份到底登记上没有"。
     * 这是"观赛者以任何形式都不能攻击/被攻击"的第二道防线
     * （第一道：isProtectedSpectator 直接拦截战斗事件）。
     */
    private void enforceSpectatorModes() {
        StringBuilder roster = new StringBuilder();
        int total = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean registered = spectatorBackups.containsKey(p.getName());
            GameMode gm = p.getGameMode();

            // ① PVP世界内的"野生观察者"自动补登记（备份目标取生存，绝不把创造当还原目标）
            if (!registered && gm == GameMode.SPECTATOR && isPVPWorld(p.getWorld())) {
                spectatorBackups.put(p.getName(), GameMode.SURVIVAL);
                registered = true;
                plugin.getLogger().info("[PVP观赛] 巡视发现 " + p.getName()
                        + " 在PVP世界处于观察者模式却没登记观赛身份，已自动补登记"
                        + "（退出观赛将还原为生存）。world=" + p.getWorld().getName()
                        + "；不是本人主动观赛的，请执行 /pvp leave see 注销身份");
                p.sendMessage("§e§l[PVP观赛] §f检测到你在PVP世界处于观察者模式，已按观赛者保护"
                        + "（与他人互不伤害）。不是主动观赛请执行 §a/pvp leave see §f注销身份");
            }
            if (!registered) continue;

            total++;
            if (gm == GameMode.SPECTATOR) {
                roster.append(p.getName()).append("(观察者@").append(p.getWorld().getName()).append(") ");
                continue;
            }

            if (isPVPWorld(p.getWorld())) {
                // ② PVP世界内：强制恢复观察者
                p.setGameMode(GameMode.SPECTATOR);
                logSpectatorDrift("enforce:" + p.getName(),
                        "观赛者 " + p.getName() + " 在PVP世界内被外部改为 " + gm.name()
                                + "，已强制恢复观察者（world=" + p.getWorld().getName()
                                + "）。若反复出现请排查：领地强制游戏模式 / 其他插件的 setGameMode");
                roster.append(p.getName()).append("(强制恢复观察者,原").append(gm.name()).append(") ");
            } else {
                // ② 出了PVP世界：只告警+保留保护，不硬锁观察者（正常退出会先注销身份）
                logSpectatorDrift("outside:" + p.getName(),
                        "观赛身份残留：" + p.getName() + " 已不在PVP世界（world="
                                + p.getWorld().getName() + "）但模式是 " + gm.name()
                                + "，仍按观赛者拦截战斗；如需彻底退出请执行 /pvp leave see");
                roster.append(p.getName()).append("(身份残留@").append(p.getWorld().getName()).append(") ");
            }
        }

        // ③ 名册日志（30秒一条）
        long now = System.currentTimeMillis();
        if (total > 0 && now - spectatorRosterLogAt >= 30_000L) {
            spectatorRosterLogAt = now;
            plugin.getLogger().info("[PVP观赛] 巡视名册：观赛者 " + total + " 名 → " + roster);
        }
    }

    /**
     * 观赛者模式被改动的诊断钩子（仅记录，不拦截——真正的纠偏交给 enforceSpectatorModes）。
     * 插件自身的正常退出（exitSpectator/forceLeaveArena/onPlayerJoin重置）都会【先移除登记】
     * 再改模式，因此不会触发本日志；触发即说明是外部改动，可据此定位根因。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpectatorGameModeChanged(org.bukkit.event.player.PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (!spectatorBackups.containsKey(p.getName())) return;
        if (e.getNewGameMode() == GameMode.SPECTATOR) return;
        logSpectatorDrift("change:" + p.getName(),
                "观赛者 " + p.getName() + " 的模式被外部从观察者改为 " + e.getNewGameMode()
                        + "（world=" + p.getWorld().getName() + "）→ 2秒内将强制恢复观察者");
    }

    /**
     * ★ 观赛者战斗保护（2026-09-28 重构 + 二次加固，以"登记身份"为准，不再只看 GameMode）：
     *   ① 观赛者不能伤害任何人（含投掷物/药水等一切以其为伤害来源的判定，也含打生物）
     *   ② 任何人不能伤害观赛者（含玩家、生物、箭、TNT、摔落等一切伤害来源）
     *   ③ "观赛者" = 原版观察者模式 ∪ 已登记观赛身份（不再限定PVP世界）
     *      —— 即使模式被外部改成生存（"观赛者+生存"），攻击/被攻击照样全部拦截。
     *   ④ 每次拦截都打印留痕日志（5秒限频），"拦没拦住"必须能在日志里查到。
     *   ⑤ 拦截时若发现身份与模式不一致（漂移），另打印限频告警帮助定位根因。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorCombat(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Player victim = e.getEntity() instanceof Player ? (Player) e.getEntity() : null;
        Player attacker = resolveDamageActor(e.getDamager());

        boolean victimProtected = victim != null && isProtectedSpectator(victim);
        boolean attackerProtected = attacker != null && isProtectedSpectator(attacker);
        if (!victimProtected && !attackerProtected) return;

        // ①/② 双向一律取消——观赛者以任何形式不能攻击、也不能被攻击
        e.setCancelled(true);

        // ④ 拦截留痕：每次拦截都看得见
        if (victimProtected) {
            logSpectatorBlocked("cbv:" + victim.getName(),
                    "拦截【被攻击】观赛者 " + describePlayerState(victim)
                            + " 被 " + describeDamager(e.getDamager())
                            + " 攻击 → 已取消，cause=" + e.getCause());
        }
        if (attackerProtected && attacker != null) {
            logSpectatorBlocked("cba:" + attacker.getName(),
                    "拦截【攻击】观赛者 " + describePlayerState(attacker)
                            + " 攻击 " + (victim != null ? victim.getName()
                            : e.getEntity().getType().name())
                            + " → 已取消，cause=" + e.getCause());
        }

        // ⑤ 漂移诊断：登记为观赛者但模式不是观察者 → 说明被外部改过
        if (victimProtected && victim.getGameMode() != GameMode.SPECTATOR) {
            logSpectatorDrift("victim:" + victim.getName(),
                    "拦截：观赛者 " + victim.getName() + "（模式=" + victim.getGameMode().name()
                            + "，world=" + victim.getWorld().getName() + "）遭到 " +
                            describeDamager(e.getDamager())
                            + " 攻击，已取消（身份与模式不一致，将强制恢复观察者）");
        }
        if (attackerProtected && attacker != null && attacker.getGameMode() != GameMode.SPECTATOR) {
            logSpectatorDrift("attacker:" + attacker.getName(),
                    "拦截：观赛者 " + attacker.getName() + "（模式=" + attacker.getGameMode().name()
                            + "，world=" + attacker.getWorld().getName() + "）攻击了 "
                            + (victim != null ? victim.getName() : e.getEntity().getType().name())
                            + "，已取消（身份与模式不一致，将强制恢复观察者）");
        }
    }

    /** 观赛者被其他实体（非EntityDamageByEntity路径）直接伤害时的兜底：一律取消（以登记身份为准） */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpectatorHurt(org.bukkit.event.entity.EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player victim = (Player) e.getEntity();
        if (!isProtectedSpectator(victim)) return;
        e.setCancelled(true);
        // 拦截留痕：每次拦截都看得见
        logSpectatorBlocked("hurt:" + victim.getName(),
                "拦截【受伤】观赛者 " + describePlayerState(victim)
                        + " 受到 " + e.getCause() + " 伤害 → 已取消");
        if (victim.getGameMode() != GameMode.SPECTATOR) {
            logSpectatorDrift("hurt:" + victim.getName(),
                    "拦截：观赛者 " + victim.getName() + "（模式=" + victim.getGameMode().name()
                            + "，world=" + victim.getWorld().getName()
                            + "）受到 " + e.getCause() + " 伤害，已取消（身份与模式不一致）");
        }
    }

    /**
     * ★ 战斗保护安全网（MONITOR 最后一道闸门，2026-09-28 二次加固）：
     *  正常情况下 onSpectatorCombat / onSpectatorHurt 已在 HIGHEST 阶段取消；
     *  若同优先级更靠后、或 MONITOR 阶段的其他插件把这次取消【又解除了】，
     *  这里会发现"涉及观赛者却没有被取消"，强制补取消并打告警——
     *  这正是"观赛者还能互殴"的头号可疑根因，日志会直接把它揪出来。
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpectatorCombatSafetyNet(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.isCancelled()) return;   // 已被拦截（含我们HIGHEST阶段的取消）→ 无需兜底
        Player victim = e.getEntity() instanceof Player ? (Player) e.getEntity() : null;
        Player attacker = null;
        if (e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent) {
            attacker = resolveDamageActor(
                    ((org.bukkit.event.entity.EntityDamageByEntityEvent) e).getDamager());
        }
        boolean victimProtected = victim != null && isProtectedSpectator(victim);
        boolean attackerProtected = attacker != null && isProtectedSpectator(attacker);
        if (!victimProtected && !attackerProtected) return;

        e.setCancelled(true);
        plugin.getLogger().warning("[PVP观赛] ⚠ 观赛拦截未生效（可能被其他插件解除），已在MONITOR强制补取消："
                + "victim=" + (victim != null ? describePlayerState(victim) : "-")
                + "，attacker=" + (attacker != null ? describePlayerState(attacker) : "-")
                + "，cause=" + e.getCause()
                + "。若频繁出现，请排查在 HIGHEST/MONITOR 阶段解除取消的其他插件");
    }

// ==================== 公共查询 ====================

    public boolean isInPVPArena(String playerName) {
        return inPVPArena.contains(playerName);
    }

    /** 获取PVP世界击杀数据（供外部排行榜汇总） */
    public Map<String, Integer> getPVPWorldKills() {
        return Collections.unmodifiableMap(pvpKills);
    }

    /** 获取PVP世界死亡数据（供外部排行榜汇总） */
    public Map<String, Integer> getPVPWorldDeaths() {
        return Collections.unmodifiableMap(pvpDeaths);
    }

    public World getPVPWorld() {
        return Bukkit.getWorld(pvpWorldName);
    }

    public List<ItemStack> getPVPEquipment() {
        return Collections.unmodifiableList(pvpEquipment);
    }

    public void addPVPEquipment(ItemStack item) {
        pvpEquipment.add(item);
        saveConfig();
    }

    /** 获取当前会话ID */
    public int getCurrentSessionId() {
        return currentSessionId;
    }

    /** 获取某玩家所有会话的总战绩（从DB） */
    public Map<String, Object> getTotalArenaStats(String uuid) {
        return db.getArenaTotalStats(uuid);
    }

    /** 获取指定会话编号的信息 */
    public Map<String, Object> getArenaSessionByNumber(int number) {
        return db.getArenaSessionByNumber(number);
    }

    /** 获取某玩家在指定会话中的战绩 */
    public Map<String, Object> getArenaSessionStats(String uuid, int sessionId) {
        return db.getArenaSessionStats(uuid, sessionId);
    }

    /** 优先传送玩家到床/重生点，兜底世界出生点 */
    public Location getBedOrSpawnLocation(Player player) {
        Location bed = player.getRespawnLocation();
        if (bed != null) return bed;
        return Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    /** 重置本局公平锁定状态（世界销毁/全量重置时调用，下一局首位玩家重新定死装备） */
    private void resetMatchLock() {
        lockedEnchantOwner = null;
        lockedEnchant = null;
        lockedWeaponOwner = null;
        lockedWeaponTier = null;
        lockedArmorOwner = null;
        lockedArmorTier = null;
        selEnchant.clear();
        selShield.clear();
        selFood.clear();
        selWeaponTier.clear();
        selRangedWeapon.clear();
        selArmorTier.clear();
        equipInteracted.clear();
        permanentlyConfirmed.clear();
        savedSelections.clear();
    }

    public void clearAllPVPArenaStates() {
        inPVPArena.clear();
        equipmentConfirmed.clear();
        inventoryBackups.clear();
        spectatorBackups.clear();
        guiReopening.clear();
        guiOpenedMillis.clear();
        resetMatchLock();
        // ★ 同时清空本局所有玩家战绩(击杀/死亡)与死亡榜
        pvpKills.clear();
        pvpDeaths.clear();
        scoreEntries.clear();
        pvpScoreboard = null;
        // 取消所有超时定时器
        for (BukkitTask t : kickTimeoutTasks.values()) {
            if (t != null && !t.isCancelled()) t.cancel();
        }
        kickTimeoutTasks.clear();
    }

    // ==================== 序列化（基于 BukkitObjectStream + Base64）====================

    /**
     * 序列化整个物品数组（背包/盔甲）为 Base64 字符串
     */
    private String serializeItems(ItemStack[] items) {
        if (items == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos);
            oos.writeInt(items.length);
            for (ItemStack item : items) {
                oos.writeObject(item); // null 元素安全
            }
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("[PVP] 背包序列化失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 反序列化物品数组
     */
    private ItemStack[] deserializeItems(String data) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream ois = new BukkitObjectInputStream(bais);
            int len = ois.readInt();
            ItemStack[] items = new ItemStack[len];
            for (int i = 0; i < len; i++) {
                Object obj = ois.readObject();
                items[i] = (obj instanceof ItemStack) ? (ItemStack) obj : null;
            }
            ois.close();
            return items;
        } catch (Exception e) {
            plugin.getLogger().warning("[PVP] 背包反序列化失败: " + e.getMessage());
            return new ItemStack[0];
        }
    }

    /**
     * 序列化单个物品（副手）
     */
    private String serializeItem(ItemStack item) {
        if (item == null) return "";
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos);
            oos.writeObject(item);
            oos.close();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            plugin.getLogger().warning("[PVP] 物品序列化失败: " + e.getMessage());
            return "";
        }
    }

    /**
     * 反序列化单个物品（副手）
     */
    private ItemStack deserializeItem(String data) {
        if (data == null || data.isEmpty()) return null;
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(data));
            BukkitObjectInputStream ois = new BukkitObjectInputStream(bais);
            Object obj = ois.readObject();
            ois.close();
            return (obj instanceof ItemStack) ? (ItemStack) obj : null;
        } catch (Exception e) {
            plugin.getLogger().warning("[PVP] 物品反序列化失败: " + e.getMessage());
            return null;
        }
    }
}
