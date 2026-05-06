package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatInputManager {

    public enum InputType {
        NONE,
        CHANGE_PWD_STEP1,
        CHANGE_PWD_STEP2,
        CHANGE_PWD_STEP3,
        SET_EMAIL,
        EMAIL_VERIFY,
        SMTP_STEP1,
        SMTP_STEP2,
        SMTP_STEP3,
        SMTP_STEP4,
        SMTP_STEP5,
        SMTP_VERIFY,
        ADMIN_AUTH,
        ADMIN_SET_AFK_TIME,
        ADMIN_SET_PWD,
        ADMIN_SET_POINTS,
        ADMIN_DELETE_CONFIRM,
        INVITE_INPUT_CODE,
        BACK_CHECKIN
    }

    public static class InputState {
        public InputType type = InputType.NONE;
        public String tmpStr = "";
        public int tmpInt = 0;
        public String targetPlayer = "";
        public Map<String, String> tmpMap =
                new LinkedHashMap<>();
    }

    private final Map<UUID, InputState> states =
            new ConcurrentHashMap<>();
    private final Map<String, String> verifyCodes =
            new ConcurrentHashMap<>();
    private final Map<String, Long> verifyExpiry =
            new ConcurrentHashMap<>();

    public InputState getState(Player p) {
        return states.computeIfAbsent(
                p.getUniqueId(), k -> new InputState());
    }

    public void clear(Player p) {
        InputState s = states.get(p.getUniqueId());
        if (s != null) {
            s.type = InputType.NONE;
            s.tmpMap.clear();
        }
    }

    public void setVerifyCode(Player p, String code,
                              String purpose) {
        String key = p.getUniqueId() + ":" + purpose;
        verifyCodes.put(key, code);
        verifyExpiry.put(key,
                System.currentTimeMillis() + 300000L);
    }

    public boolean checkVerifyCode(Player p, String code,
                                   String purpose) {
        String key = p.getUniqueId() + ":" + purpose;
        String stored = verifyCodes.get(key);
        Long expiry = verifyExpiry.get(key);
        if (stored == null || expiry == null)
            return false;
        if (System.currentTimeMillis() > expiry) {
            verifyCodes.remove(key);
            verifyExpiry.remove(key);
            return false;
        }
        if (stored.equals(code)) {
            verifyCodes.remove(key);
            verifyExpiry.remove(key);
            return true;
        }
        return false;
    }

    public boolean handleInput(Player p, String msg,
                               Main main) {
        InputState s = getState(p);
        if (s.type == InputType.NONE) return false;

        if (msg.equalsIgnoreCase("00")) {
            clear(p);
            p.sendMessage(
                    "§e[Sdf1_login] §f操作已取消");
            return true;
        }

        switch (s.type) {

            case CHANGE_PWD_STEP1: {
                String salt = (String) main.getDb()
                        .getField(p.getName(),
                                "password_salt");
                if (salt == null) {
                    clear(p);
                    return true;
                }
                String hash =
                        PasswordUtils.hash(msg, salt);
                if (main.getDb().checkPassword(
                        p.getName(), hash)) {
                    s.type =
                            InputType.CHANGE_PWD_STEP2;
                    p.sendMessage(
                            "§e[Sdf1_login] §f请输入新密码（6-20位，含大写+小写+数字）");
                } else {
                    p.sendMessage(
                            "§c[Sdf1_login] §f旧密码错误！");
                    clear(p);
                }
                return true;
            }

            case CHANGE_PWD_STEP2: {
                if (!PasswordUtils.validate(msg)) {
                    p.sendMessage(main.getConfig2()
                            .msg("password_invalid"));
                    return true;
                }
                s.tmpStr = msg;
                s.type =
                        InputType.CHANGE_PWD_STEP3;
                p.sendMessage(
                        "§e[Sdf1_login] §f请再次输入新密码确认");
                return true;
            }

            case CHANGE_PWD_STEP3: {
                if (!msg.equals(s.tmpStr)) {
                    p.sendMessage(
                            "§c[Sdf1_login] §f两次密码不一致");
                    s.type =
                            InputType.CHANGE_PWD_STEP2;
                    return true;
                }
                String salt =
                        PasswordUtils.generateSalt();
                String hash =
                        PasswordUtils.hash(msg, salt);
                main.getDb().setField(p.getName(),
                        "password_hash", hash);
                main.getDb().setField(p.getName(),
                        "password_salt", salt);
                main.getDb().setField(p.getName(),
                        "temp_password", "");
                main.getDb().setField(p.getName(),
                        "temp_pw_used", 0);
                main.getNeedsPasswordChange()
                        .remove(p.getName());
                p.sendMessage(
                        "§a[Sdf1_login] §f密码修改成功！");
                clear(p);
                return true;
            }

            case SET_EMAIL: {
                if (!msg.contains("@")
                        || !msg.contains(".")) {
                    p.sendMessage(main.getConfig2()
                            .msg("email_invalid"));
                    return true;
                }
                String code =
                        PasswordUtils
                                .generateVerifyCode();
                setVerifyCode(p, code, "email");
                main.getEmail().sendVerifyCode(
                        msg, p.getName(), code);
                s.tmpStr = msg;
                s.type =
                        InputType.EMAIL_VERIFY;
                p.sendMessage(main.getConfig2()
                        .msg("email_verify_sent",
                                "email", msg));
                return true;
            }

            case EMAIL_VERIFY: {
                if (checkVerifyCode(p, msg, "email")) {
                    main.getDb().setField(
                            p.getName(),
                            "email", s.tmpStr);
                    p.sendMessage(main.getConfig2()
                            .msg(
                                    "email_verify_success"));
                    clear(p);
                } else {
                    p.sendMessage(main.getConfig2()
                            .msg(
                                    "email_verify_failed"));
                }
                return true;
            }

            case SMTP_STEP1: {
                s.tmpMap.put("smtp地址", msg);
                s.type = InputType.SMTP_STEP2;
                p.sendMessage(
                        "§e[Sdf1_login] §f当前端口: "
                                + main.getConfig2()
                                .getSmtp("smtp端口"));
                p.sendMessage(
                        "§e[Sdf1_login] §f输入新端口（输入0跳过）:");
                return true;
            }

            case SMTP_STEP2: {
                if (!msg.equals("0"))
                    s.tmpMap.put("smtp端口", msg);
                s.type = InputType.SMTP_STEP3;
                p.sendMessage(
                        "§e[Sdf1_login] §f输入发件邮箱（输入0跳过）:");
                return true;
            }

            case SMTP_STEP3: {
                if (!msg.equals("0"))
                    s.tmpMap.put("发件邮箱", msg);
                s.type = InputType.SMTP_STEP4;
                p.sendMessage(
                        "§e[Sdf1_login] §f输入授权码（输入0跳过）:");
                return true;
            }

            case SMTP_STEP4: {
                if (!msg.equals("0"))
                    s.tmpMap.put("授权码", msg);
                s.type = InputType.SMTP_STEP5;
                p.sendMessage(
                        "§e[Sdf1_login] §f输入测试接收邮箱（输入0跳过）:");
                return true;
            }

            case SMTP_STEP5: {
                if (!msg.equals("0"))
                    s.tmpMap.put("测试邮箱", msg);
                String testEmail =
                        s.tmpMap.getOrDefault(
                                "测试邮箱", "");
                String senderEmail =
                        s.tmpMap.getOrDefault(
                                "发件邮箱", "");
                String recipient =
                        !testEmail.isEmpty()
                                ? testEmail : senderEmail;
                if (recipient == null
                        || recipient.isEmpty()) {
                    p.sendMessage(
                            "§c[Sdf1_login] §f请至少填写发件邮箱");
                    clear(p);
                    return true;
                }
                String code =
                        PasswordUtils
                                .generateVerifyCode();
                setVerifyCode(p, code, "smtp");
                main.getEmail().sendVerifyCode(
                        recipient,
                        p.getName(), code);
                s.type =
                        InputType.SMTP_VERIFY;
                p.sendMessage(main.getConfig2()
                        .msg("smtp_verify_sent",
                                "email", recipient));
                return true;
            }

            case SMTP_VERIFY: {
                if (checkVerifyCode(p, msg, "smtp")) {
                    String addr =
                            s.tmpMap.getOrDefault(
                                    "smtp地址",
                                    main.getConfig2()
                                            .getSmtp("smtp地址"));
                    String port =
                            s.tmpMap.getOrDefault(
                                    "smtp端口",
                                    main.getConfig2()
                                            .getSmtp("smtp端口"));
                    String sender =
                            s.tmpMap.getOrDefault(
                                    "发件邮箱",
                                    main.getConfig2()
                                            .getSmtp("发件邮箱"));
                    String auth =
                            s.tmpMap.getOrDefault(
                                    "授权码",
                                    main.getConfig2()
                                            .getSmtp("授权码"));
                    String test =
                            s.tmpMap.getOrDefault(
                                    "测试邮箱",
                                    main.getConfig2()
                                            .getSmtp("测试邮箱"));
                    main.getConfig2().setSmtp(
                            "smtp地址", addr);
                    main.getConfig2().setSmtp(
                            "smtp端口", port);
                    main.getConfig2().setSmtp(
                            "发件邮箱", sender);
                    main.getConfig2().setSmtp(
                            "授权码", auth);
                    main.getConfig2().setSmtp(
                            "测试邮箱", test);
                    p.sendMessage(main.getConfig2()
                            .msg(
                                    "smtp_verify_success"));
                    clear(p);
                } else {
                    p.sendMessage(main.getConfig2()
                            .msg(
                                    "smtp_verify_failed"));
                    clear(p);
                }
                return true;
            }

            case ADMIN_AUTH: {
                String pass =
                        main.getConfig2().adminPassword;
                if (msg.equals(pass)) {
                    if ("qweasd".equals(pass)) {
                        p.sendMessage(
                                "§c§l[警告] §f当前使用默认管理密码，请尽快修改！");
                    }
                    Bukkit.getScheduler()
                            .runTask(main, () -> {
                                main.getGui()
                                        .openAdmin(p);
                            });
                } else {
                    p.sendMessage(
                            "§c[Sdf1_login] §f管理密码错误！");
                }
                clear(p);
                return true;
            }

            case ADMIN_SET_AFK_TIME: {
                int seconds =
                        parseAfkTime(msg);
                if (seconds > 0) {
                    main.getConfig2()
                            .afkTimeout = seconds;
                    int min = seconds / 60;
                    p.sendMessage(main.getConfig2()
                            .msg("afk_set_time",
                                    "duration",
                                    min + "分钟"));
                } else {
                    p.sendMessage(
                            "§c[Sdf1_login] §f时间格式无效");
                }
                clear(p);
                return true;
            }

            case ADMIN_SET_PWD: {
                if (msg.equals("0")) {
                    p.sendMessage(
                            "§e[Sdf1_login] §f操作已取消");
                    clear(p);
                    return true;
                }
                String target = s.targetPlayer;
                String salt =
                        PasswordUtils.generateSalt();
                String hash =
                        PasswordUtils.hash(msg, salt);
                main.getDb().setField(target,
                        "password_hash", hash);
                main.getDb().setField(target,
                        "password_salt", salt);
                p.sendMessage(main.getConfig2()
                        .msg("admin_set_password",
                                "user", target));
                clear(p);
                return true;
            }


            case ADMIN_SET_POINTS: {
                try {
                    int pts =
                            Integer.parseInt(msg);
                    main.getDb().setField(
                            s.targetPlayer,
                            "points", pts);
                    p.sendMessage(main.getConfig2()
                            .msg("admin_points_set",
                                    "user",
                                    s.targetPlayer,
                                    "points",
                                    String.valueOf(pts)));
                } catch (NumberFormatException e) {
                    p.sendMessage(
                            "§c[Sdf1_login] §f请输入数字");
                }
                clear(p);
                return true;
            }

            case ADMIN_DELETE_CONFIRM: {
                String[] parts =
                        msg.split("\\s+", 2);
                if (parts.length < 2) {
                    p.sendMessage(main.getConfig2()
                            .msg("admin_delete_confirm"));
                    p.sendMessage(
                            "§c格式: <玩家名> <你的密码>");
                    return true;
                }
                String targetName = parts[0];
                String pwd = parts[1];
                String salt = (String) main.getDb()
                        .getField(p.getName(),
                                "password_salt");
                if (salt == null) {
                    clear(p);
                    return true;
                }
                String hash =
                        PasswordUtils.hash(pwd, salt);
                if (main.getDb().checkPassword(
                        p.getName(), hash)
                        && targetName
                        .equalsIgnoreCase(
                                s.targetPlayer)) {
                    main.getDb()
                            .deleteUser(targetName);
                    p.sendMessage(main.getConfig2()
                            .msg("admin_delete_success",
                                    "user",
                                    targetName));
                } else {
                    p.sendMessage(main.getConfig2()
                            .msg(
                                    "admin_delete_failed"));
                }
                clear(p);
                return true;
            }

            case BACK_CHECKIN: {
                String result =
                        main.getCheckIn()
                                .backCheckIn(p, msg);
                p.sendMessage(result);
                clear(p);
                return true;
            }

            case INVITE_INPUT_CODE: {
                String code =
                        msg.trim().toUpperCase();
                String inviter =
                        findInvokerByCode(main, code);
                if (inviter != null
                        && !inviter.equals(
                        p.getName())) {
                    main.getDb().setField(
                            p.getName(),
                            "invited_by", inviter);
                    // 更新邀请人数据
                    String month =
                            new SimpleDateFormat("yyyy-MM")
                                    .format(new Date());
                    String lastMonth =
                            (String) main.getDb()
                                    .getField(inviter,
                                            "invite_month");
                    if (!month.equals(lastMonth)) {
                        main.getDb().setField(
                                inviter,
                                "invite_month", month);
                        main.getDb().setField(
                                inviter,
                                "monthly_invite_count",
                                0);
                    }
                    int cnt = ((Number) main.getDb()
                            .getField(inviter,
                                    "monthly_invite_count"))
                            .intValue();
                    main.getDb().setField(
                            inviter,
                            "monthly_invite_count",
                            cnt + 1);
                    // 通知邀请人
                    Player inviterPlayer =
                            Bukkit.getPlayerExact(
                                    inviter);
                    if (inviterPlayer != null
                            && inviterPlayer
                            .isOnline()) {
                        inviterPlayer.sendMessage(
                                main.getConfig2().msg(
                                        "invite_referral_bonus",
                                        "points",
                                        String.valueOf(
                                                cnt + 1)));
                    }
                    p.sendMessage(main.getConfig2()
                            .msg("invite_bound",
                                    "user", inviter));
                } else {
                    p.sendMessage(
                            "§c[Sdf1_login] §f邀请码无效");
                }
                clear(p);
                return true;
            }

            default:
                return false;
        }
    }

    private int parseAfkTime(String input) {
        String s = input.toLowerCase().trim();
        if (s.contains("小时") || s.contains("时")) {
            String num =
                    s.replaceAll("[^0-9]", "");
            if (!num.isEmpty())
                return Integer.parseInt(num) * 3600;
        }
        if (s.contains("分钟") || s.contains("分")) {
            String num =
                    s.replaceAll("[^0-9]", "");
            if (!num.isEmpty())
                return Integer.parseInt(num) * 60;
        }
        if (s.endsWith("h")) {
            String num =
                    s.replaceAll("[^0-9]", "");
            if (!num.isEmpty())
                return Integer.parseInt(num) * 3600;
        }
        if (s.endsWith("m")) {
            String num =
                    s.replaceAll("[^0-9]", "");
            if (!num.isEmpty())
                return Integer.parseInt(num) * 60;
        }
        String num =
                s.replaceAll("[^0-9]", "");
        if (!num.isEmpty())
            return Integer.parseInt(num) * 60;
        return -1;
    }

    private String findInvokerByCode(Main main,
                                     String code) {
        for (Map<String, Object> user
                : main.getDb().getAllUsers()) {
            String ic = (String) user.getOrDefault(
                    "invite_code", "");
            if (code.equals(ic)) {
                return (String) user
                        .get("player_name");
            }
        }
        return null;
    }
}
