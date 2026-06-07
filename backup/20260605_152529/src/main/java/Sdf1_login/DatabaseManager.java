package Sdf1_login;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public class DatabaseManager {

    private Connection db;
    private final File dataFolder;
    private final Logger logger;

    public DatabaseManager(File dataFolder) {
        this.dataFolder = dataFolder;
        this.logger = Logger.getLogger("Sdf1_login");
    }

    // ==================== 初始化 ====================

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(dataFolder,
                    "login.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:"
                            + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");

            // 用户表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "users ("
                    + "player_name TEXT PRIMARY KEY,"
                    + "password_hash TEXT NOT NULL,"
                    + "password_salt TEXT NOT NULL,"
                    + "email TEXT DEFAULT '',"
                    + "register_time INTEGER DEFAULT 0,"
                    + "last_login_time INTEGER DEFAULT 0,"
                    + "total_online_time INTEGER DEFAULT 0,"
                    + "last_online_check INTEGER DEFAULT 0,"
                    + "is_logged_in INTEGER DEFAULT 0,"
                    + "blocks_broken INTEGER DEFAULT 0,"
                    + "blocks_placed INTEGER DEFAULT 0,"
                    + "gift_stage INTEGER DEFAULT 0,"
                    + "gift_claimed TEXT DEFAULT '',"
                    + "invite_code TEXT DEFAULT '',"
                    + "invited_by TEXT DEFAULT '',"
                    + "monthly_invite_count INTEGER DEFAULT 0,"
                    + "invite_month TEXT DEFAULT '',"
                    + "points INTEGER DEFAULT 0,"
                    + "checkin_streak INTEGER DEFAULT 0,"
                    + "last_checkin_date TEXT DEFAULT '',"
                    + "total_checkin_days INTEGER DEFAULT 0,"
                    + "temp_password TEXT DEFAULT '',"
                    + "temp_pw_expire INTEGER DEFAULT 0,"
                    + "temp_pw_used INTEGER DEFAULT 0,"
                    + "ip_address TEXT DEFAULT '',"
                    + "tasks_completed TEXT DEFAULT '',"
                    + "premium_uuid TEXT DEFAULT '',"
                    + "register_ip TEXT DEFAULT '',"
                    + "register_type TEXT DEFAULT 'manual',"
                    + "backcheck_dates TEXT DEFAULT ''"
                    + ")");


            // 安全添加 users 缺失列
            safeAdd(st, "tasks_completed",
                    "TEXT DEFAULT ''");
            safeAdd(st, "temp_password",
                    "TEXT DEFAULT ''");
            safeAdd(st, "temp_pw_expire",
                    "INTEGER DEFAULT 0");
            safeAdd(st, "temp_pw_used",
                    "INTEGER DEFAULT 0");
            safeAdd(st, "ip_address",
                    "TEXT DEFAULT ''");
            safeAdd(st, "premium_uuid",
                    "TEXT DEFAULT ''");
            safeAdd(st, "register_ip",
                    "TEXT DEFAULT ''");
            safeAdd(st, "register_type",
                    "TEXT DEFAULT 'manual'");

            // ===== 背包备份表：安全迁移 =====
            // 检查旧表是否存在（用 inventory_data 列判断）
            boolean hasOldTable = false;
            try {
                ResultSet rs = st.executeQuery(
                        "SELECT inventory_data "
                                + "FROM inventory_backups "
                                + "LIMIT 1");
                hasOldTable = true;
                rs.close();
            } catch (SQLException e) {
                hasOldTable = false;
            }

            if (hasOldTable) {
                // 旧表存在，迁移数据到新表
                st.execute("CREATE TABLE IF NOT EXISTS "
                        + "inventory_backups_new ("
                        + "id INTEGER PRIMARY KEY "
                        + "AUTOINCREMENT,"
                        + "player_name TEXT NOT NULL,"
                        + "contents_b64 TEXT DEFAULT '',"
                        + "armor_b64 TEXT DEFAULT '',"
                        + "extra_b64 TEXT DEFAULT '',"
                        + "level INTEGER DEFAULT 0,"
                        + "experience REAL DEFAULT 0,"
                        + "save_time INTEGER DEFAULT 0"
                        + ")");
                // 迁移旧数据
                try {
                    st.execute("INSERT INTO "
                            + "inventory_backups_new "
                            + "(player_name, "
                            + "contents_b64, "
                            + "armor_b64, "
                            + "extra_b64, "
                            + "level, "
                            + "experience) "
                            + "SELECT player_name, "
                            + "inventory_data, "
                            + "armor_data, "
                            + "extra_data, "
                            + "player_level, "
                            + "player_exp "
                            + "FROM inventory_backups");
                } catch (SQLException ignored) {
                }
                st.execute("DROP TABLE "
                        + "inventory_backups");
                st.execute("ALTER TABLE "
                        + "inventory_backups_new "
                        + "RENAME TO "
                        + "inventory_backups");
            } else {
                // 无旧表或已迁移，直接创建
                st.execute("CREATE TABLE IF NOT EXISTS "
                        + "inventory_backups ("
                        + "id INTEGER PRIMARY KEY "
                        + "AUTOINCREMENT,"
                        + "player_name TEXT NOT NULL,"
                        + "contents_b64 TEXT DEFAULT '',"
                        + "armor_b64 TEXT DEFAULT '',"
                        + "extra_b64 TEXT DEFAULT '',"
                        + "level INTEGER DEFAULT 0,"
                        + "experience REAL DEFAULT 0,"
                        + "save_time INTEGER DEFAULT 0"
                        + ")");
            }

            // 安全报警表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "security_alerts ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "player_name TEXT NOT NULL,"
                    + "alert_ip TEXT NOT NULL,"
                    + "alert_time INTEGER NOT NULL,"
                    + "alert_date TEXT NOT NULL,"
                    + "notified INTEGER DEFAULT 0,"
                    + "notified_time INTEGER DEFAULT 0)");
            // PVP区域表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "pvp_regions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE,"
                    + "world TEXT NOT NULL,"
                    + "x1 INTEGER NOT NULL,"
                    + "y1 INTEGER NOT NULL,"
                    + "z1 INTEGER NOT NULL,"
                    + "x2 INTEGER NOT NULL,"
                    + "y2 INTEGER NOT NULL,"
                    + "z2 INTEGER NOT NULL,"
                    + "create_time INTEGER DEFAULT 0"
                    + ")");

