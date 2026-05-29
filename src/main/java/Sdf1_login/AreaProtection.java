package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import java.sql.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import java.util.Iterator;
import java.util.Set;
import java.util.HashSet;



public class AreaProtection implements Listener {

    private final Main plugin;
    private final Map<UUID, Long> lastLeaveTime
            = new HashMap<>();
    private final Map<UUID, List<PotionEffectType>> pendingClear
            = new HashMap<>();
    private final Map<UUID, List<PotionEffectType>> activeEffects
            = new HashMap<>();
    private final Map<UUID, Set<String>> playerAreas
            = new HashMap<>();
    private final File areaDir;
    private final Map<String, AreaConfig> areas =
            new ConcurrentHashMap<>();
    private Connection dbConnection;

    // 全局白名单（玩家）
    private final Set<String> globalPlayerWhitelist =
            ConcurrentHashMap.newKeySet();
    // 区域白名单 key=区域名 value=玩家名集合
    private final Map<String, Set<String>> areaPlayerWhitelist =
            new ConcurrentHashMap<>();
    // 全局黑名单（物品ID）
    private final Set<String> globalItemBlacklist =
            ConcurrentHashMap.newKeySet();
    // 区域黑名单
    private final Map<String, Set<String>> areaItemBlacklist =
            new ConcurrentHashMap<>();

    // 选地
    private static final Material WAND = Material.BREEZE_ROD;

    private final Map<UUID, Location> pos1 =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2 =
            new ConcurrentHashMap<>();
    private final Set<UUID> selecting =
            ConcurrentHashMap.newKeySet();
    public boolean denyRaid = false;

    // 边框显示
    private final Map<UUID, Integer> displayTaskIds =
            new ConcurrentHashMap<>();
    private final Map<String, Set<String>> globalWhitelist
            = new HashMap<>();
    private final Map<String, Set<String>> areaWhitelist
            = new HashMap<>();
    private final Map<UUID, Long> protectedEntities
            = new ConcurrentHashMap<>();
    // 记录每个玩家当前所在的区域名
    private final Map<String, String> playerCurrentArea
            = new HashMap<>();


    public AreaProtection(Main plugin) {
        this.plugin = plugin;
        this.areaDir = new File(plugin.getDataFolder(), "区域防护");
        if (!areaDir.exists()) areaDir.mkdirs();
        initDatabase();
        writeDefaultConfig();
        loadAllAreas();
        loadWhitelists();
    }

    // 保存玩家进入区域时给予的效果
    private void savePlayerEffects(UUID uid, String playerName,
                                   String areaName, List<PotionEffectType> effects) {
        try {
            // 先删除该区域旧记录，避免重复
            PreparedStatement del = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            del.setString(1, uid.toString());
            del.setString(2, areaName);
            del.executeUpdate();
            del.close();

            PreparedStatement ps = dbConnection.prepareStatement(
                    "INSERT INTO player_effects "
                            + "(uuid, player_name, area_name, "
                            + "effect_type, effect_level, "
                            + "effect_duration, enter_time) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)");
            long now = System.currentTimeMillis();
            for (PotionEffectType type : effects) {
                ps.setString(1, uid.toString());
                ps.setString(2, playerName);
                ps.setString(3, areaName);
                ps.setString(4, type.getName());
                ps.setInt(5, 0);
                ps.setInt(6, 999);
                ps.setLong(7, now);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 保存效果失败: " + e.getMessage());
        }
    }

    // 查询玩家在某个区域获得的效果
    private List<PotionEffectType> getPlayerEffects(
            UUID uid, String areaName) {
        List<PotionEffectType> list = new ArrayList<>();
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT effect_type FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            ps.setString(1, uid.toString());
            ps.setString(2, areaName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                PotionEffectType type = PotionEffectType
                        .getByName(rs.getString("effect_type"));
                if (type != null) list.add(type);
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询效果失败: " + e.getMessage());
        }
        return list;
    }

    // 删除玩家在某个区域的效果记录
    private void removePlayerEffects(UUID uid, String areaName) {
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ? AND area_name = ?");
            ps.setString(1, uid.toString());
            ps.setString(2, areaName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 删除效果失败: " + e.getMessage());
        }
    }

    // 删除玩家所有效果记录
    private void removeAllPlayerEffects(UUID uid) {
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "DELETE FROM player_effects "
                            + "WHERE uuid = ?");
            ps.setString(1, uid.toString());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 删除全部效果失败: " + e.getMessage());
        }
    }

    // 获取玩家所有记录的区域
    private List<String> getPlayerAreas(UUID uid) {
        List<String> list = new ArrayList<>();
        try {
            PreparedStatement ps = dbConnection.prepareStatement(
                    "SELECT DISTINCT area_name "
                            + "FROM player_effects WHERE uuid = ?");
            ps.setString(1, uid.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(rs.getString("area_name"));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询区域失败: " + e.getMessage());
        }
        return list;
    }

    // 查询所有待处理的效果（跨服重启也能恢复）
    private List<String[]> getAllPendingEffects() {
        List<String[]> list = new ArrayList<>();
        try {
            Statement stmt = dbConnection.createStatement();
            ResultSet rs = stmt.executeQuery(
                    "SELECT uuid, player_name, area_name, "
                            + "effect_type FROM player_effects");
            while (rs.next()) {
                list.add(new String[]{
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getString("area_name"),
                        rs.getString("effect_type")});
            }
            rs.close();
            stmt.close();
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "[防护] 查询全部效果失败: " + e.getMessage());
        }
        return list;
    }


    private void writeDefaultConfig() {
        File f = new File(areaDir, "末地保护区.txt");
        if (f.exists()) return;

        String endWorld = "world_the_end";
        for (org.bukkit.World w : Bukkit.getWorlds()) {
            if (w.getEnvironment()
                    == org.bukkit.World.Environment.THE_END) {
                endWorld = w.getName();
                break;
            }
        }

        final String ew = endWorld;
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            pw.println("# 末地保护区");
            pw.println("起点: " + ew + ",-30000000,-30000000");
            pw.println("终点: " + ew + ",30000000,30000000");
            pw.println("高度范围: 0-255");
            pw.println();
            pw.println("没收物品: BROWN_MUSHROOM,RED_MUSHROOM,MUSHROOM_STEW");
            pw.println("没收提示: 你携带了违禁品「蘑菇」，已被全部没收！");
            pw.println("通报批评: {player} 在末地违规携带蘑菇！");
            pw.println();
            pw.println("禁止放置方块");
            pw.println("禁止破坏方块");
            pw.println("禁止PVP");
            pw.println("禁止袭击");
            pw.println();
            pw.println("效果: 夜视 1 999");
            pw.println("进入提示: 末地保护区 - 请遵守规则");
            pw.println("离开提示: 已离开末地保护区");
            pw.close();
            plugin.getLogger().info("[防护] 生成末地配置, 世界: " + ew);
        } catch (IOException ignored) {
        }
    }

    public void reload() {
        loadAllAreas();
        loadWhitelists();
        recoverPendingEffects();
        plugin.getLogger().info("[防护] 已重载 "
                + areas.size() + " 个区域");
    }

    // ==================== 加载 ====================

    public void loadAllAreas() {
        // 验证所有区域的和平白名单
        plugin.getLogger().info("====== 区域解析验证 ======");
        for (Map.Entry<String, AreaConfig> entry : areas.entrySet()) {
            AreaConfig ac = entry.getValue();
            plugin.getLogger().info("[验证] " + entry.getKey()
                    + " peaceMode=" + ac.peaceMode
                    + " peaceWhitelist=" + ac.peaceWhitelist
                    + " enforceGameMode=" + ac.enforceGameMode
                    + " modeExempt=" + ac.modeExempt);
        }
        plugin.getLogger().info("====== 验证结束 ======");

        areas.clear();
        File[] files = areaDir.listFiles(
                (d, n) -> n.endsWith(".txt"));
        if (files == null) return;
        for (File f : files) {
            try {
                String name = f.getName()
                        .replace(".txt", "");
                AreaConfig ac = parseArea(name, f);
                areas.put(name, ac);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[防护] 加载失败: "
                                + f.getName());

            }

        }
        plugin.getLogger().info(
                "[防护] 共加载 " + areas.size() + " 个区域");
    }
    private void initDatabase() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "area_effects.db");
            dbConnection = DriverManager.getConnection(
                    "jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement stmt = dbConnection.createStatement();
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS player_effects ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "uuid TEXT NOT NULL,"
                            + "player_name TEXT,"
                            + "area_name TEXT NOT NULL,"
                            + "effect_type TEXT NOT NULL,"
                            + "effect_level INTEGER DEFAULT 0,"
                            + "effect_duration INTEGER DEFAULT 999,"
                            + "enter_time INTEGER NOT NULL"
                            + ")");
            stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_uuid "
                            + "ON player_effects(uuid)");
            stmt.close();
            plugin.getLogger().info("[防护] SQLite数据库初始化完成");
        } catch (SQLException e) {
            plugin.getLogger().severe("[防护] 数据库初始化失败: " + e.getMessage());
        }
    }

    /**
     * 全量容错：把全角字符统一转半角，
     * 去掉零宽字符/不可见字符，
     * 让用户怎么写都能正确解析。
     */
    private String normalizeLine(String input) {
        if (input == null || input.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // 跳过零宽字符和不可见字符
            if (c == '\u200B'   // 零宽空格
                    || c == '\u200C'   // 零宽非连接
                    || c == '\u200D'   // 零宽连接
                    || c == '\uFEFF'   // BOM
                    || c == '\u00A0'   // 不间断空格
                    || c == '\u2028'   // 行分隔符
                    || c == '\u2029'   // 段分隔符
                    || c == '\u200E'   // LTR标记
                    || c == '\u200F'   // RTL标记
                    || c == '\u2060'   // 字连接符
                    || (c >= '\u2000' && c <= '\u200A')) { // 各种空格
                continue;
            }

            // 全角→半角映射
            if (c >= '\uFF01' && c <= '\uFF5E') {
                c = (char) (c - 0xFEE0);
            }

            // 全角空格→半角空格
            if (c == '\u3000') {
                c = ' ';
            }

            sb.append(c);
        }

        return sb.toString();
    }


    private AreaConfig parseArea(String name, File f)
            throws IOException {
        AreaConfig ac = new AreaConfig();
        ac.name = name;
        BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(f),
                        StandardCharsets.UTF_8));
        String line;
        boolean inBlockComment = false;
        boolean inHtmlComment = false;

        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
// ===== 全量容错预处理：全角→半角，去零宽字符 =====
            line = normalizeLine(line);
            if (line.isEmpty()) continue;
// ===== 暴力调试：输出所有包含关键词的行 =====
            if (line.indexOf("游戏") >= 0
                    || line.indexOf("模式") >= 0
                    || line.indexOf("生存") >= 0
                    || line.indexOf("创造") >= 0
                    || line.indexOf("排除") >= 0
                    || line.indexOf("豁免") >= 0
                    || line.indexOf("强制") >= 0
                    || line.indexOf("GameMode") >= 0
                    || line.indexOf("SURVIVAL") >= 0
                    || line.indexOf("CREATIVE") >= 0) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < line.length(); i++) {
                    hex.append(String.format("U+%04X ", (int) line.charAt(i)));
                }
                plugin.getLogger().info("[暴力调试] 行=[" + line
                        + "] 长度=" + line.length()
                        + " 字符码=" + hex.toString());
            }
// ===== 兜底扫描：确保和平白名单一定能解析 =====
            if (line.indexOf("白名单") >= 0
                    || line.indexOf("排除名单") >= 0
                    || line.indexOf("模式排除") >= 0
                    || line.indexOf("模式豁免") >= 0) {
                int ci = line.indexOf(':');
                if (ci < 0) ci = line.indexOf('：');
                if (ci < 0) ci = line.indexOf('=');
                if (ci < 0) ci = line.indexOf(' ');

                // 和平白名单
                if (line.indexOf("和平") >= 0
                        && line.indexOf("白名单") >= 0
                        && ci >= 0) {
                    String rest = line.substring(ci + 1).trim();
                    rest = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : rest.split(",")) {
                        String nm = s.trim();
                        if (!nm.isEmpty()) ac.peaceWhitelist.add(nm);
                    }
                    plugin.getLogger().info("[解析兜底] 和平白名单="
                            + ac.peaceWhitelist);
                }

                // 排除名单
                if (line.indexOf("排除名单") >= 0
                        || line.indexOf("模式排除") >= 0
                        || line.indexOf("模式豁免") >= 0) {
                    if (ci >= 0) {
                        String rest = line.substring(ci + 1).trim();
                        rest = rest.replaceAll("[,，;；|、/]", ",");
                        for (String s : rest.split(",")) {
                            String nm = s.trim();
                            if (!nm.isEmpty()) ac.modeExempt.add(nm);
                        }
                        plugin.getLogger().info("[解析兜底] 排除名单="
                                + ac.modeExempt);
                    }
                }
            }
