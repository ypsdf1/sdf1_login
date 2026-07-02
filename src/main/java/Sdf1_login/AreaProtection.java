package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Animals;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import java.sql.*;
import org.bukkit.GameMode;
import org.bukkit.event.EventPriority;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import java.util.Iterator;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryOpenEvent;

public class AreaProtection implements Listener {

    // 三级权限枚举
    public enum PermissionLevel {
        ADMIN,    // 管理员：可以做所有事
        OWNER,    // 领地所有者：可以管理自己的领地
        VISITOR   // 访客：只能在被授权的领地内活动
    }

    private final Main plugin;
    public List<String[]> giveEffects = new ArrayList<>();
    private final Map<UUID, Set<String>> playerAreas
            = new HashMap<>();
    private final Map<String, AreaConfig> areas =
            new ConcurrentHashMap<>();
    private Connection dbConnection;

    // 全局白名单（玩家）
    private final Set<String> globalPlayerWhitelist =
            ConcurrentHashMap.newKeySet();
    // 区域白名单 key=区域名 value=玩家名集合
    private final Map<String, Set<String>> areaPlayerWhitelist =
            new ConcurrentHashMap<>();
    // 全局黑名单（物品ID）
    private final Set<String> globalItemBlacklist =
            ConcurrentHashMap.newKeySet();
    // 区域黑名单
    private final Map<String, Set<String>> areaItemBlacklist =
            new ConcurrentHashMap<>();

    // ★ 领地删除延迟队列（1分钟冷却）
    private static class PendingDelete {
        final String areaName;
        final String playerName;
        final long startTime;
        final BukkitTask task;
        PendingDelete(String areaName, String playerName, long startTime, BukkitTask task) {
            this.areaName = areaName;
            this.playerName = playerName;
            this.startTime = startTime;
            this.task = task;
        }
    }
    private final Map<String, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    // ★ 圈地工具冷却：UUID → 上次获取时间（package-private供AreaGUIManager访问）
    final Map<java.util.UUID, Long> wandCooldownMap = new ConcurrentHashMap<>();
    // ★ 管理员配置输入等待：UUID → 配置key（GUI点击后等待玩家聊天输入）
    private final Map<java.util.UUID, String> pendingConfigInput = new ConcurrentHashMap<>();
    // ★ 添加成员输入等待：UUID → 领地名
    private final Map<java.util.UUID, String> pendingAddMemberInput = new ConcurrentHashMap<>();
    // ★ 效果管理待处理输入：UUID → [landName, inputType]
    private final Map<java.util.UUID, String[]> pendingEffectInput = new ConcurrentHashMap<>();
    // ★ 过户领地待输入新所有者：UUID → [landName]
    public final Map<java.util.UUID, String[]> pendingTransferInput = new ConcurrentHashMap<>();
    // ★ 用户组配置编辑待输入：UUID → [groupName, field("price"|"maxlands"|"priority")]
    public final Map<java.util.UUID, String[]> pendingGroupEditInput = new ConcurrentHashMap<>();

    // ★ 全局配置默认值
    private int globalCreatePricePerSqm = 10;  // 每㎡创建价格
    private int globalMaxLandsPerPlayer = 5;   // 每人最多领地数
    private int globalDefaultHeight = 255;      // 默认高度

    // 选地
    private static final Material WAND = Material.BLAZE_ROD;

    private final Map<UUID, Location> pos1 =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2 =
            new ConcurrentHashMap<>();
    private final Set<UUID> selecting =
            ConcurrentHashMap.newKeySet();

    public boolean hasPos1(UUID uuid) { return pos1.containsKey(uuid); }
    public boolean hasPos2(UUID uuid) { return pos2.containsKey(uuid); }
    public boolean denyRaid = false;
    // ===== 统一文件夹 =====
    private final File rootDir;
    private final File whitelistDir;
    private final File globalWhitelistFile;
    private final File oldDbFile;
    private final File newDbFile;

    // 边框显示
    private final Map<UUID, Integer> displayTaskIds =
            new ConcurrentHashMap<>();
    private final Map<String, Set<String>> globalWhitelist
            = new HashMap<>();
    private final Map<String, Set<String>> areaWhitelist
            = new HashMap<>();
    private final Map<UUID, Long> protectedEntities
            = new ConcurrentHashMap<>();
    // 区域给予的效果记录（贴标）
    private final Map<UUID, List<PotionEffectType>> playerAppliedEffects = new HashMap<>();
    // 延时清理任务
    private static final long MOVE_DEBOUNCE_MS = 100;
    // 新：全部改为线程安全
    private final Map<UUID, String> playerCurrentArea
            = new ConcurrentHashMap<>();
    private final Map<UUID, List<PotionEffectType>> playerMarkedEffects
            = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pendingClearTask
            = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingClearArea
            = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMoveProcess
            = new ConcurrentHashMap<>();
    // 防止传送时重复处理
    private final Set<UUID> teleporting
            = ConcurrentHashMap.newKeySet();
    // 待清理的效果列表（与延时任务配合）
    private final Map<UUID, List<PotionEffectType>> pendingClearEffects
            = new ConcurrentHashMap<>();
    // ★ 拾取消息节流（5~10秒随机间隔）
    private final Map<UUID, Long> lastPickupMsgTime = new ConcurrentHashMap<>();
    private static final String[] PICKUP_MSGS = {
            "§c§l[区域防护] §f禁止拾取物品",
            "§c§l[区域防护] §f此区域不允许拾取",
            "§c§l[区域防护] §f拾取功能已禁用",
            "§c§l[区域防护] §f你无法在此区域拾取物品",
            "§c§l[区域防护] §f请离开领地后再拾取"
    };



// ==================== 强制游戏模式 ====================

    /**
     * 监听玩家尝试切换游戏模式
     * 如果在强制游戏模式区域且不在豁免名单中，则取消切换
     */
    // 防止递归的标记
    private final Set<UUID> modeSwitching
            = ConcurrentHashMap.newKeySet();
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGameModeChange(
            PlayerGameModeChangeEvent event) {
        if (event.isCancelled()) return;

        Player p = event.getPlayer();
        UUID uid = p.getUniqueId();

        if (modeSwitching.contains(uid)) return;

        String areaName = playerCurrentArea.get(uid);
        if (areaName == null) return;

        AreaConfig ac = areas.get(areaName);
        if (ac == null) return;
        if (ac.enforceGameMode == null
                || ac.enforceGameMode.isEmpty()) return;
        if (isExemptFromGameMode(p, areaName)) return;

        event.setCancelled(true);

        modeSwitching.add(uid);
        try {
            GameMode target = GameMode.valueOf(
                    ac.enforceGameMode);
            if (p.getGameMode() != target) {
                p.setGameMode(target);
            }
        } finally {
            modeSwitching.remove(uid);
        }

        // ★ 切换后发一次中文提示
        p.sendMessage(formatAreaMsg(
                "&c该区域强制 " + ac.enforceGameMode
                        + " 模式，无法切换"));
    }

    public Set<UUID> getAlreadyForced() {
        return alreadyForced;
    }


    // ==================== 定时强制游戏模式 ====================

    private BukkitTask enforceTask;

    /**
     * 启动定时强制任务
     * 每秒检查一次所有在线玩家
     */
    private BukkitTask enforceTask1;

    // 记录上次强制模式的玩家（避免重复发消息）
    private final Set<UUID> alreadyForced
            = ConcurrentHashMap.newKeySet();

    public void startEnforceTask() {
        if (enforceTask != null) {
            enforceTask.cancel();
        }
        enforceTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID uid = p.getUniqueId();
                    String areaName =
                            playerCurrentArea.get(uid);
                    if (areaName == null) {
                        // 不在任何区域，清除标记
                        alreadyForced.remove(uid);
                        continue;
                    }

                    AreaConfig ac = areas.get(areaName);
                    if (ac == null) continue;
                    if (ac.enforceGameMode == null
                            || ac.enforceGameMode
                            .isEmpty()) continue;
                    if (isExemptFromGameMode(
                            p, areaName)) {
                        alreadyForced.remove(uid);
                        continue;
                    }

                    GameMode target =
                            GameMode.valueOf(
                                    ac.enforceGameMode);
                    if (p.getGameMode() != target) {
                        modeSwitching.add(uid);
                        try {
                            p.setGameMode(target);
                        } finally {
                            modeSwitching.remove(uid);
                        }

                        // ★ 只在玩家已被强制过时才发消息
                        // 首次不发，后续才提醒
                        if (alreadyForced.contains(uid)) {
                            p.sendMessage(formatAreaMsg(
                                    "&c该区域强制 "
                                            + ac.enforceGameMode
                                            + " 模式，无法切换"));
                        }
                        alreadyForced.add(uid);
                    } else {
                        // 模式一致，清除标记
                        alreadyForced.remove(uid);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void stopEnforceTask() {
        if (enforceTask != null) {
            enforceTask.cancel();
            enforceTask = null;
        }
    }



    /**
     * 检查玩家是否豁免于游戏模式强制
     * 豁免条件：全局白名单 / 区域白名单 / 模式排除名单
     */
    private boolean isExemptFromGameMode(
            Player p, String areaName) {
        String name = p.getName();

        // 管理员豁免
        if (isAreaAdmin(p)) {
            return true;
        }

        if (globalPlayerWhitelist.contains(name)) {
            return true;
        }
        Set<String> aw =
                areaPlayerWhitelist.get(areaName);
        if (aw != null && aw.contains(name)) {
            return true;
        }
        AreaConfig ac = areas.get(areaName);
        if (ac != null && ac.modeExempt.contains(name)) {
            return true;
        }
        // 领地所有者豁免
        if (ac != null && ac.owner != null && ac.owner.equals(name)) {
            return true;
        }
        return false;
    }

    /**
     * 玩家进入区域时，如果该区域强制游戏模式，
     * 则立即设置（排除豁免玩家）
     */
    private void applyEnforcedGameMode(Player p,
                                       String areaName) {
        AreaConfig ac = areas.get(areaName);
        if (ac == null) {
        /*    plugin.getLogger().info(
                    "[防护-调试] applyMode: ac=null, area="
                            + areaName);*/
            return;
        }
        if (ac.enforceGameMode == null
                || ac.enforceGameMode.isEmpty()) {
        /*    plugin.getLogger().info(
                    "[防护-调试] applyMode: 未配置强制模式, area="
                            + areaName);*/
            return;
        }

        boolean exempt = isExemptFromGameMode(
                p, areaName);
      /*  plugin.getLogger().info(
                "[防护-调试] applyMode: 玩家=" + p.getName()
                        + " 区域=" + areaName
                        + " 强制=" + ac.enforceGameMode
                        + " 当前=" + p.getGameMode()
                        + " 豁免=" + exempt
                        + " OP=" + p.isOp()
                        + " 全白=" + globalPlayerWhitelist
                        .contains(p.getName()));*/

        if (exempt) return;

        try {
            GameMode target = GameMode.valueOf(
                    ac.enforceGameMode);
            if (p.getGameMode() != target) {
                p.setGameMode(target);
             /*   plugin.getLogger().info(
                        "[防护] 强制切换 " + p.getName()
                                + " → " + ac.enforceGameMode);*/
            }
        } catch (Exception e) {
         /*  plugin.getLogger().warning(
                    "[防护] 强制模式失败: "
                            + e.getMessage());*/
        }
    }

    public AreaProtection(Main plugin) {
        this.plugin = plugin;

        // 统一根目录
        rootDir = new File(plugin.getDataFolder(), "区域防护");
        if (!rootDir.exists()) rootDir.mkdirs();

        // 白名单子目录
        whitelistDir = new File(rootDir, "whitelists");
        if (!whitelistDir.exists()) whitelistDir.mkdirs();
        globalWhitelistFile = new File(
                whitelistDir, "全局白名单.txt");

        // 数据库迁移
        oldDbFile = new File(
                plugin.getDataFolder(), "area_effects.db");
        newDbFile = new File(rootDir, "area_effects.db");
        migrateDatabase();

        // 初始化
        initDatabase();
        writeDefaultConfig();
        migrateTxtToDb(); // ★ 迁移txt文件到数据库
        loadAllAreas();
        loadWhitelists();
        recoverPendingEffects();
    }
    public void recoverPendingEffects() {
        try {
            // 1. 遍历所有在线玩家（而不是遍历DB记录）
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID uid = p.getUniqueId();
                String playerName = p.getName();

                // 2. 获取玩家当前所在区域
                AreaConfig currentAc = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());

                // 3. 获取该玩家在DB中的所有历史记录
                List<String> recordedAreas =
                        getPlayerAreas(uid);

                // 4. 清理不在当前区域的旧记录
                for (String recordedArea : recordedAreas) {
                    if (currentAc == null
                            || !currentAc.name
                            .equals(recordedArea)) {
                        // 玩家不在这个区域了 → 清DB
                        removePlayerEffects(
                                uid, recordedArea);
                        plugin.getLogger().info(
                                "[防护] 重连: " + playerName
                                        + " 不在" + recordedArea
                                        + ", 清理旧记录");
                    }
                }

                // 5. 如果在区域中 → 恢复/给予效果
                if (currentAc != null) {
                    plugin.getLogger().info(
                            "[防护] 重连: " + playerName
                                    + " 在" + currentAc.name
                                    + ", 恢复效果");
                    applyRegionEffects(p, uid,
                            currentAc.name, currentAc);
                } else {
                    plugin.getLogger().info(
                            "[防护] 重连: " + playerName
                                    + " 不在任何区域, 跳过");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[防护] 恢复效果失败: "
                            + e.getMessage());
        }
    }


    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID uid = p.getUniqueId();

        // 取消延时清理任务
        if (pendingClearTask.containsKey(uid)) {
            pendingClearTask.get(uid).cancel();
            pendingClearTask.remove(uid);
            pendingClearArea.remove(uid);
            pendingClearEffects.remove(uid);
        }

        // 清标记
        playerMarkedEffects.remove(uid);
        playerCurrentArea.remove(uid);
    }

    /**
     * 玩家上线时恢复区域效果
     * 如果玩家在有效果的区域中，恢复药水效果
     */
    public void onPlayerJoin(Player p) {
        if (p == null || !p.isOnline()) return;
        UUID uid = p.getUniqueId();
        String playerName = p.getName();

        // 获取玩家当前所在区域
        AreaConfig currentAc = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());

        if (currentAc != null) {
            plugin.getLogger().info(
                    "[防护] 上线: " + playerName
                            + " 在" + currentAc.name
                            + ", 恢复效果");
            applyRegionEffects(p, uid,
                    currentAc.name, currentAc);
        }
    }


// ==================== 数据库迁移 ====================

    /**
     * 如果旧位置有 db 文件，移到新位置
     * 如果新位置已有，不动
     * 如果旧位置也没有，在新位置创建
     */

     private void migrateDatabase() {
     if (newDbFile.exists()) {
     if (oldDbFile.exists()
     && !oldDbFile.equals(newDbFile)) {
     oldDbFile.delete();
     }
     return;
     }
     if (oldDbFile.exists()
     && !oldDbFile.equals(newDbFile)) {
     try {
     java.nio.file.Files.move(
     oldDbFile.toPath(),
     newDbFile.toPath(),
     java.nio.file.StandardCopyOption
     .REPLACE_EXISTING);
     } catch (Exception e) {
     plugin.getLogger().warning(
     "[防护] 迁移数据库失败: "
     + e.getMessage());
     }
     }
     }


    // 保存玩家进入区域时给予的效果
    private void savePlayerEffects(UUID uid, String playerName,
                                   String areaName, List<PotionEffectType> effects) {
        try {
            // 先删除该区域旧记录，避免重复
            PreparedStatement del = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            del.setString(1, uid.toString());
            del.setString(2, areaName);
            del.executeUpdate();
            del.close();

            PreparedStatement ps = dbConnection.prepareStatement(
                    "INSERT INTO player_effects "
                            + "(uuid, player_name, area_name, "
                            + "effect_type, effect_level, "
                            + "effect_duration, enter_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)");
            long now = System.currentTimeMillis();
            for (PotionEffectType type : effects) {
                ps.setString(1, uid.toString());
                ps.setString(2, playerName);
                ps.setString(3, areaName);
                ps.setString(4, type.getName());
                ps.setInt(5, 0);
                ps.setInt(6, 999);
                ps.setLong(7, now);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 保存效果失败: " + e.getMessage());
        }
    }

    // 查询玩家在某个区域获得的效果
    private List<PotionEffectType> getPlayerEffects(
            UUID uid, String areaName) {
        List<PotionEffectType> list = new ArrayList<>();
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT effect_type FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            ps.setString(1, uid.toString());
            ps.setString(2, areaName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                PotionEffectType type = PotionEffectType
                        .getByName(rs.getString("effect_type"));
                if (type != null) list.add(type);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询效果失败: " + e.getMessage());
        }
        return list;
    }

    // 删除玩家在某个区域的效果记录
    private void removePlayerEffects(UUID uid, String areaName) {
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            ps.setString(1, uid.toString());
            ps.setString(2, areaName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 删除效果失败: " + e.getMessage());
        }
    }

    // 删除玩家所有效果记录
    private void removeAllPlayerEffects(UUID uid) {
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ?");
            ps.setString(1, uid.toString());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 删除全部效果失败: " + e.getMessage());
        }
    }

    // 获取玩家所有记录的区域
    private List<String> getPlayerAreas(UUID uid) {
        List<String> list = new ArrayList<>();
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT DISTINCT area_name "
                            + "FROM player_effects WHERE uuid = ?");
            ps.setString(1, uid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(rs.getString("area_name"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询区域失败: " + e.getMessage());
        }
        return list;
    }

    // 查询所有待处理的效果（跨服重启也能恢复）
    private List<String[]> getAllPendingEffects() {
        List<String[]> list = new ArrayList<>();
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "SELECT uuid, player_name, area_name, "
                            + "effect_type FROM player_effects");
            while (rs.next()) {
                list.add(new String[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getString("area_name"),
                        rs.getString("effect_type")});
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询全部效果失败: " + e.getMessage());
        }
        return list;
    }


    private void writeDefaultConfig() {
        File f = new File(rootDir, "末地保护区.txt");
        if (f.exists()) return;

        String endWorld = "world_the_end";
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment()
                    == org.bukkit.World.Environment.THE_END) {
                endWorld = w.getName();
                break;
            }
        }

        final String ew = endWorld;
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            pw.println("# 末地保护区");
            pw.println("起点: " + ew + ",-30000000,-30000000");
            pw.println("终点: " + ew + ",30000000,30000000");
            pw.println("高度范围: 0-255");
            pw.println();
            pw.println("没收物品: BROWN_MUSHROOM,RED_MUSHROOM,MUSHROOM_STEW");
            pw.println("没收提示: 你携带了违禁品「蘑菇」，已被全部没收！");
            pw.println("通报批评: {player} 在末地违规携带蘑菇！");
            pw.println();
            pw.println("禁止放置方块");
            pw.println("禁止破坏方块");
            pw.println("禁止PVP");
            pw.println("禁止袭击");
            pw.println();
            pw.println("效果: 夜视 1 999");
            pw.println("进入提示: 末地保护区 - 请遵守规则");
            pw.println("离开提示: 已离开末地保护区");
            pw.close();
            plugin.getLogger().info("[防护] 生成末地配置, 世界: " + ew);
        } catch (IOException ignored) {
        }
    }

    public void reload() {
        loadAllAreas();
        loadWhitelists();
        // 强制输出醒目的中文日志
        plugin.getLogger().info("========================================");
        plugin.getLogger().info("[防护] 已重载 " + areas.size() + " 个区域");
        plugin.getLogger().info("[防护] 已重载白名单");
        plugin.getLogger().info("========================================");
    }

    /**
     * ★ 从数据库重新加载area_config配置（Web同步用）
     */
    public void reloadAreaConfigFromDb() {
        if (dbConnection == null) return;
        try {
            ResultSet cfgRs = dbConnection.createStatement().executeQuery("SELECT key, value FROM area_config");
            while (cfgRs.next()) {
                String key = cfgRs.getString("key");
                String value = cfgRs.getString("value");
                switch (key) {
                    case "create_price_per_sqm": /* 已在area_config表管理 */ break;
                    case "max_lands_per_player": break;
                    case "default_height": break;
                    case "peace_mode_max_duration": break;
                }
            }
            cfgRs.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 从DB重载配置失败: " + e.getMessage());
        }
    }

// ==================== 消息格式化 ====================

    /**
     * 格式化区域消息
     * 1. & → §（颜色符号转换）
     * 2. <br> 和 \n → 换行
     * 3. <b>内容</b> → §l内容§r（加粗）
     * 4. 自动添加 [区域防护] 前缀
     */
    private String formatAreaMsg(String raw) {
        if (raw == null || raw.isEmpty()) return "";

        String msg = raw;

        // & → §
        for (int i = 0; i <= 9; i++) {
            msg = msg.replace("&" + i, "§" + i);
        }
        for (char c : "abcdefklnor".toCharArray()) {
            msg = msg.replace("&" + c, "§" + c);
        }

        // 换行
        msg = msg.replace("<br>", "\n");
        msg = msg.replace("\\n", "\n");

        // HTML → Minecraft格式（栈式解析，支持嵌套）
        msg = convertHtmlTags(msg);

        // 前缀
        msg = "§c§l【区域防护】§r " + msg;

        return msg;
    }

    /**
     * 栈式HTML标签解析
     * 处理 <u><b>text</b> rest</u> 这样的嵌套标签
     * 内层关闭时重新应用外层格式
     */
    private String convertHtmlTags(String msg) {
        StringBuilder sb = new StringBuilder();
        java.util.Deque<Character> stack =
                new ArrayDeque<>();
        int i = 0;

        while (i < msg.length()) {
            if (msg.charAt(i) == '<'
                    && i + 1 < msg.length()) {
                if (msg.charAt(i + 1) == '/') {
                    // 关闭标签 </x>
                    int end = msg.indexOf('>', i);
                    if (end < 0) {
                        sb.append(msg.charAt(i));
                        i++;
                        continue;
                    }
                    String tag = msg.substring(i + 2, end)
                            .trim().toLowerCase();
                    char code = htmlToMc(tag);
                    if (code != 0 && !stack.isEmpty()
                            && stack.peek() == code) {
                        stack.pop();
                        if (!stack.isEmpty()) {
                            // 重置后重新应用外层格式
                            sb.append("§r");
                            for (char c : stack) {
                                sb.append("§").append(c);
                            }
                        } else {
                            sb.append("§r");
                        }
                    }
                    i = end + 1;
                } else {
                    // 开放标签 <x>
                    int end = msg.indexOf('>', i);
                    if (end < 0) {
                        sb.append(msg.charAt(i));
                        i++;
                        continue;
                    }
                    String tag = msg.substring(i + 1, end)
                            .trim().toLowerCase();
                    char code = htmlToMc(tag);
                    if (code != 0) {
                        stack.push(code);
                        sb.append("§").append(code);
                    }
                    i = end + 1;
                }
            } else {
                sb.append(msg.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private char htmlToMc(String tag) {
        switch (tag) {
            case "b": return 'l';
            case "i": return 'o';
            case "u": return 'n';
            case "s": return 'm';
            default: return 0;
        }
    }

// =============== 加载 ====================

    public void loadAllAreas() {
        areas.clear();

        // ★ 优先从数据库加载
        loadAreasFromDb();

        // ★ 自动迁移：如果DB有数据但txt文件仍存在，迁移txt中的白名单数据到DB后删除txt
        if (!areas.isEmpty()) {
            migrateTxtFilesToDb();
        }

        // ★ 兼容：如果没有从DB加载到数据，回退到txt（未迁移场景）并自动导入DB
        if (areas.isEmpty()) {
            File[] files = rootDir.listFiles(
                    (File d, String n) -> n.endsWith(".txt"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String name = f.getName().replace(".txt", "");
                        AreaConfig ac = parseArea(name, f);
                        areas.put(name, ac);
                        // ★ 自动写入数据库
                        saveAreaToDb(ac);
                        plugin.getLogger().info("[防护] 自动迁移txt→DB: " + name);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[防护] 加载失败: " + f.getName());
                    }
                }
                // ★ 迁移完成后删除所有txt文件
                if (!areas.isEmpty()) {
                    for (File f : files) {
                        if (f.delete()) {
                            plugin.getLogger().info("[防护] 已删除迁移后的txt: " + f.getName());
                        }
                    }
                    plugin.getLogger().info("[防护] txt迁移完成，共导入 " + areas.size() + " 个领地");
                }
            }
        }
    }

    /**
     * 迁移txt文件到数据库（如果txt存在但DB已有数据，清理txt）
     */
    private void migrateTxtFilesToDb() {
        File[] files = rootDir.listFiles(
                (File d, String n) -> n.endsWith(".txt"));
        if (files == null || files.length == 0) return;

        plugin.getLogger().info("[防护] 检测到 " + files.length + " 个残留txt文件，开始清理...");
        for (File f : files) {
            String name = f.getName().replace(".txt", "");
            // 如果txt对应的领地不在DB中，先导入
            if (!areas.containsKey(name)) {
                try {
                    AreaConfig ac = parseArea(name, f);
                    areas.put(name, ac);
                    saveAreaToDb(ac);
                    plugin.getLogger().info("[防护] 迁移txt→DB: " + name);
                } catch (Exception e) {
                    plugin.getLogger().warning("[防护] 迁移失败: " + f.getName());
                }
            }
            // 删除txt
            if (f.delete()) {
                plugin.getLogger().info("[防护] 已删除: " + f.getName());
            }
        }
    }

    /**
     * 从数据库加载所有领地配置
     */
    private void loadAreasFromDb() {
        if (dbConnection == null) return;
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM area_lands");
            int count = 0;
            while (rs.next()) {
                AreaConfig ac = new AreaConfig();
                ac.name = rs.getString("name");
                ac.owner = rs.getString("owner");
                ac.world = rs.getString("world");
                ac.x1 = rs.getInt("x1");
                ac.z1 = rs.getInt("z1");
                ac.x2 = rs.getInt("x2");
                ac.z2 = rs.getInt("z2");
                ac.yMin = rs.getInt("y_min");
                ac.yMax = rs.getInt("y_max");
                ac.confiscateItems = splitToList(rs.getString("confiscate_items"));
                ac.denyUseItems = splitToList(rs.getString("deny_use_items"));
                ac.giveEffects = parseEffectsString(rs.getString("give_effects"));
                ac.clearEffects = splitToList(rs.getString("clear_effects"));
                ac.clearAllBadEffects = rs.getInt("clear_all_bad") == 1;
                ac.punishCommands = splitToList(rs.getString("punish_commands"));
                ac.denyBlockPlace = rs.getInt("deny_block_place") == 1;
                ac.denyBlockBreak = rs.getInt("deny_block_break") == 1;
                ac.denyPVP = rs.getInt("deny_pvp") == 1;
                ac.denyFallDamage = rs.getInt("deny_fall_damage") == 1;
                ac.denyHunger = rs.getInt("deny_hunger") == 1;
                ac.denyAllDamage = rs.getInt("deny_all_damage") == 1;
                ac.allowDrop = rs.getInt("deny_drop") == 1;
                ac.denyMount = rs.getInt("deny_mount") == 1;
                ac.denyEnderPearl = rs.getInt("deny_ender_pearl") == 1;
                ac.denyBow = rs.getInt("deny_bow") == 1;
                ac.denyPotion = rs.getInt("deny_potion") == 1;
                ac.denyExplosion = rs.getInt("deny_explosion") == 1;
                ac.denyRaid = rs.getInt("deny_raid") == 1;
                ac.denyFireSpread = rs.getInt("deny_fire_spread") == 1;
                ac.denyAllEffects = rs.getInt("deny_all_effects") == 1;
                ac.denyItemFrame = rs.getInt("deny_item_frame") == 1;
                try { ac.denyMove = rs.getInt("deny_move") == 1; } catch (Exception ignored) {}
                try { ac.allowPickup = rs.getInt("deny_pickup") == 1; } catch (Exception ignored) {}
                try { ac.denyFire = rs.getInt("deny_fire") == 1; } catch (Exception ignored) {}
                try { ac.denyThrownProjectiles = rs.getInt("deny_thrown_projectiles") == 1; } catch (Exception ignored) {}
                try { ac.denyGlowing = rs.getInt("deny_glowing") == 1; } catch (Exception ignored) {}
                try { ac.denyRedstoneInteraction = rs.getInt("deny_redstone_interaction") == 1; } catch (Exception ignored) {}
                try { ac.denyDoorInteraction = rs.getInt("deny_door_interaction") == 1; } catch (Exception ignored) {}
                try { ac.denyNoteblockJukebox = rs.getInt("deny_noteblock_jukebox") == 1; } catch (Exception ignored) {}
                try { ac.denyLead = rs.getInt("deny_lead") == 1; } catch (Exception ignored) {}
                try { ac.denyCropHarvest = rs.getInt("deny_crop_harvest") == 1; } catch (Exception ignored) {}
                try { ac.denyWoolShear = rs.getInt("deny_wool_shear") == 1; } catch (Exception ignored) {}
                try { ac.denyAnimalFeeding = rs.getInt("deny_animal_feeding") == 1; } catch (Exception ignored) {}
                try { ac.denyContainer = rs.getInt("deny_container") == 1; } catch (Exception ignored) {}
                try { ac.denyMobAttack = rs.getInt("deny_mob_attack") == 1; } catch (Exception ignored) {}
                try { ac.warpX = rs.getDouble("warp_x"); } catch (Exception ignored) {}
                try { ac.warpY = rs.getDouble("warp_y"); } catch (Exception ignored) {}
                try { ac.warpZ = rs.getDouble("warp_z"); } catch (Exception ignored) {}
                try { ac.warpYaw = rs.getFloat("warp_yaw"); } catch (Exception ignored) {}
                try { ac.warpPitch = rs.getFloat("warp_pitch"); } catch (Exception ignored) {}
                try { ac.warpWorld = rs.getString("warp_world"); } catch (Exception ignored) {}
                try { ac.isPublicBuilding = rs.getInt("is_public_building") == 1; } catch (Exception ignored) {}
                ac.peaceMode = rs.getInt("peace_mode") == 1;
                ac.peaceModeDuration = rs.getInt("peace_mode_duration") * 1000; // 转毫秒
                ac.peaceWhitelist = new HashSet<>(splitToList(rs.getString("peace_whitelist")));
                String gm = rs.getString("enforce_game_mode");
                ac.enforceGameMode = (gm != null && !gm.isEmpty()) ? gm : null;
                ac.modeExempt = new HashSet<>(splitToList(rs.getString("mode_exempt")));
                ac.enterMsg = rs.getString("enter_msg");
                ac.leaveMsg = rs.getString("leave_msg");
                ac.confiscateMsg = rs.getString("confiscate_msg");
                ac.enableAnnounce = rs.getInt("enable_announce") == 1;
                ac.announceTemplate = rs.getString("announce_template");
                ac.txtContent = rs.getString("txt_content");
                areas.put(ac.name, ac);
                count++;
            }
            rs.close();
            stmt.close();
            if (count > 0) {
                plugin.getLogger().info("[防护] 从数据库加载" + count + "个领地");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 从DB加载领地失败: " + e.getMessage());
        }
    }

    /**
     * 逗号分隔字符串 → List
     */
    private List<String> splitToList(String s) {
        List<String> result = new ArrayList<>();
        if (s == null || s.isEmpty()) return result;
        for (String item : s.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /**
     * 解析效果存储字符串 → List<String[]>
     * 格式: 效果名:等级:秒数|效果名:等级:秒数
     */
    private List<String[]> parseEffectsString(String s) {
        List<String[]> result = new ArrayList<>();
        if (s == null || s.isEmpty()) return result;
        for (String part : s.split("\\|")) {
            String[] pieces = part.split(":");
            if (pieces.length >= 3) {
                result.add(new String[]{pieces[0], pieces[1], pieces[2]});
            }
        }
        return result;
    }


    private void initDatabase() {
        try {
            dbConnection = DriverManager.getConnection(
                    "jdbc:sqlite:" + newDbFile.getAbsolutePath());
            Statement stmt = dbConnection.createStatement();

            // 效果记录表（原有）
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_effects ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "uuid TEXT NOT NULL,"
                            + "player_name TEXT,"
                            + "area_name TEXT NOT NULL,"
                            + "effect_type TEXT NOT NULL,"
                            + "effect_level INTEGER DEFAULT 0,"
                            + "effect_duration INTEGER DEFAULT 999,"
                            + "enter_time INTEGER NOT NULL)");
            stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_uuid "
                            + "ON player_effects(uuid)");

            // ★ 领地主表（替代txt文件存储）
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS area_lands ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "name TEXT UNIQUE NOT NULL,"
                            + "owner TEXT NOT NULL DEFAULT '',"
                            + "world TEXT NOT NULL,"
                            + "x1 INTEGER NOT NULL,"
                            + "z1 INTEGER NOT NULL,"
                            + "x2 INTEGER NOT NULL,"
                            + "z2 INTEGER NOT NULL,"
                            + "y_min INTEGER DEFAULT 0,"
                            + "y_max INTEGER DEFAULT 255,"
                            + "confiscate_items TEXT DEFAULT '',"
                            + "deny_use_items TEXT DEFAULT '',"
                            + "give_effects TEXT DEFAULT '',"
                            + "clear_effects TEXT DEFAULT '',"
                            + "clear_all_bad INTEGER DEFAULT 0,"
                            + "punish_commands TEXT DEFAULT '',"
                            + "deny_block_place INTEGER DEFAULT 0,"
                            + "deny_block_break INTEGER DEFAULT 0,"
                            + "deny_container INTEGER DEFAULT 0,"
                            + "deny_pvp INTEGER DEFAULT 0,"
                            + "deny_fall_damage INTEGER DEFAULT 0,"
                            + "deny_hunger INTEGER DEFAULT 0,"
                            + "deny_all_damage INTEGER DEFAULT 0,"
                            + "deny_drop INTEGER DEFAULT 0,"
                            + "deny_mount INTEGER DEFAULT 0,"
                            + "deny_ender_pearl INTEGER DEFAULT 0,"
                            + "deny_bow INTEGER DEFAULT 0,"
                            + "deny_potion INTEGER DEFAULT 0,"
                            + "deny_explosion INTEGER DEFAULT 0,"
                            + "deny_raid INTEGER DEFAULT 0,"
                            + "deny_fire_spread INTEGER DEFAULT 0,"
                            + "deny_all_effects INTEGER DEFAULT 0,"
                            + "deny_item_frame INTEGER DEFAULT 0,"
                            + "deny_move INTEGER DEFAULT 0,"
                            + "deny_pickup INTEGER DEFAULT 0,"
                            + "deny_fire INTEGER DEFAULT 0,"
                            + "peace_mode INTEGER DEFAULT 0,"
                            + "peace_mode_duration INTEGER DEFAULT 5,"
                            + "peace_whitelist TEXT DEFAULT '',"
                            + "enforce_game_mode TEXT DEFAULT '',"
                            + "mode_exempt TEXT DEFAULT '',"
                            + "enter_msg TEXT DEFAULT '',"
                            + "leave_msg TEXT DEFAULT '',"
                            + "confiscate_msg TEXT DEFAULT '',"
                            + "enable_announce INTEGER DEFAULT 0,"
                            + "announce_template TEXT DEFAULT '',"
                            + "txt_content TEXT DEFAULT '',"
                            + "created_at INTEGER NOT NULL)");

            // ★ 领地权限表（管理员/用户/访客三级权限）
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS area_land_permissions ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "land_id INTEGER NOT NULL,"
                            + "player_name TEXT NOT NULL,"
                            + "role TEXT NOT NULL DEFAULT 'visitor',"
                            + "permissions TEXT DEFAULT '',"
                            + "granted_at INTEGER NOT NULL,"
                            + "expires_at INTEGER DEFAULT 0,"
                            + "UNIQUE(land_id, player_name))");
            stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_land_id "
                    + "ON area_land_permissions(land_id)");

            // ★ 权限商店表
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS area_permission_shop ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "land_id INTEGER NOT NULL,"
                            + "seller TEXT NOT NULL,"
                            + "permission TEXT NOT NULL,"
                            + "price INTEGER NOT NULL,"
                            + "duration INTEGER NOT NULL DEFAULT 86400,"
                            + "created_at INTEGER NOT NULL,"
                            + "status TEXT DEFAULT 'active',"
                            + "buyer TEXT DEFAULT '',"
                            + "bought_at INTEGER DEFAULT 0)");

            // ★ 全局配置表
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS area_config ("
                            + "key TEXT PRIMARY KEY,"
                            + "value TEXT NOT NULL)");

            // 添加可能缺失的列（兼容旧数据库）
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_move INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_pickup INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_fire INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN txt_content TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // ★ 新增权限列
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_thrown_projectiles INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_glowing INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_redstone_interaction INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_door_interaction INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_noteblock_jukebox INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_lead INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_crop_harvest INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_wool_shear INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_animal_feeding INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // ★ 传送点列
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_x REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_y REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_z REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_yaw REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_pitch REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN warp_world TEXT DEFAULT ''"); } catch (Exception ignored) {}
            // ★ 容器管理权限列（旧表缺失会导致INSERT OR REPLACE失败）
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_container INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // ★ 玩家攻击生物权限列
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_mob_attack INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // ★ 公共建筑标记
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN is_public_building INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // ★ 流体阻止
            try { stmt.executeUpdate("ALTER TABLE area_lands ADD COLUMN deny_fluid INTEGER DEFAULT 0"); } catch (Exception ignored) {}

            // ★ 全局配置默认值
            try {
                ResultSet checkRs = stmt.executeQuery("SELECT COUNT(*) FROM area_config");
                int configCount = 0;
                if (checkRs.next()) configCount = checkRs.getInt(1);
                checkRs.close();
                if (configCount == 0) {
                    stmt.executeUpdate("INSERT OR IGNORE INTO area_config VALUES ('create_price_per_sqm', '10')");
                    stmt.executeUpdate("INSERT OR IGNORE INTO area_config VALUES ('max_lands_per_player', '5')");
                    stmt.executeUpdate("INSERT OR IGNORE INTO area_config VALUES ('default_height', '255')");
                    stmt.executeUpdate("INSERT OR IGNORE INTO area_config VALUES ('peace_mode_max_duration', '3600')");
                }
                // 读取全局配置
                ResultSet cfgRs = stmt.executeQuery("SELECT key, value FROM area_config");
                while (cfgRs.next()) {
                    String k = cfgRs.getString("key");
                    String v = cfgRs.getString("value");
                    try {
                        switch (k) {
                            case "create_price_per_sqm": globalCreatePricePerSqm = Integer.parseInt(v); break;
                            case "max_lands_per_player": globalMaxLandsPerPlayer = Integer.parseInt(v); break;
                            case "default_height": globalDefaultHeight = Integer.parseInt(v); break;
                        }
                    } catch (NumberFormatException ignored) {}
                }
                cfgRs.close();
            } catch (Exception ignored) {}

            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().severe(
                    "[防护] 数据库初始化失败: "
                            + e.getMessage());
        }
    }


// ==================== txt→db 迁移 ====================

    /**
     * 将现有的txt文件区域配置迁移到数据库
     * 只在数据库中没有记录时执行（首次升级）
     */
    private void migrateTxtToDb() {
        try {
            // 检查数据库是否已有领地数据
            Statement checkStmt = dbConnection.createStatement();
            ResultSet rs = checkStmt.executeQuery("SELECT COUNT(*) FROM area_lands");
            int dbCount = 0;
            if (rs.next()) dbCount = rs.getInt(1);
            rs.close();
            checkStmt.close();

            // 如果数据库已有数据，跳过迁移
            if (dbCount > 0) {
                plugin.getLogger().info("[防护] 数据库已有" + dbCount + "个领地，跳过迁移");
                return;
            }

            // 扫描txt文件
            File[] txtFiles = rootDir.listFiles(
                    (File d, String n) -> n.endsWith(".txt"));
            if (txtFiles == null || txtFiles.length == 0) {
                plugin.getLogger().info("[防护] 无txt文件需要迁移");
                return;
            }

            int migrated = 0;
            PreparedStatement insertStmt = dbConnection.prepareStatement(
                    "INSERT INTO area_lands (name, owner, world, x1, z1, x2, z2, y_min, y_max, "
                    + "confiscate_items, deny_use_items, give_effects, clear_effects, clear_all_bad, "
                    + "punish_commands, deny_block_place, deny_block_break, deny_pvp, deny_fall_damage, "
                    + "deny_hunger, deny_all_damage, deny_drop, deny_mount, deny_ender_pearl, "
                    + "deny_bow, deny_potion, deny_explosion, deny_raid, deny_fire_spread, "
                    + "deny_all_effects, deny_item_frame, deny_move, deny_pickup, deny_fire, "
                    + "peace_mode, peace_mode_duration, "
                    + "peace_whitelist, enforce_game_mode, mode_exempt, enter_msg, leave_msg, "
                    + "confiscate_msg, enable_announce, announce_template, txt_content, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            for (File f : txtFiles) {
                try {
                    String name = f.getName().replace(".txt", "");
                    // 读取原始txt内容
                    String txtContent = new String(
                            java.nio.file.Files.readAllBytes(f.toPath()),
                            StandardCharsets.UTF_8);

                    // 解析AreaConfig
                    AreaConfig ac = parseArea(name, f);

                    // 写入数据库
                    insertStmt.setString(1, name);
                    insertStmt.setString(2, ""); // owner（txt迁移无owner）
                    insertStmt.setString(3, ac.world);
                    insertStmt.setInt(4, ac.x1);
                    insertStmt.setInt(5, ac.z1);
                    insertStmt.setInt(6, ac.x2);
                    insertStmt.setInt(7, ac.z2);
                    insertStmt.setInt(8, ac.yMin);
                    insertStmt.setInt(9, ac.yMax);
                    insertStmt.setString(10, String.join(",", ac.confiscateItems));
                    insertStmt.setString(11, String.join(",", ac.denyUseItems));
                    insertStmt.setString(12, effectsToString(ac.giveEffects));
                    insertStmt.setString(13, String.join(",", ac.clearEffects));
                    insertStmt.setInt(14, ac.clearAllBadEffects ? 1 : 0);
                    insertStmt.setString(15, String.join("|", ac.punishCommands));
                    insertStmt.setInt(16, ac.denyBlockPlace ? 1 : 0);
                    insertStmt.setInt(17, ac.denyBlockBreak ? 1 : 0);
                    insertStmt.setInt(18, ac.denyPVP ? 1 : 0);
                    insertStmt.setInt(19, ac.denyFallDamage ? 1 : 0);
                    insertStmt.setInt(20, ac.denyHunger ? 1 : 0);
                    insertStmt.setInt(21, ac.denyAllDamage ? 1 : 0);
                    insertStmt.setInt(22, ac.allowDrop ? 1 : 0);
                    insertStmt.setInt(23, ac.denyMount ? 1 : 0);
                    insertStmt.setInt(24, ac.denyEnderPearl ? 1 : 0);
                    insertStmt.setInt(25, ac.denyBow ? 1 : 0);
                    insertStmt.setInt(26, ac.denyPotion ? 1 : 0);
                    insertStmt.setInt(27, ac.denyExplosion ? 1 : 0);
                    insertStmt.setInt(28, ac.denyRaid ? 1 : 0);
                    insertStmt.setInt(29, ac.denyFireSpread ? 1 : 0);
                    insertStmt.setInt(30, ac.denyAllEffects ? 1 : 0);
                    insertStmt.setInt(31, ac.denyItemFrame ? 1 : 0);
                    insertStmt.setInt(32, ac.denyMove ? 1 : 0);
                    insertStmt.setInt(33, ac.allowPickup ? 1 : 0);
                    insertStmt.setInt(34, ac.denyFire ? 1 : 0);
                    insertStmt.setInt(35, ac.peaceMode ? 1 : 0);
                    insertStmt.setInt(36, ac.peaceModeDuration / 1000); // 存秒
                    insertStmt.setString(37, String.join(",", ac.peaceWhitelist));
                    insertStmt.setString(38, ac.enforceGameMode != null ? ac.enforceGameMode : "");
                    insertStmt.setString(39, String.join(",", ac.modeExempt));
                    insertStmt.setString(40, ac.enterMsg);
                    insertStmt.setString(41, ac.leaveMsg);
                    insertStmt.setString(42, ac.confiscateMsg);
                    insertStmt.setInt(43, ac.enableAnnounce ? 1 : 0);
                    insertStmt.setString(44, ac.announceTemplate);
                    insertStmt.setString(45, txtContent);
                    insertStmt.setLong(46, System.currentTimeMillis() / 1000);
                    insertStmt.executeUpdate();
                    migrated++;
                    plugin.getLogger().info("[防护] 迁移txt→db: " + name);
                } catch (Exception e) {
                    plugin.getLogger().warning("[防护] 迁移失败: " + f.getName() + " - " + e.getMessage());
                }
            }
            insertStmt.close();

            if (migrated > 0) {
                plugin.getLogger().info("[防护] ★ 迁移完成，共" + migrated + "个区域已写入数据库");
                // 创建迁移完成标记文件，防止重复迁移
                File marker = new File(rootDir, ".db_migrated");
                marker.createNewFile();
                // 创建owner待分配标记文件
                File ownerMarker = new File(rootDir, ".need_owner_assign");
                ownerMarker.createNewFile();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 迁移过程异常: " + e.getMessage());
        }
    }

    /**
     * 将效果列表转为存储字符串
     * 格式: 效果名:等级:秒数|效果名:等级:秒数
     */
    private String effectsToString(List<String[]> effects) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < effects.size(); i++) {
            String[] e = effects.get(i);
            if (i > 0) sb.append("|");
            sb.append(e[0]).append(":").append(e[1]).append(":").append(e[2]);
        }
        return sb.toString();
    }

// ==================== 效果名称中英文映射 ====================

    /**
     * 将中文效果名转为 Bukkit 能识别的英文名
     */
    private String resolveEffectName(String name) {
        if (name == null) return null;
        String n = name.trim();
        // 如果已经是英文或命名空间格式，直接用
        if (n.contains(":")
                || n.equals(n.toUpperCase())) {
            return n;
        }
        // 中文 → 英文映射
        switch (n) {
            case "伤害吸收": return "ABSORPTION";
            case "生命提升": return "HEALTH_BOOST";
            case "抗性提升": return "DAMAGE_RESISTANCE";
            case "抗火": return "FIRE_RESISTANCE";
            case "夜视": return "NIGHT_VISION";
            case "力量": return "INCREASE_DAMAGE";
            case "速度": return "SPEED";
            case "急迫": return "FAST_DIGGING";
            case "跳跃提升": return "JUMP";
            case "再生": return "REGENERATION";
            case "生命恢复": return "REGENERATION";
            case "治疗": return "HEAL";
            case "瞬间治疗": return "HEAL";
            case "伤害药水": return "HARM";
            case "瞬间伤害": return "HARM";
            case "虚弱": return "WEAKNESS";
            case "中毒": return "POISON";
            case "缓慢": return "SLOW";
            case "挖掘疲劳": return "SLOW_DIGGING";
            case "失明": return "BLINDNESS";
            case "隐身": return "INVISIBILITY";
            case "发光": return "GLOWING";
            // 村庄英雄
            case "村庄英雄": return "HERO_OF_THE_VILLAGE";
            case "幸运": return "LUCK";
            case "不幸": return "UNLUCK";
            case "水下呼吸": return "WATER_BREATHING";
            case "水下速掘": return "DOLPHINS_GRACE";
            case "缓降": return "LEVITATION";
            case "漂浮": return "LEVITATION";
            case "饱和": return "SATURATION";
            // 无效效果
            case "伤害": return "HARM";
            case "治疗药水": return "HEAL";
            default:
                // 尝试直接用英文匹配
                return n;
        }
    }

    /**
     * 全量容错：把全角字符统一转半角，
     * 去掉零宽字符/不可见字符，
     * 让用户怎么写都能正确解析。
     */
    private String normalizeLine(String input) {
        if (input == null || input.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 跳过零宽字符和不可见字符
            if (c == '\u200B'   // 零宽空格
                    || c == '\u200C'   // 零宽非连接
                    || c == '\u200D'   // 零宽连接
                    || c == '\uFEFF'   // BOM
                    || c == '\u00A0'   // 不间断空格
                    || c == '\u2028'   // 行分隔符
                    || c == '\u2029'   // 段分隔符
                    || c == '\u200E'   // LTR标记
                    || c == '\u200F'   // RTL标记
                    || c == '\u2060'   // 字连接符
                    || (c >= '\u2000' && c <= '\u200A')) { // 各种空格
                continue;
            }

            // 全角→半角映射
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }

            // 全角空格→半角空格
            if (c == '\u3000') {
                c = ' ';
            }

            sb.append(c);
        }

        return sb.toString();
    }


    private AreaConfig parseArea(String name, File f)
            throws IOException {
        AreaConfig ac = new AreaConfig();
        ac.name = name;
        BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(f),
                        StandardCharsets.UTF_8));
        String line;
        boolean inBlockComment = false;
        boolean inHtmlComment = false;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
// ===== 全量容错预处理：全角→半角，去零宽字符 =====
            line = normalizeLine(line);
            if (line.isEmpty()) continue;
// ===== 暴力调试：输出所有包含关键词的行 =====
            if (line.indexOf("游戏") >= 0
                    || line.indexOf("模式") >= 0
                    || line.indexOf("生存") >= 0
                    || line.indexOf("创造") >= 0
                    || line.indexOf("排除") >= 0
                    || line.indexOf("豁免") >= 0
                    || line.indexOf("强制") >= 0
                    || line.indexOf("GameMode") >= 0
                    || line.indexOf("SURVIVAL") >= 0
                    || line.indexOf("CREATIVE") >= 0) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < line.length(); i++) {
                    hex.append(String.format("U+%04X ", (int) line.charAt(i)));
                }
            /*    plugin.getLogger().info("[暴力调试] 行=[" + line
                        + "] 长度=" + line.length()
                        + " 字符码=" + hex.toString());*/
            }
// ===== 兜底扫描：确保和平白名单一定能解析 =====
            if (line.indexOf("白名单") >= 0
                    || line.indexOf("排除名单") >= 0
                    || line.indexOf("模式排除") >= 0
                    || line.indexOf("模式豁免") >= 0) {
                int ci = line.indexOf(':');
                if (ci < 0) ci = line.indexOf('：');
                if (ci < 0) ci = line.indexOf('=');
                if (ci < 0) ci = line.indexOf(' ');

                // 和平白名单
                if (line.indexOf("和平") >= 0
                        && line.indexOf("白名单") >= 0
                        && ci >= 0) {
                    String rest = line.substring(ci + 1).trim();
                    rest = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : rest.split(",")) {
                        String nm = s.trim();
                        if (!nm.isEmpty()) ac.peaceWhitelist.add(nm);
                    }
                    plugin.getLogger().info("[解析兜底] 和平白名单="
                            + ac.peaceWhitelist);
                }

                // 排除名单
                if (line.indexOf("排除名单") >= 0
                        || line.indexOf("模式排除") >= 0
                        || line.indexOf("模式豁免") >= 0) {
                    if (ci >= 0) {
                        String rest = line.substring(ci + 1).trim();
                        rest = rest.replaceAll("[,，;；|、/]", ",");
                        for (String s : rest.split(",")) {
                            String nm = s.trim();
                            if (!nm.isEmpty()) ac.modeExempt.add(nm);
                        }
                        plugin.getLogger().info("[解析兜底] 排除名单="
                                + ac.modeExempt);
                    }
                }
            }
// ===== 兜底结束 =====

            if (inBlockComment) {
                int endIdx = line.indexOf("*/");
                if (endIdx >= 0) {
                    inBlockComment = false;
                    line = line.substring(endIdx + 2).trim();
                    if (line.isEmpty()) continue;
                } else {
                    continue;
                }
            }
            if (line.startsWith("/*")) {
                int endIdx = line.indexOf("*/", 2);
                if (endIdx >= 0) {
                    line = line.substring(endIdx + 2).trim();
                    if (line.isEmpty()) continue;
                } else {
                    inBlockComment = true;
                    continue;
                }
            }
            if (inHtmlComment) {
                int endIdx = line.indexOf("-->");
                if (endIdx >= 0) {
                    inHtmlComment = false;
                    line = line.substring(endIdx + 3).trim();
                    if (line.isEmpty()) continue;
                } else {
                    continue;
                }
            }
            if (line.startsWith("<!--")) {
                int endIdx = line.indexOf("-->", 4);
                if (endIdx >= 0) {
                    line = line.substring(endIdx + 3).trim();
                    if (line.isEmpty()) continue;
                } else {
                    inHtmlComment = true;
                    continue;
                }
            }
            if (line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int hashIdx = line.indexOf("#");
            if (hashIdx > 0) {
                line = line.substring(0, hashIdx).trim();
            }
            int slashIdx = line.indexOf("//");
            if (slashIdx > 0) {
                line = line.substring(0, slashIdx).trim();
            }
            if (line.isEmpty()) continue;

            if (line.startsWith("起点:")) {
                String[] pp = line.substring("起点:".length())
                        .trim().split(",");
                if (pp.length == 3) {
                    ac.world = pp[0].trim();
                    ac.x1 = Integer.parseInt(pp[1].trim());
                    ac.z1 = Integer.parseInt(pp[2].trim());
                }
            } else if (line.startsWith("终点:")) {
                String[] pp = line.substring("终点:".length())
                        .trim().split(",");
                if (pp.length == 3) {
                    ac.world = pp[0].trim();
                    ac.x2 = Integer.parseInt(pp[1].trim());
                    ac.z2 = Integer.parseInt(pp[2].trim());
                }
            } else if (line.startsWith("高度范围:")) {
                String[] pp = line.substring("高度范围:".length())
                        .trim().split("-");
                if (pp.length == 2) {
                    ac.yMin = Integer.parseInt(pp[0].trim());
                    ac.yMax = Integer.parseInt(pp[1].trim());
                }
            } else if (line.startsWith("没收物品:")) {
                for (String s : line.substring("没收物品:".length())
                        .trim().split("[,，]"))
                    ac.confiscateItems.add(
                            s.trim().toUpperCase());
            } else if (line.startsWith("禁止使用物品:")) {
                for (String s : line.substring("禁止使用物品:".length())
                        .trim().split("[,，]"))
                    ac.denyUseItems.add(
                            s.trim().toUpperCase());
            } else if (line.equals("禁止放置方块")) {
                ac.denyBlockPlace = true;
            } else if (line.equals("禁止破坏方块")) {
                ac.denyBlockBreak = true;
            } else if (line.equals("禁止PVP")) {
                ac.denyPVP = true;
            } else if (line.equals("禁止摔伤")) {
                ac.denyFallDamage = true;
            } else if (line.equals("禁止饥饿")) {
                ac.denyHunger = true;
            } else if (line.equals("禁止一切伤害")) {
                ac.denyAllDamage = true;
            } else if (line.equals("允许丢弃物品")) {
                ac.allowDrop = true;
            } else if (line.equals("禁止末影珍珠")) {
                ac.denyEnderPearl = true;
            } else if (line.equals("禁止使用弓箭")) {
                ac.denyBow = true;
            } else if (line.equals("禁止骑乘")) {
                ac.denyMount = true;
            } else if (line.equals("禁止袭击")) {
                ac.denyRaid = true;
            } else if (line.equals("禁止药水")) {
                ac.denyPotion = true;
            } else if (line.equals("禁止爆炸")) {
                ac.denyExplosion = true;
            } else if (line.equals("禁止燃烧")
                    || line.equals("禁止火焰蔓延")) {
                ac.denyFireSpread = true;
            } else if (line.equals("清除所有效果")
                    || line.equals("清理所有效果")) {
                ac.clearAllBadEffects = true;
            } else if (line.startsWith("清除")
                    || line.startsWith("清理")) {
                String effName = line.substring(2).trim();
                if (effName.endsWith("效果"))
                    effName = effName.substring(0,
                            effName.length() - 2);
                if (!effName.isEmpty())
                    ac.clearEffects.add(effName);
            } else if (line.contains("和平模式时间")) {
                ac.peaceMode = true; // 自动开启和平模式
                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) colonIdx = line.indexOf('：');
                if (colonIdx >= 0) {
                    try {
                        int seconds = Integer.parseInt(line.substring(colonIdx + 1).trim());
                        // 最大1小时=3600秒，最小1秒
                        seconds = Math.max(1, Math.min(3600, seconds));
                        ac.peaceModeDuration = seconds * 1000;
                        plugin.getLogger().info("[解析] 和平模式时间=" + seconds + "秒");
                    } catch (NumberFormatException ignored) {}
                }
            } else if (line.contains("和平模式")
                    && !line.contains("白名单")
                    && !line.contains("定时")
                    && !line.contains("开关")
                    && !line.contains("时间")) {
                ac.peaceMode = true;
                plugin.getLogger().info("[解析] 和平模式已识别");

            } else if (line.startsWith("效果")) {
                String rest = line.substring(2).trim();
                // 去掉前导冒号、空格
                while (rest.length() > 0
                        && (rest.charAt(0) == ':'
                        || rest.charAt(0) == '：'
                        || rest.charAt(0) == ' '
                        || rest.charAt(0) == '\t')) {
                    rest = rest.substring(1);
                }
                if (!rest.isEmpty()) {
                    String[] parts = rest.split("\\s+");
                    // 第一个是效果名（可能是中文）
                    String effName = parts[0];
                    int effLv = 1;
                    int effDur = 999;
                    if (parts.length >= 2) {
                        try {
                            effLv = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {}
                    }
                    if (parts.length >= 3) {
                        try {
                            effDur = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {}
                    }

                    // ★ 用映射方法转为英文名
                    String resolved = resolveEffectName(effName);
                    plugin.getLogger().info("[防护] 解析效果: "
                            + effName + " → " + resolved
                            + " 等级=" + effLv
                            + " 时长=" + effDur);

                    ac.giveEffects.add(new String[]{
                            resolved,
                            String.valueOf(effLv),
                            String.valueOf(effDur)});
                    // ★ 加日志确认解析结果
                    PotionEffectType checkType =
                            resolveEffectType(effName);
                    plugin.getLogger().info("[防护] 解析效果: "
                            + effName + " → type="
                            + (checkType != null
                            ? checkType.getName()
                            : "NULL(失败)")
                            + " 等级=" + effLv
                            + " 时长=" + effDur);


                }
                if (!rest.isEmpty()) {
                    String[] parts = rest.split("\\s+");
                    int effLv = 1;
                    int effDur = 999;
                    if (parts.length >= 2) {
                        try {
                            effLv = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (parts.length >= 3) {
                        try {
                            effDur = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    ac.giveEffects.add(new String[]{
                            parts[0],
                            String.valueOf(effLv),
                            String.valueOf(effDur)});
                }
            } else if (line.startsWith("通报批评:")) {
                ac.announceTemplate = line.substring(
                        "通报批评:".length()).trim();
                ac.enableAnnounce = true;
            } else if (line.startsWith("没收提示:")) {
                ac.confiscateMsg = line.substring(
                        "没收提示:".length()).trim();
            } else if (line.startsWith("进入提示:")) {
                String raw = line.substring("进入提示:".length()).trim();
                ac.enterMsg = raw;

                // ★ 调试：打印原文和Unicode
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    hex.append(String.format("U+%04X ",
                            (int) raw.charAt(i)));
                }
                plugin.getLogger().info(
                        "[防护-解析] ★★★进入提示原文=[" + raw
                                + "] 长度=" + raw.length()
                                + " Unicode=" + hex.toString());
            } else if (line.startsWith("离开提示:")) {
                String raw = line.substring("离开提示:".length()).trim();
                ac.leaveMsg = raw;

                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < raw.length(); i++) {
                    hex.append(String.format("U+%04X ",
                            (int) raw.charAt(i)));
                }
                plugin.getLogger().info(
                        "[防护-解析] ★★★离开提示原文=[" + raw
                                + "] 长度=" + raw.length()
                                + " Unicode=" + hex.toString());
        } else if (line.startsWith("惩罚命令:")) {
                ac.punishCommands.add(line.substring(
                        "惩罚命令:".length()).trim());
            } else if (line.startsWith("强制游戏模式:")
                    || line.startsWith("游戏模式:")) {
                String prefix = line.startsWith("强制游戏模式:")
                        ? "强制游戏模式:" : "游戏模式:";
                String modeStr = line.substring(prefix.length())
                        .trim().toUpperCase();
                // 容错：支持多种写法
                if (modeStr.equals("CREATIVE") || modeStr.equals("创造")
                        || modeStr.equals("1")) {
                    ac.enforceGameMode = "CREATIVE";
                } else if (modeStr.equals("SURVIVAL") || modeStr.equals("生存")
                        || modeStr.equals("0")) {
                    ac.enforceGameMode = "SURVIVAL";
                } else if (modeStr.equals("ADVENTURE") || modeStr.equals("冒险")
                        || modeStr.equals("2")) {
                    ac.enforceGameMode = "ADVENTURE";
                } else if (modeStr.equals("SPECTATOR") || modeStr.equals("旁观")
                        || modeStr.equals("spectator")
                        || modeStr.equals("3")) {
                    ac.enforceGameMode = "SPECTATOR";
                }
            } else if (line.contains("排除名单")
                    || line.contains("模式排除")
                    || line.contains("模式豁免")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) colonIdx = line.indexOf('：');
                if (colonIdx < 0) colonIdx = line.indexOf('=');
                if (colonIdx >= 0) {
                    String rest = line.substring(colonIdx + 1).trim();
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String playerName = s.trim();
                        if (!playerName.isEmpty()) {
                            ac.modeExempt.add(playerName);
                        }
                    }
                }

            String rest = line.substring(/*idx*/ + 4).trim();
                while (rest.length() > 0) {
                    char c = rest.charAt(0);
                    if (c == ':' || c == '：' || c == '='
                            || c == ' ' || c == '\t') {
                        rest = rest.substring(1);
                    } else {
                        break;
                    }
                }
                if (!rest.isEmpty()) {
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String playername = s.trim();
                        if (!name.isEmpty()) ac.modeExempt.add(name);
                    }
                }

            while (rest.length() > 0) {
                    char c = rest.charAt(0);
                    if (c == ':' || c == '：' || c == '='
                            || c == ' ' || c == '\t') {
                        rest = rest.substring(1);
                    } else {
                        break;
                    }

                }
                if (!rest.isEmpty()) {
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String wlName = s.trim();
                        if (!wlName.isEmpty()) {
                            ac.peaceWhitelist.add(wlName);
                        }
                    }
                }



        } else if (line.equals("禁止交互展示框")
                    || line.equals("禁止展示框")) {
                ac.denyItemFrame = true;

        }
        }
        br.close();
        return ac;
    }
// ===== 和平白名单操作 =====

    // 添加生物名字到区域和平白名单
    public boolean addPeaceName(String areaName, String creatureName) {
        AreaConfig ac = areas.get(areaName);
        if (ac == null) return false;
        ac.peaceWhitelist.add(creatureName);
        savePeaceWhitelist(ac);
        return true;
    }

    // 从区域和平白名单移除生物名字
    public boolean removePeaceName(String areaName, String creatureName) {
        AreaConfig ac = areas.get(areaName);
        if (ac == null) return false;
        ac.peaceWhitelist.remove(creatureName);
        savePeaceWhitelist(ac);
        return true;
    }

// ===== 模式排除名单操作 =====


    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Entity entity = e.getEntity();
        if (!isHostile(entity.getType().name())) return;

        Location loc = entity.getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ());

        if (ac == null || !ac.peaceMode) return;

        protectedEntities.put(
                entity.getUniqueId(),
                System.currentTimeMillis() + ac.peaceModeDuration);

       /* plugin.getLogger().info("[和平] "
                + entity.getType().name()
                + " 获得5秒保护期"
                + " 区域=" + ac.name
                + " 白名单=" + ac.peaceWhitelist
                + " 白名单数量=" + ac.peaceWhitelist.size());*/
    }


    public void cleanupProtectedEntities() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        Iterator<Map.Entry<UUID, Long>> it =
                protectedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now > entry.getValue()) {
                it.remove();
                cleaned++;
            }
        }
        if (cleaned > 0) {
       /*     plugin.getLogger().info("[和平模式] 清理过期保护: "
                    + cleaned + "个 剩余:"
                    + protectedEntities.size() + "个");*/
        }
    }


    public void banHostilesWithWhitelist(Player p, AreaConfig ac) {
        long now = System.currentTimeMillis();
        List<Entity> nearby = p.getNearbyEntities(48, 48, 48);
        int scanned = 0;
        int waiting = 0;
        int saved = 0;
        int banished = 0;

        for (Entity ent : nearby) {
            if (!isHostile(ent.getType().name())) continue;
            scanned++;

            UUID entUid = ent.getUniqueId();
            Long protectUntil = protectedEntities.get(entUid);
            String customName = ent.getCustomName();

            // 白名单命名生物：永久保留（含保护期内贴标的）
            if (customName != null && !customName.isEmpty() && ac.peaceWhitelist.contains(customName)) {
                if (protectUntil != null) {
                    protectedEntities.remove(entUid); // ★ 取消清理倒计时
                    // ★ 保护期内贴标：立即确认
                    if (protectUntil - now > 0) {
                        p.sendMessage("§a§l[和平模式] §f" + ent.getType().name() + " §a已被白名单保护，永久保留");
                    }
                }
                saved++;
                continue;
            }

            // 有保护期记录
            if (protectUntil != null) {
                long remain = protectUntil - now;
                if (remain > 0) {
                    waiting++;
                    continue;
                }
                // 保护期到期 → 移除记录并检查白名单（最后一刻贴标也有效）
                protectedEntities.remove(entUid);
                if (customName != null && !customName.isEmpty() && ac.peaceWhitelist.contains(customName)) {
                    saved++;
                    continue;
                }
                banEntity(ent);
                banished++;
                continue;
            }

            // 无保护期记录 → 未命名敌对生物，立即清理
            banEntity(ent);
            banished++;
        }
    }

    /**
     * 全图扫描清理和平模式下的未命名敌对生物
     * 由 Main 中的定时任务调用
     */
    public void scanAllLandsPeaceMode() {
        long now = System.currentTimeMillis();
        int cleared = 0;

        // 遍历所有已加载的区域
        for (Map.Entry<String, AreaConfig> entry : areas.entrySet()) {
            AreaConfig ac = entry.getValue();
            if (!ac.peaceMode) continue;

            // 对该区域边界内的实体扫描
            double centerX = (ac.x1 + ac.x2) / 2.0;
            double centerZ = (ac.z1 + ac.z2) / 2.0;
            World world = Bukkit.getWorld(ac.world);
            if (world == null) continue;

            // ★ 根据领地实际大小计算扫描范围（取x和z范围的最大值，加边距）
            int rangeX = Math.abs(ac.x2 - ac.x1) / 2 + 10;
            int rangeZ = Math.abs(ac.z2 - ac.z1) / 2 + 10;
            int range = Math.max(rangeX, rangeZ);
            // 限制最大扫描范围避免性能问题
            range = Math.min(range, 200);

            // ★ 使用世界实际高度范围
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int heightRange = (maxY - minY) / 2;

            List<Entity> nearby = new java.util.ArrayList<>(world.getNearbyEntities(
                    new Location(world, centerX, (minY + maxY) / 2.0, centerZ), range, heightRange, range));

            for (Entity ent : nearby) {
                if (!isHostile(ent.getType().name())) continue;

                // 检查是否在领地范围内
                AreaConfig landAc = getArea(world.getName(),
                        ent.getLocation().getBlockX(),
                        ent.getLocation().getBlockY(),
                        ent.getLocation().getBlockZ());
                if (landAc == null || !landAc.peaceMode) continue;

                // 白名单命名生物：跳过
                String cname = ent.getCustomName();
                if (cname != null && !cname.isEmpty() && landAc.peaceWhitelist.contains(cname)) continue;

                // 保护期内：跳过
                UUID uid = ent.getUniqueId();
                Long protectUntil = protectedEntities.get(uid);
                if (protectUntil != null && (protectUntil - now) > 0) continue;

                // ★ 直接删除实体（传送可能卡住或重新刷新）
                ent.remove();
                cleared++;
            }
        }

        if (cleared > 0) {
            plugin.getLogger().info("[和平模式扫描] 清理未命名敌对生物: " + cleared + " 个");
        }
    }

    private void banEntity(Entity ent) {
        Location voidLoc = ent.getLocation().clone();
        voidLoc.setY(ent.getWorld().getMinHeight() - 50);
        if ("ENDERMAN".equals(ent.getType().name())) {
            ent.remove();
        } else {
            ent.teleport(voidLoc);
        }
        protectedEntities.remove(ent.getUniqueId());
    }

    private static final Set<String> HOSTILE_TYPES = new HashSet<>(Arrays.asList(
            "ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED",
            "SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON",
            "CREEPER", "SPIDER", "CAVE_SPIDER", "ENDERMITE",
            "SILVERFISH", "BLAZE", "GHAST", "BIG_GHAST",
            "MAGMA_CUBE", "SLIME", "WITCH",
            "GUARDIAN", "ELDER_GUARDIAN",
            "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER",
            "ILLUSIONER", "VEX",
            "PHANTOM", "SHULKER", "WARDEN", "BREEZE",
            "PIGLIN", "PIGLIN_BRUTE", "ZOGLIN",
            "ZOMBIFIED_PIGLIN", "ENDER_DRAGON",
            "ENDERMAN", "WITHER"
    ));

    private boolean isHostile(String typeName) {
        return HOSTILE_TYPES.contains(typeName);
    }

    // ==================== 白名单持久化 ====================

    /**
     * 保存白名单到文件
     * 路径：区域防护/whitelists/全局白名单.txt
     * 路径：区域防护/whitelists/区域名.txt
     */
    public void saveWhitelists() {
        try {
            // 1. 保存全局白名单
            PrintWriter pwGlobal = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(globalWhitelistFile),
                            StandardCharsets.UTF_8));
            for (String name : globalPlayerWhitelist) {
                pwGlobal.println(name);
            }
            pwGlobal.close();

            // 2. 删除旧的区域白名单文件（防止残留）
            File[] oldFiles = whitelistDir.listFiles(
                    (d, n) -> n.endsWith(".txt")
                            && !n.equals("全局白名单.txt"));
            if (oldFiles != null) {
                for (File f : oldFiles) f.delete();
            }

            // 3. 保存各区域白名单
            for (Map.Entry<String, Set<String>> entry
                    : areaPlayerWhitelist.entrySet()) {
                String name = entry.getKey();
                File areaFile = new File(
                        whitelistDir, name + ".txt");
                PrintWriter pwArea = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(areaFile),
                                StandardCharsets.UTF_8));
                for (String playerName : entry.getValue()) {
                    pwArea.println(playerName);
                }
                pwArea.close();
            }

            plugin.getLogger().info("[防护] 白名单已保存");
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[防护] 保存白名单失败: "
                            + e.getMessage());
        }
    }



    // ==================== 区域检测 ====================

    public AreaConfig getArea(String worldName,
                              int x, int y, int z) {
        for (AreaConfig ac : areas.values()) {
            if (ac.world.equalsIgnoreCase(worldName)
                    && inRect(x, z, ac)
                    && y >= ac.yMin
                    && y <= ac.yMax) {
                return ac;
            }
        }
        return null;
    }

    private boolean inRect(int x, int z, AreaConfig ac) {
        int minX = Math.min(ac.x1, ac.x2);
        int maxX = Math.max(ac.x1, ac.x2);
        int minZ = Math.min(ac.z1, ac.z2);
        int maxZ = Math.max(ac.z1, ac.z2);
        return x >= minX && x <= maxX
                && z >= minZ && z <= maxZ;
    }

    /**
     * 获取玩家在指定领地的权限级别
     */
    public PermissionLevel getPermissionLevel(Player player, AreaConfig ac) {
        // 1. 全局管理员（ScoreboardTag）
        if (isAreaAdmin(player)) {
            return PermissionLevel.ADMIN;
        }

        // 2. 领地所有者权限
        if (ac != null && ac.owner != null && !ac.owner.isEmpty()) {
            if (player.getName().equalsIgnoreCase(ac.owner)) {
                return PermissionLevel.OWNER;
            }
        }

        // 3. 领地管理员（DB role='admin'，通过/protect setadmin设置）
        // ★ 必须在VISITOR检查之前，否则白名单优先级会覆盖管理员身份
        if (ac != null && isLandAdmin(ac.name, player.getName())) {
            return PermissionLevel.ADMIN;
        }

        // 4. 访客权限（白名单 + 数据库购买权限）
        if (isPlayerWhitelisted(player.getName(), ac)) {
            return PermissionLevel.VISITOR;
        }

        // 5. 数据库购买的访客权限（未过期）
        if (hasValidVisitorPermission(player, ac)) {
            return PermissionLevel.VISITOR;
        }

        // 6. 无权限
        return null;
    }

    /**
     * 检查玩家在领地是否有足够权限执行操作
     * @param requiredLevel 所需的最低权限级别
     * @return true如果有足够权限
     */
    public boolean hasPermission(Player player, AreaConfig ac, PermissionLevel requiredLevel) {
        PermissionLevel playerLevel = getPermissionLevel(player, ac);
        if (playerLevel == null) return false;

        // ADMIN拥有所有权限
        if (playerLevel == PermissionLevel.ADMIN) return true;

        // OWNER可以执行VISITOR能执行的操作
        if (playerLevel == PermissionLevel.OWNER) {
            return requiredLevel != PermissionLevel.ADMIN;
        }

        // VISITOR只能执行VISITOR级别的操作
        return requiredLevel == PermissionLevel.VISITOR;
    }

    // ===== Per-Player 独立权限系统 =====

    /**
     * 获取玩家在指定领地的独立权限JSON
     * 格式: {"denyMove":true,"denyPVP":false,...}
     * 空字符串或null表示没有自定义权限（使用领地默认）
     */
    public String getPlayerPermJson(int landId, String playerName) {
        if (dbConnection == null) return "";
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT permissions FROM area_land_permissions "
                            + "WHERE land_id = ? AND player_name = ?");
            stmt.setInt(1, landId);
            stmt.setString(2, playerName);
            ResultSet rs = stmt.executeQuery();
            String result = "";
            if (rs.next()) {
                result = rs.getString("permissions");
                if (result == null) result = "";
            }
            rs.close();
            stmt.close();
            return result;
        } catch (SQLException e) {
            return "";
        }
    }

    /**
     * 设置玩家在指定领地的独立权限JSON
     */
    public void setPlayerPermJson(int landId, String playerName, String permJson) {
        if (dbConnection == null) return;
        try {
            // ★ 先尝试UPDATE，如果没有行则INSERT
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "UPDATE area_land_permissions SET permissions = ? "
                            + "WHERE land_id = ? AND player_name = ?");
            stmt.setString(1, permJson != null ? permJson : "");
            stmt.setInt(2, landId);
            stmt.setString(3, playerName);
            int affected = stmt.executeUpdate();
            stmt.close();
            if (affected == 0) {
                // 不存在行，先插入一条记录再UPDATE
                PreparedStatement ins = dbConnection.prepareStatement(
                        "INSERT INTO area_land_permissions (land_id, player_name, role, permissions, granted_at) "
                                + "VALUES (?, ?, 'member', '', ?)");
                ins.setInt(1, landId);
                ins.setString(2, playerName);
                ins.setLong(3, System.currentTimeMillis() / 1000);
                ins.executeUpdate();
                ins.close();
                // 再次UPDATE
                PreparedStatement upd = dbConnection.prepareStatement(
                        "UPDATE area_land_permissions SET permissions = ? "
                                + "WHERE land_id = ? AND player_name = ?");
                upd.setString(1, permJson != null ? permJson : "");
                upd.setInt(2, landId);
                upd.setString(3, playerName);
                upd.executeUpdate();
                upd.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 设置玩家权限失败: " + e.getMessage());
        }
    }

    /**
     * 获取玩家独立权限（解析JSON为Map）
     */
    public Map<String, Boolean> getPlayerPermMap(int landId, String playerName) {
        Map<String, Boolean> map = new HashMap<>();
        String json = getPlayerPermJson(landId, playerName);
        if (json == null || json.isEmpty()) return map;
        // 简单解析 {"key":true,"key2":false}
        String clean = json.trim();
        if (clean.startsWith("{")) clean = clean.substring(1);
        if (clean.endsWith("}")) clean = clean.substring(0, clean.length() - 1);
        if (clean.isEmpty()) return map;
        for (String pair : clean.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                boolean val = Boolean.parseBoolean(kv[1].trim().replace("\"", ""));
                map.put(key, val);
            }
        }
        return map;
    }

    /**
     * 设置玩家独立权限中的单个权限项
     * @param permName 权限名（如 "denyMove"）
     * @param enabled true=启用限制（deny），false=取消限制（允许）
     */
    public void setPlayerPerm(int landId, String playerName, String permName, boolean enabled) {
        Map<String, Boolean> map = getPlayerPermMap(landId, playerName);
        map.put(permName, enabled);
        String json = mapToJson(map);
        setPlayerPermJson(landId, playerName, json);
    }

    /**
     * 获取所有有独立权限的成员列表
     */
    public List<Map<String, Object>> getLandMembersWithPerms(int landId) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (dbConnection == null) return list;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT player_name, role, permissions FROM area_land_permissions "
                            + "WHERE land_id = ?");
            stmt.setInt(1, landId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("player", rs.getString("player_name"));
                entry.put("role", rs.getString("role"));
                entry.put("permissions", rs.getString("permissions"));
                list.add(entry);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            // 忽略
        }
        return list;
    }

    /**
     * 获取所有领地的成员权限数据（用于同步到PHP端）
     */
    public List<Map<String, Object>> getAllPermsForSync() {
        List<Map<String, Object>> list = new ArrayList<>();
        if (dbConnection == null) return list;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT p.id, p.land_id, l.name AS land_name, p.player_name, p.role, "
                            + "p.permissions, p.granted_at, p.expires_at "
                            + "FROM area_land_permissions p "
                            + "JOIN area_lands l ON p.land_id = l.id "
                            + "ORDER BY p.land_id, p.player_name");
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("land_id", rs.getInt("land_id"));
                entry.put("land_name", rs.getString("land_name"));
                entry.put("player_name", rs.getString("player_name"));
                entry.put("role", rs.getString("role"));
                entry.put("permissions", rs.getString("permissions"));
                entry.put("granted_at", rs.getLong("granted_at"));
                entry.put("expires_at", rs.getLong("expires_at"));
                list.add(entry);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] getAllPermsForSync失败: " + e.getMessage());
        }
        return list;
    }

    /**
     * 检查玩家是否是某领地的管理员（role='admin'）
     */
    public boolean isLandAdmin(String landName, String playerName) {
        int landId = getLandIdFromDb(landName);
        if (landId <= 0) return false;
        if (dbConnection == null) return false;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT role FROM area_land_permissions WHERE land_id = ? AND player_name = ?");
            stmt.setInt(1, landId);
            stmt.setString(2, playerName);
            ResultSet rs = stmt.executeQuery();
            boolean isAdmin = false;
            if (rs.next()) {
                isAdmin = "admin".equalsIgnoreCase(rs.getString("role"));
            }
            rs.close();
            stmt.close();
            return isAdmin;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 设置/取消玩家的领地管理员身份
     */
    public void setLandAdmin(String landName, String playerName, boolean admin) {
        int landId = getLandIdFromDb(landName);
        if (landId <= 0) return;
        if (dbConnection == null) return;
        try {
            String role = admin ? "admin" : "member";
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "INSERT INTO area_land_permissions (land_id, player_name, role, permissions, granted_at) "
                            + "VALUES (?, ?, ?, '', ?) "
                            + "ON CONFLICT(land_id, player_name) DO UPDATE SET role = ?");
            stmt.setInt(1, landId);
            stmt.setString(2, playerName);
            stmt.setString(3, role);
            stmt.setLong(4, System.currentTimeMillis() / 1000);
            stmt.setString(5, role);
            stmt.executeUpdate();
            stmt.close();
        } catch (SQLException e) {
            // 忽略
        }
    }

    /**
     * ★ 核心方法：获取玩家的effective deny状态
     * 优先级：per-player权限 > 领地默认权限
     * @param permName 权限字段名（如 "denyMove"）
     * @return true=限制生效，false=允许
     */
    public boolean getEffectiveDeny(Player player, AreaConfig ac, String permName) {
        // ADMIN/OWNER不检查per-player deny
        PermissionLevel level = getPermissionLevel(player, ac);
        if (level == PermissionLevel.ADMIN || level == PermissionLevel.OWNER) {
            return false;
        }

        // 尝试读取per-player权限
        int landId = getLandIdFromDb(ac.name);
        if (landId > 0) {
            Map<String, Boolean> playerPerms = getPlayerPermMap(landId, player.getName());
            if (playerPerms.containsKey(permName)) {
                return playerPerms.get(permName);
            }
        }

        // 回退到领地默认权限
        return getLandDefaultDeny(ac, permName);
    }

    /**
     * 获取领地默认deny状态（按字段名映射）
     */
    private boolean getLandDefaultDeny(AreaConfig ac, String permName) {
        switch (permName) {
            case "denyMove": return ac.denyMove;
            case "denyBlockPlace": return ac.denyBlockPlace;
            case "denyBlockBreak": return ac.denyBlockBreak;
            case "denyContainer": return ac.denyContainer;
            case "denyPVP": return ac.denyPVP;
            case "denyFallDamage": return ac.denyFallDamage;
            case "denyHunger": return ac.denyHunger;
            case "denyAllDamage": return ac.denyAllDamage;
            case "denyDrop": return !ac.allowDrop;
            case "denyMount": return ac.denyMount;
            case "denyEnderPearl": return ac.denyEnderPearl;
            case "denyBow": return ac.denyBow;
            case "denyPotion": return ac.denyPotion;
            case "denyExplosion": return ac.denyExplosion;
            case "denyRaid": return ac.denyRaid;
            case "denyFireSpread": return ac.denyFireSpread;
            case "denyAllEffects": return ac.denyAllEffects;
            case "denyItemFrame": return ac.denyItemFrame;
            case "denyPickup": return !ac.allowPickup;
            case "denyFire": return ac.denyFire;
            case "denyThrownProjectiles": return ac.denyThrownProjectiles;
            case "denyGlowing": return ac.denyGlowing;
            case "denyRedstoneInteraction": return ac.denyRedstoneInteraction;
            case "denyDoorInteraction": return ac.denyDoorInteraction;
            case "denyNoteblockJukebox": return ac.denyNoteblockJukebox;
            case "denyLead": return ac.denyLead;
            case "denyCropHarvest": return ac.denyCropHarvest;
            case "denyWoolShear": return ac.denyWoolShear;
            case "denyAnimalFeeding": return ac.denyAnimalFeeding;
            case "denyMobAttack": return ac.denyMobAttack;
            case "peaceMode": return ac.peaceMode;
            default: return false;
        }
    }

    /**
     * Map转简单JSON
     */
    private String mapToJson(Map<String, Boolean> map) {
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Boolean> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 获取指定领地的成员列表
     */
    public Set<String> getAreaMembers(String areaName) {
        Set<String> members = areaPlayerWhitelist.get(areaName);
        return members != null ? new HashSet<>(members) : new HashSet<>();
    }

    // ===== 统一白名单检查（供所有事件处理器使用）=====

    /**
     * 检查玩家是否在任意白名单中（全局白名单 + 区域白名单）
     * 这是所有白名单判断的唯一入口
     */
    private boolean isPlayerWhitelisted(String player,
                                        AreaConfig ac) {
        String lowerName = player.toLowerCase();
        // 1. 全局白名单
        if (globalPlayerWhitelist.contains(lowerName))
            return true;
        // 2. 区域白名单
        if (ac != null) {
            Set<String> areaList =
                    areaPlayerWhitelist.get(ac.name);
            if (areaList != null
                    && areaList.contains(lowerName))
                return true;
        }
        return false;
    }

    /**
     * 快速检查：仅全局白名单（不带 AreaConfig）
     */
    private boolean isGlobalWhite(String player) {
        return globalPlayerWhitelist.contains(player.toLowerCase());
    }

    /**
     * 快速检查：仅区域白名单
     */
    private boolean isAreaWhite(String player, String areaName) {
        Set<String> areaList =
                areaPlayerWhitelist.get(areaName);
        return areaList != null
                && areaList.contains(player.toLowerCase());
    }

    /**
     * 检查玩家是否为区域防护管理员
     * 与 Main.isAdmin() 使用相同的 ScoreboardTag 机制
     */
    public boolean isAreaAdmin(Player player) {
        ConfigManager cfg = plugin.getConfigMgr();
        if (cfg == null) return false;

        String tag = cfg.areaProtectAdminTag;
        if (tag == null || tag.isEmpty()) return false;

        // 使用ScoreboardTag验证（与Main.isAdmin()一致）
        try {
            return player.getScoreboardTags().contains(tag);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查CommandSender是否为区域防护管理员（支持控制台）
     */
    public boolean isAreaAdmin(CommandSender sender) {
        if (sender instanceof Player) {
            return isAreaAdmin((Player) sender);
        }
        // 控制台视为管理员
        return true;
    }

    /**
     * 自动将无owner的txt迁移领地分配给第一个操作的管理员
     */
    private void assignUnownedLandsToAdmin(Player admin) {
        File ownerMarker = new File(rootDir, ".need_owner_assign");
        if (!ownerMarker.exists()) return;

        String adminName = admin.getName();
        int assigned = 0;

        for (AreaConfig ac : areas.values()) {
            if (ac.owner == null || ac.owner.isEmpty()) {
                ac.owner = adminName;
                saveAreaToDb(ac);
                assigned++;
            }
        }

        // 删除标记文件
        ownerMarker.delete();

        if (assigned > 0) {
            admin.sendMessage("§a§l[防护] 已将 " + assigned + " 个无主领地分配给你");
            plugin.getLogger().info("[防护] txt迁移领地已分配给管理员: " + adminName + " 共" + assigned + "个");
        }
    }

    private boolean isItemBlacklisted(String itemId,
                                      AreaConfig ac) {
        if (globalItemBlacklist.contains(
                itemId.toUpperCase()))
            return true;
        Set<String> areaList =
                areaItemBlacklist.get(ac.name);
        return areaList != null
                && areaList.contains(itemId.toUpperCase());
    }

    // ==================== 没收 ====================

    private int confiscateItem(Player p, String itemTypeId) {
        Material mat = Material.matchMaterial(itemTypeId);
        if (mat == null) return 0;
        ItemStack[] contents = p.getInventory().getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null
                    && contents[i].getType() == mat) {
                removed += contents[i].getAmount();
                contents[i] = null;
            }
        }
        p.getInventory().setContents(contents);
        return removed;
    }

    // ==================== 边框显示 ====================
    private void showBorder(Player p, AreaConfig ac, boolean temp) {
        cancelBorder(p);
        int taskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!p.isOnline()) return;
                        Location loc = p.getLocation();
                        int px = loc.getBlockX();
                        int py = loc.getBlockY();
                        int pz = loc.getBlockZ();
                        int minX = Math.min(ac.x1, ac.x2);
                        int maxX = Math.max(ac.x1, ac.x2);
                        int minZ = Math.min(ac.z1, ac.z2);
                        int maxZ = Math.max(ac.z1, ac.z2);
                        for (int dy = 5; dy <= 30; dy += 5) {
                            for (int x = minX; x <= maxX; x += 4) {
                                for (int z = minZ; z <= maxZ; z += 4) {
                                    if (x == minX || x == maxX
                                            || z == minZ || z == maxZ) {
                                        p.spawnParticle(Particle.END_ROD,
                                                x + 0.5, py + dy, z + 0.5,
                                                1, 0, 0, 0, 0);
                                    }
                                }
                            }
                        }
                    }
                }, 0L, 20L).getTaskId();
        displayTaskIds.put(p.getUniqueId(), taskId);  // taskId 是 int
        if (temp) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> cancelBorder(p), 300L);
        }
    }


    private void cancelBorder(Player p) {
        Integer taskId = displayTaskIds.remove(p.getUniqueId());
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public void onPlayerQuit(String playerName) {
        playerCurrentArea.remove(playerName);
    }

    public void clearAllPlayerAreas() {
        playerCurrentArea.clear();
    }
    private void saveModeExempt(AreaConfig ac) {
        File file = new File(rootDir, ac.name + ".txt");
        if (!file.exists()) return;

        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new FileReader(file, StandardCharsets.UTF_8));
            String line;
            boolean found = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("排除名单")
                        || trimmed.contains("模式排除")
                        || trimmed.contains("模式豁免")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("排除名单:");
                    boolean first = true;
                    for (String name : ac.modeExempt) {
                        if (!first) sb.append(",");
                        sb.append(name);
                        first = false;
                    }
                    lines.add(sb.toString());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
            reader.close();

            if (!found && !ac.modeExempt.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("排除名单:");
                boolean first = true;
                for (String name : ac.modeExempt) {
                    if (!first) sb.append(",");
                    sb.append(name);
                    first = false;
                }
                lines.add(sb.toString());
            }

            FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8);
            for (String l : lines) {
                writer.write(l + "\n");
            }
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "[防护] 保存模式排除名单失败: " + e.getMessage());
        }
    }

    // ==================== 事件监听 ====================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        UUID uid = p.getUniqueId();
        Location to = event.getTo();
        if (to == null) return;

        Location from = event.getFrom();
        // X/Z debounce removed to allow denyMove teleport for small movements

        // 防抖
        Long lastTime = lastMoveProcess.get(uid);
        long now = System.currentTimeMillis();
        if (lastTime != null
                && now - lastTime < MOVE_DEBOUNCE_MS) {
            return;
        }
        lastMoveProcess.put(uid, now);

        // 检测区域
        String oldArea = playerCurrentArea.get(uid);
        String newArea = null;
        String forcedMode = null;

        for (Map.Entry<String, AreaConfig> entry
                : areas.entrySet()) {
            AreaConfig ac = entry.getValue();
            if (!ac.world.equals(to.getWorld().getName()))
                continue;
            int px = to.getBlockX();
            int pz = to.getBlockZ();
            int py = to.getBlockY();
            int minX = Math.min(ac.x1, ac.x2);
            int maxX = Math.max(ac.x1, ac.x2);
            int minZ = Math.min(ac.z1, ac.z2);
            int maxZ = Math.max(ac.z1, ac.z2);
            if (px >= minX && px <= maxX
                    && pz >= minZ && pz <= maxZ
                    && py >= ac.yMin && py <= ac.yMax) {
                newArea = entry.getKey();
                forcedMode = ac.enforceGameMode;
                break;
            }
        }

     /*   plugin.getLogger().info(
               "[防护-移动] " + p.getName()
                        + " 旧=[" + oldArea
                        + "] 新=[" + newArea + "]"
                        + " areas.size=" + areas.size()
                        + " world=" + to.getWorld().getName());*/

        // ===== 2. 区域没变 =====
        if (Objects.equals(oldArea, newArea)) {
            if (newArea != null) {
                AreaConfig ac = areas.get(newArea);
                if (ac != null) {
                    // ★ denyMove检查：支持per-player独立权限
                    if (getEffectiveDeny(p, ac, "denyMove")) {
                        Location safeLoc = findSafeExitLocation(p.getLocation(), ac);
                        if (safeLoc != null) {
                            p.teleport(safeLoc);
                            p.sendMessage("§c§l[区域防护] §f你不具备此领地的移动权限，已被传送出去");
                        } else {
                            p.teleport(p.getWorld().getSpawnLocation());
                            p.sendMessage("§c§l[区域防护] §f你不具备此领地的移动权限");
                        }
                        return;
                    }

                    // ★ 每次移动都清除指定效果
                    clearBadEffects(p, ac);

                    // ★ 每次移动都强制游戏模式
                    if (ac.enforceGameMode != null
                            && !ac.enforceGameMode.isEmpty()
                            && !isExemptFromGameMode(p, newArea)) {
                        GameMode target =
                                GameMode.valueOf(ac.enforceGameMode);
                        if (p.getGameMode() != target) {
                            p.setGameMode(target);
                        }
                    }
                }
            }
            return;
        }

        // 离开旧区域
        if (oldArea != null) {
          /*  plugin.getLogger().info(
                "[防护-移动-离开] ★★★开始离开处理:
                            + oldArea);*/

            AreaConfig oldAc = areas.get(oldArea);

            // 离开提示
            if (oldAc != null && oldAc.leaveMsg != null
                    && !oldAc.leaveMsg.isEmpty()) {
                p.sendMessage(formatAreaMsg(oldAc.leaveMsg));
            }

            // 收标
            List<PotionEffectType> marked =
                    playerMarkedEffects.remove(uid);

        /*    plugin.getLogger().info(
                    "[防护-移动-离开] ★★★收标: "
                            + (marked != null ? marked.size() : "null"));*/

            if (marked != null) {
                for (PotionEffectType t : marked) {
                    plugin.getLogger().info(
                            "[防护-移动-离开] 标记项: " + t.getName());
                }
            }

            // 取消旧延时
            if (pendingClearTask.containsKey(uid)) {
                pendingClearTask.get(uid).cancel();
                pendingClearTask.remove(uid);
                pendingClearArea.remove(uid);
                pendingClearEffects.remove(uid);
                plugin.getLogger().info(
                        "[防护-移动-离开] 取消旧延时");
            }

            // 创建延时清理
            if (marked != null && !marked.isEmpty()) {
                String clearArea = oldArea;
                UUID clearUid = uid;
                List<PotionEffectType> toClear =
                        new ArrayList<>(marked);
                pendingClearArea.put(uid, clearArea);
                pendingClearEffects.put(uid, toClear);
             /*   plugin.getLogger().info(
                        "[防护-移动-离开] ★★★创建延时任务: "
                                + toClear.size() + "个");*/

                BukkitTask task = new BukkitRunnable() {
                    @Override
                    public void run() {
                    /*    plugin.getLogger().info(
                                "[防护-移动-清理] ★★★延时触发!");*/
                        pendingClearTask.remove(clearUid);
                        pendingClearArea.remove(clearUid);

                        Player online =
                                Bukkit.getPlayer(clearUid);

                        plugin.getLogger().info(
                                "[防护-移动-清理] 在线="
                                        + (online != null && online.isOnline())
                                        + " 效果数=" + toClear.size());

                        if (online == null || !online.isOnline()) {
                            removePlayerEffects(
                                    clearUid, clearArea);
                            return;
                        }

                        int cleared = 0;
                        for (PotionEffectType type : toClear) {
                            PotionEffect eff =
                                    online.getPotionEffect(type);
                 /*           plugin.getLogger().info(
                                    "[防护-移动-清理] 检查: "
                                            + type.getName()
                                            + " 身上=" + (eff != null));*/
                            if (eff != null) {
                                online.removePotionEffect(type);
                                cleared++;
                            }
                        }
                        removePlayerEffects(clearUid, clearArea);
                        plugin.getLogger().info(
                                "[防护-移动-清理] + cleared" + toClear.size());
                    }
                }.runTaskLater(plugin, 100L); // 5秒 = 100 tick
                pendingClearTask.put(uid, task);
            }
        }


        // 进入新区域
        if (newArea != null) {
            if (pendingClearTask.containsKey(uid)) {
                pendingClearTask.get(uid).cancel();
                pendingClearTask.remove(uid);
                String oldP = pendingClearArea.remove(uid);
                if (oldP != null) {
                    diffEffects(p, uid, oldP, newArea);
                }
            }

            AreaConfig newAc = areas.get(newArea);
            if (newAc != null) {
                // ★ denyMove检查：支持per-player独立权限
                if (getEffectiveDeny(p, newAc, "denyMove")) {
                    Location safeLoc = findSafeExitLocation(event.getFrom(), newAc);
                    if (safeLoc != null) {
                        p.teleport(safeLoc);
                        p.sendMessage("§c§l[区域防护] §f你不具备此领地的移动权限，已被传送出去");
                    } else {
                        p.teleport(p.getWorld().getSpawnLocation());
                        p.sendMessage("§c§l[区域防护] §f你不具备此领地的移动权限");
                    }
                    return;
                }

                if (newAc.enterMsg != null
                        && !newAc.enterMsg.isEmpty()) {
                    p.sendMessage(
                            formatAreaMsg(newAc.enterMsg));
                }
                // ★ 公共建筑设施：访客自动获得传送、免疫伤害、攻击敌对生物权限
                if (newAc.isPublicBuilding && !p.getName().equalsIgnoreCase(newAc.owner) && !isAreaAdmin(p)) {
                    p.sendMessage("§a§l[公共建筑] §f欢迎来到公共设施: §e" + newAc.name);
                    p.sendMessage("§7你在此区域享有: 传送、免疫伤害、攻击敌对生物权限");
                    // 暂时移除伤害限制：给玩家5秒免疫效果
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.RESISTANCE, 100, 255, false, false, true));
                }
                applyRegionEffects(p, uid, newArea, newAc);
                // 清除效果
                clearBadEffects(p, newAc);

            }
// ===== 清除指定效果 =====
            if (newAc.clearEffects != null
                    && !newAc.clearEffects.isEmpty()) {
                for (String effName : newAc.clearEffects) {
                    PotionEffectType type =
                            resolveEffectType(effName);
                    if (type != null && p.getPotionEffect(type) != null) {
                        p.removePotionEffect(type);
                        plugin.getLogger().info(
                                "[防护] ★清除效果: " + effName
                                        + " 玩家=" + p.getName());
                    }
                }
            }

// ===== 清除负面效果（clearBadEffects 已在上方处理） =====
            // clearAllBadEffects 仅清除负面+中性效果，不清除正面效果
            // 此处不再单独处理，统一由上方 clearBadEffects() 负责

            if (forcedMode != null
                    && !isExemptFromGameMode(p, newArea)) {
                GameMode target =
                        GameMode.valueOf(forcedMode);
                if (p.getGameMode() != target) {
                    p.setGameMode(target);
                }
            }
        }

        if (newArea != null) {
            playerCurrentArea.put(uid, newArea);
        } else {
            playerCurrentArea.remove(uid);
        }
    }

    /**
     * 查找安全的领地外部传送位置
     */
    private Location findSafeExitLocation(Location loc, AreaConfig ac) {
        int px = loc.getBlockX(), pz = loc.getBlockZ();
        int minX = Math.min(ac.x1, ac.x2), maxX = Math.max(ac.x1, ac.x2);
        int minZ = Math.min(ac.z1, ac.z2), maxZ = Math.max(ac.z1, ac.z2);

        int distLeft = px - minX;
        int distRight = maxX - px;
        int distTop = pz - minZ;
        int distBottom = maxZ - pz;

        int minDist = Math.min(Math.min(distLeft, distRight), Math.min(distTop, distBottom));

        int exitX = px, exitZ = pz;
        if (minDist == distLeft) exitX = minX - 3;
        else if (minDist == distRight) exitX = maxX + 3;
        else if (minDist == distTop) exitZ = minZ - 3;
        else exitZ = maxZ + 3;

        // ★ 安全判定：头顶≥2格空气、脚下实体方块、距边界≥3格
        World world = loc.getWorld();
        if (world == null) return null;
        for (int y = Math.min(ac.yMax, world.getMaxHeight() - 3); y >= Math.max(ac.yMin, world.getMinHeight() + 2); y--) {
            Location check = new Location(world, exitX, y, exitZ);
            Block feet = check.getBlock();
            Block head = world.getBlockAt(exitX, y + 1, exitZ);
            Block head2 = world.getBlockAt(exitX, y + 2, exitZ);
            // 脚下必须是实体方块
            if (!feet.getType().isSolid()) continue;
            // 头顶2格必须是空气（可通过）
            if (head.getType() != Material.AIR && head.getType() != Material.CAVE_AIR && head.getType() != Material.VOID_AIR) continue;
            if (head2.getType() != Material.AIR && head2.getType() != Material.CAVE_AIR && head2.getType() != Material.VOID_AIR) continue;
            // ★ 确保出口不在领地范围内（再往外推3格）
            int finalX = exitX;
            int finalZ = exitZ;
            // 如果出口坐标还在领地内，继续往外推
            if (finalX >= minX && finalX <= maxX && finalZ >= minZ && finalZ <= maxZ) {
                // 在minDist方向继续推
                if (minDist == distLeft) finalX = minX - 3;
                else if (minDist == distRight) finalX = maxX + 3;
                else if (minDist == distTop) finalZ = minZ - 3;
                else finalZ = maxZ + 3;
            }
            return new Location(world, finalX + 0.5, y + 1, finalZ + 0.5);
        }
        // ★ 不满足条件，传送到世界出生点
        return world.getSpawnLocation().clone();
    }

// ===== 清理袭击生物 =====

    // 需要清理的袭击生物类型
    private static final Set<String> RAID_MOBS = new HashSet<>(Arrays.asList(
            "VEX",
            "EVOKER",
            "PILLAGER",
            "VINDICATOR",
            "WITCH",
            "RAVAGER",
            "PILLAGER"
    ));

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        Location loc = entity.getLocation();

        // 检查是否在防护区域内
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ());
        if (ac == null) return;

        // 禁止袭击：取消袭击事件
        if (ac.denyRaid) {
            // 清理袭击生物
            String typeName = entity.getType().name();
            if (RAID_MOBS.contains(typeName)) {
                event.setCancelled(true);
                plugin.getLogger().info(
                        "[防护] ★清理袭击生物: " + typeName
                                + " 区域=" + ac.name);
            }
        }

        // 没收物品检测
        if (ac.confiscateItems != null
                && !ac.confiscateItems.isEmpty()) {
            if (entity instanceof Item) {
                Item item = (Item) entity;
                String itemType = item.getItemStack()
                        .getType().name();
                if (ac.confiscateItems.contains(itemType)) {
                    event.setCancelled(true);
                    plugin.getLogger().info(
                            "[防护] 没收违禁掉落物: "
                                    + itemType);
                }
            }
        }
    }


    /**
     * 进入区域时发放效果
     * 流程：查DB → 有记录跳过写入 → 无记录写入DB
     *       → 给效果 → 贴标
     */
    private void applyRegionEffects(Player p, UUID uid,
                                    String areaName, AreaConfig ac) {
    /*    plugin.getLogger().info(
                "[防护-贴标] ★★★方法被调用! 玩家="
                        + p.getName() + " 区域=" + areaName
                        + " ac=" + (ac != null)
                        + " 效果配置数="
                        + (ac != null ? ac.giveEffects.size() : "null"));*/

        if (ac == null || ac.giveEffects.isEmpty()) return;
        if (ac.giveEffects.isEmpty()) return;
        // 查DB：该玩家在此区域是否已有效果记录
        List<String> dbEffects =
                getPlayerEffectNames(uid, areaName);

        if (dbEffects.isEmpty()) {
            // 无记录 → 写入DB
            for (String[] eff : ac.giveEffects) {
                PotionEffectType t =
                        resolveEffectType(eff[0]);
                if (t == null) continue;
                saveSingleEffect(uid, p.getName(),
                        areaName, t.getName());
            }
            plugin.getLogger().info(
                    "[防护] DB写入, 玩家=" + p.getName()
                            + " 区域=" + areaName);
        } else {
            plugin.getLogger().info(
                    "[防护] DB已有记录, 跳过写入, 玩家="
                            + p.getName()
                            + " 区域=" + areaName);
        }

        // 给效果 + 贴标
        List<PotionEffectType> applied = new ArrayList<>();
        for (String[] eff : ac.giveEffects) {
            try {
                PotionEffectType type =
                        resolveEffectType(eff[0]);
                if (type == null) continue;
                int lv = Integer.parseInt(eff[1]) - 1;
                int dur = Integer.parseInt(eff[2]) * 20;

                PotionEffect existing =
                        p.getPotionEffect(type);
                if (existing != null
                        && existing.getAmplifier() >= lv) {
                    continue;
                }
                p.addPotionEffect(
                        new PotionEffect(type, dur, lv));
                applied.add(type);
            } catch (Exception ignored) {
            }
        }

        // 贴标
        // 新代码（合并，不覆盖）：
        List<PotionEffectType> existingMarks =
                playerMarkedEffects.get(uid);
        if (existingMarks != null && !existingMarks.isEmpty()) {
            // 已有标记，合并新增的
            for (PotionEffectType t : applied) {
                if (!existingMarks.contains(t)) {
                    existingMarks.add(t);
                }
            }
          /*  plugin.getLogger().info(
                    "[防护-贴标] 合并标记: "
                            + existingMarks.size()
                            + " 个, 玩家=" + p.getName());*/
        } else {
            // 无标记，新建
            playerMarkedEffects.put(uid, applied);
        /*    plugin.getLogger().info(
                    "[防护-贴标] 新建标记: "
                            + applied.size()
                            + " 个, 玩家=" + p.getName());*/
        }
    }

        /**
         * 离开区域时，延时5秒清理效果
         * 流程：收标 → 5秒后清效果 → 确认清空 → 清DB
         */
    private void scheduleEffectClear(Player p, UUID uid,
                                     String areaName) {
        // 收标
        List<PotionEffectType> marked =
                playerAppliedEffects.remove(uid);

        plugin.getLogger().info(
                "[防护] 收标 " + (marked != null ? marked.size() : 0)
                        + " 个, 玩家=" + p.getName()
                        + " 区域=" + areaName);

        // 启动5秒延时
        pendingClearArea.put(uid, areaName);
        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                pendingClearTask.remove(uid);
                pendingClearArea.remove(uid);
                pendingClearEffects.remove(uid);

                // 清除效果
                if (marked != null) {
                    for (PotionEffectType type : marked) {
                        p.removePotionEffect(type);
                    }
                }

                // 确认清空后清DB
                removePlayerEffects(uid, areaName);
                plugin.getLogger().info(
                        "[防护] 5秒已到, 清除 "
                                + (marked != null ? marked.size() : 0)
                                + " 个效果 + DB, 玩家="
                                + p.getName()
                                + " 区域=" + areaName);
            }
        }.runTaskLater(plugin, 100L); // 5秒 = 100 tick

        pendingClearTask.put(uid, task);
    }
    public Set<String> getAllAreaNames() {
        return areas.keySet();
    }

    /**
     * 5秒内进了新区域：
     * 清除旧区域有、新区域没有的效果
     */
    private void diffEffects(Player p, UUID uid,
                             String oldAreaName, String newAreaName) {
        AreaConfig oldAc = areas.get(oldAreaName);
        AreaConfig newAc = areas.get(newAreaName);
        if (oldAc == null || newAc == null) return;

        // ★ 从 pendingClearEffects 获取旧标记
        List<PotionEffectType> oldMarked =
                pendingClearEffects.remove(uid);

        if (oldMarked == null || oldMarked.isEmpty()) {
            plugin.getLogger().info(
                    "[防护-跨区域] 无待清理效果");
            return;
        }

        // 新区域的效果集合
        Set<PotionEffectType> newTypes = new HashSet<>();
        if (newAc != null) {
            for (String[] eff : newAc.giveEffects) {
                PotionEffectType t =
                        resolveEffectType(eff[0]);
                if (t != null) newTypes.add(t);
            }
        }

        int cleared = 0;
        for (PotionEffectType type : oldMarked) {
            if (!newTypes.contains(type)) {
                p.removePotionEffect(type);
                cleared++;
                plugin.getLogger().info(
                        "[防护-跨区域] 清除: "
                                + type.getName());
            } else {
                plugin.getLogger().info(
                        "[防护-跨区域] 保留: "
                                + type.getName()
                                + " (新区域也有)");
            }
        }

        removePlayerEffects(uid, oldAreaName);
        plugin.getLogger().info(
                "[防护-跨区域] 完成: 清除"
                        + cleared + "/" + oldMarked.size()
                        + " 个, " + oldAreaName
                        + " → " + newAreaName);
    }

// ===== DB 辅助方法（简化版）=====

    /**
     * 查询玩家在某个区域的效果记录（返回英文名列表）
     */
    private List<String> getPlayerEffectNames(
            UUID uid, String areaName) {
        List<String> list = new ArrayList<>();
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT effect_type FROM player_effects "
                            + "WHERE uuid=? AND area_name=?");
            ps.setString(1, uid.toString());
            ps.setString(2, areaName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(rs.getString("effect_type"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询效果失败: " + e.getMessage());
        }
        return list;
    }

    /**
     * 写入单条效果记录
     */
    private void saveSingleEffect(UUID uid,
                                  String playerName, String areaName,
                                  String effectType) {
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "INSERT INTO player_effects "
                            + "(uuid, player_name, area_name, "
                            + "effect_type, effect_level, "
                            + "effect_duration, enter_time) "
                            + "VALUES (?,?,?,?,?,?,?)");
            ps.setString(1, uid.toString());
            ps.setString(2, playerName);
            ps.setString(3, areaName);
            ps.setString(4, effectType);
            ps.setInt(5, 0);
            ps.setInt(6, 999);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 写入效果失败: " + e.getMessage());
        }
    }

    // ==================== 违禁品没收 ====================

    /**
     * 检查玩家是否豁免于没收
     * 全局白名单 / 区域白名单 / modeExempt / OP 都跳过
     */
    public boolean isExemptFromConfiscation(
            Player p, String areaName) {
        AreaConfig ac = areas.get(areaName);
        // 管理员和领地所有者免检
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return true;
        // modeExempt名单也免检
        if (ac != null && ac.modeExempt.contains(p.getName()))
            return true;
        return false;
    }


    private String getChineseGameMode(GameMode mode) {
        switch (mode) {
            case SURVIVAL:  return "生存模式";
            case CREATIVE:  return "创造模式";
            case ADVENTURE: return "冒险模式";
            case SPECTATOR: return "旁观模式";
            default:        return mode.name();
        }
    }

    private void handleEnter(Player p, AreaConfig ac) {
        UUID uid = p.getUniqueId();

        // 清除负面效果
        clearBadEffects(p, ac);

        // 给予效果
        List<PotionEffectType> given = new ArrayList<>();
        for (String[] parts : ac.giveEffects) {
            String effName = parts[0];
            int level = 1;
            int duration = 999;
            try { level = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
            try { duration = Integer.parseInt(parts[2]); }
            catch (NumberFormatException ignored) {}
            PotionEffectType type = resolveEffectType(effName);
            if (type != null) {
                p.addPotionEffect(new PotionEffect(
                        type, duration * 20, level - 1));
                given.add(type);
            }
        }

        removePlayerEffects(uid, ac.name);
        if (!given.isEmpty()) {
          //  savePlayerEffects(uid, p.getName(), ac.name, given);
        }

        // 白名单检查（最先执行）
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

        // 游戏模式切换（只对非白名单玩家）
        if (ac.enforceGameMode != null) {
            boolean exempt = isPlayerExemptFromModeChange(p, ac);
            if (!exempt) {
                GameMode target = GameMode.valueOf(ac.enforceGameMode);
                if (p.getGameMode() != target) {
                    p.setGameMode(target);
                    p.sendMessage("§e§l[区域防护] §f游戏模式已切换为: "
                            + getChineseGameMode(target));
                }
            }
        }

        // 和平模式/袭击检查
        if (ac.peaceMode) {
            banHostilesWithWhitelist(p, ac);
        } else if (ac.denyRaid) {
            banRaidMobs(p, ac);
        }

        handleConfiscate(p, ac);
    }

    private boolean isPlayerExemptFromModeChange(
            Player p, AreaConfig ac) {
     //   if (p.isOp()) return true;
        String lower = p.getName().toLowerCase();
        // 1. 全局白名单
        if (globalPlayerWhitelist.contains(lower)) {
            return true;
        }
        // 2. 区域白名单
        Set<String> areaWl = areaPlayerWhitelist.get(ac.name);
        if (areaWl != null
                && areaWl.contains(lower)) {
            return true;
        }
        if (ac.modeExempt.contains(p.getName())) {
            return true;
        }
        return false;
    }

    private void banRangedHostiles(Player p, AreaConfig ac) {
        for (Entity ent : p.getNearbyEntities(48, 48, 48)) {
            if (isRangedHostile(ent.getType().name())) {
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(ent.getWorld().getMinHeight() - 50);
                ent.teleport(voidLoc);
            }
        }
    }

    /**
     * 按配置清除效果
     * denyRaid → 清除袭击相关
     * denyAllEffects → 清除所有负面+中性
     * denyAllEffects > denyRaid（全清优先）
     */
    private void clearBadEffects(Player p, AreaConfig ac) {
        // 和平模式 > 清除所有效果 > 禁止单个
        if (ac.peaceMode || ac.clearAllBadEffects) {
            PotionEffectType[] allBad = {
                    PotionEffectType.SLOWNESS,
                    PotionEffectType.MINING_FATIGUE,
                    PotionEffectType.INSTANT_DAMAGE,
                    PotionEffectType.NAUSEA,
                    PotionEffectType.BLINDNESS,
                    PotionEffectType.HUNGER,
                    PotionEffectType.WEAKNESS,
                    PotionEffectType.POISON,
                    PotionEffectType.WITHER,
                    PotionEffectType.LEVITATION,
                    PotionEffectType.UNLUCK,
                    PotionEffectType.DARKNESS,
                    PotionEffectType.WIND_CHARGED,
                    PotionEffectType.WEAVING,
                    PotionEffectType.OOZING,
                    PotionEffectType.INFESTED,
                    PotionEffectType.BAD_OMEN,
                    PotionEffectType.TRIAL_OMEN,
                    PotionEffectType.RAID_OMEN
            };
            for (PotionEffectType type : allBad) {
                p.removePotionEffect(type);
            }
            return;
        }

        // 禁止袭击 → 自动关联清除
        if (ac.denyRaid) {
            p.removePotionEffect(PotionEffectType.BAD_OMEN);
            p.removePotionEffect(PotionEffectType.RAID_OMEN);
        }

        // 配置文件单个清除
        for (String effName : ac.clearEffects) {
            PotionEffectType type = resolveEffectType(effName);
            if (type != null) {
                p.removePotionEffect(type);
            }
        }
    }


    private PotionEffectType resolveEffectType(String name) {
        if (name == null || name.isEmpty()) return null;

        // 去掉"效果"后缀
        String clean = name;
        if (clean.endsWith("效果")) {
            clean = clean.substring(0, clean.length() - 2);
        }

        // 硬编码中文名→英文ID映射（不依赖任何外部Map）
        switch (clean) {
            // 负面
            case "缓慢":
                return resolveEffectType("slowness");
            case "挖掘疲劳":
                return resolveEffectType("mining_fatigue");
            case "瞬间伤害":
                return resolveEffectType("instant_damage");
            case "反胃":
                return resolveEffectType("nausea");
            case "失明":
                return resolveEffectType("blindness");
            case "饥饿":
                return resolveEffectType("hunger");
            case "虚弱":
                return resolveEffectType("weakness");
            case "中毒":
                return resolveEffectType("poison");
            case "凋零":
                return resolveEffectType("wither");
            case "飘浮":
                return resolveEffectType("levitation");
            case "霉运":
                return resolveEffectType("unluck");
            case "黑暗":
                return resolveEffectType("darkness");
            case "蓄风":
                return resolveEffectType("wind_charged");
            case "盘丝":
                return resolveEffectType("weaving");
            case "渗浆":
                return resolveEffectType("oozing");
            case "寄生":
                return resolveEffectType("infested");
            // 中性
            case "不祥之兆":
            case "不祥征兆":
                return resolveEffectType("bad_omen");
            case "袭击之兆":
            case "袭击征兆":
                return resolveEffectType("raid_omen");
            case "试炼之兆":
            case "试炼征兆":
                return resolveEffectType("trial_omen");
            // 正面
            case "迅捷":
                return resolveEffectType("speed");
            case "急迫":
                return resolveEffectType("haste");
            case "力量":
                return resolveEffectType("strength");
            case "瞬间治疗":
                return resolveEffectType("instant_health");
            case "跳跃提升":
                return resolveEffectType("jump_boost");
            case "生命恢复":
                return resolveEffectType("regeneration");
            case "抗性提升":
                return resolveEffectType("resistance");
            case "抗火":
                return resolveEffectType("fire_resistance");
            case "水下呼吸":
                return resolveEffectType("water_breathing");
            case "隐身":
                return resolveEffectType("invisibility");
            case "夜视":
                return resolveEffectType("night_vision");
            case "发光":
                return resolveEffectType("glowing");
            case "生命提升":
                return resolveEffectType("health_boost");
            case "伤害吸收":
                return resolveEffectType("absorption");
            case "饱和":
                return resolveEffectType("saturation");
            case "幸运":
                return resolveEffectType("luck");
            case "村庄英雄":
                return resolveEffectType("hero_of_the_village");
            case "缓降":
                return resolveEffectType("slow_falling");
            case "潮涌能量":
                return resolveEffectType("conduit_power");
            case "海豚的恩惠":
                return resolveEffectType("dolphins_grace");
            case "致命中毒":
                return resolveEffectType("poison");
            default:
                break;
        }


// 兜底：直接用原版英文ID查（★ 注意是 PotionEffectType.getByName，不是 resolveEffectType）
        PotionEffectType fallback = PotionEffectType.getByName(clean);
        if (fallback != null) return fallback;
        fallback = PotionEffectType.getByName(clean.toLowerCase());
        if (fallback != null) return fallback;

        plugin.getLogger().warning("[防护] 无法识别效果: " + name);
        return null;

    }


    private static final Map<String, PotionEffectType> EFFECT_MAP
            = new HashMap<>();


    private void clearRaidEffects(Player p) {
        boolean cleared = false;
        try {
            if (p.hasPotionEffect(PotionEffectType.BAD_OMEN)) {
                p.removePotionEffect(PotionEffectType.BAD_OMEN);
                cleared = true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (p.hasPotionEffect(PotionEffectType.RAID_OMEN)) {
                p.removePotionEffect(PotionEffectType.RAID_OMEN);
                cleared = true;
            }
        } catch (Exception ignored) {
        }
        if (cleared) {
            p.sendMessage("§c§l[区域防护] §f已清除袭击相关效果");
        }
    }

    public Map<UUID, List<PotionEffectType>>
    getPendingClearEffects() {
        return pendingClearEffects;
    }

    public void onPlayerOffline(UUID uid, String playerName,
                                String areaName) {
        // 清除该玩家在该区域的所有效果
        List<PotionEffectType> given = getPlayerEffects(uid, areaName);
        for (PotionEffectType type : given) {
            // 只清除我们给予的效果
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.removePotionEffect(type);
            }
        }
        // 清除DB记录
        removePlayerEffects(uid, areaName);
        plugin.getLogger().info("[防护] " + playerName
                + " 下线，已清除" + given.size()
                + "个效果并清理DB");
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        if (from.getWorld() == to.getWorld()
                && from.distance(to) < 5) return;

        Player p = e.getPlayer();
        UUID uid = p.getUniqueId();

        // 标记传送中
        teleporting.add(uid);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            teleporting.remove(uid);
        }, 5L);

        AreaConfig fromArea = getArea(
                from.getWorld().getName(),
                from.getBlockX(), from.getBlockY(),
                from.getBlockZ());
        AreaConfig toArea = getArea(
                to.getWorld().getName(),
                to.getBlockX(), to.getBlockY(),
                to.getBlockZ());

        String fromName = fromArea != null
                ? fromArea.name : null;
        String toName = toArea != null
                ? toArea.name : null;

    /*    plugin.getLogger().info(
              "[防护-传送] ★★★进入onTeleport: "
                        + p.getName()
                        + " [" + fromName + "] → [" + toName + "]"
                        + " teleporting=" + teleporting.contains(uid));*/

        if (Objects.equals(fromName, toName)) {
          /*  plugin.getLogger().info(
                    "[防护-传送] 区域相同, 跳过");*/
            return;
        }

        // ===== 离开旧区域 =====
        if (fromName != null) {
          /*  plugin.getLogger().info(
                    "[防护-传送-离开] ★★★开始离开处理: "
                            + fromName);*/

            // 收标
            List<PotionEffectType> marked =
                    playerMarkedEffects.remove(uid);

        /*    plugin.getLogger().info(
                    "[防护-传送-离开] ★★★收标结果: "
                            + (marked != null ? marked.size() : "null"));*/

            if (marked != null) {
                for (PotionEffectType t : marked) {
                  /*  plugin.getLogger().info(
                            "[防护-传送-离开] 标记项: "
                                    + t.getName());*/
                }
            } else {
              /*  plugin.getLogger().info(
                        "[防护-传送-离开] ★★标记为null! "
                                + "尝试其他key查找...");*/
                // 遍历查找可能的标记
                for (Map.Entry<UUID, List<PotionEffectType>> entry
                        : playerMarkedEffects.entrySet()) {
                    if (entry.getKey().equals(uid)) {
                       /* plugin.getLogger().info(
                                "[防护-传送-离开] 找到标记! 效果数="
                                        + entry.getValue().size());*/
                    }
                }
               /* plugin.getLogger().info(
                        "[防护-传送-离开] playerMarkedEffects大小="
                                + playerMarkedEffects.size());*/
            }

            // 取消旧延时
            if (pendingClearTask.containsKey(uid)) {
                pendingClearTask.get(uid).cancel();
                pendingClearTask.remove(uid);
                pendingClearArea.remove(uid);
                pendingClearEffects.remove(uid);
            }

            // 创建5秒延时清理
            if (marked != null && !marked.isEmpty()) {
                String clearArea = fromName;
                UUID clearUid = uid;
                List<PotionEffectType> toClear =
                        new ArrayList<>(marked);
                pendingClearArea.put(uid, clearArea);
                pendingClearEffects.put(uid, toClear);

            /*    plugin.getLogger().info(
                        "[防护-传送-离开] ★★★创建延时任务: "
                                + toClear.size() + "个效果");*/

                BukkitTask task = new BukkitRunnable() {
                    @Override
                    public void run() {
                       /* plugin.getLogger().info(
                                "[防护-传送-清理] ★★★延时触发!");*/
                        pendingClearTask.remove(clearUid);
                        pendingClearArea.remove(clearUid);

                        Player online =
                                Bukkit.getPlayer(clearUid);
/*
                        plugin.getLogger().info(
                                "[防护-传送-清理] 在线="
                                        + (online != null && online.isOnline())
                                        + " 效果数=" + toClear.size()
                                        + " 区域=" + clearArea);*/

                        if (online == null
                                || !online.isOnline()) {
                            plugin.getLogger().info(
                                    "[防护-传送-清理] 玩家不在线, 清DB");
                            removePlayerEffects(
                                    clearUid, clearArea);
                            return;
                        }

                        int cleared = 0;
                        for (PotionEffectType type : toClear) {
                            PotionEffect eff =
                                    online.getPotionEffect(type);
                         /*   plugin.getLogger().info(
                                    "[防护-传送-清理] 检查: "
                                            + type.getName()
                                            + " 身上=" + (eff != null));*/
                            if (eff != null) {
                                online.removePotionEffect(type);
                                cleared++;
                            }
                        }
                        removePlayerEffects(clearUid, clearArea);
                        plugin.getLogger().info(
                                "[防护-传送-清理] ★★★完成: 清除"
                                        + cleared + "/" + toClear.size());
                    }
                }.runTaskLater(plugin, 100L); // 5秒 = 100 tick
                pendingClearTask.put(uid, task);

            }

            // 离开提示
            if (fromArea != null && fromArea.leaveMsg != null
                    && !fromArea.leaveMsg.isEmpty()) {
                p.sendMessage(formatAreaMsg(fromArea.leaveMsg));
            }
        }

        // ===== 进入新区域 =====
        if (toName != null) {
          /*  plugin.getLogger().info(
                    "[防护-传送-进入] ★★★开始进入处理: "
                            + toName);*/

            // 取消旧延时
            if (pendingClearTask.containsKey(uid)) {
                pendingClearTask.get(uid).cancel();
                pendingClearTask.remove(uid);
                String oldP = pendingClearArea.remove(uid);
                plugin.getLogger().info(
                        "[防护-传送-进入] 取消旧延时, 旧区域="
                                + oldP);
                if (oldP != null) {
                    diffEffects(p, uid, oldP, toName);
                }
            }

            // 进入提示
            if (toArea != null && toArea.enterMsg != null
                    && !toArea.enterMsg.isEmpty()) {
                p.sendMessage(formatAreaMsg(toArea.enterMsg));
            }

            // 发放效果
            if (toArea != null) {
            /*    plugin.getLogger().info(
                        "[防护-传送-进入] 调用applyRegionEffects");*/
                applyRegionEffects(p, uid, toName, toArea);
// 清除效果
                clearBadEffects(p, toArea);

                // ★ 没收违禁品（使用统一的handleConfiscate）
                if (!isExemptFromConfiscation(p, toName)) {
                    handleConfiscate(p, toArea);
                }
            }

            // 强制模式
            if (toArea != null && toArea.enforceGameMode != null
                    && !toArea.enforceGameMode.isEmpty()
                    && !isExemptFromGameMode(p, toName)) {
                GameMode target = GameMode.valueOf(
                        toArea.enforceGameMode);
                if (p.getGameMode() != target) {
                    p.setGameMode(target);
                }
            }
        }

        // 更新记录
        if (toName != null) {
            playerCurrentArea.put(uid, toName);
        } else {
            playerCurrentArea.remove(uid);
        }
    }


    public Map<UUID, BukkitTask> getPendingClearTasks() {
        return pendingClearTask;
    }

    public Map<UUID, List<PotionEffectType>>
    getPlayerMarkedEffects() {
        return playerMarkedEffects;
    }

    public void handleInside(Player p, AreaConfig ac) {
        clearBadEffects(p, ac);

        if (ac.peaceMode) {
            banHostilesWithWhitelist(p, ac);
        } else if (ac.denyRaid) {
            banRaidMobs(p, ac);
        }

        // 所有者或管理员 → 跳过所有限制性规则（包括没收）
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

        handleConfiscate(p, ac);
    }

    public void cleanupExpiredProtections() {
        protectedEntities.entrySet().removeIf(
                entry -> System.currentTimeMillis() > entry.getValue());
    }

    // 禁止袭击：只驱逐袭击怪物
    private void banRaidMobs(Player p, AreaConfig ac) {
        for (Entity ent : p.getNearbyEntities(32, 32, 32)) {
            String t = ent.getType().name();
            if (t.equals("PILLAGER")
                    || t.equals("VINDICATOR")
                    || t.equals("EVOKER")
                    || t.equals("RAVAGER")
                    || t.equals("VEX")
                    || t.equals("WITCH")
                    || t.equals("ILLUSIONER")
                    || t.equals("ZOMBIE_VILLAGER")) {
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(voidLoc.getWorld().getMinHeight() - 50);
                ent.teleport(voidLoc);
            }
        }
    }

    public Set<String> getAreaNames() {
        return areas.keySet();
    }

    /** 获取所有用户组名（tab补全用） */
    public List<String> getUserGroupNames() {
        List<String> names = new ArrayList<>();
        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm != null) {
            for (UserGroupManager.UserGroupConfig cfg : ugm.getAllGroups()) {
                names.add(cfg.name);
            }
        }
        return names;
    }

    /**
     * 根据名称获取领地配置
     */
    public AreaConfig getLand(String name) {
        return areas.get(name);
    }

    /**
     * 获取所有领地（只读）
     */
    public Map<String, AreaConfig> getAllLands() {
        return Collections.unmodifiableMap(areas);
    }

    /**
     * 检查领地是否有待删除请求
     */
    public boolean hasPendingDelete(String areaName) {
        return pendingDeletes.containsKey(areaName);
    }

    /**
     * 获取指定玩家拥有的所有领地
     */
    public List<AreaConfig> getLandsByOwner(String ownerName) {
        List<AreaConfig> result = new ArrayList<>();
        for (AreaConfig ac : areas.values()) {
            if (ac.owner.equalsIgnoreCase(ownerName)) {
                result.add(ac);
            }
        }
        return result;
    }

    /**
     * 删除领地
     */
    public void deleteLand(String name) {
        areas.remove(name);
        // 删除数据库记录
        try {
            if (dbConnection != null && !dbConnection.isClosed()) {
                PreparedStatement ps = dbConnection.prepareStatement(
                        "DELETE FROM area_lands WHERE name = ?");
                ps.setString(1, name);
                ps.executeUpdate();
                ps.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 删除领地数据库失败: " + e.getMessage());
        }
    }

    /**
     * 获取领地成员列表（白名单）
     */
    public Set<String> getLandMembers(String landName) {
        Set<String> members = areaPlayerWhitelist.get(landName);
        return members != null ? new HashSet<>(members) : new HashSet<>();
    }

    /**
     * 添加领地成员（领地主自动跳过，不允许作为成员入库）
     */
    public boolean addLandMember(String landName, String playerName) {
        AreaConfig ac = getLand(landName);
        if (ac != null && ac.owner != null && ac.owner.equalsIgnoreCase(playerName)) {
            return false; // 领地主不能作为成员添加
        }
        Set<String> members = areaPlayerWhitelist.computeIfAbsent(
                landName, k -> ConcurrentHashMap.newKeySet());
        members.add(playerName.toLowerCase());
        saveWhitelists();
        return true;
    }

    /**
     * 移除领地成员
     */
    public void removeLandMember(String landName, String playerName) {
        Set<String> members = areaPlayerWhitelist.get(landName);
        if (members != null) {
            // ★ 尝试精确匹配 + 小写匹配
            if (!members.remove(playerName)) {
                members.remove(playerName.toLowerCase());
            }
            saveWhitelists();
        }
    }

    /**
     * 获取玩家当前所在区域名
     */
    public String getPlayerArea(Player p) {
        return playerCurrentArea.get(p.getUniqueId());
    }

    public Collection<BukkitTask> getPendingTasks() {
        return pendingClearTask.values();
    }


    // 判断是否为远程攻击敌对生物
    private boolean isRangedHostile(String typeName) {
        switch (typeName) {
            // ===== 骷髅系 =====
            case "SKELETON":            // 骷髅（弓箭）
            case "STRAY":               // 流浪者（迟缓之箭）
            case "BOGGED":              // 虫蚀骷髅（毒箭）
            case "WITHER_SKELETON":     // 凋灵骷髅（近战但算灾厄）

                // ===== 灾厄村民 =====
            case "PILLAGER":            // 掠夺者（弩）
            case "ILLUSIONER":          // 幻术师（弓箭+失明）
            case "EVOKER":              // 幻魔者（唤魔）
            case "VINDICATOR":          // 卫道士（斧头近战）
            case "VEX":                 // 恼鬼（剑近战，但会穿墙飞行）

                // ===== 法术/投射 =====
            case "BLAZE":               // 烈焰人（火球）
            case "WITCH":               // 女巫（药水）

                // ===== 飞行/爆炸 =====
            case "GHAST":               // 恶魂（火球）
            case "PHANTOM":             // 幻翼（俯冲）
            case "BIG_GHAST":           // 大恶魂（1.21新增）

                // ===== 水下 =====
            case "GUARDIAN":            // 守卫者（激光）
            case "ELDER_GUARDIAN":      // 远古守卫者（激光）

                // ===== 远程特殊 =====
            case "SHULKER":             // 潜影贝（潜影弹）
            case "BREEZE":              // 旋风人（风弹）

                // ===== 蜘蛛系（远程毒液攻击） =====
            case "SPIDER":              // 蜘蛛（白天远程吐丝）
            case "CAVE_SPIDER":         // 洞穴蜘蛛（毒）

                // ===== 1.21新增 =====
            case "WIND_CHARGED":        // 蓄风怪（风弹）
            case "WEAVING":             // 盘丝怪（吐丝）
            case "OOZING":              // 渗浆怪（史莱姆）
            case "INFESTED":            // 寄生怪（蠹虫）

                // ===== 盔甲/变种（部分版本） =====
            case "ARMORED_SKELETON":    // 盔甲骷髅
            case "ARCHER_SKELETON":     // 弓箭骷髅（部分版本）
            case "SNOW_GOLEM":          // 雪傀儡（不是敌对但远程）

                // ===== 亡灵骑乘 =====
            case "HUSK":                // 尸壳（近战但属于灾厄系）
            case "DROWNED":             // 溺尸（三叉戟远程）
            case "ZOMBIE_VILLAGER":     // 僵尸村民
            case "ZOMBIE_PIGMAN":       // 僵尸猪灵
            case "ZOMBIFIED_PIGLIN":    // 僵尸猪灵（1.16+）
            case "PIGLIN_BRUTE":        // 猪灵蛮兵

                // ===== 猪灵（弓箭） =====
            case "PIGLIN":              // 猪灵（金弩远程）

                // ===== 末影系 =====
            case "ENDER_DRAGON":        // 末影龙（远程吐息）
            case "WARDEN":              // 监守者（远程音波）

                // ===== 其他 =====
            case "CREPER":              // 苦力怕（爆炸不算远程但危险）
            case "CREEPER":             // 苦力怕

                return true;
            default:
                return false;
        }
    }



    private void applyEffects(Player p, AreaConfig ac) {
        for (String[] parts : ac.giveEffects) {
            String effName = parts[0];
            int level = 1;
            int duration = 999;
            try { level = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
            try { duration = Integer.parseInt(parts[2]); }
            catch (NumberFormatException ignored) {}

            PotionEffectType type = resolveEffectType(effName);
            plugin.getLogger().info("[防护调试] 给予: "
                    + effName + " -> " + type);
            if (type != null) {
                p.addPotionEffect(new PotionEffect(
                        type, duration * 20, level - 1));
                plugin.getLogger().info("[防护调试] 已给予: "
                        + effName + " 等级" + level
                        + " 持续" + duration + "秒");
            }
        }
    }


    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getBlock().getX(),
                e.getBlock().getY(),
                e.getBlock().getZ());
        if (ac == null) return;

        Material placedMat = e.getBlock().getType();

        // ★ 农作物种植权限分层：种子/农作物由denyCropHarvest独立控制
        if (isCropSeed(placedMat)) {
            if (getEffectiveDeny(p, ac, "denyCropHarvest")) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止种植农作物");
            }
            return; // 农作物种子不走denyBlockPlace
        }

        // 非农作物：正常denyBlockPlace检查
        if (getEffectiveDeny(p, ac, "denyBlockPlace")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止放置方块");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getBlock().getX(),
                e.getBlock().getY(),
                e.getBlock().getZ());
        if (ac == null) return;

        Material blockMat = e.getBlock().getType();

        // ★ 农作物权限分层：农作物由denyCropHarvest独立控制，不受denyBlockBreak影响
        if (isCropBlock(blockMat)) {
            if (getEffectiveDeny(p, ac, "denyCropHarvest")) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止收获农作物");
            }
            return; // 农作物不走denyBlockBreak
        }

        // 非农作物：正常denyBlockBreak检查
        if (getEffectiveDeny(p, ac, "denyBlockBreak")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏方块");
        }
    }


    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;
        // 保护性规则：支持per-player独立权限
        if (getEffectiveDeny(p, ac, "denyAllDamage")) { e.setCancelled(true); return; }
        if (getEffectiveDeny(p, ac, "denyFallDamage")
                && e.getCause() == EntityDamageEvent.DamageCause.FALL)
            e.setCancelled(true);
    }


    @EventHandler
    public void onPVP(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac != null && getEffectiveDeny(p, ac, "denyPVP")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止PVP");
        }
    }


    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac != null && getEffectiveDeny(p, ac, "denyHunger"))
            e.setCancelled(true);
    }



    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;
        // 使用getEffectiveDeny，OWNER/Admin自动豁免，visitor走领地默认
        if (getEffectiveDeny(p, ac, "denyDrop")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止丢弃物品");
        }
    }

    // ★ 拾取限制：allowPickup=false时禁止拾取（denyPickup映射到allowPickup）
    @EventHandler
    public void onPlayerPickup(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;
        // 使用getEffectiveDeny，OWNER/Admin自动豁免，visitor走领地默认
        if (getEffectiveDeny(p, ac, "denyPickup")) {
            e.setCancelled(true);
            // 节流：5~10秒随机间隔
            long now = System.currentTimeMillis();
            Long last = lastPickupMsgTime.get(p.getUniqueId());
            long cooldown = 5000 + new java.util.Random().nextLong(5000); // 5~10秒
            if (last == null || now - last > cooldown) {
                lastPickupMsgTime.put(p.getUniqueId(), now);
                p.sendMessage(PICKUP_MSGS[new java.util.Random().nextInt(PICKUP_MSGS.length)]);
            }
        }
    }

    // 替换整个 onInteract 方法
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return;

        // ===== 选区工具 =====
        if (hand.getType() == Material.BLAZE_ROD) {
            ItemMeta meta = hand.getItemMeta();
            if (meta != null && meta.getDisplayName() != null
                    && meta.getDisplayName().contains("区域选择")) {
                Action action = e.getAction();
                boolean isLeft = (action == Action.LEFT_CLICK_AIR
                        || action == Action.LEFT_CLICK_BLOCK);
                boolean isRight = (action == Action.RIGHT_CLICK_AIR
                        || action == Action.RIGHT_CLICK_BLOCK);
                if (isLeft || isRight) {
                    e.setCancelled(true);
                    Block block = e.getClickedBlock();
                    if (block == null) {
                        try { block = p.getTargetBlockExact(5); }
                        catch (Exception ignored) {}
                    }
                    if (block == null) return;
                    UUID uid = p.getUniqueId();
                    if (isLeft) {
                        pos1.put(uid, block.getLocation());
                        p.sendMessage("§a§l[防护] §f位置1: "
                                + block.getX() + ", "
                                + block.getY() + ", "
                                + block.getZ());
                    } else {
                        Location newLoc = block.getLocation();
                        Location oldLoc = pos2.get(uid);
                        boolean changed = oldLoc == null
                                || oldLoc.getBlockX() != newLoc.getBlockX()
                                || oldLoc.getBlockY() != newLoc.getBlockY()
                                || oldLoc.getBlockZ() != newLoc.getBlockZ();
                        pos2.put(uid, newLoc);
                        if (changed) {
                            p.sendMessage("§a§l[防护] §f位置2: "
                                    + block.getX() + ", "
                                    + block.getY() + ", "
                                    + block.getZ());
                            // ★ 两个位置都选好后，显示面积+价格预览 + 一键创建超链接
                            if (pos1.containsKey(uid)) {
                                Location lp1 = pos1.get(uid);
                                int w = Math.abs(newLoc.getBlockX() - lp1.getBlockX()) + 1;
                                int l = Math.abs(newLoc.getBlockZ() - lp1.getBlockZ()) + 1;
                                int a = w * l;
                                UserGroupManager ug = plugin.getUserGroup();
                                int pricePerSqm = (ug != null) ? ug.getPlayerLandPricePerSqm(p.getName(), globalCreatePricePerSqm) : globalCreatePricePerSqm;
                                int totalCost = a * pricePerSqm;
                                String src = (pricePerSqm != globalCreatePricePerSqm) ? " §7（用户组优惠价）" : "";
                                BondManager bnd = plugin.getBonds();
                                int bal = (bnd != null) ? bnd.getBonds(p.getName()) : 0;
                                p.sendMessage("§e§l[防护] §f选区完成！面积: §a" + a + "㎡§7（" + w + "×" + l + "）  单价: §f" + pricePerSqm + "/㎡" + src + "  预估: §e" + totalCost + "§7债券  余额: §a" + bal);
                                // ★ 一键创建超链接：自动用玩家名，冲突则加数字
                                String autoName = p.getName();
                                if (areas.containsKey(autoName)) {
                                    for (int i = 0; i <= 99; i++) {
                                        String candidate = autoName + i;
                                        if (!areas.containsKey(candidate)) { autoName = candidate; break; }
                                    }
                                }
                                String finalAutoName = autoName;
                                p.sendMessage(Component.empty()
                                        .append(Component.text("§a§l[✅ 点击一键创建]"))
                                        .hoverEvent(HoverEvent.showText(Component.text("§e以 §f" + finalAutoName + " §e为名创建领地\n§7费用: " + totalCost + " 债券")))
                                        .clickEvent(ClickEvent.runCommand("/protect confirm_create " + finalAutoName))
                                );
                                p.sendMessage(Component.text("§7或输入 §f/protect 创建 <自定义名> §7指定领地名"));
                            }
                        }
                    }
                }
                return;
            }
        }

        // ===== 放置实体类物品时不检查（展示框、矿车、船、地图等）=====
        Material mat = hand.getType();
        if (mat == Material.ITEM_FRAME
                || mat == Material.GLOW_ITEM_FRAME
                || mat == Material.MINECART
                || mat == Material.TNT_MINECART
                || mat == Material.CHEST_MINECART
                || mat == Material.HOPPER_MINECART
                || mat == Material.OAK_BOAT
                || mat == Material.SPRUCE_BOAT
                || mat == Material.BIRCH_BOAT
                || mat == Material.JUNGLE_BOAT
                || mat == Material.ACACIA_BOAT
                || mat == Material.DARK_OAK_BOAT
                || mat == Material.MANGROVE_BOAT
                || mat == Material.CHERRY_BOAT
                || mat == Material.BAMBOO_RAFT
                || mat == Material.MAP
                || mat == Material.ARMOR_STAND) {
            return;
        }

        // ===== 区域规则检查 =====
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;

        // 末影珍珠禁止（支持per-player权限）
        if (getEffectiveDeny(p, ac, "denyEnderPearl")
                && hand.getType() == Material.ENDER_PEARL) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用末影珍珠");
            return;
        }

        // 弓箭禁止（支持per-player权限）
        if (getEffectiveDeny(p, ac, "denyBow")
                && (hand.getType() == Material.BOW
                || hand.getType() == Material.CROSSBOW)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用弓箭");
            return;
        }

        // 所有者+管理员跳过禁止使用物品检查
        if (hasPermission(p, ac, PermissionLevel.OWNER)) {
            // 管理员和所有者不受限制
        } else {
            // 禁止使用物品（访客级别检查）
            String typeId = hand.getType().name();
            if (ac.denyUseItems.contains(typeId)) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止使用此物品");
                return;
            }
        }

        // ★ 农作物收获：右键检测（南瓜/西瓜用剪刀收获）
        if (ac.denyCropHarvest && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = e.getClickedBlock();
            if (clicked != null) {
                Material clickedMat = clicked.getType();
                if (clickedMat == Material.PUMPKIN || clickedMat == Material.MELON) {
                    if (getEffectiveDeny(p, ac, "denyCropHarvest")) {
                        e.setCancelled(true);
                        p.sendMessage("§c§l[区域防护] §f禁止收获农作物");
                        return;
                    }
                }
            }
        }

        // ★ 容器交互扩展：检测非Container类型UI方块（工作台、砂轮、制图台、告示牌等）
        if (ac.denyContainer && e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = e.getClickedBlock();
            if (clicked != null) {
                Material clickedMat = clicked.getType();
                if (isUIBlock(clickedMat)) {
                    if (getEffectiveDeny(p, ac, "denyContainer")) {
                        e.setCancelled(true);
                        p.sendMessage("§c§l[区域防护] §f此领地禁止访问容器");
                        return;
                    }
                }
            }
        }

        // 没收检查
        handleConfiscate(p, ac);
    }


    @EventHandler
    public void onExplosion(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            AreaConfig ac = getArea(
                    b.getWorld().getName(),
                    b.getX(), b.getY(), b.getZ());
            if (ac != null && ac.denyExplosion)
                it.remove();
        }
    }

    // ===== 火床/烈焰弹/三叉戟闪电也拦截 =====
    @EventHandler
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        for (Iterator<Block> it = e.blockList().iterator(); it.hasNext();) {
            Block b = it.next();
            AreaConfig ac = getArea(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            if (ac != null && ac.denyExplosion) it.remove();
        }
    }

    @EventHandler
    public void onEntityChangeBlock(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        // 拦截末影龙/末影螨改变方块（如把水变成源）
        if (e.getBlock().getType() == Material.FIRE || e.getBlock().getType() == Material.LAVA) {
            AreaConfig ac = getArea(e.getBlock().getWorld().getName(), 
                    e.getBlock().getX(), e.getBlock().getY(), e.getBlock().getZ());
            if (ac != null && ac.denyFire) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();

        InventoryHolder holder = e.getInventory().getHolder();
        Location loc = null;

        // ★ 检测所有容器类型：箱子、熔炉、高炉、潜影盒、末影箱、讲台、铁砧、信标、漏斗、发射器、投掷器、酿造台、工作台等
        if (holder instanceof BlockState) {
            BlockState bs = (BlockState) holder;
            loc = bs.getLocation();
        } else if (holder instanceof Container) {
            // Container接口覆盖所有方块容器
            if (holder instanceof org.bukkit.block.Block) {
                org.bukkit.block.Block block = (org.bukkit.block.Block) holder;
                loc = block.getLocation();
            }
        }

        // ★ Issue 9: 矿车容器（漏斗矿车、运输矿车）
        if (holder != null && loc == null) {
            String className = holder.getClass().getSimpleName();
            if (className.contains("Minecart")) {
                // 矿车容器使用实体位置
                if (holder instanceof org.bukkit.entity.Entity) {
                    org.bukkit.entity.Entity entity = (org.bukkit.entity.Entity) holder;
                    loc = entity.getLocation();
                }
            }
        }

        // 末影箱特殊处理：检查末影箱所在位置而非玩家位置
        if (holder != null) {
            String typeName = holder.getClass().getSimpleName();
            if (typeName.contains("EnderChest") || typeName.contains("Ender")) {
                // 末影箱也是BlockState，已经在上面处理
            }
        }

        if (loc == null) return;

        AreaConfig ac = getArea(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (ac == null) return;

        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

        // ★ 容器管理权限：denyContainer=true时非领主/管理员不能访问容器
        if (getEffectiveDeny(p, ac, "denyContainer")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f此领地禁止访问容器");
            return;
        }

        PermissionLevel level = getPermissionLevel(p, ac);
        if (level == null) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f你不具备访问此容器的权限");
        }
    }

    public void loadWhitelists() {
        globalPlayerWhitelist.clear();
        areaPlayerWhitelist.clear();

        if (!whitelistDir.exists()) {
            whitelistDir.mkdirs();
            return;
        }

        try {
            // 加载全局白名单
            if (globalWhitelistFile.exists()) {
                List<String> lines =
                        java.nio.file.Files.readAllLines(
                                globalWhitelistFile.toPath(),
                                StandardCharsets.UTF_8);
                for (String line : lines) {
                    String name = line.trim();
                    if (!name.isEmpty()
                            && !name.startsWith("#")) {
                        globalPlayerWhitelist.add(name);
                    }
                }
            }

            // 加载各区域白名单
            File[] files = whitelistDir.listFiles(
                    (File d, String n) -> n.endsWith(".txt")
                            && !n.equals("全局白名单.txt"));
            if (files != null) {
                for (File f : files) {
                    String areaName = f.getName()
                            .replace(".txt", "");
                    Set<String> set =
                            ConcurrentHashMap.newKeySet();
                    List<String> lines =
                            java.nio.file.Files.readAllLines(
                                    f.toPath(),
                                    StandardCharsets.UTF_8);
                    for (String line : lines) {
                        String name = line.trim();
                        if (!name.isEmpty()
                                && !name.startsWith("#")) {
                            set.add(name);
                        }
                    }
                    if (!set.isEmpty()) {
                        areaPlayerWhitelist.put(
                                areaName, set);
                    }
                }
            }

            plugin.getLogger().info(
                    "[防护] 白名单加载完成，全局: "
                            + globalPlayerWhitelist.size()
                            + "，区域数: "
                            + areaPlayerWhitelist.size());
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[防护] 加载白名单失败: "
                            + e.getMessage());
        }
    }


    @EventHandler
    public void onPotionSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) e.getEntity().getShooter();
        AreaConfig ac = getArea(
                shooter.getWorld().getName(),
                shooter.getLocation().getBlockX(),
                shooter.getLocation().getBlockY(),
                shooter.getLocation().getBlockZ());
        if (ac != null && getEffectiveDeny(shooter, ac, "denyPotion")) {
            e.setCancelled(true);
            shooter.sendMessage("§c§l[区域防护] §f禁止使用药水");
        }
    }

    // ===== ★ 投掷物检测（三叉戟、雪球、风蛋、箭）=====
    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) e.getEntity().getShooter();
        Entity proj = e.getEntity();
        AreaConfig ac = getArea(
                shooter.getWorld().getName(),
                shooter.getLocation().getBlockX(),
                shooter.getLocation().getBlockY(),
                shooter.getLocation().getBlockZ());
        if (ac == null) return;
        if (!getEffectiveDeny(shooter, ac, "denyThrownProjectiles")) return;
        // 检查投掷物类型：箭、三叉戟、雪球、风蛋、龙息弹等
        String typeName = proj.getType().name();
        if (typeName.contains("ARROW") || typeName.contains("TRIDENT")
                || typeName.contains("SNOWBALL") || typeName.contains("WIND_CHARGE")
                || typeName.contains("DRAGON_FIREBALL") || typeName.contains("FIREBALL")
                || typeName.contains("SHULKER_BULLET") || typeName.contains("LLAMA_SPIT")) {
            e.setCancelled(true);
            shooter.sendMessage("§c§l[区域防护] §f禁止在此区域投掷物品");
        }
    }

    // ★ 门禁交互检测（按钮、门、压力板）
    @EventHandler
    public void onDoorInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Material mat = e.getClickedBlock().getType();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getClickedBlock().getX(),
                e.getClickedBlock().getY(),
                e.getClickedBlock().getZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyDoorInteraction")) return;
        // 按钮
        if (mat.name().contains("BUTTON")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止操作按钮");
            return;
        }
        // 压力板
        if (mat.name().contains("PRESSURE_PLATE")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止踩踏压力板");
            return;
        }
    }

    // ★ 门禁交互检测（门类 - 独立事件，因为门是RIGHT_CLICK_BLOCK）
    @EventHandler
    public void onDoorOpen(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        Material mat = e.getClickedBlock().getType();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getClickedBlock().getX(),
                e.getClickedBlock().getY(),
                e.getClickedBlock().getZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyDoorInteraction")) return;
        String name = mat.name();
        if (name.contains("DOOR") || name.contains("GATE") || name.contains("FENCE_GATE")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止操作门/栅栏门");
        }
    }

    // ★ 红石交互检测（中继器、比较器、拉杆、按钮、压力板、阳光传感器）
    @EventHandler
    public void onRedstoneInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Material mat = e.getClickedBlock().getType();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getClickedBlock().getX(),
                e.getClickedBlock().getY(),
                e.getClickedBlock().getZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyRedstoneInteraction")) return;
        String name = mat.name();
        if (mat == Material.REPEATER || mat == Material.COMPARATOR
                || mat == Material.LEVER
                || mat == Material.STONE_BUTTON || mat == Material.OAK_BUTTON
                || mat == Material.SPRUCE_BUTTON || mat == Material.BIRCH_BUTTON
                || mat == Material.JUNGLE_BUTTON || mat == Material.ACACIA_BUTTON
                || mat == Material.DARK_OAK_BUTTON || mat == Material.MANGROVE_BUTTON
                || mat == Material.CHERRY_BUTTON || mat == Material.BAMBOO_BUTTON
                || mat == Material.CRIMSON_BUTTON || mat == Material.WARPED_BUTTON
                || mat == Material.POLISHED_BLACKSTONE_BUTTON
                || mat == Material.STONE_PRESSURE_PLATE || mat == Material.OAK_PRESSURE_PLATE
                || mat == Material.SPRUCE_PRESSURE_PLATE || mat == Material.BIRCH_PRESSURE_PLATE
                || mat == Material.JUNGLE_PRESSURE_PLATE || mat == Material.ACACIA_PRESSURE_PLATE
                || mat == Material.DARK_OAK_PRESSURE_PLATE || mat == Material.MANGROVE_PRESSURE_PLATE
                || mat == Material.CHERRY_PRESSURE_PLATE || mat == Material.BAMBOO_PRESSURE_PLATE
                || mat == Material.CRIMSON_PRESSURE_PLATE || mat == Material.WARPED_PRESSURE_PLATE
                || mat == Material.POLISHED_BLACKSTONE_PRESSURE_PLATE || mat == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || mat == Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                || mat == Material.DAYLIGHT_DETECTOR
                || name.contains("REPEATER") || name.contains("COMPARATOR")
                || name.contains("BUTTON") || name.contains("PRESSURE_PLATE")
                || name.contains("DAYLIGHT")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止操作红石元件");
        }
    }

    // ★ 音频交互检测（音符盒、唱片机）
    @EventHandler
    public void onNoteblockInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Material mat = e.getClickedBlock().getType();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getClickedBlock().getX(),
                e.getClickedBlock().getY(),
                e.getClickedBlock().getZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyNoteblockJukebox")) return;
        if (mat == Material.NOTE_BLOCK || mat.name().contains("JUKEBOX")
                || mat.name().contains("CHERRY_JUKEBOX")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止操作音符盒/唱片机");
        }
    }

    // ★ 农作物状态检测（播种&收获）
    @EventHandler
    public void onCropHarvest(BlockGrowEvent e) {
        Location loc = e.getBlock().getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
        if (ac != null && ac.denyCropHarvest) {
            e.setCancelled(true);
        }
    }

    // ★ 判断方块是否为农作物
    private boolean isCropBlock(Material mat) {
        String name = mat.name();
        return name.contains("WHEAT") || name.contains("CARROT")
                || name.contains("POTATO") || name.contains("BEETROOT")
                || name.contains("MELON") || name.contains("PUMPKIN")
                || name.contains("COCOA") || name.contains("CROP")
                || name.contains("SWEET_BERRY") || name.contains("BAMBOO")
                || name.contains("SUGAR_CANE") || name.contains("NETHER_WART")
                || name.contains("KELP") || name.contains("SEA_PICKLE")
                || name.contains("CHORUS") || name.contains("TWISTING_VINES")
                || name.contains("WEEPING_VINES") || name.contains("PITCHER")
                || name.contains("TORCHFLOWER")
                || mat == Material.MELON_STEM
                || mat == Material.ATTACHED_MELON_STEM
                || mat == Material.PUMPKIN_STEM
                || mat == Material.ATTACHED_PUMPKIN_STEM;
    }

    // ★ 判断方块是否为UI交互方块（非Container类型，需要单独检测）
    private boolean isUIBlock(Material mat) {
        return mat == Material.CRAFTING_TABLE || mat == Material.STONECUTTER
                || mat == Material.GRINDSTONE || mat == Material.ANVIL
                || mat == Material.CHIPPED_ANVIL || mat == Material.DAMAGED_ANVIL
                || mat == Material.CARTOGRAPHY_TABLE || mat == Material.LOOM
                || mat == Material.SMITHING_TABLE || mat == Material.ENCHANTING_TABLE
                || mat == Material.BREWING_STAND
                || mat.name().contains("SIGN") || mat.name().contains("BANNER")
                || mat == Material.BARREL || mat.name().contains("SHULKER_BOX")
                || mat == Material.ENDER_CHEST || mat.name().contains("CHEST")
                || mat.name().contains("FURNACE") || mat.name().contains("BLAST_FURNACE")
                || mat.name().contains("SMOKER") || mat == Material.HOPPER
                || mat.name().contains("DISPENSER") || mat.name().contains("DROPPER")
                || mat == Material.BEACON || mat.name().contains("LECTERN")
                // ★ 展示架/展示柜（1.21.5+新方块）：SHELF是MC 1.21.5 Spring Drop加入的
                || mat.name().contains("SHELF") || mat.name().contains("DISPLAY")
                || mat.name().contains("SHOWCASE") || mat.name().contains("DECORATED_POT");
    }

    // ★ 判断物品是否为可种植的农作物种子（用于BlockPlaceEvent分层权限）
    private boolean isCropSeed(Material mat) {
        String name = mat.name();
        return name.contains("SEEDS") || name.contains("SAPLING")
                || name.contains("STEM") || name.contains("PICKLE")
                || mat == Material.BAMBOO || mat == Material.SUGAR_CANE
                || mat == Material.CACTUS || mat == Material.COCOA_BEANS
                || mat == Material.NETHER_WART || mat == Material.KELP
                || mat == Material.TWISTING_VINES || mat == Material.WEEPING_VINES
                || mat == Material.CHORUS_FLOWER || mat == Material.SWEET_BERRY_BUSH
                || mat == Material.CARROT || mat == Material.POTATO
                || mat == Material.BEETROOT_SEEDS || mat == Material.WHEAT_SEEDS
                || mat == Material.PITCHER_POD || mat == Material.TORCHFLOWER_SEEDS;
    }

    // ★ 检查是否为动物食物（包括玩家食物+动物专用食物）
    private boolean isAnimalFood(Material mat) {
        if (mat.isEdible()) return true; // 玩家食物（面包、胡萝卜等）
        // 动物专用食物（玩家不可食用）
        return mat == Material.WHEAT || mat == Material.BEETROOT_SEEDS
                || mat == Material.MELON_SEEDS || mat == Material.PUMPKIN_SEEDS
                || mat == Material.COCOA_BEANS || mat == Material.BONE_MEAL
                || mat == Material.NAME_TAG || mat == Material.SADDLE
                || mat.name().contains("SEEDS"); // 各种种子
    }

    // ★ 采集羊毛检测
    @EventHandler
    public void onShearEntity(PlayerShearEntityEvent e) {
        Player p = e.getPlayer();
        // 检查羊的位置
        Location loc = e.getEntity().getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyWoolShear")) return;
        String entityType = e.getEntity().getType().name();
        if (entityType.contains("SHEEP") || entityType.contains("MOoshroom")) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止在此区域剪毛");
        }
    }

    // ★ 投喂动物检测
    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = e.getPlayer();
        Entity entity = e.getRightClicked();
        // 只检测动物类
        String typeName = entity.getType().name();
        if (!(entity instanceof Animals || typeName.contains("IRON_GOLEM")
                || typeName.contains("WOLF") || typeName.contains("CAT")
                || typeName.contains("PARROT") || typeName.contains("FROG"))) {
            return;
        }
        Location loc = entity.getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyAnimalFeeding")) return;
        // 检查玩家手持的物品是否是食物（isEdible()只检测玩家食物，需额外检测动物食物）
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() != Material.AIR && isAnimalFood(hand.getType())) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止在此区域投喂动物");
        }
    }

    // ★ 拴绳使用检测
    @EventHandler
    public void onLeadUse(PlayerLeashEntityEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = e.getPlayer();
        Entity entity = e.getEntity();
        Location loc = entity.getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(),
                loc.getBlockY(),
                loc.getBlockZ());
        if (ac == null) return;
        if (!getEffectiveDeny(p, ac, "denyLead")) return;
        e.setCancelled(true);
        p.sendMessage("§c§l[区域防护] §f禁止在此区域使用拴绳");
    }

    /**
     * 解析区域名：先精准匹配，再模糊匹配
     * @param input 用户输入的区域名
     * @return 匹配到的区域名，无匹配返回null
     */
    public String resolveAreaName(String input) {
        if (input == null || input.isEmpty()) return null;
        if (input.equalsIgnoreCase("global")) return "global";

        // 第一步：精准匹配
        for (String name : areas.keySet()) {
            if (name.equalsIgnoreCase(input)) {
                return name;
            }
        }

        // 第二步：包含匹配（用户只打了部分名字）
        for (String name : areas.keySet()) {
            if (name.contains(input) || input.contains(name)) {
                return name;
            }
        }

        // 第三步：编辑距离模糊匹配（著称→主城）
        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : areas.keySet()) {
            int dist = levenshteinDistance(
                    input.toLowerCase(), name.toLowerCase());
            if (dist < bestDistance) {
                bestDistance = dist;
                bestMatch = name;
            }
        }

        // 容错阈值：编辑距离 <= 名字长度的一半
        if (bestMatch != null) {
            int threshold = Math.max(1, bestMatch.length() / 2);
            if (bestDistance <= threshold) {
                return bestMatch;
            }
        }

        return null;
    }

    /**
     * 判断字符串是否是区域名（精准或模糊）
     */
    private boolean isAreaName(String name) {
        if (name.equalsIgnoreCase("global")) return false;
        return resolveAreaName(name) != null;
    }

    /**
     * 获取模糊匹配的实际区域名（用于提示用户）
     */
    private String getResolvedName(String input) {
        return resolveAreaName(input);
    }

    /**
     * 编辑距离算法（莱文斯坦距离）
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1],
                            Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }

    /**
     * 智能重排参数：找出子命令位置，重排为标准顺序
     * 标准顺序：/protect 子命令 参数1 参数2 ...
     */
    private String[] smartReorderArgs(String[] original) {
        if (original.length <= 1) return original;

        Set<String> knownSubs = new HashSet<>(Arrays.asList(
                "add", "remove", "additem", "removeitem",
                "addname", "removename", "listname",
                "addwhite", "removewhite", "listwhite",
                "list", "listitem", "创建", "create", "删除", "delete", "重载", "reload",
                "工具", "wand", "expand", "contraction",
                "on", "off", "tempon", "modeexempt",
                "settp", "setwarp", "tp", "warp",
                "setowner", "setadmin", "unsetadmin", "addvisitor", "removevisitor", "listvisitors", "transfer", "transfeland",
                "info", "setowner", "addvisitor", "removevisitor",
                "listvisitors", "transfer", "shop",
                "menu", "菜单", "sdf1debug", "testclear", "cli",
                "取消删除", "canceldelete", "canceladminchange",
                "removemember", "addmember",
                "config", "配置",
                "public", "公共", "listpublic", "公共列表", "tpb", "传送公共",
                "group", "用户组", "groupadd", "groupdel", "grouplist", "groupset", "groupedit", "groupdelconfig", "groupmembers",
                "confirm_create"
        ));

        String first = original[0].toLowerCase();
        if (knownSubs.contains(first)) {
            return original;
        }

        int subIndex = -1;
        for (int i = 0; i < original.length; i++) {
            if (knownSubs.contains(original[i].toLowerCase())) {
                subIndex = i;
                break;
            }
        }

        if (subIndex < 0) {
            return original;
        }

        String[] reordered = new String[original.length];
        reordered[0] = original[subIndex];
        int idx = 1;
        for (int i = 0; i < original.length; i++) {
            if (i == subIndex) continue;
            reordered[idx] = original[i];
            idx++;
        }

        return reordered;
    }

    // ==================== 管理命令 ====================
    public boolean handleCommand(CommandSender sender,
                                 String[] args) {
        args = smartReorderArgs(args);
        if (args.length == 0) {
            // ★ 读取用户偏好决定打开GUI还是CLI
            if (sender instanceof Player) {
                Player p = (Player) sender;
                int uiMode = 1;
                try {
                    uiMode = plugin.getDb()
                            .getUiMode(p.getName());
                } catch (Exception ignored) {}
                if (uiMode == 0) {
                    // GUI模式 → 打开领地管理GUI
                    if (plugin.areaGUIManager != null) {
                        plugin.areaGUIManager.openMainMenu(p);
                    } else {
                        p.sendMessage("§cGUI管理器未初始化，显示文本帮助");
                        showHelp(sender);
                    }
                    return true;
                }
            }
            // CLI模式 → 显示交互式菜单
            if (sender instanceof Player) {
                Player p = (Player) sender;
                if (plugin.areaCLIManager != null) {
                    plugin.areaCLIManager.showMainMenu(p);
                    return true;
                }
            }
            showHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();

        // ★ 管理员操作时，自动分配无owner的领地
        if (sender instanceof Player && isAreaAdmin(sender)) {
            assignUnownedLandsToAdmin((Player) sender);
        }
// ===== 诊断命令 =====
        if (sub.equals("sdf1debug")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            UUID uid = p.getUniqueId();

            sender.sendMessage("§e===== 调试信息 =====");
            sender.sendMessage("§7当前区域: "
                    + playerCurrentArea.get(uid));
            sender.sendMessage("§7标记效果数: "
                    + (playerMarkedEffects.containsKey(uid)
                    ? playerMarkedEffects.get(uid).size()
                    : "无标记"));
            sender.sendMessage("§7待清理任务: "
                    + pendingClearTask.containsKey(uid));
            sender.sendMessage("§7区域数: " + areas.size());

            if (playerMarkedEffects.containsKey(uid)) {
                List<PotionEffectType> marked =
                        playerMarkedEffects.get(uid);
                for (PotionEffectType type : marked) {
                    sender.sendMessage("§7  标记: "
                            + type.getName()
                            + " 身上有="
                            + (p.getPotionEffect(type) != null));
                }
            }
            return true;
        }

// ===== 强制清理命令（测试用）=====
        if (sub.equals("testclear")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            UUID uid = p.getUniqueId();

            List<PotionEffectType> marked =
                    playerMarkedEffects.remove(uid);
            if (marked != null) {
                for (PotionEffectType type : marked) {
                    p.removePotionEffect(type);
                }
                sender.sendMessage("§a已强制清理 "
                        + marked.size() + " 个效果");
            } else {
                sender.sendMessage("§c无标记效果可清理");
            }

            // 取消延时任务
            if (pendingClearTask.containsKey(uid)) {
                pendingClearTask.get(uid).cancel();
                pendingClearTask.remove(uid);
                pendingClearArea.remove(uid);
                pendingClearEffects.remove(uid);
                sender.sendMessage("§a已取消延时任务");
            }

            removePlayerEffects(uid,
                    playerCurrentArea.getOrDefault(uid, ""));
            sender.sendMessage("§aDB已清理");
            return true;
        }

        // ===== 工具 =====
        if (sub.equals("工具") || sub.equals("wand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            // ★ 5秒冷却
            long now = System.currentTimeMillis();
            Long lastGive = wandCooldownMap.get(p.getUniqueId());
            if (lastGive != null && now - lastGive < 5000) {
                long remaining = (5000 - (now - lastGive)) / 1000 + 1;
                p.sendMessage("§c§l[防护] §f请等待 " + remaining + " 秒后再获取");
                return true;
            }
            // ★ 检查是否已拥有
            for (ItemStack item : p.getInventory().getContents()) {
                if (item != null && item.getType() == WAND
                        && item.hasItemMeta() && item.getItemMeta() != null
                        && item.getItemMeta().hasDisplayName()
                        && item.getItemMeta().getDisplayName().contains("区域选择工具")) {
                    p.sendMessage("§e§l[防护] §f你已拥有圈地工具");
                    wandCooldownMap.put(p.getUniqueId(), now);
                    return true;
                }
            }
            ItemStack tool = new ItemStack(WAND);
            ItemMeta meta = tool.getItemMeta();
            meta.setDisplayName("§a§l区域选择工具");
            meta.setLore(Arrays.asList(
                    "§7左键: 位置1", "§7右键: 位置2"));
            tool.setItemMeta(meta);
            p.getInventory().addItem(tool);
            p.sendMessage("§a§l[防护] §f已获取选择工具");
            wandCooldownMap.put(p.getUniqueId(), now);
            return true;
        }

        // ===== GUI菜单 =====
        if (sub.equals("menu") || sub.equals("菜单")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (plugin.areaGUIManager != null) {
                plugin.areaGUIManager.openMainMenu(p);
            } else {
                p.sendMessage("§cGUI管理器未初始化");
            }
            return true;
        }

        // ===== UI模式偏好 =====
        if (sub.equals("uimode") || sub.equals("偏好")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                int current = plugin.getDb()
                        .getUiMode(p.getName());
                p.sendMessage("§e当前模式: §f"
                        + (current == 0 ? "GUI" : "CLI"));
                p.sendMessage("§7用法: /protect uimode <gui|cli>");
                return true;
            }
            String mode = args[1].toLowerCase();
            if (mode.equals("gui") || mode.equals("0")) {
                plugin.getDb().setUiMode(p.getName(), 0);
                p.sendMessage("§a已切换为 §6GUI §a模式");
                // ★ 立即打开GUI
                if (plugin.areaGUIManager != null) {
                    plugin.areaGUIManager.openMainMenu(p);
                }
            } else if (mode.equals("cli") || mode.equals("1")) {
                plugin.getDb().setUiMode(p.getName(), 1);
                p.sendMessage("§a已切换为 §6CLI §a模式");
                // ★ 立即显示CLI菜单
                if (plugin.areaCLIManager != null) {
                    plugin.areaCLIManager.showMainMenu(p);
                }
            } else {
                p.sendMessage("§c用法: /protect uimode <gui|cli>");
            }
            return true;
        }

        // ===== CLI交互式菜单 =====
        if (sub.equals("cli")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (plugin.areaCLIManager == null) {
                p.sendMessage("§cCLI管理器未初始化");
                return true;
            }
            String action = args.length >= 2 ? args[1] : "menu";
            switch (action) {
                case "menu":
                    plugin.areaCLIManager.showMainMenu(p);
                    break;
                case "lands":
                    int page = 1;
                    if (args.length >= 3) {
                        try { page = Integer.parseInt(args[2]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showLandList(p, page);
                    break;
                case "manage":
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /protect cli manage <领地名> [页码]");
                        break;
                    }
                    int permPage = 1;
                    if (args.length >= 4) {
                        try { permPage = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showLandManage(p, args[2], permPage);
                    break;
                case "members":
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /protect cli members <领地名> [页码]");
                        break;
                    }
                    int memberPage = 1;
                    if (args.length >= 4) {
                        try { memberPage = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showMemberList(p, args[2], memberPage);
                    break;
                case "visitorperm":
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /protect cli visitorperm <领地名> [页码]");
                        break;
                    }
                    int vPermPage = 1;
                    if (args.length >= 4) {
                        try { vPermPage = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showVisitorPerm(p, args[2], vPermPage);
                    break;
                case "toggle":
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli toggle <领地名> <权限key> [页码]");
                        break;
                    }
                    int togglePage = 1;
                    if (args.length >= 5) {
                        try { togglePage = Integer.parseInt(args[4]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.togglePerm(p, args[2], args[3], togglePage);
                    // ★ 切换后刷新到访客授权页面（停留当前页面，不退出）
                    plugin.areaCLIManager.refreshCurrentPage(p);
                    break;
                case "config":
                    // ★ CLI全局配置页面
                    plugin.areaCLIManager.showConfigPage(p);
                    break;
                case "memberperm":
                    // ★ 成员独立权限列表
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /protect cli memberperm <领地名> [页码]");
                        break;
                    }
                    int mpPage = 1;
                    if (args.length >= 4) {
                        try { mpPage = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showMemberPermList(p, args[2], mpPage);
                    break;
                case "playerperm":
                    // ★ 某成员的独立权限编辑
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli playerperm <领地名> <玩家> [页码]");
                        break;
                    }
                    int ppPage = 1;
                    if (args.length >= 5) {
                        try { ppPage = Integer.parseInt(args[4]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showPlayerPerm(p, args[2], args[3], ppPage);
                    break;
                case "toggleplayerperm":
                    // ★ 切换某成员的独立权限
                    if (args.length < 5) {
                        p.sendMessage("§c用法: /protect cli toggleplayerperm <领地名> <玩家> <权限key> [页码]");
                        break;
                    }
                    int tpPage = 1;
                    if (args.length >= 6) {
                        try { tpPage = Integer.parseInt(args[5]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.togglePlayerPerm(p, args[2], args[3], args[4], tpPage);
                    break;
                case "toggleadmin":
                    // ★ 切换玩家的管理员身份（领地主专属）
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli toggleadmin <领地名> <玩家> [页码]");
                        break;
                    }
                    int taPage = 1;
                    if (args.length >= 5) {
                        try { taPage = Integer.parseInt(args[4]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.toggleAdmin(p, args[2], args[3], taPage);
                    break;
                case "clearplayerperm":
                    // ★ 清除某成员所有自定义权限
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli clearplayerperm <领地名> <玩家>");
                        break;
                    }
                    plugin.areaCLIManager.clearPlayerPerm(p, args[2], args[3]);
                    break;
                case "effectsmgmt":
                    // ★ 效果管理菜单
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /protect cli effectsmgmt <领地名> [子页]");
                        break;
                    }
                    int effPage = 1;
                    if (args.length >= 4) {
                        try { effPage = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    }
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], effPage);
                    break;
                case "effectsclearall":
                    // ★ 切换「全清负面效果」
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsclearall <领地名> on/off");
                        break;
                    }
                    plugin.areaCLIManager.toggleClearAllBadEffects(p, args[2], args[3]);
                    // 刷新：回到效果管理(subPage 1)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 1);
                    break;
                case "effectsdenyall":
                    // ★ 切换「禁止所有效果」
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsdenyall <领地名> on/off");
                        break;
                    }
                    plugin.areaCLIManager.toggleDenyAllEffects(p, args[2], args[3]);
                    // 刷新：回到效果管理(subPage 1)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 1);
                    break;
                case "effectsclearremove":
                    // ★ 移除指定清除效果
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsclearremove <领地名> <序号>");
                        break;
                    }
                    int clrIdx = 1;
                    try { clrIdx = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    plugin.areaCLIManager.removeClearEffect(p, args[2], clrIdx);
                    // 刷新：回到单清效果列表(subPage 2)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 2);
                    break;
                case "effectsclearadd":
                    // ★ 添加清除效果
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsclearadd <领地名> <效果名>");
                        break;
                    }
                    plugin.areaCLIManager.addClearEffect(p, args[2], args[3]);
                    // 刷新：回到单清效果列表(subPage 2)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 2);
                    break;
                case "effectsaddremove":
                    // ★ 移除指定增益效果
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsaddremove <领地名> <序号>");
                        break;
                    }
                    int giveIdx = 1;
                    try { giveIdx = Integer.parseInt(args[3]); } catch (Exception ignored) {}
                    plugin.areaCLIManager.removeGiveEffect(p, args[2], giveIdx);
                    // 刷新：回到增益效果列表(subPage 3)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 3);
                    break;
                case "effectsaddadd":
                    // ★ 添加增益效果: 效果名 [等级] [秒数]
                    if (args.length < 4) {
                        p.sendMessage("§c用法: /protect cli effectsaddadd <领地名> <效果名> [等级] [秒数]");
                        break;
                    }
                    String effName = args[3];
                    String effLevel = "1";
                    String effDuration = "999";
                    if (args.length >= 5) effLevel = args[4];
                    if (args.length >= 6) effDuration = args[5];
                    plugin.areaCLIManager.addGiveEffect(p, args[2], effName, effLevel, effDuration);
                    // 刷新：回到增益效果列表(subPage 3)
                    plugin.areaCLIManager.showEffectsManagement(p, args[2], 3);
                    break;
                case "effectsaddedit":
                    // ★ 编辑增益效果等级或时长: 序号 level|duration
                    if (args.length < 5) {
                        p.sendMessage("§c用法: /protect cli effectsaddedit <领地名> <序号> <level|duration>");
                        break;
                    }
                    plugin.areaCLIManager.startEditGiveEffect(p, args[2], args[3], args[4]);
                    break;
                case "create":
                    // 跳转到创建命令
                    // 直接转发
                    break;
                default:
                    plugin.areaCLIManager.showMainMenu(p);
                    break;
            }
            return true;
        }

        // ===== 创建 =====
        if (sub.equals("创建") || sub.equals("create")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            UUID u = p.getUniqueId();

            // ★ 无参数 → 快速创建（3×3以自身为中心）
            if (args.length < 2) {
                if (!pos1.containsKey(u) || !pos2.containsKey(u)) {
                    // 无选点：快速创建3×3
                    if (quickCreateLand(p)) {
                        p.sendMessage("§7领地创建成功，如需精细控制请使用圈地工具选点后再次使用");
                    }
                } else {
                    // 有选点但没有名字：用默认名创建
                    String autoName = p.getName();
                    if (areas.containsKey(autoName)) {
                        for (int i = 0; i <= 999; i++) {
                            String c = autoName + i;
                            if (!areas.containsKey(c)) { autoName = c; break; }
                        }
                    }
                    p.sendMessage("§e用法: /protect 创建 <自定义名>");
                    p.sendMessage("§7自动创建领地名: §e" + autoName);
                    // 通过confirm_create走标准流程
                    p.performCommand("protect confirm_create " + autoName);
                }
                return true;
            }

            String areaName = args[1];
            // 检查重名（数据库+txt）
            if (areas.containsKey(areaName)) {
                p.sendMessage("§c区域已存在");
                return true;
            }
            // ★ 检查每人领地数量上限（用户组专属上限）— 同时查内存和DB兜底
            UserGroupManager ugm = plugin.getUserGroup();
            int playerMaxLands = (ugm != null) ? ugm.getPlayerMaxLands(p.getName(), globalMaxLandsPerPlayer) : globalMaxLandsPerPlayer;
            long playerLandCount = areas.values().stream()
                    .filter(a -> p.getName().equalsIgnoreCase(a.owner))
                    .count();
            // ★ DB兜底：如果内存计数<上限但DB实际已有更多领地（内存可能过期）
            if (playerLandCount < playerMaxLands && dbConnection != null) {
                try {
                    java.sql.PreparedStatement cntStmt = dbConnection.prepareStatement(
                            "SELECT COUNT(*) as cnt FROM area_lands WHERE LOWER(owner) = LOWER(?)");
                    cntStmt.setString(1, p.getName());
                    java.sql.ResultSet cntRs = cntStmt.executeQuery();
                    if (cntRs.next()) {
                        long dbCount = cntRs.getLong("cnt");
                        if (dbCount > playerLandCount) playerLandCount = dbCount;
                    }
                    cntRs.close(); cntStmt.close();
                } catch (Exception ignored) {}
            }
            if (playerLandCount >= playerMaxLands) {
                String groupHint = (ugm != null && ugm.getHighestGroup(p.getName()) != null)
                        ? "（用户组: " + ugm.getHighestGroup(p.getName()).displayName + "）" : "";
                p.sendMessage("§c§l[防护] §f已达领地上限！每人最多 §e" + playerMaxLands + " §f个领地（当前: " + playerLandCount + "个）" + groupHint);
                return true;
            }
            Location l1 = pos1.get(u);
            Location l2 = pos2.get(u);

            // ★ 领地计费：计算面积并预估价格（用户组专属价格）
            int width = Math.abs(l2.getBlockX() - l1.getBlockX()) + 1;
            int length = Math.abs(l2.getBlockZ() - l1.getBlockZ()) + 1;
            int area = width * length;
            int effectivePricePerSqm = (ugm != null) ? ugm.getPlayerLandPricePerSqm(p.getName(), globalCreatePricePerSqm) : globalCreatePricePerSqm;
            int cost = area * effectivePricePerSqm;
            String priceSource = (effectivePricePerSqm != globalCreatePricePerSqm) ? "§7（用户组优惠价）" : "";

            // ★ Issue 4: 创建确认提示 — 显示预估价格 + 交互链接
            BondManager bm = plugin.getBonds();
            int balance = (bm != null) ? bm.getBonds(p.getName()) : 0;
            String balanceStr = (bm != null) ? String.valueOf(balance) : "?";
            p.sendMessage("§e§l[防护] §f创建领地 §a" + areaName + " §f的预览:");
            p.sendMessage("§7  面积: §f" + area + "㎡（" + width + "×" + length + "）");
            p.sendMessage("§7  单价: §f" + effectivePricePerSqm + "/㎡" + priceSource);
            p.sendMessage("§7  费用: §e" + cost + " §7债券  余额: §a" + balanceStr + " §7债券");
            if (bm != null && balance < cost) {
                p.sendMessage("§c§l[防护] §f余额不足！需要 §e" + cost + " §f，当前 §e" + balance);
                return true;
            }
            if (p instanceof Player) {
                Player pp = (Player) p;
                String confirmCmd = "/protect confirm_create " + areaName;
                pp.sendMessage(Component.empty()
                        .append(Component.text("§a§l[✅ 点击确认创建]"))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击确认创建领地 §f" + areaName + "\n§7费用: " + cost + " 债券")))
                        .clickEvent(ClickEvent.runCommand(confirmCmd))
                );
                pp.sendMessage(Component.text("§7或手动输入: §f/protect 创建 " + areaName));
            }
            return true;
        }

        // ===== 确认创建领地（交互链接触发） =====
        if (sub.equals("confirm_create")) {
            if (!(sender instanceof Player)) { sender.sendMessage("§c仅玩家可用"); return true; }
            if (args.length < 2) { sender.sendMessage("§c用法: /protect confirm_create <领地名>"); return true; }
            Player cp = (Player) sender;
            String confirmName = args[1];
            // 检查选点
            if (!pos1.containsKey(cp.getUniqueId()) || !pos2.containsKey(cp.getUniqueId())) {
                cp.sendMessage("§c请先用工具选点"); return true;
            }
            // 检查重名
            if (areas.containsKey(confirmName)) { cp.sendMessage("§c区域已存在"); return true; }
            // ★ 检查上限（含DB兜底）
            UserGroupManager cugm = plugin.getUserGroup();
            int cMaxLands = (cugm != null) ? cugm.getPlayerMaxLands(cp.getName(), globalMaxLandsPerPlayer) : globalMaxLandsPerPlayer;
            long cLandCount = areas.values().stream().filter(a -> cp.getName().equalsIgnoreCase(a.owner)).count();
            if (cLandCount < cMaxLands && dbConnection != null) {
                try {
                    java.sql.PreparedStatement cs = dbConnection.prepareStatement("SELECT COUNT(*) as cnt FROM area_lands WHERE LOWER(owner) = LOWER(?)");
                    cs.setString(1, cp.getName());
                    java.sql.ResultSet cr = cs.executeQuery();
                    if (cr.next()) { long dbc = cr.getLong("cnt"); if (dbc > cLandCount) cLandCount = dbc; }
                    cr.close(); cs.close();
                } catch (Exception ignored) {}
            }
            if (cLandCount >= cMaxLands) { cp.sendMessage("§c§l[防护] §f已达领地上限！"); return true; }
            Location cl1 = pos1.get(cp.getUniqueId());
            Location cl2 = pos2.get(cp.getUniqueId());
            int cw = Math.abs(cl2.getBlockX() - cl1.getBlockX()) + 1;
            int cl = Math.abs(cl2.getBlockZ() - cl1.getBlockZ()) + 1;
            int cArea = cw * cl;
            int cPrice = (cugm != null) ? cugm.getPlayerLandPricePerSqm(cp.getName(), globalCreatePricePerSqm) : globalCreatePricePerSqm;
            int cCost = cArea * cPrice;
            // 扣费
            if (cCost > 0) {
                BondManager cbm = plugin.getBonds();
                if (cbm != null) {
                    if (!cbm.deductBonds(cp.getName(), cCost, "land_create", cp.getName(), cp.getName(), "创建领地: " + confirmName + " (" + cArea + "㎡×" + cPrice + ")")) {
                        cp.sendMessage("§c§l[防护] §f扣费失败，请稍后重试"); return true;
                    }
                    String cSrc = (cPrice != globalCreatePricePerSqm) ? "（用户组优惠价）" : "";
                    cp.sendMessage("§a§l[防护] §f创建领地扣除 §e" + cCost + " §f债券（" + cArea + "㎡×" + cPrice + "/㎡）" + cSrc);
                }
            }
            AreaConfig cac = new AreaConfig();
            cac.name = confirmName; cac.owner = cp.getName(); cac.world = cl1.getWorld().getName();
            cac.x1 = cl1.getBlockX(); cac.z1 = cl1.getBlockZ(); cac.x2 = cl2.getBlockX(); cac.z2 = cl2.getBlockZ();
            cac.yMin = 0; cac.yMax = 255;
            saveAreaToDb(cac);
            areas.put(confirmName, cac); // ★ 立即更新内存
            cp.sendMessage("§a§l[防护] §f区域 " + confirmName + " 已创建 (owner: " + cp.getName() + ")");
            loadAllAreas();
            return true;
        }

        // ===== 和平白名单 =====
        if (sub.equals("addname")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别: "
                            + args[1] + " 或 " + args[2]);
                    return true;
                }
                AreaConfig ac = areas.get(parsed[0]);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在");
                    return true;
                }
                ac.peaceWhitelist.add(parsed[1]);
                saveAreaToDb(ac);
                sender.sendMessage("§a已添加: "
                        + parsed[1] + " → "
                        + parsed[0] + " 和平白名单");
                return true;
            }
            sender.sendMessage("§c用法: /protect addname "
                    + "<区域> <名字>");
            return true;
        }
        if (sub.equals("removename")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别");
                    return true;
                }
                AreaConfig ac = areas.get(parsed[0]);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在");
                    return true;
                }
                ac.peaceWhitelist.remove(parsed[1]);
                saveAreaToDb(ac);
                sender.sendMessage("§a已移除: "
                        + parsed[1] + " ← " + parsed[0]);
                return true;
            }
            sender.sendMessage("§c用法: /protect removename "
                    + "<区域> <名字>");
            return true;
        }
        if (sub.equals("listname")) {
            if (args.length < 2) {
                sender.sendMessage(
                        "§c用法: /protect listname <区域>");
                return true;
            }
            AreaConfig ac = areas.get(args[1]);
            if (ac == null) {
                sender.sendMessage("§c区域不存在");
                return true;
            }
            sender.sendMessage("§a" + args[1]
                    + " 和平白名单:");
            if (ac.peaceWhitelist.isEmpty()) {
                sender.sendMessage("§7  (空)");
            } else {
                for (String n : ac.peaceWhitelist) {
                    sender.sendMessage("§7  - " + n);
                }
            }
            return true;
        }

        // ===== 模式排除 =====
        if (sub.equals("addwhite")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别");
                    return true;
                }
                AreaConfig ac = areas.get(parsed[0]);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在");
                    return true;
                }
                ac.modeExempt.add(parsed[1]);
                saveAreaToDb(ac);
                sender.sendMessage("§a已添加: "
                        + parsed[1] + " → "
                        + parsed[0] + " 模式排除");
                return true;
            }
            sender.sendMessage("§c用法: /protect addwhite "
                    + "<区域> <玩家>");
            return true;
        }
        if (sub.equals("removewhite")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别");
                    return true;
                }
                AreaConfig ac = areas.get(parsed[0]);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在");
                    return true;
                }
                ac.modeExempt.remove(parsed[1]);
                saveAreaToDb(ac);
                sender.sendMessage("§a已移除: "
                        + parsed[1] + " ← "
                        + parsed[0] + " 模式排除");
                return true;
            }
            sender.sendMessage("§c用法: /protect removewhite "
                    + "<区域> <玩家>");
            return true;
        }
        if (sub.equals("listwhite")) {
            if (args.length < 2) {
                sender.sendMessage(
                        "§c用法: /protect listwhite <区域>");
                return true;
            }
            AreaConfig ac = areas.get(args[1]);
            if (ac == null) {
                sender.sendMessage("§c区域不存在");
                return true;
            }
            sender.sendMessage("§a" + args[1]
                    + " 模式排除名单:");
            if (ac.modeExempt.isEmpty()) {
                sender.sendMessage("§7  (空)");
            } else {
                for (String n : ac.modeExempt) {
                    sender.sendMessage("§7  - " + n);
                }
            }
            return true;
        }

        // ===== 列表 =====
        if (sub.equals("列表") || sub.equals("list")) {
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("global")) {
                    sender.sendMessage("§a全局白名单:");
                    if (globalPlayerWhitelist.isEmpty()) {
                        sender.sendMessage("§7  (空)");
                    } else {
                        for (String n
                                : globalPlayerWhitelist) {
                            sender.sendMessage("§7  - " + n);
                        }
                    }
                    return true;
                }
                String an = args[1];
                Set<String> wl =
                        areaPlayerWhitelist.get(an);
                sender.sendMessage("§a" + an + " 白名单:");
                if (wl == null || wl.isEmpty()) {
                    sender.sendMessage("§7  (空)");
                } else {
                    for (String n : wl) {
                        sender.sendMessage("§7  - " + n);
                    }
                }
                return true;
            }
            if (areas.isEmpty()) {
                sender.sendMessage("§7暂无区域");
            } else {
                sender.sendMessage("§e§l区域列表:");
                for (Map.Entry<String, AreaConfig> en
                        : areas.entrySet()) {
                    sender.sendMessage("§a  " + en.getKey()
                            + " §7规则:"
                            + en.getValue().ruleCount()
                            + "条");
                }
            }
            return true;
        }

        // ===== config 全局配置 =====
        if (sub.equals("config") || sub.equals("配置")) {
            if (!isAreaAdmin(sender)) {
                sender.sendMessage("§c需要管理员权限");
                return true;
            }
            if (args.length < 3) {
                // 查看配置
                sender.sendMessage("§e§l==== 全局配置 ====");
                sender.sendMessage("§a创建价格(每㎡): §f" + globalCreatePricePerSqm);
                sender.sendMessage("§a每人最大领地数: §f" + globalMaxLandsPerPlayer);
                sender.sendMessage("§7用法: /protect config <key> <value>");
                sender.sendMessage("§7可用key: create_price, max_lands, default_height, peace_duration");
                return true;
            }
            String key = args[1];
            String value = args[2];
            try {
                switch (key) {
                    case "create_price":
                        globalCreatePricePerSqm = parseSmartNumber(value, globalCreatePricePerSqm, 0);
                        updateAreaConfig("create_price_per_sqm", String.valueOf(globalCreatePricePerSqm));
                        break;
                    case "max_lands":
                        globalMaxLandsPerPlayer = parseSmartNumber(value, globalMaxLandsPerPlayer, 1);
                        updateAreaConfig("max_lands_per_player", String.valueOf(globalMaxLandsPerPlayer));
                        break;
                    case "default_height":
                        int newHeight = parseSmartNumber(value, 255, 1);
                        updateAreaConfig("default_height", String.valueOf(newHeight));
                        break;
                    case "peace_duration":
                        int newDuration = parseSmartNumber(value, 3600, 0);
                        updateAreaConfig("peace_mode_max_duration", String.valueOf(newDuration));
                        break;
                    default:
                        sender.sendMessage("§c未知配置项: " + key);
                        return true;
                }
                // ★ 配置变更：立即触发PHP同步
                try {
                    if (plugin.webManager != null) {
                        plugin.webManager.requestImmediateLandSync();
                    }
                } catch (Exception ignored) {}

                // ★ 更新后显示完整配置页面（而非仅打印"已更新"）
                if (plugin.areaCLIManager != null) {
                    plugin.areaCLIManager.showConfigPage((Player) sender);
                } else {
                    sender.sendMessage("§a配置已更新");
                }
            } catch (Exception e) {
                sender.sendMessage("§c无效的值: " + value + "（支持: 纯数字、+/-前缀、中文数字、罗马数字、英文数字）");
            }
            return true;
        }

        // ===== 重载 =====
        if (sub.equals("重载") || sub.equals("reload")) {
            reload();
            sender.sendMessage("§a已重载 "
                    + areas.size() + " 个区域");
            return true;
        }

        // ===== 公共建筑设施 =====
        if (sub.equals("public") || sub.equals("公共")) {
            if (!(sender instanceof Player)) { sender.sendMessage("§c仅玩家可用"); return true; }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§c用法: /protect public <领地名>");
                return true;
            }
            String landName = args[1];
            AreaConfig ac = areas.get(landName);
            if (ac == null) { sender.sendMessage("§c领地不存在: " + landName); return true; }
            // 检查权限
            if (!p.getName().equalsIgnoreCase(ac.owner) && !isAreaAdmin(sender)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }
            ac.isPublicBuilding = !ac.isPublicBuilding;
            saveAreaToDb(ac);
            if (ac.isPublicBuilding) {
                sender.sendMessage("§a§l[公共建筑] §f" + landName + " §a已设为公共建筑设施");
                sender.sendMessage("§7所有访客将自动获得传送、免疫伤害、攻击敌对生物权限");
            } else {
                sender.sendMessage("§a§l[公共建筑] §f" + landName + " §7已取消公共建筑标记");
            }
            return true;
        }
        if (sub.equals("listpublic") || sub.equals("公共列表")) {
            if (!(sender instanceof Player)) { sender.sendMessage("§c仅玩家可用"); return true; }
            Player p = (Player) sender;
            java.util.List<AreaConfig> publicLands = new ArrayList<>();
            for (AreaConfig ac : areas.values()) {
                if (ac.isPublicBuilding) publicLands.add(ac);
            }
            if (publicLands.isEmpty()) {
                sender.sendMessage("§7当前没有公共建筑设施");
                return true;
            }
            sender.sendMessage("§e§l==== 公共建筑设施 ====");
            int idx = 1;
            for (AreaConfig ac : publicLands) {
                String warpInfo = (ac.warpX != 0 || ac.warpZ != 0) ? "§a[有传送点]" : "§7[无传送点]";
                // 可点击传送的列表项
                p.sendMessage(Component.empty()
                        .append(Component.text("§e" + idx + ". §f" + ac.name + " §7所有者:" + ac.owner + " " + warpInfo))
                        .append(Component.text(" §a[传送到此]"))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击传送到 " + ac.name)))
                        .clickEvent(ClickEvent.runCommand("/protect tpb " + ac.name))
                );
                idx++;
            }
            return true;
        }
        if (sub.equals("tpb") || sub.equals("传送公共")) {
            if (!(sender instanceof Player)) { sender.sendMessage("§c仅玩家可用"); return true; }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§c用法: /protect tpb <序号或领地名>");
                return true;
            }
            java.util.List<AreaConfig> publicLands = new ArrayList<>();
            for (AreaConfig ac : areas.values()) {
                if (ac.isPublicBuilding) publicLands.add(ac);
            }
            if (publicLands.isEmpty()) { sender.sendMessage("§c没有公共建筑设施"); return true; }
            AreaConfig target = null;
            // 尝试序号
            try {
                int num = Integer.parseInt(args[1]);
                if (num >= 1 && num <= publicLands.size()) {
                    target = publicLands.get(num - 1);
                }
            } catch (NumberFormatException ignored) {}
            // 尝试名字
            if (target == null) {
                for (AreaConfig ac : publicLands) {
                    if (ac.name.equalsIgnoreCase(args[1])) { target = ac; break; }
                }
            }
            if (target == null) { sender.sendMessage("§c未找到公共建筑: " + args[1]); return true; }
            // 传送（优先用传送点，否则传送到领地中心）
            Location dest;
            if (target.warpX != 0 || target.warpZ != 0 || target.warpY != 0) {
                World w = Bukkit.getWorld(target.warpWorld != null && !target.warpWorld.isEmpty() ? target.warpWorld : p.getWorld().getName());
                if (w == null) w = p.getWorld();
                dest = new Location(w, target.warpX, target.warpY, target.warpZ, target.warpYaw, target.warpPitch);
            } else {
                dest = new Location(p.getWorld(),
                    (target.x1 + target.x2) / 2.0 + 0.5,
                    p.getWorld().getHighestBlockYAt((target.x1 + target.x2) / 2, (target.z1 + target.z2) / 2) + 1,
                    (target.z1 + target.z2) / 2.0 + 0.5);
            }
            p.teleport(dest);
            sender.sendMessage("§a已传送至公共建筑: §f" + target.name);
            return true;
        }

        // ===== 用户组管理 =====
        if (sub.equals("grouplist") || sub.equals("用户组")) {
            if (!sender.hasPermission("sdf1.admin")) {
                sender.sendMessage("§c需要管理员权限"); return true;
            }
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }

            // ★ 先从PHP拉取最新用户组（确保PHP端创建的组能显示）
            if (plugin.webManager != null) {
                plugin.webManager.pullUserGroupsFromPHP();
            }

            showGroupListPanel(sender);
            return true;
        }

        if (sub.equals("groupset")) {
            if (!sender.hasPermission("sdf1.admin")) {
                sender.sendMessage("§c需要管理员权限"); return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect groupset <组名> [显示名] [颜色] [优先级] [每㎡价格] [最大领地数]");
                sender.sendMessage("§7或: /protect groupset <组名> price <值> | maxlands <值> | priority <值>");
                sender.sendMessage("§7或: /protect groupset sync §c(从PHP同步)");
                return true;
            }
            // ★ groupset sync → 从PHP拉取用户组
            if (args[1].equalsIgnoreCase("sync")) {
                sender.sendMessage("§b正在从PHP拉取用户组...");
                if (plugin.webManager != null) {
                    plugin.webManager.pullUserGroupsFromPHP();
                }
                showGroupListPanel(sender);
                return true;
            }
            String groupName = args[1].toLowerCase();
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            UserGroupManager.UserGroupConfig cfg = ugm.getGroupConfig(groupName);

            // ★ 支持单字段编辑: /protect groupset <组名> price/maxlands/priority <值>
            if (args.length == 4 && cfg != null) {
                String field = args[2].toLowerCase();
                String valStr = args[3];
                // 智能数字解析（中文/罗马/英文数字）
                Integer parsed = parseSmartNumberBase(valStr);
                if (parsed == null) {
                    sender.sendMessage("§c无法解析数字: " + valStr);
                    return true;
                }
                int val = parsed;
                switch (field) {
                    case "price":
                    case "价格":
                        cfg.landPricePerSqm = val;
                        break;
                    case "maxlands":
                    case "上限":
                    case "领地上限":
                        cfg.maxLands = val;
                        break;
                    case "priority":
                    case "优先级":
                        cfg.priority = val;
                        break;
                    default:
                        sender.sendMessage("§c未知字段: " + field + "。支持: price/maxlands/priority");
                        return true;
                }
                ugm.updateGroupConfig(cfg);
                sender.sendMessage("§a已更新用户组 §f" + cfg.displayName + " §a的 " + field + " = " + val);
                // 刷新编辑面板
                if (sender instanceof Player) showGroupEditCLI((Player) sender, cfg);
                return true;
            }

            if (cfg == null) cfg = new UserGroupManager.UserGroupConfig();
            cfg.name = groupName;
            if (args.length >= 3) cfg.displayName = args[2];
            if (args.length >= 4) cfg.displayColor = args[3];
            if (args.length >= 5) {
                try { cfg.priority = Integer.parseInt(args[4]); } catch (Exception ignored) {}
            }
            if (args.length >= 6) {
                try { cfg.landPricePerSqm = Integer.parseInt(args[5]); } catch (Exception ignored) {}
            }
            if (args.length >= 7) {
                try { cfg.maxLands = Integer.parseInt(args[6]); } catch (Exception ignored) {}
            }
            if (cfg.displayName.isEmpty()) cfg.displayName = groupName;
            ugm.saveGroupConfigToDB(cfg);
            ugm.loadGroupConfigs();
            sender.sendMessage("§a已创建/更新用户组: §f" + cfg.displayColor + cfg.displayName
                    + " §7(name=" + groupName + ", 价格=" + cfg.landPricePerSqm + "/㎡"
                    + ", 上限=" + cfg.maxLands + ")");
            // ★ 创建/更新后重新打印面板，让用户看到组并能操作
            showGroupListPanel(sender);
            return true;
        }

        if (sub.equals("groupdelconfig")) {
            if (!sender.hasPermission("sdf1.admin")) {
                sender.sendMessage("§c需要管理员权限"); return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect groupdelconfig <组名>");
                return true;
            }
            String groupName = args[1].toLowerCase();
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            if (groupName.equals(UserGroupManager.DEFAULT_GROUP)) {
                sender.sendMessage("§c不能删除默认组"); return true;
            }
            if (ugm.deleteGroupConfig(groupName)) {
                sender.sendMessage("§a已删除用户组: " + groupName);
            } else {
                sender.sendMessage("§c未找到用户组: " + groupName);
            }
            showGroupListPanel(sender);
            return true;
        }

        // ★ 用户组交互式编辑（CLI模式）
        if (sub.equals("groupedit")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用"); return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect groupedit <组名>");
                return true;
            }
            Player gp = (Player) sender;
            String gName = args[1];
            UserGroupManager ugmEdit = plugin.getUserGroup();
            if (ugmEdit == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            UserGroupManager.UserGroupConfig cfgEdit = ugmEdit.getGroupConfig(gName);
            if (cfgEdit == null) {
                sender.sendMessage("§c未找到用户组: " + gName);
                return true;
            }
            showGroupEditCLI(gp, cfgEdit);
            return true;
        }

        if (sub.equals("groupadd") || sub.equals("addmember")) {
            if (args.length < 3) {
                sender.sendMessage("§e用法: /protect groupadd <组名> <玩家名>");
                return true;
            }
            String inputName = args[1];
            String playerName = args[2];
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            UserGroupManager.UserGroupConfig cfgAdd = ugm.getGroupConfig(inputName);
            if (cfgAdd == null) {
                sender.sendMessage("§c未找到用户组: " + inputName);
                return true;
            }
            String groupName = cfgAdd.name; // 使用DB中的实际组名
            String err = ugm.addPlayer(playerName, groupName, sender.getName());
            if (err == null) {
                sender.sendMessage("§a已将 §f" + playerName + " §a加入用户组: §f" + cfgAdd.displayName);
                // 通知在线玩家
                Player target = plugin.getServer().getPlayerExact(playerName);
                if (target != null) {
                    target.sendMessage("§a§l[用户组] §f你已被加入用户组: §e" + cfgAdd.displayName);
                }
            } else {
                sender.sendMessage("§c" + err);
            }
            return true;
        }

        if (sub.equals("groupdel")) {
            if (args.length < 3) {
                sender.sendMessage("§e用法: /protect groupdel <组名> <玩家名>");
                return true;
            }
            String inputName = args[1];
            String playerName = args[2];
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            UserGroupManager.UserGroupConfig cfgDel = ugm.getGroupConfig(inputName);
            String groupName = (cfgDel != null) ? cfgDel.name : inputName;
            if (ugm.removePlayer(playerName, groupName)) {
                String displayName = (cfgDel != null) ? cfgDel.displayName : groupName;
                sender.sendMessage("§a已将 §f" + playerName + " §a移出用户组: " + displayName);
                Player target = plugin.getServer().getPlayerExact(playerName);
                if (target != null) {
                    target.sendMessage("§a§l[用户组] §f你已被移出用户组: §e" + displayName);
                }
            } else {
                sender.sendMessage("§c操作失败（玩家可能不在此组）");
            }
            return true;
        }

        // ===== 用户组成员管理面板 =====
        if (sub.equals("groupmembers")) {
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect groupmembers <组名>");
                return true;
            }
            String inputName = args[1];
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return true; }
            UserGroupManager.UserGroupConfig cfg = ugm.getGroupConfig(inputName);
            if (cfg == null) { sender.sendMessage("§c未找到用户组: " + inputName); return true; }
            String groupName = cfg.name; // 使用DB中的实际组名（保留大小写）

            java.util.List<Map<String, Object>> members = ugm.getGroupMembers(groupName);
            sender.sendMessage("§a§l========== " + cfg.displayColor + cfg.displayName + " §a成员管理 ==========");
            sender.sendMessage("§7共 §f" + members.size() + " §7名成员");

            if (members.isEmpty()) {
                sender.sendMessage("§7暂无成员");
            } else {
                for (Map<String, Object> m : members) {
                    String pn = (String) m.get("player_name");
                    String addedBy = (String) m.get("added_by");
                    if (sender instanceof Player) {
                        Player pp = (Player) sender;
                        pp.sendMessage(Component.empty()
                                .append(Component.text("  §f" + pn + " §7(添加者: " + (addedBy != null ? addedBy : "system") + ") "))
                                .append(Component.text("§c[移除]"))
                                .hoverEvent(HoverEvent.showText(Component.text("§c点击移除此成员")))
                                .clickEvent(ClickEvent.runCommand("/protect groupdel " + groupName + " " + pn))
                        );
                    } else {
                        sender.sendMessage("  §f" + pn + " §7(添加者: " + (addedBy != null ? addedBy : "system") + ")");
                    }
                }
            }

            // 操作入口
            sender.sendMessage("");
            if (sender instanceof Player) {
                Player pp = (Player) sender;
                // 添加在线玩家
                pp.sendMessage(Component.empty()
                        .append(Component.text("§a[添加在线玩家] "))
                        .hoverEvent(HoverEvent.showText(Component.text("§e输入玩家名添加到此组")))
                        .clickEvent(ClickEvent.suggestCommand("/protect groupadd " + groupName + " "))
                );
                // 返回用户组列表（上一层）
                pp.sendMessage(Component.empty()
                        .append(Component.text("§c[返回用户组列表] "))
                        .hoverEvent(HoverEvent.showText(Component.text("§c返回用户组管理列表")))
                        .clickEvent(ClickEvent.runCommand("/protect grouplist"))
                );
                // 返回领地系统首页（CLI模式）
                pp.sendMessage(Component.empty()
                        .append(Component.text("§7[返回领地首页] "))
                        .hoverEvent(HoverEvent.showText(Component.text("§7返回领地系统主菜单")))
                        .clickEvent(ClickEvent.runCommand("/protect"))
                );
            }
            sender.sendMessage("§7§l─────────────────────────────────");
            return true;
        }

        // ===== 物品黑名单 =====
        if (sub.equals("additem")) {
            if (args.length == 2) {
                String itemName = args[1].toUpperCase();
                if (Material.matchMaterial(itemName)
                        == null) {
                    sender.sendMessage(
                            "§c无效物品ID: " + args[1]);
                    return true;
                }
                globalItemBlacklist.add(itemName);
                sender.sendMessage("§a已全局加黑: "
                        + itemName);
                return true;
            }
            if (args.length == 3) {
                String r1 = resolveAreaName(args[1]);
                String r2 = resolveAreaName(args[2]);
                String areaName;
                String itemName;
                if (r1 != null) {
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else if (r2 != null) {
                    areaName = r2;
                    itemName = args[1].toUpperCase();
                } else {
                    areaName = "global";
                    itemName = args[1].toUpperCase();
                }
                if (Material.matchMaterial(itemName)
                        == null) {
                    sender.sendMessage(
                            "§c无效物品ID: " + itemName);
                    return true;
                }
                if (areaName.equalsIgnoreCase("global")) {
                    globalItemBlacklist.add(itemName);
                    sender.sendMessage("§a已全局加黑: "
                            + itemName);
                } else {
                    areaItemBlacklist
                            .computeIfAbsent(areaName,
                                    k -> ConcurrentHashMap
                                            .newKeySet())
                            .add(itemName);
                    sender.sendMessage("§a已加黑: "
                            + itemName + " → " + areaName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect additem "
                    + "<区域/物品> <区域/物品>");
            return true;
        }
        if (sub.equals("removeitem")) {
            if (args.length == 2) {
                String itemName = args[1].toUpperCase();
                if (!globalItemBlacklist
                        .contains(itemName)) {
                    sender.sendMessage(
                            "§c全局黑名单中没有: "
                                    + itemName);
                    return true;
                }
                globalItemBlacklist.remove(itemName);
                sender.sendMessage("§a已移除: " + itemName);
                return true;
            }
            if (args.length == 3) {
                String r1 = resolveAreaName(args[1]);
                String r2 = resolveAreaName(args[2]);
                String areaName;
                String itemName;
                if (r1 != null) {
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else if (r2 != null) {
                    areaName = r2;
                    itemName = args[1].toUpperCase();
                } else {
                    areaName = "global";
                    itemName = args[1].toUpperCase();
                }
                if (areaName.equalsIgnoreCase("global")) {
                    globalItemBlacklist.remove(itemName);
                } else {
                    Set<String> list =
                            areaItemBlacklist.get(areaName);
                    if (list != null) {
                        list.remove(itemName);
                    }
                }
                sender.sendMessage("§a已移除: "
                        + itemName);
                return true;
            }
            sender.sendMessage("§c用法: /protect removeitem "
                    + "<区域/物品> <区域/物品>");
            return true;
        }
        if (sub.equals("listitem")) {
            if (args.length < 2) {
                sender.sendMessage(
                        "§a全局物品黑名单:");
                if (globalItemBlacklist.isEmpty()) {
                    sender.sendMessage("§7（空）");
                } else {
                    for (String item
                            : globalItemBlacklist) {
                        sender.sendMessage("§f- " + item);
                    }
                }
                return true;
            }
            Set<String> list =
                    areaItemBlacklist.get(args[1]);
            sender.sendMessage("§a" + args[1]
                    + " 物品黑名单:");
            if (list == null || list.isEmpty()) {
                sender.sendMessage("§7（空）");
            } else {
                for (String item : list) {
                    sender.sendMessage("§f- " + item);
                }
            }
            return true;
        }

        // ===== expand 扩建 =====
        if (sub.equals("expand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            int amount = 5;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
            int minX = Math.min(ac.x1, ac.x2);
            int maxX = Math.max(ac.x1, ac.x2);
            int minZ = Math.min(ac.z1, ac.z2);
            int maxZ = Math.max(ac.z1, ac.z2);
            float yaw = p.getLocation().getYaw();
            String dir = yawToDir(yaw);
            if (dir.equals("北")) minZ -= amount;
            else if (dir.equals("南")) maxZ += amount;
            else if (dir.equals("东")) maxX += amount;
            else if (dir.equals("西")) minX -= amount;
            ac.x1 = minX;
            ac.x2 = maxX;
            ac.z1 = minZ;
            ac.z2 = maxZ;
            saveAreaToDb(ac);
            p.sendMessage("§a§l[防护] §f区域 §e" + ac.name
                    + " §f向 §e" + dir + " §f扩建 §e"
                    + amount + "§f格");
            return true;
        }

        // ===== contract 收缩 =====
        if (sub.equals("contraction")
                || sub.equals("contract")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            int amount = 5;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
            int minX = Math.min(ac.x1, ac.x2);
            int maxX = Math.max(ac.x1, ac.x2);
            int minZ = Math.min(ac.z1, ac.z2);
            int maxZ = Math.max(ac.z1, ac.z2);
            float yaw = p.getLocation().getYaw();
            String dir = yawToDir(yaw);
            if (dir.equals("北")) minZ += amount;
            else if (dir.equals("南")) maxZ -= amount;
            else if (dir.equals("东")) maxX -= amount;
            else if (dir.equals("西")) minX += amount;
            if (minX > maxX || minZ > maxZ) {
                p.sendMessage("§c收缩过度");
                return true;
            }
            ac.x1 = minX;
            ac.x2 = maxX;
            ac.z1 = minZ;
            ac.z2 = maxZ;
            saveAreaToDb(ac);
            p.sendMessage("§a§l[防护] §f区域 §e" + ac.name
                    + " §f向 §e" + dir + " §f收缩 §e"
                    + amount + "§f格");
            return true;
        }

        // ===== 删除（带60秒延迟自动删除 + 取消）=====
        if (sub.equals("删除") || sub.equals("delete")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect 删除 <名>");
                return true;
            }
            String areaName = args[1];
            // 精确匹配
            String resolved = resolveAreaName(areaName);
            if (resolved != null) areaName = resolved;
            if (!areas.containsKey(areaName)) {
                sender.sendMessage("§c领地不存在: " + areaName);
                return true;
            }
            // ★ 领主或管理员均可删除
            AreaProtection.AreaConfig delAc = areas.get(areaName);
            boolean isOwner = delAc != null && p.getName().equalsIgnoreCase(delAc.owner);
            if (!isAreaAdmin(sender) && !isOwner) {
                sender.sendMessage("§c需要管理员权限或领地所有权");
                return true;
            }
            // ★ 检查是否已有待删除请求
            PendingDelete existing = pendingDeletes.get(areaName);
            if (existing != null) {
                // 已有等待期，提示取消方式
                long elapsed = System.currentTimeMillis() - existing.startTime;
                long remaining = Math.max(0, (60000 - elapsed) / 1000);
                sender.sendMessage("§e领地 §c" + areaName + " §e正在等待删除中，还剩 §c" + remaining + " §e秒后自动删除");
                sender.sendMessage("§7输入 §e/protect 取消删除 " + areaName + " §7可取消");
                return true;
            }
            // ★ 新建等待期：60秒后自动删除
            sender.sendMessage("§e⚠ 此操作不可逆！领地 §c" + areaName + " §e将在 §c60秒 §e后自动删除");
            sender.sendMessage("§7输入 §e/protect 取消删除 " + areaName + " §7可取消");
            // 也显示到领地列表（CLI/GUI取消按钮会自动刷新）
            UUID uid = p.getUniqueId();
            String finalAreaName = areaName;
            long start = System.currentTimeMillis();
            BukkitTask task = new BukkitRunnable() {
                @Override
                public void run() {
                    pendingDeletes.remove(finalAreaName);
                    // ★ 60秒到期后自动删除领地
                    if (areas.containsKey(finalAreaName)) {
                        deleteAreaFromDb(finalAreaName);
                        areas.remove(finalAreaName);
                        plugin.getLogger().info("[防护] 领地 §e" + finalAreaName + " §7等待期结束，已自动删除");
                        // 通知发起者
                        Player online = Bukkit.getPlayer(uid);
                        if (online != null && online.isOnline()) {
                            online.sendMessage("§a§l[防护] §f领地 §e" + finalAreaName + " §f等待期结束，已自动删除");
                        }
                        // 通知所有在线管理员
                        for (Player admin : Bukkit.getOnlinePlayers()) {
                            if (admin.getUniqueId().equals(uid)) continue;
                            if (isAreaAdmin(admin)) {
                                admin.sendMessage("§7[防护] 领地 §e" + finalAreaName + " §7等待期结束，已自动删除");
                            }
                        }
                    }
                }
            }.runTaskLater(plugin, 1200L); // 60秒 = 1200 tick
            pendingDeletes.put(areaName, new PendingDelete(areaName, p.getName(), start, task));
            return true;
        }

        // ===== 取消删除 =====
        if (sub.equals("取消删除") || sub.equals("canceldelete")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect 取消删除 <领地名>");
                return true;
            }
            String areaName = args[1];
            String resolved = resolveAreaName(areaName);
            if (resolved != null) areaName = resolved;
            PendingDelete pd = pendingDeletes.remove(areaName);
            if (pd == null) {
                sender.sendMessage("§c没有待删除的领地: " + areaName);
                return true;
            }
            pd.task.cancel();
            sender.sendMessage("§a§l[防护] §f已取消删除领地: §e" + areaName);
            return true;
        }

        // ===== 取消管理员改主 =====
        if (sub.equals("canceladminchange")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect canceladminchange <领地名>");
                return true;
            }
            String areaName = args[1];
            String resolved = resolveAreaName(areaName);
            if (resolved != null) areaName = resolved;

            // 检查领地是否存在
            AreaConfig ac = getLand(areaName);
            if (ac == null) {
                sender.sendMessage("§c领地不存在: " + areaName);
                return true;
            }

            // 检查是否是原主人
            if (!p.getName().equalsIgnoreCase(ac.owner)) {
                sender.sendMessage("§c只有原主人才能撤回改主");
                return true;
            }

            // 查找最近的管理员改主记录
            try {
                if (dbConnection == null) {
                    sender.sendMessage("§c数据库未连接");
                    return true;
                }
                PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT id, change_data FROM web_admin_changes WHERE change_type = 'owner_change' AND target_name = ? AND status = 'completed' ORDER BY id DESC LIMIT 1");
                ps.setString(1, areaName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int changeId = rs.getInt("id");
                    String changeDataStr = rs.getString("change_data");
                    java.util.Map<String, Object> changeData = WebManager.parseJson(changeDataStr);
                    String newOwner = (String) changeData.get("new_owner");
                    String oldOwner = (String) changeData.get("old_owner");

                    // 验证当前owner是否是newOwner（即已经被改主了）
                    if (!p.getName().equalsIgnoreCase(newOwner)) {
                        sender.sendMessage("§c当前所有者不是你，无法撤回");
                        rs.close();
                        ps.close();
                        return true;
                    }

                    // 回退owner
                    PreparedStatement updatePs = dbConnection.prepareStatement(
                        "UPDATE area_lands SET owner = ? WHERE name = ?");
                    updatePs.setString(1, oldOwner);
                    updatePs.setString(2, areaName);
                    updatePs.executeUpdate();
                    updatePs.close();

                    // 更新内存
                    ac.owner = oldOwner;

                    // 通知PHP回滚
                    plugin.webManager.callbackOwnerChangeToPHP(changeId, false, "原主人撤回");

                    sender.sendMessage("§a§l[防护] §f已撤回领地 §e" + areaName + " §f的改主，恢复为 §a" + oldOwner);
                    plugin.getLogger().info("[防护] " + p.getName() + " 撤回了领地 " + areaName + " 的管理员改主");
                } else {
                    sender.sendMessage("§c没有可撤回的改主记录");
                }
                rs.close();
                ps.close();
            } catch (Exception e) {
                sender.sendMessage("§c撤回失败: " + e.getMessage());
                plugin.getLogger().warning("[防护] 撤回管理员改主失败: " + e.getMessage());
            }
            return true;
        }

        // ===== add 加白名单 =====
        if (sub.equals("add")) {
            if (args.length == 2) {
                String name = args[1];
                if (globalPlayerWhitelist.contains(name)) {
                    sender.sendMessage("§e" + name
                            + " 已在全局白名单中");
                    return true;
                }
                globalPlayerWhitelist.add(name);
                saveWhitelists();
                sender.sendMessage("§a已全局加白: " + name);
                return true;
            }
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别");
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (areaName.equalsIgnoreCase("global")) {
                    globalPlayerWhitelist.add(playerName);
                    saveWhitelists();
                    sender.sendMessage("§a已全局加白: "
                            + playerName);
                } else {
                    Set<String> wl = areaPlayerWhitelist
                            .computeIfAbsent(areaName,
                                    k -> ConcurrentHashMap
                                            .newKeySet());
                    wl.add(playerName);
                    saveWhitelists();
                    sender.sendMessage("§a已加白: "
                            + playerName + " → " + areaName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect add "
                    + "<区域/玩家> <区域/玩家>");
            return true;
        }

        // ===== remove 移除白名单 =====
        if (sub.equals("remove")) {
            if (args.length == 2) {
                String name = args[1];
                if (!globalPlayerWhitelist.contains(name)) {
                    sender.sendMessage("§e" + name
                            + " 不在全局白名单中");
                    return true;
                }
                globalPlayerWhitelist.remove(name);
                saveWhitelists();
                sender.sendMessage("§a已从全局白名单移除: "
                        + name);
                return true;
            }
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(
                        args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别");
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (areaName.equalsIgnoreCase("global")) {
                    globalPlayerWhitelist.remove(playerName);
                    saveWhitelists();
                    sender.sendMessage("§a已移除: "
                            + playerName);
                } else {
                    Set<String> wl =
                            areaPlayerWhitelist.get(areaName);
                    if (wl != null) {
                        wl.remove(playerName);
                    }
                    saveWhitelists();
                    sender.sendMessage("§a已从 " + areaName
                            + " 移除: " + playerName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect remove "
                    + "<区域/玩家> <区域/玩家>");
            return true;
        }

        // ===== on/off/tempon 边框 =====
        if (sub.equals("on")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            showBorder(p, ac, false);
            p.sendMessage("§a§l[防护] §f已显示 "
                    + ac.name + " 边框");
            return true;
        }
        if (sub.equals("off")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            cancelBorder((Player) sender);
            ((Player) sender).sendMessage(
                    "§a§l[防护] §f已关闭边框显示");
            return true;
        }
        if (sub.equals("tempon")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            showBorder(p, ac, true);
            p.sendMessage("§a§l[防护] §f临时显示 "
                    + ac.name + " 边框（15秒）");
            return true;
        }

        // ===== shop 权限商店 =====
        if (sub.equals("shop")) {
            String shopAction = args.length >= 2 ? args[1] : "";
            // shop create <领地> <价格> [时长秒] — 所有者创建权限售卖
            if (shopAction.equals("create")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c仅玩家可用");
                    return true;
                }
                Player p = (Player) sender;
                if (args.length < 4) {
                    sender.sendMessage("§e用法: /protect shop create <领地> <价格> [时长秒]");
                    sender.sendMessage("§7时长默认86400秒(24h)，最大86400秒");
                    return true;
                }
                String areaName = resolveAreaName(args[2]);
                if (areaName == null) {
                    sender.sendMessage("§c领地不存在: " + args[2]);
                    return true;
                }
                AreaConfig ac = areas.get(areaName);
                if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                    sender.sendMessage("§c需要领地所有者或管理员权限");
                    return true;
                }
                int price;
                try { price = Integer.parseInt(args[3]); }
                catch (NumberFormatException e) {
                    sender.sendMessage("§c价格必须是整数");
                    return true;
                }
                if (price < 1000) {
                    sender.sendMessage("§c最低价格为1000债券");
                    return true;
                }
                int duration = 86400; // 默认24h
                if (args.length >= 5) {
                    try { duration = Integer.parseInt(args[4]); }
                    catch (NumberFormatException e) {
                        sender.sendMessage("§c时长必须是整数(秒)");
                        return true;
                    }
                }
                if (duration > 86400) {
                    sender.sendMessage("§c最大时长为86400秒(24小时)");
                    return true;
                }
                // 获取land_id
                int landId = getLandIdFromDb(areaName);
                if (landId <= 0) {
                    sender.sendMessage("§c领地数据库记录不存在");
                    return true;
                }
                // 插入商店记录
                try {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "INSERT INTO area_permission_shop (land_id, seller, permission, price, duration, created_at) "
                                    + "VALUES (?, ?, 'visitor', ?, ?, ?)");
                    stmt.setInt(1, landId);
                    stmt.setString(2, p.getName());
                    stmt.setInt(3, price);
                    stmt.setInt(4, duration);
                    stmt.setLong(5, System.currentTimeMillis());
                    stmt.executeUpdate();
                    stmt.close();
                    sender.sendMessage("§a§l[权限商店] §f已上架访客权限: §e" + areaName + " §f价格: §e" + price + "债券 §f时长: §e" + formatDuration(duration));
                } catch (SQLException e) {
                    sender.sendMessage("§c数据库错误: " + e.getMessage());
                }
                return true;
            }

            // shop remove <id> — 下架
            if (shopAction.equals("remove")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c仅玩家可用");
                    return true;
                }
                Player p = (Player) sender;
                if (args.length < 3) {
                    sender.sendMessage("§e用法: /protect shop remove <商品ID>");
                    return true;
                }
                int shopId;
                try { shopId = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage("§cID必须是整数");
                    return true;
                }
                try {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "DELETE FROM area_permission_shop WHERE id = ? AND seller = ? AND status = 'active'");
                    stmt.setInt(1, shopId);
                    stmt.setString(2, p.getName());
                    int rows = stmt.executeUpdate();
                    stmt.close();
                    if (rows > 0) {
                        sender.sendMessage("§a§l[权限商店] §f已下架商品 #" + shopId);
                    } else {
                        sender.sendMessage("§c商品不存在或你不是卖家");
                    }
                } catch (SQLException e) {
                    sender.sendMessage("§c数据库错误: " + e.getMessage());
                }
                return true;
            }

            // shop list — 查看可购买的权限
            if (shopAction.equals("list") || shopAction.isEmpty()) {
                try {
                    Statement stmt = dbConnection.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            "SELECT s.id, l.name AS land_name, s.seller, s.price, s.duration "
                                    + "FROM area_permission_shop s "
                                    + "JOIN area_lands l ON s.land_id = l.id "
                                    + "WHERE s.status = 'active' AND s.buyer = '' "
                                    + "ORDER BY s.created_at DESC LIMIT 50");
                    sender.sendMessage("§e§l==== 权限商店 ====");
                    boolean found = false;
                    while (rs.next()) {
                        found = true;
                        int id = rs.getInt("id");
                        String landName = rs.getString("land_name");
                        String seller = rs.getString("seller");
                        int price = rs.getInt("price");
                        int dur = rs.getInt("duration");
                        sender.sendMessage("§a#" + id + " §f" + landName + " §7卖家:" + seller + " §e" + price + "债券 §7" + formatDuration(dur));
                    }
                    if (!found) {
                        sender.sendMessage("§7(暂无在售权限)");
                    }
                    rs.close();
                    stmt.close();
                } catch (SQLException e) {
                    sender.sendMessage("§c数据库错误: " + e.getMessage());
                }
                return true;
            }

            // shop buy <id> — 购买权限
            if (shopAction.equals("buy")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c仅玩家可用");
                    return true;
                }
                Player p = (Player) sender;
                if (args.length < 3) {
                    sender.sendMessage("§e用法: /protect shop buy <商品ID>");
                    return true;
                }
                int shopId;
                try { shopId = Integer.parseInt(args[2]); }
                catch (NumberFormatException e) {
                    sender.sendMessage("§cID必须是整数");
                    return true;
                }
                // 查询商品
                try {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "SELECT s.*, l.name AS land_name FROM area_permission_shop s "
                                    + "JOIN area_lands l ON s.land_id = l.id "
                                    + "WHERE s.id = ? AND s.status = 'active' AND s.buyer = ''");
                    stmt.setInt(1, shopId);
                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) {
                        sender.sendMessage("§c商品不存在或已售出");
                        stmt.close();
                        return true;
                    }
                    String seller = rs.getString("seller");
                    int price = rs.getInt("price");
                    int duration = rs.getInt("duration");
                    int landId = rs.getInt("land_id");
                    String landName = rs.getString("land_name");
                    rs.close();
                    stmt.close();

                    // 不能买自己的
                    if (seller.equalsIgnoreCase(p.getName())) {
                        sender.sendMessage("§c不能购买自己上架的权限");
                        return true;
                    }

                    // 扣款
                    BondManager bm = plugin.getBonds();
                    if (bm == null) {
                        sender.sendMessage("§c债券系统未初始化");
                        return true;
                    }
                    if (bm.getBonds(p.getName()) < price) {
                        sender.sendMessage("§c债券不足，需要 §e" + price + " §c债券");
                        return true;
                    }
                    if (!bm.deductBonds(p.getName(), price, "permission_shop_buy", "", "权限商店", "购买权限: " + landName)) {
                        sender.sendMessage("§c扣款失败");
                        return true;
                    }

                    // 分账：80%给卖家，20%税
                    int ownerShare = price * 80 / 100;
                    int tax = price - ownerShare;
                    bm.addBonds(seller, ownerShare, "permission_shop_sell", p.getName(), "权限商店", "出售权限: " + landName);

                    // 标记已售出
                    PreparedStatement updateStmt = dbConnection.prepareStatement(
                            "UPDATE area_permission_shop SET status = 'sold', buyer = ?, bought_at = ? WHERE id = ?");
                    updateStmt.setString(1, p.getName());
                    updateStmt.setLong(2, System.currentTimeMillis());
                    updateStmt.setInt(3, shopId);
                    updateStmt.executeUpdate();
                    updateStmt.close();

                    // 写入访客权限（带过期时间）
                    long expiresAt = System.currentTimeMillis() + (long) duration * 1000;
                    PreparedStatement permStmt = dbConnection.prepareStatement(
                            "INSERT OR REPLACE INTO area_land_permissions (land_id, player_name, role, permissions, granted_at, expires_at) "
                                    + "VALUES (?, ?, 'visitor', 'shop_purchase', ?, ?)");
                    permStmt.setInt(1, landId);
                    permStmt.setString(2, p.getName());
                    permStmt.setLong(3, System.currentTimeMillis());
                    permStmt.setLong(4, expiresAt);
                    permStmt.executeUpdate();
                    permStmt.close();

                    sender.sendMessage("§a§l[权限商店] §f购买成功!");
                    sender.sendMessage("§f领地: §e" + landName + " §f时长: §e" + formatDuration(duration));
                    sender.sendMessage("§f花费: §e" + price + " §f债券 (卖家得 §e" + ownerShare + " §f债券)");

                    // 通知卖家
                    Player sellerPlayer = Bukkit.getPlayerExact(seller);
                    if (sellerPlayer != null) {
                        sellerPlayer.sendMessage("§a§l[权限商店] §f你的权限商品被 §e" + p.getName() + " §f购买，到账 §e" + ownerShare + " §f债券");
                    }
                } catch (SQLException e) {
                    sender.sendMessage("§c数据库错误: " + e.getMessage());
                }
                return true;
            }

            // shop my — 查看我购买的权限
            if (shopAction.equals("my")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c仅玩家可用");
                    return true;
                }
                Player p = (Player) sender;
                try {
                    PreparedStatement stmt = dbConnection.prepareStatement(
                            "SELECT p2.*, l.name AS land_name FROM area_land_permissions p2 "
                                    + "JOIN area_lands l ON p2.land_id = l.id "
                                    + "WHERE p2.player_name = ? AND p2.role = 'visitor' "
                                    + "AND (p2.expires_at = 0 OR p2.expires_at > ?) "
                                    + "ORDER BY p2.expires_at DESC");
                    stmt.setString(1, p.getName());
                    stmt.setLong(2, System.currentTimeMillis());
                    ResultSet rs = stmt.executeQuery();
                    sender.sendMessage("§e§l==== 我的领地权限 ====");
                    boolean found = false;
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        found = true;
                        String landName = rs.getString("land_name");
                        long expiresAt = rs.getLong("expires_at");
                        if (expiresAt > 0) {
                            long remain = expiresAt - now;
                            sender.sendMessage("§a" + landName + " §7剩余: §e" + formatDuration((int)(remain / 1000)));
                        } else {
                            sender.sendMessage("§a" + landName + " §7永久");
                        }
                    }
                    if (!found) {
                        sender.sendMessage("§7(暂无领地权限)");
                    }
                    rs.close();
                    stmt.close();
                } catch (SQLException e) {
                    sender.sendMessage("§c数据库错误: " + e.getMessage());
                }
                return true;
            }

            sender.sendMessage("§e用法: /protect shop <create|remove|list|buy|my>");
            return true;
        }

        // ===== info 显示当前领地权限信息 =====
        if (sub.equals("info")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            PermissionLevel level = getPermissionLevel(p, ac);
            String levelName = level != null ? level.name() : "无权限";
            p.sendMessage("§e§l==== 领地信息 ====");
            p.sendMessage("§f名称: §e" + ac.name);
            p.sendMessage("§f所有者: §e" + (ac.owner != null && !ac.owner.isEmpty() ? ac.owner : "无"));
            p.sendMessage("§f你的权限: §a" + levelName);
            if (level == PermissionLevel.OWNER) {
                p.sendMessage("§7(你是此领地的所有者)");
            } else if (level == PermissionLevel.ADMIN) {
                p.sendMessage("§7(你是服务器管理员)");
            } else if (level == PermissionLevel.VISITOR) {
                p.sendMessage("§7(你是此领地的访客)");
            }
            return true;
        }

        // ===== setowner 设置领地所有者（管理员命令）=====
        if (sub.equals("setowner") || sub.equals("setowner")) {
            if (!isAreaAdmin(sender)) {
                sender.sendMessage("§c需要管理员权限");
                return true;
            }
            // If args.length < 3, try to auto-assign: use current location's land and set admin as owner
            if (args.length < 3) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§e用法: /protect setowner <领地> <玩家>");
                    return true;
                }
                Player p = (Player) sender;
                AreaConfig ac = getArea(p.getWorld().getName(), p.getLocation().getBlockX(), p.getLocation().getBlockY(), (int) p.getLocation().getZ());
                if (ac == null) {
                    sender.sendMessage("§c你不在任何领地内");
                    return true;
                }
                if (ac.owner != null && !ac.owner.isEmpty()) {
                    sender.sendMessage("§c该领地已有所有者: " + ac.owner + "。用法: /protect setowner <领地> <新玩家>");
                    return true;
                }
                ac.owner = p.getName();
                saveAreaToDb(ac);
                cancelPendingDelete(ac.name);
                sender.sendMessage("§a已自动将 §e" + ac.name + " §a的所有者设为 §e" + p.getName());
                return true;
            }
            String areaName = resolveAreaName(args[1]);
            if (areaName == null) {
                sender.sendMessage("§c领地不存在: " + args[1]);
                return true;
            }
            AreaConfig ac = areas.get(areaName);
            ac.owner = args[2];
            saveAreaToDb(ac);
            // ★ 删除冷却期间变更所有者 → 自动取消删除
            cancelPendingDelete(areaName);
            sender.sendMessage("§a已设置 §e" + areaName + " §a的所有者为 §e" + args[2]);
            return true;
        }

        // ===== setadmin 设置管理员（通过ScoreboardTag）=====
        if (sub.equals("setadmin")) {
            if (!isAreaAdmin(sender)) {
                sender.sendMessage("§c需要管理员权限");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect setadmin <玩家>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c玩家不在线: " + args[1]);
                return true;
            }
            ConfigManager cfg = plugin.getConfigMgr();
            String tag = cfg != null ? cfg.areaProtectAdminTag : null;
            if (tag == null || tag.isEmpty()) {
                sender.sendMessage("§c管理员标签未配置");
                return true;
            }
            if (target.getScoreboardTags().contains(tag)) {
                sender.sendMessage("§e" + args[1] + " §c已经是管理员");
                return true;
            }
            target.addScoreboardTag(tag);
            sender.sendMessage("§a已将 §e" + args[1] + " §a设置为管理员");
            target.sendMessage("§6[区域防护] §a你已被设置为管理员");
            return true;
        }

        // ===== unsetadmin 移除管理员 =====
        if (sub.equals("unsetadmin")) {
            if (!isAreaAdmin(sender)) {
                sender.sendMessage("§c需要管理员权限");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect unsetadmin <玩家>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§c玩家不在线: " + args[1]);
                return true;
            }
            ConfigManager cfg = plugin.getConfigMgr();
            String tag = cfg != null ? cfg.areaProtectAdminTag : null;
            if (tag == null || tag.isEmpty()) {
                sender.sendMessage("§c管理员标签未配置");
                return true;
            }
            if (!target.getScoreboardTags().contains(tag)) {
                sender.sendMessage("§e" + args[1] + " §c不是管理员");
                return true;
            }
            target.removeScoreboardTag(tag);
            sender.sendMessage("§a已移除 §e" + args[1] + " §a的管理员权限");
            target.sendMessage("§6[区域防护] §c你的管理员权限已被移除");
            return true;
        }

        // ===== addvisitor 添加访客（所有者+管理员）=====
        if (sub.equals("addvisitor")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            String[] parsed = parseAreaAndTarget(args.length >= 3 ? args[1] : null, args.length >= 3 ? args[2] : null);
            if (parsed == null || args.length < 2) {
                // 尝试获取玩家当前所在领地
                AreaConfig ac = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac == null) {
                    sender.sendMessage("§c用法: /protect addvisitor <领地> <玩家>");
                    return true;
                }
                if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                    sender.sendMessage("§c需要领地所有者或管理员权限");
                    return true;
                }
                // ★ 删除冷却期间添加成员 → 自动取消删除
                if (hasPendingDelete(ac.name)) {
                    cancelPendingDelete(ac.name);
                    p.sendMessage("§e§l[防护] §f检测到成员变更，已自动取消领地删除");
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /protect addvisitor <玩家>");
                    return true;
                }
                // ★ 领地主不能作为成员添加
                if (args[1].equalsIgnoreCase(ac.owner)) {
                    sender.sendMessage("§c领地所有者本身就是领地主，无需添加为成员");
                    return true;
                }
                addPlayerToAreaWhitelist(ac.name, args[1]);
                sender.sendMessage("§a已添加 §e" + args[1] + " §a为 §e" + ac.name + " §a的访客");
                return true;
            }
            String areaName = parsed[0];
            String playerName = parsed[1];
            AreaConfig ac = areas.get(areaName);
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }
            // ★ 领地主不能作为成员添加
            if (playerName.equalsIgnoreCase(ac.owner)) {
                sender.sendMessage("§c领地所有者本身就是领地主，无需添加为成员");
                return true;
            }
            // ★ 删除冷却期间添加成员 → 自动取消删除
            if (hasPendingDelete(areaName)) {
                cancelPendingDelete(areaName);
                p.sendMessage("§e§l[防护] §f检测到成员变更，已自动取消领地删除");
            }
            addPlayerToAreaWhitelist(areaName, playerName);
            sender.sendMessage("§a已添加 §e" + playerName + " §a为 §e" + areaName + " §a的访客");
            return true;
        }

        // ===== removevisitor / removemember 移除访客/成员 =====
        if (sub.equals("removevisitor") || sub.equals("removemember")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            String[] parsed = parseAreaAndTarget(args.length >= 3 ? args[1] : null, args.length >= 3 ? args[2] : null);
            if (parsed == null || args.length < 2) {
                AreaConfig ac = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac == null) {
                    sender.sendMessage("§c用法: /protect removevisitor <领地> <玩家>");
                    return true;
                }
                if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                    sender.sendMessage("§c需要领地所有者或管理员权限");
                    return true;
                }
                // ★ 删除冷却期间移除成员 → 自动取消删除
                if (hasPendingDelete(ac.name)) {
                    cancelPendingDelete(ac.name);
                    p.sendMessage("§e§l[防护] §f检测到成员变更，已自动取消领地删除");
                }
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /protect removevisitor <玩家>");
                    return true;
                }
                removePlayerFromAreaWhitelist(ac.name, args[1]);
                sender.sendMessage("§a已移除 §e" + args[1] + " §a的 §e" + ac.name + " §a访客权限");
                return true;
            }
            String areaName = parsed[0];
            String playerName = parsed[1];
            AreaConfig ac = areas.get(areaName);
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }
            // ★ 删除冷却期间移除成员 → 自动取消删除
            if (hasPendingDelete(areaName)) {
                cancelPendingDelete(areaName);
                p.sendMessage("§e§l[防护] §f检测到成员变更，已自动取消领地删除");
            }
            removePlayerFromAreaWhitelist(areaName, playerName);
            sender.sendMessage("§a已移除 §e" + playerName + " §a的 §e" + areaName + " §a访客权限");
            return true;
        }

        // ===== listvisitors 列出访客 =====
        if (sub.equals("listvisitors")) {
            String areaName = args.length >= 2 ? resolveAreaName(args[1]) : null;
            if (areaName == null && sender instanceof Player) {
                Player p = (Player) sender;
                AreaConfig ac = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac != null) areaName = ac.name;
            }
            if (areaName == null) {
                sender.sendMessage("§c用法: /protect listvisitors <领地>");
                return true;
            }
            Set<String> visitors = areaPlayerWhitelist.get(areaName);
            sender.sendMessage("§e§l==== " + areaName + " 访客列表 ====");
            if (visitors == null || visitors.isEmpty()) {
                sender.sendMessage("§7(无访客)");
            } else {
                for (String v : visitors) {
                    sender.sendMessage("§f- §a" + v);
                }
            }
            return true;
        }

        // ===== transfer / transfeland 转让领地 =====
        if (sub.equals("transfer") || sub.equals("transfeland")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect transfer <领地> <新所有者>");
                return true;
            }
            // ★ 如果只给了领地名，进入交互式输入流程
            if (args.length == 2) {
                String areaName = resolveAreaName(args[1]);
                if (areaName == null) {
                    sender.sendMessage("§c领地不存在: " + args[1]);
                    return true;
                }
                AreaConfig ac = areas.get(areaName);
                if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                    sender.sendMessage("§c需要领地所有者或管理员权限");
                    return true;
                }
                // 记录待输入状态
                pendingTransferInput.put(p.getUniqueId(), new String[]{areaName});
                p.sendMessage("§e请在聊天栏输入新所有者名称（输入 §c取消§e）");
                p.sendMessage("§7§l───────────────────────────────");
                return true;
            }
            String areaName = resolveAreaName(args[1]);
            if (areaName == null) {
                sender.sendMessage("§c领地不存在: " + args[1]);
                return true;
            }
            AreaConfig ac = areas.get(areaName);
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }
            String newOwner = args[2];
            String oldOwner = ac.owner;

            // ★ 验证新所有者名格式
            if (!newOwner.matches("^[a-zA-Z0-9_]{3,16}$")) {
                sender.sendMessage("§c玩家名格式无效：仅允许英文字母、数字和下划线（3-16位）");
                return true;
            }
            if (newOwner.equalsIgnoreCase(oldOwner)) {
                sender.sendMessage("§c不能转让给自己");
                return true;
            }

            // ★ 验证新所有者是否存在（login.db）
            DatabaseManager dbMgr = plugin.getDb();
            if (dbMgr != null && !dbMgr.userExists(newOwner)) {
                sender.sendMessage("§c玩家 §e" + newOwner + " §c尚未注册");
                return true;
            }

            // ★ 验证玩家注册时间必须超过5分钟（防止注册秒退玩家接收领地）
            if (dbMgr != null) {
                long registerTime = 0;
                try {
                    Object regTimeObj = dbMgr.getField(newOwner, "register_time");
                    if (regTimeObj instanceof Number) {
                        registerTime = ((Number) regTimeObj).longValue();
                    }
                } catch (Exception e) {
                    // 忽略异常
                }
                if (registerTime > 0) {
                    long now = System.currentTimeMillis();
                    long fiveMinutesMs = 5 * 60 * 1000;
                    if (now - registerTime < fiveMinutesMs) {
                        long minutesLeft = (fiveMinutesMs - (now - registerTime)) / 60000;
                        sender.sendMessage("§c玩家 §e" + newOwner + " §c注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                        return true;
                    }
                }
            }

            // ★ 执行转让
            ac.owner = newOwner;
            saveAreaToDb(ac);

            // ★ 获取权限快照 + 追踪cooldown
            if (plugin.webManager != null) {
                String snapshot = getLandPermissionsSnapshot(areaName);
                long expiresAt = System.currentTimeMillis() + 60000; // 60秒cooldown
                WebManager.TransferInfo info = new WebManager.TransferInfo(
                    areaName, oldOwner, newOwner, expiresAt, 0, 0, snapshot
                );
                plugin.webManager.trackTransfer(areaName, info);
                // 通知PHP同步
                plugin.webManager.requestImmediateLandSync();
            }

            sender.sendMessage("§a已将 §e" + areaName + " §a从 §e" + (oldOwner != null ? oldOwner : "无") + " §a转让给 §e" + newOwner + "§a，§e60秒冷却期内可取消");
            return true;
        }

        // ===== canceltransfer 取消过户 =====
        if (sub.equals("canceltransfer")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect canceltransfer <领地>");
                return true;
            }
            String areaName = resolveAreaName(args[1]);
            if (areaName == null) {
                sender.sendMessage("§c领地不存在: " + args[1]);
                return true;
            }
            AreaConfig ac = areas.get(areaName);
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }

            // ★ 查找并取消进行中的过户
            if (plugin.webManager != null && plugin.webManager.cancelTransfer(areaName, p.getName())) {
                sender.sendMessage("§a过户已取消，领地 §e" + areaName + " §a已恢复");
            } else {
                sender.sendMessage("§c没有可取消的过户（可能已过期或不存在）");
            }
            return true;
        }

        // ===== settp 设置领地传送点 =====
        if (sub.equals("settp") || sub.equals("setwarp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = null;
            // ★ 支持指定领地名：/protect settp <领地名>
            if (args.length >= 2) {
                String targetName = args[1];
                String resolved = resolveAreaName(targetName);
                if (resolved != null) targetName = resolved;
                ac = areas.get(targetName);
                if (ac == null) {
                    sender.sendMessage("§c领地不存在: " + targetName);
                    return true;
                }
            } else {
                // 找到玩家所在的领地
                ac = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac == null) {
                    sender.sendMessage("§c你不在任何领地内，请指定领地名: /protect settp <领地名>");
                    return true;
                }
            }
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                sender.sendMessage("§c需要领地所有者或管理员权限");
                return true;
            }
            ac.warpX = p.getLocation().getX();
            ac.warpY = p.getLocation().getY();
            ac.warpZ = p.getLocation().getZ();
            ac.warpYaw = p.getLocation().getYaw();
            ac.warpPitch = p.getLocation().getPitch();
            ac.warpWorld = p.getWorld().getName();
            saveAreaToDb(ac);
            cancelPendingDelete(ac.name);
            sender.sendMessage("§a§l[防护] §f已将 §e" + ac.name + " §f的传送点设置为当前坐标");
            return true;
        }

        // ===== tp 传送到领地传送点 =====
        if (sub.equals("tp") || sub.equals("warp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = null;
            if (args.length >= 2) {
                String areaName = resolveAreaName(args[1]);
                if (areaName == null) {
                    sender.sendMessage("§c领地不存在: " + args[1]);
                    return true;
                }
                ac = areas.get(areaName);
            } else {
                // 传送到当前所在领地的传送点
                ac = getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac == null) {
                    sender.sendMessage("§c你不在任何领地内，请指定领地名: /protect tp <领地名>");
                    return true;
                }
            }
            if (ac == null) {
                sender.sendMessage("§c领地不存在");
                return true;
            }
            // 检查传送点是否已设置
            if (ac.warpWorld == null || ac.warpWorld.isEmpty()) {
                // 没有传送点，传送到领地中心
                int cx = (Math.min(ac.x1, ac.x2) + Math.max(ac.x1, ac.x2)) / 2;
                int cz = (Math.min(ac.z1, ac.z2) + Math.max(ac.z1, ac.z2)) / 2;
                World w = Bukkit.getWorld(ac.world);
                if (w == null) {
                    sender.sendMessage("§c世界不存在: " + ac.world);
                    return true;
                }
                // 找到安全位置：从最高向下找第一个上方有2格空气、下方有实体的方块
                Location safeLoc = null;
                for (int y = w.getMaxHeight(); y >= w.getMinHeight(); y--) {
                    Location check = new Location(w, cx, y, cz);
                    // 脚下必须有实体方块
                    if (!check.getBlock().getType().isSolid()) continue;
                    // 脚上和头顶至少2格空气（站立空间）
                    if (check.clone().add(0, 1, 0).getBlock().getType().isSolid()) continue;
                    if (check.clone().add(0, 2, 0).getBlock().getType().isSolid()) continue;
                    safeLoc = check.clone().add(0, 1, 0); // 站在方块上表面+1的位置
                    break;
                }
                if (safeLoc != null) {
                    p.teleport(safeLoc);
                    sender.sendMessage("§a§l[防护] §f已传送到 §e" + ac.name + " §f安全位置（未设置传送点）");
                } else {
                    sender.sendMessage("§c§l[防护] §f无法找到安全传送位置，请为该领地设置传送点 (/protect settp)");
                }
            } else {
                World w = Bukkit.getWorld(ac.warpWorld);
                if (w == null) {
                    sender.sendMessage("§c传送点所在世界不存在");
                    return true;
                }
                Location warp = new Location(w, ac.warpX, ac.warpY, ac.warpZ, ac.warpYaw, ac.warpPitch);
                p.teleport(warp);
                sender.sendMessage("§a§l[防护] §f已传送到 §e" + ac.name + " §f传送点");
            }
            return true;
        }

        showHelp(sender);
        return true;
    }

    /**
     * 如果领地有待删除请求，取消它（玩家操作领地时自动解除冻结）
     */
    // ★ package-private供AreaCLIManager访问
    void cancelPendingDelete(String areaName) {
        PendingDelete pd = pendingDeletes.remove(areaName);
        if (pd != null) {
            pd.task.cancel();
            Player p = Bukkit.getPlayerExact(pd.playerName);
            if (p != null && p.isOnline()) {
                p.sendMessage("§c§l[防护] §f领地 §e" + areaName + " §f已被操作，删除请求已自动取消");
            }
        }
    }


    private String[] parseAreaAndTarget(String arg1, String arg2) {
        String r1 = resolveAreaName(arg1);
        if (r1 != null) {
            return new String[]{r1, arg2};
        }
        String r2 = resolveAreaName(arg2);
        if (r2 != null) {
            return new String[]{r2, arg1};
        }
        return null;
    }

    private boolean playerExists(String playerName) {
        // 在线玩家
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(playerName)) return true;
        }
        // 离线玩家
        @SuppressWarnings("deprecation")
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        return op.hasPlayedBefore();
    }


    private boolean validateArea(CommandSender sender, String areaName) {
        if (!areas.containsKey(areaName)) {
            sender.sendMessage("§c区域不存在: " + areaName);
            return false;
        }
        return true;
    }


    private String matname(Material mat) {
        String name = mat.name();
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase())
                    .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
    private String yawToDir(float yaw) {
        // MC中: 0=南, 90=西, 180=北, -90/270=东
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        if (yaw >= 315 || yaw < 45) return "南";
        if (yaw >= 45 && yaw < 135) return "西";
        if (yaw >= 135 && yaw < 225) return "北";
        return "东";
    }
    private void saveAreaConfig(AreaConfig ac) {
        File f = new File(rootDir, ac.name + ".txt");
        if (!f.exists()) return;
        try {
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("起点:")) {
                    lines.add("起点: " + ac.world
                            + "," + ac.x1 + "," + ac.z1);
                } else if (line.trim().startsWith("终点:")) {
                    lines.add("终点: " + ac.world
                            + "," + ac.x2 + "," + ac.z2);
                } else {
                    lines.add(line);
                }
            }
            br.close();
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            for (String l : lines) pw.println(l);
            pw.close();
        } catch (IOException ignored) {}
    }

    /**
     * 保存领地配置到数据库（新方法）
     */
    public void saveAreaToDb(AreaConfig ac) {
        if (dbConnection == null) return;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO area_lands (name, owner, world, x1, z1, x2, z2, y_min, y_max, "
                    + "confiscate_items, deny_use_items, give_effects, clear_effects, clear_all_bad, "
                    + "punish_commands, deny_block_place, deny_block_break, deny_pvp, deny_fall_damage, "
                    + "deny_hunger, deny_all_damage, deny_drop, deny_mount, deny_ender_pearl, "
                    + "deny_bow, deny_potion, deny_explosion, deny_raid, deny_fire_spread, "
                    + "deny_all_effects, deny_item_frame, deny_move, deny_pickup, deny_fire, "
                    + "peace_mode, peace_mode_duration, "
                    + "peace_whitelist, enforce_game_mode, mode_exempt, enter_msg, leave_msg, "
                    + "confiscate_msg, enable_announce, announce_template, txt_content, created_at, "
                    + "deny_thrown_projectiles, deny_glowing, deny_redstone_interaction, deny_door_interaction, "
                    + "deny_noteblock_jukebox, deny_lead, deny_crop_harvest, deny_wool_shear, deny_animal_feeding, "
                    + "warp_x, warp_y, warp_z, warp_yaw, warp_pitch, warp_world, deny_container, deny_mob_attack, is_public_building) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            stmt.setString(1, ac.name);
            stmt.setString(2, ac.owner != null ? ac.owner : "");
            stmt.setString(3, ac.world);
            stmt.setInt(4, ac.x1);
            stmt.setInt(5, ac.z1);
            stmt.setInt(6, ac.x2);
            stmt.setInt(7, ac.z2);
            stmt.setInt(8, ac.yMin);
            stmt.setInt(9, ac.yMax);
            stmt.setString(10, String.join(",", ac.confiscateItems));
            stmt.setString(11, String.join(",", ac.denyUseItems));
            stmt.setString(12, effectsToString(ac.giveEffects));
            stmt.setString(13, String.join(",", ac.clearEffects));
            stmt.setInt(14, ac.clearAllBadEffects ? 1 : 0);
            stmt.setString(15, String.join("|", ac.punishCommands));
            stmt.setInt(16, ac.denyBlockPlace ? 1 : 0);
            stmt.setInt(17, ac.denyBlockBreak ? 1 : 0);
            stmt.setInt(18, ac.denyPVP ? 1 : 0);
            stmt.setInt(19, ac.denyFallDamage ? 1 : 0);
            stmt.setInt(20, ac.denyHunger ? 1 : 0);
            stmt.setInt(21, ac.denyAllDamage ? 1 : 0);
            stmt.setInt(22, ac.allowDrop ? 1 : 0);
            stmt.setInt(23, ac.denyMount ? 1 : 0);
            stmt.setInt(24, ac.denyEnderPearl ? 1 : 0);
            stmt.setInt(25, ac.denyBow ? 1 : 0);
            stmt.setInt(26, ac.denyPotion ? 1 : 0);
            stmt.setInt(27, ac.denyExplosion ? 1 : 0);
            stmt.setInt(28, ac.denyRaid ? 1 : 0);
            stmt.setInt(29, ac.denyFireSpread ? 1 : 0);
            stmt.setInt(30, ac.denyAllEffects ? 1 : 0);
            stmt.setInt(31, ac.denyItemFrame ? 1 : 0);
            stmt.setInt(32, ac.denyMove ? 1 : 0);
            stmt.setInt(33, ac.allowPickup ? 1 : 0);
            stmt.setInt(34, ac.denyFire ? 1 : 0);
            stmt.setInt(35, ac.peaceMode ? 1 : 0);
            stmt.setInt(36, ac.peaceModeDuration / 1000);
            stmt.setString(37, String.join(",", ac.peaceWhitelist));
            stmt.setString(38, ac.enforceGameMode != null ? ac.enforceGameMode : "");
            stmt.setString(39, String.join(",", ac.modeExempt));
            stmt.setString(40, ac.enterMsg);
            stmt.setString(41, ac.leaveMsg);
            stmt.setString(42, ac.confiscateMsg);
            stmt.setInt(43, ac.enableAnnounce ? 1 : 0);
            stmt.setString(44, ac.announceTemplate);
            stmt.setString(45, ac.txtContent != null ? ac.txtContent : "");
            stmt.setLong(46, System.currentTimeMillis() / 1000);
            // ★ 新增权限列
            stmt.setInt(47, ac.denyThrownProjectiles ? 1 : 0);
            stmt.setInt(48, ac.denyGlowing ? 1 : 0);
            stmt.setInt(49, ac.denyRedstoneInteraction ? 1 : 0);
            stmt.setInt(50, ac.denyDoorInteraction ? 1 : 0);
            stmt.setInt(51, ac.denyNoteblockJukebox ? 1 : 0);
            stmt.setInt(52, ac.denyLead ? 1 : 0);
            stmt.setInt(53, ac.denyCropHarvest ? 1 : 0);
            stmt.setInt(54, ac.denyWoolShear ? 1 : 0);
            stmt.setInt(55, ac.denyAnimalFeeding ? 1 : 0);
            // ★ 传送点列
            stmt.setDouble(56, ac.warpX);
            stmt.setDouble(57, ac.warpY);
            stmt.setDouble(58, ac.warpZ);
            stmt.setFloat(59, ac.warpYaw);
            stmt.setFloat(60, ac.warpPitch);
            stmt.setString(61, ac.warpWorld != null ? ac.warpWorld : "");
            stmt.setInt(62, ac.denyContainer ? 1 : 0);
            stmt.setInt(63, ac.denyMobAttack ? 1 : 0);
            stmt.setInt(64, ac.isPublicBuilding ? 1 : 0);
            stmt.executeUpdate();
            stmt.close();
            // ★ 领地设置变更：立即触发PHP同步（防抖10秒）
            try {
                if (plugin.webManager != null) {
                    plugin.webManager.requestImmediateLandSync();
                }
            } catch (Exception ignored) {}
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 保存到DB失败: " + ac.name + " - " + e.getMessage());
        }
    }

    /**
     * 从数据库删除领地
     */
    public void deleteAreaFromDb(String name) {
        if (dbConnection == null) return;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement("DELETE FROM area_lands WHERE name = ?");
            stmt.setString(1, name);
            stmt.executeUpdate();
            stmt.close();
            // ★ 领地删除：立即触发PHP同步
            try {
                if (plugin.webManager != null) {
                    plugin.webManager.requestImmediateLandSync();
                }
            } catch (Exception ignored) {}
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 从DB删除领地失败: " + name + " - " + e.getMessage());
        }
    }

    /**
     * 获取所有领地数据（供WebManager同步到PHP）
     */
    public List<Map<String, Object>> getAllLandsForSync() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (dbConnection == null) return result;
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM area_lands");
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                for (int i = 1; i <= cols; i++) {
                    String colName = rs.getMetaData().getColumnName(i);
                    map.put(colName, rs.getObject(colName));
                }
                result.add(map);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 获取领地数据失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 获取权限商店数据（供WebManager同步到PHP）
     */
    public List<Map<String, Object>> getPermissionShopForSync() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (dbConnection == null) return result;
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "SELECT s.id, s.land_id, l.name AS land_name, s.seller, s.permission, s.price, s.duration, s.status, s.buyer, s.bought_at, s.created_at "
                            + "FROM area_permission_shop s "
                            + "LEFT JOIN area_lands l ON s.land_id = l.id");
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", rs.getInt("id"));
                map.put("land_id", rs.getInt("land_id"));
                map.put("land_name", rs.getString("land_name"));
                map.put("seller", rs.getString("seller"));
                map.put("permission", rs.getString("permission"));
                map.put("price", rs.getInt("price"));
                map.put("duration", rs.getInt("duration"));
                map.put("status", rs.getString("status"));
                map.put("buyer", rs.getString("buyer"));
                map.put("bought_at", rs.getLong("bought_at"));
                map.put("created_at", rs.getLong("created_at"));
                result.add(map);
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 获取权限商店数据失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 从数据库获取领地ID
     */
    public int getLandIdFromDb(String name) {
        if (dbConnection == null) return -1;
        try {
            PreparedStatement stmt = dbConnection.prepareStatement("SELECT id FROM area_lands WHERE name = ?");
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            int id = rs.next() ? rs.getInt("id") : -1;
            rs.close();
            stmt.close();
            return id;
        } catch (SQLException e) {
            return -1;
        }
    }

    /**
     * 获取领地权限快照（用于过户cooldown期间检测权限变更）
     * 返回JSON字符串，包含owner、members、visitors等关键字段
     */
    public String getLandPermissionsSnapshot(String landName) {
        AreaConfig ac = areas.get(landName);
        if (ac == null) return null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"owner\":\"").append(ac.owner != null ? ac.owner : "").append("\"");
            // 成员列表（areaPlayerWhitelist）
            sb.append(",\"members\":[");
            Set<String> members = areaPlayerWhitelist.get(landName);
            if (members != null && !members.isEmpty()) {
                java.util.List<String> sorted = new java.util.ArrayList<>(members);
                java.util.Collections.sort(sorted);
                boolean first = true;
                for (String m : sorted) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(m).append("\"");
                    first = false;
                }
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * PHP端修改领地所有者时调用
     */
    public void setLandOwnerFromWeb(String landName, String newOwner) {
        if (dbConnection == null) return;
        try {
            // ★ 幂等检查：如果所有者已相同则跳过，避免ack失败后重复打印
            AreaConfig ac = getLand(landName);
            if (ac != null && newOwner.equalsIgnoreCase(ac.owner)) {
                return;
            }

            // 更新数据库
            PreparedStatement ps = dbConnection.prepareStatement("UPDATE area_lands SET owner = ? WHERE name = ?");
            ps.setString(1, newOwner);
            ps.setString(2, landName);
            ps.executeUpdate();
            ps.close();

            // 更新内存中的AreaConfig
            if (ac != null) {
                ac.owner = newOwner;
            }

            plugin.getLogger().info("[防护] PHP端更新领地所有者: " + landName + " → " + newOwner);
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 更新领地所有者失败: " + e.getMessage());
        }
    }

    /**
     * PHP端清除成员权限时调用
     */
    public void clearPlayerPermFromWeb(String landName, String playerName) {
        if (dbConnection == null) return;
        try {
            int landId = getLandIdFromDb(landName);
            if (landId < 0) return;

            // 清除数据库中的自定义权限
            PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE area_land_permissions SET permissions = '' WHERE land_id = ? AND player_name = ?");
            ps.setInt(1, landId);
            ps.setString(2, playerName);
            ps.executeUpdate();
            ps.close();

            plugin.getLogger().info("[防护] PHP端清除成员权限: " + playerName + " @ " + landName);
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 清除成员权限失败: " + e.getMessage());
        }
    }

    /**
     * ★ PHP端修改领地字段时调用（效果管理等）
     * field: give_effects, clear_effects, clear_all_bad, deny_all_effects等
     * value: 新值（已转为Java格式）
     */
    public void updateLandFieldFromWeb(String landName, String field, String value) {
        if (dbConnection == null) return;
        try {
            // 白名单校验字段名（防SQL注入）
            java.util.Set<String> allowedFields = new java.util.HashSet<>(java.util.Arrays.asList(
                "give_effects", "clear_effects", "clear_all_bad", "deny_all_effects",
                "deny_block_place", "deny_block_break", "deny_pvp", "deny_fall_damage",
                "deny_hunger", "deny_all_damage", "deny_drop", "deny_mount", "deny_ender_pearl",
                "deny_bow", "deny_potion", "deny_explosion", "deny_raid", "deny_fire_spread",
                "deny_all_effects", "deny_item_frame", "deny_move", "deny_pickup", "deny_fire",
                "confiscate_items", "deny_use_items", "punish_commands",
                "peace_mode", "peace_mode_duration", "enforce_game_mode",
                "enter_msg", "leave_msg", "confiscate_msg",
                "deny_thrown_projectiles", "deny_glowing", "deny_redstone_interaction",
                "deny_door_interaction", "deny_noteblock_jukebox", "deny_lead",
                "deny_crop_harvest", "deny_wool_shear", "deny_animal_feeding",
                "deny_container", "deny_mob_attack", "deny_fluid"
            ));
            if (!allowedFields.contains(field)) {
                plugin.getLogger().warning("[防护] PHP端更新未知字段: " + field);
                return;
            }

            PreparedStatement ps = dbConnection.prepareStatement(
                "UPDATE area_lands SET " + field + " = ? WHERE name = ?");
            ps.setString(1, value);
            ps.setString(2, landName);
            int affected = ps.executeUpdate();
            ps.close();

            if (affected > 0) {
                // 更新内存缓存
                AreaConfig ac = getLand(landName);
                if (ac != null) {
                    reloadAreaConfigFromDb(); // 简单起见重新加载
                }
                plugin.getLogger().info("[防护] PHP端更新领地字段: " + landName + "." + field + " = " + value);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] PHP端更新领地字段失败: " + e.getMessage());
        }
    }

    /**
     * PHP端更新访客/成员权限时调用
     * landName 可能是JSON数组格式 "[领地名]" 或纯领地名
     */
    public void updateVisitorPermFromWeb(String landName, String playerName, String permsJson) {
        if (dbConnection == null) return;
        try {
            // 解析可能的JSON数组格式
            String[] landNames = landName.startsWith("[") ? parseJsonArray(landName) : new String[]{landName};
            
            for (String lname : landNames) {
                int landId = getLandIdFromDb(lname);
                if (landId < 0) continue;

                // 更新数据库中的自定义权限
                PreparedStatement ps = dbConnection.prepareStatement(
                    "INSERT INTO area_land_permissions (land_id, land_name, player_name, role, permissions, granted_at, synced_at) " +
                    "VALUES (?, ?, ?, 'member', ?, 0, 0) " +
                    "ON CONFLICT(land_id, player_name) DO UPDATE SET permissions = excluded.permissions");
                ps.setInt(1, landId);
                ps.setString(2, lname);
                ps.setString(3, playerName);
                ps.setString(4, permsJson);
                ps.executeUpdate();
                ps.close();
            }

            plugin.getLogger().info("[防护] PHP端更新权限: " + playerName + " @ " + Arrays.toString(landNames));
        } catch (SQLException e) {
            plugin.getLogger().warning("[防护] 更新权限失败: " + e.getMessage());
        }
    }

    /**
     * 解析JSON数组格式的领地名字符串
     */
    private String[] parseJsonArray(String json) {
        try {
            String inner = json.replaceAll("^\\[|\\]$", "");
            String[] parts = inner.split("\"");
            java.util.List<String> result = new java.util.ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim().replaceAll("\u00A7[0-9a-fk-or]", "");
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
            return result.toArray(new String[0]);
        } catch (Exception e) {
            return new String[]{json};
        }
    }

    /**
     * 格式化时长（秒 → 可读字符串）
     */
    private String formatDuration(int seconds) {
        if (seconds >= 86400) return (seconds / 86400) + "天";
        if (seconds >= 3600) return (seconds / 3600) + "小时";
        if (seconds >= 60) return (seconds / 60) + "分钟";
        return seconds + "秒";
    }

    /**
     * 更新area_config表中的配置值
     */
    private void updateAreaConfig(String key, String value) {
        if (dbConnection == null) return;
        // ★ 去除前导零：01 → 1，防止静默失败
        try { value = String.valueOf(Integer.parseInt(value.trim())); } catch (Exception ignored) {}
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "INSERT INTO area_config (key, value) VALUES (?, ?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = ?");
            ps.setString(1, key);
            ps.setString(2, value);
            ps.setString(3, value);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 更新配置失败: " + e.getMessage());
        }
    }

    /**
     * ★ 获取area_config表中的配置值
     */
    public String getAreaConfigValue(String key) {
        if (dbConnection == null) return null;
        try {
            PreparedStatement ps = dbConnection.prepareStatement("SELECT value FROM area_config WHERE key = ?");
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String val = rs.getString("value");
                rs.close();
                ps.close();
                return val;
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 读取配置失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * ★ 设置area_config表中的配置值（公开方法）
     */
    public void setAreaConfigValue(String key, String value) {
        updateAreaConfig(key, value);
    }

    /**
     * ★ 获取全部area_config配置（用于同步到PHP）
     */
    public Map<String, String> getAllAreaConfigForSync() {
        Map<String, String> config = new LinkedHashMap<>();
        if (dbConnection == null) return config;
        try {
            ResultSet rs = dbConnection.createStatement().executeQuery("SELECT key, value FROM area_config");
            while (rs.next()) {
                config.put(rs.getString("key"), rs.getString("value"));
            }
            rs.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 读取配置同步数据失败: " + e.getMessage());
        }
        return config;
    }

    // ==================== 管理员配置输入（GUI→聊天栏） ====================

    /**
     * ★ 设置等待配置输入状态
     */
    public void setPendingConfigInput(java.util.UUID uuid, String configKey) {
        pendingConfigInput.put(uuid, configKey);
    }

    /**
     * ★ 检查并处理配置输入（在onPlayerChat中调用）
     * @return true 如果是配置输入，已处理
     */
    public boolean handleConfigInput(Player p, String message) {
        String configKey = pendingConfigInput.remove(p.getUniqueId());
        if (configKey == null) return false;

        // ★ 智能数字解析：支持纯数字、中文数字、中文大写、罗马数字、英文数字
        Integer newValue = parseSmartNumber(message.trim());
        if (newValue == null) {
            p.sendMessage("§c§l[配置] §f无法识别输入的数字: " + message);
            p.sendMessage("§7支持: 纯数字123、中文一二三、大写壹佰贰拾叁、罗马I II III、英文one two three");
            return true;
        }

        // 读取当前值
        int currentValue = 0;
        try {
            String v = getAreaConfigValue(configKey);
            if (v != null) currentValue = Integer.parseInt(v);
        } catch (Exception ignored) {}

        // 应用新值（如果比原来大=加钱，比原来小=减钱，直接使用绝对值）
        setAreaConfigValue(configKey, String.valueOf(newValue));

        // 更新内存变量
        switch (configKey) {
            case "create_price_per_sqm": globalCreatePricePerSqm = newValue; break;
            case "max_lands_per_player": globalMaxLandsPerPlayer = newValue; break;
            case "default_height": globalDefaultHeight = newValue; break;
        }

        String configName = getConfigNameByKey(configKey);
        p.sendMessage("§a§l[配置] §f" + configName + " 已更新: §e" + currentValue + " → " + newValue);

        // 刷新GUI（必须切主线程：handleConfigInput来自AsyncPlayerChatEvent）
        if (plugin.areaGUIManager != null) {
            Sdf1_login.Main pl = plugin;
            org.bukkit.entity.Player pp = p;
            org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.areaGUIManager.openAdminPanel(pp));
        }
        return true;
    }

    /**
     * 设置等待添加成员输入状态
     */
    public void setPendingAddMemberInput(java.util.UUID uuid, String landName) {
        pendingAddMemberInput.put(uuid, landName);
    }

    /**
     * 检查并处理添加成员输入
     * @return true 如果是添加成员输入，已处理
     */
    public boolean handleAddMemberInput(Player p, String message) {
        String landName = pendingAddMemberInput.remove(p.getUniqueId());
        if (landName == null) return false;

        String playerName = message.trim();
        // 验证玩家名格式
        if (!playerName.matches("^[a-zA-Z0-9_]{3,16}$")) {
            p.sendMessage("§c§l[添加成员] §f无效的玩家名格式（3-16位字母数字下划线）: " + playerName);
            p.sendMessage("§7请重新输入有效玩家名，或输入 cancel 取消");
            return true;
        }

        // 检查玩家是否已存在（本地）
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            // 玩家不在线，但仍然可以添加（因为可能在数据库中有记录）
            p.sendMessage("§e§l[添加成员] §f玩家 " + playerName + " 当前不在线，但仍可添加为成员");
        }

        // 检查是否已是成员
        Set<String> members = getLandMembers(landName);
        if (members.contains(playerName)) {
            p.sendMessage("§c§l[添加成员] §f" + playerName + " 已经是该领地成员");
            return true;
        }

        // 检查是否是领地主
        AreaConfig ac = getLand(landName);
        if (ac != null && ac.owner != null && ac.owner.equalsIgnoreCase(playerName)) {
            p.sendMessage("§c§l[添加成员] §f领地主不能作为成员添加");
            return true;
        }

        // 添加成员
        addLandMember(landName, playerName);
        p.sendMessage("§a§l[添加成员] §f已成功添加 " + playerName + " 为领地成员");

        // 刷新GUI（必须切主线程）
        if (plugin.areaGUIManager != null) {
            Sdf1_login.Main pl = plugin;
            org.bukkit.entity.Player pp = p;
            String ln = landName;
            org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.areaGUIManager.openAddMember(pp, ln));
        }
        return true;
    }

    /**
     * 设置等待效果管理输入状态
     * @param subPage 子页面索引（1=开关, 2=单清, 3=增益）
     */
    public void setPendingClearEffectInput(java.util.UUID uuid, String landName, String inputType, int subPage) {
        pendingEffectInput.put(uuid, new String[]{landName, inputType, String.valueOf(subPage)});
    }

    /**
     * ★ 通用设置效果输入状态
     */
    public void setPendingEffectInput(java.util.UUID uuid, String landName, String inputType, int subPage) {
        pendingEffectInput.put(uuid, new String[]{landName, inputType, String.valueOf(subPage)});
    }

    /**
     * 检查并处理效果管理输入
     * @return true 如果是效果管理输入，已处理
     */
    public boolean handleEffectInput(Player p, String message) {
        String[] landAndType = pendingEffectInput.remove(p.getUniqueId());
        if (landAndType == null) return false;

        String landName = landAndType[0];
        String inputType = landAndType[1];
        AreaProtection.AreaConfig land = getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return true;
        }

        // 获取subPage（如果有）
        int subPage = 1;
        if (landAndType.length >= 3) {
            try {
                subPage = Integer.parseInt(landAndType[2]);
            } catch (NumberFormatException ignored) {
            }
        }

        if ("clear".equals(inputType)) {
            // 添加单清效果
            String effName = message.trim();
            if (effName.isEmpty()) {
                p.sendMessage("§c§l[添加清除效果] §f效果名不能为空");
                return true;
            }
            // ★ 验证效果名是否有效
            PotionEffectType resolved = resolveEffectType(effName);
            if (resolved == null) {
                p.sendMessage("§c§l[添加清除效果] §f无效的效果名: §e" + effName);
                p.sendMessage("§7支持的中文名: 缓慢、挖掘疲劳、瞬间伤害、反胃、失明、饥饿、虚弱、中毒、凋零、飘浮、霉运、黑暗、蓄风、盘丝、渗浆、寄生");
                p.sendMessage("§7中性: 不祥之兆、袭击之兆、试炼之兆");
                p.sendMessage("§7正面: 迅捷、急迫、力量、瞬间治疗、跳跃提升、生命恢复、抗性提升、抗火、水下呼吸、隐身、夜视、发光、生命提升、伤害吸收、饱和、幸运、村庄英雄、缓降、潮涌能量、海豚的恩惠");
                return true;
            }
            if (!land.clearEffects.contains(effName)) {
                land.clearEffects.add(effName);
                saveAreaToDb(land);
                p.sendMessage("§a§l[添加清除效果] §f已添加: §e" + effName);
            } else {
                p.sendMessage("§e§l[添加清除效果] §f效果 §e" + effName + " §f已在列表中");
            }
        } else if ("give".equals(inputType)) {
            // 添加增益效果: 格式 效果名 [等级] [秒数]
            String[] parts = message.trim().split("\\s+");
            if (parts.length < 1) {
                p.sendMessage("§c§l[添加增益效果] §f请输入: 效果名 [等级] [秒数]");
                return true;
            }
            String effName = parts[0].trim();
            int effLv = 1;
            int effDur = 300; // 默认5分钟
            try {
                if (parts.length >= 2) effLv = Integer.parseInt(parts[1]);
                if (parts.length >= 3) effDur = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ignored) {
                p.sendMessage("§c§l[添加增益效果] §f等级和秒数必须是数字");
                return true;
            }
            // ★ 验证等级和秒数范围
            if (effLv < 1 || effLv > 255) {
                p.sendMessage("§c§l[添加增益效果] §f等级范围: 1~255");
                return true;
            }
            if (effDur < 1 || effDur > 3600) {
                p.sendMessage("§c§l[添加增益效果] §f持续时间范围: 1~3600秒");
                return true;
            }
            // ★ 验证效果名是否有效
            PotionEffectType resolved = resolveEffectType(effName);
            if (resolved == null) {
                p.sendMessage("§c§l[添加增益效果] §f无效的效果名: §e" + effName);
                p.sendMessage("§7推荐增益: 迅捷、急迫、力量、瞬间治疗、跳跃提升、生命恢复、抗性提升、抗火、水下呼吸、隐身、夜视、发光、生命提升、伤害吸收、饱和、幸运、村庄英雄、缓降、潮涌能量、海豚的恩惠");
                return true;
            }
            // ★ 检查是否已存在同名增益
            for (String[] existing : land.giveEffects) {
                if (existing[0].equals(effName)) {
                    p.sendMessage("§e§l[添加增益效果] §f增益 §e" + effName + " §f已存在，先移除旧的再添加");
                    return true;
                }
            }
            String[] effRecord = {effName, String.valueOf(effLv), String.valueOf(effDur)};
            land.giveEffects.add(effRecord);
            saveAreaToDb(land);
            p.sendMessage("§a§l[添加增益效果] §f已添加: §a" + effName + " §fLv" + effLv + " " + effDur + "秒");
        } else if (inputType.startsWith("editGive_")) {
            // ★ 编辑增益效果等级/时长: editGive_level_3 或 editGive_duration_3
            String[] parts = inputType.split("_");
            if (parts.length < 3) return true;
            String editField = parts[1]; // level 或 duration
            int idx;
            try { idx = Integer.parseInt(parts[2]); } catch (Exception e) { return true; }
            if (idx < 1 || idx > land.giveEffects.size()) {
                p.sendMessage("§c序号超出范围");
                return true;
            }
            if (message.trim().equalsIgnoreCase("取消")) {
                p.sendMessage("§7已取消编辑");
                if (plugin.areaCLIManager != null) plugin.areaCLIManager.showEffectsManagement(p, landName, 3);
                return true;
            }
            String[] eff = land.giveEffects.get(idx - 1);
            if ("level".equals(editField)) {
                int newLevel;
                try { newLevel = Integer.parseInt(message.trim()); } catch (Exception e) {
                    p.sendMessage("§c请输入有效数字");
                    return true;
                }
                if (newLevel < 1 || newLevel > 255) { p.sendMessage("§c等级范围1~255"); return true; }
                if (eff.length < 2) { String[] tmp = new String[3]; System.arraycopy(eff, 0, tmp, 0, eff.length); eff = tmp; land.giveEffects.set(idx - 1, eff); }
                eff[1] = String.valueOf(newLevel);
                p.sendMessage("§a§l[编辑增益] §f已将 §e" + eff[0] + " §f等级修改为 " + newLevel);
            } else {
                int newDur;
                try { newDur = Integer.parseInt(message.trim()); } catch (Exception e) {
                    p.sendMessage("§c请输入有效数字");
                    return true;
                }
                if (newDur < 1 || newDur > 86400) { p.sendMessage("§c时长范围1~86400秒"); return true; }
                if (eff.length < 3) { String[] tmp = new String[3]; System.arraycopy(eff, 0, tmp, 0, Math.min(eff.length, 3)); eff = tmp; land.giveEffects.set(idx - 1, eff); }
                eff[2] = String.valueOf(newDur);
                p.sendMessage("§a§l[编辑增益] §f已将 §e" + eff[0] + " §f时长修改为 " + newDur + "秒");
            }
            saveAreaToDb(land);
            if (plugin.areaCLIManager != null) plugin.areaCLIManager.showEffectsManagement(p, landName, 3);
        }

        // 刷新GUI，回到原来的subPage（必须切主线程）
        if (plugin.areaGUIManager != null) {
            Sdf1_login.Main pl = plugin;
            org.bukkit.entity.Player pp = p;
            String ln = landName;
            int sp = subPage;
            org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.areaGUIManager.openEffectsManagement(pp, ln, sp));
        }
        return true;
    }

    /**
     * ★ 检查并处理过户领地输入（新所有者名）
     * @return true 如果是过户输入，已处理
     */
    public boolean handleTransferInput(Player p, String message) {
        String[] data = pendingTransferInput.remove(p.getUniqueId());
        if (data == null) return false;

        String areaName = data[0];
        if (message.trim().equalsIgnoreCase("取消")) {
            p.sendMessage("§7已取消过户");
            return true;
        }

        String newOwner = message.trim();
        // 验证格式
        if (!newOwner.matches("^[a-zA-Z0-9_]{3,16}$")) {
            p.sendMessage("§c玩家名格式无效：仅允许英文字母、数字和下划线（3-16位）");
            p.sendMessage("§7请重新输入（或输入 §c取消§7）");
            pendingTransferInput.put(p.getUniqueId(), data); // 恢复等待状态
            return true;
        }

        // 验证领地存在
        AreaConfig ac = areas.get(areaName);
        if (ac == null) {
            p.sendMessage("§c领地不存在: " + areaName);
            return true;
        }
        if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
            p.sendMessage("§c需要领地所有者或管理员权限");
            return true;
        }
        if (newOwner.equalsIgnoreCase(ac.owner)) {
            p.sendMessage("§c不能转让给自己");
            return true;
        }

        // 验证新所有者是否存在
        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr != null && !dbMgr.userExists(newOwner)) {
            p.sendMessage("§c玩家 §e" + newOwner + " §c尚未注册");
            return true;
        }

        // ★ 验证玩家注册时间必须超过5分钟（防止注册秒退玩家接收领地）
        if (dbMgr != null) {
            long registerTime = 0;
            try {
                Object regTimeObj = dbMgr.getField(newOwner, "register_time");
                if (regTimeObj instanceof Number) {
                    registerTime = ((Number) regTimeObj).longValue();
                }
            } catch (Exception e) {
                // 忽略异常
            }
            if (registerTime > 0) {
                long now = System.currentTimeMillis();
                long fiveMinutesMs = 5 * 60 * 1000;
                if (now - registerTime < fiveMinutesMs) {
                    long minutesLeft = (fiveMinutesMs - (now - registerTime)) / 60000;
                    p.sendMessage("§c玩家 §e" + newOwner + " §c注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                    return true;
                }
            }
        }

        // 执行转让
        String oldOwner = ac.owner;
        ac.owner = newOwner;
        saveAreaToDb(ac);

        // 获取权限快照 + 追踪cooldown
        if (plugin.webManager != null) {
            String snapshot = getLandPermissionsSnapshot(areaName);
            long expiresAt = System.currentTimeMillis() + 60000;
            WebManager.TransferInfo info = new WebManager.TransferInfo(
                areaName, oldOwner, newOwner, expiresAt, 0, 0, snapshot
            );
            plugin.webManager.trackTransfer(areaName, info);
            plugin.webManager.requestImmediateLandSync();
        }

        p.sendMessage("§a已将 §e" + areaName + " §a从 §e" + (oldOwner != null ? oldOwner : "无") + " §a转让给 §e" + newOwner + "§a，§e60秒冷却期内可取消");
        // ★ 可点击取消超链接
        if (p instanceof Player) {
            Player pp = (Player) p;
            pp.sendMessage(Component.empty()
                    .append(Component.text("§c§l[点击取消过户] "))
                    .hoverEvent(HoverEvent.showText(Component.text("§c点击立即取消领地 §e" + areaName + " §c的过户")))
                    .clickEvent(ClickEvent.runCommand("/protect canceltransfer " + areaName))
            );
        }
        return true;
    }

    /**
     * ★ 检查并处理用户组配置编辑输入
     * @return true 如果是用户组编辑输入，已处理
     */
    public boolean handleGroupEditInput(Player p, String message) {
        String[] data = pendingGroupEditInput.remove(p.getUniqueId());
        if (data == null) return false;

        String groupName = data[0];
        String field = data[1]; // "price", "maxlands", "priority"

        if (message.trim().equalsIgnoreCase("取消")) {
            p.sendMessage("§7已取消编辑");
            if (plugin.areaGUIManager != null) {
                Sdf1_login.Main pl = plugin;
                org.bukkit.entity.Player pp = p;
                String gn = groupName;
                org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.areaGUIManager.openGroupEditPanel(pp, gn));
            }
            return true;
        }

        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm == null) {
            p.sendMessage("§c用户组系统未初始化");
            return true;
        }
        UserGroupManager.UserGroupConfig cfg = ugm.getGroupConfig(groupName);
        if (cfg == null) {
            p.sendMessage("§c用户组不存在: " + groupName);
            return true;
        }

        // ★ 智能数字解析：支持中文、罗马、英文数字
        Integer parsed = parseSmartNumberBase(message.trim());
        if (parsed == null) {
            p.sendMessage("§c无法解析数字: " + message.trim());
            p.sendMessage("§7支持: 纯数字123、中文一二三、大写壹佰贰拾叁、罗马I II III、英文one two three");
            p.sendMessage("§7或输入 §c取消§7 放弃编辑");
            pendingGroupEditInput.put(p.getUniqueId(), data); // 恢复等待
            return true;
        }

        String fieldLabel;
        switch (field) {
            case "price":
                if (parsed < 0 || parsed > 999999) {
                    p.sendMessage("§c价格范围: 0~999999（0=使用全局默认）");
                    pendingGroupEditInput.put(p.getUniqueId(), data);
                    return true;
                }
                cfg.landPricePerSqm = parsed;
                fieldLabel = "每㎡价格";
                break;
            case "maxlands":
                if (parsed < 0 || parsed > 999) {
                    p.sendMessage("§c最大领地数范围: 0~999（0=使用全局默认）");
                    pendingGroupEditInput.put(p.getUniqueId(), data);
                    return true;
                }
                cfg.maxLands = parsed;
                fieldLabel = "最大领地数";
                break;
            case "priority":
                if (parsed < 0 || parsed > 100) {
                    p.sendMessage("§c优先级范围: 0~100");
                    pendingGroupEditInput.put(p.getUniqueId(), data);
                    return true;
                }
                cfg.priority = parsed;
                fieldLabel = "优先级";
                break;
            default:
                return true;
        }

        // 保存
        ugm.updateGroupConfig(cfg);
        p.sendMessage("§a已将用户组 §e" + cfg.displayName + " §a的 §f" + fieldLabel + " §a修改为 §e" + parsed);

        // 刷新GUI（必须切主线程）
        if (plugin.areaGUIManager != null) {
            Sdf1_login.Main pl = plugin;
            org.bukkit.entity.Player pp = p;
            String gn = groupName;
            org.bukkit.Bukkit.getScheduler().runTask(pl, () -> pl.areaGUIManager.openGroupEditPanel(pp, gn));
        }
        return true;
    }

    private String getConfigNameByKey(String key) {
        switch (key) {
            case "create_price_per_sqm": return "创建价格(每㎡)";
            case "max_lands_per_player": return "每人最大领地数";
            case "default_height": return "默认高度";
            case "peace_mode_max_duration": return "和平模式最大时长(秒)";
            default: return key;
        }
    }

    /**
     * ★ 智能数字解析：支持多种格式
     * 纯数字、中文数字、中文大写、罗马数字、英文数字
     */
    public static Integer parseSmartNumber(String input) {
        if (input == null || input.isEmpty()) return null;
        input = input.trim().toLowerCase();

        // ★ 支持 +/- 前缀（相对增减）
        if (input.startsWith("+") || input.startsWith("-")) {
            // +/- 前缀需要有后续数字
            String numPart = input.substring(1).trim();
            if (numPart.isEmpty()) return null;
            Integer delta = parseSmartNumberBase(numPart);
            if (delta == null) return null;
            return input.startsWith("-") ? -delta : delta;
        }

        return parseSmartNumberBase(input);
    }

    /**
     * ★ 智能数字解析（带默认值和下限）
     * @param input 用户输入
     * @param current 当前值（用于 +/- 计算）
     * @param floor 最小值（封底）
     */
    public static int parseSmartNumber(String input, int current, int floor) {
        if (input == null || input.isEmpty()) return current;
        input = input.trim().toLowerCase();

        // ★ 支持 +/- 前缀（相对增减）
        if (input.startsWith("+") || input.startsWith("-")) {
            String numPart = input.substring(1).trim();
            if (numPart.isEmpty()) return current;
            Integer delta = parseSmartNumberBase(numPart);
            if (delta == null) return current;
            int result = input.startsWith("-") ? current - delta : current + delta;
            return Math.max(result, floor);
        }

        // 纯数字（绝对值）
        Integer abs = parseSmartNumberBase(input);
        if (abs == null) return current;
        return Math.max(abs, floor);
    }

    public static Integer parseSmartNumberBase(String input) {
        if (input == null || input.isEmpty()) return null;
        input = input.trim().toLowerCase();

        // 1. 纯数字
        try {
            return Integer.parseInt(input);
        } catch (Exception ignored) {}

        // 2. 中文数字（小写）
        Integer result = parseChineseNumber(input);
        if (result != null) return result;

        // 3. 罗马数字
        result = parseRomanNumber(input);
        if (result != null) return result;

        // 4. 英文数字单词
        result = parseEnglishNumber(input);
        if (result != null) return result;

        return null;
    }

    /**
     * 解析中文数字（一~九十九万九千九百九十九）
     */
    private static Integer parseChineseNumber(String input) {
        if (input.isEmpty()) return null;

        // 中文大写数字映射
        String[][] chineseMap = {
            {"零", "0"}, {"一", "1"}, {"二", "2"}, {"三", "3"}, {"四", "4"},
            {"五", "5"}, {"六", "6"}, {"七", "7"}, {"八", "8"}, {"九", "9"},
            {"壹", "1"}, {"贰", "2"}, {"叁", "3"}, {"肆", "4"}, {"伍", "5"},
            {"陆", "6"}, {"柒", "7"}, {"捌", "8"}, {"玖", "9"},
            {"十", "10"}, {"百", "100"}, {"千", "1000"}, {"万", "10000"},
            {"拾", "10"}, {"佰", "100"}, {"仟", "1000"}
        };

        // 替换中文大写为小写
        String normalized = input;
        for (String[] pair : chineseMap) {
            normalized = normalized.replace(pair[0], pair[1]);
        }

        // 检查是否包含数字
        if (!normalized.matches("[0-9零一二三四五六七八九十百千万]+")) {
            return null;
        }

        // 简单解析：直接替换后计算
        try {
            // 处理 "十二" 这种格式 → "12"
            normalized = normalized.replace("零", "");
            normalized = normalized.replace("一十", "10");
            normalized = normalized.replace("二十", "20");
            normalized = normalized.replace("三十", "30");
            normalized = normalized.replace("四十", "40");
            normalized = normalized.replace("五十", "50");
            normalized = normalized.replace("六十", "60");
            normalized = normalized.replace("七十", "70");
            normalized = normalized.replace("八十", "80");
            normalized = normalized.replace("九十", "90");

            // 处理 "一百" 格式
            if (normalized.contains("百")) {
                String[] parts = normalized.split("百");
                int hundreds = Integer.parseInt(parts[0]) * 100;
                int tens = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
                return hundreds + tens;
            }

            // 处理 "千" 格式
            if (normalized.contains("千")) {
                String[] parts = normalized.split("千");
                int thousands = Integer.parseInt(parts[0]) * 1000;
                int rest = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
                return thousands + rest;
            }

            // 处理 "万" 格式
            if (normalized.contains("万")) {
                String[] parts = normalized.split("万");
                int tenThousands = Integer.parseInt(parts[0]) * 10000;
                int rest = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1]) : 0;
                return tenThousands + rest;
            }

            // 纯数字
            return Integer.parseInt(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析罗马数字
     */
    private static Integer parseRomanNumber(String input) {
        input = input.toUpperCase();
        if (!input.matches("[IVXLCDM]+")) return null;

        int result = 0;
        int prev = 0;
        for (int i = input.length() - 1; i >= 0; i--) {
            int val = 0;
            switch (input.charAt(i)) {
                case 'I': val = 1; break;
                case 'V': val = 5; break;
                case 'X': val = 10; break;
                case 'L': val = 50; break;
                case 'C': val = 100; break;
                case 'D': val = 500; break;
                case 'M': val = 1000; break;
            }
            if (val < prev) result -= val;
            else result += val;
            prev = val;
        }
        return result > 0 ? result : null;
    }

    /**
     * 解析英文数字单词
     */
    private static Integer parseEnglishNumber(String input) {
        input = input.toLowerCase().trim();
        switch (input) {
            case "zero": return 0;
            case "one": return 1;
            case "two": return 2;
            case "three": return 3;
            case "four": return 4;
            case "five": return 5;
            case "six": return 6;
            case "seven": return 7;
            case "eight": return 8;
            case "nine": return 9;
            case "ten": return 10;
            case "eleven": return 11;
            case "twelve": return 12;
            case "thirteen": return 13;
            case "fourteen": return 14;
            case "fifteen": return 15;
            case "sixteen": return 16;
            case "seventeen": return 17;
            case "eighteen": return 18;
            case "nineteen": return 19;
            case "twenty": return 20;
            case "thirty": return 30;
            case "forty": return 40;
            case "fifty": return 50;
            case "sixty": return 60;
            case "seventy": return 70;
            case "eighty": return 80;
            case "ninety": return 90;
            case "hundred": return 100;
            case "thousand": return 1000;
            default: return null;
        }
    }

    /**
     * 检查玩家的访客权限是否有效（未过期）
     */
    public boolean hasValidVisitorPermission(Player player, AreaConfig ac) {
        if (dbConnection == null || ac == null) return false;
        try {
            int landId = getLandIdFromDb(ac.name);
            if (landId <= 0) return false;
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT expires_at FROM area_land_permissions "
                            + "WHERE land_id = ? AND player_name = ? AND role = 'visitor'");
            stmt.setInt(1, landId);
            stmt.setString(2, player.getName());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                long expiresAt = rs.getLong("expires_at");
                if (expiresAt == 0 || expiresAt > System.currentTimeMillis()) {
                    rs.close();
                    stmt.close();
                    return true;
                }
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            // 忽略
        }
        return false;
    }

    /** 显示用户组管理面板（CLI可点击交互） */
    private void showGroupListPanel(CommandSender sender) {
        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm == null) { sender.sendMessage("§c用户组系统未初始化"); return; }

        Map<String, UserGroupManager.UserGroupConfig> groups = ugm.getGroupConfigs();
        sender.sendMessage("§a§l========== 用户组管理 ==========");

        if (groups.isEmpty()) {
            sender.sendMessage("§7当前暂无用户组定义");
            sender.sendMessage("§7你可以通过以下方式创建用户组:");
            sender.sendMessage("§e  /protect groupset <组名> [显示名] [颜色] [优先级] [价格] [上限]");
            sender.sendMessage("§7或通过PHP管理后台创建");
        } else {
            sender.sendMessage("§f共 §a" + groups.size() + " §f个用户组:");
            sender.sendMessage("");
            for (UserGroupManager.UserGroupConfig cfg : groups.values()) {
                String priceStr = cfg.landPricePerSqm >= 0 ? cfg.landPricePerSqm + "/㎡" : "全局默认";
                String maxStr = cfg.maxLands >= 0 ? String.valueOf(cfg.maxLands) : "全局默认";
                sender.sendMessage("§f" + cfg.displayColor + "§l" + cfg.displayName
                        + " §7(ID=" + cfg.name + ", 优先级=" + cfg.priority + ")");
                sender.sendMessage("§7  价格=" + priceStr + " 上限=" + maxStr + " 默认权限=" + cfg.defaultPerms);
                // ★ 每个组提供编辑和删除操作入口
                if (sender instanceof Player) {
                    Player pp = (Player) sender;
                    // ★ 成员管理
                    pp.sendMessage(Component.empty()
                            .append(Component.text("  §a[成员管理] "))
                            .hoverEvent(HoverEvent.showText(Component.text("§a管理此用户组的成员")))
                            .clickEvent(ClickEvent.runCommand("/protect groupmembers " + cfg.name))
                    );
                    pp.sendMessage(Component.empty()
                            .append(Component.text("  §e[编辑] "))
                            .hoverEvent(HoverEvent.showText(Component.text("§e交互式编辑此用户组配置")))
                            .clickEvent(ClickEvent.runCommand("/protect groupedit " + cfg.name))
                    );
                    if (!cfg.name.equals(UserGroupManager.DEFAULT_GROUP)) {
                        pp.sendMessage(Component.empty()
                                .append(Component.text("  §c[删除] "))
                                .hoverEvent(HoverEvent.showText(Component.text("§c删除此用户组")))
                                .clickEvent(ClickEvent.runCommand("/protect groupdelconfig " + cfg.name))
                        );
                    }
                }
            }
        }

        // 操作入口
        sender.sendMessage("");
        if (sender instanceof Player) {
            Player pp = (Player) sender;
            // 创建新组
            pp.sendMessage(Component.empty()
                    .append(Component.text("§a[创建新用户组] §e点击交互式创建"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击开始创建流程")))
                    .clickEvent(ClickEvent.suggestCommand("/protect groupset "))
            );
            // 从PHP同步
            pp.sendMessage(Component.empty()
                    .append(Component.text("§b[从PHP同步] §e点击拉取PHP端用户组"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e从管理后台同步用户组数据")))
                    .clickEvent(ClickEvent.runCommand("/protect groupset sync"))
            );
            // ★ 返回领地系统首页（CLI模式）
            pp.sendMessage(Component.empty()
                    .append(Component.text("§7[返回领地首页] "))
                    .hoverEvent(HoverEvent.showText(Component.text("§7返回领地系统主菜单")))
                    .clickEvent(ClickEvent.runCommand("/protect"))
            );
        }
        sender.sendMessage("§7§l─────────────────────────────────");
    }

    /**
     * ★ 用户组CLI交互式编辑面板
     * 每个字段显示当前值，点击可输入新值
     */
    private void showGroupEditCLI(Player p, UserGroupManager.UserGroupConfig cfg) {
        p.sendMessage(Component.text("§6§l────────── 编辑用户组: " + cfg.displayName + " ──────────"));

        // 价格
        p.sendMessage(Component.empty()
                .append(Component.text("§a每㎡价格: §f" + cfg.landPricePerSqm + " "))
                .append(Component.text("§a[+1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " price " + (cfg.landPricePerSqm + 1))))
                .append(Component.text("§c[-1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " price " + Math.max(0, cfg.landPricePerSqm - 1))))
                .append(Component.text("§e[输入] ").clickEvent(ClickEvent.suggestCommand("/protect groupset " + cfg.name + " price ")))
        );
        p.sendMessage(Component.empty()
                .append(Component.text("  §7点击§e[输入]§7后在聊天栏输入新值（支持中文/罗马数字）"))
        );

        // 最大领地数
        p.sendMessage(Component.empty()
                .append(Component.text("§a最大领地数: §f" + cfg.maxLands + " "))
                .append(Component.text("§a[+1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " maxlands " + (cfg.maxLands + 1))))
                .append(Component.text("§c[-1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " maxlands " + Math.max(0, cfg.maxLands - 1))))
                .append(Component.text("§e[输入] ").clickEvent(ClickEvent.suggestCommand("/protect groupset " + cfg.name + " maxlands ")))
        );

        // 优先级
        p.sendMessage(Component.empty()
                .append(Component.text("§a优先级: §f" + cfg.priority + " "))
                .append(Component.text("§a[+1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " priority " + (cfg.priority + 1))))
                .append(Component.text("§c[-1] ").clickEvent(ClickEvent.runCommand("/protect groupset " + cfg.name + " priority " + Math.max(0, cfg.priority - 1))))
                .append(Component.text("§e[输入] ").clickEvent(ClickEvent.suggestCommand("/protect groupset " + cfg.name + " priority ")))
        );

        p.sendMessage("");
        // 返回按钮
        p.sendMessage(Component.empty()
                .append(Component.text("§c[返回用户组列表] "))
                .hoverEvent(HoverEvent.showText(Component.text("§c返回用户组管理列表")))
                .clickEvent(ClickEvent.runCommand("/protect grouplist"))
        );
        p.sendMessage(Component.text("§6§l─────────────────────────────────"));
    }

    private void showHelp(CommandSender s) {
        s.sendMessage("§e§l==== 区域防护 ====");
        s.sendMessage("§a§l点击下方命令可直接执行:");

        // 可点击的帮助消息
        sendClickableHelp(s, "/protect menu", "打开GUI菜单");
        sendClickableHelp(s, "/protect 工具", "获取选地工具");
        sendClickableHelp(s, "/protect 创建", "创建区域(先选点)");
        sendClickableHelp(s, "/protect 列表", "区域列表");
        sendClickableHelp(s, "/protect 重载", "重载配置");
        sendClickableHelp(s, "/protect 删除", "删除当前所在区域");
        sendClickableHelp(s, "/protect info", "当前领地信息");
        sendClickableHelp(s, "/protect add", "玩家加白(需在领地内)");
        sendClickableHelp(s, "/protect remove", "玩家删白(需在领地内)");
        sendClickableHelp(s, "/protect additem", "物品加黑");
        sendClickableHelp(s, "/protect removeitem", "物品删黑");
        sendClickableHelp(s, "/protect on", "显示边框");
        sendClickableHelp(s, "/protect off", "关闭边框");
        sendClickableHelp(s, "/protect tempon", "临时边框(15秒)");
        sendClickableHelp(s, "/protect list", "列出白名单(需在领地内)");
        sendClickableHelp(s, "/protect listitem", "列出物品黑名单");
        sendClickableHelp(s, "/protect config", "查看/修改全局配置");

        s.sendMessage("§e§l---- 权限管理 ----");
        sendClickableHelp(s, "/protect setowner", "设置当前领地所有者(管理员)");
        sendClickableHelp(s, "/protect addvisitor", "添加访客(需在领地内)");
        sendClickableHelp(s, "/protect removevisitor", "移除访客(需在领地内)");
        sendClickableHelp(s, "/protect listvisitors", "列出访客(需在领地内)");
        sendClickableHelp(s, "/protect transfer", "转让领地(需在领地内)");
        sendClickableHelp(s, "/protect settp", "设置领地传送点");
        sendClickableHelp(s, "/protect tp <领地>", "传送到领地传送点");

        s.sendMessage("§e§l---- 权限商店 ----");
        sendClickableHelp(s, "/protect shop list", "查看在售权限");
        sendClickableHelp(s, "/protect shop my", "查看我的权限");
        sendClickableHelp(s, "/protect info", "当前领地信息");

        s.sendMessage("§e§l---- 设置 ----");
        sendClickableHelp(s, "/protect uimode", "切换UI模式(GUI/CLI)");

        s.sendMessage("§b§l欢迎游玩草原探险服务器");
        s.sendMessage("§b§l服务器ip：mc2.ypshidifu.cn 端口30679");
    }

    /**
     * 发送可点击的帮助消息
     * 玩家点击后自动执行命令
     */
    private void sendClickableHelp(CommandSender s, String command, String description) {
        if (!(s instanceof Player)) {
            // 控制台直接显示纯文本
            s.sendMessage("§a" + command + " §7" + description);
            return;
        }

        Player p = (Player) s;
        // 使用Adventure API创建可点击消息
        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component.empty()
                .append(net.kyori.adventure.text.Component.text("§a§l" + command + " "))
                .append(net.kyori.adventure.text.Component.text("§7" + description)
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                net.kyori.adventure.text.Component.text("§e点击执行该命令")))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command)));
        p.sendMessage(msg);
    }
// ==================== 白名单管理公共方法 ====================

    // ==================== 白名单操作 ====================

    public boolean isPlayerGlobalWhitelisted(
            String playerName) {
        return globalPlayerWhitelist.contains(playerName);
    }

    public boolean addPlayerToGlobalWhitelist(
            String playerName) {
        boolean ok = globalPlayerWhitelist.add(playerName);
        if (ok) saveWhitelists();
        return ok;
    }

    public boolean removePlayerFromGlobalWhitelist(
            String playerName) {
        boolean ok = globalPlayerWhitelist.remove(playerName);
        if (ok) saveWhitelists();
        return ok;
    }

    public boolean isPlayerAreaWhitelisted(
            String areaName, String playerName) {
        Set<String> wl =
                areaPlayerWhitelist.get(areaName);
        return wl != null && wl.contains(playerName);
    }

    public boolean addPlayerToAreaWhitelist(
            String areaName, String playerName) {
        Set<String> set = areaPlayerWhitelist
                .computeIfAbsent(areaName,
                        k -> ConcurrentHashMap.newKeySet());
        boolean ok = set.add(playerName);
        if (ok) saveWhitelists();
        return ok;
    }

    public boolean removePlayerFromAreaWhitelist(
            String areaName, String playerName) {
        Set<String> wl =
                areaPlayerWhitelist.get(areaName);
        if (wl == null) return false;
        // ★ 精确匹配 + 小写匹配
        boolean ok = wl.remove(playerName);
        if (!ok) ok = wl.remove(playerName.toLowerCase());
        if (ok) saveWhitelists();
        return ok;
    }



    public int handleConfiscate(Player p, AreaConfig ac) {
        // 管理员和领地所有者免检
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return 0;
        Set<String> allItems = new HashSet<>(ac.confiscateItems);
        allItems.addAll(globalItemBlacklist);
        Set<String> areaList = areaItemBlacklist.get(ac.name);
        if (areaList != null) allItems.addAll(areaList);

        if (allItems.isEmpty()) return 0;

        int totalRemoved = 0;
        String confiscatedNames = "";

        for (String itemId : allItems) {
            // 过滤掉区域名和无效ID
            Material mat = Material.matchMaterial(itemId);
            if (mat == null) {
                plugin.getLogger().warning(
                        "[防护] 跳过无效物品: " + itemId);
                continue;
            }
            ItemStack[] all = p.getInventory().getContents();
            int count = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] != null && all[i].getType() == mat) {
                    count += all[i].getAmount();
                    all[i] = null;
                }
            }
            p.getInventory().setContents(all);

            ItemStack off = p.getInventory().getItemInOffHand();
            if (off != null && off.getType() == mat) {
                count += off.getAmount();
                p.getInventory().setItemInOffHand(null);
            }

            if (count > 0) {
                totalRemoved += count;
                if (!confiscatedNames.isEmpty())
                    confiscatedNames += "§7、§e";
                confiscatedNames += "§e" + matname(mat)
                        + " x" + count;
            }
        }

        if (totalRemoved > 0) {
            String msg = (ac.confiscateMsg != null
                    && !ac.confiscateMsg.isEmpty())
                    ? ac.confiscateMsg
                    : "你携带了违禁品，已被没收";
            // ★ 替换变量
            msg = msg.replace("{player}", p.getName())
                    .replace("{area}", ac.name)
                    .replace("{count}", String.valueOf(totalRemoved))
                    .replace("{items}", confiscatedNames);
            p.sendMessage("§c§l[区域防护] §f" + msg);
            p.sendMessage("§7没收物品: " + confiscatedNames);

            if (ac.enableAnnounce
                    && ac.announceTemplate != null
                    && !ac.announceTemplate.isEmpty()) {
                String ann = ac.announceTemplate
                        .replace("{player}", p.getName())
                        .replace("{area}", ac.name)
                        .replace("{count}", String.valueOf(totalRemoved))
                        .replace("{items}", confiscatedNames);
                Bukkit.broadcastMessage("§c§l[区域防护] §f" + ann);
            }
            for (String cmd : ac.punishCommands) {
                String finalCmd = cmd
                        .replace("{player}", p.getName())
                        .replace("{area}", ac.name)
                        .replace("{count}", String.valueOf(totalRemoved));
                Bukkit.getScheduler().runTask(plugin,
                        () -> Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(), finalCmd));
            }
        }
        return totalRemoved;
    }
    public void clearBlacklists() {
        globalItemBlacklist.clear();
        areaItemBlacklist.clear();
        plugin.getLogger().info("[防护] 已清空所有黑名单");
    }

    @EventHandler
    public void onEntityDamage(
            org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        String typeName = entity.getType().name();

        // ★ 和平模式攻击免疫：敌对生物（含投射物）对玩家的攻击无效
        if (entity instanceof Player) {
            boolean hostileAttack = false;
            Entity damager = e.getDamager();
            if (isHostile(damager.getType().name())) {
                hostileAttack = true;
            } else if (damager instanceof org.bukkit.entity.Projectile) {
                // 投射物（箭/三叉戟等）：检查shooter是否敌对生物
                org.bukkit.entity.Projectile proj = (org.bukkit.entity.Projectile) damager;
                if (proj.getShooter() instanceof Entity) {
                    hostileAttack = isHostile(((Entity) proj.getShooter()).getType().name());
                }
            }
            if (hostileAttack) {
                Player victim = (Player) entity;
                AreaConfig pac = getArea(
                        victim.getWorld().getName(),
                        victim.getLocation().getBlockX(),
                        victim.getLocation().getBlockY(),
                        victim.getLocation().getBlockZ());
                if (pac != null && pac.peaceMode) {
                    e.setCancelled(true);
                    return;
                }
            }
        }

        if (typeName.equals("ITEM_FRAME")
                || typeName.equals("GLOW_ITEM_FRAME")) {
            if (!(e.getDamager() instanceof Player)) return;
            Player p = (Player) e.getDamager();

            // ★ 统一白名单检查（支持per-player权限）
            AreaConfig ac = findFrameArea(entity);
            if (ac == null || !getEffectiveDeny(p, ac, "denyItemFrame")) return;

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== 攻击生物逻辑 =====
        if (!(e.getDamager() instanceof Player)) return;
        if (e.getEntity() instanceof Player) return; // PVP走下面的逻辑
        Player mobAttacker = (Player) e.getDamager();
        AreaConfig acMob = getArea(
                mobAttacker.getWorld().getName(),
                mobAttacker.getLocation().getBlockX(),
                mobAttacker.getLocation().getBlockY(),
                mobAttacker.getLocation().getBlockZ());
        if (acMob != null && getEffectiveDeny(mobAttacker, acMob, "denyMobAttack")) {
            // ★ 公共建筑设施：访客允许攻击敌对生物
            if (!acMob.isPublicBuilding || mobAttacker.getName().equalsIgnoreCase(acMob.owner) || isAreaAdmin(mobAttacker)) {
                e.setCancelled(true);
                mobAttacker.sendMessage("§c§l[区域防护] §f此区域禁止攻击生物");
                return;
            }
        }

        // ===== PVP 逻辑 =====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        Player victim = (Player) e.getEntity();

        // 管理员互殴不受限
        if (isAreaAdmin(attacker) && isAreaAdmin(victim)) return;
        // 伤害者在自己领地无denyPVP时，跳过
        AreaConfig acAttacker = getArea(
                attacker.getWorld().getName(),
                attacker.getLocation().getBlockX(),
                attacker.getLocation().getBlockY(),
                attacker.getLocation().getBlockZ());
        if (acAttacker == null || !getEffectiveDeny(attacker, acAttacker, "denyPVP")) return;
        // 受害者领地有denyPVP → 阻断
        AreaConfig acVictim = getArea(
                victim.getWorld().getName(),
                victim.getLocation().getBlockX(),
                victim.getLocation().getBlockY(),
                victim.getLocation().getBlockZ());
        if (acVictim != null && getEffectiveDeny(victim, acVictim, "denyPVP")) {
            e.setCancelled(true);
            attacker.sendMessage("§c§l[区域防护] §f该领地禁止PVP");
        }
    }

    // 展示框被点击（旋转/放入/取出）
    @EventHandler
    public void onItemFrameInteract(
            PlayerInteractAtEntityEvent e) {
        Entity clicked = e.getRightClicked();
        String typeName = clicked.getType().name();
        if (!typeName.equals("ITEM_FRAME")
                && !typeName.equals("GLOW_ITEM_FRAME")) {
            return;
        }

        Player p = e.getPlayer();

        // ★ 使用统一的权限检查方法（支持per-player权限）
        AreaConfig ac = findFrameArea(clicked);
        if (ac == null) return;
        // ★ 展示框同时受denyItemFrame和denyContainer控制
        if (!getEffectiveDeny(p, ac, "denyItemFrame") && !getEffectiveDeny(p, ac, "denyContainer")) return;

        // 非白名单 → 拦截
        // 记录当前旋转角度
        final Rotation savedRotation;
        final Entity frame = clicked;
        if (clicked instanceof org.bukkit.entity.ItemFrame) {
            savedRotation =
                    ((org.bukkit.entity.ItemFrame) clicked)
                            .getRotation();
        } else {
            savedRotation = null;
        }

        e.setCancelled(true);

        // 下一tick强制恢复旋转
        if (savedRotation != null) {
            final Rotation restore = savedRotation;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (frame.isValid()
                        && frame instanceof org.bukkit.entity.ItemFrame) {
                    ((org.bukkit.entity.ItemFrame) frame)
                            .setRotation(restore);
                }
            }, 1L);
        }

        p.sendMessage("§c§l[区域防护] §f禁止交互展示框");

        // 驱逐远程敌对生物
        banRangedHostiles(p, ac);
    }



    // 展示框保护：检查展示框本身+周围方块所属区域

    // ===== 展示框攻击拦截（左键/投射物破坏）=====
    @EventHandler
    public void onEntityDamageByEntity(
            org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        String typeName = entity.getType().name();

        if (typeName.equals("ITEM_FRAME")
                || typeName.equals("GLOW_ITEM_FRAME")) {
            // 展示框被攻击
            if (!(e.getDamager() instanceof Player)) return;
            Player p = (Player) e.getDamager();

            AreaConfig ac = findFrameArea(entity);
            if (ac == null) return;

            // ★ 展示框同时受denyItemFrame和denyContainer控制
            if (!getEffectiveDeny(p, ac, "denyItemFrame") && !getEffectiveDeny(p, ac, "denyContainer")) return;

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== PVP 逻辑（原有的，不动）=====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player attacker = (Player) e.getDamager();
        Player victim = (Player) e.getEntity();

        // 管理员互殴不受限
        if (isAreaAdmin(attacker) && isAreaAdmin(victim)) return;
        // 伤害者在自己领地无denyPVP时，跳过
        AreaConfig acAttacker = getArea(
                attacker.getWorld().getName(),
                attacker.getLocation().getBlockX(),
                attacker.getLocation().getBlockY(),
                attacker.getLocation().getBlockZ());
        if (acAttacker == null || !getEffectiveDeny(attacker, acAttacker, "denyPVP")) return;
        // 受害者领地有denyPVP → 阻断
        AreaConfig acVictim = getArea(
                victim.getWorld().getName(),
                victim.getLocation().getBlockX(),
                victim.getLocation().getBlockY(),
                victim.getLocation().getBlockZ());
        if (acVictim != null && getEffectiveDeny(victim, acVictim, "denyPVP")) {
            e.setCancelled(true);
            attacker.sendMessage("§c§l[区域防护] §f该领地禁止PVP");
        }
    }
    private AreaConfig findFrameArea(Entity frame) {
        Location loc = frame.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        String world = loc.getWorld().getName();

        AreaConfig ac = getArea(world, bx, by, bz);
        if (ac != null && ac.denyItemFrame) return ac;

        int[][] offsets = {
                {0, 0, 1}, {0, 0, -1},
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0}
        };
        for (int[] off : offsets) {
            ac = getArea(world,
                    bx + off[0], by + off[1], bz + off[2]);
            if (ac != null && ac.denyItemFrame) return ac;
        }
        return null;
    }

    // ===== 投射物击中展示框（弓箭/弩）=====
    @EventHandler
    public void onProjectileHit(
            org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) e.getEntity().getShooter();
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        String typeName = hit.getType().name();
        if (!typeName.equals("ITEM_FRAME")
                && !typeName.equals("GLOW_ITEM_FRAME")) {
            return;
        }

        // ★ 使用统一的权限检查方法（支持per-player权限）
        AreaConfig ac = findFrameArea(hit);
        if (ac == null) return;
        // ★ 展示框同时受denyItemFrame和denyContainer控制
        if (!getEffectiveDeny(shooter, ac, "denyItemFrame") && !getEffectiveDeny(shooter, ac, "denyContainer")) return;

        e.setCancelled(true);
        shooter.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
    }


    // ===== 区域外火焰不能蔓延进区域 =====
    @EventHandler
    public void onBlockSpread(BlockSpreadEvent e) {
        Block block = e.getBlock();
        AreaConfig tgtArea = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (tgtArea != null && tgtArea.denyFireSpread) {
            e.setCancelled(true);
        }
    }

    // ===== 区域内禁止点燃方块 =====
    @EventHandler
    public void onIgnite(BlockIgniteEvent e) {
        Player p = e.getPlayer();
        Block tgtBlock = e.getBlock();
        
        AreaConfig srcArea = null;
        AreaConfig tgtArea = null;
        
        // 检查源领地（玩家所在位置）
        if (p != null) {
            srcArea = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
        }
        
        // 检查目标领地（被点燃方块位置）
        tgtArea = getArea(
                tgtBlock.getWorld().getName(),
                tgtBlock.getX(), tgtBlock.getY(), tgtBlock.getZ());
        
        // OWNER/ADMIN 豁免
        if (p != null && srcArea != null && hasPermission(p, srcArea, PermissionLevel.OWNER)) return;
        if (p != null && tgtArea != null && hasPermission(p, tgtArea, PermissionLevel.OWNER)) return;
        
        // 任何一方denyFire=true就阻止
        if ((srcArea != null && srcArea.denyFire) || (tgtArea != null && tgtArea.denyFire)) {
            e.setCancelled(true);
            if (p != null) {
                p.sendMessage("§c§l[区域防护] §f此区域禁止点燃物品");
            }
        }
    }

    // ===== 玩家使用打火石/点燃物品时直接拦截 =====
    @EventHandler
    public void onPlayerInteractFire(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action action = e.getAction();
        Material mat = e.getItem() == null ? null : e.getItem().getType();
        
        // 只拦截 RIGHT_CLICK_BLOCK/AIR 使用打火石/火床/TNT/岩浆桶
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;
        if (mat != Material.FLINT_AND_STEEL && mat != Material.FIRE_CHARGE 
                && mat != Material.CAMPFIRE && mat != Material.SOUL_CAMPFIRE
                && mat != Material.LAVA_BUCKET) {
            return;
        }
        
        // 检查目标方块或玩家所处领地
        Block clickedBlock = e.getClickedBlock();
        AreaConfig ac1 = null;
        AreaConfig ac2 = null;
        
        // 玩家位置
        ac1 = getArea(p.getWorld().getName(), 
                p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ());
        
        // 点击的方块
        if (clickedBlock != null) {
            ac2 = getArea(clickedBlock.getWorld().getName(),
                    clickedBlock.getX(), clickedBlock.getY(), clickedBlock.getZ());
        }
        
        // OWNER/ADMIN 豁免
        if (hasPermission(p, ac1, PermissionLevel.OWNER)) return;
        if (hasPermission(p, ac2, PermissionLevel.OWNER)) return;
        
        if ((ac1 != null && ac1.denyFire) || (ac2 != null && ac2.denyFire)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f此区域禁止点燃物品");
        }
    }

    // ===== 火焰弹/烈焰球拦截 =====
    @EventHandler
    public void onProjectileHitFireball(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Fireball)) return;
        org.bukkit.entity.Fireball fb = (org.bukkit.entity.Fireball) e.getEntity();
        AreaConfig ac = getArea(fb.getWorld().getName(), 
                fb.getLocation().getBlockX(), fb.getLocation().getBlockY(), fb.getLocation().getBlockZ());
        if (ac != null && ac.denyFire) {
            fb.remove();
            // 如果射手是玩家，发消息
            if (fb.getShooter() instanceof Player) {
                ((Player) fb.getShooter()).sendMessage("§c§l[区域防护] §f此区域禁止投掷火焰");
            }
        }
    }

    // ===== TNT自爆防漏网：EntityExplodeEvent =====
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
        org.bukkit.entity.Entity ent = e.getEntity();
        if (ent != null) {
            // 如果是TNT引爆，检查引爆位置
            AreaConfig ac = getArea(ent.getWorld().getName(), 
                    (int) ent.getLocation().getBlockX(), 
                    (int) ent.getLocation().getBlockY(), 
                    (int) ent.getLocation().getBlockZ());
            // 如果爆炸源在禁止火焰蔓延的区域，取消所有爆炸
            if (ac != null && ac.denyFireSpread) {
                e.blockList().clear();
                return;
            }
        }
        // 检查每个被爆炸的方块是否在禁止爆炸的区域
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            AreaConfig a = getArea(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
            if (a != null && a.denyExplosion) {
                it.remove();
            }
        }
    }

    // ===== 区域内方块着火（TNT、岩浆等）也阻止 =====
    @EventHandler
    public void onBlockBurn(BlockBurnEvent e) {
        Block block = e.getBlock();
        AreaConfig ac = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (ac != null && ac.denyFireSpread) {
            e.setCancelled(true);
        }
    }

    // ===== ★ 拴绳使用（给生物拴绳或拉拽）=====
    @EventHandler
    public void onLeash(PlayerInteractEntityEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = e.getPlayer();
        Entity entity = e.getRightClicked();
        // 检查玩家所在位置或实体位置
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) {
            ac = getArea(
                    entity.getWorld().getName(),
                    entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockY(),
                    entity.getLocation().getBlockZ());
        }
        if (ac == null || !ac.denyLead) return;
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;
        // 只拦截拴绳操作（主手持拴绳）
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand != null && hand.getType() == Material.LEAD) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止在此区域使用拴绳");
        }
    }

    // ===== ★ 投喂动物 =====
    @EventHandler
    public void onPlayerFeedAnimal(EntityBreedEvent e) {
        if (!(e.getBreeder() instanceof Player)) return;
        Player p = (Player) e.getBreeder();
        Entity entity = e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) {
            ac = getArea(
                    entity.getWorld().getName(),
                    entity.getLocation().getBlockX(),
                    entity.getLocation().getBlockY(),
                    entity.getLocation().getBlockZ());
        }
        if (ac == null || !ac.denyAnimalFeeding) return;
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;
        e.setCancelled(true);
        p.sendMessage("§c§l[区域防护] §f禁止在此区域繁殖动物");
    }

    // ===== ★ 袭击侦听（玩家触发袭击时检测）=====
    @EventHandler
    public void onRaidTrigger(org.bukkit.event.raid.RaidTriggerEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null || !ac.denyRaid) return;
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;
        e.setCancelled(true);
        p.sendMessage("§c§l[区域防护] §f此区域禁止触发袭击事件");
    }

    // ===== ★ 玩家发光检测（通过定期扫描清除区域内发光效果）=====
    // 在onEnable中注册repeating task，此处提供public方法供Main调用
    public void checkGlowingPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac != null && getEffectiveDeny(p, ac, "denyGlowing")) {
                if (p.hasPotionEffect(PotionEffectType.GLOWING)) {
                    p.removePotionEffect(PotionEffectType.GLOWING);
                    p.sendMessage("§c§l[区域防护] §f此区域禁止使用发光效果");
                }
            }
        }
    }


    private void savePeaceWhitelist(AreaConfig ac) {
        File file = new File(rootDir, ac.name + ".txt");
        if (!file.exists()) return;

        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new FileReader(file, StandardCharsets.UTF_8));
            String line;
            boolean found = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("和平")
                        && trimmed.contains("白名单")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("和平模式白名单:");
                    boolean first = true;
                    for (String name : ac.peaceWhitelist) {
                        if (!first) sb.append(",");
                        sb.append(name);
                        first = false;
                    }
                    lines.add(sb.toString());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
            reader.close();

            if (!found && !ac.peaceWhitelist.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("和平模式白名单:");
                boolean first = true;
                for (String name : ac.peaceWhitelist) {
                    if (!first) sb.append(",");
                    sb.append(name);
                    first = false;
                }
                lines.add(sb.toString());
            }

            FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8);
            for (String l : lines) {
                writer.write(l + "\n");
            }
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "[防护] 保存和平白名单失败: " + e.getMessage());
        }
    }


    @EventHandler
    public void onPaintingPlace(
            org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        // 暂不处理
    }


    // ==================== 区域配置文件 ====================

    public static class LandConfig {
        public int 创建价格 = 1000;
        public int 默认高度范围最小 = 0;
        public int 默认高度范围最大 = 255;
        public int 默认和平模式持续秒数 = 5;
        public int 权限商店最低价格 = 1000;
        public int 权限商店默认时长秒 = 86400;
        public int 权限商店最大时长秒 = 86400;
        public String 进入提示 = "进入领地: {area}";
        public String 离开提示 = "离开领地: {area}";
    }

    private final LandConfig landConfig = new LandConfig();

    public LandConfig getLandConfig() { return landConfig; }

    private void loadAreaConfig() {
        // ★ 配置文件放在area/子目录，避免插件根目录凌乱
        File areaDir = new File(plugin.getDataFolder(), "area");
        if (!areaDir.exists()) {
            areaDir.mkdirs();
        }
        // ★ 自动迁移：如果根目录有旧配置文件，移到area/目录
        File oldConfigFile = new File(plugin.getDataFolder(), "区域防护配置.txt");
        File configFile = new File(areaDir, "区域防护配置.txt");
        if (!configFile.exists() && oldConfigFile.exists()) {
            try {
                oldConfigFile.renameTo(configFile);
                plugin.getLogger().info("[防护] 已将区域防护配置迁移到 area/ 目录");
            } catch (Exception e) {
                // 迁移失败，使用新位置
            }
        }
        if (!configFile.exists()) {
            writeDefaultAreaConfig(configFile);
        }
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                switch (key) {
                    case "创建价格": landConfig.创建价格 = Integer.parseInt(value); break;
                    case "默认高度范围最小": landConfig.默认高度范围最小 = Integer.parseInt(value); break;
                    case "默认高度范围最大": landConfig.默认高度范围最大 = Integer.parseInt(value); break;
                    case "默认和平模式持续秒数": landConfig.默认和平模式持续秒数 = Integer.parseInt(value); break;
                    case "权限商店最低价格": landConfig.权限商店最低价格 = Integer.parseInt(value); break;
                    case "权限商店默认时长秒": landConfig.权限商店默认时长秒 = Integer.parseInt(value); break;
                    case "权限商店最大时长秒": landConfig.权限商店最大时长秒 = Integer.parseInt(value); break;
                    case "进入提示": landConfig.进入提示 = value; break;
                    case "离开提示": landConfig.离开提示 = value; break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 加载区域配置文件失败: " + e.getMessage());
        }
    }

    private void writeDefaultAreaConfig(File configFile) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
            pw.println("# 区域防护配置文件");
            pw.println("创建价格=1000");
            pw.println("默认高度范围最小=0");
            pw.println("默认高度范围最大=255");
            pw.println("默认和平模式持续秒数=5");
            pw.println("权限商店最低价格=1000");
            pw.println("权限商店默认时长秒=86400");
            pw.println("权限商店最大时长秒=86400");
            pw.println("进入提示=进入领地: {area}");
            pw.println("离开提示=离开领地: {area}");
        } catch (Exception e) {
            plugin.getLogger().warning("[防护] 写入默认区域配置失败: " + e.getMessage());
        }
    }

    // ==================== 数据类 ====================

    public static class AreaConfig {
        public String name = "";
        public String owner = "";  // 领地所有者
        public String world = "";
        public int x1, z1, x2, z2;
        public int yMin = 0, yMax = 255;
        public List<String> confiscateItems = new ArrayList<>();
        public List<String> denyUseItems = new ArrayList<>();
        public List<String> clearEffects = new ArrayList<>();     // 清除单个效果名
        public boolean clearAllBadEffects = false;                // 清除所有负面+中性
        public List<String[]> giveEffects = new ArrayList<>();    // [效果名, 等级, 秒数]
        public List<String> punishCommands = new ArrayList<>();
        public boolean denyBlockPlace = false;
        public boolean denyBlockBreak = false;
        public boolean denyContainer = false;   // ★ 独立容器管理权限
        public boolean denyPVP = false;
        public boolean denyFallDamage = false;
        public boolean denyHunger = false;
        public boolean denyAllDamage = false;
        public boolean denyMount = false;
        public boolean allowDrop = false;
        public boolean denyEnderPearl = false;
        public boolean denyBow = false;
        public boolean denyPotion = false;
        public boolean denyExplosion = false;
        public boolean denyMove = false;
        public boolean allowPickup = false;
        public boolean denyFire = false;
        // ★ 新增投掷物权限（三叉戟、雪球、风蛋、箭）
        public boolean denyThrownProjectiles = false;
        // ★ 玩家发光控制
        public boolean denyGlowing = false;
        // ★ 红石电路交互（中继器、比较器）
        public boolean denyRedstoneInteraction = false;
        // ★ 门禁交互（按钮、普通门和铁门、压力板）
        public boolean denyDoorInteraction = false;
        // ★ 音频交互（音符盒、唱片机）
        public boolean denyNoteblockJukebox = false;
        // ★ 拴绳使用
        public boolean denyLead = false;
        // ★ 农作物状态检测（播种&收获）
        public boolean denyCropHarvest = false;
        // ★ 采集羊毛和乐魂挽具
        public boolean denyWoolShear = false;
        // ★ 投喂动物
        public boolean denyAnimalFeeding = false;
        // ★ 玩家攻击生物
        public boolean denyMobAttack = false;
        public boolean enableAnnounce = false;
        public String announceTemplate = "";
        public String txtContent = "";
        public String confiscateMsg = "";
        public String enterMsg = "";
        public String leaveMsg = "";
        public boolean denyRaid = false;
        public boolean denyFireSpread = false;
        public boolean denyAllEffects = false;;
        public boolean peaceMode = false;
        public int peaceModeDuration = 5000; // 和平模式生物保护期(毫秒)，默认5秒，最大3600秒
        public boolean denyItemFrame = false;
        public Set<String> peaceWhitelist = new HashSet<>();
        public String enforceGameMode = null;  // 强制游戏模式
        public Set<String> modeExempt = new HashSet<>(); // 模式排除名单
        // ★ 传送点坐标（玩家自定义，防止卡墙）
        public double warpX = 0, warpY = 0, warpZ = 0;
        public float warpYaw = 0, warpPitch = 0;
        public String warpWorld = "";
        // ★ 公共建筑标记
        public boolean isPublicBuilding = false;




        private static final Map<String, PotionEffectType> EFFECT_MAP
                = new HashMap<>();
        static {
            // 负面效果
            EFFECT_MAP.put("缓慢", PotionEffectType.SLOWNESS);
            EFFECT_MAP.put("挖掘疲劳", PotionEffectType.MINING_FATIGUE);
            EFFECT_MAP.put("瞬间伤害", PotionEffectType.INSTANT_DAMAGE);
            EFFECT_MAP.put("反胃", PotionEffectType.NAUSEA);
            EFFECT_MAP.put("失明", PotionEffectType.BLINDNESS);
            EFFECT_MAP.put("饥饿", PotionEffectType.HUNGER);
            EFFECT_MAP.put("虚弱", PotionEffectType.WEAKNESS);
            EFFECT_MAP.put("中毒", PotionEffectType.POISON);
            EFFECT_MAP.put("凋零", PotionEffectType.WITHER);
            EFFECT_MAP.put("飘浮", PotionEffectType.LEVITATION);
            EFFECT_MAP.put("霉运", PotionEffectType.UNLUCK);
            EFFECT_MAP.put("黑暗", PotionEffectType.DARKNESS);
            EFFECT_MAP.put("蓄风", PotionEffectType.WIND_CHARGED);
            EFFECT_MAP.put("盘丝", PotionEffectType.WEAVING);
            EFFECT_MAP.put("渗浆", PotionEffectType.OOZING);
            EFFECT_MAP.put("寄生", PotionEffectType.INFESTED);
            // 中性效果
            EFFECT_MAP.put("不祥之兆", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("不祥征兆", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("袭击之兆", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("袭击征兆", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("试炼之兆", PotionEffectType.TRIAL_OMEN);
            EFFECT_MAP.put("试炼征兆", PotionEffectType.TRIAL_OMEN);
            // 正面效果
            EFFECT_MAP.put("迅捷", PotionEffectType.SPEED);
            EFFECT_MAP.put("急迫", PotionEffectType.HASTE);
            EFFECT_MAP.put("力量", PotionEffectType.STRENGTH);
            EFFECT_MAP.put("瞬间治疗", PotionEffectType.INSTANT_HEALTH);
            EFFECT_MAP.put("跳跃提升", PotionEffectType.JUMP_BOOST);
            EFFECT_MAP.put("生命恢复", PotionEffectType.REGENERATION);
            EFFECT_MAP.put("抗性提升", PotionEffectType.RESISTANCE);
            EFFECT_MAP.put("抗火", PotionEffectType.FIRE_RESISTANCE);
            EFFECT_MAP.put("水下呼吸", PotionEffectType.WATER_BREATHING);
            EFFECT_MAP.put("隐身", PotionEffectType.INVISIBILITY);
            EFFECT_MAP.put("夜视", PotionEffectType.NIGHT_VISION);
            EFFECT_MAP.put("发光", PotionEffectType.GLOWING);
            EFFECT_MAP.put("生命提升", PotionEffectType.HEALTH_BOOST);
            EFFECT_MAP.put("伤害吸收", PotionEffectType.ABSORPTION);
            EFFECT_MAP.put("饱和", PotionEffectType.SATURATION);
            EFFECT_MAP.put("幸运", PotionEffectType.LUCK);
            EFFECT_MAP.put("村庄英雄", PotionEffectType.HERO_OF_THE_VILLAGE);
            EFFECT_MAP.put("缓降", PotionEffectType.SLOW_FALLING);
            EFFECT_MAP.put("潮涌能量", PotionEffectType.CONDUIT_POWER);
            EFFECT_MAP.put("海豚的恩惠", PotionEffectType.DOLPHINS_GRACE);


            // 带效果二字
            // 补充带"效果"后缀的别名
            EFFECT_MAP.put("黑暗效果", PotionEffectType.DARKNESS);
            EFFECT_MAP.put("不祥之兆效果", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("袭击之兆效果", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("试炼之兆效果", PotionEffectType.TRIAL_OMEN);
            EFFECT_MAP.put("缓慢效果", PotionEffectType.SLOWNESS);
            EFFECT_MAP.put("中毒效果", PotionEffectType.POISON);
            EFFECT_MAP.put("凋零效果", PotionEffectType.WITHER);
            EFFECT_MAP.put("失明效果", PotionEffectType.BLINDNESS);
            EFFECT_MAP.put("饥饿效果", PotionEffectType.HUNGER);
            EFFECT_MAP.put("虚弱效果", PotionEffectType.WEAKNESS);
            EFFECT_MAP.put("反胃效果", PotionEffectType.NAUSEA);
            EFFECT_MAP.put("飘浮效果", PotionEffectType.LEVITATION);
            EFFECT_MAP.put("发光效果", PotionEffectType.GLOWING);
            EFFECT_MAP.put("霉运效果", PotionEffectType.UNLUCK);
            EFFECT_MAP.put("蓄风效果", PotionEffectType.WIND_CHARGED);
            EFFECT_MAP.put("盘丝效果", PotionEffectType.WEAVING);
            EFFECT_MAP.put("渗浆效果", PotionEffectType.OOZING);
            EFFECT_MAP.put("寄生效果", PotionEffectType.INFESTED);
            EFFECT_MAP.put("挖掘疲劳效果", PotionEffectType.MINING_FATIGUE);
            EFFECT_MAP.put("瞬间伤害效果", PotionEffectType.INSTANT_DAMAGE);
            EFFECT_MAP.put("村庄英雄效果", PotionEffectType.HERO_OF_THE_VILLAGE);
        }


        public int ruleCount() {
            int c = 0;
            if (!confiscateItems.isEmpty()) c++;
            if (denyBlockPlace) c++;
            if (denyBlockBreak) c++;
            if (denyPVP) c++;
            if (denyFallDamage) c++;
            if (denyHunger) c++;
            if (denyAllDamage) c++;
            if (allowDrop) c++;
            if (denyMount) c++;
            if (denyEnderPearl) c++;
            if (denyBow) c++;
            if (denyRaid) c++;
            if (denyPotion) c++;
            if (denyExplosion) c++;
            if (denyFireSpread) c++;
            if (denyAllEffects) c++;
            if (!clearEffects.isEmpty()) c++;
            if (clearAllBadEffects) c++;
            if (!giveEffects.isEmpty()) c++;
            if (enableAnnounce) c++;
            if (!punishCommands.isEmpty()) c++;
            if (!denyUseItems.isEmpty()) c++;
            if (peaceMode) c++;
            if (denyItemFrame) c++;
            if (denyThrownProjectiles) c++;
            if (denyGlowing) c++;
            if (denyRedstoneInteraction) c++;
            if (denyDoorInteraction) c++;
            if (denyNoteblockJukebox) c++;
            if (denyLead) c++;
            if (denyCropHarvest) c++;
            if (denyWoolShear) c++;
            if (denyAnimalFeeding) c++;
            if (!peaceWhitelist.isEmpty()) c++;

            return c;
        }
    }

    // ==================== 快速创建领地（无选点，以自身为中心） ====================

    /**
     * ★ 快速创建领地：以玩家为中心3×3，自动命名+冲突数字0~999
     * @return true成功创建，false失败
     */
    public boolean quickCreateLand(Player p) {
        if (p == null || dbConnection == null) return false;

        // ★ 检查领地上限
        UserGroupManager ugm = plugin.getUserGroup();
        int maxLands = (ugm != null) ? ugm.getPlayerMaxLands(p.getName(), globalMaxLandsPerPlayer) : globalMaxLandsPerPlayer;
        long landCount = areas.values().stream().filter(a -> p.getName().equalsIgnoreCase(a.owner)).count();
        // DB兜底
        if (landCount < maxLands) {
            try {
                java.sql.PreparedStatement cs = dbConnection.prepareStatement("SELECT COUNT(*) as cnt FROM area_lands WHERE LOWER(owner) = LOWER(?)");
                cs.setString(1, p.getName());
                java.sql.ResultSet cr = cs.executeQuery();
                if (cr.next()) { long dbc = cr.getLong("cnt"); if (dbc > landCount) landCount = dbc; }
                cr.close(); cs.close();
            } catch (Exception ignored) {}
        }
        if (landCount >= maxLands) {
            p.sendMessage("§c§l[防护] §f已达领地上限！每人最多 §e" + maxLands + " §f个领地");
            return false;
        }

        // ★ 以玩家为中心3×3
        int cx = p.getLocation().getBlockX();
        int cz = p.getLocation().getBlockZ();
        int x1 = cx - 1, z1 = cz - 1;
        int x2 = cx + 1, z2 = cz + 1;
        int area = 9; // 3×3

        // ★ 检查这个区域是否与其他领地重叠
        for (AreaConfig other : areas.values()) {
            if (!other.world.equalsIgnoreCase(p.getWorld().getName())) continue;
            // AABB重叠检测
            if (x1 <= other.x2 && x2 >= other.x1 && z1 <= other.z2 && z2 >= other.z1) {
                p.sendMessage("§c§l[防护] §f该区域与领地 §e" + other.name + " §f重叠，请走远点再试");
                return false;
            }
        }

        // ★ 计算费用
        UserGroupManager ug = plugin.getUserGroup();
        int pricePerSqm = (ug != null) ? ug.getPlayerLandPricePerSqm(p.getName(), globalCreatePricePerSqm) : globalCreatePricePerSqm;
        int totalCost = area * pricePerSqm;

        // ★ 检查余额
        BondManager bm = plugin.getBonds();
        if (bm != null && totalCost > 0) {
            int bal = bm.getBonds(p.getName());
            if (bal < totalCost) {
                p.sendMessage("§c§l[防护] §f余额不足！需要 §e" + totalCost + " §f债券，当前 §a" + bal);
                return false;
            }
        }

        // ★ 自动命名：玩家名，冲突加数字0~999
        String autoName = p.getName();
        if (areas.containsKey(autoName)) {
            boolean found = false;
            for (int i = 0; i <= 999; i++) {
                String candidate = p.getName() + i;
                if (!areas.containsKey(candidate)) { autoName = candidate; found = true; break; }
            }
            if (!found) {
                p.sendMessage("§c§l[防护] §f无法找到可用的领地名（已用尽0~999后缀）");
                return false;
            }
        }

        // ★ 扣费
        if (bm != null && totalCost > 0) {
            String src = (pricePerSqm != globalCreatePricePerSqm) ? "（用户组优惠价）" : "";
            if (!bm.deductBonds(p.getName(), totalCost, "land_create", p.getName(), p.getName(),
                    "快速创建领地: " + autoName + " (" + area + "㎡×" + pricePerSqm + ")")) {
                p.sendMessage("§c§l[防护] §f扣费失败，请稍后重试");
                return false;
            }
            p.sendMessage("§a§l[防护] §f创建领地扣除 §e" + totalCost + " §f债券（" + area + "㎡×" + pricePerSqm + "/㎡）" + src);
        }

        // ★ 创建领地
        AreaConfig ac = new AreaConfig();
        ac.name = autoName;
        ac.owner = p.getName();
        ac.world = p.getWorld().getName();
        ac.x1 = x1; ac.z1 = z1; ac.x2 = x2; ac.z2 = z2;
        ac.yMin = 0; ac.yMax = 255;
        saveAreaToDb(ac);
        areas.put(autoName, ac);
        loadAllAreas();

        p.sendMessage("§a§l[防护] §f区域 §e" + autoName + " §f已创建（" + area + "㎡，以你为中心3×3）");
        return true;
    }
    }