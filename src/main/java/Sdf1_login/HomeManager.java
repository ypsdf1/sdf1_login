package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
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

    private static final org.bukkit.block.BlockFace[] HORIZ_FACES = {
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST };

    /** 危险方块：落点优先级判断用 */
    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW,
            Material.POINTED_DRIPSTONE);

    /** 是否是床方块 */
    private static boolean isBed(Block b) {
        return b.getBlockData() instanceof Bed;
    }

    /** 取床头方块（配对缺失时返回原方块）；非床返回 null */
    private static Block getBedHead(Block b) {
        if (!isBed(b)) return null;
        if (((Bed) b.getBlockData()).getPart() == Bed.Part.HEAD) return b;
        Block head = horizontalNeighborWithPart(b, Bed.Part.HEAD);
        return head != null ? head : b;
    }

    private static Block horizontalNeighborWithPart(Block b, Bed.Part part) {
        for (org.bukkit.block.BlockFace f : HORIZ_FACES) {
            Block n = b.getRelative(f);
            if (isBed(n) && ((Bed) n.getBlockData()).getPart() == part) return n;
        }
        return null;
    }

    /** 床的两半 [head, foot(可能为null)]；非床返回 null */
    private static Block[] getBedParts(Block b) {
        if (!isBed(b)) return null;
        if (((Bed) b.getBlockData()).getPart() == Bed.Part.HEAD) {
            return new Block[]{ b, horizontalNeighborWithPart(b, Bed.Part.FOOT) };
        }
        Block head = horizontalNeighborWithPart(b, Bed.Part.HEAD);
        return new Block[]{ head != null ? head : b, b };
    }

    /**
     * 玩家与床交互（上床/白天点床）→ bed 传送点始终更新为该床的精确坐标
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        // 下界/末地床爆炸、怪物附近、距离过远等未真正交互的情况不记录
        PlayerBedEnterEvent.BedEnterResult r = event.getBedEnterResult();
        if (r != PlayerBedEnterEvent.BedEnterResult.OK
                && r != PlayerBedEnterEvent.BedEnterResult.NOT_POSSIBLE_NOW
                && r != PlayerBedEnterEvent.BedEnterResult.OBSTRUCTED) {
            return;
        }
        Player player = event.getPlayer();
        Block head = getBedHead(event.getBed());
        if (head == null) return;
        Location bedLoc = head.getLocation();

        bedLocations.put(player.getName(), bedLoc);

        HomeData existing = getHome(player.getName(), "bed");
        if (existing == null) {
            saveHome(player.getName(), "bed", bedLoc);
            player.sendMessage("§a床位置已自动保存为 §e/home bed");
        } else if (!sameBlock(existing, bedLoc)) {
            // 与其它床交互 → 更新传送点坐标
            updateHome(player.getName(), "bed", bedLoc);
            player.sendMessage("§a床传送点已更新为该床的位置");
        }
    }

    /**
     * 玩家复活 → 抓取重生点附近的精确床坐标并更新 bed 传送点
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location respawnLoc = event.getRespawnLocation();
        if (respawnLoc == null || respawnLoc.getWorld() == null) return;

        Block bed = findNearbyBed(respawnLoc);
        if (bed == null) return; // 世界出生点复活等，不碰传送点
        Block head = getBedHead(bed);
        Location exact = (head != null ? head : bed).getLocation();

        HomeData existing = getHome(player.getName(), "bed");
        if (existing == null) {
            saveHome(player.getName(), "bed", exact);
        } else if (!sameBlock(existing, exact)) {
            updateHome(player.getName(), "bed", exact);
        }
        bedLocations.put(player.getName(), exact);
    }

    /** 在重生点附近（水平±3、垂直-1~+2）寻找最近的床方块 */
    private Block findNearbyBed(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        Block best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    Block b = w.getBlockAt(bx + dx, by + dy, bz + dz);
                    if (!isBed(b)) continue;
                    double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (d < bestD) { bestD = d; best = b; }
                }
        return best;
    }

    private boolean sameBlock(HomeData home, Location loc) {
        return home.world != null && loc.getWorld() != null
                && home.world.equals(loc.getWorld().getName())
                && Math.floor(home.x) == loc.getBlockX()
                && Math.floor(home.y) == loc.getBlockY()
                && Math.floor(home.z) == loc.getBlockZ();
    }

    /**
     * 床没了 → 自动删除对应玩家的 bed 传送点（破坏/爆炸均触发）
     */
    private void removeBedHomesFor(Block bedPart) {
        Block[] parts = getBedParts(bedPart);
        if (parts == null) return;
        Set<String> owners = new HashSet<>();
        for (Block part : parts) {
            if (part == null) continue;
            owners.addAll(queryBedHomeOwners(part));
        }
        for (String owner : owners) {
            deleteHome(owner, "bed");
            bedLocations.remove(owner);
            Player p = Bukkit.getPlayerExact(owner);
            if (p != null && p.isOnline()) {
                p.sendMessage("§c你的床已被破坏，/home bed 传送点已自动删除");
            }
        }
    }

    /** 查询 bed 传送点坐标与指定床方块重合的玩家列表 */
    private Set<String> queryBedHomeOwners(Block bedBlock) {
        Set<String> owners = new HashSet<>();
        try {
            java.sql.Connection db = plugin.getDb().getConnection();
            if (db == null) return owners;
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name FROM homes WHERE home_name='bed' AND world=? "
                            + "AND ABS(x-?)<0.01 AND ABS(y-?)<0.01 AND ABS(z-?)<0.01");
            ps.setString(1, bedBlock.getWorld().getName());
            ps.setDouble(2, bedBlock.getX());
            ps.setDouble(3, bedBlock.getY());
            ps.setDouble(4, bedBlock.getZ());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) owners.add(rs.getString(1));
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("[Home] 查询bed传送点失败: " + e.getMessage());
        }
        return owners;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isBed(event.getBlock())) removeBedHomesFor(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block b : event.blockList()) {
            if (isBed(b)) removeBedHomesFor(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block b : event.blockList()) {
            if (isBed(b)) removeBedHomesFor(b);
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
     * 读取area_config中的max_home_per_player配置（管理员可在Web后台调整）
     * 解析失败或值<=0时回退到默认5
     */
    private int getGlobalHomeLimit() {
        try {
            if (plugin.areaProtection != null) {
                int limit = plugin.areaProtection.getGlobalMaxHomePerPlayer();
                if (limit > 0) {
                    return limit;
                }
            }
        } catch (Exception ignored) {}
        // 默认5个家
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
        } catch (SQLException e) {
            plugin.getLogger().warning("[Home] 删除家失败: " + e.getMessage());
        }
    }
    
    /**
     * 传送玩家到家
     */
    private void teleportToHome(Player player, HomeData home) {
        // bed 家走原版复活落点算法（自写，不模拟死亡、无死亡提示）
        if ("bed".equalsIgnoreCase(home.name)) {
            teleportToBedHome(player, home);
            return;
        }
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

    /**
     * 回床：先校验床还在（没了自动删传送点），再用自写算法模拟原版复活落点
     * （床周合法站位 + 视线水平看向床头），直接传送，不模拟死亡。
     */
    private void teleportToBedHome(Player player, HomeData home) {
        World world = Bukkit.getWorld(home.world);
        if (world == null) {
            player.sendMessage("§c世界 §e" + home.world + " §c不存在");
            return;
        }

        Location saved = home.toLocation(world);
        Block savedBlock = world.getBlockAt(saved);
        Block head = getBedHead(savedBlock);
        // 保存坐标上没有床 → 床已没，自动删传送点
        if (head == null) {
            deleteHome(player.getName(), "bed");
            bedLocations.remove(player.getName());
            player.sendMessage("§c你的床已不存在，/home bed 传送点已自动删除");
            return;
        }

        Location dest = findVanillaRespawnSpot(world, head);
        if (dest == null) {
            player.sendMessage("§c床周围被阻挡，暂时无法回到床边");
            return;
        }

        // 若坐标与记录不一致（床被重摆），顺手修正传送点
        if (!sameBlock(home, head.getLocation())) {
            updateHome(player.getName(), "bed", head.getLocation());
            bedLocations.put(player.getName(), head.getLocation());
        }

        player.teleport(dest);
        player.sendMessage("§a已回到床边");
    }

    /**
     * 自写算法模拟原版床复活落点：
     * 1. 候选 = 床头8邻（原版7块优先区，排除床自身占位）→ 床尾8邻兜底；
     *    排序遵循原版"西北(低X低Z)最先，其次向南(+Z)再向东(+X)"。
     * 2. 合法性 = 落点方块及上方一格无碰撞 + 下方一格有碰撞（可站立），
     *    优先无危险方块的落点（火/岩浆/仙人掌等降级为次选）。
     * 3. 落地朝向：视线水平看向床头。
     * 全部被阻挡时回退到床位置向上找安全格。
     */
    private Location findVanillaRespawnSpot(World world, Block head) {
        Block[] parts = getBedParts(head);
        Block foot = (parts != null && parts.length > 1) ? parts[1] : null;
        int bedY = head.getY();

        // 收集候选：床头邻格（排除床两半自身），再床尾邻格
        List<Block> candidates = new ArrayList<>();
        collectRing(head, foot, candidates);
        if (foot != null) collectRing(foot, head, candidates);

        // 排序：先床头环（保持顺序），床尾环在后；环内按 X→Z 升序（西北优先、南先于东）
        candidates.sort(Comparator
                .comparingInt((Block b) -> b.getY() == bedY ? 0 : 1)
                .thenComparingInt(Block::getX)
                .thenComparingInt(Block::getZ));

        // 两轮：先无危险落点，再放宽（原版同样优先安全位）
        for (int pass = 0; pass < 2; pass++) {
            for (Block c : candidates) {
                if (!isStandable(world, c.getX(), c.getY(), c.getZ())) continue;
                boolean dangerous = isDangerous(world, c.getX(), c.getY(), c.getZ())
                        || DANGEROUS.contains(world.getBlockAt(c.getX(), c.getY() - 1, c.getZ()).getType());
                if (pass == 0 && dangerous) continue;
                float yaw = faceBlockYaw(c, head);
                return new Location(world, c.getX() + 0.5, c.getY(), c.getZ() + 0.5, yaw, 0f);
            }
        }

        // 全被阻挡：回退到床本身向上找安全格
        Location loc = new Location(world, head.getX() + 0.5, head.getY(), head.getZ() + 0.5);
        for (int y = head.getY(); y < world.getMaxHeight(); y++) {
            if (isStandable(world, head.getX(), y, head.getZ())) {
                float yaw = faceBlockYaw(head.getX(), y, head.getZ(), head);
                return new Location(world, head.getX() + 0.5, y, head.getZ() + 0.5, yaw, 0f);
            }
            loc.setY(y + 1);
        }
        return null;
    }

    /** 收集 center 的8个水平邻格（排除 exclude 床块及其配对半块） */
    private void collectRing(Block center, Block exclude, List<Block> out) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block n = center.getRelative(dx, 0, dz);
                if (isBed(n)) continue;
                if (exclude != null && isBedPartOf(n, exclude)) continue;
                out.add(n);
            }
        }
    }

    private boolean isBedPartOf(Block b, Block anyBedPart) {
        Block[] parts = getBedParts(anyBedPart);
        if (parts == null) return false;
        for (Block p : parts) {
            if (p != null && p.equals(b)) return true;
        }
        return false;
    }

    /** 站位合法性：落点与上方一格无碰撞，脚下有支撑 */
    private boolean isStandable(World world, int x, int y, int z) {
        if (y - 1 < world.getMinHeight()) return false;
        Block feet = world.getBlockAt(x, y, z);
        Block headSpace = world.getBlockAt(x, y + 1, z);
        Block ground = world.getBlockAt(x, y - 1, z);
        if (feet.getType() == Material.LAVA || feet.getType() == Material.WATER) return false;
        if (!feet.isPassable() || !headSpace.isPassable()) return false;
        return !ground.isPassable() || ground.getType() == Material.LADDER
                || ground.getType() == Material.VINE;
    }

    private boolean isDangerous(World world, int x, int y, int z) {
        return DANGEROUS.contains(world.getBlockAt(x, y, z).getType());
    }

    private float faceBlockYaw(Block from, Block target) {
        return faceBlockYaw(from.getX(), from.getY(), from.getZ(), target);
    }

    private float faceBlockYaw(int x, int y, int z, Block target) {
        double dx = (target.getX() + 0.5) - (x + 0.5);
        double dz = (target.getZ() + 0.5) - (z + 0.5);
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
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
        bedLocations.clear();
    }
}