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
    private String lastPushCredentialsHash = "";
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
            while (dbWorkerRunning.get()) {
                try {
                    DbTask task = dbTaskQueue.take();
                    long waitMs = System.currentTimeMillis() - task.createdAt;
                    if (waitMs > 10000) {
                        plugin.getLogger().warning("[DB队列] 等待过久: " + task.name + " 等待=" + waitMs + "ms");
                    }
                    task.run();
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
            HttpResponse<String> resp = plainHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
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
                    .connectTimeout(Duration.ofSeconds(10))
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
                    .connectTimeout(Duration.ofSeconds(10))
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
                    .connectTimeout(Duration.ofSeconds(10))
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
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = cfHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sslConsecutiveFailures = 0; // 成功 → 重置断路器
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;  // 恢复正常HTTPS
                String body = resp.body();
                detectPhpBusy(body);  // ★ 锁库检测
                return body;
            }
            // ★ 500时记录响应体（PHP错误信息在body里）
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] GET HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            return null;
        } catch (Exception e) {
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
            plugin.getLogger().warning("[Web通信] GET异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * GET请求 - 返回状态码
     */
    private int doGetStatus(String urlStr) {
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
            HttpResponse<String> resp = cfHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
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
            HttpResponse<String> resp = cfHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
            // ★ 非2xx记录响应体
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] POST HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            return null;
        } catch (Exception e) {
            // ★ SSL断路器：跟踪连续失败（所有连接异常都计数）
            sslConsecutiveFailures++;
            plugin.getLogger().warning("[Web通信] POST异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * POST请求 - 返回响应体，失败返回null（含SSL降级逻辑）
     */
    private String doPostWithSslFallback(String urlStr, String jsonBody) {
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
            HttpResponse<String> resp = cfHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                sslConsecutiveFailures = 0;
                sslCircuitOpenCount = 0;    // 重置退避计数
                sslDowngraded = false;  // 恢复正常HTTPS
                return resp.body();
            }
            // ★ 非2xx记录响应体
            String shortUrl = urlStr.length() > 120 ? urlStr.substring(0, 120) + "..." : urlStr;
            String body = resp.body();
            String shortBody = (body != null && body.length() > 200) ? body.substring(0, 200) : body;
            plugin.getLogger().warning("[Web通信] POST HTTP " + resp.statusCode() + ": " + shortUrl + " | 响应: " + shortBody);
            return null;
        } catch (Exception e) {
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
            plugin.getLogger().warning("[Web通信] POST异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * 降级后的HTTP POST请求（使用不带SSL的HttpClient）
     */
    private String doPostHttpFallback(String urlStr, String jsonBody) {
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
            HttpResponse<String> resp = plainHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
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
            plugin.getLogger().warning("[Web通信] HTTP降级POST异常: " + e.getMessage());
            triggerCircuitBreaker(true);
            return null;
        }
    }

    /**
     * POST请求带重试（处理SSL/网络异常）
     */
    private String doPostWithRetry(String urlStr, String jsonBody, int maxRetries) {
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
                HttpResponse<String> resp = cfHttpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String body = resp.body();
                    detectPhpBusy(body);  // ★ 锁库检测
                    return body;
                }
                if (attempt < maxRetries) {
                    plugin.getLogger().info("[Web通信] POST重试 " + attempt + "/" + maxRetries + " HTTP " + resp.statusCode());
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) {}
                } else {
                    return resp.body();
                }
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    plugin.getLogger().info("[Web通信] POST重试 " + attempt + "/" + maxRetries + ": " + e.getClass().getSimpleName());
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) {}
                } else {
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
        plugin.getLogger().info("[Web通信] 后端地址: " + webBaseUrl + " | 启用: " + enabled);
    }

    /**
     * 重载后端设置（仅Web通信相关配置）
     */
    public void reloadWebConfig() {
        boolean wasEnabled = enabled;
        loadConfig();
        plugin.getLogger().info("[Web通信] Web后端配置已重载: 地址=" + webBaseUrl + " 启用=" + enabled + " 密钥=" + secretKey);

        // ★★★ 运行时启停：根据enabled状态动态启动/停止轮询定时器
        // 1) 已禁用：定时器内部已有 !enabled 守卫，下一轮自动跳过HTTP请求（无需手动取消）
        // 2) 从禁用→启用且定时器未启动：立即启动合并定时器
        if (enabled && !pollingStarted) {
            plugin.getLogger().info("[Web通信] ★ 重载后检测到已启用，启动合并定时器（A/B/C/D/E）");
            startMergedPolling();
        } else if (!enabled && wasEnabled) {
            plugin.getLogger().info("[Web通信] ★ 重载后检测到已禁用，定时器将在下一轮自动停止请求Web后端");
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
        bondMgr.addTransactionListener((playerName, type, amount) -> {
            plugin.getLogger().info("[Web交易即时] 检测到交易: " + playerName + " " + type + " " + amount + "，立即推送");
            requestImmediateTransactionSync();
        });
        plugin.getLogger().info("[Web通信] 交易即时推送监听器已注册");
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("[Web通信] 未启用，在插件设置.txt中设置 web通信-启用=true");
            return;
        }
        plugin.getLogger().info("[Web通信] 已启用，地址: " + webBaseUrl);

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

        // ★ 启动后延迟30秒执行首次全量同步（通过DB队列串行化）
        // 每个任务之间加入随机延迟（2-4秒），避免瞬时堆叠导致DB队列等待过久
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getLogger().info("[Web通信] 开始首次全量同步（通过DB队列串行化）...");
                // 登录相关操作高优先级
                submitDbTask("首次-syncUserRegistrations", () -> syncUserRegistrations());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitDbTask("首次-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                // 普通同步操作低优先级（每个任务间随机间隔2-4秒，避免PHP端DB锁）
                submitDbTask("首次-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncShopData", () -> syncShopData());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-pushShopCatalog", () -> pushShopCatalog());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncBondBalances", () -> syncBondBalances());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncBondTransactions", () -> syncBondTransactions());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncAllPlayerIps", () -> syncAllPlayerIps());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncServiceProviders", () -> syncServiceProviders());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-syncLandData", () -> syncLandData());
                try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                submitNormalDbTask("首次-pollAdminChanges", () -> pollAdminChanges());
                initialSyncComplete = true;  // 首次全量同步提交完成
                allowLoginPolling = true;  // 允许登录轮询
            }
        }.runTaskLaterAsynchronously(plugin, 20L * 10);

        // ★ 启动嵌入式HTTP服务器接收PHP回调
        startCallbackServer();

        // ★ 初始化全量同步调度时间（由合并定时器C在到达nextSyncTime时触发）
        scheduleNextSync();

        // ★ 启动3个合并定时器（替代原来的6个独立定时器，自动错峰≥5秒）
        startMergedPolling();
    }

    public void shutdown() {
        stop();
        stopDbWorker();
        // ★ 关闭Web线程池
        webExecutor.shutdownNow();
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
                plugin.getLogger().warning("[Web通信] 回调服务器启动失败: " + e.getMessage());
            }
        }, "web-callback-server").start();
    }

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
     *
     * @param token 要作废的token
     * @return 如果token存在并被成功作废返回true
     */
    public boolean revokeToken(String token) {
        return tokenStore.remove(token) != null;
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
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitDbTask("即时-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitDbTask("即时-syncUserRegistrations", () -> syncUserRegistrations());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitNormalDbTask("即时-pullPendingTransactions", () -> pullPendingTransactions());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitNormalDbTask("即时-pullShopStock", () -> pullShopStock());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitNormalDbTask("即时-pullShopPrices", () -> pullShopPrices());
                        submitNormalDbTask("即时-pullShopConfig", () -> pullShopConfig());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitNormalDbTask("即时-pullBondChanges", () -> pullBondChanges());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                        submitNormalDbTask("即时-syncAllPlayerIps", () -> syncAllPlayerIps());
                        try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
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
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitDbTask("末轮-pushWebLoginCredentials", () -> pushWebLoginCredentials());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitDbTask("末轮-syncUserRegistrations", () -> syncUserRegistrations());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitDbTask("末轮-syncBondTransactions", () -> syncBondTransactions());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitNormalDbTask("末轮-pullPendingTransactions", () -> pullPendingTransactions());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitNormalDbTask("末轮-pullShopStock", () -> pullShopStock());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
                    submitNormalDbTask("末轮-pullShopPrices", () -> pullShopPrices());
                    submitNormalDbTask("末轮-pullShopConfig", () -> pullShopConfig());
                    try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
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
                long randB = (long)(Math.random() * 10) * 2;
                long randC = (long)(Math.random() * 10) * 2;
                long randE = (long)(Math.random() * 10) * 2;
                scheduleTimerB(40L + randB);   // 2秒后重启B
                scheduleTimerC(120L + randC);  // 6秒后重启C
                scheduleTimerE(240L + randE);  // 12秒后重启E
                plugin.getLogger().info("[合并C] ★ 玩家上线，Timer B/C/E已恢复(随机偏移B=" + (randB/20) + "s C=" + (randC/20) + "s E=" + (randE/20) + "s)");
            }

            // 玩家在线：全量批处理
            submitDbTask("周期-pushWebLoginCredentials", () -> pushWebLoginCredentials());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitDbTask("周期-syncUserRegistrations", () -> syncUserRegistrations());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitDbTask("周期-syncOnlinePlayers", () -> syncOnlinePlayers(), true);
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitDbTask("周期-syncBondTransactions", () -> syncBondTransactions());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-pullPendingTransactions", () -> pullPendingTransactions());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-pullShopStock", () -> pullShopStock());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-pullShopPrices", () -> pullShopPrices());
            submitNormalDbTask("周期-pullShopConfig", () -> pullShopConfig());
            submitNormalDbTask("周期-pushShopCatalog", () -> pushShopCatalog());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-pullBondChanges", () -> pullBondChanges());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-syncAllPlayerIps", () -> syncAllPlayerIps());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
            submitNormalDbTask("周期-syncServiceProviders", () -> syncServiceProviders());
            try { Thread.sleep(6000 + (long)(Math.random() * 8000)); } catch (InterruptedException ignored) {}
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
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=check_pending_transactions&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doGet(urlStr);
            if (resp == null) {
                txPollFailCount++;
                long now = System.currentTimeMillis();
                if (now - lastTxPollLogTime > POLL_LOG_INTERVAL) {
                    plugin.getLogger().warning("[合并B-交易] GET失败 (连续失败" + txPollFailCount + "次)");
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
            for (int i = 0; i < 4; i++) {
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
                if (timerACycleCount % 5 == 0) { // 每5轮(~15-25秒)检查一次
                    submitNormalDbTask("TimerA-cdkPull", () -> {
                        try {
                            // 本地CDK（web_transactions pending）
                            doTransactionPollCheck();
                        } catch (Exception e) { /* 静默 */ }
                    });
                    // 远程CDK（cdk_validate_requests，sdf1计分板CDK验证）
                    if (initialSyncComplete) {
                        submitNormalDbTask("TimerA-cdkRemotePull", () -> {
                            try {
                                pullWebCdkRequestsAndValidate();
                                pullSdf1PendingAndValidateWeb();
                            } catch (Exception e) { /* 静默 */ }
                        });
                    }
                    // ★ PHP→Java变更轮询（管理员在PHP改了配置/领地，Java及时拉取）
                    if (initialSyncComplete) {
                        submitNormalDbTask("TimerA-pollAdminChanges", () -> {
                            try { pollAdminChanges(); } catch (Exception e) { /* 静默 */ }
                        });
                        // ★ 过户cooldown检测：权限变更则取消过户
                        submitNormalDbTask("TimerA-transferCancellations", () -> {
                            try { handlePendingTransferCancellations(); } catch (Exception e) { /* 静默 */ }
                        });
                    }
                    // ★ 异步玩家验证轮询：拉取PHP的pending_player_validations，验证后推回结果
                    if (initialSyncComplete) {
                        submitNormalDbTask("TimerA-pollPlayerValidations", () -> {
                            try { pullPendingPlayerValidations(); } catch (Exception e) { /* 静默 */ }
                        });
                    }
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
                if (!enabled) {
                    scheduleTimerB(calcStaggeredDelay(TIMER_B, 0, 10));
                    return;
                }

                // ★ 全员下线暂停：不调度下一轮
                if (timersBCPaused) {
                    plugin.getLogger().info("[合并B] ★ 全员下线，Timer B已暂停");
                    return; // 不调度下一轮，定时器自然停止
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
                if (!enabled) {
                    scheduleTimerC(calcStaggeredDelay(TIMER_C, 10, 20));
                    return;
                }

                // ★ 全员下线暂停：不调度下一轮
                if (timersBCPaused) {
                    plugin.getLogger().info("[合并C] ★ 全员下线，Timer C已暂停");
                    return; // 不调度下一轮，定时器自然停止
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

                // 全量批处理同步（仅在nextSyncTime到达时执行）
                if (now >= nextSyncTime) {
                    try { doActiveSyncBatch(); }
                    catch (Exception e) {
                        plugin.getLogger().warning("[合并C-全量同步] 异常: " + e.getMessage());
                    } finally {
                        scheduleNextSync(); // 设置60~90秒后的下一次同步时间
                    }
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
                if (!enabled) {
                    scheduleTimerE(calcStaggeredDelay(TIMER_E, 20, 30));
                    return;
                }

                // ★ 全员下线暂停：不调度下一轮
                if (timersBCPaused) {
                    plugin.getLogger().info("[续费轮询E] ★ 全员下线，Timer E已暂停");
                    log.error("\n" +
                            " __          __                             _                                                                    \n" +
                            " \\ \\        / /                            | |                                                                   \n" +
                            "  \\ \\  /\\  / /__  ___ ___  _ __ ___   ___  | |_ ___                                                              \n" +
                            "   \\ \\/  \\/ / _ \\/ __/ _ \\| '_ ` _ \\ / _ \\ | __/ _ \\                                                             \n" +
                            "    \\  /\\  /  __/ (_| (_) | | | | | |  __/ | || (_) |                                                            \n" +
                            "     \\/  \\/ \\___|\\___\\___/|_| |_| |_|\\___|  \\__\\___/                _                                            \n" +
                            "                                             | |                   (_)                                           \n" +
                            "   ___ __ _  ___    _   _ _   _  __ _ _ __   | |_ __ _ _ __   __  ___  __ _ _ __    ___  ___ _ ____   _____ _ __ \n" +
                            "  / __/ _` |/ _ \\  | | | | | | |/ _` | '_ \\  | __/ _` | '_ \\  \\ \\/ / |/ _` | '_ \\  / __|/ _ \\ '__\\ \\ / / _ \\ '__|\n" +
                            " | (_| (_| | (_) | | |_| | |_| | (_| | | | | | || (_| | | | |  >  <| | (_| | | | | \\__ \\  __/ |   \\ V /  __/ |   \n" +
                            "  \\___\\__,_|\\___/   \\__, |\\__,_|\\__,_|_| |_|  \\__\\__,_|_| |_| /_/\\_\\_|\\__,_|_| |_| |___/\\___|_|    \\_/ \\___|_|   \n" +
                            "  _                  __/ |  ___                   _     _     _ _  __                                            \n" +
                            " (_)     _          |___/  |__ \\                 | |   (_)   | (_)/ _|                                           \n" +
                            "  _ _ __(_)  _ __ ___   ___   ) | _   _ _ __  ___| |__  _  __| |_| |_ _   _   ___ _ __                           \n" +
                            " | | '_ \\   | '_ ` _ \\ / __| / / | | | | '_ \\/ __| '_ \\| |/ _` | |  _| | | | / __| '_ \\                          \n" +
                            " | | |_) |  | | | | | | (__ / /_ | |_| | |_) \\__ \\ | | | | (_| | | | | |_| || (__| | | |                         \n" +
                            " |_| .__(_) |_| |_| |_|\\___|____(_)__, | .__/|___/_| |_|_|\\__,_|_|_|  \\__,_(_)___|_| |_|                         \n" +
                            "   | |                             __/ | |                                                                       \n" +
                            "   |_|            _       ____   _|___/|_|______ ___                                                             \n" +
                            "                 | |  _  |___ \\ / _ \\  / /____  / _ \\                                                            \n" +
                            "  _ __   ___  ___| |_(_)   __) | | | |/ /_   / / (_) |                                                           \n" +
                            " | '_ \\ / _ \\/ __| __|    |__ <| | | | '_ \\ / / \\__, |                                                           \n" +
                            " | |_) | (_) \\__ \\ |_ _   ___) | |_| | (_) / /    / /                                                            \n" +
                            " | .__/ \\___/|___/\\__(_) |____/ \\___/ \\___/_/    /_/                                                             \n" +
                            " | |                                                                                                             \n" +
                            " |_|                                                                                                             ");
                    return; // 不调度下一轮，定时器自然停止
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
                    plugin.getLogger().warning("[合并B-库存] GET失败 (连续失败" + shopStockPollFailCount + "次)");
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
            // 检查是否启用
            java.lang.reflect.Method isEnabled = sdf1Plugin.getClass().getMethod("isEnabled");
            if (!(Boolean) isEnabled.invoke(sdf1Plugin)) return;

            // 调用 sdf1.Main.pullPendingCdkValidations()
            java.lang.reflect.Method pullMethod = sdf1Plugin.getClass().getMethod("pullPendingCdkValidations");
            Object[][] pendingList = (Object[][]) pullMethod.invoke(sdf1Plugin);
            if (pendingList == null || pendingList.length == 0) return;

            for (Object[] item : pendingList) {
                String requestId = (String) item[0];
                String cdkCode = (String) item[1];
                String playerName = (String) item[2];

                // ★ 先检查CDK是否存在（只读）
                String status = "not_found";
                int amount = 0;
                try {
                    String validateUrl = webBaseUrl + "/api/sync.php?action=check_cdk_exists&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8")
                            + "&code=" + java.net.URLEncoder.encode(cdkCode, "UTF-8");
                    String vJson = doGet(validateUrl);
                    if (vJson != null) {
                        // ★ 锁库检测：PHP返回database is locked时跳过
                        if (vJson.contains("database is locked")) {
                            return;
                        }
                        // ★ 详细日志：PHP返回的原始JSON
                        plugin.getLogger().info("[CDK-Web验证] PHP原始返回: " + vJson);
                        String found = extractJsonStr(vJson, "found");
                        String st = extractJsonStr(vJson, "status");
                        String am = extractJsonStr(vJson, "amount");
                        plugin.getLogger().info("[CDK-Web验证] 解析结果: found=" + found + " status=" + st + " amount=" + am);

                        if ("true".equals(found) && "available".equals(st)) {
                            amount = am.isEmpty() ? 0 : Integer.parseInt(am);
                            // ★ CDK存在且可用，调用兑换API标记已使用+写流水
                            // status只在cdk_redeem_remote成功后才设为success
                            try {
                                String redeemUrl = webBaseUrl + "/api/sync.php?action=cdk_redeem_remote&secret="
                                        + java.net.URLEncoder.encode(secretKey, "UTF-8")
                                        + "&code=" + java.net.URLEncoder.encode(cdkCode, "UTF-8")
                                        + "&player=" + java.net.URLEncoder.encode(playerName, "UTF-8");
                                String rJson = doGet(redeemUrl);
                                if (rJson != null) {
                                    // ★ 锁库检测：PHP返回database is locked时跳过
                                    if (rJson.contains("database is locked")) {
                                        return;
                                    }
                                    plugin.getLogger().info("[CDK-Web验证] 兑换结果: " + rJson);
                                    String rSt = extractJsonStr(rJson, "status");
                                    if ("success".equals(rSt)) {
                                        status = "success";
                                    } else if ("already_used".equals(rSt)) {
                                        status = "already_used";
                                    }
                                } else {
                                    plugin.getLogger().warning("[CDK-Web验证] cdk_redeem_remote GET失败");
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("[CDK-Web验证] 兑换请求失败: " + e.getMessage());
                            }
                        } else if ("true".equals(found) && "already_used".equals(st)) {
                            status = "already_used";
                        } else {
                            plugin.getLogger().info("[CDK-Web验证] CDK " + cdkCode + " 在Web端不存在或状态未知");
                        }
                    } else {
                        plugin.getLogger().warning("[CDK-Web验证] GET失败 CDK=" + cdkCode);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[CDK-Web验证] 请求失败: " + e.getMessage());
                }

                // 回传结果给sdf1
                java.lang.reflect.Method setResult = sdf1Plugin.getClass().getMethod("setCdkValidationResult", String.class, String.class, int.class);
                setResult.invoke(sdf1Plugin, requestId, status, amount);

                if (!"not_found".equals(status)) {
                    plugin.getLogger().info("[CDK-Web验证] " + cdkCode + " → " + status + " player=" + playerName + (amount > 0 ? " 金额:" + amount : ""));
                }
            }
        } catch (Exception e) {
            // 静默
        }
    }

    // ==================== Token同步到Web ====================

    /**
     * 将本地生成的Token批量注册到PHP数据库
     * 使用SECRET_KEY认证，不需要token（解决鸡生蛋问题）
     */
    private void syncTokensToWeb(List<String[]> tokens) {
        if (tokens == null || tokens.isEmpty()) return;
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
            if (resp != null) {
                plugin.getLogger().info("[Web通信] Token注册成功");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] Token注册失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    /**
     * 包装方法：生成token并自动同步到Web
     *
     * @return 生成的token
     */
    private String generateAndSyncToken(String playerName, String purpose) {
        String token = generateToken(playerName, purpose);
        List<String[]> batch = new ArrayList<>();
        batch.add(new String[]{token, playerName, purpose});
        syncTokensToWeb(batch);
        return token;
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
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int colon = json.indexOf(":", i);
        int start = json.indexOf("\"", colon + 1);
        if (start < 0) return "";
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return "";
        return json.substring(start + 1, end);
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

            if (items.isEmpty()) return;

            // ★ 无变化静默：对比商品数量和内容hash
            String currentHash = items.size() + ":" + items.hashCode();
            if (currentHash.equals(lastShopDataHash)) return; // 无变化，跳过
            lastShopDataHash = currentHash;

            // 构建请求数据
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);

            String json = mapToJson(body);
            String response = httpPostWithToken("api/sync.php?action=sync_shop", token, body);

            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 商城数据变更，已同步: " + items.size() + "个商品");
                } else {
                    plugin.getLogger().warning("[Web通信] 商城同步失败: " + result.get("message"));
                }
            } else {
                plugin.getLogger().warning("[Web通信] 商城同步请求失败");
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
            for (Map<String, Object> sp : plugin.getDb().getAllServiceProviders()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("player_name", sp.get("player_name"));
                p.put("role", sp.get("role"));
                p.put("active", sp.get("active"));
                p.put("join_time", sp.get("join_time"));
                providers.add(p);
            }

            if (providers.isEmpty()) return;

            // ★ 无变化静默
            String currentHash = providers.size() + ":" + providers.hashCode();
            if (currentHash.equals(lastServiceProviderHash)) return;
            lastServiceProviderHash = currentHash;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("data", providers);

            String response = httpPostWithToken("api/sync.php?action=sync_service_providers", token, body);

            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
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
     */
    public void syncRegistration(String playerName, String passwordHash, String salt, String email, String ip) {
        if (!enabled) return;

        try {
            String token = generateAndSyncToken(playerName, "register");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("player", playerName);
            body.put("password_hash", passwordHash);
            body.put("salt", salt);
            body.put("email", email != null ? email : "");
            body.put("ip", ip != null ? ip : "");

            String response = httpPostWithToken("api/register.php?action=register", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 注册同步成功: " + playerName);
                } else {
                    plugin.getLogger().warning("[Web通信] 注册同步失败: " + result.get("message"));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web通信] 注册同步异常: " + e.getMessage());
        }
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
            if (currentHash.equals(lastLandDataHash)) {
                // ★ hash未变化，静默跳过（但首次运行或强制刷新时会同步）
                return;
            }
            plugin.getLogger().info("[防护-sync] hash变化: lands=" + lands.size() + " config=" + cfgForHash.size() + "项，开始同步");
            lastLandDataHash = currentHash;

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
            if (allPlayers.isEmpty()) return;

            Map<String, Object> bonds = new LinkedHashMap<>();
            for (String name : allPlayers) {
                bonds.put(name, bondMgr.getBonds(name));
            }

            // ★ 无变化静默：对比债券数据hash
            String currentHash = bonds.size() + ":" + bonds.hashCode();
            if (currentHash.equals(lastBondBalanceHash)) return; // 无变化，跳过
            lastBondBalanceHash = currentHash;

            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("bonds", bonds);

            String response = httpPostWithToken("api/sync.php?action=sync_bonds", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
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

    public void syncBondTransactions() {
        if (!enabled) return;

        try {
            BondManager bondMgr = plugin.getBonds();
            if (bondMgr == null) return;

            // 读取上次同步的最晚时间
            loadLastSyncedTxTime();

            // 获取增量交易记录
            List<Map<String, Object>> txs = bondMgr.getTransactionsAfterTime(lastSyncedTxTime);
            if (txs.isEmpty()) return;

            // 生成同步token
            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transactions", txs);

            String response = httpPostWithToken("api/sync.php?action=sync_transactions", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    // 更新已同步的最晚时间
                    long maxTime = lastSyncedTxTime;
                    for (Map<String, Object> tx : txs) {
                        long t = ((Number) tx.get("time")).longValue();
                        if (t > maxTime) maxTime = t;
                    }
                    if (maxTime > lastSyncedTxTime) {
                        lastSyncedTxTime = maxTime;
                        saveLastSyncedTxTime(maxTime);
                    }
                    int synced = txs.size();
                    Object dataObj = result.get("data");
                    if (dataObj instanceof Map) {
                        Object syncedVal = ((Map<?, ?>) dataObj).get("synced");
                        if (syncedVal instanceof Number) {
                            synced = ((Number) syncedVal).intValue();
                        }
                    }
                    plugin.getLogger().info("[Web交易同步] 推送" + synced + "笔交易到PHP");
                } else {
                    plugin.getLogger().warning("[Web交易同步] 失败: " + result.get("message"));
                }
            } else {
                plugin.getLogger().warning("[Web交易同步] PHP响应为空");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易同步] 异常: " + e.getMessage());
        }
    }

    private void loadLastSyncedTxTime() {
        if (lastSyncedTxTime > 0) return;
        try {
            File dbFile = new File(plugin.getDataFolder(), "web_sync.db");
            if (!dbFile.exists()) return;
            java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            java.sql.Statement st = conn.createStatement();
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
            if (users.isEmpty()) return;

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

            // ★ 无变化静默：对比MD5 hash，避免无效网络请求
            String currentHash = syncData.toString().hashCode() + "_" + syncData.size();
            if (currentHash.equals(lastUserRegistrationHash)) return; // 无变化，跳过
            lastUserRegistrationHash = currentHash;

            // ★ 同步前不打印任何日志，只有失败时才打印warning

            String token = generateAndSyncToken("system", "sync");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("users", syncData);

            String response = httpPostWithToken("api/sync.php?action=sync_login", token, body);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 用户注册数据变更，已同步: " + syncData.size() + "人");
                } else {
                    plugin.getLogger().warning("[Web通信] 用户注册同步失败: " + response);
                }
            } else {
                plugin.getLogger().warning("[Web通信] 用户注册同步无响应");
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
    public void recordJavaLogin(String playerName) {
        javaLoginRecords.put(playerName, System.currentTimeMillis());
        plugin.getLogger().info("[Web登录] ✅ 记录Java登录: " + playerName + "（5分钟内重连可直接放行，当前记录数: " + javaLoginRecords.size() + "）");
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
            try {
                long delayMs = (long) (Math.random() * 2000); // 0-2秒
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {}
            
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

        // 收集需要同步的玩家
        List<Map<String, Object>> needSync = new ArrayList<>();
        ConcurrentHashMap<String, String> newSnapshot = new ConcurrentHashMap<>();

        for (Map<String, Object> user : allUsers) {
            String name = (String) user.get("player_name");
            if (name == null || name.isEmpty()) continue;

            String ip = (String) user.get("ip_address");
            if (ip == null) ip = "";

            // 获取玩家当前IP（从login.db的ip_address或register_ip）
            // ip_address 是玩家登录后Java记录的最新IP
            String currentIp = dbMgr.getField(name, "ip_address") != null ? String.valueOf(dbMgr.getField(name, "ip_address")) : null;
            if (currentIp == null || currentIp.isEmpty()) {
                // 如果 ip_address 为空，尝试 register_ip
                currentIp = (String) user.get("register_ip");
            }
            if (currentIp == null || currentIp.isEmpty()) {
                currentIp = "";
            }
            // 如果 IP 是空字符串，跳过
            if (currentIp.isEmpty()) continue;

            String[] cached = ipCache.get(name);
            if (cached != null && "0".equals(cached[1]) && currentIp.equals(cached[0])) {
                // IP已同步且未变化，跳过
                continue;
            }

            Map<String, Object> pInfo = new LinkedHashMap<>();
            pInfo.put("name", name);
            pInfo.put("ip", currentIp);
            needSync.add(pInfo);
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
                        plugin.getLogger().info("[Web通信] 全量同步" + needSync.size() + "个玩家IP到PHP端");
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
            if (allUsers.isEmpty()) return;

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
                    // ★ 如果有临时密码，也推送
                    if (tempHash != null && !tempHash.isEmpty()) {
                        cred.put("temp_password_hash", tempHash);
                        cred.put("temp_pw_expire", tempExpire);
                    }
                    credentials.add(cred);
                }
            }

            if (credentials.isEmpty()) return;

            // ★ 无变化静默
            String currentHash = credentials.size() + ":" + credentials.hashCode();
            if (currentHash.equals(lastPushCredentialsHash)) return;
            lastPushCredentialsHash = currentHash;

            String secretKey = this.secretKey;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("secret", secretKey);
            body.put("players", credentials);

            String jsonBody = mapToJson(body);
            String response = httpPost("api/sync.php?action=push_web_credentials", jsonBody);
            if (response != null) {
                Map<String, Object> result = parseJson(response);
                Boolean success = (Boolean) result.get("success");
                if (Boolean.TRUE.equals(success)) {
                    plugin.getLogger().info("[Web通信] 密码凭证变更，已同步: " + credentials.size() + "人");
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
                    player.sendMessage("§7[Web] 使用 §e/sdf1_login weblogin §7可重新获取");
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

    public boolean isEnabled() {
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
                    plugin.getLogger().warning("[Web登录确认轮询] ✗ GET失败 (连续失败" + loginPollFailCount + "次)");
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
                    plugin.getLogger().warning("[Web密码验证轮询] GET失败 (连续失败" + loginPollFailCount + "次)");
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
            plugin.getLogger().info("[Web密码验证轮询] ★ 发现待处理请求，数组长度=" + arrStr.length());
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

                // 在主线程验证密码
                final String fReqId = reqId;
                final String fName = playerName;
                final String fPwd = password;
                Bukkit.getScheduler().runTask(plugin, () -> {
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
                        sendWebLoginResult(fReqId, fName, result);
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
     */
    private void sendWebLoginResult(String reqId, String playerName, String result) {
        new BukkitRunnable() {
            @Override
            public void run() {
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
                }
            }
        }.runTaskAsynchronously(plugin);
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

    // ==================== 新增功能 ====================

    // 日志控制：避免频繁打印
    private long lastPullTransactionsLog = 0;
    private long lastPullShopStockLog = 0;
    private long lastPullBondChangesLog = 0;
    private long lastPollRegisterRequestsLog = 0;
    private long lastPollWebLoginExceptionLog = 0;
    private static final long LOG_INTERVAL = 60000; // 1分钟内不重复打印相同日志

    /**
     * 拉取待处理的交易（Web购买/充值）
     */
    private void pullPendingTransactions() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String urlStr = webBaseUrl + "/api/sync.php?action=pull_pending_transactions&secret="
                            + java.net.URLEncoder.encode(secretKey, "UTF-8");
                    String json = doGet(urlStr);
                    if (json == null) {
                        plugin.getLogger().warning("[Web交易] GET失败，3秒后重试...");
                        try { Thread.sleep(3000); } catch (InterruptedException ie) {}
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

                        // 在主线程处理交易
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
            } else if (type.equals("admin_recharge") || type.equals("bond_recharge") || type.equals("recharge") || type.equals("admin_give")) {
                // 管理员充值：增加债券（充值不受冻结限制）
                int balBefore = plugin.getBondManager().getBonds(playerName);
                plugin.getBondManager().addBonds(playerName, amount, "web_recharge", "", "Web后台", "管理员充值");
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
                plugin.getBondManager().addBonds(playerName, amount, "web_cdk", "", "Web商城", "CDK兑换");
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
     * 确认交易已处理（同步调用，确保PHP收到确认后才返回，防止重启后重复发货）
     */
    private void confirmTransaction(String txId) {
        try {
            confirmedTxIds.add(txId); // ★ 先标记本地已确认（即使HTTP失败也不会重复处理）
            String bodyJson = "{\"tx_id\":\"" + txId + "\"}";
            String postUrl = webBaseUrl + "/api/sync.php?action=confirm_transaction&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            doPost(postUrl, bodyJson);
        } catch (Exception e) {
            plugin.getLogger().warning("[Web交易] 确认交易 " + txId + " 失败（已标记本地已确认，不会重复处理）: " + e.getMessage());
        }
    }

    /**
     * 写入退款记录到 PHP 端（通过 sync.php），让 PHP 自动退款+恢复库存。
     * 用于购物车发货部分/全部失败时的整单退款。
     */
    private void writeRefundTransaction(String playerName, int amount, String origTxId, String reason, String payMode) {
        try {
            String bodyJson = "{\"player_name\":\"" + playerName
                    + "\",\"amount\":" + amount
                    + ",\"orig_tx_id\":\"" + origTxId
                    + "\",\"reason\":\"" + reason.replace("\"", "'")
                    + "\",\"pay_mode\":\"" + (payMode != null ? payMode : "bond") + "\"}";
            String postUrl = webBaseUrl + "/api/sync.php?action=write_shop_refund&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doPost(postUrl, bodyJson);
            plugin.getLogger().info("[Web交易] 已写入退款记录: 玩家=" + playerName + " 金额=" + amount + " 原交易#" + origTxId + " 响应=" + resp);
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
        try {
            String urlStr = webBaseUrl + "/api/sync.php?action=set_shop_config&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8")
                    + "&key=" + java.net.URLEncoder.encode(key, "UTF-8")
                    + "&value=" + java.net.URLEncoder.encode(value, "UTF-8");
            doGet(urlStr);
        } catch (Exception e) {
            plugin.getLogger().warning("[配置推送] 保存商店配置失败: " + e.getMessage());
        }
    }

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
            String urlStr = webBaseUrl + "/api/sync.php?action=set_shop_catalog&secret="
                    + java.net.URLEncoder.encode(secretKey, "UTF-8");
            String resp = doPost(urlStr, json);
            plugin.getLogger().info("[商品同步] 已推送游戏内商品目录到PHP"
                    + (resp != null ? " 响应:" + resp : "（无响应）"));
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
                    plugin.getLogger().warning("[Web注册轮询] GET失败 (连续失败" + registerPollFailCount + "次)");
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

                // 在主线程创建游戏账号
                final String fReqId = reqId;
                final String fName = playerName;
                final String fHash = passwordHash;
                final String fSalt = salt;
                final String fEmail = email != null ? email : "";
                final String fIp = ipAddress != null ? ipAddress : "";

                Bukkit.getScheduler().runTask(plugin, () -> {
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

                        // ★ 如果玩家当前在线，自动登录
                        Player onlinePlayer = Bukkit.getPlayer(fName);
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            plugin.getLogger().info("[Web注册] 玩家 " + fName + " 当前在线，执行自动登录");
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                plugin.autoLogin(onlinePlayer, "web_register");
                                onlinePlayer.sendMessage("§a[Sdf1_login] §fWeb注册成功，已自动登录！");
                            });
                        } else {
                            plugin.getLogger().info("[Web注册] 玩家 " + fName + " 当前不在线");
                        }

                        // 同步注册到Web端
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

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        dbMgr.createUser(fName, fHash, fSalt);
                        plugin.getLogger().info("[Web注册] 补全成功: " + fName);

                        // ★ 写入注册IP到login.db
                        if (!fIp.isEmpty()) {
                            dbMgr.setField(fName, "ip_address", fIp);
                            dbMgr.setField(fName, "register_ip", fIp);
                        }

                        Player onlinePlayer = Bukkit.getPlayer(fName);
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                plugin.autoLogin(onlinePlayer, "web_register");
                                onlinePlayer.sendMessage("§a[Sdf1_login] §fWeb注册成功，已自动登录！");
                            });
                        }

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