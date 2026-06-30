package Sdf1_login;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Home传送点管理器
 * 支持: sethome, delhome, home, homes
 * 集成用户组系统: home_limit (0=全局默认, >0=组限制, -1=无限)
 */
public class HomeManager implements Listener {
    private final Main plugin;
    
    // 缓存: player_name → homes列表
    private final Map<String, List<HomeData>> homesCache = new ConcurrentHashMap<>();
    
    // 床位置缓存: player_name → Location (用于自动记录床位置)
    private final Map<String, Location> bedLocations = new ConcurrentHashMap<>();
    
    public HomeManager(Main plugin) {
        this.plugin = plugin;
        initDatabase();
    }
    
    // ==================== 数据结构 ====================
    
    public static class HomeData {
        public String name;
        public String world;
        public double x, y, z;
        public float yaw, pitch;
        public long createdAt;
        
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
    
    // ==================== 数据库初始化 ====================

    private void initDatabase() {
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) {
                plugin.getLogger().warning("[Home] 无法获取数据库连接");
                return;
            }

            java.sql.Statement st = db.createStatement();
            // homes表已在DatabaseManager中创建，此处确保索引
            st.execute("CREATE INDEX IF NOT EXISTS idx_homes_player ON homes(player_name)");
            st.close();

            plugin.getLogger().info("[Home] 数据库初始化完成");
        } catch (Exception e) {
            plugin.getLogger().warning("[Home] 数据库初始化失败: " + e.getMessage());
        }
    }
    
    // ==================== 命令处理 ====================
    
    public boolean handleCommand(Player player, String label, String[] args) {
        String lowerLabel = label.toLowerCase();
        
        switch (lowerLabel) {
            case "sethome":
                return handleSetHome(player, args);
            case "delhome":
                return handleDelHome(player, args);
            case "home":
                return handleHome(player, args);
            case "homes":
                return handleHomes(player, args);
            default:
                return false;
        }
    }
    
    /**
     * /sethome <名称> - 设置家
     */
    private boolean handleSetHome(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("§c用法: /sethome <名称>");
            return true;
        }
        
        String homeName = args[0].toLowerCase();
        
        // 校验名称格式
        if (!homeName.matches("^[a-zA-Z0-9_]{1,16}$")) {
            player.sendMessage("§c家名只能包含字母、数字、下划线，长度1-16");
            return true;
        }
        
        // 检查家数量限制
        int maxHomes = getMaxHomes(player);
        if (maxHomes != -1) {
            List<HomeData> homes = getHomes(player.getName());
            if (homes.size() >= maxHomes) {
                player.sendMessage("§c你已达到家数量上限 (" + maxHomes + "个)");
                return true;
            }
        }
        
        // 检查是否已存在同名家
        if (homeExists(player.getName(), homeName)) {
            player.sendMessage("§c你已有一个名为 §e" + homeName + " §c的家");
            return true;
        }
        
        // 保存家
        Location loc = player.getLocation();
        saveHome(player.getName(), homeName, loc);
        
        player.sendMessage("§a家 §e" + homeName + " §a已设置！");
        player.sendMessage("§7使用 §e/home " + homeName + " §7传送回家");
        
        return true;
    }
    
    /**
     * /delhome <名称> - 删除家
     */
    private boolean handleDelHome(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage("§c用法: /delhome <名称>");
            return true;
        }
        
        String homeName = args[0].toLowerCase();
        
        if (!homeExists(player.getName(), homeName)) {
            player.sendMessage("§c你没有名为 §e" + homeName + " §c的家");
            return true;
        }
        
        deleteHome(player.getName(), homeName);
        player.sendMessage("§a家 §e" + homeName + " §a已删除！");
        
        return true;
    }
    
    /**
     * /home [名称] - 传送到家
     */
    private boolean handleHome(Player player, String[] args) {
        if (args.length == 0) {
            // 无参数: 传送到第一个家
            List<HomeData> homes = getHomes(player.getName());
            if (homes.isEmpty()) {
                player.sendMessage("§c你还没有设置任何家");
                player.sendMessage("§7使用 §e/sethome <名称> §7设置一个家");
                return true;
            }
            teleportToHome(player, homes.get(0));
            return true;
        }
        
        String homeName = args[0].toLowerCase();
        HomeData home = getHome(player.getName(), homeName);
        
        if (home == null) {
            player.sendMessage("§c你没有名为 §e" + homeName + " §c的家");
            return true;
        }
        
        teleportToHome(player, home);
        return true;
    }
    
    /**
     * /homes - 查看所有家
     */
    private boolean handleHomes(Player player, String[] args) {
        List<HomeData> homes = getHomes(player.getName());
        
        if (homes.isEmpty()) {
            player.sendMessage("§e你还没有设置任何家");
            player.sendMessage("§7使用 §e/sethome <名称> §7设置一个家");
            return true;
        }
        
        int maxHomes = getMaxHomes(player);
        player.sendMessage("§6§l你的家 §7(" + homes.size() + (maxHomes == -1 ? "/" + "∞" : "/" + maxHomes) + ")");
        player.sendMessage("§7────────────────────");
        
        for (HomeData home : homes) {
            String worldName = home.world != null ? home.world : "未知";
            player.sendMessage("§e  • " + home.name + " §7[" + worldName + "]");
        }
        
        player.sendMessage("§7────────────────────");
        player.sendMessage("§7使用 §e/home <名称> §7传送");
        player.sendMessage("§7使用 §e/sethome <名称> §7设置新家");
        player.sendMessage("§7使用 §e/delhome <名称> §7删除家");
        
        return true;
    }
    
    // ==================== 床事件监听 ====================
    
    /**
     * 玩家上床时自动记录床位置
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        Player player = event.getPlayer();
        Location bedLoc = event.getBed().getLocation();
        
        bedLocations.put(player.getName(), bedLoc);
        
        // 自动保存为"bed"家（如果不存在）
        if (!homeExists(player.getName(), "bed")) {
            saveHome(player.getName(), "bed", bedLoc);
            player.sendMessage("§a床位置已自动保存为 §e/home bed");
        }
    }
    
    /**
     * 玩家重生时更新床位置
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location respawnLoc = event.getRespawnLocation();
        
        // 如果重生点是床位置，更新bed家
        Location bedLoc = bedLocations.get(player.getName());
        if (bedLoc != null && bedLoc.getWorld() != null) {
            if (respawnLoc.getWorld() != null &&
                respawnLoc.getWorld().getName().equals(bedLoc.getWorld().getName()) &&
                respawnLoc.distance(bedLoc) < 5) {
                // 重生点就是床，更新bed家
                updateHome(player.getName(), "bed", respawnLoc);
            }
        }
    }
    
    // ==================== 家数量限制 ====================
    
    /**
     * 获取玩家最大家数量
     * @return -1=无限, 0=使用全局默认, >0=用户组独立限制
     */
    private int getMaxHomes(Player player) {
        UserGroupManager userGroupMgr = plugin.getUserGroup();
        if (userGroupMgr == null) {
            return getGlobalHomeLimit();
        }
        
        // 获取玩家最高优先级用户组
        UserGroupManager.UserGroupConfig highestGroup = userGroupMgr.getHighestGroup(player.getName());
        if (highestGroup == null) {
            return getGlobalHomeLimit();
        }
        
        // 获取用户组的home_limit配置
        int groupLimit = userGroupMgr.getHomeLimit(highestGroup.name);
        
        if (groupLimit == -1) {
            // 无限
            return -1;
        } else if (groupLimit == 0) {
            // 跟随全局
            return getGlobalHomeLimit();
        } else {
            // 独立限制
            return groupLimit;
        }
    }
    
    /**
     * 获取全局家数量限制
     */
    private int getGlobalHomeLimit() {
        // 默认5个家，可从配置文件读取
        return 5;
    }
    
    // ==================== 数据库操作 ====================
    
    private boolean homeExists(String playerName, String homeName) {
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) return false;
            
            PreparedStatement ps = db.prepareStatement(
                "SELECT COUNT(*) FROM homes WHERE player_name=? AND home_name=?");
            ps.setString(1, playerName);
            ps.setString(2, homeName);
            ResultSet rs = ps.executeQuery();
            boolean exists = rs.next() && rs.getInt(1) > 0;
            rs.close();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }
    
    private List<HomeData> getHomes(String playerName) {
        // 先查缓存
        List<HomeData> cached = homesCache.get(playerName);
        if (cached != null) {
            return cached;
        }
        
        List<HomeData> homes = new ArrayList<>();
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) return homes;
            
            PreparedStatement ps = db.prepareStatement(
                "SELECT * FROM homes WHERE player_name=? ORDER BY created_at");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            
            while (rs.next()) {
                HomeData home = new HomeData();
                home.name = rs.getString("home_name");
                home.world = rs.getString("world");
                home.x = rs.getDouble("x");
                home.y = rs.getDouble("y");
                home.z = rs.getDouble("z");
                home.yaw = rs.getFloat("yaw");
                home.pitch = rs.getFloat("pitch");
                home.createdAt = rs.getLong("created_at");
                homes.add(home);
            }
            
            rs.close();
            ps.close();
            
            // 缓存
            homesCache.put(playerName, homes);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Home] 获取家列表失败: " + e.getMessage());
        }
        
        return homes;
    }
    
    private HomeData getHome(String playerName, String homeName) {
        List<HomeData> homes = getHomes(playerName);
        for (HomeData home : homes) {
            if (home.name.equalsIgnoreCase(homeName)) {
                return home;
            }
        }
        return null;
    }
    
    private void saveHome(String playerName, String homeName, Location loc) {
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) return;
            
            PreparedStatement ps = db.prepareStatement(
                "INSERT OR REPLACE INTO homes (player_name, home_name, world, x, y, z, yaw, pitch, created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)");
            ps.setString(1, playerName);
            ps.setString(2, homeName);
            ps.setString(3, loc.getWorld() != null ? loc.getWorld().getName() : "world");
            ps.setDouble(4, loc.getX());
            ps.setDouble(5, loc.getY());
            ps.setDouble(6, loc.getZ());
            ps.setFloat(7, loc.getYaw());
            ps.setFloat(8, loc.getPitch());
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            
            // 清除缓存
            homesCache.remove(playerName);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Home] 保存家失败: " + e.getMessage());
        }
    }
    
    private void updateHome(String playerName, String homeName, Location loc) {
        saveHome(playerName, homeName, loc); // INSERT OR REPLACE 自动更新
    }
    
    private void deleteHome(String playerName, String homeName) {
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) return;
            
            PreparedStatement ps = db.prepareStatement(
                "DELETE FROM homes WHERE player_name=? AND home_name=?");
            ps.setString(1, playerName);
            ps.setString(2, homeName);
            ps.executeUpdate();
            ps.close();
            
            // 清除缓存
            homesCache.remove(playerName);
        } catch (SQLException e) {
            plugin.getLogger().warning("[Home] 删除家失败: " + e.getMessage());
        }
    }
    
    /**
     * 传送玩家到家
     */
    private void teleportToHome(Player player, HomeData home) {
        World world = Bukkit.getWorld(home.world);
        if (world == null) {
            player.sendMessage("§c世界 §e" + home.world + " §c不存在");
            return;
        }
        
        Location loc = home.toLocation(world);
        
        // 安全检查: 确保位置安全
        if (loc.getBlock().getType().isSolid()) {
            // 向上寻找安全位置
            for (int y = (int) loc.getY(); y < 256; y++) {
                Location safeLoc = new Location(world, loc.getX(), y + 1, loc.getZ());
                if (!safeLoc.getBlock().getType().isSolid()) {
                    loc = safeLoc;
                    break;
                }
            }
        }
        
        player.teleport(loc);
        player.sendMessage("§a已传送到家 §e" + home.name);
    }
    
    // ==================== Tab补全 ====================
    
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }
        
        Player player = (Player) sender;
        String cmdName = command.getName().toLowerCase();
        
        if (cmdName.equals("home") || cmdName.equals("delhome")) {
            if (args.length == 1) {
                List<String> homes = new ArrayList<>();
                for (HomeData home : getHomes(player.getName())) {
                    if (home.name.toLowerCase().startsWith(args[0].toLowerCase())) {
                        homes.add(home.name);
                    }
                }
                return homes;
            }
        } else if (cmdName.equals("sethome") || cmdName.equals("homes")) {
            return Collections.emptyList();
        }
        
        return Collections.emptyList();
    }
    
    // ==================== 清理 ====================
    
    public void shutdown() {
        homesCache.clear();
        bedLocations.clear();
    }
}