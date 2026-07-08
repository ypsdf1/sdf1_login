package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.*;


import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PVPManager implements Listener {

    private final Main plugin;
    private Connection pvpDb;

    private final Map<String, PVPRegion>
            regions = new ConcurrentHashMap<>();
    private final Map<String, String>
            inRegion = new ConcurrentHashMap<>();
    private final Map<String, Integer>
            sessionKills =
            new ConcurrentHashMap<>();
    private final Map<String, Long>
            lastKillTime =
            new ConcurrentHashMap<>();
    private final Map<String, String[]>
            selectState =
            new ConcurrentHashMap<>();
    private final Map<String, KillSession>
            killSessions =
            new ConcurrentHashMap<>();
    private final Set<String> tripleAnnounced =
            ConcurrentHashMap.newKeySet();
    private final Map<String, Long> lastScoreboardRefresh = new ConcurrentHashMap<>();


    // 边框显示
    private final Set<String>
            permanentBorders =
            ConcurrentHashMap.newKeySet();
    private final Set<String>
            tempBorders =
            ConcurrentHashMap.newKeySet();


    public static class KillSession {
        public int count = 0;
        public long firstKill = 0;
        public long lastKill = 0;
        public boolean tripleAnnounced =
                false;
        public String region = "";
    }

    public static class PVPRegion {
        public String name;
        public String world;
        public int x1, y1, z1;
        public int x2, y2, z2;
        public int minX, minY, minZ;
        public int maxX, maxY, maxZ;

        public void calcBounds() {
            minX = Math.min(x1, x2);
            minY = Math.min(y1, y2);
            minZ = Math.min(z1, z2);
            maxX = Math.max(x1, x2);
            maxY = Math.max(y1, y2);
            maxZ = Math.max(z1, z2);
        }

        public boolean contains(
                Location loc) {
            if (loc == null) return false;
            if (!loc.getWorld().getName()
                    .equals(world))
                return false;
            int x = loc.getBlockX();
            int y = loc.getBlockY();
            int z = loc.getBlockZ();
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    public PVPManager(Main plugin) {
        this.plugin = plugin;
        initDB();
        loadRegions();
    }

    // ===== 独立数据库 =====

    private void initDB() {
        try {
            Class.forName(
                    "org.sqlite.JDBC");
            File dbFile = new File(
                    plugin.getDataFolder(),
                    "pvp.db");
            pvpDb = DriverManager.getConnection(
                    "jdbc:sqlite:"
                            + dbFile.getAbsolutePath());
            Statement st =
                    pvpDb.createStatement();
            st.execute(
                    "PRAGMA journal_mode=WAL");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + "pvp_regions ("
                            + "id INTEGER PRIMARY KEY "
                            + "AUTOINCREMENT,"
                            + "name TEXT NOT NULL "
                            + "UNIQUE,"
                            + "world TEXT NOT NULL,"
                            + "x1 INTEGER NOT NULL,"
                            + "y1 INTEGER NOT NULL,"
                            + "z1 INTEGER NOT NULL,"
                            + "x2 INTEGER NOT NULL,"
                            + "y2 INTEGER NOT NULL,"
                            + "z2 INTEGER NOT NULL,"
                            + "create_time INTEGER "
                            + "DEFAULT 0)");
            st.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + "pvp_kills ("
                            + "player_name TEXT "
                            + "NOT NULL,"
                            + "region_name TEXT "
                            + "NOT NULL,"
                            + "kills INTEGER "
                            + "DEFAULT 0,"
                            + "deaths INTEGER "
                            + "DEFAULT 0,"
                            + "PRIMARY KEY"
                            + "(player_name, "
                            + "region_name))");
            st.close();
        } catch (Exception e) {
            throw new RuntimeException(
                    "[PVP] DB init failed: "
                            + e.getMessage(), e);
        }
    }

    // ===== DB 操作 =====

    private void saveRegion(String name,
                            String world,
                            int x1, int y1, int z1,
                            int x2, int y2, int z2) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "INSERT OR REPLACE "
                                    + "INTO pvp_regions"
                                    + " (name, world,"
                                    + " x1,y1,z1,"
                                    + " x2,y2,z2,"
                                    + " create_time)"
                                    + " VALUES "
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

    private Map<String, Object>
    getRegion(String name) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "SELECT * FROM "
                                    + "pvp_regions "
                                    + "WHERE name=?");
            ps.setString(1, name);
            ResultSet rs =
                    ps.executeQuery();
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

    private List<Map<String, Object>>
    getAllRegions() {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            Statement st =
                    pvpDb.createStatement();
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

    private void deleteRegion(String name) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
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

    private int getKills(String player,
                         String region) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "SELECT kills FROM "
                                    + "pvp_kills "
                                    + "WHERE "
                                    + "player_name=?"
                                    + " AND "
                                    + "region_name=?");
            ps.setString(1, player);
            ps.setString(2, region);
            ResultSet rs =
                    ps.executeQuery();
            int v = 0;
            if (rs.next())
                v = rs.getInt("kills");
            rs.close();
            ps.close();
            return v;
        } catch (SQLException e) {
            return 0;
        }
    }

    private int getDeaths(String player,
                          String region) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "SELECT deaths FROM "
                                    + "pvp_kills "
                                    + "WHERE "
                                    + "player_name=?"
                                    + " AND "
                                    + "region_name=?");
            ps.setString(1, player);
            ps.setString(2, region);
            ResultSet rs =
                    ps.executeQuery();
            int v = 0;
            if (rs.next())
                v = rs.getInt("deaths");
            rs.close();
            ps.close();
            return v;
        } catch (SQLException e) {
            return 0;
        }
    }

    private void addKill(String player,
                         String region) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "INSERT INTO pvp_kills"
                                    + " (player_name,"
                                    + " region_name,"
                                    + " kills,deaths)"
                                    + " VALUES "
                                    + "(?,?,1,0) "
                                    + "ON CONFLICT"
                                    + "(player_name,"
                                    + " region_name)"
                                    + " DO UPDATE "
                                    + "SET kills="
                                    + "kills+1");
            ps.setString(1, player);
            ps.setString(2, region);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void addDeath(String player,
                          String region) {
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "INSERT INTO pvp_kills"
                                    + " (player_name,"
                                    + " region_name,"
                                    + " kills,deaths)"
                                    + " VALUES "
                                    + "(?,?,0,1) "
                                    + "ON CONFLICT"
                                    + "(player_name,"
                                    + " region_name)"
                                    + " DO UPDATE "
                                    + "SET deaths="
                                    + "deaths+1");
            ps.setString(1, player);
            ps.setString(2, region);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private List<Map<String, Object>>
    getTop(String region, int limit) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps =
                    pvpDb.prepareStatement(
                            "SELECT player_name,"
                                    + " kills,deaths"
                                    + " FROM "
                                    + "pvp_kills "
                                    + "WHERE "
                                    + "region_name=?"
                                    + " AND kills>0"
                                    + " ORDER BY kills"
                                    + " DESC LIMIT ?");
            ps.setString(1, region);
            ps.setInt(2, limit);
            ResultSet rs =
                    ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("player_name",
                        rs.getString(
                                "player_name"));
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

    public Set<String> getRegionNames() {
        return regions.keySet();
    }

    // ===== 加载 =====

    private void loadRegions() {
        regions.clear();
        List<Map<String, Object>> list =
                getAllRegions();
        for (Map<String, Object> row : list) {
            PVPRegion r = new PVPRegion();
            r.name = (String) row
                    .get("name");
            r.world = (String) row
                    .get("world");
            r.x1 = ((Number) row
                    .get("x1")).intValue();
            r.y1 = ((Number) row
                    .get("y1")).intValue();
            r.z1 = ((Number) row
                    .get("z1")).intValue();
            r.x2 = ((Number) row
                    .get("x2")).intValue();
            r.y2 = ((Number) row
                    .get("y2")).intValue();
            r.z2 = ((Number) row
                    .get("z2")).intValue();
            r.calcBounds();
            regions.put(r.name, r);
        }
    }

    // ===== 工具 =====

    public ItemStack createTool() {
        ItemStack tool = new ItemStack(
                Material.BLAZE_ROD);
        ItemMeta m = tool.getItemMeta();
        m.setDisplayName(
                "§6§l[PVP] 区域选择工具");
        m.setLore(Arrays.asList(
                "§7右键：设置第一个角",
                "§7左键：设置第二个角"));
        m.getPersistentDataContainer()
                .set(new NamespacedKey(
                                plugin, "pvp_tool"),
                        PersistentDataType.STRING,
                        "tool");
        tool.setItemMeta(m);
        return tool;
    }

    private boolean isPVPTool(
            ItemStack item) {
        if (item == null) return false;
        ItemMeta m = item.getItemMeta();
        if (m == null) return false;
        return m.getPersistentDataContainer()
                .has(new NamespacedKey(
                                plugin, "pvp_tool"),
                        PersistentDataType.STRING);
    }

    public void giveTool(Player p) {
        boolean has = false;
        for (ItemStack it : p.getInventory()
                .getContents()) {
            if (isPVPTool(it)) {
                has = true;
                break;
            }
        }
        if (!has) {
            p.getInventory()
                    .addItem(createTool());
        }
    }

    // ===== 边框粒子 =====

    private void drawBorder(Player target,
                            PVPRegion r) {
        if (r == null) return;
        World w = Bukkit.getWorld(r.world);
        if (w == null) return;
        int step = 2;
        drawLine(target, w,
                r.minX, r.minY, r.minZ,
                r.maxX, r.minY, r.minZ, step);
        drawLine(target, w,
                r.maxX, r.minY, r.minZ,
                r.maxX, r.minY, r.maxZ, step);
        drawLine(target, w,
                r.maxX, r.minY, r.maxZ,
                r.minX, r.minY, r.maxZ, step);
        drawLine(target, w,
                r.minX, r.minY, r.maxZ,
                r.minX, r.minY, r.minZ, step);
        drawLine(target, w,
                r.minX, r.maxY, r.minZ,
                r.maxX, r.maxY, r.minZ, step);
        drawLine(target, w,
                r.maxX, r.maxY, r.minZ,
                r.maxX, r.maxY, r.maxZ, step);
        drawLine(target, w,
                r.maxX, r.maxY, r.maxZ,
                r.minX, r.maxY, r.maxZ, step);
        drawLine(target, w,
                r.minX, r.maxY, r.maxZ,
                r.minX, r.maxY, r.minZ, step);
        drawLine(target, w,
                r.minX, r.minY, r.minZ,
                r.minX, r.maxY, r.minZ, step);
        drawLine(target, w,
                r.maxX, r.minY, r.minZ,
                r.maxX, r.maxY, r.minZ, step);
        drawLine(target, w,
                r.maxX, r.minY, r.maxZ,
                r.maxX, r.maxY, r.maxZ, step);
        drawLine(target, w,
                r.minX, r.minY, r.maxZ,
                r.minX, r.maxY, r.maxZ, step);
    }

    private void drawLine(Player target,
                          World w,
                          int x1, int y1, int z1,
                          int x2, int y2, int z2,
                          int step) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double dist = Math.sqrt(
                dx * dx + dy * dy + dz * dz);
        int steps = (int) (dist / step);
        if (steps < 1) steps = 1;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location loc = new Location(w,
                    x1 + dx * t,
                    y1 + dy * t + 0.1,
                    z1 + dz * t);
            w.spawnParticle(
                    Particle.FLAME, loc, 1,
                    0.0, 0.0, 0.0, 0.0);
        }
    }

    // ===== 每秒 tick =====

    public void tickRegions() {
        long now =
                System.currentTimeMillis();

        // 玩家区域检测
        for (Player p :
                Bukkit.getOnlinePlayers()) {
            String pn = p.getName();
            Location loc = p.getLocation();
            String inside = null;
            for (PVPRegion r :
                    regions.values()) {
                if (r.contains(loc)) {
                    inside = r.name;
                    break;
                }
            }
            String was = inRegion.get(pn);

            if (inside != null && !inside.equals(was)) {
                inRegion.put(pn, inside);
                if (was == null) {
                    String reg = inside;
                    final Player pp = p;
                    Bukkit.getScheduler()
                            .runTaskLater(plugin,
                                    () ->
                                            updateScoreboard(
                                                    pp, reg),
                                    1L);
                }

        } else if (inside == null
                    && was != null) {
                inRegion.remove(pn);
                // 不删 sessionKills，等倒计时结束再清
                removeScoreboard(p);
                final Player pp = p;
                Bukkit.getScheduler()
                        .runTaskLater(plugin,
                                () -> plugin
                                        .giveMenuSnowball(pp),
                                5L);
            }

        }

        // 5分钟超时重置连杀session
        for (String pname :
                new ArrayList<>(
                        killSessions
                                .keySet())) {
            KillSession ks =
                    killSessions.get(pname);
            if (ks == null) continue;
            if (now - ks.lastKill
                    > 5 * 60 * 1000) {
                killSessions.remove(pname);
            }
        }

        // 5分钟无击杀 → 计数榜切总榜
        // 5分钟无击杀 → 归档并清零
        for (String rName :
                new ArrayList<>(
                        lastKillTime
                                .keySet())) {
            long last = lastKillTime
                    .getOrDefault(rName, 0L);
            if (now - last > 5 * 60 * 1000) {
                // 清零该区域所有sessionKills
                sessionKills.keySet()
                        .removeIf(k -> k
                                .startsWith(
                                        rName + ":"));
                // 切换到总榜
                refreshScoreboard(rName);
                lastKillTime.remove(rName);
                lastScoreboardRefresh.remove(rName);

            }
        }


        // 边框渲染
        for (Player p :
                Bukkit.getOnlinePlayers()) {
            String pr =
                    inRegion.get(p.getName());
            if (pr == null) continue;
            PVPRegion r = regions.get(pr);
            if (r == null) continue;
            if (permanentBorders
                    .contains(pr)) {
                drawBorder(p, r);
            }
            if (tempBorders
                    .contains(pr)) {
                drawBorder(p, r);
            }
            // 每秒刷新活跃区域的计分板倒计时
            for (String rName : new ArrayList<>(lastKillTime.keySet())) {
                long last = lastScoreboardRefresh.getOrDefault(rName, 0L);
                if (now - last >= 1000) {
                    lastScoreboardRefresh.put(rName, now);
                    for (Player tp : Bukkit.getOnlinePlayers()) {
                        String inR = inRegion.get(tp.getName());
                        if (rName.equals(inR)) {
                            showCounter(tp, rName);
                        }
                    }
                }
            }



        }
    }

    private void broadcastWithSound(String msg, Sound sound) {
        Bukkit.broadcastMessage(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
        }
    }


    private void initSession(String player,
                             String region) {
        killSessions.remove(player);
        tripleAnnounced.remove(
                region + ":" + player);
        KillSession s = new KillSession();
        s.region = region;
        s.firstKill =
                System.currentTimeMillis();
        s.lastKill =
                System.currentTimeMillis();
        killSessions.put(player, s);
    }

    // ===== 击杀事件 =====

    @EventHandler
    public void onDeath(
            EntityDeathEvent event) {
        if (!(event.getEntity()
                instanceof Player)) return;
        Player victim =
                (Player) event.getEntity();
        org.bukkit.entity.Entity kc =
                event.getDamageSource().getCausingEntity();
        Player killer =
                (kc instanceof Player) ? (Player) kc : null;
        if (killer == null) return;
        if (killer.equals(victim)) return;

        String region =
                inRegion.get(
                        victim.getName());
        if (region == null) return;

        String vName = victim.getName();
        String kName = killer.getName();

        // 总榜
        addKill(kName, region);
        addDeath(vName, region);

        // 计数榜
        String kKey =
                region + ":" + kName;
        sessionKills.merge(kKey, 1,
                Integer::sum);
        lastKillTime.put(region,
                System.currentTimeMillis());

        // 终结检测
        // 终结检测
        KillSession victimSession =
                killSessions.get(vName);
        if (victimSession != null
                && victimSession.count >= 3) {
            String endMsg =
                    "§b§l" + kName
                            + " §e终结了 "
                            + vName + " §e的 "
                            + victimSession.count
                            + "连杀！§a恭喜终结者 "
                            + kName
                            + "§e！§7快来挑战！";
            killSessions.remove(vName);
            broadcastWithSound(endMsg, Sound.ENTITY_ENDER_DRAGON_GROWL);

            killSessions.remove(vName);
        }


        // 击杀者连杀
        KillSession ks =
                killSessions.get(kName);
        if (ks == null
                || !ks.region.equals(region)
                || System.currentTimeMillis()
                - ks.lastKill > 5 * 60 * 1000) {
            tripleAnnounced.remove(
                    region + ":" + kName);
            KillSession ns =
                    new KillSession();
            ns.region = region;
            ns.count = 1;
            ns.firstKill =
                    System.currentTimeMillis();
            ns.lastKill =
                    System.currentTimeMillis();
            killSessions.put(kName, ns);
        } else {
            ks.count++;
            ks.lastKill =
                    System.currentTimeMillis();
        }

        // 多杀通报
        KillSession killerSession =
                killSessions.get(kName);
        if (killerSession != null) {
            plugin.getLogger().info(
                    "[PVP] checkMultiKill: "
                            + kName + " count="
                            + killerSession.count
                            + " region=" + region);
            checkMultiKill(killer,
                    region, killerSession);
        }

        // 刷新计分板
        for (Player p :
                Bukkit.getOnlinePlayers()) {
            if (region.equals(
                    inRegion.get(
                            p.getName()))) {
                updateScoreboard(p, region);
            }
        }

        victim.sendMessage("§c你在「"
                + region + "」被 "
                + kName + " 击杀");
        killer.sendMessage("§a你在「"
                + region + "」击杀了 "
                + vName);
    }

    private void checkMultiKill(
            Player killer, String region,
            KillSession session) {
        int count = session.count;
        String kName = killer.getName();
        String key = region + ":" + kName;
        String msg = null;

        if (count >= 5) {
            msg = "§c§l" + kName
                    + " §e在「" + region
                    + "」达成 " + count
                    + "连杀！§7§l快来挑战！";
        } else if (count == 3) {
            if (tripleAnnounced.add(key)) {
                msg = "§6§l三杀！"
                        + kName
                        + " 势不可挡！"
                        + "§7快来挑战！";
            }
        }

        if (msg != null) {
            if (count >= 5) {
                broadcastWithSound(msg, Sound.ENTITY_ENDER_DRAGON_GROWL);
            } else {
                broadcastWithSound(msg, Sound.BLOCK_END_PORTAL_SPAWN);
            }
        }

    }



    // ===== 计分板（英文ID） =====

    private void ensureScoreboard(
            Player p, String region) {
        // 活跃击杀：每秒强制刷新（更新倒计时）
        if (lastKillTime.containsKey(region)) {
            showCounter(p, region);
            return;
        }
        // 无活跃击杀：只在没有scoreboard时创建总榜
        Scoreboard sb = p.getScoreboard();
        if (sb == null
                || sb.getObjective(
                "sdf1pvp") == null) {
            showTotal(p, region);
        }
    }


    private void updateScoreboard(
            Player p, String region) {
        if (lastKillTime
                .containsKey(region)) {
            showCounter(p, region);
        } else {
            showTotal(p, region);
        }
    }

    private void showTotal(Player p,
                           String region) {
        ScoreboardManager mgr =
                Bukkit.getScoreboardManager();
        Scoreboard sb =
                mgr.getNewScoreboard();
        Objective obj =
                sb.registerNewObjective(
                        "sdf1pvp",
                        Criteria.DUMMY,
                        "§6§l击杀总榜 ["
                                + region + "]");
        obj.setDisplaySlot(
                DisplaySlot.SIDEBAR);

        List<Map<String, Object>> top =
                getTop(region, 10);
        if (top.isEmpty()) {
            obj.getScore("§7暂无记录")
                    .setScore(0);
        } else {
            int rank = 10;
            for (Map<String, Object> row :
                    top) {
                String name = (String) row
                        .get("player_name");
                int kills = ((Number) row
                        .get("kills"))
                        .intValue();
                int deaths = ((Number) row
                        .get("deaths"))
                        .intValue();
                String line =
                        "§e#" + (11 - rank)
                                + " §f" + name
                                + " §a" + kills
                                + "§7/§c" + deaths;
                obj.getScore(line)
                        .setScore(rank);
                rank--;
            }
        }
        p.setScoreboard(sb);
    }
    private void showCounter(Player p,
                             String region) {
        ScoreboardManager mgr =
                Bukkit.getScoreboardManager();
        Scoreboard sb = mgr.getNewScoreboard();
        Objective obj =
                sb.registerNewObjective(
                        "sdf1pvp",
                        Criteria.DUMMY,
                        "§c§l实时战况 ["
                                + region + "]");
        obj.setDisplaySlot(
                DisplaySlot.SIDEBAR);

        List<String[]> entries =
                new ArrayList<>();
        for (Map.Entry<String, Integer> e :
                sessionKills.entrySet()) {
            if (!e.getKey().startsWith(
                    region + ":"))
                continue;
            if (e.getValue() <= 0) continue;
            String pName = e.getKey()
                    .substring(
                            region.length() + 1);
            entries.add(new String[]{
                    pName,
                    String.valueOf(
                            e.getValue())});
        }

        if (entries.isEmpty()) {
            obj.getScore("§7等待击杀...")
                    .setScore(1);
        } else {
            entries.sort((a, b) ->
                    Integer.compare(
                            Integer.parseInt(b[1]),
                            Integer.parseInt(a[1])));
            int rank = 10;
            for (String[] entry : entries) {
                obj.getScore(
                                "§e" + entry[0]
                                        + " §a" + entry[1])
                        .setScore(rank);
                rank--;
                if (rank < 1) break;
            }
        }

        Long last =
                lastKillTime.get(region);
        if (last != null) {
            long elapsed =
                    System.currentTimeMillis()
                            - last;
            long remaining =
                    Math.max(0,
                            5 * 60 * 1000 - elapsed);
            long secs = remaining / 1000;
            obj.getScore(
                            "§7冷却: " + secs + "s")
                    .setScore(-1);
        }

        // 关键：强制赋值新scoreboard
        p.setScoreboard(sb);
    }

    private void removeScoreboard(
            Player p) {
        ScoreboardManager mgr =
                Bukkit.getScoreboardManager();
        p.setScoreboard(
                mgr.getNewScoreboard());
    }

    private void refreshScoreboard(
            String regionName) {
        for (Player p :
                Bukkit.getOnlinePlayers()) {
            String pr =
                    inRegion.get(p.getName());
            if (regionName.equals(pr)) {
                showTotal(p, regionName);
            }
        }
    }

    // ===== 工具选择事件 =====

    @EventHandler
    public void onInteract(
            PlayerInteractEvent event) {
        Player p = event.getPlayer();
        ItemStack hand =
                p.getInventory()
                        .getItemInMainHand();
        if (!isPVPTool(hand)) return;

        if (!event.getAction()
                .isRightClick()
                && !event.getAction()
                .isLeftClick()) return;

        Location loc = p.getLocation();
        String[] state =
                selectState.get(p.getName());
        if (state == null) {
            state = new String[]{
                    loc.getWorld()
                            .getName(),
                    "", ""};
            selectState.put(
                    p.getName(), state);
        }

        String coord =
                loc.getBlockX() + ","
                        + loc.getBlockY() + ","
                        + loc.getBlockZ();

        if (event.getAction()
                .isRightClick()) {
            state[0] = loc.getWorld()
                    .getName();
            state[1] = coord;
            p.sendMessage(
                    "§a第一个角: " + coord);
        } else {
            state[2] = coord;
            p.sendMessage(
                    "§a第二个角: " + coord);
        }
        event.setCancelled(true);
    }

    // ===== 退出清理 =====

    @EventHandler
    public void onQuit(
            PlayerQuitEvent event) {
        String name =
                event.getPlayer().getName();
        inRegion.remove(name);
        selectState.remove(name);
    }

    // ===== 指令 =====

    public boolean onCommand(Player p,
                             String[] args) {
        if (args.length == 0) {
            return showStats(p,
                    p.getName());
        }
        switch (args[0].toLowerCase()) {
            case "create":
                return handleCreate(
                        p, args);
            case "stats":
                if (args.length >= 2)
                    return showStats(p,
                            args[1]);
                return showStats(p,
                        p.getName());
            case "list":
                return handleList(p);
            case "delete":
                if (args.length >= 2)
                    return handleDelete(p,
                            args[1]);
                p.sendMessage(
                        "§c用法: "
                                + "/pvp delete <名字>");
                return true;
            case "tool":
                giveTool(p);
                p.sendMessage(
                        "§a已发放"
                                + "PVP区域选择工具");
                return true;
            case "on":
                return handleBorderOn(
                        p, args);
            case "off":
                return handleBorderOff(
                        p, args);
            case "tempban":
                return handleTempBan(
                        p, args);
            case "join":
            case "arena":
                plugin.getPVPArenaManager().joinArena(p);
                return true;
            case "leave":
            case "exit":
                plugin.getPVPArenaManager().leaveArena(p);
                return true;
            default:
                showStats(p, p.getName());
                p.sendMessage("§7——— PVP指令 ———");
                p.sendMessage("§e/pvp join §7进入PVP竞技场");
                p.sendMessage("§e/pvp leave §7离开PVP竞技场");
                p.sendMessage("§e/pvp stats [玩家] §7查看战绩");
                p.sendMessage("§e/pvp list §7查看PVP区域列表");
                return true;
        }
    }

    private boolean handleBorderOn(
            Player p, String[] args) {
        String name = args.length >= 2
                ? args[1] : null;
        if (name == null) {
            p.sendMessage("§c用法: "
                    + "/pvp on <区域名>");
            return true;
        }
        if (!regions
                .containsKey(name)) {
            p.sendMessage(
                    "§c区域不存在: " + name);
            return true;
        }
        permanentBorders.add(name);
        p.sendMessage("§a已开启「"
                + name + "」永久边框显示");
        return true;
    }

    private boolean handleBorderOff(
            Player p, String[] args) {
        String name = args.length >= 2
                ? args[1] : null;
        if (name == null) {
            p.sendMessage("§c用法: "
                    + "/pvp off <区域名>");
            return true;
        }
        permanentBorders.remove(name);
        tempBorders.remove(name);
        p.sendMessage("§a已关闭「"
                + name + "」边框显示");
        return true;
    }

    private boolean handleTempBan(
            Player p, String[] args) {
        String name = args.length >= 2
                ? args[1] : null;
        if (name == null) {
            p.sendMessage("§c用法: "
                    + "/pvp tempban <区域名>");
            return true;
        }
        if (!regions
                .containsKey(name)) {
            p.sendMessage(
                    "§c区域不存在: " + name);
            return true;
        }
        tempBorders.add(name);
        p.sendMessage("§a「" + name
                + "」临时边框显示15秒");
        final String rName = name;
        Bukkit.getScheduler()
                .runTaskLater(plugin, () ->
                                tempBorders
                                        .remove(rName),
                        300L);
        return true;
    }

    private boolean handleCreate(
            Player p, String[] args) {
        if (args.length >= 8) {
            try {
                int x1 = Integer.parseInt(
                        args[1]);
                int y1 = Integer.parseInt(
                        args[2]);
                int z1 = Integer.parseInt(
                        args[3]);
                int x2 = Integer.parseInt(
                        args[4]);
                int y2 = Integer.parseInt(
                        args[5]);
                int z2 = Integer.parseInt(
                        args[6]);
                String rName = args[7];
                return createRegion(p,
                        rName,
                        p.getWorld()
                                .getName(),
                        x1, y1, z1,
                        x2, y2, z2);
            } catch (
                    NumberFormatException e) {
                p.sendMessage(
                        "§c坐标格式错误");
                return true;
            }
        }

        if (args.length >= 2) {
            String[] state =
                    selectState
                            .get(p.getName());
            if (state == null
                    || state[1].isEmpty()
                    || state[2].isEmpty()) {
                p.sendMessage(
                        "§c请先用工具选择两个角");
                return true;
            }
            String[] p1 =
                    state[1].split(",");
            String[] p2 =
                    state[2].split(",");
            return createRegion(p,
                    args[1], state[0],
                    Integer.parseInt(p1[0]),
                    Integer.parseInt(p1[1]),
                    Integer.parseInt(p1[2]),
                    Integer.parseInt(p2[0]),
                    Integer.parseInt(p2[1]),
                    Integer.parseInt(p2[2]));
        }

        p.sendMessage(
                "§c用法: /pvp create "
                        + "<x1> <y1> <z1> "
                        + "<x2> <y2> <z2> <名字>");
        p.sendMessage(
                "§c或: /pvp create "
                        + "<名字> (先用工具选择)");
        return true;
    }

    private boolean createRegion(
            Player p, String name,
            String world,
            int x1, int y1, int z1,
            int x2, int y2, int z2) {
        if (getRegion(name) != null) {
            p.sendMessage(
                    "§c区域已存在: " + name);
            return true;
        }
        PVPRegion r = new PVPRegion();
        r.name = name;
        r.world = world;
        r.x1 = x1;
        r.y1 = y1;
        r.z1 = z1;
        r.x2 = x2;
        r.y2 = y2;
        r.z2 = z2;
        r.calcBounds();
        saveRegion(name, world,
                x1, y1, z1,
                x2, y2, z2);
        regions.put(name, r);
        p.sendMessage(
                "§a§lPVP区域创建成功!");
        p.sendMessage(
                "§7名称: §f" + name);
        p.sendMessage("§7范围: §f"
                + r.minX + "," + r.minY
                + "," + r.minZ + " → "
                + r.maxX + "," + r.maxY
                + "," + r.maxZ);
        selectState.remove(p.getName());
        return true;
    }

    private boolean handleList(
            Player p) {
        List<Map<String, Object>> all =
                getAllRegions();
        if (all.isEmpty()) {
            p.sendMessage(
                    "§7暂无PVP区域");
            return true;
        }
        p.sendMessage(
                "§6§lPVP区域列表:");
        for (Map<String, Object> row :
                all) {
            p.sendMessage("§e- §f"
                    + row.get("name")
                    + " §7(" + row.get("world")
                    + ")");
        }
        return true;
    }

    private boolean handleDelete(
            Player p, String name) {
        if (getRegion(name) == null) {
            p.sendMessage(
                    "§c区域不存在: " + name);
            return true;
        }
        deleteRegion(name);
        regions.remove(name);
        permanentBorders.remove(name);
        tempBorders.remove(name);
        sessionKills.keySet()
                .removeIf(k -> k
                        .startsWith(
                                name + ":"));
        lastKillTime.remove(name);
        lastScoreboardRefresh.remove(name);
        p.sendMessage(
                "§a已删除: " + name);
        return true;
    }

    private boolean showStats(Player p,
                              String target) {
        String region =
                inRegion.get(target);
        if (region == null) {
            p.sendMessage("§6§l" + target
                    + " 的PVP战绩:");
            List<Map<String, Object>> all =
                    getAllRegions();
            boolean has = false;
            for (Map<String, Object> row :
                    all) {
                String rn = (String) row
                        .get("name");
                int kills =
                        getKills(target, rn);
                int deaths =
                        getDeaths(target, rn);
                if (kills > 0
                        || deaths > 0) {
                    p.sendMessage(
                            "§e- §f" + rn
                                    + " §a击杀:" + kills
                                    + " §c死亡:" + deaths);
                    has = true;
                }
            }
            if (!has)
                p.sendMessage(
                        "§7暂无记录");
        } else {
            int kills =
                    getKills(target, region);
            int deaths =
                    getDeaths(target, region);
            double kd = deaths > 0
                    ? (double) kills / deaths
                    : kills;
            p.sendMessage("§6§l" + target
                    + " @ " + region);
            p.sendMessage(
                    "§a击杀: " + kills
                            + "  §c死亡: " + deaths
                            + "  §eKD: "
                            + String.format(
                            "%.2f", kd));
        }
        return true;
    }
}
