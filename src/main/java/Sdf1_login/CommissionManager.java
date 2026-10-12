package Sdf1_login;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * 邀请好友赢债券奖励（配置：邀请好友赢债券/规则.md）。
 *
 * <p>★ 2026-10-12 第八批（用户决策）：旧「提成规则.txt」（邀请人数:提成百分比）
 * 体系整体废弃——其 payCommission 因 Economy 移除早已是无调用死代码，
 * 签到链路奖励计算 (int)(1*rate) 恒为 0 从未生效（「邀请好友无效」根因）。
 * 代码层面删除旧 txt（启动时清理而非 SFTP 手删——否则 writeDefault
 * 会再次释放，写文件逻辑已一并移除），奖励改由新 md 规则控制：
 * 绑定邀请码成功时立即结算债券，受「单次邀请奖励上限」「单日邀请奖励上限」
 * 双重限制（当日累计按 bond_transaction type='invite_reward' 汇总，自然日重置）。</p>
 */
public class CommissionManager {

    private final Main main;
    private final Logger logger;

    /** 单次邀请奖励（债券数，绑定成功立即结算） */
    private volatile int perInviteReward = 10;
    /** 单日邀请奖励上限（债券数，自然日重置） */
    private volatile int dailyInviteCap = 100;

    public CommissionManager(Main main) {
        this.main = main;
        this.logger = main.getLogger();
        loadRules();
    }

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
                    + " 单次=" + perInviteReward
                    + " 单日=" + dailyInviteCap);
            return;
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
                if ("单次邀请奖励上限".equals(key)) {
                    perInviteReward = parseIntSafe(val,
                            perInviteReward);
                } else if ("单日邀请奖励上限".equals(key)) {
                    dailyInviteCap = parseIntSafe(val,
                            dailyInviteCap);
                }
            }
            logger.info("[Commission] 邀请债券规则: 单次="
                    + perInviteReward + " 单日=" + dailyInviteCap);
        } catch (Exception e) {
            logger.warning("[Commission] 规则解析失败: "
                    + e.getMessage());
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 单次邀请奖励（债券） */
    public int getPerInviteReward() {
        return perInviteReward;
    }

    /** 单日邀请奖励上限（债券） */
    public int getDailyInviteCap() {
        return dailyInviteCap;
    }

    /**
     * 邀请绑定成功 → 给邀请人发债券。
     * 规则见 邀请好友赢债券/规则.md：每次按单次上限结算，
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
        int give = Math.min(perInviteReward, room);
        int after = main.getBonds().addBonds(inviter, give,
                "invite_reward", invitee, "system",
                "邀请好友绑定奖励(" + invitee + ")");
        if (after < 0) return -1; // 冻结期未入账
        logger.info("[Commission] 邀请债券: " + inviter + " ← "
                + invitee + " | +" + give + "（今日累计 "
                + (used + give) + "/" + dailyInviteCap + "）");
        return give;
    }

    /** 今日 0 点起该玩家已发放的邀请债券总数 */
    private int inviteBondToday(String inviter) {
        long dayStart = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        try {
            PreparedStatement ps = main.getDb()
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
