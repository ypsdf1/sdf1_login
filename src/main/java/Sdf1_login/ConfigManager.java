package Sdf1_login;
// 配置文件
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public class ConfigManager {

    private final Map<String, String> messages =
            new LinkedHashMap<>();
    private final Map<String, String> smtp = new LinkedHashMap<>();
    private final File dataFolder;

    // 配置项
    public int afkTimeout = 300;
    public int loginTimeout = 120;
    public boolean afkEnabled = true;
    public Set<String> afkWhitelist =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    public String adminTag = "admin";
    public String adminPassword = "qweasd";


    public ConfigManager(File dataFolder) {
        this.dataFolder = dataFolder;
    }

    // ===== 消息配置 =====

    public void loadMessages() {
        messages.clear();
        File f = new File(dataFolder, "设置.txt");
        if (!f.exists()) writeDefaultMessages();
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF"))
                    line = line.substring(1);
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                messages.put(t.substring(0, eq).trim(),
                        t.substring(eq + 1).trim());
            }
            r.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        ensureMessageDefaults();
    }


    private void ensureMessageDefaults() {
        putDefault("not_registered",
                "§c§l[Sdf1_login] §f你还没有注册，请使用 /reg <密码> 注册");
        putDefault("not_logged_in",
                "§c§l[Sdf1_login] §f请先使用 /login <密码> 登录");
        putDefault("login_success",
                "§a§l[Sdf1_login] §f登录成功！欢迎回来 {user}");
        putDefault("register_success",
                "§a§l[Sdf1_login] §f注册成功！请使用 /login <密码> 登录");
        putDefault("login_failed",
                "§c§l[Sdf1_login] §f密码错误！");
        putDefault("already_registered",
                "§c§l[Sdf1_login] §f你已经注册过了");
        putDefault("already_logged_in",
                "§a§l[Sdf1_login] §f你已经登录了");
        putDefault("password_invalid",
                "§c§l[Sdf1_login] §f密码不符合要求！需要6-20位，包含大写字母+小写字母+数字");
        putDefault("password_format_hint",
                "§7密码要求: 6-20位，至少1个大写字母+1个小写字母+1个数字");
        putDefault("auto_login_ip",
                "§a§l[Sdf1_login] §f检测到同IP近期登录，已自动登录！欢迎回来 {user}");
        putDefault("email_verify_sent",
                "§a§l[Sdf1_login] §f验证码已发送到 {email}，请输入6位验证码：");
        putDefault("email_verify_success",
                "§a§l[Sdf1_login] §f邮箱验证成功，已绑定！");
        putDefault("email_verify_failed",
                "§c§l[Sdf1_login] §f验证码错误，请重试");
        putDefault("smtp_verify_sent",
                "§a§l[Sdf1_login] §f验证码已发送到 {email}，请输入6位验证码：");
        putDefault("smtp_verify_success",
                "§a§l[Sdf1_login] §fSMTP配置验证成功！");
        putDefault("smtp_verify_failed",
                "§c§l[Sdf1_login] §f验证码错误，请重试");
        putDefault("login_timeout",
                "§c§l[Sdf1_login] §f登录超时，已被踢出");
        putDefault("login_required",
                "§c§l[Sdf1_login] §f你还没有登录，请先 /login <密码>");
        putDefault("reset_no_email",
                "§c§l[Sdf1_login] §f你还没有绑定邮箱，无法通过邮件重置密码");
        putDefault("reset_sent",
                "§a§l[Sdf1_login] §f临时密码已发送到你的邮箱，请查收");
        putDefault("reset_temp_used",
                "§a§l[Sdf1_login] §f临时密码已生效，旧密码已失效");
        putDefault("reset_failed",
                "§c§l[Sdf1_login] §f重置失败，请联系管理员");
        putDefault("email_set",
                "§a§l[Sdf1_login] §f邮箱已设置为: {email}");
        putDefault("email_invalid",
                "§c§l[Sdf1_login] §f邮箱格式不正确");
        putDefault("checkin_success",
                "§a§l[Sdf1_login] §f签到成功！获得 {points} 积分（连续{streak}天，倍率x{multi}）");
        putDefault("checkin_already",
                "§c§l[Sdf1_login] §f今天已经签到过了");
        putDefault("backcheckin_success",
                "§a§l[Sdf1_login] §f补签成功！消耗10积分");
        putDefault("backcheckin_no_point",
                "§c§l[Sdf1_login] §f积分不足，补签需要10积分");
        putDefault("backcheckin_expired",
                "§c§l[Sdf1_login] §f只能补签3天内的签到");
        putDefault("points_insufficient",
                "§c§l[Sdf1_login] §f积分不足！当前: {points}");
        putDefault("points_purchase_success",
                "§a§l[Sdf1_login] §f兑换成功！消耗 {count} 积分");
        putDefault("invite_code_generated",
                "§a§l[Sdf1_login] §f你的邀请码: {code}");
        putDefault("invite_bound",
                "§a§l[Sdf1_login] §f你已被 {user} 邀请！");
        putDefault("invite_referral_bonus",
                "§a§l[Sdf1_login] §f邀请返点: +{points} 积分");
        putDefault("gift_claimed",
                "§a§l[Sdf1_login] §f礼包领取成功！");
        putDefault("gift_not_ready",
                "§c§l[Sdf1_login] §f礼包条件未满足");
        putDefault("gift_all_claimed",
                "§a§l[Sdf1_login] §f所有礼包已领取完毕！");
        putDefault("admin_no_permission",
                "§c§l[Sdf1_login] §f无权限");
        putDefault("admin_set_password",
                "§a§l[Sdf1_login] §f已为 {user} 设置新密码");
        putDefault("admin_delete_confirm",
                "§c§l[Sdf1_login] §f请输入要删除的玩家名和你的密码进行确认");
        putDefault("admin_delete_success",
                "§a§l[Sdf1_login] §f玩家 {user} 已删除");
        putDefault("admin_delete_failed",
                "§c§l[Sdf1_login] §f删除失败：验证不通过");
        putDefault("admin_points_set",
                "§a§l[Sdf1_login] §f已设置 {user} 积分为 {points}");
        putDefault("afk_warning",
                "§e§l[Sdf1_login] §f你已挂机 {duration}，将在 {remain} 后被踢出");
        putDefault("afk_kicked",
                "§c§l[Sdf1_login] §f因挂机过久被踢出");
        putDefault("afk_set_time",
                "§a§l[Sdf1_login] §f挂机踢出时长已设为 {duration}");
        putDefault("afk_set_enabled",
                "§a§l[Sdf1_login] §f挂机踢出已开启");
        putDefault("afk_set_disabled",
                "§a§l[Sdf1_login] §f挂机踢出已关闭");
        putDefault("afk_whitelist_added",
                "§a§l[Sdf1_login] §f已将 {user} 加入防踢白名单");
        putDefault("afk_whitelist_removed",
                "§a§l[Sdf1_login] §f已将 {user} 移出防踢白名单");
    }

    private void putDefault(String key, String value) {
        messages.putIfAbsent(key, value);
    }


    public String msg(String key, Object... kvPairs) {
        String template = messages.getOrDefault(key, key);
        for (int i = 0; i < kvPairs.length - 1; i += 2) {
            String varName = kvPairs[i].toString();
            String value = kvPairs[i + 1] != null
                    ? kvPairs[i + 1].toString() : "";
            String[] open  = {"{", "[", "(", "【", "（", "《", "<"};
            String[] close = {"}", "]", ")", "】", "）", "》", ">"};
            for (int j = 0; j < open.length; j++) {
                template = template.replace(
                        open[j] + varName + close[j], value);
            }
        }
        template = template.replace("\\n", "\n");
        template = template.replace("<br>", "\n");
        return template;
    }

    private void writeDefaultMessages() {
        File f = new File(dataFolder, "设置.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# ==========================================");
            pw.println("#        Sdf1_login 消息提示配置");
            pw.println("# ==========================================");
            pw.println("# 变量支持: {变量} [变量] (变量) 【变量】");
            pw.println("# 可用变量: [user] [duration] [count]");
            pw.println("#           [code] [points] [stage]");
            pw.println("#           [email] [url] [reason]");
            pw.println();
            pw.println("# ===== 登录系统 =====");
            pw.println("not_registered=§c§l[Sdf1_login] §f你还没有注册，请使用 /reg <密码> 注册");
            pw.println("not_logged_in=§c§l[Sdf1_login] §f请先使用 /login <密码> 登录");
            pw.println("login_success=§a§l[Sdf1_login] §f登录成功！欢迎回来 [user]");
            pw.println("register_success=§a§l[Sdf1_login] §f注册成功！请使用 /login <密码> 登录");
            pw.println("login_failed=§c§l[Sdf1_login] §f密码错误！");
            pw.println("already_registered=§c§l[Sdf1_login] §f你已经注册过了");
            pw.println("already_logged_in=§a§l[Sdf1_login] §f你已经登录了");
            pw.println("password_invalid=§c§l[Sdf1_login] §f密码不符合要求！需要6-20位，包含大写字母+小写字母+数字");
            pw.println("password_format_hint=§7密码要求: 6-20位，至少1个大写字母+1个小写字母+1个数字");
            pw.println();
            pw.println("# ===== IP自动登录 =====");
            pw.println("auto_login_ip=§a§l[Sdf1_login] §f检测到同IP近期登录，已自动登录！欢迎回来 [user]");
            pw.println();
            pw.println("# ===== 邮箱验证 =====");
            pw.println("email_verify_sent=§a§l[Sdf1_login] §f验证码已发送到 [email]，请输入6位验证码：");
            pw.println("email_verify_success=§a§l[Sdf1_login] §f邮箱验证成功，已绑定！");
            pw.println("email_verify_failed=§c§l[Sdf1_login] §f验证码错误，请重试");
            pw.println();
            pw.println("# ===== SMTP验证 =====");
            pw.println("smtp_verify_sent=§a§l[Sdf1_login] §f验证码已发送到 [email]，请输入6位验证码：");
            pw.println("smtp_verify_success=§a§l[Sdf1_login] §fSMTP配置验证成功！");
            pw.println("smtp_verify_failed=§c§l[Sdf1_login] §f验证码错误，请重试");
            pw.println("login_timeout=§c§l[Sdf1_login] §f登录超时，已被踢出");
            pw.println("login_required=§c§l[Sdf1_login] §f你还没有登录，请先 /login <密码>");
            pw.println();
            pw.println("# ===== 密码重置 =====");
            pw.println("reset_no_email=§c§l[Sdf1_login] §f你还没有绑定邮箱，无法通过邮件重置密码");
            pw.println("reset_sent=§a§l[Sdf1_login] §f临时密码已发送到你的邮箱，请查收");
            pw.println("reset_temp_used=§a§l[Sdf1_login] §f临时密码已生效，旧密码已失效");
            pw.println("reset_failed=§c§l[Sdf1_login] §f重置失败，请联系管理员");
            pw.println();
            pw.println("# ===== 邮箱 =====");
            pw.println("email_set=§a§l[Sdf1_login] §f邮箱已设置为: [email]");
            pw.println("email_invalid=§c§l[Sdf1_login] §f邮箱格式不正确");
            pw.println();
            pw.println("# ===== 签到 =====");
            pw.println("checkin_success=§a§l[Sdf1_login] §f签到成功！获得 [points] 积分（连续[streak]天，倍率x[multi]）");
            pw.println("checkin_already=§c§l[Sdf1_login] §f今天已经签到过了");
            pw.println("backcheckin_success=§a§l[Sdf1_login] §f补签成功！消耗10积分");
            pw.println("backcheckin_no_point=§c§l[Sdf1_login] §f积分不足，补签需要10积分");
            pw.println("backcheckin_expired=§c§l[Sdf1_login] §f只能补签3天内的签到");
            pw.println();
            pw.println("# ===== 积分 =====");
            pw.println("points_insufficient=§c§l[Sdf1_login] §f积分不足！当前: [points]");
            pw.println("points_purchase_success=§a§l[Sdf1_login] §f兑换成功！消耗 [count] 积分");
            pw.println();
            pw.println("# ===== 邀请 =====");
            pw.println("invite_code_generated=§a§l[Sdf1_login] §f你的邀请码: [code]");
            pw.println("invite_bound=§a§l[Sdf1_login] §f你已被 [user] 邀请！");
            pw.println("invite_referral_bonus=§a§l[Sdf1_login] §f邀请返点: +[points] 积分");
            pw.println();
            pw.println("# ===== 新人礼包 =====");
            pw.println("gift_claimed=§a§l[Sdf1_login] §f礼包领取成功！");
            pw.println("gift_not_ready=§c§l[Sdf1_login] §f礼包条件未满足");
            pw.println("gift_all_claimed=§a§l[Sdf1_login] §f所有礼包已领取完毕！");
            pw.println();
            pw.println("# ===== 管理员 =====");
            pw.println("admin_no_permission=§c§l[Sdf1_login] §f无权限");
            pw.println("admin_set_password=§a§l[Sdf1_login] §f已为 [user] 设置新密码");
            pw.println("admin_delete_confirm=§c§l[Sdf1_login] §f请输入要删除的玩家名和你的密码进行确认");
            pw.println("admin_delete_success=§a§l[Sdf1_login] §f玩家 [user] 已删除");
            pw.println("admin_delete_failed=§c§l[Sdf1_login] §f删除失败：验证不通过");
            pw.println("admin_points_set=§a§l[Sdf1_login] §f已设置 [user] 积分为 [points]");
            pw.println("管理密码=qweasd");
            pw.println();
            pw.println("# ===== 挂机踢出 =====");
            pw.println("afk_warning=§e§l[Sdf1_login] §f你已挂机 [duration]，将在 [remain] 后被踢出");
            pw.println("afk_kicked=§c§l[Sdf1_login] §f因挂机过久被踢出");
            pw.println("afk_set_time=§a§l[Sdf1_login] §f挂机踢出时长已设为 [duration]");
            pw.println("afk_set_enabled=§a§l[Sdf1_login] §f挂机踢出已开启");
            pw.println("afk_set_disabled=§a§l[Sdf1_login] §f挂机踢出已关闭");
            pw.println("afk_whitelist_added=§a§l[Sdf1_login] §f已将 [user] 加入防踢白名单");
            pw.println("afk_whitelist_removed=§a§l[Sdf1_login] §f已将 [user] 移出防踢白名单");
            pw.println();
            pw.println("# ===== 帮助 =====");
            pw.println("help_header=§e[Sdf1_login] §f命令列表:");
            pw.println("help_1=§a/sdf1_login §7- 打开面板");
            pw.println("help_2=§a/reg <密码> §7- 注册");
            pw.println("help_3=§a/login <密码> §7- 登录");
            pw.println("help_4=§a/sdf1_login reset §7- 重置密码");
            pw.println("help_5=§a/sdf1_login email <邮箱> §7- 绑定邮箱");
            pw.println("help_6=§a/sdf1_login set <玩家> <密码> §7- [管理员] 设置密码");
            pw.println("help_7=§a/sdf1_login del <玩家> §7- [管理员] 删除用户");
            pw.println("help_8=§a/sdf1_login kick on/off [分钟] §7- [管理员] 挂机设置");
            pw.println("help_9=§a/sdf1_login add/remove <玩家> §7- [管理员] 防踢白名单");
            pw.println("help_10=§a/sdf1_login get <玩家> §7- [管理员] 查看礼包进度");
            pw.println("help_11=§a/sdf1_login reload §7- [管理员] 重载配置");
        } catch (IOException ignored) {}
    }

    // ===== SMTP配置 =====

    public void loadSmtp() {
        smtp.clear();
        File f = new File(dataFolder, "smtp.txt");
        if (!f.exists()) writeDefaultSmtp();
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF"))
                    line = line.substring(1);
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq < 0) continue;
                smtp.put(t.substring(0, eq).trim(),
                        t.substring(eq + 1).trim());
            }
            r.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void writeDefaultSmtp() {
        File f = new File(dataFolder, "smtp.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# ==========================================");
            pw.println("#        Sdf1_login SMTP邮件配置");
            pw.println("# ==========================================");
            pw.println();
            pw.println("# SMTP服务器地址");
            pw.println("smtp地址=smtp.qq.com");
            pw.println();
            pw.println("# 端口号（SSL默认465）");
            pw.println("smtp端口=465");
            pw.println();
            pw.println("# 发件人邮箱地址");
            pw.println("发件邮箱=your@email.com");
            pw.println();
            pw.println("# 邮箱授权码（非登录密码）");
            pw.println("授权码=");
            pw.println();
            pw.println("# 测试接收邮箱（留空则发给发件人）");
            pw.println("测试邮箱=");
        } catch (IOException ignored) {}
    }

    public String getSmtp(String key) {
        return smtp.getOrDefault(key, "");
    }

    public void setSmtp(String key, String value) {
        smtp.put(key, value);
        saveSmtp();
    }

    public Map<String, String> getSmtpAll() {
        return new LinkedHashMap<>(smtp);
    }
    public void saveSettings() {
        File f = new File(dataFolder, "url.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("管理标签: " + adminTag);
            pw.println("管理密码: " + adminPassword);
            pw.println("挂机踢出: "
                    + (afkEnabled ? "true" : "false"));
            pw.println("挂机时长: " + (afkTimeout / 60));
            pw.println("登录超时: " + loginTimeout);
        } catch (IOException ignored) {}
    }

    private void saveSmtp() {
        File f = new File(dataFolder, "smtp.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# ==========================================");
            pw.println("#        Sdf1_login SMTP邮件配置");
            pw.println("# ==========================================");
            pw.println();
            pw.println("smtp地址=" + smtp.getOrDefault("smtp地址", "smtp.qq.com"));
            pw.println("smtp端口=" + smtp.getOrDefault("smtp端口", "465"));
            pw.println("发件邮箱=" + smtp.getOrDefault("发件邮箱", ""));
            pw.println("授权码=" + smtp.getOrDefault("授权码", ""));
            pw.println("测试邮箱=" + smtp.getOrDefault("测试邮箱", ""));
        } catch (IOException ignored) {}
    }

    // ===== 其他配置 =====

    public void loadSettings() {
        File f = new File(dataFolder, "url.txt");
        if (!f.exists()) return;
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] kv = t.split(":", 2);
                if (kv.length < 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                switch (k) {
                    case "管理标签":
                        adminTag = v; break;
                    case "管理密码":
                        adminPassword = v; break;
                    case "挂机踢出":
                        afkEnabled = v.equalsIgnoreCase("true")
                                || v.equalsIgnoreCase("开启");
                        break;
                    case "挂机时长":
                        try {
                            afkTimeout = Integer.parseInt(v) * 60;
                        } catch (Exception ignored) {}
                        break;
                    case "登录超时":
                        try {
                            loginTimeout = Integer.parseInt(v);
                        } catch (Exception ignored) {}
                        break;
                }

            }
            r.close();
        } catch (Exception ignored) {}
    }
}