// ===== 兜底结束 =====

            if (inBlockComment) {
                int endIdx = line.indexOf("*/");
                if (endIdx >= 0) {
                    inBlockComment = false;
                    line = line.substring(endIdx + 2).trim();
                    if (line.isEmpty()) continue;
                } else {
                    continue;
                }
            }
            if (line.startsWith("/*")) {
                int endIdx = line.indexOf("*/", 2);
                if (endIdx >= 0) {
                    line = line.substring(endIdx + 2).trim();
                    if (line.isEmpty()) continue;
                } else {
                    inBlockComment = true;
                    continue;
                }
            }
            if (inHtmlComment) {
                int endIdx = line.indexOf("-->");
                if (endIdx >= 0) {
                    inHtmlComment = false;
                    line = line.substring(endIdx + 3).trim();
                    if (line.isEmpty()) continue;
                } else {
                    continue;
                }
            }
            if (line.startsWith("<!--")) {
                int endIdx = line.indexOf("-->", 4);
                if (endIdx >= 0) {
                    line = line.substring(endIdx + 3).trim();
                    if (line.isEmpty()) continue;
                } else {
                    inHtmlComment = true;
                    continue;
                }
            }
            if (line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            int hashIdx = line.indexOf("#");
            if (hashIdx > 0) {
                line = line.substring(0, hashIdx).trim();
            }
            int slashIdx = line.indexOf("//");
            if (slashIdx > 0) {
                line = line.substring(0, slashIdx).trim();
            }
            if (line.isEmpty()) continue;

            if (line.startsWith("起点:")) {
                String[] pp = line.substring("起点:".length())
                        .trim().split(",");
                if (pp.length == 3) {
                    ac.world = pp[0].trim();
                    ac.x1 = Integer.parseInt(pp[1].trim());
                    ac.z1 = Integer.parseInt(pp[2].trim());
                }
            } else if (line.startsWith("终点:")) {
                String[] pp = line.substring("终点:".length())
                        .trim().split(",");
                if (pp.length == 3) {
                    ac.world = pp[0].trim();
                    ac.x2 = Integer.parseInt(pp[1].trim());
                    ac.z2 = Integer.parseInt(pp[2].trim());
                }
            } else if (line.startsWith("高度范围:")) {
                String[] pp = line.substring("高度范围:".length())
                        .trim().split("-");
                if (pp.length == 2) {
                    ac.yMin = Integer.parseInt(pp[0].trim());
                    ac.yMax = Integer.parseInt(pp[1].trim());
                }
            } else if (line.startsWith("没收物品:")) {
                for (String s : line.substring("没收物品:".length())
                        .trim().split("[,，]"))
                    ac.confiscateItems.add(
                            s.trim().toUpperCase());
            } else if (line.startsWith("禁止使用物品:")) {
                for (String s : line.substring("禁止使用物品:".length())
                        .trim().split("[,，]"))
                    ac.denyUseItems.add(
                            s.trim().toUpperCase());
            } else if (line.equals("禁止放置方块")) {
                ac.denyBlockPlace = true;
            } else if (line.equals("禁止破坏方块")) {
                ac.denyBlockBreak = true;
            } else if (line.equals("禁止PVP")) {
                ac.denyPVP = true;
            } else if (line.equals("禁止摔伤")) {
                ac.denyFallDamage = true;
            } else if (line.equals("禁止饥饿")) {
                ac.denyHunger = true;
            } else if (line.equals("禁止一切伤害")) {
                ac.denyAllDamage = true;
            } else if (line.equals("禁止丢弃物品")) {
                ac.denyDrop = true;
            } else if (line.equals("禁止末影珍珠")) {
                ac.denyEnderPearl = true;
            } else if (line.equals("禁止使用弓箭")) {
                ac.denyBow = true;
            } else if (line.equals("禁止骑乘")) {
                ac.denyMount = true;
            } else if (line.equals("禁止袭击")) {
                ac.denyRaid = true;
            } else if (line.equals("禁止药水")) {
                ac.denyPotion = true;
            } else if (line.equals("禁止爆炸")) {
                ac.denyExplosion = true;
            } else if (line.equals("禁止燃烧")
                    || line.equals("禁止火焰蔓延")) {
                ac.denyFireSpread = true;
            } else if (line.equals("清除所有效果")
                    || line.equals("清理所有效果")) {
                ac.clearAllBadEffects = true;
            } else if (line.startsWith("清除")
                    || line.startsWith("清理")) {
                String effName = line.substring(2).trim();
                if (effName.endsWith("效果"))
                    effName = effName.substring(0,
                            effName.length() - 2);
                if (!effName.isEmpty())
                    ac.clearEffects.add(effName);
            } else if (line.contains("和平模式")
                    && !line.contains("白名单")
                    && !line.contains("定时")
                    && !line.contains("开关")) {
                ac.peaceMode = true;
                plugin.getLogger().info("[解析] 和平模式已识别");

        } else if (line.startsWith("效果")) {
                String rest = line.substring(2).trim();
                while (rest.length() > 0
                        && (rest.charAt(0) == ':'
                        || rest.charAt(0) == '：'
                        || rest.charAt(0) == ' '
                        || rest.charAt(0) == '\t')) {
                    rest = rest.substring(1);

                }
                if (!rest.isEmpty()) {
                    String[] parts = rest.split("\\s+");
                    int effLv = 1;
                    int effDur = 999;
                    if (parts.length >= 2) {
                        try {
                            effLv = Integer.parseInt(parts[1]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (parts.length >= 3) {
                        try {
                            effDur = Integer.parseInt(parts[2]);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    ac.giveEffects.add(new String[]{
                            parts[0],
                            String.valueOf(effLv),
                            String.valueOf(effDur)});
                }
            } else if (line.startsWith("通报批评:")) {
                ac.announceTemplate = line.substring(
                        "通报批评:".length()).trim();
                ac.enableAnnounce = true;
            } else if (line.startsWith("没收提示:")) {
                ac.confiscateMsg = line.substring(
                        "没收提示:".length()).trim();
            } else if (line.startsWith("进入提示:")) {
                ac.enterMsg = line.substring(
                        "进入提示:".length()).trim();
            } else if (line.startsWith("离开提示:")) {
                ac.leaveMsg = line.substring(
                        "离开提示:".length()).trim();
            } else if (line.startsWith("惩罚命令:")) {
                ac.punishCommands.add(line.substring(
                        "惩罚命令:".length()).trim());
            } else if (line.startsWith("强制游戏模式:")
                    || line.startsWith("游戏模式:")) {
                String prefix = line.startsWith("强制游戏模式:")
                        ? "强制游戏模式:" : "游戏模式:";
                String modeStr = line.substring(prefix.length())
                        .trim().toUpperCase();
                // 容错：支持多种写法
                if (modeStr.equals("CREATIVE") || modeStr.equals("创造")
                        || modeStr.equals("1")) {
                    ac.enforceGameMode = "CREATIVE";
                } else if (modeStr.equals("SURVIVAL") || modeStr.equals("生存")
                        || modeStr.equals("0")) {
                    ac.enforceGameMode = "SURVIVAL";
                } else if (modeStr.equals("ADVENTURE") || modeStr.equals("冒险")
                        || modeStr.equals("2")) {
                    ac.enforceGameMode = "ADVENTURE";
                } else if (modeStr.equals("SPECTATOR") || modeStr.equals("旁观")
                        || modeStr.equals("spectator")
                        || modeStr.equals("3")) {
                    ac.enforceGameMode = "SPECTATOR";
                }
            } else if (line.contains("排除名单")
                    || line.contains("模式排除")
                    || line.contains("模式豁免")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) colonIdx = line.indexOf('：');
                if (colonIdx < 0) colonIdx = line.indexOf('=');
                if (colonIdx >= 0) {
                    String rest = line.substring(colonIdx + 1).trim();
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String playerName = s.trim();
                        if (!playerName.isEmpty()) {
                            ac.modeExempt.add(playerName);
                        }
                    }
                }

            String rest = line.substring(/*idx*/ + 4).trim();
                while (rest.length() > 0) {
                    char c = rest.charAt(0);
                    if (c == ':' || c == '：' || c == '='
                            || c == ' ' || c == '\t') {
                        rest = rest.substring(1);
                    } else {
                        break;
                    }
                }
                if (!rest.isEmpty()) {
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String playername = s.trim();
                        if (!name.isEmpty()) ac.modeExempt.add(name);
                    }
                }

            while (rest.length() > 0) {
                    char c = rest.charAt(0);
                    if (c == ':' || c == '：' || c == '='
                            || c == ' ' || c == '\t') {
                        rest = rest.substring(1);
                    } else {
                        break;
                    }

                }
                if (!rest.isEmpty()) {
                    String cleaned = rest.replaceAll("[,，;；|、/]", ",");
                    for (String s : cleaned.split(",")) {
                        String wlName = s.trim();
                        if (!wlName.isEmpty()) {
                            ac.peaceWhitelist.add(wlName);
                        }
                    }
                }



        } else if (line.equals("禁止交互展示框")
                    || line.equals("禁止展示框")) {
                ac.denyItemFrame = true;

        }
        }
        br.close();
        return ac;
    }
// ===== 和平白名单操作 =====

    // 添加生物名字到区域和平白名单
    public boolean addPeaceName(String areaName, String creatureName) {
        AreaConfig ac = areas.get(areaName);
        if (ac == null) return false;
        ac.peaceWhitelist.add(creatureName);
        savePeaceWhitelist(ac);
        return true;
    }

    // 从区域和平白名单移除生物名字
    public boolean removePeaceName(String areaName, String creatureName) {
        AreaConfig ac = areas.get(areaName);
        if (ac == null) return false;
        ac.peaceWhitelist.remove(creatureName);
        savePeaceWhitelist(ac);
        return true;
    }

// ===== 模式排除名单操作 =====


    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Entity entity = e.getEntity();
        if (!isHostile(entity.getType().name())) return;

        Location loc = entity.getLocation();
        AreaConfig ac = getArea(
                loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(),
                loc.getBlockZ());

        if (ac == null || !ac.peaceMode) return;

        protectedEntities.put(
                entity.getUniqueId(),
                System.currentTimeMillis() + 5000);

       /* plugin.getLogger().info("[和平] "
                + entity.getType().name()
                + " 获得5秒保护期"
                + " 区域=" + ac.name
                + " 白名单=" + ac.peaceWhitelist
                + " 白名单数量=" + ac.peaceWhitelist.size());*/
    }


    public void cleanupProtectedEntities() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        Iterator<Map.Entry<UUID, Long>> it =
                protectedEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now > entry.getValue()) {
                it.remove();
                cleaned++;
            }
        }
        if (cleaned > 0) {
       /*     plugin.getLogger().info("[和平模式] 清理过期保护: "
                    + cleaned + "个 剩余:"
                    + protectedEntities.size() + "个");*/
        }
    }


    public void banHostilesWithWhitelist(Player p, AreaConfig ac) {
        long now = System.currentTimeMillis();
        // 看看附近到底有什么实体
        List<Entity> nearby = p.getNearbyEntities(48, 48, 48);
       /* plugin.getLogger().info("[和平] " + p.getName()
                + " 附近实体总数=" + nearby.size());*/
        for (Entity ent : nearby) {
            String typeName = ent.getType().name();
       /*     plugin.getLogger().info("[和平] 实体: "
                    + typeName + " isHostile=" + isHostile(typeName));*/
        }
        int scanned = 0;
        int waiting = 0;
        int saved = 0;
        int banished = 0;

        for (Entity ent : p.getNearbyEntities(48, 48, 48)) {
            if (!isHostile(ent.getType().name())) continue;
            scanned++;

            UUID entUid = ent.getUniqueId();
            Long protectUntil = protectedEntities.get(entUid);
            String customName = ent.getCustomName();

            // 有保护期记录
            if (protectUntil != null) {
                long remain = protectUntil - now;

                if (remain > 0) {
                    // 保护期内，精确剩余秒数
                    int secondsLeft = (int) Math.ceil(remain / 1000.0);
                    waiting++;

                    // 保护期内也检查名字（玩家可能刚贴上）
                    if (customName != null
                            && !customName.isEmpty()
                            && ac.peaceWhitelist.contains(customName)) {
                        protectedEntities.remove(entUid);
                        saved++;
                /*        plugin.getLogger().info("[和平] "
                                + ent.getType().name()
                                + " 命名[" + customName
                                + "] 白名单匹配，永久保留");*/
                        continue;
                    }

                  /*  plugin.getLogger().info("[和平] "
                            + ent.getType().name()
                            + " 保护期剩余约" + secondsLeft + "秒");*/
                    continue;
                }

                // 保护期到了，最终检查
                protectedEntities.remove(entUid);
           /*     plugin.getLogger().info("[和平] "
                        + ent.getType().name() + " 5秒到期");*/

                // 到期时再查一次白名单
                if (customName != null
                        && !customName.isEmpty()
                        && ac.peaceWhitelist.contains(customName)) {
                    saved++;
                 /*   plugin.getLogger().info("[和平] "
                            + ent.getType().name()
                            + " 命名[" + customName
                            + "] 到期但白名单匹配，保留");*/
                    continue;
                }

                // 没名字或不在白名单，传送虚空
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(ent.getWorld().getMinHeight() - 50);
                ent.teleport(voidLoc);
                banished++;
            /*    plugin.getLogger().info("[和平] "
                        + ent.getType().name()
                        + " 命名=[" + customName + "] 传送虚空");*/
                continue;
            }

            // 没有保护期记录（非刷怪蛋生成的或记录被清了）
            // 也检查一下白名单
            if (customName != null
                    && !customName.isEmpty()
                    && ac.peaceWhitelist.contains(customName)) {
                saved++;
             /*   plugin.getLogger().info("[和平] "
                        + ent.getType().name()
                        + " 命名[" + customName
                        + "] 无保护期但白名单匹配，保留");*/
                continue;
            }

            // 没有保护期且不在白名单，传送虚空
            Location voidLoc = ent.getLocation().clone();
            voidLoc.setY(ent.getWorld().getMinHeight() - 50);
            ent.teleport(voidLoc);
            banished++;
           /* plugin.getLogger().info("[和平] "
                    + ent.getType().name()
                    + " 无保护期，命名=[" + customName + "] 传送虚空");*/
        }

     /*   plugin.getLogger().info("[和平] "
                + " 扫描=" + scanned
                + " 等待=" + waiting
                + " 保留=" + saved
                + " 传送=" + banished
                + " 白名单=" + ac.peaceWhitelist);*/
    }

    private static final Set<String> HOSTILE_TYPES = new HashSet<>(Arrays.asList(
            "ZOMBIE", "ZOMBIE_VILLAGER", "HUSK", "DROWNED",
            "SKELETON", "STRAY", "BOGGED", "WITHER_SKELETON",
            "CREEPER", "SPIDER", "CAVE_SPIDER", "ENDERMITE",
            "SILVERFISH", "BLAZE", "GHAST", "BIG_GHAST",
            "MAGMA_CUBE", "SLIME", "WITCH",
            "GUARDIAN", "ELDER_GUARDIAN",
            "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER",
            "ILLUSIONER", "VEX",
            "PHANTOM", "SHULKER", "WARDEN", "BREEZE",
            "PIGLIN", "PIGLIN_BRUTE", "ZOGLIN",
            "ZOMBIFIED_PIGLIN", "ENDER_DRAGON"
    ));

    private boolean isHostile(String typeName) {
        return HOSTILE_TYPES.contains(typeName);
    }

    // ==================== 白/黑名单持久化 ====================

    private File getWhitelistFile(String type, String area) {
        String fileName = type + "_" + area + ".txt";
        return new File(areaDir, "_whitelists/" + fileName);
    }

    private void loadWhitelists() {
        File wlDir = new File(areaDir, "_whitelists");
        if (!wlDir.exists()) wlDir.mkdirs();

        // 读全局玩家白名单
        File f = new File(wlDir, "global_player.txt");
        if (f.exists()) {
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(f),
                                StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty())
                        globalPlayerWhitelist.add(line.toLowerCase());
                }
                br.close();
            } catch (IOException ignored) {
            }
        }

        // 读全局物品黑名单
        f = new File(wlDir, "global_item.txt");
        if (f.exists()) {
            try {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(f),
                                StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty())
                        globalItemBlacklist.add(line.toUpperCase());
                }
                br.close();
            } catch (IOException ignored) {
            }
        }

        // 读区域白名单
        for (String area : areas.keySet()) {
            loadAreaWhitelist(area, "player", areaPlayerWhitelist);
            loadAreaWhitelist(area, "item", areaItemBlacklist);
        }
    }

    private void loadAreaWhitelist(String area, String type,
                                   Map<String, Set<String>> map) {
        File wlDir = new File(areaDir, "_whitelists");
        File f = new File(wlDir, area + "_" + type + ".txt");
        if (!f.exists()) return;
        try {
            Set<String> set = map.computeIfAbsent(
                    area, k -> ConcurrentHashMap.newKeySet());
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) set.add(line.toLowerCase());
            }
            br.close();
        } catch (IOException ignored) {
        }
    }

    private void saveGlobalWhitelist() {
        File wlDir = new File(areaDir, "_whitelists");
        if (!wlDir.exists()) wlDir.mkdirs();
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(
                                    new File(wlDir, "global_player.txt")),
                            StandardCharsets.UTF_8));
            for (String s : globalPlayerWhitelist)
                pw.println(s);
            pw.close();
        } catch (IOException ignored) {
        }
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(
                                    new File(wlDir, "global_item.txt")),
                            StandardCharsets.UTF_8));
            for (String s : globalItemBlacklist)
                pw.println(s);
            pw.close();
        } catch (IOException ignored) {
        }
    }

    private void saveAreaWhitelist(String area, String type,
                                   Set<String> set) {
        File wlDir = new File(areaDir, "_whitelists");
        if (!wlDir.exists()) wlDir.mkdirs();
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(
                                    new File(wlDir,
                                            area + "_" + type + ".txt")),
                            StandardCharsets.UTF_8));
            for (String s : set) pw.println(s);
            pw.close();
        } catch (IOException ignored) {
        }
    }

    // ==================== 区域检测 ====================

    public AreaConfig getArea(String worldName,
                              int x, int y, int z) {
        for (AreaConfig ac : areas.values()) {
            if (ac.world.equalsIgnoreCase(worldName)
                    && inRect(x, z, ac)
                    && y >= ac.yMin
                    && y <= ac.yMax) {
                return ac;
            }
        }
        return null;
    }

    private boolean inRect(int x, int z, AreaConfig ac) {
        int minX = Math.min(ac.x1, ac.x2);
        int maxX = Math.max(ac.x1, ac.x2);
        int minZ = Math.min(ac.z1, ac.z2);
        int maxZ = Math.max(ac.z1, ac.z2);
        return x >= minX && x <= maxX
                && z >= minZ && z <= maxZ;
    }

    // ==================== 白名单检查 ====================

    private boolean isPlayerWhitelisted(String player,
                                        AreaConfig ac) {
        if (globalPlayerWhitelist.contains(
                player.toLowerCase()))
            return true;
        Set<String> areaList =
                areaPlayerWhitelist.get(ac.name);
        return areaList != null
                && areaList.contains(player.toLowerCase());
    }

    private boolean isItemBlacklisted(String itemId,
                                      AreaConfig ac) {
        if (globalItemBlacklist.contains(
                itemId.toUpperCase()))
            return true;
        Set<String> areaList =
                areaItemBlacklist.get(ac.name);
        return areaList != null
                && areaList.contains(itemId.toUpperCase());
    }

    // ==================== 没收 ====================

    private int confiscateItem(Player p, String itemTypeId) {
        Material mat = Material.matchMaterial(itemTypeId);
        if (mat == null) return 0;
        ItemStack[] contents = p.getInventory().getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null
                    && contents[i].getType() == mat) {
                removed += contents[i].getAmount();
                contents[i] = null;
            }
        }
        p.getInventory().setContents(contents);
        return removed;
    }

    // ==================== 边框显示 ====================
    private void showBorder(Player p, AreaConfig ac, boolean temp) {
        cancelBorder(p);
        int taskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!p.isOnline()) return;
                        Location loc = p.getLocation();
                        int px = loc.getBlockX();
                        int py = loc.getBlockY();
                        int pz = loc.getBlockZ();
                        int minX = Math.min(ac.x1, ac.x2);
                        int maxX = Math.max(ac.x1, ac.x2);
                        int minZ = Math.min(ac.z1, ac.z2);
                        int maxZ = Math.max(ac.z1, ac.z2);
                        for (int dy = 5; dy <= 30; dy += 5) {
                            for (int x = minX; x <= maxX; x += 4) {
                                for (int z = minZ; z <= maxZ; z += 4) {
                                    if (x == minX || x == maxX
                                            || z == minZ || z == maxZ) {
                                        p.spawnParticle(Particle.END_ROD,
                                                x + 0.5, py + dy, z + 0.5,
                                                1, 0, 0, 0, 0);
                                    }
                                }
                            }
                        }
                    }
                }, 0L, 20L).getTaskId();
        displayTaskIds.put(p.getUniqueId(), taskId);  // taskId 是 int
        if (temp) {
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> cancelBorder(p), 300L);
        }
    }


    private void cancelBorder(Player p) {
        Integer taskId = displayTaskIds.remove(p.getUniqueId());
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    public void onPlayerQuit(String playerName) {
        playerCurrentArea.remove(playerName);
    }

    public void clearAllPlayerAreas() {
        playerCurrentArea.clear();
    }
    private void saveModeExempt(AreaConfig ac) {
        File file = new File(areaDir, ac.name + ".txt");
        if (!file.exists()) return;

        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new FileReader(file, StandardCharsets.UTF_8));
            String line;
            boolean found = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("排除名单")
                        || trimmed.contains("模式排除")
                        || trimmed.contains("模式豁免")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("排除名单:");
                    boolean first = true;
                    for (String name : ac.modeExempt) {
                        if (!first) sb.append(",");
                        sb.append(name);
                        first = false;
                    }
                    lines.add(sb.toString());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
            reader.close();

            if (!found && !ac.modeExempt.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("排除名单:");
                boolean first = true;
                for (String name : ac.modeExempt) {
                    if (!first) sb.append(",");
                    sb.append(name);
                    first = false;
                }
                lines.add(sb.toString());
            }

            FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8);
            for (String l : lines) {
                writer.write(l + "\n");
            }
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "[防护] 保存模式排除名单失败: " + e.getMessage());
        }
    }

    // ==================== 事件监听 ====================
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // 获取当前位置的区域
        AreaConfig toArea = getArea(
                to.getWorld().getName(),
                to.getBlockX(), to.getBlockY(),
                to.getBlockZ());

        // 获取上一tick位置的区域
        AreaConfig fromArea = getArea(
                from.getWorld().getName(),
                from.getBlockX(), from.getBlockY(),
                from.getBlockZ());

        // 用 playerCurrentArea 追踪区域变化
        String toAreaName = (toArea != null) ? toArea.name : null;
        String lastAreaName = playerCurrentArea.get(p.getName());

        if (toAreaName != null) {
            if (!toAreaName.equals(lastAreaName)) {
                // 进入了新区域
                // 先离开旧区域
                if (lastAreaName != null) {
                    AreaConfig oldAc = areas.get(lastAreaName);
                    if (oldAc != null) {
                        handleLeave(p, oldAc);
                    }
                }
                // 进入新区域
                handleEnter(p, toArea);
                playerCurrentArea.put(p.getName(), toAreaName);
            }
            // 在同一区域内 → 调用 handleInside（持续检查）
            handleInside(p, toArea);
        } else {
            // 不在任何区域内
            if (lastAreaName != null) {
                // 离开了区域
                AreaConfig oldAc = areas.get(lastAreaName);
                if (oldAc != null) {
                    handleLeave(p, oldAc);
                }
                playerCurrentArea.remove(p.getName());
            }
        }
    }

    private String getChineseGameMode(GameMode mode) {
        switch (mode) {
            case SURVIVAL:  return "生存模式";
            case CREATIVE:  return "创造模式";
            case ADVENTURE: return "冒险模式";
            case SPECTATOR: return "旁观模式";
            default:        return mode.name();
        }
    }

    private void handleEnter(Player p, AreaConfig ac) {
        UUID uid = p.getUniqueId();

        // 进入提示
        if (ac.enterMsg != null && !ac.enterMsg.isEmpty()) {
            p.sendTitle("§c§l" + ac.name,
                    "§e" + ac.enterMsg, 10, 60, 20);
            p.sendMessage("§c§l[区域防护] §f" + ac.enterMsg);
        }

        // 清除负面效果
        clearBadEffects(p, ac);

        // 给予效果
        List<PotionEffectType> given = new ArrayList<>();
        for (String[] parts : ac.giveEffects) {
            String effName = parts[0];
            int level = 1;
            int duration = 999;
            try { level = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
            try { duration = Integer.parseInt(parts[2]); }
            catch (NumberFormatException ignored) {}
            PotionEffectType type = resolveEffectType(effName);
            if (type != null) {
                p.addPotionEffect(new PotionEffect(
                        type, duration * 20, level - 1));
                given.add(type);
            }
        }

        removePlayerEffects(uid, ac.name);
        if (!given.isEmpty()) {
            savePlayerEffects(uid, p.getName(), ac.name, given);
        }

        // 白名单检查（最先执行）
        if (isPlayerWhitelisted(p.getName(), ac)) return;

        // 游戏模式切换（只对非白名单玩家）
        if (ac.enforceGameMode != null) {
            boolean exempt = isPlayerExemptFromModeChange(p, ac);
            if (!exempt) {
                GameMode target = GameMode.valueOf(ac.enforceGameMode);
                if (p.getGameMode() != target) {
                    p.setGameMode(target);
                    p.sendMessage("§e§l[区域防护] §f游戏模式已切换为: "
                            + getChineseGameMode(target));
                }
            }
        }

        // 和平模式/袭击检查
        if (ac.peaceMode) {
            banHostilesWithWhitelist(p, ac);
        } else if (ac.denyRaid) {
            banRaidMobs(p, ac);
        }

        handleConfiscate(p, ac);
    }

    private boolean isPlayerExemptFromModeChange(
            Player p, AreaConfig ac) {
     //   if (p.isOp()) return true;
        Set<String> global = globalWhitelist.get("global");
        if (global != null
                && global.contains(p.getName().toLowerCase())) {
            return true;
        }
        Set<String> areaWl = areaWhitelist.get(ac.name);
        if (areaWl != null
                && areaWl.contains(p.getName().toLowerCase())) {
            return true;
        }
        if (ac.modeExempt.contains(p.getName())) {
            return true;
        }
        return false;
    }

    private void banRangedHostiles(Player p, AreaConfig ac) {
        for (Entity ent : p.getNearbyEntities(48, 48, 48)) {
            if (isRangedHostile(ent.getType().name())) {
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(ent.getWorld().getMinHeight() - 50);
                ent.teleport(voidLoc);
            }
        }
    }

    /**
     * 按配置清除效果
     * denyRaid → 清除袭击相关
     * denyAllEffects → 清除所有负面+中性
     * denyAllEffects > denyRaid（全清优先）
     */
    private void clearBadEffects(Player p, AreaConfig ac) {
        // 和平模式 > 清除所有效果 > 禁止单个
        if (ac.peaceMode || ac.clearAllBadEffects) {
            PotionEffectType[] allBad = {
                    PotionEffectType.SLOWNESS,
                    PotionEffectType.MINING_FATIGUE,
                    PotionEffectType.INSTANT_DAMAGE,
                    PotionEffectType.NAUSEA,
                    PotionEffectType.BLINDNESS,
                    PotionEffectType.HUNGER,
                    PotionEffectType.WEAKNESS,
                    PotionEffectType.POISON,
                    PotionEffectType.WITHER,
                    PotionEffectType.LEVITATION,
                    PotionEffectType.UNLUCK,
                    PotionEffectType.DARKNESS,
                    PotionEffectType.WIND_CHARGED,
                    PotionEffectType.WEAVING,
                    PotionEffectType.OOZING,
                    PotionEffectType.INFESTED,
                    PotionEffectType.BAD_OMEN,
                    PotionEffectType.TRIAL_OMEN,
                    PotionEffectType.RAID_OMEN
            };
            for (PotionEffectType type : allBad) {
                p.removePotionEffect(type);
            }
            return;
        }

        // 禁止袭击 → 自动关联清除
        if (ac.denyRaid) {
            p.removePotionEffect(PotionEffectType.BAD_OMEN);
            p.removePotionEffect(PotionEffectType.RAID_OMEN);
        }

        // 配置文件单个清除
        for (String effName : ac.clearEffects) {
            PotionEffectType type = resolveEffectType(effName);
            if (type != null) {
                p.removePotionEffect(type);
            }
        }
    }


    private PotionEffectType resolveEffectType(String name) {
        if (name == null || name.isEmpty()) return null;

        // 去掉"效果"后缀
        String clean = name;
        if (clean.endsWith("效果")) {
            clean = clean.substring(0, clean.length() - 2);
        }

        // 硬编码中文名→英文ID映射（不依赖任何外部Map）
        switch (clean) {
            // 负面
            case "缓慢":
                return PotionEffectType.getByName("slowness");
            case "挖掘疲劳":
                return PotionEffectType.getByName("mining_fatigue");
            case "瞬间伤害":
                return PotionEffectType.getByName("instant_damage");
            case "反胃":
                return PotionEffectType.getByName("nausea");
            case "失明":
                return PotionEffectType.getByName("blindness");
            case "饥饿":
                return PotionEffectType.getByName("hunger");
            case "虚弱":
                return PotionEffectType.getByName("weakness");
            case "中毒":
                return PotionEffectType.getByName("poison");
            case "凋零":
                return PotionEffectType.getByName("wither");
            case "飘浮":
                return PotionEffectType.getByName("levitation");
            case "霉运":
                return PotionEffectType.getByName("unluck");
            case "黑暗":
                return PotionEffectType.getByName("darkness");
            case "蓄风":
                return PotionEffectType.getByName("wind_charged");
            case "盘丝":
                return PotionEffectType.getByName("weaving");
            case "渗浆":
                return PotionEffectType.getByName("oozing");
            case "寄生":
                return PotionEffectType.getByName("infested");
            // 中性
            case "不祥之兆":
            case "不祥征兆":
                return PotionEffectType.getByName("bad_omen");
            case "袭击之兆":
            case "袭击征兆":
                return PotionEffectType.getByName("raid_omen");
            case "试炼之兆":
            case "试炼征兆":
                return PotionEffectType.getByName("trial_omen");
            // 正面
            case "迅捷":
                return PotionEffectType.getByName("speed");
            case "急迫":
                return PotionEffectType.getByName("haste");
            case "力量":
                return PotionEffectType.getByName("strength");
            case "瞬间治疗":
                return PotionEffectType.getByName("instant_health");
            case "跳跃提升":
                return PotionEffectType.getByName("jump_boost");
            case "生命恢复":
                return PotionEffectType.getByName("regeneration");
            case "抗性提升":
                return PotionEffectType.getByName("resistance");
            case "抗火":
                return PotionEffectType.getByName("fire_resistance");
            case "水下呼吸":
                return PotionEffectType.getByName("water_breathing");
            case "隐身":
                return PotionEffectType.getByName("invisibility");
            case "夜视":
                return PotionEffectType.getByName("night_vision");
            case "发光":
                return PotionEffectType.getByName("glowing");
            case "生命提升":
                return PotionEffectType.getByName("health_boost");
            case "伤害吸收":
                return PotionEffectType.getByName("absorption");
            case "饱和":
                return PotionEffectType.getByName("saturation");
            case "幸运":
                return PotionEffectType.getByName("luck");
            case "村庄英雄":
                return PotionEffectType.getByName("hero_of_the_village");
            case "缓降":
                return PotionEffectType.getByName("slow_falling");
            case "潮涌能量":
                return PotionEffectType.getByName("conduit_power");
            case "海豚的恩惠":
                return PotionEffectType.getByName("dolphins_grace");
            case "致命中毒":
                return PotionEffectType.getByName("poison");
            default:
                break;
        }

        // 兜底：直接用原版英文ID查
        PotionEffectType fallback = PotionEffectType.getByName(clean);
        if (fallback != null) return fallback;
        fallback = PotionEffectType.getByName(clean.toLowerCase());
        if (fallback != null) return fallback;

        plugin.getLogger().warning("[防护] 无法识别效果: " + name);
        return null;
    }


    private static final Map<String, PotionEffectType> EFFECT_MAP
            = new HashMap<>();


    private void clearRaidEffects(Player p) {
        boolean cleared = false;
        try {
            if (p.hasPotionEffect(PotionEffectType.BAD_OMEN)) {
                p.removePotionEffect(PotionEffectType.BAD_OMEN);
                cleared = true;
            }
        } catch (Exception ignored) {
        }
        try {
            if (p.hasPotionEffect(PotionEffectType.RAID_OMEN)) {
                p.removePotionEffect(PotionEffectType.RAID_OMEN);
                cleared = true;
            }
        } catch (Exception ignored) {
        }
        if (cleared) {
            p.sendMessage("§c§l[区域防护] §f已清除袭击相关效果");
        }
    }

    private void handleLeave(Player p, AreaConfig ac) {
        UUID uid = p.getUniqueId();

        if (ac.leaveMsg != null && !ac.leaveMsg.isEmpty()) {
            p.sendTitle("§a§l离开区域", "§e" + ac.leaveMsg, 10, 40, 10);
            p.sendMessage("§a§l[区域防护] §f" + ac.leaveMsg);
        }

        List<PotionEffectType> given = getPlayerEffects(uid, ac.name);

        plugin.getLogger().info("[防护] " + p.getName()
                + " 离开" + ac.name
                + " DB效果数=" + given.size());

        if (!given.isEmpty()) {
            lastLeaveTime.put(uid, System.currentTimeMillis());
            pendingClear.put(uid, new ArrayList<>(given));
         plugin.getLogger().info("[防护] 已记录待清除 lastLeaveTime="
                    + lastLeaveTime.containsKey(uid)
                    + " pendingClear数量="
                    + pendingClear.get(uid).size());
        }

        cancelBorder(p);
    }

    public void recoverPendingEffects() {
        List<String[]> pending = getAllPendingEffects();
        if (pending.isEmpty()) return;

        plugin.getLogger().info("[防护] 恢复"
                + pending.size() + "条未处理的效果记录");

        for (String[] row : pending) {
            String uuidStr = row[0];
            String playerName = row[1];
            String areaName = row[2];
            String effectName = row[3];

            UUID uid = UUID.fromString(uuidStr);
            Player p = Bukkit.getPlayer(uid);

            // 玩家不在线，直接清理DB
            if (p == null || !p.isOnline()) {
                plugin.getLogger().info("[防护] "
                        + playerName + "不在线，清理DB记录");
                removePlayerEffects(uid, areaName);
                continue;
            }

            // 玩家在线，检查当前是否还在该区域
            AreaConfig ac = areas.get(areaName);
            if (ac == null) {
                removePlayerEffects(uid, areaName);
                continue;
            }

            AreaConfig currentArea = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());

            if (currentArea == null
                    || !currentArea.name.equals(areaName)) {
                // 不在原来区域了，清除效果
                PotionEffectType type = PotionEffectType
                        .getByName(effectName);
                if (type != null) {
                    p.removePotionEffect(type);
                    plugin.getLogger().info("[防护] "
                            + playerName + " 重连后清除: "
                            + effectName);
                }
                removePlayerEffects(uid, areaName);
            } else {
                // 还在原来区域，重新给予效果
                PotionEffectType type = PotionEffectType
                        .getByName(effectName);
                if (type != null) {
                    p.addPotionEffect(new PotionEffect(
                            type, 999 * 20, 0));
                    plugin.getLogger().info("[防护] "
                            + playerName + " 重连后重新给予: "
                            + effectName);
                }
            }
        }
    }

    public void onPlayerOffline(UUID uid, String playerName,
                                String areaName) {
        // 清除该玩家在该区域的所有效果
        List<PotionEffectType> given = getPlayerEffects(uid, areaName);
        for (PotionEffectType type : given) {
            // 只清除我们给予的效果
            Player p = Bukkit.getPlayer(uid);
            if (p != null) {
                p.removePotionEffect(type);
            }
        }
        // 清除DB记录
        removePlayerEffects(uid, areaName);
        lastLeaveTime.remove(uid);
        pendingClear.remove(uid);
        plugin.getLogger().info("[防护] " + playerName
                + " 下线，已清除" + given.size()
                + "个效果并清理DB");
    }


    public void checkPendingClears() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it =
                lastLeaveTime.entrySet().iterator();
        /*plugin.getLogger().info("[防护] checkPendingClears执行"
                + " 待处理=" + lastLeaveTime.size());*/
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            UUID uid = entry.getKey();
            long leaveTime = entry.getValue();

            if (now - leaveTime < 15000) continue;

            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline()) {
                it.remove();
                pendingClear.remove(uid);
                continue;
            }

            List<PotionEffectType> toClear = pendingClear.get(uid);
            if (toClear == null || toClear.isEmpty()) {
                it.remove();
                continue;
            }

            AreaConfig currentArea = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());

            if (currentArea == null) {
                // 不在任何区域：只清除DB记录的效果，不动玩家其他效果
                for (PotionEffectType type : toClear) {
                    p.removePotionEffect(type);
                }
                removeAllPlayerEffects(uid);
                plugin.getLogger().info("[防护] " + p.getName()
                        + " 全清效果: " + toClear);
            } else {
                // 在新区域：只清新区域没有的效果
                List<PotionEffectType> newGiven =
                        getPlayerEffects(uid, currentArea.name);
                if (newGiven == null) newGiven = new ArrayList<>();

                for (PotionEffectType type : toClear) {
                    if (!newGiven.contains(type)) {
                        p.removePotionEffect(type);
                    }
                }
                // 清除旧区域DB记录
                removePlayerEffects(uid,"");
                plugin.getLogger().info("[防护] " + p.getName()
                        + " 在新区域, 清除旧效果");
            }

            it.remove();
            pendingClear.remove(uid);
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        // 忽略同世界内短距离传送（如骑马、矿车）
        if (from.getWorld() == to.getWorld()
                && from.distance(to) < 5) return;

        Player p = e.getPlayer();

        AreaConfig fromArea = getArea(
                from.getWorld().getName(),
                from.getBlockX(), from.getBlockY(),
                from.getBlockZ());
        AreaConfig toArea = getArea(
                to.getWorld().getName(),
                to.getBlockX(), to.getBlockY(),
                to.getBlockZ());

        // 从区域内传送到区域外
        if (fromArea != null && toArea == null) {
            handleLeave(p, fromArea);
        }
        // 从区域外传送到区域内
        else if (fromArea == null && toArea != null) {
      //      plugin.getLogger().info("[防护] 触发handleEnter");
            handleEnter(p, toArea);
        }

        // 从一个区域传送到另一个区域
        else if (fromArea != null && toArea != null
                && !fromArea.name.equals(toArea.name)) {
            handleLeave(p, fromArea);
            handleEnter(p, toArea);
        }
    }

    public void handleInside(Player p, AreaConfig ac) {
        clearBadEffects(p, ac);

        // 和平模式
        if (ac.peaceMode) {
            banHostilesWithWhitelist(p, ac);
        } else if (ac.denyRaid) {
            banRaidMobs(p, ac);
        }

        // 游戏模式：每次都强制切换（排除全白/区白/豁免/OP）
        if (ac.enforceGameMode != null) {
            if (!isPlayerExemptFromModeChange(p, ac)) {
                GameMode target = GameMode.valueOf(ac.enforceGameMode);
                if (p.getGameMode() != target) {
                    p.setGameMode(target);
                }
            }
        }

        if (isPlayerWhitelisted(p.getName(), ac)) return;
        handleConfiscate(p, ac);
    }

    public void cleanupExpiredProtections() {
        protectedEntities.entrySet().removeIf(
                entry -> System.currentTimeMillis() > entry.getValue());
    }

    // 禁止袭击：只驱逐袭击怪物
    private void banRaidMobs(Player p, AreaConfig ac) {
        for (Entity ent : p.getNearbyEntities(32, 32, 32)) {
            String t = ent.getType().name();
            if (t.equals("PILLAGER")
                    || t.equals("VINDICATOR")
                    || t.equals("EVOKER")
                    || t.equals("RAVAGER")
                    || t.equals("VEX")
                    || t.equals("WITCH")
                    || t.equals("ILLUSIONER")
                    || t.equals("ZOMBIE_VILLAGER")) {
                Location voidLoc = ent.getLocation().clone();
                voidLoc.setY(voidLoc.getWorld().getMinHeight() - 50);
                ent.teleport(voidLoc);
            }
        }
    }

    public Set<String> getAreaNames() {
        return areas.keySet();
    }



    // 判断是否为远程攻击敌对生物
    private boolean isRangedHostile(String typeName) {
        switch (typeName) {
            // ===== 骷髅系 =====
            case "SKELETON":            // 骷髅（弓箭）
            case "STRAY":               // 流浪者（迟缓之箭）
            case "BOGGED":              // 虫蚀骷髅（毒箭）
            case "WITHER_SKELETON":     // 凋灵骷髅（近战但算灾厄）

                // ===== 灾厄村民 =====
            case "PILLAGER":            // 掠夺者（弩）
            case "ILLUSIONER":          // 幻术师（弓箭+失明）
            case "EVOKER":              // 幻魔者（唤魔）
            case "VINDICATOR":          // 卫道士（斧头近战）
            case "VEX":                 // 恼鬼（剑近战，但会穿墙飞行）

                // ===== 法术/投射 =====
            case "BLAZE":               // 烈焰人（火球）
            case "WITCH":               // 女巫（药水）

                // ===== 飞行/爆炸 =====
            case "GHAST":               // 恶魂（火球）
            case "PHANTOM":             // 幻翼（俯冲）
            case "BIG_GHAST":           // 大恶魂（1.21新增）

                // ===== 水下 =====
            case "GUARDIAN":            // 守卫者（激光）
            case "ELDER_GUARDIAN":      // 远古守卫者（激光）

                // ===== 远程特殊 =====
            case "SHULKER":             // 潜影贝（潜影弹）
            case "BREEZE":              // 旋风人（风弹）

                // ===== 蜘蛛系（远程毒液攻击） =====
            case "SPIDER":              // 蜘蛛（白天远程吐丝）
            case "CAVE_SPIDER":         // 洞穴蜘蛛（毒）

                // ===== 1.21新增 =====
            case "WIND_CHARGED":        // 蓄风怪（风弹）
            case "WEAVING":             // 盘丝怪（吐丝）
            case "OOZING":              // 渗浆怪（史莱姆）
            case "INFESTED":            // 寄生怪（蠹虫）

                // ===== 盔甲/变种（部分版本） =====
            case "ARMORED_SKELETON":    // 盔甲骷髅
            case "ARCHER_SKELETON":     // 弓箭骷髅（部分版本）
            case "SNOW_GOLEM":          // 雪傀儡（不是敌对但远程）

                // ===== 亡灵骑乘 =====
            case "HUSK":                // 尸壳（近战但属于灾厄系）
            case "DROWNED":             // 溺尸（三叉戟远程）
            case "ZOMBIE_VILLAGER":     // 僵尸村民
            case "ZOMBIE_PIGMAN":       // 僵尸猪灵
            case "ZOMBIFIED_PIGLIN":    // 僵尸猪灵（1.16+）
            case "PIGLIN_BRUTE":        // 猪灵蛮兵

                // ===== 猪灵（弓箭） =====
            case "PIGLIN":              // 猪灵（金弩远程）

                // ===== 末影系 =====
            case "ENDER_DRAGON":        // 末影龙（远程吐息）
            case "WARDEN":              // 监守者（远程音波）

                // ===== 其他 =====
            case "CREPER":              // 苦力怕（爆炸不算远程但危险）
            case "CREEPER":             // 苦力怕

                return true;
            default:
                return false;
        }
    }



    private void applyEffects(Player p, AreaConfig ac) {
        for (String[] parts : ac.giveEffects) {
            String effName = parts[0];
            int level = 1;
            int duration = 999;
            try { level = Integer.parseInt(parts[1]); }
            catch (NumberFormatException ignored) {}
            try { duration = Integer.parseInt(parts[2]); }
            catch (NumberFormatException ignored) {}

            PotionEffectType type = resolveEffectType(effName);
            plugin.getLogger().info("[防护调试] 给予: "
                    + effName + " -> " + type);
            if (type != null) {
                p.addPotionEffect(new PotionEffect(
                        type, duration * 20, level - 1));
                plugin.getLogger().info("[防护调试] 已给予: "
                        + effName + " 等级" + level
                        + " 持续" + duration + "秒");
            }
        }
    }


    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getBlock().getX(),
                e.getBlock().getY(),
                e.getBlock().getZ());
        if (ac != null && ac.denyBlockPlace
                && !isPlayerWhitelisted(p.getName(), ac)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止放置方块");
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                e.getBlock().getX(),
                e.getBlock().getY(),
                e.getBlock().getZ());
        if (ac != null && ac.denyBlockBreak
                && !isPlayerWhitelisted(p.getName(), ac)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏方块");
        }
    }


    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;
        // 保护性规则：对所有人无条件生效
        if (ac.denyAllDamage) { e.setCancelled(true); return; }
        if (ac.denyFallDamage
                && e.getCause() == EntityDamageEvent.DamageCause.FALL)
            e.setCancelled(true);
    }


    @EventHandler
    public void onPVP(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac != null && ac.denyPVP
                && !isPlayerWhitelisted(p.getName(), ac)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止PVP");
        }
    }


    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac != null && ac.denyHunger)
            e.setCancelled(true);
    }



    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac != null && ac.denyDrop
                && !isPlayerWhitelisted(p.getName(), ac)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止丢弃物品");
        }
    }

    // 替换整个 onInteract 方法
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return;

        // ===== 选区工具 =====
        if (hand.getType() == Material.BREEZE_ROD) {
            ItemMeta meta = hand.getItemMeta();
            if (meta != null && meta.getDisplayName() != null
                    && meta.getDisplayName().contains("区域选择")) {
                Action action = e.getAction();
                boolean isLeft = (action == Action.LEFT_CLICK_AIR
                        || action == Action.LEFT_CLICK_BLOCK);
                boolean isRight = (action == Action.RIGHT_CLICK_AIR
                        || action == Action.RIGHT_CLICK_BLOCK);
                if (isLeft || isRight) {
                    e.setCancelled(true);
                    Block block = e.getClickedBlock();
                    if (block == null) {
                        try { block = p.getTargetBlockExact(5); }
                        catch (Exception ignored) {}
                    }
                    if (block == null) return;
                    UUID uid = p.getUniqueId();
                    if (isLeft) {
                        pos1.put(uid, block.getLocation());
                        p.sendMessage("§a§l[防护] §f位置1: "
                                + block.getX() + ", "
                                + block.getY() + ", "
                                + block.getZ());
                    } else {
                        pos2.put(uid, block.getLocation());
                        p.sendMessage("§a§l[防护] §f位置2: "
                                + block.getX() + ", "
                                + block.getY() + ", "
                                + block.getZ());
                    }
                }
                return;
            }
        }

        // ===== 放置实体类物品时不检查（展示框、矿车、船、地图等）=====
        Material mat = hand.getType();
        if (mat == Material.ITEM_FRAME
                || mat == Material.GLOW_ITEM_FRAME
                || mat == Material.MINECART
                || mat == Material.TNT_MINECART
                || mat == Material.CHEST_MINECART
                || mat == Material.HOPPER_MINECART
                || mat == Material.OAK_BOAT
                || mat == Material.SPRUCE_BOAT
                || mat == Material.BIRCH_BOAT
                || mat == Material.JUNGLE_BOAT
                || mat == Material.ACACIA_BOAT
                || mat == Material.DARK_OAK_BOAT
                || mat == Material.MANGROVE_BOAT
                || mat == Material.CHERRY_BOAT
                || mat == Material.BAMBOO_RAFT
                || mat == Material.MAP
                || mat == Material.ARMOR_STAND) {
            return;
        }

        // ===== 区域规则检查 =====
        AreaConfig ac = getArea(
                p.getWorld().getName(),
                p.getLocation().getBlockX(),
                p.getLocation().getBlockY(),
                p.getLocation().getBlockZ());
        if (ac == null) return;

        // 末影珍珠禁止
        if (ac.denyEnderPearl
                && hand.getType() == Material.ENDER_PEARL) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用末影珍珠");
            return;
        }

        // 弓箭禁止
        if (ac.denyBow
                && (hand.getType() == Material.BOW
                || hand.getType() == Material.CROSSBOW)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用弓箭");
            return;
        }

        // 白名单跳过
        if (isPlayerWhitelisted(p.getName(), ac)) return;

        // 禁止使用物品
        String typeId = hand.getType().name();
        if (ac.denyUseItems.contains(typeId)) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止使用此物品");
            return;
        }

        // 没收检查
        handleConfiscate(p, ac);
    }



    @EventHandler
    public void onExplosion(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            AreaConfig ac = getArea(
                    b.getWorld().getName(),
                    b.getX(), b.getY(), b.getZ());
            if (ac != null && ac.denyExplosion)
                it.remove();
        }
    }


    @EventHandler
    public void onPotionSplash(PotionSplashEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) e.getEntity().getShooter();
        AreaConfig ac = getArea(
                shooter.getWorld().getName(),
                shooter.getLocation().getBlockX(),
                shooter.getLocation().getBlockY(),
                shooter.getLocation().getBlockZ());
        if (ac != null && ac.denyPotion
                && !isPlayerWhitelisted(shooter.getName(), ac)) {
            e.setCancelled(true);
            shooter.sendMessage("§c§l[区域防护] §f禁止使用药水");
        }
    }

    /**
     * 解析区域名：先精准匹配，再模糊匹配
     * @param input 用户输入的区域名
     * @return 匹配到的区域名，无匹配返回null
     */
    public String resolveAreaName(String input) {
        if (input == null || input.isEmpty()) return null;
        if (input.equalsIgnoreCase("global")) return "global";

        // 第一步：精准匹配
        for (String name : areas.keySet()) {
            if (name.equalsIgnoreCase(input)) {
                return name;
            }
        }

        // 第二步：包含匹配（用户只打了部分名字）
        for (String name : areas.keySet()) {
            if (name.contains(input) || input.contains(name)) {
                return name;
            }
        }

        // 第三步：编辑距离模糊匹配（著称→主城）
        String bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String name : areas.keySet()) {
            int dist = levenshteinDistance(
                    input.toLowerCase(), name.toLowerCase());
            if (dist < bestDistance) {
                bestDistance = dist;
                bestMatch = name;
            }
        }

        // 容错阈值：编辑距离 <= 名字长度的一半
        if (bestMatch != null) {
            int threshold = Math.max(1, bestMatch.length() / 2);
            if (bestDistance <= threshold) {
                return bestMatch;
            }
        }

        return null;
    }

    /**
     * 判断字符串是否是区域名（精准或模糊）
     */
    private boolean isAreaName(String name) {
        if (name.equalsIgnoreCase("global")) return false;
        return resolveAreaName(name) != null;
    }

    /**
     * 获取模糊匹配的实际区域名（用于提示用户）
     */
    private String getResolvedName(String input) {
        return resolveAreaName(input);
    }

    /**
     * 编辑距离算法（莱文斯坦距离）
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1],
                            Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }

    /**
     * 智能重排参数：找出子命令位置，重排为标准顺序
     * 标准顺序：/protect 子命令 参数1 参数2 ...
     */
    private String[] smartReorderArgs(String[] original) {
        if (original.length <= 1) return original;

        Set<String> knownSubs = new HashSet<>(Arrays.asList(
                "add", "remove", "additem", "removeitem",
                "addname", "removename", "listname",
                "addwhite", "removewhite", "listwhite",
                "list", "listitem", "创建", "删除", "重载",
                "工具", "expand", "contraction",
                "on", "off", "tempon", "modeexempt"
        ));

        String first = original[0].toLowerCase();
        if (knownSubs.contains(first)) {
            return original;
        }

        int subIndex = -1;
        for (int i = 0; i < original.length; i++) {
            if (knownSubs.contains(original[i].toLowerCase())) {
                subIndex = i;
                break;
            }
        }

        if (subIndex < 0) {
            return original;
        }

        String[] reordered = new String[original.length];
        reordered[0] = original[subIndex];
        int idx = 1;
        for (int i = 0; i < original.length; i++) {
            if (i == subIndex) continue;
            reordered[idx] = original[i];
            idx++;
        }

        return reordered;
    }

    // ==================== 管理命令 ====================

    public boolean handleCommand(CommandSender sender,
                                 String[] args) {
        args = smartReorderArgs(args);
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase();

        // 工具
        if (sub.equals("工具") || sub.equals("wand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            ItemStack tool = new ItemStack(WAND);
            ItemMeta meta = tool.getItemMeta();
            meta.setDisplayName("§a§l区域选择工具");
            meta.setLore(Arrays.asList(
                    "§7左键: 位置1",
                    "§7右键: 位置2"));
            tool.setItemMeta(meta);
            p.getInventory().addItem(tool);
            p.sendMessage("§a§l[防护] §f已获取选择工具（旋风棒）");
            p.sendMessage("§7左键方块=位置1，右键方块=位置2");
            return true;
        }


        // 创建
        if (sub.equals("创建") || sub.equals("create")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /protect 创建 <名>");
                return true;
            }
            Player p = (Player) sender;
            UUID u = p.getUniqueId();
            if (!pos1.containsKey(u)
                    || !pos2.containsKey(u)) {
                p.sendMessage("§c请先用工具选点");
                return true;
            }
            String areaName = args[1];
            File f = new File(areaDir, areaName + ".txt");
            if (f.exists()) {
                p.sendMessage("§c区域已存在");
                return true;
            }
            Location l1 = pos1.get(u);
            Location l2 = pos2.get(u);
            try {
                PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(f),
                                StandardCharsets.UTF_8));
                pw.println("# 区域: " + areaName);
                pw.println("起点: " + l1.getWorld().getName()
                        + "," + l1.getBlockX()
                        + "," + l1.getBlockZ());
                pw.println("终点: " + l2.getWorld().getName()
                        + "," + l2.getBlockX()
                        + "," + l2.getBlockZ());
                pw.println("高度范围: 0-255");
                pw.println();
                pw.println("# 没收物品: 蘑菇,毒蘑菇");
                pw.println("# 禁止放置方块");
                pw.println("# 禁止破坏方块");
                pw.println("# 禁止PVP");
                pw.println("# 禁止摔伤");
                pw.println("# 禁止饥饿");
                pw.println("# 禁止一切伤害");
                pw.println("# 禁止丢弃物品");
                pw.println("# 禁止末影珍珠");
                pw.println("# 禁止使用弓箭");
                pw.println("# 禁止骑乘");
                pw.println("# 禁止袭击");
                pw.println("# 禁止药水");
                pw.println("# 禁止爆炸");
                pw.println("# 禁止使用物品: 蘑菇,毒蘑菇");
                pw.println("# 效果: 夜视 1 999");
                pw.println("# 进入提示: 欢迎来到保护区");
                pw.println("# 离开提示: 已离开保护区");
                pw.println("# 没收提示: 你的违禁品已被没收");
                pw.println("# 通报批评: {player} 在保护区违规！");
                pw.println("# 惩罚命令: ban {player} 60s 违规");
                pw.close();
                p.sendMessage("§a§l[防护] §f区域 " + areaName + " 已创建");
                loadAllAreas();
            } catch (IOException ex) {
                p.sendMessage("§c创建失败");
            }
            return true;
        }
