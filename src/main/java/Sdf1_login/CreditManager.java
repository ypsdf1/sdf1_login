package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ 第58批: 信誉积分管理系统
 *
 * 核心逻辑:
 *   - 每人初始化100积分
 *   - /credits 打开9格GUI：上报好人好事/举报违规/我的积分/积分明细
 *   - 管理员(Tag)额外：加分/扣分/待审批/审批记录
 *   - /credits user <玩家名> 任何人可查他人积分
 *   - 上报通过聊天框分步引导：填目标玩家名 → 填上报理由
 *   - 低于阈值 → 行为限制（可配置规则）
 */
public class CreditManager {

    private final Main plugin;
    private final DatabaseManager db;

    // 默认初始积分
    public static final int DEFAULT_INIT_SCORE = 100;

    // 审批状态
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    // 上报类型
    public static final String TYPE_GOOD = "good";       // 好人好事
    public static final String TYPE_BAD = "bad";         // 举报

    // 玩家积分缓存 (playerName -> score)
    private final Map<String, Integer> scoreCache = new ConcurrentHashMap<>();

    // GUI标题
    public static final String GUI_TITLE = "§d§l信誉积分";

    // GUI尺寸
    private static final int GUI_SIZE = 9;

    // ==================== 聊天引导状态 ====================

    /** 上报/审批聊天引导状态 */
    public enum FlowState {
        REPORT_GOOD_TARGET,       // 好人好事 → 等待目标玩家名
        REPORT_GOOD_REASON,       // 好人好事 → 等待上报理由
        REPORT_BAD_TARGET,        // 举报 → 等待目标玩家名
        REPORT_BAD_REASON,        // 举报 → 等待上报理由
        ADD_SCORE_TARGET,         // 加分 → 等待玩家名
        ADD_SCORE_AMOUNT,         // 加分 → 等待数值
        DEDUCT_SCORE_TARGET,      // 扣分 → 等待玩家名
        DEDUCT_SCORE_AMOUNT,      // 扣分 → 等待数值
        APPROVE_SCORE_CHANGE      // 审批通过 → 等待积分变动值
    }

    // 玩家当前引导状态 (playerName -> FlowState + 临时数据)
    private static class FlowData {
        FlowState state;
        String tempName;  // 临时存储的目标玩家名
        int recordId;     // 临时存储的审批记录ID
    }
    private final Map<String, FlowData> chatFlows = new ConcurrentHashMap<>();

    // 玩家当前查看的待审批详情记录ID（不拦截聊天，仅记录）
    private final Map<String, Integer> detailViewRecord = new ConcurrentHashMap<>();

    public CreditManager(Main plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        ensureTables();
    }

    // ==================== DB 初始化 ====================

    private void ensureTables() {
        try {
            Statement st = db.getConnection().createStatement();

            // 信誉积分记录表
            st.execute("CREATE TABLE IF NOT EXISTS credit_records ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "target_player TEXT NOT NULL,"
                    + "reporter_player TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "content TEXT DEFAULT '',"
                    + "status TEXT DEFAULT 'pending',"
                    + "score_change INTEGER DEFAULT 0,"
                    + "created_at INTEGER DEFAULT 0,"
                    + "approved_at INTEGER DEFAULT 0,"
                    + "approve_reason TEXT DEFAULT '',"
                    + "source TEXT DEFAULT 'game'"
                    + ")");

            // 信誉限制规则表
            st.execute("CREATE TABLE IF NOT EXISTS credit_rules ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "rule_name TEXT NOT NULL UNIQUE,"
                    + "min_score INTEGER DEFAULT 0,"
                    + "max_score INTEGER DEFAULT 0,"
                    + "restriction_type TEXT DEFAULT '',"
                    + "restriction_duration INTEGER DEFAULT 0,"
                    + "enabled INTEGER DEFAULT 1,"
                    + "description TEXT DEFAULT ''"
                    + ")");

            // 默认规则初始化
            initDefaultRules();

            // users 表添加 credit_score 列
            try {
                st.executeUpdate("ALTER TABLE users ADD COLUMN credit_score INTEGER DEFAULT " + DEFAULT_INIT_SCORE);
            } catch (Exception ignored) {}

        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 建表失败: " + e.getMessage());
        }
    }

