package Sdf1_login;

import java.io.File;
import java.sql.*;
import java.util.logging.Logger;

public class PlayerStorage {
    private Connection conn;
    private final Logger logger;

    public PlayerStorage(File dataFolder,
                         Logger logger) {
        this.logger = logger;
        File dbFile = new File(
                dataFolder,
                "quest_data.db");
        try {
            Class.forName(
                    "org.sqlite.JDBC");
            conn = DriverManager
                    .getConnection(
                            "jdbc:sqlite:"
                                    + dbFile
                                    .getAbsolutePath());
            conn.setAutoCommit(true);
            Statement s =
                    conn.createStatement();
            s.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS "
                            + "quest_kv ("
                            + "player TEXT NOT NULL,"
                            + "key TEXT NOT NULL,"
                            + "value TEXT,"
                            + "PRIMARY KEY"
                            + "(player, key))");
            s.close();
            logger.info(
                    "[QuestStorage] 初始化: "
                            + dbFile.getAbsolutePath());
        } catch (Exception e) {
            logger.severe(
                    "[QuestStorage] 失败: "
                            + e.getMessage());
        }
    }

    public Object getCondition(
            String player, String key) {
        if (conn == null) return null;
        try {
            PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT value "
                                    + "FROM quest_kv "
                                    + "WHERE player=? "
                                    + "AND key=?");
            ps.setString(1,
                    player.toLowerCase());
            ps.setString(2, key);
            ResultSet rs =
                    ps.executeQuery();
            String val = rs.next()
                    ? rs.getString("value")
                    : null;
            rs.close();
            ps.close();
            return parseValue(val);
        } catch (SQLException e) {
            return null;
        }
    }

    public void setCondition(
            String player, String key) {
        setConditionRaw(
                player, key, "true");
    }
    public void debugPlayer(String player) {

    }
    public void setConditionRaw(
            String player, String key,
            String value) {
        if (conn == null) return;
        try {
            PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT OR REPLACE "
                                    + "INTO quest_kv"
                                    + "(player,key,value)"
                                    + " VALUES(?,?,?)");
            ps.setString(1,
                    player.toLowerCase());
            ps.setString(2, key);
            ps.setString(3, value);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            logger.warning(
                    "[QuestStorage] 写入失败: "
                            + key + "=" + value
                            + " | " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (conn != null
                    && !conn.isClosed())
                conn.close();
        } catch (Exception e) {}
    }

    private Object parseValue(String v) {
        if (v == null) return null;
        try {
            return Long.parseLong(v);
        } catch (Exception e1) {
            try {
                return Double.parseDouble(v);
            } catch (Exception e2) {
                if ("true".equalsIgnoreCase(v))
                    return true;
                if ("false".equalsIgnoreCase(v))
                    return false;
                return v;
            }
        }
    }
}
