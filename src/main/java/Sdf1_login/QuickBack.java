package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快速返回系统 (/back)
 * - 每分钟记录一次在线玩家的坐标
 * - /back 命令传送玩家到最近的一个记录坐标
 */
public class QuickBack implements Listener, CommandExecutor, TabCompleter {
    private final Main plugin;
    
    // 内存缓存: player_name → Location (每分钟刷新)
    private final Map<String, Location> lastKnownLocations = new ConcurrentHashMap<>();
    
    // 冷却: player_name → next_available_time
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    
    // ★ 定时器引用（用于 shutdown 时取消）
    private BukkitTask scheduleTask = null;
    
    private static final long COOLDOWN_MS = 5 * 60 * 1000; // 5分钟冷却

    public QuickBack(Main plugin) {
        this.plugin = plugin;
        loadAllFromDB();
        
        // 每分钟记录所有在线玩家的坐标
        scheduleTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    recordLocation(p);
                }
            }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    // ==================== 事件监听 ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 移动超过8格时才记录（减少数据库写入压力）
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;
        
        double distance = from.distance(to);
        if (distance < 8.0) return;
        
        recordLocation(event.getPlayer());
    }

    // ==================== 命令处理 ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§c此命令仅限玩家使用");
            return true;
        }
        
        Player player = (Player) sender;
        
        // 检查冷却
        Long nextAvailable = cooldowns.get(player.getName());
        if (nextAvailable != null && System.currentTimeMillis() < nextAvailable) {
            long remaining = (nextAvailable - System.currentTimeMillis()) / 1000;
            player.sendMessage("§c快速返回冷却中，请等待 §e" + remaining + " §c秒");
            return true;
        }
        
        // 获取最近的已知位置
        Location loc = getLastKnownLocation(player);
        if (loc == null) {
            player.sendMessage("§c没有找到可返回的位置记录");
            return true;
        }
        
        // 传送
        if (loc.getWorld() == null) {
            player.sendMessage("§c位置世界信息无效");
            return true;
        }
        
        Location dest = loc.clone().add(0.5, 0, 0.5); // 对齐格子中心
        player.teleport(dest);
        
        // 设置冷却
        cooldowns.put(player.getName(), System.currentTimeMillis() + COOLDOWN_MS);
        player.sendMessage("§a§l[快速返回] §f已传送到最近记录点");
        
        return true;
    }

    // ==================== 位置记录 ====================

    private void recordLocation(Player player) {
        Location loc = player.getLocation();
        lastKnownLocations.put(player.getName(), loc);
        saveToDB(player.getName(), loc);
    }

    public Location getLastKnownLocation(Player player) {
        // 优先从内存获取
        Location loc = lastKnownLocations.get(player.getName());
        if (loc != null && loc.getWorld() != null) {
            return loc;
        }
        
        // 从数据库获取
        return loadFromDB(player.getName());
    }

    // ==================== 数据库操作 ====================

    private void loadAllFromDB() {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            PreparedStatement ps = conn.prepareStatement(
                "SELECT player_name, world, x, y, z FROM quick_back_records");
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                String playerName = rs.getString("player_name");
                Location loc = new Location(
                    Bukkit.getWorld(rs.getString("world")),
                    rs.getDouble("x"),
                    rs.getDouble("y"),
                    rs.getDouble("z")
                );
                lastKnownLocations.put(playerName, loc);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            // 表可能不存在
        }
    }

    private void saveToDB(String playerName, Location loc) {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO quick_back_records (player_name, world, x, y, z) VALUES (?, ?, ?, ?, ?)");
            ps.setString(1, playerName);
            ps.setString(2, loc.getWorld() != null ? loc.getWorld().getName() : "world");
            ps.setDouble(3, loc.getX());
            ps.setDouble(4, loc.getY());
            ps.setDouble(5, loc.getZ());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            // 忽略
        }
    }

    private Location loadFromDB(String playerName) {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return null;
            
            PreparedStatement ps = conn.prepareStatement(
                "SELECT world, x, y, z FROM quick_back_records WHERE player_name=? LIMIT 1");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                Location loc = new Location(
                    Bukkit.getWorld(rs.getString("world")),
                    rs.getDouble("x"),
                    rs.getDouble("y"),
                    rs.getDouble("z")
                );
                rs.close();
                ps.close();
                return loc;
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            // 忽略
        }
        return null;
    }

    // ==================== Tab补全 ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return new ArrayList<>();
    }

    // ==================== 初始化 ====================

    public void initTable() {
        try {
            java.sql.Connection conn = plugin.getDb().getConnection();
            if (conn == null) return;
            
            java.sql.Statement st = conn.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS quick_back_records ("
                    + "player_name TEXT PRIMARY KEY, "
                    + "world TEXT, "
                    + "x REAL, y REAL, z REAL)");
            st.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[快速返回] 建表失败: " + e.getMessage());
        }
    }
    
    // ==================== 关闭 ====================
    
    public void shutdown() {
        if (scheduleTask != null) {
            scheduleTask.cancel();
        }
        lastKnownLocations.clear();
        cooldowns.clear();
    }
}