// ===== 和平白名单：addname / removename =====
        if (sub.equals("addname")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String creatureName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                AreaConfig ac = areas.get(areaName);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在: " + areaName);
                    return true;
                }
                if (ac.peaceWhitelist.contains(creatureName)) {
                    sender.sendMessage("§e" + creatureName + " 已在 " + areaName + " 和平白名单中");
                    return true;
                }
                ac.peaceWhitelist.add(creatureName);
                savePeaceWhitelist(ac);
                sender.sendMessage("§a已添加: " + creatureName + " → " + areaName + " 和平白名单");
                return true;
            }
            sender.sendMessage("§c用法: /protect addname <区域/名字> <区域/名字>");
            return true;
        }


        if (sub.equals("removename")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String creatureName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                AreaConfig ac = areas.get(areaName);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在: " + areaName);
                    return true;
                }
                if (!ac.peaceWhitelist.contains(creatureName)) {
                    sender.sendMessage("§e" + creatureName + " 不在 " + areaName + " 和平白名单中");
                    return true;
                }
                ac.peaceWhitelist.remove(creatureName);
                savePeaceWhitelist(ac);
                sender.sendMessage("§a已移除: " + creatureName + " ← " + areaName + " 和平白名单");
                return true;
            }
            sender.sendMessage("§c用法: /protect removename <区域/名字> <区域/名字>");
            return true;
        }


        if (sub.equals("listname")) {
            if (args.length < 2) {
                sender.sendMessage("§c用法: /protect listname <区域>");
                return true;
            }
            String areaName = args[1];
            AreaConfig ac = areas.get(areaName);
            if (ac == null) {
                sender.sendMessage("§c区域不存在: " + areaName);
                return true;
            }
            sender.sendMessage("§a" + areaName + " 和平白名单:");
            if (ac.peaceWhitelist.isEmpty()) {
                sender.sendMessage("§7  (空)");
            } else {
                for (String name : ac.peaceWhitelist) {
                    sender.sendMessage("§7  - " + name);
                }
            }
            return true;
        }