    private void initDefaultRules() {
        try {
            Statement st = db.getConnection().createStatement();
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) "
                    + "VALUES ('禁止交易', 60, 0, 'trade', 0, '低于60分时禁止与NPC交易')");
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) "
                    + "VALUES ('禁止PVP', 40, 0, 'pvp', 0, '低于40分时禁止进入PVP竞技场')");
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) "
                    + "VALUES ('禁止传送', 20, 0, 'teleport', 0, '低于20分时禁止使用传送功能')");
        } catch (Exception ignored) {}
    }

    // ==================== 积分查询 ====================

    public int getScore(String playerName) {
        if (scoreCache.containsKey(playerName)) {
            return scoreCache.get(playerName);
        }
        int score = DEFAULT_INIT_SCORE;
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT credit_score FROM users WHERE player_name = ?");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                score = rs.getInt("credit_score");
            }
            ps.close();
        } catch (Exception ignored) {}
        scoreCache.put(playerName, score);
        return score;
    }

    // ==================== 积分修改 ====================

    public void setScore(String playerName, int score) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE users SET credit_score = ? WHERE player_name = ?");
            ps.setInt(1, score);
            ps.setString(2, playerName);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 设置积分失败: " + e.getMessage());
        }
        scoreCache.put(playerName, score);
        checkRestrictions(playerName, score);
    }

    public void addScore(String playerName, int amount, String reason) {
        int current = getScore(playerName);
        setScore(playerName, current + amount);
        plugin.getLogger().info("[信誉积分] " + playerName + " 积分变动: "
                + (amount > 0 ? "+" : "") + amount + " (" + reason + ")");
    }

    // ==================== 上报 ====================

    public void submitReport(String targetPlayer, String reporterPlayer, String type, String content, String source) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "INSERT INTO credit_records (target_player, reporter_player, type, content, status, created_at, source) "
                            + "VALUES (?, ?, ?, ?, 'pending', ?, ?)");
            ps.setString(1, targetPlayer);
            ps.setString(2, reporterPlayer);
            ps.setString(3, type);
            ps.setString(4, content);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, source);
            ps.executeUpdate();
            ps.close();
            plugin.getLogger().info("[信誉积分] 上报: " + reporterPlayer + " 举报/表扬 " + targetPlayer
                    + " 类型=" + type + " 来源=" + source);
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 上报失败: " + e.getMessage());
        }
    }

    // ==================== 审批 ====================

    public boolean approveRecord(int recordId, boolean approved, String reason, int scoreChange, String approverName) {
        try {
            // 先查目标玩家
            String target = null;
            PreparedStatement ps2 = db.getConnection().prepareStatement(
                    "SELECT target_player FROM credit_records WHERE id = ?");
            ps2.setInt(1, recordId);
            ResultSet rs2 = ps2.executeQuery();
            if (rs2.next()) target = rs2.getString("target_player");
            ps2.close();

            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE credit_records SET status = ?, score_change = ?, approved_at = ?, approve_reason = ? WHERE id = ?");
            ps.setString(1, approved ? STATUS_APPROVED : STATUS_REJECTED);
            ps.setInt(2, approved ? scoreChange : 0);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, reason);
            ps.setInt(5, recordId);
            ps.executeUpdate();
            ps.close();

            if (approved && scoreChange != 0 && target != null) {
                addScore(target, scoreChange, "信誉积分审批(" + (approved ? "通过" : "驳回") + "): " + reason);
                plugin.getLogger().info("[信誉积分] " + approverName + " 审批通过 " + target
                        + " 积分变动: " + (scoreChange > 0 ? "+" : "") + scoreChange);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 审批失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 查询 ====================

    public List<Map<String, Object>> getPendingRecords() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM credit_records WHERE status = 'pending' ORDER BY created_at DESC");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("target_player", rs.getString("target_player"));
                row.put("reporter_player", rs.getString("reporter_player"));
                row.put("type", rs.getString("type"));
                row.put("content", rs.getString("content"));
                row.put("created_at", rs.getLong("created_at"));
                row.put("source", rs.getString("source"));
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询待审批失败: " + e.getMessage());
        }
        return list;
    }

    public List<Map<String, Object>> getAllRecords(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM credit_records ORDER BY created_at DESC LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("target_player", rs.getString("target_player"));
                row.put("reporter_player", rs.getString("reporter_player"));
                row.put("type", rs.getString("type"));
                row.put("content", rs.getString("content"));
                row.put("status", rs.getString("status"));
                row.put("score_change", rs.getInt("score_change"));
                row.put("created_at", rs.getLong("created_at"));
                row.put("source", rs.getString("source"));
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询记录失败: " + e.getMessage());
        }
        return list;
    }

    // ==================== 限制规则 ====================

    public List<Map<String, Object>> getEnabledRules() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM credit_rules WHERE enabled = 1");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("rule_name", rs.getString("rule_name"));
                row.put("min_score", rs.getInt("min_score"));
                row.put("max_score", rs.getInt("max_score"));
                row.put("restriction_type", rs.getString("restriction_type"));
                row.put("restriction_duration", rs.getInt("restriction_duration"));
                row.put("description", rs.getString("description"));
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询规则失败: " + e.getMessage());
        }
        return list;
    }

    public boolean isRestricted(String playerName, String restrictionType) {
        int score = getScore(playerName);
        for (Map<String, Object> rule : getEnabledRules()) {
            int minScore = ((Number) rule.get("min_score")).intValue();
            String type = (String) rule.get("restriction_type");
            if (score < minScore && type.equals(restrictionType)) {
                return true;
            }
        }
        return false;
    }

    private void checkRestrictions(String playerName, int score) {
        for (Map<String, Object> rule : getEnabledRules()) {
            int minScore = ((Number) rule.get("min_score")).intValue();
            int maxScore = ((Number) rule.get("max_score")).intValue();
            String type = (String) rule.get("restriction_type");
            String desc = (String) rule.get("description");

            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null || !p.isOnline()) continue;

            if (score < minScore && (maxScore == 0 || score <= maxScore)) {
                p.sendMessage("§c§l[信誉积分] §f你的积分 " + score + " 低于阈值 " + minScore + "，"
                        + "已触发限制: " + desc);
            }
        }
    }

    // ==================== 9格 GUI ====================

    public Inventory createMainGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, GUI_SIZE, GUI_TITLE);
        boolean admin = plugin.isAdmin(p);

        // Slot 0: 上报好人好事
        inv.setItem(0, mkItem(Material.EMERALD,
                "§a§l上报好人好事",
                "§7表扬其他玩家的良好行为",
                "§7点击后聊天框输入目标玩家名"));

        // Slot 1: 举报违规违纪
        inv.setItem(1, mkItem(Material.REDSTONE,
                "§c§l举报违规违纪",
                "§7举报其他玩家的违规行为",
                "§7点击后聊天框输入目标玩家名"));

        // Slot 2: 我的积分
        int myScore = getScore(p.getName());
        inv.setItem(2, mkItem(Material.GOLD_INGOT,
                "§e§l我的积分",
                "§7当前积分: " + myScore,
                "§7初始积分: " + DEFAULT_INIT_SCORE));

        // Slot 3: 积分明细
        inv.setItem(3, mkItem(Material.PAPER,
                "§f§l积分明细",
                "§7查看自己的积分变动记录",
                "§7点击查看"));

        // Slot 4: 加分 (管理员)
        if (admin) {
            inv.setItem(4, mkItem(Material.EMERALD,
                    "§a§l加分",
                    "§7管理员给玩家加分",
                    "§7点击后聊天框输入玩家名"));
        }

        // Slot 5: 扣分 (管理员)
        if (admin) {
            inv.setItem(5, mkItem(Material.REDSTONE,
                    "§c§l扣分",
                    "§7管理员给玩家扣分",
                    "§7点击后聊天框输入玩家名"));
        }

        // Slot 6: 待审批 (管理员)
        if (admin) {
            inv.setItem(6, mkItem(Material.CHEST,
                    "§e§l待审批",
                    "§7审批玩家上报的好人好事/举报",
                    "§7点击查看"));
        }

        // Slot 7: 审批记录 (管理员)
        if (admin) {
            inv.setItem(7, mkItem(Material.BOOK,
                    "§b§l审批记录",
                    "§7查看历史审批记录",
                    "§7点击查看"));
        }

        // Slot 8: 规则说明
        inv.setItem(8, mkItem(Material.IRON_INGOT,
                "§7§l规则说明",
                "§7低于60分: 禁止交易",
                "§7低于40分: 禁止PVP",
                "§7低于20分: 禁止传送"));

        // 非管理员在 4-7 槽显示锁定
        if (!admin) {
            for (int i = 4; i <= 7; i++) {
                if (inv.getItem(i) == null) {
                    inv.setItem(i, mkItem(Material.RED_STAINED_GLASS_PANE,
                            "§c§l管理员专属",
                            "§7需要Tag管理员身份"));
                }
            }
        }

        return inv;
    }

    public Inventory createPendingGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE + " - 待审批");
        fillBg(inv);
        List<Map<String, Object>> records = getPendingRecords();
        for (int i = 0; i < records.size() && i < 49; i++) {
            Map<String, Object> r = records.get(i);
            String type = (String) r.get("type");
            Material mat = TYPE_GOOD.equals(type) ? Material.EMERALD : Material.REDSTONE;
            String typeText = TYPE_GOOD.equals(type) ? "§a好人好事" : "§c举报";
            inv.setItem(i, mkItem(mat,
                    "§e#" + r.get("id") + " " + r.get("target_player"),
                    "§7上报人: " + r.get("reporter_player"),
                    "§7类型: " + typeText,
                    "§7内容: " + r.get("content"),
                    "§7来源: " + r.get("source"),
                    "",
                    "§e点击查看详情"));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    /**
     * 单条待审批记录详情 GUI — 含通过/驳回按钮
     */
    public Inventory createPendingDetailGUI(Player p, int recordId) {
        Inventory inv = Bukkit.createInventory(null, 9, GUI_TITLE + " - 审批详情");
        fillBg(inv);

        Map<String, Object> record = null;
        for (Map<String, Object> r : getPendingRecords()) {
            if (((Number) r.get("id")).intValue() == recordId) {
                record = r;
                break;
            }
        }
        if (record == null) {
            inv.setItem(3, mkItem(Material.BARRIER, "§c§l记录不存在", "§7该记录可能已被审批"));
            inv.setItem(4, mkItem(Material.ARROW, "§7返回"));
            return inv;
        }

        String type = (String) record.get("type");
        Material mat = TYPE_GOOD.equals(type) ? Material.EMERALD : Material.REDSTONE;
        String typeText = TYPE_GOOD.equals(type) ? "§a好人好事" : "§c举报";

        // Slot 0: 通过按钮 (EMERALD)
        inv.setItem(0, mkItem(Material.EMERALD,
                "§a§l通过",
                "§7点击通过此条上报",
                "§7通过后需要输入积分变动值"));

        // Slot 1: 驳回按钮 (REDSTONE)
        inv.setItem(1, mkItem(Material.REDSTONE,
                "§c§l驳回",
                "§7点击驳回此条上报",
                "§7驳回则不扣分不加分"));

        // Slot 3: 记录详情
        inv.setItem(3, mkItem(mat,
                "§e§l#" + recordId + " " + record.get("target_player"),
                "§7上报人: " + record.get("reporter_player"),
                "§7类型: " + typeText,
                "§7内容: " + record.get("content"),
                "§7来源: " + record.get("source"),
                "§7时间: " + formatTime((Long) record.get("created_at"))));

        // Slot 4: 返回
        inv.setItem(4, mkItem(Material.ARROW, "§7返回"));

        return inv;
    }

    public Inventory createRecordsGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE + " - 历史记录");
        fillBg(inv);
        // 只看自己的记录
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> r : getAllRecords(49)) {
            if (r.get("target_player").equals(p.getName())) {
                records.add(r);
            }
        }
        for (int i = 0; i < records.size() && i < 49; i++) {
            Map<String, Object> r = records.get(i);
            String status = (String) r.get("status");
            String statusText = STATUS_APPROVED.equals(status) ? "§a已通过"
                    : STATUS_REJECTED.equals(status) ? "§c已驳回" : "§e待审批";
            inv.setItem(i, mkItem(Material.PAPER,
                    "§e#" + r.get("id") + " " + r.get("type"),
                    "§7上报人: " + r.get("reporter_player"),
                    "§7状态: " + statusText,
                    "§7积分变动: " + r.get("score_change"),
                    "§7内容: " + r.get("content"),
                    "§7来源: " + r.get("source")));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    public Inventory createAdminRecordsGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, GUI_TITLE + " - 审批记录");
        fillBg(inv);
        List<Map<String, Object>> records = getAllRecords(49);
        for (int i = 0; i < records.size() && i < 49; i++) {
            Map<String, Object> r = records.get(i);
            String status = (String) r.get("status");
            String statusText = STATUS_APPROVED.equals(status) ? "§a已通过"
                    : STATUS_REJECTED.equals(status) ? "§c已驳回" : "§e待审批";
            inv.setItem(i, mkItem(Material.PAPER,
                    "§e#" + r.get("id") + " " + r.get("target_player"),
                    "§7上报人: " + r.get("reporter_player"),
                    "§7类型: " + r.get("type"),
                    "§7状态: " + statusText,
                    "§7积分变动: " + r.get("score_change"),
                    "§7内容: " + r.get("content"),
                    "§7来源: " + r.get("source")));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    // ==================== 命令入口 ====================

    public boolean handleCommand(CommandSender sender, String[] args) {
        // /credits → 打开GUI
        if (args.length == 0) {
            if (sender instanceof Player) {
                ((Player) sender).openInventory(createMainGUI((Player) sender));
            } else {
                sender.sendMessage("§c用法: /credits [玩家名|审批|approve]");
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        // ★ 快捷查积分：/credits <玩家名> 直接查，无需 user 关键字
        // 判断第一个参数是否是已知子命令
        boolean isSubCommand = sub.equals("user") || sub.equals("审批") || sub.equals("approve");
        if (!isSubCommand && args.length >= 1) {
            // 直接是玩家名 → 查询积分
            String targetName = args[0];
            int targetScore = getScore(targetName);
            sender.sendMessage("§d§l[信誉积分] §f" + targetName + " §7的当前积分: §e" + targetScore
                    + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
            // 判断是否触发限制
            for (Map<String, Object> rule : getEnabledRules()) {
                int minScore = ((Number) rule.get("min_score")).intValue();
                if (targetScore < minScore) {
                    String desc = (String) rule.get("description");
                    sender.sendMessage("§c§l[信誉积分] §f" + targetName + " §7已触发限制: §c" + desc);
                }
            }
            return true;
        }

        switch (sub) {
            case "user":
                // /credits user <玩家名> — 兼容旧格式
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /credits user <玩家名> 或直接 /credits <玩家名>");
                    return true;
                }
                String targetName = args[1];
                int targetScore = getScore(targetName);
                sender.sendMessage("§d§l[信誉积分] §f" + targetName + " §7的当前积分: §e" + targetScore
                        + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
                // 判断是否触发限制
                for (Map<String, Object> rule : getEnabledRules()) {
                    int minScore = ((Number) rule.get("min_score")).intValue();
                    if (targetScore < minScore) {
                        String desc = (String) rule.get("description");
                        sender.sendMessage("§c§l[信誉积分] §f" + targetName + " §7已触发限制: §c" + desc);
                    }
                }
                return true;

            case "审批":
            case "approve":
                // 管理员审批
                if (!(sender instanceof Player && plugin.isAdmin(sender))) {
                    sender.sendMessage("§c权限不足");
                    return true;
                }
                // /credits approve <id> <通过|驳回> <理由> [积分变动]
                if (args.length < 4) {
                    // 无 id 参数 → 打开 GUI
                    if (sender instanceof Player) {
                        ((Player) sender).openInventory(createPendingGUI((Player) sender));
                    } else {
                        List<Map<String, Object>> records = getPendingRecords();
                        if (records.isEmpty()) {
                            sender.sendMessage("§a没有待审批的记录");
                            return true;
                        }
                        for (Map<String, Object> r : records) {
                            sender.sendMessage("§e#" + r.get("id") + " " + r.get("target_player")
                                    + " §7(" + r.get("type") + ") " + r.get("content"));
                        }
                        sender.sendMessage("§7用法: /credits approve <id> <通过|驳回> <理由> [积分变动]");
                    }
                    return true;
                }
                // 有 id 参数 → CLI 审批
                try {
                    int recordId = Integer.parseInt(args[1]);
                    String decision = args[2].toLowerCase();
                    boolean approved;
                    if (decision.equals("通过") || decision.equals("approve") || decision.equals("approved")) {
                        approved = true;
                    } else if (decision.equals("驳回") || decision.equals("reject") || decision.equals("rejected")) {
                        approved = false;
                    } else {
                        sender.sendMessage("§c决定必须是「通过」或「驳回」");
                        return true;
                    }
                    StringBuilder reason = new StringBuilder();
                    for (int i = 3; i < args.length; i++) {
                        if (i > 3) reason.append(" ");
                        reason.append(args[i]);
                    }
                    int scoreChange = 0;
                    String reasonStr = reason.toString();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(reasonStr);
                    if (approved && m.find()) {
                        scoreChange = Integer.parseInt(m.group(1));
                        reasonStr = reasonStr.substring(0, reasonStr.length() - m.group(1).length()).trim();
                        if (reasonStr.isEmpty()) reasonStr = "信誉积分审批";
                    } else if (approved) {
                        scoreChange = 10;
                    }
                    String approver = sender instanceof Player ? ((Player) sender).getName() : "控制台";
                    boolean ok = approveRecord(recordId, approved, reasonStr, scoreChange, approver);
                    if (ok) {
                        sender.sendMessage("§a审批完成: #" + recordId + " " + (approved ? "通过" : "驳回"));
                    } else {
                        sender.sendMessage("§c审批失败，记录不存在或已处理");
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c记录ID必须是数字");
                }
                return true;

            default:
                sender.sendMessage("§c未知子命令: " + sub);
                sender.sendMessage("§7用法: /credits [玩家名|审批|approve]");
                return true;
        }
    }

    // ==================== GUI 点击处理 ====================

    public void handleClick(Player p, int slot, String title) {
        // 子GUI的返回按钮
        if (slot == 53) {
            p.closeInventory();
            p.openInventory(createMainGUI(p));
            return;
        }

        // 主GUI
        if (title != null && title.equals(GUI_TITLE)) {
            switch (slot) {
                case 0: // 上报好人好事
                    startFlow(p, FlowState.REPORT_GOOD_TARGET);
                    p.closeInventory();
                    p.sendMessage("§a§l[信誉积分] §f请输入要表扬的玩家名");
                    p.sendMessage("§7直接在聊天框输入名字即可");
                    break;
                case 1: // 举报违规违纪
                    startFlow(p, FlowState.REPORT_BAD_TARGET);
                    p.closeInventory();
                    p.sendMessage("§c§l[信誉积分] §f请输入要举报的玩家名");
                    p.sendMessage("§7直接在聊天框输入名字即可");
                    break;
                case 2: // 我的积分
                    int myScore = getScore(p.getName());
                    p.closeInventory();
                    p.sendMessage("§d§l[信誉积分] §f你的当前积分: §e" + myScore + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
                    break;
                case 3: // 积分明细
                    p.closeInventory();
                    p.openInventory(createRecordsGUI(p));
                    break;
                case 4: // 加分 (管理员)
                    if (!plugin.isAdmin(p)) {
                        p.sendMessage("§c§l[信誉积分] §c权限不足");
                        return;
                    }
                    startFlow(p, FlowState.ADD_SCORE_TARGET);
                    p.closeInventory();
                    p.sendMessage("§a§l[信誉积分] §f请输入要加分的玩家名");
                    break;
                case 5: // 扣分 (管理员)
                    if (!plugin.isAdmin(p)) {
                        p.sendMessage("§c§l[信誉积分] §c权限不足");
                        return;
                    }
                    startFlow(p, FlowState.DEDUCT_SCORE_TARGET);
                    p.closeInventory();
                    p.sendMessage("§c§l[信誉积分] §f请输入要扣分的玩家名");
                    break;
                case 6: // 待审批 (管理员)
                    if (!plugin.isAdmin(p)) {
                        p.sendMessage("§c§l[信誉积分] §c权限不足");
                        return;
                    }
                    p.closeInventory();
                    p.openInventory(createPendingGUI(p));
                    break;
                case 7: // 审批记录 (管理员)
                    if (!plugin.isAdmin(p)) {
                        p.sendMessage("§c§l[信誉积分] §c权限不足");
                        return;
                    }
                    p.closeInventory();
                    p.openInventory(createAdminRecordsGUI(p));
                    break;
                case 8: // 规则说明
                    p.closeInventory();
                    p.sendMessage("§d§l[信誉积分] §f规则说明:");
                    for (Map<String, Object> r : getEnabledRules()) {
                        p.sendMessage("§7" + r.get("rule_name") + ": " + r.get("description"));
                    }
                    break;
            }
            return;
        }

        // 待审批详情GUI — 独立处理（标题含「审批详情」，不含「待审批」）
        if (title != null && title.contains("审批详情")) {
            // 返回按钮
            if (slot == 4) {
                detailViewRecord.remove(p.getName());
                p.closeInventory();
                p.openInventory(createPendingGUI(p));
                return;
            }
            Integer rid = detailViewRecord.get(p.getName());
            if (rid == null) {
                p.sendMessage("§c§l[信誉积分] §c记录已失效，请重新打开");
                p.closeInventory();
                return;
            }
            int recordId = rid;
            // Slot 0: 通过
            if (slot == 0) {
                String targetName = getRecordTarget(recordId);
                FlowData fd = new FlowData();
                fd.state = FlowState.APPROVE_SCORE_CHANGE;
                fd.recordId = recordId;
                fd.tempName = targetName;
                chatFlows.put(p.getName(), fd);
                detailViewRecord.remove(p.getName());
                p.closeInventory();
                p.sendMessage("§a§l[信誉积分] §f审批通过 #" + recordId + " (目标: " + targetName + ")");
                p.sendMessage("§7请输入积分变动值(正数加分，负数扣分，如 +10 或 -5)");
                return;
            }
            // Slot 1: 驳回
            if (slot == 1) {
                approveRecord(recordId, false, "管理员GUI驳回", 0, p.getName());
                detailViewRecord.remove(p.getName());
                p.closeInventory();
                p.sendMessage("§c§l[信誉积分] §f已驳回 #" + recordId + " (不扣分不加分)");
                return;
            }
            p.closeInventory();
            return;
        }

        // 待审批列表GUI
        if (title != null && title.contains("待审批")) {
            // 列表GUI的返回按钮
            if (slot == 53) {
                p.closeInventory();
                p.openInventory(createMainGUI(p));
                return;
            }
            // 点击记录 → 打开详情GUI
            if (slot >= 0 && slot < 49) {
                List<Map<String, Object>> records = getPendingRecords();
                if (slot < records.size()) {
                    Map<String, Object> r = records.get(slot);
                    int recordId = ((Number) r.get("id")).intValue();
                    detailViewRecord.put(p.getName(), recordId);
                    p.closeInventory();
                    p.openInventory(createPendingDetailGUI(p, recordId));
                    return;
                }
            }
            p.closeInventory();
            return;
        }
    }

    // ==================== 聊天引导处理 ====================

    /**
     * 在 Main.java onChat 中调用，处理信誉积分的聊天引导输入
     * @return true=消息已消费(需取消聊天事件)，false=非引导状态(放行正常聊天)
     */
    public boolean handleChatInput(Player p, String msg) {
        FlowData fd = chatFlows.get(p.getName());
        if (fd == null) return false;

        switch (fd.state) {
            case REPORT_GOOD_TARGET:
                fd.tempName = msg.trim();
                fd.state = FlowState.REPORT_GOOD_REASON;
                p.sendMessage("§a§l[信誉积分] §f已记录: " + fd.tempName);
                p.sendMessage("§7请输入上报理由(好人好事)");
                return true;

            case REPORT_GOOD_REASON:
                submitReport(fd.tempName, p.getName(), TYPE_GOOD, msg.trim(), "game");
                chatFlows.remove(p.getName());
                p.sendMessage("§a§l[信誉积分] §f上报成功，等待管理员审批");
                return true;

            case REPORT_BAD_TARGET:
                fd.tempName = msg.trim();
                fd.state = FlowState.REPORT_BAD_REASON;
                p.sendMessage("§c§l[信誉积分] §f已记录: " + fd.tempName);
                p.sendMessage("§7请输入举报理由");
                return true;

            case REPORT_BAD_REASON:
                submitReport(fd.tempName, p.getName(), TYPE_BAD, msg.trim(), "game");
                chatFlows.remove(p.getName());
                p.sendMessage("§a§l[信誉积分] §f举报已提交，等待管理员审批");
                return true;

            case ADD_SCORE_TARGET:
                fd.tempName = msg.trim();
                fd.state = FlowState.ADD_SCORE_AMOUNT;
                p.sendMessage("§a§l[信誉积分] §f已记录: " + fd.tempName);
                p.sendMessage("§7请输入加分数值(正整数)");
                return true;

            case ADD_SCORE_AMOUNT:
                try {
                    int amount = Integer.parseInt(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage("§c数值必须是正整数");
                        fd.state = FlowState.ADD_SCORE_AMOUNT;
                        return true;
                    }
                    addScore(fd.tempName, amount, "管理员手动加分");
                    chatFlows.remove(p.getName());
                    p.sendMessage("§a§l[信誉积分] §f已给 " + fd.tempName + " 加分 §e+" + amount);
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字");
                    fd.state = FlowState.ADD_SCORE_AMOUNT;
                }
                return true;

            case DEDUCT_SCORE_TARGET:
                fd.tempName = msg.trim();
                fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                p.sendMessage("§c§l[信誉积分] §f已记录: " + fd.tempName);
                p.sendMessage("§7请输入扣分数值(正整数)");
                return true;

            case DEDUCT_SCORE_AMOUNT:
                try {
                    int amount = Integer.parseInt(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage("§c数值必须是正整数");
                        fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                        return true;
                    }
                    addScore(fd.tempName, -amount, "管理员手动扣分");
                    chatFlows.remove(p.getName());
                    p.sendMessage("§c§l[信誉积分] §f已给 " + fd.tempName + " 扣分 §e-" + amount);
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字");
                    fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                }
                return true;

            case APPROVE_SCORE_CHANGE:
                try {
                    String input = msg.trim();
                    // 支持 +10 / -5 / 10 / -5 格式
                    int amount = Integer.parseInt(input.replaceFirst("^[+]", ""));
                    int rid = fd.recordId;
                    String targetName = fd.tempName;
                    // 通过审批并加分/扣分
                    approveRecord(rid, true, "管理员GUI审批通过", amount, p.getName());
                    chatFlows.remove(p.getName());
                    p.sendMessage("§a§l[信誉积分] §f已通过 #" + rid + " " + targetName + " §e"
                            + (amount > 0 ? "+" : "") + amount);
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字(如 +10 或 -5)");
                }
                return true;
        }
        return true;
    }

    private void startFlow(Player p, FlowState state) {
        FlowData fd = new FlowData();
        fd.state = state;
        chatFlows.put(p.getName(), fd);
    }

    /** 是否处于聊天引导状态 */
    public boolean isInChatFlow(Player p) {
        return chatFlows.containsKey(p.getName());
    }

    // ==================== GUI 工具 ====================

    private void fillBg(Inventory inv) {
        ItemStack gl = mkItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, gl);
    }

    private ItemStack mkItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    // ==================== PHP 端提交线索 ====================

    public boolean submitFromPhp(String targetPlayer, String reporterPlayer, String type, String content) {
        submitReport(targetPlayer, reporterPlayer, type, content, "php");
        return true;
    }

    public boolean approveFromPhp(int recordId, boolean approved, String reason, int scoreChange, String approverName) {
        return approveRecord(recordId, approved, reason, scoreChange, approverName);
    }

    // ==================== 辅助方法 ====================

    /**
     * 查询审批记录的目标玩家名
     */
    private String getRecordTarget(int recordId) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT target_player FROM credit_records WHERE id = ?");
            ps.setInt(1, recordId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String target = rs.getString("target_player");
                ps.close();
                return target;
            }
            ps.close();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 格式化时间戳为可读字符串
     */
    private String formatTime(long timestamp) {
        if (timestamp <= 0) return "未知";
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(new java.util.Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
}
