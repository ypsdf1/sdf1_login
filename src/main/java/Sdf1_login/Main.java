package Sdf1_login;


import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.io.File;

import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import java.util.Date;
import org.bukkit.command.CommandSender;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;


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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;



public class Main extends JavaPlugin
        implements CommandExecutor, Listener,
        TabCompleter {

    private DatabaseManager db;
    private MenuIconManager menuIconMgr;
    private ConfigManager config;
    private EmailManager email;
    private LoginManager loginMgr;
    private AFKManager afk;
    private GiftManager gift;
    private CheckInManager checkIn;
    private InviteManager invite;
    private PointsManager points;
    public GUIManager gui;
    private ChatInputManager chatInput;
    private Economy economy;
    private VerificationManager verification;
    private IPGroupManager ipGroup;
    private AccountRequestManager accountRequest;
    private TicketManager ticket;
    private ChatFilterManager chatFilter;
    private GarbageManager garbage;
    public RadioManager radio;
    // 在 lastActivity 字段后面添加：
    // [ADDED] 任务追踪系统
    private QuestTracker questTracker;
    // [FIX] 垃圾箱防抖锁（记录上次操作时间戳）
    private final Map<UUID, Long> garbageBusy =
            new ConcurrentHashMap<>();
    // [ADDED] 群系检测节流
    private final Map<UUID, Long> lastBiomeCheck =
            new ConcurrentHashMap<>();
    // [ADDED] 垃圾站宝箱物品投入次数计数器（事不过三，按物品单独计数）
    private final Map<String, Integer> treasureDiscardCount =
            new ConcurrentHashMap<>();
    // 在 private WelcomeManager welcome; 字段（若不存在）添加：
    private WelcomeManager welcome;
    private final Set<String> pendingAdminAuth =
            new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    /*
    资源包下载部分控制，不经过RadioDownloadListener的熔断控制
     */
    private RadioDownloadListener radioDL;
    private final Set<String> chatInputActive =
            ConcurrentHashMap.newKeySet();
    // 补签确认：玩家名 → "日期:总积分"
    public final Map<String, String>
            pendingBackCheck =
            new HashMap<>();
    public MenuManager menu;

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
    private SalesStatsManager salesStats;

    private final Map<UUID, Long> lastActivity =
            new ConcurrentHashMap<>();
    private CommissionManager commission;
    // 余额操作待处理
    private String balanceTarget = null;
    private boolean balanceGiveMode = false;
    private BondManager bondManager;
    private TreasureBridge treasureBridge;
    private CDKManager cdkManager;
    private UserGroupManager userGroupManager;
    private CypayCommand cypayCommand;
    private UpdateChecker updateChecker;

    public BondManager getBonds() {
        return bondManager;
    }

    public CDKManager getCDK() {
        return cdkManager;
    }

    public UserGroupManager getUserGroup() {
        return userGroupManager;
    }

    public CypayCommand getCypay() {
        return cypayCommand;
    }

    // 在 Main.java 字段声明区域（bondManager 附近）添加：
// 在 bondManager 字段附近添加：
    private BondPrinter bondPrinter;
    private OrderManager orderManager;

    public OrderManager getOrderManager() {
        return orderManager;
    }

    public AreaProtection areaProtection;


    // 钱包流水查看目标（玩家名 → 查看目标）
    private final Map<String, String> walletViewTarget =
            new ConcurrentHashMap<>();

    // 流水格式化
    private static final java.text.SimpleDateFormat
            TX_SDF = new java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss");

    private ShopManager shopManager; //商店


    public CommissionManager getCommission() {
        return commission;
    }

    //pvp
    private PVPManager pvpManager;

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

    // 待删除的玩家名（仅保留最后一个）
    private String pendingDeleteName = null;
    private BukkitRunnable pendingDeleteTask = null;

    private final Map<String, PwdRollback>
            pwdRollback =
            new ConcurrentHashMap<>();
    private final Map<String, String> menuChatField =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> menuChatIdx =
            new java.util.concurrent.ConcurrentHashMap<>();


    public void recordPasswordChange(String name,
                                     String oldHash, String oldSalt) {
        pwdRollback.put(name,
                new PwdRollback(oldHash, oldSalt,
                        System.currentTimeMillis()));
    }

    // ===== Getters =====
    public DatabaseManager getDb() {
        return db;
    }


    public ConfigManager getConfig2() {
        return config;
    }

    public EmailManager getEmail() {
        return email;
    }

    public Economy getEconomy() {
        return economy;
    }

    public LoginManager getLoginMgr() {
        return loginMgr;
    }

    public AFKManager getAfk() {
        return afk;
    }

    public GiftManager getGift() {
        return gift;
    }

    public CheckInManager getCheckIn() {
        return checkIn;
    }

    public InviteManager getInvite() {
        return invite;
    }

    public PointsManager getPoints() {
        return points;
    }

    public GUIManager getGui() {
        return gui;
    }

    public ChatInputManager getChatInput() {
        return chatInput;
    }

    public VerificationManager getVerification() {
        return verification;
    }

    public IPGroupManager getIPGroup() {
        return ipGroup;
    }

    public AccountRequestManager getAccountRequest() {
        return accountRequest;
    }

    public TicketManager getTicket() {
        return ticket;
    }

    public QuestTracker getQuestTracker() {
        return questTracker;
    }

    public ChatFilterManager getChatFilter() {
        return chatFilter;
    }

    public GarbageManager getGarbage() {
        return garbage;
    }

    public Set<String> getLoggedIn() {
        return loggedIn;
    }

    public Set<String> getNeedsPasswordChange() {
        return needsPasswordChange;
    }


    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        if (areaProtection != null) {
            Player p = e.getPlayer();
            areaProtection.onPlayerOffline(
                    p.getUniqueId(), p.getName(), "");
            if (areaProtection != null) {
                areaProtection.onPlayerQuit(p.getName());
            }

        }
    }


    // public RadioManager radio;
    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        // 启动时清理.sdf1临时文件
        cleanupSdf1Files();
        salesStats = new SalesStatsManager(this);

        // ===== 1. 基础配置 =====
        config = new ConfigManager(getDataFolder());
        config.loadMessages();
        config.loadSmtp();
        config.loadSettings();
        configMgr = new ConfigManager(getDataFolder());
        configMgr.loadSettings();
        configMgr.loadMessages();
        orderManager = new OrderManager(this);
        // ===== 2. 数据库 =====
        db = new DatabaseManager(getDataFolder());
        db.init();
        menuIconMgr = new MenuIconManager(this);


        // ===== 3. 菜单 =====
        menu = new MenuManager(this);
        menu.loadMenu();

        // ===== 4. GUI（依赖 menu） =====
        gui = new GUIManager(this);
        shopManager = new ShopManager(this);
        Bukkit.getPluginManager().registerEvents(shopManager, this);
        // shopManager.loadCategories();  // 构造器内已调用，此处重复

        getServer().getPluginManager()
                .registerEvents(gui, this);

        // ===== 5. 其他管理器 =====
        email = new EmailManager(config);
        loginMgr = new LoginManager(this);
        afk = new AFKManager(config, this);
        gift = new GiftManager(this);
        gift.loadStages();
        checkIn = new CheckInManager(this);
        invite = new InviteManager(this);
        points = new PointsManager(this);
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
        commission = new CommissionManager(this);
        questTracker = new QuestTracker(this);
        treasureBridge = new TreasureBridge();
        treasureBridge.hook();
        chatFilter = new ChatFilterManager(this);
        chatFilter.loadConfig();
        welcome = new WelcomeManager(this);

        // ===== 6. 垃圾箱 =====
        garbage = new GarbageManager(this);
        garbage.init();
        garbage.loadConfig();
        garbage.startAutoCleanup();

        // ===== 7. Radio =====
        radio = new RadioManager(this);
        radio.init();
        radioDL = new RadioDownloadListener(this);
        getServer().getPluginManager()
                .registerEvents(radioDL, this);

        // ===== 铁块电梯 =====
        getServer().getPluginManager()
                .registerEvents(new IronBlockElevator(), this);

        // ===== 8. 经济 =====
        setupEconomy();

        // ===== 9. 注册命令 =====
        if (getCommand("sdf1_login") != null) {
            getCommand("sdf1_login")
                    .setExecutor(this);
            getCommand("sdf1_login")
                    .setTabCompleter(this);
        }
        if (getCommand("reg") != null)
            getCommand("reg").setExecutor(this);
        if (getCommand("login") != null)
            getCommand("login").setExecutor(this);
        if (getCommand("l") != null)
            getCommand("l").setExecutor(this);
        if (getCommand("签到") != null)
            getCommand("签到")
                    .setExecutor(this);
        if (getCommand("recycle") != null)
            getCommand("recycle")
                    .setExecutor(this);
        if (getCommand("垃圾清理") != null)
            getCommand("垃圾清理")
                    .setExecutor(this);
      /*  if (getCommand("oa") != null)
            getCommand("oa").setExecutor(this);*/
        if (getCommand("menu") != null)
            getCommand("menu").setExecutor(this);
        if (getCommand("printer") != null) {
            getCommand("printer").setExecutor(this);
            getCommand("shop").setExecutor(this);
            getCommand("商店").setExecutor(this);
            //       getCommand("testorder").setExecutor(this);
            if (getCommand("printer") != null) {
                getCommand("printer").setExecutor(this);
                getCommand("shop").setExecutor(this);
                getCommand("商店").setExecutor(this);
            }
// protect 命令独立注册
            if (getCommand("protect") != null) {
                getCommand("protect").setExecutor(this);
                getCommand("protect").setTabCompleter(this);
            }
        }

        // ===== 10. 注册事件 =====
        getServer().getPluginManager()
                .registerEvents(this, this);

        // ===== 11. 定时任务 =====
        afk.startCheck();
        startLoginReminder();
        startTimeoutCheck();
        startPasswordReminder();
        startTicketAutoProcess();
        radio.startMainRadio();
        new BukkitRunnable() {
            public void run() {
                if (questTracker == null) return;
                for (Player p :
                        Bukkit.getOnlinePlayers()) {
                    if (!isFrozen(p))
                        questTracker.onPlayTime(
                                p.getName(), 30000L);
                }
            }
        }.runTaskTimer(this, 600L, 600L);
// [新增] Printer 文件清理（每小时检测一次）
        new BukkitRunnable() {
            public void run() {
                if (bondPrinter != null)
                    bondPrinter.cleanupOldFiles();
            }
        }.runTaskTimer(this, 1200L, 72000L);

        // 12 ===PVP===
        pvpManager = new PVPManager(this);
        getServer().getPluginManager()
                .registerEvents(pvpManager, this);
// PVP区域检测定时器
        getServer().getScheduler()
                .runTaskTimer(this,
                        () -> pvpManager.tickRegions(),
                        20L, 20L);
        setupPVPTab();

        // 13 ====cypay债券====
        // 债券系统（独立DB）
        bondManager = new BondManager(this);
        cdkManager = new CDKManager(this);
        userGroupManager = new UserGroupManager(this);
        cypayCommand = new CypayCommand(this);
/*加载CDK文件
        cdkManager.loadCDKsFromDir();*/
        // cypay 命令
        if (getCommand("cypay") != null) {
            getCommand("cypay").setExecutor(cypayCommand);
            getCommand("cypay").setTabCompleter(cypayCommand);
        }
        bondPrinter = new BondPrinter(this);
        //   cdkManager.loadCDKsFromDir();
//14 ====商店====
        // ★ 构造器内已加载完毕，此处重复
        // if (shopManager != null) {
        //     shopManager.loadCategories();
        // }

        configMgr = new ConfigManager(getDataFolder());
        configMgr.loadSettings();
        orderManager = new OrderManager(this);

// 15 ======区域保护=========
        areaProtection = new AreaProtection(this);
        getServer().getPluginManager()
                .registerEvents(areaProtection, this);

// 效果清除定时器（只留一个）
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (areaProtection != null) {
            }
        }, 20L, 20L);

