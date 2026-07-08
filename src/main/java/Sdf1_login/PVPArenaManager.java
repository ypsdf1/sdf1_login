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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // 程序内重开装备GUI（如切换档位）时，旧GUI关闭事件需忽略，避免误触发遣返
    private final Set<String> guiReopening = ConcurrentHashMap.newKeySet();

    // ★ GUI打开时间戳（仅用于历史清理兼容，当前检测逻辑已改为基于 selectedTier 状态判断）
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

    // 记分板条目缓存：玩家名 -> 侧边栏条目字符串（用于精确清除）
    private final Map<String, String> scoreEntries = new ConcurrentHashMap<>();

    // ★ PVP模板世界池：首次启动时生成并存盘，后续每次开战仅复制+加载（避免实时生成地形卡服）
    private static final int PVP_TEMPLATE_POOL = 3;
    private final List<String> pvpTemplateNames = new ArrayList<>();

    // 装备选择GUI标题
    private static final String EQUIPMENT_GUI_TITLE = "§6§l选择PVP装备";

    // ★ 装备档位定义
    public enum EquipmentTier {
        LEATHER("§e皮革套装", "轻便敏捷，适合新手", Material.LEATHER_HELMET, 1),
        CHAINMAIL("§7锁链套装", "攻守平衡，进阶选择", Material.CHAINMAIL_HELMET, 2),
        IRON("§f铁甲套装", "经典配置，可靠之选", Material.IRON_HELMET, 3),
        DIAMOND("§b钻石套装", "高端防护，强力输出", Material.DIAMOND_HELMET, 4),
        NETHERITE("§6下合金套装", "顶级装备，所向披靡", Material.NETHERITE_HELMET, 5);

        public final String displayName;
        public final String description;
        public final Material helmetMaterial;
        public final int tierId;

        EquipmentTier(String displayName, String desc, Material helmet, int id) {
            this.displayName = displayName; this.description = desc;
            this.helmetMaterial = helmet; this.tierId = id;
        }
    }

    // 玩家当前选中的装备档位
    private final Map<String, EquipmentTier> selectedTier = new ConcurrentHashMap<>();

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
     * 创建PVP竞技场世界。
     *
     * 2026-07-08 修复"创建世界卡服务器"：
     *   原实现每次用全新随机种子调用 Bukkit.createWorld，在从未生成过的种子上同步生成地形，
     *   主线程阻塞数秒导致全服卡顿。现改为：首次启动一次性生成 PVP_TEMPLATE_POOL 个模板世界并持久化；
     *   每次开战仅复制模板目录为随机新世界名再加载（仅读盘、不生成地形），主线程耗时降到毫秒级。
     */
    private World createPVPWorld() {
        long t0 = System.currentTimeMillis();

        // 确保模板世界已就绪（懒加载：首次 /pvp join 时按需生成，不拖慢启动）
        ensurePVPTemplates();
        if (pvpTemplateNames.isEmpty()) {
            plugin.getLogger().severe("[PVP] 模板世界初始化失败，无法创建竞技场");
            return null;
        }

        // 随机选一个模板（保留地形多样性）
        String tpl = pvpTemplateNames.get(worldSeedRandom.nextInt(pvpTemplateNames.size()));
        File tplFolder = new File(Bukkit.getWorldContainer(), tpl);

        // ★ 安全检查：确认模板目录确实存在（防止外部删除或卸载后目录丢失）
        if (!tplFolder.exists()) {
            plugin.getLogger().warning("[PVP] 模板 " + tpl + " 目录不存在，从列表移除并重试");
            pvpTemplateNames.remove(tpl);
            if (pvpTemplateNames.isEmpty()) {
                plugin.getLogger().severe("[PVP] 所有模板世界均不可用，无法创建竞技场");
                return null;
            }
            // 递归重试（换一个模板）
            return createPVPWorld();
        }

        // 随机新世界名（彻底绕开旧目录残留冲突）
        deleteWorldFolderIfExists(pvpWorldName);
        this.pvpWorldName = PVP_WORLD_NAME + "_" + Long.toUnsignedString(worldSeedRandom.nextLong());
        File arenaFolder = new File(Bukkit.getWorldContainer(), pvpWorldName);

        try {
            copyWorldFolder(tplFolder, arenaFolder);
        } catch (IOException e) {
            plugin.getLogger().severe("[PVP] 复制模板世界失败: " + e.getMessage());
            return null;
        }

        plugin.getLogger().info("[PVP] 已从模板 " + tpl + " 复制为新世界 " + pvpWorldName + "（仅加载，不实时生成地形）");

        WorldCreator creator = new WorldCreator(pvpWorldName);
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.NORMAL);
        World pvpWorld = Bukkit.createWorld(creator);
        long dt = System.currentTimeMillis() - t0;
        if (pvpWorld == null) {
            plugin.getLogger().severe("[PVP] 无法创建PVP竞技场世界! (Bukkit.createWorld返回null, 耗时" + dt + "ms)");
            return null;
        }

        reapplyWorldRules(pvpWorld);

        plugin.getLogger().info("[PVP] 已加载PVP竞技场世界(模板副本) 耗时=" + dt + "ms");
        return pvpWorld;
    }

    /**
     * 确保 PVP 模板世界池已生成并持久化到磁盘。
     * 首次启动（磁盘无模板目录）时同步生成 PVP_TEMPLATE_POOL 个地形各异的模板世界，
     * 生成完毕后立即卸载（仅保留磁盘目录），后续开战直接复制，不卡服。
     * 非首次（目录已存在）则仅校验，几乎零耗时。
     */
    public void ensurePVPTemplates() {
        if (!pvpTemplateNames.isEmpty()) return;
        for (int i = 0; i < PVP_TEMPLATE_POOL; i++) {
            String tpl = "pvp_tpl_" + i;
            File folder = new File(Bukkit.getWorldContainer(), tpl);
            if (folder.exists()) {
                pvpTemplateNames.add(tpl); // ★ 仅在目录确认存在时才加入可用列表
                plugin.getLogger().info("[PVP] 模板世界 " + tpl + " 已存在，跳过生成");
                continue;
            }
            long seed = worldSeedRandom.nextLong();
            WorldCreator c = new WorldCreator(tpl);
            c.environment(World.Environment.NORMAL);
            c.type(WorldType.NORMAL);
            c.seed(seed);
            plugin.getLogger().info("[PVP] 首次生成模板世界 " + tpl + " 种子=" + seed + "（懒加载，/pvp join 时触发）");
            World w = Bukkit.createWorld(c);
            if (w == null) {
                plugin.getLogger().severe("[PVP] 模板世界 " + tpl + " 生成失败，该模板将不可用");
                continue; // ★ 不加入 pvpTemplateNames，不会被选中复制
            }
            preGenerateSpawnChunksSync(w, 4);
            reapplyWorldRules(w);
            Bukkit.unloadWorld(w, true);
            // ★ 卸载后再次确认目录仍存在（某些配置下 unloadWorld 可能删除目录）
            if (folder.exists()) {
                pvpTemplateNames.add(tpl);
                plugin.getLogger().info("[PVP] 模板世界 " + tpl + " 已生成并存盘");
            } else {
                plugin.getLogger().severe("[PVP] 模板世界 " + tpl + " 卸载后目录消失，该模板将不可用");
            }
        }
    }

    /**
     * 同步预生成出生点周边区块（用于模板世界一次性生成，启动阶段调用，可接受短暂阻塞）。
     */
    private void preGenerateSpawnChunksSync(World world, int radius) {
        int cx = world.getSpawnLocation().getBlockX() >> 4;
        int cz = world.getSpawnLocation().getBlockZ() >> 4;
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                try {
                    world.loadChunk(cx + dx, cz + dz, true);
                    n++;
                } catch (Exception e) {
                    plugin.getLogger().warning("[PVP] 同步生成模板区块失败: " + (cx + dx) + "," + (cz + dz));
                }
            }
        }
        plugin.getLogger().info("[PVP] 模板世界已同步生成 " + n + " 个区块");
    }

    /**
     * 递归复制世界目录（用于把模板世界复制为新的竞技场世界）。
     * 跳过 session.lock（服务器运行锁）与 uid.dat（让新世界重新生成 uid），避免复制后加载冲突。
     */
    private void copyWorldFolder(File src, File dst) throws IOException {
        if (dst.exists()) deleteWorldFolder(dst);
        copyDirRecursively(src, dst);
    }

    /** 递归复制目录（不使用 nio Files.walkFileTree，避免触发 JDK26 javac 类型推断内部崩溃） */
    private void copyDirRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录: " + dst.getAbsolutePath());
            }
            File[] children = src.listFiles();
            if (children == null) return;
            for (File child : children) {
                copyDirRecursively(child, new File(dst, child.getName()));
            }
        } else {
            String name = src.getName();
            if (name.equals("session.lock") || name.equals("uid.dat")) {
                return; // 跳过服务器运行锁与 uid.dat，避免复制后加载冲突
            }
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建目录: " + parent.getAbsolutePath());
            }
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
        }
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
        List<String> sorted = new ArrayList<String>(inPVPArena.size());
        sorted.addAll(inPVPArena);
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

    /** 玩家离开PVP世界时清除其战绩并恢复默认记分板 */
    private void cleanupPlayerStats(Player p) {
        if (p == null) return;
        String name = p.getName();
        pvpKills.remove(name);
        pvpDeaths.remove(name);
        String entry = scoreEntries.remove(name);
        if (pvpScoreboard != null && entry != null) {
            pvpScoreboard.resetScores(entry);
        }
        if (p.isOnline()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
        // 无人则销毁本局记分板，下次重建
        if (inPVPArena.isEmpty() && pvpScoreboard != null) {
            pvpScoreboard = null;
        } else {
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
    private void deleteWorldFolder(File folder) {
        if (folder == null || !folder.exists()) return;
        File[] children = folder.listFiles();
        if (children != null) {
            for (File c : children) deleteWorldFolder(c);
        }
        folder.delete();
    }

    /**
     * 将旧世界目录"重命名"移入回收站（而非直接删除）。
     * ★ 原因：Windows 上 unloadWorld 后 region 文件可能仍被 OS 锁定，
     *   File.delete() 会静默失败导致旧虚空世界残留；而 File.renameTo() 移动目录在
     *   文件锁定时仍可靠成功。移走后 createWorld() 因目录不存在而生成全新 NORMAL 主世界地形。
     */
    private void moveWorldToTrash(File folder) {
        if (folder == null || !folder.exists()) return;
        plugin.getLogger().info("[PVP] moveWorldToTrash: 目标=" + folder.getAbsolutePath());
        File parent = folder.getParentFile();
        String base = folder.getName();
        long ts = System.currentTimeMillis();
        File trash = new File(parent, base + "_trash_" + ts);
        int i = 0;
        while (trash.exists()) {
            i++;
            trash = new File(parent, base + "_trash_" + ts + "_" + i);
        }
        if (folder.renameTo(trash)) {
            plugin.getLogger().info("[PVP] 旧世界已移入回收站(避免Windows删除锁): " + trash.getName());
        } else {
            // 极少数情况下重命名也失败，退回递归删除
            plugin.getLogger().warning("[PVP] 旧世界重命名失败，退回递归删除: " + base);
            deleteWorldFolder(folder);
        }
    }

    /**
     * 清理历史回收站目录（pvp_arena_trash_*），避免磁盘无限增长。
     * best-effort，失败不阻断主流程。
     */
    private void cleanupOldTrashWorlds() {
        File container = Bukkit.getWorldContainer();
        if (container == null || !container.isDirectory()) return;
        File[] list = container.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory() && f.getName().startsWith(PVP_WORLD_NAME + "_") && f.getName().contains("_trash_")) {
                deleteWorldFolder(f);
            }
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
     */
    private void scheduleWorldDeletionIfEmpty() {
        World w = Bukkit.getWorld(pvpWorldName);
        if (w == null) return;
        // 仍有玩家滞留于 PVP 世界内 → 保留世界（多人保护，后续进入直接加入战斗）
        if (!w.getPlayers().isEmpty()) {
            plugin.getLogger().info("[PVP] PVP世界仍有玩家在场，保留世界（后续进入直接加入战斗）");
            return;
        }
        plugin.getLogger().info("[PVP] PVP世界已无玩家，立即删除世界以释放资源（下次进入随机重生）");
        deletePVPWorld(w);
    }

    /**
     * 卸载并删除PVP世界（含磁盘目录），下次进入将随机重新生成地形
     */
    private void deletePVPWorld(World world) {
        if (world == null) return;
        // 兜底：确保世界内无玩家（理论上冷却结束时已无人）
        for (Player p : new ArrayList<>(world.getPlayers())) {
            World main = Bukkit.getWorlds().get(0);
            p.teleport(main.getSpawnLocation());
            p.sendMessage("§e[PVP] 竞技场已关闭，你被传回主世界");
        }
        Bukkit.unloadWorld(world, false); // false=不保存（即将删除，避免残留破坏的地形）
        // ★ 同样用重命名移走，避免 Windows 删除锁导致旧世界残留
        moveWorldToTrash(new File(Bukkit.getWorldContainer(), pvpWorldName));
        plugin.getLogger().info("[PVP] PVP世界已删除，下次进入将随机重新生成地形");
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
        File folder = new File(Bukkit.getWorldContainer(), name);
        if (!folder.exists()) return;
        plugin.getLogger().info("[PVP] best-effort 清理上一次世界目录: " + name);
        moveWorldToTrash(folder);
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
        // 方案：检查玩家是否已选择了装备档位（selectedTier 有值）
        //   - 已选档位 → 玩家在浏览/切换，自动重开 GUI（不遣返）
        //   - 从未选择 → 玩家直接跳过了（ESC/未交互），执行遣返
        //
        // 同时配合超时安全网（onPlayerEnterPVPWorld 中注册60秒定时器），
        // 防止玩家一直开着 GUI 不操作也不退出

        if (selectedTier.containsKey(player.getName())) {
            // 玩家已选择了档位 → 自动重开 GUI 让其继续操作或点确认
            plugin.getLogger().info("[PVP] 玩家 " + player.getName()
                    + " 已选择档位但未确认就关了GUI，自动重开（selectedTier="
                    + selectedTier.get(player.getName()) + "）");
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

        // 清理状态并取消超时定时器
        inPVPArena.remove(player.getName());
        cancelKickTimeout(player.getName());

        // 延迟一帧传回主世界（避免与关闭事件冲突）
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                World main = Bukkit.getWorlds().get(0);
                player.teleport(main.getSpawnLocation());
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

        // ★ 关键变更：先选装备，不急着备份清空
        openEquipmentSelection(player);

        // ★ 注册超时安全网：60秒内必须确认，否则自动遣返
        scheduleKickTimeout(playerName);

        player.sendMessage("§a§l欢迎来到PVP竞技场!");
        player.sendMessage("§7请先选择你的装备套装，确认后将备份你的原背包");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 进入PVP竞技场（等待选装备）");
    }

    /**
     * 执行"确认装备"步骤：备份原背包 → 清空 → 发放PVP装备
     * 由装备GUI的确认按钮和一键装备按钮触发
     */
    public void confirmEquipment(Player player) {
        String playerName = player.getName();
        if (!inPVPArena.contains(playerName)) return;
        if (equipmentConfirmed.contains(playerName)) return;

        // ★ 第一步：备份原背包（在清空之前！）
        backupInventory(player);

        // ★ 第二步：清空背包
        clearInventory(player);

        // ★ 第三步：发放PVP装备
        equipFullSet(player);

        // 标记已完成装备确认 + 取消超时定时器
        equipmentConfirmed.add(playerName);
        cancelKickTimeout(playerName);

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
                inPVPArena.remove(playerName);
                cleanupPlayerStats(p);
                guiReopening.remove(playerName);
                guiOpenedMillis.remove(playerName);
                graceReopenCount.remove(playerName);
                selectedTier.remove(playerName);
                if (p != null && p.isOnline()) {
                    p.closeInventory();
                    World main = Bukkit.getWorlds().get(0);
                    p.teleport(main.getSpawnLocation());
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

        // 清理所有状态（含取消超时定时器）
        inPVPArena.remove(playerName);
        equipmentConfirmed.remove(playerName);
        inventoryBackups.remove(playerName);
        guiReopening.remove(playerName);
        guiOpenedMillis.remove(playerName);
        graceReopenCount.remove(playerName);
        selectedTier.remove(playerName);
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
     * 玩家死亡事件 — 死亡后强制离开PVP
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!inPVPArena.contains(player.getName())) return;
        // paper-api 1.21.4: getKiller() 已移除，改用 DamageSource.getCausingEntity()
        org.bukkit.entity.Entity killer = event.getDamageSource().getCausingEntity();
        if (killer instanceof Player && inPVPArena.contains(killer.getName())) {
            pvpKills.put(killer.getName(), pvpKills.getOrDefault(killer.getName(), 0) + 1);
            ((Player) killer).sendMessage("\u00a7a\u00a7l击杀 " + player.getName() + "\u00a7a 当前击杀: " + pvpKills.get(killer.getName()));
        }
        pvpDeaths.put(player.getName(), pvpDeaths.getOrDefault(player.getName(), 0) + 1);
        refreshPVPScoreboard();

        // 死亡时保留经验（避免丢失）
        event.setKeepLevel(true);
        event.setKeepInventory(true); // 不掉落PVP装备（因为要回收）

        // 延迟1秒后强制离开（等死亡事件处理完）
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    forceLeaveArena(player, false);
                    // 传回主世界
                    World main = Bukkit.getWorlds().get(0);
                    player.teleport(main.getSpawnLocation());
                    player.sendMessage("§c§l你在PVP中阵亡，已自动退出并还原背包");
                }
            }
        }.runTaskLater(plugin, 20L); // 1秒后
    }

    /**
     * 玩家加入事件 — 如果上次在PVP中断线，还原背包
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
            }, 2L);
        }

        // 防止玩家登录时就在PVP世界（异常情况兜底）
        if (player.getWorld().getName().equals(pvpWorldName)) {
            inPVPArena.add(player.getName());
            registerPlayerStats(player);
            // 给个提示但不自动操作，让玩家自己决定
            player.sendMessage("§e[PVP] 你当前在PVP竞技场世界，输入 /pvp leave 离开");
        }
    }

    /**
     * 玩家退出事件 — 断线处理
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (inPVPArena.contains(player.getName())) {
            // 断线前最后备份一次当前状态
            if (equipmentConfirmed.contains(player.getName())) {
                // 已装备确认过 → 当前身上是PVP装备，需要用之前的备份来还原
                // 备份已经在confirmEquipment时做过，这里只需标记
                forceLeaveArena(player, true); // isDisconnect=true
            } else {
                // 还没确认装备 → 身上还是原背包，不需要特殊处理
                inPVPArena.remove(player.getName());
                equipmentConfirmed.remove(player.getName());
                guiReopening.remove(player.getName());
                guiOpenedMillis.remove(player.getName());
                graceReopenCount.remove(player.getName());
                selectedTier.remove(player.getName());
                cancelKickTimeout(player.getName());
                cleanupPlayerStats(player);
            }
            // 断线后若世界已无玩家（含本玩家），立即删除世界（"没人了就删除"）
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
        Material type = item.getType();
        String name = type.name();

        // 所有档位可能用到的盔甲材料
        boolean isArmor = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");

        // PVP 武器和消耗品
        boolean isWeapon = (name.contains("SWORD") || name.contains("BOW") || name.equals("CROSSBOW"));
        boolean isAmmo = (type == Material.ARROW || type == Material.SPECTRAL_ARROW);
        boolean isConsumable = (type == Material.GOLDEN_APPLE || type == Material.POTION
                || type == Material.ENDER_PEARL);

        return isArmor || isWeapon || isAmmo || isConsumable;
    }

    /**
     * 打开装备选择GUI
     */
    public void openEquipmentSelection(Player player) {
        // ★ 记录GUI打开时间（宽限期机制：此后短时间内内的关闭事件不触发遣返）
        guiOpenedMillis.put(player.getName(), System.currentTimeMillis());
        graceReopenCount.put(player.getName(), 0);

        Inventory gui = Bukkit.createInventory(null, 54, EQUIPMENT_GUI_TITLE);

        // ★ 顶部：当前选中档位的装备预览（第0-8格显示当前档位的装备）
        EquipmentTier tier = selectedTier.getOrDefault(player.getName(), EquipmentTier.IRON);
        List<ItemStack> tierItems = buildTierEquipment(tier);
        for (int i = 0; i < tierItems.size() && i < 9; i++) {
            gui.setItem(i, tierItems.get(i));
        }
        // 填充空位
        for (int i = tierItems.size(); i < 9; i++) {
            gui.setItem(i, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        }

        // ★ 中间行：5个装备档位按钮（第18-26格，每行3个）
        int[] tierSlots = {19, 21, 23, 25, 27};
        Material[] tierIcons = {Material.LEATHER, Material.CHAINMAIL_CHESTPLATE,
                Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE};
        String[] tierLore = {"§7速度+ | 防御低", "§7平衡型", "§7经典配置",
                "§7高防高伤", "§7最强装备"};

        for (int t = 0; t < EquipmentTier.values().length; t++) {
            EquipmentTier et = EquipmentTier.values()[t];
            ItemStack tierBtn = new ItemStack(tierIcons[t]);
            ItemMeta tm = tierBtn.getItemMeta();
            if (tm != null) {
                tm.setDisplayName(et.displayName);
                boolean isSelected = (tier == et);
                List<String> lore = new ArrayList<>();
                lore.add("§7" + et.description);
                lore.add("");
                lore.add(tierLore[t]);
                lore.add("");
                if (isSelected) {
                    lore.add("§a✓ 当前选中");
                    // 给选中的加个绿色玻璃框效果
                    tierBtn = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
                    tm = tierBtn.getItemMeta();
                    if (tm != null) {
                        tm.setDisplayName(et.displayName);
                        tm.setLore(lore);
                        tierBtn.setItemMeta(tm);
                    }
                } else {
                    lore.add("§e点击选择此档位");
                    tm.setLore(lore);
                    tierBtn.setItemMeta(tm);
                }
            }
            gui.setItem(tierSlots[t], tierBtn);
        }

        // 分隔线
        for (int i = 36; i < 45; i++) {
            if (i == 40) continue;
            gui.setItem(i, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
        }

        // 当前档位信息
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoM = info.getItemMeta();
        if (infoM != null) {
            infoM.setDisplayName("§6§l当前: " + tier.displayName);
            infoM.setLore(Arrays.asList(
                    "§7" + tier.description,
                    "",
                    "§e点击上方档位切换",
                    "§a点击下方确认开始战斗"));
            info.setItemMeta(infoM);
        }
        gui.setItem(40, info);

        // 确认选择按钮
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§a§l确认选择（将备份你的原背包）");
            confirmMeta.setLore(Arrays.asList(
                    "§7确认后将发放 " + tier.displayName,
                    "§7并备份你当前的背包"));
            confirm.setItemMeta(confirmMeta);
        }
        gui.setItem(49, confirm);

        // 一键装备全套按钮
        ItemStack selectAll = new ItemStack(Material.NETHER_STAR);
        ItemMeta selectAllMeta = selectAll.getItemMeta();
        if (selectAllMeta != null) {
            selectAllMeta.setDisplayName("§b§l一键装备全套");
            selectAllMeta.setLore(Arrays.asList("§7使用 " + tier.displayName + " §7快速开始"));
            selectAll.setItemMeta(selectAllMeta);
        }
        gui.setItem(53, selectAll);

        player.openInventory(gui);
    }

    /**
     * 根据档位构建装备列表
     */
    private List<ItemStack> buildTierEquipment(EquipmentTier tier) {
        List<ItemStack> items = new ArrayList<>();

        switch (tier) {
            case LEATHER:
                items.add(new ItemStack(Material.LEATHER_HELMET));
                items.add(new ItemStack(Material.LEATHER_CHESTPLATE));
                items.add(new ItemStack(Material.LEATHER_LEGGINGS));
                items.add(new ItemStack(Material.LEATHER_BOOTS));
                items.add(makeWeapon(Material.STONE_SWORD, "§7PVP石剑", false));
                items.add(new ItemStack(Material.BOW));
                items.add(new ItemStack(Material.ARROW, 32));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 4));
                break;

            case CHAINMAIL:
                items.add(new ItemStack(Material.CHAINMAIL_HELMET));
                items.add(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                items.add(new ItemStack(Material.CHAINMAIL_LEGGINGS));
                items.add(new ItemStack(Material.CHAINMAIL_BOOTS));
                items.add(makeWeapon(Material.IRON_SWORD, "§7PVP铁剑", false));
                items.add(new ItemStack(Material.BOW));
                items.add(new ItemStack(Material.ARROW, 48));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 6));
                break;

            case IRON:
                items.add(new ItemStack(Material.IRON_HELMET));
                items.add(new ItemStack(Material.IRON_CHESTPLATE));
                items.add(new ItemStack(Material.IRON_LEGGINGS));
                items.add(new ItemStack(Material.IRON_BOOTS));
                items.add(makeWeapon(Material.DIAMOND_SWORD, "§b§lPVP钻石剑", false));
                items.add(new ItemStack(Material.BOW));
                items.add(new ItemStack(Material.ARROW, 64));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 8));
                items.add(makePotion(3));
                break;

            case DIAMOND:
                items.add(new ItemStack(Material.DIAMOND_HELMET));
                items.add(new ItemStack(Material.DIAMOND_CHESTPLATE));
                items.add(new ItemStack(Material.DIAMOND_LEGGINGS));
                items.add(new ItemStack(Material.DIAMOND_BOOTS));
                items.add(makeWeapon(Material.DIAMOND_SWORD, "§b§lPVP钻石剑", true));
                items.add(new ItemStack(Material.CROSSBOW));
                items.add(new ItemStack(Material.ARROW, 64));
                items.add(new ItemStack(Material.SPECTRAL_ARROW, 8));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 10));
                items.add(makePotion(5));
                break;

            case NETHERITE:
                items.add(new ItemStack(Material.NETHERITE_HELMET));
                items.add(new ItemStack(Material.NETHERITE_CHESTPLATE));
                items.add(new ItemStack(Material.NETHERITE_LEGGINGS));
                items.add(new ItemStack(Material.NETHERITE_BOOTS));
                items.add(makeWeapon(Material.NETHERITE_SWORD, "§6§lPVP下合金剑", true));
                items.add(new ItemStack(Material.CROSSBOW));
                items.add(new ItemStack(Material.ARROW, 64));
                items.add(new ItemStack(Material.SPECTRAL_ARROW, 16));
                items.add(new ItemStack(Material.GOLDEN_APPLE, 12));
                items.add(makePotion(8));
                items.add(new ItemStack(Material.ENDER_PEARL, 4));
                break;
        }

        return items;
    }

    private ItemStack makeWeapon(Material mat, String name, boolean enchant) {
        ItemStack sword = new ItemStack(mat);
        ItemMeta meta = sword.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (enchant) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 4, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 3, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.MENDING, 1, true);
            }
            sword.setItemMeta(meta);
        }
        return sword;
    }

    private ItemStack makePotion(int count) {
        ItemStack potion = new ItemStack(Material.POTION, count);
        ItemMeta meta = potion.getItemMeta();
        if (meta instanceof PotionMeta) {
            PotionMeta pMeta = (PotionMeta) meta;
            pMeta.setDisplayName("§a§l瞬间治疗药水");
            // ★ 写入真实药水效果 NBT：瞬间治疗（数量越多档位越高效果越强），
            //   否则客户端只显示"药水"却没有任何效果（玩家反馈"药水无效果"）。
            pMeta.addCustomEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.INSTANT_HEALTH,
                    1,                       // 瞬间效果，duration 无意义
                    count >= 8 ? 1 : 0,      // 下合金档(8瓶)→治疗II，其余治疗I
                    false, false), true);
            pMeta.setBasePotionType(org.bukkit.potion.PotionType.HEALING);
            potion.setItemMeta(pMeta);
        }
        return potion;
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

        // ★ 档位选择按钮（19,21,23,25,27）
        int[] tierSlots = {19, 21, 23, 25, 27};
        for (int t = 0; t < tierSlots.length; t++) {
            if (slot == tierSlots[t]) {
                EquipmentTier chosen = EquipmentTier.values()[t];
                selectedTier.put(player.getName(), chosen);
                // 标记：接下来重开GUI导致的旧GUI关闭事件需忽略（避免误触发遣返）
                guiReopening.add(player.getName());
                // 刷新 GUI 显示新档位的装备预览
                openEquipmentSelection(player);
                player.sendMessage("§a已选择: " + chosen.displayName);
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                return true;
            }
        }

        // 确认选择按钮 → 备份+清空+发装备（使用当前选中的档位）
        if (slot == 49) {
            // ★ 关键修复：标记即将关闭GUI，避免 onInventoryClose 将"确认关闭"误判为
            //   "未选装备/ESC关闭"而遣返。（Paper 的 closeInventory 会延迟到下一tick触发
            //   关闭事件，届时 confirmEquipment 已同步执行完，selectedTier 已被移除，
            //   若不标记会落入 else 遣返分支）
            guiReopening.add(player.getName());
            player.closeInventory();
            confirmEquipment(player);
            return true;
        }

        // 一键装备全套 → 同样触发确认流程
        if (slot == 53) {
            guiReopening.add(player.getName());
            player.closeInventory();
            confirmEquipment(player);
            return true;
        }

        // 预览区物品（只看不动）
        if (slot >= 0 && slot < 9) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return true;
        }

        return false;
    }

    /**
     * 装备全套PVP装备到玩家背包（根据玩家选择的档位）
     */
    private void equipFullSet(Player player) {
        EquipmentTier tier = selectedTier.getOrDefault(player.getName(), EquipmentTier.IRON);
        List<ItemStack> equipment = buildTierEquipment(tier);

        for (ItemStack item : equipment) {
            ItemStack clone = item.clone();
            Material type = clone.getType();
            String typeName = type.name();

            // 头盔
            if (typeName.endsWith("_HELMET")) {
                player.getInventory().setHelmet(clone);
            }
            // 胸甲
            else if (typeName.endsWith("_CHESTPLATE")) {
                player.getInventory().setChestplate(clone);
            }
            // 护腿
            else if (typeName.endsWith("_LEGGINGS")) {
                player.getInventory().setLeggings(clone);
            }
            // 靴子
            else if (typeName.endsWith("_BOOTS")) {
                player.getInventory().setBoots(clone);
            }
            // 其他物品（武器、弓箭、药水等）放入背包
            else {
                player.getInventory().addItem(clone);
            }
        }

        // 清除档位选择记录（已使用）
        selectedTier.remove(player.getName());
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

        // ★ 关键修复（2026-07-08 第二版）：/pvp join 卡死 + WorldInitEvent 异步崩溃
        //
        // 第一版曾把 createPVPWorld() 放进异步线程，但 Bukkit.createWorld() 内部会【同步】触发
        // WorldInitEvent / WorldLoadEvent，这些事件只允许在主线程触发，异步调用直接抛
        // java.lang.IllegalStateException: WorldInitEvent may only be triggered synchronously。
        //
        // 因此 createPVPWorld() 必须在【主线程】执行。卡死的根因并非 createWorld 本身，而是
        // setKeepSpawnInMemory(true) 强制同步加载出生点区块（~8s）。现改为
        // WorldCreator.keepSpawnInMemory(false)（见 createPVPWorld），createWorld 仅做世界初始化、
        // 不加载出生点区块（毫秒级），主线程不会被长时间占用，Watchdog 卡死彻底消除。
        // 出生点周边区块由 preGenerateSpawnChunks 异步预生成，玩家传送时按需加载单个区块（极快）。
        if (pvpWorld != null) {
            plugin.getLogger().info("[PVP] PVP世界当前无人，主线程卸载旧世界并重新生成全新主世界");
            deletePVPWorld(pvpWorld);
        } else {
            File folder = new File(Bukkit.getWorldContainer(), pvpWorldName);
            if (folder.exists()) {
                plugin.getLogger().info("[PVP] 检测到磁盘残留PVP世界目录（无人在场），移入回收站后重新生成");
                moveWorldToTrash(folder);
            }
        }

        player.sendMessage("§a§l正在生成PVP竞技场世界，请稍候...");
        pvpWorld = createPVPWorld();
        if (pvpWorld == null) {
            player.sendMessage("§c§l[PVP] 竞技场世界加载失败，请联系管理员");
            return;
        }
        finalizeJoin(player, pvpWorld);
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
        // 传回主世界（触发 PlayerChangedWorldEvent → forceLeaveArena）
        World main = Bukkit.getWorlds().get(0);
        player.teleport(main.getSpawnLocation());
        player.sendMessage("§a§l正在离开PVP竞技场...");
    }

    // ==================== 公共查询 ====================

    public boolean isInPVPArena(String playerName) {
        return inPVPArena.contains(playerName);
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

    public void clearAllPVPArenaStates() {
        inPVPArena.clear();
        equipmentConfirmed.clear();
        inventoryBackups.clear();
        guiReopening.clear();
        guiOpenedMillis.clear();
        selectedTier.clear();
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
