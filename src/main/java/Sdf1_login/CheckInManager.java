package Sdf1_login;

import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;

public class CheckInManager {

    private final Main plugin;

    public CheckInManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean isCheckedInToday(String name) {
        String last = (String) plugin.getDb()
                .getField(name, "last_checkin_date");
        String today = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date());
        return today.equals(last);
    }

    public String checkIn(Player p) {
        String name = p.getName();
        if (isCheckedInToday(name)) {
            return plugin.getConfig2()
                    .msg("checkin_already");
        }

        Object streakObj = plugin.getDb()
                .getField(name, "checkin_streak");
        int streak = streakObj != null
                ? ((Number) streakObj).intValue() : 0;

        String lastDate = (String) plugin.getDb()
                .getField(name, "last_checkin_date");
        String today = new SimpleDateFormat("yyyy-MM-dd")
                .format(new Date());

        if (lastDate != null && !lastDate.isEmpty()) {
            try {
                java.util.Date lastD = new SimpleDateFormat(
                        "yyyy-MM-dd").parse(lastDate);
                java.util.Date todayD = new SimpleDateFormat(
                        "yyyy-MM-dd").parse(today);
                long diff = todayD.getTime() - lastD.getTime();
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

        int points = (int) (1 * multi);
        plugin.getDb().addPoints(name, points);
        plugin.getDb().setField(
                name, "checkin_streak", streak);
        plugin.getDb().setField(
                name, "last_checkin_date", today);
        Object totalObj = plugin.getDb()
                .getField(name, "total_checkin_days");
        int total = totalObj != null
                ? ((Number) totalObj).intValue() : 0;
        plugin.getDb().setField(
                name, "total_checkin_days", total + 1);

        return plugin.getConfig2()
                .msg("checkin_success")
                .replace("{points}",
                        String.valueOf(points))
                .replace("{streak}",
                        String.valueOf(streak))
                .replace("{multi}",
                        String.valueOf(multi));
    }

    public String backCheckIn(Player p, String dateStr) {
        String name = p.getName();
        if (!plugin.getDb().deductPoints(name, 10)) {
            return plugin.getConfig2()
                    .msg("checkin_already");
        }
        try {
            java.util.Date target = new SimpleDateFormat(
                    "yyyy-MM-dd").parse(dateStr);
            java.util.Date now = new SimpleDateFormat(
                    "yyyy-MM-dd").parse(
                    new SimpleDateFormat("yyyy-MM-dd")
                            .format(new Date()));
            long diff = now.getTime() - target.getTime();
            if (diff < 0 || diff > 3 * 86400000L) {
                plugin.getDb().addPoints(name, 10);
                return plugin.getConfig2()
                        .msg("checkin_already");
            }
            plugin.getDb().addPoints(name, 1);
            return "§a补签成功！日期: " + dateStr;
        } catch (Exception e) {
            plugin.getDb().addPoints(name, 10);
            return "§c日期格式错误，请用 yyyy-MM-dd";
        }
    }
}