// 和平模式独立检测
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (areaProtection == null) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                AreaProtection.AreaConfig ac = areaProtection.getArea(
                        p.getWorld().getName(),
                        p.getLocation().getBlockX(),
                        p.getLocation().getBlockY(),
                        p.getLocation().getBlockZ());
                if (ac != null) {
                    // 无条件打印，不管 peaceMode 是啥
                 /*   getLogger().info("[和平定时器] " + p.getName()
                            + " 在[" + ac.name
                            + "] peaceMode=" + ac.peaceMode
                            + " 白名单=" + ac.peaceWhitelist);*/
                    if (ac.peaceMode) {
                        areaProtection.banHostilesWithWhitelist(p, ac);
                    }
                }
            }
        }, 40L, 40L);
        areaProtection.startEnforceTask();
        if (areaProtection != null) {
            areaProtection.reload();
            areaProtection.startEnforceTask();
        }

        // ★ 启动时异步检查更新（GitHub/Gitee双通道）
        updateChecker = new UpdateChecker(this);
        updateChecker.checkOnEnable();

        getLogger().info("§b[Sdf1_login]启动完毕\n§lS欢迎使用sdf1系列插件，如有问题，您可在\nGitHub和Gitee提交反馈");
        getLogger().info("sdf1系列插件包含：\nsdf1自助兑奖插件\nCY_beibao云背包\nsdf1_login登陆插件\nSdf1_game娱乐游戏插件");
        // 大字画
        getLogger().info("\n" +
                "  ____      _  __ _     _             _                          \n" +
                " / ___|  __| |/ _/ |   | | ___   __ _(_)_ __                     \n" +
                " \\___ \\ / _` | |_| |   | |/ _ \\ / _` | | '_ \\                    \n" +
                "  ___) | (_| |  _| |   | | (_) | (_| | | | | |                   \n" +
                " |____/ \\__,_|_| |_|___|_|\\___/ \\__, |_|_| |_|                   \n" +
                "  ____      _  __ |_____| _     |___/   _                        \n" +
                " / ___|  __| |/ _/ |_ __ | |_   _  __ _(_)_ __                   \n" +
                " \\___ \\ / _` | |_| | '_ \\| | | | |/ _` | | '_ \\                  \n" +
                "  ___) | (_| |  _| | |_) | | |_| | (_| | | | | |                 \n" +
                " |____/ \\__,_|_| |_| .__/|_|\\__,_|\\__, |_|_| |_|___      _  __ _ \n" +
                "  _ __   _____     |_|___ _ __  | |___/_   _  / ___|  __| |/ _/ |\n" +
                " | '_ \\ / _ \\ \\ /\\ / / _ \\ '__| | '_ \\| | | | \\___ \\ / _` | |_| |\n" +
                " | |_) | (_) \\ V  V /  __/ |    | |_) | |_| |  ___) | (_| |  _| |\n" +
                " | .__/ \\___/ \\_/\\_/ \\___|_|    |_.__/ \\__, | |____/ \\__,_|_| |_|\n" +
                " |_|                                   |___/                     "
        );
    }

    public BondPrinter getBondPrinter() {
        return bondPrinter;
    }

    public ShopManager getShopManager() {
        return shopManager;
    }

    private ConfigManager configMgr;

    public ConfigManager getConfigMgr() {
        return configMgr;
    }


    private void setupPVPTab() {
        if (getCommand("pvp") != null) {
            getCommand("pvp")
                    .setTabCompleter(
                            (sender, cmd,
                             alias, args) -> {
                                List<String> list =
                                        new ArrayList<>();
                                if (args.length == 1) {
                                    String[] sub = {
                                            "create", "stats",
                                            "list", "delete",
                                            "tool", "on", "off",
                                            "tempban"};
                                    for (String s : sub) {
                                        if (s.startsWith(
                                                args[0]
                                                        .toLowerCase())) {
                                            list.add(s);
                                        }
                                    }
                                } else if (args.length == 2) {
                                    String sub =
                                            args[0].toLowerCase();
                                    if (sub.equals("stats")) {
                                        for (Player pp : Bukkit
                                                .getOnlinePlayers()) {
                                            if (pp.getName()
                                                    .toLowerCase()
                                                    .startsWith(args[1]
                                                            .toLowerCase())) {
                                                list.add(pp.getName());
                                            }
                                        }
                                    } else if (sub.equals("delete")
                                            || sub.equals("on")
                                            || sub.equals("off")
                                            || sub.equals("tempban")) {
                                        for (String rn :
                                                pvpManager
                                                        .getRegionNames()) {
                                            if (rn.toLowerCase()
                                                    .startsWith(args[1]
                                                            .toLowerCase())) {
                                                list.add(rn);
                                            }
                                        }
                                    }
                                }
                                return list;
                            });
        }
    }

    @Override
    public void onDisable() {
        // 卸载时清理.sdf1临时文件
        cleanupSdf1Files();
        if (shopManager != null) shopManager.saveAll();
        // 取消未执行的删除任务
        if (pendingDeleteTask != null) {
            pendingDeleteTask.cancel();
            pendingDeleteTask = null;
            getLogger().info(
                    "[Sdf1_login] 服务器关闭，"
                            + "取消待删除: "
                            + pendingDeleteName);
            pendingDeleteName = null;
        }
        if (db != null) db.close();
        if (garbage != null) garbage.close();
        if (questTracker != null)
            questTracker.shutdown();
        if (areaProtection != null) {
            areaProtection.stopEnforceTask();
        }
        for (BukkitTask task : areaProtection.getPendingTasks()) {
            task.cancel();
        }
        areaProtection.saveWhitelists();
        areaProtection.stopEnforceTask();
        // 取消所有延时清理任务，立即清理
        if (areaProtection != null) {
            for (Map.Entry<UUID, BukkitTask> entry
                    : areaProtection.getPendingClearTasks().entrySet()) {
                entry.getValue().cancel();
                // 立即清理效果
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null && p.isOnline()) {
                    List<PotionEffectType> marked =
                            areaProtection.getPlayerMarkedEffects()
                                    .remove(entry.getKey());
                    if (marked != null) {
                        for (PotionEffectType type : marked) {
                            p.removePotionEffect(type);
                        }
                    }
                }
            }
            areaProtection.stopEnforceTask();
            areaProtection.saveWhitelists();
            areaProtection.getAlreadyForced().clear();

        }
    }

    /**
     * 清理插件目录下的 .sdf1 后缀文件
     * 用于防风控任务准备
     */
    private void cleanupSdf1Files() {
        File dataFolder = getDataFolder();
        if (dataFolder == null || !dataFolder.exists()) {
            return;
        }

        File[] sdf1Files = dataFolder.listFiles(
                (dir, name) -> name.endsWith(".sdf1")
        );

        if (sdf1Files != null && sdf1Files.length > 0) {
            int deletedCount = 0;
            for (File file : sdf1Files) {
                if (file.delete()) {
                    deletedCount++;
                }
            }
            getLogger().info("[Sdf1_login] 已清理 "
                    + deletedCount + " 个 .sdf1 临时文件");
        }
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
    private String debugCodePoints(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(String.format("U+%04X",
                    (int) s.charAt(i)));
        }
        return sb.toString();
    }

    private boolean isAdmin(CommandSender sender) {
        if (sender instanceof Player)
            return ((Player) sender)
                    .getScoreboardTags()
                    .contains(config.adminTag);
        return true;
    }

    // ★ 集群更新：同时唤醒所有sdf1系列插件检查更新
    private void checkAllPluginsUpdate(CommandSender sender) {
        // 1. Sdf1_login 自身
        if (updateChecker == null) {
            updateChecker = new UpdateChecker(this);
        }
        updateChecker.checkUpdate(sender);

        // 2. Sdf1_game
        try {
            org.bukkit.plugin.Plugin gamePlugin =
                    org.bukkit.Bukkit.getPluginManager().getPlugin("Sdf1_game");
            if (gamePlugin != null && gamePlugin.isEnabled()) {
                java.lang.reflect.Method m = gamePlugin.getClass().getMethod("getUpdateChecker");
                Object checker = m.invoke(gamePlugin);
                if (checker != null) {
                    java.lang.reflect.Method check = checker.getClass().getMethod("checkUpdate", CommandSender.class);
                    check.invoke(checker, sender);
                }
            }
        } catch (Exception ignored) {}

        // 3. CY_beibao
        try {
            org.bukkit.plugin.Plugin cyPlugin =
                    org.bukkit.Bukkit.getPluginManager().getPlugin("CY_beibao");
            if (cyPlugin != null && cyPlugin.isEnabled()) {
                java.lang.reflect.Method m = cyPlugin.getClass().getMethod("getUpdateChecker");
                Object checker = m.invoke(cyPlugin);
                if (checker != null) {
                    java.lang.reflect.Method check = checker.getClass().getMethod("checkUpdate", CommandSender.class);
                    check.invoke(checker, sender);
                }
            }
        } catch (Exception ignored) {}

        // 4. sdf1
        try {
            org.bukkit.plugin.Plugin sdf1Plugin =
                    org.bukkit.Bukkit.getPluginManager().getPlugin("sdf1");
            if (sdf1Plugin != null && sdf1Plugin.isEnabled()) {
                // sdf1 使用不同的更新检查方式，直接调用 /sdf1 update
                org.bukkit.Bukkit.dispatchCommand(sender, "sdf1 update");
            }
        } catch (Exception ignored) {}
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
        p.setAllowFlight(false);
        p.setFlying(false);


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
        if (welcome != null) welcome.onLogin(p);
        activateBeibao(p);
        pushPendingAlerts(p);

        // ★ 玩家上线时恢复区域效果
        AreaProtection areaProt = getAreaProtection();
        if (areaProt != null) {
            areaProt.onPlayerJoin(p);
        }

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

        // 打卡按钮（独立，slot 11）
        g.setItem(11, mkItem(Material.COMPASS,
                "§b§l群系打卡",
                "§7在不同群系打卡完成任务",
                "§7打卡范围: 32格去重"));

        // 新人任务（slot 15）
        g.setItem(15, mkItem(Material.BOOK,
                "§e新人任务",
                "§7完成新手阶段获取奖励"));

        // 返回
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
                if (isTreasureItem(item))
                    continue; // Sdf1_game宝箱物品不计入
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
        // 强制重发资源包（清掉客户端拒绝记录）
        Bukkit.getScheduler()
                .runTaskLater(this, () -> {
                    // ★ 资源包：只发送一次，延迟3秒确保客户端就绪
                    if (radio != null) {
                        final Player jp = p;
                        Bukkit.getScheduler().runTaskLater(
                                this, () -> {
                                    if (jp.isOnline()) {
                                        radio.sendResourcePack(jp);
                                    }
                                }, 60L);
                    }

                }, 20L);
        // 重置聊天输入状态（防止上次退出时残留）
        chatInput.reset(p);
        if (needsPasswordChange.contains(name))
            needsPasswordChange.remove(name);
// 改完了直接打包，要打sdf1一起
        lastActivity.put(p.getUniqueId(),
                System.currentTimeMillis());
        if (needsPasswordChange.contains(name))
            needsPasswordChange.remove(name);

        boolean isBedrock =
                verification.isBedrockPlayer(p);
        boolean isOnlineMode =
                verification.isOnlineMode();

        // ★ 登录阶段始终允许飞行，防止悬空被踢出
        p.setAllowFlight(true);

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
        // [ADDED] 资源包（延迟发送，不强制）
        // ★ 资源包：只发送一次，延迟3秒确保客户端就绪
        if (radio != null) {
            final Player jp = p;
            Bukkit.getScheduler().runTaskLater(
                    this, () -> {
                        if (jp.isOnline()) {
                            radio.sendResourcePack(jp);
                        }
                    }, 60L);
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
        // [ADDED] 发送资源包
        // ★ 资源包：只发送一次，延迟3秒确保客户端就绪
        if (radio != null) {
            final Player jp = p;
            Bukkit.getScheduler().runTaskLater(
                    this, () -> {
                        if (jp.isOnline()) {
                            radio.sendResourcePack(jp);
                        }
                    }, 60L);
        }


        // [ADDED] 发送资源包（延迟5秒确保客户端就绪）
        // ★ 资源包：只发送一次，延迟3秒确保客户端就绪
        if (radio != null) {
            final Player jp = p;
            Bukkit.getScheduler().runTaskLater(
                    this, () -> {
                        if (jp.isOnline()) {
                            radio.sendResourcePack(jp);
                        }
                    }, 60L);
        }



        // ===== 登录连续天数更新（基于上线，非签到） =====
        if (db.userExists(name)) {
            String today = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd").format(new java.util.Date());
            Object lastLoginObj = db.getField(name, "last_login_date");
            String lastLoginDate = lastLoginObj != null ? lastLoginObj.toString() : "";
            Object streakObj = db.getField(name, "login_streak");
            int loginStreak = streakObj instanceof Number ? ((Number) streakObj).intValue() : 0;

            if (!lastLoginDate.isEmpty() && !lastLoginDate.equals(today)) {
                try {
                    java.util.Date lastD = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(lastLoginDate);
                    java.util.Date todayD = new java.text.SimpleDateFormat("yyyy-MM-dd").parse(today);
                    long diff = todayD.getTime() - lastD.getTime();
                    if (diff > 86400000L * 2) {
                        loginStreak = 1;
                    } else if (diff > 86400000L) {
                        loginStreak++;
                    }
                } catch (Exception ignored) {}
            } else if (lastLoginDate.isEmpty()) {
                loginStreak = 1;
            }
            db.setField(name, "login_streak", loginStreak);
            db.setField(name, "last_login_date", today);
        }

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
        // [ADDED] 清除 Radio 熔断状态
        // [ADDED] 清除 Radio 熔断状态
        if (radioDL != null)
            radioDL.onPlayerQuit(
                    p.getUniqueId());
        pendingAdminAuth.remove(name);
    }

    private static final String MENU_SNOWBALL_TAG =
            "sdf1_menu";

    public void giveMenuSnowball(Player p) {
        String name = p.getName();
        try {
        // getLogger().info("[菜单] ★调用 giveMenuSnowball: " + name);
        Object val = db.getField(name, "menu_snowball");
        if (val != null) {
            int on = val instanceof Number
                    ? ((Number) val).intValue() : 1;
            if (on == 0) {
                // getLogger().info("[菜单] " + name + " menu_snowball=0, 跳过发放");
                return;
            }
        }

        boolean hasMenu = false;
        for (ItemStack it : p.getInventory()
                .getContents()) {
            if (isMenuSnowball(it)) { hasMenu = true; break; }
            if (isCustomMenuTrigger(it)) { hasMenu = true; break; }
            if (isTreasureItem(it)) continue; // 跳过Sdf1_game宝箱物品
        }
        if (hasMenu) {
            // getLogger().info("[菜单] " + name + " 已有菜单物品, 跳过发放");
            return;
        }

        ItemStack customIcon =
                menuIconMgr != null ? menuIconMgr.getIcon(p) : null;
        // getLogger().info("[菜单] " + name + " 自定义图标: " + (customIcon != null ? customIcon.getType().name() : "null"));
        if (customIcon != null) {
            tagAsMenuTrigger(customIcon);
            // 加外观标识
            ItemMeta cm = customIcon.getItemMeta();
            if (cm != null) {
                String originalName =
                        cm.getDisplayName();
                if (originalName == null
                        || originalName.isEmpty()
                        || originalName.equals(
                        customIcon.getType()
                                .name())) {
                    originalName = "自定义物品";
                }
                cm.setDisplayName(
                        "\u00a7e\u00a7l[菜单] \u00a7f"
                                + originalName);
                List<String> cmLore =
                        cm.hasLore()
                                ? new ArrayList<>(cm.getLore())
                                : new ArrayList<>();
                // 避免重复添加
                if (cmLore.isEmpty()
                        || !cmLore.get(0).contains(
                        "\u00a77右键打开主菜单")) {
                    cmLore.add(0,
                            "\u00a77右键打开主菜单");
                }
                cm.setLore(cmLore);
                customIcon.setItemMeta(cm);
            }
            int slot = p.getInventory()
                    .firstEmpty();
            if (slot >= 0) {
                p.getInventory().setItem(
                        slot, customIcon);
            } else {
                p.getWorld().dropItemNaturally(
                        p.getLocation(), customIcon);
            }
            return;
        }

        // getLogger().info("[菜单] " + name + " 发放默认雪球菜单");
        ItemStack snow = new ItemStack(
                Material.SNOWBALL);
        ItemMeta im = snow.getItemMeta();
        if (im != null) {
            im.setDisplayName(
                    "\u00a7e\u00a7l[菜单] \u00a7f右键打开主菜单");
            List<String> lore = new ArrayList<>();
            lore.add("\u00a77右键点击打开功能菜单");
            lore.add("\u00a78" + MENU_SNOWBALL_TAG);
            im.setLore(lore);
            snow.setItemMeta(im);
        }
        int slot = p.getInventory()
                .firstEmpty();
        if (slot >= 0) {
            p.getInventory().setItem(slot, snow);
        } else {
            p.getWorld().dropItemNaturally(
                    p.getLocation(), snow);
        }
        } catch (Exception e) {
            getLogger().severe("[菜单] giveMenuSnowball异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }


    /**
     * 手动发放雪球菜单（绕过开关检查）
     */
    public void forceGiveMenuSnowball(
            Player p) {
        // 只检查是否已有
        for (ItemStack it : p.getInventory()
                .getContents()) {
            if (isMenuSnowball(it)) {
                return;
            }
        }
        ItemStack snow = new ItemStack(
                Material.SNOWBALL);
        ItemMeta im = snow.getItemMeta();
        if (im != null) {
            im.setDisplayName(
                    "§e§l[菜单] §f右键打开主菜单");
            List<String> lore =
                    new ArrayList<>();
            lore.add("§7右键点击打开功能菜单");
            lore.add("§8" + MENU_SNOWBALL_TAG);
            im.setLore(lore);
            snow.setItemMeta(im);
        }
        int slot = p.getInventory()
                .firstEmpty();
        if (slot >= 0) {
            p.getInventory().setItem(
                    slot, snow);
        } else {
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
    // 运行时初始化版本（避免静态加载问题）
    private NamespacedKey getTriggerKey() {
        return new NamespacedKey(this, "menu_trigger");
    }

    /**
     * 给物品打上菜单触发标记
     */
    private void tagAsMenuTrigger(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(
                getTriggerKey(),
                PersistentDataType.STRING,
                "true");
        item.setItemMeta(meta);
    }

    /**
     * 检查物品是否带菜单触发标记
     */
    private boolean isCustomMenuTrigger(
            ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        // 检查两种标记：menu_trigger（主标记）和 custom_menu_icon（MenuIconManager标记）
        return meta.getPersistentDataContainer()
                .has(getTriggerKey(),
                        PersistentDataType.STRING)
                || meta.getPersistentDataContainer()
                .has(menuIconMgr.getIconKey(),
                        PersistentDataType.STRING);
    }

    /**
     * 检查物品是否是Sdf1_game宝箱自定义物品
     * Sdf1_game的宝箱物品lore中包含"§0§k"标记
     */
    private boolean isTreasureItem(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (!meta.hasLore()) return false;
        List<String> lore = meta.getLore();
        if (lore == null) return false;
        for (String line : lore) {
            // Sdf1_game宝箱物品标记格式: §0§kCUSTOM|玩家|...
            // 同时检查两种标记格式
            if (line.contains("CUSTOM")
                    || line.contains("\u00a70\u00a7k"))
                return true;
        }
        return false;
    }


    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        // 冻结处理（未登录玩家不能移动）
        if (isFrozen(p)) {
            if (from.getBlockX() == to.getBlockX()
                    && from.getBlockY() == to.getBlockY()
                    && from.getBlockZ() == to.getBlockZ()) {
                return;
            }
            e.setCancelled(true);
            p.setAllowFlight(true);
        }
    }

    private List<String> filterTab(List<String> opts, String prefix) {
        List<String> r = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase().startsWith(prefix.toLowerCase())) {
                r.add(o);
            }
        }
        return r;
    }


    /*
    雪球菜单：
    同时检测左右手
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        // ★ 过滤物理交互（压力板、绊线等），防止GUI被反复打开
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL) return;

        Player p = e.getPlayer();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }

        ItemStack hand = p.getInventory()
                .getItemInMainHand();
        ItemStack offhand = p.getInventory()
                .getItemInOffHand();

        // ★ 屏蔽Sdf1_game宝箱自定义物品
        if (isTreasureItem(hand) || isTreasureItem(offhand)) return;

        if (isMenuSnowball(hand)
                || isMenuSnowball(offhand)) {
            e.setCancelled(true);
            gui.openMain(p);
            return;
        }

        // ★ 自定义菜单物品：只需PDC标记即可触发（不再额外检查DB）
        if (isCustomMenuTrigger(hand)
                || isCustomMenuTrigger(offhand)) {
            e.setCancelled(true);
            gui.openMain(p);
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
        // [ADDED] 任务计数
        if (questTracker != null)
            questTracker.onBlockMined(p.getName(),
                    e.getBlock().getType().name());

    }

    @EventHandler
    public void onBlockPlace2(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (isFrozen(p)) return;
        afk.recordAction(p.getUniqueId());
        if (questTracker != null)
            questTracker.onBlockPlaced(
                    p.getName(),
                    e.getBlock().getType().name());
    }

    @EventHandler
    public void onChatTrack(AsyncPlayerChatEvent e) {
        if (isFrozen(e.getPlayer())) return;
        if (questTracker != null)
            questTracker.onPlayerChat(
                    e.getPlayer().getName());
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
        ItemStack drop =
                e.getItemDrop().getItemStack();
        if (isMenuSnowball(drop)) {
            e.setCancelled(true);
        }
        // ★ Sdf1_game宝箱物品不干预，由Sdf1_game自行处理
    }


    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        Player p = e.getPlayer();
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }

        // ★ 拾取物品后检测违禁品没收
        if (areaProtection != null) {
            String areaName = areaProtection.getPlayerArea(p);
            if (areaName != null) {
                AreaProtection.AreaConfig ac =
                        areaProtection.getArea(
                                p.getWorld().getName(),
                                p.getLocation().getBlockX(),
                                p.getLocation().getBlockY(),
                                p.getLocation().getBlockZ());
                if (ac != null
                        && !areaProtection.isExemptFromConfiscation(
                        p, areaName)) {
                    areaProtection.handleConfiscate(p, ac);
                }
            }
        }
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

    // [ADDED] 击杀追踪
    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        if (questTracker == null) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null || isFrozen(killer)) return;
        if (e.getEntity() instanceof Player)
            questTracker.onPlayerKill(killer.getName());
        else
            questTracker.onMobKill(killer.getName(),
                    e.getEntityType().name());
    }

    // [ADDED] 合成追踪
    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player) {
            Player p = (Player) e.getWhoClicked();
            if (!isFrozen(p) && questTracker != null)
                questTracker.onItemCrafted(p.getName());
        }
    }

    // [ADDED] 钓鱼追踪
    @EventHandler
    public void onFish(PlayerFishEvent e) {
        Player p = e.getPlayer();
        if (p == null || isFrozen(p)) return;
        if (questTracker != null
                && e.getState() == PlayerFishEvent.State.CAUGHT_FISH)
            questTracker.onFishCaught(p.getName());
    }

    // [ADDED] 村民交易追踪
    @EventHandler(priority = EventPriority.MONITOR,
            ignoreCancelled = true)
    public void onVillagerTrade(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p)) return;
        if (questTracker == null) return;

        Inventory topInv =
                e.getView().getTopInventory();
        if (topInv == null
                || topInv.getHolder() == null)
            return;
        if (!(topInv.getHolder()
                instanceof org.bukkit.entity.Villager))
            return;

        // 结果槽 = 2
        if (e.getRawSlot() != 2) return;
        ItemStack result = topInv.getItem(2);
        if (result == null
                || result.getType() == Material.AIR)
            return;

        final Player fp = p;
        Bukkit.getScheduler().runTaskLater(this,
                () -> {
                    if (fp.isOnline())
                        questTracker.onVillagerTrade(
                                fp.getName());
                }, 2L);
    }



    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String msg = e.getMessage();
        // ★ CDK/经济/债券输入拦截 — 必须在所有聊天处理之前
        if (this.getCDK() != null && this.getCDK().isListening(p.getName())) {
            e.setCancelled(true);
            this.getCDK().onChat(p, msg);
            return;
        }


// ===== 菜单聊天输入 =====
        if (getMenu().isEditing(p.getName())) {
            e.setCancelled(true);
            getMenu().onChat(p, msg);
            return;
        }

            // ===== 冻结检查 =====
        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }

        // ===== 聊天输入流程（改密码/绑邮箱等） =====
        if (chatInput.isInFlow(p)) {
            e.setCancelled(true);
            chatInput.handleInput(p, msg, this);
            return;
        }

        // ===== 管理员密码验证 =====
        if (pendingAdminAuth.contains(p.getName())) {
            e.setCancelled(true);
            pendingAdminAuth.remove(p.getName());
            if (msg.equals(config.adminPassword)) {
                p.sendMessage("§a验证通过");
                p.sendMessage("§7输入的密码已验证");
            } else {
                p.sendMessage("§c密码错误");
            }
            return;
        }

        // ===== 聊天过滤 - 开关检查 =====
        if (!chatFilter.isEnabled()) return;

        // ===== 白名单玩家 =====
        if (chatFilter.isPlayerWhitelisted(
                p.getName())) return;

        // ===== 禁言检查 =====
        if (chatFilter.isMuted(p.getName())) {
            e.setCancelled(true);
            p.sendMessage(chatFilter.msg(
                    "chat_muted"));
            return;
        }

        // ===== URL检测 =====
        List<String> urls =
                chatFilter.extractUrls(msg);
        if (urls.isEmpty()) return;

        // 过滤白名单
        List<String> blocked = new ArrayList<>();
        for (String url : urls) {
            if (!chatFilter.isWhitelisted(url)) {
                blocked.add(url);
            }
        }
        if (blocked.isEmpty()) return;

        // 取消消息
        e.setCancelled(true);

        for (String url : blocked) {
            p.sendMessage(chatFilter.msg(
                    "chat_url_blocked",
                    "url", url));
        }

        // 增加违规计数
        chatFilter.incrementViolation(p.getName());
        int count = chatFilter.getViolationCount(
                p.getName());
        p.sendMessage(chatFilter.msg(
                "chat_url_violation",
                "count", String.valueOf(count)));

        // 通知管理员
        if (chatFilter.isNotifyAdmin()) {
            String notify = chatFilter.msg(
                    "chat_url_admin_notify",
                    "player", p.getName(),
                    "url", blocked.get(0),
                    "count", String.valueOf(count));
            for (Player op :
                    Bukkit.getOnlinePlayers()) {
                if (op.hasPermission(
                        "sdf1.admin")
                        || op.isOp()) {
                    op.sendMessage(notify);
                }
            }
        }

        // 通报
        if (chatFilter.isNotifyAll()) {
            Bukkit.broadcastMessage(
                    chatFilter.msg(
                            "chat_url_broadcast",
                            "player",
                            p.getName()));
        }

        // 处罚
        chatFilter.applyPunishment(p, count);
        // ===== 补签确认 =====
        if (pendingBackCheck.containsKey(
                p.getName())) {
            e.setCancelled(true);
            String data = pendingBackCheck
                    .remove(p.getName());
            String[] parts = data.split(":");
            String dateStr = parts[0];
            int totalPoints =
                    Integer.parseInt(parts[1]);

            if (msg.trim().equals("1")
                    || msg.trim()
                    .equalsIgnoreCase("yes")
                    || msg.trim().contains("是")
                    || msg.trim().contains("愿意")) {
                int extra = totalPoints - 10;
                if (extra > 0
                        && !db.deductPoints(
                        p.getName(), extra)) {
                    db.addPoints(p.getName(), 10);
                    p.sendMessage("§c积分也不足，"
                            + "10积分已退还");
                    return;
                }
                checkIn.doBackCheckIn(p, dateStr);
                p.sendMessage("§a补签成功！日期: "
                        + dateStr + " §7(扣除"
                        + totalPoints
                        + "积分，免经济)");
                playSuccessSound(p);
            } else {
                db.addPoints(p.getName(), 10);
                p.sendMessage("§c补签已取消，"
                        + "10积分已退还");
            }
            return;
        }

    }

    // 在 getter 区域添加
    public MenuManager getMenu() {
        return menu;
    }
    public PVPManager getPVPManager() {
        return pvpManager;
    }
    // 在 Main.java 中（与其他 getter 放一起）
    public AreaProtection getAreaProtection() {
        return areaProtection;
    }

// ==================== 我的钱包 ====================

    public void openMyWallet(Player p) {
        openWalletGUI(p, p.getName());
    }

    private void openWalletGUI(Player p, String target) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§6§l我的钱包");


        ItemStack glass = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        if (gm != null) {
            gm.setDisplayName(" ");
            glass.setItemMeta(gm);
        }
        for (int i = 0; i < 27; i++)
            g.setItem(i, glass);

        // 债券余额 (slot 4)
        int balance = bondManager.getBonds(target);
        g.setItem(4, mkItem(Material.GOLD_INGOT,
                "§6§l债券余额: §e" + balance + " §6枚",
                "§7当前持有的债券数量"));

        // 积分余额 (slot 5)
        int pointsBalance = points.getPoints(target);
        g.setItem(5, mkItem(Material.EMERALD,
                "§6§l积分余额: §e" + pointsBalance + " §6分",
                "§7当前持有的积分数量"));

        // 账户状态 (slot 10)
        String status = bondManager
                .getAccountStatus(target);
        if ("frozen".equals(status)) {
            g.setItem(10, mkItem(Material.REDSTONE,
                    "§c§l账户状态: 冻结中",
                    "§7无法进行任何资金交易"));
        } else {
            g.setItem(10, mkItem(Material.EMERALD,
                    "§a§l账户状态: 正常",
                    "§7账户运行正常"));
        }

        // 同IP玩家 (slot 11)
        if (target.equals(p.getName())) {
            List<String> sameIP = bondManager
                    .getSameIPPlayers(target);
            List<String> lore = new ArrayList<>();
            lore.add("§7同IP下注册的其他玩家:");
            if (sameIP.isEmpty()) {
                lore.add("§a  无");
            } else {
                for (String n : sameIP)
                    lore.add("§c  - " + n);
            }
            lore.add("§7");
            lore.add("§7转账限制:");
            lore.add("§7  不能与同IP玩家转账");
            g.setItem(11, mkItem(Material.PAPER,
                    "§e§l同IP玩家", lore.toArray(
                            new String[0])));
        }

        // 近7天流水 (slot 12)
        g.setItem(12, mkItem(Material.BOOK,
                "§e近7天流水",
                "§7点击查看最近7天交易记录"));

        // 近14天流水 (slot 13)
        g.setItem(13, mkItem(Material.BOOK,
                "§e近14天流水",
                "§7点击查看最近14天交易记录",
                "§8(系统最大支持14天)"));

        // 转账记录 (slot 14)
        g.setItem(14, mkItem(Material.PAPER,
                "§e转账记录",
                "§7点击查看所有转账明细"));

        // 返回 (slot 22)
        g.setItem(22, mkItem(Material.ARROW,
                "§7← 返回"));

        walletViewTarget.put(p.getName(), target);
        p.openInventory(g);
    }

// ==================== 流水记录GUI ====================

    public void openWalletTransactions(
            Player p, int days) {
        String target = walletViewTarget.getOrDefault(
                p.getName(), p.getName());
        openWalletTxGUI(p, target, days, false);
    }

    public void openWalletTransfers(Player p) {
        String target = walletViewTarget.getOrDefault(
                p.getName(), p.getName());
        openWalletTxGUI(p, target, 14, true);
    }

    private void openWalletTxGUI(Player p,
                                 String target,
                                 int days,
                                 boolean transfersOnly) {
        days = Math.min(days, 14); // 普通玩家最大14天
        String title = transfersOnly
                ? "§e§l转账记录 - " + target
                : "§e§l流水记录 - 近" + days
                  + "天 - " + target;

        Inventory g = Bukkit.createInventory(
                null, 54, title);

        List<Map<String, Object>> txs = transfersOnly
                ? bondManager.getTransferTransactions(
                target, days)
                : bondManager.getTransactions(
                target, days);

        int slot = 0;
        for (Map<String, Object> tx : txs) {
            if (slot >= 45) break;

            String type = (String) tx.get("type");
            int amount = ((Number) tx.get("amount"))
                    .intValue();
            String tgt = (String) tx.get("target_player");
            String reason = (String) tx.get("reason");
            int bal = ((Number) tx.get("balance_after"))
                    .intValue();
            long time = ((Number) tx.get("time"))
                    .longValue();

            boolean isExpense = "shop_buy".equals(type)
                    || "shop_packing".equals(type);
            boolean pos = isExpense ? false : (amount > 0);
            String sign = pos ? "+" : (isExpense ? "-" : "");
            String color = pos ? "§a" : (isExpense ? "§c" : (amount < 0 ? "§c" : ""));
// 金额取绝对值显示
            int displayAmount = Math.abs(amount);


            // 用 if-else 替代 switch 避免编译问题
            Material mat = Material.PAPER;
            if (BondManager.TX_TRANSFER_OUT.equals(type)) {
                mat = Material.RED_WOOL;
            } else if (BondManager.TX_TRANSFER_IN
                    .equals(type)) {
                mat = Material.LIME_WOOL;
            } else if (BondManager.TX_ADMIN_GIVE
                    .equals(type)) {
                mat = Material.GOLD_BLOCK;
            } else if (BondManager.TX_ADMIN_DEDUCT
                    .equals(type)) {
                mat = Material.REDSTONE_BLOCK;
            } else if (BondManager.TX_REDEEM
                    .equals(type)) {
                mat = Material.NAME_TAG;
            } else if (BondManager.TX_SHOP_BUY
                    .equals(type)) {
                mat = Material.CHEST;
            } else if (BondManager.TX_FREEZE
                    .equals(type)) {
                mat = Material.BARRIER;
            } else if (BondManager.TX_UNFREEZE
                    .equals(type)) {
                mat = Material.EMERALD_BLOCK;
            } else if (pos) {
                mat = Material.LIME_WOOL;
            } else {
                mat = Material.RED_WOOL;
            }
            String typeName = walletFormatType(type);

            List<String> lore = new ArrayList<>();
            lore.add("§7类型: " + typeName);
            lore.add("§7金额: " + color + sign + displayAmount
                    + " §6枚");

            if (tgt != null && !tgt.isEmpty())
                lore.add("§7对象: §e" + tgt);
            if (reason != null && !reason.isEmpty())
                lore.add("§7理由: §7" + reason);
            lore.add("§7余额: §f" + bal + " §6枚");
            lore.add("§8" + TX_SDF.format(
                    new Date(time)));

            ItemStack item = new ItemStack(mat);
            ItemMeta im = item.getItemMeta();
            if (im != null) {
                im.setDisplayName(color + sign + displayAmount
                        + " §6枚 - " + typeName);

                im.setLore(lore);
                item.setItemMeta(im);
            }
            g.setItem(slot, item);
            slot++;
        }

        if (txs.isEmpty()) {
            ItemStack empty = new ItemStack(
                    Material.BARRIER);
            ItemMeta em = empty.getItemMeta();
            if (em != null) {
                em.setDisplayName("§7暂无记录");
                empty.setItemMeta(em);
            }
            g.setItem(22, empty);
        } else if (txs.size() > 45) {
            g.setItem(49, mkItem(Material.PAPER,
                    "§7还有 " + (txs.size() - 45)
                            + " 条未显示"));
        }

        g.setItem(53, mkItem(Material.ARROW,
                "§7← 返回钱包"));

        p.openInventory(g);
    }


    private String walletFormatType(String type) {
        if (type == null) return "未知";
        switch (type) {
            case BondManager.TX_ADMIN_GIVE:
                return "管理员发放";
            case BondManager.TX_ADMIN_DEDUCT:
                return "管理员扣除";
            case BondManager.TX_TRANSFER_OUT:
                return "转出";
            case BondManager.TX_TRANSFER_IN:
                return "转入";
            case BondManager.TX_REDEEM:
                return "CDK兑换";
            case BondManager.TX_SHOP_BUY:
                return "商城购买";
            case BondManager.TX_DAILY_SIGN:
                return "签到奖励";
            case BondManager.TX_FREEZE:
                return "冻结";
            case BondManager.TX_UNFREEZE:
                return "解冻";
            default:
                return type;
        }
    }


    // ===== Inventory Click（旧逻辑 + 新主菜单布局 + 新功能） =====
    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getWhoClicked() instanceof Player))
            return;
        Player p = (Player) e.getWhoClicked();

        if (isFrozen(p)) {
            e.setCancelled(true);
            return;
        }

        String title = e.getView().getTitle();
        int slot = e.getRawSlot();

        // ===== 工单 =====
        if (title.equals(TicketManager.T_TICKET_MAIN)
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
            if (e.getClickedInventory() == null)
                return;
            if (e.getClickedInventory()
                    != e.getView().getTopInventory())
                return;
            e.setCancelled(true);
            ticket.handleClick(p, title, slot);
            return;
        }

        // ===== 垃圾箱 =====
        // ===== 垃圾回收站 =====
        if (title.equals("§6§l垃圾回收站")) {
            if (e.getClickedInventory() == null)
                return;

            // 玩家背包普通点击 → 完全不管
            if (e.getClickedInventory()
                    == p.getInventory()
                    && !e.isShiftClick()) {
                return;
            }

            // Shift+Click 从玩家背包存入
            if (e.isShiftClick()
                    && e.getClickedInventory()
                    == p.getInventory()
                    && e.getCurrentItem() != null
                    && e.getCurrentItem().getType()
                    != Material.AIR
                    && !isMenuSnowball(
                    e.getCurrentItem())) {
                e.setCancelled(true);
                long now = System.currentTimeMillis();
                Long last = garbageBusy.get(
                        p.getUniqueId());
                if (last != null && now - last < 200L)
                    return;
                garbageBusy.put(p.getUniqueId(), now);
                ItemStack toSave =
                        e.getCurrentItem().clone();
                int slotIndex = e.getSlot();

                // ★ 宝箱物品防护（事不过三，按物品单独计数）
                if (isTreasureItem(toSave)) {
                    UUID uid = p.getUniqueId();
                    // 按物品类型单独计数（玩家UUID+物品类型）
                    String itemKey = uid + ":" + toSave.getType().name();
                    int count = treasureDiscardCount
                            .getOrDefault(itemKey, 0) + 1;
                    treasureDiscardCount.put(itemKey, count);
                    if (count < 3) {
                        // 前两次：仅警告（事件已取消，物品仍在背包）
                        p.sendMessage("§c§l[防护] §f该物品是宝箱自定义物品，"
                                + "禁止投入垃圾站！"
                                + "§7（第" + count + "/3次警告）");
                    } else {
                        // 第三次：直接没收（从背包移除）
                        p.getInventory().setItem(slotIndex, null);
                        p.sendMessage("§c§l[防护] §f您多次尝试投入宝箱物品，"
                                + "物品已被没收！");
                        treasureDiscardCount.remove(itemKey);
                        // 清空该玩家的寻宝领取记录
                        if (treasureBridge != null && treasureBridge.isHooked()) {
                            int cleared = treasureBridge.clearPlayerAllClaims(p.getName());
                            if (cleared > 0) {
                                p.sendMessage("§7已清空" + cleared + "条寻宝领取记录");
                            }
                        }
                    }
                    return;
                }

                // ★ 正常物品：保存到数据库后从背包移除（防止刷物品）
                p.getInventory().setItem(slotIndex, null);
                garbage.saveItem(toSave);
                Bukkit.getScheduler()
                        .runTaskLater(this, () -> {
                            garbage.openRecyclePage(p, 0);
                        }, 1L);
                return;
            }

            // GUI 内点击 → 全部取消，由 garbage.handleClick 处理
            e.setCancelled(true);

            // 功能格 49-53
            if (slot >= 49 && slot <= 53) {
                garbage.handleClick(p, title, slot);
                return;
            }

            // 光标有物品 → 存入
            if (slot < 54
                    && e.getCursor() != null
                    && e.getCursor().getType()
                    != Material.AIR
                    && !isMenuSnowball(
                    e.getCursor())) {
                e.setCancelled(true);
                ItemStack toSave =
                        e.getCursor().clone();
                p.getOpenInventory().setCursor(null);

                // ★ 宝箱物品防护（事不过三，按物品单独计数）
                if (isTreasureItem(toSave)) {
                    UUID uid = p.getUniqueId();
                    // 按物品类型单独计数（玩家UUID+物品类型）
                    String itemKey = uid + ":" + toSave.getType().name();
                    int count = treasureDiscardCount
                            .getOrDefault(itemKey, 0) + 1;
                    treasureDiscardCount.put(itemKey, count);
                    if (count < 3) {
                        // 前两次：警告 + 踢回背包
                        p.sendMessage("§c§l[防护] §f该物品是宝箱自定义物品，"
                                + "禁止投入垃圾站！"
                                + "§7（第" + count + "/3次警告）");
                        p.getInventory().addItem(toSave);
                    } else {
                        // 第三次：直接没收
                        p.sendMessage("§c§l[防护] §f您多次尝试投入宝箱物品，"
                                + "物品已被没收！");
                        treasureDiscardCount.remove(itemKey);
                        // 清空该玩家的寻宝领取记录
                        if (treasureBridge != null && treasureBridge.isHooked()) {
                            int cleared = treasureBridge.clearPlayerAllClaims(p.getName());
                            if (cleared > 0) {
                                p.sendMessage("§7已清空" + cleared + "条寻宝领取记录");
                            }
                        }
                    }
                    return;
                }

                garbage.saveItem(toSave);
                Bukkit.getScheduler()
                        .runTaskLater(this, () -> {
                            garbage.openRecyclePage(p, 0);
                        }, 2L);
                return;
            }

            // 点击有 ID 的物品 → 取出
            if (slot < 54) {
                ItemStack inSlot =
                        e.getInventory().getItem(slot);
                if (inSlot != null
                        && inSlot.hasItemMeta()
                        && inSlot.getItemMeta()
                        .hasLore()) {
                    List<String> lore =
                            inSlot.getItemMeta().getLore();
                    if (lore != null && !lore.isEmpty()
                            && lore.get(0).startsWith(
                            "§7ID: #")) {
                        try {
                            String numStr = lore.get(0)
                                    .replace(
                                            "§7ID: #", "")
                                    .trim();
                            int dbId =
                                    Integer.parseInt(
                                            numStr);
                            if (garbage.removeItem(
                                    dbId)) {
                                ItemStack give =
                                        inSlot.clone();
                                ItemMeta gm =
                                        give.getItemMeta();
                                if (gm == null)
                                    gm = Bukkit
                                            .getItemFactory()
                                            .getItemMeta(
                                                    give
                                                            .getType());
                                gm.setLore(null);
                                give.setItemMeta(gm);
                                p.getInventory()
                                        .addItem(give);
                                e.getInventory()
                                        .setItem(
                                                slot, null);
                            }
                        } catch (Exception ex) {
                            p.sendMessage(
                                    "§c取出失败");
                        }
                    }
                }
            }
            return;
        }


        // ===== 二级菜单（.txt）—— 移到前面 =====
        if (title.startsWith("§6§l")
                && !title.equals(GUIManager.T_MAIN)
                && !title.equals(GUIManager.T_ADMIN)
                && !title.equals(GUIManager.T_MY_INFO)
                && !title.equals(GUIManager.T_INVITE)
                && !title.equals(
                GUIManager.T_TASK_CENTER)
                && !title.equals(
                GUIManager.T_GIFT_STAGES)
                && !title.equals("§6§l任务面板")
                && !title.equals(
                "§6§l垃圾回收站")) {
            e.setCancelled(true);
            if (slot == 49) {
                gui.openMain(p);
                return;
            }
            // ★ bounds检查 ★
            if (slot < 45
                    && slot < e.getInventory()
                    .getSize()
                    && e.getCurrentItem() != null) {
                ItemMeta subLm =
                        e.getCurrentItem().getItemMeta();
                if (subLm != null
                        && subLm.getLore() != null) {
                    for (String subLn
                            : subLm.getLore()) {
                        if (subLn.contains(
                                "§7指令: §f")) {
                            String cmd =
                                    subLn.replace(
                                            "§7指令: §f",
                                            "").trim();
                            if (cmd.isEmpty()
                                    || cmd.equals("/")
                                    || cmd.equals(
                                    "null")) {
                                p.sendMessage(
                                        "§c指令为空");
                                return;
                            }
                            p.closeInventory();
                            if (cmd.startsWith("/"))
                                cmd = cmd.substring(1);
                            Bukkit.dispatchCommand(
                                    p, cmd);
                            return;
                        }
                    }
                }
            }
            return;
        }

        // ===== [DEBUG] 标题追踪 =====
        {
            String dt = title;
            if (dt.contains("钱包")
                    || dt.contains("余额")
                    || dt.contains("流水")
                    || dt.contains("转账")) {
                getLogger().info(
                        "[DEBUG-CLICK] "
                                + "player=" + p.getName()
                                + " title=[" + dt + "]"
                                + " slot=" + slot
                                + " codePoints="
                                + debugCodePoints(dt));
            }
        }

        // ===== 二级菜单（.txt）—— 排除所有固定GUI =====
        if (title.startsWith("§6§l")
                && !title.equals(GUIManager.T_MAIN)
                && !title.equals(GUIManager.T_ADMIN)
                && !title.equals(GUIManager.T_MY_INFO)
                && !title.equals(GUIManager.T_INVITE)
                && !title.equals(
                GUIManager.T_TASK_CENTER)
                && !title.equals(
                GUIManager.T_GIFT_STAGES)
                && !title.equals("§6§l任务面板")
                && !title.equals("§6§l垃圾回收站")
                && !title.equals("§6§l我的钱包")
                && !title.equals("§6§l余额操作")) {
            e.setCancelled(true);
            if (slot == 49) {
                gui.openMain(p);
                return;
            }
            if (slot < 45
                    && slot < e.getInventory()
                    .getSize()
                    && e.getCurrentItem() != null) {
                ItemMeta subLm = e.getCurrentItem()
                        .getItemMeta();
                if (subLm != null
                        && subLm.getLore() != null) {
                    for (String subLn
                            : subLm.getLore()) {
                        if (subLn.contains(
                                "§7指令: §f")) {
                            String cmd = subLn
                                    .replace("§7指令: §f", "")
                                    .trim();
                            if (cmd.isEmpty()
                                    || cmd.equals("/")
                                    || cmd.equals("null")) {
                                p.sendMessage("§c指令为空");
                                return;
                            }
                            p.closeInventory();
                            if (cmd.startsWith("/")) {
                                cmd = cmd.substring(1);
                            }
                            Bukkit.dispatchCommand(p, cmd);
                            return;
                        }
                    }
                }
            }
            return;
        }

        // ===== 我的钱包（唯一handler） =====
        if (title.equals("§6§l我的钱包")) {
            e.setCancelled(true);
            if (slot == 22) {
                gui.openMain(p);
            } else if (slot == 12) {
                openWalletTransactions(p, 7);
            } else if (slot == 13) {
                openWalletTransactions(p, 14);
            } else if (slot == 14) {
                openWalletTransfers(p);
            }
            return;
        }

        // ===== 流水/转账记录（唯一handler） =====
        if (title.startsWith("§e§l流水记录")
                || title.startsWith("§e§l转账记录")) {
            e.setCancelled(true);
            if (slot == 53) {
                openMyWallet(p);
            }
            return;
        }

        // ===== 余额操作（唯一handler） =====
        if (title.equals("§d§l余额操作")) {
            e.setCancelled(true);
            if (slot == 49) {
                gui.openMain(p);
                return;
            }
            if (slot >= 0
                    && slot < e.getInventory().getSize()) {
                ItemStack item = e.getInventory()
                        .getItem(slot);
                if (item == null
                        || !item.hasItemMeta()
                        || !item.getItemMeta()
                        .hasDisplayName()) {
                    return;
                }
                // 在 slot == 49 的 return 之后、读取 item 之前
             //   ItemStack item = e.getInventory().getItem(slot);
                if (item == null) return;
                Material mat = item.getType();
// 以下是常见的装饰性玻璃板材质，按需扩展
                if (mat.name().contains("STAINED_GLASS")
                        || mat.name().contains("GLASS_PANE")
                        || mat == Material.LIGHT_BLUE_STAINED_GLASS_PANE
                        || mat == Material.GRAY_STAINED_GLASS_PANE
                        // ... 其他装饰物
                        || mat == Material.BLACK_STAINED_GLASS_PANE) {
                    e.setCancelled(true);
                    return; // 装饰物品，不处理
                }

                String targetName = item.getItemMeta()
                        .getDisplayName()
                        .replace("§e", "")
                        .replace("§a", "");
                if (targetName.equals(p.getName())) {
                    p.sendMessage("§c不能操作自己");
                    return;
                }
                if (e.isLeftClick()) {
                    balanceTarget = targetName;
                    balanceGiveMode = true;
                    p.closeInventory();
                    p.sendMessage(
                            "§e请输入金额和理由");
                    p.sendMessage(
                            "§7例: +100 给款 / -100 扣款");
                } else if (e.isRightClick()) {
                    if (this.getCDK() != null) {
                        this.getCDK().requestInput(
                                p, "bond",
                                targetName);
                    }
                    p.closeInventory();
                    p.sendMessage(
                            "§e【债券操作】对 §a"
                                    + targetName
                                    + " §e进行债券操作");
                    p.sendMessage(
                            "§e输入 +金额 给债券,"
                                    + " -金额 扣债券");
                    p.sendMessage(
                            "§7可附加理由: §a+100 "
                                    + "活动奖励 §7不填默认:"
                                    + "管理员手动调整");
                }
            }
            return;
        }




// ===== 菜单管理 =====
        if (title.equals("§c§l菜单管理")) {
            e.setCancelled(true);
            if (slot == 26) {
                gui.openMain(p);
            } else if (slot == 22) {
                gui.openEditor(p, -1);
            } else if (slot >= 0 && slot < 9) {
                List<MenuManager.MenuItem> items =
                        getMenu().getItems();
                if (slot < items.size()) {
                    if (e.isLeftClick()) {
                        gui.openEditor(p, slot);
                    } else if (e.isRightClick()) {
                        MenuManager.MenuItem mi =
                                items.get(slot);
                        if ("OP".equals(mi.permType)) {
                            mi.permType =
                                    "玩家";
                        } else {
                            mi.permType = "OP";
                        }
                        gui.openMenuManager(p);
                        p.sendMessage(
                                "§a" + mi.title
                                        + " §7权限切换为: §e"
                                        + mi.permType);
                    }
                }
            }
            return;
        }

        // ===== 全局 slot 检查（放后面） =====
        if (e.getClickedInventory() == null) return;
        if (e.getClickedInventory()
                != e.getView().getTopInventory())
            return;
        if (slot < 0 || slot >= e.getInventory()
                .getSize()) return;
        if (e.getCurrentItem() == null) return;


        // ==================== 群系传送（新） ====================
        if (title.equals(GUIManager.T_BIOME)) {
            e.setCancelled(true);
            if (slot == 49) {
                gui.openMain(p);
                return;
            }
            if (slot < 12) {
                String[] cats = {
                        "海洋", "平原", "森林", "沙漠",
                        "恶地", "雪原", "沼泽", "河流",
                        "地下", "蘑菇岛", "下界", "末地"
                };
                p.closeInventory();
                gui.teleportToBiome(p, cats[slot]);
            }
            return;
        }

        // ===== 编辑器 =====
        if (title.equals(GUIManager.T_EDITOR)) {
            e.setCancelled(true);
            if (slot >= 27) return;
            List<MenuManager.MenuItem> el =
                    getMenu().getItems();
            int ei = el.size() - 1;
            if (ei < 0) return;
            MenuManager.MenuItem em = el.get(ei);
            switch (slot) {
                case 10:
                    getMenu().editTitle(p, ei);
                    break;
                case 11:
                    getMenu().editCommand(p, ei);
                    break;
                case 20:
                    em.permType =
                            "玩家".equals(em.permType)
                                    ? "OP" : "玩家";
                    gui.openEditor(p, ei);
                    break;
                // 编辑器 case 22（保存）
                case 22:
                    if (getMenu() != null) {
                        MenuManager.MenuItem last =
                                getMenu().getItems().get(ei);
                        if (last.command == null
                                || last.command.trim().isEmpty()
                                || last.command.trim()
                                .equals("/")) {
                            p.sendMessage(
                                    "§c指令不能为空，请先填写指令");
                            gui.openEditor(p, ei);
                            return;
                        }
                        getMenu().saveMenu();
                        p.closeInventory();
                        p.sendMessage("§a已保存！");
                        gui.openMain(p);
                    }
                    break;

                case 24:
                    getMenu().loadMenu();
                    // ★ 如果最后一项是空指令的新项，删掉 ★
                    List<MenuManager.MenuItem> cancelItems =
                            getMenu().getItems();
                    if (!cancelItems.isEmpty()) {
                        MenuManager.MenuItem last =
                                cancelItems
                                        .get(cancelItems.size() - 1);
                        if ((last.command == null
                                || last.command.trim().isEmpty()
                                || last.command.trim().equals("/"))
                                && "新菜单项"
                                .equals(last.title)) {
                            cancelItems.remove(
                                    cancelItems.size() - 1);
                        }
                    }
                    p.closeInventory();
                    p.sendMessage("§c已取消");
                    gui.openMain(p);
                    break;
            }
        }
        // ==================== 主菜单（新布局） ====================
        // ==================== 主菜单（新布局） ====================
        if (title.equals(GUIManager.T_MAIN)) {
            e.setCancelled(true);

            // ★ 统一：所有自定义菜单项通过 lore 识别 ★
            if (e.getCurrentItem() != null) {
                ItemMeta meta = e.getCurrentItem()
                        .getItemMeta();
                if (meta != null && meta.getLore() != null) {
                    for (String ln : meta.getLore()) {
                        if (ln.contains("§7指令: §f")) {
                            String cmd = ln.replace(
                                    "§7指令: §f", "").trim();
                            if (cmd.isEmpty()
                                    || cmd.equals("/")
                                    || cmd.equals("null")) {
                                p.sendMessage("§c指令为空");
                                return;
                            }
                            p.closeInventory();
                            if (cmd.startsWith("/")) {
                                cmd = cmd.substring(1);
                            }
                            if (cmd.endsWith(".txt")) {
                                gui.openSubMenu(p, cmd);
                            } else {
                                Bukkit.dispatchCommand(p, cmd);
                            }
                            return;
                        }
                    }
                }
            }

            // 固定按钮
            if (slot == 10) {
                gui.openMyInfo(p);
            } else if (slot == 12) {
                gui.openInvite(p);
            } else if (slot == 14) {
                p.openInventory(points.createShopGUI(p));
            } else if (slot == 16) {
                gui.openBiomeMenu(p);
            } else if (slot == 19) {
                ticket.openMain(p);
            } else if (slot == 20) {
                openMyWallet(p);
            } else if (slot == 21) {
                dailySignWithReward(p);
                gui.openMain(p);
            } else if (slot == 23) {
                gui.openTaskCenter(p);
            } else if (slot == 25) {
                if (p.isOp() || isAdmin(p)) {
                    garbage.openRecycle(p);
                }
            } else if (slot == 29) {
                if (isAdmin(p)) {
                    p.closeInventory();
                    chatInput.getState(p).type =
                            ChatInputManager.InputType.ADMIN_AUTH;
                    p.sendMessage("请输入管理密码:");
                }
            } else if (slot == 31) {
                if (p.isOp()) {
                    gui.openMenuManager(p);
                }
            } else if (slot == 34) {
                if (p.isOp() || isAdmin(p)) {
                    gui.openBalanceOps(p);
                }
            }


            return;
        }

        // ==================== 新人礼包 ====================
        if (title.equals(GUIManager.T_GIFT_STAGES)) {
            e.setCancelled(true);
            if (slot == 26) {
                gui.openTaskCenter(p);
            } else if (slot >= 10 && slot <= 18) {
                QuestTracker qt = getQuestTracker();
                if (qt != null) {
                    List<QuestTracker.QuestFile> quests =
                            qt.getQuests("新人任务");
                    int idx = slot - 10;
                    if (idx >= 0 && idx < quests.size()) {
                        QuestTracker.QuestFile qf =
                                quests.get(idx);
                        String pn = p.getName();
                        if (qt.hasClaimed(pn, qf)) {
                            p.sendMessage(
                                    "§c该阶段奖励已领取");
                        } else if (!qt.isStageCompleted(
                                pn, qf)) {
                            p.sendMessage("§c条件未满足");
                        } else {
                            qt.claimRewards(p, qf);
                            qt.markClaimed(pn, qf);
                            gui.openGiftStages(p);
                            playSuccessSound(p);
                        }
                    }
                } else {
                    int stage = slot - 9;
                    if (gift.canClaim(p, stage)) {
                        gift.claimReward(p, stage);
                        gui.openGiftStages(p);
                    } else {
                        p.sendMessage(config.msg(
                                "gift_not_ready"));
                    }
                }
            }
            return;
        }

        // ==================== 主线/支线任务 ====================
        if (title.equals("§d§l主线任务")
                || title.equals("§d§l支线任务")) {
            e.setCancelled(true);
            if (slot == e.getInventory().getSize() - 1) {
                gui.openTaskCenter(p);
            } else if (questTracker != null) {
                String cat = title.contains("主线")
                        ? "主线任务" : "支线任务";
                List<QuestTracker.QuestFile> quests =
                        questTracker.getQuests(cat);
                if (slot < quests.size()) {
                    QuestTracker.QuestFile qf =
                            quests.get(slot);
                    if (!questTracker.isStageCompleted(
                            p.getName(), qf)) {
                        p.sendMessage("§c条件未满足");
                    } else if (questTracker.hasClaimed(
                            p.getName(), qf)) {
                        p.sendMessage("§c已领取过");
                    } else {
                        questTracker.claimRewards(p, qf);
                        questTracker.markClaimed(
                                p.getName(), qf);
                        gui.openTaskList(p, cat);
                        playSuccessSound(p);
                    }
                }
            }
            return;
        }

        // ==================== 我的信息 ====================
        if (title.equals(GUIManager.T_MY_INFO)) {
            e.setCancelled(true);
            // ★ 图标槽(slot 31)和清除槽(slot 32)调用自定义图标处理
            if ((slot == 31 || slot == 32)
                    && gui.handleMyInfoIconClick(p, e)) {
                return;
            }
            if (slot == 26) gui.openMain(p);

            else if (slot == 10) {
                if (checkIn.isCheckedInToday(
                        p.getName()))
                    p.sendMessage(
                            config.msg("checkin_already"));
                else {
                    p.sendMessage(checkIn.checkIn(p));
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
            } else if (slot == 15) {
                p.openInventory(
                        points.createShopGUI(p));
            }
            return;
        }


        // ==================== 管理面板 ====================
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
                p.sendMessage(
                        "§e输入新地址(0跳过):");
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

        // ==================== 任务中心 ====================
        if (title.equals(GUIManager.T_TASK_CENTER)) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 10)
                gui.openGiftStages(p);
            else if (slot == 12) {
                if (questTracker != null) {
                    Location loc = p.getLocation();
                    String result =
                            questTracker
                                    .checkInAtPosition(
                                            p.getName(),
                                            p.getWorld()
                                                    .getName(),
                                            loc.getX(),
                                            loc.getY(),
                                            loc.getZ());
                    p.sendMessage(result);
                    playSuccessSound(p);
                }
            } else if (slot == 14)
                gui.openTaskList(p, "主线任务");
            else if (slot == 16)
                gui.openTaskList(p, "支线任务");
            else if (slot == 18)
                ticket.openMain(p);
            else if (slot == 20) {
                dailySignWithReward(p);
                gui.openMain(p);
            }
            return;
        }

        // ==================== 邀请 ====================
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

        // ==================== 用户管理 ====================
        if (title.equals(GUIManager.T_USER_MGMT)) {
            e.setCancelled(true);
            if (slot == 53) gui.openAdmin(p);
            else if (slot >= 0 && slot < 45) {
                ItemStack item =
                        e.getInventory().getItem(slot);
                if (item != null && item.hasItemMeta()
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

        // ==================== 用户详情 ====================
        if (title.startsWith("§e§l管理: ")) {
            e.setCancelled(true);
            String tgt = title.substring(
                    "§e§l管理: ".length());
            if (slot == 22) gui.openUserManagement(p);
            else if (slot == 10) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_SET_POINTS;
                chatInput.getState(p)
                        .targetPlayer = tgt;
                p.closeInventory();
                p.sendMessage(
                        "§e输入" + tgt + "的新积分:");
            } else if (slot == 12) {
                chatInput.getState(p).type =
                        ChatInputManager.InputType
                                .ADMIN_SET_PWD;
                chatInput.getState(p)
                        .targetPlayer = tgt;
                p.closeInventory();
                p.sendMessage(
                        "§e输入" + tgt + "的临时密码:");
            } else if (slot == 14) {
                Player tp = Bukkit.getPlayer(tgt);
                if (tp != null && tp.isOnline()) {
                    forceGiveMenuSnowball(tp);
                    p.sendMessage(
                            "§a已发放雪球菜单给 "
                                    + tgt);
                } else {
                    p.sendMessage("§c玩家不在线");
                }
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

        // ==================== 积分商城 ====================
        if (title.startsWith("§d§l积分商城")) {
            e.setCancelled(true);
            if (slot == 49) gui.openMain(p);
            else if (slot == 50 && isAdmin(p))
                gui.openShopAdmin(p);
            else points.handleClick(p, slot);
            return;
        }

        // ==================== 商城管理 ====================
        if (title.equals("§c§l商城管理")) {
            e.setCancelled(true);
            if (slot == 49)
                p.openInventory(
                        points.createShopGUI(p));
            return;
        }

        // ==================== CY背包商城 ====================
        if (title.startsWith("§d§lCY背包商城")) {
            e.setCancelled(true);
            int size = e.getInventory().getSize();
            if (slot == size - 1)
                p.openInventory(
                        points.createShopGUI(p));
            else points.handleCYClick(p, slot);
            return;
        }

        // ==================== 任务面板 ====================
        if (title.equals("§6§l任务面板")) {
            e.setCancelled(true);
            if (slot == 22) gui.openMain(p);
            else if (slot == 11) {
                Location loc = p.getLocation();
                String result =
                        questTracker.checkInAtPosition(
                                p.getName(),
                                p.getWorld().getName(),
                                loc.getX(), loc.getY(),
                                loc.getZ());
                p.sendMessage(result);
                playSuccessSound(p);
                openTaskPanel(p);
            } else if (slot == 15) {
                gui.openGiftStages(p);
            }
            return;
        }

        // ==================== 账号请求面板 ====================
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
        }
    }

            /*
     * 签到 + 盲盒经济奖励
     */
    public void dailySignWithReward(Player p) {
        String name = p.getName();

        if (checkIn.isCheckedInToday(name)) {
            p.sendMessage(
                    config.msg("checkin_already"));
            return;
        }

        // ===== 1. 执行签到（内部已加积分） =====
        p.sendMessage(checkIn.checkIn(p));
        invite.onInviteeCheckIn(name);

        // ===== 2. 获取连续天数 =====
        int streak = checkIn.getStreak(name);
        double multiplier = 1.0;
        if (streak >= 21) {
            multiplier = 1.3;
        } else if (streak >= 14) {
            multiplier = 1.2;
        } else if (streak >= 7) {
            multiplier = 1.1;
        }

        // ===== 3. 经济奖励（不再重复加积分） =====
        if (economy == null) {
            playSuccessSound(p);
            return;
        }

        // 固定金额模式
        if ("fixed".equals(
                config.checkinRewardType)
                && config.checkinRewardFixed > 0) {
            final double reward =
                    config.checkinRewardFixed;
            final double m = multiplier;
            p.sendMessage("§e§l[签到] §f"
                    + "正在生成今日奖励...");
            Bukkit.getScheduler()
                    .runTaskLater(this, () -> {
                        double rewardFinal =
                                Math.round(
                                        reward * m
                                                * 100.0)
                                        / 100.0;
                        double before =
                                economy
                                        .getBalance(p);
                        economy.depositPlayer(
                                p, rewardFinal);
                        double after =
                                economy
                                        .getBalance(p);
                        p.sendMessage(
                                "§6§l[签到] §f"
                                        + "获得奖励: §a§l$"
                                        + String.format(
                                        "%.2f",
                                        rewardFinal));
                        p.sendMessage("§7余额: §e"
                                + String.format(
                                "%.2f", before)
                                + " §7→ §a"
                                + String.format(
                                "%.2f", after));
                        playSuccessSound(p);
                    }, 30L);
            return;
        }

        // 盲盒范围模式
        if ("range".equals(
                config.checkinRewardType)
                && config.checkinRewardMin > 0
                && config.checkinRewardMax
                > config.checkinRewardMin) {
            final double min =
                    config.checkinRewardMin;
            final double max =
                    config.checkinRewardMax;
            final double m = multiplier;
            getLogger().info(
                    "[签到] 盲盒: min="
                            + min + " max=" + max
                            + " multi=" + m);
            p.sendMessage("§e§l[签到盲盒] "
                    + "§f正在生成今日惊喜...");
            Bukkit.getScheduler()
                    .runTaskLater(this, () -> {
                        double rand =
                                Math.random();
                        double reward = min
                                + rand
                                * (max - min);
                        reward = Math.round(
                                reward * m * 100.0)
                                / 100.0;
                        getLogger().info(
                                "[签到] 盲盒结果: rand="
                                        + String.format(
                                        "%.4f", rand)
                                        + " reward="
                                        + reward);
                        double before =
                                economy
                                        .getBalance(p);
                        economy.depositPlayer(
                                p, reward);
                        double after =
                                economy
                                        .getBalance(p);
                        p.sendMessage(
                                "§6§l====== 签到盲盒 ======");
                        p.sendMessage(
                                "§7开出: §a§l$"
                                        + String.format(
                                        "%.2f",
                                        reward));
                        p.sendMessage("§7余额: §e"
                                + String.format(
                                "%.2f", before)
                                + " §7→ §a"
                                + String.format(
                                "%.2f", after));
                        p.sendMessage(
                                "§6§l========================");
                        playSuccessSound(p);
                    }, 40L);
            return;
        }

        // 无经济奖励
        playSuccessSound(p);
    }

    public BondManager getBondManager() {
        return bondManager;
    }


    @EventHandler
    public void onVillagerTrade(
            org.bukkit.event.inventory.TradeSelectEvent e) {
        // TradeSelectEvent 在玩家点击交易时触发
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (isFrozen(p) || questTracker == null) return;
        questTracker.onVillagerTrade(p.getName());
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
// 禁止T_MY_INFO拖拽，只允许点击操作
        // 我的信息：允许拖入图标槽(31)
        if (GUIManager.T_MY_INFO.equals(
                e.getView().getTitle())) {
            boolean toIconSlot = false;
            for (int raw : e.getRawSlots()) {
                if (raw == 31) {
                    toIconSlot = true;
                    break;
                }
            }
            if (toIconSlot) {
                ItemStack dragged =
                        e.getOldCursor().clone();
                // ★ 拦截宝箱自定义物品（事不过三原则）
                if (gui.handleTreasureItemBlock(p, dragged)) {
                    e.setCancelled(true);
                    return;
                }
                String b64 = gui.serializeItem(dragged);
                if (b64 != null) {
                    db.saveMenuIcon(p.getName(), b64,
                            dragged.getType().name());
                }
                p.sendMessage("§a已保存菜单图标: "
                        + dragged.getType().name());
                // 刷新GUI显示
                Bukkit.getScheduler()
                        .runTaskLater(this, () ->
                                gui.openMyInfo(p), 2L);
            }
            e.setCancelled(true);
            return;
        }


        // 回收站拖拽
        if (t.equals("§6§l垃圾回收站")) {
            boolean toGUI = false;
            for (int rawSlot : e.getRawSlots()) {
                if (rawSlot < 54) {
                    toGUI = true;
                    break;
                }
            }
            if (toGUI) {
                ItemStack cur = e.getCursor();
                if (cur != null
                        && cur.getType() != Material.AIR
                        && !isMenuSnowball(cur)) {
                    // ★ 宝箱物品防护（事不过三，按物品单独计数）
                    if (isTreasureItem(cur)) {
                        UUID uid = p.getUniqueId();
                        // 按物品类型单独计数（玩家UUID+物品类型）
                        String itemKey = uid + ":" + cur.getType().name();
                        int count = treasureDiscardCount
                                .getOrDefault(itemKey, 0) + 1;
                        treasureDiscardCount.put(itemKey, count);
                        e.setCancelled(true);
                        if (count < 3) {
                            // 前两次：警告 + 踢回背包
                            p.sendMessage("§c§l[防护] §f该物品是宝箱自定义物品，"
                                    + "禁止投入垃圾站！"
                                    + "§7（第" + count + "/3次警告）");
                            p.getInventory().addItem(cur.clone());
                        } else {
                            // 第三次：直接没收
                            p.sendMessage("§c§l[防护] §f您多次尝试投入宝箱物品，"
                                    + "物品已被没收！");
                            treasureDiscardCount.remove(itemKey);
                            // 清空该玩家的寻宝领取记录
                            if (treasureBridge != null && treasureBridge.isHooked()) {
                                int cleared = treasureBridge.clearPlayerAllClaims(p.getName());
                                if (cleared > 0) {
                                    p.sendMessage("§7已清空" + cleared + "条寻宝领取记录");
                                }
                            }
                        }
                        p.getOpenInventory().setCursor(null);
                        return;
                    }
                    
                    e.setCancelled(true);
                    p.getOpenInventory().setCursor(null);
                    garbage.saveItem(cur.clone());
                    Bukkit.getScheduler()
                            .runTaskLater(this, () -> {
                                garbage.openRecyclePage(
                                        p,
                                        garbage
                                                .getRecyclePage(
                                                        p
                                                                .getName()));
                            }, 2L);
                    return;
                }
            }
            return;
        }



        //服务商工单系统
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
    /** 验证玩家是否在主库中真实存在 */
    public boolean isRealPlayer(String name) {
        if (name == null || name.isEmpty()) return false;
        try {
            Map<String, Object> user =
                    getDb().getUser(name);
            return user != null && !user.isEmpty()
                    && user.containsKey("player_name");
        } catch (Exception e) {
            return false;
        }
    }

    // ===== Commands =====
    @Override
    public boolean onCommand(CommandSender sender,
                             Command cmd, String label,
                             String[] args) {

        String cmdName = cmd.getName().toLowerCase();
        // 区域防护（必须在最前面）
        if (cmd.getName().equalsIgnoreCase("protect")
                || cmd.getName().equals("区域保护")) {
            if (areaProtection == null) {
                sender.sendMessage("§c区域防护未初始化");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage("§a§l===== 区域防护 =====");
                sender.sendMessage("§e/protect 工具 §7获取选区工具");
                sender.sendMessage("§e/protect 创建 <区域名> §7创建区域");
                sender.sendMessage("§e/protect 列表 §7查看所有区域");
                sender.sendMessage("§e/protect 重载 §7重载配置");
                sender.sendMessage("§e/protect add <玩家> §7全局加白");
                sender.sendMessage("§e/protect add <区域> <玩家> §7区域加白");
                sender.sendMessage("§e/protect remove <玩家> §7全局删白");
                sender.sendMessage("§e/protect remove <区域> <玩家> §7区域删白");
                sender.sendMessage("§e/protect additem <物品ID> §7全局加黑");
                sender.sendMessage("§e/protect additem <区域> <物品ID> §7区域加黑");
                sender.sendMessage("§e/protect removeitem <物品ID> §7全局删黑");
                sender.sendMessage("§e/protect removeitem <区域> <物品ID> §7区域删黑");
                sender.sendMessage("§e/protect list §7全局白名单");
                sender.sendMessage("§e/protect list <区域> §7区域白名单");
                sender.sendMessage("§e/protect listitem §7全局物品黑名单");
                sender.sendMessage("§e/protect listitem <区域> §7区域物品黑名单");
                sender.sendMessage("§e/protect expand [格数] §7扩建选区");
                sender.sendMessage("§e/protect contraction [格数] §7收缩选区");
                sender.sendMessage("§e/protect addname <区域> <名字> §7添加和平白名单生物");
                sender.sendMessage("§e/protect removename <区域> <名字> §7移除和平白名单生物");
                sender.sendMessage("§e/protect addwhite <玩家> §7添加模式排除玩家");
                sender.sendMessage("§e/protect removewhite <玩家> §7移除模式排除玩家");
                sender.sendMessage("§e/protect listname <区域> §7查看和平白名单");
                sender.sendMessage("§e/protect listwhite §7查看模式排除名单");

                return true;
            }
            // 其他子命令交给 areaProtection
            try {
                return areaProtection.handleCommand(sender, args);
            } catch (Exception e) {
                sender.sendMessage("§c执行出错: " + e.getMessage());
                e.printStackTrace();
                return true;
            }
        }

        // ★ 独立 /update 命令 → 集群更新所有插件
        if (cmd.getName().equalsIgnoreCase("update")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            sender.sendMessage("§e[更新] 正在检查所有插件更新...");
            checkAllPluginsUpdate(sender);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("shop")
                || cmd.getName().equals("商店")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可使用");
                return true;
            }
            if (shopManager != null) {
                shopManager.openShopMain((Player) sender);
            } else {
                sender.sendMessage("§c商店系统未加载");
            }
            return true;
        }

        String sub = (args.length > 0) ? args[0].toLowerCase() : "";

// ===== 在 onCommand 方法体内，最开头加： =====
        if (cmd.getName().equalsIgnoreCase("sdf1_login")
                && args.length > 0
                && args[0].equalsIgnoreCase("shop")) {
            if (shopManager == null) {
                sender.sendMessage("§c商店未加载");
                return true;
            }
            return shopManager.handleCommand(sender, args);
        }
        // set
        if (sub.equals("set")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage("§e用法: /sdf1_login set <玩家> <临时密码>");
                return true;
            }
            String tgt = args[1];
            String tempPwd = args[2];
            if (!db.userExists(tgt)) {
                sender.sendMessage("§c玩家不存在");
                return true;
            }
            String salt = (String) db.getField(tgt, "password_salt");
            String hash = PasswordUtils.hash(tempPwd, salt);
            db.setField(tgt, "temp_password", hash);
            db.setField(tgt, "temp_pw_expire",
                    System.currentTimeMillis() + 300000L);
            db.setField(tgt, "temp_pw_used", 0);

            // ★ 立即发邮件（踢人之前）
            String emailAddr = (String) db.getField(tgt, "email");
            if (emailAddr != null && !emailAddr.isEmpty()) {
                // 获取最近登录信息
                Object lastLoginObj = db.getField(tgt, "last_login_time");
                long lastLogin = lastLoginObj != null
                        ? ((Number) lastLoginObj).longValue() : 0;
                String lastIP = (String) db.getField(tgt, "last_ip");
                if (lastIP == null || lastIP.isEmpty())
                    lastIP = "未知";

                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String loginTimeStr = lastLogin > 0
                        ? sdf.format(new java.util.Date(lastLogin))
                        : "未知时间";

                final String to = emailAddr;
                final String fName = tgt;
                final String pwd = tempPwd;
                final String ipInfo = lastIP;
                final String timeInfo = loginTimeStr;
                final Main self = this;

                Bukkit.getScheduler().runTaskAsynchronously(this,
                        () -> {
                            // ★ 发送临时密码邮件（带登录信息）
                            boolean sent = email.sendTempPassword(
                                    to, fName, pwd);
                            // ★ 额外发送安全通知邮件
                            if (sent) {
                                String body = "玩家 " + fName
                                        + "：\n\n"
                                        + "管理员已为您的账号设置了临时密码。\n\n"
                                        + "临时密码: " + pwd + "\n"
                                        + "过期时间: 5分钟内\n\n"
                                        + "最近登录记录:\n"
                                        + "  时间: " + timeInfo + "\n"
                                        + "  IP地址: " + ipInfo + "\n\n"
                                        + "如果您本人未操作，请忽略此邮件。\n"
                                        + "如果您怀疑账号被盗，请立即修改密码。";
                                email.sendBody(to,
                                        "【安全通知】账号临时密码已设置", body);
                            }
                            Bukkit.getScheduler().runTask(self, () -> {
                                if (sent) {
                                    sender.sendMessage("§a临时密码已发送至 "
                                            + tgt + " 的邮箱");
                                }
                            });
                        });
            } else {
                sender.sendMessage("§7该玩家未绑定邮箱，跳过邮件通知");
            }

            // 踢人
            Player tp = Bukkit.getPlayer(tgt);
            if (tp != null && tp.isOnline())
                tp.kickPlayer("§c管理员已设置了临时密码，请用新密码登录");
            sender.sendMessage("§a已为 " + tgt + " 设置临时密码");
            return true;
        }


        if (label.equalsIgnoreCase("pvp")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p = (Player) sender;
            return pvpManager.onCommand(p, args);
        }

        if (cmdName.equals("cypay")) {
            if (args.length > 0) {
                String target = args[0];
                if (!getDb().userExists(target)) {
                    sender.sendMessage("§c玩家 " + target
                            + " 不存在于服务器");
                    return true;
                }
            }
            return cypayCommand.onCommand(
                    sender, cmd, label, args);
        }



        // ===== /printer [玩家名] =====
        if (label.equalsIgnoreCase("printer")) {
            if (bondPrinter == null) {
                sender.sendMessage("§cPrinter未初始化");
                return true;
            }
            if (!sender.isOp()
                    && !isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (args.length == 0) {
                // 打印全服
                sender.sendMessage(
                        "§e正在导出全服流水...");
                bondPrinter.printAll(sender);
                return true;
            }
            if (args.length == 1) {
                // 打印指定玩家
                String target = args[0];
                sender.sendMessage("§e正在导出 "
                        + target + " 的流水...");
                bondPrinter.printPlayer(
                        target, sender);
                return true;
            }
            sender.sendMessage(
                    "§7用法: /printer [玩家名]");
            return true;
        }
        // ===== /chat 独立命令 =====
        if (cmdName.equals("chat")
                || cmdName.equals("聊天")) {
            if (sender instanceof Player) {
                Player chatP = (Player) sender;
                if (!chatP.getScoreboardTags()
                        .contains(config.adminTag)) {
                    sender.sendMessage("§c权限不足");
                    return true;
                }
            }
            if (args.length < 1) {
                sender.sendMessage(
                        "§e/chat <reload|开|关|add|remove|addplayer|takeplayer|unmute|reset>");
                return true;
            }
            String cs = args[0].toLowerCase();
            if (cs.equals("reload")) {
                chatFilter.loadConfig();
                sender.sendMessage("§a聊天配置已重载");
                return true;
            }
            if (cs.equals("开") || cs.equals("on")) {
                chatFilter.setEnabled(true);
                chatFilter.saveConfig();
                sender.sendMessage("§aURL过滤已开启");
                return true;
            }
            if (cs.equals("关") || cs.equals("off")) {
                chatFilter.setEnabled(false);
                chatFilter.saveConfig();
                sender.sendMessage("§cURL过滤已关闭");
                return true;
            }
            if (cs.equals("add")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat add <URL>");
                    return true;
                }
                chatFilter.addUrl(args[1]);
                sender.sendMessage("§a已添加白名单: " + args[1]);
                return true;
            }
            if (cs.equals("remove")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat remove <URL>");
                    return true;
                }
                if (chatFilter.removeUrl(args[1]))
                    sender.sendMessage("§c已移除: " + args[1]);
                else
                    sender.sendMessage("§c未找到: " + args[1]);
                return true;
            }
            if (cs.equals("addplayer")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat addplayer <玩家>");
                    return true;
                }
                chatFilter.addWhitelistPlayer(args[1]);
                sender.sendMessage("§a白名单玩家: " + args[1]);
                return true;
            }
            if (cs.equals("takeplayer")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat takeplayer <玩家>");
                    return true;
                }
                if (chatFilter.removeWhitelistPlayer(args[1]))
                    sender.sendMessage("§c已移除白名单: " + args[1]);
                else
                    sender.sendMessage("§c未找到: " + args[1]);
                return true;
            }
            if (cs.equals("unmute")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat unmute <玩家>");
                    return true;
                }
                chatFilter.unmutePlayer(args[1]);
                sender.sendMessage("§a已解禁: " + args[1]);
                return true;
            }
            if (cs.equals("reset")) {
                if (args.length < 2) {
                    sender.sendMessage("§e/chat reset <玩家>");
                    return true;
                }
                chatFilter.resetPlayer(args[1]);
                sender.sendMessage("§a已重置: " + args[1]);
                return true;
            }
            sender.sendMessage("§c未知子命令");
            return true;
        }
        if (label.equalsIgnoreCase("sdf1_login")
                && args.length >= 2
                && args[0].equalsIgnoreCase("give")) {
            if (!sender.isOp()) {
                sender.sendMessage("§c仅OP可用");
                return true;
            }
            Player target =
                    Bukkit.getPlayer(args[1]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(
                        "§c玩家不在线: " + args[1]);
                return true;
            }
            giveMenuSnowball(target);
            sender.sendMessage("§a已发放菜单雪球给 "
                    + target.getName());
            target.sendMessage(
                    "§a管理员已发放菜单雪球给你");
            return true;
        }

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
        // /menu 开关雪球菜单
        if (cmdName.equals("menu")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(
                        "§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            if (args.length == 0) {
                p2.sendMessage(
                        "§e用法: /menu <开/关>");
                return true;
            }
            String arg = args[0];
            if (arg.equals("开")) {
                db.setField(p2.getName(),
                        "menu_snowball", 1);
                p2.sendMessage(
                        "§a雪球菜单已开启，"
                                + "下次进入将发放");
            } else if (arg.equals("关")) {
                db.setField(p2.getName(),
                        "menu_snowball", 0);
                p2.sendMessage(
                        "§c雪球菜单已关闭，"
                                + "下次进入不再发放");
            } else {
                p2.sendMessage(
                        "§e用法: /menu <开/关>");
            }
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
                // [ADDED] 欢迎仪式
                if (welcome != null
                        && loggedIn.contains(p.getName())) {
                    welcome.onRegister(p);
                }

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
                // [FIX] 手动登录后关闭飞行（仅一次）
                if (loggedIn.contains(p.getName())
                        && p.getAllowFlight()) {
                    p.setAllowFlight(false);
                    p.setFlying(false);
                }

                // [ADDED] 欢迎仪式
                if (welcome != null
                        && loggedIn.contains(p.getName())) {
                    welcome.onLogin(p);
                }

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

// ===== del / confirm / cancel（最先处理） =====
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
            String target = args[1];
            if (!db.userExists(target)) {
                sender.sendMessage(
                        "§c玩家 " + target + " 不存在");
                return true;
            }
            if (pendingDeleteTask != null) {
                pendingDeleteTask.cancel();
                String old = pendingDeleteName;
                pendingDeleteTask = null;
                pendingDeleteName = null;
                getLogger().info(
                        "[Sdf1_login] 取消旧待删除: " + old);
            }
            pendingDeleteName = target;
            pendingDeleteTask = new BukkitRunnable() {
                @Override
                public void run() {
                    String name = pendingDeleteName;
                    pendingDeleteName = null;
                    pendingDeleteTask = null;
                    if (name == null) return;
                    db.deleteUser(name);
                    getLogger().info(
                            "[Sdf1_login] 已删除: " + name);
                    Player tp = Bukkit.getPlayer(name);
                    if (tp != null && tp.isOnline())
                        tp.kickPlayer(
                                "§c账号已被管理员删除");
                }
            };
            pendingDeleteTask.runTaskLater(this, 1200L);
            sender.sendMessage("§e§l[删除确认] 目标: §c"
                    + target);
            sender.sendMessage("§760秒后执行，"
                    + "§a/sdf1_login confirm §7确认，"
                    + "§c/sdf1_login cancel §7取消");
            getLogger().warning("[Sdf1_login] 待删除: "
                    + target + " by " + sender.getName());
            return true;
        }

        if (sub.equals("confirm")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (pendingDeleteName == null
                    || pendingDeleteTask == null) {
                sender.sendMessage(
                        "§c当前没有待删除的账号");
                return true;
            }
            pendingDeleteTask.cancel();
            String name = pendingDeleteName;
            pendingDeleteName = null;
            pendingDeleteTask = null;
            db.deleteUser(name);
            sender.sendMessage("§a已立即删除: " + name);
            Player tp = Bukkit.getPlayer(name);
            if (tp != null && tp.isOnline())
                tp.kickPlayer("§c账号已被管理员删除");
            return true;
        }

        if (sub.equals("cancel")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (pendingDeleteName == null
                    || pendingDeleteTask == null) {
                sender.sendMessage(
                        "§c当前没有待删除的账号");
                return true;
            }
            pendingDeleteTask.cancel();
            String name = pendingDeleteName;
            pendingDeleteName = null;
            pendingDeleteTask = null;
            sender.sendMessage("§a已取消删除: " + name);
            return true;
        }