// ===== 模式排除：addwhite / removewhite =====
        if (sub.equals("addwhite")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                AreaConfig ac = areas.get(areaName);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在: " + areaName);
                    return true;
                }
                Set<String> global = globalWhitelist.get("global");
                if (global != null && global.contains(playerName.toLowerCase())) {
                    sender.sendMessage("§c" + playerName + " 已在全局白名单中，不需要添加到模式排除");
                    return true;
                }
                Set<String> areaWl = areaWhitelist.get(areaName);
                if (areaWl != null && areaWl.contains(playerName.toLowerCase())) {
                    sender.sendMessage("§c" + playerName + " 已在" + areaName + "白名单中，不需要添加到模式排除");
                    return true;
                }
                if (ac.modeExempt.contains(playerName)) {
                    sender.sendMessage("§c" + playerName + " 已在" + areaName + "模式排除名单中");
                    return true;
                }
                ac.modeExempt.add(playerName);
                saveModeExempt(ac);
                sender.sendMessage("§a已添加: " + playerName + " → " + areaName + " 模式排除名单");
                return true;
            }
            sender.sendMessage("§c用法: /protect addwhite <区域/玩家> <区域/玩家>");
            return true;
        }

        if (sub.equals("removewhite")) {
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                AreaConfig ac = areas.get(areaName);
                if (ac == null) {
                    sender.sendMessage("§c区域不存在: " + areaName);
                    return true;
                }
                if (!ac.modeExempt.contains(playerName)) {
                    sender.sendMessage("§c" + playerName + " 不在 " + areaName + " 模式排除名单中");
                    return true;
                }
                ac.modeExempt.remove(playerName);
                saveModeExempt(ac);
                sender.sendMessage("§a已移除: " + playerName + " ← " + areaName + " 模式排除名单");
                return true;
            }
            sender.sendMessage("§c用法: /protect removewhite <区域/玩家> <区域/玩家>");
            return true;
        }

        if (sub.equals("listwhite")) {
            if (args.length < 2) {
                sender.sendMessage("§c用法: /protect listwhite <区域>");
                return true;
            }
            String areaName = args[1];
            AreaConfig ac = areas.get(areaName);
            if (ac == null) {
                sender.sendMessage("§c区域不存在: " + areaName);
                return true;
            }
            sender.sendMessage("§a" + areaName + " 模式排除名单:");
            if (ac.modeExempt.isEmpty()) {
                sender.sendMessage("§7  (空)");
            } else {
                for (String name : ac.modeExempt) {
                    sender.sendMessage("§7  - " + name);
                }
            }
            return true;
        }



        // 列表
        if (sub.equals("列表") || sub.equals("list")) {
            if (areas.isEmpty()) {
                sender.sendMessage("§7暂无区域");
            } else {
                sender.sendMessage("§e§l区域列表:");
                for (Map.Entry<String, AreaConfig> en
                        : areas.entrySet()) {
                    sender.sendMessage("§a  " + en.getKey()
                            + " §7规则:"
                            + en.getValue().ruleCount() + "条");
                }
            }
            return true;
        }

        // 重载
        if (sub.equals("重载") || sub.equals("reload")) {
            reload();
            sender.sendMessage("§a已重载 " + areas.size() + " 个区域");
            return true;
        }
