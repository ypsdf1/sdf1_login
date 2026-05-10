package Sdf1_login;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import org.bukkit.event.player.PlayerInteractEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.scheduler.BukkitRunnable;

public class Main extends JavaPlugin
        implements CommandExecutor, Listener,
        TabCompleter {

    private DatabaseManager db;
    private ConfigManager config;
    private EmailManager email;
    private LoginManager loginMgr;
    private AFKManager afk;
    private GiftManager gift;
    private CheckInManager checkIn;
    private InviteManager invite;
    private PointsManager points;
    private GUIManager gui;
    private ChatInputManager chatInput;
    private Economy economy;
    private VerificationManager verification;
    private IPGroupManager ipGroup;
    private AccountRequestManager accountRequest;
    private TicketManager ticket;
    private ChatFilterManager chatFilter;
    private GarbageManager garbage;

    private final Set<String> loggedIn =
            new TreeSet<>(
                    String.CASE_INSENSITIVE_ORDER);
    private final Set<String> needsPasswordChange =
            new TreeSet<>(
                    String.CASE_INSENSITIVE_ORDER);
    private final Map<UUID, Long> joinTime =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> joinLoc =
            new HashMap<>();
    private final Map<String, Long> ipAutoLogin =
            new HashMap<>();
    private static final long IP_SKIP_MS = 300000L;

    private final Map<UUID, Long> lastActivity =
            new ConcurrentHashMap<>();

    private static class PwdRollback {
        final String hash;
        final String salt;
        final long time;
        PwdRollback(String h, String s, long t) {
            hash = h;
            salt = s;
            time = t;
        }
    }

    private final Map<String, PwdRollback>
            pwdRollback =
            new ConcurrentHashMap<>();

    public void recordPasswordChange(String name,
                                     String oldHash, String oldSalt) {
        pwdRollback.put(name,
                new PwdRollback(oldHash, oldSalt,
                        System.currentTimeMillis()));
    }

    // ===== Getters =====
    public DatabaseManager getDb() { return db; }
    public ConfigManager getConfig2() { return config; }
    public EmailManager getEmail() { return email; }
    public Economy getEconomy() { return economy; }
    public LoginManager getLoginMgr() { return loginMgr; }
    public AFKManager getAfk() { return afk; }
    public GiftManager getGift() { return gift; }
    public CheckInManager getCheckIn() { return checkIn; }
    public InviteManager getInvite() { return invite; }
    public PointsManager getPoints() { return points; }
    public GUIManager getGui() { return gui; }
    public ChatInputManager getChatInput() { return chatInput; }
    public VerificationManager getVerification() { return verification; }
    public IPGroupManager getIPGroup() { return ipGroup; }
    public AccountRequestManager getAccountRequest() { return accountRequest; }
    public TicketManager getTicket() { return ticket; }
    public ChatFilterManager getChatFilter() { return chatFilter; }
    public GarbageManager getGarbage() { return garbage; }
    public Set<String> getLoggedIn() { return loggedIn; }
    public Set<String> getNeedsPasswordChange() { return needsPasswordChange; }

    // ===== Enable/Disable =====
    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        config = new ConfigManager(getDataFolder());
        config.loadMessages();
        config.loadSmtp();
        config.loadSettings();
        getCommand("oa").setExecutor(this);

        db = new DatabaseManager(getDataFolder());
        db.init();
        email = new EmailManager(config);
        loginMgr = new LoginManager(this);
        afk = new AFKManager(config, this);
        gift = new GiftManager(this);
        gift.loadStages();
        checkIn = new CheckInManager(this);
        invite = new InviteManager(this);
        points = new PointsManager(this);
        gui = new GUIManager(this);
        chatInput = new ChatInputManager();
        verification = new VerificationManager(this);
        ipGroup = new IPGroupManager(this,
                config.maxAccountsPerIP);
        accountRequest =
                new AccountRequestManager(this);
        accountRequest.setMode(config.approvalMode);
        accountRequest.setAutoDelayMinutes(
                config.autoApproveDelayMinutes);
        ticket = new TicketManager(this);
        garbage = new GarbageManager(this);
        garbage.init();
        garbage.loadConfig();
        garbage.startAutoCleanup();
        chatFilter = new ChatFilterManager(this);
        chatFilter.loadConfig();

        setupEconomy();

        if (getCommand("sdf1_login") != null) {
            getCommand("sdf1_login").setExecutor(this);
            getCommand("sdf1_login").setTabCompleter(this);
        }
        if (getCommand("reg") != null)
            getCommand("reg").setExecutor(this);
        if (getCommand("login") != null)
            getCommand("login").setExecutor(this);
        if (getCommand("l") != null)
            getCommand("l").setExecutor(this);
        if (getCommand("签到") != null)
            getCommand("签到").setExecutor(this);
        if (getCommand("recycle") != null)
            getCommand("recycle").setExecutor(this);
        if (getCommand("垃圾清理") != null)
            getCommand("垃圾清理").setExecutor(this);
        if (getCommand("绑定邮箱") != null)
            getCommand("绑定邮箱").setExecutor(this);
        if (getCommand("找回密码") != null)
            getCommand("找回密码").setExecutor(this);
        if (getCommand("玩家信息") != null)
            getCommand("玩家信息").setExecutor(this);
        if (getCommand("sdf1debug") != null)
            getCommand("sdf1debug").setExecutor(this);
        if (getCommand("oa") != null)
            getCommand("oa").setExecutor(this);


        getServer().getPluginManager()
                .registerEvents(this, this);

        afk.startCheck();
        startLoginReminder();
        startTimeoutCheck();
        startPasswordReminder();
        startTicketAutoProcess();

        getLogger().info(
                "Sdf1_login v1.0 | 就绪");
    }

    @Override
    public void onDisable() {
        if (db != null) db.close();
        if (garbage != null) garbage.close();
    }

    private void setupEconomy() {
        if (getServer().getPluginManager()
                .getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager()
                        .getRegistration(
                                Economy.class);
        if (rsp != null)
            economy = rsp.getProvider();
    }

    // ===== Timers =====
    private void startTimeoutCheck() {
        new BukkitRunnable() {
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it =
                        joinTime.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Long> entry =
                            it.next();
                    if (now - entry.getValue()
                            > config.loginTimeout
                            * 1000L) {
                        Player p = Bukkit.getPlayer(
                                entry.getKey());
                        if (p != null && p.isOnline()
                                && !loggedIn.contains(
                                p.getName()))
                            p.kickPlayer(config.msg(
                                    "login_timeout"));
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(this, 80L, 80L);
    }

    private void startLoginReminder() {
        new BukkitRunnable() {
            public void run() {
                Iterator<UUID> it =
                        joinTime.keySet().iterator();
                while (it.hasNext()) {
                    UUID uuid = it.next();
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) {
                        it.remove();
                        continue;
                    }
                    if (loggedIn.contains(
                            p.getName())) {
                        it.remove();
                        continue;
                    }
                    // 已注册→提示登录，未注册→提示注册
                    if (db.userExists(p.getName()))
                        p.sendMessage(config.msg(
                                "not_logged_in"));
                    else
                        p.sendMessage(config.msg(
                                "not_registered"));
                }
            }
        }.runTaskTimer(this, 200L, 200L);
    }

    private void startPasswordReminder() {
        new BukkitRunnable() {
            public void run() {
                for (Player p :
                        Bukkit.getOnlinePlayers()) {
                    if (needsPasswordChange.contains(
                            p.getName())) {
                        p.sendTitle("§c§l请修改密码",
                                "§f使用 /sdf1_login pw 修改密码",
                                10, 40, 10);
                        p.playSound(p.getLocation(),
                                Sound.BLOCK_NOTE_BLOCK_PLING,
                                1.0f, 1.0f);
                    }
                }
            }
        }.runTaskTimer(this, 200L, 200L);
    }

    private void startTicketAutoProcess() {
        new BukkitRunnable() {
            public void run() {
                if (ticket != null)
                    ticket.autoProcessCompleted();
            }
        }.runTaskTimer(this, 6000L, 6000L);
    }

    // ===== Utils =====
    public String getPlayerIP(Player p) {
        InetSocketAddress addr = p.getAddress();
        if (addr == null) return null;
        return addr.getAddress().getHostAddress();
    }


    public void resetActivity(Player p) {
        lastActivity.put(p.getUniqueId(),
                System.currentTimeMillis());
    }

    public boolean canAutoLogin(Player p) {
        String ip = getPlayerIP(p);
        if (ip == null) return false;
        Long last = ipAutoLogin.get(ip);
        return last != null
                && System.currentTimeMillis() - last
                < IP_SKIP_MS;
    }

    public void recordIPLogin(Player p) {
        String ip = getPlayerIP(p);
        if (ip != null)
            ipAutoLogin.put(ip,
                    System.currentTimeMillis());
    }

    public boolean isFrozen(Player p) {
        return !loggedIn.contains(p.getName());
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender instanceof Player)
            return ((Player) sender)
                    .getScoreboardTags()
                    .contains(config.adminTag);
        return true;
    }

    private void clearPlayer(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(
                new ItemStack[4]);
        p.getInventory().setExtraContents(
                new ItemStack[1]);
        p.setLevel(0);
        p.setExp(0f);
    }

    public void restoreInventory(Player p) {
        String name = p.getName();
        try {
            String[] backup =
                    db.loadInventoryBackup(name);
            if (backup == null
                    || !isValidBackup(backup)) {
                return;
            }

            debugPrintBase64("恢复前", name,
                    backup[0]);

            ItemStack[] contents =
                    InventorySerializer
                            .fromBase64(backup[0]);
            if (contents != null)
                p.getInventory()
                        .setContents(contents);

            if (backup[1] != null
                    && !backup[1].isEmpty()) {
                ItemStack[] armor =
                        InventorySerializer
                                .fromBase64(
                                        backup[1]);
                if (armor != null)
                    p.getInventory()
                            .setArmorContents(
                                    armor);
            }

            if (backup[2] != null
                    && !backup[2].isEmpty()) {
                ItemStack[] extra =
                        InventorySerializer
                                .fromBase64(
                                        backup[2]);
                if (extra != null)
                    p.getInventory()
                            .setExtraContents(
                                    extra);
            }

            int lv = Integer.parseInt(
                    backup[3]);
            if (lv > 0) p.setLevel(lv);
            double xp = Double.parseDouble(
                    backup[4]);
            if (xp > 0)
                p.setExp((float) xp);

            debugPrintBase64("恢复后", name,
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getContents()));

            getLogger().info(
                    "[Sdf1_login] " + name
                            + " 背包已恢复");
        } catch (Exception e) {
            getLogger().severe(
                    "[Sdf1_login] 还原背包失败: "
                            + name);
            e.printStackTrace();
        }
    }




    public void backupInventory(Player p) {
        String name = p.getName();
        int cnt = countItems(p)
                + countArmor(p)
                + countExtra(p);
        if (cnt == 0) {
            getLogger().warning(
                    "[Sdf1_login] " + name
                            + " 背包为空，跳过备份");
            return;
        }
        try {
            String invB64 =
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getContents());
            String armorB64 =
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getArmorContents());
            String extraB64 =
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getExtraContents());
            db.saveInventoryBackup(name, invB64,
                    armorB64, extraB64,
                    p.getLevel(), p.getExp());
            getLogger().info(
                    "[Sdf1_login] " + name
                            + " 背包已备份 (物品:"
                            + cnt + ")");
        } catch (Exception e) {
            getLogger().severe(
                    "[Sdf1_login] 背包备份失败: "
                            + name);
            e.printStackTrace();
        }
    }

    private void hideInventory(Player p) {
        String name = p.getName();
        try {
            debugPrintBase64("清空前", name,
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getContents()));

            // 清空背包（备份已在上次退出时保存）
            p.getInventory().clear();
            p.getInventory()
                    .setArmorContents(null);
            p.getInventory()
                    .setExtraContents(null);
            p.setLevel(0);
            p.setExp(0);

            debugPrintBase64("清空后", name,
                    InventorySerializer.toBase64(
                            p.getInventory()
                                    .getContents()));
        } catch (Exception e) {
            getLogger().severe(
                    "[Sdf1_login] hideInventory失败: "
                            + name);
            e.printStackTrace();
        }
    }

    private void hideInventoryDelayed(Player p,
                                      int attempt) {
        String name = p.getName();
        if (!p.isOnline()) return;
        if (!isFrozen(p)) return;

        // 从数据库加载已有备份
        String[] existing =
                db.loadInventoryBackup(name);

        // 调试打印
        debugPrintBase64("加入-加载DB", name,
                existing != null
                        ? existing[0] : null);

        // 无有效备份 → 跳过清空背包
        if (existing == null
                || !isValidBackup(existing)) {
            getLogger().info(
                    "[Sdf1_login] " + name
                            + " 无有效备份，"
                            + "跳过清空背包");
            return;
        }

        // 有有效备份 → 清空当前背包
        hideInventory(p);
    }


    public void autoLogin(Player p,
                          String registerType) {
        String name = p.getName();
        String ip = getPlayerIP(p);
        String uuid =
                p.getUniqueId().toString();
        String defaultPwd = "123456aA";

        if (!db.userExists(name)) {
            if (config.maxAccountsPerIP > 0
                    && ip != null
                    && !ipGroup.canRegister(ip)) {
                p.kickPlayer(
                        buildIPKickMessage(p, ip));
                return;
            }
            if (!"manual".equals(
                    config.approvalMode)) {
                AccountRequestManager.Request req =
                        accountRequest.createRequest(
                                name, name, ip);
                if (req != null) {
                    p.kickPlayer(
                            config.msg("need_approval")
                                    .replace("{id}",
                                            String.valueOf(
                                                    req.id)));
                    return;
                }
            }
            String salt =
                    PasswordUtils.generateSalt();
            String hash = PasswordUtils.hash(
                    defaultPwd, salt);
            db.createUser(name, hash, salt);
            db.setField(name,
                    "premium_uuid", uuid);
            db.setField(name,
                    "register_ip", ip);
            db.setField(name,
                    "register_type", registerType);
            db.recordIP(name, ip);
        }

        loggedIn.add(name);
        db.setLoggedIn(name, true);
        db.setField(name,
                "last_login_time",
                System.currentTimeMillis());
        db.setField(name,
                "last_online_check",
                System.currentTimeMillis());
        db.recordIP(name, ip);
        joinTime.remove(p.getUniqueId());
        recordIPLogin(p);

        // 只在有有效备份时恢复
        restoreInventory(p);

        giveMenuSnowball(p);
        p.sendMessage("§a[Sdf1_login] §f您已自动登录！");
        activateBeibao(p);
        pushPendingAlerts(p);


    }

    private void activateBeibao(Player p) {
        try {
            org.bukkit.plugin.Plugin cy =
                    Bukkit.getPluginManager()
                            .getPlugin("CY_beibao");
            if (cy != null && cy.isEnabled())
                cy.getClass().getMethod(
                                "onSdf1Activation",
                                String.class,
                                int.class, int.class)
                        .invoke(cy, p.getName(),
                                0, 0);
        } catch (Exception ignored) {
        }
    }

    public void triggerSecurityAlert(Player player,
                                     String suspiciousIP) {
        String name = player.getName();
        db.recordSecurityAlert(name, suspiciousIP);
        Bukkit.broadcastMessage(
                "§c§l[安全警告] §e" + name
                        + " §f账号疑似遭到 §c"
                        + suspiciousIP
                        + " §f的盗号尝试");
        String emailAddr =
                (String) db.getField(name, "email");
        if (emailAddr != null
                && !emailAddr.isEmpty()) {
            String body = "玩家 " + name
                    + "：\n您的账号在 "
                    + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date())
                    + " 疑似遭到 IP: "
                    + suspiciousIP
                    + " 的盗号尝试。\n"
                    + "建议立即修改密码。";
            final String to = emailAddr;
            final String finalBody = body;
            Bukkit.getScheduler()
                    .runTaskAsynchronously(this,
                            () -> email
                                    .sendTempPassword(
                                            to, name,
                                            finalBody));
        }
    }

    public void pushPendingAlerts(Player p) {
        String name = p.getName();
        List<Map<String, Object>> alerts =
                db.getUnnotifiedAlerts(name);
        if (alerts.isEmpty()) return;
        p.sendMessage("§c§l========== 安全报警 ==========");
        for (Map<String, Object> alert : alerts) {
            p.sendMessage("§e时间: §f"
                    + alert.get("alert_date"));
            p.sendMessage("§e可疑IP: §c"
                    + alert.get("alert_ip"));
            db.markAlertNotified(
                    ((Number) alert.get("id"))
                            .intValue());
        }
        p.sendMessage(
                "§c建议使用 §e/sdf1_login pw §c修改密码");
        p.sendMessage(
                "§c§l==================================");
    }

    public void handleReset(Player p) {
        loginMgr.handleReset(p);
    }

    public boolean dailySign(Player p) {
        if (checkIn.isCheckedInToday(p.getName()))
            return false;
        p.sendMessage(checkIn.checkIn(p));
        invite.onInviteeCheckIn(p.getName());
        return true;
    }

    public void openTaskPanel(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§6§l任务面板");
        ItemStack gl = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glm = gl.getItemMeta();
        if (glm != null) {
            glm.setDisplayName(" ");
            gl.setItemMeta(glm);
        }
        for (int i = 0; i < 27; i++)
            g.setItem(i, gl);
        Map<String, Object> user =
                db.getUser(p.getName());
        int stage = ((Number) user.getOrDefault(
                "gift_stage", 0)).intValue();
        String claimed = (String) user.getOrDefault(
                "gift_claimed", "");
        g.setItem(11, mkItem(Material.BOOK,
                "§e新人礼包进度",
                "§7当前阶段: §e" + stage,
                "§7已领取: §a" + (claimed.isEmpty()
                        ? "无" : claimed)));
        g.setItem(15, mkItem(Material.CLOCK,
                "§b签到", "§7今日签到获取积分"));
        g.setItem(22, mkItem(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openAccountRequestPanel(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§e§l账号请求");
        ItemStack gl = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glm = gl.getItemMeta();
        if (glm != null) {
            glm.setDisplayName(" ");
            gl.setItemMeta(glm);
        }
        for (int i = 0; i < 27; i++)
            g.setItem(i, gl);
        g.setItem(10, mkItem(Material.BARRIER,
                "§c申请删除账号",
                "§7申请删除自己名下的账号"));
        g.setItem(13, mkItem(Material.PAPER,
                "§6提交工单",
                "§7提交bug反馈、求助、举报等"));
        g.setItem(16, mkItem(Material.REDSTONE,
                "§e找回密码",
                "§7通过邮箱找回密码"));
        g.setItem(22, mkItem(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    private void playSuccessSound(Player p) {
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

    private int countItems(Player p) {
        int c = 0;
        for (ItemStack it : p.getInventory()
                .getContents())
            if (it != null && it.getType()
                    != Material.AIR) c++;
        return c;
    }

    private int countArmor(Player p) {
        int c = 0;
        for (ItemStack it : p.getInventory()
                .getArmorContents())
            if (it != null && it.getType()
                    != Material.AIR) c++;
        return c;
    }

    private int countExtra(Player p) {
        int c = 0;
        for (ItemStack it : p.getInventory()
                .getExtraContents())
            if (it != null && it.getType()
                    != Material.AIR) c++;
        return c;
    }

    /**
     * 检查备份是否有效（包含非雪球的真实物品）
     */
    public boolean isValidBackup(String[] backup) {
        if (backup == null
                || backup[0] == null
                || backup[0].isEmpty()) {
            return false;
        }
        try {
            ItemStack[] items =
                    InventorySerializer
                            .fromBase64(backup[0]);
            if (items == null) return false;
            for (ItemStack item : items) {
                if (item == null
                        || item.getType()
                        == Material.AIR)
                    continue;
                if (isMenuSnowball(item))
                    continue;
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 控制台打印 base64 前10位
     */
    private void debugPrintBase64(String label,
                                  String name, String base64) {
        if (base64 == null || base64.isEmpty()) {
            getLogger().info(
                    "[BACKUP_DEBUG] " + label
                            + " " + name
                            + ": <empty>");
            return;
        }
        String preview =
                base64.length() > 10
                        ? base64.substring(0, 10)
                        : base64;
        getLogger().info(
                "[BACKUP_DEBUG] " + label + " "
                        + name + ": " + preview);
    }

    private ItemStack mkItem(Material mat,
                             String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) {
                List<String> list =
                        new ArrayList<>();
                for (String l : lore)
                    if (!l.isEmpty()) list.add(l);
                if (!list.isEmpty())
                    im.setLore(list);
            }
            it.setItemMeta(im);
        }
        return it;
    }

    private String buildIPKickMessage(Player p,
                                      String ip) {
        List<Map<String, Object>> accounts =
                db.getAccountDetailsByIP(ip);
        StringBuilder sb = new StringBuilder();
        sb.append("§c§l======== IP注册已满 ========\n");
        sb.append("§e您的IP §f").append(ip)
                .append(" §e下已注册 §f")
                .append(accounts.size())
                .append(" §e个账号：\n");
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm");
        int idx = 1;
        for (Map<String, Object> acc : accounts) {
            long regTime = ((Number) acc.get(
                    "register_time")).longValue();
            String dateStr = regTime > 0
                    ? sdf.format(new java.util.Date(
                    regTime))
                    : "未知";
            sb.append("§7").append(idx)
                    .append(". §f")
                    .append(acc.get("player_name"))
                    .append(" §7注册于: §e")
                    .append(dateStr).append("\n");
            idx++;
        }
        sb.append("§7-----------------------------\n");
        sb.append("§e请使用已有账号登录\n");
        sb.append("§e登录后输入 §f/oa §e提交工单\n");
        sb.append("§c§l============================");
        return sb.toString();
    }
    /**
     * 读取备份，返回物品名预览
     */
    private String getBackupItemPreview(
            int backupId) {
        try {
            String[] backup =
                    db.getInventoryBackupById(
                            backupId);
            if (backup == null
                    || backup[0] == null
                    || backup[0].isEmpty())
                return "§7空";
            ItemStack[] items =
                    InventorySerializer
                            .fromBase64(
                                    backup[0]);
            if (items == null)
                return "§7空";
            Map<String, Integer> counts =
                    new LinkedHashMap<>();
            for (ItemStack it : items) {
                if (it == null
                        || it.getType()
                        == Material.AIR)
                    continue;
                String name = translateMaterial(
                        it.getType().name());
                counts.merge(name,
                        it.getAmount(),
                        Integer::sum);
            }
            if (counts.isEmpty())
                return "§7空";
            StringBuilder sb =
                    new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> en
                    : counts.entrySet()) {
                if (shown >= 5) {
                    sb.append(" §7...");
                    break;
                }
                if (shown > 0)
                    sb.append("§7, ");
                sb.append("§f").append(en.getKey())
                        .append("§7x")
                        .append(en.getValue());
                shown++;
            }
            return sb.toString();
        } catch (Exception e) {
            return "§7解析失败";
        }
    }

    private String translateMaterial(
            String name) {
        switch (name) {
            case "GRASS_BLOCK":
                return "草方块";
            case "OAK_LOG":
                return "橡木原木";
            case "OAK_PLANKS":
                return "橡木木板";
            case "STONE":
                return "石头";
            case "COBBLESTONE":
                return "圆石";
            case "DIRT":
                return "泥土";
            case "SAND":
                return "沙子";
            case "GRAVEL":
                return "砂砾";
            case "DIAMOND":
                return "钻石";
            case "DIAMOND_ORE":
                return "钻石矿石";
            case "IRON_INGOT":
                return "铁锭";
            case "GOLD_INGOT":
                return "金锭";
            case "COAL":
                return "煤炭";
            case "COAL_ORE":
                return "煤矿石";
            case "RAW_IRON":
                return "粗铁";
            case "RAW_GOLD":
                return "粗金";
            case "OAK_LEAVES":
                return "橡树树叶";
            case "OAK_SAPLING":
                return "橡树树苗";
            case "STICK":
                return "木棍";
            case "CRAFTING_TABLE":
                return "工作台";
            case "FURNACE":
                return "熔炉";
            case "CHEST":
                return "箱子";
            case "TORCH":
                return "火把";
            case "BEDROCK":
                return "基岩";
            case "TNT":
                return "TNT";
            case "REDSTONE":
                return "红石";
            case "REDSTONE_ORE":
                return "红石矿石";
            case "LAPIS_LAZULI":
                return "青金石";
            case "EMERALD":
                return "绿宝石";
            case "EMERALD_ORE":
                return "绿宝石矿石";
            case "OBSIDIAN":
                return "黑曜石";
            case "GLOWSTONE":
                return "萤石";
            case "ARROW":
                return "箭";
            case "BOW":
                return "弓";
            case "IRON_SWORD":
                return "铁剑";
            case "DIAMOND_SWORD":
                return "钻石剑";
            case "IRON_PICKAXE":
                return "铁镐";
            case "DIAMOND_PICKAXE":
                return "钻石镐";
            case "IRON_AXE":
                return "铁斧";
            case "DIAMOND_AXE":
                return "钻石斧";
            case "IRON_HELMET":
                return "铁头盔";
            case "IRON_CHESTPLATE":
                return "铁胸甲";
            case "IRON_LEGGINGS":
                return "铁护腿";
            case "IRON_BOOTS":
                return "铁靴子";
            case "DIAMOND_HELMET":
                return "钻石头盔";
            case "DIAMOND_CHESTPLATE":
                return "钻石胸甲";
            case "DIAMOND_LEGGINGS":
                return "钻石护腿";
            case "DIAMOND_BOOTS":
                return "钻石靴子";
            case "BREAD":
                return "面包";
            case "COOKED_BEEF":
                return "牛排";
            case "COOKED_PORKCHOP":
                return "熟猪排";
            case "COOKED_CHICKEN":
                return "熟鸡肉";
            case "GOLDEN_APPLE":
                return "金苹果";
            case "ENDER_PEARL":
                return "末影珍珠";
            case "BLAZE_ROD":
                return "烈焰棒";
            case "GHAST_TEAR":
                return "恶魂之泪";
            case "NETHER_STAR":
                return "下界之星";
            case "SHULKER_SHELL":
                return "潜影壳";
            case "ELYTRA":
                return "鞘翅";
            case "TOTEM_OF_UNDYING":
                return "不死图腾";
            case "TRIDENT":
                return "三叉戟";
            case "ENCHANTED_GOLDEN_APPLE":
                return "附魔金苹果";
            default:
                // 转中文名格式：SANDSTONE -> 沙石
                return name.toLowerCase()
                        .replace("_", " ");
        }
    }

    // ===== Events =====
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String name = p.getName();
        String ip = getPlayerIP(p);
        // 重置聊天输入状态（防止上次退出时残留）
        chatInput.reset(p);
        if (needsPasswordChange.contains(name))
            needsPasswordChange.remove(name);

        lastActivity.put(p.getUniqueId(),
                System.currentTimeMillis());
        if (needsPasswordChange.contains(name))
            needsPasswordChange.remove(name);

        boolean isBedrock =
                verification.isBedrockPlayer(p);
        boolean isOnlineMode =
                verification.isOnlineMode();

        if (!db.userExists(name)) {
            if (config.maxAccountsPerIP > 0
                    && ip != null
                    && !ipGroup.canRegister(ip)) {
                p.kickPlayer(
                        buildIPKickMessage(p, ip));
                return;
            }
        }

        if (!"manual".equals(config.approvalMode)
                && !db.userExists(name)) {
            AccountRequestManager.Request req =
                    accountRequest.createRequest(
                            name, name, ip);
            if (req != null) {
                p.kickPlayer(
                        config.msg("need_approval")
                                .replace("{id}",
                                        String.valueOf(
                                                req.id)));
                return;
            }
        }

        if (isOnlineMode) {
            autoLogin(p, "premium");
            return;
        }
        if (isBedrock) {
            autoLogin(p, "bedrock");
            return;
        }
        if (canAutoLogin(p)) {
            autoLogin(p, "ip_reconnect");
            return;
        }
        afk.remove(p.getUniqueId());
        afk.recordAction(p.getUniqueId());
        hideInventoryDelayed(p, 0);


        verification.verifyPremiumAsync(p,
                isPremium -> {
                    if (isPremium) {
                        Bukkit.getScheduler()
                                .runTask(this,
                                        () -> autoLogin(p,
                                                "premium"));
                    } else {
                        Bukkit.getScheduler()
                                .runTask(this, () -> {
                                    joinTime.put(
                                            p.getUniqueId(),
                                            System.currentTimeMillis());
                                    if (!db.userExists(name))
                                        p.sendMessage(config.msg(
                                                "not_registered"));
                                    else
                                        p.sendMessage(config.msg(
                                                "not_logged_in"));
                                });
                    }
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String name = p.getName();
        chatInput.reset(p);

        boolean wasLoggedIn =
                loggedIn.contains(name);

        // 判断是否需要保存：
        // 1. 已登录 → 保存当前背包
        // 2. 未登录但有真实物品 → 保存
        //    （插件安装前的老玩家）
        // 3. 未登录且背包被插件清空 → 不保存
        //    （避免覆盖有效备份）
        boolean hasRealItems = false;
        for (ItemStack it : p.getInventory()
                .getContents()) {
            if (it != null
                    && it.getType()
                    != Material.AIR
                    && !isMenuSnowball(it)) {
                hasRealItems = true;
                break;
            }
        }

        if (wasLoggedIn || hasRealItems) {
            try {
                String contents =
                        InventorySerializer
                                .toBase64(
                                        p.getInventory()
                                                .getContents());
                String armor =
                        InventorySerializer
                                .toBase64(
                                        p.getInventory()
                                                .getArmorContents());
                String extra =
                        InventorySerializer
                                .toBase64(
                                        p.getInventory()
                                                .getExtraContents());
                int level = p.getLevel();
                double exp = p.getExp();

                debugPrintBase64(
                        "退出保存", name,
                        contents);
                db.saveInventoryBackup(name,
                        contents, armor, extra,
                        level, exp);
            } catch (Exception ex) {
                getLogger().severe(
                        "[Sdf1_login] "
                                + "退出保存背包失败: "
                                + name);
            }
        } else {
            getLogger().info(
                    "[BACKUP_DEBUG] 退出跳过 "
                            + name
                            + ": 背包为空"
                            + "（插件已清空）");
        }

        if (wasLoggedIn) {
            loggedIn.remove(name);
            db.setLoggedIn(name, false);
            recordIPLogin(p);
        }

        lastActivity.remove(p.getUniqueId());
        joinTime.remove(p.getUniqueId());
        joinLoc.remove(p.getUniqueId());
        needsPasswordChange.remove(name);
    }

    private static final String MENU_SNOWBALL_TAG =
            "sdf1_menu";

    public void giveMenuSnowball(Player p) {
        // 检查背包里是否已有菜单雪球
        for (ItemStack it : p.getInventory()
                .getContents()) {
            if (isMenuSnowball(it)) {
                return; // 已有，不重复发
            }
        }
        // 没有则发放
        ItemStack snow = new ItemStack(
                Material.SNOWBALL);
        ItemMeta im = snow.getItemMeta();
        if (im != null) {
            im.setDisplayName(
                    "§e§l[菜单] §f右键打开主菜单");
            List<String> lore = new ArrayList<>();
            lore.add("§7右键点击打开功能菜单");
            lore.add("§8" + MENU_SNOWBALL_TAG);
            im.setLore(lore);
            snow.setItemMeta(im);
        }
        // 放到最后一个空格，避免覆盖
        int emptySlot =
                p.getInventory()
                        .firstEmpty();
        if (emptySlot >= 0) {
            p.getInventory().setItem(
                    emptySlot, snow);
        } else {
            // 背包满，丢在地上
            p.getWorld().dropItemNaturally(
                    p.getLocation(), snow);
        }
    }

    private boolean isMenuSnowball(ItemStack item) {
        if (item == null
                || item.getType()
                != Material.SNOWBALL)
            return false;
        if (!item.hasItemMeta())
            return false;
        ItemMeta im = item.getItemMeta();
        if (im == null) return false;
        if (!im.hasLore()) return false;
        List<String> lore = im.getLore();
        if (lore == null) return false;
        for (String line : lore) {
            if (line.contains(MENU_SNOWBALL_TAG))
                return true;
        }
        return false;
    }


    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (isFrozen(p)) {
            if (to != null && (from.getX() != to.getX()
                    || from.getY() != to.getY()
                    || from.getZ() != to.getZ())) {
                e.setTo(e.getFrom());
            }
            return;
        }
        if (to != null && (from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ())) {
            afk.recordAction(p.getUniqueId());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }
        // 右键菜单雪球
        if (e.getAction().name()
                .contains("RIGHT")
                || e.getAction().name()
                .contains("LEFT")) {
            ItemStack hand =
                    p.getInventory()
                            .getItemInMainHand();
            if (isMenuSnowball(hand)) {
                e.setCancelled(true);
                gui.openMain(p);
                return;
            }
        }
    }


    @EventHandler
    public void onBlockBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }
        afk.recordAction(p.getUniqueId());
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isFrozen(e.getPlayer())) {
            e.setCancelled(true);
            return;
        }
        // 禁止丢弃菜单雪球
        ItemStack drop =
                e.getItemDrop().getItemStack();
        if (isMenuSnowball(drop)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        if (isFrozen(e.getPlayer()))
            e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            if (isFrozen((Player) e.getEntity()))
                e.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityAttack(
            EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player
                && isFrozen(
                (Player) e.getDamager()))
            e.setCancelled(true);
        if (e.getEntity() instanceof Player
                && isFrozen(
                (Player) e.getEntity()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();

        if (chatInput.getState(p).type
                != ChatInputManager.InputType.NONE) {
            e.setCancelled(true);
            ChatInputManager.InputType inputType =
                    chatInput.getState(p).type;
            if (ticket != null
                    && ticket.handleChatInput(
                    p, msg, inputType)) {
                return;
            }
            chatInput.handleInput(p, msg, this);
            return;
        }

        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }

        if (chatFilter == null) return;
        if (chatFilter.isPlayerWhitelisted(
                p.getName())) return;
        if (chatFilter.isMuted(p.getName())) {
            e.setCancelled(true);
            p.sendMessage(
                    chatFilter.msg("chat_muted"));
            return;
        }

        List<String> urls =
                chatFilter.extractUrls(msg);
        if (urls.isEmpty()) return;

        for (String u : urls) {
            if (chatFilter.isWhitelisted(u))
                continue;
            if (!chatFilter.isLikelyDomain(u))
                continue;

            e.setCancelled(true);
            chatFilter.incrementViolation(
                    p.getName());
            int count = chatFilter
                    .getViolationCount(p.getName());

            p.sendMessage(chatFilter
                    .msg("chat_url_blocked")
                    .replace("{url}", u));
            p.sendMessage(chatFilter
                    .msg("chat_url_violation")
                    .replace("{count}",
                            String.valueOf(count)));

            chatFilter.applyPunishment(p, count);

            if (chatFilter.isNotifyAdmin()) {
                String tag = config.adminTag;
                for (Player op :
                        Bukkit.getOnlinePlayers()) {
                    if (op.getScoreboardTags()
                            .contains(tag)
                            && !op.equals(p)) {
                        op.sendMessage(chatFilter
                                .msg("chat_url_admin_notify")
                                .replace("{player}",
                                        p.getName())
                                .replace("{url}", u)
                                .replace("{count}",
                                        String.valueOf(
                                                count)));
                    }
                }
            }

            if (chatFilter.isNotifyAll()) {
                Bukkit.broadcastMessage(chatFilter
                        .msg("chat_url_broadcast")
                        .replace("{player}",
                                p.getName()));
            }
        }
    }



        // ===== Inventory Click =====
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }
        String title =
                e.getView().getTitle();
        int slot = e.getRawSlot();

        if (needsPasswordChange.contains(p.getName())
                && !title.equals("§6§l任务面板")) {
            e.setCancelled(true);
            p.sendMessage("§c请先修改密码！");
            return;
        }

        // 工单GUI
        if (title.equals(
                TicketManager.T_TICKET_MAIN)
                || title.equals(
                TicketManager.T_TICKET_CREATE)
                || title.equals(
                TicketManager.T_TICKET_MY)
                || title.equals(
                TicketManager.T_TICKET_ADMIN)
                || title.equals(
                TicketManager.T_TICKET_DETAIL)
                || title.equals("§d§l服务商面板")
                || title.equals("§6§l抢单大厅")
                || title.equals(
                "§b§l我处理中的工单")) {
            e.setCancelled(true);
            ticket.handleClick(p, title, slot);
            return;
        }

        // 垃圾回收站
        if (title.equals("§6§l垃圾回收站")) {
            if (slot < 0 || slot >= 54) return;

            // 功能格 49-53
            if (slot >= 49 && slot <= 53) {
                e.setCancelled(true);
                garbage.handleClick(
                        p, title, slot);
                return;
            }

            ItemStack inSlot =
                    e.getInventory().getItem(slot);
            ItemStack onCursor = e.getCursor();

            // 1. 格子里是垃圾站物品 → 取出
            if (isTrashDisplayItem(inSlot)) {
                e.setCancelled(true);
                try {
                    List<String> lore =
                            inSlot.getItemMeta()
                                    .getLore();
                    String numStr =
                            lore.get(0)
                                    .replace(
                                            "§7ID: #", "")
                                    .trim();
                    int dbId = Integer
                            .parseInt(numStr);
                    // 先从DB删除
                    if (garbage.removeItem(
                            dbId)) {
                        // 再给玩家（去掉ID lore）
                        ItemStack give =
                                inSlot.clone();
                        ItemMeta gm =
                                give.getItemMeta();
                        if (gm != null) {
                            List<String> newLore =
                                    new ArrayList<>();
                            // 保留原始lore（跳过ID和数量行）
                            if (lore.size() > 2) {
                                for (int li = 2;
                                     li < lore
                                             .size();
                                     li++) {
                                    newLore.add(
                                            lore.get(
                                                    li));
                                }
                            }
                            gm.setLore(
                                    newLore.isEmpty()
                                            ? null
                                            : newLore);
                            give.setItemMeta(gm);
                        }
                        p.getInventory()
                                .addItem(give);
                        // 清空格子
                        e.getInventory()
                                .setItem(slot,
                                        null);
                    }
                } catch (Exception ex) {
                    p.sendMessage("§c取出失败");
                }
                return;
            }

            // 2. 格子空 + 手上有物品 → 放入
            if ((inSlot == null
                    || inSlot.getType()
                    == Material.AIR)
                    && onCursor != null
                    && onCursor.getType()
                    != Material.AIR
                    && !isMenuSnowball(
                    onCursor)) {
                // 不取消！让物品自然进入格子
                Bukkit.getScheduler()
                        .runTaskLater(this,
                                () -> {
                                    ItemStack after =
                                            e.getInventory()
                                                    .getItem(
                                                            slot);
                                    if (after != null
                                            && after.getType()
                                            != Material.AIR) {
                                        garbage.saveItem(
                                                after
                                                        .clone());
                                        e.getInventory()
                                                .setItem(
                                                        slot,
                                                        null);
                                    }
                                }, 1L);
                return;
            }

            // 3. 其他情况
            e.setCancelled(true);
            return;
        }

        // 主菜单
        if (title.equals(GUIManager.T_MAIN)) {
            e.setCancelled(true);
            if (slot == 10) gui.openMyInfo(p);
            else if (slot == 12) gui.openInvite(p);
            else if (slot == 14)
                p.openInventory(
                        points.createShopGUI(p));
            else if (slot == 16)
                gui.openTaskCenter(p);
            else if (slot == 18) ticket.openMain(p);
            else if (slot == 20) {
                if (p.isOp() || p.getScoreboardTags()
                        .contains(config.adminTag)) {
                    garbage.openRecycle(p);
                }
            }

            else if (slot == 22 && isAdmin(p)) {
                p.closeInventory();
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_AUTH;
                p.sendMessage("请输入管理密码:");
            }
            return;
        }


        // 我的信息
        if (title.equals(GUIManager.T_MY_INFO)) {
            e.setCancelled(true);
            if (slot == 26) gui.openMain(p);
            else if (slot == 10) {
                if (checkIn.isCheckedInToday(
                        p.getName()))
                    p.sendMessage(config.msg(
                            "checkin_already"));
                else {
                    p.sendMessage(
                            checkIn.checkIn(p));
                    invite.onInviteeCheckIn(
                            p.getName());
                    playSuccessSound(p);
                    gui.openMyInfo(p);
                }
            } else if (slot == 11) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .CHANGE_PWD_STEP1;
                p.closeInventory();
                p.sendMessage("§e请输入当前密码:");
            } else if (slot == 12) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .BACK_CHECKIN;
                p.closeInventory();
                p.sendMessage("§e请输入补签日期:");
            } else if (slot == 13) {
                String code = (String) db.getField(
                        p.getName(), "invite_code");
                if (code == null || code.isEmpty())
                    code = invite.generateCode(p);
                p.sendMessage("§e邀请码: " + code);
            } else if (slot == 14) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .SET_EMAIL;
                p.closeInventory();
                p.sendMessage("§e请输入邮箱:");
            } else if (slot == 15)
                p.openInventory(
                        points.createShopGUI(p));
            return;
        }

        // 管理面板
        if (title.equals(GUIManager.T_ADMIN)) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 10) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .SMTP_CONFIG;
                p.closeInventory();
                p.sendMessage("§e当前SMTP: "
                        + config.getSmtp("smtp地址"));
                p.sendMessage("§e输入新地址(0跳过):");
            } else if (slot == 11)
                gui.openUserManagement(p);
            else if (slot == 12) gui.openMyInfo(p);
            else if (slot == 14)
                p.openInventory(
                        points.createShopGUI(p));
            else if (slot == 16) {
                if (e.getClick().isLeftClick()) {
                    config.afkEnabled =
                            !config.afkEnabled;
                    p.sendMessage(config.msg(
                            config.afkEnabled
                                    ? "afk_set_enabled"
                                    : "afk_set_disabled"));
                    config.saveSettings();
                    gui.openAdmin(p);
                } else if (e.getClick()
                        .isRightClick()) {
                    p.closeInventory();
                    chatInput.getState(p).type =
                            ChatInputManager.InputType
                                    .ADMIN_SET_AFK_TIME;
                    p.sendMessage(
                            "§e输入挂机时长(分钟):");
                }
            }
            return;
        }

        // 任务中心
        if (title.equals(
                GUIManager.T_TASK_CENTER)) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 10)
                gui.openGiftStages(p);
            else if (slot == 12)
                gui.openTaskList(p, "主线任务");
            else if (slot == 14)
                gui.openTaskList(p, "支线任务");
            else if (slot == 16) {
                if (dailySign(p)) {
                    gui.openTaskCenter(p);
                    playSuccessSound(p);
                } else
                    p.sendMessage(config.msg(
                            "checkin_already"));
            }
            return;
        }

        // 新人礼包
        if (title.equals(
                GUIManager.T_GIFT_STAGES)) {
            e.setCancelled(true);
            if (slot == 26)
                gui.openTaskCenter(p);
            else if (slot >= 10 && slot <= 18) {
                int stage = slot - 9;
                if (gift.canClaim(p, stage)) {
                    gift.claimReward(p, stage);
                    gui.openGiftStages(p);
                } else
                    p.sendMessage(config.msg(
                            "gift_not_ready"));
            }
            return;
        }

        // 邀请
        if (title.equals(GUIManager.T_INVITE)) {
            e.setCancelled(true);
            if (slot == 26) gui.openMain(p);
            else if (slot == 10) {
                String code =
                        invite.generateCode(p);
                p.sendMessage("§e邀请码: " + code);
                gui.openInvite(p);
            } else if (slot == 16) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .INVITE_INPUT_CODE;
                p.closeInventory();
                p.sendMessage("§e请输入邀请码:");
            }
            return;
        }

        // 用户管理
        if (title.equals(
                GUIManager.T_USER_MGMT)) {
            e.setCancelled(true);
            if (slot == 53)
                gui.openAdmin(p);
            else if (slot >= 0 && slot < 45) {
                ItemStack item =
                        e.getInventory().getItem(slot);
                if (item != null
                        && item.hasItemMeta()
                        && item.getItemMeta()
                        .hasDisplayName())
                    gui.openUserDetail(p,
                            item.getItemMeta()
                                    .getDisplayName()
                                    .replace("§e", "")
                                    .replace("§a", ""));
            }
            return;
        }

        // 用户详情
        if (title.startsWith("§e§l管理: ")) {
            e.setCancelled(true);
            String tgt = title.substring(
                    "§e§l管理: ".length());
            if (slot == 22)
                gui.openUserManagement(p);
            else if (slot == 10) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_SET_POINTS;
                chatInput.getState(p)
                        .targetPlayer = tgt;
                p.closeInventory();
                p.sendMessage("§e输入" + tgt
                        + "的新积分:");
            } else if (slot == 12) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_SET_PWD;
                chatInput.getState(p)
                        .targetPlayer = tgt;
                p.closeInventory();
                p.sendMessage("§e输入" + tgt
                        + "的临时密码:");
            } else if (slot == 16) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_DELETE_CONFIRM;
                chatInput.getState(p)
                        .targetPlayer = tgt;
                p.closeInventory();
                p.sendMessage(config.msg(
                        "admin_delete_confirm"));
            }
            return;
        }

        // 积分商城
        if (title.startsWith("§d§l积分商城")) {
            e.setCancelled(true);
            if (slot == 49) gui.openMain(p);
            else if (slot == 50 && isAdmin(p)) {
                gui.openShopAdmin(p);
            } else
                points.handleClick(p, slot);
            return;
        }

        // 商城管理
        if (title.equals("§c§l商城管理")) {
            e.setCancelled(true);
            if (slot == 49) {
                p.openInventory(
                        points.createShopGUI(p));
                return;
            }
            return;
        }

        // CY背包商城
        if (title.startsWith("§d§lCY背包商城")) {
            e.setCancelled(true);
            int size = e.getInventory().getSize();
            if (slot == size - 1)
                p.openInventory(
                        points.createShopGUI(p));
            else points.handleCYClick(p, slot);
            return;
        }

        // 任务面板
        if (title.equals("§6§l任务面板")) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 15) {
                if (dailySign(p)) {
                    openTaskPanel(p);
                    playSuccessSound(p);
                } else
                    p.sendMessage(config.msg(
                            "checkin_already"));
            }
            return;
        }

        // 账号请求面板
        if (title.equals("§e§l账号请求")) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 10) {
                p.closeInventory();
                p.sendMessage("§c§l[确认] §f请输入:");
                p.sendMessage("§e/sdf1_login del "
                        + p.getName());
            } else if (slot == 13) {
                ticket.openCreate(p);
            } else if (slot == 16) {
                p.closeInventory();
                loginMgr.handleReset(p);
            }
            return;
        }

        // 主线/支线任务
        if (title.equals("§d§l主线任务")
                || title.equals("§d§l支线任务")) {
            e.setCancelled(true);
            if (slot == e.getInventory().getSize() - 1)
                gui.openTaskCenter(p);
            return;
        }
    }

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }
        String t = e.getView().getTitle();

        // 回收站拖拽
        if (t.equals("§6§l垃圾回收站")) {
            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot >= 49
                        && rawSlot <= 53) {
                    e.setCancelled(true);
                    return;
                }
            }
            // 0-48：不取消，延迟存DB
            Bukkit.getScheduler()
                    .runTaskLater(this, () -> {
                        for (int rawSlot :
                                e.getRawSlots()) {
                            if (rawSlot < 0
                                    || rawSlot >= 49)
                                continue;
                            ItemStack item =
                                    e.getInventory()
                                            .getItem(
                                                    rawSlot);
                            if (item != null
                                    && item.getType()
                                    != Material.AIR
                                    && !isMenuSnowball(
                                    item)) {
                                garbage.saveItem(
                                        item.clone());
                                e.getInventory()
                                        .setItem(
                                                rawSlot,
                                                null);
                            }
                        }
                    }, 1L);
            return;
        }


        if (t.equals(TicketManager.T_TICKET_MAIN)
                || t.equals(
                TicketManager.T_TICKET_CREATE)
                || t.equals(
                TicketManager.T_TICKET_MY)
                || t.equals(
                TicketManager.T_TICKET_ADMIN)
                || t.equals(
                TicketManager.T_TICKET_DETAIL)
                || t.equals("§d§l服务商面板")
                || t.equals("§6§l抢单大厅")
                || t.equals(
                "§b§l我处理中的工单")
                || t.equals("§6§l我的历史工单")
                || t.equals("§c§l商城管理")) {
            e.setCancelled(true);
        }
    }

    // ===== Commands =====
    @Override
    public boolean onCommand(CommandSender sender,
                             Command cmd, String label,
                             String[] args) {

        String cmdName = cmd.getName().toLowerCase();

        if (cmdName.equals("recycle")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player pr = (Player) sender;
            if (!pr.isOp()
                    && !pr.getScoreboardTags()
                    .contains(config.adminTag)) {
                pr.sendMessage("§c权限不足");
                return true;
            }
            garbage.openRecycle(pr);
            return true;
        }


        // /垃圾清理
        if (cmdName.equals("垃圾清理")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            int count = garbage.collectItems();
            sender.sendMessage("§a已清理 "
                    + count + " 个掉落物");
            return true;
        }

        if (cmdName.equals("reg")
                || cmdName.equals("注册")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            chatInput.reset(p);
            if (db.userExists(p.getName())) {
                p.sendMessage(
                        "§c您已注册过，请使用 /login");
                return true;
            }
            try {
                loginMgr.handleRegister(p, args);
            } catch (Exception e) {
                getLogger().severe(
                        "[Sdf1_login] reg命令异常: "
                                + e.getMessage());
                e.printStackTrace();
                p.sendMessage("§c注册出错，请联系管理员");
            }
            return true;
        }



        if (cmdName.equals("login")
                || cmdName.equals("l")
                || cmdName.equals("登录")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            chatInput.reset(p);
            if (loggedIn.contains(p.getName())) {
                p.sendMessage("§c您已登录");
                return true;
            }
            try {
                loginMgr.handleLogin(p, args);
            } catch (Exception e) {
                getLogger().severe(
                        "[Sdf1_login] login命令异常: "
                                + e.getMessage());
                e.printStackTrace();
                p.sendMessage("§c登录出错，请联系管理员");
            }
            return true;
        }

        // /找回密码
        if (cmdName.equals("找回密码")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            handleReset((Player) sender);
            return true;
        }

        // /签到
        if (cmdName.equals("签到")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            if (dailySign((Player) sender))
                playSuccessSound((Player) sender);
            else
                ((Player) sender).sendMessage(
                        config.msg("checkin_already"));
            return true;
        }

        // 绑定邮箱
        if (cmdName.equals("绑定邮箱")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            if (args.length >= 1) {
                String emailAddr = args[0];
                if (!emailAddr.contains("@")
                        || !emailAddr.contains(".")) {
                    p2.sendMessage("§c邮箱格式不正确");
                    return true;
                }
                String oldEmail = (String) db.getField(
                        p2.getName(), "email");
                if (oldEmail != null
                        && !oldEmail.isEmpty()) {
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType
                                    .SET_EMAIL_VERIFY;
                    chatInput.getState(p2)
                            .targetPlayer = emailAddr;
                    chatInput.getState(p2)
                            .ticketTitle = "need_pwd";
                    p2.sendMessage("§e您已绑定邮箱: "
                            + oldEmail);
                    p2.sendMessage(
                            "§e请输入登录密码以确认修改:");
                } else {
                    String code = String.valueOf(
                            (int) (Math.random()
                                    * 900000 + 100000));
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType
                                    .SET_EMAIL_VERIFY;
                    chatInput.getState(p2)
                            .targetPlayer = emailAddr;
                    chatInput.getState(p2)
                            .ticketTitle = code;
                    final String to = emailAddr;
                    final String c = code;
                    final Player fp = p2;
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(this,
                                    () -> {
                                        boolean sent = email
                                                .sendVerifyCode(to, c);
                                        Bukkit.getScheduler().runTask(
                                                this, () -> {
                                                    if (sent) {
                                                        fp.sendMessage(
                                                                "§a验证码已发送到 "
                                                                        + to);
                                                        fp.sendMessage(
                                                                "§e输入6位验证码(0取消):");
                                                    } else {
                                                        fp.sendMessage(
                                                                "§c发送失败");
                                                    }
                                                });
                                    });
                }
            } else {
                chatInput.getState(p2).type =
                        ChatInputManager.InputType
                                .SET_EMAIL;
                p2.sendMessage("§e请输入邮箱:");
            }
            return true;
        }


        // /sdf1debug
        if (cmdName.equals("sdf1debug")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            if (!isAdmin(sender)) {
                p2.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                p2.sendMessage("§e用法: /sdf1debug <玩家> <阶段>");
                return true;
            }
            String tgt = args[0];
            try {
                int stage = Integer.parseInt(args[1]);
                db.setField(tgt, "gift_stage", stage);
                p2.sendMessage("§a已将 " + tgt
                        + " 的任务阶段设为 " + stage);
            } catch (NumberFormatException ex) {
                p2.sendMessage("§c请输入有效数字");
            }
            return true;
        }

        // /oa
        if (cmdName.equals("oa")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            openAccountRequestPanel(
                    (Player) sender);
            return true;
        }

        // /玩家信息
        if (cmdName.equals("玩家信息")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            gui.openMain((Player) sender);
            return true;
        }

        // ===== /sdf1_login =====
        if (!cmdName.equals("sdf1_login"))
            return false;

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(
                        "§e用法: /sdf1_login <子命令>");
                return true;
            }
            gui.openMain((Player) sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        // stop
        if (sub.equals("stop")) {
            if (!(sender instanceof Player)) {
                // 控制台
                if (args.length >= 2) {
                    db.clearTempPassword(args[1]);
                    sender.sendMessage("§a已报废 "
                            + args[1] + " 的临时密码");
                } else {
                    db.clearAllTempPasswords();
                    sender.sendMessage(
                            "§a已报废所有临时密码");
                }
                return true;
            }
            Player p2 = (Player) sender;
            if (args.length >= 2) {
                String tgt = args[1];
                String myIP =
                        getPlayerIP(p2);
                String targetIP =
                        db.getPlayerIPByName(tgt);
                if (targetIP == null) {
                    Player tp =
                            Bukkit.getPlayer(tgt);
                    if (tp != null && tp.isOnline())
                        targetIP =
                                getPlayerIP(tp);
                }
                if (targetIP == null
                        || !targetIP.equals(myIP)) {
                    p2.sendMessage(
                            "§c只能操作同IP下的玩家");
                    return true;
                }
                db.clearTempPassword(tgt);
                p2.sendMessage("§a已报废 " + tgt
                        + " 的临时密码");
            } else {
                db.clearTempPassword(p2.getName());
                p2.sendMessage(
                        "§a已报废您的临时密码");
            }
            return true;
        }

        // reload
        if (sub.equals("reload")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            config.loadMessages();
            config.loadSmtp();
            config.loadSettings();
            chatFilter.loadConfig();
            sender.sendMessage("§a配置已重载！");
            return true;
        }
        // back - 恢复背包备份
        if (sub.equals("back")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /sdf1_login back <玩家> [#编号]");
                return true;
            }
            String backTarget = args[1];
            if (args.length >= 3
                    && args[2].startsWith("#")) {
                // /sdf1_login back Aplayer #1
                int backupId;
                try {
                    backupId = Integer.parseInt(
                            args[2].replace("#", ""));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(
                            "§c无效编号");
                    return true;
                }
                String[] backup =
                        db.getInventoryBackupById(
                                backupId);
                if (backup == null) {
                    sender.sendMessage(
                            "§c备份 #" + backupId
                                    + " 不存在");
                    return true;
                }
                // 如果玩家在线，直接恢复
                Player tp = Bukkit.getPlayer(
                        backTarget);
                if (tp != null && tp.isOnline()) {
                    try {
                        tp.getInventory()
                                .setContents(
                                        InventorySerializer
                                                .fromBase64(
                                                        backup[0]));
                        if (backup[1] != null
                                && !backup[1]
                                .isEmpty())
                            tp.getInventory()
                                    .setArmorContents(
                                            InventorySerializer
                                                    .fromBase64(
                                                            backup[1]));
                        if (backup[2] != null
                                && !backup[2]
                                .isEmpty())
                            tp.getInventory()
                                    .setExtraContents(
                                            InventorySerializer
                                                    .fromBase64(
                                                            backup[2]));
                        int lv = Integer.parseInt(
                                backup[3]);
                        if (lv > 0)
                            tp.setLevel(lv);
                        tp.sendMessage(
                                "§a管理员已恢复您的背包");
                        sender.sendMessage(
                                "§a已恢复 " + backTarget
                                        + " 的背包 (备份#"
                                        + backupId + ")");
                    } catch (Exception e) {
                        sender.sendMessage(
                                "§c恢复失败: "
                                        + e.getMessage());
                    }
                } else {
                    // 玩家离线：保存到数据库供下次登录恢复
                    db.saveInventoryBackup(
                            backTarget,
                            backup[0],
                            backup[1],
                            backup[2],
                            Integer.parseInt(
                                    backup[3]),
                            Double.parseDouble(
                                    backup[4]));
                    sender.sendMessage(
                            "§a" + backTarget
                                    + " 离线，备份已写入数据库，"
                                    + "下次登录时恢复");
                }
            } else {
                // /sdf1_login back Aplayer
                // 罗列最近5条备份
                List<Map<String, Object>> backups =
                        db.getInventoryBackups(
                                backTarget, 5);
                if (backups.isEmpty()) {
                    sender.sendMessage(
                            "§c" + backTarget
                                    + " 没有背包备份");
                    return true;
                }
                sender.sendMessage(
                        "§e§l=== " + backTarget
                                + " 背包备份 ===");
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat(
                                "MM-dd HH:mm:ss");
                for (Map<String, Object> b :
                        backups) {
                    int bid = ((Number)
                            b.get("id"))
                            .intValue();
                    Object stObj =
                            b.get("save_time");
                    long st = stObj != null
                            ? ((Number) stObj)
                              .longValue() : 0;
                    String time = st > 0
                            ? sdf.format(
                            new java.util.Date(st))
                            : "未知时间";
                    String itemNames =
                            getBackupItemPreview(
                                    bid);
                    sender.sendMessage(
                            "§7#" + bid
                                    + " §f" + time
                                    + " §7: "
                                    + itemNames);
                }
                sender.sendMessage(
                        "§7使用 §e/sdf1_login back "
                                + backTarget
                                + " #编号 §7恢复");
                sender.sendMessage(
                        "§e§l================================");
            }
            }

                // pw / password
        if (sub.equals("pw")
                || sub.equals("password")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;

            if (args.length >= 3) {
                // /sdf1_login pw 旧密码 新密码
                String oldPwd = args[1];
                String newPwd = args[2];
                if (!PasswordUtils.validate(
                        newPwd)) {
                    p2.sendMessage(config.msg(
                            "password_format_error"));
                    return true;
                }
                String salt = (String) db.getField(
                        p2.getName(),
                        "password_salt");
                String hash =
                        PasswordUtils.hash(
                                oldPwd, salt);
                boolean mainOk =
                        db.checkPassword(
                                p2.getName(), hash);
                boolean tempOk =
                        db.checkPasswordOrTemp(
                                p2.getName(), hash);
                if (!mainOk && !tempOk) {
                    p2.sendMessage(config.msg(
                            "password_wrong"));
                    return true;
                }
                String oldHash =
                        (String) db.getField(
                                p2.getName(),
                                "password_hash");
                String newSalt =
                        PasswordUtils.generateSalt();
                String newHash =
                        PasswordUtils.hash(
                                newPwd, newSalt);
                if (newHash.equals(oldHash)) {
                    p2.sendMessage(config.msg(
                            "password_same"));
                    return true;
                }
                recordPasswordChange(
                        p2.getName(),
                        oldHash, salt);
                db.setField(p2.getName(),
                        "password_hash", newHash);
                db.setField(p2.getName(),
                        "password_salt", newSalt);
                if (!mainOk && tempOk) {
                    db.clearTempPassword(
                            p2.getName());
                }
                needsPasswordChange.remove(
                        p2.getName());
                p2.sendMessage(config.msg(
                        "password_changed"));
                return true;
            }

            if (args.length == 2) {
                // /sdf1_login pw 旧密码
                // 验证旧密码，然后聊天输入新密码
                String oldPwd = args[1];
                String salt = (String) db.getField(
                        p2.getName(),
                        "password_salt");
                String hash =
                        PasswordUtils.hash(
                                oldPwd, salt);
                boolean mainOk =
                        db.checkPassword(
                                p2.getName(), hash);
                boolean tempOk =
                        db.checkPasswordOrTemp(
                                p2.getName(), hash);
                if (!mainOk && !tempOk) {
                    p2.sendMessage(config.msg(
                            "password_wrong"));
                    return true;
                }
                chatInput.getState(p2).type =
                        ChatInputManager.InputType
                                .CHANGE_PWD_STEP2;
                chatInput.getState(p2)
                        .ticketTitle =
                        mainOk ? "main" : "temp";
                p2.sendMessage(
                        "§e请输入新密码:");
                return true;
            }

            // /sdf1_login pw (无参数)
            chatInput.getState(p2).type =
                    ChatInputManager.InputType
                            .CHANGE_PWD_STEP1;
            p2.sendMessage(
                    "§e请输入当前密码:");
            return true;
        }

        // email
        if (sub.equals("email")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            if (args.length >= 2) {
                String emailAddr = args[1];
                if (!emailAddr.contains("@")
                        || !emailAddr.contains(".")) {
                    p2.sendMessage("§c邮箱格式不正确");
                    return true;
                }
                String oldEmail = (String) db.getField(
                        p2.getName(), "email");
                if (oldEmail != null
                        && !oldEmail.isEmpty()) {
                    // 已绑定：先验证密码
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType
                                    .SET_EMAIL_VERIFY;
                    chatInput.getState(p2)
                            .targetPlayer = emailAddr;
                    chatInput.getState(p2)
                            .ticketTitle = "need_pwd";
                    p2.sendMessage("§e您已绑定邮箱: "
                            + oldEmail);
                    p2.sendMessage(
                            "§e请输入登录密码以确认修改:");
                } else {
                    // 未绑定：直接发验证码
                    String code = String.valueOf(
                            (int) (Math.random()
                                    * 900000 + 100000));
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType
                                    .SET_EMAIL_VERIFY;
                    chatInput.getState(p2)
                            .targetPlayer = emailAddr;
                    chatInput.getState(p2)
                            .ticketTitle = code;
                    final String to = emailAddr;
                    final String c = code;
                    final Player fp = p2;
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(this,
                                    () -> {
                                        boolean sent = email
                                                .sendVerifyCode(to, c);
                                        Bukkit.getScheduler().runTask(
                                                this, () -> {
                                                    if (sent) {
                                                        fp.sendMessage(
                                                                "§a验证码已发送到 "
                                                                        + to);
                                                        fp.sendMessage(
                                                                "§e输入6位验证码(0取消):");
                                                    } else {
                                                        fp.sendMessage(
                                                                "§c发送失败");
                                                    }
                                                });
                                    });
                }
            } else {
                chatInput.getState(p2).type =
                        ChatInputManager.InputType
                                .SET_EMAIL;
                p2.sendMessage("§e请输入邮箱:");
            }
            return true;
        }


        // sign
        if (sub.equals("sign")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            if (dailySign((Player) sender))
                playSuccessSound((Player) sender);
            else
                ((Player) sender).sendMessage(
                        config.msg("checkin_already"));
            return true;
        }

        // reset
        if (sub.equals("reset")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            loginMgr.handleReset(
                    (Player) sender);
            return true;
        }

        // undo
        if (sub.equals("undo")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            PwdRollback rb =
                    pwdRollback.get(p2.getName());
            if (rb == null) {
                p2.sendMessage("§c无可撤销记录");
                return true;
            }
            if (System.currentTimeMillis() - rb.time
                    > 600000L) {
                pwdRollback.remove(p2.getName());
                p2.sendMessage("§c撤销已过期");
                return true;
            }
            db.setField(p2.getName(),
                    "password_hash", rb.hash);
            db.setField(p2.getName(),
                    "password_salt", rb.salt);
            pwdRollback.remove(p2.getName());
            p2.sendMessage("§a密码已撤销！");
            return true;
        }

        // take
        if (sub.equals("take")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(
                        "§e用法: /sdf1_login take <玩家> <积分>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c无效数字");
                return true;
            }
            if (!db.deductPoints(args[1], amount)) {
                sender.sendMessage("§c积分不足");
                return true;
            }
            sender.sendMessage("§a已扣除 " + args[1]
                    + " " + amount + " 积分");
            return true;
        }

        // set
        if (sub.equals("set")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(
                        "§e用法: /sdf1_login set <玩家> <临时密码>");
                return true;
            }
            String tgt = args[1];
            String tempPwd = args[2];
            if (!PasswordUtils.validate(tempPwd)) {
                sender.sendMessage("§c密码格式不符合要求");
                return true;
            }
            if (!db.userExists(tgt)) {
                sender.sendMessage("§c玩家不存在");
                return true;
            }
            String salt = (String) db.getField(
                    tgt, "password_salt");
            String hash = PasswordUtils.hash(
                    tempPwd, salt);
            db.setField(tgt, "temp_password", hash);
            db.setField(tgt, "temp_pw_expire",
                    System.currentTimeMillis()
                            + 300000L);

            db.setField(tgt, "temp_pw_used", 0);
            sender.sendMessage("§a已为 " + tgt
                    + " 设置临时密码");
            Player tp = Bukkit.getPlayer(tgt);
            if (tp != null && tp.isOnline())
                tp.kickPlayer(
                        "§c管理员已设置临时密码");
            // 邮件通知
            String emailAddr = (String) db.getField(
                    tgt, "email");
            if (emailAddr != null
                    && !emailAddr.isEmpty()) {
                String time =
                        new java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss")
                                .format(new java.util.Date());
                String body = "玩家 " + tgt
                        + "：\n您的密码已于 " + time
                        + " 被管理员重置为临时密码。\n"
                        + "如果是本人操作请忽略。\n"
                        + "如非本人操作请立即联系管理员。";
                final String to = emailAddr;
                final String fName = tgt;
                final String bodyF = body;
                Bukkit.getScheduler()
                        .runTaskAsynchronously(this,
                                () -> email
                                        .sendTempPassword(
                                                to, fName,
                                                bodyF));
            }
            return true;
        }

        // shopadd
        if (sub.equals("shopadd")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 4) {
                sender.sendMessage(
                        "§e用法: /sdf1_login shopadd <名称> <价格> <命令>");
                return true;
            }
            String shopName = args[1];
            int price;
            try {
                price = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c无效数字");
                return true;
            }
            String shopCmd = args[3];
            points.addItem(shopName, price, shopCmd);
            sender.sendMessage("§a已添加商品: "
                    + shopName + " (" + price
                    + "积分)");
            return true;
        }

        // shopdel
        if (sub.equals("shopdel")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /sdf1_login shopdel <序号>");
                return true;
            }
            try {
                int idx = Integer.parseInt(args[1]);
                points.removeItem(idx);
                sender.sendMessage("§a已删除商品 #" + idx);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c无效数字");
            }
            return true;
        }

        // get
        if (sub.equals("get")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /sdf1_login get <玩家>");
                return true;
            }
            Map<String, Object> user =
                    db.getUser(args[1]);
            if (user.isEmpty()) {
                sender.sendMessage("§c玩家不存在");
                return true;
            }
            sender.sendMessage(
                    "§e=== " + args[1] + " ===");
            sender.sendMessage("§7积分: §e"
                    + user.getOrDefault("points", 0));
            sender.sendMessage("§7邮箱: §e"
                    + user.getOrDefault("email", "无"));
            sender.sendMessage("§7签到天数: §e"
                    + user.getOrDefault(
                    "total_checkin_days", 0));
            sender.sendMessage("§7邀请码: §e"
                    + user.getOrDefault(
                    "invite_code", "无"));
            return true;
        }

        // del
        if (sub.equals("del")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /sdf1_login del <玩家>");
                return true;
            }
            db.deleteUser(args[1]);
            sender.sendMessage("§a已删除 " + args[1]);
            Player tp = Bukkit.getPlayer(args[1]);
            if (tp != null && tp.isOnline())
                tp.kickPlayer("§c账号已被管理员删除");
            return true;
        }

        // kick (挂机)
        if (sub.equals("kick")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(
                        "§e用法: /sdf1_login kick <开关> [分钟]");
                return true;
            }
            String mode = args[1].toLowerCase();
            if (mode.equals("on")
                    || mode.equals("开")
                    || mode.equals("启用")) {
                config.afkEnabled = true;
                if (args.length >= 3) {
                    try {
                        config.afkTimeout =
                                Integer.parseInt(
                                        args[2]) * 60;
                    } catch (NumberFormatException ignored) {
                    }
                }
                config.saveSettings();
                int minutes =
                        config.afkTimeout / 60;
                sender.sendMessage(
                        "§a挂机踢出已开启，超时: "
                                + minutes + " 分钟");
            } else if (mode.equals("off")
                    || mode.equals("关")
                    || mode.equals("停用")) {
                config.afkEnabled = false;
                config.saveSettings();
                sender.sendMessage(
                        "§c挂机踢出已关闭");
            } else {
                sender.sendMessage(
                        "§c无效参数 on/off");
            }
            return true;
        }

        // add
        if (sub.equals("add")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(
                        "§e用法: /sdf1_login add <玩家> <积分>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§c无效数字");
                return true;
            }
            db.addPoints(args[1], amount);
            sender.sendMessage("§a已为 " + args[1]
                    + " 增加 " + amount + " 积分");
            return true;
        }

        // ticket
        if (sub.equals("ticket")
                || sub.equals("工单")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            ticket.openMain((Player) sender);
            return true;
        }

        sender.sendMessage("§c未知参数");
        return true;
    }
    /**
     * 是否是垃圾站展示物品（带ID lore）
     */
    private boolean isTrashDisplayItem(
            ItemStack item) {
        if (item == null
                || item.getType()
                == Material.AIR)
            return false;
        if (!item.hasItemMeta())
            return false;
        ItemMeta im = item.getItemMeta();
        if (im == null || !im.hasLore())
            return false;
        List<String> lore = im.getLore();
        if (lore == null || lore.isEmpty())
            return false;
        return lore.get(0)
                .startsWith("§7ID: #");
    }

    private void sendChatHelp(Player p) {
        p.sendMessage(
                "§e§l======== 聊天过滤帮助 ==========");
        p.sendMessage("§e/chat reload §7- 重载配置");
        p.sendMessage("§e/chat add <URL> §7- 添加白名单URL");
        p.sendMessage("§e/chat remove <URL> §7- 移除白名单URL");
        p.sendMessage("§e/chat addplayer <玩家> §7- 添加白名单玩家");
        p.sendMessage("§e/chat takeplayer <玩家> §7- 移除白名单玩家");
        p.sendMessage("§e/chat unmute <玩家> §7- 解禁玩家");
        p.sendMessage("§e/chat reset <玩家> §7- 重置违规记录");
        p.sendMessage(
                "§e§l==================================");
    }
    private int findEmptySlot(Inventory inv) {
        for (int i = 0; i < 49; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null
                    || item.getType()
                    == Material.AIR)
                return i;
        }
        return -1;
    }

    // ===== TabComplete =====
    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command cmd,
            String label, String[] args) {
        List<String> list = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase(
                "sdf1_login")
                && args.length == 1) {
            if (isAdmin(sender)) {
                list.addAll(Arrays.asList(
                        "reload", "pw", "email",
                        "sign", "reset", "undo",
                        "get", "del", "set",
                        "take", "add", "kick",
                        "ticket", "oa", "stop",
                        "shopadd", "shopdel","back"));
            } else {
                list.addAll(Arrays.asList(
                        "pw", "email", "sign",
                        "reset", "undo",
                        "ticket", "oa", "stop"));
            }
        }
        if (cmd.getName().equalsIgnoreCase("chat")
                && args.length == 1
                && isAdmin(sender)) {
            list.addAll(Arrays.asList(
                    "reload", "add", "remove",
                    "addplayer", "takeplayer",
                    "unmute", "reset"));
        }
        return list;
    }

}
