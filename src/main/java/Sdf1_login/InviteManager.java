package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

public class InviteManager {

    private final Main plugin;

    public InviteManager(Main plugin) {
        this.plugin = plugin;
    }

    public String generateCode(Player p) {
        String code = PasswordUtils.generateInviteCode();
        plugin.getDb().setField(
                p.getName(), "invite_code", code);
        return code;
    }

    /**
     * ★ 第九批（2026-10-12）：绑定邀请码成功时立即计入邀请人
     * 「本月邀请」计数（带月度重置）。
     *
     * <p>旧逻辑把计数放在 onInviteeCheckIn（被邀请人签到）里，
     * 导致「绑定成功但未签到」时 GUI 恒显示「已邀请 0 人」（用户反馈
     * 的计数 bug 根因）。现改为绑定即计数——绑定才是「邀请成功」
     * 的真正时点，与债券发放同一时机，语义一致。</p>
     *
     * @param inviter  邀请人（被计数方）
     * @param invitee  被邀请人（绑定者）
     */
    public void onInviteeBind(String inviter, String invitee) {
        if (inviter == null || inviter.isEmpty()) return;

        String month = new SimpleDateFormat("yyyy-MM")
                .format(new Date());
        String lastMonth = (String) plugin.getDb()
                .getField(inviter, "invite_month");

        if (!month.equals(lastMonth)) {
            plugin.getDb().setField(
                    inviter, "invite_month", month);
            plugin.getDb().setField(
                    inviter, "monthly_invite_count", 0);
        }

        int count = ((Number) plugin.getDb()
                .getField(inviter,
                        "monthly_invite_count"))
                .intValue();
        plugin.getDb().setField(
                inviter, "monthly_invite_count",
                count + 1);
    }

    /**
     * ★ 第九批：签到链路不再重复计数——「本月邀请」计数已在
     * 绑定邀请码时由 onInviteeBind 完成（见上）。若此处再 +1，
     * 同一被邀请人每签到一次都会重复累加，造成计数虚高。
     * 方法保留以兼容既有三处签到调用点（Main.dailySign 等），
     * 但已清空计数逻辑。
     */
    public void onInviteeCheckIn(String inviteeName) {
        // 计数已迁移至 onInviteeBind（绑定时），签到不再计数
    }

    public List<Map<String, Object>> getAllInviteData() {
        List<Map<String, Object>> result =
                new ArrayList<>();
        for (Map<String, Object> user
                : plugin.getDb().getAllUsers()) {
            String code = (String) user.getOrDefault(
                    "invite_code", "");
            if (!code.isEmpty()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("player",
                        user.get("player_name"));
                row.put("code", code);
                row.put("monthly",
                        user.getOrDefault(
                                "monthly_invite_count", 0));
                result.add(row);
            }
        }
        return result;
    }

    // ==================== 第十批（2026-10-12）存量自愈 + 派生口径 ====================

    /** 本月 1 号 00:00（本地时区）毫秒时间戳 */
    private long monthStartTime() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long lng(Object o) {
        return (o instanceof Number)
                ? ((Number) o).longValue() : 0L;
    }

