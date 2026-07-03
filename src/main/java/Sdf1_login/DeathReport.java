package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡报告系统
 * - 记录玩家死亡坐标（一次性，新死亡覆盖）
 * - 死亡后发送包含坐标的消息
 */
public class DeathReport implements Listener {
    private final Main plugin;
    
    // 内存缓存: player_name → DeathData
    private final ConcurrentHashMap<String, DeathData> deathReports = new ConcurrentHashMap<>();

    public static class DeathData {
        public final String playerName;
        public String worldName;
        public final double x, y, z;
        public long deathTime;
        public final UUID playerId;
        
        DeathData(String playerName, UUID playerId, Location loc) {
            this.playerName = playerName;
            this.playerId = playerId;
            this.worldName = loc.getWorld() != null ? loc.getWorld().getName() : "world";
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.deathTime = System.currentTimeMillis();
        }
    }

    public DeathReport(Main plugin) {
        this.plugin = plugin;
        initTable();
        loadAllFromDB();
    }

    // ==================== 事件监听 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        Location deathLoc = player.getLocation();
        if (deathLoc == null) return;
        
        DeathData data = new DeathData(player.getName(), player.getUniqueId(), deathLoc);
        deathReports.put(player.getName(), data);
        saveToDB(data);
        
        // 发送死亡报告消息
        String locMsg = String.format("§c✦ 你已死亡 ✦§7 世界: §f%s §| §7坐标: §e%.1f, %.1f, %.1f§7 时间: §f%s",
                data.worldName, data.x, data.y, data.z,
                new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(data.deathTime)));
        
        player.sendMessage(locMsg);
    }

    // ==================== 查询/获取 ====================

    /**
     * 获取玩家的最新死亡坐标（如果有的话）
     */
    public DeathData getDeathReport(String playerName) {
        return deathReports.get(playerName);
    }

    /**
     * 清除玩家的死亡报告
     */
    public void clearDeathReport(String playerName) {
        deathReports.remove(playerName);
    }

    // ==================== 数据库操作 ====================

    private void loadAllFromDB() {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            PreparedStatement ps = conn.prepareStatement(
                "SELECT player_name, player_uuid, world, x, y, z, death_time FROM death_reports");
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                DeathData data = new DeathData(
                    rs.getString("player_name"),
                    UUID.fromString(rs.getString("player_uuid")),
                    new Location(null, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"))
                );
                data.worldName = rs.getString("world");
                data.deathTime = rs.getLong("death_time");
                deathReports.put(data.playerName, data);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            // 表可能不存在，忽略（会在第一次死亡时自动创建）
        }
    }

    private void saveToDB(DeathData data) {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO death_reports (player_name, player_uuid, world, x, y, z, death_time) VALUES (?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, data.playerName);
            ps.setString(2, data.playerId.toString());
            ps.setString(3, data.worldName);
            ps.setDouble(4, data.x);
            ps.setDouble(5, data.y);
            ps.setDouble(6, data.z);
            ps.setLong(7, data.deathTime);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[死亡报告] 保存失败: " + e.getMessage());
        }
    }

    private void persistAll() {
        for (DeathData data : deathReports.values()) {
            saveToDB(data);
        }
    }

    // ==================== 初始化 ====================

    public void initTable() {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            java.sql.Statement st = conn.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS death_reports ("
                    + "player_name TEXT PRIMARY KEY, "
                    + "player_uuid TEXT NOT NULL, "
                    + "world TEXT, "
                    + "x REAL, y REAL, z REAL, "
                    + "death_time LONG)");
            st.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[死亡报告] 建表失败: " + e.getMessage());
        }
    }
}