// PVP击杀总榜表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "pvp_kills ("
                    + "player_name TEXT NOT NULL,"
                    + "region_name TEXT NOT NULL,"
                    + "kills INTEGER DEFAULT 0,"
                    + "deaths INTEGER DEFAULT 0,"
                    + "PRIMARY KEY(player_name, region_name)"
                    + ")");


// 菜单图标表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "menu_icons ("
                    + "player_name TEXT PRIMARY KEY,"
                    + "icon_b64 TEXT DEFAULT '',"
                    + "item_name TEXT DEFAULT '',"
                    + "save_time INTEGER DEFAULT 0"
                    + ")");

            // 工单表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "tickets ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "type TEXT NOT NULL,"
                    + "status TEXT DEFAULT 'pending',"
                    + "priority TEXT DEFAULT 'normal',"
                    + "requester TEXT NOT NULL,"
                    + "assigned_to TEXT DEFAULT '',"
                    + "title TEXT DEFAULT '',"
                    + "description TEXT DEFAULT '',"
                    + "reward_amount INTEGER DEFAULT 0,"
                    + "score INTEGER DEFAULT 0,"
                    + "target_player TEXT DEFAULT '',"
                    + "create_time INTEGER NOT NULL,"
                    + "update_time INTEGER DEFAULT 0,"
                    + "complete_time INTEGER DEFAULT 0,"
                    + "admin_confirmed INTEGER DEFAULT 0,"
                    + "reject_reason TEXT DEFAULT ''"
                    + ")");

            // 工单回复表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "ticket_replies ("
                    + "id INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT,"
                    + "ticket_id INTEGER NOT NULL,"
                    + "sender TEXT NOT NULL,"
                    + "message TEXT NOT NULL,"
                    + "create_time INTEGER NOT NULL"
                    + ")");

            // 服务商表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "service_providers ("
                    + "player_name TEXT PRIMARY KEY,"
                    + "role TEXT DEFAULT 'waiter',"
                    + "active INTEGER DEFAULT 1,"
                    + "join_time INTEGER DEFAULT 0"
                    + ")");
            // 垃圾回收站物品表
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "items ("
                    + "id INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT,"
                    + "player_name TEXT NOT NULL,"
                    + "item_data TEXT DEFAULT '',"
                    + "amount INTEGER DEFAULT 1,"
                    + "create_time INTEGER DEFAULT 0"
                    + ")");
            safeAdd(st, "menu_snowball",
                    "INTEGER DEFAULT 1");
            st.close();
            logger.info("[Sdf1_login] 数据库初始化完成");
        } catch (Exception e) {
            throw new RuntimeException(
                    "[Sdf1_login] DB初始化失败: "
                            + e.getMessage(), e);
        }
    }


    private void safeAdd(Statement st,
                         String col, String def) {
        try {
            st.executeUpdate("ALTER TABLE users "
                    + "ADD COLUMN " + col + " " + def);
        } catch (SQLException ignored) {
        }
    }

    public void close() {
        try {
            if (db != null && !db.isClosed())
                db.close();
        } catch (Exception ignored) {
        }
    }
