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
}
