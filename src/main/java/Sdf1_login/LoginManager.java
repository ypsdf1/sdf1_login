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
        // ★ 记录Java手动登录：5分钟内重连可直接放行（检查点1）
        if (plugin.webManager != null) {
            plugin.webManager.recordJavaLogin(name, plugin.getPlayerIP(p));
        }
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
        // ★ 注册成功：actionbar 提示1分钟保存刚才的账号和密码
        plugin.showCredentialSaveReminder(p, name, pwd);

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

        // ★ 注册后立即同步用户数据到PHP
        if (plugin.webManager != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try { plugin.webManager.syncUserRegistrations(); }
                catch (Exception e) {
                    plugin.getLogger().warning("[Web通信] 注册后同步用户异常: " + e.getMessage());
                }
            });
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

            // ★ 风控检查：封禁期内拒绝登录
            if (plugin.getRiskControl().isBanned(name)) {
                int remaining = plugin.getRiskControl()
                        .getBanRemainingSeconds(name);
                p.sendMessage("§c§l[安全风控] §f账号因密码错误过多已被封禁，"
                        + "剩余 §e" + remaining + " §f秒");
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
                // ★ IP风控双验证第一步：只认密码，不解冻、不恢复背包
                if (markDualStep1(p, false)) return true;
                finishPasswordLogin(p, false);
                return true;
            }

            String pwdResult =
                    plugin.getDb()
                            .checkPasswordWithFallback(
                                    name, hash);
            if (pwdResult != null) {
                // ★ IP风控双验证第一步：只认密码，不解冻、不恢复背包
                if (markDualStep1(p, true)) return true;
                finishPasswordLogin(p, true);
                return true;
            }


            // ★ 密码错误，记录风控
            int remaining = plugin.getRiskControl()
                    .recordFailAttempt(p);
            if (remaining < 0) {
                // 已被封禁（triggerBan内部已踢出）
                return true;
            }
            p.sendMessage(plugin.getConfig2()
                    .msg("login_failed"));
            if (remaining > 0) {
                p.sendMessage("§c§l[风控] §f剩余 §e"
                        + remaining + " §f次尝试机会");
            }
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


    /**
     * IP风控「密码 + 2FA」顺序两关的第一步。
     * 密码正确只记录进度：<b>不加入 loggedIn、不恢复背包</b>，必须等第二步 /2fa code 通过才收口。
     *
     * @return true = 该玩家处于双验证流程，本轮 /login 到此为止
     */
    private boolean markDualStep1(Player p, boolean tempPassword) {
        TwoFactorManager twofa = plugin.getTwofa();
        String name = p.getName();
        if (twofa == null || !twofa.isDualVerify(p)) return false;
        twofa.onDualPasswordPassed(p, tempPassword);
        // 密码这一步确实对了 → 重置「密码错误次数」风控（不代表已登录，仍不解冻）
        plugin.getRiskControl().onLoginSuccess(name);
        p.sendMessage("§a§l[双重验证] §a第一步密码验证通过（尚未解冻）");
        p.sendMessage("§7第二步: 执行 §f/2fa code <动态码|恢复代码>");
        p.sendMessage("§c两步全部通过后才会解冻并恢复背包");
        return true;
    }

    /**
     * 密码登录成功的统一收口：解冻 + 恢复背包 + 记录登录信息。
     * 调用点：/login 主密码、/login 临时密码、IP风控双验证第二步通过后。
     */
    public void finishPasswordLogin(Player p, boolean tempPassword) {
        String name = p.getName();
        // ★ 登录成功，重置风控
        plugin.getRiskControl().onLoginSuccess(name);
        plugin.getLoggedIn().add(name);
        // ★ 记录Java手动登录：5分钟内重连可直接放行（检查点1）
        if (plugin.webManager != null) {
            plugin.webManager.recordJavaLogin(name, plugin.getPlayerIP(p));
        }
        plugin.getDb().setLoggedIn(name, true);
        if (!tempPassword) {
            // 主密码路径保留原有的重复写入（行为与历史版本一致）
            plugin.getDb().setLoggedIn(name, true);
        }
        plugin.getDb().setField(name, "last_login_time",
                System.currentTimeMillis());
        plugin.getDb().setField(name, "last_online_check",
                System.currentTimeMillis());
        // ★ 立即同步在线状态到PHP（异步，避免阻塞主线程）
        if (!tempPassword && plugin.webManager != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.webManager.syncOnlinePlayers();
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web通信] 异步syncOnlinePlayers异常: " + e.getMessage());
                }
            });
        }
        // 登录成功关闭飞行
        p.setAllowFlight(false);
        p.setFlying(false);
        // 只在有有效备份时恢复
        plugin.restoreInventory(p);
        plugin.recordIPLogin(p);
        plugin.giveMenuSnowball(p);
        if (tempPassword) {
            // ★ 确保这行存在
            plugin.getNeedsPasswordChange().add(name);
        }
        p.sendMessage(plugin.getConfig2().msg("login_success"));
        if (tempPassword) {
            p.sendMessage("§c§l[警告] §f您使用的是临时密码，请尽快修改密码！");
            p.sendMessage("§7用法: /sdf1_login pw");
        }
        activateCY(p, name);
        // ★ 登录后恢复区域效果
        Sdf1_login.AreaProtection areaProt =
                plugin.getAreaProtection();
        if (areaProt != null) {
            areaProt.onPlayerJoin(p);
        }
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
