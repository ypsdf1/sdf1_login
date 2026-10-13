package Sdf1_login;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邀请好友赢债券奖励（配置：邀请好友赢债券/规则.md）。
 *
 * <p>★ 2026-10-12 第八批（用户决策）：旧「提成规则.txt」体系整体废弃，
 * 奖励改由新 md 规则控制，绑定邀请码成功时立即结算债券。</p>
 *
 * <p>★ 2026-10-12 第九批（用户需求）三点改造：
 *   ① 解析键改用新 md 4 键（旧「单次邀请奖励上限」→「邀请人单次奖励」），
 *      新增「被邀请人单次奖励」「单日邀请翻倍额度」；
 *   ② 奖励单向改<b>双向</b>——payInviteBond 给邀请人、payInviteeBond 给被邀请人；
 *      翻倍额度作用于邀请人（每邀请 X 人，奖金翻 Y 倍）；
 *   ③ 支持<b>懒更新</b>——玩家绑定邀请码时调用 loadRules() 实时重载规则，
 *      不再依赖启动时一次性加载或定时热重载。</p>
 */
public class CommissionManager {

    private final Main main;
    private final Logger logger;

    /** 邀请人单次奖励（债券数，绑定成功立即结算） */
    private volatile int perInviteReward = 10;
    /** 被邀请人单次奖励（债券数，绑定成功立即结算） */
    private volatile int perInviteeReward = 5;
    /** 单日邀请奖励上限（债券数，自然日重置，仅针对邀请人） */
    private volatile int dailyInviteCap = 100;
    /** 单日邀请翻倍额度：每邀请 X 人（>0 生效，0=关闭翻倍） */
    private volatile int doubleEveryX = 0;
    /** 单日邀请翻倍额度：奖金翻 Y 倍（翻 Y 倍 = 每满 X 人基数加 Y 倍） */
    private volatile int doubleByY = 0;

    public CommissionManager(Main main) {
        this.main = main;
        this.logger = main.getLogger();
        loadRules();
    }