// ===== list：列出白名单 =====
        if (sub.equals("list")) {
            if (args.length == 2) {
                String areaName = resolveAreaName(args[1]);
                if (areaName == null || areaName.equalsIgnoreCase("global")) {
                    sender.sendMessage("§a全局白名单:");
                    Set<String> wl = globalWhitelist.get("global");
                    if (wl == null || wl.isEmpty()) {
                        sender.sendMessage("§7  (空)");
                    } else {
                        for (String name : wl) {
                            sender.sendMessage("§7  - " + name);
                        }
                    }
                    return true;
                }
                if (!areaName.equals(args[1])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                Set<String> wl = areaWhitelist.get(areaName);
                sender.sendMessage("§a" + areaName + " 白名单:");
                if (wl == null || wl.isEmpty()) {
                    sender.sendMessage("§7  (空)");
                } else {
                    for (String name : wl) {
                        sender.sendMessage("§7  - " + name);
                    }
                }
                return true;
            }
            sender.sendMessage("§a全局白名单:");
            Set<String> wl = globalWhitelist.get("global");
            if (wl == null || wl.isEmpty()) {
                sender.sendMessage("§7  (空)");
            } else {
                for (String name : wl) {
                    sender.sendMessage("§7  - " + name);
                }
            }
            return true;
        }


// ===== listitem：列出黑名单物品 =====
        if (sub.equals("listitem")) {
            if (args.length < 2) {
                // 全局黑名单
                sender.sendMessage("§a§l===== 全局物品黑名单 =====");
                if (globalItemBlacklist.isEmpty()) {
                    sender.sendMessage("§7（空）");
                } else {
                    for (String item : globalItemBlacklist) {
                        sender.sendMessage("§f- " + item);
                    }
                }
                return true;
            }
            // 区域黑名单
            String area = args[1];
            if (!validateArea(sender, area)) return true;
            Set<String> list = areaItemBlacklist.get(area);
            sender.sendMessage("§a§l===== " + area + " 物品黑名单 =====");
            if (list == null || list.isEmpty()) {
                sender.sendMessage("§7（空）");
                return true;
            }
            for (String item : list) {
                sender.sendMessage("§f- " + item);
            }
            return true;
        }

// ===== expand 扩大选区 =====
        if (sub.equals("expand")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            int amount = 5;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }

            // 获取区域当前边界
            int minX = Math.min(ac.x1, ac.x2);
            int maxX = Math.max(ac.x1, ac.x2);
            int minZ = Math.min(ac.z1, ac.z2);
            int maxZ = Math.max(ac.z1, ac.z2);

            // 根据面朝方向扩建
            float yaw = p.getLocation().getYaw();
            String dir = yawToDir(yaw);
            switch (dir) {
                case "北": minZ -= amount; break;
                case "南": maxZ += amount; break;
                case "东": maxX += amount; break;
                case "西": minX -= amount; break;
            }

            // 写回配置文件
            ac.x1 = minX;
            ac.x2 = maxX;
            ac.z1 = minZ;
            ac.z2 = maxZ;
            saveAreaConfig(ac);

            p.sendMessage("§a§l[防护] §f区域 §e" + ac.name
                    + " §f已向 §e" + dir + " §f扩建 §e" + amount + "§f格");
            p.sendMessage("§7新范围: X(" + minX + "~" + maxX
                    + ") Z(" + minZ + "~" + maxZ + ")");
            return true;
        }


