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
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import java.sql.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.GameMode;
import org.bukkit.event.EventPriority;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import java.util.Iterator;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.entity.Item;




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

    // 选地
    private static final Material WAND = Material.BREEZE_ROD;

    private final Map<UUID, Location> pos1 =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2 =
            new ConcurrentHashMap<>();
    private final Set<UUID> selecting =
            ConcurrentHashMap.newKeySet();
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

      /*  if (p.isOp()) {
            plugin.getLogger().info(
                    "[防护-调试] 豁免原因: OP, 玩家=" + name);
            return true;
        }*/
        if (globalPlayerWhitelist.contains(name)) {
          /*  plugin.getLogger().info(
                    "[防护-调试] 豁免原因: 全局白名单, 玩家="
                            + name);*/
            return true;
        }
        Set<String> aw =
                areaPlayerWhitelist.get(areaName);
        if (aw != null && aw.contains(name)) {
         /*   plugin.getLogger().info(
                    "[防护-调试] 豁免原因: 区域白名单, 玩家="
                            + name + " 区域=" + areaName);*/
            return true;
        }
        AreaConfig ac = areas.get(areaName);
        if (ac != null && ac.modeExempt.contains(name)) {
          /*  plugin.getLogger().info(
                    "[防护-调试] 豁免原因: 模式排除名单, 玩家="
                            + name);*/
            return true;
        }
      /*  plugin.getLogger().info(
                "[防护-调试] 无豁免, 玩家=" + name);*/
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

        // ★ 兼容：如果没有从DB加载到数据，回退到txt（未迁移场景）
        if (areas.isEmpty()) {
            File[] files = rootDir.listFiles(
                    (File d, String n) -> n.endsWith(".txt"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String name = f.getName().replace(".txt", "");
                        AreaConfig ac = parseArea(name, f);
                        areas.put(name, ac);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[防护] 加载失败: " + f.getName());
                    }
                }
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
                ac.denyDrop = rs.getInt("deny_drop") == 1;
                ac.denyMount = rs.getInt("deny_mount") == 1;
                ac.denyEnderPearl = rs.getInt("deny_ender_pearl") == 1;
                ac.denyBow = rs.getInt("deny_bow") == 1;
                ac.denyPotion = rs.getInt("deny_potion") == 1;
                ac.denyExplosion = rs.getInt("deny_explosion") == 1;
                ac.denyRaid = rs.getInt("deny_raid") == 1;
                ac.denyFireSpread = rs.getInt("deny_fire_spread") == 1;
                ac.denyAllEffects = rs.getInt("deny_all_effects") == 1;
                ac.denyItemFrame = rs.getInt("deny_item_frame") == 1;
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
                    + "deny_all_effects, deny_item_frame, peace_mode, peace_mode_duration, "
                    + "peace_whitelist, enforce_game_mode, mode_exempt, enter_msg, leave_msg, "
                    + "confiscate_msg, enable_announce, announce_template, txt_content, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

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
                    insertStmt.setInt(22, ac.denyDrop ? 1 : 0);
                    insertStmt.setInt(23, ac.denyMount ? 1 : 0);
                    insertStmt.setInt(24, ac.denyEnderPearl ? 1 : 0);
                    insertStmt.setInt(25, ac.denyBow ? 1 : 0);
                    insertStmt.setInt(26, ac.denyPotion ? 1 : 0);
                    insertStmt.setInt(27, ac.denyExplosion ? 1 : 0);
                    insertStmt.setInt(28, ac.denyRaid ? 1 : 0);
                    insertStmt.setInt(29, ac.denyFireSpread ? 1 : 0);
                    insertStmt.setInt(30, ac.denyAllEffects ? 1 : 0);
                    insertStmt.setInt(31, ac.denyItemFrame ? 1 : 0);
                    insertStmt.setInt(32, ac.peaceMode ? 1 : 0);
                    insertStmt.setInt(33, ac.peaceModeDuration / 1000); // 存秒
                    insertStmt.setString(34, String.join(",", ac.peaceWhitelist));
                    insertStmt.setString(35, ac.enforceGameMode != null ? ac.enforceGameMode : "");
                    insertStmt.setString(36, String.join(",", ac.modeExempt));
                    insertStmt.setString(37, ac.enterMsg);
                    insertStmt.setString(38, ac.leaveMsg);
                    insertStmt.setString(39, ac.confiscateMsg);
                    insertStmt.setInt(40, ac.enableAnnounce ? 1 : 0);
                    insertStmt.setString(41, ac.announceTemplate);
                    insertStmt.setString(42, txtContent);
                    insertStmt.setLong(43, System.currentTimeMillis() / 1000);
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
            } else if (line.equals("禁止丢弃物品")) {
                ac.denyDrop = true;
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
        // 看看附近到底有什么实体
        List<Entity> nearby = p.getNearbyEntities(48, 48, 48);
       /* plugin.getLogger().info("[和平] " + p.getName()
                + " 附近实体总数=" + nearby.size());*/
        for (Entity ent : nearby) {
            String typeName = ent.getType().name();
       /*     plugin.getLogger().info("[和平] 实体: "
                    + typeName + " isHostile=" + isHostile(typeName));*/
        }
        int scanned = 0;
        int waiting = 0;
        int saved = 0;
        int banished = 0;

        for (Entity ent : p.getNearbyEntities(48, 48, 48)) {
            if (!isHostile(ent.getType().name())) continue;
            scanned++;

            UUID entUid = ent.getUniqueId();
            Long protectUntil = protectedEntities.get(entUid);
            String customName = ent.getCustomName();

            // 有保护期记录
            if (protectUntil != null) {
                long remain = protectUntil - now;

                if (remain > 0) {
                    // 保护期内，精确剩余秒数
                    int secondsLeft = (int) Math.ceil(remain / 1000.0);
                    waiting++;

                    // 保护期内也检查名字（玩家可能刚贴上）
                    if (customName != null
                            && !customName.isEmpty()
                            && ac.peaceWhitelist.contains(customName)) {
                        protectedEntities.remove(entUid);
                        saved++;
                /*        plugin.getLogger().info("[和平] "
                                + ent.getType().name()
                                + " 命名[" + customName
                                + "] 白名单匹配，永久保留");*/
                        continue;
                    }

                  /*  plugin.getLogger().info("[和平] "
                            + ent.getType().name()
                            + " 保护期剩余约" + secondsLeft + "秒");*/
                    continue;
                }

                // 保护期到了，最终检查
                protectedEntities.remove(entUid);
           /*     plugin.getLogger().info("[和平] "
                        + ent.getType().name() + " 5秒到期");*/

                // 到期时再查一次白名单
                if (customName != null
                        && !customName.isEmpty()
                        && ac.peaceWhitelist.contains(customName)) {
                    saved++;
                 /*   plugin.getLogger().info("[和平] "
                            + ent.getType().name()
                            + " 命名[" + customName
                            + "] 到期但白名单匹配，保留");*/
                    continue;
                }

                // 没名字或不在白名单，传送虚空
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(ent.getWorld().getMinHeight() - 50);
                // 末影人受伤害会自动传送回来，所以直接移除实体
                if ("ENDERMAN".equals(ent.getType().name())) {
                    ent.remove();
                } else {
                    ent.teleport(voidLoc);
                }
                banished++;
            /*    plugin.getLogger().info("[和平] "
                        + ent.getType().name()
                        + " 命名=[" + customName + "] 传送虚空");*/
                continue;
            }

            // 没有保护期记录（非刷怪蛋生成的或记录被清了）
            // 也检查一下白名单
            if (customName != null
                    && !customName.isEmpty()
                    && ac.peaceWhitelist.contains(customName)) {
                saved++;
             /*   plugin.getLogger().info("[和平] "
                        + ent.getType().name()
                        + " 命名[" + customName
                        + "] 无保护期但白名单匹配，保留");*/
                continue;
            }

            // 没有保护期且不在白名单，传送虚空
            Location voidLoc = ent.getLocation().clone();
            voidLoc.setY(ent.getWorld().getMinHeight() - 50);
            // 末影人受伤害会自动传送回来，直接移除
            if ("ENDERMAN".equals(ent.getType().name())) {
                ent.remove();
            } else {
                ent.teleport(voidLoc);
            }
            banished++;
           /* plugin.getLogger().info("[和平] "
                    + ent.getType().name()
                    + " 无保护期，命名=[" + customName + "] 传送虚空");*/
        }

     /*   plugin.getLogger().info("[和平] "
                + " 扫描=" + scanned
                + " 等待=" + waiting
                + " 保留=" + saved
                + " 传送=" + banished
                + " 白名单=" + ac.peaceWhitelist);*/
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
        // 1. 管理员权限
        if (isAreaAdmin(player)) {
            return PermissionLevel.ADMIN;
        }

        // 2. 领地所有者权限
        if (ac != null && ac.owner != null && !ac.owner.isEmpty()) {
            if (player.getName().equalsIgnoreCase(ac.owner)) {
                return PermissionLevel.OWNER;
            }
        }

        // 3. 访客权限（白名单 + 数据库购买权限）
        if (isPlayerWhitelisted(player.getName(), ac)) {
            return PermissionLevel.VISITOR;
        }

        // 4. 数据库购买的访客权限（未过期）
        if (hasValidVisitorPermission(player, ac)) {
            return PermissionLevel.VISITOR;
        }

        // 5. 无权限
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
     * 支持TAG和OP两种模式（不共存，按配置文件中的顺序优先）
     * 全局管理员不受访客权限限制
     */
    public boolean isAreaAdmin(Player player) {
        ConfigManager cfg = plugin.getConfigMgr();
        if (cfg == null) return player.isOp();

        String mode = cfg.areaProtectAdminMode;
        if ("op".equals(mode)) {
            // OP模式：只有OP是管理员
            return player.isOp();
        } else {
            // TAG模式（默认）：有指定Tag的玩家是管理员
            String tag = cfg.areaProtectAdminTag;
            if (tag == null || tag.isEmpty()) return player.isOp();
            String playerName = player.getName();
            // 检查玩家是否拥有该权限节点（作为Tag的替代方案）
            // 如果没有权限系统，直接检查名字是否匹配
            return player.hasPermission("area.admin")
                    || player.isOp(); // OP始终是管理员（fallback）
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
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

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
                if (newAc.enterMsg != null
                        && !newAc.enterMsg.isEmpty()) {
                    p.sendMessage(
                            formatAreaMsg(newAc.enterMsg));
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

    /**
     * 根据名称获取领地配置
     */
    public AreaConfig getLand(String name) {
        return areas.get(name);
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
     * 添加领地成员
     */
    public void addLandMember(String landName, String playerName) {
        Set<String> members = areaPlayerWhitelist.computeIfAbsent(
                landName, k -> ConcurrentHashMap.newKeySet());
        members.add(playerName.toLowerCase());
        saveWhitelists();
    }

    /**
     * 移除领地成员
     */
    public void removeLandMember(String landName, String playerName) {
        Set<String> members = areaPlayerWhitelist.get(landName);
        if (members != null) {
            members.remove(playerName.toLowerCase());
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
        if (ac != null && ac.denyBlockPlace) {
            // 访客禁止放置方块，所有者和管理员允许
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止放置方块");
            }
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
        if (ac != null && ac.denyBlockBreak) {
            // 访客禁止破坏方块，所有者和管理员允许
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止破坏方块");
            }
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
        // 保护性规则：对所有人无条件生效
        if (ac.denyAllDamage) { e.setCancelled(true); return; }
        if (ac.denyFallDamage
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
        if (ac != null && ac.denyPVP) {
            // 访客禁止PVP，所有者和管理员允许
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止PVP");
            }
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
        if (ac != null && ac.denyHunger)
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
        if (ac != null && ac.denyDrop) {
            if (!hasPermission(p, ac, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p.sendMessage("§c§l[区域防护] §f禁止丢弃物品");
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
        if (hand.getType() == Material.BREEZE_ROD) {
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
                        pos2.put(uid, block.getLocation());
                        p.sendMessage("§a§l[防护] §f位置2: "
                                + block.getX() + ", "
                                + block.getY() + ", "
                                + block.getZ());
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

        // 末影珍珠禁止
        if (ac.denyEnderPearl
                && hand.getType() == Material.ENDER_PEARL) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用末影珍珠");
            return;
        }

        // 弓箭禁止
        if (ac.denyBow
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
        if (ac != null && ac.denyPotion) {
            if (!hasPermission(shooter, ac, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                shooter.sendMessage("§c§l[区域防护] §f禁止使用药水");
            }
        }
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
                "list", "listitem", "创建", "删除", "重载",
                "工具", "expand", "contraction",
                "on", "off", "tempon", "modeexempt",
                "info", "setowner", "addvisitor", "removevisitor",
                "listvisitors", "transfer", "shop"
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
            showHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
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
            ItemStack tool = new ItemStack(WAND);
            ItemMeta meta = tool.getItemMeta();
            meta.setDisplayName("§a§l区域选择工具");
            meta.setLore(Arrays.asList(
                    "§7左键: 位置1", "§7右键: 位置2"));
            tool.setItemMeta(meta);
            p.getInventory().addItem(tool);
            p.sendMessage("§a§l[防护] §f已获取选择工具");
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

        // ===== 创建 =====
        if (sub.equals("创建") || sub.equals("create")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /protect 创建 <名>");
                return true;
            }
            Player p = (Player) sender;
            UUID u = p.getUniqueId();
            if (!pos1.containsKey(u)
                    || !pos2.containsKey(u)) {
                p.sendMessage("§c请先用工具选点");
                return true;
            }
            String areaName = args[1];
            // 检查重名（数据库+txt）
            if (areas.containsKey(areaName)) {
                p.sendMessage("§c区域已存在");
                return true;
            }
            Location l1 = pos1.get(u);
            Location l2 = pos2.get(u);

            // ★ 创建AreaConfig并保存到数据库
            AreaConfig ac = new AreaConfig();
            ac.name = areaName;
            ac.owner = p.getName();
            ac.world = l1.getWorld().getName();
            ac.x1 = l1.getBlockX();
            ac.z1 = l1.getBlockZ();
            ac.x2 = l2.getBlockX();
            ac.z2 = l2.getBlockZ();
            ac.yMin = 0;
            ac.yMax = 255;
            saveAreaToDb(ac);

            // 兼容：同时创建txt文件
            File f = new File(rootDir, areaName + ".txt");
            try {
                PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(f),
                                StandardCharsets.UTF_8));
                pw.println("# 区域: " + areaName);
                pw.println("起点: " + l1.getWorld().getName()
                        + "," + l1.getBlockX()
                        + "," + l1.getBlockZ());
                pw.println("终点: " + l2.getWorld().getName()
                        + "," + l2.getBlockX()
                        + "," + l2.getBlockZ());
                pw.println("高度范围: 0-255");
                pw.println();
                pw.println("# 禁止放置方块");
                pw.println("# 禁止破坏方块");
                pw.println("# 禁止PVP");
                pw.println("# 效果: 夜视 1 999");
                pw.println("# 进入提示: 欢迎来到保护区");
                pw.println("# 离开提示: 已离开保护区");
                pw.close();
            } catch (IOException ignored) {}

            p.sendMessage("§a§l[防护] §f区域 "
                    + areaName + " 已创建 (owner: " + p.getName() + ")");
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
                saveAreaConfig(ac);
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
                saveAreaConfig(ac);
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
                saveAreaConfig(ac);
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
                saveAreaConfig(ac);
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

        // ===== 重载 =====
        if (sub.equals("重载") || sub.equals("reload")) {
            reload();
            sender.sendMessage("§a已重载 "
                    + areas.size() + " 个区域");
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
            saveAreaConfig(ac);
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
            saveAreaConfig(ac);
            p.sendMessage("§a§l[防护] §f区域 §e" + ac.name
                    + " §f向 §e" + dir + " §f收缩 §e"
                    + amount + "§f格");
            return true;
        }

        // ===== 删除 =====
        if (sub.equals("删除") || sub.equals("delete")) {
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /protect 删除 <名>");
                return true;
            }
            String areaName = args[1];
            // 从数据库删除
            deleteAreaFromDb(areaName);
            // 从内存映射移除
            areas.remove(areaName);
            // 删除txt文件（如果存在）
            File f = new File(rootDir, areaName + ".txt");
            if (f.exists()) {
                f.delete();
            }
            sender.sendMessage("§a已删除: " + areaName);
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
            if (args.length < 3) {
                sender.sendMessage("§e用法: /protect setowner <领地> <玩家>");
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
            sender.sendMessage("§a已设置 §e" + areaName + " §a的所有者为 §e" + args[2]);
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
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /protect addvisitor <玩家>");
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
            addPlayerToAreaWhitelist(areaName, playerName);
            sender.sendMessage("§a已添加 §e" + playerName + " §a为 §e" + areaName + " §a的访客");
            return true;
        }

        // ===== removevisitor 移除访客 =====
        if (sub.equals("removevisitor")) {
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

        // ===== transfer 转让领地 =====
        if (sub.equals("transfer")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            if (args.length < 3) {
                sender.sendMessage("§e用法: /protect transfer <领地> <新所有者>");
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
            String oldOwner = ac.owner;
            ac.owner = args[2];
            saveAreaToDb(ac);
            sender.sendMessage("§a已将 §e" + areaName + " §a从 §e" + (oldOwner != null ? oldOwner : "无") + " §a转让给 §e" + args[2]);
            return true;
        }

        showHelp(sender);
        return true;
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
                    + "deny_all_effects, deny_item_frame, peace_mode, peace_mode_duration, "
                    + "peace_whitelist, enforce_game_mode, mode_exempt, enter_msg, leave_msg, "
                    + "confiscate_msg, enable_announce, announce_template, created_at) "
                    + "VALUES (?, '', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");

            stmt.setString(1, ac.name);
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
            stmt.setInt(22, ac.denyDrop ? 1 : 0);
            stmt.setInt(23, ac.denyMount ? 1 : 0);
            stmt.setInt(24, ac.denyEnderPearl ? 1 : 0);
            stmt.setInt(25, ac.denyBow ? 1 : 0);
            stmt.setInt(26, ac.denyPotion ? 1 : 0);
            stmt.setInt(27, ac.denyExplosion ? 1 : 0);
            stmt.setInt(28, ac.denyRaid ? 1 : 0);
            stmt.setInt(29, ac.denyFireSpread ? 1 : 0);
            stmt.setInt(30, ac.denyAllEffects ? 1 : 0);
            stmt.setInt(31, ac.denyItemFrame ? 1 : 0);
            stmt.setInt(32, ac.peaceMode ? 1 : 0);
            stmt.setInt(33, ac.peaceModeDuration / 1000);
            stmt.setString(34, String.join(",", ac.peaceWhitelist));
            stmt.setString(35, ac.enforceGameMode != null ? ac.enforceGameMode : "");
            stmt.setString(36, String.join(",", ac.modeExempt));
            stmt.setString(37, ac.enterMsg);
            stmt.setString(38, ac.leaveMsg);
            stmt.setString(39, ac.confiscateMsg);
            stmt.setInt(40, ac.enableAnnounce ? 1 : 0);
            stmt.setString(41, ac.announceTemplate);
            stmt.setLong(42, System.currentTimeMillis() / 1000);
            stmt.executeUpdate();
            stmt.close();
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
            ResultSet rs = stmt.executeQuery("SELECT id, name, owner, world, x1, z1, x2, z2, y_min, y_max, created_at FROM area_lands");
            while (rs.next()) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", rs.getInt("id"));
                map.put("name", rs.getString("name"));
                map.put("owner", rs.getString("owner"));
                map.put("world", rs.getString("world"));
                map.put("x1", rs.getInt("x1"));
                map.put("z1", rs.getInt("z1"));
                map.put("x2", rs.getInt("x2"));
                map.put("z2", rs.getInt("z2"));
                map.put("y_min", rs.getInt("y_min"));
                map.put("y_max", rs.getInt("y_max"));
                int x1 = rs.getInt("x1"), x2 = rs.getInt("x2");
                int z1 = rs.getInt("z1"), z2 = rs.getInt("z2");
                map.put("area_size", Math.abs((x2 - x1 + 1) * (z2 - z1 + 1)));
                map.put("created_at", rs.getLong("created_at"));
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
    private int getLandIdFromDb(String name) {
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
     * 格式化时长（秒 → 可读字符串）
     */
    private String formatDuration(int seconds) {
        if (seconds >= 86400) return (seconds / 86400) + "天";
        if (seconds >= 3600) return (seconds / 3600) + "小时";
        if (seconds >= 60) return (seconds / 60) + "分钟";
        return seconds + "秒";
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

    private void showHelp(CommandSender s) {
        s.sendMessage("§e§l==== 区域防护 ====");
        s.sendMessage("§a/protect menu §7打开GUI菜单");
        s.sendMessage("§a/protect 工具 §7选地工具");
        s.sendMessage("§a/protect 创建 <名> §7创建区域");
        s.sendMessage("§a/protect 列表 §7区域列表");
        s.sendMessage("§a/protect 重载 §7重载配置");
        s.sendMessage("§a/protect 删除 <名> §7删除区域");
        s.sendMessage("§a/protect info §7当前领地信息");
        s.sendMessage("§a/protect add [区域] <玩家> §7玩家加白");
        s.sendMessage("§a/protect remove [区域] <玩家> §7玩家删白");
        s.sendMessage("§a/protect additem [区域] <物品> §7物品加黑");
        s.sendMessage("§a/protect removeitem [区域] <物品> §7物品删黑");
        s.sendMessage("§a/protect on §7显示边框");
        s.sendMessage("§a/protect off §7关闭边框");
        s.sendMessage("§a/protect tempon §7临时边框(15秒)");
        s.sendMessage("§a/protect list §7列出全局白名单");
        s.sendMessage("§a/protect list <区域> §7列出区域白名单");
        s.sendMessage("§a/protect listitem §7列出全局物品黑名单");
        s.sendMessage("§a/protect listitem <区域> §7列出区域物品黑名单");
        s.sendMessage("§e§l---- 权限管理 ----");
        s.sendMessage("§a/protect setowner <领地> <玩家> §7设置所有者");
        s.sendMessage("§a/protect addvisitor [领地] <玩家> §7添加访客");
        s.sendMessage("§a/protect removevisitor [领地] <玩家> §7移除访客");
        s.sendMessage("§a/protect listvisitors [领地] §7列出访客");
        s.sendMessage("§a/protect transfer <领地> <新所有者> §7转让领地");
        s.sendMessage("§e§l---- 权限商店 ----");
        s.sendMessage("§a/protect shop create <领地> <价格> [时长秒] §7上架权限");
        s.sendMessage("§a/protect shop remove <ID> §7下架权限");
        s.sendMessage("§a/protect shop list §7查看在售权限");
        s.sendMessage("§a/protect shop buy <ID> §7购买权限");
        s.sendMessage("§a/protect shop my §7查看我的权限");
        s.sendMessage("§b§l欢迎游玩草原探险服务器");
        s.sendMessage("§b§l服务器ip：mc2.ypshidifu.cn\n端口30679");
        s.sendMessage("");
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
        boolean ok = wl != null && wl.remove(playerName);
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

        if (typeName.equals("ITEM_FRAME")
                || typeName.equals("GLOW_ITEM_FRAME")) {
            if (!(e.getDamager() instanceof Player)) return;
            Player p = (Player) e.getDamager();

            // ★ 统一白名单检查（globalPlayerWhitelist + areaPlayerWhitelist）
            AreaConfig ac = findFrameArea(entity);
            if (ac == null || !ac.denyItemFrame) return;

            if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== PVP 逻辑 =====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p2 = (Player) e.getEntity();
        AreaConfig ac2 = getArea(
                p2.getWorld().getName(),
                p2.getLocation().getBlockX(),
                p2.getLocation().getBlockY(),
                p2.getLocation().getBlockZ());
        if (ac2 != null && ac2.denyPVP) {
            if (!hasPermission(p2, ac2, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p2.sendMessage("§c§l[区域防护] §f禁止PVP");
            }
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

        // ★ 使用统一的权限检查方法
        AreaConfig ac = findFrameArea(clicked);
        if (ac == null || !ac.denyItemFrame) return;

        // 所有者+管理员跳过
        if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

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

            // ★ 使用统一的权限检查方法
            if (hasPermission(p, ac, PermissionLevel.OWNER)) return;

            if (!ac.denyItemFrame) return;

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== PVP 逻辑（原有的，不动）=====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p2 = (Player) e.getEntity();
        AreaConfig ac2 = getArea(
                p2.getWorld().getName(),
                p2.getLocation().getBlockX(),
                p2.getLocation().getBlockY(),
                p2.getLocation().getBlockZ());
        if (ac2 != null && ac2.denyPVP) {
            if (!hasPermission(p2, ac2, PermissionLevel.OWNER)) {
                e.setCancelled(true);
                p2.sendMessage("§c§l[区域防护] §f禁止PVP");
            }
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

        // ★ 使用统一的权限检查方法
        AreaConfig ac = findFrameArea(hit);
        if (ac == null || !ac.denyItemFrame) return;

        if (hasPermission(shooter, ac, PermissionLevel.OWNER)) return;

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
        if (p == null) return; // 非玩家点燃的（闪电等）
        Block block = e.getBlock();
        AreaConfig ac = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (ac != null && ac.denyFireSpread) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f此区域禁止点燃物品");
        }
    }

    // ===== 区域内方块着火（TNT、岩浆等）也阻止 =====
    @EventHandler
    public void onBlockBurn(BlockBurnEvent e) {
        Block block = e.getBlock();
        AreaConfig ac = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (ac != null && ac.denyFireSpread)
            e.setCancelled(true);
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
        public boolean denyPVP = false;
        public boolean denyFallDamage = false;
        public boolean denyHunger = false;
        public boolean denyAllDamage = false;
        public boolean denyMount = false;
        public boolean denyDrop = false;
        public boolean denyEnderPearl = false;
        public boolean denyBow = false;
        public boolean denyPotion = false;
        public boolean denyExplosion = false;
        public boolean denyMove = false;
        public boolean denyPickup = false;
        public boolean denyFire = false;
        public boolean enableAnnounce = false;
        public String announceTemplate = "";
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
            if (denyDrop) c++;
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
            if (!peaceWhitelist.isEmpty()) c++;

            return c;
        }
    }
    }