// ===== stop =====
        if (sub.equals("stop")) {
            if (!(sender instanceof Player)) {
                if (args.length >= 2) {
                    db.clearTempPassword(args[1]);
                    sender.sendMessage("§a已报废 "
                            + args[1] + " 的临时密码");
                } else {
                    db.clearAllTempPasswords();
                    sender.sendMessage("§a已报废所有临时密码");
                }
                return true;
            }
            Player p2 = (Player) sender;
            if (args.length >= 2) {
                String tgt = args[1];
                String myIP = getPlayerIP(p2);
                String targetIP =
                        db.getPlayerIPByName(tgt);
                if (targetIP == null) {
                    Player tp = Bukkit.getPlayer(tgt);
                    if (tp != null && tp.isOnline())
                        targetIP = getPlayerIP(tp);
                }
                if (targetIP == null
                        || !targetIP.equals(myIP)) {
                    p2.sendMessage("§c只能操作同IP下的玩家");
                    return true;
                }
                db.clearTempPassword(tgt);
                p2.sendMessage("§a已报废 " + tgt + " 的临时密码");
            } else {
                db.clearTempPassword(p2.getName());
                p2.sendMessage("§a已报废您的临时密码");
            }
            return true;
        }

// ===== reload =====
        if (sub.equals("reload")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            // 重载时清理.sdf1临时文件
            cleanupSdf1Files();
            config.loadMessages();
            config.loadSmtp();
            config.loadSettings();
            chatFilter.loadConfig();
            if (radio != null) radio.reload();
            if (getMenu() != null) {
                getMenu().loadMenu();
            }
            if (shopManager != null) {
                shopManager.loadCategories();
            }
            if (shopManager != null) shopManager.loadCategories();
            if (areaProtection != null) {
                areaProtection.clearAllPlayerAreas();
            }
            if (questTracker != null) {
                questTracker.reload();
            }
            sender.sendMessage("§a§l配置已重载！");
            return true;
        }

