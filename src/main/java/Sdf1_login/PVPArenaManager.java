package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
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

    // PVP世界空场冷却时间（毫秒）：所有玩家退场后世界继续保留此时长，
    // 冷却内若有玩家重新进入则取消删除；冷却结束且仍无人则删除世界，下次进入随机重新生成地形。
    // 目的：①战斗破坏的地形/被砍的树随世界删除而完全复原；②每次地形随机刷新，防止背图玩家单方面碾压。
    private static final long PVP_WORLD_IDLE_DELETE_MS = 5 * 60 * 1000L; // 5分钟（可按需调整）

    // 玩家在PVP世界中的状态
    private final Set<String> inPVPArena = ConcurrentHashMap.newKeySet();

    // 已完成装备选择的玩家（备份+清空+发装备已执行完毕）
    private final Set<String> equipmentConfirmed = ConcurrentHashMap.newKeySet();

    // 玩家背包备份缓存 (玩家名 -> 备份数据)
    private final Map<String, InventoryBackup> inventoryBackups = new ConcurrentHashMap<>();

    // 待执行的"空场冷却删除世界"定时任务（有人重新进入时取消）
    private BukkitTask pendingDeleteTask = null;

    // 随机地形种子生成器（每次创建世界用新种子 → 地形不同）
    private final Random worldSeedRandom = new Random();

    // PVP装备列表 (管理员可配置)
    private final List<ItemStack> pvpEquipment = new ArrayList<>();

    // 装备选择GUI标题
    private static final String EQUIPMENT_GUI_TITLE = "§6§l选择PVP装备";

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

        // 药水
        ItemStack potion = new ItemStack(Material.POTION, 3);
        ItemMeta potionMeta = potion.getItemMeta();
        if (potionMeta != null) {
            potionMeta.setDisplayName("§a§l瞬间治疗药水");
            potion.setItemMeta(potionMeta);
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
     * 检查并确保PVP世界可用（按需创建，随机地形）。
     *
     * 生命周期模型：PVP世界是"一次性随机竞技场"——
     *   • 有人要进入且世界不存在时，删除磁盘残留（含旧版小平台）后随机重新生成；
     *   • 玩家全部退场并经过5分钟空场冷却后，世界被删除；
     *   • 下次进入再次随机生成 → 地形每次不同，破坏/砍树随删除完全复原，杜绝背图。
     */
    public void ensurePVPWorldExists() {
        // 有人要进来了：取消任何待执行的删除任务（保留当前这一局地形）
        cancelPendingDeletion();

        World pvpWorld = Bukkit.getWorld(PVP_WORLD_NAME);
        if (pvpWorld != null && !pvpWorld.getPlayers().isEmpty()) {
            // 世界中仍有其他玩家在战斗 → 复用同一局地形，一起竞技（不重建）
            plugin.getLogger().info("[PVP] PVP世界已有玩家在场，复用当前地形（不重建）");
            return;
        }

        // 世界不存在，或世界存在但已无人活动 → 重新随机生成全新地形。
        // 这样满足"每次空场进入都随机生成地形"；同时，服务器若自动从磁盘加载了
        // 旧版小平台（残留世界），这里会先卸载并删除磁盘目录，用随机地形替换它。
        if (pvpWorld != null) {
            plugin.getLogger().info("[PVP] 检测到PVP世界当前无人，卸载旧世界以重新随机生成...");
            Bukkit.unloadWorld(pvpWorld, false); // false=不保存，直接丢弃旧地形（含旧版小平台）
            deleteWorldFolder(new File(Bukkit.getWorldContainer(), PVP_WORLD_NAME));
        }

        // 兜底：确保磁盘残留目录被清理（含旧版小平台），再随机重新生成
        File worldDir = new File(Bukkit.getWorldContainer(), PVP_WORLD_NAME);
        if (worldDir.exists() && worldDir.isDirectory()) {
            plugin.getLogger().info("[PVP] 清理磁盘残留的PVP世界目录，准备随机重新生成...");
            deleteWorldFolder(worldDir);
        }

        // 创建全新随机地形世界
        createPVPWorld();
    }

    /**
     * 创建全新PVP世界（每次随机地形）
     */
    private void createPVPWorld() {
        // ★ 每次使用随机种子 → 每次地形都不同，杜绝背图碾压
        long seed = worldSeedRandom.nextLong();
        WorldCreator creator = new WorldCreator(PVP_WORLD_NAME);
        creator.environment(World.Environment.NORMAL);
        creator.type(WorldType.NORMAL);
        creator.seed(seed); // 随机种子：不同seed = 不同生物群系与地形

        World pvpWorld = creator.createWorld();
        if (pvpWorld == null) {
            plugin.getLogger().severe("[PVP] 无法创建PVP竞技场世界!");
            return;
        }

        // 设置PVP世界规则
        pvpWorld.setPVP(true);
        pvpWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        pvpWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        pvpWorld.setTime(6000); // 中午

        // ★ 关键：先强制同步生成出生点周边区块（半径3），确保地形已存在后再找安全出生点
        preGenerateSpawnChunks(pvpWorld, 3);

        // 设置出生点（在正常地形中找安全位置）
        Location spawnLoc = findSafeSpawn(pvpWorld);
        if (spawnLoc == null) {
            // 半径3内仍是虚空（极端情况），扩大到半径6再找一次
            preGenerateSpawnChunks(pvpWorld, 6);
            spawnLoc = findSafeSpawn(pvpWorld);
        }

        if (spawnLoc != null) {
            pvpWorld.setSpawnLocation(spawnLoc.getBlockX(), spawnLoc.getBlockY(), spawnLoc.getBlockZ());
            plugin.getLogger().info("[PVP] 已创建PVP竞技场世界(随机地形 seed=" + seed + "): " + PVP_WORLD_NAME
                    + " 出生点=(" + spawnLoc.getBlockX() + "," + spawnLoc.getBlockY() + "," + spawnLoc.getBlockZ() + ")");
        } else {
            // 真正的虚空世界（很可能是服务端为 pvp_arena 强制了 void/flat 生成器）：
            // 退而求其次铺保底平台，但必须告警，因为这种世界不是"完整地形"。
            pvpWorld.setSpawnLocation(0, 100, 0);
            buildFallbackPlatform(pvpWorld);
            plugin.getLogger().severe("[PVP] 警告：随机世界内找不到任何陆地（疑似服务端为 "
                    + PVP_WORLD_NAME + " 强制了 void/flat 生成器），已退化为小平台。"
                    + " 若是此原因，需在服务端 bukkit.yml / 世界管理插件中为该世界设置正常生成器。");
        }
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
     * 取消待执行的"空场冷却删除世界"任务（有人重新进入时调用）
     */
    private void cancelPendingDeletion() {
        if (pendingDeleteTask != null) {
            pendingDeleteTask.cancel();
            pendingDeleteTask = null;
        }
    }

    /**
     * 若PVP世界已无人活动，启动5分钟空场冷却计时；冷却结束且仍无人则删除世界。
     * 冷却期内若有玩家重新进入（ensurePVPWorldExists 会 cancelPendingDeletion），则取消删除。
     */
    private void scheduleWorldDeletionIfEmpty() {
        // 仍有活跃PVP玩家则不触发删除
        if (!inPVPArena.isEmpty()) return;

        cancelPendingDeletion();
        pendingDeleteTask = new BukkitRunnable() {
            @Override
            public void run() {
                World w = Bukkit.getWorld(PVP_WORLD_NAME);
                // 冷却结束时再次确认：仍有活跃玩家或仍有玩家滞留世界中则取消删除
                if (w == null || !inPVPArena.isEmpty() || !w.getPlayers().isEmpty()) {
                    pendingDeleteTask = null;
                    return;
                }
                plugin.getLogger().info("[PVP] PVP世界空场冷却结束，删除世界以释放资源...");
                deletePVPWorld(w);
                pendingDeleteTask = null;
            }
        }.runTaskLater(plugin, PVP_WORLD_IDLE_DELETE_MS / 50L); // ticks = ms / 50
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
        deleteWorldFolder(new File(Bukkit.getWorldContainer(), PVP_WORLD_NAME));
        plugin.getLogger().info("[PVP] PVP世界已删除，下次进入将随机重新生成地形");
    }

    /**
     * 强制同步生成出生点周边区块（半径 radius 个区块），确保地形已存在后再寻找安全出生点
     * 必须在主线程调用（命令处理阶段）
     */
    private void preGenerateSpawnChunks(World world, int radius) {
        int cx = world.getSpawnLocation().getBlockX() >> 4;
        int cz = world.getSpawnLocation().getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                try {
                    // true=强制生成；再读取一个方块以触发区块填充，避免异步未就绪导致 findSafeSpawn 误判虚空
                    world.loadChunk(cx + dx, cz + dz, true);
                    world.getChunkAt(cx + dx, cz + dz).getBlock(8, 64, 8).getType();
                } catch (Exception e) {
                    plugin.getLogger().warning("[PVP] 生成区块失败: " + (cx + dx) + "," + (cz + dz));
                }
            }
        }
    }

    /**
     * 在世界中寻找安全的出生点（螺旋向外搜索最高的非空气且非流体方块作为地面）
     * 搜索半径较大（SEARCH_RADIUS 格），即使出生点恰好落在海洋/洞穴也能找到附近陆地，
     * 杜绝"整片虚空→误铺小平台"。
     */
    private Location findSafeSpawn(World world) {
        int baseX = world.getSpawnLocation().getBlockX();
        int baseZ = world.getSpawnLocation().getBlockZ();
        final int SEARCH_RADIUS = 32; // 覆盖出生点周边 64x64 格范围
        int bestX = baseX, bestZ = baseZ, bestY = -1;
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            // 仅遍历半径为 r 的方形环
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = baseX + dx, z = baseZ + dz;
                    for (int y = 255; y >= 1; y--) {
                        Block block = world.getBlockAt(x, y, z);
                        Material type = block.getType();
                        if (!type.isAir() && !isFluid(type)) {
                            if (y > bestY) { bestY = y; bestX = x; bestZ = z; }
                            break;
                        }
                    }
                }
            }
            if (bestY >= 0) break; // 找到陆地立即停止
        }
        if (bestY >= 0) {
            return new Location(world, bestX + 0.5, bestY + 1, bestZ + 0.5);
        }
        return null; // 未找到安全地面（疑似虚空世界）
    }

    /**
     * 判断方块是否为流体（水/熔岩），流体上不适合作为安全出生地面
     */
    private boolean isFluid(Material type) {
        return type == Material.WATER || type == Material.LAVA;
    }

    /**
     * 保底平台：如果出生点下方全是空气则铺一个平台
     */
    private void buildFallbackPlatform(World world) {
        int centerY = 99;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                world.getBlockAt(dx, centerY, dz).setType(Material.STONE);
                world.getBlockAt(dx, centerY - 1, dz).setType(Material.STONE);
            }
        }
        world.getBlockAt(-5, centerY + 1, -5).setType(Material.GLOWSTONE);
        world.getBlockAt(5, centerY + 1, -5).setType(Material.GLOWSTONE);
        world.getBlockAt(-5, centerY + 1, 5).setType(Material.GLOWSTONE);
        world.getBlockAt(5, centerY + 1, 5).setType(Material.GLOWSTONE);
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
        equipmentConfirmed.remove(playerName);

        // ★ 关键变更：先选装备，不急着备份清空
        openEquipmentSelection(player);

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

        // 标记已完成装备确认
        equipmentConfirmed.add(playerName);

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

        // 清理所有状态
        inPVPArena.remove(playerName);
        equipmentConfirmed.remove(playerName);
        inventoryBackups.remove(playerName);

        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 离开PVP竞技场"
                + (isDisconnect ? "（断线）" : ""));

        // 退场后若已无人，启动空场冷却；冷却结束且仍无人则删除世界（下次随机重生）
        scheduleWorldDeletionIfEmpty();
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

        if (to.equals(PVP_WORLD_NAME) && !from.equals(PVP_WORLD_NAME)) {
            // 进入PVP世界 → 打开装备选择GUI（此时玩家仍持有原背包）
            onPlayerEnterPVPWorld(player);
        } else if (from.equals(PVP_WORLD_NAME) && !to.equals(PVP_WORLD_NAME)) {
            // 离开PVP世界 → 回收装备并还原背包
            onPlayerExitPVPWorld(player);
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
        if (player.getWorld().getName().equals(PVP_WORLD_NAME)) {
            inPVPArena.add(player.getName());
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
                // 若因此成为最后一名退场者，启动空场冷却删除
                scheduleWorldDeletionIfEmpty();
            }
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
     * 检查物品是否是PVP装备
     */
    private boolean isPVPEquipment(ItemStack item) {
        if (item == null) return false;
        Material type = item.getType();
        return type == Material.IRON_HELMET ||
               type == Material.IRON_CHESTPLATE ||
               type == Material.IRON_LEGGINGS ||
               type == Material.IRON_BOOTS ||
               type == Material.DIAMOND_SWORD ||
               type == Material.BOW ||
               type == Material.ARROW ||
               type == Material.GOLDEN_APPLE ||
               type == Material.POTION;
    }

    /**
     * 打开装备选择GUI
     */
    public void openEquipmentSelection(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, EQUIPMENT_GUI_TITLE);

        for (int i = 0; i < pvpEquipment.size() && i < 45; i++) {
            gui.setItem(i, pvpEquipment.get(i).clone());
        }

        // 确认选择按钮
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§a§l确认选择（将备份你的原背包）");
            confirm.setItemMeta(confirmMeta);
        }
        gui.setItem(49, confirm);

        // 一键装备全套按钮
        ItemStack selectAll = new ItemStack(Material.NETHER_STAR);
        ItemMeta selectAllMeta = selectAll.getItemMeta();
        if (selectAllMeta != null) {
            selectAllMeta.setDisplayName("§b§l一键装备全套");
            selectAll.setItemMeta(selectAllMeta);
        }
        gui.setItem(53, selectAll);

        player.openInventory(gui);
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

        // 确认选择按钮 → 备份+清空+发装备（三合一）
        if (slot == 49) {
            player.closeInventory();
            confirmEquipment(player);
            return true;
        }

        // 一键装备全套 → 同样触发确认流程
        if (slot == 53) {
            player.closeInventory();
            confirmEquipment(player);
            return true;
        }

        // 单独查看物品（预览用途，实际装备需按确认按钮）
        if (slot >= 0 && slot < pvpEquipment.size()) {
            ItemStack item = pvpEquipment.get(slot).clone();
            player.sendMessage("§7预览: §f" + (item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                    ? item.getItemMeta().getDisplayName() : item.getType().name()));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return true;
        }

        return false;
    }

    /**
     * 装备全套PVP装备到玩家背包
     */
    private void equipFullSet(Player player) {
        for (ItemStack item : pvpEquipment) {
            ItemStack clone = item.clone();
            Material type = clone.getType();
            if (type == Material.IRON_HELMET) {
                player.getInventory().setHelmet(clone);
            } else if (type == Material.IRON_CHESTPLATE) {
                player.getInventory().setChestplate(clone);
            } else if (type == Material.IRON_LEGGINGS) {
                player.getInventory().setLeggings(clone);
            } else if (type == Material.IRON_BOOTS) {
                player.getInventory().setBoots(clone);
            } else {
                player.getInventory().addItem(clone);
            }
        }
    }

    // ==================== 命令入口 ====================

    /**
     * 玩家进入PVP竞技场（/pvp join）
     */
    public void joinArena(Player player) {
        ensurePVPWorldExists();
        World pvpWorld = Bukkit.getWorld(PVP_WORLD_NAME);
        if (pvpWorld == null) {
            player.sendMessage("§c§l[PVP] 竞技场世界加载失败，请联系管理员");
            return;
        }
        if (inPVPArena.contains(player.getName())) {
            player.sendMessage("§e你已在PVP竞技场中，请选择装备或输入 /pvp leave 离开");
            return;
        }
        // 传送至出生点
        Location spawn = pvpWorld.getSpawnLocation();
        player.teleport(spawn);
        // ★ 关键修复：直接触发进入流程，确保装备选择GUI必然弹出，
        //   不再单纯依赖 PlayerChangedWorldEvent 的异步时机（避免"直接进去跳过选装备"）
        onPlayerEnterPVPWorld(player);
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
        return Bukkit.getWorld(PVP_WORLD_NAME);
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
