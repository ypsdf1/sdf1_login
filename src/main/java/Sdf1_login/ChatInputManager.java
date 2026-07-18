package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class ChatInputManager {

    public enum InputType {
        NONE,
        CHANGE_PWD_STEP1,
        CHANGE_PWD_STEP2,
        SET_EMAIL,
        SET_EMAIL_VERIFY,
        ADMIN_AUTH,
        ADMIN_SET_POINTS,
        ADMIN_SET_PWD,
        ADMIN_DELETE_CONFIRM,
        ADMIN_SET_AFK_TIME,
        SMTP_CONFIG,
        SMTP_VERIFY,
        BACK_CHECKIN,
        INVITE_INPUT_CODE,
        TICKET_TITLE,
        TICKET_DESC,
        TICKET_SCORE,
        TICKET_REJECT,
        TICKET_ASSIGN,
        TICKET_INVITE_PROVIDER,
        TICKET_REMOVE_PROVIDER,
        TICKET_COMPLETE,
        TICKET_REPORT_ISSUE,
        TICKET_REPLY,
        TICKET_ADMIN_TYPE,
        TICKET_ADMIN_TITLE,
        TICKET_ADMIN_DESC,
        TICKET_ADMIN_PROVIDER,
        BACK_CHECK_ECONOMY_CONFIRM,
        USER_MGMT_SEARCH,

        }

    public static class PlayerState {
        public InputType type = InputType.NONE;
        public String targetPlayer = "";
        public String ticketType = "";
        public String ticketTitle = "";
        public String ticketDesc = "";
        public int ticketId = 0;
        public int smtpStep = 0;
        public int scoreValue = 0;

    }

    private final Map<String, PlayerState> states =
            new HashMap<>();
    private Main plugin;

    public PlayerState getState(Player p) {
        return states.computeIfAbsent(
                p.getName(),
                k -> new PlayerState());
    }

    public void reset(Player p) {
        states.remove(p.getName());
    }
    public boolean isInFlow(Player p) {
        PlayerState state = states.get(p.getName());
        return state != null
                && state.type != InputType.NONE;
    }

    public void handleInput(Player p, String msg,
                            Main mainPlugin) {
        if (this.plugin == null)
            this.plugin = mainPlugin;
        PlayerState state = getState(p);
        ConfigManager config =
                mainPlugin.getConfig2();
        DatabaseManager db = mainPlugin.getDb();

        switch (state.type) {

            case CHANGE_PWD_STEP1: {
                String name = p.getName();
                String salt = (String) db.getField(
                        name, "password_salt");
                String hash =
                        PasswordUtils.hash(msg, salt);
                String result = db.checkPasswordWithFallback(name, hash);
                if ("main".equals(result)) {
                    state.type =
                            InputType.CHANGE_PWD_STEP2;
                    state.ticketTitle = "main";
                    p.sendMessage(
                            "§e请输入新密码:");
                    return;
                }
                if ("temp".equals(result)) {
                    state.type =
                            InputType.CHANGE_PWD_STEP2;
                    state.ticketTitle = "temp";
                    p.sendMessage(
                            "§e请输入新密码:");
                    return;
                }
                p.sendMessage(config.msg(
                        "password_wrong"));
                return;
            }

            case CHANGE_PWD_STEP2: {
                String name = p.getName();
                if (!PasswordUtils.validate(msg)) {
                    p.sendMessage(config.msg(
                            "password_format_error"));
                    reset(p);
                    return;
                }
                String oldHash =
                        (String) db.getField(name,
                                "password_hash");
                String oldSalt =
                        (String) db.getField(name,
                                "password_salt");
                String newSalt =
                        PasswordUtils.generateSalt();
                String newHash =
                        PasswordUtils.hash(
                                msg, newSalt);
                if (newHash.equals(oldHash)) {
                    p.sendMessage(config.msg(
                            "password_same"));
                    reset(p);
                    return;
                }
                mainPlugin.recordPasswordChange(
                        name, oldHash, oldSalt);
                db.setField(name,
                        "password_hash", newHash);
                db.setField(name,
                        "password_salt", newSalt);
                mainPlugin
                        .getNeedsPasswordChange()
                        .remove(name);
                p.sendMessage(config.msg(
                        "password_changed"));
                reset(p);
                return;
            }

            case SET_EMAIL: {
                if (!msg.contains("@")
                        || !msg.contains(".")) {
                    p.sendMessage(config.msg(
                            "email_format_error"));
                    reset(p);
                    return;
                }
                String emailAddr = msg.trim();
                // 邮箱后缀黑白名单校验
                if (!config.isEmailSuffixAllowed(emailAddr)) {
                    String mode = config.getEmailValidationMode();
                    if ("白名单".equals(mode)) {
                        p.sendMessage("§c该邮箱后缀不在允许列表中");
                    } else if ("黑名单".equals(mode)) {
                        p.sendMessage("§c该邮箱后缀被禁止使用");
                    }
                    reset(p);
                    return;
                }
                String oldEmail = (String)
                        db.getField(p.getName(),
                                "email");
                // 已绑定邮箱：验证登录密码
                if (oldEmail != null
                        && !oldEmail.isEmpty()) {
                    state.targetPlayer = emailAddr;
                    state.type =
                            InputType.SET_EMAIL_VERIFY;
                    state.ticketTitle =
                            "need_pwd";
                    p.sendMessage(
                            "§e您已绑定邮箱: "
                                    + oldEmail);
                    p.sendMessage(
                            "§e请验证登录密码以确认修改:");
                    return;
                }
                // 未绑定邮箱：直接发验证码
                String code = String.valueOf(
                        (int) (Math.random()
                                * 900000
                                + 100000));
                state.targetPlayer = emailAddr;
                state.ticketTitle = code;
                state.type =
                        InputType.SET_EMAIL_VERIFY;
                final String to = emailAddr;
                final String c = code;
                final Player fp = p;
                Bukkit.getScheduler()
                        .runTaskAsynchronously(
                                mainPlugin, () -> {
                                    boolean sent = mainPlugin
                                            .getEmail()
                                            .sendVerifyCode(
                                                    to, c);
                                    Bukkit.getScheduler()
                                            .runTask(mainPlugin,
                                                    () -> {
                                                        if (sent) {
                                                            fp.sendMessage(
                                                                    "§a验证码已发送到 "
                                                                            + to);
                                                            fp.sendMessage(
                                                                    "§e输入6位验证码(0取消):");
                                                        } else {
                                                            fp.sendMessage(
                                                                    "§c发送失败");
                                                            reset(fp);
                                                        }
                                                    });
                                });
                return;
            }


            case SET_EMAIL_VERIFY: {
                if ("0".equals(msg.trim())
                        || "取消".equals(
                        msg.trim())) {
                    p.sendMessage(
                            "§c邮箱设置已取消");
                    reset(p);
                    return;
                }
                String expected =
                        state.ticketTitle;
                if (msg.trim().equals(expected)) {
                    db.setField(p.getName(),
                            "email",
                            state.targetPlayer);
                    p.sendMessage(
                            "§a邮箱已设置: "
                                    + state.targetPlayer);
                    reset(p);
                } else {
                    p.sendMessage(
                            "§c验证码错误(0取消):");
                }
                return;
            }

            case ADMIN_AUTH: {
                if (msg.equals(
                        config.adminPassword)) {
                    Bukkit.getScheduler().runTask(
                            mainPlugin, () ->
                                    mainPlugin
                                            .getGui()
                                            .openAdmin(p));
                } else {
                    p.sendMessage(
                            "§c管理密码错误");
                }
                reset(p);
                return;
            }

            case ADMIN_SET_POINTS: {
                try {
                    int pts = Integer.parseInt(
                            msg.trim());
                    db.setField(
                            state.targetPlayer,
                            "points", pts);
                    p.sendMessage("§a已将 "
                            + state.targetPlayer
                            + " 积分设为 " + pts);
                } catch (
                        NumberFormatException e) {
                    p.sendMessage(
                            "§c请输入有效数字");
                }
                String tgt =
                        state.targetPlayer;
                Bukkit.getScheduler().runTask(
                        mainPlugin, () ->
                                mainPlugin
                                        .getGui()
                                        .openUserDetail(
                                                p, tgt));
                reset(p);
                return;
            }

            case ADMIN_SET_PWD: {
                if (!PasswordUtils.validate(msg)) {
                    p.sendMessage(config.msg(
                            "password_format_error"));
                    reset(p);
                    return;
                }
                String tgt = state.targetPlayer;
                String salt =
                        PasswordUtils
                                .generateSalt();
                String hash =
                        PasswordUtils.hash(
                                msg, salt);
                db.setField(tgt,
                        "temp_password", hash);
                db.setField(tgt, "temp_pw_expire",
                        System.currentTimeMillis()
                                + 300000L);
                db.setField(tgt,
                        "temp_pw_used", 0);
                p.sendMessage("§a已为 " + tgt
                        + " 设置临时密码");
                Player tp =
                        Bukkit.getPlayer(tgt);
                if (tp != null && tp.isOnline())
                    tp.kickPlayer(
                            "§c管理员已设置临时密码，"
                                    + "请使用临时密码登录");
                // 邮件通知
                String emailAddr = (String)
                        db.getField(tgt, "email");
                if (emailAddr != null
                        && !emailAddr.isEmpty()) {
                    String time =
                            new java.text.SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss")
                                    .format(new java.util.Date());
                    String body = "玩家 " + tgt
                            + "：\n您的密码已于 "
                            + time
                            + " 被管理员重置为临时密码。\n"
                            + "如果是本人操作请忽略。\n"
                            + "如非本人操作请立即联系管理员。";
                    final String to = emailAddr;
                    final String fBody = body;
                    final String fName = tgt;
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(
                                    mainPlugin, () ->
                                            mainPlugin.getEmail()
                                                    .sendTempPassword(
                                                            to,
                                                            fName,
                                                            fBody));
                }
                reset(p);
                return;
            }

            case ADMIN_DELETE_CONFIRM: {
                if (msg.equals(
                        state.targetPlayer)) {
                    db.deleteUser(
                            state.targetPlayer);
                    if (plugin.webManager != null) {
                        plugin.webManager.deleteWebUser(state.targetPlayer);
                    }
                    p.sendMessage("§a已删除 "
                            + state.targetPlayer);
                    Player tgt =
                            Bukkit.getPlayer(
                                    state.targetPlayer);
                    if (tgt != null
                            && tgt.isOnline())
                        tgt.kickPlayer(
                                "§c账号已被删除");
                } else {
                    p.sendMessage(
                            "§c输入不匹配，已取消");
                }
                reset(p);
                return;
            }

            case ADMIN_SET_AFK_TIME: {
                try {
                    int minutes =
                            Integer.parseInt(
                                    msg.trim());
                    config.afkTimeout =
                            minutes * 60;
                    config.saveSettings();
                    p.sendMessage(
                            "§a挂机时长已设为 "
                                    + minutes
                                    + " 分钟");
                } catch (
                        NumberFormatException e) {
                    p.sendMessage(
                            "§c请输入有效数字");
                }
                reset(p);
                return;
            }

            case SMTP_CONFIG: {
                String m = msg.trim();
                if ("000".equals(m)
                        || "取消取消取消"
                        .equals(m)) {
                    p.sendMessage(
                            "§cSMTP配置已取消");
                    reset(p);
                    return;
                }
                if ("取消".equals(m)) {
                    p.sendMessage(
                            "§cSMTP配置已取消");
                    reset(p);
                    return;
                }
                int step = state.smtpStep;
                if (step <= 1) {
                    if (!"0".equals(m))
                        config.setSmtp(
                                "smtp地址", m);
                    state.smtpStep = 2;
                    p.sendMessage("§e当前端口: "
                            + config.getSmtp(
                            "smtp端口"));
                    p.sendMessage(
                            "§e输入端口(0跳过):");
                } else if (step == 2) {
                    if (!"0".equals(m))
                        config.setSmtp(
                                "smtp端口", m);
                    state.smtpStep = 3;
                    p.sendMessage(
                            "§e输入发件邮箱:");
                } else if (step == 3) {
                    if (!"0".equals(m))
                        config.setSmtp(
                                "smtp账号", m);
                    state.smtpStep = 4;
                    p.sendMessage(
                            "§e输入SMTP密码(0跳过):");
                } else if (step == 4) {
                    if (!"0".equals(m))
                        config.setSmtp(
                                "smtp密码", m);
                    state.smtpStep = 5;
                    p.sendMessage(
                            "§e输入接收邮箱(留空发到发件邮箱):");
                } else if (step == 5) {
                    String recv = m;
                    if (recv.isEmpty()
                            || "0".equals(recv))
                        recv = config.getSmtp(
                                "smtp账号");
                    config.setSmtp(
                            "验证码接收邮箱", recv);
                    if (!config
                            .canSendSmtpVerify()) {
                        p.sendMessage(
                                "§c发送间隔不足1分钟");
                        reset(p);
                        return;
                    }
                    String code = config
                            .generateSmtpVerifyCode();
                    String addr = config.getSmtp(
                            "smtp地址");
                    String acct = config.getSmtp(
                            "smtp账号");
                    if (acct.isEmpty()
                            || addr.isEmpty()) {
                        p.sendMessage(
                                "§c地址或账号为空");
                        reset(p);
                        return;
                    }
                    config.recordSmtpSendTime();
                    final String fRecv = recv;
                    final String fCode = code;
                    final Player fp = p;
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(
                                    mainPlugin, () -> {
                                        Bukkit.getScheduler()
                                                .runTask(
                                                        mainPlugin,
                                                        () -> fp.sendMessage(
                                                                "§e正在发送验证码..."));
                                        boolean sent =
                                                mainPlugin.getEmail()
                                                        .sendVerifyCode(
                                                                fRecv,
                                                                fCode);
                                        final boolean fSent = sent;
                                        Bukkit.getScheduler()
                                                .runTask(
                                                        mainPlugin,
                                                        () -> {
                                                            if (fSent) {
                                                                state.type =
                                                                        InputType
                                                                                .SMTP_VERIFY;
                                                                state.smtpStep = 0;
                                                                fp.sendMessage(
                                                                        "§a验证码已发送到 "
                                                                                + fRecv);
                                                                fp.sendMessage(
                                                                        "§e输入6位验证码(0取消):");
                                                            } else {
                                                                fp.sendMessage(
                                                                        "§c发送失败，查看控制台");
                                                                reset(fp);
                                                            }
                                                        });
                                    });
                }
                return;
            }

            case SMTP_VERIFY: {
                if ("0".equals(msg.trim())
                        || "取消".equals(
                        msg.trim())) {
                    p.sendMessage(
                            "§cSMTP配置已取消");
                    reset(p);
                    return;
                }
                if (config.checkSmtpVerifyCode(
                        msg.trim())) {
                    config.saveSmtp();
                    config.clearSmtpVerifyCode();
                    String addr = config.getSmtp(
                            "smtp地址");
                    String port = config.getSmtp(
                            "smtp端口");
                    String acct = config.getSmtp(
                            "smtp账号");
                    String recv = config.getSmtp(
                            "验证码接收邮箱");
                    boolean ssl = config.smtpSsl;
                    p.sendMessage("§a§l=== SMTP配置完成 ===");
                    p.sendMessage("§7地址: §f" + addr);
                    p.sendMessage("§7端口: §f" + port);
                    p.sendMessage("§7账号: §f" + acct);
                    p.sendMessage("§7接收: §f" + recv);
                    p.sendMessage("§7加密: §f" + ssl);
                    p.sendMessage("§a§l====================");
                    reset(p);
                } else {
                    p.sendMessage(
                            "§c验证码错误(0取消):");
                }
                return;
            }

            case BACK_CHECKIN: {
                String result =
                        mainPlugin.getCheckIn()
                                .backCheckIn(p, msg);
                if (result == null) {
                    // 等待选择（NEED_CONFIRM）
                    return;
                }
                if (result.startsWith(
                        "NEED_CONFIRM:")) {
                    String[] parts =
                            result.split(":");
                    // 存储到Main的Map
                    mainPlugin.pendingBackCheck.put(
                            p.getName(),
                            parts[1] + ":" + parts[2]);
                    return;
                }
                if (result.startsWith("§c")) {
                    p.sendMessage(result);
                } else {
                    p.sendMessage(result);
                }
                reset(p);
                return;
            }


            case INVITE_INPUT_CODE: {
                String code = msg.trim();
                String myName = p.getName();
                String myCode =
                        (String) db.getField(
                                myName,
                                "invite_code");
                if (code.isEmpty()) {
                    p.sendMessage(
                            "§c邀请码不能为空");
                    reset(p);
                    return;
                }
                if (code.equals(myCode)) {
                    p.sendMessage(
                            "§c不能用自己邀请码");
                    reset(p);
                    return;
                }
                boolean found = false;
                for (Map<String, Object> u :
                        db.getAllUsers()) {
                    String uc = (String)
                            u.getOrDefault(
                                    "invite_code", "");
                    if (code.equals(uc)) {
                        String inv = (String)
                                u.get("player_name");
                        db.setField(myName,
                                "invited_by", inv);
                        db.addPoints(inv, 50);
                        p.sendMessage(
                                "§a邀请成功！邀请人: "
                                        + inv);
                        Player ip =
                                Bukkit.getPlayer(inv);
                        if (ip != null
                                && ip.isOnline())
                            ip.sendMessage("§a"
                                    + myName
                                    + " 使用了您的邀请码");
                        found = true;
                        break;
                    }
                }
                if (!found)
                    p.sendMessage(
                            "§c邀请码不存在");
                reset(p);
                return;
            }

            case TICKET_TITLE: {
                state.ticketTitle = msg;
                state.type =
                        InputType.TICKET_DESC;
                p.sendMessage(
                        "§e请输入工单详细描述:");
                return;
            }

            case TICKET_DESC: {
                String tt = state.ticketType;
                String tl = state.ticketTitle;
                reset(p);
                mainPlugin.getTicket()
                        .submitTicket(
                                p, tt, tl, msg);
                return;
            }

            case TICKET_SCORE: {
                int tid = state.ticketId;
                try {
                    int sc = Integer.parseInt(
                            msg.trim());
                    reset(p);
                    mainPlugin.getTicket()
                            .confirmAndScore(
                                    p, tid, sc);
                } catch (
                        NumberFormatException e) {
                    reset(p);
                    p.sendMessage(
                            "§c请输入数字1-5");
                }
                return;
            }

            case TICKET_REJECT: {
                int tid = state.ticketId;
                reset(p);
                mainPlugin.getTicket()
                        .rejectTicket(
                                p, tid, msg);
                return;
            }

            case TICKET_ASSIGN: {
                int tid = state.ticketId;
                String[] parts = msg.trim()
                        .split("\\s+");
                String prov = parts[0];
                int price = 0;
                if (parts.length >= 2) {
                    try {
                        price = Integer.parseInt(
                                parts[1]);
                    } catch (
                            NumberFormatException e) {
                        p.sendMessage(
                                "§c价格格式错误");
                    }
                }
                reset(p);
                mainPlugin.getTicket()
                        .assignToProvider(
                                p, tid, prov,
                                price);
                final int fTid = tid;
                Bukkit.getScheduler().runTask(
                        mainPlugin, () ->
                                mainPlugin
                                        .getTicket()
                                        .openDetail(
                                                p, fTid));
                return;
            }

            case TICKET_INVITE_PROVIDER: {
                String tgt = msg.trim();
                reset(p);
                if (!db.userExists(tgt)) {
                    p.sendMessage(
                            "§c玩家不存在");
                    return;
                }
                db.addServiceProvider(
                        tgt, "waiter");
                p.sendMessage("§a已邀请 " + tgt
                        + " 成为服务商");
                return;
            }

            case TICKET_REMOVE_PROVIDER: {
                String tgt = msg.trim();
                reset(p);
                if (!db.isServiceProvider(tgt)) {
                    p.sendMessage("§c" + tgt
                            + " 不是服务商");
                    return;
                }
                db.removeServiceProvider(tgt);
                p.sendMessage("§a已移除 " + tgt);
                return;
            }

            case TICKET_COMPLETE: {
                try {
                    int tid = Integer.parseInt(
                            msg.trim());
                    mainPlugin.getTicket()
                            .completeAsProvider(
                                    p, tid);
                } catch (
                        NumberFormatException e) {
                    p.sendMessage(
                            "§c请输入有效工单号");
                }
                reset(p);
                return;
            }

            case TICKET_REPORT_ISSUE: {
                String report = msg.trim();
                reset(p);
                p.sendMessage("§e异常已上报");
                String tag = config.adminTag;
                for (Player op :
                        Bukkit.getOnlinePlayers()) {
                    if (op.getScoreboardTags()
                            .contains(tag)) {
                        op.sendMessage(
                                "§c§l[工单异常] §f"
                                        + p.getName()
                                        + ": " + report);
                    }
                }
                return;
            }

            case TICKET_REPLY: {
                int replyTid = state.ticketId;
                reset(p);
                mainPlugin.getTicket()
                        .replyTicket(
                                p, replyTid, msg);
                return;
            }

            case TICKET_ADMIN_TYPE: {
                state.ticketType = msg.trim();
                state.type =
                        InputType
                                .TICKET_ADMIN_TITLE;
                p.sendMessage(
                        "§e请输入工单标题:");
                return;
            }

            case TICKET_ADMIN_TITLE: {
                state.ticketTitle = msg;
                state.type =
                        InputType
                                .TICKET_ADMIN_DESC;
                p.sendMessage(
                        "§e请输入工单描述:");
                return;
            }

            case TICKET_ADMIN_DESC: {
                state.targetPlayer = msg;
                state.type =
                        InputType
                                .TICKET_ADMIN_PROVIDER;
                p.sendMessage(
                        "§e输入服务商名和价格:");
                p.sendMessage(
                        "§7例: playerA 20");
                return;
            }

            case TICKET_ADMIN_PROVIDER: {
                String[] parts2 = msg.trim()
                        .split("\\s+");
                String prov2 = parts2[0];
                int price2 = 0;
                if (parts2.length >= 2) {
                    try {
                        price2 = Integer.parseInt(
                                parts2[1]);
                    } catch (
                            NumberFormatException e) {
                    }
                }
                String t2 = state.ticketType;
                String ti2 = state.ticketTitle;
                String d2 = state.targetPlayer;
                reset(p);
                mainPlugin.getTicket()
                        .adminCreateAndAssign(
                                p, t2, ti2,
                                d2, prov2, price2);
                return;
            }

            default:
                reset(p);
                return;
        }
    }
}
