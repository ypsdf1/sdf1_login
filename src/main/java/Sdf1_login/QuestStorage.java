package Sdf1_login;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class QuestStorage {

    private volatile Connection conn;
    private final String dbPath;
    private final Plugin plugin;
    private final Object connLock = new Object();

    public QuestStorage(File dataFolder, Plugin plugin) {
        this.plugin = plugin;
        String path = "";
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    dataFolder, "quest_data.db");
            path = dbFile.getPath();
        } catch (Exception e) {
            e.printStackTrace();
        }
        dbPath = path;

        conn = openConnection();
        if (conn != null) {
            initTable();
        }
    }

    private Connection openConnection() {
        String url = "jdbc:sqlite:" + dbPath
                + "?journal_mode=WAL"
                + "&synchronous=NORMAL"
                + "&busy_timeout=5000";
        try {
            return DriverManager.getConnection(url);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 轻量级连接检查 — 仅检查本地状态，不执行SQL
     * 避免 isValid() 在主线程上阻塞
     */
    private void ensureConnection() {
        try {
            if (conn != null && !conn.isClosed()) {
                return;
            }
        } catch (SQLException ignored) {}
        synchronized (connLock) {
            try { if (conn != null) conn.close(); }
            catch (Exception ignored) {}
            conn = openConnection();
        }
    }

    private void initTable() {
        try {
            conn.createStatement().executeUpdate(
                    "CREATE TABLE IF NOT EXISTS quest_conditions ("
                            + "player TEXT NOT NULL, "
                            + "condition_key TEXT NOT NULL, "
                            + "value TEXT DEFAULT '1', "
                            + "timestamp INTEGER, "
                            + "PRIMARY KEY (player, condition_key))");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void setCondition(String player, String condition) {
        setConditionRaw(player, condition, "1");
    }

    /**
     * 异步写入 — 不阻塞主线程
     * 使用 Bukkit 异步调度，在独立线程中执行 SQLite 写操作
     */
    public void setConditionRaw(String player,
            String condition, String value) {
        final String sql =
                "INSERT OR REPLACE INTO quest_conditions "
                        + "(player, condition_key, value, timestamp) "
                        + "VALUES (?, ?, ?, ?)";
        final long ts = System.currentTimeMillis();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            writeSync(sql, player, condition, value, ts);
        });
    }

    /**
     * 在异步线程中执行写入，带重试
     */
    private void writeSync(String sql, String player,
            String condKey, String value, long timestamp) {
        synchronized (connLock) {
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    ensureConnection();
                    if (conn == null) return;
                    PreparedStatement ps = conn.prepareStatement(sql);
                    ps.setString(1, player);
                    ps.setString(2, condKey);
                    ps.setString(3, value);
                    ps.setLong(4, timestamp);
                    ps.executeUpdate();
                    ps.close();
                    return;
                } catch (SQLException e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("SQLITE_BUSY")
                            || msg.contains("database is locked"))) {
                        try {
                            Thread.sleep(200 + attempt * 200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    } else {
                        e.printStackTrace();
                        return;
                    }
                }
            }
        }
    }

    /**
     * 同步读取（主线程）
     * WAL 模式下读操作不阻塞写，可安全在主线程执行
     */
    public Object getCondition(String player,
            String condition) {
        synchronized (connLock) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    ensureConnection();
                    if (conn == null) return null;
                    PreparedStatement ps = conn.prepareStatement(
                            "SELECT value FROM quest_conditions "
                                    + "WHERE player = ? "
                                    + "AND condition_key = ?");
                    ps.setString(1, player);
                    ps.setString(2, condition);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        String val = rs.getString("value");
                        rs.close();
                        ps.close();
                        try {
                            if (val.contains("."))
                                return Double.parseDouble(val);
                            return Long.parseLong(val);
                        } catch (NumberFormatException e) {
                            return val;
                        }
                    }
                    rs.close();
                    ps.close();
                    return null;
                } catch (SQLException e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("SQLITE_BUSY")
                            || msg.contains("database is locked"))) {
                        try {
                            Thread.sleep(100 + attempt * 100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                    } else {
                        e.printStackTrace();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public void close() {
        synchronized (connLock) {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