    /**
     * ★ 第十批（2026-10-12）：存量邀请数据自愈，
     * onEnable 启动时调用一次（幂等）。
     *
     * <p>① 回填 invited_at=0 的存量绑定时间——绑定功能自项目
     * 初始化（2026-05）就存在但没记时间，用户 GUI 看到
     * 「绑定时间: 未知」。回填优先级：</p>
     * <ol>
     *   <li>bond_transaction type='invitee_reward'
     *       （第九批起被邀请人收款，time=绑定时刻，最精确）</li>
     *   <li>bond_transaction type='invite_reward' 且
     *       target_player=被邀请人（第八批起邀请人收款）</li>
     *   <li>注册时间（早期绑定大多发生在注册前后，最佳代理）</li>
     *   <li>最后登录时间 → 当前时间</li>
     * </ol>
     *
     * <p>② 按回填后的口径重算 monthly_invite_count / invite_month
     * ——修复存量绑定不计入导致的「已邀请 0 人」。</p>
     */
    public void reconcileInviteData() {
        try {
            List<Map<String, Object>> users =
                    plugin.getDb().getAllUsers();
            long now = System.currentTimeMillis();
            int fixedAt = 0;
            // ---- ① 回填存量绑定时间 ----
            for (Map<String, Object> u : users) {
                String name = str(u.get("player_name"));
                if (name.isEmpty()) continue;
                if (str(u.get("invited_by")).isEmpty()) continue;
                if (lng(u.get("invited_at")) > 0) continue;
                long ts = lookupBondTx(
                        "player_name", name,
                        "invitee_reward");
                if (ts <= 0) ts = lookupBondTx(
                        "target_player", name,
                        "invite_reward");
                if (ts <= 0) ts = lng(u.get("register_time"));
                if (ts <= 0) ts = lng(u.get("last_login_time"));
                if (ts <= 0) ts = now;
                plugin.getDb().setField(
                        name, "invited_at", ts);
                u.put("invited_at", ts);
                fixedAt++;
            }
            // ---- ② 重算月度计数（仅维护邀请人，与 GUI 派生口径同源） ----
            long monthStart = monthStartTime();
            Map<String, Integer> counter = new HashMap<>();
            for (Map<String, Object> u : users) {
                String inviter = str(u.get("invited_by"));
                if (inviter.isEmpty()) continue;
                if (lng(u.get("invited_at")) >= monthStart) {
                    counter.merge(inviter, 1, Integer::sum);
                }
            }
            String curMonth = new SimpleDateFormat("yyyy-MM")
                    .format(new Date());
            int fixedCnt = 0;
            for (Map<String, Object> u : users) {
                String name = str(u.get("player_name"));
                if (name.isEmpty()) continue;
                // 只维护邀请人（有邀请码的玩家）
                if (str(u.get("invite_code")).isEmpty()) {
                    continue;
                }
                int want = counter.getOrDefault(name, 0);
                if (lng(u.get("monthly_invite_count")) != want) {
                    plugin.getDb().setField(
                            name, "monthly_invite_count",
                            want);
                    fixedCnt++;
                }
                if (!curMonth.equals(
                        str(u.get("invite_month")))) {
                    plugin.getDb().setField(
                            name, "invite_month", curMonth);
                }
            }
            if (fixedAt > 0 || fixedCnt > 0) {
                plugin.getLogger().info(
                        "[Invite] 存量自愈: 回填绑定时间 "
                                + fixedAt + " 条, 重算月度计数 "
                                + fixedCnt + " 人");
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Invite] 存量邀请数据自愈失败: "
                            + e.getMessage());
        }
    }

    /**
     * 在 bond.db 的 bond_transaction 里查该玩家某类流水的最早时间
     * （跨库：必须走 BondManager.getConnection()，
     *  bond_transaction 不在 login.db）
     */
    private long lookupBondTx(String col, String player,
                              String type) {
        try {
            java.sql.PreparedStatement ps = plugin.getBonds()
                    .getConnection().prepareStatement(
                            "SELECT MIN(time) "
                                    + "FROM bond_transaction "
                                    + "WHERE type=? AND "
                                    + col + "=?");
            ps.setString(1, type);
            ps.setString(2, player);
            java.sql.ResultSet rs = ps.executeQuery();
            long t = rs.next() ? rs.getLong(1) : 0L;
            rs.close();
            ps.close();
            // 兼容以秒为单位的历史行
            if (t > 0 && t < 1000000000000L) t *= 1000L;
            return t;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * ★ 第十批：派生口径的「本月邀请人数」——
     * 按 invited_at >= 本月 1 号实时统计（GUI 显示用，
     * 跨月自愈，不依赖增量列 monthly_invite_count）。
     */
    public int getMonthlyInviteCount(String inviter) {
        if (inviter == null || inviter.isEmpty()) return 0;
        long monthStart = monthStartTime();
        int n = 0;
        for (Map<String, Object> u
                : plugin.getDb().getAllUsers()) {
            if (!inviter.equals(
                    str(u.get("invited_by")))) continue;
            if (lng(u.get("invited_at")) >= monthStart) n++;
        }
        return n;
    }

    /**
     * ★ 第十批：本月绑定该玩家邀请码的玩家列表（头颅明细 GUI）。
     *
     * @return 每项含 name（被邀请人）与 at（绑定时间戳），
     *         按绑定时间倒序
     */
    public List<Map<String, Object>> getMonthlyInvitedPlayers(
            String inviter) {
        List<Map<String, Object>> result =
                new ArrayList<>();
        if (inviter == null || inviter.isEmpty()) return result;
        long monthStart = monthStartTime();
        for (Map<String, Object> u
                : plugin.getDb().getAllUsers()) {
            if (!inviter.equals(
                    str(u.get("invited_by")))) continue;
            long ts = lng(u.get("invited_at"));
            if (ts < monthStart) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", str(u.get("player_name")));
            row.put("at", ts);
            result.add(row);
        }
        result.sort((a, b) -> Long.compare(
                lng(b.get("at")), lng(a.get("at"))));
        return result;
    }
}
