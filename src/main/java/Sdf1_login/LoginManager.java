package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class LoginManager {

    private final Main plugin;

    public LoginManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean handleRegister(Player p,
                                  String[] args) {
        String name = p.getName();

        // 已注册玩家不能再注册
        if (plugin.getDb().userExists(name)) {
            p.sendMessage(plugin.getConfig2()
                    .msg("already_registered"));
            return true;
        }

        // IP注册限制检查
        String ip = plugin.getPlayerIP(p);
        if (ip != null && plugin.getConfig2()
                .maxAccountsPerIP > 0) {
            if (!plugin.getIPGroup()
                    .canRegister(ip)) {
                int count = plugin.getIPGroup()
                        .getAccountCount(ip);
                int max = plugin.getConfig2()
                        .maxAccountsPerIP;
                p.sendMessage("§c该IP注册已达上限（"
                        + count + "/" + max + "）");
                return true;
            }
        }

        if (args.length < 1) {
            p.sendMessage("§c用法: /reg <密码>");
            p.sendMessage("§7密码要求: 6位以上，"
                    + "包含大小写字母和数字");
            return true;
        }

        String pwd = args[0];
        if (pwd.length() < 6) {
            p.sendMessage("§c密码长度不足6位");
            return true;
        }
        if (!pwd.matches(".*[a-z].*")
                || !pwd.matches(".*[A-Z].*")
                || !pwd.matches(".*[0-9].*")) {
            p.sendMessage("§c密码必须包含大写字母、"
                    + "小写字母和数字");
            return true;
        }

        String salt =
                PasswordUtils.generateSalt();
        String hash =
                PasswordUtils.hash(pwd, salt);
        plugin.getDb().createUser(
                name, hash, salt);
        plugin.getLoggedIn().add(name);
        p.setAllowFlight(false);
        p.setFlying(false);
        plugin.getDb().setLoggedIn(name, true);
        plugin.getDb().setField(name,
                "last_login_time",
                System.currentTimeMillis());
        plugin.getDb().setField(name,
                "last_online_check",
                System.currentTimeMillis());
        plugin.getDb().recordIP(name, ip);
        plugin.getDb().setField(name,
                "register_time",
                System.currentTimeMillis());
        plugin.restoreInventory(p);
        plugin.recordIPLogin(p);
        plugin.giveMenuSnowball(p);

        p.sendMessage(plugin.getConfig2()
                .msg("reg_success"));
        playRegisterSound(p);

        try {
            org.bukkit.plugin.Plugin cy =
                    Bukkit.getPluginManager()
                            .getPlugin("CY_beibao");
            if (cy != null && cy.isEnabled()) {
                cy.getClass().getMethod(
                                "onSdf1Activation",
                                String.class,
                                int.class, int.class)
                        .invoke(cy, name, 9, 0);
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private void playRegisterSound(Player p) {
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_CHIME,
                1.0f, 0.79f);
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_BASS,
                1.0f, 0.79f);
        p.playSound(p.getLocation(),
                Sound.BLOCK_NOTE_BLOCK_PLING,
                1.0f, 0.79f);
    }


    public boolean handleLogin(Player p,
                               String[] args) {

        String name = p.getName();
        // ★ 在 handleLogin 方法开头
        if (plugin.getNeedsPasswordChange().contains(name)
                && plugin.getLoggedIn().contains(name)) {
            // 已登录但还没改密码，只允许执行 /sdf1_login pw
            p.sendMessage("§c请先修改密码: /sdf1_login pw <旧密码> <新密码>");
            return true;
        }

        try {
            if (plugin.getLoggedIn().contains(name)) {
                p.sendMessage(plugin.getConfig2()
                        .msg("already_logged_in"));
                return true;
            }
            if (!plugin.getDb().userExists(name)) {
                p.sendMessage(plugin.getConfig2()
                        .msg("not_registered"));
                return true;
            }
            if (args.length < 1) {
                p.sendMessage("§c用法: /login <密码>");
                return true;
            }
            String pwd = args[0];
            String salt = (String) plugin.getDb()
                    .getField(name, "password_salt");
            if (salt == null) {
                p.sendMessage(
                        "§c数据异常，请联系管理员");
                plugin.getLogger().severe(
                        "[Sdf1_login] 玩家 " + name
                                + " 盐值为null");
                return true;
            }
            String hash =
                    PasswordUtils.hash(pwd, salt);

            boolean matchMain =
                    plugin.getDb().checkPassword(
                            name, hash);
            if (matchMain) {
                plugin.getLoggedIn().add(name);
                plugin.getDb().setLoggedIn(
                        name, true);
                plugin.getDb().setLoggedIn(
                        name, true);
                plugin.getDb().setField(name,
                        "last_login_time",
                        System.currentTimeMillis());
                plugin.getDb().setField(name,
                        "last_online_check",
                        System.currentTimeMillis());
                // 只在有有效备份时恢复
                plugin.restoreInventory(p);
                plugin.recordIPLogin(p);
                plugin.giveMenuSnowball(p);
                p.sendMessage(plugin.getConfig2()
                        .msg("login_success"));
                activateCY(p, name);
                return true;
            }

            boolean matchTemp =
                    plugin.getDb()
                            .checkPasswordOrTemp(
                                    name, hash);
            if (matchTemp) {
                plugin.getLoggedIn().add(name);
                plugin.getDb().setLoggedIn(name, true);
                p.setAllowFlight(false);
                p.setFlying(false);
                plugin.getDb().setField(name, "last_login_time",
                        System.currentTimeMillis());
                plugin.getDb().setField(name, "last_online_check",
                        System.currentTimeMillis());
                plugin.restoreInventory(p);
                plugin.recordIPLogin(p);
                plugin.giveMenuSnowball(p);
                // ★ 确保这行存在
                plugin.getNeedsPasswordChange().add(name);
                p.sendMessage(plugin.getConfig2().msg("login_success"));
                p.sendMessage("§c§l[警告] §f您使用的是临时密码，请尽快修改密码！");
                p.sendMessage("§7用法: /sdf1_login pw");
                activateCY(p, name);
                return true;
            }


            p.sendMessage(plugin.getConfig2()
                    .msg("login_failed"));
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Sdf1_login] handleLogin异常: "
                            + e.getMessage());
            e.printStackTrace();
            p.sendMessage(
                    "§c登录过程出错，请联系管理员");
        }
        return true;
    }


    public void handleReset(Player p) {
        String name = p.getName();
        String emailAddr = (String)
                plugin.getDb().getField(
                        name, "email");
        if (emailAddr == null
                || emailAddr.isEmpty()) {
            p.sendMessage(
                    "§c未设置邮箱，无法重置密码");
            return;
        }
        String tempPwd = PasswordUtils
                .generateTempPassword();
        String salt = (String)
                plugin.getDb().getField(
                        name, "password_salt");
        String hash = PasswordUtils.hash(
                tempPwd, salt);
        plugin.getDb().setField(name,
                "temp_password", hash);
            plugin.getDb().setField(name,
                    "temp_pw_expire",
                    System.currentTimeMillis()
                            + 300000L);

            plugin.getDb().setField(name,
                "temp_pw_used", 0);
        p.sendMessage("§e正在发送临时密码...");
        final String to = emailAddr;
        final String fName = name;
        final String pwd = tempPwd;
        final Main self = plugin;
        Bukkit.getScheduler()
                .runTaskAsynchronously(self,
                        () -> {
                            final boolean[] sent = {false};
                            Thread t = new Thread(() -> {
                                sent[0] = self.getEmail()
                                        .sendTempPassword(
                                                to, fName, pwd);
                            }, "Sdf1_login-reset");
                            t.setDaemon(true);
                            t.start();
                            try {
                                t.join(15000);
                            } catch (
                                    InterruptedException ignored) {
                            }
                            if (t.isAlive()) {
                                t.interrupt();
                                Bukkit.getScheduler().runTask(
                                        self, () ->
                                                p.sendMessage(
                                                        "§c邮件发送超时"));
                                return;
                            }
                            Bukkit.getScheduler().runTask(
                                    self, () -> {
                                        if (sent[0]) {
                                            p.sendMessage(
                                                    "§a临时密码已发送到 "
                                                            + to);
                                        } else {
                                            p.sendMessage(
                                                    "§c邮件发送失败");
                                        }
                                    });
                        });
    }

    private void activateCY(Player p,
                            String name) {
        try {
            org.bukkit.plugin.Plugin cy =
                    Bukkit.getPluginManager()
                            .getPlugin("CY_beibao");
            if (cy != null && cy.isEnabled()) {
                cy.getClass().getMethod(
                                "onSdf1Activation",
                                String.class,
                                int.class, int.class)
                        .invoke(cy, name, 0, 0);
            }
        } catch (Exception ignored) {
        }
    }
}
