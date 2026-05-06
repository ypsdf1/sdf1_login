package Sdf1_login;

import java.util.UUID;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Main extends JavaPlugin
        implements CommandExecutor, Listener, TabCompleter {

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

    private final Set<String> loggedIn =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Set<String> needsPasswordChange =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<UUID, Long> joinTime =
            new ConcurrentHashMap<>();
    private final Map<UUID, Location> joinLoc =
            new HashMap<>();
    private final Map<UUID, ItemStack[]> savedInventory =
            new HashMap<>();
    private final Map<UUID, ItemStack[]> savedArmor =
            new HashMap<>();
    private final Map<UUID, ItemStack[]> savedExtra =
            new HashMap<>();
    private final Map<UUID, Integer> savedLevel =
            new HashMap<>();
    private final Map<UUID, Float> savedExp =
            new HashMap<>();
    private final Map<String, Long> ipAutoLogin =
            new HashMap<>();
    private static final long IP_SKIP_MS = 300000L;

    private static class PwdRollback {
        final String hash;
        final String salt;
        final long time;
        PwdRollback(String h, String s, long t) {
            hash = h; salt = s; time = t;
        }
    }
    private final Map<String, PwdRollback> pwdRollback =
            new ConcurrentHashMap<>();

    public void recordPasswordChange(String name,
                                     String oldHash, String oldSalt) {
        pwdRollback.put(name,
                new PwdRollback(oldHash, oldSalt,
                        System.currentTimeMillis()));
    }

    public DatabaseManager getDb() { return db; }
    public ConfigManager getConfig2() { return config; }
    public EmailManager getEmail() { return email; }
    public Economy getEconomy() { return economy; }
    public LoginManager getLoginMgr() { return loginMgr; }
    public GiftManager getGift() { return gift; }
    public CheckInManager getCheckIn() { return checkIn; }
    public InviteManager getInvite() { return invite; }
    public PointsManager getPoints() { return points; }
    public GUIManager getGui() { return gui; }
    public ChatInputManager getChatInput() { return chatInput; }
    public Set<String> getLoggedIn() { return loggedIn; }
    public Set<String> getNeedsPasswordChange() { return needsPasswordChange; }

    /* ==================== 生命周期 ==================== */

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        config = new ConfigManager(getDataFolder());
        config.loadMessages();
        config.loadSmtp();
        config.loadSettings();
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
        setupEconomy();
        getCommand("sdf1_login").setExecutor(this);
        getCommand("sdf1_login").setTabCompleter(this);
        getCommand("reg").setExecutor(this);
        getCommand("reg").setTabCompleter(this);
        getCommand("login").setExecutor(this);
        getCommand("login").setTabCompleter(this);
        getCommand("l").setExecutor((s, c, la, a) -> true);
        getCommand("注册").setExecutor(this);
        getCommand("登录").setExecutor(this);
        getCommand("玩家信息").setExecutor(this);
        getCommand("改密码").setExecutor(this);
        getCommand("设置邮箱").setExecutor(this);
        getCommand("撤销").setExecutor(this);
        getCommand("删除").setExecutor(this);
        getCommand("找回密码").setExecutor(this);
        getCommand("签到").setExecutor(this);
        getCommand("debug").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        afk.startCheck();
        startLoginReminder();
        startTimeoutCheck();
        startPasswordReminder();
        getLogger().info("Sdf1_login v1.0 | 就绪");
    }

    @Override
    public void onDisable() {
        if (db != null) db.close();
    }

    private void setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private void startTimeoutCheck() {
        new BukkitRunnable() {
            public void run() {
                long now = System.currentTimeMillis();
                Iterator<Map.Entry<UUID, Long>> it = joinTime.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Long> entry = it.next();
                    if (now - entry.getValue() > config.loginTimeout * 1000L) {
                        Player p = Bukkit.getPlayer(entry.getKey());
                        if (p != null && p.isOnline() && !loggedIn.contains(p.getName())) {
                            p.kickPlayer(config.msg("login_timeout"));
                        }
                        it.remove();
                    }
                }
            }
        }.runTaskTimer(this, 80L, 80L);
    }

    private void startLoginReminder() {
        new BukkitRunnable() {
            public void run() {
                Iterator<UUID> it = joinTime.keySet().iterator();
                while (it.hasNext()) {
                    UUID uuid = it.next();
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) { it.remove(); continue; }
                    if (loggedIn.contains(p.getName())) { it.remove(); continue; }
                    if (!db.userExists(p.getName())) {
                        p.sendMessage(config.msg("not_registered"));
                    } else {
                        p.sendMessage(config.msg("not_logged_in"));
                    }
                }
            }
        }.runTaskTimer(this, 60L, 60L);
    }

    private void startPasswordReminder() {
        new BukkitRunnable() {
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (needsPasswordChange.contains(p.getName())) {
                        p.sendTitle("§c§l⚠ 请修改密码 ⚠",
                                "§f使用 /sdf1_login pw 修改密码", 10, 40, 10);
                        p.playSound(p.getLocation(),
                                Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
                    }
                }
            }
        }.runTaskTimer(this, 200L, 200L);
    }

    /* ==================== 工具 ==================== */

    private String getPlayerIP(Player p) {
        InetSocketAddress addr = p.getAddress();
        if (addr == null) return null;
        return addr.getAddress().getHostAddress();
    }

    private boolean canAutoLogin(Player p) {
        String ip = getPlayerIP(p);
        if (ip == null) return false;
        Long last = ipAutoLogin.get(ip);
        if (last == null) return false;
        return System.currentTimeMillis() - last < IP_SKIP_MS;
    }

    public void recordIPLogin(Player p) {
        String ip = getPlayerIP(p);
        if (ip != null) ipAutoLogin.put(ip, System.currentTimeMillis());
    }

    public void hideInventory(Player p) {
        savedInventory.put(p.getUniqueId(), p.getInventory().getContents());
        savedArmor.put(p.getUniqueId(), p.getInventory().getArmorContents());
        savedExtra.put(p.getUniqueId(), p.getInventory().getExtraContents());
        savedLevel.put(p.getUniqueId(), p.getLevel());
        savedExp.put(p.getUniqueId(), p.getExp());
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setExtraContents(new ItemStack[1]);
        p.setLevel(0);
        p.setExp(0f);
    }

    public void restoreInventory(Player p) {
        ItemStack[] inv = savedInventory.remove(p.getUniqueId());
        ItemStack[] armor = savedArmor.remove(p.getUniqueId());
        ItemStack[] extra = savedExtra.remove(p.getUniqueId());
        Integer level = savedLevel.remove(p.getUniqueId());
        Float exp = savedExp.remove(p.getUniqueId());
        if (inv != null) p.getInventory().setContents(inv);
        if (armor != null) p.getInventory().setArmorContents(armor);
        if (extra != null) p.getInventory().setExtraContents(extra);
        if (level != null) p.setLevel(level);
        if (exp != null) p.setExp(exp);
    }

    public boolean isFrozen(Player p) {
        return !loggedIn.contains(p.getName());
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getScoreboardTags()
                    .contains(config.adminTag);
        }
        return true;
    }

    public boolean dailySign(Player p) {
        if (checkIn.isCheckedInToday(p.getName())) return false;
        p.sendMessage(checkIn.checkIn(p));
        invite.onInviteeCheckIn(p.getName());
        return true;
    }

    public void openTaskPanel(Player p) {
        Inventory g = Bukkit.createInventory(null, 27, "§6§l任务面板");
        ItemStack gl = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glm = gl.getItemMeta();
        if (glm != null) { glm.setDisplayName(" "); gl.setItemMeta(glm); }
        for (int i = 0; i < 27; i++) g.setItem(i, gl);
        Map<String, Object> user = db.getUser(p.getName());
        int stage = ((Number) user.getOrDefault("gift_stage", 0)).intValue();
        String claimed = (String) user.getOrDefault("gift_claimed", "");
        g.setItem(11, mkItem(Material.BOOK, "§e新人礼包进度",
                "§7当前阶段: §e" + stage,
                "§7已领取: §a" + (claimed.isEmpty() ? "无" : claimed)));
        g.setItem(15, mkItem(Material.CLOCK, "§b签到", "§7今日签到获取积分"));
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    private ItemStack mkItem(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    /* ==================== 指令 ==================== */

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        String cn = cmd.getName();

        if (cn.equalsIgnoreCase("debug")) {
            if (!isAdmin(sender)) { sender.sendMessage(config.msg("admin_no_permission")); return true; }
            if (args.length < 2) { sender.sendMessage("§c用法: /debug <玩家> take.<阶段>"); return true; }
            String target = args[0];
            String action = args[1].toLowerCase();
            if (action.startsWith("take.")) {
                try {
                    int stg = Integer.parseInt(action.substring(5));
                    db.setField(target, "gift_stage", stg);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= stg; i++) sb.append("[").append(i).append("]");
                    db.setField(target, "gift_claimed", sb.toString());
                    db.setField(target, "tasks_completed", sb.toString());
                    sender.sendMessage("§a[Debug] §f已将 " + target + " 设为礼包阶段 " + stg);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§c[Debug] §f阶段号无效");
                }
            } else {
                sender.sendMessage("§c[Debug] §f未知操作: " + action);
            }
            return true;
        }

        if (cn.equals("注册")) {
            if (!(sender instanceof Player)) return true;
            return loginMgr.handleRegister((Player) sender, args);
        }
        if (cn.equals("登录")) {
            if (!(sender instanceof Player)) return true;
            return loginMgr.handleLogin((Player) sender, args);
        }
        if (cn.equals("玩家信息")) {
            if (!(sender instanceof Player)) return true;
            gui.openMain((Player) sender);
            return true;
        }
        if (cn.equals("改密码")) {
            if (!(sender instanceof Player)) return true;
            String[] na = new String[args.length + 1];
            na[0] = "pw";
            System.arraycopy(args, 0, na, 1, args.length);
            return handleSubCommand((Player) sender, na);
        }
        if (cn.equals("设置邮箱")) {
            if (!(sender instanceof Player)) return true;
            String[] na = new String[args.length + 1];
            na[0] = "email";
            System.arraycopy(args, 0, na, 1, args.length);
            return handleSubCommand((Player) sender, na);
        }
        if (cn.equals("撤销")) {
            if (!(sender instanceof Player)) return true;
            String[] na = new String[args.length + 1];
            na[0] = "undo";
            System.arraycopy(args, 0, na, 1, args.length);
            return handleSubCommand((Player) sender, na);
        }
        if (cn.equals("删除")) {
            if (!(sender instanceof Player)) {
                if (args.length < 2) { sender.sendMessage("§c用法: /删除 <玩家> <管理密码>"); return true; }
                if (args[1].equals(config.adminPassword)) {
                    db.deleteUser(args[0]);
                    sender.sendMessage("§a[Sdf1_login] §f玩家 " + args[0] + " 已删除");
                } else {
                    sender.sendMessage("§c[Sdf1_login] §f管理密码错误！");
                }
                return true;
            }
            String[] na = new String[args.length + 1];
            na[0] = "del";
            System.arraycopy(args, 0, na, 1, args.length);
            return handleSubCommand((Player) sender, na);
        }
        if (cn.equals("找回密码")) {
            if (!(sender instanceof Player)) return true;
            return loginMgr.handleReset((Player) sender);
        }
        if (cn.equals("签到")) {
            if (!(sender instanceof Player)) return true;
            Player pl = (Player) sender;
            if (dailySign(pl)) gui.openTaskCenter(pl);
            else pl.sendMessage(config.msg("checkin_already"));
            return true;
        }
        if (cn.equalsIgnoreCase("reg")) {
            if (!(sender instanceof Player)) return true;
            return loginMgr.handleRegister((Player) sender, args);
        }
        if (cn.equalsIgnoreCase("login")) {
            if (!(sender instanceof Player)) return true;
            return loginMgr.handleLogin((Player) sender, args);
        }
        if (cn.equalsIgnoreCase("sdf1_login")) {
            if (args.length == 0) {
                if (!(sender instanceof Player)) return true;
                gui.openMain((Player) sender);
                return true;
            }
            if (args[0].equalsIgnoreCase("kick") && !(sender instanceof Player)) {
                return handleAfkCommand(sender, args);
            }
            if (args[0].equalsIgnoreCase("del") && !(sender instanceof Player)) {
                if (args.length < 3) { sender.sendMessage("§c用法: /sdf1_login del <玩家> <管理密码>"); return true; }
                if (args[2].equals(config.adminPassword)) {
                    db.deleteUser(args[1]);
                    sender.sendMessage("§a[Sdf1_login] §f玩家 " + args[1] + " 已删除");
                } else {
                    sender.sendMessage("§c[Sdf1_login] §f管理密码错误！");
                }
                return true;
            }
            if (!(sender instanceof Player)) return true;
            return handleSubCommand((Player) sender, args);
        }
        return false;
    }

    /* ==================== 子命令 ==================== */

    private boolean handleSubCommand(Player p, String[] args) {
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reset":
                return loginMgr.handleReset(p);
            case "email":
                if (args.length < 2) {
                    chatInput.getState(p).type = ChatInputManager.InputType.SET_EMAIL;
                    p.sendMessage("§e[Sdf1_login] §f请输入邮箱地址:");
                    return true;
                }
                if (!args[1].contains("@")) { p.sendMessage(config.msg("email_invalid")); return true; }
            {
                String code = PasswordUtils.generateVerifyCode();
                chatInput.setVerifyCode(p, code, "email");
                email.sendVerifyCode(args[1], p.getName(), code);
                chatInput.getState(p).tmpStr = args[1];
                chatInput.getState(p).type = ChatInputManager.InputType.EMAIL_VERIFY;
                p.sendMessage(config.msg("email_verify_sent", "email", args[1]));
            }
            return true;
            case "password":
            case "pw":
                if (!loggedIn.contains(p.getName())) { p.sendMessage(config.msg("not_logged_in")); return true; }
                if (args.length < 4) { p.sendMessage("§c用法: /sdf1_login pw <旧密码> <新密码> <确认新密码>"); return true; }
            {
                String newPwd = args[2];
                String cfmPwd = args[3];
                if (!newPwd.equals(cfmPwd)) { p.sendMessage("§c[Sdf1_login] §f两次新密码不一致"); return true; }
                if (!PasswordUtils.validate(newPwd)) { p.sendMessage(config.msg("password_invalid")); return true; }
                String pwdSalt = (String) db.getField(p.getName(), "password_salt");
                if (pwdSalt == null) return true;
                String oldHash = PasswordUtils.hash(args[1], pwdSalt);
                if (db.checkPasswordOrTemp(p.getName(), oldHash)) {
                    String oldH = (String) db.getField(p.getName(), "password_hash");
                    String oldS = (String) db.getField(p.getName(), "password_salt");
                    recordPasswordChange(p.getName(), oldH, oldS);
                    String nSalt = PasswordUtils.generateSalt();
                    String nHash = PasswordUtils.hash(newPwd, nSalt);
                    db.setField(p.getName(), "password_hash", nHash);
                    db.setField(p.getName(), "password_salt", nSalt);
                    db.setField(p.getName(), "temp_password", "");
                    db.setField(p.getName(), "temp_pw_expire", 0L);
                    needsPasswordChange.remove(p.getName());
                    p.sendMessage("§a[Sdf1_login] §f密码修改成功！");
                    p.sendMessage("§73分钟内可用 /sdf1_login undo <旧密码> 撤销");
                } else {
                    p.sendMessage("§c[Sdf1_login] §f旧密码错误！");
                }
            }
            return true;
            case "undo":
                if (!loggedIn.contains(p.getName())) { p.sendMessage(config.msg("not_logged_in")); return true; }
                if (args.length < 2) { p.sendMessage("§c用法: /sdf1_login undo <旧密码>"); return true; }
            {
                PwdRollback rb = pwdRollback.get(p.getName());
                if (rb == null) { p.sendMessage("§c[Sdf1_login] §f没有可撤销的密码修改"); return true; }
                if (System.currentTimeMillis() - rb.time > 180000L) {
                    pwdRollback.remove(p.getName());
                    p.sendMessage("§c[Sdf1_login] §f已超过3分钟，无法撤销");
                    return true;
                }
                if (!PasswordUtils.hash(args[1], rb.salt).equals(rb.hash)) {
                    p.sendMessage("§c[Sdf1_login] §f旧密码错误");
                    return true;
                }
                db.setField(p.getName(), "password_hash", rb.hash);
                db.setField(p.getName(), "password_salt", rb.salt);
                db.setField(p.getName(), "temp_password", "");
                db.setField(p.getName(), "temp_pw_expire", 0L);
                needsPasswordChange.remove(p.getName());
                pwdRollback.remove(p.getName());
                p.sendMessage("§a[Sdf1_login] §f密码已回滚到修改前");
            }
            return true;
            case "set":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                if (args.length < 3) { p.sendMessage("§c用法: /sdf1_login set <玩家> <密码>"); return true; }
            {
                String sSalt = PasswordUtils.generateSalt();
                String sHash = PasswordUtils.hash(args[2], sSalt);
                db.setField(args[1], "password_hash", sHash);
                db.setField(args[1], "password_salt", sSalt);
                db.setField(args[1], "temp_password", "");
                p.sendMessage(config.msg("admin_set_password", "user", args[1]));
            }
            return true;
            case "del":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                if (args.length < 2) { p.sendMessage("§c用法: /sdf1_login del <玩家> [密码]"); return true; }
                if (args.length >= 3) {
                    String dSalt = (String) db.getField(p.getName(), "password_salt");
                    if (dSalt == null) return true;
                    if (db.checkPassword(p.getName(), PasswordUtils.hash(args[2], dSalt))) {
                        db.deleteUser(args[1]);
                        p.sendMessage(config.msg("admin_delete_success", "user", args[1]));
                    } else {
                        p.sendMessage(config.msg("admin_delete_failed"));
                    }
                    return true;
                }
                chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_DELETE_CONFIRM;
                chatInput.getState(p).targetPlayer = args[1];
                p.sendMessage(config.msg("admin_delete_confirm"));
                p.sendMessage("§c格式: <玩家名> <你的密码>");
                return true;
            case "take":
                openTaskPanel(p);
                return true;
            case "sign":
                if (!loggedIn.contains(p.getName())) { p.sendMessage(config.msg("not_logged_in")); return true; }
                if (dailySign(p)) gui.openTaskCenter(p);
                else p.sendMessage(config.msg("checkin_already"));
                return true;
            case "kick":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                handleAfkCommand(p, args);
                return true;
            case "add":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                if (args.length < 2) { p.sendMessage("§c用法: /sdf1_login add <玩家>"); return true; }
                config.afkWhitelist.add(args[1]);
                p.sendMessage(config.msg("afk_whitelist_added", "user", args[1]));
                return true;
            case "remove":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                if (args.length < 2) { p.sendMessage("§c用法: /sdf1_login remove <玩家>"); return true; }
                config.afkWhitelist.remove(args[1]);
                p.sendMessage(config.msg("afk_whitelist_removed", "user", args[1]));
                return true;
            case "get":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                if (args.length < 2) { p.sendMessage("§c用法: /sdf1_login get <玩家>"); return true; }
            {
                Map<String, Object> gUser = db.getUser(args[1]);
                if (gUser.isEmpty()) { p.sendMessage("§c未找到玩家: " + args[1]); return true; }
                p.sendMessage("§e" + args[1] + " 礼包阶段: "
                        + gUser.getOrDefault("gift_stage", 0)
                        + " 已领: " + gUser.getOrDefault("gift_claimed", ""));
            }
            return true;
            case "reload":
                if (!isAdmin(p)) { p.sendMessage(config.msg("admin_no_permission")); return true; }
                config.loadMessages(); config.loadSmtp(); config.loadSettings(); gift.loadStages();
                p.sendMessage("§a[Sdf1_login] §f配置已重载");
                return true;
            case "open":
                gui.openMain(p);
                return true;
            default:
                sendHelp(p);
                return true;
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage(config.msg("help_header"));
        p.sendMessage(config.msg("help_1"));
        p.sendMessage(config.msg("help_2"));
        p.sendMessage(config.msg("help_3"));
        p.sendMessage(config.msg("help_4"));
        p.sendMessage(config.msg("help_5"));
        if (isAdmin(p)) {
            p.sendMessage(config.msg("help_6"));
            p.sendMessage(config.msg("help_7"));
            p.sendMessage(config.msg("help_8"));
            p.sendMessage(config.msg("help_9"));
            p.sendMessage(config.msg("help_10"));
            p.sendMessage(config.msg("help_11"));
        }
    }

    /* ==================== AFK ==================== */

    private boolean handleAfkCommand(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String param = args[1].toLowerCase();
            if (isAfkOn(param)) { config.afkEnabled = true; sendFeedback(sender, config.msg("afk_set_enabled")); }
            else if (isAfkOff(param)) { config.afkEnabled = false; sendFeedback(sender, config.msg("afk_set_disabled")); }
        }
        if (args.length >= 3) {
            int seconds = parseAfkTimeToSeconds(args[2]);
            if (seconds > 0) { config.afkTimeout = seconds; sendFeedback(sender, config.msg("afk_set_time", "duration", (seconds / 60) + "分钟")); }
        }
        config.saveSettings();
        return true;
    }

    private boolean isAfkOn(String s) {
        return s.equals("on") || s.equals("开") || s.equals("启用") || s.equals("开启") || s.equals("true");
    }

    private boolean isAfkOff(String s) {
        return s.equals("off") || s.equals("关") || s.equals("停用") || s.equals("关闭") || s.equals("false");
    }

    private int parseAfkTimeToSeconds(String input) {
        String s = input.toLowerCase().trim();
        if (s.contains("小时") || s.contains("时")) { String n = s.replaceAll("[^0-9]", ""); if (!n.isEmpty()) return Integer.parseInt(n) * 3600; }
        if (s.contains("分钟") || s.contains("分")) { String n = s.replaceAll("[^0-9]", ""); if (!n.isEmpty()) return Integer.parseInt(n) * 60; }
        if (s.endsWith("h")) { String n = s.replaceAll("[^0-9]", ""); if (!n.isEmpty()) return Integer.parseInt(n) * 3600; }
        if (s.endsWith("m")) { String n = s.replaceAll("[^0-9]", ""); if (!n.isEmpty()) return Integer.parseInt(n) * 60; }
        String n = s.replaceAll("[^0-9]", "");
        if (!n.isEmpty()) return Integer.parseInt(n) * 60;
        return -1;
    }

    private void sendFeedback(CommandSender sender, String message) {
        sender.sendMessage(message);
        getLogger().info(message.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]", ""));
    }

    /* ==================== 事件 ==================== */

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String name = p.getName();
        joinLoc.put(p.getUniqueId(), p.getLocation().clone());
        if (canAutoLogin(p) && db.userExists(name)) {
            loggedIn.add(name);
            db.setLoggedIn(name, true);
            db.setField(name, "last_login_time", System.currentTimeMillis());
            db.setField(name, "last_online_check", System.currentTimeMillis());
            p.sendMessage(config.msg("auto_login_ip", "user", name));
            joinTime.remove(p.getUniqueId());
            try {
                org.bukkit.plugin.Plugin cy = Bukkit.getPluginManager().getPlugin("CY_beibao");
                if (cy != null && cy.isEnabled()) {
                    cy.getClass().getMethod("onSdf1Activation", String.class, int.class, int.class)
                            .invoke(cy, name, 0, 0);
                }
            } catch (Exception ignored) {}
            return;
        }
        hideInventory(p);
        if (!db.userExists(name)) p.sendMessage(config.msg("not_registered"));
        else p.sendMessage(config.msg("not_logged_in"));
        joinTime.put(p.getUniqueId(), System.currentTimeMillis());
        db.setField(name, "last_online_check", System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        String name = p.getName();
        try {
            Object lastObj = db.getField(name, "last_online_check");
            long last = lastObj != null ? ((Number) lastObj).longValue() : 0;
            if (last > 0) {
                long elapsed = System.currentTimeMillis() - last;
                Object totalObj = db.getField(name, "total_online_time");
                long total = totalObj != null ? ((Number) totalObj).longValue() : 0;
                db.setField(name, "total_online_time", total + elapsed);
            }
        } catch (Exception ignored) {}
        loggedIn.remove(name);
        needsPasswordChange.remove(name);
        joinTime.remove(p.getUniqueId());
        joinLoc.remove(p.getUniqueId());
        savedInventory.remove(p.getUniqueId());
        savedArmor.remove(p.getUniqueId());
        savedExtra.remove(p.getUniqueId());
        savedLevel.remove(p.getUniqueId());
        savedExp.remove(p.getUniqueId());
        afk.remove(p.getUniqueId());
        chatInput.clear(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (isFrozen(p)) {
            Location from = e.getFrom();
            Location to = e.getTo();
            if (to == null) return;
            if (from.getBlockX() != to.getBlockX()
                    || from.getBlockY() != to.getBlockY()
                    || from.getBlockZ() != to.getBlockZ()) {
                Location saved = joinLoc.get(p.getUniqueId());
                if (saved != null) e.setTo(saved.clone());
            }
            return;
        }
        afk.recordAction(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        afk.recordAction(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if (isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        String name = e.getPlayer().getName();
        db.setField(name, "blocks_broken",
                ((Number) db.getField(name, "blocks_broken")).intValue() + 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (isFrozen(e.getPlayer())) { e.setCancelled(true); return; }
        String name = e.getPlayer().getName();
        db.setField(name, "blocks_placed",
                ((Number) db.getField(name, "blocks_placed")).intValue() + 1);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (chatInput.handleInput(e.getPlayer(), e.getMessage(), this)) {
            e.setCancelled(true);
            return;
        }
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String raw = e.getMessage();
        String msg = raw.toLowerCase();
        if (msg.equals("/l") || msg.startsWith("/l ")) {
            e.setCancelled(true);
            String argStr = raw.length() > 2 ? raw.substring(2).trim() : "";
            loginMgr.handleLogin(p, argStr.isEmpty() ? new String[0] : argStr.split("\\s+"));
            return;
        }
        if (isFrozen(p)) {
            String[] white = { "/reg", "/login", "/sdf1_login", "/l",
                    "/注册", "/登录", "/改密码", "/设置邮箱",
                    "/撤销", "/玩家信息", "/删除", "/找回密码", "/签到" };
            boolean pass = false;
            for (String w : white) {
                if (msg.equals(w) || msg.startsWith(w + " ")) { pass = true; break; }
            }
            if (!pass) { e.setCancelled(true); p.sendMessage(config.msg("not_logged_in")); }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        if (isFrozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && isFrozen((Player) e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && isFrozen((Player) e.getDamager())) {
            e.setCancelled(true);
        }
    }

    /* ==================== GUI ==================== */

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p)) { e.setCancelled(true); return; }
        String t = e.getView().getTitle();
        int r = e.getRawSlot();

        /* 临时密码强制改密检查 */
        if (needsPasswordChange.contains(p.getName())
                && !t.equals("§6§l任务面板")) {
            e.setCancelled(true);
            p.sendMessage("§c[Sdf1_login] §f请先修改密码！");
            p.sendMessage("§7用法: /sdf1_login pw <旧密码> <新密码> <确认新密码>");
            return;
        }

        /* 主线/支线任务面板禁止取出物品 */
        if (t.equals("§d§l主线任务") || t.equals("§d§l支线任务")) {
            e.setCancelled(true);
            int size = e.getInventory().getSize();
            if (r == size - 1) gui.openTaskCenter(p);
            return;
        }

        /* 主界面 */
        if (t.equals(GUIManager.T_MAIN)) {
            e.setCancelled(true);
            if (r == 10) gui.openMyInfo(p);
            else if (r == 12) gui.openInvite(p);
            else if (r == 14) p.openInventory(points.createShopGUI(p));
            else if (r == 16) gui.openTaskCenter(p);
            else if (r == 22 && isAdmin(p)) {
                p.closeInventory();
                chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_AUTH;
                p.sendMessage("§e[Sdf1_login] §f请输入管理密码:");
            }
            return;
        }

        /* 个人信息 */
        if (t.equals(GUIManager.T_MY_INFO)) {
            e.setCancelled(true);
            if (r == 26) {
                gui.openMain(p);
            } else if (r == 10) {
                if (checkIn.isCheckedInToday(p.getName())) {
                    p.sendMessage(config.msg("checkin_already"));
                } else {
                    p.sendMessage(checkIn.checkIn(p));
                    invite.onInviteeCheckIn(p.getName());
                    gui.openMyInfo(p);
                }
            } else if (r == 11) {
                chatInput.getState(p).type = ChatInputManager.InputType.CHANGE_PWD_STEP1;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f请输入当前密码:");
            } else if (r == 12) {
                chatInput.getState(p).type = ChatInputManager.InputType.BACK_CHECKIN;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f请输入补签日期 (yyyy-MM-dd):");
            } else if (r == 13) {
                String code = (String) db.getField(p.getName(), "invite_code");
                if (code == null || code.isEmpty()) code = invite.generateCode(p);
                p.sendMessage(config.msg("invite_code_generated", "code", code));
            } else if (r == 14) {
                chatInput.getState(p).type = ChatInputManager.InputType.SET_EMAIL;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f请输入邮箱地址:");
            } else if (r == 15) {
                p.openInventory(points.createShopGUI(p));
            }
            return;
        }

        /* 管理面板 */
        if (t.equals(GUIManager.T_ADMIN)) {
            e.setCancelled(true);
            if (r == 22) {
                gui.openMain(p);
            } else if (r == 10) {
                chatInput.getState(p).type = ChatInputManager.InputType.SMTP_STEP1;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f当前SMTP: " + config.getSmtp("smtp地址"));
                p.sendMessage("§e[Sdf1_login] §f输入新地址（输入0跳过）:");
            } else if (r == 11) {
                gui.openUserManagement(p);
            } else if (r == 12) {
                gui.openMyInfo(p);
            } else if (r == 14) {
                p.openInventory(points.createShopGUI(p));
            } else if (r == 16) {
                if (e.getClick().isLeftClick()) {
                    config.afkEnabled = !config.afkEnabled;
                    p.sendMessage(config.msg(config.afkEnabled ? "afk_set_enabled" : "afk_set_disabled"));
                    config.saveSettings();
                    gui.openAdmin(p);
                } else if (e.getClick().isRightClick()) {
                    p.closeInventory();
                    chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_SET_AFK_TIME;
                    p.sendMessage("§e[Sdf1_login] §f输入挂机踢出时长（如: 5分钟、10m、2小时）:");
                }
            }
            return;
        }

        /* 任务中心 */
        if (t.equals(GUIManager.T_TASK_CENTER)) {
            e.setCancelled(true);
            if (r == 22) gui.openMain(p);
            else if (r == 10) gui.openGiftStages(p);
            else if (r == 12) gui.openTaskList(p, "主线任务");
            else if (r == 14) gui.openTaskList(p, "支线任务");
            else if (r == 16) {
                if (dailySign(p)) gui.openTaskCenter(p);
                else p.sendMessage(config.msg("checkin_already"));
            }
            return;
        }

        /* 新人任务阶段 */
        if (t.equals(GUIManager.T_GIFT_STAGES)) {
            e.setCancelled(true);
            if (r == 26) {
                gui.openTaskCenter(p);
            } else if (r >= 10 && r <= 18) {
                int stage = r - 9;
                if (gift.canClaim(p, stage)) {
                    gift.claimReward(p, stage);
                    gui.openGiftStages(p);
                } else {
                    p.sendMessage(config.msg("gift_not_ready"));
                }
            }
            return;
        }

        /* 邀请 */
        if (t.equals(GUIManager.T_INVITE)) {
            e.setCancelled(true);
            if (r == 26) {
                gui.openMain(p);
            } else if (r == 10) {
                String code = invite.generateCode(p);
                p.sendMessage(config.msg("invite_code_generated", "code", code));
                gui.openInvite(p);
            } else if (r == 16) {
                chatInput.getState(p).type = ChatInputManager.InputType.INVITE_INPUT_CODE;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f请输入邀请码:");
            }
            return;
        }

        /* 玩家管理列表 */
        if (t.equals(GUIManager.T_USER_MGMT)) {
            e.setCancelled(true);
            if (r == 53) {
                gui.openAdmin(p);
            } else if (r >= 0 && r < 45) {
                ItemStack item = e.getInventory().getItem(r);
                if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    gui.openUserDetail(p, item.getItemMeta().getDisplayName().replace("§e", "").replace("§a", ""));
                }
            }
            return;
        }

        /* 玩家详情 */
        if (t.startsWith("§e§l管理: ")) {
            e.setCancelled(true);
            String prefix = "§e§l管理: ";
            if (r == 22) {
                gui.openUserManagement(p);
            } else if (r == 10) {
                String target = t.substring(prefix.length());
                chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_SET_POINTS;
                chatInput.getState(p).targetPlayer = target;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f输入" + target + "的新积分值:");
            } else if (r == 12) {
                String target = t.substring(prefix.length());
                chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_SET_PWD;
                chatInput.getState(p).targetPlayer = target;
                p.closeInventory();
                p.sendMessage("§e[Sdf1_login] §f输入" + target + "的新密码:");
            } else if (r == 16) {
                String target = t.substring(prefix.length());
                chatInput.getState(p).type = ChatInputManager.InputType.ADMIN_DELETE_CONFIRM;
                chatInput.getState(p).targetPlayer = target;
                p.closeInventory();
                p.sendMessage(config.msg("admin_delete_confirm"));
                p.sendMessage("§c格式: <玩家名> <你的密码>");
            }
            return;
        }

        /* 积分商城 */
        if (t.startsWith("§d§l积分商城")) {
            e.setCancelled(true);
            if (r == 49) gui.openMain(p);
            else points.handleClick(p, r);
            return;
        }

        /* CY背包商城 */
        if (t.startsWith("§d§lCY背包商城")) {
            e.setCancelled(true);
            int size = e.getInventory().getSize();
            if (r == size - 1) p.openInventory(points.createShopGUI(p));
            else points.handleCYClick(p, r);
            return;
        }

        /* 任务面板 */
        if (t.equals("§6§l任务面板")) {
            e.setCancelled(true);
            if (r == 22) gui.openMain(p);
            else if (r == 15) {
                if (dailySign(p)) openTaskPanel(p);
                else p.sendMessage(config.msg("checkin_already"));
            }
            return;
        }
    } // onInvClick 结束

    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p)) { e.setCancelled(true); return; }
        String t = e.getView().getTitle();
        if (t.equals("§d§l主线任务") || t.equals("§d§l支线任务")) {
            e.setCancelled(true);
        }
    }

    /* ==================== TabComplete ==================== */

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd,
                                      String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("sdf1_login")) return null;
        List<String> opts = new ArrayList<>();
        boolean op = isAdmin(sender);
        if (args.length == 1) {
            opts.addAll(Arrays.asList("open", "reset", "email", "password", "pw", "undo", "take", "sign"));
            if (op) opts.addAll(Arrays.asList("set", "del", "kick", "add", "remove", "get", "reload"));
            return filterTab(opts, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (op && (sub.equals("set") || sub.equals("del") || sub.equals("get")
                    || sub.equals("add") || sub.equals("remove"))) {
                for (Player op2 : Bukkit.getOnlinePlayers()) opts.add(op2.getName());
            }
            if (op && sub.equals("kick")) {
                opts.addAll(Arrays.asList("on", "off", "开", "关", "启用", "停用"));
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("kick")) {
            opts.add("<时间>");
        }
        return filterTab(opts, args[args.length - 1]);
    }

    private List<String> filterTab(List<String> opts, String prefix) {
        List<String> r = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) r.add(o);
        }
        return r;
    }
}
