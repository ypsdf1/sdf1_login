package Sdf1_login;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class UserGroupManager {

    private final Main plugin;
    private Connection db;
    private final Map<String, UserGroupConfig> groupConfigs = new ConcurrentHashMap<>();

    public UserGroupManager(Main plugin) {
        this.plugin = plugin;
        initDB();
        loadGroupConfigs();
        startExpiryChecker();
    }

    // ===== DB =====

    private void initDB() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(plugin.getDataFolder(), "usergroup.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS user_group_config ("
                    + "group_name TEXT PRIMARY KEY,"
                    + "display_name TEXT DEFAULT '',"
                    + "display_color TEXT DEFAULT '§f',"
                    + "display_emoji TEXT DEFAULT '',"
                    + "priority INTEGER DEFAULT 0,"
                    + "is_permanent INTEGER DEFAULT 1,"
                    + "duration_minutes INTEGER DEFAULT 0,"
                    + "pack_url TEXT DEFAULT '',"
                    + "pack_hash TEXT DEFAULT '',"
                    + "pack_prompt TEXT DEFAULT 'Custom Resource Pack',"
                    + "pack_size INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS user_group_member ("
                    + "player_name TEXT NOT NULL,"
                    + "group_name TEXT NOT NULL,"
                    + "added_by TEXT DEFAULT 'system',"
                    + "added_time INTEGER DEFAULT 0,"
                    + "expiry_time INTEGER DEFAULT 0,"
                    + "PRIMARY KEY(player_name, group_name))");
            st.close();
        } catch (Exception e) {
            throw new RuntimeException("[UserGroup] DB init failed: " + e.getMessage(), e);
        }
    }

    // ===== 配置加载 =====

    public void loadGroupConfigs() {
        groupConfigs.clear();
        File dir = new File(plugin.getDataFolder(), "用户组");
        if (!dir.exists()) dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".txt"));
        if (files == null) return;

        for (File f : files) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(
                        f.toPath(), StandardCharsets.UTF_8);
                UserGroupConfig cfg = parseGroupConfig(lines);
                if (cfg != null) {
                    groupConfigs.put(cfg.name, cfg);
                    saveGroupConfigToDB(cfg);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[UserGroup] Failed to load: " + f.getName());
            }
        }
    }

    private UserGroupConfig parseGroupConfig(List<String> lines) {
        UserGroupConfig cfg = new UserGroupConfig();
        boolean inBlock = false;

        for (String raw : lines) {
            String l = raw;

            // 块注释
            if (l.contains("<!--")) { inBlock = true; continue; }
            if (l.contains("-->")) { inBlock = false; continue; }
            if (l.contains("/*")) { inBlock = true; continue; }
            if (l.contains("*/")) { inBlock = false; continue; }
            if (inBlock) continue;

            // 行注释
            l = l.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#") || l.startsWith("//")) continue;

            // 行内注释
            int h = l.indexOf('#');
            int s = l.indexOf("//");
            if (h >= 0) l = l.substring(0, h).trim();
            if (s >= 0 && (h < 0 || s < h)) l = l.substring(0, s).trim();
            if (l.isEmpty()) continue;

            // 解析键值对
            String key = l;
            String val = "";
            int idx = l.indexOf('\uff1a');
            if (idx < 0) idx = l.indexOf(':');
            if (idx >= 0) {
                key = l.substring(0, idx).trim();
                val = l.substring(idx + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\""))
                    val = val.substring(1, val.length() - 1);
            }

            switch (key) {
                case "组名":
                case "name": cfg.name = val; break;
                case "显示名":
                case "display": cfg.displayName = val; break;
                case "颜色":
                case "color": cfg.displayColor = val; break;
                case "符号":
                case "emoji": cfg.displayEmoji = val; break;
                case "优先级":
                case "priority":
                    try { cfg.priority = Integer.parseInt(val); } catch (Exception ignored) {}
                    break;
                case "类型":
                case "type":
                    cfg.permanent = val.equalsIgnoreCase("永久") || val.equalsIgnoreCase("permanent");
                    break;
                case "时长":
                case "duration":
                    try { cfg.durationMinutes = Integer.parseInt(val); } catch (Exception ignored) {}
                    break;
                case "资源包URL":
                case "pack_url": cfg.packUrl = val; break;
                case "资源包Hash":
                case "pack_hash": cfg.packHash = val; break;
                case "资源包提示":
                case "pack_prompt": cfg.packPrompt = val; break;
                case "资源包大小":
                case "pack_size":
                    try { cfg.packSize = Integer.parseInt(val); } catch (Exception ignored) {}
                    break;
                case "本地文件":
                case "local_file": cfg.localFile = val; break;
            }
        }

        if (cfg.name == null || cfg.name.isEmpty()) return null;
        return cfg;
    }

    private void saveGroupConfigToDB(UserGroupConfig cfg) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_config "
                            + "(group_name, display_name, display_color, display_emoji,"
                            + " priority, is_permanent, duration_minutes,"
                            + " pack_url, pack_hash, pack_prompt, pack_size)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)");
            ps.setString(1, cfg.name);
            ps.setString(2, cfg.displayName);
            ps.setString(3, cfg.displayColor);
            ps.setString(4, cfg.displayEmoji);
            ps.setInt(5, cfg.priority);
            ps.setInt(6, cfg.permanent ? 1 : 0);
            ps.setInt(7, cfg.durationMinutes);
            ps.setString(8, cfg.packUrl);
            ps.setString(9, cfg.packHash);
            ps.setString(10, cfg.packPrompt);
            ps.setInt(11, cfg.packSize);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ===== 玩家操作 =====

    public boolean addPlayer(String player, String groupName, String addedBy, long durationMinutes) {
        UserGroupConfig cfg = groupConfigs.get(groupName);
        if (cfg == null) return false;
        long now = System.currentTimeMillis();
        long expiry = cfg.permanent ? 0 : now + durationMinutes * 60 * 1000;
        if (!cfg.permanent && durationMinutes > 0) expiry = now + durationMinutes * 60 * 1000;
        else if (!cfg.permanent) expiry = now + cfg.durationMinutes * 60 * 1000;
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO user_group_member "
                            + "(player_name, group_name, added_by, added_time, expiry_time)"
                            + " VALUES (?,?,?,?,?)");
            ps.setString(1, player);
            ps.setString(2, groupName);
            ps.setString(3, addedBy);
            ps.setLong(4, now);
            ps.setLong(5, expiry);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
        Player p = plugin.getServer().getPlayerExact(player);
        if (p != null) sendResourcePack(p);
        return true;
    }

    public boolean removePlayer(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            int rows = ps.executeUpdate();
            ps.close();
            return rows > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public List<Map<String, Object>> getPlayerGroups(String player) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT * FROM user_group_member WHERE player_name=?");
            ps.setString(1, player);
            ResultSet rs = ps.executeQuery();
            long now = System.currentTimeMillis();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("group_name", rs.getString("group_name"));
                row.put("added_by", rs.getString("added_by"));
                long expiry = rs.getLong("expiry_time");
                row.put("expiry_time", expiry);
                row.put("expired", expiry > 0 && expiry <= now);
                list.add(row);
            }
            rs.close(); ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public UserGroupConfig getHighestGroup(String player) {
        List<Map<String, Object>> groups = getPlayerGroups(player);
        long now = System.currentTimeMillis();
        UserGroupConfig best = null;
        for (Map<String, Object> g : groups) {
            long expiry = (long) g.get("expiry_time");
            if (expiry > 0 && expiry <= now) continue;
            String name = (String) g.get("group_name");
            UserGroupConfig cfg = groupConfigs.get(name);
            if (cfg != null && (best == null || cfg.priority > best.priority)) {
                best = cfg;
            }
        }
        return best;
    }

    public boolean isPlayerInGroup(String player, String groupName) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT 1 FROM user_group_member WHERE player_name=? AND group_name=?");
            ps.setString(1, player);
            ps.setString(2, groupName);
            boolean exists = ps.executeQuery().next();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }

    // ===== 过期自动移除 =====

    private void startExpiryChecker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiry();
            }
        }.runTaskTimerAsynchronously(plugin, 1200L, 1200L); // 每分钟检查
    }

    private void checkExpiry() {
        try {
            long now = System.currentTimeMillis();
            PreparedStatement ps = db.prepareStatement(
                    "SELECT player_name, group_name FROM user_group_member "
                            + "WHERE expiry_time > 0 AND expiry_time <= ?");
            ps.setLong(1, now);
            ResultSet rs = ps.executeQuery();
            List<String[]> expired = new ArrayList<>();
            while (rs.next()) {
                expired.add(new String[]{
                        rs.getString("player_name"),
                        rs.getString("group_name")});
            }
            rs.close(); ps.close();

            for (String[] e : expired) {
                PreparedStatement del = db.prepareStatement(
                        "DELETE FROM user_group_member WHERE player_name=? AND group_name=?");
                del.setString(1, e[0]);
                del.setString(2, e[1]);
                del.executeUpdate();
                del.close();
                plugin.getLogger().info("[UserGroup] 过期移除: " + e[0] + " -> " + e[1]);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ===== 资源包分发 =====

    public void sendResourcePack(Player p) {
        UserGroupConfig cfg = getHighestGroup(p.getName());
        if (cfg == null || cfg.packUrl == null || cfg.packUrl.isEmpty()) return;

        String url = cfg.packUrl;
        String prompt = cfg.packPrompt != null
                ? cfg.packPrompt : "Custom Resource Pack";

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            try {
                p.setResourcePack(url, new byte[0], prompt, false);
                plugin.getLogger().info("[UserGroup] 资源包已发送: "
                        + p.getName() + " -> " + cfg.displayName);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[UserGroup] 资源包发送失败: " + e.getMessage());
            }
        }, 40L);
    }

    public Map<String, UserGroupConfig> getGroupConfigs() {
        return groupConfigs;
    }

    // ===== 配置类 =====

    public static class UserGroupConfig {
        public String name;
        public String displayName = "";
        public String displayColor = "§f";
        public String displayEmoji = "";
        public int priority = 0;
        public boolean permanent = true;
        public int durationMinutes = 0;
        public String packUrl = "";
        public String packHash = "";
        public String packPrompt = "Custom Resource Pack";
        public int packSize = 0;
        public String localFile = "";
    }
}