// ===== update =====
        if (sub.equals("update")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            if (updateChecker == null) {
                updateChecker = new UpdateChecker(this);
            }
            updateChecker.checkUpdate(sender);
            return true;
        }

// ===== updateall =====
        if (sub.equals("updateall")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            // ★ 集群更新：同时唤醒所有sdf1系列插件检查更新
            sender.sendMessage("§e[更新] 正在检查所有插件更新...");
            checkAllPluginsUpdate(sender);
            return true;
        }

// ===== back =====
        if (sub.equals("back")) {
            if (!isAdmin(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            // ... 保持原有back代码不变 ...
            if (args.length < 2) {
                sender.sendMessage("§e用法: /sdf1_login back <玩家> [#编号]");
                return true;
            }
            // 原有back逻辑
            String backTarget = args[1];
            if (args.length >= 3
                    && args[2].startsWith("#")) {
                int backupId;
                try {
                    backupId = Integer.parseInt(
                            args[2].replace("#", ""));
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§c无效编号");
                    return true;
                }
                String[] backup =
                        db.getInventoryBackupById(backupId);
                if (backup == null) {
                    sender.sendMessage("§c备份 #" + backupId + " 不存在");
                    return true;
                }
                Player tp = Bukkit.getPlayer(backTarget);
                if (tp != null && tp.isOnline()) {
                    try {
                        tp.getInventory().setContents(
                                InventorySerializer.fromBase64(backup[0]));
                        if (backup[1] != null && !backup[1].isEmpty())
                            tp.getInventory().setArmorContents(
                                    InventorySerializer.fromBase64(backup[1]));
                        if (backup[2] != null && !backup[2].isEmpty())
                            tp.getInventory().setExtraContents(
                                    InventorySerializer.fromBase64(backup[2]));
                        int lv = Integer.parseInt(backup[3]);
                        if (lv > 0) tp.setLevel(lv);
                        tp.sendMessage("§a管理员已恢复您的背包");
                        sender.sendMessage("§a已恢复 " + backTarget + " (备份#" + backupId + ")");
                    } catch (Exception e) {
                        sender.sendMessage("§c恢复失败: " + e.getMessage());
                    }
                } else {
                    db.saveInventoryBackup(backTarget,
                            backup[0], backup[1], backup[2],
                            Integer.parseInt(backup[3]),
                            Double.parseDouble(backup[4]));
                    sender.sendMessage("§a" + backTarget + " 离线，备份已写入");
                }
            } else {
                List<Map<String, Object>> backups =
                        db.getInventoryBackups(backTarget, 5);
                if (backups.isEmpty()) {
                    sender.sendMessage("§c" + backTarget + " 没有背包备份");
                    return true;
                }
                sender.sendMessage("§e§l=== " + backTarget + " 背包备份 ===");
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
                for (Map<String, Object> b : backups) {
                    int bid = ((Number) b.get("id")).intValue();
                    Object stObj = b.get("save_time");
                    long st = stObj != null
                            ? ((Number) stObj).longValue() : 0;
                    String time = st > 0
                            ? sdf.format(new java.util.Date(st))
                            : "未知时间";
                    String itemNames = getBackupItemPreview(bid);
                    sender.sendMessage("§7#" + bid + " §f" + time + " §7: " + itemNames);
                }
                sender.sendMessage("§7使用 §e/sdf1_login back " + backTarget + " #编号 §7恢复");
            }
            return true;
        }

// ===== pw / password =====
        if (sub.equals("pw") || sub.equals("password")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;

            if (args.length >= 3) {
                String oldPwd = args[1];
                String newPwd = args[2];
                if (!PasswordUtils.validate(newPwd)) {
                    p2.sendMessage(config.msg("password_format_error"));
                    return true;
                }
                String salt = (String) db.getField(
                        p2.getName(), "password_salt");
                String hash = PasswordUtils.hash(oldPwd, salt);
                boolean mainOk = db.checkPassword(
                        p2.getName(), hash);
                boolean tempOk = db.checkPasswordOrTemp(
                        p2.getName(), hash);
                if (!mainOk && !tempOk) {
                    p2.sendMessage(config.msg("password_wrong"));
                    return true;
                }
                String oldHash = (String) db.getField(
                        p2.getName(), "password_hash");
                String newSalt = PasswordUtils.generateSalt();
                String newHash = PasswordUtils.hash(newPwd, newSalt);
                if (newHash.equals(oldHash)) {
                    p2.sendMessage(config.msg("password_same"));
                    return true;
                }
                recordPasswordChange(p2.getName(), oldHash, salt);
                db.setField(p2.getName(), "password_hash", newHash);
                db.setField(p2.getName(), "password_salt", newSalt);
                if (!mainOk && tempOk)
                    db.clearTempPassword(p2.getName());
                // pw 命令改密码成功后
                needsPasswordChange.remove(p2.getName());
// 清除临时密码
                if (!mainOk && tempOk)
                    db.clearTempPassword(p2.getName());

                return true;
            }

            if (args.length == 2) {
                String oldPwd = args[1];
                String salt = (String) db.getField(
                        p2.getName(), "password_salt");
                String hash = PasswordUtils.hash(oldPwd, salt);
                boolean mainOk = db.checkPassword(
                        p2.getName(), hash);
                boolean tempOk = db.checkPasswordOrTemp(
                        p2.getName(), hash);
                if (!mainOk && !tempOk) {
                    p2.sendMessage(config.msg("password_wrong"));
                    return true;
                }
                chatInput.getState(p2).type =
                        ChatInputManager.InputType.CHANGE_PWD_STEP2;
                chatInput.getState(p2).ticketTitle =
                        mainOk ? "main" : "temp";
                p2.sendMessage("§e请输入新密码:");
                return true;
            }

            chatInput.getState(p2).type =
                    ChatInputManager.InputType.CHANGE_PWD_STEP1;
            p2.sendMessage("§e请输入当前密码:");
            return true;
        }

// ===== email =====
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
                if (oldEmail != null && !oldEmail.isEmpty()) {
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType.SET_EMAIL_VERIFY;
                    chatInput.getState(p2).targetPlayer = emailAddr;
                    chatInput.getState(p2).ticketTitle = "need_pwd";
                    p2.sendMessage("§e您已绑定邮箱: " + oldEmail);
                    p2.sendMessage("§e请输入登录密码以确认修改:");
                } else {
                    String code = String.valueOf(
                            (int) (Math.random() * 900000 + 100000));
                    chatInput.getState(p2).type =
                            ChatInputManager.InputType.SET_EMAIL_VERIFY;
                    chatInput.getState(p2).targetPlayer = emailAddr;
                    chatInput.getState(p2).ticketTitle = code;
                    final String to = emailAddr;
                    final String c = code;
                    final Player fp = p2;
                    Bukkit.getScheduler()
                            .runTaskAsynchronously(this,
                                    () -> {
                                        boolean sent =
                                                email.sendVerifyCode(to, c);
                                        Bukkit.getScheduler()
                                                .runTask(this, () -> {
                                                    if (sent) {
                                                        fp.sendMessage("§a验证码已发送到 " + to);
                                                        fp.sendMessage("§e输入6位验证码(0取消):");
                                                    } else {
                                                        fp.sendMessage("§c发送失败");
                                                    }
                                                });
                                    });
                }
            } else {
                chatInput.getState(p2).type =
                        ChatInputManager.InputType.SET_EMAIL;
                p2.sendMessage("§e请输入邮箱:");
            }
            return true;
        }