    /**
     * 重载规则（★ 第九批：绑定邀请码时懒调用，实时刷新奖励信息）。
     */
    public void loadRules() {
        // ★ 代码层面删除旧提成规则.txt（手删会被再次释放，故写文件
        //   逻辑已移除，这里负责清理存量文件）
        File legacy = new File(main.getDataFolder(),
                "提成规则.txt");
        if (legacy.exists() && legacy.delete()) {
            logger.info("[Commission] 已删除旧版 提成规则.txt"
                    + "（改由 邀请好友赢债券/规则.md 控制）");
        }

        File f = new File(main.getDataFolder(),
                "邀请好友赢债券/规则.md");
        if (!f.exists()) {
            try {
                main.saveResource(
                        "邀请好友赢债券/规则.md", false);
            } catch (Exception e) {
                logger.warning("[Commission] 规则文件释放失败: "
                        + e.getMessage());
            }
        }
        if (!f.exists()) {
            logger.warning("[Commission] 规则文件缺失，使用默认值"
                    + " 邀请人=" + perInviteReward
                    + " 被邀请人=" + perInviteeReward
                    + " 单日=" + dailyInviteCap);
            return;
        }
        // ★ 安全交换（2026-10-13）：jar 模板多出的配置行补进原文件，
        //   说明文本与用户改过的值原样保留（旧菜新菜一起端上桌）
        java.util.List<String> inviteTpl =
                ConfigManager.readJarResourceLines(
                        main.getResource("邀请好友赢债券/规则.md"));
        if (inviteTpl != null) {
            main.getConfig2().safeUpgradeDoc(f, inviteTpl, "邀请债券规则");
        }
        boolean inCfg = false;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(f),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("##") && t.contains("配置区")
                        && t.contains("开始")) {
                    inCfg = true;
                    continue;
                }
                if (t.startsWith("##") && t.contains("配置区")
                        && t.contains("结束")) {
                    break;
                }
                if (!inCfg || !t.startsWith("|")) continue;
                String[] cols = t.split("\\|", -1);
                if (cols.length < 4) continue; // | 键 | 解释 | 值 |
                String key = cols[1].trim();
                String val = cols[cols.length - 2].trim()
                        .replace("`", "");
                // ★ 第九批：适配新 md 4 键
                if ("邀请人单次奖励".equals(key)) {
                    perInviteReward = parseIntSafe(val,
                            perInviteReward);
                } else if ("被邀请人单次奖励".equals(key)) {
                    perInviteeReward = parseIntSafe(val,
                            perInviteeReward);
                } else if ("单日邀请奖励上限".equals(key)) {
                    dailyInviteCap = parseIntSafe(val,
                            dailyInviteCap);
                } else if ("单日邀请翻倍额度".equals(key)) {
                    parseDoubleRule(val);
                }
            }
            logger.info("[Commission] 邀请债券规则: 邀请人="
                    + perInviteReward
                    + " 被邀请人=" + perInviteeReward
                    + " 单日=" + dailyInviteCap
                    + " 翻倍=" + (doubleEveryX > 0
                            ? ("每" + doubleEveryX + "人翻"
                                    + doubleByY + "倍")
                            : "关闭"));
        } catch (Exception e) {
            logger.warning("[Commission] 规则解析失败: "
                    + e.getMessage());
        }
    }

    /** 解析「每邀请X人，奖金翻Y倍」格式 */
    private void parseDoubleRule(String v) {
        // 默认关闭
        doubleEveryX = 0;
        doubleByY = 0;
        if (v == null) return;
        Matcher m = Pattern.compile(
                "每邀请\\s*(\\d+)\\s*人\\s*[，,]\\s*奖金翻\\s*(\\d+)\\s*倍")
                .matcher(v);
        if (m.find()) {
            doubleEveryX = parseIntSafe(m.group(1), 0);
            doubleByY = parseIntSafe(m.group(2), 0);
        } else {
            logger.warning("[Commission] 翻倍额度格式无法解析: "
                    + v + "（示例：每邀请2人，奖金翻1倍）");
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 邀请人单次奖励（债券） */
    public int getPerInviteReward() {
        return perInviteReward;
    }

    /** 被邀请人单次奖励（债券） */
    public int getPerInviteeReward() {
        return perInviteeReward;
    }

    /** 单日邀请奖励上限（债券） */
    public int getDailyInviteCap() {
        return dailyInviteCap;
    }

    /**
     * 邀请绑定成功 → 给<b>邀请人</b>发债券（★ 第九批：含翻倍额度）。
     * 规则见 邀请好友赢债券/规则.md：每次按单次上限结算（每满 X 人翻 Y 倍），
     * 当日累计不超单日上限。
     *
     * @param inviter 邀请人（收款方）
     * @param invitee 被邀请人（绑定者）
     * @return &gt;0 实发债券数；0 = 今日已达上限或规则为0；
     *         -1 = 异常/冻结未发
     */
    public int payInviteBond(String inviter, String invitee) {
        if (inviter == null || inviter.isEmpty()) return -1;
        if (perInviteReward <= 0) return 0;
        int used = inviteBondToday(inviter);
        int room = dailyInviteCap - used;
        if (room <= 0) {
            logger.info("[Commission] " + inviter
                    + " 今日邀请债券已达上限(" + dailyInviteCap + ")");
            return 0;
        }
        // ★ 第九批：单日邀请翻倍额度——每邀请 X 人，奖金翻 Y 倍
        //   nToday = 今日已发放的邀请奖励笔数（≈已成功邀请人数）
        //   mult = 1 + (nToday / X) * Y（整数除法，每满 X 人加 Y 倍）
        int nToday = inviteBondCountToday(inviter);
        int mult = 1;
        if (doubleEveryX > 0) {
            mult = 1 + (nToday / doubleEveryX) * doubleByY;
            if (mult < 1) mult = 1;
        }
        int base = perInviteReward * mult;
        int give = Math.min(base, room);
        int after = main.getBonds().addBonds(inviter, give,
                "invite_reward", invitee, "system",
                "邀请好友绑定奖励(" + invitee + ")");
        if (after < 0) return -1; // 冻结期未入账
        logger.info("[Commission] 邀请债券: " + inviter + " ← "
                + invitee + " | +" + give + "（基数"
                + perInviteReward + "×" + mult + "，今日累计 "
                + (used + give) + "/" + dailyInviteCap + "）");
        return give;
    }

    /**
     * 邀请绑定成功 → 给<b>被邀请人</b>发债券（★ 第九批：双向奖励）。
     * 被邀请人奖励为固定单次数额，不受单日上限约束（上限仅针对邀请人）。
     *
     * @param invitee 被邀请人（绑定者，收款方）
     * @param inviter 邀请人（被邀请人的邀请来源）
     * @return &gt;0 实发债券数；0 = 规则为0；-1 = 异常/冻结未发
     */
    public int payInviteeBond(String invitee, String inviter) {
        if (invitee == null || invitee.isEmpty()) return -1;
        if (perInviteeReward <= 0) return 0;
        // 用独立 type='invitee_reward'，避免污染邀请人日上限统计
        int after = main.getBonds().addBonds(invitee,
                perInviteeReward, "invitee_reward", inviter,
                "system",
                "被邀请人绑定奖励(邀请人:" + inviter + ")");
        if (after < 0) return -1; // 冻结期未入账
        logger.info("[Commission] 被邀请人债券: " + invitee
                + " ← " + inviter + " | +" + perInviteeReward);
        return perInviteeReward;
    }

    /** 今日 0 点起该玩家已发放的邀请债券总数（金额） */
    private int inviteBondToday(String inviter) {
        long dayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        try {
            PreparedStatement ps = main.getBonds()
                    .getConnection().prepareStatement(
                            "SELECT COALESCE(SUM(amount),0) "
                                    + "FROM bond_transaction "
                                    + "WHERE player_name=? "
                                    + "AND type='invite_reward' "
                                    + "AND time>=?");
            ps.setString(1, inviter);
            ps.setLong(2, dayStart);
            ResultSet rs = ps.executeQuery();
            int v = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            ps.close();
            return v;
        } catch (Exception e) {
            logger.warning("[Commission] 查询当日邀请债券失败: "
                    + e.getMessage());
            return 0;
        }
    }

    /** 今日 0 点起该玩家已成功邀请人数（发奖笔数，用于翻倍档位） */
    private int inviteBondCountToday(String inviter) {
        long dayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        try {
            PreparedStatement ps = main.getBonds()
                    .getConnection().prepareStatement(
                            "SELECT COUNT(*) "
                                    + "FROM bond_transaction "
                                    + "WHERE player_name=? "
                                    + "AND type='invite_reward' "
                                    + "AND time>=?");
            ps.setString(1, inviter);
            ps.setLong(2, dayStart);
            ResultSet rs = ps.executeQuery();
            int v = rs.next() ? rs.getInt(1) : 0;
            rs.close();
            ps.close();
            return v;
        } catch (Exception e) {
            logger.warning("[Commission] 查询当日邀请人数失败: "
                    + e.getMessage());
            return 0;
        }
    }

    /**
     * 判断金额是否包含经济内容
     */
    public boolean hasEconomyContent(String text) {
        String t = text.toLowerCase();
        return t.contains("游戏币")
                || t.contains("金币")
                || t.contains("积分")
                || t.contains("块钱")
                || t.contains("元")
                || t.contains("$")
                || t.contains("经济")
                || t.contains("余额");
    }
}
