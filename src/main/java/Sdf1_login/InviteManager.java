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

    public void onInviteeCheckIn(String inviteeName) {
        String invitedBy = (String) plugin.getDb()
                .getField(inviteeName, "invited_by");
        if (invitedBy == null || invitedBy.isEmpty())
            return;

        String month = new SimpleDateFormat("yyyy-MM")
                .format(new Date());
        String lastMonth = (String) plugin.getDb()
                .getField(invitedBy, "invite_month");

        if (!month.equals(lastMonth)) {
            plugin.getDb().setField(
                    invitedBy, "invite_month", month);
            plugin.getDb().setField(
                    invitedBy, "monthly_invite_count", 0);
        }

        int count = ((Number) plugin.getDb()
                .getField(invitedBy,
                        "monthly_invite_count"))
                .intValue();
        plugin.getDb().setField(
                invitedBy, "monthly_invite_count",
                count + 1);

        // ★ 第八批（2026-10-12）：旧「签到给邀请人积分提成」废弃——
        //   原 bonus=(int)(1*rate) 恒为 0（rate 最大 0.60，截断后为 0），
        //   奖励从未实际发放，即用户反馈「邀请好友无效」的根因；
        //   邀请奖励统一在绑定邀请码时按 邀请好友赢债券/规则.md 发债券
        //   （CommissionManager.payInviteBond）。此处只保留月度邀请计数。
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
