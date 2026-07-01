package Sdf1_login;

import org.bukkit.entity.Player;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户组管理器（重构版）
 * 负责：
 *   1. 用户组定义（名称、颜色、优先级）
 *   2. 领地相关参数：创建价格倍率、每人最大领地数、默认权限
 *   3. 玩家↔用户组关联（带过期时间）
 *   4. 给玩家发送展示名前缀（scoreboard tag）
 */
public class UserGroupManager {

    private final Main plugin;
    private Connection db;
    private final Map<String, UserGroupConfig> groupConfigs = new ConcurrentHashMap<>();

    // 默认组名（所有玩家都属于此组，不需要手动加入）
    public static final String DEFAULT_GROUP = "default";

    public UserGroupManager(Main plugin) {
        this.plugin = plugin;
        initDB();
        loadGroupConfigs();
    }

    // ==================== DB ====================

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "usergroup.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");

            // 主表：用户组配置
            st.execute("CREATE TABLE IF NOT EXISTS user_group_config ("
                    + "group_name TEXT PRIMARY KEY,"
                    + "display_name TEXT DEFAULT '',"
                    + "display_color TEXT DEFAULT '§f',"
                    + "display_emoji TEXT DEFAULT '',"
                    + "priority INTEGER DEFAULT 0,"
                    // === 领地相关 ===
                    + "land_price_per_sqm INTEGER DEFAULT -1,"   // -1 = 使用全局值
                    + "max_lands INTEGER DEFAULT -1,"             // -1 = 使用全局值
                    + "default_perms TEXT DEFAULT '{}',"          // JSON: 默认deny_*标志
                    + "is_permanent INTEGER DEFAULT 1,"           // 保留兼容
                    + "duration_minutes INTEGER DEFAULT 0"        // 保留兼容
                    + ")");

            // 玩家↔用户组关联
            st.execute("CREATE TABLE IF NOT EXISTS user_group_member ("
                    + "player_name TEXT NOT NULL,"
                    + "group_name TEXT NOT NULL,"
                    + "added_by TEXT DEFAULT 'system',"
                    + "added_time INTEGER DEFAULT 0,"
                    + "expiry_time INTEGER DEFAULT 0,"
                    + "PRIMARY KEY(player_name, group_name))");

            // === 迁移：给旧表加新列 ===
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN land_price_per_sqm INTEGER DEFAULT -1"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN max_lands INTEGER DEFAULT -1"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN default_perms TEXT DEFAULT '{}'"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN is_permanent INTEGER DEFAULT 1"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN duration_minutes INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // Home相关
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN home_limit INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // 付费相关
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN join_price INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN auto_renew INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE user_group_config ADD COLUMN renew_price INTEGER DEFAULT 0"); } catch (Exception ignored) {}
            // duration_minutes已有(旧列)，复用

            st.close();
        } catch (Exception e) {
            throw new RuntimeException("[UserGroup] DB init failed: " + e.getMessage(), e);
        }
    }

    // ==================== 配置加载 ====================

    /** 从 usergroup.db 加载所有组配置到内存 */
    public void loadGroupConfigs() {
        groupConfigs.clear();
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery("SELECT * FROM user_group_config");
            while (rs.next()) {
                UserGroupConfig cfg = new UserGroupConfig();
                cfg.name = rs.getString("group_name");
                cfg.displayName = rs.getString("display_name");
                cfg.displayColor = rs.getString("display_color");
                cfg.priority = rs.getInt("priority");
                cfg.landPricePerSqm = rs.getInt("land_price_per_sqm");
                cfg.maxLands = rs.getInt("max_lands");
                cfg.defaultPerms = rs.getString("default_perms");
                cfg.homeLimit = rs.getInt("home_limit");
                cfg.joinPrice = rs.getInt("join_price");
                cfg.autoRenew = rs.getInt("auto_renew") == 1;
                cfg.renewPrice = rs.getInt("renew_price");
                cfg.durationMinutes = rs.getLong("duration_minutes");
                if (cfg.name != null && !cfg.name.isEmpty()) {
                    groupConfigs.put(cfg.name, cfg);
                }
            }
            rs.close();
            st.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[UserGroup] loadGroupConfigs failed: " + e.getMessage());
        }

        plugin.getLogger().fine("[UserGroup] 已加载 " + groupConfigs.size() + " 个用户组");
    }

    /** 保存组配置到DB */
    public void saveGroupConfigToDB(UserGroupConfig cfg) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_config "
                            + "(group_name, display_name, display_color,"
                            + " priority, land_price_per_sqm, max_lands, default_perms,"
                            + " home_limit, join_price, auto_renew, renew_price, duration_minutes)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)");
            ps.setString(1, cfg.name);
            ps.setString(2, cfg.displayName);
            ps.setString(3, cfg.displayColor);
            ps.setInt(4, cfg.priority);
            ps.setInt(5, cfg.landPricePerSqm);
            ps.setInt(6, cfg.maxLands);
            ps.setString(7, cfg.defaultPerms != null ? cfg.defaultPerms : "{}");
            ps.setInt(8, cfg.homeLimit);
            ps.setInt(9, cfg.joinPrice);
            ps.setInt(10, cfg.autoRenew ? 1 : 0);
            ps.setInt(11, cfg.renewPrice);
            ps.setLong(12, cfg.durationMinutes);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] saveGroupConfigToDB failed: " + e.getMessage());
        }
    }

    /** ★ 更新组配置（保存DB + 刷新内存） */
    public void updateGroupConfig(UserGroupConfig cfg) {
        saveGroupConfigToDB(cfg);
        groupConfigs.put(cfg.name, cfg);
    }

    /** 删除组配置 */
    public boolean deleteGroupConfig(String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement("DELETE FROM user_group_config WHERE group_name=?");
            ps.setString(1, groupName);
            int rows = ps.executeUpdate();
            ps.close();
            groupConfigs.remove(groupName);
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 玩家操作 ====================

    /** 玩家名格式校验：3-16字符，仅字母数字下划线 */
    private static final java.util.regex.Pattern MC_NAME_PATTERN = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private boolean isValidPlayerName(String name) {
        return name != null && MC_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * 添加玩家到用户组
     * @return null=成功, 非null=错误消息
     */
    public String addPlayer(String player, String groupName, String addedBy) {
        // ★ 校验玩家名格式
        if (!isValidPlayerName(player)) {
            String msg = "玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）";
            plugin.getLogger().warning("[UserGroup] addPlayer失败: " + msg + " \"" + player + "\"");
            return msg;
        }

        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) return "未找到用户组: " + groupName;
        groupName = cfg.name; // 使用DB中实际的组名（保留大小写）

        // ★ 校验玩家是否存在（查login.db）
        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr != null && !dbMgr.userExists(player)) {
            String msg = "玩家 " + player + " 尚未注册，请确认玩家名是否正确";
            plugin.getLogger().warning("[UserGroup] addPlayer失败: " + msg);
            return msg;
        }

        long now = System.currentTimeMillis();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_member "
                            + "(player_name, group_name, added_by, added_time, expiry_time)"
                            + " VALUES (?,?,?,?,0)");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ps.setString(3, addedBy);
            ps.setLong(4, now);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] addPlayer failed: " + e.getMessage());
            return "数据库错误: " + e.getMessage();
        }
        // ★ 推送到PHP
        pushMemberToPHP(player, groupName, "add");
        return null; // 成功
    }

    public boolean removePlayer(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM user_group_member WHERE player_name=? AND LOWER(group_name)=LOWER(?)");
            ps.setString(1, player);
            ps.setString(2, groupName);
            int rows = ps.executeUpdate();
            ps.close();
            if (rows > 0) {
                // ★ 推送到PHP
                UserGroupConfig cfg = getGroupConfig(groupName);
                pushMemberToPHP(player, cfg != null ? cfg.name : groupName, "remove");
            }
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 清除指定用户组的所有成员（本地操作，不推PHP）
     * 用于PHP→Java同步时先清后写
     */
    public int clearGroupMembers(String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM user_group_member WHERE LOWER(group_name)=LOWER(?)");
            ps.setString(1, groupName);
            int rows = ps.executeUpdate();
            ps.close();
            return rows;
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] clearGroupMembers failed: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 仅写入本地DB（不触发PHP推送，用于PHP→Java同步避免循环）
     */
    public boolean addPlayerLocal(String player, String groupName, String addedBy) {
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) return false;
        groupName = cfg.name;
        long now = System.currentTimeMillis();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_member "
                            + "(player_name, group_name, added_by, added_time, expiry_time)"
                            + " VALUES (?,?,?,?,0)");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ps.setString(3, addedBy);
            ps.setLong(4, now);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            return false;
        }
        return true;
    }

    /**
     * 添加玩家到用户组（带到期时间）
     * @param expiryTimeMillis 到期时间戳(ms), 0=永久
     * @return null=成功, 非null=错误消息
     */
    public String addPlayerWithExpiry(String player, String groupName, String addedBy, long expiryTimeMillis) {
        if (!isValidPlayerName(player)) {
            return "玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）";
        }
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) return "未找到用户组: " + groupName;
        groupName = cfg.name;

        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr != null && !dbMgr.userExists(player)) {
            return "玩家 " + player + " 尚未注册，请确认玩家名是否正确";
        }

        long now = System.currentTimeMillis();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_member "
                            + "(player_name, group_name, added_by, added_time, expiry_time)"
                            + " VALUES (?,?,?,?,?)");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ps.setString(3, addedBy);
            ps.setLong(4, now);
            ps.setLong(5, expiryTimeMillis);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] addPlayerWithExpiry failed: " + e.getMessage());
            return "数据库错误: " + e.getMessage();
        }
        pushMemberToPHP(player, groupName, "add");
        return null;
    }

    /**
     * 付费加入用户组
     * @return null=成功, 非null=错误消息
     */
    public String joinGroupByPrice(String player, String groupName) {
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) return "未找到用户组: " + groupName;
        groupName = cfg.name;

        if (cfg.joinPrice <= 0) return "该用户组不开放付费加入";

        // durationMinutes <= 0 表示永久（expiry_time=0）
        // 不再报错，允许购买永久用户组

        // 检查是否已在组内（有效期内）
        if (isPlayerInGroup(player, groupName)) {
            List<Map<String, Object>> groups = getPlayerGroups(player);
            long now = System.currentTimeMillis();
            for (Map<String, Object> g : groups) {
                if (groupName.equals(g.get("group_name"))) {
                    long expiry = (long) g.get("expiry_time");
                    if (expiry <= 0 || expiry > now) {
                        return "你已经是该用户组的成员" + (expiry > 0 ? "（到期: " + formatExpiry(expiry) + "）" : "（永久）");
                    }
                }
            }
        }

        // 扣费
        BondManager bondMgr = plugin.getBondManager();
        if (bondMgr == null) return "债券系统未初始化";

        int balance = bondMgr.getBonds(player);
        if (balance < cfg.joinPrice) {
            return "债券不足！需要 " + cfg.joinPrice + " 张，当前 " + balance + " 张";
        }

        boolean deducted = bondMgr.deductBonds(player, cfg.joinPrice,
                "group_join", "", "system",
                "付费加入用户组: " + groupName);
        if (!deducted) return "扣费失败，请稍后重试";

        // 计算到期时间
        long now = System.currentTimeMillis();
        long expiryTime;
        if (cfg.durationMinutes <= 0) {
            expiryTime = 0; // 永久
        } else {
            expiryTime = now + (long) cfg.durationMinutes * 60 * 1000;
        }

        // 加入组
        String err = addPlayerWithExpiry(player, groupName, "paid_join", expiryTime);
        if (err != null) {
            // 回退扣费
            bondMgr.addBonds(player, cfg.joinPrice, "group_join_refund", "", "system", "加入失败退款: " + groupName);
            return err;
        }

        plugin.getLogger().info("[UserGroup] " + player + " 付费加入用户组 " + groupName + "，扣费 " + cfg.joinPrice + " 张，到期: " + formatExpiry(expiryTime));
        return null;
    }

    /**
     * 续费用户组（延长到期时间）
     * @return null=成功, 非null=错误消息
     */
    public String renewGroup(String player, String groupName) {
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) return "未找到用户组: " + groupName;
        groupName = cfg.name;

        if (cfg.renewPrice <= 0) return "该用户组不支持续费";

        // 查找当前成员记录
        long currentExpiry = 0;
        boolean found = false;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT expiry_time FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                currentExpiry = rs.getLong("expiry_time");
                found = true;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            return "数据库错误: " + e.getMessage();
        }

        if (!found) return "你不在该用户组中，请先 /group buy " + groupName;

        // 如果已过期，不能续费，需要重新购买
        long now = System.currentTimeMillis();
        if (currentExpiry > 0 && currentExpiry <= now) {
            return "用户组已过期，请重新购买";
        }

        // 扣费
        BondManager bondMgr = plugin.getBondManager();
        if (bondMgr == null) return "债券系统未初始化";

        int balance = bondMgr.getBonds(player);
        if (balance < cfg.renewPrice) {
            return "债券不足！需要 " + cfg.renewPrice + " 张，当前 " + balance + " 张";
        }

        boolean deducted = bondMgr.deductBonds(player, cfg.renewPrice,
                "group_renew", "", "system",
                "续费用户组: " + groupName);
        if (!deducted) return "扣费失败，请稍后重试";

        // 延长到期时间：从当前到期时间往后延（如果已过期则从当前时间开始）
        long newExpiry;
        if (cfg.durationMinutes <= 0) {
            newExpiry = 0; // 永久
        } else {
            long baseTime = (currentExpiry > now) ? currentExpiry : now;
            newExpiry = baseTime + (long) cfg.durationMinutes * 60 * 1000;
        }

        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE user_group_member SET expiry_time=? WHERE player_name=? AND group_name=?");
            ps.setLong(1, newExpiry);
            ps.setString(2, player);
            ps.setString(3, groupName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            return "数据库错误: " + e.getMessage();
        }

        pushMemberToPHP(player, groupName, "add");
        plugin.getLogger().info("[UserGroup] " + player + " 续费用户组 " + groupName + "，扣费 " + cfg.renewPrice + " 张，新到期: " + formatExpiry(newExpiry));
        return null;
    }

    /**
     * 检查并移除已过期的玩家用户组（用于登录时调用）
     * @return 过期被移除的组列表 [{player, group}]
     */
    public List<Map<String, Object>> checkAndRemoveExpired() {
        List<Map<String, Object>> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, group_name FROM user_group_member WHERE expiry_time > 0 AND expiry_time <= ?");
            ps.setLong(1, now);
            ResultSet rs = ps.executeQuery();
            List<String[]> toRemove = new ArrayList<>();
            while (rs.next()) {
                String[] item = new String[]{rs.getString("player_name"), rs.getString("group_name")};
                toRemove.add(item);
            }
            rs.close();
            ps.close();

            for (String[] item : toRemove) {
                String player = item[0];
                String group = item[1];
                // 删除过期记录
                PreparedStatement del = db.prepareStatement(
                        "DELETE FROM user_group_member WHERE player_name=? AND group_name=?");
                del.setString(1, player);
                del.setString(2, group);
                del.executeUpdate();
                del.close();
                pushMemberToPHP(player, group, "remove");
                expired.add(Map.of("player", player, "group", group));
            }

            if (!expired.isEmpty()) {
                plugin.getLogger().info("[UserGroup] 自动清理 " + expired.size() + " 个过期用户组成员");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] checkAndRemoveExpired failed: " + e.getMessage());
        }
        return expired;
    }

    /**
     * 检查指定玩家的过期并移除
     */
    public List<String> checkAndRemoveExpiredForPlayer(String player) {
        List<String> expiredGroups = new ArrayList<>();
        long now = System.currentTimeMillis();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT group_name FROM user_group_member WHERE player_name=? AND expiry_time > 0 AND expiry_time <= ?");
            ps.setString(1, player);
            ps.setLong(2, now);
            ResultSet rs = ps.executeQuery();
            List<String> toRemove = new ArrayList<>();
            while (rs.next()) {
                toRemove.add(rs.getString("group_name"));
            }
            rs.close();
            ps.close();

            for (String group : toRemove) {
                PreparedStatement del = db.prepareStatement(
                        "DELETE FROM user_group_member WHERE player_name=? AND group_name=?");
                del.setString(1, player);
                del.setString(2, group);
                del.executeUpdate();
                del.close();
                pushMemberToPHP(player, group, "remove");
                expiredGroups.add(group);
            }

            if (!expiredGroups.isEmpty()) {
                plugin.getLogger().info("[UserGroup] " + player + " 过期用户组已清理: " + String.join(", ", expiredGroups));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] checkAndRemoveExpiredForPlayer failed: " + e.getMessage());
        }
        return expiredGroups;
    }

    /**
     * 自动续费（到期前自动扣费延长）
     * @return true=续费成功, false=续费失败或不需要
     */
    public boolean autoRenewGroup(String player, String groupName) {
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null || !cfg.autoRenew || cfg.renewPrice <= 0) return false;

        // 查当前到期时间
        long currentExpiry = 0;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT expiry_time FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                currentExpiry = rs.getLong("expiry_time");
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            return false;
        }

        // 只在即将到期时续费（10分钟内）
        long now = System.currentTimeMillis();
        if (currentExpiry <= 0 || currentExpiry > now + 10 * 60 * 1000) return false;

        // 扣费
        BondManager bondMgr = plugin.getBondManager();
        if (bondMgr == null) return false;

        int balance = bondMgr.getBonds(player);
        if (balance < cfg.renewPrice) {
            plugin.getLogger().info("[UserGroup] " + player + " 自动续费失败: 债券不足(" + balance + "/" + cfg.renewPrice + ")");
            return false;
        }

        boolean deducted = bondMgr.deductBonds(player, cfg.renewPrice,
                "group_auto_renew", "", "system",
                "自动续费用户组: " + groupName);
        if (!deducted) return false;

        long newExpiry = now + (long) cfg.durationMinutes * 60 * 1000;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE user_group_member SET expiry_time=? WHERE player_name=? AND group_name=?");
            ps.setLong(1, newExpiry);
            ps.setString(2, player);
            ps.setString(3, groupName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            return false;
        }

        pushMemberToPHP(player, groupName, "add");

        // 通知在线玩家
        Player onlinePlayer = plugin.getServer().getPlayerExact(player);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            onlinePlayer.sendMessage("§a用户组 §e" + (cfg.displayName.isEmpty() ? groupName : cfg.displayName) + " §a已自动续费！");
            onlinePlayer.sendMessage("§7新到期时间: §e" + formatExpiry(newExpiry));
        }
        return true;
    }

    /**
     * 获取玩家用户组到期时间
     * @return 到期时间戳(ms), 0=永久, -1=不在组内
     */
    public long getExpiryTime(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT expiry_time FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ResultSet rs = ps.executeQuery();
            long expiry = -1;
            if (rs.next()) {
                expiry = rs.getLong("expiry_time");
            }
            rs.close();
            ps.close();
            return expiry;
        } catch (SQLException e) {
            return -1;
        }
    }

    /**
     * 获取即将到期的玩家列表（10分钟内到期且开启自动续费的）
     * 用于定时器轮询提醒
     */
    public List<Map<String, Object>> getExpiringGroups() {
        List<Map<String, Object>> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        long tenMinLater = now + 10 * 60 * 1000;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT m.player_name, m.group_name, m.expiry_time, "
                            + "c.auto_renew, c.renew_price "
                            + "FROM user_group_member m "
                            + "LEFT JOIN user_group_config c ON m.group_name = c.group_name "
                            + "WHERE m.expiry_time > 0 AND m.expiry_time <= ? AND m.expiry_time > ?");
            ps.setLong(1, tenMinLater);
            ps.setLong(2, now);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("player", rs.getString("player_name"));
                row.put("group", rs.getString("group_name"));
                row.put("expiry", rs.getLong("expiry_time"));
                row.put("autoRenew", rs.getInt("auto_renew") == 1);
                row.put("renewPrice", rs.getInt("renew_price"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] getExpiringGroups failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * 格式化到期时间为可读字符串
     */
    private String formatExpiry(long expiryTimeMillis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(new java.util.Date(expiryTimeMillis));
    }

    /** 获取玩家所属的所有有效组（排除过期） */
    public List<Map<String, Object>> getPlayerGroups(String player) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM user_group_member WHERE player_name=?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            long now = System.currentTimeMillis();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group_name", rs.getString("group_name"));
                row.put("added_by", rs.getString("added_by"));
                long expiry = rs.getLong("expiry_time");
                row.put("expiry_time", expiry);
                row.put("expired", expiry > 0 && expiry <= now);
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 获取玩家最高优先级的有效用户组
     * 如果没有加入任何组，返回 null（调用方应使用全局默认值）
     */
    public UserGroupConfig getHighestGroup(String player) {
        List<Map<String, Object>> groups = getPlayerGroups(player);
        long now = System.currentTimeMillis();
        UserGroupConfig best = null;
        for (Map<String, Object> g : groups) {
            long expiry = (long) g.get("expiry_time");
            if (expiry > 0 && expiry <= now) continue;
            String name = (String) g.get("group_name");
            UserGroupConfig cfg = groupConfigs.get(name);
            if (cfg != null && (best == null || cfg.priority > best.priority)) {
                best = cfg;
            }
        }
        return best;
    }

    /**
     * 获取用户组的Home数量限制
     * @return 0=跟随全局, >0=独立限制, -1=无限
     */
    public int getHomeLimit(String groupName) {
        UserGroupConfig cfg = getGroupConfig(groupName);
        if (cfg == null) {
            return 0; // 默认跟随全局
        }
        return cfg.homeLimit;
    }

    public boolean isPlayerInGroup(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            boolean exists = ps.executeQuery().next();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 领地相关查询 ====================

    /**
     * 获取玩家的领地创建价格（每㎡）
     * 返回：组专属价格 或 全局默认
     */
    public int getPlayerLandPricePerSqm(String player, int globalDefault) {
        UserGroupConfig cfg = getHighestGroup(player);
        if (cfg == null) return globalDefault;
        return cfg.landPricePerSqm >= 0 ? cfg.landPricePerSqm : globalDefault;
    }

    /**
     * 获取玩家的最大领地持有数
     * 返回：组专属上限 或 全局默认
     */
    public int getPlayerMaxLands(String player, int globalDefault) {
        UserGroupConfig cfg = getHighestGroup(player);
        if (cfg == null) return globalDefault;
        return cfg.maxLands >= 0 ? cfg.maxLands : globalDefault;
    }

    /**
     * 获取玩家的默认领地权限 JSON
     * 返回：组默认权限 或 空JSON "{}"
     */
    public String getPlayerDefaultPerms(String player) {
        UserGroupConfig cfg = getHighestGroup(player);
        if (cfg == null || cfg.defaultPerms == null || cfg.defaultPerms.isEmpty()) {
            return "{}";
        }
        return cfg.defaultPerms;
    }

    // ==================== 工具方法 ====================

    public Map<String, UserGroupConfig> getGroupConfigs() {
        return groupConfigs;
    }

    /** 获取所有组名列表 */
    public Set<String> getAllGroupNames() {
        return groupConfigs.keySet();
    }

    /** 获取组配置（大小写不敏感） */
    public UserGroupConfig getGroupConfig(String groupName) {
        UserGroupConfig cfg = groupConfigs.get(groupName);
        if (cfg != null) return cfg;
        // 大小写不敏感查找
        for (UserGroupConfig c : groupConfigs.values()) {
            if (c.name.equalsIgnoreCase(groupName)) return c;
        }
        return null;
    }

    /** 获取全部组列表（含默认组） */
    public List<UserGroupConfig> getAllGroups() {
        List<UserGroupConfig> list = new ArrayList<>(groupConfigs.values());
        list.sort((a, b) -> b.priority - a.priority);
        return list;
    }

    // ==================== PHP同步 ====================

    /** 推送成员变更到PHP */
    private void pushMemberToPHP(String player, String groupName, String action) {
        try {
            WebManager wm = plugin.webManager;
            if (wm == null) return;
            String endpoint = "api/land_api.php";
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("action", action + "_group_member");
            params.put("player", player);
            params.put("group", groupName);
            params.put("added_by", "Java");
            params.put("secret", wm.getSecretKey());
            String resp = wm.httpGet(endpoint, params);
            if (resp != null && resp.contains("\"success\":false")) {
                plugin.getLogger().warning("[UserGroup] pushMemberToPHP PHP拒绝: " + resp);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[UserGroup] pushMemberToPHP failed: " + e.getMessage());
        }
    }

    // ==================== 成员查询 ====================

    /**
     * 获取指定用户组的所有成员
     */
    public List<Map<String, Object>> getGroupMembers(String groupName) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, added_by, added_time FROM user_group_member WHERE LOWER(group_name)=LOWER(?)");
            ps.setString(1, groupName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("player_name", rs.getString("player_name"));
                row.put("added_by", rs.getString("added_by"));
                row.put("added_time", rs.getLong("added_time"));
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ==================== 配置类 ====================

    public static class UserGroupConfig {
        public String name;
        public String displayName = "";
        public String displayColor = "§f";
        public int priority = 0;
        // 领地相关
        public int landPricePerSqm = -1;   // -1 = 使用全局
        public int maxLands = -1;          // -1 = 使用全局
        public String defaultPerms = "{}"; // JSON
        // Home相关
        public int homeLimit = 0;          // 0=跟随全局, >0=独立限制, -1=无限
        // 付费相关
        public int joinPrice = 0;          // 加入价格(债券), 0=不开放付费
        public boolean autoRenew = false;  // 是否自动续费
        public int renewPrice = 0;         // 续费价格(债券)
        public long durationMinutes = 0;   // 有效时长(分钟), 0=永久
    }
}