// ===== contraction 收缩选区 =====
        if (sub.equals("contraction") || sub.equals("contract")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            int amount = 5;
            if (args.length >= 2) {
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }

            int minX = Math.min(ac.x1, ac.x2);
            int maxX = Math.max(ac.x1, ac.x2);
            int minZ = Math.min(ac.z1, ac.z2);
            int maxZ = Math.max(ac.z1, ac.z2);

            float yaw = p.getLocation().getYaw();
            String dir = yawToDir(yaw);
            switch (dir) {
                case "北": minZ += amount; break;
                case "南": maxZ -= amount; break;
                case "东": maxX -= amount; break;
                case "西": minX += amount; break;
            }

            if (minX > maxX || minZ > maxZ) {
                p.sendMessage("§c收缩过度，区域已无效");
                return true;
            }

            ac.x1 = minX;
            ac.x2 = maxX;
            ac.z1 = minZ;
            ac.z2 = maxZ;
            saveAreaConfig(ac);

            p.sendMessage("§a§l[防护] §f区域 §e" + ac.name
                    + " §f已向 §e" + dir + " §f收缩 §e" + amount + "§f格");
            p.sendMessage("§7新范围: X(" + minX + "~" + maxX
                    + ") Z(" + minZ + "~" + maxZ + ")");
            return true;
        }

        // 删除
        if (sub.equals("删除") || sub.equals("delete")) {
            if (args.length < 2) {
                sender.sendMessage("§e用法: /protect 删除 <名>");
                return true;
            }
            File f = new File(areaDir, args[1] + ".txt");
            if (f.exists()) {
                f.delete();
                areas.remove(args[1]);
                sender.sendMessage("§a已删除: " + args[1]);
            } else {
                sender.sendMessage("§c不存在: " + args[1]);
            }
            return true;
        }

        // ===== add 玩家白名单 =====
        if (sub.equals("add")) {
            if (args.length == 2) {
                String playerName = args[1];
                if (!playerExists(playerName)) {
                    sender.sendMessage("§c玩家不存在: " + playerName);
                    return true;
                }
                Set<String> wl = globalWhitelist
                        .computeIfAbsent("global", k -> new HashSet<>());
                if (wl.contains(playerName.toLowerCase())) {
                    sender.sendMessage("§e" + playerName + " 已在全局白名单中");
                    return true;
                }
                wl.add(playerName.toLowerCase());
                saveGlobalWhitelist();
                sender.sendMessage("§a已全局加白: " + playerName);
                return true;
            }
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                if (!playerExists(playerName)) {
                    sender.sendMessage("§c玩家不存在: " + playerName);
                    return true;
                }
                if (areaName.equalsIgnoreCase("global")) {
                    Set<String> wl = globalWhitelist
                            .computeIfAbsent("global", k -> new HashSet<>());
                    if (wl.contains(playerName.toLowerCase())) {
                        sender.sendMessage("§e" + playerName + " 已在全局白名单中");
                        return true;
                    }
                    wl.add(playerName.toLowerCase());
                    saveGlobalWhitelist();
                    sender.sendMessage("§a已全局加白: " + playerName);
                } else {
                    Set<String> wl = areaWhitelist
                            .computeIfAbsent(areaName, k -> new HashSet<>());
                    if (wl.contains(playerName.toLowerCase())) {
                        sender.sendMessage("§e" + playerName + " 已在 " + areaName + " 白名单中");
                        return true;
                    }
                    wl.add(playerName.toLowerCase());
                    saveAreaWhitelist(areaName, "whitelist", wl);
                    sender.sendMessage("§a已加白: " + playerName + " → " + areaName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect add <区域/玩家> <区域/玩家>");
            return true;
        }

// remove 命令
        if (sub.equals("remove")) {
            if (args.length == 2) {
                String playerName = args[1];
                Set<String> wl = globalWhitelist.get("global");
                if (wl == null || !wl.contains(playerName.toLowerCase())) {
                    sender.sendMessage("§e" + playerName + " 不在全局白名单中");
                    return true;
                }
                wl.remove(playerName.toLowerCase());
                saveGlobalWhitelist();
                sender.sendMessage("§a已从全局白名单移除: " + playerName);
                return true;
            }
            if (args.length == 3) {
                String[] parsed = parseAreaAndTarget(args[1], args[2]);
                if (parsed == null) {
                    sender.sendMessage("§c无法识别区域名: " + args[1] + " 或 " + args[2]);
                    return true;
                }
                String areaName = parsed[0];
                String playerName = parsed[1];
                if (!areaName.equals(args[1]) && !areaName.equals(args[2])) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                if (areaName.equalsIgnoreCase("global")) {
                    Set<String> wl = globalWhitelist.get("global");
                    if (wl == null || !wl.contains(playerName.toLowerCase())) {
                        sender.sendMessage("§e" + playerName + " 不在全局白名单中");
                        return true;
                    }
                    wl.remove(playerName.toLowerCase());
                    saveGlobalWhitelist();
                    sender.sendMessage("§a已从全局白名单移除: " + playerName);
                } else {
                    Set<String> wl = areaWhitelist.get(areaName);
                    if (wl == null || !wl.contains(playerName.toLowerCase())) {
                        sender.sendMessage("§e" + playerName + " 不在 " + areaName + " 白名单中");
                        return true;
                    }
                    wl.remove(playerName.toLowerCase());
                    saveAreaWhitelist(areaName, "whitelist", wl);
                    sender.sendMessage("§a已从 " + areaName + " 白名单移除: " + playerName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect remove <区域/玩家> <区域/玩家>");
            return true;
        }


        if (sub.equals("additem")) {
            if (args.length == 2) {
                String itemName = args[1].toUpperCase();
                if (Material.matchMaterial(itemName) == null) {
                    sender.sendMessage("§c无效的物品ID: " + args[1]);
                    return true;
                }
                globalItemBlacklist.add(itemName);
                saveGlobalWhitelist();
                sender.sendMessage("§a已全局加黑: " + itemName);
                return true;
            }
            if (args.length == 3) {
                // 先尝试两边都解析区域名
                String r1 = resolveAreaName(args[1]);
                String r2 = resolveAreaName(args[2]);

                String areaName = null;
                String itemName = null;

                if (r1 != null && r2 == null) {
                    // arg1是区域名，arg2是物品
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else if (r2 != null && r1 == null) {
                    // arg2是区域名，arg1是物品
                    areaName = r2;
                    itemName = args[1].toUpperCase();
                } else if (r1 != null && r2 != null) {
                    // 两边都是区域名，第一个优先
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else {
                    // 都不是区域名，默认arg1是物品，arg2是区域名
                    // 也尝试 arg1 当区域名模糊匹配
                    itemName = args[1].toUpperCase();
                    areaName = r2 != null ? r2 : "global";
                }

                // 验证物品ID
                if (Material.matchMaterial(itemName) == null) {
                    // 再试一次：可能 arg2 才是物品
                    itemName = args[2].toUpperCase();
                    if (areaName.equals(args[2])) {
                        areaName = r1 != null ? r1 : "global";
                    }
                    if (Material.matchMaterial(itemName) == null) {
                        sender.sendMessage("§c无效的物品ID: " + args[1] + " 和 " + args[2]);
                        return true;
                    }
                }

                // 纠正提示
                if (r1 != null && !r1.equals(args[1]) && areaName.equals(r1)) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                if (r2 != null && !r2.equals(args[2]) && areaName.equals(r2)) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }

                if (areaName.equalsIgnoreCase("global")) {
                    globalItemBlacklist.add(itemName);
                    saveGlobalWhitelist();
                    sender.sendMessage("§a已全局加黑: " + itemName);
                } else {
                    areaItemBlacklist
                            .computeIfAbsent(areaName, k -> ConcurrentHashMap.newKeySet())
                            .add(itemName);
                    saveAreaWhitelist(areaName, "item",
                            areaItemBlacklist.get(areaName));
                    sender.sendMessage("§a已加黑: " + itemName + " → " + areaName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect additem <区域/物品> <区域/物品>");
            return true;
        }

        if (sub.equals("removeitem")) {
            if (args.length == 2) {
                String itemName = args[1].toUpperCase();
                if (!globalItemBlacklist.contains(itemName)) {
                    sender.sendMessage("§c全局黑名单中没有: " + itemName);
                    return true;
                }
                globalItemBlacklist.remove(itemName);
                saveGlobalWhitelist();
                sender.sendMessage("§a已从全局黑名单移除: " + itemName);
                return true;
            }
            if (args.length == 3) {
                String r1 = resolveAreaName(args[1]);
                String r2 = resolveAreaName(args[2]);

                String areaName = null;
                String itemName = null;

                if (r1 != null && r2 == null) {
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else if (r2 != null && r1 == null) {
                    areaName = r2;
                    itemName = args[1].toUpperCase();
                } else if (r1 != null && r2 != null) {
                    areaName = r1;
                    itemName = args[2].toUpperCase();
                } else {
                    itemName = args[1].toUpperCase();
                    areaName = "global";
                }

                // 纠正提示
                if (r1 != null && !r1.equals(args[1]) && areaName.equals(r1)) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }
                if (r2 != null && !r2.equals(args[2]) && areaName.equals(r2)) {
                    sender.sendMessage("§7区域名已自动纠正为: §f" + areaName);
                }

                if (areaName.equalsIgnoreCase("global")) {
                    if (!globalItemBlacklist.contains(itemName)) {
                        sender.sendMessage("§c全局黑名单中没有: " + itemName);
                        return true;
                    }
                    globalItemBlacklist.remove(itemName);
                    saveGlobalWhitelist();
                    sender.sendMessage("§a已从全局黑名单移除: " + itemName);
                } else {
                    Set<String> list = areaItemBlacklist.get(areaName);
                    if (list == null || !list.contains(itemName)) {
                        sender.sendMessage("§c" + areaName + " 黑名单中没有: " + itemName);
                        return true;
                    }
                    list.remove(itemName);
                    saveAreaWhitelist(areaName, "item", list);
                    sender.sendMessage("§a已从 " + areaName + " 黑名单移除: " + itemName);
                }
                return true;
            }
            sender.sendMessage("§c用法: /protect removeitem <区域/物品> <区域/物品>");
            return true;
        }


        // ===== on 显示边框 =====
        if (sub.equals("on")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            showBorder(p, ac, false);
            p.sendMessage("§a§l[防护] §f已显示 " + ac.name + " 边框");
            return true;
        }

        // ===== off 关闭边框 =====
        if (sub.equals("off")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            cancelBorder(p);
            p.sendMessage("§a§l[防护] §f已关闭边框显示");
            return true;
        }

        // ===== tempon 临时显示边框 =====
        if (sub.equals("tempon")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            AreaConfig ac = getArea(
                    p.getWorld().getName(),
                    p.getLocation().getBlockX(),
                    p.getLocation().getBlockY(),
                    p.getLocation().getBlockZ());
            if (ac == null) {
                p.sendMessage("§c你不在任何防护区域内");
                return true;
            }
            showBorder(p, ac, true);
            p.sendMessage("§a§l[防护] §f临时显示 "
                    + ac.name + " 边框（15秒）");
            return true;
        }

        showHelp(sender);
        return true;
    }
    private String[] parseAreaAndTarget(String arg1, String arg2) {
        String r1 = resolveAreaName(arg1);
        if (r1 != null) {
            return new String[]{r1, arg2};
        }
        String r2 = resolveAreaName(arg2);
        if (r2 != null) {
            return new String[]{r2, arg1};
        }
        return null;
    }

    private boolean playerExists(String playerName) {
        // 在线玩家
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(playerName)) return true;
        }
        // 离线玩家
        @SuppressWarnings("deprecation")
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        return op.hasPlayedBefore();
    }


    private boolean validateArea(CommandSender sender, String areaName) {
        if (!areas.containsKey(areaName)) {
            sender.sendMessage("§c区域不存在: " + areaName);
            return false;
        }
        return true;
    }


    private String matname(Material mat) {
        String name = mat.name();
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase())
                    .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
    private String yawToDir(float yaw) {
        // MC中: 0=南, 90=西, 180=北, -90/270=东
        yaw = yaw % 360;
        if (yaw < 0) yaw += 360;
        if (yaw >= 315 || yaw < 45) return "南";
        if (yaw >= 45 && yaw < 135) return "西";
        if (yaw >= 135 && yaw < 225) return "北";
        return "东";
    }
    private void saveAreaConfig(AreaConfig ac) {
        File f = new File(areaDir, ac.name + ".txt");
        if (!f.exists()) return;
        try {
            List<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("起点:")) {
                    lines.add("起点: " + ac.world
                            + "," + ac.x1 + "," + ac.z1);
                } else if (line.trim().startsWith("终点:")) {
                    lines.add("终点: " + ac.world
                            + "," + ac.x2 + "," + ac.z2);
                } else {
                    lines.add(line);
                }
            }
            br.close();
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            for (String l : lines) pw.println(l);
            pw.close();
        } catch (IOException ignored) {}
    }

    private void showHelp(CommandSender s) {
        s.sendMessage("§e§l==== 区域防护 ====");
        s.sendMessage("§a/protect 工具 §7选地工具");
        s.sendMessage("§a/protect 创建 <名> §7创建区域");
        s.sendMessage("§a/protect 列表 §7区域列表");
        s.sendMessage("§a/protect 重载 §7重载配置");
        s.sendMessage("§a/protect 删除 <名> §7删除区域");
        s.sendMessage("§a/protect add [区域] <玩家> §7玩家加白");
        s.sendMessage("§a/protect remove [区域] <玩家> §7玩家删白");
        s.sendMessage("§a/protect additem [区域] <物品> §7物品加黑");
        s.sendMessage("§a/protect removeitem [区域] <物品> §7物品删黑");
        s.sendMessage("§a/protect on §7显示边框");
        s.sendMessage("§a/protect off §7关闭边框");
        s.sendMessage("§a/protect tempon §7临时边框(15秒)");
        s.sendMessage("§a/protect list §7列出全局白名单");
        s.sendMessage("§a/protect list <区域> §7列出区域白名单");
        s.sendMessage("§a/protect listitem §7列出全局物品黑名单");
        s.sendMessage("§a/protect listitem <区域> §7列出区域物品黑名单");

    }

    public int handleConfiscate(Player p, AreaConfig ac) {
        Set<String> allItems = new HashSet<>(ac.confiscateItems);
        allItems.addAll(globalItemBlacklist);
        Set<String> areaList = areaItemBlacklist.get(ac.name);
        if (areaList != null) allItems.addAll(areaList);

        if (allItems.isEmpty()) return 0;

        int totalRemoved = 0;
        String confiscatedNames = "";

        for (String itemId : allItems) {
            // 过滤掉区域名和无效ID
            Material mat = Material.matchMaterial(itemId);
            if (mat == null) {
                plugin.getLogger().warning(
                        "[防护] 跳过无效物品: " + itemId);
                continue;
            }
            ItemStack[] all = p.getInventory().getContents();
            int count = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i] != null && all[i].getType() == mat) {
                    count += all[i].getAmount();
                    all[i] = null;
                }
            }
            p.getInventory().setContents(all);

            ItemStack off = p.getInventory().getItemInOffHand();
            if (off != null && off.getType() == mat) {
                count += off.getAmount();
                p.getInventory().setItemInOffHand(null);
            }

            if (count > 0) {
                totalRemoved += count;
                if (!confiscatedNames.isEmpty())
                    confiscatedNames += "§7、§e";
                confiscatedNames += "§e" + matname(mat)
                        + " x" + count;
            }
        }

        if (totalRemoved > 0) {
            String msg = (ac.confiscateMsg != null
                    && !ac.confiscateMsg.isEmpty())
                    ? ac.confiscateMsg
                    : "你携带了违禁品，已被没收";
            p.sendMessage("§c§l[区域防护] §f" + msg);
            p.sendMessage("§7没收物品: " + confiscatedNames);

            if (ac.enableAnnounce
                    && ac.announceTemplate != null
                    && !ac.announceTemplate.isEmpty()) {
                String ann = ac.announceTemplate
                        .replace("{player}", p.getName())
                        .replace("{area}", ac.name)
                        .replace("{count}", String.valueOf(totalRemoved))
                        .replace("{items}", confiscatedNames);
                Bukkit.broadcastMessage("§c§l[区域防护] §f" + ann);
            }
            for (String cmd : ac.punishCommands) {
                String finalCmd = cmd
                        .replace("{player}", p.getName())
                        .replace("{area}", ac.name)
                        .replace("{count}", String.valueOf(totalRemoved));
                Bukkit.getScheduler().runTask(plugin,
                        () -> Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(), finalCmd));
            }
        }
        return totalRemoved;
    }
    public void clearBlacklists() {
        globalItemBlacklist.clear();
        areaItemBlacklist.clear();
        plugin.getLogger().info("[防护] 已清空所有黑名单");
    }

    @EventHandler
    public void onEntityDamage(
            org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        String typeName = entity.getType().name();

        if (typeName.equals("ITEM_FRAME")
                || typeName.equals("GLOW_ITEM_FRAME")) {
            if (!(e.getDamager() instanceof Player)) return;
            Player p = (Player) e.getDamager();

            Set<String> global = globalWhitelist.get("global");
            if (global != null
                    && global.contains(p.getName().toLowerCase())) {
                return;
            }

            AreaConfig ac = findFrameArea(entity);
            if (ac == null || !ac.denyItemFrame) return;

            Set<String> areaWl = areaWhitelist.get(ac.name);
            if (areaWl != null
                    && areaWl.contains(p.getName().toLowerCase())) {
                return;
            }

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== PVP 逻辑 =====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p2 = (Player) e.getEntity();
        AreaConfig ac2 = getArea(
                p2.getWorld().getName(),
                p2.getLocation().getBlockX(),
                p2.getLocation().getBlockY(),
                p2.getLocation().getBlockZ());
        if (ac2 != null && ac2.denyPVP
                && !isPlayerWhitelisted(p2.getName(), ac2)) {
            e.setCancelled(true);
            p2.sendMessage("§c§l[区域防护] §f禁止PVP");
        }
    }

    // 展示框被点击（旋转/放入/取出）
    @EventHandler
    public void onItemFrameInteract(
            PlayerInteractAtEntityEvent e) {
        Entity clicked = e.getRightClicked();
        String typeName = clicked.getType().name();
        if (!typeName.equals("ITEM_FRAME")
                && !typeName.equals("GLOW_ITEM_FRAME")) {
            return;
        }

        Player p = e.getPlayer();

        // 全局白放行
        Set<String> global = globalWhitelist.get("global");
        if (global != null
                && global.contains(p.getName().toLowerCase())) {
            return;
        }

        AreaConfig ac = findFrameArea(clicked);
        if (ac == null || !ac.denyItemFrame) return;

        // 区域白放行
        Set<String> areaWl = areaWhitelist.get(ac.name);
        if (areaWl != null
                && areaWl.contains(p.getName().toLowerCase())) {
            return;
        }

        // 非白名单 → 拦截
        // 记录当前旋转角度
        final Rotation savedRotation;
        final Entity frame = clicked;
        if (clicked instanceof org.bukkit.entity.ItemFrame) {
            savedRotation =
                    ((org.bukkit.entity.ItemFrame) clicked)
                            .getRotation();
        } else {
            savedRotation = null;
        }

        e.setCancelled(true);

        // 下一tick强制恢复旋转
        if (savedRotation != null) {
            final Rotation restore = savedRotation;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (frame.isValid()
                        && frame instanceof org.bukkit.entity.ItemFrame) {
                    ((org.bukkit.entity.ItemFrame) frame)
                            .setRotation(restore);
                }
            }, 1L);
        }

        p.sendMessage("§c§l[区域防护] §f禁止交互展示框");

        // 驱逐远程敌对生物
        banRangedHostiles(p, ac);
    }



    // 展示框保护：检查展示框本身+周围方块所属区域

    // ===== 展示框攻击拦截（左键/投射物破坏）=====
    @EventHandler
    public void onEntityDamageByEntity(
            org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        Entity entity = e.getEntity();
        String typeName = entity.getType().name();

        if (typeName.equals("ITEM_FRAME")
                || typeName.equals("GLOW_ITEM_FRAME")) {
            // 展示框被攻击
            if (!(e.getDamager() instanceof Player)) return;
            Player p = (Player) e.getDamager();

            // 全局白放行
            Set<String> gwl = globalWhitelist.get("global");
            if (gwl != null
                    && gwl.contains(p.getName().toLowerCase())) {
                return;
            }

            AreaConfig ac = findFrameArea(entity);
            if (ac == null || !ac.denyItemFrame) return;

            // 区域白放行
            Set<String> awl = areaWhitelist.get(ac.name);
            if (awl != null
                    && awl.contains(p.getName().toLowerCase())) {
                return;
            }

            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
            return;
        }

        // ===== PVP 逻辑（原有的，不动）=====
        if (!(e.getDamager() instanceof Player)) return;
        if (!(e.getEntity() instanceof Player)) return;
        Player p2 = (Player) e.getEntity();
        AreaConfig ac2 = getArea(
                p2.getWorld().getName(),
                p2.getLocation().getBlockX(),
                p2.getLocation().getBlockY(),
                p2.getLocation().getBlockZ());
        if (ac2 != null && ac2.denyPVP
                && !isPlayerWhitelisted(p2.getName(), ac2)) {
            e.setCancelled(true);
            p2.sendMessage("§c§l[区域防护] §f禁止PVP");
        }
    }
    private AreaConfig findFrameArea(Entity frame) {
        Location loc = frame.getLocation();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        String world = loc.getWorld().getName();

        AreaConfig ac = getArea(world, bx, by, bz);
        if (ac != null && ac.denyItemFrame) return ac;

        int[][] offsets = {
                {0, 0, 1}, {0, 0, -1},
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0}
        };
        for (int[] off : offsets) {
            ac = getArea(world,
                    bx + off[0], by + off[1], bz + off[2]);
            if (ac != null && ac.denyItemFrame) return ac;
        }
        return null;
    }

    // ===== 投射物击中展示框（弓箭/弩）=====
    @EventHandler
    public void onProjectileHit(
            org.bukkit.event.entity.ProjectileHitEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player)) return;
        Player shooter = (Player) e.getEntity().getShooter();
        Entity hit = e.getHitEntity();
        if (hit == null) return;
        String typeName = hit.getType().name();
        if (!typeName.equals("ITEM_FRAME")
                && !typeName.equals("GLOW_ITEM_FRAME")) {
            return;
        }

        // 全局白放行
        Set<String> gwl = globalWhitelist.get("global");
        if (gwl != null
                && gwl.contains(shooter.getName().toLowerCase())) {
            return;
        }

        AreaConfig ac = findFrameArea(hit);
        if (ac == null || !ac.denyItemFrame) return;

        // 区域白放行
        Set<String> awl = areaWhitelist.get(ac.name);
        if (awl != null
                && awl.contains(shooter.getName().toLowerCase())) {
            return;
        }

        e.setCancelled(true);
        shooter.sendMessage("§c§l[区域防护] §f禁止破坏展示框");
    }


    // ===== 区域外火焰不能蔓延进区域 =====
    @EventHandler
    public void onBlockSpread(BlockSpreadEvent e) {
        Block block = e.getBlock();
        AreaConfig tgtArea = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (tgtArea != null && tgtArea.denyFireSpread) {
            e.setCancelled(true);
        }
    }

    // ===== 区域内禁止点燃方块 =====
    @EventHandler
    public void onIgnite(BlockIgniteEvent e) {
        Player p = e.getPlayer();
        if (p == null) return; // 非玩家点燃的（闪电等）
        Block block = e.getBlock();
        AreaConfig ac = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (ac != null && ac.denyFireSpread) {
            e.setCancelled(true);
            p.sendMessage("§c§l[区域防护] §f此区域禁止点燃物品");
        }
    }

    // ===== 区域内方块着火（TNT、岩浆等）也阻止 =====
    @EventHandler
    public void onBlockBurn(BlockBurnEvent e) {
        Block block = e.getBlock();
        AreaConfig ac = getArea(
                block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (ac != null && ac.denyFireSpread)
            e.setCancelled(true);
    }


    private void savePeaceWhitelist(AreaConfig ac) {
        File file = new File(areaDir, ac.name + ".txt");
        if (!file.exists()) return;

        try {
            List<String> lines = new ArrayList<>();
            BufferedReader reader = new BufferedReader(
                    new FileReader(file, StandardCharsets.UTF_8));
            String line;
            boolean found = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("和平")
                        && trimmed.contains("白名单")) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("和平模式白名单:");
                    boolean first = true;
                    for (String name : ac.peaceWhitelist) {
                        if (!first) sb.append(",");
                        sb.append(name);
                        first = false;
                    }
                    lines.add(sb.toString());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
            reader.close();

            if (!found && !ac.peaceWhitelist.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("和平模式白名单:");
                boolean first = true;
                for (String name : ac.peaceWhitelist) {
                    if (!first) sb.append(",");
                    sb.append(name);
                    first = false;
                }
                lines.add(sb.toString());
            }

            FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8);
            for (String l : lines) {
                writer.write(l + "\n");
            }
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning(
                    "[防护] 保存和平白名单失败: " + e.getMessage());
        }
    }


    @EventHandler
    public void onPaintingPlace(
            org.bukkit.event.player.PlayerBucketEmptyEvent e) {
        // 暂不处理
    }


    // ==================== 数据类 ====================

    public static class AreaConfig {
        public String name = "";
        public String world = "";
        public int x1, z1, x2, z2;
        public int yMin = 0, yMax = 255;
        public List<String> confiscateItems = new ArrayList<>();
        public List<String> denyUseItems = new ArrayList<>();
        public List<String> clearEffects = new ArrayList<>();     // 清除单个效果名
        public boolean clearAllBadEffects = false;                // 清除所有负面+中性
        public List<String[]> giveEffects = new ArrayList<>();    // [效果名, 等级, 秒数]
        public List<String> punishCommands = new ArrayList<>();
        public boolean denyBlockPlace = false;
        public boolean denyBlockBreak = false;
        public boolean denyPVP = false;
        public boolean denyFallDamage = false;
        public boolean denyHunger = false;
        public boolean denyAllDamage = false;
        public boolean denyMount = false;
        public boolean denyDrop = false;
        public boolean denyEnderPearl = false;
        public boolean denyBow = false;
        public boolean denyPotion = false;
        public boolean denyExplosion = false;
        public boolean enableAnnounce = false;
        public String announceTemplate = "";
        public String confiscateMsg = "";
        public String enterMsg = "";
        public String leaveMsg = "";
        public boolean denyRaid = false;
        public boolean denyFireSpread = false;
        public boolean denyAllEffects = false;;
        public boolean peaceMode = false;
        public boolean denyItemFrame = false;
        public Set<String> peaceWhitelist = new HashSet<>();
        public String enforceGameMode = null;  // 强制游戏模式
        public Set<String> modeExempt = new HashSet<>(); // 模式排除名单




        private static final Map<String, PotionEffectType> EFFECT_MAP
                = new HashMap<>();
        static {
            // 负面效果
            EFFECT_MAP.put("缓慢", PotionEffectType.SLOWNESS);
            EFFECT_MAP.put("挖掘疲劳", PotionEffectType.MINING_FATIGUE);
            EFFECT_MAP.put("瞬间伤害", PotionEffectType.INSTANT_DAMAGE);
            EFFECT_MAP.put("反胃", PotionEffectType.NAUSEA);
            EFFECT_MAP.put("失明", PotionEffectType.BLINDNESS);
            EFFECT_MAP.put("饥饿", PotionEffectType.HUNGER);
            EFFECT_MAP.put("虚弱", PotionEffectType.WEAKNESS);
            EFFECT_MAP.put("中毒", PotionEffectType.POISON);
            EFFECT_MAP.put("凋零", PotionEffectType.WITHER);
            EFFECT_MAP.put("飘浮", PotionEffectType.LEVITATION);
            EFFECT_MAP.put("霉运", PotionEffectType.UNLUCK);
            EFFECT_MAP.put("黑暗", PotionEffectType.DARKNESS);
            EFFECT_MAP.put("蓄风", PotionEffectType.WIND_CHARGED);
            EFFECT_MAP.put("盘丝", PotionEffectType.WEAVING);
            EFFECT_MAP.put("渗浆", PotionEffectType.OOZING);
            EFFECT_MAP.put("寄生", PotionEffectType.INFESTED);
            // 中性效果
            EFFECT_MAP.put("不祥之兆", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("不祥征兆", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("袭击之兆", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("袭击征兆", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("试炼之兆", PotionEffectType.TRIAL_OMEN);
            EFFECT_MAP.put("试炼征兆", PotionEffectType.TRIAL_OMEN);
            // 正面效果
            EFFECT_MAP.put("迅捷", PotionEffectType.SPEED);
            EFFECT_MAP.put("急迫", PotionEffectType.HASTE);
            EFFECT_MAP.put("力量", PotionEffectType.STRENGTH);
            EFFECT_MAP.put("瞬间治疗", PotionEffectType.INSTANT_HEALTH);
            EFFECT_MAP.put("跳跃提升", PotionEffectType.JUMP_BOOST);
            EFFECT_MAP.put("生命恢复", PotionEffectType.REGENERATION);
            EFFECT_MAP.put("抗性提升", PotionEffectType.RESISTANCE);
            EFFECT_MAP.put("抗火", PotionEffectType.FIRE_RESISTANCE);
            EFFECT_MAP.put("水下呼吸", PotionEffectType.WATER_BREATHING);
            EFFECT_MAP.put("隐身", PotionEffectType.INVISIBILITY);
            EFFECT_MAP.put("夜视", PotionEffectType.NIGHT_VISION);
            EFFECT_MAP.put("发光", PotionEffectType.GLOWING);
            EFFECT_MAP.put("生命提升", PotionEffectType.HEALTH_BOOST);
            EFFECT_MAP.put("伤害吸收", PotionEffectType.ABSORPTION);
            EFFECT_MAP.put("饱和", PotionEffectType.SATURATION);
            EFFECT_MAP.put("幸运", PotionEffectType.LUCK);
            EFFECT_MAP.put("村庄英雄", PotionEffectType.HERO_OF_THE_VILLAGE);
            EFFECT_MAP.put("缓降", PotionEffectType.SLOW_FALLING);
            EFFECT_MAP.put("潮涌能量", PotionEffectType.CONDUIT_POWER);
            EFFECT_MAP.put("海豚的恩惠", PotionEffectType.DOLPHINS_GRACE);


            // 带效果二字
            // 补充带"效果"后缀的别名
            EFFECT_MAP.put("黑暗效果", PotionEffectType.DARKNESS);
            EFFECT_MAP.put("不祥之兆效果", PotionEffectType.BAD_OMEN);
            EFFECT_MAP.put("袭击之兆效果", PotionEffectType.RAID_OMEN);
            EFFECT_MAP.put("试炼之兆效果", PotionEffectType.TRIAL_OMEN);
            EFFECT_MAP.put("缓慢效果", PotionEffectType.SLOWNESS);
            EFFECT_MAP.put("中毒效果", PotionEffectType.POISON);
            EFFECT_MAP.put("凋零效果", PotionEffectType.WITHER);
            EFFECT_MAP.put("失明效果", PotionEffectType.BLINDNESS);
            EFFECT_MAP.put("饥饿效果", PotionEffectType.HUNGER);
            EFFECT_MAP.put("虚弱效果", PotionEffectType.WEAKNESS);
            EFFECT_MAP.put("反胃效果", PotionEffectType.NAUSEA);
            EFFECT_MAP.put("飘浮效果", PotionEffectType.LEVITATION);
            EFFECT_MAP.put("发光效果", PotionEffectType.GLOWING);
            EFFECT_MAP.put("霉运效果", PotionEffectType.UNLUCK);
            EFFECT_MAP.put("蓄风效果", PotionEffectType.WIND_CHARGED);
            EFFECT_MAP.put("盘丝效果", PotionEffectType.WEAVING);
            EFFECT_MAP.put("渗浆效果", PotionEffectType.OOZING);
            EFFECT_MAP.put("寄生效果", PotionEffectType.INFESTED);
            EFFECT_MAP.put("挖掘疲劳效果", PotionEffectType.MINING_FATIGUE);
            EFFECT_MAP.put("瞬间伤害效果", PotionEffectType.INSTANT_DAMAGE);
            EFFECT_MAP.put("村庄英雄效果", PotionEffectType.HERO_OF_THE_VILLAGE);
        }


        public int ruleCount() {
            int c = 0;
            if (!confiscateItems.isEmpty()) c++;
            if (denyBlockPlace) c++;
            if (denyBlockBreak) c++;
            if (denyPVP) c++;
            if (denyFallDamage) c++;
            if (denyHunger) c++;
            if (denyAllDamage) c++;
            if (denyDrop) c++;
            if (denyMount) c++;
            if (denyEnderPearl) c++;
            if (denyBow) c++;
            if (denyRaid) c++;
            if (denyPotion) c++;
            if (denyExplosion) c++;
            if (denyFireSpread) c++;
            if (denyAllEffects) c++;
            if (!clearEffects.isEmpty()) c++;
            if (clearAllBadEffects) c++;
            if (!giveEffects.isEmpty()) c++;
            if (enableAnnounce) c++;
            if (!punishCommands.isEmpty()) c++;
            if (!denyUseItems.isEmpty()) c++;
            if (peaceMode) c++;
            if (denyItemFrame) c++;
            if (!peaceWhitelist.isEmpty()) c++;

            return c;
        }
    }
    }