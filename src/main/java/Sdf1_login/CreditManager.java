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
 *   - /credits 上报好人好事/举报
 *   - PHP 端可提交线索
 *   - 管理员审批 → 加分/扣分
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
                    + "target_player TEXT NOT NULL,"          // 被评价的玩家
                    + "reporter_player TEXT NOT NULL,"        // 上报人
                    + "type TEXT NOT NULL,"                   // good / bad
                    + "content TEXT DEFAULT '',"              // 详细内容
                    + "status TEXT DEFAULT 'pending',"        // pending / approved / rejected
                    + "score_change INTEGER DEFAULT 0,"        // 审批后的积分变动
                    + "created_at INTEGER DEFAULT 0,"          // 上报时间
                    + "approved_at INTEGER DEFAULT 0,"        // 审批时间
                    + "approve_reason TEXT DEFAULT '',"       // 审批理由
                    + "source TEXT DEFAULT 'game',"           // game / php
                    + "FOREIGN KEY(target_player) REFERENCES users(player_name)"
                    + ")");

            // 信誉限制规则表
            st.execute("CREATE TABLE IF NOT EXISTS credit_rules ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "rule_name TEXT NOT NULL UNIQUE,"       // 规则名
                    + "min_score INTEGER DEFAULT 0,"          // 低于此分触发
                    + "max_score INTEGER DEFAULT 0,"          // 高于此分解除（0=无上限）
                    + "restriction_type TEXT DEFAULT '',"     // 限制类型
                    + "restriction_duration INTEGER DEFAULT 0," // 持续秒数（0=直到加分）
                    + "enabled INTEGER DEFAULT 1,"            // 1=启用 0=禁用
                    + "description TEXT DEFAULT ''"           // 说明
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
            // 默认规则：低于60分禁止交易
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) "
                    + "VALUES ('禁止交易', 60, 0, 'trade', 0, '低于60分时禁止与NPC交易')");
            // 默认规则：低于40分禁止进入PVP区域
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) "
                    + "VALUES ('禁止PVP', 40, 0, 'pvp', 0, '低于40分时禁止进入PVP竞技场')");
            // 默认规则：低于20分禁止使用传送
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
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE credit_records SET status = ?, score_change = ?, approved_at = ?, approve_reason = ? WHERE id = ?");
            ps.setString(1, approved ? STATUS_APPROVED : STATUS_REJECTED);
            ps.setInt(2, approved ? scoreChange : 0);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, reason);
            ps.setInt(5, recordId);
            ps.executeUpdate();
            ps.close();

            if (approved && scoreChange != 0) {
                // 查询目标玩家
                PreparedStatement ps2 = db.getConnection().prepareStatement(
                        "SELECT target_player FROM credit_records WHERE id = ?");
                ps2.setInt(1, recordId);
                ResultSet rs = ps2.executeQuery();
                if (rs.next()) {
                    String target = rs.getString("target_player");
                    addScore(target, scoreChange, "信誉积分审批(" + (approved ? "通过" : "驳回") + "): " + reason);
                    plugin.getLogger().info("[信誉积分] " + approverName + " 审批通过 " + target
                            + " 积分变动: " + (scoreChange > 0 ? "+" : "") + scoreChange);
                }
                ps2.close();
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
        // 检查是否触发限制，通知玩家
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

    // ==================== GUI ====================

    public Inventory createPendingGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§d§l信誉积分 - 待审批");
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
                    "§e点击审批"));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    public Inventory createRecordsGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§d§l信誉积分 - 历史记录");
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

    public Inventory createRulesGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, "§d§l信誉积分 - 限制规则");
        fillBg(inv);
        List<Map<String, Object>> rules = getEnabledRules();
        for (int i = 0; i < rules.size() && i < 49; i++) {
            Map<String, Object> r = rules.get(i);
            inv.setItem(i, mkItem(Material.GOLD_INGOT,
                    "§e" + r.get("rule_name"),
                    "§7阈值: " + r.get("min_score"),
                    "§7类型: " + r.get("restriction_type"),
                    "§7说明: " + r.get("description")));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    // ==================== 命令入口 ====================

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            // 无参数：显示玩家自己的积分
            if (sender instanceof Player) {
                String name = ((Player) sender).getName();
                int score = getScore(name);
                sender.sendMessage("§d§l[信誉积分] §f你的当前积分: §e" + score + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
            } else {
                sender.sendMessage("§c用法: /credits [上报|审批|记录|规则] [参数]");
            }
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "上报":
            case "report":
                // /credits report <目标玩家> <good|bad> <内容>
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /credits report <目标玩家> <good|bad> <内容>");
                    return true;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c此命令只能玩家使用");
                    return true;
                }
                String target = args[1];
                String type = args[2].toLowerCase();
                StringBuilder content = new StringBuilder();
                for (int i = 3; i < args.length; i++) {
                    if (i > 3) content.append(" ");
                    content.append(args[i]);
                }
                if (!TYPE_GOOD.equals(type) && !TYPE_BAD.equals(type)) {
                    sender.sendMessage("§c类型必须是 good 或 bad");
                    return true;
                }
                submitReport(target, ((Player) sender).getName(), type, content.toString(), "game");
                sender.sendMessage("§a[信誉积分] 上报成功，等待管理员审批");
                return true;

            case "审批":
            case "approve":
                // 管理员审批
                if (!(sender.hasPermission("sdf1_login.admin"))) {
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
                    // 解析积分变动（最后一个参数如果是数字）
                    int scoreChange = 0;
                    String reasonStr = reason.toString();
                    // 检查是否以数字结尾（积分变动在最后）
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(reasonStr);
                    if (approved && m.find()) {
                        scoreChange = Integer.parseInt(m.group(1));
                        reasonStr = reasonStr.substring(0, reasonStr.length() - m.group(1).length()).trim();
                        if (reasonStr.isEmpty()) reasonStr = "信誉积分审批";
                    } else if (approved) {
                        // 默认好人好事+10，举报+10
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

            case "记录":
            case "records":
                // 查看历史记录
                if (sender instanceof Player) {
                    ((Player) sender).openInventory(createRecordsGUI((Player) sender));
                } else {
                    List<Map<String, Object>> records = getAllRecords(20);
                    for (Map<String, Object> r : records) {
                        sender.sendMessage("§e#" + r.get("id") + " " + r.get("target_player")
                                + " §7(" + r.get("type") + ") " + r.get("status")
                                + " §7积分变动=" + r.get("score_change"));
                    }
                }
                return true;

            case "规则":
            case "rules":
                // 查看限制规则
                if (sender instanceof Player) {
                    ((Player) sender).openInventory(createRulesGUI((Player) sender));
                } else {
                    for (Map<String, Object> r : getEnabledRules()) {
                        sender.sendMessage("§e" + r.get("rule_name")
                                + " §7阈值=" + r.get("min_score")
                                + " §7类型=" + r.get("restriction_type")
                                + " §7说明=" + r.get("description"));
                    }
                }
                return true;

            default:
                sender.sendMessage("§c未知子命令: " + sub);
                return true;
        }
    }

    // ==================== GUI 点击处理 ====================

    public void handleClick(Player p, int slot, String title) {
        if (slot == 53) {
            p.closeInventory();
            return;
        }

        if (title != null && title.contains("待审批")) {
            p.sendMessage("§c请使用 /credits approve <id> <通过|驳回> <理由> [积分变动]");
            p.closeInventory();
        } else if (title != null && title.contains("历史记录")) {
            p.sendMessage("§7此GUI仅展示，请用命令管理");
        } else if (title != null && title.contains("限制规则")) {
            p.sendMessage("§7规则阈值低于当前积分则触发限制");
        }
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

    /**
     * 供 PHP 端调用的提交线索接口（通过 WebManager 对账或独立 API）
     */
    public boolean submitFromPhp(String targetPlayer, String reporterPlayer, String type, String content) {
        submitReport(targetPlayer, reporterPlayer, type, content, "php");
        return true;
    }

    /**
     * 供 PHP 端调用的审批接口
     */
    public boolean approveFromPhp(int recordId, boolean approved, String reason, int scoreChange, String approverName) {
        return approveRecord(recordId, approved, reason, scoreChange, approverName);
    }
}
