package Sdf1_login;

import java.io.File;
import java.sql.*;
import java.util.*;

//全部数据库操作
public class DatabaseManager {

    private Connection db;
    private final File dataFolder;

    public DatabaseManager(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(dataFolder, "login.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS users ("
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
                    + "temp_pw_used INTEGER DEFAULT 0)");
            addColumnIfMissing("tasks_completed", "TEXT DEFAULT ''");
                addColumnIfMissing("temp_password", "TEXT DEFAULT ''");
                addColumnIfMissing("temp_pw_expire", "INTEGER DEFAULT 0");
                addColumnIfMissing("temp_pw_used", "INTEGER DEFAULT 0");
            st.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[Sdf1_login] DB初始化失败: " + e.getMessage(), e);
        }
    }

    public void close() {
        try {
            if (db != null && !db.isClosed()) db.close();
        } catch (Exception ignored) {}
    }

    public Connection getDb() { return db; }
    private void addColumnIfMissing(String col, String def) {
        try {
            db.createStatement().executeUpdate(
                    "ALTER TABLE users ADD COLUMN "
                            + col + " " + def);
        } catch (SQLException ignored) {}
    }

    // ===== 用户操作 =====

    public boolean userExists(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM users WHERE player_name=?");
            ps.setString(1, name);
            boolean r = ps.executeQuery().next();
            ps.close();
            return r;
        } catch (SQLException e) { return false; }
    }

    public void createUser(String name, String hash, String salt) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO users "
                            + "(player_name,password_hash,"
                            + "password_salt,register_time) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, hash);
            ps.setString(3, salt);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Object> getUser(String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM users WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                for (int i = 1;
                     i <= rs.getMetaData().getColumnCount(); i++) {
                    r.put(rs.getMetaData().getColumnName(i),
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

    public void setField(String name, String field, Object val) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "UPDATE users SET " + field
                            + "=? WHERE player_name=?");
            if (val instanceof Integer)
                ps.setInt(1, (Integer) val);
            else if (val instanceof Long)
                ps.setLong(1, (Long) val);
            else
                ps.setString(1, String.valueOf(val));
            ps.setString(2, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public Object getField(String name, String field) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT " + field
                            + " FROM users WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            Object val = rs.next() ? rs.getObject(1) : null;
            rs.close();
            ps.close();
            return val;
        } catch (SQLException e) {
            return null;
        }
    }

    public boolean checkPassword(String name, String hash) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT password_hash FROM users "
                            + "WHERE player_name=?");
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String stored = rs.getString("password_hash");
                rs.close();
                ps.close();
                return stored.equals(hash);
            }
            rs.close();
            ps.close();
            return false;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean checkPasswordOrTemp(
            String name, String hash) {
        if (checkPassword(name, hash)) return true;
        Map<String, Object> user = getUser(name);
        if (user.isEmpty()) return false;
        String tempHash = (String) user.getOrDefault(
                "temp_password", "");
        long expire = ((Number) user.getOrDefault(
                "temp_pw_expire", 0L)).longValue();
        if (tempHash == null || tempHash.isEmpty())
            return false;
        if (System.currentTimeMillis() > expire)
            return false;
        return tempHash.equals(hash);
    }

    public boolean isUsingTempPassword(String name) {
        String h = (String) getField(name, "temp_password");
        return h != null && !h.isEmpty();
    }

    public void setLoggedIn(String name, boolean val) {
        setField(name, "is_logged_in", val ? 1 : 0);
    }

    public void addPoints(String name, int amount) {
        int cur = ((Number) getField(name, "points"))
                .intValue();
        setField(name, "points", cur + amount);
    }

    public boolean deductPoints(String name, int amount) {
        int cur = ((Number) getField(name, "points"))
                .intValue();
        if (cur < amount) return false;
        setField(name, "points", cur - amount);
        return true;
    }

    public List<Map<String, Object>> getAllUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT * FROM users");
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1;
                     i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(
                            rs.getMetaData().getColumnName(i),
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

    public void deleteUser(String name) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM users WHERE player_name=?");
            ps.setString(1, name);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