// ===== sign =====
        if (sub.equals("sign")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            dailySignWithReward((Player) sender);
            return true;
        }

// ===== reset =====
        if (sub.equals("reset")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            loginMgr.handleReset((Player) sender);
            return true;
        }

// ===== undo（密码撤销） =====
        if (sub.equals("undo")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c仅玩家可用");
                return true;
            }
            Player p2 = (Player) sender;
            PwdRollback rb = pwdRollback.get(p2.getName());
            if (rb == null) {
                p2.sendMessage("§c无可撤销记录");
                return true;
            }
            if (System.currentTimeMillis() - rb.time > 600000L) {
                pwdRollback.remove(p2.getName());
                p2.sendMessage("§c撤销已过期");
                return true;
            }
            db.setField(p2.getName(), "password_hash", rb.hash);
            db.setField(p2.getName(), "password_salt", rb.salt);
            pwdRollback.remove(p2.getName());
            p2.sendMessage("§a密码已撤销！");
            return true;
        }

            // radio子命令（控制台+玩家）
        // sdf1_login radio → 停止全服广播
        // sdf1_login radio reload → 重载
        // sdf1_login radio <歌名> → 全服播放
        // sdf1_login radio <歌名> <玩家名> → 指定玩家播放
        // sdf1_login radio <歌名> login → 推送给登录中玩家
        if (sub.equals("radio")) {
            if (args.length < 2) {
                // 无参数 = 停止
                radio.stopMainRadio();
                sender.sendMessage("§c全服广播已停止");
                return true;
            }
            String arg1 = args[1];

            // reload
            if (arg1.equalsIgnoreCase("reload")) {
                if (!isAdmin(sender)) {
                    sender.sendMessage("§c权限不足");
                    return true;
                }
                radio.reload();
                sender.sendMessage("§aRadio已重载");
                return true;
            }

            // radio <歌名> → 全服播放
            // radio <歌名> <玩家名> → 指定玩家
            // radio <歌名> login → 登录阶段播放
            String songName = arg1;

            if (args.length >= 3) {
                String target = args[2];

                if (target.equalsIgnoreCase("login")) {
                    if (radio.hasSong(songName)) {
                        radio.playToLoginPlayers(songName);
                        sender.sendMessage(
                                "§a已推送 §e" + songName
                                        + " §a给登录中玩家");
                    } else {
                        sender.sendMessage(
                                "§c未找到歌曲: " + songName);
                        return true;
                    }
                } else {
                    Player targetP =
                            Bukkit.getPlayer(target);
                    if (targetP != null
                            && targetP.isOnline()) {
                        if (radio.hasSong(songName)) {
                            radio.playToPlayer(
                                    targetP, songName);
                            sender.sendMessage(
                                    "§a已向 §e" + target
                                            + " §a播放 §e"
                                            + songName);
                            targetP.sendMessage(
                                    "§6§l[广播] §f正在播放: §e"
                                            + songName);
                        } else {
                            sender.sendMessage(
                                    "§c未找到歌曲: "
                                            + songName);
                        }
                    } else {
                        sender.sendMessage(
                                "§c玩家不在线: "
                                        + target);
                    }
                    return true;
                }
            } else {
                if (radio.hasSong(songName)) {
                    radio.playToAll(songName);
                    sender.sendMessage(
                            "§a全服播放: §e" + songName);
                } else {
                    sender.sendMessage(
                            "§c未找到歌曲: " + songName);
                }
                return true;
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


            // give - 手动发放雪球菜单
            if (sub.equals("give")) {
                if (args.length < 2) {
                    sender.sendMessage(
                            "§e用法: /sdf1_login give <玩家名>");
                    return true;
                }
                Player tp = Bukkit.getPlayer(
                        args[1]);
                if (tp == null || !tp.isOnline()) {
                    sender.sendMessage(
                            "§c玩家不在线: "
                                    + args[1]);
                    return true;
                }
                forceGiveMenuSnowball(tp);
                tp.sendMessage(
                        "§a管理员已为您发放菜单雪球");
                sender.sendMessage(
                        "§a已发放雪球菜单给 "
                                + args[1]);
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

        return true;
    }
    /**
     * 供SDF1反射调用的CDK兑换
     * 返回: "success:金额:余额前:余额后"
     *       或 "fail:原因"
     */
    public String redeemBondForExternal(
            String playerName, String code) {
        if (cdkManager == null)
            return "fail:CDK未初始化";
        return cdkManager.redeem(code, playerName);
    }
    // 旧的（可能缺失或拼写错误）
    public SalesStatsManager getSalesStats() {
        return salesStats;
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
        p.sendMessage("§e/chat 开/关 §7- 开启/关闭聊天过滤");
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
                        "shopadd", "shopdel", "back", "radio", "back", "shop",
                        "update", "updateall"));
            } else {
                list.addAll(Arrays.asList(
                        "pw", "email", "sign",
                        "reset", "undo",
                        "ticket", "oa", "stop"));
            }
        }
        // 独立 /update 命令无子命令
        if (cmd.getName().equalsIgnoreCase("update")) {
            return list;
        }
        if (cmd.getName().equalsIgnoreCase("protect")
                || cmd.getName().equals("区域保护")) {

            String sub = args.length > 0 ? args[0].toLowerCase() : "";

            // ===== 第一层：子命令 =====
            if (args.length == 1) {
                List<String> opts = Arrays.asList(
                        "工具", "创建", "列表", "重载", "删除",
                        "add", "remove",
                        "additem", "removeitem",
                        "on", "off", "tempon",
                        "expand", "contraction",
                        "list", "listitem",
                        "addname", "removename", "listname",
                        "addwhite", "removewhite", "listwhite");
                return filterTab(opts, args[0]);
            }

            // ===== 第二层 =====
            if (args.length == 2) {
                // add/remove: 区域名 + global + 在线玩家
                if (sub.equals("add") || sub.equals("remove")) {
                    List<String> all = new ArrayList<>();
                    all.add("global");
                    all.addAll(areaProtection.getAreaNames());
                    for (Player pl : Bukkit.getOnlinePlayers())
                        all.add(pl.getName());
                    return filterTab(all, args[1]);
                }
                // additem/removeitem: 区域名 + global
                if (sub.equals("additem") || sub.equals("removeitem")) {
                    List<String> all = new ArrayList<>();
                    all.add("global");
                    all.addAll(areaProtection.getAreaNames());
                    return filterTab(all, args[1]);
                }
                // list/listitem: 区域名
                if (sub.equals("list") || sub.equals("listitem")) {
                    return filterTab(
                            new ArrayList<>(areaProtection.getAreaNames()),
                            args[1]);
                }
                // expand/contraction: 数字
                if (sub.equals("expand") || sub.equals("contraction")) {
                    return Arrays.asList("1", "3", "5", "10", "20", "50");
                }
                // 创建/删除: 区域名
                if (sub.equals("创建") || sub.equals("删除")) {
                    return filterTab(
                            new ArrayList<>(areaProtection.getAreaNames()),
                            args[1]);
                }
                // addname/removename/listname: 区域名
                if (sub.equals("addname") || sub.equals("removename")
                        || sub.equals("listname")) {
                    return filterTab(
                            new ArrayList<>(areaProtection.getAreaNames()),
                            args[1]);
                }
                // addwhite/removewhite/listwhite: 区域名
                if (sub.equals("addwhite") || sub.equals("removewhite")
                        || sub.equals("listwhite")) {
                    return filterTab(
                            new ArrayList<>(areaProtection.getAreaNames()),
                            args[1]);
                }
            }

            // ===== 第三层 =====
            if (args.length == 3) {
                // add/remove: 第三层是玩家名
                if (sub.equals("add") || sub.equals("remove")) {
                    List<String> players = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers())
                        players.add(pl.getName());
                    return filterTab(players, args[2]);
                }
                // additem/removeitem: 第三层是物品ID
                if (sub.equals("additem") || sub.equals("removeitem")) {
                    List<String> items = Arrays.asList(
                            "TNT", "BROWN_MUSHROOM", "RED_MUSHROOM",
                            "MUSHROOM_STEW", "POISONOUS_POTATO",
                            "SPIDER_EYE", "POTION", "SPLASH_POTION",
                            "OMINOUS_BOTTLE", "ENDER_PEARL",
                            "BOW", "CROSSBOW", "ARROW",
                            "FLINT_AND_STEEL");
                    return filterTab(items, args[2]);
                }
                // addname/removename: 第三层是生物名字（自由输入）
                if (sub.equals("addname") || sub.equals("removename")) {
                    return null;
                }
                // addwhite/removewhite: 第三层是玩家名
                if (sub.equals("addwhite") || sub.equals("removewhite")) {
                    List<String> players = new ArrayList<>();
                    for (Player pl : Bukkit.getOnlinePlayers())
                        players.add(pl.getName());
                    return filterTab(players, args[2]);
                }
            }

            return null;
        }


        if (cmd.getName().equalsIgnoreCase("chat")
                && args.length == 1
                && isAdmin(sender)) {
            list.addAll(Arrays.asList(
                    "reload", "add", "remove",
                    "addplayer", "takeplayer",
                    "unmute", "reset"));
        }
        if (cmd.getName().equalsIgnoreCase("cypay")) {
            return cypayCommand.onTabComplete(sender, cmd, label, args);
        }

        return list;
    }
}