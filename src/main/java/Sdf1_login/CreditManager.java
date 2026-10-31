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
 *   - /credits user <玩家名> give/remove <数值> [备注] 管理员快捷加减分
 *   - /credits user give/remove <数值> [备注] 给自己加减分
 *   - 上报通过聊天框分步引导：填目标玩家名 → 填上报理由
 *   - 低于阈值 → 行为限制（可配置规则）
 *   - ★ 第58批-问题4：积分支持小数（如0.01），内部用 double，展示保留2位
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
    public static final String STATUS_REVOKED = "revoked";  // ★ 第59批-问题3/5：上报人自行撤回

    // 上报类型
    public static final String TYPE_GOOD = "good";       // 好人好事
    public static final String TYPE_BAD = "bad";         // 举报

    // ★ 第58批-问题4：积分支持小数，缓存用 Double
    private final Map<String, Double> scoreCache = new ConcurrentHashMap<>();

    /** 格式化积分展示：整数显示整数，小数保留2位 */
    public static String formatScore(double score) {
        if (score == Math.floor(score) && !Double.isInfinite(score)) {
            return String.valueOf((long) score);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", score);
    }

    /** ★ 第60批-问题3b：安全格式化 content 字段，null/empty 显示"无内容" */
    public static String formatContent(Object content) {
        if (content == null) return "§7无内容";
        String s = content.toString().trim();
        return s.isEmpty() ? "§7无内容" : "§7内容: " + s;
    }

    // GUI标题
    public static final String GUI_TITLE = "§d§l信誉积分";

    // GUI尺寸
    private static final int GUI_SIZE = 18;  // ★ 第59批-问题5：扩容到18格(2行)，新增"我的上报"按钮

    // ★ 第59批-问题5：二级菜单27格，22-24号位翻页
    private static final int RECORDS_MENU_SIZE = 27;
    private static final int PAGE_PREV = 22;   // 上一页
    private static final int PAGE_NEXT = 23;   // 下一页
    private static final int PAGE_INFO = 24;   // 页码信息
    private static final int RECORDS_BACK = 26; // 返回

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
        APPROVE_SCORE_CHANGE,     // 审批通过 → 等待积分变动值
        REVOKE_CONFIRM,           // ★ 第59批-问题5：撤回上报确认
        RULE_EDIT_PARAMS          // ★ 第64批：编辑规则参数
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
            // ★ 第58批-问题4：score_change 改为 REAL 支持小数积分
            st.execute("CREATE TABLE IF NOT EXISTS credit_records ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "target_player TEXT NOT NULL,"
                    + "reporter_player TEXT NOT NULL,"
                    + "type TEXT NOT NULL,"
                    + "content TEXT DEFAULT '',"
                    + "status TEXT DEFAULT 'pending',"
                    + "score_change REAL DEFAULT 0,"
                    + "created_at INTEGER DEFAULT 0,"
                    + "approved_at INTEGER DEFAULT 0,"
                    + "approve_reason TEXT DEFAULT '',"
                    + "source TEXT DEFAULT 'game'"
                    + ")");

            // 信誉限制规则表
            // ★ 第63批：新增 limit_amount(限额) 和 multiplier(倍数) 两列
            // ★ 第64批：新增 amount 列 — 规则的参数值（如 login_restore=0.1, chat_penalty=0.5）
            st.execute("CREATE TABLE IF NOT EXISTS credit_rules ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "rule_name TEXT NOT NULL UNIQUE,"
                    + "min_score INTEGER DEFAULT 0,"
                    + "max_score INTEGER DEFAULT 0,"
                    + "restriction_type TEXT DEFAULT '',"
                    + "restriction_duration INTEGER DEFAULT 0,"
                    + "enabled INTEGER DEFAULT 0,"
                    + "description TEXT DEFAULT '',"
                    + "limit_amount REAL DEFAULT 0,"
                    + "multiplier REAL DEFAULT 0,"
                    + "amount REAL DEFAULT 0"
                    + ")");

            // ★ 第63批：已有表扩展新列（列已存在时报错，忽略）
            try { st.executeUpdate("ALTER TABLE credit_rules ADD COLUMN limit_amount REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { st.executeUpdate("ALTER TABLE credit_rules ADD COLUMN multiplier REAL DEFAULT 0"); } catch (Exception ignored) {}
            // ★ 第64批：amount 列 — 新规则的参数值（如 login_restore=0.1, chat_penalty=0.5）
            try { st.executeUpdate("ALTER TABLE credit_rules ADD COLUMN amount REAL DEFAULT 0"); } catch (Exception ignored) {}

            // ★ 第65批修复：每日恢复日志表 — 持久化记录，替代内存 Map
            //   解决服务器重启后同一天重复恢复的问题
            st.execute("CREATE TABLE IF NOT EXISTS credit_restore_log ("
                    + "player_name TEXT NOT NULL PRIMARY KEY,"
                    + "last_restore_date TEXT NOT NULL,"
                    + "restored_at INTEGER DEFAULT 0"
                    + ")");

            // 默认规则初始化
            initDefaultRules();

            // users 表添加 credit_score 列
            // ★ 第58批-问题4：用 REAL 支持小数积分
            try {
                st.executeUpdate("ALTER TABLE users ADD COLUMN credit_score REAL DEFAULT " + DEFAULT_INIT_SCORE);
            } catch (Exception ignored) {}

        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 建表失败: " + e.getMessage());
        }
    }

    /**
     * ★ 第63批：6条内置规则，默认全部禁用（enabled=0）
     * 每条规则启用后，满足分数阈值时对目标玩家做对应限制/加成
     */
    private void initDefaultRules() {
        try {
            Statement st = db.getConnection().createStatement();

            // 规则1: ＜60分 → 债券每小时交易限额5000元(卖出/买入全算)
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) "
                    + "VALUES ('债券交易限额5000', 60, 0, 'bond_trade_limit', 0, 0, '低于60分时债券每小时交易限额5000元(买入/卖出全算)', 5000, 0)");

            // 规则2: ＜20分 → 禁止使用群系传送（GUIManager.teleportToBiome）
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) "
                    + "VALUES ('禁止群系传送', 20, 0, 'block_biome_tp', 0, 0, '低于20分时禁止使用群系传送', 0, 0)");

            // 规则3: ＜0分 → 二手商店内商品标记，提示买家谨慎购买
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) "
                    + "VALUES ('二手商店低信誉警示', 0, 0, 'shop_low_warning', 0, 0, '低于0分时二手商店商品标记为低信誉卖家，提示买家谨慎购买', 0, 0)");

            // 规则4: ＜-10分 → 禁止使用CDK + 债券每小时交易限额1000元
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) "
                    + "VALUES ('禁止CDK+债券限额1000', -10, 0, 'block_cdk_and_bond', 0, 0, '低于-10分时禁止使用CDK兑换码，债券每小时交易限额1000元(买入/卖出全算)', 1000, 0)");

            // 规则5: ＞80分 → 债券每小时交易额1.2倍(接入债券出售限额)
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) "
                    + "VALUES ('债券交易额1.2倍', 0, 80, 'bond_trade_bonus', 0, 0, '高于80分时债券每小时交易额1.2倍(接入债券出售限额)', 0, 1.2)");

            // 规则6: ＞100分 → 二手商店内商品标记为信誉极好，放心购买
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) "
                    + "VALUES ('二手商店高信誉推荐', 0, 100, 'shop_high_recommend', 0, 0, '高于100分时二手商店商品标记为信誉极好卖家，可以放心购买', 0, 0, 0)");

            // ★ 第64批：新规则7 — 每日登录自动恢复积分（默认启用，amount=0.1）
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) "
                    + "VALUES ('每日登录恢复0.1分', 0, 0, 'login_restore', 0, 1, '每日登录自动恢复信誉积分(默认0.1分，可配置)', 0, 0, 0.1)");

            // ★ 第64批：新规则8 — 聊天违规扣分（默认禁用，amount=0.5）
            st.execute("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) "
                    + "VALUES ('聊天违规扣0.5分', 0, 0, 'chat_penalty', 0, 0, '发送脏话或违规链接扣除信誉积分(默认0.5分，可配置)', 0, 0, 0.5)");

            // ★ 清理旧规则（禁止交易/禁止PVP/禁止传送）
            // 第63批替换了内置规则，旧的3条规则不再使用，删除它们
            // 保留管理员手动添加的自定义规则（这些不在内置规则列表中）
            st.execute("DELETE FROM credit_rules WHERE rule_name IN ('禁止交易', '禁止PVP', '禁止传送')");

        } catch (Exception ignored) {}
    }

    // ==================== 积分查询 ====================

    /** ★ 第58批-问题4：返回 double，支持小数积分 */
    public double getScore(String playerName) {
        if (scoreCache.containsKey(playerName)) {
            return scoreCache.get(playerName);
        }
        double score = DEFAULT_INIT_SCORE;
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT credit_score FROM users WHERE player_name = ?");
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                score = rs.getDouble("credit_score");
            }
            ps.close();
        } catch (Exception ignored) {}
        scoreCache.put(playerName, score);
        return score;
    }

    /** 兼容旧调用：返回 int 版本（截断小数） */
    public int getScoreInt(String playerName) {
        return (int) Math.floor(getScore(playerName));
    }

    // ==================== 积分修改 ====================

    /** ★ 第58批-问题4：setScore 支持 double */
    public void setScore(String playerName, double score) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE users SET credit_score = ? WHERE player_name = ?");
            ps.setDouble(1, score);
            ps.setString(2, playerName);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 设置积分失败: " + e.getMessage());
        }
        scoreCache.put(playerName, score);
        checkRestrictions(playerName, score);
    }

    /** ★ 第58批-问题4：addScore 支持 double */
    public void addScore(String playerName, double amount, String reason) {
        double current = getScore(playerName);
        setScore(playerName, current + amount);
        // ★ 第65批修复（#336）：每次积分变动都写入 credit_records，确保 PHP 端能看到完整记录
        //   之前只有 UPDATE users 表，PHP 同步时查不到记录，导致违规扣分/登录恢复等记录缺失
        insertScoreRecord(playerName, amount, reason);
        plugin.getLogger().info("[信誉积分] " + playerName + " 积分变动: "
                + (amount > 0 ? "+" : "") + formatScore(amount) + " (" + reason + ")");
        // ★ 第64批修复（#335）：积分变动后异步推送 PHP，避免 PHP 端长时间显示旧值
        //   防抖：5秒内只推一次，避免连续扣分时疯狂打 HTTP
        triggerCreditPushToPhpDebounced();
    }

    /**
     * ★ 第65批修复（#336）：写入积分变动记录到 credit_records
     *   自动触发的积分变动（违规扣分/登录恢复/管理员调整）都记录在此
     *   status='approved'（系统自动操作，无需审核）
     *   source='game'（来自游戏内，区别于 PHP 端上报）
     */
    private void insertScoreRecord(String playerName, double amount, String reason) {
        try {
            // 根据 reason 推导 type（用于 PHP 端分类展示）
            String type;
            if (reason.contains("聊天违规")) {
                type = "chat_penalty";
            } else if (reason.contains("登录恢复")) {
                type = "login_restore";
            } else if (reason.contains("管理员") || reason.contains("调整")) {
                type = "admin_adjust";
            } else {
                type = "system_adjust";
            }
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "INSERT INTO credit_records (target_player, reporter_player, type, content, status, score_change, created_at, source) "
                            + "VALUES (?, '', ?, ?, 'approved', ?, ?, 'game')");
            ps.setString(1, playerName);
            ps.setString(2, type);
            ps.setString(3, reason);
            ps.setDouble(4, amount);
            ps.setString(5, java.time.LocalDateTime.now().toString());
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 写入积分记录失败: " + e.getMessage());
        }
    }

    /** 兼容旧调用：int 版本的 addScore */
    public void addScore(String playerName, int amount, String reason) {
        addScore(playerName, (double) amount, reason);
    }

    /** 兼容旧调用：int 版本的 setScore */
    public void setScore(String playerName, int score) {
        setScore(playerName, (double) score);
    }

    /** 兼容旧调用：int 版本的 getScore（返回 int） */
    @Deprecated
    public int getScore(String playerName, boolean old) {
        return (int) Math.floor(getScore(playerName));
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

            // ★ 第59批-问题1修复：游戏内提交上报后，立即通知 PHP 拉取
            //   原逻辑：pushCreditToPhp 只在 Timer C 周期运行，PHP 超过2分钟仍显示0个待审核
            //   修复：submitReport 插入记录后，立即触发 pushCreditToPhp（异步，不阻塞主线程）
            triggerCreditPushToPhp();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 上报失败: " + e.getMessage());
        }
    }

    /**
     * ★ 第59批-问题1修复：立即触发信誉积分推送到 PHP
     *   submitReport 在主线程被调用（GUI点击/聊天引导），不能直接做 HTTP 请求阻塞游戏
     *   → 用 Bukkit 异步任务调用 WebManager.pushCreditToPhp（内部 doPost 会阻塞线程）
     */
    private void triggerCreditPushToPhp() {
        try {
            Main plugin = this.plugin;
            if (plugin.webManager == null) return;
            // 异步执行 HTTP 推送，不阻塞主线程
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.webManager.pushCreditToPhp();
                } catch (Exception e) {
                    plugin.getLogger().warning("[信誉积分] 异步推送PHP失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 触发推送PHP失败: " + e.getMessage());
        }
    }

    // ★ 第64批修复（#335）：防抖推送时间戳（ms）
    private long lastCreditPushTimestamp = 0;

    /**
     * ★ 第64批修复（#335）：带防抖的积分推送触发
     *   addScore 可能被频繁调用（多次违规扣分），每次都推 PHP 太浪费
     *   → 5秒内只推一次，最后一次触发会覆盖前面的（合并写入）
     *   首次调用立即推，后续5秒内调用延迟到防抖窗口结束后再推
     */
    private void triggerCreditPushToPhpDebounced() {
        try {
            Main plugin = this.plugin;
            if (plugin.webManager == null) return;
            long now = System.currentTimeMillis();
            if (now - lastCreditPushTimestamp > 5000) {
                // 距上次推送超过5秒 → 立即推
                triggerCreditPushToPhp();
                lastCreditPushTimestamp = now;
            } else {
                // 5秒内 → 延迟到窗口结束后再推（最后一次调用的效果会覆盖前面的）
                final long delayTicks = ((5000 - (now - lastCreditPushTimestamp)) / 50);
                org.bukkit.Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    try {
                        plugin.webManager.pushCreditToPhp();
                        lastCreditPushTimestamp = System.currentTimeMillis();
                    } catch (Exception e) {
                        plugin.getLogger().warning("[信誉积分] 防抖推送PHP失败: " + e.getMessage());
                    }
                }, delayTicks + 1);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 防抖推送PHP触发失败: " + e.getMessage());
        }
    }

    // ==================== 审批 ====================

    /** ★ 第58批-问题4：scoreChange 支持 double */
    public boolean approveRecord(int recordId, boolean approved, String reason, double scoreChange, String approverName) {
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
            ps.setDouble(2, approved ? scoreChange : 0);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, reason);
            ps.setInt(5, recordId);
            ps.executeUpdate();
            ps.close();

            if (approved && scoreChange != 0 && target != null) {
                addScore(target, scoreChange, "信誉积分审批(" + (approved ? "通过" : "驳回") + "): " + reason);
                plugin.getLogger().info("[信誉积分] " + approverName + " 审批通过 " + target
                        + " 积分变动: " + (scoreChange > 0 ? "+" : "") + formatScore(scoreChange));
            }

            // ★ 第59批-问题1修复：审批结果需要同步到PHP
            //   审批通过/驳回后，PHP端需要立即看到审批结果，不用等Timer C周期
            triggerCreditPushToPhp();

            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 审批失败: " + e.getMessage());
            return false;
        }
    }

    /** 兼容旧调用：int 版本的 approveRecord */
    public boolean approveRecord(int recordId, boolean approved, String reason, int scoreChange, String approverName) {
        return approveRecord(recordId, approved, reason, (double) scoreChange, approverName);
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
                row.put("score_change", rs.getDouble("score_change"));
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

    /** ★ 第59批-问题5：查询自己提交的上报（作为 reporter_player） */
    public List<Map<String, Object>> getMyReports(String reporterName, int offset, int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM credit_records WHERE reporter_player = ? ORDER BY created_at DESC LIMIT ? OFFSET ?");
            ps.setString(1, reporterName);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("target_player", rs.getString("target_player"));
                row.put("reporter_player", rs.getString("reporter_player"));
                row.put("type", rs.getString("type"));
                row.put("content", rs.getString("content"));
                row.put("status", rs.getString("status"));
                row.put("score_change", rs.getDouble("score_change"));
                row.put("created_at", rs.getLong("created_at"));
                row.put("source", rs.getString("source"));
                row.put("approve_reason", rs.getString("approve_reason"));
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询我的上报失败: " + e.getMessage());
        }
        return list;
    }

    /** ★ 第59批-问题5：统计自己提交的上报总数 */
    public int getMyReportsCount(String reporterName) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT COUNT(*) as cnt FROM credit_records WHERE reporter_player = ?");
            ps.setString(1, reporterName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("cnt");
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 统计我的上报失败: " + e.getMessage());
        }
        return 0;
    }

    /** ★ 第59批-问题5：撤回自己提交的上报 */
    public boolean revokeReport(int recordId, String reporterName) {
        try {
            // 验证：只能撤回自己提交的记录
            PreparedStatement check = db.getConnection().prepareStatement(
                    "SELECT reporter_player, status FROM credit_records WHERE id = ?");
            check.setInt(1, recordId);
            ResultSet rs = check.executeQuery();
            if (!rs.next()) return false;
            String reporter = rs.getString("reporter_player");
            String status = rs.getString("status");
            rs.close();
            check.close();

            if (!reporter.equals(reporterName)) return false;
            if (STATUS_APPROVED.equals(status)) return false;

            // ★ 第59批-问题5：改为 revoked 状态，保留审计痕迹
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE credit_records SET status = 'revoked', approve_reason = '上报人自行撤回' WHERE id = ?");
            ps.setInt(1, recordId);
            ps.executeUpdate();
            ps.close();

            // ★ 联动PHP：写 sync_requests 标记，PHP 端 pullCreditFromPhp 拉回后同步
            triggerCreditPushToPhp();

            plugin.getLogger().info("[信誉积分] " + reporterName + " 撤回上报 #" + recordId);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 撤回上报失败: " + e.getMessage());
            return false;
        }
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
                // ★ 第63批：新增列
                try { row.put("limit_amount", rs.getDouble("limit_amount")); } catch (Exception ignored) {}
                try { row.put("multiplier", rs.getDouble("multiplier")); } catch (Exception ignored) {}
                // ★ 第64批：amount 列
                try { row.put("amount", rs.getDouble("amount")); } catch (Exception ignored) {}
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询规则失败: " + e.getMessage());
        }
        return list;
    }

    /**
     * ★ 第63批：获取所有规则（含禁用）供管理员GUI展示
     */
    public List<Map<String, Object>> getAllRules() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "SELECT * FROM credit_rules ORDER BY min_score DESC, rule_name");
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", rs.getInt("id"));
                row.put("rule_name", rs.getString("rule_name"));
                row.put("min_score", rs.getInt("min_score"));
                row.put("max_score", rs.getInt("max_score"));
                row.put("restriction_type", rs.getString("restriction_type"));
                row.put("restriction_duration", rs.getInt("restriction_duration"));
                row.put("enabled", rs.getInt("enabled"));
                row.put("description", rs.getString("description"));
                try { row.put("limit_amount", rs.getDouble("limit_amount")); } catch (Exception ignored) {}
                try { row.put("multiplier", rs.getDouble("multiplier")); } catch (Exception ignored) {}
                try { row.put("amount", rs.getDouble("amount")); } catch (Exception ignored) {}
                list.add(row);
            }
            ps.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询所有规则失败: " + e.getMessage());
        }
        return list;
    }

    /**
     * ★ 第63批：切换规则启用/禁用状态
     * ★ 第65批修复（#341）：toggle 后立即推回 PHP（双向交换）
     */
    public boolean toggleRule(int ruleId) {
        try {
            PreparedStatement ps = db.getConnection().prepareStatement(
                    "UPDATE credit_rules SET enabled = CASE enabled WHEN 1 THEN 0 ELSE 1 END WHERE id = ?");
            ps.setInt(1, ruleId);
            ps.executeUpdate();
            ps.close();
            // ★ 第65批修复（#341）：Java 端改了开关 → 立即推回 PHP
            triggerCreditPushToPhpDebounced();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 切换规则失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * ★ 第63批：检查玩家是否受某类型限制
     *   - score < min_score → 触发（低于阈值，min_score 可为正/零/负）
     *   - score > max_score → 触发（高于阈值，bonus类规则）
     */
    public boolean isRestricted(String playerName, String restrictionType) {
        double score = getScore(playerName);
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (!type.equals(restrictionType)) continue;
            double minScore = ((Number) rule.get("min_score")).doubleValue();
            double maxScore = ((Number) rule.get("max_score")).doubleValue();
            // 低于阈值触发（覆盖所有 min_score 情况：正/零/负）
            if (score < minScore) return true;
            // 高于阈值触发（bonus类规则）
            if (maxScore > 0 && score > maxScore) return true;
        }
        return false;
    }

    /**
     * ★ 第63批：获取玩家债券每小时交易限额
     *   规则1: <60分 → 5000元
     *   规则4: <-10分 → 1000元（更严格，覆盖规则1）
     *   返回 0 表示无限制
     */
    public double getBondTradeLimit(String playerName) {
        double score = getScore(playerName);
        double limit = 0;
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (!type.equals("bond_trade_limit") && !type.equals("block_cdk_and_bond")) continue;
            double ruleLimit = ((Number) rule.getOrDefault("limit_amount", 0)).doubleValue();
            if (ruleLimit <= 0) continue;
            double minScore = ((Number) rule.get("min_score")).doubleValue();
            // 分数低于阈值 → 适用此限额
            if (score < minScore) {
                // 取最严格的限额（最小的值）
                if (limit == 0 || ruleLimit < limit) {
                    limit = ruleLimit;
                }
            }
        }
        return limit;
    }

    /**
     * ★ 第63批：获取玩家债券交易倍数
     *   规则5: >80分 → 1.2倍
     *   返回 1.0 表示无加成
     */
    public double getBondTradeMultiplier(String playerName) {
        double score = getScore(playerName);
        double multiplier = 1.0;
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (!type.equals("bond_trade_bonus")) continue;
            double ruleMultiplier = ((Number) rule.getOrDefault("multiplier", 0)).doubleValue();
            if (ruleMultiplier <= 0) continue;
            double maxScore = ((Number) rule.get("max_score")).doubleValue();
            // 分数高于阈值 → 适用此倍数
            if (score > maxScore) {
                // 取最高的倍数
                if (ruleMultiplier > multiplier) {
                    multiplier = ruleMultiplier;
                }
            }
        }
        return multiplier;
    }

    /**
     * ★ 第63批：二手商店低信誉警示（规则3）
     *   分数 < 0 时返回 true，商品标记为低信誉卖家
     */
    public boolean isShopLowWarning(String playerName) {
        double score = getScore(playerName);
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (!type.equals("shop_low_warning")) continue;
            double minScore = ((Number) rule.get("min_score")).doubleValue();
            if (score < minScore) return true;
        }
        return false;
    }

    /**
     * ★ 第63批：二手商店高信誉推荐（规则6）
     *   分数 > 100 时返回 true，商品标记为信誉极好
     */
    public boolean isShopHighRecommend(String playerName) {
        double score = getScore(playerName);
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (!type.equals("shop_high_recommend")) continue;
            double maxScore = ((Number) rule.get("max_score")).doubleValue();
            if (score > maxScore) return true;
        }
        return false;
    }

    private void checkRestrictions(String playerName, double score) {
        for (Map<String, Object> rule : getEnabledRules()) {
            double minScore = ((Number) rule.get("min_score")).doubleValue();
            double maxScore = ((Number) rule.get("max_score")).doubleValue();
            String type = (String) rule.get("restriction_type");
            String desc = (String) rule.get("description");

            Player p = Bukkit.getPlayerExact(playerName);
            if (p == null || !p.isOnline()) continue;

            // 低于阈值触发（限制类规则）
            boolean belowThreshold = score < minScore;
            // 高于阈值触发（加成类规则，如债券1.2倍）
            boolean aboveThreshold = maxScore > 0 && score > maxScore;

            if (belowThreshold || aboveThreshold) {
                String direction = belowThreshold ? "低于阈值" : "高于阈值";
                double threshold = belowThreshold ? minScore : maxScore;
                String ruleName = (String) rule.get("rule_name");
                p.sendMessage("§c§l[信誉积分] §f你的积分 " + formatScore(score) + " " + direction
                        + " " + formatScore(threshold) + "，"
                        + (aboveThreshold ? "§a已触发加成: §e" : "已触发限制: §e") + ruleName);
            }
        }
    }

    // ==================== ★ 第64批：登录恢复 & 聊天管控 ====================

    /** 玩家上次每日恢复的日期（playerName -> "YYYY-MM-DD"） */
    private final Map<String, String> lastRestoreDate = new ConcurrentHashMap<>();

    /** 内置规则名称列表（用于恢复出厂时识别） */
    private static final List<String> BUILTIN_RULE_NAMES = Arrays.asList(
        "债券交易限额5000", "禁止群系传送", "二手商店低信誉警示",
        "禁止CDK+债券限额1000", "债券交易额1.2倍", "二手商店高信誉推荐",
        "每日登录恢复0.1分", "聊天违规扣0.5分"
    );

    /**
     * ★ 第65批修复：每日登录自动恢复积分（持久化版）
     *   在 PlayerJoinEvent 中调用，每日仅触发一次
     *   amount 参数从规则配置中读取，默认0.1
     *   持久化到 credit_restore_log 表，服务器重启后同一天不会重复恢复
     */
    public void tryDailyLoginRestore(Player p) {
        String name = p.getName();
        String today = java.time.LocalDate.now().toString();

        // 先查内存缓存（快路径）
        String memDate = lastRestoreDate.get(name);
        if (memDate != null && memDate.equals(today)) return; // 今天已经恢复过了

        // 查数据库（持久化，防重启重复）
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT last_restore_date FROM credit_restore_log WHERE player_name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dbDate = rs.getString("last_restore_date");
                    if (dbDate != null && dbDate.equals(today)) {
                        lastRestoreDate.put(name, today); // 回填缓存
                        return; // 今天已经恢复过了
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 查询恢复日志失败: " + e.getMessage());
        }

        // 查找 login_restore 规则
        double amount = 0;
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (type.equals("login_restore")) {
                amount = ((Number) rule.getOrDefault("amount", 0)).doubleValue();
                break;
            }
        }
        if (amount <= 0) return; // 规则未启用或 amount 未配置

        // 恢复积分
        addScore(name, amount, "每日登录自动恢复");
        p.sendMessage("§a§l[信誉积分] §f每日登录恢复信誉积分 §e+" + formatScore(amount));

        // 持久化到数据库
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "INSERT OR REPLACE INTO credit_restore_log (player_name, last_restore_date, restored_at) VALUES (?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, today);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 写入恢复日志失败: " + e.getMessage());
        }

        // 回填内存缓存
        lastRestoreDate.put(name, today);
    }

    /**
     * ★ 第64批：聊天违规检测 — 在 AsyncPlayerChatEvent 中调用
     *   检测脏话/违规链接，命中则扣分（amount 从规则配置读取，默认0.5）
     *   ★ 修复（#333）：不再自己搞一套违禁词，直接委托 ChatFilterManager.checkSensitiveWords
     *     —— 白名单、归一化、URL检测全在 ChatFilterManager 里统一管理，只维护一套系统
     * @return true=检测到违规（不阻止聊天，仅扣分），false=未检测到
     */
    public boolean checkChatViolation(Player p, String msg) {
        if (msg == null || msg.isEmpty()) return false;

        // ★ 委托 ChatFilterManager 的敏感词检测（含归一化：s.b / s b → sb）
        //   白名单URL在 ChatFilterManager 内部处理，这里只管命中后扣分
        List<String> matchedWords;
        try {
            matchedWords = plugin.getChatFilter().checkSensitiveWords(msg);
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉同步] 调用 ChatFilterManager 敏感词检测失败: " + e.getMessage());
            return false;
        }
        if (matchedWords == null || matchedWords.isEmpty()) return false;

        // 查找 chat_penalty 规则
        double amount = 0;
        for (Map<String, Object> rule : getEnabledRules()) {
            String type = (String) rule.get("restriction_type");
            if (type.equals("chat_penalty")) {
                amount = ((Number) rule.getOrDefault("amount", 0)).doubleValue();
                break;
            }
        }
        if (amount <= 0) return false; // 规则未启用

        // 扣分
        addScore(p.getName(), -amount, "聊天违规扣分");
        p.sendMessage("§c§l[信誉积分] §c聊天违规，扣除信誉积分 §e-" + formatScore(amount));
        return true;
    }

    // ==================== ★ 第64批：规则参数编辑 & 恢复出厂 ====================

    /**
     * ★ 第64批：更新规则的参数
     *   ruleId, minScore, maxScore, limitAmount, multiplier, amount
     *   只更新非0值（null 或 <=0 的字段跳过，避免误清空）
     */
    public boolean updateRuleParams(int ruleId, int minScore, int maxScore,
                                   double limitAmount, double multiplier, double amount) {
        try {
            // 构建动态 UPDATE
            StringBuilder sql = new StringBuilder("UPDATE credit_rules SET ");
            List<Object> params = new ArrayList<>();

            boolean hasAny = false;
            if (minScore != 0) { sql.append("min_score = ?, "); params.add(minScore); hasAny = true; }
            if (maxScore != 0) { sql.append("max_score = ?, "); params.add(maxScore); hasAny = true; }
            if (limitAmount != 0) { sql.append("limit_amount = ?, "); params.add(limitAmount); hasAny = true; }
            if (multiplier != 0) { sql.append("multiplier = ?, "); params.add(multiplier); hasAny = true; }
            if (amount != 0) { sql.append("amount = ?, "); params.add(amount); hasAny = true; }

            if (!hasAny) return false;

            sql.setLength(sql.length() - 2); // 去掉末尾 ", "
            sql.append(" WHERE id = ?");
            params.add(ruleId);

            PreparedStatement ps = db.getConnection().prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            ps.executeUpdate();
            ps.close();
            plugin.getLogger().info("[信誉积分] 规则 #" + ruleId + " 参数已更新");
            // ★ 第65批修复（#341）：Java 端改了规则参数 → 立即推回 PHP
            triggerCreditPushToPhpDebounced();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 更新规则参数失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * ★ 第64批：一键恢复内置规则到出厂默认
     *   仅恢复 BUILTIN_RULE_NAMES 列表中的规则
     *   description 和 rule_name 不变，只恢复数值参数
     */
    public boolean resetBuiltinRulesToDefault() {
        try {
            Statement st = db.getConnection().createStatement();
            // 恢复默认参数（rule_name → min, max, enabled, limit_amount, multiplier, amount）
            st.execute("UPDATE credit_rules SET min_score=60, max_score=0, enabled=0, limit_amount=5000, multiplier=0, amount=0 WHERE rule_name='债券交易限额5000'");
            st.execute("UPDATE credit_rules SET min_score=20, max_score=0, enabled=0, limit_amount=0, multiplier=0, amount=0 WHERE rule_name='禁止群系传送'");
            st.execute("UPDATE credit_rules SET min_score=0, max_score=0, enabled=0, limit_amount=0, multiplier=0, amount=0 WHERE rule_name='二手商店低信誉警示'");
            st.execute("UPDATE credit_rules SET min_score=-10, max_score=0, enabled=0, limit_amount=1000, multiplier=0, amount=0 WHERE rule_name='禁止CDK+债券限额1000'");
            st.execute("UPDATE credit_rules SET min_score=0, max_score=80, enabled=0, limit_amount=0, multiplier=1.2, amount=0 WHERE rule_name='债券交易额1.2倍'");
            st.execute("UPDATE credit_rules SET min_score=0, max_score=100, enabled=0, limit_amount=0, multiplier=0, amount=0 WHERE rule_name='二手商店高信誉推荐'");
            st.execute("UPDATE credit_rules SET min_score=0, max_score=0, enabled=1, limit_amount=0, multiplier=0, amount=0.1 WHERE rule_name='每日登录恢复0.1分'");
            st.execute("UPDATE credit_rules SET min_score=0, max_score=0, enabled=0, limit_amount=0, multiplier=0, amount=0.5 WHERE rule_name='聊天违规扣0.5分'");
            plugin.getLogger().info("[信誉积分] 内置规则已恢复出厂默认");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉积分] 恢复出厂失败: " + e.getMessage());
            return false;
        }
    }

    /** 检查规则名是否为内置规则 */
    public boolean isBuiltinRule(String ruleName) {
        return BUILTIN_RULE_NAMES.contains(ruleName);
    }

    // ==================== 9格 GUI ====================

    public Inventory createMainGUI(Player p) {
        boolean admin = plugin.isAdmin(p);
        // ★ 第60批-问题2：管理员18格，普通用户9格
        int size = admin ? 18 : 9;
        Inventory inv = Bukkit.createInventory(null, size, GUI_TITLE);

        // ═══════════════ 所有人可见 (Slot 0-4) ═══════════════

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
        double myScore = getScore(p.getName());
        inv.setItem(2, mkItem(Material.GOLD_INGOT,
                "§e§l我的积分",
                "§7当前积分: " + formatScore(myScore),
                "§7初始积分: " + DEFAULT_INIT_SCORE));

        // Slot 3: 积分明细
        inv.setItem(3, mkItem(Material.PAPER,
                "§f§l积分明细",
                "§7查看自己的积分变动记录",
                "§7点击查看"));

        // Slot 4: 我的上报
        inv.setItem(4, mkItem(Material.WRITABLE_BOOK,
                "§b§l我的上报",
                "§7查看自己提交的上报记录",
                "§7可撤回未通过的上报",
                "§7点击查看"));

        if (admin) {
            // ═══════════════ 管理员专用 (Slot 5-8) ═══════════════

            // Slot 5: 加分
            inv.setItem(5, mkItem(Material.EMERALD,
                    "§a§l加分",
                    "§7管理员给玩家加分",
                    "§7点击后聊天框输入玩家名"));

            // Slot 6: 扣分
            inv.setItem(6, mkItem(Material.REDSTONE,
                    "§c§l扣分",
                    "§7管理员给玩家扣分",
                    "§7点击后聊天框输入玩家名"));

            // Slot 7: 待审批
            inv.setItem(7, mkItem(Material.CHEST,
                    "§e§l待审批",
                    "§7审批玩家上报的好人好事/举报",
                    "§7点击查看"));

            // Slot 8: 审批记录
            inv.setItem(8, mkItem(Material.BOOK,
                    "§b§l审批记录",
                    "§7查看历史审批记录",
                    "§7点击查看"));

            // Slot 9: 规则配置（管理员）
            inv.setItem(9, mkItem(Material.COMMAND_BLOCK,
                    "§6§l规则配置",
                    "§7管理信誉积分限制规则",
                    "§7启用/禁用内置规则",
                    "§7点击查看"));
        } else {
            // ═══════════════ 非管理员：规则说明放在 Slot 5 ═══════════════
            inv.setItem(5, mkItem(Material.IRON_INGOT,
                    "§7§l规则说明",
                    "§7信誉积分低于60分: 债券交易限额5000元",
                    "§7信誉积分低于20分: 禁止使用群系传送",
                    "§7信誉积分低于0分: 二手商店警示买家",
                    "§7信誉积分低于-10分: 禁止CDK+债券限额1000元",
                    "§7信誉积分高于80分: 债券交易额1.2倍",
                    "§7信誉积分高于100分: 二手商店信誉极好",
                    "§7每日登录: 自动恢复0.1分",
                    "§7聊天违规: 扣0.5分(默认禁用)"));
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
                    formatContent(r.get("content")),
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
                formatContent(record.get("content")),
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
            String statusText;
            if (STATUS_APPROVED.equals(status)) {
                statusText = "§a已通过";
            } else if (STATUS_REJECTED.equals(status)) {
                statusText = "§c已驳回";
            } else if (STATUS_REVOKED.equals(status)) {
                statusText = "§7已撤回";
            } else {
                statusText = "§e待审批";
            }
            inv.setItem(i, mkItem(Material.PAPER,
                    "§e#" + r.get("id") + " " + r.get("type"),
                    "§7上报人: " + r.get("reporter_player"),
                    "§7状态: " + statusText,
                    "§7积分变动: " + formatScore((Double) r.get("score_change")),
                    formatContent(r.get("content")),
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
            String statusText;
            if (STATUS_APPROVED.equals(status)) {
                statusText = "§a已通过";
            } else if (STATUS_REJECTED.equals(status)) {
                statusText = "§c已驳回";
            } else if (STATUS_REVOKED.equals(status)) {
                statusText = "§7已撤回";
            } else {
                statusText = "§e待审批";
            }
            inv.setItem(i, mkItem(Material.PAPER,
                    "§e#" + r.get("id") + " " + r.get("target_player"),
                    "§7上报人: " + r.get("reporter_player"),
                    "§7类型: " + r.get("type"),
                    "§7状态: " + statusText,
                    "§7积分变动: " + formatScore((Double) r.get("score_change")),
                    formatContent(r.get("content")),
                    "§7来源: " + r.get("source")));
        }
        inv.setItem(53, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    // ==================== ★ 第59批-问题5：我的上报 27格二级菜单 ====================

    // 每页显示条数（27格 - 5格导航区 = 22格，实际每页显示 20 条留边距）
    private static final int REPORTS_PER_PAGE = 20;

    // 页码跟踪（playerName -> 当前页码）
    private final Map<String, Integer> myReportsPage = new ConcurrentHashMap<>();

    /**
     * ★ 第59批-问题5：创建"我的上报"27格二级菜单
     * 布局：
     *   Slot 0-19: 上报记录（每页20条）
     *   Slot 21: 返回
     *   Slot 22: 上一页
     *   Slot 23: 下一页
     *   Slot 24: 页码信息
     */
    public Inventory createMyReportsGUI(Player p, int page) {
        Inventory inv = Bukkit.createInventory(null, RECORDS_MENU_SIZE, GUI_TITLE + " - 我的上报");
        fillBg(inv);

        int totalCount = getMyReportsCount(p.getName());
        int totalPages = Math.max(1, (totalCount + REPORTS_PER_PAGE - 1) / REPORTS_PER_PAGE);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int offset = (page - 1) * REPORTS_PER_PAGE;

        List<Map<String, Object>> reports = getMyReports(p.getName(), offset, REPORTS_PER_PAGE);
        for (int i = 0; i < reports.size(); i++) {
            Map<String, Object> r = reports.get(i);
            String status = (String) r.get("status");
            Material mat = TYPE_GOOD.equals(r.get("type")) ? Material.EMERALD : Material.REDSTONE;
            String typeText = TYPE_GOOD.equals(r.get("type")) ? "§a好人好事" : "§c举报";
            String statusText;
            if (STATUS_PENDING.equals(status)) {
                statusText = "§e待审批";
            } else if (STATUS_APPROVED.equals(status)) {
                statusText = "§a已通过";
            } else if (STATUS_REJECTED.equals(status)) {
                statusText = "§c已驳回";
            } else if (STATUS_REVOKED.equals(status)) {
                statusText = "§7已撤回";
            } else {
                statusText = "§7" + status;
            }

            // 判断是否可撤回：pending 或 rejected 且未撤回
            boolean canRevoke = (STATUS_PENDING.equals(status) || STATUS_REJECTED.equals(status));
            String revokeLore = canRevoke ? "§a§l点击查看/右键快速撤回" : "";

            inv.setItem(i, mkItem(mat,
                    "§e#" + r.get("id") + " → " + r.get("target_player"),
                    "§7类型: " + typeText,
                    "§7状态: " + statusText,
                    formatContent(r.get("content")),
                    "§7时间: " + formatTime((Long) r.get("created_at")),
                    revokeLore));
        }

        // Slot 21: 返回
        inv.setItem(21, mkItem(Material.ARROW, "§7返回"));

        // Slot 22: 上一页
        if (page > 1) {
            inv.setItem(PAGE_PREV, mkItem(Material.PLAYER_HEAD,
                    "§e§l上一页",
                    "§7当前第 " + page + "/" + totalPages + " 页"));
        }

        // Slot 23: 下一页
        if (page < totalPages) {
            inv.setItem(PAGE_NEXT, mkItem(Material.PLAYER_HEAD,
                    "§e§l下一页",
                    "§7当前第 " + page + "/" + totalPages + " 页"));
        }

        // Slot 24: 页码信息
        inv.setItem(PAGE_INFO, mkItem(Material.PAPER,
                "§7第 " + page + "/" + totalPages + " 页",
                "§7共 " + totalCount + " 条上报",
                "§7右键点击记录可撤回"));

        return inv;
    }

    /**
     * ★ 第63批：规则配置GUI（27格，每页显示5条规则）
     * 布局：
     *   Slot 0-4: 5条规则（点击启用/禁用）
     *   Slot 21: 返回
     *   Slot 22: 上一页
     *   Slot 23: 下一页
     *   Slot 24: 页码信息
     */
    private static final int RULES_MENU_SIZE = 27;
    private static final int RULES_PER_PAGE = 5;

    private final Map<String, Integer> rulesPage = new ConcurrentHashMap<>();

    public Inventory createRulesGUI(Player p, int page) {
        Inventory inv = Bukkit.createInventory(null, RULES_MENU_SIZE, GUI_TITLE + " - 规则配置");
        fillBg(inv);

        List<Map<String, Object>> rules = getAllRules();
        int totalCount = rules.size();
        int totalPages = Math.max(1, (totalCount + RULES_PER_PAGE - 1) / RULES_PER_PAGE);
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;
        int offset = (page - 1) * RULES_PER_PAGE;

        for (int i = 0; i < RULES_PER_PAGE; i++) {
            int idx = offset + i;
            if (idx >= rules.size()) break;
            Map<String, Object> r = rules.get(idx);
            int ruleId = ((Number) r.get("id")).intValue();
            int enabled = ((Number) r.get("enabled")).intValue();
            String ruleName = (String) r.get("rule_name");
            String description = (String) r.get("description");
            double minScore = ((Number) r.get("min_score")).doubleValue();
            double maxScore = ((Number) r.get("max_score")).doubleValue();
            double limitAmount = ((Number) r.getOrDefault("limit_amount", 0)).doubleValue();
            double multiplier = ((Number) r.getOrDefault("multiplier", 0)).doubleValue();
            double amount = ((Number) r.getOrDefault("amount", 0)).doubleValue();

            // 启用状态决定颜色和材质
            Material mat = enabled == 1 ? Material.GREEN_WOOL : Material.RED_WOOL;
            String statusText = enabled == 1 ? "§a§l已启用" : "§c§l已禁用";

            // 构建lore
            String thresholdText;
            if (minScore > 0 && maxScore > 0) {
                thresholdText = "§7阈值: " + minScore + " < 积分 < " + maxScore;
            } else if (minScore > 0) {
                thresholdText = "§7低于 " + minScore + " 分时触发";
            } else if (maxScore > 0) {
                thresholdText = "§7高于 " + maxScore + " 分时触发";
            } else {
                thresholdText = "§7无限阈值";
            }

            String detailText = "§7" + description;
            if (limitAmount > 0) {
                detailText += "\n§7限额: " + formatScore(limitAmount) + " 元/小时";
            }
            if (multiplier > 0) {
                detailText += "\n§7倍数: " + multiplier + " 倍";
            }
            if (amount > 0) {
                detailText += "\n§7参数值: " + amount;
            }

            inv.setItem(i, mkItem(mat,
                    "§e§l" + ruleName + " " + statusText,
                    thresholdText,
                    detailText,
                    "",
                    "§7点击切换启用状态",
                    "§7右键编辑参数"));
        }

        // Slot 21: 返回
        inv.setItem(21, mkItem(Material.ARROW, "§7返回"));

        // Slot 25: 恢复出厂
        inv.setItem(25, mkItem(Material.GOLD_INGOT,
                "§6§l恢复出厂默认",
                "§7将所有内置规则参数恢复为默认值",
                "§7不影响自定义规则"));

        // Slot 22: 上一页
        if (page > 1) {
            inv.setItem(22, mkItem(Material.PLAYER_HEAD,
                    "§e§l上一页",
                    "§7当前第 " + page + "/" + totalPages + " 页"));
        }

        // Slot 23: 下一页
        if (page < totalPages) {
            inv.setItem(23, mkItem(Material.PLAYER_HEAD,
                    "§e§l下一页",
                    "§7当前第 " + page + "/" + totalPages + " 页"));
        }

        // Slot 24: 页码信息
        inv.setItem(24, mkItem(Material.PAPER,
                "§7第 " + page + "/" + totalPages + " 页",
                "§7共 " + totalCount + " 条规则",
                "§7点击切换启用",
                "§7右键编辑参数"));

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
        boolean isSubCommand = sub.equals("user") || sub.equals("审批") || sub.equals("approve")
                || sub.equals("give") || sub.equals("remove");
        // ★ 第58批-问题3修复：/credits <玩家名> give/remove <数值> [备注] — 给他人加减分
        //   之前只判断第一个参数，WRW 被当成玩家名查询，give/remove 被忽略。
        //   现在先检查第二个参数是不是 give/remove，是的话走加减分流程。
        if (!isSubCommand && args.length >= 3) {
            String arg1Lower = args[1].toLowerCase();
            if (arg1Lower.equals("give") || arg1Lower.equals("remove")) {
                // /credits <玩家名> give/remove <数值> [备注] — 给他人加减分
                if (!(sender instanceof Player)) {
                    sender.sendMessage("§c仅玩家可用");
                    return true;
                }
                Player p = (Player) sender;
                boolean isGive = arg1Lower.equals("give");
                // 给他人加减分需要管理员权限
                if (!plugin.isAdmin(p)) {
                    p.sendMessage("§c§l[信誉积分] §c权限不足");
                    return true;
                }
                String targetName = args[0];
                String amountStr = args[2];
                double amount;
                try {
                    amount = Double.parseDouble(amountStr);
                } catch (NumberFormatException e) {
                    p.sendMessage("§c§l[信誉积分] §c数值必须是数字: " + amountStr);
                    return true;
                }
                if (!isGive) amount = -Math.abs(amount); // remove → 负数
                else amount = Math.abs(amount); // give → 正数
                String reason = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : (isGive ? "管理员加分" : "管理员扣分");
                // ★ 第58批-问题3修复：负分用户也能继续加减分——直接 addScore，不检查 score 是否 < 0
                double newScore = getScore(targetName) + amount;
                addScore(targetName, amount, reason);
                p.sendMessage("§d§l[信誉积分] §f对 " + targetName + " " + (isGive ? "加分" : "扣分") + "成功: §e"
                        + (amount > 0 ? "+" : "") + formatScore(amount)
                        + " §7目标当前积分: §e" + formatScore(newScore));
                return true;
            }
        }
        if (!isSubCommand && args.length >= 1) {
            // 直接是玩家名 → 查询积分
            String targetName = args[0];
            double targetScore = getScore(targetName);
            sender.sendMessage("§d§l[信誉积分] §f" + targetName + " §7的当前积分: §e" + formatScore(targetScore)
                    + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
            // 判断是否触发限制
            for (Map<String, Object> rule : getEnabledRules()) {
                double minScore = ((Number) rule.get("min_score")).doubleValue();
                if (targetScore < minScore) {
                    String ruleName = (String) rule.get("rule_name");
                    sender.sendMessage("§c§l[信誉积分] §f" + targetName + " §7已触发限制: §c" + ruleName);
                }
            }
            return true;
        }

        // ★ 第58批-问题4：/credits give/remove <数值> [备注] — 给自己加减分
        if (sub.equals("give") || sub.equals("remove")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            boolean isGive = sub.equals("give");
            if (!isGive && !plugin.isAdmin(p)) {
                p.sendMessage("§c§l[信誉积分] §c权限不足");
                return true;
            }
            if (args.length < 2) {
                p.sendMessage("§c用法: /credits " + (isGive ? "give" : "remove") + " <数值> [备注]");
                p.sendMessage("§7示例: /credits give 5 帮助新人 / credits remove 0.01 违规");
                return true;
            }
            String amountStr = args[1];
            double amount;
            try {
                amount = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                p.sendMessage("§c§l[信誉积分] §c数值必须是数字: " + amountStr);
                return true;
            }
            if (!isGive) amount = -Math.abs(amount); // remove → 负数
            else amount = Math.abs(amount); // give → 正数
            String reason = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : (isGive ? "快捷加分" : "快捷扣分");
            double newScore = getScore(p.getName()) + amount;
            addScore(p.getName(), amount, reason);
            p.sendMessage("§d§l[信誉积分] §f" + (isGive ? "加分" : "扣分") + "成功: §e"
                    + (amount > 0 ? "+" : "") + formatScore(amount)
                    + " §7当前积分: §e" + formatScore(newScore));
            return true;
        }

        switch (sub) {
            case "user":
                // /credits user <玩家名> — 查积分
                // /credits user <玩家名> give/remove <数值> [备注] — 快捷加减分（管理员）
                // /credits user give/remove <数值> [备注] — 给自己加减分
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /credits user <玩家名> [give/remove <数值> [备注]]");
                    sender.sendMessage("§7或: /credits user give/remove <数值> [备注] (给自己加减分)");
                    return true;
                }
                String arg1 = args[1].toLowerCase();

                // ★ /credits user give/remove <数值> [备注] — 给自己加减分
                if (arg1.equals("give") || arg1.equals("remove")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§c仅玩家可用");
                        return true;
                    }
                    Player p = (Player) sender;
                    boolean isGive = arg1.equals("give");
                    if (!isGive && !plugin.isAdmin(p)) {
                        p.sendMessage("§c§l[信誉积分] §c权限不足");
                        return true;
                    }
                    if (args.length < 3) {
                        p.sendMessage("§c用法: /credits user " + (isGive ? "give" : "remove") + " <数值> [备注]");
                        p.sendMessage("§7示例: /credits user give 5 帮助新人");
                        return true;
                    }
                    String amountStr = args[2];
                    double amount;
                    try {
                        amount = Double.parseDouble(amountStr);
                    } catch (NumberFormatException e) {
                        p.sendMessage("§c§l[信誉积分] §c数值必须是数字: " + amountStr);
                        return true;
                    }
                    if (!isGive) amount = -Math.abs(amount);
                    else amount = Math.abs(amount);
                    String reason = args.length >= 4 ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)) : (isGive ? "快捷加分" : "快捷扣分");
                    double newScore = getScore(p.getName()) + amount;
                    addScore(p.getName(), amount, reason);
                    p.sendMessage("§d§l[信誉积分] §f" + (isGive ? "加分" : "扣分") + "成功: §e"
                            + (amount > 0 ? "+" : "") + formatScore(amount)
                            + " §7当前积分: §e" + formatScore(newScore));
                    return true;
                }

                // /credits user <玩家名> [give/remove <数值> [备注>] — 查积分或快捷加减分
                String targetName = args[1];
                // 检查是否有第三个参数 give/remove
                if (args.length >= 4) {
                    String action = args[2].toLowerCase();
                    if (action.equals("give") || action.equals("remove")) {
                        if (!(sender instanceof Player && plugin.isAdmin(sender))) {
                            sender.sendMessage("§c§l[信誉积分] §c权限不足");
                            return true;
                        }
                        boolean isGive = action.equals("give");
                        String amountStr = args[3];
                        double amount;
                        try {
                            amount = Double.parseDouble(amountStr);
                        } catch (NumberFormatException e) {
                            sender.sendMessage("§c§l[信誉积分] §c数值必须是数字: " + amountStr);
                            return true;
                        }
                        if (!isGive) amount = -Math.abs(amount);
                        else amount = Math.abs(amount);
                        String reason = args.length >= 5 ? String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)) : (isGive ? "管理员快捷加分" : "管理员快捷扣分");
                        double newScore = getScore(targetName) + amount;
                        addScore(targetName, amount, reason);
                        sender.sendMessage("§d§l[信誉积分] §f已给 " + targetName + " " + (isGive ? "加分" : "扣分") + " §e"
                                + (amount > 0 ? "+" : "") + formatScore(amount)
                                + " §7当前积分: §e" + formatScore(newScore));
                        return true;
                    }
                }

                // 普通查询
                double targetScore = getScore(targetName);
                sender.sendMessage("§d§l[信誉积分] §f" + targetName + " §7的当前积分: §e" + formatScore(targetScore)
                        + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
                // 判断是否触发限制
                for (Map<String, Object> rule : getEnabledRules()) {
                    double minScore = ((Number) rule.get("min_score")).doubleValue();
                    if (targetScore < minScore) {
                        String ruleName = (String) rule.get("rule_name");
                        sender.sendMessage("§c§l[信誉积分] §f" + targetName + " §7已触发限制: §c" + ruleName);
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
                    double scoreChange = 0;
                    String reasonStr = reason.toString();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)$").matcher(reasonStr);
                    if (approved && m.find()) {
                        scoreChange = Double.parseDouble(m.group(1));
                        reasonStr = reasonStr.substring(0, reasonStr.length() - m.group(1).length()).trim();
                        if (reasonStr.isEmpty()) reasonStr = "信誉积分审批";
                    } else if (approved) {
                        scoreChange = 10;
                    }
                    String approver = sender instanceof Player ? ((Player) sender).getName() : "控制台";
                    boolean ok = approveRecord(recordId, approved, reasonStr, scoreChange, approver);
                    if (ok) {
                        sender.sendMessage("§a审批完成: #" + recordId + " " + (approved ? "通过" : "驳回")
                                + (approved && scoreChange != 0 ? " §e积分变动: " + (scoreChange > 0 ? "+" : "") + formatScore(scoreChange) : ""));
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
                    double myScore = getScore(p.getName());
                    p.closeInventory();
                    p.sendMessage("§d§l[信誉积分] §f你的当前积分: §e" + formatScore(myScore) + " §7(初始: " + DEFAULT_INIT_SCORE + ")");
                    break;
                case 3: // 积分明细
                    p.closeInventory();
                    p.openInventory(createRecordsGUI(p));
                    break;
                case 4: // 我的上报
                    myReportsPage.put(p.getName(), 1);
                    p.closeInventory();
                    p.openInventory(createMyReportsGUI(p, 1));
                    break;
                case 5: // 管理员: 加分 / 非管理员: 规则说明
                    if (plugin.isAdmin(p)) {
                        startFlow(p, FlowState.ADD_SCORE_TARGET);
                        p.closeInventory();
                        p.sendMessage("§a§l[信誉积分] §f请输入要加分的玩家名");
                    } else {
                        p.closeInventory();
                        p.sendMessage("§d§l[信誉积分] §f规则说明:");
                        for (Map<String, Object> r : getEnabledRules()) {
                            p.sendMessage("§7" + r.get("rule_name") + ": " + r.get("description"));
                        }
                    }
                    break;
                case 6: // 管理员: 扣分 / 非管理员: 静默
                    if (plugin.isAdmin(p)) {
                        startFlow(p, FlowState.DEDUCT_SCORE_TARGET);
                        p.closeInventory();
                        p.sendMessage("§c§l[信誉积分] §f请输入要扣分的玩家名");
                    } else {
                        p.closeInventory(); // 静默关闭，不报错
                    }
                    break;
                case 7: // 管理员: 待审批 / 非管理员: 静默
                    if (plugin.isAdmin(p)) {
                        p.closeInventory();
                        p.openInventory(createPendingGUI(p));
                    } else {
                        p.closeInventory(); // 静默关闭，不报错
                    }
                    break;
                case 8: // 管理员: 审批记录 / 非管理员: 静默
                    if (plugin.isAdmin(p)) {
                        p.closeInventory();
                        p.openInventory(createAdminRecordsGUI(p));
                    } else {
                        p.closeInventory(); // 静默关闭，不报错
                    }
                    break;
                case 9: // 管理员: 规则配置（18格GUI才有slot9）
                    if (plugin.isAdmin(p)) {
                        rulesPage.put(p.getName(), 1);
                        p.closeInventory();
                        p.openInventory(createRulesGUI(p, 1));
                    } else {
                        // 非管理员不应进入slot9，静默关闭
                        p.closeInventory();
                    }
                    break;
                default:
                    // ★ 第60批-问题1：空槽位静默处理，不报"权限不足"
                    //   非管理员9格GUI的slot 6-8没有物品，但Paper允许点击空槽位
                    //   之前会触发权限不足提示 → 泄露管理员功能的存在
                    //   现在：静默关闭，不报错
                    p.closeInventory();
                    break;
            }
            return;
        }

        // ★ 第63批：规则配置GUI
        if (title != null && title.contains("规则配置")) {
            int currentPage = rulesPage.getOrDefault(p.getName(), 1);

            // 返回按钮
            if (slot == 21) {
                rulesPage.remove(p.getName());
                p.closeInventory();
                p.openInventory(createMainGUI(p));
                return;
            }

            // ★ 第64批：恢复出厂默认
            if (slot == 25) {
                boolean ok = resetBuiltinRulesToDefault();
                if (ok) {
                    p.sendMessage("§a§l[信誉积分] §f内置规则已恢复出厂默认");
                    p.closeInventory();
                    p.openInventory(createRulesGUI(p, currentPage));
                } else {
                    p.sendMessage("§c§l[信誉积分] §c恢复出厂失败");
                }
                return;
            }

            // 翻页
            if (slot == 22) {
                currentPage = Math.max(1, currentPage - 1);
                rulesPage.put(p.getName(), currentPage);
                p.closeInventory();
                p.openInventory(createRulesGUI(p, currentPage));
                return;
            }
            if (slot == 23) {
                currentPage++;
                rulesPage.put(p.getName(), currentPage);
                p.closeInventory();
                p.openInventory(createRulesGUI(p, currentPage));
                return;
            }

            // 点击规则槽位（0-4）：切换启用/禁用
            if (slot >= 0 && slot < RULES_PER_PAGE) {
                int offset = (currentPage - 1) * RULES_PER_PAGE;
                List<Map<String, Object>> rules = getAllRules();
                if (slot < rules.size()) {
                    // 实际上需要按offset索引
                    int idx = offset + slot;
                    if (idx < rules.size()) {
                        Map<String, Object> r = rules.get(idx);
                        int ruleId = ((Number) r.get("id")).intValue();
                        String ruleName = (String) r.get("rule_name");
                        int enabled = ((Number) r.get("enabled")).intValue();
                        boolean ok = toggleRule(ruleId);
                        if (ok) {
                            p.sendMessage("§a§l[信誉积分] §f规则 §e" + ruleName + " §f已"
                                + (enabled == 1 ? "§c禁用" : "§a启用"));
                        } else {
                            p.sendMessage("§c§l[信誉积分] §c切换规则失败");
                        }
                        // 重新打开规则GUI刷新显示
                        p.closeInventory();
                        p.openInventory(createRulesGUI(p, currentPage));
                        return;
                    }
                }
            }
            p.closeInventory();
            return;
        }

        // ★ 第59批-问题5：我的上报27格二级菜单
        if (title != null && title.contains("我的上报")) {
            int currentPage = myReportsPage.getOrDefault(p.getName(), 1);

            // 返回按钮
            if (slot == 21) {
                myReportsPage.remove(p.getName());
                p.closeInventory();
                p.openInventory(createMainGUI(p));
                return;
            }

            // 翻页
            if (slot == PAGE_PREV) {
                currentPage = Math.max(1, currentPage - 1);
                myReportsPage.put(p.getName(), currentPage);
                p.closeInventory();
                p.openInventory(createMyReportsGUI(p, currentPage));
                return;
            }
            if (slot == PAGE_NEXT) {
                currentPage++;
                myReportsPage.put(p.getName(), currentPage);
                p.closeInventory();
                p.openInventory(createMyReportsGUI(p, currentPage));
                return;
            }

            // 点击记录槽位（0-19）：打开撤回确认流程
            if (slot >= 0 && slot < REPORTS_PER_PAGE) {
                int offset = (currentPage - 1) * REPORTS_PER_PAGE;
                List<Map<String, Object>> reports = getMyReports(p.getName(), offset, REPORTS_PER_PAGE);
                if (slot < reports.size()) {
                    Map<String, Object> r = reports.get(slot);
                    String status = (String) r.get("status");
                    // 只允许撤回 pending 或 rejected
                    if (STATUS_PENDING.equals(status) || STATUS_REJECTED.equals(status)) {
                        FlowData fd = new FlowData();
                        fd.state = FlowState.REVOKE_CONFIRM;
                        fd.recordId = ((Number) r.get("id")).intValue();
                        fd.tempName = (String) r.get("target_player");
                        chatFlows.put(p.getName(), fd);
                        p.closeInventory();
                        p.sendMessage("§c§l[信誉积分] §f确认撤回上报 #" + fd.recordId + " (目标: " + fd.tempName + ")？");
                        p.sendMessage("§7输入 §e确认撤回 §7确认，输入其他内容取消");
                    } else {
                        p.sendMessage("§c§l[信誉积分] §c此上报状态为 " + status + "，无法撤回");
                    }
                }
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

    /**
     * ★ 第59批-问题5：右键撤回上报
     * 仅在"我的上报"27格菜单中生效
     */
    public void handleRightClick(Player p, int slot, String title) {
        if (title == null) return;

        // ★ 第64批：规则配置GUI右键 → 编辑参数
        if (title.contains("规则配置")) {
            if (!plugin.isAdmin(p)) {
                p.sendMessage("§c§l[信誉积分] §c权限不足");
                return;
            }
            int currentPage = rulesPage.getOrDefault(p.getName(), 1);
            if (slot < 0 || slot >= RULES_PER_PAGE) return;

            int offset = (currentPage - 1) * RULES_PER_PAGE;
            List<Map<String, Object>> rules = getAllRules();
            int idx = offset + slot;
            if (idx >= rules.size()) return;

            Map<String, Object> r = rules.get(idx);
            int ruleId = ((Number) r.get("id")).intValue();
            String ruleName = (String) r.get("rule_name");
            String restrictionType = (String) r.get("restriction_type");
            double currentMin = ((Number) r.get("min_score")).doubleValue();
            double currentMax = ((Number) r.get("max_score")).doubleValue();
            double currentLimit = ((Number) r.getOrDefault("limit_amount", 0)).doubleValue();
            double currentMultiplier = ((Number) r.getOrDefault("multiplier", 0)).doubleValue();
            double currentAmount = ((Number) r.getOrDefault("amount", 0)).doubleValue();

            p.closeInventory();
            p.sendMessage("§6§l[信誉积分] §f编辑规则: §e" + ruleName);
            p.sendMessage("§7类型: §f" + restrictionType);
            p.sendMessage("§7当前值: §emin=" + currentMin + " max=" + currentMax + " limit=" + currentLimit + " mult=" + currentMultiplier + " amount=" + currentAmount);
            p.sendMessage("");
            p.sendMessage("§7请按格式输入新参数（只需输入要修改的值，其他保持不变）：");
            p.sendMessage("§7格式: §emin:60 max:0 limit:5000 mult:0 amount:0.1");
            p.sendMessage("§7可用字段: min, max, limit, mult, amount");
            p.sendMessage("§7输入 §e取消编辑 §7取消");

            FlowData fd = new FlowData();
            fd.state = FlowState.RULE_EDIT_PARAMS;
            fd.recordId = ruleId;
            fd.tempName = ruleName + "|" + restrictionType + "|" + currentMin + "|" + currentMax + "|" + currentLimit + "|" + currentMultiplier + "|" + currentAmount;
            chatFlows.put(p.getName(), fd);
            return;
        }

        if (!title.contains("我的上报")) return;

        int currentPage = myReportsPage.getOrDefault(p.getName(), 1);

        // 只处理记录槽位（0-19）
        if (slot < 0 || slot >= REPORTS_PER_PAGE) return;

        int offset = (currentPage - 1) * REPORTS_PER_PAGE;
        List<Map<String, Object>> reports = getMyReports(p.getName(), offset, REPORTS_PER_PAGE);
        if (slot >= reports.size()) return;

        Map<String, Object> r = reports.get(slot);
        String status = (String) r.get("status");
        String targetName = (String) r.get("target_player");
        int recordId = ((Number) r.get("id")).intValue();

        // 只允许撤回 pending 或 rejected
        if (STATUS_PENDING.equals(status) || STATUS_REJECTED.equals(status)) {
            FlowData fd = new FlowData();
            fd.state = FlowState.REVOKE_CONFIRM;
            fd.recordId = recordId;
            fd.tempName = targetName;
            chatFlows.put(p.getName(), fd);
            p.closeInventory();
            p.sendMessage("§c§l[信誉积分] §f确认撤回上报 #" + recordId + " (目标: " + targetName + ")？");
            p.sendMessage("§7输入 §e确认撤回 §7确认，输入其他内容取消");
        } else {
            p.sendMessage("§c§l[信誉积分] §c此上报状态为 " + status + "，无法撤回");
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
                p.sendMessage("§7请输入加分数值(正数，支持小数如 0.01)");
                return true;

            case ADD_SCORE_AMOUNT:
                try {
                    double amount = Double.parseDouble(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage("§c数值必须是正数");
                        fd.state = FlowState.ADD_SCORE_AMOUNT;
                        return true;
                    }
                    addScore(fd.tempName, amount, "管理员手动加分");
                    chatFlows.remove(p.getName());
                    p.sendMessage("§a§l[信誉积分] §f已给 " + fd.tempName + " 加分 §e+" + formatScore(amount));
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字");
                    fd.state = FlowState.ADD_SCORE_AMOUNT;
                }
                return true;

            case DEDUCT_SCORE_TARGET:
                fd.tempName = msg.trim();
                fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                p.sendMessage("§c§l[信誉积分] §f已记录: " + fd.tempName);
                p.sendMessage("§7请输入扣分数值(正数，支持小数如 0.01)");
                return true;

            case DEDUCT_SCORE_AMOUNT:
                try {
                    double amount = Double.parseDouble(msg.trim());
                    if (amount <= 0) {
                        p.sendMessage("§c数值必须是正数");
                        fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                        return true;
                    }
                    addScore(fd.tempName, -amount, "管理员手动扣分");
                    chatFlows.remove(p.getName());
                    p.sendMessage("§c§l[信誉积分] §f已给 " + fd.tempName + " 扣分 §e-" + formatScore(amount));
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字");
                    fd.state = FlowState.DEDUCT_SCORE_AMOUNT;
                }
                return true;

            case APPROVE_SCORE_CHANGE:
                try {
                    String input = msg.trim();
                    // 支持 +10 / -5 / 10 / -5 / 0.01 格式
                    double amount = Double.parseDouble(input.replaceFirst("^[+]", ""));
                    int rid = fd.recordId;
                    String targetName = fd.tempName;
                    // 通过审批并加分/扣分
                    approveRecord(rid, true, "管理员GUI审批通过", amount, p.getName());
                    chatFlows.remove(p.getName());
                    p.sendMessage("§a§l[信誉积分] §f已通过 #" + rid + " " + targetName + " §e"
                            + (amount > 0 ? "+" : "") + formatScore(amount));
                } catch (NumberFormatException e) {
                    p.sendMessage("§c请输入数字(如 +10 或 -5 或 0.01)");
                }
                return true;

            // ★ 第59批-问题5：撤回上报确认
            case REVOKE_CONFIRM:
                if ("确认撤回".equals(msg.trim())) {
                    int rid = fd.recordId;
                    boolean ok = revokeReport(rid, p.getName());
                    chatFlows.remove(p.getName());
                    if (ok) {
                        p.sendMessage("§a§l[信誉积分] §f已撤回上报 #" + rid);
                    } else {
                        p.sendMessage("§c§l[信誉积分] §c撤回失败，记录不存在或状态不允许");
                    }
                } else {
                    // ★ 第62批-问题3：取消撤回后必须清除 chatFlows，否则玩家永远困在 REVOKE_CONFIRM 状态
                    chatFlows.remove(p.getName());
                    p.sendMessage("§7已取消撤回");
                }
                return true;

            // ★ 第64批：编辑规则参数
            case RULE_EDIT_PARAMS:
                String input = msg.trim();
                if (input.equals("取消编辑")) {
                    chatFlows.remove(p.getName());
                    p.sendMessage("§7已取消编辑");
                    return true;
                }
                // 解析格式: min:60 max:0 limit:5000 mult:0 amount:0.1
                int ruleId = fd.recordId;
                String[] parts = fd.tempName.split("\\|");
                String ruleName = parts[0];
                double curMin = Double.parseDouble(parts[2]);
                double curMax = Double.parseDouble(parts[3]);
                double curLimit = Double.parseDouble(parts[4]);
                double curMult = Double.parseDouble(parts[5]);
                double curAmount = Double.parseDouble(parts[6]);

                // 解析新值
                double newMin = curMin, newMax = curMax, newLimit = curLimit, newMult = curMult, newAmount = curAmount;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("min:(-?\\d+(?:\\.\\d+)?)").matcher(input);
                if (m.find()) newMin = Double.parseDouble(m.group(1));
                m = java.util.regex.Pattern.compile("max:(-?\\d+(?:\\.\\d+)?)").matcher(input);
                if (m.find()) newMax = Double.parseDouble(m.group(1));
                m = java.util.regex.Pattern.compile("limit:(-?\\d+(?:\\.\\d+)?)").matcher(input);
                if (m.find()) newLimit = Double.parseDouble(m.group(1));
                m = java.util.regex.Pattern.compile("mult:(-?\\d+(?:\\.\\d+)?)").matcher(input);
                if (m.find()) newMult = Double.parseDouble(m.group(1));
                m = java.util.regex.Pattern.compile("amount:(-?\\d+(?:\\.\\d+)?)").matcher(input);
                if (m.find()) newAmount = Double.parseDouble(m.group(1));

                boolean ok = updateRuleParams(ruleId,
                        (int) newMin, (int) newMax, newLimit, newMult, newAmount);
                chatFlows.remove(p.getName());
                if (ok) {
                    p.sendMessage("§a§l[信誉积分] §f规则 §e" + ruleName + " §f参数已更新:");
                    p.sendMessage("§7  min=" + newMin + " max=" + newMax + " limit=" + newLimit + " mult=" + newMult + " amount=" + newAmount);
                } else {
                    p.sendMessage("§c§l[信誉积分] §c更新失败，参数格式有误");
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

    // ==================== ★ 第64批：PHP规则同步 ====================

    /**
     * ★ 第64批修复（#332）：从 PHP 端同步 credit_rules 到 Java
     *   PHP 管理后台修改规则参数后，Java 端需要拉取并应用
     *   调用时机：WebManager.pullCreditFromPhp() 处理完 records 后
     *
     *   ★ 修复要点：PHP 和 Java 的 credit_rules id 完全不同（PHP 1-9，Java 73-78/85-86），
     *     旧逻辑按 id UPDATE 永远 0 行，但 synced++ 无条件执行 → 日志假报"同步了N条"。
     *     修复：按 rule_name 匹配（UNIQUE 列），UPDATE 影响行数=0 时 INSERT OR IGNORE，
     *     只统计真正写入的行数。
     *
     * @param rulesJson Gson JsonArray，每条规则含 id, rule_name, min_score, max_score,
     *                   restriction_type, enabled, limit_amount, multiplier, amount
     * @return 真正写入的规则数量
     */
    /**
     * ★ 第65批修复（#344）：PHP→Java 规则同步 — 谁最后改谁说了算
     *
     * 核心原则：
     *   1. PHP 传了哪个字段，就更新哪个字段
     *   2. PHP 没传的字段，保持 Java 本地原值不变
     *   3. 这样 Java 拉了 PHP 的修改后，不会把没传的参数覆盖成0
     *      → push 回 PHP 时不会把 PHP 刚改的值拒掉
     *
     * 之前 bug：PHP 没传的字段被当成0/空串，UPDATE 把所有字段覆盖，
     *          Java 本地的参数被清零，push 回去把 PHP 的修改拒掉。
     *
     * @return 实际同步的规则数（新增+更新）
     */
    public int syncCreditRulesFromPhp(com.google.gson.JsonArray rulesJson) {
        if (rulesJson == null || rulesJson.isEmpty()) return 0;
        int synced = 0, inserted = 0, failed = 0;
        try {
            for (com.google.gson.JsonElement el : rulesJson) {
                com.google.gson.JsonObject rule = el.getAsJsonObject();
                String ruleName = rule.has("rule_name") ? rule.get("rule_name").getAsString() : "";
                if (ruleName.isEmpty()) continue;

                // ★ 只记录 PHP 实际传了的字段，没传的保持 Java 原值
                boolean hasMinScore = rule.has("min_score");
                boolean hasMaxScore = rule.has("max_score");
                boolean hasEnabled = rule.has("enabled");
                boolean hasRestrictionType = rule.has("restriction_type");
                boolean hasRestrictionDuration = rule.has("restriction_duration");
                boolean hasDescription = rule.has("description");
                boolean hasLimitAmount = rule.has("limit_amount");
                boolean hasMultiplier = rule.has("multiplier");
                boolean hasAmount = rule.has("amount");

                // PHP 传的值（没传的用占位值，UPDATE 时不会用到）
                int minScore = hasMinScore ? rule.get("min_score").getAsInt() : 0;
                int maxScore = hasMaxScore ? rule.get("max_score").getAsInt() : 0;
                int enabled = hasEnabled ? rule.get("enabled").getAsInt() : 0;
                int restrictionDuration = hasRestrictionDuration ? rule.get("restriction_duration").getAsInt() : 0;
                String restrictionType = hasRestrictionType ? rule.get("restriction_type").getAsString() : "";
                String description = hasDescription ? rule.get("description").getAsString() : "";
                double limitAmount = hasLimitAmount ? rule.get("limit_amount").getAsDouble() : 0;
                double multiplier = hasMultiplier ? rule.get("multiplier").getAsDouble() : 0;
                double amount = hasAmount ? rule.get("amount").getAsDouble() : 0;

                // 读取 Java 本地当前全字段
                boolean exists = false;
                int curMin = 0, curMax = 0, curEnabled = 0, curDuration = 0;
                String curRestriction = "", curDesc = "";
                double curLimit = 0, curMult = 0, curAmount = 0;
                try {
                    PreparedStatement checkPs = db.getConnection().prepareStatement(
                        "SELECT min_score, max_score, enabled, restriction_type, restriction_duration, description, limit_amount, multiplier, amount FROM credit_rules WHERE rule_name = ?");
                    checkPs.setString(1, ruleName);
                    ResultSet checkRs = checkPs.executeQuery();
                    if (checkRs.next()) {
                        exists = true;
                        curMin = checkRs.getInt("min_score");
                        curMax = checkRs.getInt("max_score");
                        curEnabled = checkRs.getInt("enabled");
                        curRestriction = checkRs.getString("restriction_type");
                        curDuration = checkRs.getInt("restriction_duration");
                        curDesc = checkRs.getString("description");
                        curLimit = checkRs.getDouble("limit_amount");
                        curMult = checkRs.getDouble("multiplier");
                        curAmount = checkRs.getDouble("amount");
                    }
                    checkRs.close();
                    checkPs.close();
                } catch (Exception ignored) {}

                if (!exists) {
                    // PHP 有的规则 Java 本地没有 → INSERT OR IGNORE（rule_name UNIQUE）
                    // 没传的字段用 Java 默认值，不强行设0
                    PreparedStatement ps2 = db.getConnection().prepareStatement(
                            "INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, "
                            + "restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    ps2.setString(1, ruleName);
                    ps2.setInt(2, minScore);
                    ps2.setInt(3, maxScore);
                    ps2.setString(4, restrictionType);
                    ps2.setInt(5, restrictionDuration);
                    ps2.setInt(6, enabled);
                    ps2.setString(7, description);
                    ps2.setDouble(8, limitAmount);
                    ps2.setDouble(9, multiplier);
                    ps2.setDouble(10, amount);
                    int r2 = ps2.executeUpdate();
                    ps2.close();
                    if (r2 > 0) {
                        inserted++;
                        synced++;
                        plugin.getLogger().info("[信誉同步] 规则新增: " + ruleName + " enabled=" + enabled);
                    } else {
                        failed++;
                    }
                    continue;
                }

                // ★ 谁最后改谁说了算：只更新 PHP 传了的字段
                //   PHP 没传的字段保持 Java 本地原值，不覆盖
                //   用动态 SET 构建，只写 PHP 实际传了的字段
                StringBuilder setClause = new StringBuilder();
                List<Object> setParams = new ArrayList<>();
                boolean anyChanged = false;

                // 逐字段对比：PHP 传了的字段才参与对比和 UPDATE
                if (hasMinScore && curMin != minScore) {
                    setClause.append("min_score = ?, "); setParams.add(minScore);
                    anyChanged = true;
                }
                if (hasMaxScore && curMax != maxScore) {
                    setClause.append("max_score = ?, "); setParams.add(maxScore);
                    anyChanged = true;
                }
                if (hasEnabled && curEnabled != enabled) {
                    setClause.append("enabled = ?, "); setParams.add(enabled);
                    anyChanged = true;
                }
                if (hasRestrictionDuration && curDuration != restrictionDuration) {
                    setClause.append("restriction_duration = ?, "); setParams.add(restrictionDuration);
                    anyChanged = true;
                }
                if (hasRestrictionType && !curRestriction.equals(restrictionType)) {
                    setClause.append("restriction_type = ?, "); setParams.add(restrictionType);
                    anyChanged = true;
                }
                if (hasDescription && !curDesc.equals(description)) {
                    setClause.append("description = ?, "); setParams.add(description);
                    anyChanged = true;
                }
                if (hasLimitAmount && curLimit != limitAmount) {
                    setClause.append("limit_amount = ?, "); setParams.add(limitAmount);
                    anyChanged = true;
                }
                if (hasMultiplier && curMult != multiplier) {
                    setClause.append("multiplier = ?, "); setParams.add(multiplier);
                    anyChanged = true;
                }
                if (hasAmount && curAmount != amount) {
                    setClause.append("amount = ?, "); setParams.add(amount);
                    anyChanged = true;
                }

                if (!anyChanged) continue;

                setClause.setLength(setClause.length() - 2); // 去掉末尾 ", "

                PreparedStatement ps = db.getConnection().prepareStatement(
                        "UPDATE credit_rules SET " + setClause + " WHERE rule_name = ?");
                for (int i = 0; i < setParams.size(); i++) {
                    ps.setObject(i + 1, setParams.get(i));
                }
                ps.setString(setParams.size() + 1, ruleName);
                ps.executeUpdate();
                ps.close();

                synced++;
                // ★ 张嘴说变更详情：哪些字段变了
                StringBuilder changes = new StringBuilder();
                if (hasEnabled && curEnabled != enabled) {
                    changes.append("enabled ").append(curEnabled).append("→").append(enabled);
                }
                if (hasMinScore && curMin != minScore) {
                    changes.append(" ").append(changes.length() > 0 ? "," : "").append("min ").append(curMin).append("→").append(minScore);
                }
                if (hasMaxScore && curMax != maxScore) {
                    changes.append(" ").append(changes.length() > 0 ? "," : "").append("max ").append(curMax).append("→").append(maxScore);
                }
                if (hasAmount && curAmount != amount) {
                    changes.append(" ").append(changes.length() > 0 ? "," : "").append("amount ").append(curAmount).append("→").append(amount);
                }
                if (hasLimitAmount && curLimit != limitAmount) {
                    changes.append(" ").append(changes.length() > 0 ? "," : "").append("limit ").append(curLimit).append("→").append(limitAmount);
                }
                if (hasMultiplier && curMult != multiplier) {
                    changes.append(" ").append(changes.length() > 0 ? "," : "").append("mult ").append(curMult).append("→").append(multiplier);
                }
                plugin.getLogger().info("[信誉同步] 规则变更: " + ruleName + " " + changes);
            }
            if (synced > 0) {
                plugin.getLogger().info("[信誉同步] 从PHP同步了 " + synced + " 条规则 (新增" + inserted + " 更新" + (synced - inserted) + ")");
                // ★ 规则同步后对在线玩家重新执行限制检查
                for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
                    double score = getScore(p.getName());
                    checkRestrictions(p.getName(), score);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[信誉同步] 同步规则失败: " + e.getMessage());
        }
        return synced;
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
