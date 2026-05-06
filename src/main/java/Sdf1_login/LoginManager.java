package Sdf1_login;

import org.bukkit.entity.Player;

public class LoginManager {

    private final Main plugin;

    public LoginManager(Main plugin) {
        this.plugin = plugin;
    }

    public boolean handleRegister(Player p,
                                  String[] args) {
        String name = p.getName();
        if (plugin.getDb().userExists(name)) {
            p.sendMessage(plugin.getConfig2().msg(
                    "already_registered"));
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("§c用法: /reg <密码>");
            p.sendMessage(plugin.getConfig2().msg(
                    "password_format_hint"));
            return true;
        }
        String pwd = args[0];
        if (!PasswordUtils.validate(pwd)) {
            p.sendMessage(plugin.getConfig2().msg(
                    "password_invalid"));
            p.sendMessage(plugin.getConfig2().msg(
                    "password_format_hint"));
            return true;
        }
        String salt = PasswordUtils.generateSalt();
        String hash = PasswordUtils.hash(pwd, salt);
        plugin.getDb().createUser(name, hash, salt);
        plugin.getLoggedIn().add(name);
        plugin.getDb().setLoggedIn(name, true);
        plugin.getDb().setField(name,
                "last_login_time",
                System.currentTimeMillis());
        plugin.getDb().setField(name,
                "last_online_check",
                System.currentTimeMillis());
        plugin.restoreInventory(p);
        plugin.recordIPLogin(p);
        p.sendMessage(plugin.getConfig2().msg(
                "register_success", "user", name));
        try {
            org.bukkit.plugin.Plugin cy =
                    org.bukkit.Bukkit
                            .getPluginManager()
                            .getPlugin("CY_beibao");
            if (cy != null && cy.isEnabled()) {
                java.lang.reflect.Method m =
                        cy.getClass().getMethod(
                                "onSdf1Activation",
                                String.class,
                                int.class,
                                int.class);
                m.invoke(cy, name, 9, 0);
            }
        } catch (Exception ignored) {}
        return true;
    }

    public boolean handleLogin(Player p,
                               String[] args) {
        String name = p.getName();
        if (plugin.getLoggedIn().contains(name)) {
            p.sendMessage(plugin.getConfig2().msg(
                    "already_logged_in"));
            return true;
        }
        if (!plugin.getDb().userExists(name)) {
            p.sendMessage(plugin.getConfig2().msg(
                    "not_registered"));
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
                    "§c[Sdf1_login] §f数据异常，请联系管理员");
            return true;
        }
        String hash = PasswordUtils.hash(pwd, salt);
        if (plugin.getDb().checkPasswordOrTemp(
                name, hash)) {
            plugin.getLoggedIn().add(name);
            plugin.getDb().setLoggedIn(name, true);
            plugin.getDb().setField(name,
                    "last_login_time",
                    System.currentTimeMillis());
            plugin.getDb().setField(name,
                    "last_online_check",
                    System.currentTimeMillis());
            plugin.restoreInventory(p);
            plugin.recordIPLogin(p);
            p.sendMessage(plugin.getConfig2().msg(
                    "login_success", "user", name));
            if (!plugin.getDb().checkPassword(
                    name, hash)) {
                plugin.getNeedsPasswordChange()
                        .add(name);
                p.sendMessage(
                        "§c§l[警告] §f您使用的是临时密码，请尽快修改密码！");
                p.sendMessage(
                        "§7用法: /sdf1_login pw <旧密码> <新密码> <确认新密码>");
            }
            try {
                org.bukkit.plugin.Plugin cy =
                        org.bukkit.Bukkit
                                .getPluginManager()
                                .getPlugin(
                                        "CY_beibao");
                if (cy != null && cy.isEnabled()) {
                    java.lang.reflect.Method m =
                            cy.getClass().getMethod(
                                    "onSdf1Activation",
                                    String.class,
                                    int.class,
                                    int.class);
                    m.invoke(cy, name, 0, 0);
                }
            } catch (Exception ignored) {}
        } else {
            p.sendMessage(plugin.getConfig2().msg(
                    "login_failed"));
        }
        return true;
    }

    public boolean handleReset(Player p) {
        String name = p.getName();
        String email = (String) plugin.getDb()
                .getField(name, "email");
        if (email == null || email.isEmpty()) {
            p.sendMessage(plugin.getConfig2().msg(
                    "reset_no_email"));
            return true;
        }
        String tempPwd =
                PasswordUtils.generateTempPassword();
        String mainSalt = (String) plugin.getDb()
                .getField(name, "password_salt");
        String hash =
                PasswordUtils.hash(tempPwd, mainSalt);
        plugin.getDb().setField(name,
                "temp_password", hash);
        plugin.getDb().setField(name,
                "temp_pw_expire",
                System.currentTimeMillis()
                        + 300000L);
        plugin.getEmail().sendTempPassword(
                email, name, tempPwd);
        p.sendMessage(plugin.getConfig2().msg(
                "reset_sent"));
        return true;
    }
}