// ==================== 菜单图标 ====================

    public void saveMenuIcon(String name,
                             String iconB64, String itemName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO menu_icons "
                            + "(player_name, icon_b64, "
                            + "item_name, save_time) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, iconB64);
            ps.setString(3, itemName);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getMenuIcon(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT icon_b64 FROM menu_icons "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            String b64 = null;
            if (rs.next()) {
                b64 = rs.getString("icon_b64");
            }
            rs.close();
            ps.close();
            return b64;
        } catch (SQLException e) {
            return null;
        }
    }

    public void deleteMenuIcon(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM menu_icons "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean hasMenuIcon(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM menu_icons "
                            + "WHERE player_name=? "
                            + "AND icon_b64 IS NOT NULL "
                            + "AND icon_b64 != '' "
                            + "LIMIT 1");
            ps.setString(1, name);
            boolean r = ps.executeQuery().next();
            ps.close();
            return r;
        } catch (SQLException e) {
            return false;
        }
    }

    // ==================== 用户操作 ====================

    public boolean userExists(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            boolean r = ps.executeQuery().next();
            ps.close();
            return r;
        } catch (SQLException e) {
            return false;
        }
    }

    public void createUser(String name, String hash,
                           String salt) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO users "
                            + "(player_name, "
                            + "password_hash, "
                            + "password_salt, "
                            + "register_time) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ========== 临时密码管理 ==========

    public void clearTempPassword(String name) {
        setField(name, "temp_password", null);
        setField(name, "temp_pw_expire", 0);
        setField(name, "temp_pw_used", 1);
    }

    public void clearAllTempPasswords() {
        try {
            Statement st = db.createStatement();
            st.executeUpdate(
                    "UPDATE users SET "
                            + "temp_password=NULL,"
                            + "temp_pw_expire=0,"
                            + "temp_pw_used=1 "
                            + "WHERE temp_password "
                            + "IS NOT NULL");
            st.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String getPlayerIPByName(
            String name) {
        Object ip = getField(name, "ip");
        return ip != null
                ? String.valueOf(ip) : null;
    }

    public boolean checkPasswordOrTemp(
            String name, String hash) {
        // 先检查主密码
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT password_hash "
                            + "FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String mainHash =
                        rs.getString("password_hash");
                rs.close();
                ps.close();
                if (hash.equals(mainHash))
                    return true;
            } else {
                rs.close();
                ps.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        // 再检查临时密码
        try {
            PreparedStatement ps2 = db.prepareStatement(
                    "SELECT temp_password, "
                            + "temp_pw_expire, "
                            + "temp_pw_used "
                            + "FROM users "
                            + "WHERE player_name=?");
            ps2.setString(1, name);
            ResultSet rs2 = ps2.executeQuery();
            if (rs2.next()) {
                String tempHash =
                        rs2.getString(
                                "temp_password");
                long expire =
                        rs2.getLong(
                                "temp_pw_expire");
                int used =
                        rs2.getInt(
                                "temp_pw_used");
                rs2.close();
                ps2.close();
                if (tempHash != null
                        && !tempHash.isEmpty()
                        && hash.equals(tempHash)
                        && System.currentTimeMillis()
                        < expire
                        && used != 1) {
                    return true;
                }
            } else {
                rs2.close();
                ps2.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }


    public Map<String, Object> getUser(String name) {
        Map<String, Object> r =
                new LinkedHashMap<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    r.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return r;
    }

    public void setField(String name, String field,
                         Object val) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE users SET " + field
                            + "=? "
                            + "WHERE player_name=?");
            if (val instanceof Integer)
                ps.setInt(1, (Integer) val);
            else if (val instanceof Long)
                ps.setLong(1, (Long) val);
            else
                ps.setString(1,
                        String.valueOf(val));
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Object getField(String name,
                           String field) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT " + field
                            + " FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            Object val = rs.next()
                    ? rs.getObject(1) : null;
            rs.close();
            ps.close();
            return val;
        } catch (SQLException e) {
            return null;
        }
    }

    public boolean checkPassword(String name,
                                 String hash) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT password_hash "
                            + "FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String stored =
                        rs.getString("password_hash");
                rs.close();
                ps.close();
                return hash.equals(stored);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }



    public boolean isUsingTempPassword(String name) {
        String h = (String) getField(name,
                "temp_password");
        return h != null && !h.isEmpty();
    }

    public void setLoggedIn(String name,
                            boolean val) {
        setField(name, "is_logged_in",
                val ? 1 : 0);
    }

    public void addPoints(String name, int amount) {
        Object cur = getField(name, "points");
        int curVal = cur instanceof Number
                ? ((Number) cur).intValue() : 0;
        setField(name, "points", curVal + amount);
    }

    public boolean deductPoints(String name,
                                int amount) {
        Object cur = getField(name, "points");
        int curVal = cur instanceof Number
                ? ((Number) cur).intValue() : 0;
        if (curVal < amount) return false;
        setField(name, "points",
                curVal - amount);
        return true;
    }
    public List<Map<String, Object>>
    getAllUsers() {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT * FROM users");
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            st.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
    public List<Map<String, Object>>
    getTicketsByProviderAll(
            String provider) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE assigned_to=? "
                            + "ORDER BY create_time DESC");
            ps.setString(1, provider);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public void deleteUser(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void recordIP(String name, String ip) {
        if (ip != null && !ip.isEmpty()) {
            setField(name, "ip_address", ip);
        }
    }

    // ==================== IP查询 ====================

    /**
     * 统计指定IP注册的账号数量
     */
    public int countIPAccounts(String ip) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT COUNT(*) FROM users "
                            + "WHERE ip_address=?");
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            int count = 0;
            if (rs.next()) count = rs.getInt(1);
            rs.close();
            ps.close();
            return count;
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 获取指定IP下所有账号详情
     */
    public List<Map<String, Object>>
    getAccountDetailsByIP(String ip) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, "
                            + "register_time "
                            + "FROM users "
                            + "WHERE ip_address=? "
                            + "ORDER BY register_time");
            ps.setString(1, ip);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("player_name",
                        rs.getString("player_name"));
                row.put("register_time",
                        rs.getLong("register_time"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ==================== 背包备份 ====================

    public void saveInventoryBackup(String name,
                                    String contents, String armor,
                                    String extra, int level,
                                    double experience) {
        try {
            PreparedStatement del =
                    db.prepareStatement(
                            "DELETE FROM "
                                    + "inventory_backups "
                                    + "WHERE player_name=?");
            del.setString(1, name);
            del.executeUpdate();
            del.close();
            PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO "
                                    + "inventory_backups"
                                    + " (player_name, "
                                    + "contents_b64, "
                                    + "armor_b64, "
                                    + "extra_b64, "
                                    + "level, "
                                    + "experience, "
                                    + "save_time) "
                                    + "VALUES "
                                    + "(?,?,?,?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, contents);
            ps.setString(3, armor);
            ps.setString(4, extra);
            ps.setInt(5, level);
            ps.setDouble(6, experience);
            ps.setLong(7,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public String[] loadInventoryBackup(
            String name) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT contents_b64, "
                                    + "armor_b64, "
                                    + "extra_b64, "
                                    + "level, "
                                    + "experience "
                                    + "FROM "
                                    + "inventory_backups "
                                    + "WHERE player_name=? "
                                    + "ORDER BY save_time "
                                    + "DESC LIMIT 1");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String[] result =
                        new String[5];
                result[0] = rs.getString(
                        "contents_b64");
                result[1] = rs.getString(
                        "armor_b64");
                result[2] = rs.getString(
                        "extra_b64");
                result[3] = String.valueOf(
                        rs.getInt("level"));
                result[4] = String.valueOf(
                        rs.getDouble("experience"));
                rs.close();
                ps.close();
                return result;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteInventoryBackup(
            String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM inventory_backups "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean hasInventoryBackup(
            String name) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT id FROM "
                                    + "inventory_backups "
                                    + "WHERE player_name=? "
                                    + "LIMIT 1");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            boolean has = rs.next();
            rs.close();
            ps.close();
            return has;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
// ==================== PVP区域 ====================

    public void savePVPRegion(String name,
                              String world,
                              int x1, int y1, int z1,
                              int x2, int y2, int z2) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT OR REPLACE "
                                    + "INTO pvp_regions "
                                    + "(name, world, "
                                    + "x1, y1, z1, "
                                    + "x2, y2, z2, "
                                    + "create_time) "
                                    + "VALUES "
                                    + "(?,?,?,?,?,?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, world);
            ps.setInt(3, x1);
            ps.setInt(4, y1);
            ps.setInt(5, z1);
            ps.setInt(6, x2);
            ps.setInt(7, y2);
            ps.setInt(8, z2);
            ps.setLong(9,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Object>
    getPVPRegion(String name) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT * FROM "
                                    + "pvp_regions "
                                    + "WHERE name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                rs.close();
                ps.close();
                return row;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<Map<String, Object>>
    getAllPVPRegions() {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            Statement st =
                    db.createStatement();
            ResultSet rs =
                    st.executeQuery(
                            "SELECT * FROM "
                                    + "pvp_regions");
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            st.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public void deletePVPRegion(String name) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "DELETE FROM "
                                    + "pvp_regions "
                                    + "WHERE name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

// ==================== PVP击杀 ====================

    public int getPVPKills(
            String player, String region) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT kills FROM "
                                    + "pvp_kills "
                                    + "WHERE "
                                    + "player_name=? "
                                    + "AND region_name=?");
            ps.setString(1, player);
            ps.setString(2, region);
            ResultSet rs = ps.executeQuery();
            int kills = 0;
            if (rs.next())
                kills = rs.getInt("kills");
            rs.close();
            ps.close();
            return kills;
        } catch (SQLException e) {
            return 0;
        }
    }

    public int getPVPDeaths(
            String player, String region) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT deaths FROM "
                                    + "pvp_kills "
                                    + "WHERE "
                                    + "player_name=? "
                                    + "AND region_name=?");
            ps.setString(1, player);
            ps.setString(2, region);
            ResultSet rs = ps.executeQuery();
            int deaths = 0;
            if (rs.next())
                deaths = rs.getInt("deaths");
            rs.close();
            ps.close();
            return deaths;
        } catch (SQLException e) {
            return 0;
        }
    }

    public void addPVPKill(
            String player, String region) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO pvp_kills "
                                    + "(player_name, "
                                    + "region_name, "
                                    + "kills, deaths) "
                                    + "VALUES (?,?,1,0) "
                                    + "ON CONFLICT"
                                    + "(player_name, "
                                    + "region_name) "
                                    + "DO UPDATE SET "
                                    + "kills=kills+1");
            ps.setString(1, player);
            ps.setString(2, region);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void addPVPDeath(
            String player, String region) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "INSERT INTO pvp_kills "
                                    + "(player_name, "
                                    + "region_name, "
                                    + "kills, deaths) "
                                    + "VALUES (?,?,0,1) "
                                    + "ON CONFLICT"
                                    + "(player_name, "
                                    + "region_name) "
                                    + "DO UPDATE SET "
                                    + "deaths=deaths+1");
            ps.setString(1, player);
            ps.setString(2, region);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Map<String, Object>>
    getPVPKillTop(String region, int limit) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT player_name, "
                                    + "kills, deaths "
                                    + "FROM pvp_kills "
                                    + "WHERE "
                                    + "region_name=? "
                                    + "AND kills>0 "
                                    + "ORDER BY kills "
                                    + "DESC LIMIT ?");
            ps.setString(1, region);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("player_name",
                        rs.getString("player_name"));
                row.put("kills",
                        rs.getInt("kills"));
                row.put("deaths",
                        rs.getInt("deaths"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }


    // ==================== 安全报警 ====================

    public void recordSecurityAlert(
            String playerName, String ip) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO security_alerts "
                            + "(player_name, alert_ip, "
                            + "alert_time, alert_date) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, playerName);
            ps.setString(2, ip);
            long now = System.currentTimeMillis();
            ps.setLong(3, now);
            ps.setString(4,
                    new java.text.SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss")
                            .format(new java.util.Date(
                                    now)));
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

    }

    public List<Map<String, Object>>
    getUnnotifiedAlerts(
            String playerName) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM security_alerts "
                            + "WHERE player_name=? "
                            + "AND notified=0 "
                            + "ORDER BY alert_time ASC");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public void markAlertNotified(int alertId) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE security_alerts "
                            + "SET notified=1, "
                            + "notified_time=? "
                            + "WHERE id=?");
            ps.setLong(1,
                    System.currentTimeMillis());
            ps.setInt(2, alertId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    /**
     * 获取玩家最近N条背包备份
     */
    public List<Map<String, Object>>
    getInventoryBackups(String name,
                        int limit) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT id, save_time "
                                    + "FROM inventory_backups "
                                    + "WHERE player_name=? "
                                    + "ORDER BY save_time DESC "
                                    + "LIMIT ?");
            ps.setString(1, name);
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id",
                        rs.getInt("id"));
                row.put("save_time",
                        rs.getLong("save_time"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }


    /**
     * 获取指定ID的备份数据
     */
    public String[] getInventoryBackupById(
            int backupId) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT contents_b64, "
                                    + "armor_b64, "
                                    + "extra_b64, "
                                    + "level, "
                                    + "experience "
                                    + "FROM inventory_backups "
                                    + "WHERE id=?");
            ps.setInt(1, backupId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String[] result =
                        new String[5];
                result[0] = rs.getString(
                        "contents_b64");
                result[1] = rs.getString(
                        "armor_b64");
                result[2] = rs.getString(
                        "extra_b64");
                result[3] = String.valueOf(
                        rs.getInt("level"));
                result[4] = String.valueOf(
                        rs.getDouble("experience"));
                rs.close();
                ps.close();
                return result;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }


    // ==================== 工单操作 ====================

    public int createTicket(String type,
                            String status, String priority,
                            String requester, String title,
                            String description,
                            int rewardAmount,
                            String targetPlayer) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO tickets "
                            + "(type, status, priority, "
                            + "requester, title, "
                            + "description, "
                            + "reward_amount, "
                            + "target_player, "
                            + "create_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)");
            ps.setString(1, type);
            ps.setString(2, status);
            ps.setString(3, priority);
            ps.setString(4, requester);
            ps.setString(5, title);
            ps.setString(6, description);
            ps.setInt(7, rewardAmount);
            ps.setString(8, targetPlayer);
            ps.setLong(9,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ResultSet rs =
                    ps.getGeneratedKeys();
            int id = -1;
            if (rs.next()) id = rs.getInt(1);
            rs.close();
            ps.close();
            return id;
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void updateTicketStatus(int ticketId,
                                   String status) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET status=?, "
                            + "update_time=? "
                            + "WHERE id=?");
            ps.setString(1, status);
            ps.setLong(2,
                    System.currentTimeMillis());
            ps.setInt(3, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void assignTicket(int ticketId,
                             String provider) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET "
                            + "assigned_to=?, "
                            + "status='processing', "
                            + "update_time=? "
                            + "WHERE id=?");
            ps.setString(1, provider);
            ps.setLong(2,
                    System.currentTimeMillis());
            ps.setInt(3, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void completeTicket(int ticketId) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET "
                            + "status='completed', "
                            + "complete_time=?, "
                            + "update_time=? "
                            + "WHERE id=?");
            long now = System.currentTimeMillis();
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setInt(3, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void confirmTicket(int ticketId,
                              int score) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET "
                            + "admin_confirmed=1, "
                            + "score=?, "
                            + "status='resolved', "
                            + "update_time=? "
                            + "WHERE id=?");
            ps.setInt(1, score);
            ps.setLong(2,
                    System.currentTimeMillis());
            ps.setInt(3, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public void rejectTicketComplete(int ticketId,
                                     String reason) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET "
                            + "status='rejected', "
                            + "reject_reason=?, "
                            + "update_time=? "
                            + "WHERE id=?");
            ps.setString(1, reason);
            ps.setLong(2,
                    System.currentTimeMillis());
            ps.setInt(3, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 更新工单任意字段
     */
    public void updateTicketField(int ticketId,
                                  String field, Object value) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE tickets SET " + field
                            + "=? WHERE id=?");
            if (value instanceof Integer)
                ps.setInt(1, (Integer) value);
            else if (value instanceof Long)
                ps.setLong(1, (Long) value);
            else
                ps.setString(1,
                        String.valueOf(value));
            ps.setInt(2, ticketId);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Object> getTicket(
            int ticketId) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE id=?");
            ps.setInt(1, ticketId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                rs.close();
                ps.close();
                return row;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<Map<String, Object>>
    getTicketsByStatus(String status) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE status=? "
                            + "ORDER BY create_time DESC");
            ps.setString(1, status);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public void addTicketReply(int ticketId,
                               String sender, String message) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO ticket_replies "
                            + "(ticket_id, sender, "
                            + "message, create_time) "
                            + "VALUES (?,?,?,?)");
            ps.setInt(1, ticketId);
            ps.setString(2, sender);
            ps.setString(3, message);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            // 更新工单状态为已回复
            updateTicketStatus(ticketId,
                    "replied");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public List<Map<String, Object>>
    getTicketReplies(int ticketId) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM ticket_replies "
                            + "WHERE ticket_id=? "
                            + "ORDER BY create_time ASC");
            ps.setInt(1, ticketId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 获取已回复但超时未互动的工单
     */
    public List<Map<String, Object>>
    getRepliedStaleTickets(
            long timeoutMs) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            long cutoff =
                    System.currentTimeMillis()
                            - timeoutMs;
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE status='replied' "
                            + "AND update_time < ?");
            ps.setLong(1, cutoff);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, Object>>
    getTicketsByPlayer(String playerName) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE requester=? "
                            + "ORDER BY create_time DESC");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, Object>>
    getTicketsByProvider(String provider) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE assigned_to=? "
                            + "AND (status='processing' "
                            + "OR status='replied') "
                            + "ORDER BY create_time DESC");
            ps.setString(1, provider);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs
                        .getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public List<Map<String, Object>>
    getUnconfirmedCompleted(
            long timeoutMs) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            long cutoff =
                    System.currentTimeMillis()
                            - timeoutMs;
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM tickets "
                            + "WHERE status='completed' "
                            + "AND admin_confirmed=0 "
                            + "AND complete_time < ?");
            ps.setLong(1, cutoff);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ==================== 服务商操作 ====================

    public void addServiceProvider(String name,
                                   String role) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO "
                            + "service_providers "
                            + "(player_name, role, "
                            + "active, join_time) "
                            + "VALUES (?,?,1,?)");
            ps.setString(1, name);
            ps.setString(2, role);
            ps.setLong(3,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void removeServiceProvider(
            String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM service_providers "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public boolean isServiceProvider(
            String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM service_providers "
                            + "WHERE player_name=? "
                            + "AND active=1");
            ps.setString(1, name);
            boolean r = ps.executeQuery().next();
            ps.close();
            return r;
        } catch (SQLException e) {
            return false;
        }
    }

    public List<Map<String, Object>>
    getAllServiceProviders() {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT * FROM service_providers "
                            + "WHERE active=1");
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData()
                        .getColumnCount(); i++) {
                    row.put(rs.getMetaData()
                                    .getColumnName(i),
                            rs.getObject(i));
                }
                list.add(row);
            }
            rs.close();
            st.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public Connection getDb() {
        return db;
    }
}
