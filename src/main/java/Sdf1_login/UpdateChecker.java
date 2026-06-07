package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class UpdateChecker {

    // ===== SSL证书信任所有（解决PKIX错误） =====
    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception ignored) {}
    }

    private final Main plugin;
    private final String currentVersion;

    // ===== 仓库信息 =====
    private static final String GITHUB_OWNER = "ypsdf1";
    private static final String GITHUB_REPO = "sdf1_login";
    private static final String GITEE_OWNER = "nihaoshidifu";
    private static final String GITEE_REPO = "sdf1_login";

    // API 地址（使用releases/latest获取最新版本）
    private static final String GITHUB_API =
            "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/" + GITEE_OWNER + "/" + GITEE_REPO + "/releases/latest";

    // 仓库页面链接
    private static final String GITHUB_LINK =
            "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases";
    private static final String GITEE_LINK =
            "https://gitee.com/" + GITEE_OWNER + "/" + GITEE_REPO + "/releases";

    // 单次请求超时（毫秒）
    private static final int TIMEOUT_MS = 5000;

    // 熔断阈值
    private static final int TRIP_THRESHOLD = 5;

    // 熔断后重试间隔（2分钟）
    private static final long RETRY_DELAY_MS = 2 * 60 * 1000;

    // ===== 熔断状态（内存态，重启自动清除） =====
    private volatile boolean circuitTripped = false;
    private volatile int failCount = 0;
    private volatile boolean manualSuccess = false;

    // ===== 自动检测禁用（持久化到文件） =====
    private volatile boolean autoCheckDisabled = false;
    private final File disabledFile;

    public UpdateChecker(Main plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        this.disabledFile = new File(plugin.getDataFolder(), "update_disabled.dat");
        loadDisabledState();
    }

    // ========== 持久化禁用状态 ==========

    private void loadDisabledState() {
        if (disabledFile.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(disabledFile))) {
                String line = br.readLine();
                if (line != null) {
                    autoCheckDisabled = "true".equals(line.trim());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void saveDisabledState() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(disabledFile))) {
            pw.println(autoCheckDisabled ? "true" : "false");
        } catch (Exception ignored) {
        }
    }

    // ========== 公开方法 ==========

    /**
     * onEnable 调用（异步静默）
     * 熔断或禁用则直接跳过
     */
    public void checkOnEnable() {
        if (autoCheckDisabled) return;
        if (circuitTripped) {
            // 熔断状态下，2分钟后重试
            scheduleRetry();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doCheck(null, true));
    }

    /**
     * /sdf1_login update 调用（异步）
     * 忽略熔断和禁用状态，强制检测
     */
    public void checkUpdate(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doCheck(sender, false));
    }

    /**
     * 2分钟后重试
     */
    private void scheduleRetry() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (circuitTripped && !manualSuccess) {
                plugin.getLogger().info("[Sdf1_login] 熔断重试中...");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> doCheck(null, true));
            }
        }, RETRY_DELAY_MS / 50); // ticks
    }

    // ========== 核心检测逻辑 ==========
    private void doCheck(CommandSender sender, boolean isAuto) {

        if (isAuto) {
            if (autoCheckDisabled || circuitTripped) {
                return;
            }
            plugin.getLogger().info("[Sdf1_login] 启动自动检查更新...");
        }

        // ★ 双通道并行执行，一路成功掐断另一路
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<CheckResult> ghResult = new AtomicReference<>();
        AtomicReference<CheckResult> geResult = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(2);

        // 通道1：GitHub
        Thread ghThread = new Thread(() -> {
            if (!cancelled.get()) {
                ghResult.set(tryChannel(GITHUB_API, "GitHub", GITHUB_LINK));
                if (ghResult.get().isSuccess()) {
                    cancelled.set(true); // 成功则取消另一路
                }
            }
            latch.countDown();
        });
        ghThread.setDaemon(true);

        // 通道2：Gitee
        Thread geThread = new Thread(() -> {
            if (!cancelled.get()) {
                geResult.set(tryChannel(GITEE_API, "Gitee", GITEE_LINK));
                if (geResult.get().isSuccess()) {
                    cancelled.set(true); // 成功则取消另一路
                }
            }
            latch.countDown();
        });
        geThread.setDaemon(true);

        ghThread.start();
        geThread.start();

        try {
            latch.await(); // 等待两路都完成
        } catch (InterruptedException ignored) {
        }

        CheckResult gh = ghResult.get();
        CheckResult ge = geResult.get();

        // 选择成功的结果
        CheckResult chosen = null;

        if (gh != null && gh.isSuccess()) {
            chosen = gh;
        } else if (ge != null && ge.isSuccess()) {
            chosen = ge;
        } else {
            // 双通道均失败
            failCount++;
            StringBuilder sb = new StringBuilder();
            if (gh != null && gh.error != null) {
                sb.append("§7GitHub: §c").append(gh.error);
            }
            if (ge != null && ge.error != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("§7Gitee:  §c").append(ge.error);
            }
            if (failCount >= TRIP_THRESHOLD) {
                circuitTripped = true;
                plugin.getLogger().warning(
                        "[Sdf1_login] 更新检测已熔断，连续失败" + failCount + "次");
                // 熔断后安排2分钟后重试
                scheduleRetry();
            }
            if (sender != null) {
                final String msg = sb.toString();
                final boolean tripped = circuitTripped;
                final int fc = failCount;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage("§c§l[更新] 检测失败");
                    sender.sendMessage(msg);
                    sender.sendMessage("§e失败次数: " + fc + "/" + TRIP_THRESHOLD);
                    if (tripped) {
                        sender.sendMessage("§c已熔断，2分钟后自动重试，或重启服务器重置");
                    }
                });
            }
            return;
        }

        // 至少一个通道成功
        failCount = 0;

        // 检查禁用信号
        if (isDisableSignal(chosen.version)) {
            autoCheckDisabled = true;
            saveDisabledState();
            plugin.getLogger().info(
                    "[Sdf1_login] 远程版本信号 \"" + chosen.version + "\"，自动更新检测已禁用");
            if (sender != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage("§e[更新] 远程已禁用自动更新检测"));
            }
            return;
        }

        // 手动调用：成功才解除熔断和禁用
        if (!isAuto) {
            manualSuccess = true; // 标记手动成功，不再执行后续自动检查
            if (circuitTripped) {
                circuitTripped = false;
            }
            if (autoCheckDisabled) {
                autoCheckDisabled = false;
                saveDisabledState();
            }
        }

        final String src = chosen.source;
        final String lnk = chosen.link;
        final String rv = chosen.version;

        // ★ 自动检查也记录日志（含下载链接 + 颜色提醒）
        if (isAuto) {
            boolean hasUpdate = isNewer(rv, currentVersion);
            String status = hasUpdate
                    ? "(有新版本!)"
                    : "(已是最新)";
            // 使用Bukkit.getConsoleSender().sendMessage()输出彩色日志
            Bukkit.getConsoleSender().sendMessage("§a[Sdf1_login] §e更新检测成功 via: " + src
                    + " | 本地: " + currentVersion + " | 远程: " + rv + " §7" + status);
            if (hasUpdate) {
                Bukkit.getConsoleSender().sendMessage("§a[Sdf1_login] §b下载: " + lnk);
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (sender == null) return;

            sender.sendMessage("§e[Sdf1_login] [更新] 检测成功 via: §a" + src);
            sender.sendMessage("§e[Sdf1_login] [更新] 本地版本: §f" + currentVersion);
            sender.sendMessage("§e[Sdf1_login] [更新] 远程版本: §f" + rv);

            if (isNewer(rv, currentVersion)) {
                sender.sendMessage("§c[Sdf1_login] [更新] 发现新版本: " + rv);
                sender.sendMessage("§a[Sdf1_login] 请前往仓库下载更新:");
                sender.sendMessage("§a§l" + lnk);
            } else {
                sender.sendMessage("§a[Sdf1_login] [更新] 已是最新版本");
            }

            // 显示另一通道的失败原因
            CheckResult other = (src.equals("GitHub")) ? ge : gh;
            if (other != null && !other.isSuccess() && other.error != null) {
                sender.sendMessage("§7[Sdf1_login] [更新] " + other.source + " 不可用: " + other.error);
            }
        });
    }

    // ========== 禁用信号判断 ==========

    private boolean isDisableSignal(String version) {
        if (version == null) return false;
        String v = version.trim().toLowerCase();
        return "999".equals(v) || "关闭".equals(v) || "false".equals(v) || "禁用".equals(v);
    }

    // ========== HTTP 请求 ==========

    private static class CheckResult {
        final String version;
        final String source;
        final String link;
        final String error;

        CheckResult(String version, String source, String link, String error) {
            this.version = version;
            this.source = source;
            this.link = link;
            this.error = error;
        }

        boolean isSuccess() {
            return version != null;
        }
    }

    private CheckResult tryChannel(String apiUrl, String sourceName, String link) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();

            if (code == 404) {
                return new CheckResult(null, sourceName, link, sourceName + ": 未找到 (HTTP 404)");
            }
            if (code == 403) {
                return new CheckResult(null, sourceName, link, sourceName + ": 被限流 (HTTP 403)");
            }
            if (code != 200) {
                return new CheckResult(null, sourceName, link, sourceName + ": HTTP " + code);
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String tag = parseTagName(sb.toString());
            if (tag == null) {
                return new CheckResult(null, sourceName, link, sourceName + ": 无法解析版本号");
            }

            return new CheckResult(tag, sourceName, link, null);

        } catch (java.net.SocketTimeoutException e) {
            return new CheckResult(null, sourceName, link, sourceName + ": 连接超时");
        } catch (java.net.ConnectException e) {
            return new CheckResult(null, sourceName, link, sourceName + ": 连接被拒绝");
        } catch (java.net.UnknownHostException e) {
            return new CheckResult(null, sourceName, link, sourceName + ": DNS解析失败");
        } catch (Exception e) {
            return new CheckResult(null, sourceName, link, sourceName + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String parseTagName(String json) {
        // tags API 返回数组: [{"name":"v1.1",...}]
        // releases API 返回对象: {"tag_name":"v1.1"}

        int idx = json.indexOf("\"tag_name\"");
        if (idx >= 0) {
            int colon = json.indexOf(":", idx);
            int q1 = json.indexOf("\"", colon + 1);
            int q2 = json.indexOf("\"", q1 + 1);
            if (q2 > q1) {
                return cleanTag(json.substring(q1 + 1, q2));
            }
        }

        idx = json.indexOf("\"name\"");
        if (idx >= 0) {
            int colon = json.indexOf(":", idx);
            int q1 = json.indexOf("\"", colon + 1);
            int q2 = json.indexOf("\"", q1 + 1);
            if (q2 > q1) {
                return cleanTag(json.substring(q1 + 1, q2));
            }
        }

        return null;
    }

    private String cleanTag(String tag) {
        if (tag.startsWith("v") || tag.startsWith("V")) {
            tag = tag.substring(1);
        }
        return tag.isEmpty() ? null : tag;
    }

    // ========== 版本比较 ==========
    // ★ 只要本地≠远程，就视为有更新

    private boolean isNewer(String remote, String local) {
        if (remote == null || local == null) return false;
        // 版本比较：远程≠本地即视为有更新（用户即将推新版本）
        return !remote.trim().equals(local.trim());
    }

    private int parseNum(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
