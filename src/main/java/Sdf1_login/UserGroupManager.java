package Sdf1_login;

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
                if (cfg.name != null && !cfg.name.isEmpty()) {
                    groupConfigs.put(cfg.name, cfg);
                }
            }
            rs.close();
            st.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[UserGroup] loadGroupConfigs failed: " + e.getMessage());
        }

        plugin.getLogger().info("[UserGroup] 已加载 " + groupConfigs.size() + " 个用户组");
    }

    /** 保存组配置到DB */
    public void saveGroupConfigToDB(UserGroupConfig cfg) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_config "
                            + "(group_name, display_name, display_color,"
                            + " priority, land_price_per_sqm, max_lands, default_perms)"
                            + " VALUES (?,?,?,?,?,?,?)");
            ps.setString(1, cfg.name);
            ps.setString(2, cfg.displayName);
            ps.setString(3, cfg.displayColor);
            ps.setInt(4, cfg.priority);
            ps.setInt(5, cfg.landPricePerSqm);
            ps.setInt(6, cfg.maxLands);
            ps.setString(7, cfg.defaultPerms != null ? cfg.defaultPerms : "{}");
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[UserGroup] saveGroupConfigToDB failed: " + e.getMessage());
        }
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

    public boolean addPlayer(String player, String groupName, String addedBy) {
        UserGroupConfig cfg = groupConfigs.get(groupName);
        if (cfg == null) return false;
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
            return false;
        }
        return true;
    }

    public boolean removePlayer(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            int rows = ps.executeUpdate();
            ps.close();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
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

    /** 获取组配置（返回副本） */
    public UserGroupConfig getGroupConfig(String groupName) {
        return groupConfigs.get(groupName);
    }

    /** 获取全部组列表（含默认组） */
    public List<UserGroupConfig> getAllGroups() {
        List<UserGroupConfig> list = new ArrayList<>(groupConfigs.values());
        list.sort((a, b) -> b.priority - a.priority);
        return list;
    }

    // ==================== 成员查询 ====================

    /**
     * 获取指定用户组的所有成员
     */
    public List<Map<String, Object>> getGroupMembers(String groupName) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, added_by, added_time FROM user_group_member WHERE group_name=?");
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
    }
}
