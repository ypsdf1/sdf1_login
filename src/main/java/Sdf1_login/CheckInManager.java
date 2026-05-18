package Sdf1_login;

import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

public class CheckInManager {

    private final Main plugin;

    public CheckInManager(Main plugin) {
        this.plugin = plugin;
    }

    // ===== 今日是否已签到 =====
    public boolean isCheckedInToday(
            String name) {
        String last = (String) plugin.getDb()
                .getField(name,
                        "last_checkin_date");
        String today = new SimpleDateFormat(
                "yyyy-MM-dd")
                .format(new Date());
        return today.equals(last);
    }

    // ===== 签到 =====
    public String checkIn(Player p) {
        String name = p.getName();
        if (isCheckedInToday(name)) {
            return plugin.getConfig2()
                    .msg("checkin_already");
        }

        Object streakObj = plugin.getDb()
                .getField(name,
                        "checkin_streak");
        int streak = streakObj != null
                ? ((Number) streakObj)
                  .intValue() : 0;

        String lastDate = (String) plugin
                .getDb().getField(name,
                        "last_checkin_date");
        String today = new SimpleDateFormat(
                "yyyy-MM-dd")
                .format(new Date());

        if (lastDate != null
                && !lastDate.isEmpty()) {
            try {
                java.util.Date lastD =
                        new SimpleDateFormat(
                                "yyyy-MM-dd")
                                .parse(lastDate);
                java.util.Date todayD =
                        new SimpleDateFormat(
                                "yyyy-MM-dd")
                                .parse(today);
                long diff = todayD.getTime()
                        - lastD.getTime();
                if (diff > 86400000L * 2) {
                    streak = 0;
                } else if (diff
                        > 86400000L) {
                    streak++;
                }
            } catch (Exception ignored) {
            }
        }

        if (streak == 0) streak = 1;
        else streak++;

        double multi = 1.0;
        if (streak >= 21) multi = 1.5;
        else if (streak >= 14) multi = 1.3;
        else if (streak >= 7) multi = 1.1;

        int basePoints = plugin.getConfig2()
                .checkinPoints;
        int points =
                (int) (basePoints * multi);

        plugin.getDb().addPoints(
                name, points);
        plugin.getDb().setField(
                name, "checkin_streak",
                streak);
        plugin.getDb().setField(
                name,
                "last_checkin_date", today);

        Object totalObj = plugin.getDb()
                .getField(name,
                        "total_checkin_days");
        int total = totalObj != null
                ? ((Number) totalObj)
                  .intValue() : 0;
        plugin.getDb().setField(
                name,
                "total_checkin_days",
                total + 1);

        return plugin.getConfig2()
                .msg("checkin_success")
                .replace("{points}",
                        String.valueOf(points))
                .replace("{streak}",
                        String.valueOf(streak))
                .replace("{multi}",
                        String.valueOf(multi));
    }

    // ===== 获取连续天数 =====
    public int getStreak(String name) {
        Object val = plugin.getDb()
                .getField(name,
                        "checkin_streak");
        if (val instanceof Number) {
            return ((Number) val)
                    .intValue();
        }
        return 0;
    }

    // ===== 补签入口 =====
    public String backCheckIn(
            Player p, String dateStr) {
        String name = p.getName();
        String normalized =
                normalizeDate(dateStr);

        // 严格日期验证
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd");
        sdf.setLenient(false);
        java.util.Date target;
        try {
            target = sdf.parse(normalized);
        } catch (Exception ex) {
            return "§c无效日期: " + dateStr;
        }
        String reformat =
                sdf.format(target);
        if (!reformat.equals(normalized)) {
            return "§c无效日期: " + dateStr;
        }

        String today = sdf.format(new Date());

        if (normalized.compareTo(today) >= 0) {
            return "§c今天不需要补签";
        }

        String threeDaysAgo = getPastDate(3);
        if (normalized.compareTo(
                threeDaysAgo) < 0) {
            return "§c只能补签3天内"
                    + "（最多到 "
                    + threeDaysAgo + "）";
        }

        Object val = plugin.getDb()
                .getField(name,
                        "backcheck_dates");
        String existing = val != null
                ? val.toString() : "";
        boolean alreadyBacked = false;
        if (!existing.isEmpty()) {
            for (String d
                    : existing.split(",")) {
                if (normalizeDate(d.trim())
                        .equals(normalized)) {
                    alreadyBacked = true;
                    break;
                }
            }
        }
        if (alreadyBacked) {
            return "§c该日期已补签: "
                    + normalized;
        }

        Object normalCheck =
                plugin.getDb().getField(
                        name,
                        "last_checkin_date");
        String lastCheckin = normalCheck
                != null
                ? normalCheck.toString()
                : "";
        if (lastCheckin.equals(normalized)) {
            return "§c该日期已签到: "
                    + normalized;
        }

        if (!plugin.getDb()
                .deductPoints(name, 10)) {
            return "§c积分不足，补签需 10 积分";
        }

        net.milkbowl.vault.economy
                .Economy econ =
                plugin.getEconomy();
        if (econ != null) {
            double bal = econ.getBalance(p);
            if (bal < 10.0) {
                plugin.getDb()
                        .addPoints(name, 10);
                int mult = plugin.getConfig2()
                        .backCheckPointMultiplier;
                int needed = 10 * mult;
                p.sendMessage("§e§l[补签] "
                        + "§f经济不足($"
                        + String.format(
                        "%.2f", bal)
                        + " < $10)");
                p.sendMessage("§7是否用 §a§l"
                        + needed + "积分 "
                        + "§7代替 10经济?");
                p.sendMessage("§7输入 §a1 "
                        + "§7同意，"
                        + "§c其他 §7取消");
                return "NEED_CONFIRM:"
                        + normalized
                        + ":" + needed;
            }
            econ.withdrawPlayer(p, 10);
        }

        doBackCheckIn(p, normalized);
        return "§a补签成功！日期: "
                + normalized
                + " §7(扣除10积分+10经济)";
    }


    // ===== 执行补签 =====
    public void doBackCheckIn(
            Player p, String dateStr) {
        String name = p.getName();
        Object val = plugin.getDb()
                .getField(name,
                        "backcheck_dates");
        String existing = val != null
                ? val.toString() : "";
        String updated = existing.isEmpty()
                ? dateStr
                : existing + "," + dateStr;
        plugin.getDb().setField(name,
                "backcheck_dates", updated);
        int streak = getStreak(name);
        plugin.getDb().setField(name,
                "checkin_streak",
                streak + 1);
    }

    // ===== 标准化日期 =====
    private String normalizeDate(
            String input) {
        String s = input.trim()
                .replace("/", "-");
        if (s.matches("\\d{8}")) {
            return s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8);
        }
        return s;
    }

    // ===== 获取N天前日期 =====
    private String getPastDate(
            int daysAgo) {
        Calendar cal =
                Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH,
                -daysAgo);
        return new SimpleDateFormat(
                "yyyy-MM-dd")
                .format(cal.getTime());
    }
}
