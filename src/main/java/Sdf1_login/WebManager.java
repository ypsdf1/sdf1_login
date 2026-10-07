package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.scheduler.BukkitRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Web通信管理器 - 插件与PHP后端的桥梁
 *
 * 功能：
 * 1. Token生成和管理（一次性，10分钟有效期）
 * 2. 商城数据同步（推送shop/*.md到Web，从Web拉取商品）
 * 3. CDK验证（插件发CDK到Web验证，根据结果充值）
 * 4. 余额查询
 * 5. 注册账号同步到Web端
 * 6. 定时同步任务
 */
public class WebManager {

    private static final Logger log = LoggerFactory.getLogger(WebManager.class);
    private final Main plugin;
    private final ConfigManager config;

    // Token存储：token -> [playerName, purpose, createdAt]
    private final ConcurrentHashMap<String, String[]> tokenStore = new ConcurrentHashMap<>();

    // ★ 已处理的Web登录请求跟踪：reqId -> 处理时间戳（毫秒）
    // 避免PHP未正确更新状态时，Java反复验证同一个请求
    private final ConcurrentHashMap<String, Long> processedWebLoginRequests = new ConcurrentHashMap<>();
    private static final long PROCESSED_REQUEST_EXPIRE_MS = 60000; // 60秒后允许重新处理

    // ★ 本地Web登录验证状态：playerName -> 验证时间戳（毫秒）
    // Java验证成功后立即记录，玩家进游戏时直接检查，无需再轮询PHP
    private final ConcurrentHashMap<String, Long> verifiedWebLogins = new ConcurrentHashMap<>();
    private static final long VERIFIED_LOGIN_EXPIRE_MS = 300000; // 5分钟过期

    // ★ Java手动登录记录：playerName -> 登录时间戳（毫秒）
    // 玩家通过/l命令或autoLogin成功后记录，onQuit不清除，5分钟内重连可直接放行（检查点1）
    private final ConcurrentHashMap<String, Long> javaLoginRecords = new ConcurrentHashMap<>();
    private static final long JAVA_LOGIN_RECORD_EXPIRE_MS = 300000; // 5分钟过期

    // ★ Java登录记录对应的登录IP：playerName -> 该玩家本运行期上次登录时的IP（不过期，登录即覆盖）
    // 检查点1 IP风控基准：IP变了且已绑2FA → 不走5分钟直接放行，转「密码 + 2FA」双验证
    private final ConcurrentHashMap<String, String> javaLoginRecordIps = new ConcurrentHashMap<>();

    // ★ 合并定时器错峰调度（v19：4个定时器）
    private static final int TIMER_A = 0; // 注册登录 0~5秒
    private static final int TIMER_B = 1; // 交易 0~10秒
    private static final int TIMER_C = 2; // 其它 10~20秒
    private static final int TIMER_D = 3; // 领地数据同步 15~25秒（独立，防SQL锁）
    private static final int TIMER_E = 4; // 用户组续费轮询 20~30秒（独立，防SQL锁）
    private final long[] lastRunTimestamps = new long[5];
    private final Object scheduleLock = new Object();

    // ★ PHP锁库退避：检测到database is locked时暂停所有非关键定时器
    private volatile long phpBusyUntil = 0;  // 解除时间戳（毫秒）
    private static final long PHP_BUSY_BACKOFF_MS = 10000; // 退避10秒
    // ★ 全员下线暂停标志
    private volatile boolean timersBCPaused = false;  // 全员下线时暂停Timer B/C
    private volatile boolean timerAStopped = false;  // Timer A是否已停止（不应该停，但做兜底）

    // 默认配置
    private String webBaseUrl = "https://caoyuan.ypshidifu.cn/plugin";
    private boolean enabled = false;
    private boolean pollingStarted = false; // 合并定时器是否已启动（用于运行时重载启停）
    private int tokenExpireSeconds = 600; // 10分钟
    private int syncIntervalMinutes = 5;
    private String secretKey = "sdf1_web_comm_2026_ypshidifu";
    private int callbackPort = 9090; // PHP回调端口

    // ==================== 配置热重载（2026-10-04 用户要求）====================
    // ★ 需求原话：「启动时加载一次配置就不管了，如果返回密钥验证失败就再读一次配置的密钥文件。
    //   同时，开关状态也做到热重载。不再依赖 reload。reload 保留作为手动重载更新的一种方式。」
    //
    // 三条独立的生效通道，任意一条都能让配置生效，不再依赖 reload 命令：
    //  1) 热重载：5 个合并定时器每轮开头比对 插件设置.txt 的指纹（lastModified + length），
    //     变了就重新解析「启用开关 / 地址 / 密钥 / 三个数值项」，改完下一轮即生效；
    //  2) 密钥自愈：PHP 返回「密钥验证失败」时立刻重读配置文件里的密钥并重试该请求。
    //     运维在服务器上直接改 txt 就能救活，不用重启、不用 reload；
    //  3) isEnabled() 实时读文件，改开关下一毫秒生效。
    // 手动 reloadWebConfig() 保留，但不再是「生效的唯一途径」。
    private static final long HOT_RELOAD_CHECK_INTERVAL_MS = 3000;  // 文件检查节流（5 个定时器共用一个闸）
    private static final long SECRET_HEAL_COOLDOWN_MS = 30000;      // 密钥自愈冷却（防刷日志/防死循环）
    private volatile long lastHotReloadCheckAt = 0;
    private volatile long lastSecretHealAt = 0;
    private volatile long settingsFileStamp = -1L;   // 已加载配置的指纹（-1 = 强制重载）
    private volatile String lastLoadedSecret = "";   // 内存中当前生效的密钥（诊断用）
    private volatile String lastLoadedUrl = "";      // 内存中当前生效的地址（诊断用）
    private final Object configLock = new Object();

    /**
     * 检查配置文件是否变化，变了就热重载（启用开关 / 地址 / 密钥 / 数值项）。
     * 由 5 个定时器每轮开头调用；纯只读、绝不抛异常。
     *
     * ★ 为什么必须放在 enabled 守卫【之前】：
     *   开关关着时定时器走的是「自调度后 return」分支，若把热重载放在守卫后面，
     *   开关关着时就永远感知不到「运维把开关改回 true」→ 又得靠 reload。两处必须同时存在。
     *   文件指纹做闸：文件没动时连磁盘都不读；5 个定时器共用 3 秒节流，不会压 IO。
     */
    private void hotReloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastHotReloadCheckAt < HOT_RELOAD_CHECK_INTERVAL_MS) return;
        synchronized (configLock) {
            lastHotReloadCheckAt = now;
        }
        try {
            File file = new File(plugin.getDataFolder(), "插件设置.txt");
            if (!file.exists()) return;
            long stamp = file.lastModified() * 1000000L + file.length();
            if (stamp == settingsFileStamp) return;   // 文件没动 → 什么都不做

            String newUrl = getConfigValue("web通信-地址", webBaseUrl);
            String newSecret = getConfigValue("web通信-密钥", secretKey);
            boolean newEnabledFlag = Boolean.parseBoolean(getConfigValue("web通信-启用", "false"));
            String newInterval = getConfigValue("web通信-同步间隔分钟", String.valueOf(syncIntervalMinutes));
            String newTokenExpire = getConfigValue("web通信-Token有效期秒", String.valueOf(tokenExpireSeconds));
            String newPort = getConfigValue("web通信-回调端口", String.valueOf(callbackPort));

            boolean urlChanged = !newUrl.equals(webBaseUrl);
            boolean secretChanged = !newSecret.equals(secretKey);
            boolean enableChanged = newEnabledFlag != enabled;
            boolean othersChanged = false;
            try {
                othersChanged = Integer.parseInt(newInterval.trim()) != syncIntervalMinutes
                        || Integer.parseInt(newTokenExpire.trim()) != tokenExpireSeconds
                        || Integer.parseInt(newPort.trim()) != callbackPort;
            } catch (NumberFormatException ignore) {
                othersChanged = true;   // 写了非数字 → 走下面的兜底（保持旧值）
            }

            settingsFileStamp = stamp;

            if (!urlChanged && !secretChanged && !enableChanged && !othersChanged) {
                return;   // 文件动了但 Web 段没变（如改了别的模块）→ 安静走人
            }
            synchronized (configLock) {
                webBaseUrl = newUrl;
                secretKey = newSecret;
                enabled = newEnabledFlag;
                try { syncIntervalMinutes = Integer.parseInt(newInterval.trim()); } catch (Exception ignore) { }
                try { tokenExpireSeconds = Integer.parseInt(newTokenExpire.trim()); } catch (Exception ignore) { }
                try { callbackPort = Integer.parseInt(newPort.trim()); } catch (Exception ignore) { }
            }
            lastLoadedSecret = newSecret;
            lastLoadedUrl = newUrl;
            plugin.getLogger().info("[Web通信] ★ 配置已热重载（自动感知 插件设置.txt 变更）: 地址="
                    + webBaseUrl + " 启用=" + enabled + " 密钥长度=" + secretKey.length()
                    + (secretChanged ? " [密钥已更新]" : "")
                    + (urlChanged ? " [地址已更新]" : "")
                    + (enableChanged ? (enabled ? " [开关已开启]" : " [开关已关闭]") : ""));

            // ★ 2026-10-05 与 reloadWebConfig 对齐：开关打开或地址切换时，
            //   同样补一次全量同步（含流水对齐），避免「改配置生效了但数据没同步」。
            //   仅密钥变化不触发——密钥自愈路径本来就会重放当前请求，无需全量重推。
            if (enabled && (enableChanged || urlChanged)) {
                plugin.getLogger().info("[Web通信] ★ 配置热重载后排一次全量同步（含流水对齐），10 秒后开始");
                scheduleFirstFullSync("配置热重载");
            }
            // ★ 开关 false→true 时确保回调服务器在监听（开服绑定失败过则此处重试；已启动则幂等返回）
            if (enabled && enableChanged) {
                startCallbackServer();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 配置热重载失败（沿用旧配置）: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * PHP 返回「密钥验证失败」时调用：立刻重读配置文件里的密钥。
     *
     * @return true = 密钥已更新、值得重试；false = 文件里就是当前值（不是配置漂移），别再试
     */
    private boolean healSecretOnAuthFailure() {
        long now = System.currentTimeMillis();
        synchronized (configLock) {
            if (now - lastSecretHealAt < SECRET_HEAL_COOLDOWN_MS) {
                return false;   // 冷却中：不重复读盘、不刷日志
            }
            lastSecretHealAt = now;
        }
        String fileSecret;
        try {
            fileSecret = getConfigValue("web通信-密钥", null);
        } catch (Exception e) {
            return false;
        }
        String current = secretKey;
        if (fileSecret == null || fileSecret.isEmpty() || fileSecret.equals(current)) {
            return false;   // 文件里就是当前这个值 → 重读没用，问题在 PHP 侧或网络侧
        }
        synchronized (configLock) {
            secretKey = fileSecret;
            settingsFileStamp = -1L;   // 强制下一轮做完整比对，避免指纹与实际不一致
        }
        lastLoadedSecret = fileSecret;
        plugin.getLogger().warning("[Web通信] ★ 密钥验证失败 → 已自动重读 插件设置.txt 的 web通信-密钥: "
                + "旧(长度" + current.length() + ") → 新(长度" + fileSecret.length() + ")，本请求将自动重试");
        return true;
    }

    // ★★★ Web通信关闭闸（2026-10-04 热重载 bug 修复）★★★
    // 现象：开关已关闭后，日志里仍出现「已同步」「推送成功」——
    //   原因一：约 22 个同步方法（pushShopCatalog/syncServiceProviders/syncPermissions 等）
    //           压根没有 !enabled 守卫；
    //   原因二：有守卫的方法也拦不住「开关还是 true 时就已 submitDbTask 入队」的任务，
    //           db-worker 单线程排队轮到它时开关早已关闭。
    //
    // 修法：在【出网最后一公里】统一拦。doGet 是 46 处调用的唯一总出口，
    //   所以只要在这几个 HTTP 收口方法开头加闸，所有 GET/POST 路径一次覆盖，零遗漏。
    //   逻辑复用 isEnabled()（它现在实时读配置文件，改开关下一毫秒生效）。
    private static final String WEB_DISABLED_HINT =
            "[Web通信] 已关闭(web通信-启用=false)，请求已跳过（改回 true 会自动恢复，无需 reload）";
    // ★ 「已关闭」提示的节流时间戳：关闭状态下每 60 秒最多打一条，避免刷爆控制台
    private volatile long lastWebDisabledLogAt = 0;

    /**
     * 关闭状态下按节流打一条提示（60 秒一条）。
     * 用途：出网闸/队列闸都是静默 return，运维可能以为插件卡死；
     * 这里保证「偶尔能看到一句『因为开关关着所以没发』」，且不会刷屏。
     */
    private void noteWebDisabledOnce(String where) {
        long now = System.currentTimeMillis();
        if (now - lastWebDisabledLogAt < 60000L) return;
        lastWebDisabledLogAt = now;
        plugin.getLogger().info(WEB_DISABLED_HINT + "（最近触发点: " + where + "）");
    }

    /**
     * 判断响应体是否为「密钥验证失败」。
     * PHP 端 error('密钥验证失败', 403) → {"success":false,"message":"密钥验证失败"}
     * 另有 land_api.php / core.php 用「认证失败」文案，内嵌回调用 invalid_secret，一并覆盖。
     */
    private static boolean isSecretAuthFailure(String body) {
        if (body == null) return false;
        return body.contains("密钥验证失败")
                || body.contains("认证失败")
                || body.contains("invalid_secret");
    }

    /**
     * 所有出网请求的统一后处理：发现密钥失效就自动重读配置并重试一次。
     *
     * ★ 本次改动的核心价值：把「运维改 txt → 忘了 reload / 没重启 → 永久 403」
     *   变成「运维改 txt → 下一个请求自己发现并自愈」（密钥错配事故的直接对策）。
     *
     * @param call 出网执行体；自愈重试时会【再调用一次】，故必须是可重复执行的纯请求动作
     */
    private String withSecretAutoHeal(java.util.concurrent.Callable<String> call) {
        String resp = null;
        try {
            resp = call.call();
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 请求执行异常: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
        if (!isSecretAuthFailure(resp)) {
            return resp;   // 与密钥无关的响应，原样返回
        }
        plugin.getLogger().warning("[Web通信] PHP 返回「密钥验证失败」");
        if (!healSecretOnAuthFailure()) {
            return resp;   // 文件里的密钥没变 → 改不了，如实返回，不做无意义重试
        }
        try {
            String retry = call.call();
            if (retry != null && !isSecretAuthFailure(retry)) {
                plugin.getLogger().info("[Web通信] ★ 密钥自动修复生效，重试成功（本请求已同步）");
                return retry;
            }
            plugin.getLogger().warning("[Web通信] ★ 密钥已重读但重试仍失败，请核对 "
                    + "插件设置.txt 的 web通信-密钥 与 PHP config.php 的 SECRET_KEY 是否一字不差");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 密钥自愈重试异常: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return resp;
    }

    // ★ 统一HTTP客户端（绕过HttpsURLConnection的TLS时序问题，原生支持ALPN/SNI/HTTP2）
    private HttpClient cfHttpClient;
    
    // ★ HTTP降级专用HttpClient（不带SSL配置）
    private HttpClient plainHttpClient;

    // ★ 上次同步快照（用于检测变化，无变化静默）
    private String lastOnlinePlayersHash = "";
    private int lastOnlineCount = -1;
    private int lastLoggedInCount = -1;
    private String lastShopDataHash = "";
    private String lastServiceProviderHash = "";
    private String lastBondBalanceHash = "";
    private String lastLandDataHash = "";
    private String lastBansHash = "";
    private String lastAdminsHash = "";
    private String lastUserRegistrationHash = "";

    // 嵌入式HTTP服务器（接收PHP回调）
    private java.net.ServerSocket callbackServer;
    private Thread callbackThread;

    // 全员下线状态跟踪
    private boolean allPlayersOffline = false;
    private long lastOnlineCheckTime = 0;
    private boolean syncAfterAllOffline = false;
    private boolean lastSyncDone = false;  // 标记"全员下线最后同步"是否已执行，防止重复

    // ★ Web登录Token冷却：playerName -> 上次生成时间戳（毫秒）
    private final ConcurrentHashMap<String, Long> webloginTokenTimestamps = new ConcurrentHashMap<>();
    private static final long WEBLOGIN_TOKEN_COOLDOWN_MS = 10000; // 10秒冷却

    // ★ 数据库写入排队系统（防止并发SQLite操作导致database is locked）
    private static final int LOGIN_PRIORITY = 1;    // 登录相关操作最高优先级
    private static final int SHOP_PRIORITY = 5;     // 商店操作第二优先级
    private static final int NORMAL_PRIORITY = 10;   // 普通同步操作低优先级
    private final PriorityBlockingQueue<DbTask> dbTaskQueue = new PriorityBlockingQueue<>();
    private final AtomicInteger dbTaskIdGen = new AtomicInteger(0);
    private final AtomicBoolean dbWorkerRunning = new AtomicBoolean(false);
    private Thread dbWorkerThread;
    // 库存高频拉取去重标志
    private final AtomicBoolean stockFastPollTaskPending = new AtomicBoolean(false);

    // ★ Web请求专用线程池（HTTP操作不阻塞DB队列）
    private final java.util.concurrent.ExecutorService webExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
                Thread t = new Thread(r, "sdf1-web-worker");
                t.setDaemon(true);
                return t;
            });

    // ★ 10秒硬超时 + 慢请求旁路系统（2026-10-06）
    //   背景：DB队列是单线程（sdf1-db-worker），同步任务在队列线程里直接做HTTP，
    //   实测经 Cloudflare 的链路 25% 请求超过 10 秒 → 一次HTTP卡门 → 后面任务连环「等待过久」。
    //   规则（用户定）：
    //     ① 请求10秒内有返回 → 不动，照常处理；
    //     ② 10秒内没返回 → 把这个请求移交旁路线程继续等（最多HTTP_SIDE_WAIT_MS=30秒），
    //        DB工作者立刻放行去取下一个任务 —— 别堵门让其它人先走；
    //     ③ 每个DB任务另有10秒总预算：预算耗尽后任务内新的HTTP快速失败、sleep不再睡，
    //        单个任务占用大门的时间≈10秒封顶。
    //   注意：Cloudflare 代理保持原样不绕开 —— 它是源站SSL过期时的兜底（用户明确要求保留）。
    private static final long HTTP_HARD_TIMEOUT_MS = 10000;  // 门口硬等待上限：10秒
    private static final long HTTP_SIDE_WAIT_MS = 30000;     // 移交旁路后再等的上限：30秒
    private static final long DB_TASK_BUDGET_MS = 10000;     // 单个DB任务总预算：10秒
    private static final long QUEUE_WAIT_WARN_MS = 15000;    // 入队→执行超过15秒才告警（10秒是单任务硬预算）
    // ★ 2026-10-06 快速握手 + 超时降噪
    //   实测 10 次握手：TCP 连接稳定 0.2 秒，TLS 握手却约 20% 概率挂到 20 秒以上才断；
    //   JDK 的 connectTimeout 覆盖 TCP+TLS 握手（用「收下连接但不回握手」的服务端实测验证过），
    //   所以把 10 秒压到 4 秒 → 挂起的握手 4 秒判死 → 调用方立刻换连接重试 → 10 秒硬预算内拿到结果。
    private static final int HTTP_CONNECT_TIMEOUT_S = 4;     // 连接（含TLS握手）超时秒数
    private static final int GET_REQUEST_TIMEOUT_S = 15;     // GET 响应超时（原30秒：链路挂起时白等30秒还刷屏）
    private static final int CONNECT_RETRY_MAX = 1;          // 连接阶段失败后的立即重试次数

    // 旁路线程池：超时的HTTP请求挪到这里继续等，不占DB队列的门（最多8个慢请求同时挂着）
    private final java.util.concurrent.ExecutorService httpSideExecutor =
            new java.util.concurrent.ThreadPoolExecutor(0, 8, 30L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "sdf1-http-side");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    // 当前线程是否是DB队列工作者（只有它需要10秒硬超时放行）
    private static final ThreadLocal<Boolean> onDbWorkerThread = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // 当前DB任务的截止时间戳（10秒预算），仅db-worker线程有值
    private static final ThreadLocal<Long> dbTaskDeadline = new ThreadLocal<>();
    // 自上次清理以来本线程是否撞上过HTTP硬超时（对账据此判断「PHP没答复=链路慢」）
    private static final ThreadLocal<Boolean> hardTimeoutHit = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 10秒硬超时异常：门口等待超限，请求已移交旁路线程，调用方按“无响应”处理即可 */
    private static class HttpHardTimeoutException extends Exception {
        HttpHardTimeoutException(String msg) { super(msg); }
    }

    /**
     * ★ 统一HTTP发送入口（10秒硬超时）：
     * - 非DB队列线程（webExecutor轮询、Bukkit异步等）：直接发，请求自身超时兜底，行为与旧版一致；
     * - DB队列线程：真正的send交给旁线程池执行，本线程最多等10秒（且不超过任务预算剩余）；
     *   10秒内返回 → 原样返回（不动）；超时 → 抛HttpHardTimeoutException立即放行队列，
     *   请求本身留在旁线程继续等（去旁边等），排在后面的任务先走。
     */
    private HttpResponse<String> sendHard(String tag, HttpRequest req, java.net.http.HttpClient client) throws Exception {
        if (!Boolean.TRUE.equals(onDbWorkerThread.get())) {
            return client.send(req, HttpResponse.BodyHandlers.ofString());
        }
        Long deadline = dbTaskDeadline.get();
        long now = System.currentTimeMillis();
        long waitMs = HTTP_HARD_TIMEOUT_MS;
        if (deadline != null) {
            long remainMs = deadline - now;
            if (remainMs <= 0) {
                hardTimeoutHit.set(Boolean.TRUE);
                throw new HttpHardTimeoutException(tag + " " + maskSecret(req.uri().toString()) + " 任务10秒预算已耗尽，快速让位");
            }
            waitMs = Math.min(waitMs, remainMs);
        }
        final String desc = tag + " " + maskSecret(req.uri().toString());
        java.util.concurrent.Future<HttpResponse<String>> side;
        try {
            side = httpSideExecutor.submit(() -> client.send(req, HttpResponse.BodyHandlers.ofString()));
        } catch (java.util.concurrent.RejectedExecutionException re) {
            hardTimeoutHit.set(Boolean.TRUE);
            throw new HttpHardTimeoutException(desc + " 旁路线程池已满(8)，放弃本次");
        }
        try {
            return side.get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            warnSlow("HTTP硬超时", "[HTTP硬超时] " + desc + " " + (waitMs / 1000)
                    + "秒未返回 → 移交旁路线程继续等待，放行DB队列");
            hardTimeoutHit.set(Boolean.TRUE);
            throw new HttpHardTimeoutException(desc + " " + (waitMs / 1000) + "秒硬超时，已移交旁路线程");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ee;
        } catch (InterruptedException ie) {
            side.cancel(true); // 插件停用中断时，顺手掐掉旁路请求
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    /** URL里的secret打码（日志防泄漏） */
    private static String maskSecret(String url) {
        String s = url.replaceAll("secret=[^&]*", "secret=***");
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    // ==================== 超时日志节流 + 对账统计（2026-10-06） ====================
    //   背景：慢链路下一次请求会级联打出 3~5 条 WARN（门口超时 → 调用方让位 → 任务超预算 →
    //   下一个请求又让位），实测 74 分钟刷了 400+ 条，把真正有用的日志淹没了。
    //   同一 key 在窗口内只放行 1 条，放行那条上追加「另有 N 条同类已省略」—— 信息不丢，控制台不炸。
    private static final long LOG_THROTTLE_MS = 120000L;      // 一般异常：2 分钟窗口
    private static final long LOG_THROTTLE_SLOW_MS = 300000L; // 高频级联噪声：5 分钟窗口
    private final Object logThrottleLock = new Object();
    private final java.util.Map<String, Long> logThrottleLast = new java.util.HashMap<>();
    private final java.util.Map<String, Long> logThrottleHidden = new java.util.HashMap<>();

    private void warnThrottled(String key, String msg) { warnThrottled(key, msg, LOG_THROTTLE_MS); }

    /** 节流 WARN（5 分钟窗口）：给高频超时噪声用 */
    private void warnSlow(String key, String msg) { warnThrottled(key, msg, LOG_THROTTLE_SLOW_MS); }

    /** 节流 WARN：同 key 在 windowMs 内只打 1 条，被吞掉的条数附在下一条后面 */
    private void warnThrottled(String key, String msg, long windowMs) {
        long now = System.currentTimeMillis();
        String tail = "";
        synchronized (logThrottleLock) {
            Long prev = logThrottleLast.get(key);
            if (prev != null && now - prev < windowMs) {
                Long h = logThrottleHidden.get(key);
                logThrottleHidden.put(key, h == null ? 1L : h + 1L);
                return;
            }
            if (logThrottleLast.size() > 200) { logThrottleLast.clear(); logThrottleHidden.clear(); }
            logThrottleLast.put(key, now);
            Long h = logThrottleHidden.remove(key);
            if (h != null && h > 0) tail = "（另有 " + h + " 条同类已省略）";
        }
        plugin.getLogger().warning(msg + tail);
    }

    /** 节流 INFO：用于「预期中的让位/重试」这类本来就不算故障的记录 */
    private void infoThrottled(String key, String msg) {
        long now = System.currentTimeMillis();
        synchronized (logThrottleLock) {
            Long prev = logThrottleLast.get(key);
            if (prev != null && now - prev < LOG_THROTTLE_SLOW_MS) return;
            logThrottleLast.put(key, now);
        }
        plugin.getLogger().info(msg);
    }

    /**
     * 是否「连接建立阶段」就失败了（TLS 握手挂起 / 连接被拒 / 连接被重置）。
     * 这类失败意味着请求【还没发到 PHP】，因此对 POST 重发也安全；
     * 与之相对，请求超时（HttpTimeoutException 非 ConnectTimeout）说明 body 已经发出去，
     * POST 重发可能重复执行 —— 一律不自动重试。
     */
    private static boolean isConnectStageFailure(Throwable e) {
        return e instanceof java.net.http.HttpConnectTimeoutException
                || e instanceof java.net.ConnectException;
    }

    // —— 对账结果统计：5 分钟一条汇总，替代「每轮一条对账 WARN」 ——
    private final java.util.concurrent.atomic.AtomicInteger alignOkCount = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger alignNoRespCount = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger alignRejectCount = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long alignStatFlushAt = 0;

    /** ok=拿到有效答复；noresp=HTTP 层没答复（本轮不推不收）；reject=PHP 明确拒绝对账 */
    private void countAlign(String kind) {
        if ("ok".equals(kind)) alignOkCount.incrementAndGet();
        else if ("reject".equals(kind)) alignRejectCount.incrementAndGet();
        else alignNoRespCount.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now < alignStatFlushAt) return;
        alignStatFlushAt = now + 300000L;   // 5 分钟一报
        int ok = alignOkCount.getAndSet(0);
        int no = alignNoRespCount.getAndSet(0);
        int rej = alignRejectCount.getAndSet(0);
        if (ok + no + rej == 0) return;
        plugin.getLogger().info("[对账] 近5分钟：成功 " + ok + " 次，无答复 " + no + " 次，PHP拒绝 " + rej + " 次");
    }

    /**
     * ★ 预算感知sleep：DB任务10秒预算耗尽后立即返回（不再睡在门口堵队列）；
     *   非DB队列线程没有预算 → 与旧 Thread.sleep 行为完全一致。
     */
    private static void budgetSleep(long ms) {
        Long deadline = dbTaskDeadline.get();
        if (deadline != null) {
            long remainMs = deadline - System.currentTimeMillis();
            if (remainMs <= 0) return;
            ms = Math.min(ms, remainMs);
        }
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /**
     * ★ 等 DB 队列排空（最多 maxWaitMs）再提交下一批任务。
     *   队列深度始终 ≤1 → 入队等待 ≈ 上一个任务的剩余时间（≤单任务10秒预算），
     *   不再出现十几个任务叠在一起的连环「等待过久」。
     *   队列本来就是空的 → 立即返回（比旧的固定 sleep 只会更快，不会更慢）。
     */
    private void awaitDbQueueIdle(long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (System.currentTimeMillis() < deadline) {
            if (dbTaskQueue.isEmpty()) return;
            try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
        }
    }

    // ★ SSL断路器：连续失败超过阈值后暂停轮询，避免DB队列积压
    private static final int SSL_CIRCUIT_THRESHOLD = 5;  // 连续失败5次触发断路
    private static final long SSL_CIRCUIT_BASE_COOLDOWN_MS = 60000; // 断路基础冷却60秒
    private static final long SSL_CIRCUIT_MAX_COOLDOWN_MS = 300000; // 断路最大冷却5分钟
    // ★ SSL降级：连续失败3次后降级到HTTP
    private static final int SSL_DOWNGRADE_THRESHOLD = 3;  // 连续失败3次触发降级
    private volatile boolean sslDowngraded = false;  // SSL降级标志
    private volatile boolean initialSyncComplete = false;  // 首次全量同步完成标志
    public volatile boolean allowLoginPolling = false;  // 允许登录轮询（全量同步完成 OR 玩家在线）
    private volatile int sslConsecutiveFailures = 0;
    private volatile long sslCircuitOpenUntil = 0;
    private volatile int sslCircuitOpenCount = 0;  // 连续断路次数（用于指数退避）

    public WebManager(Main plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig2();
        initSSL();
        initPlainHttpClient();  // 初始化HTTP降级客户端
        loadConfig();
        startDbWorker();
    }

    // ==================== 数据库写入排队系统 ====================

    /**
     * 数据库任务：带优先级的Runnable包装
     */
    private class DbTask implements Runnable, Comparable<DbTask> {
        final int id;
        final int priority;
        final String name;
        final Runnable action;
        final long createdAt;

        DbTask(int priority, String name, Runnable action) {
            this.id = dbTaskIdGen.incrementAndGet();
            this.priority = priority;
            this.name = name;
            this.action = action;
            this.createdAt = System.currentTimeMillis();
        }

        @Override
        public void run() {
            action.run();
        }

        @Override
        public int compareTo(DbTask other) {
            // 数字越小优先级越高
            int cmp = Integer.compare(this.priority, other.priority);
            if (cmp != 0) return cmp;
            // 同优先级按FIFO
            return Integer.compare(this.id, other.id);
        }

        @Override
        public String toString() {
            return "[DbTask#" + id + " " + name + " p=" + priority + "]";
        }
    }

    /**
     * 启动单线程数据库工作者
     */
    private void startDbWorker() {
        dbWorkerRunning.set(true);
        dbWorkerThread = new Thread(() -> {
            // ★ 必须在 worker 线程【体内】设置：ThreadLocal 不继承，写在 startDbWorker 的
            //   调用线程（构造 WebManager 的主线程）上等于没设 → 硬超时/旁路放行对 DB 队列
            //   线程完全失效，任务照旧按原生超时（15/30秒）堵门。
            //   2026-10-06 实测根因：syncUserRegistrations 跑到 16.7 秒。
            onDbWorkerThread.set(Boolean.TRUE);
            while (dbWorkerRunning.get()) {
                try {
                    DbTask task = dbTaskQueue.take();
                    long waitMs = System.currentTimeMillis() - task.createdAt;
                    // 单任务硬预算10秒：上一个任务吃满预算时，等待到10秒出头属正常；
                    // 超过15秒才说明队列真积压了≥2层 → 阈值10→15秒，别拿正常等待刷告警
                    if (waitMs > QUEUE_WAIT_WARN_MS) {
                        plugin.getLogger().warning("[DB队列] 等待过久: " + task.name + " 等待=" + waitMs
                                + "ms（队列积压≥2层；单任务硬预算" + (DB_TASK_BUDGET_MS / 1000) + "秒）");
                    }
                    // ★ Web通信关闭闸（执行时点复查）：任务可能是在「开关还是 true 时」入队的，
                    //   排队期间运维把 web通信-启用 改成 false，轮到执行时必须再拦一次。
                    //   这里【静默跳过】而不打 WARN —— 关闭开关后每轮几十个任务全打日志会刷爆控制台；
                    //   真正的开关变化已由 hotReloadIfChanged 打了一行「[开关已关闭]」，信息不会丢。
                    if (!isEnabled()) {
                        continue;
                    }
                    // ★ 10秒硬预算：给任务10秒执行预算，预算耗尽后任务内新的HTTP快速失败、
                    //   sleep不再睡 —— 单个任务占用大门≈10秒封顶，别堵门让其它人先走
                    long execStart = System.currentTimeMillis();
                    dbTaskDeadline.set(execStart + DB_TASK_BUDGET_MS);
                    hardTimeoutHit.set(Boolean.FALSE);
                    try {
                        task.run();
                    } finally {
                        dbTaskDeadline.remove();
                        long execMs = System.currentTimeMillis() - execStart;
                        if (execMs > DB_TASK_BUDGET_MS) {
                            warnSlow("DB预算超时", "[DB队列] 任务执行超过10秒硬预算: "
                                    + task.name + " 耗时=" + execMs + "ms（HTTP已按预算让位）");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    plugin.getLogger().warning("[DB队列] 任务异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                }
            }
        }, "sdf1-db-worker");
        dbWorkerThread.setDaemon(true);
        dbWorkerThread.start();
    }

    /**
     * 提交高优先级（登录相关）数据库写入任务
     */
    private void submitDbTask(String name, Runnable action) {
        submitDbTask(LOGIN_PRIORITY, name, action);
    }

    /**
     * 提交普通优先级数据库写入任务
     */
    private void submitNormalDbTask(String name, Runnable action) {
        submitDbTask(NORMAL_PRIORITY, name, action);
    }

    /**
     * 提交普通优先级数据库写入任务（支持绕过SSL断路器）
     */
    private void submitNormalDbTask(String name, Runnable action, boolean bypassSslCheck) {
        submitDbTask(NORMAL_PRIORITY, name, action, bypassSslCheck);
    }

    /**
     * 提交登录优先级数据库写入任务（支持绕过SSL断路器）
     */
    private void submitDbTask(String name, Runnable action, boolean bypassSslCheck) {
        submitDbTask(LOGIN_PRIORITY, name, action, bypassSslCheck);
    }

    /**
     * 提交商店相关数据库写入任务（第二优先级）
     */
    private void submitShopDbTask(String name, Runnable action) {
        submitDbTask(SHOP_PRIORITY, name, action);
    }

    /**
     * 提交指定优先级的数据库写入任务
     * ★ 限制同种类任务最多3个（防止队列积压）
     */
    private void submitDbTask(int priority, String name, Runnable action) {
        submitDbTask(priority, name, action, false);
    }

    /**
     * 提交任务（支持bypassSslCheck：关键任务如syncOnlinePlayers不应被SSL断路器阻断）
     */
    private void submitDbTask(int priority, String name, Runnable action, boolean bypassSslCheck) {
        // ★ Web通信关闭闸（入队前）：开关关闭时不再往队列里塞任务，避免队列堆积。
        //   注意不能只靠这一处 —— 已入队的存量任务由 startDbWorker 执行时的复查兜底。
        if (!isEnabled()) {
            noteWebDisabledOnce(name);
            return;
        }
        // ★ SSL断路器：断路期间跳过所有HTTP相关任务（除非指定绕过）
        if (!bypassSslCheck && isCircuitOpen()) {
            plugin.getLogger().warning("[DB队列] SSL断路器开启，跳过任务: " + name);
            return;
        }

        // ★ 同种类任务去重：提取任务类型前缀（如 "周期-syncOnlinePlayers" → "syncOnlinePlayers"）
        String taskType = name.contains("-") ? name.substring(name.indexOf("-") + 1) : name;

        // ★ 统计同类型任务数量
        int sameTypeCount = 0;
        for (DbTask t : dbTaskQueue) {
            if (t != null) {
                String tType = t.name.contains("-") ? t.name.substring(t.name.indexOf("-") + 1) : t.name;
                if (tType.equals(taskType)) {
                    sameTypeCount++;
                }
            }
        }

        // ★ 同类型任务最多3个，超过则丢弃低优先级的（关键任务不丢弃）
        if (sameTypeCount >= 3 && !bypassSslCheck) {
            plugin.getLogger().warning("[DB队列] 同类型任务已满(3): " + taskType + "，丢弃: " + name);
            return;
        }

        DbTask task = new DbTask(priority, name, action);
        dbTaskQueue.offer(task);
        if (dbTaskQueue.size() > 50) {
            plugin.getLogger().warning("[DB队列] 队列积压: " + dbTaskQueue.size() + " 任务等待");
            discardOldestIfOverflow();
        }
    }

    /**
     * 关闭数据库工作线程
     */
    private void stopDbWorker() {
        dbWorkerRunning.set(false);
        if (dbWorkerThread != null) {
            dbWorkerThread.interrupt();
        }
    }

    /**
     * ★ 清空DB队列中所有未回应的web同步任务
     *   （清空队列/cleartake 命令调用）
     *
     *   v2：以前只清 Java 内存队列，PHP 侧 web_login_requests 表里的 pending 记录
     *       原封不动 → 下一轮轮询又原样取回来，看起来"打了几十次都没清掉"。
     *       现在增加两件事：
     *         ① 复位本地"已处理"标记与TimerA在途计数（避免清完后仍被跳过/卡死）
     *         ② 异步调用 PHP clear_pending_web_logins，把服务端 pending 一并清理
     */
    public String clearQueue() {
        int count = 0;
        Iterator<DbTask> it = dbTaskQueue.iterator();
        while (it.hasNext()) {
            DbTask t = it.next();
            // 只保留登录/商店等关键任务，丢弃所有同步类web任务
            if (t.name.contains("TimerA") || t.name.contains("首次")
                    || t.name.contains("周期") || t.name.contains("sync")
                    || t.name.contains("poll") || t.name.contains("push")
                    || t.name.contains("pull") || t.name.contains("cdk")) {
                it.remove();
                count++;
            }
        }
        // ★ 复位轮询在途标记：队列里的任务被丢弃后，finally不会执行，必须手工复位
        processedWebLoginRequests.clear();
        plugin.getLogger().info("[DB队列] 清空队列: 丢弃 " + count + " 个未回应web任务，联动清理PHP待处理请求...");

        // ★ 联动清理 PHP 侧 pending（异步，不阻塞命令线程）
        purgePhpPendingWebLogins(count);
        return "丢弃 " + count + " 个未回应web任务 + 联动清理PHP待处理登录请求";
    }

    /**
     * ★ 联动清理 PHP 的 web_login_requests pending 记录
     * 必须使用 lambda 而非匿名内部类：jar 热替换后匿名内部类会抛
     * NoClassDefFoundError（WebManager$N 找不到），导致回写/清理永远失败。
     */
    private void purgePhpPendingWebLogins(final int localDropped) {
        if (!enabled) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String urlStr = webBaseUrl + "/api/sync.php?action=clear_pending_web_logins&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                String resp = doGet(urlStr);
                plugin.getLogger().info("[DB队列] 清空队列联动: 本地丢弃=" + localDropped
                        + " PHP响应=" + (resp == null ? "null" : resp.substring(0, Math.min(200, resp.length()))));
            } catch (Throwable t) {
                plugin.getLogger().warning("[DB队列] 联动清理PHP待处理登录请求失败: " + t);
            }
        });
    }

    /**
     * ★ 队列积压时丢弃最早的任务（优先丢弃同步类低优先级任务）
     */
    public void discardOldestIfOverflow() {
        if (dbTaskQueue.size() <= 50) return;
        int before = dbTaskQueue.size();
        // 收集所有普通同步任务（priority >= NORMAL_PRIORITY），按创建时间排序
        List<DbTask> syncTasks = new ArrayList<>();
        for (DbTask t : dbTaskQueue) {
            if (t != null && t.priority >= NORMAL_PRIORITY) {
                syncTasks.add(t);
            }
        }
        syncTasks.sort(Comparator.comparingLong(t -> t.createdAt));
        // 丢弃最早的一半同步任务（至少丢弃1个）
        int toDrop = Math.max(1, syncTasks.size() / 2);
        for (int i = 0; i < toDrop && i < syncTasks.size(); i++) {
            dbTaskQueue.remove(syncTasks.get(i));
        }
        int after = dbTaskQueue.size();
        plugin.getLogger().warning("[DB队列] 队列积压(" + before + ")，自动丢弃" + (before - after) + "个最早的同步请求");
    }

    // ==================== SSL断路器 + 降级HTTP ====================

    /**
     * 检查SSL断路器是否开启（跳过轮询请求）
     */
    private boolean isCircuitOpen() {
        if (sslCircuitOpenUntil == 0) return false;
        if (System.currentTimeMillis() < sslCircuitOpenUntil) return true;
        // 冷却期结束，允许重试
        sslCircuitOpenUntil = 0;
        sslConsecutiveFailures = 0;
        sslDowngraded = false;  // 恢复降级标志
        plugin.getLogger().info("[Web通信] SSL断路器冷却结束（第" + (sslCircuitOpenCount + 1) + "轮），恢复轮询");
        // ★ 重建HttpClient，清除可能损坏的连接状态
        rebuildHttpClient();
        return false;
    }

    /**
     * 判断是否需要降级到HTTP
     * 降级条件：连续SSL失败>=3次，且尚未降级
     * ⚠️ 降级后HTTP请求如果返回301跳转，需要禁用followRedirects
     */
    private boolean shouldDowngradeToHttp(String urlStr) {
        if (sslConsecutiveFailures >= SSL_DOWNGRADE_THRESHOLD && !sslDowngraded) {
            sslDowngraded = true;
            // 将HTTPS URL转为HTTP URL
            String httpUrl = urlStr.replaceFirst("^https:", "http:");
            plugin.getLogger().warning("[Web通信] SSL连续失败" + sslConsecutiveFailures + "次，降级到HTTP: " + httpUrl);
            return true;
        }
        return false;
    }

    /**
     * ★ 安全触发断路器（防止并发重复触发）
     * 已开路时只更新冷却时间（取最大值），不增加sslCircuitOpenCount
     */
    private void triggerCircuitBreaker(boolean httpAlsoFailed) {
        if (isCircuitOpen()) {
            // 已开路，但如果HTTP也失败说明CDN完全不可达，延长冷却
            if (httpAlsoFailed) {
                long extra = SSL_CIRCUIT_BASE_COOLDOWN_MS; // 额外加一轮基础冷却
                long newUntil = System.currentTimeMillis() + extra;
                if (newUntil > sslCircuitOpenUntil) {
                    sslCircuitOpenUntil = newUntil;
                    plugin.getLogger().warning("[Web通信] HTTP降级也失败，延长断路冷却至" + (extra/1000) + "秒");
                }
            }
            return;
        }
        // 首次触发
        long cooldown = Math.min(
            SSL_CIRCUIT_BASE_COOLDOWN_MS * (1L << sslCircuitOpenCount),
            SSL_CIRCUIT_MAX_COOLDOWN_MS);
        sslCircuitOpenUntil = System.currentTimeMillis() + cooldown;
        sslCircuitOpenCount++;
        plugin.getLogger().warning("[Web通信] SSL断路器触发: 连续失败" + sslConsecutiveFailures + "次，暂停轮询" + (cooldown/1000) + "秒（第" + sslCircuitOpenCount + "轮退避）");
    }

    /**
     * 降级后的HTTP GET请求（使用不带SSL的HttpClient）
     */
    private String doGetHttpFallback(String urlStr) {
        // ★ Web通信关闭闸（降级路径同样不能出网）
        if (!isEnabled()) return null;
        if (plainHttpClient == null) {
            plugin.getLogger().warning("[Web通信] HTTP降级客户端未初始化");
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sdf1-WebManager/2.8-http")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHard("GET-HTTP降级", req, plainHttpClient);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                // HTTP成功 → 重置SSL计数，恢复正常
                sslConsecutiveFailures = 0;
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;
                plugin.getLogger().info("[Web通信] HTTP降级成功，恢复正常HTTPS");
                String body = resp.body();
                detectPhpBusy(body);  // ★ 锁库检测
                return body;
            }
            plugin.getLogger().warning("[Web通信] HTTP降级失败 HTTP " + resp.statusCode() + ": " + urlStr);
            triggerCircuitBreaker(true);
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // ★ 10秒硬超时是「让位放行」不是SSL/网络故障：不计入断路器与降级，按无响应处理
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            plugin.getLogger().warning("[Web通信] HTTP降级异常: " + e.getMessage());
            triggerCircuitBreaker(true);
            return null;
        }
    }

    /**
     * 提交Web任务到专用线程池（HTTP操作不阻塞DB队列）
     * HTTP完成后再把DB写入提交到DB队列
     */
    private void submitWebTask(String name, Runnable action) {
        if (isCircuitOpen()) return;
        // ★ Web通信关闭闸（入队前拦一道，webExecutor 队列同样可能有存量任务）
        if (!isEnabled()) return;
        webExecutor.submit(() -> {
            try {
                action.run();
            } catch (Exception e) {
                plugin.getLogger().warning("[Web线程] " + name + " 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        });
    }

    // ==================== 静态内部类 ====================

    // ★ 静态具名内部类替代匿名X509TrustManager（解决Bukkit类加载器NoClassDefFoundError）
    private static class TrustAllX509Manager implements X509TrustManager {
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
    }

    // ==================== 工具方法 ====================

    /**
     * SSL初始化 - Cloudflare兼容
     * ★ 彻底方案：用 java.net.http.HttpClient 替代 HttpsURLConnection
     * HttpsURLConnection 内部调用 factory.createSocket()(无参版本)绕过了自定义工厂的配置
     * HttpClient 原生处理 ALPN/SNI/TLS协商，完全不走HttpsURLConnection的工厂机制
     *
     * ★ 信任策略：有证→通过，没证→拦截（信任所有证书，包括自签名）
     * ★ CF兼容：强制TLS 1.2 + 指定密码套件 + 自动重建连接
     */
    private void initSSL() {
        final TrustManager[] trustAllCerts = new TrustManager[]{ new TrustAllX509Manager() };

        try {
            // ★ 使用TLS 1.2（CF兼容性最好，TLS 1.3与CF边缘偶发不兼容）
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, trustAllCerts, new SecureRandom());

            // ★ 不要硬编码密码套件！JVM可能不支持某些套件导致IllegalArgumentException
            // 只固定协议版本为TLSv1.2，密码套件让JVM自动选择
            javax.net.ssl.SSLParameters sslParams = sc.getDefaultSSLParameters();
            sslParams.setProtocols(new String[]{"TLSv1.2"});

            // ★ java.net.http.HttpClient + HTTP/1.1
            // 不用HTTP/2：CF代理对HTTP/2的某些请求模式可能导致PHP返回500
            // HTTP/1.1更稳定，且curl(也是HTTP/1.1)测试正常
            cfHttpClient = HttpClient.newBuilder()
                    .sslContext(sc)
                    .sslParameters(sslParams)
                    .connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_S))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            plugin.getLogger().info("[Web通信] SSL已初始化(TLSv1.2, 信任所有证书, 自动密码套件, HTTP/1.1)");
        } catch (Exception e) {
            plugin.getLogger().severe("[Web通信] SSL完全初始化失败: " + e.getMessage());
        }
    }

    /**
     * ★ 重建HttpClient（SSL断路器冷却后调用，清除可能损坏的连接状态）
     */
    private void rebuildHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{ new TrustAllX509Manager() };
            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, trustAllCerts, new SecureRandom());
            javax.net.ssl.SSLParameters sslParams = sc.getDefaultSSLParameters();
            sslParams.setProtocols(new String[]{"TLSv1.2"});
            cfHttpClient = HttpClient.newBuilder()
                    .sslContext(sc)
                    .sslParameters(sslParams)
                    .connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_S))
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            plugin.getLogger().info("[Web通信] HttpClient已重建");
        } catch (Exception e) {
            plugin.getLogger().severe("[Web通信] HttpClient重建失败: " + e.getMessage());
        }
    }

    /**
     * ★ 初始化纯HTTP客户端（不带SSL配置，用于降级）
     */
    private void initPlainHttpClient() {
        try {
            plainHttpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(HTTP_CONNECT_TIMEOUT_S))
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();
            plugin.getLogger().info("[Web通信] HTTP降级客户端已初始化");
        } catch (Exception e) {
            plugin.getLogger().severe("[Web通信] HTTP降级客户端初始化失败: " + e.getMessage());
        }
    }

    // ==================== 统一HTTP请求方法（java.net.http.HttpClient） ====================

    /**
     * GET请求 - 返回响应体，失败返回null
     * ★ 包含SSL降级逻辑：连续失败3次后降级到HTTP
     */
    private String doGet(String urlStr) {
        // ★ Web通信关闭闸：开关关闭后一律不出网（doGet 是全部 GET 路径的唯一出口）
        if (!isEnabled()) {
            return null;
        }
        if (cfHttpClient == null) {
            plugin.getLogger().warning("[Web通信] HttpClient未初始化");
            return null;
        }
        // ★ SSL断路器：断路期间直接返回，避免无意义请求
        if (isCircuitOpen()) {
            return null;
        }
        for (int attempt = 1; attempt <= 1 + CONNECT_RETRY_MAX; attempt++) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(GET_REQUEST_TIMEOUT_S))  // ★ 2026-10-06 30→15秒：链路挂起时白等30秒，既慢又刷屏
                    .header("User-Agent", "Sdf1-WebManager/2.9")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHard("GET", req, cfHttpClient);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sslConsecutiveFailures = 0; // 成功 → 重置断路器
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;  // 恢复正常HTTPS
                String body = resp.body();
                detectPhpBusy(body);  // ★ 锁库检测
                return withSecretAutoHeal(() -> body);   // ★ 密钥自愈（PHP 可能 200 也带失败信息）
            }
            // ★ 4xx/5xx也返回body（PHP的error()返回有用的JSON错误信息）
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] GET HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            if (isSecretAuthFailure(body)) {
                // ★ 密钥错配是唯一值得立即自愈 + 重试的 4xx（其余 4xx 重试无意义）
                return withSecretAutoHeal(() -> doGet(urlStr));
            }
            // 返回body让调用方可以解析PHP错误信息
            if (body != null && !body.isEmpty()) {
                return body;
            }
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // ★ 硬超时是「让位放行」不是网络故障：不计入断路器；sendHard 已打过节流日志，这里不重复刷屏
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            // ★ 换连接重试一次（GET 幂等，重发绝对安全）：
            //   ① 连接阶段失败（TLS 握手挂起/连接被拒）—— 实测经 CF 握手约 20% 概率挂到 20 秒以上，
            //      4 秒判死再试一次即可恢复；
            //   ② 请求超时 —— 多半是复用了 CF 已经关掉的 keep-alive 连接（FIN 丢了就一直干等），
            //      换一条新连接立刻就能通。两种情况都不计入 SSL 断路器/降级。
            //   两次都失败才计数上报；最坏耗时 2×15 秒 = 原来单次 30 秒，不更慢。
            if (attempt < 1 + CONNECT_RETRY_MAX
                    && (e instanceof java.net.http.HttpTimeoutException || e instanceof java.net.ConnectException)) {
                infoThrottled("GET重试", "[Web通信] GET 第" + attempt + "次无响应("
                        + e.getClass().getSimpleName() + ")，换连接重试");
                continue;
            }
            // ★ SSL断路器：跟踪连续失败（所有连接异常都计数）
            sslConsecutiveFailures++;
            // ★ 降级到HTTP：连续失败3次
            if (sslConsecutiveFailures >= SSL_DOWNGRADE_THRESHOLD && shouldDowngradeToHttp(urlStr)) {
                String httpUrl = urlStr.replaceFirst("^https:", "http:");
                return doGetHttpFallback(httpUrl);
            }
            if (sslConsecutiveFailures >= SSL_CIRCUIT_THRESHOLD) {
                triggerCircuitBreaker(false);
            }
            warnSlow("GET异常", "[Web通信] GET异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
        }
        return null;
    }

    /**
     * GET请求 - 返回状态码
     */
    private int doGetStatus(String urlStr) {
        // ★ Web通信关闭闸
        if (!isEnabled()) return -1;
        if (cfHttpClient == null) return -1;
        // ★ SSL断路器：断路期间直接返回，避免无意义请求
        if (isCircuitOpen()) {
            return -1;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sdf1-WebManager/2.8")
                    .GET()
                    .build();
            HttpResponse<String> resp = sendHard("GET状态", req, cfHttpClient);
            return resp.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * POST请求（JSON body）- 返回响应体，失败返回null
     * ★ 包含SSL降级逻辑
     */
    private String doPost(String urlStr, String jsonBody) {
        return doPostWithSslFallback(urlStr, jsonBody);
    }

    /**
     * POST请求（JSON body）- 返回响应体，失败返回null（不含SSL降级）
     * 供不需要降级的特殊POST使用
     */
    private String doPostWithoutFallback(String urlStr, String jsonBody) {
        // ★ Web通信关闭闸
        if (!isEnabled()) return null;
        if (cfHttpClient == null) {
            plugin.getLogger().warning("[Web通信] HttpClient未初始化");
            return null;
        }
        // ★ SSL断路器：断路期间直接返回，避免无意义请求
        if (isCircuitOpen()) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sdf1-WebManager/2.8")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendHard("POST无降级", req, cfHttpClient);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
            // ★ 非2xx也返回body（PHP的error()返回有用的JSON错误信息）
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] POST HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            if (isSecretAuthFailure(body)) {
                // ★ 密钥错配是唯一值得立即自愈 + 重试的 4xx（其余 4xx 重试无意义）
                return withSecretAutoHeal(() -> doPostWithoutFallback(urlStr, jsonBody));
            }
            if (body != null && !body.isEmpty()) {
                return body;
            }
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // ★ 10秒硬超时是「让位放行」不是SSL/网络故障：不计入断路器与降级，按无响应处理
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            // ★ SSL断路器：跟踪连续失败（所有连接异常都计数）
            sslConsecutiveFailures++;
            warnSlow("POST异常", "[Web通信] POST异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * POST请求 - 返回响应体，失败返回null（含SSL降级逻辑）
     */
    private String doPostWithSslFallback(String urlStr, String jsonBody) {
        // ★ Web通信关闭闸
        if (!isEnabled()) return null;
        if (cfHttpClient == null) {
            plugin.getLogger().warning("[Web通信] HttpClient未初始化");
            return null;
        }
        // ★ SSL断路器：断路期间直接返回，避免无意义请求
        if (isCircuitOpen()) {
            return null;
        }
        for (int attempt = 1; attempt <= 1 + CONNECT_RETRY_MAX; attempt++) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sdf1-WebManager/2.8")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendHard("POST", req, cfHttpClient);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sslConsecutiveFailures = 0;
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;  // 恢复正常HTTPS
                return resp.body();
            }
            // ★ 非2xx也返回body（PHP的error()返回有用的JSON错误信息）
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] POST HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            if (isSecretAuthFailure(body)) {
                // ★ 密钥错配是唯一值得立即自愈 + 重试的 4xx（其余 4xx 重试无意义）
                return withSecretAutoHeal(() -> doPostWithSslFallback(urlStr, jsonBody));
            }
            if (body != null && !body.isEmpty()) {
                return body;
            }
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // ★ 硬超时是「让位放行」不是网络故障：不计入断路器；sendHard 已打过节流日志
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            // ★ 连接阶段失败 → 请求还没发出去，换连接重试一次（POST 也安全，body 未发出）
            if (attempt < 1 + CONNECT_RETRY_MAX && isConnectStageFailure(e)) {
                infoThrottled("连接重试", "[Web通信] 连接阶段失败(" + e.getClass().getSimpleName() + ")，换连接重试");
                continue;
            }
            // ★ SSL断路器：跟踪连续失败（所有连接异常都计数）
            sslConsecutiveFailures++;
            // ★ 降级到HTTP：连续失败3次
            if (sslConsecutiveFailures >= SSL_DOWNGRADE_THRESHOLD && shouldDowngradeToHttp(urlStr)) {
                String httpUrl = urlStr.replaceFirst("^https:", "http:");
                return doPostHttpFallback(httpUrl, jsonBody);
            }
            if (sslConsecutiveFailures >= SSL_CIRCUIT_THRESHOLD) {
                triggerCircuitBreaker(false);
            }
            warnSlow("POST异常", "[Web通信] POST异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
        }
        return null;
    }

    /**
     * 降级后的HTTP POST请求（使用不带SSL的HttpClient）
     */
    private String doPostHttpFallback(String urlStr, String jsonBody) {
        // ★ Web通信关闭闸（降级路径同样不能出网）
        if (!isEnabled()) return null;
        if (plainHttpClient == null) {
            plugin.getLogger().warning("[Web通信] HTTP降级客户端未初始化");
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Sdf1-WebManager/2.8-http")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendHard("POST-HTTP降级", req, plainHttpClient);
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sslConsecutiveFailures = 0;
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;
                plugin.getLogger().info("[Web通信] HTTP降级POST成功，恢复正常HTTPS");
                String body = resp.body();
                detectPhpBusy(body);  // ★ 锁库检测
                return body;
            }
            plugin.getLogger().warning("[Web通信] HTTP降级失败 POST HTTP " + resp.statusCode() + ": " + urlStr);
            triggerCircuitBreaker(true);
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // ★ 10秒硬超时是「让位放行」不是SSL/网络故障：不计入断路器与降级，按无响应处理
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            plugin.getLogger().warning("[Web通信] HTTP降级POST异常: " + e.getMessage());
            triggerCircuitBreaker(true);
            return null;
        }
    }

    /**
     * POST请求带重试（处理SSL/网络异常）
     */
    private String doPostWithRetry(String urlStr, String jsonBody, int maxRetries) {
        // ★ Web通信关闭闸
        if (!isEnabled()) return null;
        // ★ SSL断路器：断路期间直接返回，避免无意义重试
        if (isCircuitOpen()) {
            return null;
        }
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(urlStr))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "Sdf1-WebManager/2.8")
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = sendHard("POST重试", req, cfHttpClient);
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String body = resp.body();
                    detectPhpBusy(body);  // ★ 锁库检测
                    return body;
                }
                if (isSecretAuthFailure(resp.body())) {
                    // ★ 密钥错配不是网络问题，重试 3 次也没用 → 直接走自愈通道（内部只重试一次）
                    return withSecretAutoHeal(() -> doPostWithRetry(urlStr, jsonBody, maxRetries));
                }
                if (attempt < maxRetries) {
                    plugin.getLogger().info("[Web通信] POST重试 " + attempt + "/" + maxRetries + " HTTP " + resp.statusCode());
                    budgetSleep(2000L * attempt);
                } else {
                    return resp.body();
                }
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    plugin.getLogger().info("[Web通信] POST重试 " + attempt + "/" + maxRetries + ": " + e.getClass().getSimpleName());
                    budgetSleep(2000L * attempt);
                } else {
                    if (e instanceof HttpHardTimeoutException) {
                        // ★ 10秒硬超时是「让位放行」：不计入断路器
                        infoThrottled("硬超时让位", "[Web通信] 硬超时让位(重试路径): " + e.getMessage());
                        return null;
                    }
                    plugin.getLogger().warning("[Web通信] POST最终失败: " + e.getMessage());
                    // ★ SSL断路器：跟踪连续失败（最终失败时计数）
                    sslConsecutiveFailures++;
                    return null;
                }
            }
        }
        return null;
    }

    // ==================== 配置加载 ====================

    private void loadConfig() {
        enabled = Boolean.parseBoolean(getConfigValue("web通信-启用", "false"));
        webBaseUrl = getConfigValue("web通信-地址", webBaseUrl);
        tokenExpireSeconds = Integer.parseInt(getConfigValue("web通信-Token有效期秒", "600"));
        syncIntervalMinutes = Integer.parseInt(getConfigValue("web通信-同步间隔分钟", "5"));
        callbackPort = Integer.parseInt(getConfigValue("web通信-回调端口", "9090"));
        secretKey = getConfigValue("web通信-密钥", secretKey);
        // ★ 记录已加载指纹，供热重载判定「文件是否变化」；同时留一份内存副本供诊断
        lastLoadedSecret = secretKey;
        lastLoadedUrl = webBaseUrl;
        try {
            File f = new File(plugin.getDataFolder(), "插件设置.txt");
            settingsFileStamp = f.exists() ? (f.lastModified() * 1000000L + f.length()) : -1L;
        } catch (Exception ignore) { }
        plugin.getLogger().info("[Web通信] 后端地址: " + webBaseUrl + " | 启用: " + enabled
                + " | 密钥长度: " + (secretKey != null ? secretKey.length() : 0)
                + "（后续改配置将自动热重载，无需 reload）");
    }

    /**
     * 重载后端设置（仅Web通信相关配置）
     */
    public void reloadWebConfig() {
        boolean wasEnabled = enabled;
        // ★ 强制清掉文件指纹，确保手动 reload 一定重新解析文件（否则会被指纹命中跳过）
        synchronized (configLock) { settingsFileStamp = -1L; }
        loadConfig();
        plugin.getLogger().info("[Web通信] Web后端配置已重载: 地址=" + webBaseUrl + " 启用=" + enabled
                + " 密钥长度=" + (secretKey != null ? secretKey.length() : 0));

        // ★★★ 运行时启停：根据enabled状态动态启动/停止轮询定时器
        // 1) 已禁用：定时器内部已有 !enabled 守卫，下一轮自动跳过HTTP请求（无需手动取消）
        // 2) 从禁用→启用且定时器未启动：立即启动合并定时器
        if (enabled && !pollingStarted) {
            plugin.getLogger().info("[Web通信] ★ 重载后检测到已启用，启动合并定时器（A/B/C/D/E）");
            startMergedPolling();
        } else if (!enabled && wasEnabled) {
            plugin.getLogger().info("[Web通信] ★ 重载后检测到已禁用，定时器将在下一轮自动停止请求Web后端");
        }

        // ★ 回调服务器幂等补启：开服时若绑定失败（端口占用），reload 可重试；
        //   已启动时 compareAndSet 直接返回，不会重复绑端口
        if (enabled) {
            startCallbackServer();
        }

        // ★ 2026-10-05 修复「reload 后不做全量同步」：
        //   旧版 reloadWebConfig() 只重启定时器，12 项全量同步与 alignTxWatermarkOnBoot()
        //   只在 start() 里跑一次 → reload 后历史流水永远不补推，必须 stop 重启才恢复。
        //   现在 reload 同样排一次全量同步（含与 PHP 的流水对齐）。
        if (enabled) {
            plugin.getLogger().info("[Web通信] ★ 重载后排一次全量同步（含流水对齐），10 秒后开始");
            scheduleFirstFullSync("reload");
        }
        plugin.getLogger().warning("\n" +
                "                                          _                                                                          \n" +
                "                                         | |                                                                         \n" +
                " __      _____  ___ ___  _ __ ___   ___  | |_ ___                                                                    \n" +
                " \\ \\ /\\ / / _ \\/ __/ _ \\| '_ ` _ \\ / _ \\ | __/ _ \\                                                                   \n" +
                "  \\ V  V /  __/ (_| (_) | | | | | |  __/ | || (_) |                                                                  \n" +
                "   \\_/\\_/ \\___|\\___\\___/|_| |_| |_|\\___|  \\__\\___/                  _                                                \n" +
                "                                             | |                   (_)                                               \n" +
                "   ___ __ _  ___    _   _ _   _  __ _ _ __   | |_ __ _ _ __   __  ___  __ _ _ __    ___  ___ _ ____   _____ _ __     \n" +
                "  / __/ _` |/ _ \\  | | | | | | |/ _` | '_ \\  | __/ _` | '_ \\  \\ \\/ / |/ _` | '_ \\  / __|/ _ \\ '__\\ \\ / / _ \\ '__|    \n" +
                " | (_| (_| | (_) | | |_| | |_| | (_| | | | | | || (_| | | | |  >  <| | (_| | | | | \\__ \\  __/ |   \\ V /  __/ |       \n" +
                "  \\___\\__,_|\\___/   \\__, |\\__,_|\\__,_|_| |_|  \\__\\__,_|_|_|_| /_/\\_\\_|\\__,_|_| |_| |___/\\___|_| __ \\_/ \\___|_|       \n" +
                "                     __/ |    (_)     _                 |__ \\                 | |   (_)   | (_)/ _|                  \n" +
                "  ___  ___ _ ____   |___/ _ __ _ _ __(_)  _ __ ___   ___   ) | _   _ _ __  ___| |__  _  __| |_| |_ _   _   ___ _ __  \n" +
                " / __|/ _ \\ '__\\ \\ / / _ \\ '__| | '_ \\   | '_ ` _ \\ / __| / / | | | | '_ \\/ __| '_ \\| |/ _` | |  _| | | | / __| '_ \\ \n" +
                " \\__ \\  __/ |   \\ V /  __/ |  | | |_) |  | | | | | | (__ / /_ | |_| | |_) \\__ \\ | | | | (_| | | | | |_| || (__| | | |\n" +
                " |___/\\___|_|    \\_/ \\___|_|  |_| .__(_) |_| |_| |_|\\___|____(_)__, | .__/|___/_| |_|_|\\__,_|_|_|  \\__,_(_)___|_| |_|\n" +
                "                                | |                             __/ | |                                              \n" +
                "                  _       ____  |_|_    ________ ___           |___/|_|                                              \n" +
                "                 | |  _  |___ \\ / _ \\  / /____  / _ \\                                                                \n" +
                "  _ __   ___  ___| |_(_)   __) | | | |/ /_   / / (_) |                                                               \n" +
                " | '_ \\ / _ \\/ __| __|    |__ <| | | | '_ \\ / / \\__, |                                                               \n" +
                " | |_) | (_) \\__ \\ |_ _   ___) | |_| | (_) / /    / /                                                                \n" +
                " | .__/ \\___/|___/\\__(_) |____/ \\___/ \\___/_/    /_/                                                                 \n" +
                " | |                                                                                                                 \n" +
                " |_|                                                                                                                 ");
    }

    private String getConfigValue(String key, String def) {
        try {
            File file = new File(plugin.getDataFolder(), "插件设置.txt");
            if (!file.exists()) return def;
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals(key)) {
                    reader.close();
                    return v;
                }
            }
            reader.close();
        } catch (Exception e) {
        }
        return def;
    }

    // ==================== 启动/停止 ====================

    // ★ 注册交易监听器（需在BondManager初始化后调用）
    public void registerTransactionListener(BondManager bondMgr) {
        if (bondMgr == null) return;
        bondMgr.addTransactionListener((playerName, type, amount, targetPlayer) -> {
            // 高频出售商品缓冲逻辑：shop_sell类型商品在1分钟内≥10次时暂缓推送
            if ("shop_sell".equals(type) && targetPlayer != null && !targetPlayer.isEmpty()) {
                handleHighFreqSell(playerName, targetPlayer, amount);
            } else {
                requestImmediateTransactionSync();
            }
        });
        plugin.getLogger().info("[Web通信] 交易即时推送监听器已注册");
    }

    /**
     * 高频出售缓冲处理：追踪每种商品的出售频率
     * 1分钟内单种商品售卖≥10次时进入缓冲模式，暂缓推送到PHP，60秒后批量推送
     */
    private void handleHighFreqSell(String playerName, String itemId, int amount) {
        long now = System.currentTimeMillis();
        SellItemBuffer buf = sellBuffers.computeIfAbsent(itemId, k -> new SellItemBuffer(itemId));

        // 如果窗口已过期（>60秒），重置计数器
        if (now - buf.windowStart > 60000) {
            buf.count = 0;
            buf.windowStart = now;
        }

        buf.count++;

        // 已经在缓冲模式中，不做任何推送
        if (buf.buffering && now < buf.bufferEnd) {
            return;
        }

        // 达到阈值，进入缓冲模式
        if (buf.count >= HIGH_FREQ_SELL_THRESHOLD && !buf.buffering) {
            buf.buffering = true;
            buf.bufferEnd = now + HIGH_FREQ_BUFFER_MS;
            inHighFreqBufferMode = true;
            bufferModeEndTime = Math.max(bufferModeEndTime, buf.bufferEnd);
            plugin.getLogger().info("[Web高频] 触发缓冲: " + itemId + " " + buf.count + "次/分钟，暂缓推送60秒");

            // 调度60秒后的批量推送
            new BukkitRunnable() {
                @Override
                public void run() {
                    buf.buffering = false;
                    buf.count = 0;
                    buf.windowStart = System.currentTimeMillis();

                    // 检查是否所有缓冲都结束了
                    boolean anyBuffering = false;
                    for (SellItemBuffer b : sellBuffers.values()) {
                        if (b.buffering) { anyBuffering = true; break; }
                    }
                    if (!anyBuffering) inHighFreqBufferMode = false;

                    plugin.getLogger().info("[Web高频] 缓冲到期，批量推送: " + itemId);
                    requestImmediateTransactionSync();
                }
            }.runTaskLater(plugin, 20L * 60); // 60秒
            return;
        }

        // 未达阈值或非shop_sell，立即推送
        requestImmediateTransactionSync();
    }

    public void start() {
        // ★ 2026-10-05 修复「开关关着时改配置永远无法热重载」（18:21 改文件、18:23 控台仍安静的根因）：
        //   旧版这里 !enabled 直接 return → startMergedPolling() 没执行 → 5 个合并定时器压根不存在
        //   → 每轮开头的 hotReloadIfChanged() 无人调用 → 运维把 插件设置.txt 的启用 false→true
        //   永远感知不到，只能手动 reload。与 hotReloadIfChanged 上方「开关关着时也要能感知改回 true」
        //   的设计注释直接矛盾——注释写对了，但 start() 把定时器掐死在了门外。
        //   现在无论开关状态都启动合并定时器：关着时每轮只做「热重载检查 + 自调度」，零 HTTP 请求。
        if (enabled) {
            plugin.getLogger().info("[Web通信] 已启用，地址: " + webBaseUrl);
        } else {
            plugin.getLogger().info("[Web通信] 未启用（web通信-启用=false），"
                    + "轮询定时器仍会启动用于自动感知配置变更，改回 true 无需 reload");
        }

        // 初始化sync_requests表和加载SQLite驱动
        try {
            Class.forName("org.sqlite.JDBC");
            File dataFolder = plugin.getDataFolder();
            File dbFile = new File(dataFolder, "web_sync.db");
            if (!dbFile.exists()) {
                dbFile.createNewFile();
            }
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS sync_log (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT, action TEXT, created_at INTEGER NOT NULL)");
            stmt.close();
            conn.close();
            plugin.getLogger().info("[Web通信] sqlite驱动已加载，sync_requests表已初始化");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] sync_requests表初始化失败: " + e.getMessage());
        }

        // 清理过期Token
        cleanExpiredTokens();

        // ★ 首次全量同步：仅启用时排（关闭态改 true 后由 hotReloadIfChanged 补排，见其末尾分支）
        if (enabled) {
            scheduleFirstFullSync("启动");
        }

        // ★ 启动嵌入式HTTP服务器接收PHP回调
        //   ★ 2026-10-05：无论开关状态都启动（内部幂等）。旧版只在 start() 且 enabled 时启动，
        //     「开服关闭 → 运行时改 true」后 PHP 的 notify_sync/validate_player 回调没人监听，
        //     与本次「定时器掐死在门外」是同一类根因。
        startCallbackServer();

        // ★ 初始化全量同步调度时间（由合并定时器C在到达nextSyncTime时触发）
        scheduleNextSync();

        // ★ 启动5个合并定时器（自动错峰≥5秒）
        //   ★ 2026-10-05：无条件启动 —— 它们每轮开头的 hotReloadIfChanged() 是「开关关着时
        //     也能感知 改回 true」的唯一载体，enabled=false 时不能不启动（否则热重载永远不触发）
        startMergedPolling();
    }

    /** 首次全量同步是否已在执行（防止 reload 与启动两条路径重叠跑两遍） */
    private final java.util.concurrent.atomic.AtomicBoolean fullSyncRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 排一次「首次全量同步」：先与 PHP 对账流水（alignTxWatermarkOnBoot），再按优先级排队 13 项。
     * 由 start()（启动 10 秒后）与 reloadWebConfig()（reload 当场）共同调用。
     *
     * @param trigger 触发来源，仅用于日志区分「启动」/「reload」
     */
    private void scheduleFirstFullSync(String trigger) {
        // ★ 每次间隔 1.5~3.5 秒由 run() 内部自己 sleep；这里只决定延迟起跑时间
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    plugin.getLogger().info("[Web通信] 全量同步已跳过（web通信-启用=false）, 来源=" + trigger);
                    return;
                }
                // ★ 与另一条路径互斥：已经在跑就不再起第二遍（13 项排队 + 水位线对齐只做一次）
                if (!fullSyncRunning.compareAndSet(false, true)) {
                    plugin.getLogger().info("[Web通信] 已有全量同步在执行，本次(" + trigger + ")跳过");
                    return;
                }
                try {
                long fullSyncStart = System.currentTimeMillis();
                plugin.getLogger().info("[Web通信] ===== 开始全量同步（来源：" + trigger + "）===== 地址=" + webBaseUrl
                        + " 密钥长度=" + (secretKey != null ? secretKey.length() : 0));
                // ★ 先与 PHP 对账流水：两边一致就完全跳过补推，对不上才补（避免每次开服全量重推）
                alignTxWatermarkOnBoot();
                // 登录相关操作高优先级
                submitDbTask("首次-syncUserRegistrations", () -> syncUserRegistrations());
                fullSyncStep("注册用户数据");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitDbTask("首次-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                fullSyncStep("密码凭证");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                // 普通同步操作低优先级（错峰提交，避免PHP端DB锁竞争）
                submitDbTask("首次-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
                fullSyncStep("在线玩家");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncShopData", () -> syncShopData());
                fullSyncStep("商城商品");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-pushShopCatalog", () -> pushShopCatalog());
                fullSyncStep("商城目录");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncBondBalances", () -> syncBondBalances());
                fullSyncStep("债券余额");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncBondTransactions", () -> syncBondTransactions());
                fullSyncStep("交易流水（先双向对账，多退少补）");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                // ★ 充值回执对账（2026-10-06）：一式两份、多退少补。启动即点一次差集，
                //   否则无人在线时只靠周期批次（需玩家上线触发），镜像与 PHP 永不收敛。
                submitNormalDbTask("首次-syncWebTxReceipts", () -> syncWebTxReceipts());
                fullSyncStep("充值回执（一式两份，多退少补）");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncAllPlayerIps", () -> syncAllPlayerIps());
                fullSyncStep("玩家IP");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncServiceProviders", () -> syncServiceProviders());
                fullSyncStep("服务商");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-syncLandData", () -> syncLandData());
                fullSyncStep("领地数据");
                awaitDbQueueIdle(10000);   // 等队列排空再提交下一项（原为固定1.5~3.5秒）
                submitNormalDbTask("首次-pollAdminChanges", () -> pollAdminChanges());
                fullSyncStep("管理员改动");
                initialSyncComplete = true;  // 首次全量同步提交完成
                allowLoginPolling = true;  // 允许登录轮询
                plugin.getLogger().info("[Web通信] ===== 全量同步 13 项已全部提交（来源：" + trigger + "），耗时 "
                        + (System.currentTimeMillis() - fullSyncStart) / 1000
                        + " 秒；随后由DB队列逐项执行，若某项失败会在日志打【失败/无响应】并下轮重试 =====");
                } finally {
                    // ★ 无论排队成功与否都要放行，否则一次异常会让后续 reload 永远拿不到锁
                    fullSyncRunning.set(false);
                }
            }
        }.runTaskLaterAsynchronously(plugin, 20L * 10);
    }

    public void shutdown() {
        stop();
        stopDbWorker();
        // ★ 关闭Web线程池
        webExecutor.shutdownNow();
        httpSideExecutor.shutdownNow(); // 旁路线程池一并关停（daemon线程，不关也能退出）
        // 关闭回调服务器
        if (callbackServer != null) {
            try {
                callbackServer.close();
            } catch (IOException e) {
            }
            plugin.getLogger().info("[Web通信] PHP回调服务器已关闭");
        }
        plugin.getLogger().info("[Web通信] Web通信管理器已关闭");
    }

    public void stop() {
        // 保存待同步数据
    }

    /**
     * 启动嵌入式HTTP服务器接收PHP回调
     */
    private void startCallbackServer() {
        // ★ 幂等闸：start() 与 reloadWebConfig 都可能调用，端口只能绑一次（否则 BindException）
        if (!callbackServerStarted.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                callbackServer = new java.net.ServerSocket(callbackPort);
                plugin.getLogger().info("[Web通信] 回调服务器监听端口: " + callbackPort);
                while (true) {
                    java.net.Socket socket = callbackServer.accept();
                    socket.setSoTimeout(5000);
                    // 在新线程处理，避免阻塞
                    new Thread(() -> {
                        try {
                            handleCallback(socket);
                        } catch (Exception e) {
                            // 静默处理
                        }
                    }).start();
                }
            } catch (IOException e) {
                // ★ 绑定失败（端口占用等）放开闸，允许下次 reload 重试
                callbackServerStarted.set(false);
                plugin.getLogger().warning("[Web通信] 回调服务器启动失败: " + e.getMessage());
            }
        }, "web-callback-server").start();
    }

    /** 回调服务器是否已启动（幂等闸，防止重复绑端口） */
    private final java.util.concurrent.atomic.AtomicBoolean callbackServerStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 处理PHP回调请求
     */
    private void handleCallback(java.net.Socket socket) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            // 读取HTTP请求
            StringBuilder request = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                request.append(line).append("\n");
            }
            if (request.length() == 0) return;

            // ★ 解析请求路径：POST /notify_sync HTTP/1.1 → /notify_sync
            String methodLine = request.toString().split("\n")[0];
            String path = "/";
            String[] parts = methodLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1]; // e.g. "/notify_sync", "/validate_player", "/register_callback"
            }

            // ★ 读取请求体（所有非GET路由共用）
            int contentLength = 0;
            for (String headerLine : request.toString().split("\n")) {
                if (headerLine.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(headerLine.split(":")[1].trim());
                }
            }
            byte[] buf = new byte[contentLength];
            if (contentLength > 0) {
                socket.getInputStream().read(buf, 0, contentLength);
            }
            String body = new String(buf, StandardCharsets.UTF_8);

            // ★ 路由分发
            if (path.contains("notify_sync")) {
                // ----- notify_sync: 触发交易拉取+登录轮询 -----
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        requestImmediateTransactionPull();
                    }
                }.runTaskAsynchronously(plugin);
                triggerLoginPoll();

            } else if (path.contains("validate_player")) {
                // ★★★ validate_player: PHP调用验证玩家是否存在（login.db） ★★★
                String playerName = extractJsonString(body, "player");
                String reqSecret = extractJsonString(body, "secret");
                if (playerName == null || playerName.isEmpty()) {
                    sendCallbackResponse(socket, "{\"success\":false,\"error\":\"missing_player\"}");
                    return;
                }
                // 验证密钥
                if (reqSecret == null || !reqSecret.equals(secretKey)) {
                    sendCallbackResponse(socket, "{\"success\":false,\"error\":\"invalid_secret\"}");
                    return;
                }
                DatabaseManager dbMgr = plugin.getDb();
                boolean exists = (dbMgr != null) && dbMgr.userExists(playerName);
                String resp = "{\"success\":true,\"player\":\"" + escapeJson(playerName) + "\",\"exists\":" + exists + "}";
                plugin.getLogger().info("[Web通信] PHP验证玩家: " + playerName + " → " + (exists ? "存在" : "不存在"));
                sendCallbackResponse(socket, resp);

            } else if (path.contains("register_callback")) {
                // ----- register_callback: 注册回调 -----
                String playerId = extractJsonString(body, "player");
                if (playerId == null || playerId.isEmpty()) {
                    sendCallbackResponse(socket, "{\"success\":false,\"message\":\"missing_player\"}");
                    return;
                }

                plugin.getLogger().info("[Web通信] 收到PHP回调：注册请求待处理 - " + playerId);

                final String fName = playerId;
                DatabaseManager dbMgr = plugin.getDb();
                if (dbMgr == null) return;

                Object existing = dbMgr.getField(fName, "password_salt");
                if (existing != null && !((String) existing).isEmpty()) {
                    plugin.getLogger().info("[Web通信] 玩家 " + fName + " 已注册，回调跳过");
                    sendCallbackResponse(socket, "{\"success\":true,\"message\":\"already_registered\"}");
                    return;
                }

                String pullUrl = webBaseUrl + "/api/sync.php?action=check_player_registered&player="
                        + java.net.URLEncoder.encode(fName, "UTF-8") + "&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                String pullJson = doGet(pullUrl);
                if (pullJson == null) {
                    plugin.getLogger().warning("[Web通信] PHP拉取注册数据失败");
                    sendCallbackResponse(socket, "{\"success\":false,\"message\":\"pull_failed\"}");
                    return;
                }
                if (!pullJson.contains("\"success\":true")) {
                    plugin.getLogger().warning("[Web通信] PHP返回非success: " + pullJson.substring(0, Math.min(200, pullJson.length())));
                    sendCallbackResponse(socket, "{\"success\":false,\"message\":\"invalid_response\"}");
                    return;
                }

                int dataStart = pullJson.indexOf("\"data\":");
                if (dataStart < 0) return;
                int dataObjStart = dataStart + 7;
                int dataObjEnd = findMatchingBracket(pullJson, dataObjStart);
                if (dataObjEnd < 0) return;
                String dataJson = pullJson.substring(dataObjStart, dataObjEnd);

                String passwordHash = extractJsonString(dataJson, "password_hash");
                String salt = extractJsonString(dataJson, "salt");
                String email = extractJsonString(dataJson, "email");

                if (passwordHash == null || salt == null) {
                    plugin.getLogger().warning("[Web通信] PHP返回数据无密码凭证");
                    sendCallbackResponse(socket, "{\"success\":false,\"message\":\"no_credentials\"}");
                    return;
                }

                dbMgr.createUser(fName, passwordHash, salt);
                plugin.getLogger().info("[Web通信] 回调创建用户成功: " + fName);

                String confirmUrl = webBaseUrl + "/api/sync.php?action=complete_web_register_request&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8")
                        + "&request_id=0"
                        + "&result=success";
                doPost(confirmUrl, "{}");

                sendCallbackResponse(socket, "{\"success\":true,\"message\":\"user_created\"}");

            } else {
                sendCallbackResponse(socket, "{\"success\":false,\"error\":\"unknown_path\"}");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[Web通信] 回调处理异常: " + e.getMessage());
        }
    }

    /**
     * 发送HTTP回调响应
     */
    private void sendCallbackResponse(java.net.Socket socket, String response) throws IOException {
        String httpResponse = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + response.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + response;
        socket.getOutputStream().write(httpResponse.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    // ==================== Token管理 ====================

    /**
     * 生成一次性Token
     *
     * @param playerName 玩家名
     * @param purpose    用途（shop/bond/cdk/admin/sync/all）
     * @return token字符串
     */
    public String generateToken(String playerName, String purpose) {
        String token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long now = System.currentTimeMillis();
        tokenStore.put(token, new String[]{playerName, purpose, String.valueOf(now)});
        return token;
    }

    /**
     * 验证Token
     */
    public boolean validateToken(String token) {
        String[] info = tokenStore.get(token);
        if (info == null) return false;

        long created = Long.parseLong(info[2]);
        long now = System.currentTimeMillis();
        long expireMs = (long) tokenExpireSeconds * 1000;

        if (now - created > expireMs) {
            tokenStore.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 作废指定Token（安全防线：公屏泄露时立即销毁）
     * 同时联控PHP后端删除weblogin_tokens表中的记录
     *
     * @param token 要作废的token
     * @return 如果token存在并被成功作废返回true
     */
    public boolean revokeToken(String token) {
        boolean removed = tokenStore.remove(token) != null;

        // 异步联控PHP作废该token
        if (removed) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=revoke_weblogin_token"
                            + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                            + "&token=" + java.net.URLEncoder.encode(token, "UTF-8");
                    String response = doGet(urlStr);
                    plugin.getLogger().info("[安全防线] PHP联控作废token结果: " + response);
                } catch (Exception e) {
                    plugin.getLogger().warning("[安全防线] PHP联控作废token失败: " + e.getMessage());
                }
            });
        }

        return removed;
    }

    /**
     * 检查token是否存在（不消耗）
     */
    public boolean hasToken(String token) {
        return tokenStore.containsKey(token);
    }

    /**
     * 使用并销毁Token（一次性）
     */
    public String[] useToken(String token) {
        String[] info = tokenStore.get(token);
        if (info == null) return null;

        long created = Long.parseLong(info[2]);
        long now = System.currentTimeMillis();
        long expireMs = (long) tokenExpireSeconds * 1000;

        if (now - created > expireMs) {
            tokenStore.remove(token);
            return null;
        }

        tokenStore.remove(token);
        return info;
    }

    private void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        long expireMs = (long) tokenExpireSeconds * 1000;
        tokenStore.entrySet().removeIf(e -> {
            long created = Long.parseLong(e.getValue()[2]);
            return now - created > expireMs;
        });
    }

    // ==================== 字段：同步调度 ====================

    private volatile boolean activeSyncRunning = false;
    private volatile boolean activeSyncStopped = false;
    private volatile long nextSyncTime = 0;

    private void scheduleNextSync() {
        long interval = 60000L + (long) (Math.random() * 30000); // 60~90秒
        nextSyncTime = System.currentTimeMillis() + interval;
    }

    // 交易同步请求触发器：立即触发一次拉取
    private volatile boolean pendingTransactionPullRequested = false;
    private volatile long lastTransactionPullTime = 0;
    private static final long MIN_PULL_INTERVAL_MS = 5000; // 最小拉取间隔5秒，防止过于频繁

    // ★ 本轮会话已确认的交易ID集合（防止重启后异步confirm未完成导致PHP回退processing→pending从而重复发货）
    private final java.util.Set<String> confirmedTxIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void requestImmediateTransactionPull() {
        long now = System.currentTimeMillis();
        if (now - lastTransactionPullTime < MIN_PULL_INTERVAL_MS) {
            pendingTransactionPullRequested = true;
            return;
        }
        lastTransactionPullTime = now;
        pendingTransactionPullRequested = false;
        submitNormalDbTask("即时拉取-交易", () -> pullPendingTransactions());
    }

    // ★ 领地即时同步触发器：领地设置变更后立即推送数据到PHP
    private volatile long lastImmediateLandSyncTime = 0;
    private static final long MIN_IMMEDIATE_LAND_SYNC_MS = 10000; // 最小间隔10秒，防抖
    private volatile boolean pendingImmediateLandSync = false;

    /**
     * 领地设置变更后调用：立即推送领地数据到PHP
     * 防抖：10秒内多次调用只执行一次
     */
    public void requestImmediateLandSync() {
        long now = System.currentTimeMillis();
        if (now - lastImmediateLandSyncTime < MIN_IMMEDIATE_LAND_SYNC_MS) {
            pendingImmediateLandSync = true;
            return;
        }
        lastImmediateLandSyncTime = now;
        pendingImmediateLandSync = false;
        submitNormalDbTask("即时同步-领地", () -> {
            try {
                lastLandDataHash = ""; // ★ 强制刷新hash，确保一定推送
                syncLandData();
            } catch (Exception e) {
                plugin.getLogger().warning("[领地即时同步] 异常: " + e.getMessage());
            }
            if (pendingImmediateLandSync) {
                pendingImmediateLandSync = false;
                submitNormalDbTask("即时同步-领地(延迟)", () -> {
                    try {
                        lastLandDataHash = "";
                        syncLandData();
                    } catch (Exception e) {}
                });
            }
        });
    }

    // ★ 交易即时推送触发器：交易发生后立即推送交易记录到PHP
    private volatile long lastImmediateTxSyncTime = 0;
    private static final long MIN_IMMEDIATE_TX_SYNC_MS = 3000; // 最小间隔3秒，防止频繁推送
    private volatile boolean pendingImmediateTxSync = false;

    // ★ 高频出售商品缓冲机制：1分钟内单种商品售卖≥10次时暂缓推送
    private final ConcurrentHashMap<String, SellItemBuffer> sellBuffers = new ConcurrentHashMap<>();
    private static final int HIGH_FREQ_SELL_THRESHOLD = 10;  // 10次触发缓冲
    private static final long HIGH_FREQ_BUFFER_MS = 60000;   // 缓冲60秒
    private volatile boolean inHighFreqBufferMode = false;    // 全局缓冲模式标志
    private volatile long bufferModeEndTime = 0;              // 缓冲模式结束时间

    private static class SellItemBuffer {
        final String itemId;
        volatile int count = 0;
        volatile long windowStart = 0;
        volatile boolean buffering = false;
        volatile long bufferEnd = 0;

        SellItemBuffer(String itemId) {
            this.itemId = itemId;
            this.windowStart = System.currentTimeMillis();
        }
    }

    private void requestImmediateTransactionSync() {
        long now = System.currentTimeMillis();
        if (now - lastImmediateTxSyncTime < MIN_IMMEDIATE_TX_SYNC_MS) {
            // 间隔太短，标记待处理
            pendingImmediateTxSync = true;
            return;
        }
        lastImmediateTxSyncTime = now;
        pendingImmediateTxSync = false;
        submitDbTask("即时推送-交易", () -> {
            try {
                syncBondTransactions();
            } catch (Exception e) {
                plugin.getLogger().warning("[Web交易即时] 推送异常: " + e.getMessage());
            }
            // ★ 关键修复：交易处理后立即推送余额快照到PHP
            // 解决全员离线时Timer C暂停导致PHP余额不同步的bug
            try {
                lastBondBalanceHash = ""; // 清除hash缓存，强制推送
                syncBondBalances();
            } catch (Exception e) {
                plugin.getLogger().warning("[Web交易即时] 余额推送异常: " + e.getMessage());
            }
            // 检查是否有待处理的推送
            if (pendingImmediateTxSync) {
                pendingImmediateTxSync = false;
                submitDbTask("即时推送-交易(延迟)", () -> {
                    try { syncBondTransactions(); } catch (Exception e) {}
                    try { lastBondBalanceHash = ""; syncBondBalances(); } catch (Exception e) {}
                });
            }
        });
    }

    /**
     * 全量批处理同步（由合并定时器C调用）
     * 包含：sync_requests即时同步 + 全员下线末轮同步 + 在线周期的全量同步
     */
    private void doActiveSyncBatch() {
        if (activeSyncRunning) return;
        activeSyncRunning = true;
        try {
            // ★ 检查sync_requests表（来自PHP端的即时同步请求）
            try {
                File dataFolder = plugin.getDataFolder();
                File dbFile = new File(dataFolder, "web_sync.db");
                if (dbFile.exists()) {
                    Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT player_name FROM sync_requests LIMIT 10");
                    boolean hasRequest = false;
                    StringBuilder players = new StringBuilder();
                    while (rs.next()) {
                        hasRequest = true;
                        String player = rs.getString("player_name");
                        if (players.length() > 0) players.append(",");
                        players.append(player);
                        stmt.execute("DELETE FROM sync_requests WHERE player_name = '" + player + "'");
                        stmt.execute("INSERT INTO sync_log (player_name, action, created_at) VALUES ('" + player + "', 'immediate_sync', " + System.currentTimeMillis() / 1000 + ")");
                    }
                    rs.close();
                    stmt.close();
                    conn.close();

                    if (hasRequest) {
                        plugin.getLogger().info("[合并C] 收到即时同步请求: " + players);
                        submitDbTask("即时-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitDbTask("即时-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitDbTask("即时-syncUserRegistrations", () -> syncUserRegistrations());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-pullPendingTransactions", () -> pullPendingTransactions());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-pullShopStock", () -> pullShopStock());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-pullShopPrices", () -> pullShopPrices());
                        submitNormalDbTask("即时-pullShopConfig", () -> pullShopConfig());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-pullBondChanges", () -> pullBondChanges());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-syncAllPlayerIps", () -> syncAllPlayerIps());
                        awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                        submitNormalDbTask("即时-syncServiceProviders", () -> syncServiceProviders());
                    }
                }
            } catch (Exception e) { /* 静默忽略 */ }

            boolean hasOnlinePlayers = !Bukkit.getOnlinePlayers().isEmpty();

            if (!hasOnlinePlayers) {
                if (!allPlayersOffline) {
                    allPlayersOffline = true;
                    timersBCPaused = true;  // ★ 全员下线：暂停Timer B/C
                    lastOnlineCheckTime = System.currentTimeMillis();
                    syncAfterAllOffline = true;
                    plugin.getLogger().info("[合并C] ★ 全员下线，Timer B/C已暂停，仅保留Timer A(注册登录)");
                } else if (syncAfterAllOffline && !lastSyncDone && (System.currentTimeMillis() - lastOnlineCheckTime > 60000)) {
                    allPlayersOffline = false;
                    syncAfterAllOffline = false;
                    lastSyncDone = true;
                    lastOnlineCheckTime = System.currentTimeMillis();
                    submitDbTask("末轮-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitDbTask("末轮-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitDbTask("末轮-syncUserRegistrations", () -> syncUserRegistrations());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitDbTask("末轮-syncBondTransactions", () -> syncBondTransactions());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitNormalDbTask("末轮-pullPendingTransactions", () -> pullPendingTransactions());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitNormalDbTask("末轮-pullShopStock", () -> pullShopStock());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitNormalDbTask("末轮-pullShopPrices", () -> pullShopPrices());
                    submitNormalDbTask("末轮-pullShopConfig", () -> pullShopConfig());
                    awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
                    submitNormalDbTask("末轮-pullBondChanges", () -> pullBondChanges());
                    plugin.getLogger().info("[合并C] 全员下线超60秒，末轮同步已执行");
                }
                return;
            }

            allPlayersOffline = false;
            syncAfterAllOffline = false;
            lastSyncDone = false;

            // ★ 玩家上线恢复：重启Timer B/C/E（之前全员下线时已暂停）
            // ★ Timer D已独立运行，不再需要重启
            if (timersBCPaused) {
                timersBCPaused = false;
                plugin.getLogger().info("[合并C] ★ 玩家上线，Timer B/C/E 已恢复");
            }

            // 玩家在线：全量批处理
            submitDbTask("周期-pushWebLoginCredentials", () -> pushWebLoginCredentials());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitDbTask("周期-syncUserRegistrations", () -> syncUserRegistrations());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitDbTask("周期-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitDbTask("周期-syncBondTransactions", () -> syncBondTransactions());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-pullPendingTransactions", () -> pullPendingTransactions());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-pullShopStock", () -> pullShopStock());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-pullShopPrices", () -> pullShopPrices());
            submitNormalDbTask("周期-pullShopConfig", () -> pullShopConfig());
            submitNormalDbTask("周期-pushShopCatalog", () -> pushShopCatalog());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-pullBondChanges", () -> pullBondChanges());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-syncAllPlayerIps", () -> syncAllPlayerIps());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            submitNormalDbTask("周期-syncServiceProviders", () -> syncServiceProviders());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            // ★ 充值回执对账（2026-10-06）：一式两份、多退少补，随全量同步一起热同步。
            //   放在 pullPendingTransactions（上面）之后：刚拉到的新充值先落镜像，再点差集。
            submitNormalDbTask("周期-syncWebTxReceipts", () -> syncWebTxReceipts());
            awaitDbQueueIdle(14000);   // 等队列排空（原为固定6~14秒错峰）
            // ★ syncLandData已由Timer D独立定时器处理（15~25秒），不再放批处理队列防SQL锁
            submitNormalDbTask("周期-pollAdminChanges", () -> pollAdminChanges());

            checkSyncNotify();
        } catch (Exception e) {
            plugin.getLogger().warning("[合并C] 全量同步异常: " + e.getMessage());
        } finally {
            activeSyncRunning = false;
        }
    }

    /**
     * 交易高频轮询：每15秒(±5)检查PHP端是否有待处理交易
     * 检测到pending交易时立即拉取，不等60-90秒的全量同步
     */
    /**
     * 交易高频轮询：每5秒检查PHP端是否有待处理交易
     * ★ 修复：移除activeSyncStopped检查，即使无在线玩家也要轮询（管理员可能在Web后台操作）
     * 支持MC服务器和Web服务器不在同一台机器的场景
     */
    /**
     * 检查PHP端是否有待处理交易（由合并定时器B调用）
     */
    private void doTransactionPollCheck() {
        // ★ 2026-07-15 高频补单触发：合并B定时器每~5秒跑一次，顺带触发 poller 快速检测已支付订单
        //    poller 内部有 flock 锁 + 幂等去重，多次调用安全
        if (System.currentTimeMillis() - lastPollerTriggerTime > 15000) { // 限流15秒一次
            lastPollerTriggerTime = System.currentTimeMillis();
            try {
                String pollerUrl = webBaseUrl + "/api/poller_online.php?secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                long pollerStart = System.currentTimeMillis();
                String pollerResp = doGet(pollerUrl);
                long pollerElapsed = System.currentTimeMillis() - pollerStart;

                if (pollerResp != null) {
                    if (pollerResp.contains("\"result\":\"ok\"")) {
                        plugin.getLogger().info("[快速补单] 补单成功 (" + pollerElapsed + "ms): " + pollerResp.substring(0, Math.min(150, pollerResp.length())));
               /*     } else if (pollerResp.contains("\"result\":\"already_running\"")) {
                        plugin.getLogger().info("[快速补单] 补单进程正在运行，跳过 (" + pollerElapsed + "ms)");
                    } else {
                        plugin.getLogger().warning("[快速补单] 补单返回: " + pollerResp.substring(0, Math.min(150, pollerResp.length())) + " (" + pollerElapsed + "ms)");*/
                    }
                } else {
                    warnSlow("快速补单", "[快速补单] 补单无响应(null)，耗时: " + pollerElapsed + "ms");
                }
            } catch (Exception ignored) {
                // 补单失败不影响交易拉取，静默忽略
            }
        }

        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=check_pending_transactions&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doGet(urlStr);
            if (resp == null) {
                txPollFailCount++;
                long now = System.currentTimeMillis();
                if (now - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                    warnSlow("合并B交易", "[合并B-交易] GET失败 (连续失败" + txPollFailCount + "次)");
                    lastTxPollLogTime = now;
                }
                return;
            }

            // ★ 锁库特殊处理：reset failCount，不计失败，等下一轮重试
            if (resp.contains("\"database is locked\"")) {
                txPollFailCount = 0;
                return;
            }
            // 成功 → 重置失败计数
            txPollFailCount = 0;
            // 快速解析 "pending":N
            int idx = resp.indexOf("\"pending\":");
            if (idx >= 0) {
                idx += 10;
                int end = resp.indexOf("}", idx);
                if (end < 0) end = resp.length();
                String val = resp.substring(idx, end).replaceAll("[^0-9]", "");
                int pending = Integer.parseInt(val);
                if (pending > 0) {
                    plugin.getLogger().info("[合并B-交易] 检测到 " + pending + " 笔待处理交易，立即拉取");
                    pullPendingTransactions();
                }
            } else {
                long now = System.currentTimeMillis();
                if (now - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                    plugin.getLogger().warning("[合并B-交易] 响应格式异常: " + resp.substring(0, Math.min(200, resp.length())));
                    lastTxPollLogTime = now;
                }
            }
        } catch (Exception e) {
            txPollFailCount++;
            long now = System.currentTimeMillis();
            if (now - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                plugin.getLogger().warning("[合并B-交易] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()
                        + " (连续失败" + txPollFailCount + "次)");
                lastTxPollLogTime = now;
            }
        }
    }

    // ==================== ★ v17 合并定时器错峰调度 ====================

    /**
     * 计算错峰延迟：确保本定时器与其他定时器至少间隔8秒
     * @param timerId 本定时器ID (TIMER_A/B/C)
     * @param baseMin 最小延迟(秒)
     * @param baseMax 最大延迟(秒)
     * @return ticks (至少1 tick)
     */
    private long calcStaggeredDelay(int timerId, long baseMin, long baseMax) {
        long baseDelay = baseMin + (long)(Math.random() * (baseMax - baseMin + 1));
        long delayMs = baseDelay * 1000L;
        long now = System.currentTimeMillis();

        synchronized (scheduleLock) {
            // ★ PHP锁库退避：如果PHP正忙，所有定时器延后
            if (phpBusyUntil > now) {
                delayMs = Math.max(delayMs, phpBusyUntil - now + 2000);
            }
            for (int i = 0; i < 5; i++) {   // ★ 2026-10-05 修复：原来写 4，漏了 TIMER_E(id=4)，E 与其他4个不定错峰
                if (i == timerId) continue;
                long otherLast = lastRunTimestamps[i];
                if (otherLast == 0) continue;
                // 如果预期执行时间距其他定时器上次执行不足8秒，推后
                long gap = (now + delayMs) - otherLast;
                if (gap >= 0 && gap < 8000) {
                    delayMs += (8000 - gap) + (long)(Math.random() * 3000);
                }
            }
        }
        return Math.max(1L, delayMs / 50L);
    }

    /**
     * 检测PHP锁库退避：response包含database is locked时设置退避
     */
    private void detectPhpBusy(String response) {
        if (response != null && response.contains("database is locked")) {
            phpBusyUntil = System.currentTimeMillis() + PHP_BUSY_BACKOFF_MS;
            plugin.getLogger().warning("[Web通信] ★ PHP锁库退避：暂停所有定时器" + (PHP_BUSY_BACKOFF_MS / 1000) + "秒");
        }
    }

    /**
     * 启动3个合并定时器（替代原来的6个独立定时器）
     * 定时器A 注册登录 3~5秒 | 定时器B 交易 0~10秒 | 定时器C 其它 10~20秒
     * 三者错峰：首次启动带±5秒随机偏移，后续通过calcStaggeredDelay自动错开≥8秒
     */
    private void startMergedPolling() {
        pollingStarted = true; // ★ 标记定时器已启动（运行时重载启停判断用）
        // ★ 首次启动加±5秒随机偏移，避免精确对齐导致并发
        long randA = (long)(Math.random() * 10) * 2; // 0~10秒(偶数tick)
        long randB = (long)(Math.random() * 10) * 2;
        long randC = (long)(Math.random() * 10) * 2;
        long randD = (long)(Math.random() * 10) * 2;
        long randE = (long)(Math.random() * 10) * 2;
        scheduleTimerA(40L + randA);
        scheduleTimerB(140L + randB);
        scheduleTimerC(240L + randC);
        scheduleTimerD(340L + randD);
        scheduleTimerE(440L + randE);
        plugin.getLogger().info("[Web通信] ★ 合并定时器已启动(随机偏移A=" + (randA/20) + "s B=" + (randB/20) + "s C=" + (randC/20) + "s D=" + (randD/20) + "s E=" + (randE/20) + "s)");
    }

    // Timer A 内部计数器：每N轮同步一次在线玩家（保持PHP心跳不断）
    private int timerACycleCount = 0;
    private static final int SYNC_ONLINE_EVERY_N_CYCLES = 10; // ~30-50秒同步一次

    /**
     * 定时器A — 注册登录（3~5秒快速轮询）
     * ★ v19: 3个请求串行化执行（不再并行），每个间隔2-3秒，彻底避免PHP锁库
     */
    private void scheduleTimerA(long ticks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                synchronized (scheduleLock) { lastRunTimestamps[TIMER_A] = now; }

                // ★★★ Web通信未启用时跳过所有轮询（支持运行时通过重载配置关闭）
                // 修复bug：之前仅在start()判断enabled，重载配置为false后定时器仍持续请求web配置地址
                // ★ 配置热重载：每轮开头自动感知 插件设置.txt 变更（启用开关 / 地址 / 密钥）
                //   放在 enabled 守卫【之前】：开关关着时也要能感知「运维把开关改回 true」
                hotReloadIfChanged();

                if (!enabled) {
                    scheduleTimerA(calcStaggeredDelay(TIMER_A, 3, 5));
                    return;
                }

                // ★ PHP锁库退避检查：如果PHP正忙，跳过本轮
                if (phpBusyUntil > System.currentTimeMillis()) {
                    scheduleTimerA(calcStaggeredDelay(TIMER_A, 3, 5));
                    return;
                }

                if (allowLoginPolling) {
                    // ★ 串行化：注册请求 → 等2~3秒 → 登录确认 → 等2~3秒 → 登录请求
                    submitWebTask("合并A-注册轮询", () -> {
                        try { pollWebRegisterRequests(); }
                        catch (Exception e) {
                            long t = System.currentTimeMillis();
                            if (t - lastPollRegisterRequestsLog > LOG_INTERVAL) {
                                plugin.getLogger().warning("[合并A-注册] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                                lastPollRegisterRequestsLog = t;
                            }
                        }
                        // ★ 串行间隔：等2~3秒再发下一个请求
                        try { Thread.sleep(2000 + (long)(Math.random() * 1000)); } catch (InterruptedException ignored) {}
                        // 登录确认轮询
                        try { pollWebLoginConfirmations(); }
                        catch (Exception e) {
                            long t = System.currentTimeMillis();
                            if (t - lastPollWebLoginExceptionLog > LOG_INTERVAL) {
                                plugin.getLogger().warning("[合并A-登录确认] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                                lastPollWebLoginExceptionLog = t;
                            }
                        }
                        // ★ 串行间隔
                        try { Thread.sleep(2000 + (long)(Math.random() * 1000)); } catch (InterruptedException ignored) {}
                        // 密码验证请求轮询
                        try { pollWebLoginRequests(); }
                        catch (Exception e) {
                            long t = System.currentTimeMillis();
                            if (t - lastPollWebLoginExceptionLog > LOG_INTERVAL) {
                                plugin.getLogger().warning("[合并A-登录请求] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                                lastPollWebLoginExceptionLog = t;
                            }
                        }
                    });
                }

                // ★ 定期同步在线玩家（保持PHP心跳，全员下线时也推送空列表）
                timerACycleCount++;
                if (timerACycleCount >= SYNC_ONLINE_EVERY_N_CYCLES) {
                    timerACycleCount = 0;
                    submitDbTask("TimerA-syncOnline", () -> {
                        try { syncOnlinePlayers(); }
                        catch (Exception e) { /* 静默 */ }
                    }, true);
                }

                // ★ CDK离线兑付：Timer A永不暂停，定期拉取CDK交易
                // 解决全员下线时Timer B暂停导致CDK兑换不到账的bug
                //
                // ★★★ 队列保护（v2.93）：原来这一轮会一口气投出【5个】子任务，
                //      而 db-worker 是单线程、每个任务又含10~30秒级HTTP请求，
                //      Timer A 却每3~5秒就再投一轮 → 队列越堆越长
                //      （日志表现为「等待过久 10s+」「清空队列丢弃17个」依然慢）。
                //      现在合并为【1个】队列任务串行执行：执行顺序与原来一致
                //      （单线程本来就是串行），但队列条目数降为 1/5。
                if (timerACycleCount % 5 == 0) { // 每5轮(~15-25秒)检查一次
                    submitNormalDbTask("TimerA-周期批处理", () -> {
                        // 本地CDK（web_transactions pending）
                        try {
                            doTransactionPollCheck();
                        } catch (Exception e) { /* 静默 */ }
                        if (!initialSyncComplete) return;

                        // 远程CDK（cdk_validate_requests，sdf1计分板CDK验证）
                        try {
                            pullWebCdkRequestsAndValidate();
                            pullSdf1PendingAndValidateWeb();
                        } catch (Exception e) { /* 静默 */ }

                        // ★ PHP→Java变更轮询（管理员在PHP改了配置/领地，Java及时拉取）
                        try { pollAdminChanges(); } catch (Exception e) { /* 静默 */ }
                        // ★ 过户cooldown检测：权限变更则取消过户
                        try { handlePendingTransferCancellations(); } catch (Exception e) { /* 静默 */ }
                        // ★ 异步玩家验证轮询：拉取PHP的pending_player_validations，验证后推回结果
                        try { pullPendingPlayerValidations(); } catch (Exception e) { /* 静默 */ }
                    });
                }

                // 自调度下一轮（3~5秒，快速响应登录请求，错峰避免锁库）
                scheduleTimerA(calcStaggeredDelay(TIMER_A, 3, 5));
            }
        }.runTaskLaterAsynchronously(plugin, ticks);
    }

    /**
     * 定时器B — 交易（0~10秒轮询）
     * ★ v19: 全员下线时自动暂停，PHP锁库时跳过
     */
    private void scheduleTimerB(long ticks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                synchronized (scheduleLock) { lastRunTimestamps[TIMER_B] = now; }

                // ★★★ Web通信未启用时跳过所有轮询（支持运行时关闭）
                // ★ 配置热重载：每轮开头自动感知 插件设置.txt 变更（启用开关 / 地址 / 密钥）
                //   放在 enabled 守卫【之前】：开关关着时也要能感知「运维把开关改回 true」
                hotReloadIfChanged();

                if (!enabled) {
                    scheduleTimerB(calcStaggeredDelay(TIMER_B, 0, 10));
                    return;
                }

                // ★ 全员下线暂停：跳过本轮工作但保持链存活
                // ★ 关键：恢复逻辑在 doActiveSyncBatch 里，只由 Timer C 调用，
                // ★ 如果在这里直接 return 停死，就再也没人能唤醒它（生产事故根因）
                if (timersBCPaused) {
                    scheduleTimerB(calcStaggeredDelay(TIMER_B, 0, 10));
                    return;
                }

                // ★ PHP锁库退避检查
                if (phpBusyUntil > now) {
                    scheduleTimerB(calcStaggeredDelay(TIMER_B, 0, 10));
                    return;
                }

                // 交易检查（check_pending_transactions）
                try { doTransactionPollCheck(); }
                catch (Exception e) {
                    long t = System.currentTimeMillis();
                    if (t - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                        plugin.getLogger().warning("[合并B-交易] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        lastTxPollLogTime = t;
                    }
                }

                if (initialSyncComplete) {
                    // 库存变更检测
                    try { doShopStockPollCheck(); }
                    catch (Exception e) {
                        long t = System.currentTimeMillis();
                        if (t - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                            plugin.getLogger().warning("[合并B-库存] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                            lastTxPollLogTime = t;
                        }
                    }
                    // CDK验证
                    try {
                        pullWebCdkRequestsAndValidate();
                        pullSdf1PendingAndValidateWeb();
                    } catch (Exception e) { /* 静默，CDK内部已有容错 */ }
                }

                // 自调度下一轮（0~10秒，错峰）
                scheduleTimerB(calcStaggeredDelay(TIMER_B, 0, 10));
            }
        }.runTaskLaterAsynchronously(plugin, ticks);
    }

    /**
     * 定时器C — 其它（10~20秒轮询）
     * ★ v19: 全员下线时自动暂停，PHP锁库时跳过
     */
    private void scheduleTimerC(long ticks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                synchronized (scheduleLock) { lastRunTimestamps[TIMER_C] = now; }

                // ★★★ Web通信未启用时跳过所有轮询（支持运行时关闭）
                // ★ 配置热重载：每轮开头自动感知 插件设置.txt 变更（启用开关 / 地址 / 密钥）
                //   放在 enabled 守卫【之前】：开关关着时也要能感知「运维把开关改回 true」
                hotReloadIfChanged();

                if (!enabled) {
                    scheduleTimerC(calcStaggeredDelay(TIMER_C, 10, 20));
                    return;
                }

                // ★ 全员下线暂停：Timer C 兼任看门狗，不能停死
                if (timersBCPaused) {
                    if (!Bukkit.getOnlinePlayers().isEmpty()) {
                        timersBCPaused = false;
                        plugin.getLogger().info("[合并C] ★ 检测到玩家上线，Timer B/C/E 恢复运行");
                        // 三条链本身一直存活（下方都改为保活调度），这里无需重新 schedule B/E，继续本轮正常流程
                    } else {
                        scheduleTimerC(calcStaggeredDelay(TIMER_C, 10, 20));
                        return;
                    }
                }

                // ★ PHP锁库退避检查
                if (phpBusyUntil > now) {
                    scheduleTimerC(calcStaggeredDelay(TIMER_C, 10, 20));
                    return;
                }

                // 领地配置同步
                if (!Bukkit.getOnlinePlayers().isEmpty()) {
                    try { doLandSyncPoll(); }
                    catch (Exception e) { landSyncFailCount++; }
                }

                // ★ SN 防刷同步（任务6）：手持上报 + 事件队列 + 命令拉取
                try {
                    SnManager snm = plugin.getSnManager();
                    if (snm != null) snm.tickSync();
                } catch (Throwable t) {
                    plugin.getLogger().warning(
                            "[SN] 同步异常: " + t.getMessage());
                }

                // 全量批处理同步（仅在nextSyncTime到达时执行）
                // ★ doActiveSyncBatch 内部有 9 处 6~14 秒的错峰 sleep（合计 54~126 秒），
                //   原来同步跑在本定时器里，直接把 Timer C 单轮拉长到约 2 分钟，
                //   SN 命令的拉取/回执也跟着变成 1~2 分钟一轮。
                //   改为丢到独立异步线程执行：Timer C 只负责到点触发后立刻排下一轮，
                //   批处理照旧串行错峰（防 PHP 锁库），只是不再占用 SN 同步链路。
                if (now >= nextSyncTime && !activeSyncRunning) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            doActiveSyncBatch();
                        } catch (Exception e) {
                            plugin.getLogger().warning("[合并C-全量同步] 异常: " + e.getMessage());
                        } finally {
                            scheduleNextSync(); // 批处理结束后设置60~90秒后的下一次同步时间
                        }
                    });
                }

                // 自调度下一轮（10~20秒，错峰）
                scheduleTimerC(calcStaggeredDelay(TIMER_C, 10, 20));
            }
        }.runTaskLaterAsynchronously(plugin, ticks);
    }

    /**
     * 定时器D — 领地数据独立同步（15~25秒，独立防SQL锁）
     * ★ 只做syncLandData()，不与其他定时器共享任务队列
     */
    private void scheduleTimerD(long ticks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                synchronized (scheduleLock) { lastRunTimestamps[TIMER_D] = now; }

                // ★★★ Web通信未启用时跳过所有轮询（支持运行时关闭）
                // ★ 配置热重载：每轮开头自动感知 插件设置.txt 变更（启用开关 / 地址 / 密钥）
                //   放在 enabled 守卫【之前】：开关关着时也要能感知「运维把开关改回 true」
                hotReloadIfChanged();

                if (!enabled) {
                    scheduleTimerD(calcStaggeredDelay(TIMER_D, 15, 25));
                    return;
                }

                // ★ Timer D独立：不检查timersBCPaused（v17重构设计）

                // ★ PHP锁库退避检查
                if (phpBusyUntil > now) {
                    scheduleTimerD(calcStaggeredDelay(TIMER_D, 15, 25));
                    return;
                }

                // 领地数据同步（独立执行，不走doActiveSyncBatch队列）
                if (!Bukkit.getOnlinePlayers().isEmpty()) {
                    try {
                        // ★ 不再强制清空hash — syncLandData内部hash比较已足够检测变化
                        syncLandData();
                    } catch (Exception e) {
                        plugin.getLogger().warning("[领地同步D] 异常: " + e.getMessage());
                    }
                }

                // 自调度下一轮（15~25秒，错峰）
                scheduleTimerD(calcStaggeredDelay(TIMER_D, 15, 25));
            }
        }.runTaskLaterAsynchronously(plugin, ticks);
    }

    /**
     * 定时器E — 用户组续费轮询（20~30秒，独立防SQL锁）
     * ★ 拉取PHP发起的续费请求，随玩家离线自动关停
     */
    private void scheduleTimerE(long ticks) {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                synchronized (scheduleLock) { lastRunTimestamps[TIMER_E] = now; }

                // ★★★ Web通信未启用时跳过所有轮询（支持运行时关闭）
                // ★ 配置热重载：每轮开头自动感知 插件设置.txt 变更（启用开关 / 地址 / 密钥）
                //   放在 enabled 守卫【之前】：开关关着时也要能感知「运维把开关改回 true」
                hotReloadIfChanged();

                if (!enabled) {
                    scheduleTimerE(calcStaggeredDelay(TIMER_E, 20, 30));
                    return;
                }

                // ★ 全员下线暂停：跳过本轮工作但保持链存活（原实现会停死，且每轮打印 ASCII 噪音）
                if (timersBCPaused) {
                    scheduleTimerE(calcStaggeredDelay(TIMER_E, 20, 30));
                    return;
                }

                // ★ PHP锁库退避检查
                if (phpBusyUntil > now) {
                    scheduleTimerE(calcStaggeredDelay(TIMER_E, 20, 30));
                    return;
                }

                // 拉取PHP续费请求
                try {
                    pollGroupRenewRequests();
                } catch (Exception e) {
                    plugin.getLogger().warning("[续费轮询E] 异常: " + e.getMessage());
                }

                // ★ 到期提醒检查（每轮执行）
                try {
                    checkAndNotifyExpiringGroups();
                } catch (Exception e) {
                    plugin.getLogger().warning("[续费轮询E] 到期提醒异常: " + e.getMessage());
                }

                // ★ 自动清理过期用户组成员（每轮执行，确保不续费的玩家被移除）
                try {
                    UserGroupManager ugmCheck = plugin.getUserGroup();
                    if (ugmCheck != null) {
                        java.util.List<java.util.Map<String, Object>> expired = ugmCheck.checkAndRemoveExpired();
                        if (!expired.isEmpty()) {
                            plugin.getLogger().info("[续费轮询E] 自动清理 " + expired.size() + " 个过期用户组成员");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[续费轮询E] 过期清理异常: " + e.getMessage());
                }

                // 自调度下一轮（20~30秒，错峰）
                scheduleTimerE(calcStaggeredDelay(TIMER_E, 20, 30));
            }
        }.runTaskLaterAsynchronously(plugin, ticks);
    }

    /**
     * 拉取PHP发起的用户组续费请求
     * PHP写入web_group_renew表（player_name, group_name, action=pending）
     * Java拉取后执行续费扣费，完成后回调PHP
     */
    private void pollGroupRenewRequests() {
        try {
            String endpoint = "api/land_api.php";
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("action", "poll_group_renews");
            params.put("secret", secretKey);
            String resp = httpGet(endpoint, params);
            if (resp == null || resp.isEmpty()) return;

            detectPhpBusy(resp);

            // 解析JSON: {"success":true, "renews":[{player_name, group_name, req_id}]}
            if (!resp.contains("\"success\":true") || !resp.contains("\"renews\"")) return;

            // 提取renews数组
            int arrStart = resp.indexOf("\"renews\":[");
            if (arrStart < 0) return;
            arrStart += 10;
            int arrEnd = resp.indexOf("]", arrStart);
            if (arrEnd < 0) return;
            String arr = resp.substring(arrStart, arrEnd).trim();
            if (arr.isEmpty() || arr.equals("null")) return;

            // 逐个处理（简单JSON解析）
            String[] items = arr.split("\\},\\s*\\{");
            for (String item : items) {
                item = item.replaceAll("[\\{\\}]", "").trim();
                if (item.isEmpty()) continue;

                String playerName = extractJsonString(item, "player_name");
                String groupName = extractJsonString(item, "group_name");
                String reqId = extractJsonString(item, "req_id");

                if (playerName == null || groupName == null || reqId == null) continue;

                // 执行续费
                UserGroupManager ugm = plugin.getUserGroup();
                if (ugm == null) continue;

                String err = ugm.renewGroup(playerName, groupName);

                // 回调PHP（使用GET更可靠）
                String callbackAction = (err == null) ? "renew_group_callback" : "renew_group_callback";
                String result = (err == null) ? "success" : "failed:" + err;
                java.util.Map<String, String> callbackParams = new java.util.LinkedHashMap<>();
                callbackParams.put("secret", secretKey);
                callbackParams.put("req_id", reqId);
                callbackParams.put("result", result);

                try {
                    String callbackResp = httpGet("api/land_api.php", callbackParams);
                    detectPhpBusy(callbackResp);
                } catch (Exception cbEx) {
                    plugin.getLogger().warning("[续费轮询E] 回调PHP失败: " + cbEx.getMessage());
                }

                plugin.getLogger().info("[续费轮询E] 处理续费请求: " + playerName + " → " + groupName + " → " + (err == null ? "成功" : "失败: " + err));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[续费轮询E] pollGroupRenewRequests异常: " + e.getMessage());
        }
    }

    /**
     * 检查即将到期的用户组并发送提醒
     * 10分钟内到期 → 提醒，5分钟内到期且自动续费 → 尝试扣费
     */
    private void checkAndNotifyExpiringGroups() {
        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm == null) return;

        List<Map<String, Object>> expiring = ugm.getExpiringGroups();
        long now = System.currentTimeMillis();

        for (Map<String, Object> g : expiring) {
            String player = (String) g.get("player");
            String group = (String) g.get("group");
            long expiry = (long) g.get("expiry");
            boolean groupAutoRenew = (boolean) g.get("autoRenew"); // 组配置默认值
            // 获取玩家个人自动续费偏好（覆盖组配置）
            boolean autoRenew = ugm.getPlayerAutoRenew(player, group);

            long remaining = expiry - now;
            if (remaining <= 0) continue;

            // 在线玩家发送提醒
            Player onlinePlayer = plugin.getServer().getPlayerExact(player);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                UserGroupManager.UserGroupConfig cfg = ugm.getGroupConfig(group);
                String displayName = cfg != null && !cfg.displayName.isEmpty() ? cfg.displayName : group;
                int minutes = (int) (remaining / 60000);

                if (minutes <= 1 && autoRenew) {
                    // 1分钟内到期，尝试自动续费
                    boolean renewed = ugm.autoRenewGroup(player, group);
                    if (!renewed) {
                        onlinePlayer.sendMessage("§c§l自动续费失败！ §e用户组 " + displayName + " §c将在 " + minutes + " 分钟后到期");
                        onlinePlayer.sendMessage("§7请使用 §e/group renew " + group + " §7手动续费");
                    }
                } else if (minutes <= 5) {
                    // 5分钟内到期
                    onlinePlayer.sendMessage("§e§l紧急提醒: §f用户组 §e" + displayName + " §f将在 §c" + minutes + " 分钟 §f后到期！");
                    if (autoRenew && cfg != null && cfg.renewPrice > 0) {
                        onlinePlayer.sendMessage("§7自动续费将在到期时执行，扣费 §e" + cfg.renewPrice + " 张债券");
                    } else {
                        onlinePlayer.sendMessage("§7使用 §e/group renew " + group + " §7手动续费");
                    }
                } else if (minutes <= 10) {
                    // 10分钟内到期
                    onlinePlayer.sendMessage("§e提醒: 用户组 §e" + displayName + " §7将在 §c" + minutes + " 分钟 §7后到期");
                    if (autoRenew && cfg != null && cfg.renewPrice > 0) {
                        onlinePlayer.sendMessage("§7已开启自动续费");
                    } else {
                        onlinePlayer.sendMessage("§7使用 §e/group renew " + group + " §7续费");
                    }
                }
            }
        }
    }

    // ==================== 高频轮询器失败计数 ====================

    /** 交易轮询连续失败计数 */
    private volatile int txPollFailCount = 0;
    /** 登录确认轮询连续失败计数 */
    private volatile int loginPollFailCount = 0;
    /** 注册轮询连续失败计数 */
    private volatile int registerPollFailCount = 0;
    /** 最后一次日志记录时间（限频） */
    private volatile long lastTxPollLogTime = 0;
    private volatile long lastLoginPollLogTime = 0;
    private volatile long lastRegisterPollLogTime = 0;
    private static final long POLL_LOG_INTERVAL = 60000; // 同类错误最多1分钟打一次

    // ==================== 库存高频轮询 ====================

    /** 上次检测到的库存修改时间戳 */
    private volatile long lastKnownShopStockModified = 0;
    /** 连续失败计数 */
    private volatile int shopStockPollFailCount = 0;
    private static final int SHOP_STOCK_POLL_FAIL_THRESHOLD = 10;
    /** 防重入锁：pullShopStock执行期间不重复拉取 */
    private volatile boolean shopStockPulling = false;

    /**
     * 库存高频轮询：每15秒(±5)请求Web端库存变更检测接口
     * 无改动跳过，有改动立即拉取完整库存并更新游戏
     * 60~90秒的全量同步定时器作为兜底
     */
    /**
     * 检查Web端库存变更（由合并定时器B调用）
     */
    private void doShopStockPollCheck() {
        if (!initialSyncComplete) return;
        try {
            if (shopStockPulling) return;
            if (shopStockPollFailCount >= SHOP_STOCK_POLL_FAIL_THRESHOLD) {
                if (shopStockPollFailCount % 12 != 0) {
                    shopStockPollFailCount++;
                    return;
                }
            }

            String urlStr = webBaseUrl + "/api/sync.php?action=check_shop_stock_changed&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8")
                    + "&last_modified=" + lastKnownShopStockModified;

            String resp = doGet(urlStr);
            if (resp == null) {
                long now = System.currentTimeMillis();
                if (now - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                    warnSlow("合并B库存", "[合并B-库存] GET失败 (连续失败" + shopStockPollFailCount + "次)");
                    lastTxPollLogTime = now;
                }
                shopStockPollFailCount++;
                return;
            }

            if (resp.contains("\"database is locked\"")) {
                shopStockPollFailCount = 0;
                return;
            }
            if (!resp.contains("\"success\":true")) {
                plugin.getLogger().warning("[合并B-库存] PHP返回非success: " + resp.substring(0, Math.min(200, resp.length())));
                shopStockPollFailCount++;
                return;
            }

            shopStockPollFailCount = 0;

            boolean changed = resp.contains("\"changed\":true") || resp.contains("\"changed\": true");

            if (changed) {
                long serverLastModified = 0;
                int lmIdx = resp.indexOf("\"last_modified\":");
                if (lmIdx >= 0) {
                    lmIdx += 16;
                    int lmEnd = resp.indexOf(",", lmIdx);
                    if (lmEnd < 0) lmEnd = resp.indexOf("}", lmIdx);
                    if (lmEnd > lmIdx) {
                        try {
                            serverLastModified = Long.parseLong(
                                    resp.substring(lmIdx, lmEnd).trim());
                        } catch (NumberFormatException e) { /* 忽略 */ }
                    }
                }

                if (stockFastPollTaskPending.compareAndSet(false, true)) {
                    final long finalServerLastModified = serverLastModified;
                    submitNormalDbTask("合并B-库存拉取", () -> {
                        shopStockPulling = true;
                        try {
                            boolean applied = pullShopStockSync();
                            if (applied && finalServerLastModified > 0) {
                                lastKnownShopStockModified = finalServerLastModified;
                            }
                        } finally {
                            shopStockPulling = false;
                            stockFastPollTaskPending.set(false);
                        }
                    });
                }
            }

        } catch (Exception e) {
            shopStockPollFailCount++;
        }
    }

    /**
     * 同步拉取库存（阻塞调用，用于高频轮询器内避免嵌套异步）
     * 逻辑与pullShopStock相同，但不在BukkitRunnable内执行
     * @return 是否成功应用了库存更新
     */
    private boolean pullShopStockSync() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=pull_shop_stock&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");

            String resp = doGet(urlStr);
            if (resp == null) {
                plugin.getLogger().warning("[库存高频] pull_shop_stock GET失败");
                return false;
            }

            if (!resp.contains("\"success\":true")) {
                plugin.getLogger().warning("[库存高频] pull_shop_stock 返回失败");
                return false;
            }

            int dataStart = resp.indexOf("\"items\":");
            if (dataStart < 0) {
                plugin.getLogger().warning("[库存高频] pull_shop_stock 无items字段");
                return false;
            }
            String dataStr = resp.substring(dataStart + 8);
            int arrEnd = findMatchingBracket(dataStr, 0);
            if (arrEnd < 0) {
                plugin.getLogger().warning("[库存高频] pull_shop_stock JSON解析失败");
                return false;
            }
            String arrStr = dataStr.substring(0, arrEnd + 1);

            boolean applied = updateLocalShopStock(arrStr);
            // ★ PHP端pullShopStock已自动清除admin_stock，无需再调clearAdminStock()
            return applied;

        } catch (Exception e) {
            plugin.getLogger().warning("[库存高频] 拉取库存异常: " + e.getMessage());
            return false;
        }
    }

    // ==================== CDK验证轮询 ====================

    /**
     * 每2秒检查PHP端的CDK验证请求，本地验证后推送结果回去
     * 让Web前端的CDK兑换可以验证Java端(bond.db)中的CDK
     * 同时拉取sdf1插件的pending远程验证请求，发送到Web后端
     */
    // ★ sdf1远程CDK验证失败重试缓存：key=requestId, value={requestId,code,player,attempts}
    //   只有「明确查到CDK不存在」才算 not_found；HTTP失败/锁库/解析不出字段一律进重试，
    //   避免远程明明匹配到(已消耗/可用)却因瞬时故障回给sdf1一个假的 not_found(未匹配)。
    private final java.util.concurrent.ConcurrentHashMap<String, String[]> sdf1CdkRetries =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SDF1_CDK_MAX_RETRY = 5;

    // CDK验证方法(pullWebCdkRequestsAndValidate/pullSdf1PendingAndValidateWeb)由合并定时器B直接调用
    // ==================== 领地数据即时同步 ====================

    /**
     * ★ 领地数据即时同步：每15秒(±5)从PHP拉取最新领地配置
     * 配合60~90秒全量同步作为兜底
     */
    private volatile boolean landSyncPulling = false;
    private volatile int landSyncFailCount = 0;
    private static final int LAND_SYNC_FAIL_THRESHOLD = 10;

    /**
     * 领地配置轮询（由合并定时器C调用）
     */
    private void doLandSyncPoll() {
        try {
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            if (landSyncPulling) return;
            if (landSyncFailCount >= LAND_SYNC_FAIL_THRESHOLD) {
                if (landSyncFailCount % 6 != 0) {
                    landSyncFailCount++;
                    return;
                }
            }

            landSyncPulling = true;
            try {
                String url = webBaseUrl + "/api/land_api.php?action=get_config&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                String json = doGet(url);
                if (json != null) {
                    if (json.contains("database is locked")) {
                        landSyncFailCount = 0;
                        return;
                    }
                    if (json.contains("\"success\":true")) {
                        landSyncFailCount = 0;
                        if (plugin.areaProtection != null) {
                            plugin.areaProtection.reloadAreaConfigFromDb();
                        }
                    } else {
                        landSyncFailCount++;
                    }
                } else {
                    landSyncFailCount++;
                }
            } finally {
                landSyncPulling = false;
            }
        } catch (Exception e) {
            landSyncFailCount++;
        }
    }

    /**
     * Part 1: Web端写cdk_validate_requests → Sdf1_login拉取 → 本地CDKManager.redeem → 写回结果
     */
    private void pullWebCdkRequestsAndValidate() {
        try {
            String listUrl = webBaseUrl + "/api/sync.php?action=pull_cdk_validate_requests&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(listUrl);
            if (json == null) return;
            // ★ 锁库检测：PHP返回database is locked时不计为"成功响应"，但也不刷屏
            if (json.contains("database is locked")) {
                return;
            }
            if (!json.contains("\"success\":true")) return;

            int reqIdx = json.indexOf("\"requests\":[");
            if (reqIdx < 0) return;
            int arrStart = json.indexOf('[', reqIdx);
            int arrEnd = json.indexOf(']', arrStart);
            if (arrEnd < 0) return;
            String arr = json.substring(arrStart, arrEnd + 1);
            if (arr.equals("[]")) return;

            int pi = 0;
            while (pi < arr.length()) {
                int objStart = arr.indexOf('{', pi);
                if (objStart < 0) break;
                int objEnd = arr.indexOf('}', objStart);
                if (objEnd < 0) break;
                String obj = arr.substring(objStart, objEnd + 1);
                pi = objEnd + 1;

                String requestId = extractJsonStr(obj, "request_id");
                String cdkCode = extractJsonStr(obj, "code");
                String playerName = extractJsonStr(obj, "player_name");
                if (requestId.isEmpty() || cdkCode.isEmpty()) continue;
                if (playerName.isEmpty()) playerName = "web_remote";

                // ★ 直接检查sdf1计分板CDK（bond.db只是钱包，不存CDK）
                String status = "not_found";
                int amount = 0;
                try {
                    Object sdf1Plugin = Bukkit.getPluginManager().getPlugin("sdf1");
                    if (sdf1Plugin != null) {
                        plugin.getLogger().info("[CDK远程验证] 调用sdf1检查计分板: " + cdkCode);
                        java.lang.reflect.Method checkMethod = sdf1Plugin.getClass().getMethod("checkScoreBoardCdk", String.class);
                        String[] sbResult = (String[]) checkMethod.invoke(sdf1Plugin, cdkCode);
                        plugin.getLogger().info("[CDK远程验证] sdf1返回: " + (sbResult != null ? String.join(",", sbResult) : "null"));
                        if (sbResult != null && sbResult.length >= 2 && "success".equals(sbResult[0])) {
                            status = "success";
                            amount = Integer.parseInt(sbResult[1]);
                            plugin.getLogger().info("[CDK远程验证] 计分板CDK匹配: " + cdkCode + " 金额=" + amount);

                            // ★ 关键修复：sdf1的checkScoreBoardCdk已删除口令但不加债券
                            // 这里直接加债券，确保核销和到账原子性
                            if (amount > 0 && !"web_remote".equals(playerName)) {
                                try {
                                    int bef = plugin.getBondManager().getBonds(playerName);
                                    plugin.getBondManager().addBonds(playerName, amount, "cdk_redeem_web", cdkCode, "Web系统", "CDK远程兑换: " + cdkCode);
                                    int aft = plugin.getBondManager().getBonds(playerName);
                                    plugin.getLogger().info("[CDK远程验证] 直接加债券: " + playerName + " +" + amount + " (" + bef + "->" + aft + ")");

                                    // ★ 修复：远程CDK加债券后立即推送余额到PHP
                                    try {
                                        lastBondBalanceHash = "";
                                        syncBondBalances();
                                    } catch (Exception ex) { /* 静默 */ }

                                    // 通知在线玩家
                                    Player targetPlayer = Bukkit.getPlayerExact(playerName);
                                    if (targetPlayer != null && targetPlayer.isOnline()) {
                                        targetPlayer.sendMessage("§6[债券] §aCDK兑换成功！§f +§a" + amount + "§f 债券");
                                        targetPlayer.sendMessage("§6[债券] §f余额: §e" + bef + " §7→ §a" + aft);
                                    }
                                } catch (Exception bondEx) {
                                    plugin.getLogger().warning("[CDK远程验证] 直接加债券失败: " + bondEx.getMessage());
                                }
                            }
                        } else if (sbResult != null && "not_bond".equals(sbResult[0])) {
                            status = "not_bond";
                            plugin.getLogger().info("[CDK远程验证] 计分板CDK存在但非债券类型: " + cdkCode);
                        } else {
                            plugin.getLogger().info("[CDK远程验证] 计分板CDK未找到: " + cdkCode);
                        }
                    } else {
                        plugin.getLogger().warning("[CDK远程验证] sdf1插件未加载");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[CDK远程验证] 检查计分板异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }

                // 推送结果回PHP
                String pushUrl = webBaseUrl + "/api/sync.php?action=push_cdk_validate_result&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8")
                        + "&request_id=" + java.net.URLEncoder.encode(requestId, "UTF-8")
                        + "&code=" + java.net.URLEncoder.encode(cdkCode, "UTF-8")
                        + "&status=" + java.net.URLEncoder.encode(status, "UTF-8")
                        + "&amount=" + amount;
                doGet(pushUrl);

                if (!"not_found".equals(status)) {
                    plugin.getLogger().info("[CDK远程验证] " + cdkCode + " → " + status + " player=" + playerName + (amount > 0 ? " 金额:" + amount : ""));
                }
            }
        } catch (Exception e) {
            // 静默
        }
    }

    /**
     * Part 2: sdf1插件pending队列 → Sdf1_login拉取 → 发送Web validate_cdk → 结果回传sdf1
     */
    private void pullSdf1PendingAndValidateWeb() {
        try {
            // 通过反射获取sdf1插件（避免直接依赖Plugin类）
            Object sdf1Plugin = Bukkit.getPluginManager().getPlugin("sdf1");
            if (sdf1Plugin == null) return;
            java.lang.reflect.Method isEnabled = sdf1Plugin.getClass().getMethod("isEnabled");
            if (!(Boolean) isEnabled.invoke(sdf1Plugin)) return;

            // ★ 先收走上一轮的重试项（带已重试次数），再拉新一轮pending
            java.util.List<String[]> items = new java.util.ArrayList<>();
            for (String rk : new java.util.ArrayList<>(sdf1CdkRetries.keySet())) {
                String[] rv = sdf1CdkRetries.remove(rk);
                if (rv != null) items.add(rv);
            }

            java.lang.reflect.Method pullMethod = sdf1Plugin.getClass().getMethod("pullPendingCdkValidations");
            Object[][] pendingList = (Object[][]) pullMethod.invoke(sdf1Plugin);
            if (pendingList != null) {
                for (Object[] it : pendingList) {
                    items.add(new String[]{(String) it[0], (String) it[1], (String) it[2], "0"});
                }
            }
            if (items.isEmpty()) return;

            for (String[] item : items) {
                String requestId = item[0];
                String cdkCode = item[1];
                String playerName = item[2];
                int attempts = 0;
                try { attempts = Integer.parseInt(item[3]); } catch (Exception ignore) { attempts = 0; }

                String status = "not_found";
                int amount = 0;
                boolean transientFail = false;   // 瞬时故障 -> 可重试
                try {
                    String validateUrl = webBaseUrl + "/api/sync.php?action=check_cdk_exists&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8")
                            + "&code=" + java.net.URLEncoder.encode(cdkCode, "UTF-8");
                    String vJson = doGet(validateUrl);
                    if (vJson == null) {
                        transientFail = true;
                        plugin.getLogger().warning("[CDK-Web验证] check_cdk_exists GET失败 CDK=" + cdkCode);
                    } else if (vJson.contains("database is locked")) {
                        transientFail = true;
                    } else {
                        plugin.getLogger().info("[CDK-Web验证] PHP原始返回: " + vJson);
                        String found = extractJsonStr(vJson, "found");
                        String st = extractJsonStr(vJson, "status");
                        String am = extractJsonStr(vJson, "amount");
                        plugin.getLogger().info("[CDK-Web验证] 解析结果: found=" + found + " status=" + st + " amount=" + am);

                        boolean foundYes = "true".equalsIgnoreCase(found) || "1".equals(found);
                        String stl = st.toLowerCase();

                        if (foundYes && "available".equals(stl)) {
                            try {
                                amount = am.isEmpty() ? 0 : Integer.parseInt(am.trim());
                            } catch (Exception parseEx) {
                                amount = 0;
                            }
                            // CDK存在且可用，调用兑换API标记已使用+写流水
                            try {
                                String redeemUrl = webBaseUrl + "/api/sync.php?action=cdk_redeem_remote&secret="
                                        + java.net.URLEncoder.encode(secretKey, "UTF-8")
                                        + "&code=" + java.net.URLEncoder.encode(cdkCode, "UTF-8")
                                        + "&player=" + java.net.URLEncoder.encode(playerName, "UTF-8");
                                String rJson = doGet(redeemUrl);
                                if (rJson == null || rJson.contains("database is locked")) {
                                    transientFail = true;
                                    plugin.getLogger().warning("[CDK-Web验证] cdk_redeem_remote 瞬时失败，稍后重试 CDK=" + cdkCode);
                                } else {
                                    plugin.getLogger().info("[CDK-Web验证] 兑换结果: " + rJson);
                                    String rSt = extractJsonStr(rJson, "status").toLowerCase();
                                    String rAmt = extractJsonStr(rJson, "amount");
                                    if ("success".equals(rSt)) {
                                        status = "success";
                                        try { if (!rAmt.isEmpty()) amount = Integer.parseInt(rAmt.trim()); } catch (Exception ignore) {}
                                    } else if ("already_used".equals(rSt)) {
                                        status = "already_used";
                                    } else {
                                        // 兑换接口回了意料之外的东西：按瞬时故障重试，别直接判死
                                        transientFail = true;
                                    }
                                }
                            } catch (Exception e) {
                                transientFail = true;
                                plugin.getLogger().warning("[CDK-Web验证] 兑换请求失败: " + e.getMessage());
                            }
                        } else if (foundYes && ("already_used".equals(stl) || "used".equals(stl) || "consumed".equals(stl))) {
                            // ★ 远程已匹配到但已消耗 -> 必须回 already_used，绝不能落进 not_found
                            status = "already_used";
                        } else if (!found.isEmpty() && "false".equalsIgnoreCase(found)) {
                            // 明确查到「CDK不存在」，这才是真正的未匹配
                            status = "not_found";
                            plugin.getLogger().info("[CDK-Web验证] CDK " + cdkCode + " 在Web端不存在");
                        } else {
                            // found/status 都没解析出来：PHP返回了错误页/嵌套结构异常 -> 可重试
                            transientFail = true;
                            plugin.getLogger().warning("[CDK-Web验证] CDK " + cdkCode + " 返回无法解析，稍后重试");
                        }
                    }
                } catch (Exception e) {
                    transientFail = true;
                    plugin.getLogger().warning("[CDK-Web验证] 请求失败: " + e.getMessage());
                }

                if (transientFail && !"success".equals(status) && !"already_used".equals(status)) {
                    int next = attempts + 1;
                    if (next < SDF1_CDK_MAX_RETRY) {
                        sdf1CdkRetries.put(requestId, new String[]{requestId, cdkCode, playerName, String.valueOf(next)});
                        plugin.getLogger().info("[CDK-Web验证] CDK=" + cdkCode + " 第" + next + "次重试排队");
                        continue;   // 本次不回传，sdf1侧继续等待
                    }
                    plugin.getLogger().warning("[CDK-Web验证] CDK=" + cdkCode + " 重试" + next + "次仍失败，按未匹配回传");
                }

                // 回传结果给sdf1
                java.lang.reflect.Method setResult = sdf1Plugin.getClass().getMethod(
                        "setCdkValidationResult", String.class, String.class, int.class);
                setResult.invoke(sdf1Plugin, requestId, status, amount);

                if (!"not_found".equals(status)) {
                    plugin.getLogger().info("[CDK-Web验证] " + cdkCode + " → " + status + " player=" + playerName
                            + (amount > 0 ? " 金额:" + amount : ""));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CDK-Web验证] 拉取sdf1队列异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }


    // ==================== Token同步到Web ====================

    /**
     * 将本地生成的Token批量注册到PHP数据库
     * 使用SECRET_KEY认证，不需要token（解决鸡生蛋问题）
     */
    private boolean syncTokensToWeb(List<String[]> tokens) {
        if (tokens == null || tokens.isEmpty()) return false;
        // 同步执行，确保token注册到PHP后端
        try {
            StringBuilder jsonArr = new StringBuilder("[");
            boolean first = true;
            for (String[] t : tokens) {
                if (!first) jsonArr.append(",");
                first = false;
                jsonArr.append("{");
                jsonArr.append("\"token\":\"").append(escapeJson(t[0])).append("\",");
                jsonArr.append("\"player\":\"").append(escapeJson(t[1])).append("\",");
                jsonArr.append("\"purpose\":\"").append(escapeJson(t[2])).append("\",");
                jsonArr.append("\"expire_seconds\":").append(tokenExpireSeconds);
                jsonArr.append("}");
            }
            jsonArr.append("]");

            String jsonBody = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"tokens\":" + jsonArr + "}";

            String resp = doPost(webBaseUrl + "/api/sync.php?action=receive_token", jsonBody);
            // ★ 只有 PHP 明确 success=true 才算注册成功（供 system/sync token 复用判定，防假成功）
            boolean ok = resp != null && resp.contains("\"success\":true");
            if (ok) {
                plugin.getLogger().info("[Web通信] Token注册成功");
            }
            return ok;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] Token注册失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        }
    }

    /** system/sync 复用 token（见 generateAndSyncToken 注释）：只对 sync.php 系列动作安全 */
    private volatile String cachedSystemSyncToken;
    private volatile long cachedSystemSyncTokenUntil;      // 软到期：到点就该换新，但旧的 PHP 仍然认
    private volatile long cachedSystemSyncTokenHardUntil;  // 硬到期：PHP 端真的过期了，必须同步取新的
    private final java.util.concurrent.atomic.AtomicBoolean syncTokenRefreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 包装方法：生成token并自动同步到Web
     *
     * @return 生成的token
     */
    private String generateAndSyncToken(String playerName, String purpose) {
        // ★ system/sync 专用 token 复用：sync.php 系列动作只 validateToken、不消耗 token
        //   （PHP 端 consume 只在 shop/cdk/balance/register 等玩家用途上），
        //   同一枚在有效期内可反复用。一次同步任务里「对账 + 推送」原本要打 2 次 receive_token，
        //   慢链路（CF 25% 请求 >10s）下这 2 次就能吃掉整个 10 秒预算。
        //   ★ 只缓存 system/sync；玩家用途（cdk/bond/register/webshop）是一次性消耗的，绝不缓存。
        //   ★ 2026-10-06 软/硬双到期：软到期只触发【异步】续期、手里的旧 token 继续用
        //     （到硬到期前 PHP 仍认它）；只有硬到期才在当前线程同步取新。
        //     这样 receive_token 永远不会在 DB 队列里吃掉 10 秒硬预算 ——
        //     日志实证：receive_token 卡 9 秒 → 同任务的 check_alignment 只剩 1 秒 → 对账必失败。
        if ("system".equals(playerName) && "sync".equals(purpose)) {
            String cached = cachedSystemSyncToken;
            long now = System.currentTimeMillis();
            if (cached != null && now < cachedSystemSyncTokenUntil) {
                return cached;                       // 新鲜，直接用
            }
            if (cached != null && now < cachedSystemSyncTokenHardUntil) {
                scheduleSyncTokenRefresh();          // 异步补一张，别卡 DB 队列
                return cached;                       // 旧的还没到 PHP 端过期时间，照样能用
            }
        }
        String token = generateToken(playerName, purpose);
        List<String[]> batch = new ArrayList<>();
        batch.add(new String[]{token, playerName, purpose});
        boolean pushed = syncTokensToWeb(batch);
        if (pushed && "system".equals(playerName) && "sync".equals(purpose)) {
            rememberSyncToken(token);
        }
        return token;
    }

    /**
     * 记录 system/sync token 的两个到期点：
     *   软到期 = 有效期 70%（默认600秒 → 420秒；原来是 60 秒，刷新频率直接降 7 倍）
     *   硬到期 = 有效期 90%（540秒，给 PHP/Java 时钟偏差留 60 秒余量）
     */
    private void rememberSyncToken(String token) {
        long lifeMs = (long) tokenExpireSeconds * 1000L;
        long now = System.currentTimeMillis();
        cachedSystemSyncToken = token;
        cachedSystemSyncTokenUntil = now + Math.max(30000L, lifeMs * 7 / 10);
        cachedSystemSyncTokenHardUntil = now + Math.max(60000L, lifeMs * 9 / 10);
    }

    /**
     * 异步补一张 system/sync token —— 放在 webExecutor 里跑，绝不在 DB 队列线程上等。
     * 刷新失败也无所谓：硬到期前旧 token 仍有效，下一轮会再补（syncTokenRefreshing 防并发重复刷）。
     */
    private void scheduleSyncTokenRefresh() {
        if (!syncTokenRefreshing.compareAndSet(false, true)) return;
        try {
            webExecutor.execute(() -> {
                try {
                    if (!isEnabled()) return;
                    String token = generateToken("system", "sync");
                    List<String[]> batch = new ArrayList<>();
                    batch.add(new String[]{token, "system", "sync"});
                    if (syncTokensToWeb(batch)) {
                        rememberSyncToken(token);
                        plugin.getLogger().info("[Web通信] system/sync token 已异步续期");
                    }
                } catch (Throwable t) {
                    // 刷新失败不影响本轮：手里的旧 token 到硬到期前都还能用
                } finally {
                    syncTokenRefreshing.set(false);
                }
            });
        } catch (Throwable t) {
            syncTokenRefreshing.set(false);
        }
    }

    // ==================== HTTP请求 ====================

    /**
     * 发送GET请求
     */
    public String httpGet(String endpoint, Map<String, String> params) {
        try {
            StringBuilder urlStr = new StringBuilder(webBaseUrl + "/" + endpoint);
            if (params != null && !params.isEmpty()) {
                urlStr.append("?");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    urlStr.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8"));
                    urlStr.append("=");
                    urlStr.append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
                    urlStr.append("&");
                }
                urlStr.setLength(urlStr.length() - 1);
            }

            String result = doGet(urlStr.toString());
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] GET请求异常: " + endpoint + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 发送POST请求（JSON body）
     */
    public String httpPost(String endpoint, String jsonBody) {
        return httpPostWithRetry(endpoint, jsonBody, 3);
    }
    
    /**
     * HTTP POST 带重试机制（处理 SSL 握手失败和网络超时）
     */
    private String httpPostWithRetry(String endpoint, String jsonBody, int maxRetries) {
        String urlStr = webBaseUrl + "/" + endpoint;
        return doPostWithRetry(urlStr, jsonBody, maxRetries);
    }

/**
     * 推送 2FA 二维码载荷给 PHP 展示端（PHP 只存+渲染，不做任何 2FA 校验）
     *
     * @param token      玩家读取载荷用的一次性随机 token
     * @param player     玩家名
     * @param otpauthUri otpauth://totp/... 完整载荷
     * @return 成功返回玩家可访问的二维码页 URL；未启用/失败返回 null
     */
    public String pushTwoFactorQr(String token, String player,
                                  String otpauthUri) {
        if (!isEnabled()) return null;
        try {
            JsonObject json = new JsonObject();
            json.addProperty("secret", secretKey);
            json.addProperty("token", token);
            json.addProperty("player", player);
            json.addProperty("otpauth", otpauthUri);
            String resp = httpPost(
                    "api/twofa.php?action=push_qr", json.toString());
            if (resp != null && resp.contains("\"success\":true")) {
                return webBaseUrl + "/twofa_qr.php?t=" + token;
            }
            plugin.getLogger().warning("[2FA] PHP拒绝二维码载荷: " + resp);
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("[2FA] 推送二维码失败: "
                    + e.getMessage());
            return null;
        }
    }

    /**
     * 发送POST请求（带token）
     */
    public String httpPostWithToken(String endpoint, String token, Map<String, Object> data) {
        return httpPostWithTokenWithRetry(endpoint, token, data, 3);
    }
    
    /**
     * HTTP POST with Token 带重试机制
     */
    private String httpPostWithTokenWithRetry(String endpoint, String token, Map<String, Object> data, int maxRetries) {
        try {
            StringBuilder urlStr = new StringBuilder(webBaseUrl + "/" + endpoint);
            if (token != null) {
                urlStr.append(urlStr.indexOf("?") >= 0 ? "&" : "?");
                urlStr.append("token=").append(java.net.URLEncoder.encode(token, "UTF-8"));
            }

            Map<String, Object> bodyWithSecret = new LinkedHashMap<>(data);
            bodyWithSecret.put("secret", secretKey);

            String json = mapToJson(bodyWithSecret);
            plugin.getLogger().info("[Web通信] POST+Token请求: " + endpoint + ", secret长度=" + (secretKey != null ? secretKey.length() : 0) + ", body长度=" + json.length());
            return doPostWithRetry(urlStr.toString(), json, maxRetries);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] POST+Token请求最终失败: " + endpoint + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * ★ 阶段E：交易对账/推送专用「单次请求」——15 秒超时、不内部重试。
     *   PHP 必须在 15 秒内给答复（成功 / 失败 / 报错都算答复）；拿不到 body 才算「无答复」。
     *   2xx 与非 2xx 一律把 body 交回调用方解析：PHP 的 JSON 就是回执，不装哑巴。
     */
    private String doPostOnce(String urlStr, String jsonBody) {
        if (!isEnabled()) return null;
        for (int attempt = 1; attempt <= 1 + CONNECT_RETRY_MAX; attempt++) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .timeout(Duration.ofSeconds(TX_SYNC_HTTP_TIMEOUT_S))
                    .header("User-Agent", "Sdf1-WebManager/2.9")
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendHard("POST交易", req, cfHttpClient);
            String body = resp.body();
            detectPhpBusy(body);
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                plugin.getLogger().warning("[Web交易同步] PHP 答复 HTTP " + resp.statusCode()
                        + "（" + (body == null ? 0 : body.length()) + " 字节），交由上层解析判定");
            }
            return body;
        } catch (java.net.http.HttpTimeoutException te) {
            if (te instanceof java.net.http.HttpConnectTimeoutException
                    && attempt < 1 + CONNECT_RETRY_MAX) {
                // 握手没完成就断了 → 请求根本没发出去，换连接立刻重试
                infoThrottled("连接重试", "[Web交易同步] 连接阶段失败，换连接重试");
                continue;
            }
            warnSlow("交易无答复", "[Web交易同步] ★ " + TX_SYNC_HTTP_TIMEOUT_S
                    + " 秒内没有答复（请求超时）→ 按无答复处理");
            return null;
        } catch (Exception e) {
            if (e instanceof HttpHardTimeoutException) {
                // 硬超时让位：sendHard 已打过节流日志
                infoThrottled("硬超时让位", "[Web通信] 硬超时让位: " + e.getMessage());
                return null;
            }
            if (attempt < 1 + CONNECT_RETRY_MAX && isConnectStageFailure(e)) {
                infoThrottled("连接重试", "[Web交易同步] 连接阶段失败(" + e.getClass().getSimpleName() + ")，换连接重试");
                continue;
            }
            warnSlow("交易无答复", "[Web交易同步] ★ 无答复（" + e.getClass().getSimpleName()
                    + "）: " + e.getMessage());
            return null;
        }
        }
        return null;
    }

    /** 单次 POST + Token（15 秒必答复，见 doPostOnce），交易对账/推送/收回统一走这里 */
    private String httpPostWithTokenOnce(String endpoint, Map<String, Object> data) {
        try {
            String token = generateAndSyncToken("system", "sync");
            StringBuilder urlStr = new StringBuilder(webBaseUrl + "/" + endpoint);
            urlStr.append(urlStr.indexOf("?") >= 0 ? "&" : "?");
            urlStr.append("token=").append(java.net.URLEncoder.encode(token, "UTF-8"));
            Map<String, Object> bodyWithSecret = new LinkedHashMap<>(data);
            bodyWithSecret.put("secret", secretKey);
            String json = mapToJson(bodyWithSecret);
            plugin.getLogger().info("[Web交易同步] 单次请求: " + endpoint + ", body长度=" + json.length());
            return doPostOnce(urlStr.toString(), json);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 单次请求构造失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== JSON工具 ====================

    /**
     * 简单Map转JSON（不依赖第三方库）
     */
    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                sb.append(mapToJson((Map<String, Object>) v));
            } else if (v instanceof List) {
                sb.append(listToJson((List<?>) v));
            } else if (v instanceof Object[]) {
                sb.append(listToJson(Arrays.asList((Object[]) v)));
            } else {
                sb.append("\"").append(escapeJson(v.toString())).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String listToJson(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(",");
            first = false;
            if (item == null) {
                sb.append("null");
            } else if (item instanceof Map) {
                sb.append(mapToJson((Map<String, Object>) item));
            } else if (item instanceof Number || item instanceof Boolean) {
                sb.append(item);
            } else {
                sb.append("\"").append(escapeJson(item.toString())).append("\"");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String escapeUrl(String s) {
        if (s == null) return "";
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 简单JSON字符串值提取（不依赖第三方库）
     */
    private static String extractJsonStr(String json, String key) {
        if (json == null || key == null || key.isEmpty()) return "";
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int i = json.indexOf(needle, from);
            if (i < 0) return "";
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return "";
            int p = colon + 1;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length()) return "";
            char c = json.charAt(p);

            if (c == '"') {                       // 字符串值（支持转义）
                StringBuilder sb = new StringBuilder();
                int j = p + 1;
                while (j < json.length()) {
                    char ch = json.charAt(j);
                    if (ch == '\\' && j + 1 < json.length()) {
                        char nx = json.charAt(j + 1);
                        switch (nx) {
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u':
                                if (j + 5 < json.length()) {
                                    try {
                                        sb.append((char) Integer.parseInt(json.substring(j + 2, j + 6), 16));
                                        j += 6;
                                        continue;
                                    } catch (Exception e) { sb.append(nx); }
                                } else { sb.append(nx); }
                                break;
                            default: sb.append(nx); break;
                        }
                        j += 2;
                        continue;
                    }
                    if (ch == '"') break;
                    sb.append(ch);
                    j++;
                }
                return sb.toString();
            }

            if (c == '{' || c == '[') {           // 命中嵌套结构的同名键，跳过继续找
                from = p + 1;
                continue;
            }

            // ★ 未加引号的字面量：true / false / null / 数字
            //   （PHP success() 包装的布尔值、json_encode 的数字都走这里）
            int j = p;
            while (j < json.length() && ",}] \r\n\t ".indexOf(json.charAt(j)) < 0) j++;
            String lit = json.substring(p, j).trim();
            if (lit.isEmpty()) {
                from = p + 1;
                continue;
            }
            return lit;
        }
    }

    /**
     * 解码JSON中的 Unicode转义序列
     */
    private static String decodeUnicodeEscapes(String str) {
        if (str == null || !str.contains("\\u")) return str;
        try {
            StringBuilder sb = new StringBuilder(str.length());
            int i = 0;
            while (i < str.length()) {
                if (str.charAt(i) == '\\' && i + 5 < str.length() && str.charAt(i + 1) == 'u') {
                    try {
                        String hex = str.substring(i + 2, i + 6);
                        int codePoint = Integer.parseUnsignedInt(hex, 16);
                        sb.appendCodePoint(codePoint);
                        i += 6;
                    } catch (Exception e) {
                        sb.append(str, i, i + 2);
                        i += 2;
                    }
                } else {
                    sb.append(str.charAt(i));
                    i++;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return str;
        }
    }

    public static Map<String, Object> parseJson(String json) {
        // 简易JSON解析器，支持嵌套对象和数组
        Map<String, Object> result = new HashMap<>();
        if (json == null || json.isEmpty()) return result;

        // ★ 先解码Unicode转义序列 XXXX → 实际字符
        json = decodeUnicodeEscapes(json.trim());

        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

        int depth = 0;
        StringBuilder key = new StringBuilder();
        StringBuilder value = new StringBuilder();
        boolean inKey = true;
        boolean inString = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (c == '\\' && i + 1 < json.length()) {
                    value.append(c).append(json.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                    value.append(c);
                } else {
                    value.append(c);
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                value.append(c);
            } else if (c == ':' && depth == 0 && inKey) {
                inKey = false;
                key = new StringBuilder(value.toString().replaceAll("^\"|\"$", ""));
                value = new StringBuilder();
            } else if (c == ',' && depth == 0) {
                addParsedEntry(result, key.toString(), value.toString());
                key = new StringBuilder();
                value = new StringBuilder();
                inKey = true;
            } else if (c == '{' || c == '[') {
                depth++;
                value.append(c);
            } else if (c == '}' || c == ']') {
                depth--;
                value.append(c);
            } else {
                value.append(c);
            }
        }
        if (key.length() > 0 || value.length() > 0) {
            addParsedEntry(result, key.toString(), value.toString());
        }
        return result;
    }

    private static void addParsedEntry(Map<String, Object> map, String key, String value) {
        key = key.replaceAll("^\"|\"$", "").trim();
        if (key.isEmpty()) return;
        value = value.trim();

        if (value.equals("null")) {
            map.put(key, null);
            return;
        }
        if (value.equals("true")) {
            map.put(key, true);
            return;
        }
        if (value.equals("false")) {
            map.put(key, false);
            return;
        }

        // 尝试数字
        try {
            map.put(key, Integer.parseInt(value));
            return;
        } catch (NumberFormatException ignored) {
        }
        try {
            map.put(key, Double.parseDouble(value));
            return;
        } catch (NumberFormatException ignored) {
        }

        // JSON数组 → List<Map>
        if (value.startsWith("[") && value.endsWith("]")) {
            List<Object> list = parseJsonArray(value);
            map.put(key, list);
            return;
        }

        // ★ JSON嵌套对象 → 递归解析成 Map（2026-10-05 修复）
        //   缺陷：这里原本只有 [ 数组分支、漏了 { 对象分支，于是 {"data":{...}}
        //   的 data 被当字符串塞进结果 → 调用方 instanceof Map 恒 false。
        //   后果：fetchPhpTxAlignmentStats()/fetchMissingTxIds() 永远返回 null，
        //   启动对齐每次判「PHP 无响应」、水位线永不归零 → 历史流水永远补推不了，
        //   只能推水位线之后的实时交易（与 2026-10-05 测试服现象完全吻合）。
        //   注意：数组元素侧的 parseJsonArrayItem 早就有这个分支，只有顶层 value 漏了。
        if (value.startsWith("{") && value.endsWith("}")) {
            Map<String, Object> nested = parseJson(value);
            map.put(key, nested);
            return;
        }

        // 字符串（去掉引号）
        String cleaned = value.replaceAll("^\"|\"$", "")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\");
        map.put(key, cleaned);
    }

    /**
     * 解析JSON数组为List
     * 支持 [{...}, {...}] 和 ["a", "b"] 格式
     */
    private static List<Object> parseJsonArray(String arrStr) {
        List<Object> list = new ArrayList<>();
        arrStr = arrStr.trim();
        if (arrStr.length() < 2) return list;
        arrStr = arrStr.substring(1, arrStr.length() - 1); // 去掉 [ ]

        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < arrStr.length(); i++) {
            char c = arrStr.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < arrStr.length()) {
                    current.append(c).append(arrStr.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                    current.append(c);
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inString = true;
                    current.append(c);
                } else if (c == '{' || c == '[') {
                    depth++;
                    current.append(c);
                } else if (c == '}' || c == ']') {
                    depth--;
                    current.append(c);
                } else if (c == ',' && depth == 0) {
                    // 分隔符
                    String item = current.toString().trim();
                    if (!item.isEmpty()) {
                        list.add(parseJsonArrayItem(item));
                    }
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        String last = current.toString().trim();
        if (!last.isEmpty()) {
            list.add(parseJsonArrayItem(last));
        }
        return list;
    }

    private static Object parseJsonArrayItem(String item) {
        item = item.trim();
        if (item.startsWith("{") && item.endsWith("}")) {
            // 嵌套对象 → Map
            return parseJson(item);
        }
        if (item.startsWith("\"") && item.endsWith("\"")) {
            return item.substring(1, item.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\");
        }
        if (item.equals("null")) return null;
        if (item.equals("true")) return true;
        if (item.equals("false")) return false;
        try { return Integer.parseInt(item); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(item); } catch (NumberFormatException ignored) {}
        return item;
    }

    // ==================== 双向对账：多退少补（2026-10-05） ====================
    //
    // 改造前：全量同步每一轮都把各类数据整包推给 PHP；内存里的 lastXxxHash 一重启就失忆，
    //         于是「开服 12 项全推、每轮周期同步再推一遍」，PHP 端还叠着整表 DELETE 覆盖。
    // 改造后：每类数据先与 PHP「点一遍」——两边各算 key + 内容指纹，
    //             missing = Java 有 PHP 无        → Java 补推
    //             changed = 两边都有但内容不同    → 按「上次交换后谁改过」决定推还是收
    //             extra   = PHP 有 Java 无        → Java 收回来（pull 类别）
    //         三个差集全空 ⇒ 一条数据都不发。
    //
    // ★ 指纹算法必须与 PHP 的 alignmentRowHash() 逐字一致：
    //       按 hashcols 顺序取值 → 整数列统一成十进制字符串、绝对值超过 1e12 的时间戳一律折成秒
    //       → 用 chr(31)（ASCII 31 分隔符）连接 → md5
    //   （折秒这一条是关键：Java 存毫秒、PHP 存秒，不折就永远对不上，变成每轮都在推。）
    //
    // ★ 「谁改过」的判据 alignSeen（上次交换后已知一致的指纹）**必须落盘**：
    //   放内存里一重启就全丢，首轮会把所有 changed 误判成「Java 改过」，
    //   于是把 PHP 端刚改的密码 / 积分 / 余额反过来覆盖掉。

    /** 单个类别的对账配置（与 PHP alignmentCatConfig() 严格对应） */
    private static final class AlignCfg {
        final String cat;
        final String[] keycols;     // 主键列（DB 列名）
        final String[] hashcols;    // 参与指纹的列（DB 列名，顺序即优先级）
        final String[] intcols;     // 其中的整数列（要折秒/十进制化）
        final Map<String, String> map;   // DB 列名 -> Java 侧字段名
        final boolean pull;         // PHP 侧的行可否写回本地
        final boolean pushOnExtra;  // extra 是否也触发推送（PHP 侧「整表重建」的类别必须开）

        AlignCfg(String cat, String[] keycols, String[] hashcols, String[] intcols,
                 Map<String, String> map, boolean pull, boolean pushOnExtra) {
            this.cat = cat;
            this.keycols = keycols;
            this.hashcols = hashcols;
            this.intcols = intcols;
            this.map = map;
            this.pull = pull;
            this.pushOnExtra = pushOnExtra;
        }

        /** DB 列名 -> Java 行里的字段名（没映射就原样） */
        String jf(String dbCol) {
            String m = map.get(dbCol);
            return (m == null || m.isEmpty()) ? dbCol : m;
        }

        boolean isIntCol(String dbCol) {
            for (String c : intcols) if (c.equals(dbCol)) return true;
            return false;
        }
    }

    private static Map<String, String> alignMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static final Map<String, AlignCfg> ALIGN_CFGS = new LinkedHashMap<>();

    static {
        // —— 注册用户（PHP users）——
        ALIGN_CFGS.put("users", new AlignCfg("users",
                new String[]{"player_name"},
                new String[]{"player_name", "register_time", "last_login_time", "email",
                        "points", "gift_stage", "total_online_time"},
                new String[]{"register_time", "last_login_time", "points", "gift_stage", "total_online_time"},
                alignMap(), true, false));
        // —— 密码凭证（PHP weblogin_credentials，列名与 Java users 表不同）——
        ALIGN_CFGS.put("credentials", new AlignCfg("credentials",
                new String[]{"player_name"},
                new String[]{"player_name", "password_hash", "salt", "temp_password_hash", "temp_pw_expire"},
                new String[]{"temp_pw_expire"},
                alignMap("salt", "password_salt", "temp_password_hash", "temp_password"), true, false));
        // —— 商城商品：库存/销量由 Web 端独立变动，绝不进指纹（否则每轮都判「变了」）——
        ALIGN_CFGS.put("shop", new AlignCfg("shop",
                new String[]{"id"},
                new String[]{"id", "category", "display_name", "material", "buy_price", "sell_price"},
                new String[]{"buy_price", "sell_price"},
                alignMap(), false, false));
        // —— 债券余额 ——
        ALIGN_CFGS.put("bonds", new AlignCfg("bonds",
                new String[]{"player_name"},
                new String[]{"player_name", "amount"},
                new String[]{"amount"},
                alignMap(), true, false));
        // —— 玩家 IP（PHP player_ip_changes，列名叫 new_ip，Java 侧叫 ip）——
        ALIGN_CFGS.put("ips", new AlignCfg("ips",
                new String[]{"player_name"},
                new String[]{"player_name", "new_ip"},
                new String[]{},
                alignMap("player_name", "name", "new_ip", "ip"), true, false));
        // —— 服务商（Java 权威名单，PHP 多出来的先留着不覆盖本地）——
        ALIGN_CFGS.put("providers", new AlignCfg("providers",
                new String[]{"player_name"},
                new String[]{"player_name", "role", "active", "join_time"},
                new String[]{"active", "join_time"},
                alignMap(), false, false));
        // —— 插件管理员（PHP 端已改为只增改不删，PHP 多出的多为离线管理员，保留）——
        ALIGN_CFGS.put("admins", new AlignCfg("admins",
                new String[]{"player_name"},
                new String[]{"player_name"},
                new String[]{},
                alignMap(), false, false));
        // —— 封禁名单（Web 后台加的封禁要拉回游戏内生效）——
        ALIGN_CFGS.put("bans", new AlignCfg("bans",
                new String[]{"target", "ban_type"},
                new String[]{"target", "ban_type", "reason", "source", "expire_time"},
                new String[]{"expire_time"},
                alignMap("ban_type", "type", "expire_time", "expire"), true, false));
        // —— 在线玩家：PHP 端是「整表重建」，PHP 比 Java 多的行（人已下线）必须靠一次推送清掉
        //    所以 extra 也要触发推送；指纹只比「谁在线」，login_time 每次都变不能进指纹 ——
        ALIGN_CFGS.put("online", new AlignCfg("online",
                new String[]{"player_name"},
                new String[]{"player_name"},
                new String[]{},
                alignMap(), false, true));
        // —— 充值回执（PHP web_transactions，一式两份的 Java 那一份）——
        //   2026-10-06：充值流水原本只有 PHP 单向持有，运行中清空 PHP 数据后
        //   Java 不会回补（poller 又被本地 pay_orders.status 挡住）→ 充值记录凭空消失。
        //   现在 Java 持镜像表 web_tx_receipts，两边按 tx_id 点差集、多退少补：
        //     missing（Java 有 PHP 无）→ 补推重建，status 直接写 processed（这些流水
        //         Java 早就处理过了，绝不能写成 pending，否则 Java 会再拉一次重复发货）
        //     extra（PHP 有 Java 无）→ 收回镜像补齐基线，只补记录、不动钱
        //   指纹不含 status/created_at：pending→processed 这类状态流转不该每轮判「变了」。
        ALIGN_CFGS.put("webtx", new AlignCfg("webtx",
                new String[]{"id"},
                new String[]{"player_name", "type", "amount", "detail"},
                new String[]{"amount"},
                alignMap(), true, false));
    }
    /** 上次交换后已知「两边一致」的指纹：cat -> (key -> hash)。落盘，重启不丢。 */
    private final Map<String, Map<String, String>> alignSeen = new ConcurrentHashMap<>();
    private volatile boolean alignSeenLoaded = false;
    private volatile long alignSeenDirtyAt = 0;
    private volatile long alignSeenLastWrite = 0;

    private File alignSeenFile() {
        return new File(plugin.getDataFolder(), "align_seen.json");
    }

    /** 落盘读取（只读一次；文件损坏一律当作「首轮」，宁可多推一次也不崩） */
    private void loadAlignSeen() {
        if (alignSeenLoaded) return;
        alignSeenLoaded = true;
        try {
            File f = alignSeenFile();
            if (!f.exists()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Map<String, Map<String, String>> loaded = new Gson().fromJson(txt,
                    new com.google.gson.reflect.TypeToken<Map<String, Map<String, String>>>() {}.getType());
            if (loaded != null) {
                for (Map.Entry<String, Map<String, String>> e : loaded.entrySet()) {
                    if (e.getValue() != null) alignSeen.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
                }
                int n = 0;
                for (Map<String, String> m : alignSeen.values()) n += m.size();
                plugin.getLogger().info("[对账] 已载入上次一致指纹 " + alignSeen.size() + " 类 / " + n + " 条");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[对账] 载入 align_seen.json 失败（按首轮处理）: " + t.getMessage());
        }
    }

    /** 落盘写入（合并 5 秒内的连续变更，避免每轮对账都刷盘） */
    private void saveAlignSeen(boolean force) {
        long now = System.currentTimeMillis();
        if (!force) {
            if (alignSeenDirtyAt == 0 || now - alignSeenDirtyAt < 5000) return;
            if (now - alignSeenLastWrite < 5000) return;
        }
        alignSeenLastWrite = now;
        alignSeenDirtyAt = 0;
        try {
            Map<String, Map<String, String>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, String>> e : alignSeen.entrySet()) {
                snapshot.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            File f = alignSeenFile();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            java.nio.file.Files.write(f.toPath(),
                    new Gson().toJson(snapshot).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            plugin.getLogger().warning("[对账] 写 align_seen.json 失败: " + t.getMessage());
        }
    }

    /** 主键串：按 keycols 顺序用 | 连接（PHP alignmentKeyOf 同款：任一段为空即整行作废） */
    private static String alignKeyOf(AlignCfg cfg, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (String c : cfg.keycols) {
            Object v = row.get(cfg.jf(c));
            String sv = v == null ? "" : String.valueOf(v);
            if (sv.isEmpty()) return "";
            if (sb.length() > 0) sb.append('|');
            sb.append(sv);
        }
        return sb.toString();
    }

    /**
     * 规范化单个值（PHP alignmentCanonVal 同款）：
     * 整数列统一十进制字符串，绝对值超过 1e12 的按毫秒时间戳折成秒。
     * 解析不出来时返回 "0"——PHP 的 (int)"abc" 也是 0，两端必须一致。
     */
    private static String alignCanonVal(Object v, boolean isInt) {
        // ★ null + 整数列归一为 "0"：与 PHP alignmentCanonVal 严格一致——
        //   本地行缺 key、PHP 库 NULL、PHP 库 0 三种形态必须归一，否则每轮判 changed
        if (v == null) return isInt ? "0" : "";
        if (!isInt) return String.valueOf(v);
        long n;
        if (v instanceof Number) {
            n = ((Number) v).longValue();
        } else {
            String t = String.valueOf(v).trim();
            try {
                n = Long.parseLong(t);
            } catch (NumberFormatException e1) {
                try {
                    n = (long) Double.parseDouble(t);
                } catch (NumberFormatException e2) {
                    return "0";
                }
            }
        }
        if (n > 1000000000000L || n < -1000000000000L) n = n / 1000;
        return String.valueOf(n);
    }
    /** 行内容指纹（PHP alignmentRowHash 同款：chr(31) 连接后 md5） */
    private static String alignRowHash(AlignCfg cfg, Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (String c : cfg.hashcols) {
            if (sb.length() > 0) sb.append((char) 31);
            sb.append(alignCanonVal(row.get(cfg.jf(c)), cfg.isIntCol(c)));
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(
                    sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 一次对账的结论 */
    private static final class AlignResult {
        boolean ok;                      // PHP 是否给了有效答复
        final List<String> missing = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        final List<String> extra = new ArrayList<>();
        final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();  // PHP 侧行（字段名已是 Java 叫法）
        final Set<String> pushKeys = new LinkedHashSet<>();
        final Set<String> pullKeys = new LinkedHashSet<>();
        int phpCount;
        int pulled;                      // 实际写回本地的条数
        boolean alignTimeout;            // 对账请求硬超时（本轮不推不收，也不回退全量硬推）

        /** 是否有需要 Java 补推的数据 */
        boolean needPush() { return !pushKeys.isEmpty(); }
    }

    /**
     * 调 PHP 的 check_alignment 拿三个差集。
     * 返回 null 表示「对账没做成」（PHP 无答复/报错）——调用方按原有逻辑继续，绝不因此停摆。
     */
    private AlignResult callCheckAlignment(AlignCfg cfg, Map<String, String> javaHash) {
        hardTimeoutHit.set(Boolean.FALSE);   // 只看本次对账自身的硬超时，不受同任务早前请求影响
        try {
            List<Map<String, Object>> records = new ArrayList<>();
            for (Map.Entry<String, String> e : javaHash.entrySet()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("k", e.getKey());
                r.put("h", e.getValue());
                records.add(r);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cat", cfg.cat);
            body.put("records", records);
            body.put("secret", secretKey);

            String token = generateAndSyncToken("system", "sync");
            String url = webBaseUrl + "/api/sync.php?action=check_alignment&token="
                    + java.net.URLEncoder.encode(token, "UTF-8");
            String resp = doPostOnce(url, mapToJson(body));
            if (resp == null) {
                // ★ 2026-10-06：HTTP 层没拿到答复（硬超时 / 连接失败 / 请求超时）一律「本轮不推不收」。
                //   旧逻辑在这里回退成【全量硬推】—— 链路一抖动就变成用户看到的「无脑推」。
                //   对账本身是幂等的全量比对，链路恢复后的第一轮就能把差集补齐，不需要靠硬推兜底。
                countAlign("noresp");
                AlignResult to = new AlignResult();
                to.ok = true;
                to.alignTimeout = true;
                return to;
            }

            Map<String, Object> res = parseJson(resp);
            if (!Boolean.TRUE.equals(res.get("success"))) {
                String msg = res.get("message") != null ? String.valueOf(res.get("message")) : resp;
                countAlign("reject");
                warnThrottled("对账拒绝:" + cfg.cat, "[对账] " + cfg.cat + " PHP 拒绝对账: "
                        + (msg.length() > 200 ? msg.substring(0, 200) : msg));
                return null;
            }
            Object dataObj = res.get("data");
            if (!(dataObj instanceof Map)) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) dataObj;

            AlignResult ar = new AlignResult();
            ar.ok = true;
            ar.missing.addAll(asStringList(data.get("missing")));
            ar.changed.addAll(asStringList(data.get("changed")));
            ar.extra.addAll(asStringList(data.get("extra")));
            Object pc = data.get("php_count");
            if (pc instanceof Number) ar.phpCount = ((Number) pc).intValue();
            Object rowsObj = data.get("rows");
            if (rowsObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawRows = (Map<String, Object>) rowsObj;
                for (Map.Entry<String, Object> e : rawRows.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> one = (Map<String, Object>) e.getValue();
                        ar.rows.put(e.getKey(), one);
                    }
                }
            }
            countAlign("ok");
            return ar;
        } catch (Throwable t) {
            countAlign("noresp");
            warnThrottled("对账异常:" + cfg.cat, "[对账] " + cfg.cat + " 对账异常: " + t.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }
    /**
     * 对账网关：拿本地全量数据与 PHP 点一遍，裁决出「该补推的」和「该拉回的」，并把该拉回的写进本地。
     *
     * @param cat       类别（必须在 ALIGN_CFGS 里）
     * @param localRows 本地全量行（字段名用 Java 侧叫法）
     * @return 对账结论；返回 null 表示对账没做成（PHP 无答复），调用方按原逻辑继续
     */
    private AlignResult alignGate(String cat, List<Map<String, Object>> localRows) {
        AlignCfg cfg = ALIGN_CFGS.get(cat);
        if (cfg == null) return null;
        loadAlignSeen();

        // 本地全量指纹
        Map<String, String> javaHash = new LinkedHashMap<>();
        for (Map<String, Object> row : localRows) {
            String k = alignKeyOf(cfg, row);
            if (k.isEmpty()) continue;
            String h = alignRowHash(cfg, row);
            if (h.isEmpty()) continue;
            javaHash.put(k, h);
        }

        AlignResult ar = callCheckAlignment(cfg, javaHash);
        if (ar != null && ar.alignTimeout) {
            warnSlow("对账无答复:" + cfg.cat,
                    "[对账] " + cfg.cat + " 无答复（"
                    + (Boolean.TRUE.equals(hardTimeoutHit.get()) ? "10秒硬超时" : "连接失败/请求超时")
                    + "）→ 本轮不推不收，下轮重试，不回退全量硬推");
            return ar;   // needPush()==false → 调用方现有判断会直接 return
        }
        if (ar == null || !ar.ok) return null;

        Map<String, String> seen = alignSeen.get(cat);
        if (seen == null) {
            seen = new ConcurrentHashMap<>();
            alignSeen.put(cat, seen);
        }

        // —— 三个差集 → 推 / 收 ——
        for (String k : ar.missing) {
            if (javaHash.containsKey(k)) ar.pushKeys.add(k);           // Java 有 PHP 无 → 补推
        }
        for (String k : ar.changed) {
            String jh = javaHash.get(k);
            if (jh == null) continue;
            String last = seen.get(k);
            if (last == null || !last.equals(jh)) {
                ar.pushKeys.add(k);                                     // 交换之后 Java 改过 → 推
            } else if (cfg.pull) {
                ar.pullKeys.add(k);                                     // 只有 PHP 改过 → 收
            }
            // pull=false 的类别（PHP 改的）：既不推也不收，保持 Java 权威
        }
        for (String k : ar.extra) {
            if (cfg.pull) {
                ar.pullKeys.add(k);                                     // PHP 多的 → 收回来
            } else if (cfg.pushOnExtra) {
                ar.pushKeys.add(k);                                     // PHP 整表重建类：要推一次才能清掉
            }
        }

        // —— 拉回写本地 ——
        for (String k : ar.pullKeys) {
            Map<String, Object> row = ar.rows.get(k);
            if (row == null) continue;
            if (applyPulledRow(cat, row)) {
                ar.pulled++;
                // 收完两边就一致了：记 PHP 的指纹，下轮不再判「PHP 改过」
                seen.put(k, alignRowHash(cfg, row));
            }
        }

        // —— 完全一致的 key：把「已知一致」的指纹记下来（推/收没成功的那些绝不动，
        //    否则下一轮会判成「Java 改过」而漏推，或者判成「PHP 改过」而漏收）——
        boolean touched = false;
        for (Map.Entry<String, String> e : javaHash.entrySet()) {
            String k = e.getKey();
            if (ar.missing.contains(k) || ar.changed.contains(k) || ar.extra.contains(k)) continue;
            if (!e.getValue().equals(seen.get(k))) {
                seen.put(k, e.getValue());
                touched = true;
            }
        }
        // 上一轮成功推过的 key，这一轮会出现在「一致」里，指纹自然就更新了

        if (touched || ar.pulled > 0) {
            alignSeenDirtyAt = System.currentTimeMillis();
            saveAlignSeen(false);
        }

        if (!ar.pushKeys.isEmpty() || !ar.pullKeys.isEmpty()) {
            plugin.getLogger().info("[对账] " + cat + ": PHP共" + ar.phpCount
                    + " 本地" + javaHash.size()
                    + " → 补推" + ar.pushKeys.size()
                    + " 收回" + ar.pullKeys.size()
                    + "（实际写入本地 " + ar.pulled + " 条）");
        }
        return ar;
    }
    /**
     * 把 PHP 侧的行写回本地（pull=1 的类别）。
     * @return 是否真的写进去了
     */
    private boolean applyPulledRow(String cat, Map<String, Object> row) {
        try {
            switch (cat) {
                case "users": {
                    DatabaseManager dbMgr = plugin.getDb();
                    if (dbMgr == null) return false;
                    String name = strOf(row.get("player_name"));
                    if (name.isEmpty()) return false;
                    if (!dbMgr.userExists(name)) {
                        // PHP 端（网页）注册的新用户：先建骨架，密码由 credentials 类别拉回补上
                        dbMgr.createUser(name, "", "");
                    }
                    dbMgr.setField(name, "register_time", longOf(row.get("register_time")));
                    dbMgr.setField(name, "last_login_time", longOf(row.get("last_login_time")));
                    dbMgr.setField(name, "email", strOf(row.get("email")));
                    dbMgr.setField(name, "points", intOf(row.get("points")));
                    dbMgr.setField(name, "gift_stage", intOf(row.get("gift_stage")));
                    dbMgr.setField(name, "total_online_time", intOf(row.get("total_online_time")));
                    return true;
                }
                case "credentials": {
                    DatabaseManager dbMgr = plugin.getDb();
                    if (dbMgr == null) return false;
                    String name = strOf(row.get("player_name"));
                    String hash = strOf(row.get("password_hash"));
                    String salt = strOf(row.get("password_salt"));
                    if (name.isEmpty() || hash.isEmpty() || salt.isEmpty()) return false;
                    if (!dbMgr.userExists(name)) dbMgr.createUser(name, hash, salt);
                    else dbMgr.updatePassword(name, hash, salt);
                    String tmpHash = strOf(row.get("temp_password"));
                    long tmpExpire = longOf(row.get("temp_pw_expire"));
                    if (!tmpHash.isEmpty() && tmpExpire > 0) {
                        dbMgr.setField(name, "temp_password", tmpHash);
                        dbMgr.setField(name, "temp_pw_expire", tmpExpire);
                    } else {
                        dbMgr.setField(name, "temp_password", "");
                        dbMgr.setField(name, "temp_pw_expire", 0L);
                    }
                    return true;
                }
                case "bonds": {
                    BondManager bondMgr = plugin.getBonds();
                    if (bondMgr == null) return false;
                    String name = strOf(row.get("player_name"));
                    if (name.isEmpty()) return false;
                    int amount = intOf(row.get("amount"));
                    if (bondMgr.getBonds(name) == amount) return false;   // 没变就别动，避免无谓写库
                    bondMgr.setBonds(name, amount);
                    return true;
                }
                case "ips": {
                    DatabaseManager dbMgr = plugin.getDb();
                    if (dbMgr == null) return false;
                    String name = strOf(row.get("name"));
                    String ip = strOf(row.get("ip"));
                    if (name.isEmpty() || ip.isEmpty()) return false;
                    if (!dbMgr.userExists(name)) return false;   // 用户还没拉回来，等 users 类别处理
                    dbMgr.setField(name, "ip_address", ip);
                    return true;
                }
                case "bans": {
                    String target = strOf(row.get("target"));
                    if (target.isEmpty()) return false;
                    boolean isIp = "ip".equalsIgnoreCase(strOf(row.get("type")));
                    org.bukkit.BanList.Type bt = isIp ? org.bukkit.BanList.Type.IP : org.bukkit.BanList.Type.NAME;
                    org.bukkit.BanList<org.bukkit.BanEntry<?>> banList = Bukkit.getBanList(bt);
                    if (banList.isBanned(target)) return false;   // 本地已有，不重复封
                    String reason = strOf(row.get("reason"));
                    if (reason.isEmpty()) reason = "Web后台封禁";
                    String source = strOf(row.get("source"));
                    if (source.isEmpty()) source = "Web";
                    long expire = longOf(row.get("expire"));
                    // 真实签名：addBan(target, reason, expires, source)
                    // Date 必须全限定——本文件同时 import 了 java.sql.* 与 java.util.*
                    banList.addBan(target, reason,
                            expire > 0 ? new java.util.Date(expire) : null, source);
                    plugin.getLogger().info("[对账] 已收回 Web 端封禁: " + (isIp ? "IP " : "") + target);
                    return true;
                }
                case "webtx": {
                    // 充值回执：PHP 有、Java 没有的那条 → 收回镜像补齐基线。
                    // ★ 只补记录，绝不触发业务：不加钱、不发货、不确认交易。
                    //   这些流水在 PHP 侧早已是 processed，Java 收回只是为了
                    //   让下一轮对账两边相等，避免每轮都在「多退少补」里空转。
                    DatabaseManager dbMgr = plugin.getDb();
                    if (dbMgr == null) return false;
                    long txId = longOf(row.get("id"));
                    if (txId <= 0) return false;
                    if (dbMgr.webTxReceiptExists(txId)) return false;   // 已有，指纹不一致也算没变化
                    String playerName = strOf(row.get("player_name"));
                    String txType = strOf(row.get("type"));
                    int txAmount = intOf(row.get("amount"));
                    String txDetail = strOf(row.get("detail"));
                    long createdAt = longOf(row.get("created_at"));
                    dbMgr.upsertWebTxReceipt(txId, playerName, txType, txAmount, txDetail, createdAt);
                    return true;
                }
                default:
                    return false;   // shop/providers/admins/online 等 pull=false 的类别不会走到这里
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[对账] 写回本地失败 cat=" + cat + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * ★ 2026-10-06 充值回执对账（一式两份 · 多退少补）
     *
     * 充值流水 PHP 持 web_transactions(type=recharge)、Java 持 web_tx_receipts，
     * 两边按 tx_id 点差集：
     *   missing（Java 有 PHP 无）→ 补推重建流水（运行中清空 PHP 数据的自愈路径）
     *   extra  （PHP 有 Java 无）→ alignGate 内部已收回镜像补齐基线
     *
     * 挂在「运行中的全量同步调度器」周期批次里，随其他类别一起热同步。
     * 补推的行一律带 status=processed —— 这些流水 Java 早已确认处理过，
     * 写成 pending 会让 Java 下轮再拉一次、重复发货/重复加钱。
     */
    public void syncWebTxReceipts() {
        if (!enabled) return;
        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr == null) return;
        try {
            List<Map<String, Object>> local = dbMgr.getAllWebTxReceipts();
            AlignResult ar = alignGate("webtx", local);
            if (ar == null) return;   // 对账没做成（PHP 无答复）→ 下轮重试，不硬推
            if (ar.pulled > 0) {
                plugin.getLogger().info("[充值回执] 从 PHP 收回 " + ar.pulled + " 条补齐本地镜像");
            }
            if (!ar.needPush()) return;   // 两边一致 → 零发送

            AlignCfg cfg = ALIGN_CFGS.get("webtx");
            Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
            for (Map<String, Object> row : local) {
                String k = alignKeyOf(cfg, row);
                if (!k.isEmpty()) byKey.put(k, row);
            }

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            int pushed = 0;
            for (String k : ar.pushKeys) {
                Map<String, Object> row = byKey.get(k);
                if (row == null) continue;
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"id\":").append(longOf(row.get("id")))
                        .append(",\"player_name\":\"").append(escapeJson(strOf(row.get("player_name"))))
                        .append("\",\"type\":\"").append(escapeJson(strOf(row.get("type"))))
                        .append("\",\"amount\":").append(intOf(row.get("amount")))
                        .append(",\"detail\":\"").append(escapeJson(strOf(row.get("detail"))))
                        .append("\",\"created_at\":").append(longOf(row.get("created_at")))
                        .append("}");
                pushed++;
            }
            sb.append("]");
            if (pushed == 0) return;

            String json = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"receipts\":" + sb + "}";
            String resp = httpPost("api/sync.php?action=sync_webtx_receipts", json);
            if (resp != null && resp.contains("\"success\":true")) {
                plugin.getLogger().info("[充值回执] 补推 " + pushed + "/" + local.size()
                        + " 条到 PHP（PHP 侧缺失已重建）: " + resp);
            } else {
                plugin.getLogger().warning("[充值回执] 补推失败（" + pushed + " 条，下轮重试）: "
                        + (resp == null ? "null" : resp.substring(0, Math.min(200, resp.length()))));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[充值回执] 对账异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 把 Bukkit 的一条封禁转成对账行（字段名必须与 PHP 侧 map 之后的叫法一致） */
    private static Map<String, Object> banRowOf(org.bukkit.BanEntry<?> entry, String type) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("target", entry.getTarget());
        r.put("type", type);
        r.put("reason", entry.getReason() != null ? entry.getReason() : "");
        r.put("source", entry.getSource() != null ? entry.getSource() : "");
        r.put("expire", entry.getExpiration() != null ? entry.getExpiration().getTime() : 0L);
        return r;
    }

    private static String strOf(Object v) { return v == null ? "" : String.valueOf(v); }

    private static int intOf(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (Exception e) { return 0; }
    }

    private static long longOf(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); } catch (Exception e) { return 0; }
    }

    // ==================== 业务功能 ====================

    /**
     * 1. 同步商城数据到Web端
     * 读取shop/*.md文件，解析后推送到Web API
     */
    public void syncShopData() {
        if (!enabled) return;

        try {
            // 生成同步Token并注册到PHP
            String token = generateAndSyncToken("system", "sync");
            // 读取shop目录下的md文件
            File shopDir = new File(plugin.getDataFolder(), "shop");
            if (!shopDir.exists()) return;

            List<Map<String, Object>> items = new ArrayList<>();
            File[] mdFiles = shopDir.listFiles((d, n) -> n.endsWith(".md"));
            if (mdFiles == null || mdFiles.length == 0) return;

            for (File mdFile : mdFiles) {
                String categoryName = mdFile.getName().replace(".md", "");
                List<Map<String, Object>> catItems = parseMdShopFile(mdFile, categoryName);
                items.addAll(catItems);
            }

            // ★ 双向对账（多退少补）：商品目录与 PHP 一致就不发。
            //   指纹刻意不含库存/销量（Web 端会独立改动，含了就每轮都判「变了」）；
            //   PHP 端管理员改的价格也不触发推送——pull=false，只有 Java 改过才推。
            AlignResult arAlign = alignGate("shop", items);
            if (arAlign != null && !arAlign.needPush()) return;

            if (items.isEmpty()) return;

            // ★ 无变化静默：对比商品数量和内容hash
            String currentHash = items.size() + ":" + items.hashCode();
            // ★ 对账明说要推时本地 hash 不得拦截（同 admins，2026-10-06 灾备修复）：
            //   PHP 库被清空后 currentHash 仍等于上次成功推送的值 → 旧逻辑在这里永久短路，
            //   表现为日志每轮打「补推N」却永远发不出去，shop_items 一直缺行。
            boolean alignNeedsPushShop = arAlign != null && arAlign.needPush();
            if (!alignNeedsPushShop && currentHash.equals(lastShopDataHash)) return; // 无变化，跳过
            // ★ 关键修复：hash 成功后才提交（防假成功，见 syncUserRegistrations 注释）

            // 构建请求数据
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);

            String json = mapToJson(body);
            String response = httpPostWithToken("api/sync.php?action=sync_shop", token, body);

            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    lastShopDataHash = currentHash; // ← 成功后才落 hash
                    plugin.getLogger().info("[Web通信] 商城数据变更，已同步: " + items.size() + "个商品");
                } else {
                    plugin.getLogger().warning("[Web通信] 商城同步失败: " + result.get("message"));
                }
            } else {
                plugin.getLogger().warning("[Web通信] 商城同步请求失败（下轮将重试）");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 商城同步异常: " + e.getMessage());
        }
    }

    // ==================== 服务商列表同步到Web ====================
    public void syncServiceProviders() {
        try {
            String token = generateAndSyncToken("system", "sync");
            List<Map<String, Object>> providers = new ArrayList<>();
            // ★ 用全量（含 active=0）：只取启用中的会让停用服务商在 PHP 侧永远是 extra
            for (Map<String, Object> sp : plugin.getDb().getAllServiceProvidersFull()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("player_name", sp.get("player_name"));
                p.put("role", sp.get("role"));
                p.put("active", sp.get("active"));
                p.put("join_time", sp.get("join_time"));
                providers.add(p);
            }

            // ★ 双向对账（多退少补）：名单与 PHP 一致就不发。
            //   pull=false —— PHP 多出来的先留着，不覆盖游戏内权威名单
            AlignResult arAlign = alignGate("providers", providers);
            if (arAlign != null && !arAlign.needPush()) return;

            if (providers.isEmpty()) return;

            // ★ 无变化静默
            String currentHash = providers.size() + ":" + providers.hashCode();
            // ★ 对账明说要推时本地 hash 不得拦截（同 admins，2026-10-06 灾备修复）
            boolean alignNeedsPushSp = arAlign != null && arAlign.needPush();
            if (!alignNeedsPushSp && currentHash.equals(lastServiceProviderHash)) return;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", providers);

            String response = httpPostWithToken("api/sync.php?action=sync_service_providers", token, body);

            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    lastServiceProviderHash = currentHash; // ← 成功后才落 hash
                    plugin.getLogger().info("[Web通信] 服务商数据变更，已同步: " + providers.size() + "人");
                } else {
                    plugin.getLogger().warning("[Web通信] 服务商同步失败: " + result.get("message"));
                }
            } else {
                plugin.getLogger().warning("[Web通信] 服务商同步请求失败");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 服务商同步异常: " + e.getMessage());
        }
    }

    /**
     * 解析md商品文件
     * 格式: | ID | 品名 | 材质 | 购入价 | 售出价 | 库存 | 本小时销量 | 总销量 |
     */
    private List<Map<String, Object>> parseMdShopFile(File file, String category) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            boolean inTable = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) {
                    inTable = false;
                    continue;
                } // 分类标题
                if (line.contains("---")) {
                    inTable = true;
                    continue;
                } // 表头分隔符
                if (line.startsWith("|") && inTable) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 9) {
                        String id = parts[1].trim();
                        if (id.isEmpty() || id.equals("ID")) continue;

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", id);
                        item.put("category", category);
                        item.put("display_name", parts[2].trim());
                        item.put("material", parts[3].trim());
                        item.put("buy_price", safeInt(parts[4].trim()));
                        item.put("sell_price", safeInt(parts[5].trim()));
                        item.put("stock", safeInt(parts[6].trim()));
                        item.put("hourly_sales", safeInt(parts[7].trim()));
                        item.put("total_sales", safeInt(parts[8].trim()));
                        items.add(item);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 解析商品文件失败: " + file.getName());
        }
        return items;
    }

    /**
     * 2. CDK验证
     * 插件发送CDK码到Web验证，返回验证结果
     *
     * @return "success:金额:余额前:余额后" 或 "fail:原因"
     */
    public String verifyCDK(String code, String playerName) {
        if (!enabled) return "fail:Web通信未启用";

        try {
            String token = generateAndSyncToken(playerName, "cdk");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            body.put("player", playerName);

            String response = httpPostWithToken("api/cdk.php?action=exchange", token, body);
            if (response == null) return "fail:请求失败";

            Map<String, Object> result = parseJson(response);
            Boolean success = (Boolean) result.get("success");
            if (Boolean.TRUE.equals(success)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null) {
                    int amount = data.get("amount") != null ? ((Number) data.get("amount")).intValue() : 0;
                    int balanceAfter = data.get("balance_after") != null ? ((Number) data.get("balance_after")).intValue() : 0;
                    return "success:" + amount + ":" + balanceAfter;
                }
            }

            String msg = result.get("message") != null ? result.get("message").toString() : "验证失败";
            return "fail:" + msg;
        } catch (Exception e) {
            return "fail:" + e.getMessage();
        }
    }

    /**
     * 3. 查询余额
     *
     * @return 债券余额，-1表示查询失败
     */
    public int queryBalance(String playerName) {
        if (!enabled) return -1;

        try {
            String token = generateAndSyncToken(playerName, "bond");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "query");
            params.put("player", playerName);
            params.put("token", token);

            String response = httpGet("api/balance.php", params);
            if (response == null) return -1;

            Map<String, Object> result = parseJson(response);
            Boolean success = (Boolean) result.get("success");
            if (Boolean.TRUE.equals(success)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) result.get("data");
                if (data != null && data.get("bonds") != null) {
                    return ((Number) data.get("bonds")).intValue();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 余额查询失败: " + e.getMessage());
        }
        return -1;
    }

    /**
     * 4. 注册账号到Web端
     * ★ 内部异步：调用点在主线程（Web注册回调 runTask 里），
     *   HTTP 同步（token + register.php POST，10s 超时）会卡死 tick。
     */
    public void syncRegistration(String playerName, String passwordHash, String salt, String email, String ip) {
        if (!enabled) return;

        // 参数在 lambda 内只读，effectively final；email/ip 可能为 null，先落地
        final String fPlayer = playerName;
        final String fHash = passwordHash;
        final String fSalt = salt;
        final String fEmail = email != null ? email : "";
        final String fIp = ip != null ? ip : "";

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String token = generateAndSyncToken(fPlayer, "register");

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("player", fPlayer);
                body.put("password_hash", fHash);
                body.put("salt", fSalt);
                body.put("email", fEmail);
                body.put("ip", fIp);

                String response = httpPostWithToken("api/register.php?action=register", token, body);
                if (response != null) {
                    Map<String, Object> result = parseJson(response);
                    Boolean success = (Boolean) result.get("success");
                    if (Boolean.TRUE.equals(success)) {
                        plugin.getLogger().info("[Web通信] 注册同步成功: " + fPlayer);
                    } else {
                        plugin.getLogger().warning("[Web通信] 注册同步失败: " + result.get("message"));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Web通信] 注册同步异常: " + e.getMessage());
            }
        });
    }

    /**
     * 同步领地数据到PHP端
     */
    public void syncLandData() {
        if (!enabled) return;

        try {
            AreaProtection areaProtect = plugin.getAreaProtection();
            if (areaProtect == null) return;

            List<Map<String, Object>> lands = areaProtect.getAllLandsForSync();
            List<Map<String, Object>> shopItems = areaProtect.getPermissionShopForSync();

            // ★ 无变化静默：用JSON内容hash检测（覆盖所有关键字段）
            // 只要有任何一个字段变化就触发同步
            StringBuilder hashBuilder = new StringBuilder();
            for (Map<String, Object> land : lands) {
                // 基础字段
                hashBuilder.append(land.getOrDefault("id", 0)).append(":");
                hashBuilder.append(land.getOrDefault("owner", "")).append(":");
                hashBuilder.append(land.getOrDefault("name", "")).append(":");
                hashBuilder.append(land.getOrDefault("world", "")).append(":");
                hashBuilder.append(land.getOrDefault("x1", 0)).append(":");
                hashBuilder.append(land.getOrDefault("z1", 0)).append(":");
                hashBuilder.append(land.getOrDefault("x2", 0)).append(":");
                hashBuilder.append(land.getOrDefault("z2", 0)).append(":");
                hashBuilder.append(land.getOrDefault("y_min", 0)).append(":");
                hashBuilder.append(land.getOrDefault("y_max", 0)).append(":");
                // 所有deny权限
                hashBuilder.append(land.getOrDefault("deny_block_break", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_block_place", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_fluid", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_pvp", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_fire_spread", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_item_frame", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_move", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_pickup", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_drop", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_explosion", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_fall_damage", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_hunger", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_container", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_thrown_projectiles", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_glowing", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_redstone_interaction", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_door_interaction", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_noteblock_jukebox", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_lead", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_crop_harvest", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_wool_shear", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_animal_feeding", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_spawn_egg", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_wax", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_mob_attack", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_fire", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_all_effects", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_all_damage", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_ender_pearl", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_mount", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_bow", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_potion", 0)).append(":");
                hashBuilder.append(land.getOrDefault("deny_raid", 0)).append(":");
                hashBuilder.append(land.getOrDefault("is_public_building", 0)).append(":");
                hashBuilder.append(land.getOrDefault("allow_visitor_teleport", 0)).append(":");
                // 效果和消息
                hashBuilder.append(land.getOrDefault("peace_mode", 0)).append(":");
                hashBuilder.append(land.getOrDefault("peace_mode_duration", 0)).append(":");
                hashBuilder.append(land.getOrDefault("enforce_game_mode", "")).append(":");
                hashBuilder.append(land.getOrDefault("confiscate_items", "")).append(":");
                hashBuilder.append(land.getOrDefault("deny_use_items", "")).append(":");
                hashBuilder.append(land.getOrDefault("punish_commands", "")).append(":");
                hashBuilder.append(land.getOrDefault("enter_msg", "")).append(":");
                hashBuilder.append(land.getOrDefault("leave_msg", "")).append(":");
                hashBuilder.append(land.getOrDefault("confiscate_msg", "")).append(":");
                hashBuilder.append(land.getOrDefault("enable_announce", 0)).append(":");
                hashBuilder.append(land.getOrDefault("announce_template", "")).append(":");
                hashBuilder.append(land.getOrDefault("txt_content", "")).append(":");
                hashBuilder.append(land.getOrDefault("peace_whitelist", "")).append(":");
                hashBuilder.append(land.getOrDefault("mode_exempt", "")).append(":");
                hashBuilder.append(land.getOrDefault("clear_effects", "")).append(":");
                hashBuilder.append(land.getOrDefault("give_effects", "")).append(":");
                // ★ 2026-10-04：负面效果拆出后也要进变更哈希，否则改了负面不会触发同步
                hashBuilder.append(land.getOrDefault("bad_effects", "")).append(":");
                hashBuilder.append(land.getOrDefault("clear_all_bad", 0)).append(":");
                // 传送点
                hashBuilder.append(land.getOrDefault("warp_x", 0)).append(":");
                hashBuilder.append(land.getOrDefault("warp_y", 0)).append(":");
                hashBuilder.append(land.getOrDefault("warp_z", 0)).append(":");
                hashBuilder.append(land.getOrDefault("warp_yaw", 0)).append(":");
                hashBuilder.append(land.getOrDefault("warp_pitch", 0)).append(":");
                hashBuilder.append(land.getOrDefault("warp_world", "")).append("|");
            }
            // ★ 配置变化也触发同步
            Map<String, String> cfgForHash = areaProtect.getAllAreaConfigForSync();
            for (Map.Entry<String, String> entry : cfgForHash.entrySet()) {
                hashBuilder.append("cfg:").append(entry.getKey()).append("=").append(entry.getValue()).append(":");
            }
            // ★ 权限数据变化也触发同步（成员增删改查）
            List<Map<String, Object>> permsForHash = areaProtect.getAllPermsForSync();
            hashBuilder.append("perms:").append(permsForHash.size()).append(":");
            for (Map<String, Object> p : permsForHash) {
                hashBuilder.append(p.getOrDefault("land_id", 0)).append(":")
                           .append(p.getOrDefault("player_name", "")).append(":")
                           .append(p.getOrDefault("role", "")).append(":")
                           .append(p.getOrDefault("permissions", "")).append("|");
            }
            String currentHash = lands.size() + ":" + hashBuilder.toString();

            // ★ 封禁名单和管理员列表独立于领地hash变化，必须每次都同步
            syncBans();
            syncAdmins();

            if (currentHash.equals(lastLandDataHash)) {
                // ★ hash未变化，静默跳过（但首次运行或强制刷新时会同步）
                return;
            }
            plugin.getLogger().info("[防护-sync] hash变化: lands=" + lands.size() + " config=" + cfgForHash.size() + "项，开始同步");
            // ★ 关键修复：hash 不在这里提交，等下面 sync_lands 明确 success 才提交。
            //   404 期间这里已提交 → 之后 hash 永远相等 → web_area_lands 永久 0 行
            //   （2026-10-06 灾备实测，与 syncUserRegistrations 同一类假成功）。

            // 1. 同步领地列表（全字段）——用POST避免GET URL长度限制
            if (!lands.isEmpty()) {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < lands.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(mapToJson(lands.get(i)));
                }
                sb.append("]");
                // 构建JSON body（含action+secret+lands）
                String jsonBody = "{\"action\":\"sync_lands\",\"secret\":\"" + escapeJson(secretKey) + "\",\"lands\":" + sb.toString() + "}";
                String resp = httpPost("api/sync.php", jsonBody);
                plugin.getLogger().fine("[防护-sync] 领地同步: " + resp);
                // ★ 关键修复：sync_lands 明确 success 才提交 hash（防 404/500 假成功）
                if (resp != null && resp.contains("\"success\":true")) {
                    lastLandDataHash = currentHash;
                } else {
                    plugin.getLogger().warning("[防护-sync] 领地同步未确认成功，hash 不提交，下轮重试: "
                            + (resp == null ? "null" : resp.substring(0, Math.min(160, resp.length()))));
                }
            } else {
                // 本地已无领地可推（领地全删）→ 无需网络确认，直接提交 hash 免得空转
                lastLandDataHash = currentHash;
            }

            // 2. 同步权限商店数据
            if (!shopItems.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < shopItems.size(); i++) {
                    if (i > 0) sb.append(",");
                    Map<String, Object> s = shopItems.get(i);
                    sb.append("{");
                    sb.append("\"id\":").append(s.getOrDefault("id", 0)).append(",");
                    sb.append("\"land_id\":").append(s.getOrDefault("land_id", 0)).append(",");
                    sb.append("\"land_name\":\"").append(escapeJson(String.valueOf(s.getOrDefault("land_name", "")))).append("\",");
                    sb.append("\"seller\":\"").append(escapeJson(String.valueOf(s.getOrDefault("seller", "")))).append("\",");
                    sb.append("\"permission\":\"").append(escapeJson(String.valueOf(s.getOrDefault("permission", "visitor")))).append("\",");
                    sb.append("\"price\":").append(s.getOrDefault("price", 0)).append(",");
                    sb.append("\"duration\":").append(s.getOrDefault("duration", 86400)).append(",");
                    sb.append("\"status\":\"").append(escapeJson(String.valueOf(s.getOrDefault("status", "active")))).append("\",");
                    sb.append("\"buyer\":\"").append(escapeJson(String.valueOf(s.getOrDefault("buyer", "")))).append("\",");
                    sb.append("\"bought_at\":").append(s.getOrDefault("bought_at", 0)).append(",");
                    sb.append("\"created_at\":").append(s.getOrDefault("created_at", 0));
                    sb.append("}");
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("action", "sync_land_shop");
                params.put("secret", secretKey);
                params.put("items", "[" + sb.toString() + "]");
                httpGet("api/sync.php", params);
            }

            // 3. 同步全局配置（area_config → web_area_config）
            Map<String, String> config = areaProtect.getAllAreaConfigForSync();
            if (!config.isEmpty()) {
                StringBuilder cfgSb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, String> entry : config.entrySet()) {
                    if (!first) cfgSb.append(",");
                    cfgSb.append("\"").append(escapeJson(entry.getKey())).append("\":\"")
                         .append(escapeJson(entry.getValue())).append("\"");
                    first = false;
                }
                cfgSb.append("}");
                Map<String, String> cfgParams = new LinkedHashMap<>();
                cfgParams.put("action", "sync_config");
                cfgParams.put("secret", secretKey);
                cfgParams.put("config", cfgSb.toString());
                String cfgResp = httpGet("api/sync.php", cfgParams);
                plugin.getLogger().fine("[防护-sync] 配置同步: " + cfgResp);
            }

            // 4. 同步成员权限数据（area_land_permissions → web_area_permissions）
            syncPermissions(areaProtect);

            plugin.getLogger().fine("[Web通信] 领地数据同步完成");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 领地同步异常: " + e.getMessage());
        }
    }

    /**
     * 同步成员权限数据到PHP端
     */
    private void syncPermissions(AreaProtection areaProtect) {
        try {
            List<Map<String, Object>> perms = areaProtect.getAllPermsForSync();
            if (perms.isEmpty()) return;

            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < perms.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> p = perms.get(i);
                sb.append("{");
                sb.append("\"land_id\":").append(p.getOrDefault("land_id", 0)).append(",");
                sb.append("\"land_name\":\"").append(escapeJson(String.valueOf(p.getOrDefault("land_name", "")))).append("\",");
                sb.append("\"player_name\":\"").append(escapeJson(String.valueOf(p.getOrDefault("player_name", "")))).append("\",");
                sb.append("\"role\":\"").append(escapeJson(String.valueOf(p.getOrDefault("role", "")))).append("\",");
                sb.append("\"permissions\":\"").append(escapeJson(String.valueOf(p.getOrDefault("permissions", "")))).append("\",");
                sb.append("\"granted_at\":").append(p.getOrDefault("granted_at", 0)).append(",");
                sb.append("\"expires_at\":").append(p.getOrDefault("expires_at", 0));
                sb.append("}");
            }
            sb.append("]");

            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "sync_permissions");
            params.put("secret", secretKey);
            params.put("permissions", sb.toString());
            String resp = httpGet("api/sync.php", params);
            plugin.getLogger().fine("[防护-sync] 权限同步: " + perms.size() + "条 → " + resp);
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 权限同步异常: " + e.getMessage());
        }

        // 5. 同步用户组配置
        try {
            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm != null) {
                Map<String, UserGroupManager.UserGroupConfig> groups = ugm.getGroupConfigs();
                if (!groups.isEmpty()) {
                    StringBuilder gs = new StringBuilder("[");
                    boolean first = true;
                    for (UserGroupManager.UserGroupConfig cfg : groups.values()) {
                        if (!first) gs.append(",");
                        gs.append("{");
                        gs.append("\"group_name\":\"").append(escapeJson(cfg.name)).append("\",");
                        gs.append("\"display_name\":\"").append(escapeJson(cfg.displayName)).append("\",");
                        gs.append("\"display_color\":\"").append(escapeJson(cfg.displayColor)).append("\",");
                        gs.append("\"priority\":").append(cfg.priority).append(",");
                        gs.append("\"land_price_per_sqm\":").append(cfg.landPricePerSqm).append(",");
                        gs.append("\"max_lands\":").append(cfg.maxLands).append(",");
                        gs.append("\"max_effects\":").append(cfg.maxEffects).append(",");
                        gs.append("\"max_effect_level\":").append(cfg.maxEffectLevel).append(",");
                        gs.append("\"home_limit\":").append(cfg.homeLimit).append(",");
                        gs.append("\"join_price\":").append(cfg.joinPrice).append(",");
                        gs.append("\"auto_renew\":").append(cfg.autoRenew ? 1 : 0).append(",");
                        gs.append("\"renew_price\":").append(cfg.renewPrice).append(",");
                        gs.append("\"duration_minutes\":").append(cfg.durationMinutes).append(",");
                        gs.append("\"default_perms\":\"").append(escapeJson(cfg.defaultPerms != null ? cfg.defaultPerms : "{}")).append("\"");
                        gs.append("}");
                        first = false;
                    }
                    gs.append("]");

                    Map<String, String> gParams = new LinkedHashMap<>();
                    gParams.put("action", "sync_user_groups");
                    gParams.put("secret", secretKey);
                    gParams.put("groups", gs.toString());
                    String gResp = httpGet("api/sync.php", gParams);
                    plugin.getLogger().fine("[防护-sync] 用户组同步: " + groups.size() + "个 → " + gResp);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 用户组同步异常: " + e.getMessage());
        }

        // 6. ★ 从PHP拉取用户组（PHP→Java反向同步）
        pullUserGroupsFromPHP();
    }

    /**
     * ★ 同步插件管理员列表到PHP
     * 收集所有具有areaProtectAdminTag的玩家，推送到PHP的web_plugin_admins表
     */
    private void syncAdmins() {
        if (!enabled) return;
        try {
            // 获取管理员tag名
            String adminTag = plugin.getConfigMgr().areaProtectAdminTag;
            if (adminTag == null || adminTag.isEmpty()) adminTag = "admin";

            // 收集所有在线+离线玩家中具有该tag的玩家
            // ★ 使用Scoreboard获取所有注册过tag的玩家
            java.util.Set<String> adminNames = new java.util.HashSet<>();
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("group." + adminTag) || player.isOp()) {
                    // 检查ScoreboardTag
                    for (String tag : player.getScoreboardTags()) {
                        if (tag.equalsIgnoreCase(adminTag)) {
                            adminNames.add(player.getName().toLowerCase());
                            break;
                        }
                    }
                }
            }

            // ★ 也检查AreaProtection的admin列表（如果有的话）
            AreaProtection areaProtect = plugin.getAreaProtection();
            if (areaProtect != null) {
                // 遍历所有在线玩家检查tag
                for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                    if (areaProtect.isAreaAdmin(player)) {
                        adminNames.add(player.getName().toLowerCase());
                    }
                }
            }

            // ★ 双向对账（多退少补）：管理员名单与 PHP 一致就不发
            List<Map<String, Object>> adminRows = new ArrayList<>();
            for (String nm0 : adminNames) {
                Map<String, Object> r0 = new LinkedHashMap<>();
                r0.put("player_name", nm0);
                adminRows.add(r0);
            }
            AlignResult arAlign = alignGate("admins", adminRows);
            if (arAlign != null && !arAlign.needPush()) return;

            String currentHash = adminNames.stream().sorted().collect(Collectors.joining("|"));
            // ★ 对账明说 PHP 缺数据时，本地 hash 绝不能拦截（2026-10-06 灾备实测）：
            //   PHP 库被清空后 currentHash 仍等于上次成功推送的值 → 旧逻辑在这里永久短路，
            //   表现为日志每轮打「补推1」却永远发不出去，web_plugin_admins 永远 0 行。
            boolean alignNeedsPush = arAlign != null && arAlign.needPush();
            if (!alignNeedsPush && currentHash.equals(lastAdminsHash)) {
                plugin.getLogger().fine("[防护-sync] 管理员列表无变化，跳过");
                return;
            }

            // 构建JSON数组
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String name : adminNames) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(name)).append("\"");
                first = false;
            }
            sb.append("]");

            // ★ 改用POST避免GET URL过长
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("secret", secretKey);
            body.put("admins", sb.toString());
            String resp = httpPost("api/sync.php?action=sync_admins", mapToJson(body));
            plugin.getLogger().info("[防护-sync] 管理员列表同步: " + adminNames.size() + "人 → " + resp);
            // ★ 关键修复：hash 成功后才提交（同 syncUserRegistrations 的规矩）。
            //   404/500 时若已提交 hash，这份数据就永远不会重推（2026-10-06 灾备实测）。
            if (resp != null && resp.contains("\"success\":true")) {
                lastAdminsHash = currentHash;
            } else {
                plugin.getLogger().warning("[防护-sync] 管理员列表同步未确认成功，下轮重试（hash 不提交）");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 管理员列表同步异常: " + e.getMessage());
        }
    }

    public void forceSyncBansAndAdmins() {
        syncBans();
        syncAdmins();
    }

    /**
     * ★ 同步封禁名单到PHP（供用户管理页面显示封禁状态）
     */
    private void syncBans() {
        if (!enabled) return;
        try {
            org.bukkit.BanList nameBanList = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
            org.bukkit.BanList ipBanList = Bukkit.getBanList(org.bukkit.BanList.Type.IP);

            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            StringBuilder hashBuilder = new StringBuilder();

            // 名字封禁
            @SuppressWarnings("unchecked")
            Set<org.bukkit.BanEntry<?>> nameEntries = (Set<org.bukkit.BanEntry<?>>)(Set<?>) nameBanList.getEntries();
            for (org.bukkit.BanEntry<?> entry : nameEntries) {
                if (!first) sb.append(",");
                first = false;
                String target = entry.getTarget();
                String reason = entry.getReason() != null ? entry.getReason() : "";
                String source = entry.getSource() != null ? entry.getSource() : "";
                long expireDate = entry.getExpiration() != null ? entry.getExpiration().getTime() : 0;
                sb.append("{\"type\":\"name\",\"target\":\"").append(escapeJson(target))
                  .append("\",\"reason\":\"").append(escapeJson(reason))
                  .append("\",\"source\":\"").append(escapeJson(source))
                  .append("\",\"expire\":").append(expireDate).append("}");
                hashBuilder.append("n:").append(target).append(":").append(reason).append(":").append(expireDate).append("|");
            }

            // IP封禁
            @SuppressWarnings("unchecked")
            Set<org.bukkit.BanEntry<?>> ipEntries = (Set<org.bukkit.BanEntry<?>>)(Set<?>) ipBanList.getEntries();
            for (org.bukkit.BanEntry<?> entry : ipEntries) {
                if (!first) sb.append(",");
                first = false;
                String target = entry.getTarget();
                String reason = entry.getReason() != null ? entry.getReason() : "";
                String source = entry.getSource() != null ? entry.getSource() : "";
                long expireDate = entry.getExpiration() != null ? entry.getExpiration().getTime() : 0;
                sb.append("{\"type\":\"ip\",\"target\":\"").append(escapeJson(target))
                  .append("\",\"reason\":\"").append(escapeJson(reason))
                  .append("\",\"source\":\"").append(escapeJson(source))
                  .append("\",\"expire\":").append(expireDate).append("}");
                hashBuilder.append("i:").append(target).append(":").append(reason).append(":").append(expireDate).append("|");
            }

            // ★ 双向对账（多退少补）：封禁名单与 PHP 一致就不发；
            //   Web 后台加的封禁（PHP 多的）拉回游戏内生效
            List<Map<String, Object>> banRows = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Set<org.bukkit.BanEntry<?>> nb0 = (Set<org.bukkit.BanEntry<?>>) (Set<?>) nameBanList.getEntries();
            for (org.bukkit.BanEntry<?> e0 : nb0) banRows.add(banRowOf(e0, "name"));
            @SuppressWarnings("unchecked")
            Set<org.bukkit.BanEntry<?>> ib0 = (Set<org.bukkit.BanEntry<?>>) (Set<?>) ipBanList.getEntries();
            for (org.bukkit.BanEntry<?> e0 : ib0) banRows.add(banRowOf(e0, "ip"));
            AlignResult arAlign = alignGate("bans", banRows);
            if (arAlign != null && !arAlign.needPush()) return;   // 零发送
            if (arAlign != null && arAlign.pulled > 0) return;    // 本轮只收不发

            sb.append("]");
            String currentHash = hashBuilder.toString();
            // ★ 对账明说要推时本地 hash 不得拦截（同 admins，2026-10-06 灾备修复）
            boolean alignNeedsPushBans = arAlign != null && arAlign.needPush();
            if (!alignNeedsPushBans && currentHash.equals(lastBansHash)) {
                plugin.getLogger().fine("[防护-sync] 封禁名单无变化，跳过");
                return;
            }

            // ★ 封禁IP → 顺手推给Web黑名单（web_ip_blacklist）
            //   Web端据此拦截被封IP的访问，除非该IP持有游戏内 /web 签发的token
            StringBuilder ipsb = new StringBuilder("[");
            boolean ipFirst = true;
            @SuppressWarnings("unchecked")
            Set<org.bukkit.BanEntry<?>> ipOnlyEntries = (Set<org.bukkit.BanEntry<?>>)(Set<?>) ipBanList.getEntries();
            for (org.bukkit.BanEntry<?> entry : ipOnlyEntries) {
                if (!ipFirst) ipsb.append(",");
                ipFirst = false;
                long expireDate = entry.getExpiration() != null ? entry.getExpiration().getTime() : 0;
                ipsb.append("{\"ip\":\"").append(escapeJson(entry.getTarget()))
                    .append("\",\"reason\":\"").append(escapeJson(entry.getReason() != null ? entry.getReason() : ""))
                    .append("\",\"source\":\"").append(escapeJson(entry.getSource() != null ? entry.getSource() : ""))
                    .append("\",\"expire\":").append(expireDate).append("}");
            }
            ipsb.append("]");

            // ★ 改用POST避免GET URL过长（55条封禁URL可达数KB）
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("secret", secretKey);
            body.put("bans", sb.toString());
            body.put("ips", ipsb.toString());
            String resp = httpPost("api/sync.php?action=sync_bans", mapToJson(body));
            plugin.getLogger().info("[防护-sync] 封禁名单同步: " + (nameEntries.size() + ipEntries.size())
                    + "条(IP黑名单 " + ipOnlyEntries.size() + "个) → " + resp);
            // ★ 关键修复：hash 成功后才提交（防 404 时把 hash 写脏 → 永久漏推，2026-10-06）
            if (resp != null && resp.contains("\"success\":true")) {
                lastBansHash = currentHash;
            } else {
                plugin.getLogger().warning("[防护-sync] 封禁名单同步未确认成功，下轮重试（hash 不提交）");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 封禁名单同步异常: " + e.getMessage());
        }
    }

    /**
     * ★ 从PHP拉取用户组配置到Java本地
     * PHP管理后台创建的用户组通过此方法同步到Java
     */
    public void pullUserGroupsFromPHP() {
        if (!enabled) return;
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "list_user_groups");
            params.put("secret", secretKey);
            String resp = httpGet("api/land_api.php", params);
            if (resp == null || resp.isEmpty()) { plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组: 响应为空"); return; }

            // 简单解析JSON: {"success":true,"groups":[{...},{...}]}
            if (!resp.contains("\"success\":true")) { plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组: success!=true, resp=" + resp.substring(0, Math.min(300, resp.length()))); return; }
            int groupsStart = resp.indexOf("\"groups\":[");
            if (groupsStart < 0) { plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组: 未找到groups数组, resp=" + resp.substring(0, Math.min(300, resp.length()))); return; }
            String arrStr = resp.substring(groupsStart + 10); // 跳过 "groups":[
            // ★ 修复：用{}计数来找数组的结束 ]（去掉开头[后内容是{...},{...}]，不含嵌套[]）
            int objDepth = 0;
            int arrEnd = -1;
            for (int i = 0; i < arrStr.length(); i++) {
                char c = arrStr.charAt(i);
                if (c == '{') objDepth++;
                else if (c == '}') objDepth--;
                else if (c == ']' && objDepth == 0) {
                    arrEnd = i;
                    break;
                }
            }
            if (arrEnd < 0) { plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组: 数组解析失败(未找到匹配的]), arrStr=" + arrStr.substring(0, Math.min(200, arrStr.length()))); return; }
            arrStr = arrStr.substring(0, arrEnd); // 保留对象内容，去掉 ] 和后续 }

            if (arrStr.trim().isEmpty()) { plugin.getLogger().fine("[防护-sync] 从PHP拉取用户组: 空数组"); return; } // 空数组

            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) { plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组: ugm==null"); return; }

            plugin.getLogger().fine("[防护-sync] 从PHP拉取用户组: 解析到 " + arrStr.length() + " 字符的数组内容");
            // 拆分每个JSON对象
            int imported = 0;
            int changed = 0;  // 实际发生变化的组数
            int parseErrors = 0;
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrStr.length(); i++) {
                char c = arrStr.charAt(i);
                if (c == '{' && depth == 0) { objStart = i; depth = 1; }
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = arrStr.substring(objStart + 1, i);
                        UserGroupManager.UserGroupConfig cfg = parseGroupJson(obj);
                        if (cfg != null && !cfg.name.isEmpty()) {
                            // ★ 对比现有配置，仅在有变化时才写入
                            UserGroupManager.UserGroupConfig existing = ugm.getGroupConfig(cfg.name);
                            boolean isDifferent = existing == null
                                    || !safeEq(existing.displayName, cfg.displayName)
                                    || !safeEq(existing.displayColor, cfg.displayColor)
                                    || existing.priority != cfg.priority
                                    || existing.landPricePerSqm != cfg.landPricePerSqm
                                    || existing.maxLands != cfg.maxLands
                                    || existing.homeLimit != cfg.homeLimit
                                    || existing.joinPrice != cfg.joinPrice
                                    || existing.autoRenew != cfg.autoRenew
                                    || existing.renewPrice != cfg.renewPrice
                                    || existing.durationMinutes != cfg.durationMinutes
                                    || !safeEq(existing.defaultPerms, cfg.defaultPerms);
                            if (isDifferent) {
                                ugm.saveGroupConfigToDB(cfg);
                                changed++;
                            }
                            imported++;
                            plugin.getLogger().fine("[防护-sync] 解析用户组: " + cfg.name + " (changed=" + isDifferent + ")");
                        } else {
                            parseErrors++;
                            plugin.getLogger().warning("[防护-sync] 用户组解析失败: " + obj.substring(0, Math.min(150, obj.length())));
                        }
                        objStart = -1;
                    }
                }
            }

            // ★ 删除PHP中已不存在的组：从PHP返回的完整JSON中解析所有组名
            Set<String> phpGroupNames = new HashSet<>();
            try {
                int idx = 0;
                while (idx < resp.length()) {
                    String key = "\"group_name\":\"";
                    int gi = resp.indexOf(key, idx);
                    if (gi < 0) break;
                    int start = gi + key.length();
                    int end = resp.indexOf("\"", start);
                    if (end > start) {
                        phpGroupNames.add(resp.substring(start, end));
                    }
                    idx = end > start ? end : start + 1;
                }
            } catch (Exception ignored) {}

            // 删除不在PHP列表中的本地组
            if (!phpGroupNames.isEmpty()) {
                int removed = ugm.removeGroupsNotIn(phpGroupNames);
                if (removed > 0) {
                    changed++;
                    plugin.getLogger().info("[防护-sync] 删除PHP中已不存在的用户组: " + removed + "个");
                }
            }

            // ★ 仅在有实际变化时才reload并打印日志
            if (changed > 0) {
                ugm.loadGroupConfigs();
                plugin.getLogger().info("[防护-sync] 从PHP拉取 " + imported + " 个用户组(" + changed + "个有变化)");
            } else if (parseErrors > 0) {
                plugin.getLogger().warning("[防护-sync] 从PHP拉取 " + imported + " 个用户组(" + parseErrors + "个解析失败)");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 从PHP拉取用户组异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从PHP拉取指定用户组的成员列表，同步到Java本地DB
     */
    public void pullGroupMembersFromPHP(String groupName) {
        if (!enabled || groupName == null || groupName.isEmpty()) return;
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "list_group_members");
            params.put("secret", secretKey);
            params.put("group", groupName);
            String resp = httpGet("api/land_api.php", params);
            if (resp == null || resp.isEmpty()) return;
            if (!resp.contains("\"success\":true")) return;

            UserGroupManager ugm = plugin.getUserGroup();
            if (ugm == null) return;

            // 解析 members 数组
            int membersStart = resp.indexOf("\"members\":[");
            if (membersStart < 0) return;
            String arrStr = resp.substring(membersStart + 11);
            int objDepth = 0;
            int arrEnd = -1;
            for (int i = 0; i < arrStr.length(); i++) {
                char c = arrStr.charAt(i);
                if (c == '{') objDepth++;
                else if (c == '}') objDepth--;
                else if (c == ']' && objDepth == 0) { arrEnd = i; break; }
            }
            if (arrEnd < 0) return;
            arrStr = arrStr.substring(0, arrEnd);
            if (arrStr.trim().isEmpty()) return;

            // 先清除Java本地该组的所有成员，再从PHP重新插入
            int deleted = ugm.clearGroupMembers(groupName);
            plugin.getLogger().info("[防护-sync] 已清除Java本地组 " + groupName + " 的 " + deleted + " 个旧成员");

            // 解析每个成员对象并插入
            int imported = 0;
            int depth = 0;
            int objStart = -1;
            for (int i = 0; i < arrStr.length(); i++) {
                char c = arrStr.charAt(i);
                if (c == '{' && depth == 0) { objStart = i; depth = 1; }
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0 && objStart >= 0) {
                        String obj = arrStr.substring(objStart + 1, i);
                        String player = extractJsonStringSafe(obj, "player_name");
                        String addedBy = extractJsonStringSafe(obj, "added_by");
                        String expiryStr = extractJsonField(obj, "expiry_time");
                        long expiryTime = 0;
                        if (!expiryStr.isEmpty()) {
                            try {
                                expiryTime = Long.parseLong(expiryStr) * 1000; // PHP存的是秒级时间戳
                            } catch (Exception e) {
                                // ignore
                            }
                        }
                        if (player != null && !player.isEmpty()) {
                            // 直接写入本地DB（不触发PHP推送，避免循环）
                            ugm.addPlayerLocalWithExpiry(player, groupName, addedBy, expiryTime);
                            imported++;
                        }
                        objStart = -1;
                    }
                }
            }
            plugin.getLogger().info("[防护-sync] 从PHP拉取组 " + groupName + " 成员: " + imported + " 人");
        } catch (Exception e) {
            plugin.getLogger().warning("[防护-sync] 从PHP拉取组成员异常: " + e.getMessage());
        }
    }

    /** 从JSON对象字符串解析用户组配置 */
    private UserGroupManager.UserGroupConfig parseGroupJson(String obj) {
        try {
            UserGroupManager.UserGroupConfig cfg = new UserGroupManager.UserGroupConfig();
            cfg.name = extractJsonStringSafe(obj, "group_name");
            cfg.displayName = decodeJsonUnicode(extractJsonStringSafe(obj, "display_name"));
            cfg.displayColor = decodeJsonUnicode(extractJsonStringSafe(obj, "display_color"));
            String priStr = extractJsonField(obj, "priority");
            if (!priStr.isEmpty()) try { cfg.priority = Integer.parseInt(priStr); } catch (Exception ignored) {}
            String priceStr = extractJsonField(obj, "land_price_per_sqm");
            if (!priceStr.isEmpty()) try { cfg.landPricePerSqm = Integer.parseInt(priceStr); } catch (Exception ignored) {}
            String maxStr = extractJsonField(obj, "max_lands");
            if (!maxStr.isEmpty()) try { cfg.maxLands = Integer.parseInt(maxStr); } catch (Exception ignored) {}
            String maxEffStr = extractJsonField(obj, "max_effects");
            if (!maxEffStr.isEmpty()) {
                try { cfg.maxEffects = Integer.parseInt(maxEffStr); }
                catch (Exception ignored) { cfg.maxEffects = 5; }
            }
            // ★ 药效等级上限（2026-10-04 任务4）
            String maxEffLvStr = extractJsonField(obj, "max_effect_level");
            if (!maxEffLvStr.isEmpty()) {
                try { cfg.maxEffectLevel = Integer.parseInt(maxEffLvStr); }
                catch (Exception ignored) { cfg.maxEffectLevel = UserGroupManager.DEFAULT_MAX_EFFECT_LEVEL; }
            }
            if (cfg.maxEffectLevel <= 0) cfg.maxEffectLevel = UserGroupManager.DEFAULT_MAX_EFFECT_LEVEL;
            String homeLimStr = extractJsonField(obj, "home_limit");
            if (!homeLimStr.isEmpty()) try { cfg.homeLimit = Integer.parseInt(homeLimStr); } catch (Exception ignored) {}
            String joinStr = extractJsonField(obj, "join_price");
            if (!joinStr.isEmpty()) try { cfg.joinPrice = Integer.parseInt(joinStr); } catch (Exception ignored) {}
            String autoRenStr = extractJsonField(obj, "auto_renew");
            if (!autoRenStr.isEmpty()) try { cfg.autoRenew = Integer.parseInt(autoRenStr) == 1; } catch (Exception ignored) {}
            String renewStr = extractJsonField(obj, "renew_price");
            if (!renewStr.isEmpty()) try { cfg.renewPrice = Integer.parseInt(renewStr); } catch (Exception ignored) {}
            String durStr = extractJsonField(obj, "duration_minutes");
            if (!durStr.isEmpty()) try { cfg.durationMinutes = Long.parseLong(durStr); } catch (Exception ignored) {}
            cfg.defaultPerms = extractJsonStringSafe(obj, "default_perms");
            return cfg;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解码JSON中的 \\uXXXX Unicode转义（如 \\u00a7 → §） */
    private String decodeJsonUnicode(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!s.contains("\\u")) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (i + 5 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                try {
                    String hex = s.substring(i + 2, i + 6);
                    int codePoint = Integer.parseInt(hex, 16);
                    sb.append((char) codePoint);
                    i += 5;
                } catch (NumberFormatException e) {
                    sb.append(s.charAt(i));
                }
            } else {
                sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    /** 从JSON字符串提取字符串字段值（返回null表示未找到） */
    private String extractJsonStringSafe(String json, String key) {
        String result = extractJsonString(json, key);
        return result != null ? result : "";
    }

    /** 从JSON字符串提取数值字段值 */
    private String extractJsonField(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ' ') break;
            end++;
        }
        return json.substring(start, end).trim();
    }

    // ==================== 异步玩家验证：拉取PHP待验证列表 ====================

    /**
     * 拉取PHP的pending_player_validations，验证后推回结果
     * PHP写入待验证 → Java拉取 → 查login.db → 推回结果
     */
    private void pullPendingPlayerValidations() {
        if (!enabled) return;
        try {
            String url = webBaseUrl + "/api/land_api.php?action=get_pending_validations&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(url);
            if (json == null || !json.contains("\"success\":true")) return;

            // 解析pending列表
            int pendingStart = json.indexOf("\"pending\":");
            if (pendingStart < 0) return;
            int arrStart = json.indexOf("[", pendingStart);
            int arrEnd = findMatchingBracket(json, arrStart);
            if (arrEnd < 0) return;
            String arrJson = json.substring(arrStart, arrEnd + 1);

            // 逐条处理
            DatabaseManager dbMgr = plugin.getDb();
            if (dbMgr == null) return;

            int idx = 0;
            while (true) {
                int objStart = arrJson.indexOf("{", idx);
                if (objStart < 0) break;
                int objEnd = findMatchingBracket(arrJson, objStart);
                if (objEnd < 0) break;
                String obj = arrJson.substring(objStart, objEnd + 1);

                String idStr = extractJsonString(obj, "id");
                String player = extractJsonString(obj, "player_name");
                String reqType = extractJsonString(obj, "request_type");

                if (idStr != null && player != null) {
                    int id = Integer.parseInt(idStr);
                    boolean exists = dbMgr.userExists(player);
                    String status = exists ? "valid" : "invalid";

                    // 推送结果回PHP（使用GET更可靠）
                    String callbackUrl = webBaseUrl + "/api/land_api.php?action=validation_callback&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8")
                            + "&id=" + id + "&status=" + status;
                    doGet(callbackUrl);

                    plugin.getLogger().info("[异步验证] 玩家 " + player + " → " + (exists ? "存在" : "不存在") + " (type=" + reqType + ")");
                }

                idx = objEnd + 1;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[异步验证] 轮询异常: " + e.getMessage());
        }
    }

    /**
     * 轮询PHP管理员变更（所有者变更、权限清除等）
     */
    private long lastPollAdminChangesId = 0;
    /** 已处理过的变更ID集合（防重复打印），最多保留500条 */
    private final java.util.HashSet<Integer> processedChangeIds = new java.util.HashSet<>();

    /** 进行中的过户追踪：key=landName, value=过户信息 */
    private final Map<String, TransferInfo> activeLandTransfers = new HashMap<>();

    public static class TransferInfo {
        String landName;
        String oldOwner;
        String newOwner;
        long expiresAt;       // cooldown到期时间戳(毫秒)
        int changeId;         // web_admin_changes的id
        int transferId;       // web_land_transfers的id
        /** 过户发起时的领地权限快照（JSON），用于检测冷却期间是否被修改 */
        String permissionsSnapshot;

        TransferInfo(String landName, String oldOwner, String newOwner, long expiresAt, int changeId, int transferId, String permissionsSnapshot) {
            this.landName = landName;
            this.oldOwner = oldOwner;
            this.newOwner = newOwner;
            this.expiresAt = expiresAt;
            this.changeId = changeId;
            this.transferId = transferId;
            this.permissionsSnapshot = permissionsSnapshot;
        }
    }

    public void pollAdminChanges() {
        if (!enabled) return;

        try {
            AreaProtection areaProtect = plugin.getAreaProtection();
            if (areaProtect == null) return;

            // 查询PHP端管理员变更
            String url = webBaseUrl + "/api/land_api.php?action=poll_admin_changes&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8") + "&last_id=" + lastPollAdminChangesId + "&limit=50";
            String response = doGet(url);
            if (response == null) return;

            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) return;

            List<Map<String, Object>> changes = (List<Map<String, Object>>) result.get("changes");
            if (changes == null || changes.isEmpty()) return;

            List<Integer> ackedIds = new ArrayList<>();
            int maxId = 0;

            for (Map<String, Object> change : changes) {
                int id = ((Number) change.getOrDefault("id", 0)).intValue();
                String changeType = String.valueOf(change.getOrDefault("change_type", ""));
                String targetName = String.valueOf(change.getOrDefault("target_name", ""));
                String changeDataStr = String.valueOf(change.getOrDefault("change_data", "{}"));

                if (id > maxId) maxId = id;

                // ★ 去重：已处理过的变更不再打印/执行
                if (processedChangeIds.contains(id)) {
                    ackedIds.add(id);
                    continue;
                }

                Map<String, Object> changeData = parseJson(changeDataStr);
                if (changeData == null) changeData = new HashMap<>();

                try {
                    boolean applied = false;
                    switch (changeType) {
                        case "owner_change": {
                            String newOwner = String.valueOf(changeData.getOrDefault("new_owner", ""));
                            String landName = targetName;
                            String source = String.valueOf(changeData.getOrDefault("source", ""));
                            int transferId = ((Number) changeData.getOrDefault("transfer_id", 0)).intValue();

                            if (newOwner.isEmpty() || landName.isEmpty()) break;

                            if ("player_transfer".equals(source) && transferId > 0) {
                                // ★ 玩家过户：需要验证新所有者 + cooldown + 回调PHP
                                applied = handlePlayerTransfer(landName, newOwner, id, transferId, changeData);
                            } else if ("transfer_cancelled".equals(source)) {
                                // ★ 过户取消：回退owner
                                areaProtect.setLandOwnerFromWeb(landName, newOwner);
                                activeLandTransfers.remove(landName);
                                plugin.getLogger().info("[Web通信] 过户取消回退: " + landName + " → " + newOwner);
                                applied = true;
                            } else {
                                // ★ 管理面板改主：验证新所有者 → 执行 → 回调PHP
                                // 1. 验证新所有者是否在login.db注册（权威数据源，不用Bukkit缓存）
                                boolean found = plugin.getDb() != null && plugin.getDb().userExists(newOwner);
                                if (!found) {
                                    plugin.getLogger().warning("[Web通信] 管理面板改主失败: 玩家 " + newOwner + " 不存在");
                                    // 回调PHP标记失败
                                    callbackOwnerChangeToPHP(id, false, "玩家 " + newOwner + " 不存在");
                                    applied = false;
                                    break;
                                }
                                // 2. 验证玩家注册时间必须超过5分钟（防止注册秒退玩家接收领地）
                                long registerTime = 0;
                                try {
                                    Object regTimeObj = plugin.getDb().getField(newOwner, "register_time");
                                    if (regTimeObj instanceof Number) {
                                        registerTime = ((Number) regTimeObj).longValue();
                                    }
                                } catch (Exception e) {
                                    // 忽略异常
                                }
                                if (registerTime > 0) {
                                    long now = System.currentTimeMillis();
                                    long fiveMinutesMs = 5 * 60 * 1000;
                                    if (now - registerTime < fiveMinutesMs) {
                                        long minutesLeft = (fiveMinutesMs - (now - registerTime)) / 60000;
                                        plugin.getLogger().warning("[Web通信] 管理面板改主失败: 玩家 " + newOwner + " 注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                                        callbackOwnerChangeToPHP(id, false, "玩家 " + newOwner + " 注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                                        applied = false;
                                        break;
                                    }
                                }
                                // 3. 执行改主
                                areaProtect.setLandOwnerFromWeb(landName, newOwner);
                                plugin.getLogger().info("[Web通信] PHP端领地所有者变更: " + landName + " → " + newOwner);
                                // 3. 回调PHP更新本地副本
                                callbackOwnerChangeToPHP(id, true, "");
                                // 4. 通知原主人（带撤回超链接）
                                String oldOwner = String.valueOf(changeData.getOrDefault("old_owner", ""));
                                if (!oldOwner.isEmpty()) {
                                    org.bukkit.entity.Player oldOwnerPlayer = Bukkit.getPlayerExact(oldOwner);
                                    if (oldOwnerPlayer != null && oldOwnerPlayer.isOnline()) {
                                        net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component.empty()
                                            .append(net.kyori.adventure.text.Component.text("§c§l[系统] §f§l你的领地 §e" + landName + " §f已被管理员变更为 §a" + newOwner))
                                            .append(net.kyori.adventure.text.Component.text(" "))
                                            .append(net.kyori.adventure.text.Component.text("§c§l[撤回]")
                                                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                                    net.kyori.adventure.text.Component.text("§e点击撤回此次改主")))
                                                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/protect canceladminchange " + landName)));
                                        oldOwnerPlayer.sendMessage(msg);
                                        plugin.getLogger().info("[Web通信] 已通知原主人 " + oldOwner + " 关于领地 " + landName + " 的改主");
                                    }
                                }
                                applied = true;
                            }
                            break;
                        }
                        case "perm_clear": {
                            String playerName = targetName;
                            String landNameJson = String.valueOf(changeData.getOrDefault("land_name", ""));
                            // ★ 防御：清除双重JSON编码引入的反斜杠
                            landNameJson = landNameJson.replace("\\", "");
                            if (!playerName.isEmpty() && !landNameJson.isEmpty()) {
                                areaProtect.clearPlayerPermFromWeb(landNameJson, playerName);
                                plugin.getLogger().info("[Web通信] PHP端清除成员权限: " + playerName + " @ " + landNameJson);
                                applied = true;
                            }
                            break;
                        }
                        case "perm_change": {
                            // handleUpdateVisitorPerm: target_name=玩家名，changeData 不含 player
                            // handleChangeVisitorRole: target_name=领地名，changeData 含 player
                            String playerName = changeData.containsKey("player")
                                    ? String.valueOf(changeData.get("player"))
                                    : targetName;
                            String landNameJson = String.valueOf(changeData.getOrDefault("land_name", ""));
                            String permsJson = String.valueOf(changeData.getOrDefault("permissions", "{}"));
                            String roleJson = String.valueOf(changeData.getOrDefault("role", ""));
                            if (!playerName.isEmpty() && !landNameJson.isEmpty()) {
                                // 防御：清除PHP双重JSON编码引入的反斜杠
                                landNameJson = landNameJson.replace("\\", "");
                                // ★ 确保玩家在白名单中（PHP端可能直接添加了成员但未走add_visitor流程）
                                areaProtect.addPlayerToAreaWhitelist(landNameJson, playerName);
                                areaProtect.updateVisitorPermFromWeb(landNameJson, playerName, permsJson, roleJson);
                                plugin.getLogger().info("[Web通信] PHP端更新访客权限: " + playerName + " @ " + landNameJson
                                        + (roleJson.isEmpty() ? "" : " role=" + roleJson));
                                applied = true;
                            }
                            break;
                        }
                        case "add_visitor": {
                            // ★ PHP端添加成员 → 先验证玩家是否存在，再添加到Java白名单+权限表
                            String playerName2 = changeData.containsKey("player")
                                    ? String.valueOf(changeData.get("player")) : targetName;
                            String landName2 = String.valueOf(changeData.getOrDefault("land_name", ""));
                            // ★ 防御：清除双重JSON编码引入的反斜杠
                            landName2 = landName2.replace("\\", "");
                            String role2 = String.valueOf(changeData.getOrDefault("role", "visitor"));
                            if (!playerName2.isEmpty() && !landName2.isEmpty()) {
                                // ★ 验证玩家是否存在（login.db）
                                boolean playerExists = plugin.getDb() != null && plugin.getDb().userExists(playerName2);
                                if (!playerExists) {
                                    plugin.getLogger().warning("[Web通信] PHP添加成员失败: 玩家 " + playerName2 + " 不存在于login.db");
                                    // ★ 回调PHP标记失败（无论回调是否成功，都ack此记录避免无限重试）
                                    callbackAddVisitorToPHP(id, false, "玩家 " + playerName2 + " 不存在");
                                    applied = true;  // ★ 必须ack，否则同一条记录会被无限重新处理
                                    break;
                                }
                                // ★ 玩家存在，执行添加
                                areaProtect.addPlayerToAreaWhitelist(landName2, playerName2);
                                // 写入area_land_permissions表：admin走setLandAdmin，其他角色走INSERT OR IGNORE
                                if ("admin".equalsIgnoreCase(role2)) {
                                    areaProtect.setLandAdmin(landName2, playerName2, true);
                                } else {
                                    areaProtect.insertLandPermission(landName2, playerName2, role2);
                                }
                                plugin.getLogger().info("[Web通信] PHP端添加成员: " + playerName2 + " → " + landName2 + " role=" + role2);
                                // ★ 回调PHP标记成功（无论回调是否成功，都ack此记录）
                                callbackAddVisitorToPHP(id, true, "");
                                applied = true;
                            } else {
                                plugin.getLogger().warning("[Web通信] PHP添加成员数据不完整: player=" + playerName2 + " land=" + landName2);
                                // ★ 数据不完整也必须ack，避免无限重试
                                applied = true;
                            }
                            break;
                        }
                        case "remove_visitor": {
                            // ★ PHP端移除成员 → 从Java白名单+权限表删除
                            String playerName3 = changeData.containsKey("player")
                                    ? String.valueOf(changeData.get("player")) : targetName;
                            String landName3 = String.valueOf(changeData.getOrDefault("land_name", ""));
                            // ★ 防御：清除双重JSON编码引入的反斜杠
                            landName3 = landName3.replace("\\", "");
                            if (!playerName3.isEmpty() && !landName3.isEmpty()) {
                                areaProtect.removePlayerFromAreaWhitelist(landName3, playerName3);
                                plugin.getLogger().info("[Web通信] PHP端移除成员: " + playerName3 + " ← " + landName3);
                                applied = true;
                            }
                            break;
                        }
                        case "land_field_change": {
                            String field = String.valueOf(changeData.getOrDefault("field", ""));
                            String value = String.valueOf(changeData.getOrDefault("value", ""));
                            if (!field.isEmpty() && !targetName.isEmpty()) {
                                areaProtect.updateLandFieldFromWeb(targetName, field, value);
                                plugin.getLogger().info("[Web通信] PHP端更新领地字段: " + targetName + "." + field);
                                applied = true;
                            }
                            break;
                        }
                        case "config_change": {
                            String configKey = String.valueOf(changeData.getOrDefault("key", ""));
                            String configValue = String.valueOf(changeData.getOrDefault("value", ""));
                            if (!configKey.isEmpty()) {
                                areaProtect.setAreaConfigValue(configKey, configValue);
                                plugin.getLogger().info("[Web通信] PHP端更新全局配置: " + configKey + " = " + configValue);
                                applied = true;
                            }
                            break;
                        }
                        case "group_change": {
                            // ★ PHP端用户组变更 → 根据action分别处理
                            String action = String.valueOf(changeData.getOrDefault("action", ""));
                            String groupName = String.valueOf(changeData.getOrDefault("group_name", ""));
                            String player = String.valueOf(changeData.getOrDefault("player", ""));

                            if ("add_member".equals(action) && !player.isEmpty()) {
                                // 添加成员：从PHP拉取该组成员
                                pullGroupMembersFromPHP(groupName);
                                plugin.getLogger().info("[Web通信] PHP端添加用户组成员(" + player + " → " + groupName + ")，已从PHP同步");
                            } else if ("remove_member".equals(action) && !player.isEmpty()) {
                                // 删除成员：直接从Java本地删除，并通知PHP确认
                                UserGroupManager ugm = plugin.getUserGroup();
                                if (ugm != null) {
                                    boolean removed = ugm.removePlayerLocal(player, groupName);
                                    if (removed) {
                                        plugin.getLogger().info("[Web通信] PHP端删除用户组成员(" + player + " ← " + groupName + ")，Java本地已删除");
                                    } else {
                                        plugin.getLogger().warning("[Web通信] PHP端删除用户组成员(" + player + " ← " + groupName + ")，Java本地未找到该成员");
                                    }
                                }
                            } else if ("delete".equals(action)) {
                                // 删除整个用户组：从PHP拉取（removeGroupsNotIn已处理）
                                pullUserGroupsFromPHP();
                                plugin.getLogger().info("[Web通信] PHP端删除用户组(" + groupName + ")，已同步");
                            } else if ("update".equals(action)) {
                                // 更新用户组配置：从PHP拉取
                                pullUserGroupsFromPHP();
                                plugin.getLogger().info("[Web通信] PHP端更新用户组(" + groupName + ")，已同步");
                            } else {
                                // 未知action，兜底全拉
                                pullUserGroupsFromPHP();
                                pullGroupMembersFromPHP(groupName);
                                plugin.getLogger().info("[Web通信] PHP端用户组变更(未知action=" + action + ": " + groupName + ")");
                            }
                            applied = true;
                            break;
                        }
                        case "land_delete": {
                            // ★ 管理面板删除领地：验证领地存在 → 删除 → 回调PHP
                            String deleteLandName = targetName;
                            if (!deleteLandName.isEmpty() && areaProtect.getLand(deleteLandName) != null) {
                                areaProtect.deleteLand(deleteLandName);
                                plugin.getLogger().info("[Web通信] 管理面板删除领地: " + deleteLandName);
                                // 回调PHP确认删除成功（使用GET更可靠）
                                String cbUrl = webBaseUrl + "/api/land_api.php?action=delete_land_callback&name=" + java.net.URLEncoder.encode(deleteLandName, "UTF-8") + "&success=true&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8");
                                doGet(cbUrl);
                                applied = true;
                            } else if (deleteLandName.isEmpty()) {
                                plugin.getLogger().warning("[Web通信] land_delete: 领地名为空");
                            } else {
                                // 领地不存在，直接回调PHP标记已处理
                                plugin.getLogger().info("[Web通信] land_delete: 领地 " + deleteLandName + " 不存在，标记已处理");
                                String cbUrl = webBaseUrl + "/api/land_api.php?action=delete_land_callback&name=" + java.net.URLEncoder.encode(deleteLandName, "UTF-8") + "&success=true&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8");
                                doGet(cbUrl);
                                applied = true;
                            }
                            break;
                        }
                        case "freeze": {
                            // ★ 玩家主动冻结账号（异地登录邮件触发）：Bukkit 原生封禁
                            String target = targetName;
                            if (!target.isEmpty()) {
                                String reason = "§c§l您的账号已被临时冻结（疑似被盗）\n§7请到网页修改密码后自动解冻";
                                Bukkit.getBanList(
                                        org.bukkit.BanList.Type.NAME)
                                        .addBan(target, reason,
                                                null, "security");
                                org.bukkit.entity.Player fp =
                                        Bukkit.getPlayerExact(target);
                                if (fp != null)
                                    fp.kickPlayer(reason);
                                plugin.getLogger().info(
                                        "[安全] 已按玩家请求冻结账号: "
                                                + target);
                                applied = true;
                            }
                            break;
                        }
                        case "unfreeze": {
                            // ★ 玩家改密后解冻：解除 Bukkit 原生封禁
                            String target = targetName;
                            if (!target.isEmpty()) {
                                Bukkit.getBanList(
                                        org.bukkit.BanList.Type.NAME)
                                        .pardon(target);
                                plugin.getLogger().info(
                                        "[安全] 已解冻账号: " + target);
                                applied = true;
                            }
                            break;
                        }
                        case "group_buy": {
                            // ★ 玩家端付费加入用户组：PHP写入pending → Java拉取执行
                            String buyGroup = String.valueOf(changeData.getOrDefault("group_name", ""));
                            String buyPlayer = String.valueOf(changeData.getOrDefault("player", ""));

                            if (buyGroup.isEmpty() || buyPlayer.isEmpty()) {
                                plugin.getLogger().warning("[Web通信] group_buy: 参数缺失");
                                break;
                            }

                            // 执行付费加入
                            UserGroupManager ugm2 = plugin.getUserGroup();
                            if (ugm2 != null) {
                                String err = ugm2.joinGroupByPrice(buyPlayer, buyGroup);
                                if (err != null) {
                                    plugin.getLogger().warning("[Web通信] 付费加入失败: " + err);
                                } else {
                                    plugin.getLogger().info("[Web通信] 玩家付费加入用户组: " + buyPlayer + " → " + buyGroup);
                                }
                            }
                            applied = true;
                            break;
                        }
                        case "group_renew": {
                            // ★ 玩家端续费用户组：PHP写入pending → Java拉取执行
                            String renewGroup = String.valueOf(changeData.getOrDefault("group_name", ""));
                            String renewPlayer = String.valueOf(changeData.getOrDefault("player", ""));
                            int renewPrice = ((Number) changeData.getOrDefault("renew_price", 0)).intValue();
                            int durationMinutes = ((Number) changeData.getOrDefault("duration_minutes", 0)).intValue();

                            if (renewGroup.isEmpty() || renewPlayer.isEmpty()) {
                                plugin.getLogger().warning("[Web通信] group_renew: 参数缺失");
                                break;
                            }

                            // 执行续费
                            UserGroupManager ugm = plugin.getUserGroup();
                            if (ugm != null) {
                                String err = ugm.renewGroup(renewPlayer, renewGroup);
                                if (err != null) {
                                    plugin.getLogger().warning("[Web通信] 续费失败: " + err);
                                } else {
                                    plugin.getLogger().info("[Web通信] 玩家续费用户组: " + renewPlayer + " → " + renewGroup);
                                }
                            }
                            applied = true;
                            break;
                        }
                        case "give_receipt_book": {
                            // ★ 收银台打包小票：PHP写入 → Java生成Written Book给在线玩家
                            String bookPlayer = targetName;
                            String orderNo = String.valueOf(changeData.getOrDefault("order_no", ""));
                            String orderTime = String.valueOf(changeData.getOrDefault("order_time", ""));
                            String orderPlayer = String.valueOf(changeData.getOrDefault("order_player", ""));
                            String operatorName = String.valueOf(changeData.getOrDefault("operator", ""));
                            String settlementMode = String.valueOf(changeData.getOrDefault("settlement", ""));
                            String payMethod = String.valueOf(changeData.getOrDefault("pay_method", ""));
                            int totalPrice = ((Number) changeData.getOrDefault("total_price", 0)).intValue();
                            String itemsText = String.valueOf(changeData.getOrDefault("items_text", ""));

                            if (bookPlayer.isEmpty()) {
                                plugin.getLogger().warning("[小票书] give_receipt_book: 缺少目标玩家");
                                break;
                            }

                            // ★ 2026-07-08 合并发货修复（兼容原始代码/中文标签两种推送值）：
                            //   PHP 推送的 settlement 可能是原始代码(shulker/backpack)，
                            //   也可能是中文标签(潜影盒打包 / 塞背包（环保单）)，两种都需拦截。
                            //   打包(潜影盒)模式：小票已通过 handleBuyCart 的 addBookToShulker 塞入【商品潜影盒】内，
                            //     此处若再单独发放"小票潜影盒"会导致玩家拿到两个潜影盒（商品+小票 / 仅小票）。
                            //   不打包(塞背包)模式：按需求跳过发放小票书。
                            //   因此两种结算模式下都【不再单独发放小票潜影盒】，直接标记已处理避免反复拉取。
                            boolean isShulker = "shulker".equals(settlementMode) || settlementMode.contains("潜影盒");
                            boolean isBackpack = "backpack".equals(settlementMode) || settlementMode.contains("背包");
                            if (isShulker || isBackpack) {
                                plugin.getLogger().info("[小票书] 结算模式=" + settlementMode
                                        + "，小票已并入商品潜影盒(打包)或在背包模式跳过，不再单独发放小票潜影盒 (玩家=" + bookPlayer + ")");
                                applied = true;
                                break;
                            }

                            org.bukkit.entity.Player target = Bukkit.getPlayerExact(bookPlayer);
                            if (target == null || !target.isOnline()) {
                                plugin.getLogger().info("[小票书] 玩家 " + bookPlayer + " 不在线，跳过发书");
                            } else {
                                giveReceiptBook(target, orderNo, orderTime, orderPlayer, operatorName,
                                        settlementMode, payMethod, totalPrice, itemsText);
                                applied = true;
                            }
                            break;
                        }
                        default:
                            plugin.getLogger().fine("[Web通信] 未知变更类型: " + changeType);
                    }
                    // ★ 标记已处理（无论applied与否，防止无效条目反复拉取）
                    processedChangeIds.add(id);
                    ackedIds.add(id);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web通信] 处理变更失败(id=" + id + "): " + e.getMessage());
                    processedChangeIds.add(id);
                    ackedIds.add(id);
                }
            }

            // ★ 更新lastId游标，下次跳过已处理的
            if (maxId > lastPollAdminChangesId) {
                lastPollAdminChangesId = maxId;
            }

            // ★ 无论是否有实际变更，都发送ack（防止无效条目永久堆积）
            if (!ackedIds.isEmpty()) {
                String idsStr = String.join(",", ackedIds.stream().map(String::valueOf).collect(Collectors.toList()));
                String ackUrl = webBaseUrl + "/api/land_api.php?action=ack_admin_changes&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8") + "&ids=" + java.net.URLEncoder.encode(idsStr, "UTF-8");
                doGet(ackUrl);
            }

            // ★ 清理去重集合：只清理已过游标的旧ID，防止ACK失败后重复打印
            if (processedChangeIds.size() > 500) {
                processedChangeIds.removeIf(id -> id < lastPollAdminChangesId);
                // 如果清理后仍然过大，才全部清空（最后手段）
                if (processedChangeIds.size() > 500) processedChangeIds.clear();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 轮询PHP管理员变更异常: " + e.getMessage());
        }
    }

    /**
     * 处理玩家过户请求：验证新所有者 → 通过则改DB+回调PHP → 追踪cooldown
     */
    private boolean handlePlayerTransfer(String landName, String newOwner, int changeId, int transferId, Map<String, Object> changeData) {
        try {
            DatabaseManager dbMgr = plugin.getDb();
            if (dbMgr == null) {
                plugin.getLogger().warning("[过户] DatabaseManager不可用，无法验证玩家");
                notifyTransferCallback(transferId, "failed", "服务端数据库不可用");
                return false;
            }

            // ★ 验证新所有者是否存在
            boolean exists = dbMgr.userExists(newOwner);
            if (!exists) {
                plugin.getLogger().info("[过户] 验证失败: 玩家 " + newOwner + " 不存在");
                notifyTransferCallback(transferId, "failed", "玩家 " + newOwner + " 尚未注册");
                return false;
            }

            // ★ 验证玩家注册时间必须超过5分钟（防止注册秒退玩家接收领地）
            long registerTime = 0;
            try {
                Object regTimeObj = dbMgr.getField(newOwner, "register_time");
                if (regTimeObj instanceof Number) {
                    registerTime = ((Number) regTimeObj).longValue();
                }
            } catch (Exception e) {
                // 忽略异常
            }
            if (registerTime > 0) {
                long now = System.currentTimeMillis();
                long fiveMinutesMs = 5 * 60 * 1000;
                if (now - registerTime < fiveMinutesMs) {
                    long minutesLeft = (fiveMinutesMs - (now - registerTime)) / 60000;
                    plugin.getLogger().info("[过户] 验证失败: 玩家 " + newOwner + " 注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                    notifyTransferCallback(transferId, "failed", "玩家 " + newOwner + " 注册时间不足5分钟（还差" + minutesLeft + "分钟）");
                    return false;
                }
            }

            // ★ 验证通过：改Java本地DB
            AreaProtection areaProtect = plugin.getAreaProtection();
            if (areaProtect == null) {
                notifyTransferCallback(transferId, "failed", "防护模块不可用");
                return false;
            }

            // 获取领地权限快照（用于cooldown期间检测变更）
            String permSnapshot = areaProtect.getLandPermissionsSnapshot(landName);

            areaProtect.setLandOwnerFromWeb(landName, newOwner);

            // ★ 回调PHP：验证通过
            notifyTransferCallback(transferId, "success", "");

            // ★ 追踪cooldown
            int cooldown = ((Number) changeData.getOrDefault("cooldown", 60)).intValue();
            long expiresAt = System.currentTimeMillis() + (cooldown * 1000L);
            activeLandTransfers.put(landName, new TransferInfo(
                landName,
                String.valueOf(changeData.getOrDefault("old_owner", "")),
                newOwner,
                expiresAt,
                changeId,
                transferId,
                permSnapshot
            ));

            plugin.getLogger().info("[过户] 验证通过: " + landName + " → " + newOwner + "，cooldown " + cooldown + "秒");
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[过户] 处理过户异常: " + e.getMessage());
            notifyTransferCallback(transferId, "failed", "服务端异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通知PHP过户验证结果
     */
    private void notifyTransferCallback(int transferId, String result, String reason) {
        try {
            String url = webBaseUrl + "/api/land_api.php?action=transfer_callback&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                + "&transfer_id=" + transferId
                + "&result=" + java.net.URLEncoder.encode(result, "UTF-8")
                + "&reason=" + java.net.URLEncoder.encode(reason, "UTF-8");
            // ★ 使用GET代替POST，更可靠（避免SSL body问题）
            String response = doGet(url);
            plugin.getLogger().fine("[过户] 回调PHP: transfer_id=" + transferId + ", result=" + result);
        } catch (Exception e) {
            plugin.getLogger().warning("[过户] 回调PHP失败: " + e.getMessage());
        }
    }

    /**
     * ★ 回调PHP：管理面板改主执行结果
     */
    public void callbackOwnerChangeToPHP(int changeId, boolean success, String reason) {
        try {
            String url = webBaseUrl + "/api/land_api.php?action=owner_change_callback&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                + "&change_id=" + changeId
                + "&success=" + (success ? "1" : "0")
                + "&reason=" + java.net.URLEncoder.encode(reason, "UTF-8");
            // ★ 使用GET代替POST，更可靠（避免SSL body问题）
            String response = doGet(url);
            plugin.getLogger().fine("[Web通信] 回调PHP改主结果: change_id=" + changeId + ", success=" + success);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 回调PHP改主结果失败: " + e.getMessage());
        }
    }

    /**
     * ★ 回调PHP：添加成员执行结果
     */
    public void callbackAddVisitorToPHP(int changeId, boolean success, String reason) {
        try {
            String url = webBaseUrl + "/api/land_api.php?action=add_visitor_callback&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                + "&change_id=" + changeId
                + "&success=" + (success ? "1" : "0")
                + "&reason=" + java.net.URLEncoder.encode(reason, "UTF-8");
            String response = doGet(url);
            if (response == null) {
                // ★ 首次失败：尝试HTTP降级重试
                plugin.getLogger().warning("[Web通信] 回调PHP添加成员结果失败(HTTPS无响应), 尝试HTTP降级: change_id=" + changeId);
                response = doGetHttpFallback(url);
            }
            if (response == null) {
                plugin.getLogger().warning("[Web通信] 回调PHP添加成员结果失败(HTTPS+HTTP均失败): change_id=" + changeId);
            } else {
                plugin.getLogger().info("[Web通信] 回调PHP添加成员结果: change_id=" + changeId + ", success=" + success + ", response=" + response.substring(0, Math.min(response.length(), 100)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 回调PHP添加成员结果异常: change_id=" + changeId + ", " + e.getMessage());
        }
    }

    /**
     * 追踪过户（Java端发起时调用）
     */
    public void trackTransfer(String landName, TransferInfo info) {
        activeLandTransfers.put(landName, info);
    }

    /** ★ 获取进行中的过户信息 */
    public TransferInfo getActiveTransfer(String landName) {
        return activeLandTransfers.get(landName);
    }

    /**
     * 取消过户：回退owner + 通知PHP
     * @return true 如果有进行中的过户被取消
     */
    public boolean cancelTransfer(String landName, String operator) {
        TransferInfo info = activeLandTransfers.remove(landName);
        if (info == null) return false;

        // 回退owner
        AreaProtection areaProtect = plugin.getAreaProtection();
        if (areaProtect != null) {
            areaProtect.setLandOwnerFromWeb(landName, info.oldOwner);
        }

        // 通知PHP
        if (info.transferId > 0) {
            notifyTransferCallback(info.transferId, "failed", "玩家主动取消");
        }

        plugin.getLogger().info("[过户] " + operator + " 取消过户: " + landName + " → " + info.newOwner + "，已回退为 " + info.oldOwner);
        return true;
    }

    /**
     * 定期检查：cooldown期间如果领地权限被修改，则取消过户
     * 由定时器每5秒调用一次
     */
    public void handlePendingTransferCancellations() {
        if (activeLandTransfers.isEmpty()) return;

        AreaProtection areaProtect = plugin.getAreaProtection();
        if (areaProtect == null) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, TransferInfo>> it = activeLandTransfers.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, TransferInfo> entry = it.next();
            TransferInfo info = entry.getValue();

            // 检查cooldown是否已过
            if (now >= info.expiresAt) {
                // cooldown结束，过户完成
                plugin.getLogger().info("[过户] 冷却完成: " + info.landName + " → " + info.newOwner);
                it.remove();
                continue;
            }

            // ★ 检查领地权限是否在cooldown期间被修改
            String currentSnapshot = areaProtect.getLandPermissionsSnapshot(info.landName);
            if (currentSnapshot != null && info.permissionsSnapshot != null && !currentSnapshot.equals(info.permissionsSnapshot)) {
                // 权限被修改了，取消过户！
                plugin.getLogger().info("[过户] 冷却期间权限被修改，取消过户: " + info.landName);

                // 回退owner
                areaProtect.setLandOwnerFromWeb(info.landName, info.oldOwner);

                // 通知PHP取消（写admin_changes让PHP处理）
                notifyTransferCallback(info.transferId, "failed", "冷却期间权限被修改");

                it.remove();
            }
        }
    }

    /**
     * 5. 推送债券余额快照到Web端
     */
    public void syncBondBalances() {
        if (!enabled) return;

        try {
            BondManager bondMgr = plugin.getBonds();
            if (bondMgr == null) return;

            List<String> allPlayers = bondMgr.getAllPlayerNames();

            Map<String, Object> bonds = new LinkedHashMap<>();
            for (String name : allPlayers) {
                bonds.put(name, bondMgr.getBonds(name));
            }

            // ★ 双向对账（多退少补）：余额与 PHP 一致就不发；
            //   网页/CDK/后台改过的余额先拉回本地
            List<Map<String, Object>> bondRows = new ArrayList<>();
            for (Map.Entry<String, Object> be : bonds.entrySet()) {
                Map<String, Object> r0 = new LinkedHashMap<>();
                r0.put("player_name", be.getKey());
                r0.put("amount", be.getValue());
                bondRows.add(r0);
            }
            AlignResult arAlign = alignGate("bonds", bondRows);
            if (arAlign != null && !arAlign.needPush()) return;
            if (arAlign != null && arAlign.pulled > 0) {
                // 本轮只收不发：避免拿对账前的旧余额把刚收回来的余额推回去
                return;
            }

            // ★ 无变化静默：对比债券数据hash
            String currentHash = bonds.size() + ":" + bonds.hashCode();
            // ★ 对账明说要推时本地 hash 不得拦截（同 admins，2026-10-06 灾备修复）
            boolean alignNeedsPushBonds = arAlign != null && arAlign.needPush();
            if (!alignNeedsPushBonds && currentHash.equals(lastBondBalanceHash)) return; // 无变化，跳过
            // ★ 关键修复：hash 成功后才提交（防假成功，见 syncUserRegistrations 注释）

            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bonds", bonds);

            String response = httpPostWithToken("api/sync.php?action=sync_bonds", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    lastBondBalanceHash = currentHash; // ← 成功后才落 hash
                    plugin.getLogger().info("[Web通信] 债券余额变更，已同步: " + bonds.size() + "人");
                } else {
                    plugin.getLogger().warning("[Web通信] 债券同步失败: " + result.get("message"));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 债券同步异常: " + e.getMessage());
        }
    }

    // ==================== 游戏内交易记录同步到Web ====================

    /**
     * 推送游戏内交易记录（bond_transaction）到PHP端
     * 使用 last_synced_tx_time 追踪已同步的最晚时间，增量推送
     * 注意：shop_buy合并逻辑会UPDATE已有记录的时间，所以用时间追踪能确保合并后的记录也被同步
     */
    private volatile long lastSyncedTxTime = 0;

    // ★ 交易推送改为「节流 + 两边点验」（2026-10-04）
    //   1) 不再盲目全量重推：开服先与 PHP 对账，对得上就完全跳过；
    //   2) 推进过程异步逐批进行，每批之间让出线程 → DB 工作线程不会被占死，其他同步任务不受影响；
    //   3) 每批推之前先问 PHP「这批序列号你有了吗」，只补缺的那几笔，已有的一律不重推。
    private final AtomicBoolean txBatchRunning = new AtomicBoolean(false);
    private volatile boolean txRescanRequested = false;
    private volatile boolean txAlignmentPending = false;

    /** 节流：纯对账（不推送）的批次走短间隔；真正推了 PHP 的批次走长间隔，别把 PHP 压太狠 */
    private static final long TX_SYNC_VERIFY_STEP_MS = 400;

    // ★ 回执窗口（2026-10-05 阶段D）：大笔数推流时 PHP 可能还在处理上一批，
    //   连续硬推会吃 500（database is locked）。改为——
    //   推一批 → 等 PHP 回执（HTTP success 即回执）→ 收到才推下一批并再等 15 秒；
    //   没收到回执则等 15 秒后重试【同一批】（不推进水位线，不丢数据）。
    /** 真推了 PHP 且收到回执：下一批前的冷却窗口 */
    private static final long TX_SYNC_RECEIPT_STEP_MS = 15000;
    /** 没收到回执：等这么久后重试同一批 */
    private static final long TX_SYNC_RECEIPT_RETRY_MS = 15000;
    /** 同一批连续重试上限（15s × 20 ≈ 5 分钟），超限才中止本轮，下轮从原位置继续 */
    private static final int TX_SYNC_MAX_BATCH_RETRIES = 20;

    // ===== 阶段E（2026-10-05）：15 秒必答复 + 轮次自愈 =====
    /** 交易对账/推送走单次请求：15 秒内 PHP 必须给答复（成功/失败/报错都算），否则视为无答复 */
    private static final int TX_SYNC_HTTP_TIMEOUT_S = 15;
    /** 轮次看门狗：距上次进度超过这么久视为卡死，热重载/reload 时可强制作废旧轮 */
    private static final long TX_BATCH_STALE_MS = 90000;
    /** 当前推进轮次 ID：延迟任务带上它，旧轮被作废后自动失效（防旧轮复活） */
    private volatile int txBatchRunId = 0;
    private final java.util.concurrent.atomic.AtomicInteger txBatchRunSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);
    /** 最近一次批次进度时间（每进一次批次刷新），卡死判定依据 */
    private volatile long txBatchLastProgress = 0;
    /** 对账发现两边有缺口 → 本轮取全量逐批点验，只补 PHP 真正缺的（不再靠归零水位线盲推） */
    private volatile boolean txFullScanPending = false;

    /**
     * ★ 周期对账节流（2026-10-06）：交易流水原本只在开服时点一次账，
     *   PHP 端 web.db 在运行期被重载/清空后，本机水位线仍停在最新 →
     *   每轮只推增量，历史流水永远补不回去，日志却显示「已对齐」（假对齐）。
     *   每隔这个间隔重新点一次账，发现 PHP 真丢了数据就转入全量点验。
     */
    private static final long TX_RECONCILE_INTERVAL_MS = 5 * 60 * 1000L;
    /** 上次周期对账时间（0=还没对过；无论成败都占位，避免给 DB 队列加压） */
    private volatile long lastTxReconcileAt = 0L;

    public void syncBondTransactions() {
        if (!enabled) return;

        // ★ 高频出售缓冲模式：跳过周期推送，等待缓冲期满后由监听器统一推送
        if (inHighFreqBufferMode) {
            return;
        }

        // ★ 启动对账没做成（当时 PHP 无响应）：先补做一次，对齐前不碰历史数据
        if (txAlignmentPending) {
            txAlignmentPending = false;
            alignTxWatermarkOnBoot();
        }

        // ★ 已有推进在跑：不打断、不重复起跑，只标记「跑完再扫一轮」；
        //   但上一轮若已超过 90 秒毫无进度（卡死），强制作废重启——
        //   否则热重载/reload 之后永远起不了新轮，只能靠手动 reload 才恢复。
        if (!claimTxBatchRun()) return;

        boolean handedOff = false;
        try {
            BondManager bondMgr = plugin.getBonds();
            if (bondMgr == null) return;

            // 读取上次同步的最晚时间
            loadLastSyncedTxTime();

            // ★ 周期对账：本轮若还没有全量点验在排队，先跟 PHP 核一次账，
            //   发现「PHP 缺的比本机待推的还多」→ 说明 PHP 运行期丢了历史，转全量点验补回
            if (!txFullScanPending) {
                maybePeriodicTxReconcile(bondMgr);
            }

            // 获取交易记录：
            //   常规 = 水位线之后的增量；
            //   对账发现缺口（txFullScanPending）= 取全部，由逐批「两边点验」只补 PHP 真正缺的，
            //   绝不靠归零水位线盲推（多退少补：PHP 有的不重推，PHP 缺的才推）。
            List<Map<String, Object>> txs = txFullScanPending
                    ? bondMgr.getTransactionsAfterTime(0)
                    : bondMgr.getTransactionsAfterTime(lastSyncedTxTime);
            if (txs.isEmpty()) {
                txFullScanPending = false;
                return;
            }

            // ★ 整轮推进丢给异步调度，DB 工作线程立刻返回（不阻塞其他同步任务）
            startTxBatchRun(txs);
            handedOff = true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 异常: " + e.getMessage());
        } finally {
            if (!handedOff) finishTxBatchRun();
        }
    }


    /**
     * 抢占一轮推进的执行权。
     * 无条件抢占失败时再看「上一轮是不是卡死了」——超过 TX_BATCH_STALE_MS 毫秒没有进度
     * 就把旧轮作废（txBatchRunId 递增，旧的延迟任务醒来后自动退出）后重抢，
     * 这样热重载/reload 之后不必手动 reload 也能自己恢复。
     */
    private boolean claimTxBatchRun() {
        if (txBatchRunning.compareAndSet(false, true)) {
            return true;
        }
        long now = System.currentTimeMillis();
        long idle = now - txBatchLastProgress;
        if (txBatchLastProgress > 0 && idle > TX_BATCH_STALE_MS) {
            plugin.getLogger().warning("[Web交易同步] ★ 上一轮已 " + (idle / 1000)
                    + " 秒没有任何进度（疑似卡死），强制作废旧轮并重启本轮");
            txBatchRunId = txBatchRunSeq.incrementAndGet();   // 作废旧轮
            txBatchRunning.set(false);
            if (txBatchRunning.compareAndSet(false, true)) {
                return true;
            }
        }
        txRescanRequested = true;
        return false;
    }


    /**
     * 启动一轮逐批推进：每批之间异步让出 + 节流，绝不长时间占住 DB 工作线程
     */
    private void startTxBatchRun(List<Map<String, Object>> txs) {
        final List<Map<String, Object>> queue = new ArrayList<>(txs);
        final int total = queue.size();
        final int batchCount = (total + TX_SYNC_BATCH_SIZE - 1) / TX_SYNC_BATCH_SIZE;
        final int myRunId = txBatchRunSeq.incrementAndGet();
        txBatchRunId = myRunId;
        txBatchLastProgress = System.currentTimeMillis();
        plugin.getLogger().info("[Web交易同步] ▶ 启动本轮推进: 共 " + total + " 笔 / "
                + batchCount + " 批（先点验后补推，多退少补）");

        new BukkitRunnable() {
            int index = 0;
            int okCount = 0;
            int pushedCount = 0;
            /** 当前批的连续失败次数（拿到回执推进后清零） */
            int batchRetries = 0;

            @Override
            public void run() {
                // ★ 本轮已被作废（卡死重启 / 新一轮已启动）：静默退出，绝不碰共享状态
                if (myRunId != txBatchRunId) return;

                // 开关被关 / 插件正在关闭：直接收尾，别把线程挂住
                if (!enabled || !txBatchRunning.get()) {
                    finishTxBatchRun();
                    return;
                }

                // ★ 心跳：每次进入批次都刷新进度时间，卡死判定的依据
                txBatchLastProgress = System.currentTimeMillis();

                if (index >= queue.size()) {
                    txFullScanPending = false;
                    plugin.getLogger().info("[Web交易同步] ✓ 本轮核对完成: " + okCount + "/" + total
                            + " 笔已对齐（实际补推 " + pushedCount + " 笔）");
                    finishTxBatchRun();
                    return;
                }

                // ★ 批号由 index 推导：重试同一批时批号保持不变，日志不虚增
                final int batchNo = index / TX_SYNC_BATCH_SIZE + 1;
                List<Map<String, Object>> batch =
                        queue.subList(index, Math.min(index + TX_SYNC_BATCH_SIZE, queue.size()));

                plugin.getLogger().info("[Web交易同步] ● 第" + batchNo + "/" + batchCount
                        + "批开始（" + batch.size() + " 笔，索引 " + index + "/" + total
                        + "，本批已重试 " + batchRetries + "/" + TX_SYNC_MAX_BATCH_RETRIES + "）");

                boolean ok;
                int pushed;
                try {
                    int[] r = syncTxBatch(batch, total, batchNo, batchCount);
                    ok = (r[0] == 1);
                    pushed = r[1];
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易同步] 第" + batchNo + "批 异常: " + e.getMessage());
                    ok = false;
                    pushed = 0;
                }

                if (!ok) {
                    // ★ 没拿到 PHP 回执（15 秒内无答复 / PHP 明确答复失败）：不中止、不推进水位线，
                    //   等 15 秒后重试【同一批】；连续超限才收尾，剩余笔数下轮继续（不丢数据）
                    batchRetries++;
                    if (batchRetries > TX_SYNC_MAX_BATCH_RETRIES) {
                        plugin.getLogger().warning("[Web交易同步] ✗ 第" + batchNo
                                + "批连续 " + TX_SYNC_MAX_BATCH_RETRIES + " 次没拿到回执，本轮中止，剩余 "
                                + (total - okCount) + " 笔将在下轮重试（不丢数据）");
                        finishTxBatchRun();
                        return;
                    }
                    plugin.getLogger().warning("[Web交易同步] 第" + batchNo + "批没拿到回执，"
                            + (TX_SYNC_RECEIPT_RETRY_MS / 1000) + " 秒后重试同一批（第 "
                            + batchRetries + "/" + TX_SYNC_MAX_BATCH_RETRIES + " 次）");
                    scheduleTxStep(this, TX_SYNC_RECEIPT_RETRY_MS, myRunId);
                    return;
                }

                batchRetries = 0;
                okCount += batch.size();
                pushedCount += pushed;
                index += TX_SYNC_BATCH_SIZE;

                // ★ 回执节奏：HTTP success 即回执，收到才推下一批；
                //   真推了 PHP 的批次再冷却 15 秒让 PHP 消化，纯对账跳过的批次走短间隔
                long stepMs = pushed > 0 ? TX_SYNC_RECEIPT_STEP_MS : TX_SYNC_VERIFY_STEP_MS;
                plugin.getLogger().info("[Web交易同步] 第" + batchNo + "批回执已收到（补推 "
                        + pushed + " 笔），" + (stepMs / 1000) + " 秒后继续下一批");
                scheduleTxStep(this, stepMs, myRunId);
            }
        }.runTaskLaterAsynchronously(plugin, 1L);
    }


    /**
     * 调度下一批。
     * ★ 关键修正（2026-10-05 阶段E）：runTaskLaterAsynchronously 第二个参数单位是
     *   tick（1 tick = 50ms），旧代码把 delayMs 直接当 tick 传 → 15 秒被排成
     *   15000 tick = 12.5 分钟，表现就是「回执收到了却一直不推下一批、也不报错」。
     *   这里统一按 毫秒/50 换算，并带上 runId 防旧轮复活。
     */
    private void scheduleTxStep(Runnable step, long delayMs, int runId) {
        long wait = Math.max(1L, delayMs);
        long busyLeft = phpBusyUntil - System.currentTimeMillis();
        if (busyLeft > 0) {
            wait = Math.max(wait, busyLeft + 2000);
        }
        long ticks = Math.max(1L, wait / 50L);
        try {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (runId != txBatchRunId) return;   // 本轮已作废：不再往下推
                    step.run();
                }
            }.runTaskLaterAsynchronously(plugin, ticks);
        } catch (Exception e) {
            if (runId == txBatchRunId) {
                plugin.getLogger().warning("[Web交易同步] 批次调度失败，本轮中止: " + e.getMessage());
                finishTxBatchRun();
            }
        }
    }


    /** 一轮推进结束：释放运行标志；期间有新交易到达则延时再扫一轮 */
    private void finishTxBatchRun() {
        txBatchRunning.set(false);
        if (txRescanRequested) {
            txRescanRequested = false;
            plugin.getLogger().info("[Web交易同步] 推进期间产生新交易，200 毫秒后补扫一轮");
            try {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        syncBondTransactions();
                    }
                }.runTaskLaterAsynchronously(plugin, 4L);   // 200ms = 4 tick
            } catch (Exception ignored) {
            }
        }
    }


    /**
     * 处理一批交易：先与 PHP 点验这批序列号，只补推缺失部分，成功后推进水位线
     * 返回 int[]{ 成功?(1:0), 实际补推笔数 }
     */
    private int[] syncTxBatch(List<Map<String, Object>> batch, int total, int batchNo, int batchCount) {
        List<Long> ids = new ArrayList<>(batch.size());
        for (Map<String, Object> tx : batch) {
            Object iv = tx.get("id");
            if (iv instanceof Number) ids.add(((Number) iv).longValue());
        }

        // ★ 两边点一遍：问 PHP 这批序列号缺哪些
        //   null = 15 秒内没拿到答复 / PHP 明确答复失败 → 本批按失败处理，
        //   等 15 秒重试同一批（绝不拿「没答复」当「全都缺」硬推一遍）
        Set<Long> missing = fetchMissingTxIds(ids);
        if (missing == null) {
            return new int[]{0, 0};
        }

        List<Map<String, Object>> toPush = batch;
        if (missing.isEmpty()) {
            // PHP 已经全都有 → 一笔都不推，只推进水位线（本批只花 1 次轻量对账请求）
            advanceTxWatermark(batch);
            plugin.getLogger().info("[Web交易同步] 第" + batchNo + "/" + batchCount
                    + "批 PHP 已齐全（" + batch.size() + " 笔），跳过推送");
            return new int[]{1, 0};
        }
        if (missing.size() < batch.size()) {
            toPush = new ArrayList<>(missing.size());
            for (Map<String, Object> tx : batch) {
                Object iv = tx.get("id");
                if (iv instanceof Number && missing.contains(((Number) iv).longValue())) {
                    toPush.add(tx);
                }
            }
            plugin.getLogger().info("[Web交易同步] 第" + batchNo + "/" + batchCount
                    + "批 仅缺 " + toPush.size() + "/" + batch.size() + " 笔，只补推缺失部分");
        }

        if (!pushTxBatch(toPush, total, batchNo, batchCount)) {
            return new int[]{0, 0};
        }
        advanceTxWatermark(batch);
        return new int[]{1, toPush.size()};
    }


    /**
     * 问 PHP 缺哪些交易序列号（交易序列号 = 本机 bond_transaction.id）
     * 返回 null  = 本次对账不可用（网络/PHP 异常），调用方按「整批推送」处理
     * 返回空集合 = PHP 全部已有，无需补推
     */
    private Set<Long> fetchMissingTxIds(List<Long> ids) {
        if (ids.isEmpty()) return new LinkedHashSet<>();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", ids);
            // ★ 多退少补：带上本机最大序列号，PHP 里比它大的就是「它有我没有」→ 下面收回来
            BondManager bondMgr = plugin.getBonds();
            body.put("java_max_id", bondMgr == null ? 0 : bondMgr.getMaxTxId());

            String response = httpPostWithTokenOnce("api/sync.php?action=check_tx_alignment", body);
            if (response == null) return null;   // 15 秒内无答复

            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                plugin.getLogger().warning("[Web交易同步] 对账 PHP 明确答复失败: " + result.get("message"));
                return null;
            }
            Object dataObj = result.get("data");
            if (!(dataObj instanceof Map)) return null;
            Object missingObj = ((Map<?, ?>) dataObj).get("missing");
            if (!(missingObj instanceof List)) return null;

            // ★ 多退少补的「补」：PHP 有而本机没有的流水，先收回来（只补记录，不触发业务）
            pullExtraTxsFromPhp((Map<String, Object>) dataObj);

            Set<Long> missing = new LinkedHashSet<>();
            for (Object o : (List<?>) missingObj) {
                if (o instanceof Number) missing.add(((Number) o).longValue());
            }
            return missing;
        } catch (Exception e) {
            warnSlow("交易对账失败", "[Web交易同步] 对账失败（本批按无答复处理，15 秒后重试）: " + e.getMessage());
            return null;
        }
    }


    /**
     * ★ 多退少补的「补」：PHP 返回 extra_ids（它有、本机没有的流水序列号）时，
     *   拉完整数据导入本地 bond_transaction。
     *   只补流水记录：INSERT OR IGNORE，不改余额、不触发交易事件、不发货。
     */
    @SuppressWarnings("unchecked")
    private void pullExtraTxsFromPhp(Map<String, Object> data) {
        Object extraObj = data.get("extra_ids");
        if (!(extraObj instanceof List) || ((List<?>) extraObj).isEmpty()) return;
        List<Long> want = new ArrayList<>();
        for (Object o : (List<?>) extraObj) {
            if (o instanceof Number) want.add(((Number) o).longValue());
        }
        if (want.isEmpty()) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("java_ids", want);
            String response = httpPostWithTokenOnce("api/sync.php?action=pull_tx", body);
            if (response == null) {
                warnSlow("交易收回无答复", "[Web交易同步] ★ 要收回 " + want.size()
                        + " 笔本机缺失流水，但 15 秒内无答复 → 下轮再收");
                return;
            }
            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                plugin.getLogger().warning("[Web交易同步] ★ 收回流水 PHP 答复失败: " + result.get("message"));
                return;
            }
            Object dataObj = result.get("data");
            if (!(dataObj instanceof Map)) return;
            Object txsObj = ((Map<?, ?>) dataObj).get("transactions");
            if (!(txsObj instanceof List)) return;
            BondManager bondMgr = plugin.getBonds();
            if (bondMgr == null) return;
            int imported = bondMgr.importTransactionsFromWeb((List<Map<String, Object>>) txsObj);
            plugin.getLogger().info("[Web交易同步] ★ 多退少补：PHP 有本机没有的流水 " + want.size()
                    + " 笔，已收回导入 " + imported + " 笔（只补记录，不改余额不发货）");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] ★ 收回流水异常: " + e.getMessage());
        }
    }


    /** 批次成功后推进水位线（只增不减，异常回退不会导致重复全量） */
    private void advanceTxWatermark(List<Map<String, Object>> batch) {
        long maxTime = lastSyncedTxTime;
        for (Map<String, Object> tx : batch) {
            Object tv = tx.get("time");
            if (tv instanceof Number) {
                long t = ((Number) tv).longValue();
                if (t > maxTime) maxTime = t;
            }
        }
        if (maxTime > lastSyncedTxTime) {
            lastSyncedTxTime = maxTime;
            saveLastSyncedTxTime(maxTime);
        }
    }


    /** 单批交易同步的最大笔数 */
    private static final int TX_SYNC_BATCH_SIZE = 300;

    /** 首次全量同步：每提交一项打一行进度，便于用户实时观察 */
    private void fullSyncStep(String what) {
        plugin.getLogger().info("[Web通信]   首次全量同步 → 已排队: " + what);
    }

    /**
     * 推送一批交易到 PHP，成功则推进水位线，失败返回 false（调用方负责中止并保留水位线）
     */
    private boolean pushTxBatch(List<Map<String, Object>> batch, int totalAll, int batchNo, int batchCount) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transactions", batch);

            // ★ 单次请求、15 秒超时：PHP 必须在窗口内给答复
            String response = httpPostWithTokenOnce("api/sync.php?action=sync_transactions", body);
            if (response == null) {
                plugin.getLogger().warning("[Web交易同步] 第" + batchNo + "/" + batchCount
                        + "批 ★ 15 秒内没有答复（超时/网络），不算成功也不算失败 → 等 15 秒重试同一批");
                return false;
            }
            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                plugin.getLogger().warning("[Web交易同步] 第" + batchNo + "/" + batchCount
                        + "批 ★ PHP 明确答复失败: " + result.get("message"));
                return false;
            }

            // ★ 水位线由调用方 advanceTxWatermark 统一推进（对账跳过时也要推进）
            plugin.getLogger().info("[Web交易同步] 第" + batchNo + "/" + batchCount + "批 已推送 "
                    + batch.size() + " 笔（本轮共 " + totalAll + " 笔），回执=" + result.get("message"));
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 第" + batchNo + "批 异常: " + e.getMessage());
            return false;
        }
    }



    /**
     * ★ 启动对账（2026-10-04，替代旧版「无条件归零水位线全量重推」）
     *
     * 旧逻辑的问题：每次开服都把水位线归零，于是几万笔历史流水被全量重推一遍。
     * 既拖慢启动、又无谓地打扰 PHP，日志还写成「累计 N 笔待推」，看着像在补丢数据。
     *
     * 新逻辑：开服时先跟 PHP 点一遍数据——
     *   · 两边笔数与最大序列号都吻合 → 判定数据已是最新，一笔都不推；
     *   · 对不上 → 才归零水位线进入补推，且补推时逐批只推 PHP 真正缺的序列号；
     *   · PHP 问不到（网络/密钥问题）→ 标记待对齐，下一轮同步时重试，绝不盲目全量重推。
     */
    private void alignTxWatermarkOnBoot() {
        try {
            BondManager bondMgr = plugin.getBonds();
            if (bondMgr == null) return;
            long localMax = bondMgr.getMaxTxId();
            long localCount = bondMgr.getTxCount();
            if (localMax <= 0 || localCount <= 0) {
                plugin.getLogger().info("[Web交易同步] 启动对齐：本机暂无交易流水，无需补推");
                return;
            }

            Map<String, Object> php = fetchPhpTxAlignmentStats(bondMgr, localMax, localCount);
            if (php == null) {
                txAlignmentPending = true;
                plugin.getLogger().warning("[Web交易同步] 启动对齐：15 秒内没拿到 PHP 答复，本轮不推历史，下轮同步时重试对齐");
                return;
            }

            // ★ 多退少补的「补」：PHP 有而本机没有的流水先收回来（只补记录，不触发业务）
            pullExtraTxsFromPhp(php);
            localMax = bondMgr.getMaxTxId();
            localCount = bondMgr.getTxCount();

            long phpMax = toLong(php.get("php_max_id"));
            long phpCount = toLong(php.get("php_count"));
            Object missingObj = php.get("missing");
            boolean phpMissingSome = missingObj instanceof List && !((List<?>) missingObj).isEmpty();

            if (!phpMissingSome && phpMax >= localMax && phpCount >= localCount) {
                plugin.getLogger().info("[Web交易同步] ★ 启动对齐通过：两边一致（PHP " + phpCount
                        + " 笔/最大序列号 " + phpMax + "，本机 " + localCount + " 笔/最大序列号 "
                        + localMax + "），PHP 有的本机都有、本机有的 PHP 都有 → 一笔都不推");
                txFullScanPending = false;
                return;
            }

            // ★ 有缺口 → 取全量逐批点验，只补 PHP 真正缺的；
            //   不再归零水位线盲推（那样 PHP 已有的会全部重推一遍）
            txFullScanPending = true;
            plugin.getLogger().info("[Web交易同步] 对账有缺口（PHP " + phpCount
                    + " 笔/最大序列号 " + phpMax + "，本机 " + localCount + " 笔/最大序列号 "
                    + localMax + "，PHP 缺 " + (phpMissingSome ? ((List<?>) missingObj).size() : 0)
                    + " 笔）→ 多退少补：本机取全量逐批点验，PHP 已有的一笔不推");
        } catch (Exception e) {
            txAlignmentPending = true;
            plugin.getLogger().warning("[Web交易同步] 启动对齐异常（本轮跳过补推判定）: " + e.getMessage());
        }
    }


    /**
     * ★ 周期对账（2026-10-06）：给交易流水补上「运行期自愈」通道。
     *
     * 背景：开服对齐只跑一次。之后 PHP 端 web.db 若被重载/重置（删站、站点恢复等），
     *   game_transactions 会被清空，而本机水位线仍停在最新——每轮只推「水位线之后的增量」，
     *   历史流水再也补不回去；日志里那句「N/N 笔已对齐」指的是本轮增量，不是全库，
     *   看起来一切正常，实际两边差着几百笔（假对齐）。
     *   对比：充值回执等 8 类走 ALIGN_CFGS 周期对账，PHP 清零后能自愈，唯独流水不能。
     *
     * 判据（轻量，只取 PHP 的笔数与最大序列号，不带全量序列号）：
     *   缺口 = 本机笔数 - PHP 笔数；
     *   水位线之后还没推的笔数 = 正常待推增量；
     *   缺口 > 待推增量 ⇒ 多出来的部分是 PHP 运行期丢的历史 → 转入全量逐批点验，
     *   由既有的「两边点验」只补 PHP 真正缺的（已有的不重推）。
     */
    @SuppressWarnings("unchecked")
    private void maybePeriodicTxReconcile(BondManager bondMgr) {
        long now = System.currentTimeMillis();
        if (lastTxReconcileAt > 0 && now - lastTxReconcileAt < TX_RECONCILE_INTERVAL_MS) {
            return;
        }
        // 先占位：即便这次没答复，也等下个周期再来，不给 DB 队列加压
        lastTxReconcileAt = now;

        long localCount = bondMgr.getTxCount();
        long localMax = bondMgr.getMaxTxId();
        if (localCount <= 0 || localMax <= 0) return;

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("java_max_id", localMax);
            body.put("java_count", localCount);
            String response = httpPostWithTokenOnce("api/sync.php?action=check_tx_alignment", body);
            if (response == null) {
                plugin.getLogger().info("[Web交易同步] 周期对账：15 秒内无答复，本轮跳过（下个周期再点）");
                return;
            }
            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                plugin.getLogger().warning("[Web交易同步] 周期对账 PHP 答复失败: " + result.get("message"));
                return;
            }
            Object dataObj = result.get("data");
            if (!(dataObj instanceof Map)) return;
            Map<String, Object> data = (Map<String, Object>) dataObj;

            // 多退少补的「补」：PHP 有、本机没有的流水先收回来（只补记录，不改余额不发货）
            pullExtraTxsFromPhp(data);
            localCount = bondMgr.getTxCount();
            localMax = bondMgr.getMaxTxId();

            long pendingPush = bondMgr.countTransactionsAfterTime(lastSyncedTxTime);
            if (pendingPush < 0) return;               // 本地查不出待推笔数，本轮不做判定
            long phpCount = toLong(data.get("php_count"));
            long phpMax = toLong(data.get("php_max_id"));
            long gap = localCount - phpCount;          // 本机有、PHP 没有的总缺口
            if (gap <= pendingPush) {
                // 两边账实相符：缺口正好等于还没推的增量（没有丢失）
                plugin.getLogger().info("[Web交易同步] ★ 周期对账通过：PHP " + phpCount
                        + " 笔/最大序列号 " + phpMax + "，本机 " + localCount + " 笔/最大序列号 "
                        + localMax + "，缺口 " + Math.max(gap, 0) + " 笔 = 待推增量 "
                        + pendingPush + " 笔 → 两边一致");
                return;
            }

            txFullScanPending = true;
            plugin.getLogger().warning("[Web交易同步] ★ 周期对账发现 PHP 流水在运行期丢失：PHP "
                    + phpCount + " 笔/最大序列号 " + phpMax + "，本机 " + localCount
                    + " 笔/最大序列号 " + localMax + "，待推增量 " + pendingPush
                    + " 笔，缺口 " + gap + " 笔（超出 " + (gap - pendingPush)
                    + " 笔）→ 转入全量逐批点验，只补 PHP 真正缺的");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 周期对账异常（本轮跳过）: " + e.getMessage());
        }
    }


    /**
     * 取 PHP 端 game_transactions 的总笔数与最大 java_id，并带上本机全量序列号做双向点验；
     * 返回的 data 里含 missing（PHP 缺的）与 extra_ids（PHP 有本机没有的）；拿不到答复返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchPhpTxAlignmentStats(BondManager bondMgr, long localMax, long localCount) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("java_max_id", localMax);
            body.put("java_count", localCount);
            // 全量序列号：量太大就不传（PHP 改用 java_max_id 判「它有我没有」）
            if (localCount > 0 && localCount <= 20000) {
                List<Long> ids = bondMgr.getAllTxIds();
                body.put("ids", ids);
                body.put("full_scan", 1);
            }
            String response = httpPostWithTokenOnce("api/sync.php?action=check_tx_alignment", body);
            if (response == null) return null;
            Map<String, Object> result = parseJson(response);
            if (!Boolean.TRUE.equals(result.get("success"))) {
                plugin.getLogger().warning("[Web交易同步] 启动对账 PHP 答复失败: " + result.get("message"));
                return null;
            }
            Object dataObj = result.get("data");
            if (!(dataObj instanceof Map)) return null;
            return (Map<String, Object>) dataObj;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 启动对账异常: " + e.getMessage());
            return null;
        }
    }


    private static long toLong(Object v) {
        return (v instanceof Number) ? ((Number) v).longValue() : 0L;
    }


    /** 写入流水水位线（含归零） */
    private void setTxWatermark(long time) {
        lastSyncedTxTime = time;
        if (time > 0) {
            saveLastSyncedTxTime(time);
            return;
        }
        try {
            File dbFile = new File(plugin.getDataFolder(), "web_sync.db");
            java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            java.sql.Statement st = conn.createStatement();
            st.execute("PRAGMA busy_timeout=8000");
            st.execute("CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value INTEGER DEFAULT 0)");
            st.execute("DELETE FROM sync_state WHERE key = 'last_synced_tx_time'");
            st.close(); conn.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 重置流水水位线失败: " + e.getMessage());
        }
    }


    private void loadLastSyncedTxTime() {
        if (lastSyncedTxTime > 0) return;
        try {
            File dbFile = new File(plugin.getDataFolder(), "web_sync.db");
            if (!dbFile.exists()) return;
            java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            java.sql.Statement st = conn.createStatement();
            st.execute("PRAGMA busy_timeout=8000");
            st.execute("CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value INTEGER DEFAULT 0)");
            java.sql.ResultSet rs = st.executeQuery("SELECT value FROM sync_state WHERE key='last_synced_tx_time'");
            if (rs.next()) {
                lastSyncedTxTime = rs.getLong("value");
            }
            rs.close(); st.close(); conn.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 读取last_synced_tx_time失败: " + e.getMessage());
        }
    }

    private void saveLastSyncedTxTime(long time) {
        try {
            File dbFile = new File(plugin.getDataFolder(), "web_sync.db");
            java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            java.sql.Statement st = conn.createStatement();
            st.execute("PRAGMA busy_timeout=8000");
            st.execute("CREATE TABLE IF NOT EXISTS sync_state (key TEXT PRIMARY KEY, value INTEGER DEFAULT 0)");
            java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO sync_state (key, value) VALUES ('last_synced_tx_time', ?)");
            ps.setLong(1, time);
            ps.executeUpdate();
            ps.close(); st.close(); conn.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 保存last_synced_tx_time失败: " + e.getMessage());
        }
    }

    /**
     * 6. 推送注册数据到Web端
     */
    public void syncUserRegistrations() {
        if (!enabled) return;

        try {
            DatabaseManager dbMgr = plugin.getDb();
            if (dbMgr == null) return;

            List<Map<String, Object>> users = dbMgr.getAllUsers();
            // （不再「空列表直接 return」：Java 空、PHP 有数据时也要走对账把数据收回来）

            List<Map<String, Object>> syncData = new ArrayList<>();
            for (Map<String, Object> user : users) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("player_name", user.get("player_name"));
                u.put("register_time", user.get("register_time"));
                u.put("last_login_time", user.get("last_login_time"));
                u.put("email", user.get("email"));
                u.put("points", user.get("points"));
                u.put("gift_stage", user.get("gift_stage"));
                u.put("total_online_time", user.get("total_online_time"));
                syncData.add(u);
            }

            // ★ 双向对账（多退少补）：先与 PHP 点一遍，三差集全空就一条都不发
            AlignResult arAlign = alignGate("users", syncData);
            if (arAlign != null && !arAlign.needPush()) {
                return;   // 两边一致 / 只做了拉回 → 零发送
            }
            if (arAlign != null && arAlign.pulled > 0) {
                // 本轮只收不发：本地刚被 PHP 的数据改过，若继续推送就会用
                // 对账前的旧值把它覆盖回去。下一轮（60~90 秒后）自然会补齐缺口。
                return;
            }

            // ★ 无变化静默：对比MD5 hash，避免无效网络请求
            String currentHash = syncData.toString().hashCode() + "_" + syncData.size();
            // ★ 对账明说要推时本地 hash 不得拦截（同 admins，2026-10-06 灾备修复）：
            //   PHP 库被清空后本地数据没变 → hash 相等 → 旧逻辑永久短路，永不补推。
            boolean alignNeedsPushUsers = arAlign != null && arAlign.needPush();
            if (!alignNeedsPushUsers && currentHash.equals(lastUserRegistrationHash)) return; // 无变化，跳过
            // ★ 关键修复：hash 不在这里提交！只有【PHP 明确返回 success=true】才提交。
            //   否则密钥错/网络断/500 时 hash 已被写脏，这份数据永远不会重推，
            //   表现为"日志显示已同步 N 人，PHP 端却是 0 条"的假成功（2026-10-04 实测）。

            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("users", syncData);

            String response = httpPostWithToken("api/sync.php?action=sync_login", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    lastUserRegistrationHash = currentHash; // ← 成功后才落 hash
                    plugin.getLogger().info("[Web通信] 用户注册数据变更，已同步: " + syncData.size() + "人");
                } else {
                    // ★ 失败：不清 hash（本次未提交），下一轮周期会重试
                    plugin.getLogger().warning("[Web通信] 用户注册同步失败: " + response);
                }
            } else {
                plugin.getLogger().warning("[Web通信] 用户注册同步无响应（下轮将重试）");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 注册同步异常: " + e.getMessage());
        }
    }

    /**
     * Java插件主动通知Web端删除用户
     */
    public void deleteWebUser(String playerName) {
        if (!enabled) return;
        try {
            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("player_name", playerName);
            String response = httpPostWithToken("api/sync.php?action=delete_user", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 用户 " + playerName + " 已从Web端彻底删除");
                } else {
                    plugin.getLogger().warning("[Web通信] 删除用户 " + playerName + " 失败: " + response);
                }
            } else {
                plugin.getLogger().warning("[Web通信] 删除用户 " + playerName + " 无响应");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 删除用户异常: " + e.getMessage());
        }
    }

    // ==================== 本地Web登录验证状态 ====================

    /**
     * 检查玩家是否已通过Web密码验证（本地状态）
     * Java验证成功后立即记录，玩家进游戏时直接检查
     */
    public boolean isWebLoginVerified(String playerName) {
        // ★ 纯内存检查（5分钟有效期，无需持久化）
        Long verifiedTime = verifiedWebLogins.get(playerName);
        if (verifiedTime != null) {
            if (System.currentTimeMillis() - verifiedTime > VERIFIED_LOGIN_EXPIRE_MS) {
                verifiedWebLogins.remove(playerName);
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * 记录玩家Web密码验证成功（Java本地验证后调用）
     * PHP确认登录时，pollWebLoginConfirmations会检查此记录，防止PHP伪造登录
     */
    public void recordWebLogin(String playerName) {
        verifiedWebLogins.put(playerName, System.currentTimeMillis());
        plugin.getLogger().info("[Web登录] 记录Web密码验证成功: " + playerName + "（等待PHP确认后自动登录）");
    }

    /**
     * 清除玩家的Web登录验证状态（玩家成功登录后调用）
     */
    public void clearWebLoginVerified(String playerName) {
        // ★ 纯内存清除（无需清除DB）
        verifiedWebLogins.remove(playerName);
    }

    // ==================== Java手动登录记录（检查点1持久化） ====================

    /**
     * 记录玩家Java手动登录成功（/l命令或autoLogin调用后）
     * onQuit不清除，5分钟内重连可直接放行（检查点1）
     */
    public void recordJavaLogin(String playerName, String ip) {
        javaLoginRecords.put(playerName, System.currentTimeMillis());
        // ★ 记下登录时IP，供检查点1做「IP是否变更」判断（下线不清除，下次登录覆盖）
        if (ip != null && !ip.isEmpty()) {
            javaLoginRecordIps.put(playerName, ip);
        }
        plugin.getLogger().info("[Web登录] ✅ 记录Java登录: " + playerName + "（5分钟内重连可直接放行，当前记录数: " + javaLoginRecords.size() + "）");
    }

    /**
     * 该玩家本运行期上次登录时的IP（检查点1 IP风控基准）
     *
     * @return 登录IP；本运行期无登录记录返回null
     */
    public String getJavaLoginRecordIp(String playerName) {
        return javaLoginRecordIps.get(playerName);
    }

    /**
     * 检查玩家是否有Java手动登录记录（检查点1用）
     * 5分钟内有效，超时自动清除
     */
    public boolean isJavaLoginRecorded(String playerName) {
        Long loginTime = javaLoginRecords.get(playerName);
        if (loginTime != null) {
            long elapsed = System.currentTimeMillis() - loginTime;
            if (elapsed > JAVA_LOGIN_RECORD_EXPIRE_MS) {
                javaLoginRecords.remove(playerName);
                plugin.getLogger().info("[Web登录] 检查点1: " + playerName + " Java登录记录已过期（" + (elapsed / 1000) + "秒前）");
                return false;
            }
            plugin.getLogger().info("[Web登录] 检查点1: " + playerName + " 找到Java登录记录（" + (elapsed / 1000) + "秒前）");
            return true;
        }
        plugin.getLogger().info("[Web登录] 检查点1: " + playerName + " 无Java登录记录，当前记录: " + javaLoginRecords.keySet());
        return false;
    }

    /**
     * 清除玩家的Java登录记录（玩家成功再次登录后调用，避免过期残留）
     */
    public void clearJavaLoginRecord(String playerName) {
        javaLoginRecords.remove(playerName);
    }

    // ===== Microsoft OAuth正版验证 =====

    // 玩家名 -> [sessionId, createdAt] - 进行中的验证会话
    private final ConcurrentHashMap<String, String[]> minecraftAuthSessions = new ConcurrentHashMap<>();
    // 玩家名 -> pollingTask - 轮询定时器
    private final ConcurrentHashMap<String, BukkitRunnable> minecraftAuthPollers = new ConcurrentHashMap<>();

    /**
     * 检查玩家是否有进行中的Minecraft验证会话
     */
    public boolean isMinecraftAuthPending(String playerName) {
        return minecraftAuthSessions.containsKey(playerName);
    }

    /**
     * 启动Microsoft OAuth验证流程
     * 1. 调用PHP创建会话
     * 2. 返回授权URL给玩家
     * 3. 启动轮询检查验证状态
     */
    public void startMinecraftAuth(Player player) {
        if (!enabled) {
            player.sendMessage("§cWeb后端未启用");
            return;
        }

        String playerName = player.getName();
        String secret = java.net.URLEncoder.encode(secretKey, StandardCharsets.UTF_8);

        // 异步调用PHP
        webExecutor.submit(() -> {
            try {
                String url = webBaseUrl + "/api/minecraft_auth.php?action=create_session&player="
                        + java.net.URLEncoder.encode(playerName, StandardCharsets.UTF_8)
                        + "&secret=" + secret;

                String response = doGet(url);
                if (response == null) {
                    player.sendMessage("§c无法连接Web后端");
                    return;
                }

                com.google.gson.JsonObject json;
                try {
                    json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
                } catch (Exception parseEx) {
                    // PHP返回了非JSON（如500错误页面），显示截断内容
                    String preview = response.length() > 150 ? response.substring(0, 150) + "..." : response;
                    player.sendMessage("§c后端返回异常: " + preview);
                    plugin.getLogger().warning("[正版验证] PHP返回非JSON: " + preview);
                    return;
                }
                if (!json.get("success").getAsBoolean()) {
                    String error = json.has("error") ? json.get("error").getAsString() : "未知错误";
                    player.sendMessage("§c验证失败: " + error);
                    return;
                }

                com.google.gson.JsonObject data = json.getAsJsonObject("data");
                String sessionId = data.get("session_id").getAsString();
                String authUrl = data.get("auth_url").getAsString();
                String pasteUrl = data.has("paste_url") ? data.get("paste_url").getAsString() : null;

                // 记录会话
                minecraftAuthSessions.put(playerName, new String[]{sessionId, String.valueOf(System.currentTimeMillis())});

                // 在主线程发送消息
                final String finalPasteUrl = pasteUrl;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        player.sendMessage("§6§l===== 正版验证 =====");
                        player.sendMessage("§7点击下方链接打开粘贴页面");
                        player.sendMessage("§7步骤: 点击登录 → 登录微软 → 复制地址栏URL → 粘贴到页面");
                        player.sendMessage("§7粘贴URL后会自动提交验证");
                        player.sendMessage("§7链接10分钟内有效");
                        player.sendMessage("§6§l====================");
                        // 尝试发送可点击的URL（授权链接）
                        try {
                            net.kyori.adventure.text.Component msg = net.kyori.adventure.text.Component.text()
                                    .content("§7[§b点击打开Microsoft登录页面§7]")
                                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(authUrl))
                                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§e点击在浏览器中打开Microsoft登录")))
                                    .build();
                            player.sendMessage(msg);
                        } catch (Exception e) {
                            // 低版本不支持Adventure API，忽略
                        }
                        // 可点击的粘贴页面
                        if (finalPasteUrl != null) {
                            try {
                                net.kyori.adventure.text.Component pasteMsg = net.kyori.adventure.text.Component.text()
                                        .content("§7[§b点击打开粘贴授权码页面§7]")
                                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(finalPasteUrl))
                                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("§e点击打开粘贴授权码页面")))
                                        .build();
                                player.sendMessage(pasteMsg);
                            } catch (Exception e) {
                                // 低版本不支持Adventure API，忽略
                            }
                        }
                    }
                });

                // 启动轮询
                startMinecraftAuthPoller(playerName, sessionId, player);

            } catch (Exception e) {
                plugin.getLogger().warning("[正版验证] 创建会话失败: " + e.getMessage());
                player.sendMessage("§c验证请求失败，请稍后重试");
            }
        });
    }

    /**
     * 启动轮询器：定期检查PHP端验证状态
     */
    private void startMinecraftAuthPoller(String playerName, String sessionId, Player player) {
        // 取消已有的轮询器
        BukkitRunnable existing = minecraftAuthPollers.remove(playerName);
        if (existing != null) {
            existing.cancel();
        }

        final String[] pollData = {sessionId};
        // ★ 连续无效响应（非JSON/结构不符）计数：只用于日志与提示，任何情况下都不会据此放行
        final int[] invalidPolls = {0};
        final boolean[] offlined = {false};
        final long startTime = System.currentTimeMillis();
        final long maxPollTime = 660000; // 11分钟（比会话过期多1分钟）
        // ★ 发起验证时先抓一次IP：玩家下线后 Player.getAddress 可能拿不到，用它兜底写 last_oauth_ip
        final String beginIp = plugin.getPlayerIP(player);

        BukkitRunnable poller = new BukkitRunnable() {
            @Override
            public void run() {
                // 超时检查
                if (System.currentTimeMillis() - startTime > maxPollTime) {
                    plugin.getLogger().info("[正版验证] 轮询超时: " + playerName);
                    minecraftAuthSessions.remove(playerName);
                    minecraftAuthPollers.remove(playerName);
                    if (player.isOnline()) {
                        player.sendMessage("§c验证超时，请重新发起验证");
                    }
                    this.cancel();
                    return;
                }

                // ★ 玩家已下线也不停轮询：PHP 一旦判定 verified 就照样写入内存+数据库，
                //   这样"验证时人不在"也能永久生效，下次上线直接登录（消息发送处均已判 isOnline）
                if (!player.isOnline() && !offlined[0]) {
                    offlined[0] = true;
                    plugin.getLogger().info("[正版验证] 玩家下线，转为后台继续轮询: " + playerName);
                }

                // 玩家已登录（可能通过其他方式）
                if (plugin.getLoggedIn().contains(playerName)) {
                    plugin.getLogger().info("[正版验证] 玩家已登录，停止轮询: " + playerName);
                    minecraftAuthSessions.remove(playerName);
                    minecraftAuthPollers.remove(playerName);
                    this.cancel();
                    return;
                }

                // 异步查询PHP
                webExecutor.submit(() -> {
                    try {
                        String secret = java.net.URLEncoder.encode(secretKey, StandardCharsets.UTF_8);
                        String url = webBaseUrl + "/api/minecraft_auth.php?action=check_session&session_id="
                                + java.net.URLEncoder.encode(pollData[0], StandardCharsets.UTF_8)
                                + "&secret=" + secret;

                        String response = doGet(url);
                        if (response == null) return;

                        // ★ fail-closed：非JSON / 结构不符 一律视为"无效响应"，绝不判为通过
                        com.google.gson.JsonObject json;
                        try {
                            json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
                        } catch (Exception parseEx) {
                            invalidPolls[0]++;
                            if (invalidPolls[0] == 1 || invalidPolls[0] % 15 == 0) {
                                String preview = response.length() > 200 ? response.substring(0, 200) + "..." : response;
                                plugin.getLogger().warning("[正版验证] 响应非JSON，忽略(第" + invalidPolls[0] + "次): " + preview);
                            }
                            if (invalidPolls[0] == 5) {
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (player.isOnline()) {
                                        player.sendMessage("§c验证服务响应异常，正在自动重试…");
                                    }
                                });
                            }
                            return;
                        }
                        if (!json.has("success") || !json.get("success").isJsonPrimitive()
                                || !json.get("success").getAsBoolean()) {
                            // PHP明确返回失败（会话不存在/密钥错误等结构性错误）→ 终止轮询，不放行
                            String err = (json.has("error") && json.get("error").isJsonPrimitive())
                                    ? json.get("error").getAsString() : "未知错误";
                            plugin.getLogger().warning("[正版验证] PHP返回失败，终止轮询: " + err);
                            minecraftAuthSessions.remove(playerName);
                            minecraftAuthPollers.remove(playerName);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) {
                                    player.sendMessage("§c验证失败: " + err);
                                }
                            });
                            this.cancel();
                            return;
                        }
                        if (!json.has("data") || !json.get("data").isJsonObject()) {
                            invalidPolls[0]++;
                            plugin.getLogger().warning("[正版验证] 响应缺少data，忽略(第" + invalidPolls[0] + "次)");
                            return;
                        }
                        com.google.gson.JsonObject data = json.getAsJsonObject("data");
                        if (!data.has("status") || !data.get("status").isJsonPrimitive()) {
                            invalidPolls[0]++;
                            plugin.getLogger().warning("[正版验证] 响应缺少status，忽略(第" + invalidPolls[0] + "次)");
                            return;
                        }
                        String status = data.get("status").getAsString();
                        invalidPolls[0] = 0; // 收到有效响应 → 重置无效计数

                        if ("verified".equals(status)) {
                            // 验证成功！
                            String mcUuid = data.has("mc_uuid") ? data.get("mc_uuid").getAsString() : "";
                            String mcUsername = data.has("mc_username") ? data.get("mc_username").getAsString() : "";
                            String sessionPlayer = data.has("player_name") ? data.get("player_name").getAsString() : "";

                            // ★ fail-closed：凭证不全 / 会话归属玩家不符 → 一律不放行（继续轮询直到超时）
                            if (mcUuid.isEmpty() || mcUsername.isEmpty()
                                    || (sessionPlayer != null && !sessionPlayer.isEmpty()
                                    && !sessionPlayer.equalsIgnoreCase(playerName))) {
                                plugin.getLogger().warning("[正版验证] verified但校验不通过，拒绝放行: session.player="
                                        + sessionPlayer + ", uuid=" + mcUuid + ", name=" + mcUsername);
                                return;
                            }

                            plugin.getLogger().info("[正版验证] 验证成功: " + playerName + " -> " + mcUsername + " (" + mcUuid + ")");

                            // 记录为已验证正版玩家
                            plugin.addVerifiedPremiumPlayer(playerName, mcUuid, mcUsername);

                            // ★ 记录OAuth登录IP（用于异地登录风控）
                            //   玩家已离线时取不到地址 → 用发起验证时捕获的IP兜底
                            String oauthIP = player.isOnline() ? plugin.getPlayerIP(player) : null;
                            if (oauthIP == null || oauthIP.isEmpty()) oauthIP = beginIp;
                            if (oauthIP != null && !oauthIP.isEmpty()) {
                                plugin.getDb().setField(playerName, "last_oauth_ip", oauthIP);
                            }

                            // 在主线程执行自动登录
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline() && !plugin.getLoggedIn().contains(playerName)) {
                                    player.sendMessage("§a§l正版验证成功！欢迎 " + mcUsername + "！");
                                    plugin.autoLogin(player, "premium");
                                }
                            });

                            // 清理会话
                            minecraftAuthSessions.remove(playerName);
                            minecraftAuthPollers.remove(playerName);
                            this.cancel();

                        } else if ("failed".equals(status) || "expired".equals(status)) {
                            plugin.getLogger().info("[正版验证] 验证失败/过期: " + playerName + " -> " + status);
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (player.isOnline()) {
                                    player.sendMessage("§c验证" + ("expired".equals(status) ? "已过期" : "失败") + "，请重新发起验证");
                                }
                            });
                            minecraftAuthSessions.remove(playerName);
                            minecraftAuthPollers.remove(playerName);
                            this.cancel();
                        }
                        // status == "pending" → 继续轮询

                    } catch (Exception e) {
                        plugin.getLogger().warning("[正版验证] 轮询查询异常: " + e.getMessage());
                    }
                });
            }
        };

        // 每2秒轮询一次
        poller.runTaskTimerAsynchronously(plugin, 40L, 40L); // 40 ticks = 2 seconds
        minecraftAuthPollers.put(playerName, poller);
    }

    /**
     * 停止玩家的验证轮询（玩家下线时调用）
     */
    public void stopMinecraftAuthPoller(String playerName) {
        BukkitRunnable poller = minecraftAuthPollers.remove(playerName);
        if (poller != null) {
            poller.cancel();
        }
        minecraftAuthSessions.remove(playerName);
    }

    /**
     * 同步在线玩家列表到PHP端（用于Web登录状态检查）
     * 注意：推送所有在线玩家（包括未登录的），PHP端通过 web_login_verified 判断是否已认证
     */
    public void syncOnlinePlayers() {
        if (!enabled) {
            plugin.getLogger().warning("[Web通信] syncOnlinePlayers: enabled=false, 跳过");
            return;
        }

        try {
            java.util.Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
            java.util.Set<String> loggedInPlayers = plugin.getLoggedIn(); // ★ 只推送已登录的玩家
            List<Map<String, Object>> playersData = new ArrayList<>();

            for (Player p : onlinePlayers) {
                // ★ 关键：只有真正输入密码登录的玩家才推送到PHP
                if (!loggedInPlayers.contains(p.getName())) continue;
                Map<String, Object> playerInfo = new LinkedHashMap<>();
                playerInfo.put("name", p.getName());
                playerInfo.put("login_time", System.currentTimeMillis() / 1000);
                // ★ 推送当前IP（从Bukkit Player对象实时获取，不走login.db缓存）
                try {
                    java.net.InetSocketAddress addr = p.getAddress();
                    if (addr != null && addr.getAddress() != null) {
                        playerInfo.put("ip", addr.getAddress().getHostAddress());
                    }
                } catch (Exception ignored) {}
                playersData.add(playerInfo);
            }

            // ★ 双向对账（多退少补）：在线名单与 PHP 完全一致就一条都不发。
            //   指纹只比「谁在线」——login_time 每次都变，进指纹就永远对不上。
            List<Map<String, Object>> onlineRows = new ArrayList<>();
            for (Map<String, Object> p0 : playersData) {
                Map<String, Object> r0 = new LinkedHashMap<>();
                r0.put("player_name", p0.get("name"));
                onlineRows.add(r0);
            }
            AlignResult arAlign = alignGate("online", onlineRows);
            if (arAlign != null && !arAlign.needPush()) {
                // ★ 2026-10-06 名单一致 → 数据一条不发（省流量、避免整表重建），
                //   但【必须刷心跳】：PHP admin 后台显示在线人数前要检查
                //   online_player_hb（120 秒内才算新鲜），而这个心跳只在
                //   sync_online_players 真正执行时才更新。
                //   旧逻辑在这里直接 return → 玩家稳定在线、名单不变时 PHP 永远收不到
                //   推送 → 心跳超期 → 后台强制显示 0 人；而清表动作也在 sync_online_players
                //   里，表没清 → 下轮对账仍判一致 → 仍然不发 → 永不自愈。
                //   （对账超时 alignTimeout 走的也是这个分支，链路活着时一并保活。）
                sendOnlineHeartbeat();
                return;
            }

            String playersJson = buildPlayersJsonArray(playersData);
            // ★ 无变化静默：仅在在线人数或玩家列表变化时才打印日志
            String currentHash = playersData.size() + ":" + loggedInPlayers;
            boolean changed = (onlinePlayers.size() != lastOnlineCount)
                    || (loggedInPlayers.size() != lastLoggedInCount)
                    || !currentHash.equals(lastOnlinePlayersHash);
            if (changed) {
                plugin.getLogger().info("[Web通信] ★ 在线玩家变化: 在线=" + onlinePlayers.size() + " 已登录=" + loggedInPlayers.size() + " 玩家=" + loggedInPlayers);
                lastOnlinePlayersHash = currentHash;
                lastOnlineCount = onlinePlayers.size();
                lastLoggedInCount = loggedInPlayers.size();
            }

            // ★ 策略：优先GET（与push_player_login_status一致），确保数据到达PHP
            // GET请求更可靠，不会被Web服务器/WAF拦截POST body
            String getUrl = webBaseUrl + "/api/sync.php?action=sync_online_players"
                    + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                    + "&players=" + java.net.URLEncoder.encode(playersJson, "UTF-8");

            String response = doGet(getUrl);

            if (response != null && response.contains("\"success\":true")) {
                if (changed) {
                    plugin.getLogger().info("[Web通信] ★ 在线玩家同步成功: " + playersData.size() + "人");
                }
            } else {
                // 检测404响应：如果response中明确包含404，直接跳过POST回退
                // 因为PHP端syncOnlinePlayers的GET和POST路由指向同一函数，404通常是URL编码过长导致CF/Nginx拦截
                if (response != null && (response.contains("404") || response.contains("\"404\"") || response.startsWith("404"))) {
                    plugin.getLogger().warning("[Web通信] ★ 在线玩家同步404（跳过POST回退，URL可能过长）: " + response);
                    // 降级：拆分玩家分批POST（每人一批，避免URL超长）
                    splitPostSyncOnlinePlayers(playersData);
                } else {
                    plugin.getLogger().warning("[Web通信] ★ 在线玩家同步失败(GET): " + response);
                    tryPostSync(playersJson, playersData.size());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] syncOnlinePlayers异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            // 异常时尝试POST
            try {
                String playersJson = buildPlayersJsonArray(new ArrayList<>());
                tryPostSync(playersJson, 0);
            } catch (Exception ignored) {}
        }
    }

    /** 上次「在线心跳失败」日志时间（限频，避免刷屏） */
    private volatile long lastOnlineHeartbeatFailLog = 0;

    /**
     * ★ 2026-10-06 在线保活心跳（轻量端点）
     *
     * 用途：在线名单与 PHP 对账完全一致、本轮一条数据都不发时，改发这个极轻的请求，
     *      只为刷新 PHP 端 online_player_hb 的 last_seen。
     *
     * 为什么非发不可：PHP admin 后台展示在线人数前会校验
     *      (now - last_seen) <= 120 秒，超了就强制返回空列表（显示 0 人）。
     *      这个心跳过去只由 sync_online_players 更新，而对账「零发送」路径把它掐断了。
     *
     * 请求体刻意做到最小（GET + secret，不带 players），不会触发 URL 过长 404，
     * 也不参与在线数据的语义 —— 名单有没有变化仍由对账裁决。
     */
    private void sendOnlineHeartbeat() {
        try {
            String url = webBaseUrl + "/api/sync.php?action=online_heartbeat"
                    + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doGet(url);
            boolean ok = resp != null && resp.contains("\"success\":true");
            if (!ok) {
                long now = System.currentTimeMillis();
                if (now - lastOnlineHeartbeatFailLog > LOG_INTERVAL) {
                    lastOnlineHeartbeatFailLog = now;
                    plugin.getLogger().warning("[Web通信] 在线心跳失败（名单一致但保活没送上）: "
                            + (resp == null ? "null" : resp.substring(0, Math.min(200, resp.length()))));
                }
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastOnlineHeartbeatFailLog > LOG_INTERVAL) {
                lastOnlineHeartbeatFailLog = now;
                plugin.getLogger().warning("[Web通信] 在线心跳异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * POST方式同步在线玩家（备用方案）
     */
    private void tryPostSync(String playersJson, int count) {
        try {
            String jsonBody = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"players\":" + playersJson + "}";
            plugin.getLogger().info("[Web通信] 尝试POST同步: " + count + "人");

            String response = doPost(webBaseUrl + "/api/sync.php?action=sync_online_players", jsonBody);

            if (response != null && response.contains("\"success\":true")) {
                plugin.getLogger().info("[Web通信] POST同步成功: " + count + "人");
            } else {
                plugin.getLogger().warning("[Web通信] POST同步也失败: " + (response != null ? response.substring(0, Math.min(300, response.length())) : "null"));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] POST同步异常: " + e.getMessage());
        }
    }

    /**
     * 拆分在线玩家分批POST同步（解决URL超长导致404的问题）
     * 当GET同步返回404时触发，每次只传1个玩家，避免URL/POST Body过长
     */
    private void splitPostSyncOnlinePlayers(List<Map<String, Object>> playersData) {
        if (playersData == null || playersData.isEmpty()) {
            plugin.getLogger().info("[Web通信] 拆分同步：玩家列表为空，跳过");
            return;
        }
        plugin.getLogger().info("[Web通信] 拆分同步：开始分批POST " + playersData.size() + " 个玩家");
        int batchSize = 1; // 每批1个玩家，确保URL最短
        int total = playersData.size();
        int sent = 0;
        int failed = 0;

        for (int i = 0; i < total; i++) {
            // 每个请求前随机等待0-2秒，与正在进行的任务拉开时间差
            budgetSleep((long) (Math.random() * 2000)); // 0-2秒（10秒预算耗尽后不再睡）
            
            List<Map<String, Object>> batch = new ArrayList<>();
            batch.add(playersData.get(i));
            String batchJson = buildPlayersJsonArray(batch);
            String jsonBody = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"players\":" + batchJson + "}";
            String url = webBaseUrl + "/api/sync.php?action=sync_online_players";

            try {
                String resp = doPost(url, jsonBody);
                if (resp != null && resp.contains("\"success\":true")) {
                    sent++;
                } else {
                    failed++;
                    if (i % 5 == 0) { // 每5个失败打印一次日志，避免刷屏
                        plugin.getLogger().warning("[Web通信] 拆分同步第" + (i+1) + "个失败: " + (resp != null ? resp.substring(0, Math.min(100, resp.length())) : "null"));
                    }
                }
            } catch (Exception e) {
                failed++;
                if (i % 5 == 0) {
                    plugin.getLogger().warning("[Web通信] 拆分同步第" + (i+1) + "个异常: " + e.getMessage());
                }
            }
        }

        plugin.getLogger().info("[Web通信] 拆分同步完成: 成功" + sent + "/" + total + " 失败" + failed + "/" + total);
    }

    // IP变更缓存：playerName -> [ip, syncFlag]
    // syncFlag: "0"=已同步, "1"=待同步
    private final ConcurrentHashMap<String, String[]> ipCache = new ConcurrentHashMap<>();

    /**
     * 获取玩家IP（从login.db读取last_ip）
     */
    private String getPlayerIpFromDb(String playerName) {
        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr == null) return null;
        Object val = dbMgr.getField(playerName, "last_ip");
        return val != null ? String.valueOf(val) : null;
    }

    /**
     * 同步玩家IP到PHP端（当IP发生变化时）
     */
    private void syncPlayerIpToWeb(String playerName, String ip) {
        if (!enabled || ip == null || ip.isEmpty()) return;

        String[] cached = ipCache.get(playerName);
        boolean hasChanged = false;

        if (cached == null) {
            hasChanged = true;
            ipCache.put(playerName, new String[]{ip, "1"});
        } else {
            String prevIp = cached[0];
            String prevSync = cached[1];
            if (!prevIp.equals(ip) || !"0".equals(prevSync)) {
                hasChanged = true;
                ipCache.put(playerName, new String[]{ip, "1"});
            }
        }

        if (!hasChanged) return;

        // 构建players数据
        Map<String, Object> playerInfo = new LinkedHashMap<>();
        playerInfo.put("name", playerName);
        playerInfo.put("ip", ip);

        List<Map<String, Object>> playersData = new ArrayList<>();
        playersData.add(playerInfo);

        String playersJson = buildPlayersJsonArrayWithIp(playersData);
        String jsonBody = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"players\":" + playersJson + "}";

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String resp = doPost(webBaseUrl + "/api/sync.php?action=sync_online_players", jsonBody);
                    if (resp != null && resp.contains("\"success\":true")) {
                        // 标记为已同步，下次有变化才会再推
                        ipCache.put(playerName, new String[]{ip, "0"});
                    }
                } catch (Exception e) {
                    // 静默忽略
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * ★ 同步所有玩家IP到PHP端（全量同步，带变更检测）
     * 在定时同步调度时调用，对比login.db中玩家IP是否有变化
     * 只有IP变化的玩家才会被推送到PHP端
     */
    public void syncAllPlayerIps() {
        if (!enabled) return;

        DatabaseManager dbMgr = plugin.getDb();
        if (dbMgr == null) return;

        // 获取所有玩家名称和当前IP
        List<Map<String, Object>> allUsers = dbMgr.getAllUsers();
        if (allUsers.isEmpty()) return;

        // ★ 双向对账（多退少补）：IP 清单与 PHP 一致就一条都不发
        List<Map<String, Object>> ipRows = new ArrayList<>();
        for (Map<String, Object> row0 : allUsers) {
            String nm0 = (String) row0.get("player_name");
            if (nm0 == null || nm0.isEmpty()) continue;
            Object ipObj0 = dbMgr.getField(nm0, "ip_address");
            String ip0 = ipObj0 != null ? String.valueOf(ipObj0) : null;
            if (ip0 == null || ip0.isEmpty()) ip0 = (String) row0.get("register_ip");
            if (ip0 == null || ip0.isEmpty()) continue;
            Map<String, Object> r0 = new LinkedHashMap<>();
            r0.put("name", nm0);
            r0.put("ip", ip0);
            ipRows.add(r0);
        }
        AlignResult arAlign = alignGate("ips", ipRows);
        if (arAlign == null) {
            // ★ 对账没做成（PHP 无答复/报错）→ 本轮不推不收，下轮重试，绝不回退全量硬推
            return;
        }
        if (!arAlign.needPush()) return;   // 两边一致 → 零发送
        if (arAlign.pulled > 0) return;    // 本轮只收不发

        // ★ 2026-10-06 只推对账裁决出的差集（missing + 本机改过的 changed），绝不全量硬推。
        //   旧版 forceFullIpPush 一旦发现缺口就跳过 ipCache 增量过滤、把本机全部 IP
        //   整包重推给 PHP —— 这就是日志里「全量推送 ip」的来源（生产实测：missing=0、
        //   changed=110 却推了 609 条）。对账本身就是全量比对，缺哪条补哪条即可。
        AlignCfg ipsCfg = ALIGN_CFGS.get("ips");
        Map<String, Map<String, Object>> ipsByKey = new LinkedHashMap<>();
        if (ipsCfg != null) {
            for (Map<String, Object> row : ipRows) {
                String k = alignKeyOf(ipsCfg, row);
                if (!k.isEmpty()) ipsByKey.put(k, row);
            }
        }
        List<Map<String, Object>> needSync = new ArrayList<>();
        for (String k : arAlign.pushKeys) {
            Map<String, Object> row = ipsByKey.get(k);
            if (row != null) needSync.add(row);
        }
        if (needSync.isEmpty()) return;

        // 构建JSON并推送到PHP端
        String playersJson = "[";
        for (int i = 0; i < needSync.size(); i++) {
            Map<String, Object> p = needSync.get(i);
            if (i > 0) playersJson += ",";
            playersJson += "{\"name\":\"" + escapeJson((String) p.get("name")) + "\",\"ip\":\"" + escapeJson((String) p.get("ip")) + "\"}";
        }
        playersJson += "]";

        String jsonBody = "{\"secret\":\"" + escapeJson(secretKey) + "\",\"players\":" + playersJson + "}";

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String resp = doPost(webBaseUrl + "/api/sync.php?action=sync_player_ips", jsonBody);
                    if (resp != null && resp.contains("\"success\":true")) {
                        plugin.getLogger().info("[Web通信] 对账补推 " + needSync.size() + "/" + ipRows.size()
                                + " 个玩家IP到PHP端（PHP 侧共 " + arAlign.phpCount + " 条）");
                        // 标记为已同步
                        for (Map<String, Object> p : needSync) {
                            String name = (String) p.get("name");
                            String ip = (String) p.get("ip");
                            if (name != null && ip != null) {
                                ipCache.put(name, new String[]{ip, "0"});
                            }
                        }
                    } else {
                        plugin.getLogger().warning("[Web通信] 同步IP到PHP失败: " + (resp != null ? resp.substring(0, Math.min(200, resp.length())) : "null"));
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web通信] 同步玩家IP异常: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    private String buildPlayersJsonArray(List<Map<String, Object>> playersData) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < playersData.size(); i++) {
            Map<String, Object> p = playersData.get(i);
            if (i > 0) sb.append(",");
            sb.append("{").append("\"name\":\"").append(escapeJson((String)p.get("name"))).append("\"");
            sb.append(",\"login_time\":").append(String.valueOf(p.getOrDefault("login_time", System.currentTimeMillis()/1000)));
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String buildPlayersJsonArrayWithIp(List<Map<String, Object>> playersData) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < playersData.size(); i++) {
            Map<String, Object> p = playersData.get(i);
            if (i > 0) sb.append(",");
            sb.append("{").append("\"name\":\"").append(escapeJson((String)p.get("name"))).append("\"");
            sb.append(",\"login_time\":").append(String.valueOf(p.getOrDefault("login_time", System.currentTimeMillis()/1000)));
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 7. 推送玩家密码凭证到Web端（用于Web登录双保险验证）
     */
    public void pushWebLoginCredentials() {
        if (!enabled) return;

        try {
            DatabaseManager dbMgr = plugin.getDb();
            if (dbMgr == null) return;

            List<Map<String, Object>> allUsers = dbMgr.getAllUsers();

            List<Map<String, Object>> credentials = new ArrayList<>();
            for (Map<String, Object> user : allUsers) {
                String name = (String) user.get("player_name");
                String hash = (String) user.get("password_hash");
                String salt = (String) user.get("password_salt");
                String tempHash = (String) user.get("temp_password");
                Object tempExpireObj = user.get("temp_pw_expire");
                long tempExpire = tempExpireObj != null ? ((Number) tempExpireObj).longValue() : 0;

                if (name != null && hash != null && salt != null && !hash.isEmpty() && !salt.isEmpty()) {
                    Map<String, Object> cred = new LinkedHashMap<>();
                    cred.put("player_name", name);
                    cred.put("password_hash", hash);
                    cred.put("salt", salt);
                    // ★ 指纹键（cfg.jf 口径，2026-10-07）：alignRowHash 按 password_salt/temp_password
                    //   取值，只放推送键会让指纹 salt 段恒为 null→空串 → 对账永远 changed=全量、
                    //   每轮整包硬推 611 条凭证（生产实测 changed=611 恒定）。双写两套键：
                    //   salt/temp_password_hash 给 PHP 推送接收，password_salt/temp_password 给指纹。
                    cred.put("password_salt", salt);
                    // ★ 如果有临时密码，也推送
                    if (tempHash != null && !tempHash.isEmpty()) {
                        cred.put("temp_password_hash", tempHash);
                        cred.put("temp_password", tempHash);
                        cred.put("temp_pw_expire", tempExpire);
                    }
                    credentials.add(cred);
                }
            }

            // ★ 双向对账（多退少补）：凭证与 PHP 一致就不发；
            //   PHP 端改过密码（网页改密/重置）时把新凭证收回来
            AlignResult arAlign = alignGate("credentials", credentials);
            if (arAlign == null) {
                // 对账没做成（PHP 无答复/报错）→ 本轮不推不收，下轮重试，绝不回退全量硬推
                return;
            }
            if (!arAlign.needPush()) return;
            if (arAlign.pulled > 0) {
                // 本轮只收不发，防止用对账前的旧密码把刚收回来的新密码覆盖掉
                return;
            }
            if (credentials.isEmpty()) return;

            // ★ 2026-10-07 只推对账裁决出的差集（missing + 本机改过的 changed），绝不整包硬推。
            //   旧版对账一说要推就把全部 611 条凭证整包重发（生产实测每轮「已同步611人」）。
            AlignCfg credCfg = ALIGN_CFGS.get("credentials");
            Map<String, Map<String, Object>> credByKey = new LinkedHashMap<>();
            if (credCfg != null) {
                for (Map<String, Object> row : credentials) {
                    String k = alignKeyOf(credCfg, row);
                    if (!k.isEmpty()) credByKey.put(k, row);
                }
            }
            List<Map<String, Object>> needCred = new ArrayList<>();
            for (String k : arAlign.pushKeys) {
                Map<String, Object> row = credByKey.get(k);
                if (row != null) needCred.add(row);
            }
            if (needCred.isEmpty()) return;

            String secretKey = this.secretKey;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("secret", secretKey);
            body.put("players", needCred);

            String jsonBody = mapToJson(body);
            String response = httpPost("api/sync.php?action=push_web_credentials", jsonBody);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 对账补推 " + needCred.size() + "/" + credentials.size()
                            + " 个凭证到PHP端（PHP 侧共 " + arAlign.phpCount + " 条）");
                } else {
                    plugin.getLogger().warning("[Web通信] 密码凭证同步失败: " + response);
                }
            } else {
                plugin.getLogger().warning("[Web通信] 密码凭证同步无响应");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 密码凭证同步异常: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private int safeInt(String s) {
        try {
            s = s.trim();
            if (s.equals("-")) return -1;
            if (s.equals("无限") || s.equals("∞")) return -1;
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== webshop / weblogin ====================

    /**
     * /sdf1_login webshop - 生成token并从Web拉取商城数据
     */
    public void handleWebShop(Player sender) {
        if (!enabled) {
            sender.sendMessage("§c[Web] Web通信未启用");
            return;
        }

        String playerName = sender.getName();
        sender.sendMessage("§7[WebShop] 正在从Web拉取商城数据...");

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // 生成webshop专用Token并注册到PHP
                    String token = generateAndSyncToken(playerName, "webshop");

                    // 拉取商城数据
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("action", "list");
                    params.put("token", token);

                    String response = httpGet("api/shop.php", params);
                    if (response == null) {
                        Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage("§c[WebShop] 请求失败，无法连接Web服务器"));
                        return;
                    }

                    Map<String, Object> result = parseJson(response);
                    Boolean success = (Boolean) result.get("success");

                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (Boolean.TRUE.equals(success)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = (Map<String, Object>) result.get("data");
                            if (data != null) {
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
                                if (items != null && !items.isEmpty()) {
                                    sender.sendMessage("§a§l===== Web商城 =====");
                                    for (Map<String, Object> item : items) {
                                        String name = item.get("display_name") != null ? item.get("display_name").toString() : "未知";
                                        Object buyObj = item.get("buy_price");
                                        int buyPrice = buyObj != null ? ((Number) buyObj).intValue() : 0;
                                        int stock = item.get("stock") != null ? ((Number) item.get("stock")).intValue() : -1;
                                        String stockStr = stock == -2 ? "§a无限" : (stock == -1 ? "§c下架" : (stock == 0 ? "§7售罄" : "§e" + stock));
                                        sender.sendMessage("§e" + name + " §7- §6" + buyPrice + "§7债券 | 库存: " + stockStr);
                                    }
                                    sender.sendMessage("§7共 " + items.size() + " 件商品 | Token: " + token.substring(0, 8) + "...");
                                } else {
                                    sender.sendMessage("§7[WebShop] Web端暂无商品数据");
                                }
                            }
                        } else {
                            String msg = result.get("message") != null ? result.get("message").toString() : "未知错误";
                            sender.sendMessage("§c[WebShop] 拉取失败: " + msg);
                        }
                    });
                } catch (Exception e) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            sender.sendMessage("§c[WebShop] 异常: " + e.getMessage()));
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * /sdf1_login weblogin - 生成Web登录Token，支持Web端登录
     * 玩家可以在游戏中执行此命令，获取token后在Web端使用
     * 也可以在登录时自动生成（由Main.java的登录事件调用）
     */
    public void handleWebLogin(Player sender) {
        if (!enabled) {
            sender.sendMessage("§c[Web] Web通信未启用");
            return;
        }

        String playerName = sender.getName();
        long now = System.currentTimeMillis();

        // ★ 检查10秒冷却
        Long lastTime = webloginTokenTimestamps.get(playerName);
        if (lastTime != null && (now - lastTime) < WEBLOGIN_TOKEN_COOLDOWN_MS) {
            long remaining = (WEBLOGIN_TOKEN_COOLDOWN_MS - (now - lastTime)) / 1000;
            sender.sendMessage("§c[Web] 请勿频繁获取Token，请等待 " + remaining + " 秒后再试");
            return;
        }
        webloginTokenTimestamps.put(playerName, now);

        // 生成weblogin专用Token
        String token = generateToken(playerName, "weblogin");

        // ★ 使用push_player_login_status端点，带上online=0（玩家当前不在游戏里执行/weblogin，只是要token）
        // 实际上玩家就在游戏里，所以online=1
        // ★ 使用push_player_login_status端点，带上online=1（玩家就在游戏里，已认证）
        boolean isRegistered = plugin.getDb().userExists(playerName);
        final String tokenFinal = token;

        // ★★★ 致命修复：原实现在主线程(命令处理)同步调用 doGet() → HttpClient.send()，
        // 当 Web 后端不可达时会阻塞服务端主线程 10 秒以上，触发 "server has not responded
        // for 10 seconds" 线程转储并冻结整个服务器。现改为完全异步执行 HTTP，
        // 结果通过 runTask 回到主线程再向玩家反馈。 ★★★
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean syncSuccess = false;
            try {
                // ★ 获取玩家真实IP地址
                String playerIp = "";
                java.net.InetSocketAddress addr = sender.getAddress();
                if (addr != null && addr.getAddress() != null) {
                    playerIp = addr.getAddress().getHostAddress();
                }
                String urlStr = webBaseUrl + "/api/sync.php?action=push_player_login_status"
                        + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                        + "&player=" + java.net.URLEncoder.encode(playerName, "UTF-8")
                        + "&web_token=" + java.net.URLEncoder.encode(tokenFinal, "UTF-8")
                        + "&expire_seconds=" + tokenExpireSeconds
                        + "&online=1"
                        + "&registered=" + (isRegistered ? "1" : "0")
                        + "&ip=" + java.net.URLEncoder.encode(playerIp, "UTF-8")
                        + "&login_verified=1"; // ★ 玩家在游戏里已认证，直接告诉PHP放行

                String response = doGet(urlStr);
                if (response != null) {
                    plugin.getLogger().info("[Web通信] push_player_login_status结果: " + response);
                    if (response.contains("\"success\":true")) {
                        syncSuccess = true;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Web通信] 同步Web登录Token失败: " + e.getMessage());
            }

            // ★ 回到主线程向玩家反馈结果（sendMessage 必须在主线程调用）
            final boolean ok = syncSuccess;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sender instanceof Player && !((Player) sender).isOnline()) return;
                if (ok) {
                    sender.sendMessage("§a§l===== Web登录 =====");
                    sender.sendMessage("§e请点击链接登录Web端:");
                    sender.sendMessage("§b" + webBaseUrl + "/login.php?token=" + tokenFinal);
                    sender.sendMessage("§7有效期: " + tokenExpireSeconds + "秒 | 一次性使用");
                } else {
                    sender.sendMessage("§c[Web] Token同步失败，请检查Web后端是否可访问");
                    sender.sendMessage("§7当前后端地址: " + webBaseUrl);
                }
            });
        });
    }

    /**
     * /sdf1_login web reload - 重载Web后端设置
     */
    public void handleWebReload(Player sender) {
        sender.sendMessage("§a[Web] Web后端配置已重载: 地址=" + webBaseUrl);
        sender.sendMessage("§a[Web] 已重新读取: 插件设置.txt");
    }

    /**
     * 自动生成URL（用于返回给前端）
     */
    public String getWebLoginUrl(String token) {
        return webBaseUrl + "/login.php?token=" + token;
    }

    /**
     * 玩家登录时自动生成Web登录Token并推送登录状态
     * 由Main.java的PlayerJoinEvent调用
     * 使用push_player_login_status端点，带上online=1让PHP知道玩家已在线
     */
    public void autoGenerateWebLoginToken(Player player) {
        if (!enabled) return;

        String playerName = player.getName();
        // 生成weblogin专用Token
        String token = generateToken(playerName, "weblogin");

        // ★ 生成token时检查login.db并推送注册状态给PHP
        boolean isRegistered = plugin.getDb().userExists(playerName);

        // ★ 使用push_player_login_status端点，推送玩家在线状态+token到PHP
        // ★ 必须异步执行，不能在server thread上同步HTTP（会导致10秒阻塞！）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean syncSuccess = false;
            try {
                String playerIp = "";
                java.net.InetSocketAddress addr = player.getAddress();
                if (addr != null && addr.getAddress() != null) {
                    playerIp = addr.getAddress().getHostAddress();
                }
                String urlStr = webBaseUrl + "/api/sync.php?action=push_player_login_status"
                        + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                        + "&player=" + java.net.URLEncoder.encode(playerName, "UTF-8")
                        + "&web_token=" + java.net.URLEncoder.encode(token, "UTF-8")
                        + "&expire_seconds=" + tokenExpireSeconds
                        + "&online=1"
                        + "&registered=" + (isRegistered ? "1" : "0")
                        + "&ip=" + java.net.URLEncoder.encode(playerIp, "UTF-8")
                        + "&login_verified=1";

                String response = doGet(urlStr);
                if (response != null) {
                    plugin.getLogger().info("[Web通信] push_player_login_status结果: " + response);
                    if (response.contains("\"success\":true")) {
                        syncSuccess = true;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Web通信] 推送玩家登录状态失败: " + e.getMessage());
            }

            // 回到主线程发送消息
            final boolean finalSuccess = syncSuccess;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    if (finalSuccess) {
                        player.sendMessage("§7[Web] §e请点击链接登录Web端:");
                        player.sendMessage("§b" + webBaseUrl + "/login.php?token=" + token);
                    } else {
                        player.sendMessage("§c[Web] Token同步失败，请检查Web后端: " + webBaseUrl);
                    }
                    player.sendMessage("§7[Web] 使用 §e/控制台 §7可重新获取");
                }
            });

            // 事件驱动：玩家加入时触发一次登录轮询
            triggerLoginPoll();
        });
    }

    /**
     * Web端验证登录Token
     * 由PHP后端调用（通过插件主动拉取）
     *
     * @param token 登录Token
     * @return 玩家名，null表示无效
     */
    public String validateWebLoginToken(String token) {
        String[] info = useToken(token);
        if (info == null) return null;
        if (!"weblogin".equals(info[1])) return null;
        return info[0]; // playerName
    }

    // ==================== 命令处理 ====================

    /**
     * 处理 /sdf1_login web 命令
     */
    public boolean handleCommand(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§e用法: /sdf1_login web <子命令>");
            sender.sendMessage("§7  sync - 手动同步商城数据");
            sender.sendMessage("§7  token <玩家> <用途> - 生成Token");
            sender.sendMessage("§7  status - 查看通信状态");
            sender.sendMessage("§7  cdk <兑换码> - 测试CDK验证");
            sender.sendMessage("§7  webshop - 从Web拉取商城数据");
            sender.sendMessage("§7  weblogin - 生成Web登录Token");
            return true;
        }

        String sub = args[1].toLowerCase();

        switch (sub) {
            case "sync":
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        submitDbTask("管理-syncShopData", () -> syncShopData());
                        submitDbTask("管理-syncBondBalances", () -> syncBondBalances());
                        submitDbTask("管理-syncBondTransactions", () -> syncBondTransactions());
                        submitDbTask("管理-syncUserRegistrations", () -> syncUserRegistrations());
                        submitDbTask("管理-syncServiceProviders", () -> syncServiceProviders());
                        submitDbTask("管理-syncLandData", () -> syncLandData());
                        submitDbTask("管理-pollAdminChanges", () -> pollAdminChanges());
                    }
                }.runTaskAsynchronously(plugin);
                sender.sendMessage("§a[Web] 同步任务已启动（通过DB队列串行化）...");
                break;

            case "token":
                if (args.length < 4) {
                    sender.sendMessage("§c用法: /sdf1_login web token <玩家> <用途>");
                    return true;
                }
                String target = args[2];
                String purpose = args[3];
                String token = generateAndSyncToken(target, purpose);
                sender.sendMessage("§a[Web] Token已生成:");
                sender.sendMessage("§e" + token);
                sender.sendMessage("§7用途: " + purpose + " | 有效期: " + tokenExpireSeconds + "秒");
                break;

            case "status":
                sender.sendMessage("§e[Web] 通信状态:");
                sender.sendMessage("§7  启用: " + (enabled ? "§a是" : "§c否"));
                sender.sendMessage("§7  地址: " + webBaseUrl);
                sender.sendMessage("§7  Token有效期: " + tokenExpireSeconds + "秒");
                sender.sendMessage("§7  同步间隔: " + syncIntervalMinutes + "分钟");
                sender.sendMessage("§7  活跃Token数: " + tokenStore.size());
                break;

            case "cdk":
                if (args.length < 3) {
                    sender.sendMessage("§c用法: /sdf1_login web cdk <兑换码>");
                    return true;
                }
                String cdkCode = args[2];
                String cdkPlayer = sender.getName();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        String result = verifyCDK(cdkCode, cdkPlayer);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (result.startsWith("success:")) {
                                String[] parts = result.split(":");
                                sender.sendMessage("§a[Web] CDK验证成功! 债券: +" + parts[1]);
                            } else {
                                sender.sendMessage("§c[Web] CDK验证失败: " + result.substring(5));
                            }
                        });
                    }
                }.runTaskAsynchronously(plugin);
                sender.sendMessage("§7[Web] 正在验证CDK...");
                break;

            case "webshop":
                handleWebShop(sender);
                break;

            case "weblogin":
                handleWebLogin(sender);
                break;

            case "reload":
                handleWebReload(sender);
                break;

            default:
                sender.sendMessage("§c未知子命令: " + sub);
                break;
        }
        return true;
    }

    // ==================== Getter ====================

    /**
     * Web通信是否启用。
     * ★ 2026-10-04：改为【实时读文件】而不是返回内存字段。
     *   这样「运维改 txt → 下一毫秒生效」，完全不需要 reload / 重启。
     *   文件不可读时回退到内存值，避免因文件临时不可读而误判为未启用。
     */
    public boolean isEnabled() {
        try {
            File file = new File(plugin.getDataFolder(), "插件设置.txt");
            if (file.exists()) {
                return Boolean.parseBoolean(getConfigValue("web通信-启用", "false"));
            }
        } catch (Exception ignore) { }
        return enabled;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public int getTokenExpireSeconds() {
        return tokenExpireSeconds;
    }

    public String getSecretKey() {
        return secretKey;
    }

    /**
     * 异步通知 PHP：玩家加入服务器，触发异地登录检测与提醒邮件。
     * PHP 端比较 IP 归属并决定是否发邮件（含冻结/改密链接）。
     */
    public void reportLoginLocation(String name, String ip) {
        if (!enabled) return;
        try {
            String url = webBaseUrl + "/api/security_alert.php"
                    + "?action=login_location_alert"
                    + "&secret=" + java.net.URLEncoder
                            .encode(secretKey, "UTF-8")
                    + "&name=" + java.net.URLEncoder
                            .encode(name, "UTF-8")
                    + "&ip=" + java.net.URLEncoder
                            .encode(ip, "UTF-8");
            doGet(url);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[安全] 通知PHP异地登录检测失败: "
                            + e.getMessage());
        }
    }

    // ==================== Web登录轮询 ====================

    /**
     * 启动Web登录轮询任务
     * 每5秒轮询PHP后端，获取已通过Web验证的玩家，自动登录游戏
     * 登录轮询保持较短间隔，提升玩家登录体验
     * 使用异步线程处理，避免Web响应慢导致游戏卡死
     */
    /**
     * Web登录轮询（已合并到定时器A，保留方法签名供回调触发）
     * 事件驱动仍有效：triggerLoginPoll() 可在必要时机提前触发
     */
    public void startWebLoginPolling() {
        // 已合并到定时器A（合并定时器自动轮询登录确认和密码验证）
    }

    /**
     * 触发一次登录轮询（事件驱动）
     * 由玩家加入服务器或PHP回调时调用
     */
    public void triggerLoginPoll() {
        if (!enabled) return;

        // 检查是否有玩家正在登录
        if (!hasPendingLogins()) return;

        // ★ HTTP在webExecutor执行，不阻塞DB队列
        submitWebTask("登录轮询-pollWebLoginConfirmations", () -> {
            try {
                pollWebLoginConfirmations();
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                if (now - lastPollWebLoginExceptionLog > LOG_INTERVAL) {
                    plugin.getLogger().warning("[Web登录轮询] pollWebLoginConfirmations异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    lastPollWebLoginExceptionLog = now;
                }
            }
        });
        submitWebTask("登录轮询-pollWebLoginRequests", () -> {
            try {
                pollWebLoginRequests();
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                if (now - lastPollWebLoginExceptionLog > LOG_INTERVAL) {
                    plugin.getLogger().warning("[Web登录轮询] pollWebLoginRequests异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    lastPollWebLoginExceptionLog = now;
                }
            }
        });
    }

    /**
     * 检查是否有待处理的登录请求
     */
    private boolean hasPendingLogins() {
        // 检查是否有玩家正在等待登录确认
        return !tokenStore.isEmpty() || Bukkit.getOnlinePlayers().size() > 0;
    }

    /**
     * 轮询Token登录确认
     * 获取已通过WebToken验证的玩家列表，自动登录游戏
     * 注意：此方法在异步线程中执行，不要在方法内创建BukkitRunnable
     */
    private void pollWebLoginConfirmations() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=check_web_login_confirmations&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(urlStr);
            if (json == null) {
                loginPollFailCount++;
                long now = System.currentTimeMillis();
                if (now - lastLoginPollLogTime > POLL_LOG_INTERVAL) {
                    warnSlow("轮询登录确认", "[Web登录确认轮询] ✗ GET失败 (连续失败" + loginPollFailCount + "次)");
                    lastLoginPollLogTime = now;
                }
                return;
            }

            // 成功 → 重置
            loginPollFailCount = 0;

            if (!json.contains("\"success\":true")) {
                plugin.getLogger().info("[Web登录确认轮询] ✗ PHP响应非success");
                return;
            }

            // 简单解析玩家名列表 + php_verified状态
            int dataStart = json.indexOf("\"data\":");
            if (dataStart < 0) {
                plugin.getLogger().info("[Web登录确认轮询] ✗ PHP响应无data字段");
                return;
            }
            String dataStr = json.substring(dataStart + 7);
            int arrEnd = findMatchingBracket(dataStr, 0);
            if (arrEnd < 0) return;
            String arrStr = dataStr.substring(0, arrEnd + 1);

            // ★ 解析每个确认记录（player_name + php_verified）
            java.util.List<String> players = new java.util.ArrayList<>();
            java.util.Map<String, Boolean> phpVerifiedMap = new java.util.HashMap<>();
            int idx = 0;
            while (true) {
                int objStart = arrStr.indexOf("{", idx);
                if (objStart < 0) break;
                int objEnd = findMatchingBracket(arrStr, objStart);
                if (objEnd < 0) break;
                String obj = arrStr.substring(objStart, objEnd + 1);

                // 提取player_name
                int nameStart = obj.indexOf("\"player_name\":\"");
                if (nameStart < 0) { idx = objEnd + 1; continue; }
                nameStart += 15;
                int nameEnd = obj.indexOf("\"", nameStart);
                if (nameEnd < 0) { idx = objEnd + 1; continue; }
                String playerName = obj.substring(nameStart, nameEnd);

                // 提取php_verified
                boolean phpVerified = obj.contains("\"php_verified\":true");

                players.add(playerName);
                phpVerifiedMap.put(playerName, phpVerified);
                idx = objEnd + 1;
            }

            if (players.isEmpty()) {
                return;
            }

            plugin.getLogger().info("[Web登录确认轮询] ★ 发现 " + players.size() + " 个登录确认: " + players
                    + " phpVerified=" + phpVerifiedMap);

            // 在主线程处理每个玩家的自动登录
            for (String playerName : players) {
                final String name = playerName;
                final boolean phpVerified = phpVerifiedMap.getOrDefault(playerName, false);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        boolean javaVerified = isWebLoginVerified(name);
                        plugin.getLogger().info("[Web登录确认轮询] ★ 安全检查 player=" + name
                                + " javaVerified=" + javaVerified
                                + " phpVerified=" + phpVerified
                                + " javaVerifiedKeys=" + verifiedWebLogins.keySet());

                        // ★ 安全逻辑简化：
                        // web_login_confirmations 记录只能由以下途径写入：
                        //   1. completeWebLoginRequest — Java密码验证成功后回调PHP写入
                        //   2. 邮箱验证码验证 — PHP验证邮箱验证码后写入
                        // PHP端 verifyWebPassword() 已禁用，不可能自验证密码后写入
                        // 因此：有确认记录 = 已通过某种安全验证，直接允许自动登录

                        if (!javaVerified && phpVerified) {
                            // Java内存没有但PHP有 → 信任PHP，写入Java内存
                            plugin.getLogger().info("[Web登录确认轮询] ★ 信任PHP验证，写入Java记录 player=" + name);
                            recordWebLogin(name);
                        } else if (!javaVerified && !phpVerified) {
                            // ★ 两边都没有内存记录，但确认记录本身已代表验证通过
                            // 仍信任确认记录（可能Java重启后内存清空，但PHP确认仍在）
                            plugin.getLogger().info("[Web登录确认轮询] ★ 内存记录均已过期，信任PHP确认记录 player=" + name);
                        }

                        plugin.getLogger().info("[Web登录确认轮询] ★ 尝试自动登录 player=" + name);
                        boolean ok = plugin.handleWebLoginConfirmation(name);
                        if (ok) {
                            clearWebLoginVerified(name);  // ★ 消费后立即清除
                            plugin.getLogger().info("[Web登录确认轮询] ✓ 玩家 " + name + " 已自动登录成功");
                        } else {
                            plugin.getLogger().info("[Web登录确认轮询] ○ 玩家 " + name + " 不在线，跳过（确认记录保留5分钟，等上线后onJoin放行）");
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Web登录确认轮询] ✗ 处理异常: player=" + name
                                + " error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            loginPollFailCount++;
            long now = System.currentTimeMillis();
            if (now - lastLoginPollLogTime > POLL_LOG_INTERVAL) {
                plugin.getLogger().warning("[Web登录确认轮询] ✗ 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()
                        + " (连续失败" + loginPollFailCount + "次)");
                lastLoginPollLogTime = now;
            }
        }
    }

    /**
     * 轮询密码登录请求
     * 获取Web端提交的密码登录请求，本地验证密码，写回结果
     * 注意：此方法在异步线程中执行，不要在方法内创建BukkitRunnable
     */
    private void pollWebLoginRequests() {
        try {
            // ★ 定期清理已处理请求记录和已验证登录状态（避免内存泄漏）
            if (processedWebLoginRequests.size() > 100) {
                long now = System.currentTimeMillis();
                processedWebLoginRequests.entrySet().removeIf(entry ->
                        (now - entry.getValue()) > PROCESSED_REQUEST_EXPIRE_MS
                );
            }
            if (verifiedWebLogins.size() > 0) {
                long now = System.currentTimeMillis();
                verifiedWebLogins.entrySet().removeIf(entry ->
                        (now - entry.getValue()) > VERIFIED_LOGIN_EXPIRE_MS
                );
            }
            // ★ 清理过期的Java登录记录（检查点1用）
            if (javaLoginRecords.size() > 0) {
                long now = System.currentTimeMillis();
                javaLoginRecords.entrySet().removeIf(entry ->
                        (now - entry.getValue()) > JAVA_LOGIN_RECORD_EXPIRE_MS
                );
            }
            String urlStr = webBaseUrl + "/api/sync.php?action=check_pending_web_logins&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(urlStr);
            if (json == null) {
                loginPollFailCount++;
                long now = System.currentTimeMillis();
                if (now - lastLoginPollLogTime > POLL_LOG_INTERVAL) {
                    warnSlow("轮询密码验证", "[Web密码验证轮询] GET失败 (连续失败" + loginPollFailCount + "次)");
                    lastLoginPollLogTime = now;
                }
                return;
            }
            // 成功 → 重置
            loginPollFailCount = 0;
            if (!json.contains("\"success\":true")) {
                plugin.getLogger().info("[Web密码验证轮询] ✗ PHP响应非success");
                return;
            }

            // 解析请求列表
            int dataStart = json.indexOf("\"data\":");
            if (dataStart < 0) {
                plugin.getLogger().info("[Web密码验证轮询] ✗ PHP响应无data字段");
                return;
            }
            String dataStr = json.substring(dataStart + 7);
            int arrEnd = findMatchingBracket(dataStr, 0);
            if (arrEnd < 0) return;
            String arrStr = dataStr.substring(0, arrEnd + 1);

            // ★ 逐个提取 {...} 对象，用 parseJson 解析，避免手动截取密码出错
            if (arrStr.trim().equals("[]")) {
                return;
            }
            int pendingCount = countTopLevelJsonObjects(arrStr);
            plugin.getLogger().info("[Web密码验证轮询] ★ 发现待处理请求，条数=" + pendingCount
                    + "（JSON长度=" + arrStr.length() + "）");
            int idx = 0;
            while (true) {
                int objStart = arrStr.indexOf("{", idx);
                if (objStart < 0) break;
                int objEnd = findMatchingBracket(arrStr, objStart);
                if (objEnd < 0) break;
                String objStr = arrStr.substring(objStart, objEnd + 1);
                idx = objEnd + 1;

                Map<String, Object> obj = parseJson(objStr);
                if (obj == null || obj.isEmpty()) continue;

                String reqId = String.valueOf(obj.get("id"));
                String playerName = (String) obj.get("player_name");
                String password = (String) obj.get("password");

                if (reqId == null || playerName == null || password == null) continue;

                // ★ 检查是否已处理过此请求（防止PHP未更新状态时重复验证）
                Long processedTime = processedWebLoginRequests.get(reqId);
                if (processedTime != null && (System.currentTimeMillis() - processedTime) < PROCESSED_REQUEST_EXPIRE_MS) {
                    // 已在60秒内处理过，跳过
                    continue;
                }

                plugin.getLogger().info("[Web密码验证] 收到请求 reqId=" + reqId + " player=" + playerName + " pwdLen=" + password.length());

                // 标记为已处理
                processedWebLoginRequests.put(reqId, System.currentTimeMillis());

                // ★ 异步验证密码：handleWebPasswordVerify 是纯 DB（login.db），
                //   原来 runTask 到主线程跑同步查询，数据量涨起来会顶 tick
                final String fReqId = reqId;
                final String fName = playerName;
                final String fPwd = password;
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String result = plugin.handleWebPasswordVerify(fName, fPwd);
                        plugin.getLogger().info("[Web密码验证] 结果: player=" + fName + " result=" + result);

                        // ★ 密码验证成功时立即写入verifiedWebLogins
                        // 这是Java本地验证的结果（不是PHP自验证），安全可信
                        // pollWebLoginConfirmations收到PHP确认后，会检查此记录才允许自动登录
                        if ("\"success\"".equals(result)) {
                            recordWebLogin(fName);
                        }

                        // 异步将结果写回PHP（供Web端查询）
                        try {
                            sendWebLoginResult(fReqId, fName, result);
                        } catch (Throwable t) {
                            // ★ 热替换jar后类加载器可能找不到匿名/内部类（Error不是Exception，
                            //   外层catch接不住）→ 复位处理标记，下一轮重新验证重试
                            plugin.getLogger().warning("[Web密码验证] 回写调度失败: " + t
                                    + " → 已复位处理标记，稍后重试");
                            processedWebLoginRequests.remove(fReqId);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Web密码验证] 处理异常: player=" + fName + " error=" + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastPollWebLoginExceptionLog > LOG_INTERVAL) {
                plugin.getLogger().warning("[Web密码登录轮询] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                lastPollWebLoginExceptionLog = now;
            }
        }
    }

    /**
     * 将密码验证结果写回PHP后端
     * 使用GET请求传所有参数（避免POST body解析问题），同时增加重试机制
     *
     * ★ 用 lambda 而非匿名内部类（原 WebManager$15）：
     *   jar 被热替换后 PluginClassLoader 找不到旧的匿名内部类，
     *   抛 NoClassDefFoundError → 回写永远失败 → PHP pending 永不清空。
     *   lambda 编译为 WebManager 自身的合成方法，类已加载，不受热替换影响。
     */
    private void sendWebLoginResult(String reqId, String playerName, String result) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 将result转换为JSON对象格式 {"success": true/false, "status": "..."}
            String resultJson;
            if ("\"success\"".equals(result)) {
                resultJson = "{\"success\":true,\"status\":\"success\"}";
            } else if ("\"not_registered\"".equals(result)) {
                resultJson = "{\"success\":false,\"status\":\"not_registered\"}";
            } else {
                resultJson = "{\"success\":false,\"status\":\"failed\"}";
            }

            plugin.getLogger().info("[Web密码验证回写] ★ 开始回写PHP player=" + playerName
                    + " reqId=" + reqId + " result=" + result);

            // 使用GET请求，所有参数通过URL传递，避免POST body解析问题
            // 最多重试3次，每次间隔2秒
            boolean sent = false;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    String resultEncoded = java.net.URLEncoder.encode(resultJson, "UTF-8");
                    String urlStr = webBaseUrl + "/api/sync.php?action=complete_web_login_request"
                            + "&secret=" + java.net.URLEncoder.encode(secretKey, "UTF-8")
                            + "&request_id=" + reqId
                            + "&player=" + java.net.URLEncoder.encode(playerName, "UTF-8")
                            + "&result=" + resultEncoded;

                    String resp = doGet(urlStr);

                    if (resp != null) {
                        sent = true;
                        plugin.getLogger().info("[Web密码验证回写] ✓ 成功: player=" + playerName
                                + " reqId=" + reqId + " result=" + result
                                + " PHP响应=" + resp.substring(0, Math.min(200, resp.length()))
                                + " (第" + (attempt + 1) + "次)");
                        break;
                    } else {
                        plugin.getLogger().warning("[Web密码验证回写] ✗ GET失败: player=" + playerName
                                + " reqId=" + reqId + " (第" + (attempt + 1) + "次)");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web密码验证回写] ✗ 异常: player=" + playerName
                            + " reqId=" + reqId + " (第" + (attempt + 1) + "次) " + e.getMessage());
                }
                // 重试前等待
                try { Thread.sleep(2000); } catch (InterruptedException ie) { break; }
            }

            if (!sent) {
                plugin.getLogger().warning("[Web密码验证回写] ✗ 最终失败，已重试3次: player=" + playerName + " reqId=" + reqId);
                // ★ 回写失败 → 复位"已处理"标记，下一轮轮询重新验证并重试写回，
                //   否则该请求会在PHP里挂满10分钟过期，Web端一直转圈
                processedWebLoginRequests.remove(reqId);
            }
        });
    }

    /**
     * 查找匹配的右括号位置（支持 [] 和 {}）
     * 根据 start 位置的字符自动选择匹配对：
     * '[' → 匹配 ']'
     * '{' → 匹配 '}'
     */
    private int findMatchingBracket(String s, int start) {
        if (start < 0 || start >= s.length()) return -1;
        char open = s.charAt(start);
        char close;
        if (open == '[') close = ']';
        else if (open == '{') close = '}';
        else return -1;

        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < s.length()) {
                    i++;
                    continue;
                }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * 统计JSON数组中顶层对象 {…} 的个数（用于日志显示真实待处理条数，
     * 以前打印的是字符串长度，容易被误读成条数）
     */
    private static int countTopLevelJsonObjects(String arr) {
        if (arr == null || arr.isEmpty()) return 0;
        int count = 0, depth = 0;
        boolean inString = false;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < arr.length()) { i++; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == '{') { if (depth == 0) count++; depth++; }
            else if (c == '}') { if (depth > 0) depth--; }
        }
        return count;
    }

    // ==================== 新增功能 ====================

    // 日志控制：避免频繁打印
    private long lastPullTransactionsLog = 0;
    private long lastPullShopStockLog = 0;
    private long lastPullBondChangesLog = 0;
    private long lastPollRegisterRequestsLog = 0;
    private long lastPollWebLoginExceptionLog = 0;
    private long lastPollerTriggerTime = 0; // 补单触发限流（15秒间隔）
    private static final long LOG_INTERVAL = 60000; // 1分钟内不重复打印相同日志

    /**
     * 拉取待处理的交易（Web购买/充值）
     */
    private void pullPendingTransactions() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    // ★ 2026-07-15 补单机制：拉取交易前先触发 poller_online.php 补单
                    //   平台 HTTP 回调被 Cloudflare WAF 拦截，只能靠本脚本读平台 MySQL 已支付订单补单。
                    //   先等补单返回（doGet 内置 30s 超时）再拉取 web_transactions，确保充值能到账。
                    try {
                        String pollerUrl = webBaseUrl + "/api/poller_online.php?secret="
                                + java.net.URLEncoder.encode(secretKey, "UTF-8");
                        long pollerStart = System.currentTimeMillis();
                        String pollerResp = doGet(pollerUrl);
                        long pollerElapsed = System.currentTimeMillis() - pollerStart;

                        if (pollerResp != null) {
                            if (pollerResp.contains("\"result\":\"ok\"")) {
                                plugin.getLogger().info("[Web交易] 补单成功 (" + pollerElapsed + "ms): " + pollerResp.substring(0, Math.min(150, pollerResp.length())));
                            } else if (pollerResp.contains("\"result\":\"already_running\"")) {
                                plugin.getLogger().info("[Web交易] 补单进程正在运行，跳过 (" + pollerElapsed + "ms)");
                            } else {
                                plugin.getLogger().warning("[Web交易] 补单返回: " + pollerResp.substring(0, Math.min(150, pollerResp.length())) + " (" + pollerElapsed + "ms)");
                            }
                        } else {
                            warnSlow("交易补单", "[Web交易] 补单无响应(null)，耗时: " + pollerElapsed + "ms，继续拉取");
                        }
                    } catch (Exception pollerEx) {
                        plugin.getLogger().warning("[Web交易] 补单触发异常(忽略，继续拉取): " + pollerEx.getMessage());
                    }

                    String urlStr = webBaseUrl + "/api/sync.php?action=pull_pending_transactions&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null) {
                        plugin.getLogger().warning("[Web交易] GET失败，3秒后重试...");
                        budgetSleep(3000);
                        // 重试一次
                        json = doGet(urlStr);
                        if (json == null) {
                            plugin.getLogger().warning("[Web交易] 重试后仍失败，跳过本轮交易拉取");
                            return;
                        }
                    }
                    if (!json.contains("\"success\":true")) {
                        plugin.getLogger().warning("[Web交易] 响应不含success:true: " + json.substring(0, Math.min(200, json.length())));
                        return;
                    }

                    plugin.getLogger().info("[Web交易] PHP响应长度: " + json.length());

                    // 解析交易列表
                    int dataStart = json.indexOf("\"transactions\":");
                    if (dataStart < 0) {
                        plugin.getLogger().warning("[Web交易] 响应不含transactions字段");
                        return;
                    }
                    String dataStr = json.substring(dataStart + 15);
                    int arrEnd = findMatchingBracket(dataStr, 0);
                    if (arrEnd < 0) {
                        plugin.getLogger().warning("[Web交易] 找不到transactions数组结束括号");
                        return;
                    }
                    String arrStr = dataStr.substring(0, arrEnd + 1);
                    plugin.getLogger().info("[Web交易] transactions数组内容: " + (arrStr.length() > 500 ? arrStr.substring(0, 500) + "..." : arrStr));

                    // 提取每个交易
                    int idx = 0;
                    int txCount = 0;
                    while (true) {
                        int idStart = arrStr.indexOf("\"id\":", idx);
                        if (idStart < 0) break;
                        idStart += 5;
                        int idEnd = arrStr.indexOf(",", idStart);
                        if (idEnd < 0) break;
                        String txId = arrStr.substring(idStart, idEnd).trim();

                        int nameStart = arrStr.indexOf("\"player_name\":\"", idEnd);
                        if (nameStart < 0) break;
                        nameStart += 15;
                        int nameEnd = arrStr.indexOf("\"", nameStart);
                        if (nameEnd < 0) break;
                        String playerName = arrStr.substring(nameStart, nameEnd);

                        int typeStart = arrStr.indexOf("\"type\":\"", nameEnd);
                        if (typeStart < 0) break;
                        typeStart += 8;
                        int typeEnd = arrStr.indexOf("\"", typeStart);
                        if (typeEnd < 0) break;
                        String type = arrStr.substring(typeStart, typeEnd);

                        int amountStart = arrStr.indexOf("\"amount\":", typeEnd);
                        if (amountStart < 0) break;
                        amountStart += 9;
                        int amountEnd = arrStr.indexOf(",", amountStart);
                        if (amountEnd < 0) break;
                        String amount = arrStr.substring(amountStart, amountEnd).trim();

                        // 提取detail字段（处理嵌套JSON，支持转义引号\"）
                        String detail = "";
                        int detailStart = arrStr.indexOf("\"detail\":", amountEnd);
                        if (detailStart >= 0) {
                            detailStart += 9;
                            // 跳过空格
                            while (detailStart < arrStr.length() && arrStr.charAt(detailStart) == ' ') detailStart++;
                            if (detailStart < arrStr.length() && arrStr.charAt(detailStart) == '"') {
                                detailStart++;
                                // 查找匹配的闭合引号（跳过转义的\"）
                                int detailEnd = -1;
                                for (int i = detailStart; i < arrStr.length(); i++) {
                                    char c = arrStr.charAt(i);
                                    if (c == '\\' && i + 1 < arrStr.length() && arrStr.charAt(i + 1) == '"') {
                                        i++; // 跳过转义字符
                                        continue;
                                    }
                                    if (c == '"') {
                                        detailEnd = i;
                                        break;
                                    }
                                }
                                if (detailEnd > detailStart) {
                                    detail = arrStr.substring(detailStart, detailEnd);
                                    // 反转义：JSON中的 \" 还原为 "
                                    detail = detail.replace("\\\"", "\"");
                                }
                            }
                        }

                        idx = amountEnd + 1;
                        txCount++;

                        plugin.getLogger().info("[Web交易] 提取交易 #" + txCount + ": ID=" + txId + ", 玩家=" + playerName + ", 类型=" + type + ", 金额=" + amount);

                        // ★ 2026-07-14 关键修复：拉取即贴标 —— 立即ack防止重复处理
                        // 之前：拉取→processing→处理→confirm，中间任何环节失败都会导致重拉重复扣费
                        // 现在：拉取→立即ack（写confirmed表）→处理→confirm（保险）
                        ackTransaction(txId, type);

                        // 在主线程处理交易（Bukkit 发货/背包操作必须主线程）
                        // ★ 交易内的 HTTP（confirm/refund）已由 confirmTransaction/
                        //   writeRefundTransaction 改为异步投递，主线程不再等待网络。
                        final String fTxId = txId;
                        final String fName = playerName;
                        final String fType = type;
                        final int fAmount = Integer.parseInt(amount);
                        final String fDetail = detail;
                        plugin.getLogger().info("[Web交易] 准备处理交易 #" + txCount + ": " + fName + " " + fType + " " + fAmount);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            try {
                                processWebTransaction(fTxId, fName, fType, fAmount, fDetail);
                            } catch (Exception e) {
                                plugin.getLogger().warning("[Web通信] 处理交易异常: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                    }
                    plugin.getLogger().info("[Web交易] 本轮共提取 " + txCount + " 个交易");
                } catch (Exception e) {
                    // 静默处理
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 处理Web交易
     */
    private void processWebTransaction(String txId, String playerName, String type, int amount, String detail) {
        // ★ 去重检查：本会话已确认的交易不再重复处理（防止重启后PHP回退processing→pending导致重复发货）
        if (confirmedTxIds.contains(txId)) {
            plugin.getLogger().info("[Web交易] 跳过已确认交易: ID=" + txId + "（本会话已处理，可能PHP端confirm未持久化）");
            confirmTransaction(txId); // 重新确认一次，确保PHP状态一致
            return;
        }

        plugin.getLogger().info("[Web交易] 处理交易: ID=" + txId + ", 玩家=" + playerName + ", 类型=" + type + ", 金额=" + amount + ", 详情=" + detail);
        boolean txSuccess = false;
        String errorMsg = "";
        try {
            if (type.equals("shop_buy")) {
                // Web购买商品：先检查冻结 + 余额
                if (plugin.getBondManager().isFrozen(playerName)) {
                    plugin.getLogger().warning("[Web交易] 拒绝: 玩家 " + playerName + " 账户已冻结");
                    confirmTransaction(txId);
                    return;
                }
                int balance = plugin.getBondManager().getBonds(playerName);
                if (balance < amount) {
                    plugin.getLogger().warning("[Web交易] 拒绝: 玩家 " + playerName + " 余额不足 (有" + balance + ", 需" + amount + ")");
                    confirmTransaction(txId);
                    return;
                }

                // 解析detail获取商品信息
                String itemId = "";
                int itemCount = 1;
                try {
                    if (detail != null && !detail.isEmpty()) {
                        // 简单JSON解析: {"item_id":"XXX","amount":N}
                        int itemIdStart = detail.indexOf("\"item_id\":\"") + 11;
                        if (itemIdStart > 10) {
                            int itemIdEnd = detail.indexOf("\"", itemIdStart);
                            if (itemIdEnd > itemIdStart) {
                                itemId = detail.substring(itemIdStart, itemIdEnd);
                            }
                        }
                        int amountFieldStart = detail.indexOf("\"amount\":");
                        if (amountFieldStart >= 0) {
                            amountFieldStart += 9;
                            int amountFieldEnd = detail.indexOf(",", amountFieldStart);
                            if (amountFieldEnd < 0) amountFieldEnd = detail.indexOf("}", amountFieldStart);
                            if (amountFieldEnd > amountFieldStart) {
                                itemCount = Integer.parseInt(detail.substring(amountFieldStart, amountFieldEnd).trim());
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易] 解析商品详情失败: " + detail);
                }

                plugin.getLogger().info("[Web交易] 商品ID: " + itemId + ", 数量: " + itemCount);

                // ★ 关键修复：type=shop_buy时，PHP的amount是消费金额，Java应该扣除
                // 但PHP的交易记录amount字段对shop_buy是消费金额，对cdk_redeem是充值金额
                // 这里Java收到的amount参数已经是PHP传来的正确值
                boolean ok = plugin.getBondManager().deductBonds(playerName, amount,
                        "web_shop", itemId, "Web商城", "Web购买商品 " + itemId + " x" + itemCount);
                if (ok) {
                    int newBal = plugin.getBondManager().getBonds(playerName);
                    plugin.getLogger().info("[Web交易] 扣除成功: 玩家 " + playerName + " 购买商品 " + itemId + " x" + itemCount + "，金额: " + amount);
                    log.info("[草原探险]MC草原探险服务器欢迎您，服务器ip：mc2.ypshidifu.cn，端口：30679");

                    // 发货给玩家
                    Player player = plugin.getServer().getPlayer(playerName);
                    if (player != null && player.isOnline()) {
                        plugin.getLogger().info("[Web交易] 玩家在线，立即发货");
                        // ★ 通知玩家购买成功
                        player.sendMessage("§6[商城] §fWeb购买 §e" + itemId + " x" + itemCount + " §f成功！§c-" + amount + "§f 债券");
                        player.sendMessage("§6[债券] §f余额: §e" + (newBal + amount) + " §7→ §a" + newBal);
                        boolean delivered = dispatchItemByMaterialOrId(player, itemId, itemCount);
                        if (!delivered) {
                            // ★ 发放失败（附魔书解析失败等）→ 整笔不确认 + 写退款，避免玩家付了钱拿不到东西
                            plugin.getLogger().warning("[Web交易] 单笔购买发放失败，发起退款: " + itemId + " 交易#" + txId);
                            player.sendMessage("§c[商城] §f商品发放失败，已自动退款：§e" + itemId);
                            try {
                                writeRefundTransaction(playerName, amount, txId, "单笔购买发放失败:" + itemId, "bond");
                            } catch (Exception ex) {
                                plugin.getLogger().warning("[Web交易] 写入退款记录失败: " + ex.getMessage());
                            }
                            return; // 不 confirm，PHP 端退款
                        }
                    } else {
                        plugin.getLogger().info("[Web交易] 玩家离线，保存到离线邮件");
                        saveOfflineItem(playerName, itemId, itemCount);
                    }
                } else {
                    plugin.getLogger().warning("[Web交易] 扣除失败: 玩家 " + playerName + " 余额不足 (当前余额: " + plugin.getBondManager().getBonds(playerName) + ")");
                }
                confirmTransaction(txId);
            } else if (type.equals("shop_cart")) {
                // 购物车批量结算：detail 含 settlement + items[] + pay_mode
                if (plugin.getBondManager().isFrozen(playerName)) {
                    plugin.getLogger().warning("[Web交易] 拒绝: 玩家 " + playerName + " 账户已冻结");
                    confirmTransaction(txId);
                    return;
                }

                // 解析 detail 中的结算方式、颜色、收款模式与商品列表
                String settlement = "backpack";
                String shulkerColorName = "default"; // 默认原色（免费潜影盒 = SHULKER_BOX）
                Material shulkerMat = Material.SHULKER_BOX; // 免费潜影盒使用原版默认颜色
                String payMode = "bond"; // bond=债券扣款; cash=现金仅记账不扣债券
                java.util.List<CartEntry> entries = new java.util.ArrayList<>();
                java.util.List<OrderManager.OrderItem> receiptItems = new java.util.ArrayList<>();
                try {
                    if (detail != null && !detail.isEmpty()) {
                        Gson gson = new Gson();
                        JsonObject root = gson.fromJson(detail, JsonObject.class);
                        if (root != null) {
                            if (root.has("settlement")) settlement = root.get("settlement").getAsString();
                            if (root.has("shulker_color")) shulkerColorName = root.get("shulker_color").getAsString();
                            if (root.has("pay_mode")) payMode = root.get("pay_mode").getAsString();
                            if (root.has("items")) {
                                JsonArray arr = root.getAsJsonArray("items");
                                for (int i = 0; i < arr.size(); i++) {
                                    JsonObject it = arr.get(i).getAsJsonObject();
                                    String iid = it.has("item_id") ? it.get("item_id").getAsString() : "";
                                    int amt = it.has("amount") ? it.get("amount").getAsInt() : 0;
                                    if (!iid.isEmpty() && amt > 0) {
                                        String iname = it.has("name") ? it.get("name").getAsString() : iid;
                                        entries.add(new CartEntry(iid, amt, iname));
                                        int iprice = it.has("unit_price") ? it.get("unit_price").getAsInt() : 0;
                                        receiptItems.add(new OrderManager.OrderItem(iname, iid, iprice, iprice, amt));
                                    }
                                }
                            }
                        }
                        // 潜影盒颜色映射
                        shulkerMat = mapShulkerColor(shulkerColorName);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易] 购物车detail解析失败: " + detail);
                }

                // 余额校验（现金模式跳过：仅记账不扣债券）
                if (!"cash".equals(payMode)) {
                    int balance = plugin.getBondManager().getBonds(playerName);
                    if (balance < amount) {
                        plugin.getLogger().warning("[Web交易] 拒绝: 玩家 " + playerName + " 余额不足 (有" + balance + ", 需" + amount + ")");
                        confirmTransaction(txId);
                        return;
                    }
                }

                // 扣款 / 发货（现金模式跳过扣款）
                boolean deliver;
                if ("cash".equals(payMode)) {
                    deliver = true; // 现金收款：仅记账，不扣玩家债券
                } else {
                    deliver = plugin.getBondManager().deductBonds(playerName, amount,
                            "web_shop", "cart", "Web商城", "Web购物车结算(共" + entries.size() + "项)");
                    if (!deliver) plugin.getLogger().warning("[Web交易] 购物车扣款失败: 玩家 " + playerName + " 余额不足");
                }

                if (deliver) {
                    int newBal = plugin.getBondManager().getBonds(playerName);
                    Player player = plugin.getServer().getPlayer(playerName);
                    if (player != null && player.isOnline()) {
                        // ★ 发货失败跟踪：任一商品发放失败则整单标记为需退款
                        java.util.List<String> failedItems = new java.util.ArrayList<>();
                        java.util.List<String> okItems = new java.util.ArrayList<>();

                        if ("cash".equals(payMode)) {
                            player.sendMessage("§6[商城] §f购物车结算成功！§e现金记账 §f（未扣债券），共 " + entries.size() + " 项");
                        } else {
                            player.sendMessage("§6[商城] §f购物车结算成功！§c-" + amount + "§f 债券，共 " + entries.size() + " 项");
                        }
                        if ("shulker".equals(settlement)) {
                            java.util.List<ItemStack> stacks = new java.util.ArrayList<>();
                            for (CartEntry ce : entries) {
                                ItemStack st = buildShopStack(ce.itemId, ce.amount, ce.name);
                                if (st != null) {
                                    stacks.add(st);
                                    okItems.add(ce.name != null ? ce.name : ce.itemId);
                                } else {
                                    failedItems.add(ce.itemId);
                                }
                            }
                            // 即使有失败商品，仍尝试打包成功的那部分
                            if (!stacks.isEmpty()) {
                                java.util.List<ItemStack> boxes = packCartIntoShulkers(stacks, shulkerMat);
                                String colorCn = (shulkerColorName.equals("default") || shulkerColorName.equals("purple"))
                                        ? "原色" : shulkerColorName;
                                // 购物小票
                                if (!boxes.isEmpty() && plugin.getOrderManager() != null) {
                                    try {
                                        OrderManager.OrderRecord rec = new OrderManager.OrderRecord();
                                        rec.orderId = System.currentTimeMillis();
                                        rec.player = playerName;
                                        rec.items = receiptItems;
                                        rec.totalOriginal = amount;
                                        rec.totalPaid = amount;
                                        rec.discount = 0;
                                        rec.discountType = "cash".equals(payMode) ? "cash" : "none";
                                        rec.packFee = 0;
                                        rec.packType = (shulkerMat == Material.SHULKER_BOX) ? "default" : "custom";
                                        rec.packColor = shulkerColorName;
                                        rec.timestamp = System.currentTimeMillis();
                                        rec.status = 1;
                                        ItemStack book = plugin.getOrderManager().createReceiptBook(rec);
                                        plugin.getOrderManager().addBookToShulker(boxes.get(0), book);
                                    } catch (Exception ex) {
                                        plugin.getLogger().warning("[Web交易] 小票书生成失败: " + ex.getMessage());
                                    }
                                }
                                for (ItemStack box : boxes) {
                                    java.util.HashMap<Integer, ItemStack> left = player.getInventory().addItem(box);
                                    for (ItemStack drop : left.values()) player.getWorld().dropItemNaturally(player.getLocation(), drop);
                                }
                                player.sendMessage("§6[商城] §f已打包为 §e" + boxes.size() + " §f个" + colorCn + "潜影盒（含小票书）");
                            }
                        } else {
                            for (CartEntry ce : entries) {
                                try {
                                    boolean delivered = dispatchItemByMaterialOrId(player, ce.itemId, ce.amount, ce.name);
                                    if (delivered) {
                                        okItems.add(ce.name != null ? ce.name : ce.itemId);
                                    } else {
                                        // ★ 发放失败（含附魔书解析失败）→ 计入失败列表，触发整单取消+退款
                                        failedItems.add(ce.itemId);
                                    }
                                } catch (Exception ex) {
                                    failedItems.add(ce.itemId);
                                    plugin.getLogger().warning("[Web交易] 发放商品异常: " + ce.itemId + " → " + ex.getMessage());
                                }
                            }
                        }

                        // ★ 失败处理：有商品发放失败 → 不confirm交易（PHP会重试/退款），并通知玩家
                        if (!failedItems.isEmpty()) {
                            plugin.getLogger().warning("[Web交易] 购物车部分/全部发货失败 (" + failedItems.size() + "/" + entries.size() + "): " + String.join(", ", failedItems)
                                    + " — 交易 #" + txId + " 不确认，PHP端应触发退款");
                            player.sendMessage("§c[商城] §l以下商品发放失败，已自动发起退款：");
                            for (String fi : failedItems) {
                                player.sendMessage("§c  ✗ " + fi);
                            }
                            if (!okItems.isEmpty()) {
                                player.sendMessage("§a[商城] 以下商品已成功发放：");
                                for (String oi : okItems) {
                                    player.sendMessage("§a  ✓ " + oi);
                                }
                            }
                            // 不调用 confirmTransaction(txId)，让 PHP 端检测到未确认后退款
                            // 同时写一条 refund 标记到 web_transactions
                            try {
                                String failReason = "发货失败商品: " + String.join(", ", failedItems);
                                writeRefundTransaction(playerName, amount, txId, failReason, payMode);
                            } catch (Exception ex) {
                                plugin.getLogger().warning("[Web交易] 写入退款记录失败: " + ex.getMessage());
                            }
                            return; // ← 不 confirm，退出 try 块
                        }

                        if ("cash".equals(payMode)) {
                            player.sendMessage("§6[债券] §f余额不变: §e" + newBal);
                        } else {
                            player.sendMessage("§6[债券] §f余额: §e" + (newBal + amount) + " §7→ §a" + newBal);
                        }
                    } else {
                        for (CartEntry ce : entries) {
                            saveOfflineItem(playerName, ce.itemId, ce.amount);
                        }
                        plugin.getLogger().info("[Web交易] 玩家离线，购物车商品已保存为离线待发放");
                    }
                }
                confirmTransaction(txId);
            } else if (type.equals("recharge") || type.equals("pay_recharge")) {
                // 在线支付充值（债券在线充值平台）：增加债券
                int balBefore = plugin.getBondManager().getBonds(playerName);
                // ★ 冻结期豁免：PHP 后端发起的充值允许动账（addBondsAdmin 不查冻结）
                plugin.getBondManager().addBondsAdmin(playerName, amount, "pay_recharge", "", "支付平台", "在线充值");
                int balAfter = plugin.getBondManager().getBonds(playerName);
                plugin.getLogger().info("[Web交易] 玩家 " + playerName + " 在线充值，金额: " + amount + " 债券");
                // 通知在线玩家
                Player rechargePlayer = plugin.getServer().getPlayer(playerName);
                if (rechargePlayer != null && rechargePlayer.isOnline()) {
                    rechargePlayer.sendMessage("§6[充值] §f在线充值成功！§a+" + amount + " §f债券");
                    rechargePlayer.sendMessage("§6[债券] §f余额: §e" + balBefore + " §7→ §a" + balAfter);
                }
                txSuccess = true;
                confirmTransaction(txId);
                // ★ 2026-10-06 充值回执：处理成功即在 Java 侧落一份（一式两份的第二份）。
                //   运行中 PHP 的 web_transactions 被清空时，靠这份镜像补推回去。
                //   放在这里（confirm 之后）是因为只有确认过的流水才有资格按 processed
                //   补推 —— 补推成 pending 会让 Java 下轮又拉一次、重复加钱。
                try {
                    long txIdNum = Long.parseLong(txId.trim());
                    plugin.getDb().upsertWebTxReceipt(txIdNum, playerName, type, amount, detail,
                            System.currentTimeMillis() / 1000);
                } catch (Exception recEx) {
                    plugin.getLogger().warning("[充值回执] 落镜像失败 tx=" + txId + ": " + recEx.getMessage());
                }
            } else if (type.equals("admin_recharge") || type.equals("bond_recharge") || type.equals("admin_give")) {
                // 管理员充值：增加债券（充值不受冻结限制）
                int balBefore = plugin.getBondManager().getBonds(playerName);
                // ★ 冻结期豁免：PHP 后台充值允许动账（注释早就写了「不受冻结限制」，之前实际被拦）
                plugin.getBondManager().addBondsAdmin(playerName, amount, "web_recharge", "", "Web后台", "管理员充值");
                int balAfter = plugin.getBondManager().getBonds(playerName);
                plugin.getLogger().info("[Web交易] 玩家 " + playerName + " 管理员充值，金额: " + amount);
                // ★ 通知在线玩家
                Player rechargePlayer = plugin.getServer().getPlayer(playerName);
                if (rechargePlayer != null && rechargePlayer.isOnline()) {
                    rechargePlayer.sendMessage("§6[债券] §a管理员充值！§f +§a" + amount + "§f 债券");
                    rechargePlayer.sendMessage("§6[债券] §f余额: §e" + balBefore + " §7→ §a" + balAfter);
                }
                txSuccess = true;
                confirmTransaction(txId);
            } else if (type.equals("admin_deduct") || type.equals("admin_add")) {
                // 管理员扣减/增加债券（Web后台操作）
                // ★ 冻结期豁免：PHP 后端/管理员手动操作（含手动 remove）走 setBonds，本就不查冻结
                int currentBalance = plugin.getBondManager().getBonds(playerName);
                // ★ 从PHP的detail字段读取PHP计算的新余额（PHP和Java本地余额可能不同步）
                int newBalance = -1;
                try {
                    // detail格式: {"admin_action":"deduct","original":500,"new":400}
                    int newIdx = detail.indexOf("\"new\":");
                    if (newIdx >= 0) {
                        newIdx += 6; // skip "new":
                        int newEnd = detail.indexOf("}", newIdx);
                        if (newEnd < 0) newEnd = detail.indexOf(",", newIdx);
                        if (newEnd > newIdx) {
                            newBalance = Integer.parseInt(detail.substring(newIdx, newEnd).trim());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易] 解析detail失败: " + detail);
                }

                if (newBalance >= 0) {
                    // PHP已计算好新余额，直接设置
                    plugin.getBondManager().setBonds(playerName, newBalance);
                    plugin.getLogger().info("[Web交易] 玩家 " + playerName + " " + (type.equals("admin_deduct") ? "管理员扣减" : "管理员增加") + "，PHP新余额: " + newBalance);
                } else {
                    // fallback：用Java本地余额计算
                    newBalance = type.equals("admin_deduct") ? currentBalance - Math.abs(amount) : currentBalance + amount;
                    if (newBalance < 0) newBalance = 0;
                    plugin.getBondManager().setBonds(playerName, newBalance);
                    plugin.getLogger().info("[Web交易] 玩家 " + playerName + " " + (type.equals("admin_deduct") ? "管理员扣减" : "管理员增加") + "，Java计算新余额: " + newBalance);
                }

                // ★ 通知在线玩家动账结果
                Player targetPlayer = plugin.getServer().getPlayer(playerName);
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    String actionText = type.equals("admin_deduct") ? "§c扣减" : "§a增加";
                    String changeText = type.equals("admin_deduct") ? "-" + Math.abs(amount) : "+" + amount;
                    targetPlayer.sendMessage("§6[债券] §f" + actionText + " §7" + Math.abs(amount) + "§f 债券（Web管理操作）");
                    targetPlayer.sendMessage("§6[债券] §f余额: §e" + currentBalance + " §7→ §a" + newBalance);
                }

                txSuccess = true;
                confirmTransaction(txId);
            } else if (type.equals("cdk_redeem")) {
                // CDK兑换：增加债券（本地CDK由PHP标记used，远程CDK由sdf1标记）
                int balanceBefore = plugin.getBondManager().getBonds(playerName);
                // ★ 冻结期豁免：CDK 是 PHP 后端交易（入账类），放行避免吞码假成功
                plugin.getBondManager().addBondsAdmin(playerName, amount, "web_cdk", "", "Web商城", "CDK兑换");
                int balanceAfter = plugin.getBondManager().getBonds(playerName);
                plugin.getLogger().info("[Web交易] 玩家 " + playerName + " CDK兑换，金额: " + amount);

                // 通知在线玩家
                Player cdkPlayer = plugin.getServer().getPlayer(playerName);
                if (cdkPlayer != null && cdkPlayer.isOnline()) {
                    cdkPlayer.sendMessage("§6[债券] §aCDK兑换成功！§f +§a" + amount + "§f 债券");
                    cdkPlayer.sendMessage("§6[债券] §f余额: §e" + balanceBefore + " §7→ §a" + balanceAfter);
                }

                txSuccess = true;
                confirmTransaction(txId);
            } else {
                plugin.getLogger().warning("[Web交易] 未知交易类型: " + type + "，跳过");
                confirmTransaction(txId);
            }
        } catch (Exception e) {
            errorMsg = e.getMessage();
            plugin.getLogger().warning("[Web交易] 处理交易失败: " + e.getMessage());
            e.printStackTrace();
            // ★ 失败不confirm，交由重试机制处理
        } finally {
            if (txSuccess) {
                plugin.getLogger().info("[Web交易] 交易 #" + txId + " 处理成功");
            } else if (!errorMsg.isEmpty()) {
                plugin.getLogger().warning("[Web交易] 交易 #" + txId + " 处理异常: " + errorMsg + "，将在下一轮定时同步中重试");
            }
        }
    }

    /**
     * 发放商品给在线玩家
     */
    private void dispatchItem(Player player, String itemId, int amount) {
        try {
            plugin.getLogger().info("[Web交易] 发放商品: " + itemId + " x" + amount + " 给 " + player.getName());

            // 使用ShopManager获取商品并发放
            if (plugin.getShopManager() != null) {
                ShopItem shopItem = plugin.getShopManager().findItemById(itemId);
                if (shopItem != null) {
                    ItemStack itemStack = plugin.getShopManager().getShopStack(shopItem, amount);
                    if (itemStack == null) {
                        plugin.getLogger().warning("[Web交易] 无法创建商品堆叠: " + itemId);
                        player.sendMessage("§c[Web商城] §f无法发放商品: " + itemId);
                        return;
                    }
                    HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(itemStack);
                    if (!leftover.isEmpty()) {
                        // 背包满了，掉落到地上
                        for (ItemStack drop : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }
                    player.sendMessage("§a[Web商城] §f成功购买商品: " + shopItem.getDisplayName() + " x" + amount);
                    plugin.getLogger().info("[Web交易] 商品发放成功");
                } else {
                    plugin.getLogger().warning("[Web交易] 商品不存在: " + itemId);
                    player.sendMessage("§c[Web商城] §f商品不存在: " + itemId);
                }
            } else {
                plugin.getLogger().warning("[Web交易] ShopManager未初始化");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 发放商品失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 通过Material名称或商品ID发放商品（兼容PHP传Material名称的情况）
     */
    private boolean dispatchItemByMaterialOrId(Player player, String itemId, int amount) {
        return dispatchItemByMaterialOrId(player, itemId, amount, null);
    }

    /**
     * 发放单个商品（支持按购物车携带的真实显示名重建附魔书等带NBT物品）。
     * name 为 PHP 购物车携带的真实显示名（含附魔关键词），用于修正材质匹配取到裸附魔书的问题。
     */
    private boolean dispatchItemByMaterialOrId(Player player, String itemId, int amount, String name) {
        try {
            plugin.getLogger().info("[Web交易] 发放商品(材料匹配): " + itemId + " x" + amount + " 给 " + player.getName());

            if (plugin.getShopManager() != null) {
                org.bukkit.inventory.ItemStack itemStack = null;

                // 方式1: 按Material名称查找
                try {
                    org.bukkit.Material material = org.bukkit.Material.getMaterial(itemId.toUpperCase());
                    if (material != null) {
                        // 遍历所有商品，找第一个匹配的
                     /*   for (org.bukkit.inventory.ItemStack drop : java.util.Collections.emptySet()) {
                            // 跳过空循环，仅用于导入
                        }*/
                        for (Sdf1_login.ShopCategory cat : plugin.getShopManager().getCategories()) {
                            for (Sdf1_login.ShopItem item : cat.getItems()) {
                                if (item.getMaterial() == material) {
                                    itemStack = plugin.getShopManager().getShopStack(item, amount);
                                    break;
                                }
                            }
                            if (itemStack != null) break;
                        }
                        if (itemStack != null) {
                            plugin.getLogger().info("[Web交易] 通过Material找到商品并创建堆栈成功");
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易] Material查找失败: " + e.getMessage());
                }

                // 方式2: 按商品ID查找（如果Material查找失败）
                if (itemStack == null) {
                    Sdf1_login.ShopItem shopItem = plugin.getShopManager().findItemById(itemId);
                    if (shopItem != null) {
                        itemStack = plugin.getShopManager().getShopStack(shopItem, amount);
                    }
                }

                // ★ 方式3 附魔书兜底：前两路均失败但传入名称含附魔关键词时，
                //   直接按名称推断构建 EnchantedBook（覆盖 .md material 写错导致商品未加载的场景）
                if (itemStack == null && name != null && !name.isEmpty()
                        && (name.contains("修补") || name.contains("锋利") || name.contains("保护")
                            || name.contains("射击") || name.contains("火焰")
                            || name.contains("击退") || name.contains("时运")
                            || name.contains("耐久") || name.contains("深海")
                            || name.contains("穿刺") || name.contains("弩道")
                            || name.contains("快速") || name.contains("穿透")
                            || name.contains("忠诚") || name.contains("引雷")
                            || name.contains("激流") || name.contains("通道")
                            || itemId.matches("(?i).*_(I{1,3}|II|III|IV|V|[1-5])$"))) {
                    itemStack = plugin.getShopManager().getShopStackByName(name, org.bukkit.Material.ENCHANTED_BOOK, amount);
                    if (itemStack != null) {
                        plugin.getLogger().info("[Web交易] 通过附魔书名称兜底推断成功: " + name);
                    }
                }

                // ★ 附魔书NBT修复：购物车携带真实显示名时，优先按名称重建附魔书，
                //   确保 enchantments NBT 正确。仅当按名称解析出带存储附魔的书时才覆盖。
                if (itemStack != null
                        && itemStack.getType() == org.bukkit.Material.ENCHANTED_BOOK
                        && name != null && !name.isEmpty()) {
                    org.bukkit.inventory.ItemStack fromName = plugin.getShopManager().getShopStackByName(name, org.bukkit.Material.ENCHANTED_BOOK, amount);
                    if (fromName != null
                            && fromName.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta fesm
                            && !fesm.getStoredEnchants().isEmpty()) {
                        itemStack = fromName;
                    }
                }

                // ★ 附魔书空壳兜底（ID权威重建）：若上面任一路径给出无附魔的附魔书
                //   （显示名解析失败、ENCHANT_MAP 为空等），用商品ID（MENDING_I 等）重建NBT。
                //   若重建也失败，ensureEnchantedBookNbt 返回 null —— 不发放空壳，返回 false 触发整单取消/退款。
                itemStack = plugin.getShopManager().ensureEnchantedBookNbt(itemStack, itemId, amount);

                if (itemStack != null) {
                    java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> leftover = player.getInventory().addItem(itemStack);
                    if (!leftover.isEmpty()) {
                        for (org.bukkit.inventory.ItemStack drop : leftover.values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), drop);
                        }
                    }
                    // 从Stack中获取displayName
                    String displayName = itemId;
                    if (itemStack.hasItemMeta() && itemStack.getItemMeta() != null) {
                        if (itemStack.getItemMeta().hasDisplayName()) {
                            displayName = itemStack.getItemMeta().getDisplayName();
                        }
                    }
                    player.sendMessage("§a[Web商城] §f成功购买商品: " + displayName + " x" + amount);
                    plugin.getLogger().info("[Web交易] 商品发放成功");
                    return true;
                } else {
                    // ★ 发放失败：附魔书解析失败或商品不存在 → 返回 false，由上层整单取消/退款
                    plugin.getLogger().warning("[Web交易] 商品无法发放(附魔书解析失败或商品不存在): " + itemId);
                    player.sendMessage("§c[Web商城] §f商品发放失败(解析失败): " + itemId);
                    return false;
                }
            } else {
                plugin.getLogger().warning("[Web交易] ShopManager未初始化");
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 发放商品失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 保存离线商品到数据库（待玩家上线后发放）
     */
    private void saveOfflineItem(String playerName, String itemId, int amount) {
        try {
            plugin.getLogger().info("[Web交易] 保存离线商品: " + itemId + " x" + amount + " 给 " + playerName);

            // 直接保存到数据库
            saveItemToDatabase(playerName, itemId, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 保存离线商品失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 保存商品到数据库（备用方案）
     */
    private void saveItemToDatabase(String playerName, String itemId, int amount) {
        String sql = "INSERT INTO pending_items (player_name, item_id, amount, created_at) VALUES (?, ?, ?, ?)";
        try {
            java.sql.PreparedStatement ps = plugin.getDb().getDb().prepareStatement(sql);
            ps.setString(1, playerName);
            ps.setString(2, itemId);
            ps.setInt(3, amount);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
            plugin.getLogger().info("[Web交易] 商品已保存到待发放列表，等待玩家上线领取");
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 保存商品到数据库失败: " + e.getMessage());
            plugin.getLogger().warning("[Web交易] 商品发放失败，玩家: " + playerName + ", 商品: " + itemId + " x" + amount);
        }
    }

    /**
     * 购物车条目（内部数据载体）
     */
    private static class CartEntry {
        final String itemId;
        final int amount;
        final String name;
        CartEntry(String itemId, int amount) {
            this(itemId, amount, null);
        }
        CartEntry(String itemId, int amount, String name) {
            this.itemId = itemId;
            this.amount = amount;
            this.name = name;
        }
    }

    /**
     * 通过商品ID或Material名称构建商品ItemStack（不立即发放）
     * name 为 PHP 购物车携带的真实显示名（含附魔关键词），用于附魔书等带NBT物品按名称重建
     */
    private ItemStack buildShopStack(String itemId, int amount, String name) {
        if (plugin.getShopManager() == null) return null;
        ItemStack itemStack = null;
        try {
            Material material = Material.getMaterial(itemId.toUpperCase());
            if (material != null) {
                for (Sdf1_login.ShopCategory cat : plugin.getShopManager().getCategories()) {
                    for (Sdf1_login.ShopItem item : cat.getItems()) {
                        if (item.getMaterial() == material) {
                            itemStack = plugin.getShopManager().getShopStack(item, amount);
                            break;
                        }
                    }
                    if (itemStack != null) break;
                }
            }
        } catch (Exception ignored) {}
        if (itemStack == null) {
            Sdf1_login.ShopItem shopItem = plugin.getShopManager().findItemById(itemId);
            if (shopItem != null) itemStack = plugin.getShopManager().getShopStack(shopItem, amount);
        }
        // ★ 附魔书NBT修复：购物车携带真实显示名时，优先按名称重建附魔书，
        //   确保 enchantments NBT 正确（避免按材质/ID 解析取到错误或无附魔的附魔书）。
        //   仅当按名称成功解析出带存储附魔的附魔书时才覆盖，否则保留原结果。
        if (itemStack != null
                && itemStack.getType() == Material.ENCHANTED_BOOK
                && name != null && !name.isEmpty()) {
            ItemStack fromName = plugin.getShopManager().getShopStackByName(name, Material.ENCHANTED_BOOK, amount);
            if (fromName != null
                    && fromName.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta fesm
                    && !fesm.getStoredEnchants().isEmpty()) {
                itemStack = fromName;
            }
        }
        // ★ 附魔书空壳兜底（ID权威重建）
        itemStack = plugin.getShopManager().ensureEnchantedBookNbt(itemStack, itemId, amount);
        return itemStack;
    }

    /**
     * 潜影盒颜色名称 → Material 映射
     */
    private Material mapShulkerColor(String colorName) {
        switch (colorName != null ? colorName.toLowerCase() : "default") {
            case "white":  return Material.WHITE_SHULKER_BOX;
            case "black":  return Material.BLACK_SHULKER_BOX;
            case "red":    return Material.RED_SHULKER_BOX;
            case "blue":   return Material.BLUE_SHULKER_BOX;
            case "green":  return Material.GREEN_SHULKER_BOX;
            case "yellow": return Material.YELLOW_SHULKER_BOX;
            case "orange": return Material.ORANGE_SHULKER_BOX;
            case "purple":
            default:      return Material.SHULKER_BOX; // 默认原色（免费潜影盒）
        }
    }

    /**
     * 将多个商品堆叠分装进潜影盒（每个盒最多27格，溢出则追加新盒）
     */
    private java.util.List<ItemStack> packCartIntoShulkers(java.util.List<ItemStack> all, Material color) {
        java.util.List<ItemStack> result = new java.util.ArrayList<>();
        java.util.List<ItemStack> remaining = new java.util.ArrayList<>(all);
        while (!remaining.isEmpty()) {
            ItemStack shulker = new ItemStack(color);
            BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
            if (meta == null) { result.addAll(remaining); break; }
            BlockState state = meta.getBlockState();
            if (!(state instanceof Container)) { result.addAll(remaining); break; }
            Container c = (Container) state;
            java.util.List<ItemStack> next = new java.util.ArrayList<>();
            for (ItemStack stack : remaining) {
                java.util.HashMap<Integer, ItemStack> left = c.getInventory().addItem(stack);
                for (ItemStack l : left.values()) next.add(l);
            }
            remaining = next;
            meta.setBlockState(c);
            shulker.setItemMeta(meta);
            result.add(shulker);
        }
        return result;
    }

    /**
     * ★ 2026-07-14 新增：拉取即贴标 —— 立即标记交易已读到PHP
     * 在处理交易之前调用，确保即使后续处理失败/服务器重启，PHP也不会重新下发该交易
     * 双重保险：confirmedTxIds(内存) + confirmed_transactions(PHP DB) + web_transactions.status=processed
     */
    private void ackTransaction(String txId, String txType) {
        try {
            // ★ 不加confirmedTxIds！只写PHP confirmed_transactions表
            // confirmedTxIds仅在confirmTransaction()处理完后才写入，防止拉取→ack→处理时被自己跳过
            String bodyJson = "{\"tx_id\":\"" + txId + "\",\"tx_type\":\"" + (txType != null ? txType : "shop_cart") + "\"}";
            String postUrl = webBaseUrl + "/api/sync.php?action=ack_transaction&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            doPost(postUrl, bodyJson);
            plugin.getLogger().info("[Web交易] 已贴标ack交易: ID=" + txId + ", type=" + txType);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 贴标ack交易 " + txId + " 失败: " + e.getMessage());
        }
    }

    /**
     * 确认交易已处理。
     *
     * <p>★ 2026-10-03 主线程零HTTP改造：本地去重标记仍然同步写入，
     * 但上报 PHP 的 doPost（HttpClient.send，10 秒超时）改丢异步线程。
     * 原实现被 processWebTransaction 调用，而后者是被
     * Bukkit.getScheduler().runTask 派回主线程执行的 —— 后端一慢，
     * 主线程就被 cfHttpClient.send 卡满 10 秒，触发
     * "The server has not responded for 10 seconds" 线程转储（线上报错根因）。
     * 重启防重不受影响：confirmedTxIds 同步写入 + 拉取阶段 ackTransaction 已先行落库。
     */
    private void confirmTransaction(String txId) {
        try {
            confirmedTxIds.add(txId); // ★ 先标记本地已确认（即使HTTP失败也不会重复处理）
            String bodyJson = "{\"tx_id\":\"" + txId + "\"}";
            String postUrl = webBaseUrl + "/api/sync.php?action=confirm_transaction&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            postAsyncFireAndForget("[Web交易] 确认交易 " + txId, postUrl, bodyJson);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 确认交易 " + txId + " 失败（已标记本地已确认，不会重复处理）: " + e.getMessage());
        }
    }

    /**
     * 异步 POST 上报（fire-and-forget）：结果只用于记日志的场合，
     * 绝不在主线程同步等待 HTTP。
     */
    private void postAsyncFireAndForget(final String tag, final String url, final String body) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    doPost(url, body);
                } catch (Exception e) {
                    plugin.getLogger().warning(tag + " 异步上报失败: " + e.getMessage());
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning(tag + " 无法投递异步上报: " + t.getMessage());
        }
    }

    /**
     * 写入退款记录到 PHP 端（通过 sync.php），让 PHP 自动退款+恢复库存。
     * 用于购物车发货部分/全部失败时的整单退款。
     */
    private void writeRefundTransaction(final String playerName, final int amount, final String origTxId,
                                        final String reason, final String payMode) {
        try {
            final String bodyJson = "{\"player_name\":\"" + playerName
                    + "\",\"amount\":" + amount
                    + ",\"orig_tx_id\":\"" + origTxId
                    + "\",\"reason\":\"" + reason.replace("\"", "'")
                    + "\",\"pay_mode\":\"" + (payMode != null ? payMode : "bond") + "\"}";
            final String postUrl = webBaseUrl + "/api/sync.php?action=write_shop_refund&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            // ★ 主线程零HTTP：退款上报同样丢异步（结果仅记日志，不影响发货流程）
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String resp = doPost(postUrl, bodyJson);
                    plugin.getLogger().info("[Web交易] 已写入退款记录: 玩家=" + playerName + " 金额=" + amount + " 原交易#" + origTxId + " 响应=" + resp);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Web交易] 写入退款记录失败: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 写入退款记录失败: " + e.getMessage());
        }
    }

    /**
     * 拉取Web端修改的商品库存
     */
    private void pullShopStock() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=pull_shop_stock&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null || !json.contains("\"success\":true")) return;

                    // 解析商品库存列表
                    int dataStart = json.indexOf("\"items\":");
                    if (dataStart < 0) return;
                    String dataStr = json.substring(dataStart + 8);
                    int arrEnd = findMatchingBracket(dataStr, 0);
                    if (arrEnd < 0) return;
                    String arrStr = dataStr.substring(0, arrEnd + 1);

                    // ★ 更新本地商品库存，返回是否有改动
                    boolean changed = updateLocalShopStock(arrStr);

                    // ★ 如果有改动，清除PHP端的admin_stock标记（防止重复同步）
                    if (changed) {
                        clearAdminStock();
                    }

                } catch (Exception e) {
                    // 静默处理
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 清除PHP端的管理员库存标记
     */
    private void clearAdminStock() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=clear_admin_stock&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            doGet(urlStr);
        } catch (Exception e) {
            // 静默处理
        }
    }

    /**
     * 拉取PHP端修改的商品价格
     * 类似pullShopStock，但针对admin_buy_price/admin_sell_price
     */
    private void pullShopPrices() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=pull_shop_prices&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null || !json.contains("\"success\":true")) return;

                    // 解析商品价格列表
                    int dataStart = json.indexOf("\"items\":");
                    if (dataStart < 0) return;
                    String dataStr = json.substring(dataStart + 8);
                    int arrEnd = findMatchingBracket(dataStr, 0);
                    if (arrEnd < 0) return;
                    String arrStr = dataStr.substring(0, arrEnd + 1);

                    // ★ 解析JSON数组为Map列表
                    List<Map<String, Object>> priceUpdates = new ArrayList<>();
                    // 简单JSON解析：遍历每个对象
                    int idx = 0;
                    while (true) {
                        int objStart = arrStr.indexOf("{", idx);
                        if (objStart < 0) break;
                        int objEnd = findMatchingBracket(arrStr, objStart);
                        if (objEnd < 0) break;
                        String objStr = arrStr.substring(objStart, objEnd + 1);
                        Map<String, Object> itemData = parseJsonObject(objStr);
                        if (itemData != null && itemData.containsKey("id")) {
                            priceUpdates.add(itemData);
                        }
                        idx = objEnd + 1;
                    }

                    if (priceUpdates.isEmpty()) return;

                    // ★ 调用ShopManager批量更新价格
                    ShopManager shopManager = plugin.getShopManager();
                    if (shopManager == null) {
                        plugin.getLogger().warning("[价格同步] ShopManager未初始化");
                        return;
                    }
                    int updated = shopManager.updateItemPrices(priceUpdates);
                    if (updated > 0) {
                        plugin.getLogger().info("[价格同步] 应用PHP价格改动: " + updated + "个商品");
                    }

                } catch (Exception e) {
                    plugin.getLogger().warning("[价格同步] 拉取价格异常: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 拉取商店打包配置（打包费 / 环保单折扣率）并写入 ConfigManager
     * 配置以 PHP shop_config 表为准，Java 命令 set packmoney / shop setgreen 也会回写该表
     */
    private void pullShopConfig() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=get_shop_config&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null || !json.contains("\"success\":true")) return;
                    int dataIdx = json.indexOf("\"data\":");
                    if (dataIdx < 0) return;
                    String sub = json.substring(dataIdx + 7);
                    int objStart = sub.indexOf("{");
                    if (objStart < 0) return;
                    int objEnd = findMatchingBracket(sub, objStart);
                    if (objEnd < 0) return;
                    Map<String, Object> m = parseJsonObject(sub.substring(objStart, objEnd + 1));
                    if (m.containsKey("packmoney")) {
                        plugin.getConfigMgr().packingFee = ((Number) m.get("packmoney")).doubleValue();
                    }
                    if (m.containsKey("green_discount")) {
                        plugin.getConfigMgr().greenDiscount = ((Number) m.get("green_discount")).doubleValue();
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[配置同步] 拉取商店打包配置异常: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 给在线玩家发送一本"打包小票"书本（Written Book）
     *
     * 书本内容包含完整的订单信息，玩家可在游戏中随时翻阅。
     * 使用 § 颜色代码实现热敏小票风格。
     */
    private void giveReceiptBook(org.bukkit.entity.Player player,
            String orderNo, String orderTime, String orderPlayer, String operatorName,
            String settlementMode, String payMethod, int totalPrice, String itemsText) {
        try {
            org.bukkit.inventory.ItemStack book = new org.bukkit.inventory.ItemStack(Material.WRITTEN_BOOK);
            org.bukkit.inventory.meta.BookMeta meta = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
            if (meta == null) return;

            meta.setTitle("§6§lSDF1 打包小票");
            meta.setAuthor("SDF1 商城");

            // 构建小票内容（每行一个页面元素，BookMeta 支持多行）
            java.util.List<String> pages = new java.util.ArrayList<>();

            // 第1页：抬头 + 订单信息
            StringBuilder page1 = new StringBuilder();
            page1.append("§6§l===== SDF1 商城 =====\n\n");
            page1.append("§7打包小票 / PACKING RECEIPT\n\n");
            // ★ 改用 §0（黑色）文字：书本背景为米色羊皮纸，白色(§f)文字几乎不可见
            page1.append("§0订单号: §e").append(orderNo.isEmpty() ? "—" : orderNo).append("\n");
            page1.append("§0时间:   §7").append(orderTime.isEmpty() ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) : orderTime).append("\n");
            page1.append("§0玩家:   §b").append(orderPlayer).append("\n");
            page1.append("§0操作员: §d").append(operatorName.isEmpty() ? "系统" : operatorName).append("\n");
            page1.append("§0结算:   §a").append(settlementMode.isEmpty() ? "塞背包" : settlementMode).append("\n");
            page1.append("§0收款:   ").append("cash".equals(payMethod) ? "§6现金(记账)" : "§e债券扣款").append("\n");
            pages.add(page1.toString());

            // 第2页：商品明细
            StringBuilder page2 = new StringBuilder();
            page2.append("§6§l----- 商品明细 -----\n\n");
            if (!itemsText.isEmpty()) {
                String[] lines = itemsText.split("\n");
                int lineOnPage = 0;
                StringBuilder currentPage = page2;
                for (String line : lines) {
                    if (lineOnPage >= 12) { // 每页约12行
                        pages.add(currentPage.toString());
                        currentPage = new StringBuilder();
                        lineOnPage = 0;
                    }
                    currentPage.append("§0").append(line).append("\n");
                    lineOnPage++;
                }
                if (currentPage.length() > 30) pages.add(currentPage.toString());
            } else {
                page2.append("§7（无商品明细）\n");
                pages.add(page2.toString());
            }

            // 最后一页：合计 + 底部
            StringBuilder lastPage = new StringBuilder();
            lastPage.append("§6§l--------------------\n\n");
            lastPage.append("§0§l实收合计: §a§l").append(totalPrice).append(" §7债券\n\n");
            lastPage.append("§8感谢惠顾 · 请妥善保管小票\n");
            lastPage.append("§7SDF1 商城自动生成");
            pages.add(lastPage.toString());

            meta.setPages(pages);
            book.setItemMeta(meta);

            // ★ 将小票书放入潜影盒（避免直接进背包被误丢/难找），潜影盒命名便于识别
            org.bukkit.inventory.ItemStack shulker = new org.bukkit.inventory.ItemStack(Material.SHULKER_BOX);
            org.bukkit.inventory.meta.BlockStateMeta shulkerMeta =
                    (org.bukkit.inventory.meta.BlockStateMeta) shulker.getItemMeta();
            if (shulkerMeta != null) {
                org.bukkit.block.ShulkerBox shulkerInv = (org.bukkit.block.ShulkerBox) shulkerMeta.getBlockState();
                shulkerInv.getInventory().setItem(0, book);
                shulkerMeta.setBlockState(shulkerInv);
                shulkerMeta.setDisplayName("§6§lSDF1 打包小票盒");
                shulkerMeta.setLore(java.util.Arrays.asList(
                        "§7内含打包小票书 · 右键打开查看",
                        "§7订单号: " + (orderNo.isEmpty() ? "—" : orderNo)
                ));
                shulker.setItemMeta(shulkerMeta);
            }

            // 给玩家潜影盒（放到背包第一个空格，或掉落）
            player.getInventory().addItem(shulker);
            player.sendMessage("§a§l[商城] §f你收到了一个 §6§l打包小票盒§f（内含小票书），请查收！");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);

            plugin.getLogger().info("[小票书] 已给玩家 " + player.getName() + " 发送小票盒(订单:" + orderNo + ")");
        } catch (Exception e) {
            plugin.getLogger().warning("[小票书] 发送小票书失败: " + e.getMessage());
        }
    }

    /**
     * 推送商店配置到 PHP（Java命令 set packmoney / shop setgreen 调用）
     * 直接写入 PHP shop_config 表（secret 认证），随后由 pullShopConfig 定时器刷新本地缓存
     */
    public void pushShopConfig(String key, String value) {
        // ★ 调用点在命令处理（主线程）：doGet 有 10s 超时，同步执行会卡死 tick
        //   沿用项目惯例走 submitWebTask（webExecutor），熔断打开时静默跳过
        final String fKey = key;
        final String fValue = value;
        submitWebTask("pushShopConfig-" + fKey, () -> {
            try {
                String urlStr = webBaseUrl + "/api/sync.php?action=set_shop_config&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8")
                        + "&key=" + java.net.URLEncoder.encode(fKey, "UTF-8")
                        + "&value=" + java.net.URLEncoder.encode(fValue, "UTF-8");
                doGet(urlStr);
            } catch (Exception e) {
                plugin.getLogger().warning("[配置推送] 保存商店配置失败: " + e.getMessage());
            }
        });
    }

    /** 上次成功推送的商品目录指纹（内容没变就不重复整包推） */
    private volatile String lastShopCatalogHash;

    /**
     * 推送游戏内完整商品目录到 PHP（商城定时同步：游戏内增删分类/商品 → Web 端镜像）
     * 安全护栏：目录为空时不推送，避免误清空 PHP 端商品表。
     */
    public void pushShopCatalog() {
        try {
            ShopManager sm = plugin.getShopManager();
            if (sm == null || sm.getCategories().isEmpty()) {
                plugin.getLogger().info("[商品同步] 游戏内目录为空，跳过推送（防止误清空PHP）");
                return;
            }
            String json = sm.buildCatalogJson();
            // ★ 无变化静默：目录只由 Java 写（PHP 的 set_shop_catalog 只 upsert 目录字段，
            //   库存/管理员价走独立列），内容没变就别每轮整包硬推 —— 这是「无脑推」大户之一。
            String catalogHash = json.length() + ":" + json.hashCode();
            if (catalogHash.equals(lastShopCatalogHash)) return;
            String urlStr = webBaseUrl + "/api/sync.php?action=set_shop_catalog&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doPost(urlStr, json);
            plugin.getLogger().info("[商品同步] 已推送游戏内商品目录到PHP"
                    + (resp != null ? " 响应:" + resp : "（无响应）"));
            // ★ PHP 明确 success 才提交指纹（防假成功，同 syncUserRegistrations 的规矩）
            if (resp != null && resp.contains("\"success\":true")) {
                lastShopCatalogHash = catalogHash;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[商品同步] 推送商品目录失败: " + e.getMessage());
        }
    }

    /**
     * 清除PHP端的管理员价格标记
     */
    private void clearAdminPrices() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=clear_admin_prices&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            doGet(urlStr);
        } catch (Exception e) {
            // 静默处理
        }
    }

    /**
     * 简单的JSON对象解析（提取键值对）
     * 支持字符串、数字、布尔值、null
     */
    private Map<String, Object> parseJsonObject(String json) {
        Map<String, Object> map = new HashMap<>();
        try {
            // 去除首尾空白
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) return null;
            json = json.substring(1, json.length() - 1).trim();
            if (json.isEmpty()) return map;

            int idx = 0;
            while (idx < json.length()) {
                // 跳过空白
                while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
                if (idx >= json.length()) break;

                // 解析键
                if (json.charAt(idx) != '"') break;
                int keyStart = idx + 1;
                int keyEnd = json.indexOf('"', keyStart);
                if (keyEnd < 0) break;
                String key = json.substring(keyStart, keyEnd);
                idx = keyEnd + 1;

                // 跳过冒号
                while (idx < json.length() && (json.charAt(idx) == ':' || Character.isWhitespace(json.charAt(idx)))) idx++;
                if (idx >= json.length()) break;

                // 解析值
                char firstChar = json.charAt(idx);
                Object value = null;
                if (firstChar == '"') {
                    // 字符串值
                    int valStart = idx + 1;
                    int valEnd = valStart;
                    while (valEnd < json.length()) {
                        if (json.charAt(valEnd) == '\\') {
                            valEnd += 2;
                            continue;
                        }
                        if (json.charAt(valEnd) == '"') break;
                        valEnd++;
                    }
                    value = json.substring(valStart, valEnd);
                    idx = valEnd + 1;
                } else if (firstChar == 't' || firstChar == 'f') {
                    // 布尔值
                    if (json.startsWith("true", idx)) {
                        value = true;
                        idx += 4;
                    } else if (json.startsWith("false", idx)) {
                        value = false;
                        idx += 5;
                    }
                } else if (firstChar == 'n') {
                    // null
                    if (json.startsWith("null", idx)) {
                        value = null;
                        idx += 4;
                    }
                } else if (firstChar == '-' || Character.isDigit(firstChar)) {
                    // 数字
                    int numStart = idx;
                    while (idx < json.length() && (json.charAt(idx) == '-' || json.charAt(idx) == '.' || Character.isDigit(json.charAt(idx))) ) idx++;
                    String numStr = json.substring(numStart, idx);
                    try {
                        if (numStr.contains(".")) {
                            value = Double.parseDouble(numStr);
                        } else {
                            value = Long.parseLong(numStr);
                        }
                    } catch (NumberFormatException e) {
                        // 忽略
                    }
                } else {
                    // 未知类型，跳过到下一个逗号或结束
                    while (idx < json.length() && json.charAt(idx) != ',' && json.charAt(idx) != '}') idx++;
                }

                map.put(key, value);

                // 跳过逗号
                while (idx < json.length() && (json.charAt(idx) == ',' || Character.isWhitespace(json.charAt(idx)))) idx++;
            }
        } catch (Exception e) {
            // 解析失败，返回部分结果
        }
        return map;
    }

    /**
     * 更新本地商品库存
     * @return 是否有任何库存被更新
     */
    private boolean updateLocalShopStock(String itemsJson) {
        try {
            File shopDir = new File(plugin.getDataFolder(), "shop");
            if (!shopDir.exists()) return false;

            File[] mdFiles = shopDir.listFiles((d, n) -> n.endsWith(".md"));
            if (mdFiles == null) return false;

            // 解析Web端返回的库存数据
            // 格式: [{"id":"ITEM_ID","stock":100,"last_sync":...,"admin_stock_override":true}, ...]
            Map<String, Integer> webStock = new HashMap<>();
            // ★ 跟踪哪些商品是管理员手动修改的（admin_stock_override=true）
            Set<String> adminOverride = new HashSet<>();
            int idx = 0;
            while (true) {
                int idStart = itemsJson.indexOf("\"id\":\"", idx);
                if (idStart < 0) break;
                idStart += 6;
                int idEnd = itemsJson.indexOf("\"", idStart);
                if (idEnd < 0) break;
                String itemId = itemsJson.substring(idStart, idEnd);

                int stockStart = itemsJson.indexOf("\"stock\":", idEnd);
                if (stockStart < 0) break;
                stockStart += 8;
                int stockEnd = itemsJson.indexOf(",", stockStart);
                if (stockEnd < 0) stockEnd = itemsJson.indexOf("}", stockStart);
                if (stockEnd < 0) break;
                String stockStr = itemsJson.substring(stockStart, stockEnd).trim();
                stockStr = stockStr.replace("\"", "");

                try {
                    int stock = Integer.parseInt(stockStr);
                    webStock.put(itemId, stock);
                } catch (NumberFormatException e) {
                    // 忽略无效值
                }

                // ★ 检查admin_stock_override标记
                int overrideCheck = itemsJson.indexOf("\"admin_stock_override\":", idEnd);
                if (overrideCheck > idEnd && overrideCheck < stockEnd + 20) {
                    adminOverride.add(itemId);
                }

                idx = stockEnd + 1;
            }

            if (webStock.isEmpty()) return false;

            // 更新每个md文件中的库存
            int updatedCount = 0;
            for (File mdFile : mdFiles) {
                List<String> lines = new ArrayList<>();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(mdFile), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) lines.add(line);
                reader.close();

                boolean modified = false;
                for (int i = 0; i < lines.size(); i++) {
                    String l = lines.get(i).trim();
                    if (!l.startsWith("|") || l.contains("---")) continue;

                    String[] cols = l.split("\\|");
                    if (cols.length < 7) continue;

                    String itemId = cols[1].trim();
                    if (!webStock.containsKey(itemId)) continue;

                    int newStock = webStock.get(itemId);
                    String oldStock = cols[6].trim();
                    String newStockStr = String.valueOf(newStock);
                    int oldStockNum;
                    try {
                        oldStockNum = Integer.parseInt(oldStock);
                    } catch (NumberFormatException e) {
                        oldStockNum = ("无限".equals(oldStock) || "∞".equals(oldStock)) ? -1 : 0;
                    }

                    // ★ 管理员手动修改的库存：跳过保护逻辑，直接应用
                    // 管理员设stock=0表示"售罄"，需要同步到游戏
                    if (!adminOverride.contains(itemId)) {
                        // stock<-1 为未定义值（-2以下），不生效，跳过同步
                        // -1=无限库存, 0=售罄, >=1=有库存，都正常同步
                        if (newStock < -1) continue;
                    }

                    if (oldStockNum != newStock) {
                        cols[6] = " " + newStockStr + " ";
                        lines.set(i, String.join("|", cols));
                        modified = true;
                        updatedCount++;
                    }
                }

                if (modified) {
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(new FileOutputStream(mdFile), StandardCharsets.UTF_8));
                    for (String l : lines) {
                        writer.write(l);
                        writer.newLine();
                    }
                    writer.close();
                }
            }

            // 重新加载商店分类
            if (updatedCount > 0) {
                plugin.getShopManager().loadCategories();

                long now = System.currentTimeMillis();
                if (now - lastPullShopStockLog > LOG_INTERVAL) {
                    plugin.getLogger().info("[Web通信] 已同步Web端商品库存，更新了 " + updatedCount + " 个商品");
                    lastPullShopStockLog = now;
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 更新商品库存失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 检查同步通知文件
     */
    private void checkSyncNotify() {
        try {
            File notifyFile = new File(plugin.getDataFolder(), "../sync_notify.txt");
            if (notifyFile.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(notifyFile.toPath())).trim();
                // ★ 兼容两种格式：纯数字时间戳 或 JSON {"time":xxx,"tx_id":xxx}
                long notifyTime = 0;
                if (content.startsWith("{")) {
                    // JSON格式
                    int timeIdx = content.indexOf("\"time\":");
                    if (timeIdx > 0) {
                        int start = timeIdx + 8;
                        int end = content.indexOf(",", start);
                        if (end < 0) end = content.indexOf("}", start);
                        if (end > start) {
                            notifyTime = Long.parseLong(content.substring(start, end).trim());
                        }
                    }
                } else {
                    notifyTime = Long.parseLong(content);
                }
                long now = System.currentTimeMillis();

                // 如果通知时间在5分钟内，执行同步
                if (notifyTime > 0 && now - notifyTime < 300000) {
                    // 有人在线才响应通知
                    if (!Bukkit.getOnlinePlayers().isEmpty()) {
                        // ★ 通过DB队列串行化执行同步
                        submitDbTask("通知-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
                        submitDbTask("通知-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                        submitDbTask("通知-syncUserRegistrations", () -> syncUserRegistrations());
                        submitDbTask("通知-syncServiceProviders", () -> syncServiceProviders());
                        submitDbTask("通知-syncShopData", () -> syncShopData());
                        submitNormalDbTask("通知-pullPendingTransactions", () -> pullPendingTransactions());
                        submitNormalDbTask("通知-pullShopStock", () -> pullShopStock());
                        submitNormalDbTask("通知-pullShopPrices", () -> pullShopPrices());
                        submitNormalDbTask("通知-pullShopConfig", () -> pullShopConfig());
                        submitNormalDbTask("通知-pullBondChanges", () -> pullBondChanges());
                        // 删除通知文件
                        notifyFile.delete();
                    }
                }
            }
        } catch (Exception e) {
            // 静默处理
        }
    }

    /**
     * 拉取Web端债券变化
     */
    private void pullBondChanges() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=pull_bonds&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null || !json.contains("\"success\":true")) return;

                    // 解析债券变化
                    int dataStart = json.indexOf("\"bonds\":");
                    if (dataStart < 0) return;
                    String dataStr = json.substring(dataStart + 8);
                    int arrEnd = findMatchingBracket(dataStr, 0);
                    if (arrEnd < 0) return;
                    String arrStr = dataStr.substring(0, arrEnd + 1);

                    // 更新本地债券数据
                    updateLocalBondData(arrStr);

                } catch (Exception e) {
                    // 静默处理
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * 更新本地债券数据
     */
    private void updateLocalBondData(String bondsJson) {
        try {
            // 解析Web端返回的债券数据
            // 格式: [{"player_name":"PLAYER","amount":100,"updated_at":123456}, ...]

            // 简单JSON解析（手动解析，不依赖外部库）
            int idx = 0;
            while (idx < bondsJson.length()) {
                // 找到下一个 {
                int start = bondsJson.indexOf('{', idx);
                if (start == -1) break;
                int end = bondsJson.indexOf('}', start);
                if (end == -1) break;

                String item = bondsJson.substring(start, end + 1);
                idx = end + 1;

                // 提取 player_name
                String playerName = extractJsonString(item, "player_name");
                if (playerName == null) continue;

                // 提取 amount
                int amountStart = item.indexOf("\"amount\":");
                if (amountStart == -1) continue;
                int amountValStart = amountStart + 9;
                int amountValEnd = amountValStart;
                while (amountValEnd < item.length() && (Character.isDigit(item.charAt(amountValEnd)) || item.charAt(amountValEnd) == '-')) {
                    amountValEnd++;
                }
                int newAmount = Integer.parseInt(item.substring(amountValStart, amountValEnd));

                // 更新本地债券数据
                BondManager bondMgr = plugin.getBonds();
                if (bondMgr != null) {
                    int currentAmount = bondMgr.getBonds(playerName);
                    if (currentAmount != newAmount) {
                        // 只在Web端数据更新时才同步（避免覆盖游戏内操作）
                        // 注意：这里使用setBonds直接设置值，不记录reason
                        bondMgr.setBonds(playerName, newAmount);
                        plugin.getLogger().info("[Web通信] 同步Web端债券: " + playerName + " " + currentAmount + " → " + newAmount);
                    }
                }
            }

            // 减少日志打印频率：1分钟内不重复打印
            long now = System.currentTimeMillis();
            if (now - lastPullBondChangesLog > LOG_INTERVAL) {
                plugin.getLogger().info("[Web通信] 已同步Web端债券数据");
                lastPullBondChangesLog = now;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 更新债券数据失败: " + e.getMessage());
        }
    }

    /** 安全字符串比较（null-safe） */
    private static boolean safeEq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    /**
     * 从JSON字符串中提取字符串值
     */
    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        return json.substring(start, end);
    }

    // ==================== Web注册请求轮询 ====================

    /**
     * 启动Web注册请求轮询任务
     * 每30~90秒随机间隔轮询PHP后端，获取Web端提交的注册请求，创建游戏账号
     * 使用异步线程处理，避免Web响应慢导致游戏卡死
     */
    /**
     * Web注册请求轮询（已合并到定时器A）
     * 保留方法签名兼容启动调用
     */
    public void startWebRegisterPolling() {
        // 已合并到定时器A（合并定时器自动轮询注册请求）
    }

    /**
     * 轮询Web端提交的注册请求
     * 获取待处理的注册请求，在游戏端创建账号并确认
     * 注意：此方法在异步线程中执行，不要在方法内创建BukkitRunnable
     */
    private void pollWebRegisterRequests() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=check_pending_web_register_requests&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(urlStr);
            if (json == null) {
                registerPollFailCount++;
                long now = System.currentTimeMillis();
                if (now - lastRegisterPollLogTime > POLL_LOG_INTERVAL) {
                    warnSlow("轮询注册", "[Web注册轮询] GET失败 (连续失败" + registerPollFailCount + "次)");
                    lastRegisterPollLogTime = now;
                }
                return;
            }

            // ★ 锁库检测：PHP返回database is locked时不计为"成功响应"，但也不刷屏
            if (json.contains("database is locked")) {
                registerPollFailCount = 0;
                return;
            }
            // 成功 → 重置
            registerPollFailCount = 0;
            if (!json.contains("\"success\":true")) {
                plugin.getLogger().warning("[Web注册轮询] 响应不含success:true: " + json.substring(0, Math.min(200, json.length())));
                return;
            }

            // 解析注册请求列表
            // 格式: {"success":true,"data":{"requests":[{...}],"count":N}}
            int countStart = json.indexOf("\"count\":");
            if (countStart < 0) return;
            int countValStart = countStart + 8;
            int countValEnd = countValStart;
            while (countValEnd < json.length() && Character.isDigit(json.charAt(countValEnd))) countValEnd++;
            int count = Integer.parseInt(json.substring(countValStart, countValEnd));

            if (count == 0) {
                // 没有待处理请求，静默返回（不再每次都调用checkAndSyncCompletedRegistrations）
                return;
            }

            plugin.getLogger().info("[Web注册] 发现 " + count + " 个待处理的Web注册请求");

            // 提取每个请求的详细信息
            int requestsStart = json.indexOf("\"requests\":[");
            if (requestsStart < 0) return;
            int arrStart = requestsStart + 12; // len of "requests":[
            int arrEnd = findMatchingBracket(json, arrStart - 1);
            if (arrEnd < 0) return;
            String arrStr = json.substring(arrStart, arrEnd);

            // 逐个处理注册请求
            int idx = 0;
            while (idx < arrStr.length()) {
                int objStart = arrStr.indexOf('{', idx);
                if (objStart == -1) break;
                int objEnd = arrStr.indexOf('}', objStart);
                if (objEnd == -1) break;

                String item = arrStr.substring(objStart, objEnd + 1);

                // 提取字段
                String reqId = extractJsonNumber(item, "id");
                String playerName = extractJsonString(item, "player_name");
                String passwordHash = extractJsonString(item, "password_hash");
                String salt = extractJsonString(item, "salt");
                String email = extractJsonString(item, "email");
                String ipAddress = extractJsonString(item, "ip_address");

                if (reqId == null || playerName == null || passwordHash == null || salt == null) {
                    idx = objEnd + 1;
                    continue;
                }

                plugin.getLogger().info("[Web注册] 处理Web注册请求: " + playerName + " (ID:" + reqId + ")");

                // ★ 异步创建游戏账号：主体是 DB 写 + HTTP 同步，
                //   只有"在线玩家自动登录"必须回主线程（Bukkit API）
                final String fReqId = reqId;
                final String fName = playerName;
                final String fHash = passwordHash;
                final String fSalt = salt;
                final String fEmail = email != null ? email : "";
                final String fIp = ipAddress != null ? ipAddress : "";

                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        // 检查玩家是否已注册
                        DatabaseManager dbMgr = plugin.getDb();
                        if (dbMgr == null) {
                            plugin.getLogger().warning("[Web注册] DatabaseManager未初始化");
                            sendWebRegisterResult(fReqId, "failed", "插件数据库未初始化");
                            return;
                        }

                        // 检查是否已存在 — 如果已存在，用Web端的新salt+hash更新（玩家可能在Web重新注册）
                        Object existing = dbMgr.getField(fName, "password_salt");
                        if (existing != null && !((String) existing).isEmpty()) {
                            plugin.getLogger().info("[Web注册] 玩家 " + fName + " 已注册，更新密码凭证");
                            try {
                                // 用Java原生的hash+salt更新（覆盖旧密码，保留login.db中的数据一致性）
                                dbMgr.updatePassword(fName, fHash, fSalt);
                                plugin.getLogger().info("[Web注册] 密码凭证更新成功: " + fName);
                            } catch (Exception e) {
                                plugin.getLogger().warning("[Web注册] 更新密码凭证失败: " + fName + " - " + e.getMessage());
                                sendWebRegisterResult(fReqId, "failed", "更新密码失败: " + e.getMessage());
                                return;
                            }
                        } else {
                            // 创建用户
                            dbMgr.createUser(fName, fHash, fSalt);
                            plugin.getLogger().info("[Web注册] 游戏账号创建成功: " + fName);
                        }

                        // ★ 写入注册IP到login.db
                        if (!fIp.isEmpty()) {
                            dbMgr.setField(fName, "ip_address", fIp);
                            dbMgr.setField(fName, "register_ip", fIp);
                        }

                        // ★ 如果玩家当前在线，自动登录 —— Bukkit API 必须主线程
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player onlinePlayer = Bukkit.getPlayer(fName);
                            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                plugin.getLogger().info("[Web注册] 玩家 " + fName + " 当前在线，执行自动登录");
                                plugin.autoLogin(onlinePlayer, "web_register");
                                onlinePlayer.sendMessage("§a[Sdf1_login] §fWeb注册成功，已自动登录！");
                            } else {
                                plugin.getLogger().info("[Web注册] 玩家 " + fName + " 当前不在线");
                            }
                        });

                        // 同步注册到Web端（方法内部已异步）
                        syncRegistration(fName, fHash, fSalt, fIp, fEmail);

                        // 同步密码凭证到Web端（通过DB队列）
                        submitDbTask("注册后-pushWebLoginCredentials", () -> pushWebLoginCredentials());

                        // 确认注册完成
                        sendWebRegisterResult(fReqId, "success", "");

                    } catch (Exception e) {
                        plugin.getLogger().warning("[Web注册] 创建游戏账号失败: " + fName + " - " + e.getMessage());
                        sendWebRegisterResult(fReqId, "failed", e.getMessage());
                    }
                });

                idx = objEnd + 1;
            }

        } catch (Exception e) {
            registerPollFailCount++;
            long now = System.currentTimeMillis();
            if (now - lastRegisterPollLogTime > POLL_LOG_INTERVAL) {
                plugin.getLogger().warning("[Web注册轮询] 异常: " + e.getClass().getSimpleName() + " - " + e.getMessage()
                        + " (连续失败" + registerPollFailCount + "次)");
                lastRegisterPollLogTime = now;
            }
        }
    }

    /**
     * 检查已完成的注册请求，将未同步到Java本地的用户补全
     * 修改：不再复用check_pending_web_register_requests（只返回pending/processing），
     * 而是调用新的check_completed_web_register_requests接口来查询completed状态的请求
     */
    private void checkAndSyncCompletedRegistrations() {
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=check_completed_web_register_requests&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String json = doGet(urlStr);
            if (json == null || !json.contains("\"success\":true")) return;

            // 解析所有请求
            int requestsStart = json.indexOf("\"requests\":[");
            if (requestsStart < 0) return;
            int arrStart = requestsStart + 12;
            int arrEnd = findMatchingBracket(json, arrStart - 1);
            if (arrEnd < 0) return;
            String arrStr = json.substring(arrStart, arrEnd);

            // 逐个检查
            int idx = 0;
            int syncedCount = 0;
            while (idx < arrStr.length()) {
                int objStart = arrStr.indexOf('{', idx);
                if (objStart == -1) break;
                int objEnd = arrStr.indexOf('}', objStart);
                if (objEnd == -1) break;

                String item = arrStr.substring(objStart, objEnd + 1);

                String reqId = extractJsonNumber(item, "id");
                String playerName = extractJsonString(item, "player_name");
                String passwordHash = extractJsonString(item, "password_hash");
                String salt = extractJsonString(item, "salt");
                String email = extractJsonString(item, "email");
                String ipAddress = extractJsonString(item, "ip_address");
                String status = extractJsonString(item, "status");

                if (playerName == null || passwordHash == null || salt == null) {
                    idx = objEnd + 1;
                    continue;
                }

                // 检查Java本地是否已有该用户
                final String fName = playerName;
                DatabaseManager dbMgr = plugin.getDb();
                if (dbMgr == null) break;

                Object existing = dbMgr.getField(fName, "password_hash");
                if (existing != null && !((String) existing).isEmpty()) {
                    // 本地已有，跳过
                    idx = objEnd + 1;
                    continue;
                }

                // Java本地没有，需要补全
                final String fReqId = reqId;
                final String fHash = passwordHash;
                final String fSalt = salt;
                final String fEmail = email != null ? email : "";
                final String fIp = ipAddress != null ? ipAddress : "";

                plugin.getLogger().info("[Web注册] 发现未同步用户: " + fName + "，正在补全到本地数据库");

                // ★ 异步补全：DB 写 + HTTP 同步；自动登录回主线程
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        dbMgr.createUser(fName, fHash, fSalt);
                        plugin.getLogger().info("[Web注册] 补全成功: " + fName);

                        // ★ 写入注册IP到login.db
                        if (!fIp.isEmpty()) {
                            dbMgr.setField(fName, "ip_address", fIp);
                            dbMgr.setField(fName, "register_ip", fIp);
                        }

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player onlinePlayer = Bukkit.getPlayer(fName);
                            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                plugin.autoLogin(onlinePlayer, "web_register");
                                onlinePlayer.sendMessage("§a[Sdf1_login] §fWeb注册成功，已自动登录！");
                            }
                        });

                        syncRegistration(fName, fHash, fSalt, fIp, fEmail);
                        submitDbTask("补全-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                        if (fReqId != null) {
                            sendWebRegisterResult(fReqId, "success", "补全同步");
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[Web注册] 补全用户失败: " + fName + " - " + e.getMessage());
                    }
                });

                syncedCount++;
                idx = objEnd + 1;
            }

            if (syncedCount > 0) {
                plugin.getLogger().info("[Web注册] 本次补全同步 " + syncedCount + " 个用户");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[Web注册同步] 检查已完成注册异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 从JSON字符串中提取数值
     */
    private String extractJsonNumber(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        // 跳过空白字符
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-' || json.charAt(end) == '+'))
            end++;
        if (end == start) return null;
        return json.substring(start, end);
    }

    /**
     * 将注册结果写回PHP后端
     */
    private void sendWebRegisterResult(String reqId, String result, String errorMsg) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String bodyJson = "{\"request_id\":" + reqId
                            + ",\"result\":\"" + escapeJson(result) + "\""
                            + ",\"error\":\"" + escapeJson(errorMsg) + "\"}";
                    String postUrl = webBaseUrl + "/api/sync.php?action=complete_web_register_request&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    doPost(postUrl, bodyJson);
                } catch (Exception e) {
                    // 静默处理
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    // ==================== 邮件验证码功能 ====================

    /**
     * 生成6位随机验证码
     */
    public String generateVerificationCode() {
        int code = (int) (Math.random() * 900000) + 100000;
        return String.valueOf(code);
    }

    /**
     * 玩家加入游戏时，同步PHP后端的用户数据到Java本地数据库
     * 由Main.java的PlayerJoinEvent调用
     */
    public void syncUserOnJoin(final String playerName) {
        if (!enabled) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 第一步：检查玩家是否在PHP后端已注册（查询users表）
                String urlStr = webBaseUrl + "/api/sync.php?action=check_player_registered&player="
                        + java.net.URLEncoder.encode(playerName, "UTF-8") + "&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                String json = doGet(urlStr);
                if (json == null || !json.contains("\"success\":true")) return;

                // 解析响应：{"success":true,"message":"ok","data":{"registered":true,...}}
                int dataStart = json.indexOf("\"data\":");
                if (dataStart < 0) return;
                int dataObjStart = dataStart + 7;
                int dataObjEnd = findMatchingBracket(json, dataObjStart);
                if (dataObjEnd < 0) return;
                String dataJson = json.substring(dataObjStart, dataObjEnd);

                // 检查registered字段
                boolean registered = dataJson.contains("\"registered\":true");
                if (!registered) {
                    // PHP后端无注册记录
                    plugin.getLogger().info("[Web注册] 玩家 " + playerName + " 在PHP后端未注册");
                    return;
                }

                // 提取用户信息
                String email = extractJsonString(dataJson, "email");
                String passwordHash = extractJsonString(dataJson, "password_hash");
                String salt = extractJsonString(dataJson, "salt");
                long registerTime = Long.parseLong(extractJsonNumber(dataJson, "register_time") != null ? extractJsonNumber(dataJson, "register_time") : "0");
                long lastLoginTime = Long.parseLong(extractJsonNumber(dataJson, "last_login_time") != null ? extractJsonNumber(dataJson, "last_login_time") : "0");
                int points = Integer.parseInt(extractJsonNumber(dataJson, "points") != null ? extractJsonNumber(dataJson, "points") : "0");
                int giftStage = Integer.parseInt(extractJsonNumber(dataJson, "gift_stage") != null ? extractJsonNumber(dataJson, "gift_stage") : "0");
                int totalOnlineTime = Integer.parseInt(extractJsonNumber(dataJson, "total_online_time") != null ? extractJsonNumber(dataJson, "total_online_time") : "0");
                String ipAddress = extractJsonString(dataJson, "ip_address");

                plugin.getLogger().info("[Web注册] 玩家在PHP后端已注册: " + playerName + ", 有密码凭证:" + !extractJsonString(dataJson, "password_hash").isEmpty());

                // 在主线程操作Java本地数据库
                final String fEmail = email != null ? email : "";
                final String fHash = passwordHash != null ? passwordHash : "";
                final String fSalt = salt != null ? salt : "";
                final String fIp = ipAddress != null ? ipAddress : "";
                final long fRegisterTime = registerTime;
                final long fLastLoginTime = lastLoginTime;
                final int fPoints = points;
                final int fGiftStage = giftStage;
                final int fTotalOnlineTime = totalOnlineTime;
                final String fName = playerName;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    DatabaseManager dbMgr = plugin.getDb();
                    if (dbMgr == null) return;

                    // 检查Java本地是否已有该用户
                    Object existing = dbMgr.getField(fName, "password_hash");
                    if (existing != null && !((String) existing).isEmpty()) {
                        plugin.getLogger().info("[Web注册] 玩家 " + fName + " 已在Java本地存在，无需同步");
                        return;
                    }

                    // Java本地没有用户，PHP后端有注册记录
                    // 如果PHP后端有密码凭证，直接创建用户
                    if (!fHash.isEmpty() && !fSalt.isEmpty()) {
                        dbMgr.createUser(fName, fHash, fSalt);
                        plugin.getLogger().info("[Web注册] 用户 " + fName + " 已从PHP后端同步注册数据到Java本地（含密码凭证）");
                        // ★ 写入注册IP到login.db
                        if (!fIp.isEmpty()) {
                            dbMgr.setField(fName, "ip_address", fIp);
                            dbMgr.setField(fName, "register_ip", fIp);
                        }
                    } else {
                        // PHP后端没有密码凭证，可能是通过register.php的webRegister()直接注册的
                        // 需要等待Java插件通过pollWebRegisterRequests()轮询后创建用户
                        // 或者玩家在游戏中使用/register命令注册
                        plugin.getLogger().info("[Web注册] 用户 " + fName + " 在PHP后端已注册但无密码凭证，等待Java插件同步");
                    }

                    // 更新用户基本信息（注册时间和最后登录时间等）
                    if (fRegisterTime > 0) {
                        dbMgr.setField(fName, "register_time", String.valueOf(fRegisterTime));
                    }
                    if (fLastLoginTime > 0) {
                        dbMgr.setField(fName, "last_login_time", String.valueOf(fLastLoginTime));
                    }
                    if (fPoints > 0) {
                        dbMgr.setField(fName, "points", String.valueOf(fPoints));
                    }
                    if (fGiftStage > 0) {
                        dbMgr.setField(fName, "gift_stage", String.valueOf(fGiftStage));
                    }
                    if (fTotalOnlineTime > 0) {
                        dbMgr.setField(fName, "total_online_time", String.valueOf(fTotalOnlineTime));
                    }
                    if (!fEmail.isEmpty()) {
                        dbMgr.setField(fName, "email", fEmail);
                    }

                    // 如果玩家在线，自动登录
                    Player onlinePlayer = Bukkit.getPlayer(fName);
                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                        plugin.autoLogin(onlinePlayer, "web_sync");
                        onlinePlayer.sendMessage("§a[Sdf1_login] §fWeb注册数据已同步，正在为你自动登录...");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[Web注册同步] 玩家 " + playerName + " 加入时同步异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * 延迟推送指定玩家的密码凭证到Web端（已废弃，不再使用）
     *
     * @deprecated 改用 pull_player_credentials API
     */
    @Deprecated
    private void pushWebLoginCredentialsDelayed(final String playerName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String urlStr = webBaseUrl + "/api/sync.php?action=push_web_credentials&secret="
                        + java.net.URLEncoder.encode(secretKey, "UTF-8");
                String postData = "players=" + java.net.URLEncoder.encode(
                        "[{\"player_name\":\"" + playerName + "\",\"temp_only\":true}]", "UTF-8");
                String resp = doPost(urlStr, postData);
                if (resp != null) {
                    plugin.getLogger().info("[Web凭证] 玩家 " + playerName + " 的密码凭证已推送到PHP后端");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Web凭证] 推送玩家 " + playerName + " 的密码凭证失败: " + e.getMessage());
            }
        });
    }
}