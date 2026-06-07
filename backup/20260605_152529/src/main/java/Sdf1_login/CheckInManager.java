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
    // ===== 签到 =====
    public String checkIn(Player p) {
        String name = p.getName();
        if (isCheckedInToday(name)) {
            return plugin.getConfig2()
                    .msg("checkin_already");
        }

        Object streakObj = plugin.getDb()
                .getField(name, "checkin_streak");
        int streak = streakObj != null
                ? ((Number) streakObj).intValue()
                : 0;

        String lastDate = (String) plugin.getDb()
                .getField(name, "last_checkin_date");
        String today = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date());

        if (lastDate != null && !lastDate.isEmpty()) {
            try {
                java.util.Date lastD =
                        new SimpleDateFormat("yyyy-MM-dd")
                                .parse(lastDate);
                java.util.Date todayD =
                        new SimpleDateFormat("yyyy-MM-dd")
                                .parse(today);
                long diff = todayD.getTime()
                        - lastD.getTime();
                if (diff > 86400000L * 2) {
                    streak = 0;
                } else if (diff > 86400000L) {
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

        int basePoints =
                plugin.getConfig2().checkinPoints;
        int points = (int) (basePoints * multi);

        plugin.getDb().addPoints(name, points);
        plugin.getDb().setField(name,
                "checkin_streak", streak);
        plugin.getDb().setField(name,
                "last_checkin_date", today);

        Object totalObj = plugin.getDb()
                .getField(name, "total_checkin_days");
        int total = totalObj != null
                ? ((Number) totalObj).intValue()
                : 0;
        plugin.getDb().setField(name,
                "total_checkin_days", total + 1);

        // ★ 债券盲盒奖励（6参数版，写入流水）★
        int bondMin =
                plugin.getConfig2().checkinBondMin;
        int bondMax =
                plugin.getConfig2().checkinBondMax;
        if (bondMin <= 0) bondMin = 1;
        if (bondMax < bondMin) bondMax = bondMin;
        int bondReward = bondMin
                + new java.util.Random()
                .nextInt(bondMax - bondMin + 1);
        plugin.getBonds().addBonds(name, bondReward,
                "daily_sign", "", "签到系统",
                "每日签到连续" + streak + "天");

        return plugin.getConfig2()
                .msg("checkin_success")
                .replace("{points}",
                        String.valueOf(points))
                .replace("{streak}",
                        String.valueOf(streak))
                .replace("{multi}",
                        String.valueOf(multi))
                + " §e+" + bondReward + "债券";
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
    // 标准化日期
    private String normalizeDate(String input) {
        String s = input.trim().replace("/", "-");
        if (s.matches("\\d{8}")) {
            return s.substring(0, 4) + "-"
                    + s.substring(4, 6) + "-"
                    + s.substring(6, 8);
        }
        return s;
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
        String reformat = sdf.format(target);
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
                    + "（最多到 " + threeDaysAgo + "）";
        }

        Object val = plugin.getDb()
                .getField(name, "backcheck_dates");
        String existing = val != null
                ? val.toString() : "";
        boolean alreadyBacked = false;
        if (!existing.isEmpty()) {
            for (String d : existing.split(",")) {
                if (normalizeDate(d.trim())
                        .equals(normalized)) {
                    alreadyBacked = true;
                    break;
                }
            }
        }
        if (alreadyBacked) {
            return "§c该日期已补签: " + normalized;
        }

        Object normalCheck =
                plugin.getDb().getField(
                        name, "last_checkin_date");
        String lastCheckin = normalCheck != null
                ? normalCheck.toString() : "";
        if (lastCheckin.equals(normalized)) {
            return "§c该日期已签到: " + normalized;
        }

        // ★ 补签消耗：3债券 + 10积分
        //   积分不够时：用 9债券(3倍)代替 ★
        int bondCost = 3;
        int pointCost = 10;
        int bondAltCost = 9; // 3倍债券替代

        int myBonds = plugin.getBonds()
                .getBonds(name);
        Object ptsObj = plugin.getDb()
                .getField(name, "points");
        int myPoints = ptsObj != null
                ? ((Number) ptsObj).intValue()
                : 0;


        // 方案A：3债券 + 10积分
        boolean canA = myBonds >= bondCost
                && myPoints >= pointCost;
        // 方案B：9债券（积分不够时）
        boolean canB = myBonds >= bondAltCost
                && myPoints < pointCost;

        if (!canA && !canB) {
            if (myPoints < pointCost
                    && myBonds < bondAltCost) {
                return "§c补签费用不足"
                        + "（需3债券+10积分，"
                        + "或9债券代替）"
                        + " 当前: "
                        + myBonds + "债券 "
                        + myPoints + "积分";
            }
            if (myBonds < bondCost) {
                return "§c债券不足（需"
                        + bondCost + "枚）";
            }
            return "§c积分不足（需"
                    + pointCost + "分）";
        }

        // 执行扣款
        if (canA) {
            // 方案A：3债券 + 10积分
            plugin.getBonds().deductBonds(
                    name, bondCost,
                    "admin_deduct", "",
                    "补签系统", "补签扣款");
            plugin.getDb().deductPoints(
                    name, pointCost);
        } else {
            // 方案B：9债券代替
            plugin.getBonds().deductBonds(
                    name, bondAltCost,
                    "admin_deduct", "",
                    "补签系统", "补签扣款(债券替代)");
        }

        doBackCheckIn(p, normalized);

        String costMsg = canA
                ? bondCost + "债券+" + pointCost + "积分"
                : bondAltCost + "债券(替代)";
        return "§a补签成功！日期: "
                + normalized
                + " §7消耗: " + costMsg;
    }



    // ===== 执行补签 =====
    // ===== 执行补签 =====
    public void doBackCheckIn(
            Player p, String dateStr) {
        String name = p.getName();
        Object val = plugin.getDb()
                .getField(name, "backcheck_dates");
        String existing = val != null
                ? val.toString() : "";
        String updated = existing.isEmpty()
                ? dateStr
                : existing + "," + dateStr;
        plugin.getDb().setField(name,
                "backcheck_dates", updated);
        int streak = getStreak(name);
        plugin.getDb().setField(name,
                "checkin_streak", streak + 1);
        // ★ 补签不发放奖励，只记录
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