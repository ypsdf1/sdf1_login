package Sdf1_login;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class QuestStorage {

    private Connection conn;

    public QuestStorage(File dataFolder) {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    dataFolder,
                    "quest_data.db");
            conn = DriverManager.getConnection(
                    "jdbc:sqlite:"
                            + dbFile.getPath());
            initTable();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initTable()
            throws SQLException {
        Statement stmt =
                conn.createStatement();
        stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS "
                        + "quest_conditions ("
                        + "player TEXT NOT NULL, "
                        + "condition_key TEXT NOT NULL, "
                        + "value TEXT DEFAULT '1', "
                        + "timestamp INTEGER, "
                        + "PRIMARY KEY "
                        + "(player, condition_key))");
        stmt.close();
    }

    /**
     * 标记条件已完成（value = "1"）
     */
    public void setCondition(
            String player,
            String condition) {
        setConditionRaw(
                player, condition, "1");
    }

    /**
     * 写入任意值（计数器等）
     */
    public void setConditionRaw(
            String player,
            String condition,
            String value) {
        try {
            PreparedStatement ps =
                    conn.prepareStatement(
                            "INSERT OR REPLACE "
                                    + "INTO "
                                    + "quest_conditions "
                                    + "(player, "
                                    + "condition_key, "
                                    + "value, timestamp) "
                                    + "VALUES "
                                    + "(?, ?, ?, ?)");
            ps.setString(1, player);
            ps.setString(2, condition);
            ps.setString(3, value);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 查询条件值
     */
    public Object getCondition(
            String player,
            String condition) {
        try {
            PreparedStatement ps =
                    conn.prepareStatement(
                            "SELECT value FROM "
                                    + "quest_conditions "
                                    + "WHERE player = ? "
                                    + "AND "
                                    + "condition_key = ?");
            ps.setString(1, player);
            ps.setString(2, condition);
            ResultSet rs =
                    ps.executeQuery();
            if (rs.next()) {
                String val =
                        rs.getString("value");
                rs.close();
                ps.close();
                // 尝试解析为数字
                try {
                    if (val.contains(".")) {
                        return Double.parseDouble(
                                val);
                    }
                    return Long.parseLong(val);
                } catch (NumberFormatException e) {
                    return val;
                }
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void close() {
        try {
            if (conn != null
                    && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
