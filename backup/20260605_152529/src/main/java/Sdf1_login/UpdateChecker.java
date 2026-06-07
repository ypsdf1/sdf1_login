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

public class UpdateChecker {

    private final Main plugin;
    private final String currentVersion;

    // ===== 仓库信息 =====
    private static final String GITHUB_OWNER =
            "ypsdf1";
    private static final String GITHUB_REPO =
            "sdf1_login";
    private static final String GITEE_OWNER =
            "nihaoshidifu";
    private static final String GITEE_REPO =
            "sdf1_login";


    // API 地址
    private static final String GITHUB_API =
            "https://api.github.com/repos/"
                    + GITHUB_OWNER + "/"
                    + GITHUB_REPO
                    + "/tags";
    private static final String GITEE_API =
            "https://gitee.com/api/v5/repos/"
                    + GITEE_OWNER + "/"
                    + GITEE_REPO
                    + "/tags";

    // 仓库页面链接（给用户看的）
    private static final String GITHUB_LINK =
            "https://github.com/"
                    + GITHUB_OWNER + "/"
                    + GITHUB_REPO + "/releases";
    private static final String GITEE_LINK =
            "https://gitee.com/"
                    + GITEE_OWNER + "/"
                    + GITEE_REPO + "/releases";


    // 单次请求超时（毫秒）
    private static final int TIMEOUT_MS = 3000;

    // 熔断阈值
    private static final int TRIP_THRESHOLD = 5;

    // ===== 熔断状态（内存态，重启自动清除） =====
    private volatile boolean circuitTripped =
            false;
    private volatile int failCount = 0;

    // ===== 自动检测禁用（持久化到文件） =====
    private volatile boolean autoCheckDisabled =
            false;
    private final File disabledFile;

    public UpdateChecker(Main plugin) {
        this.plugin = plugin;
        this.currentVersion =
                plugin.getDescription()
                        .getVersion();
        this.disabledFile = new File(
                plugin.getDataFolder(),
                "update_disabled.dat");
        loadDisabledState();
    }

    // ========== 持久化禁用状态 ==========

    private void loadDisabledState() {
        if (disabledFile.exists()) {
            try (BufferedReader br =
                         new BufferedReader(
                                 new FileReader(
                                         disabledFile))) {
                String line = br.readLine();
                if (line != null) {
                    autoCheckDisabled =
                            "true".equals(
                                    line.trim());
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void saveDisabledState() {
        try (PrintWriter pw =
                     new PrintWriter(
                             new FileWriter(
                                     disabledFile))) {
            pw.println(autoCheckDisabled
                    ? "true" : "false");
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
        if (circuitTripped) return;
        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin,
                        () -> doCheck(null, true));
    }

    /**
     * /sdf1_login update 调用（异步）
     * 忽略熔断和禁用状态，强制检测
     * 只有拿到版号才解除熔断
     */
    public void checkUpdate(
            CommandSender sender) {
        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin,
                        () ->
                                doCheck(sender,
                                        false));
    }

    // ========== 核心检测逻辑 ==========
    private void doCheck(CommandSender sender,
                         boolean isAuto) {

        if (isAuto) {
            if (autoCheckDisabled
                    || circuitTripped) {
                return;
            }
        }

        // 通道1：GitHub
        CheckResult gh =
                tryChannel(GITHUB_API,
                        "GitHub", GITHUB_LINK);

        // 通道2：Gitee
        CheckResult ge =
                tryChannel(GITEE_API,
                        "Gitee", GITEE_LINK);

        // 选择成功的结果
        CheckResult chosen = null;
        String failMsg = null;

        if (gh.isSuccess()) {
            chosen = gh;
        } else if (ge.isSuccess()) {
            chosen = ge;
        } else {
            // 双通道均失败
            failCount++;
            // 拼接两个通道的失败原因
            StringBuilder sb =
                    new StringBuilder();
            if (gh.error != null) {
                sb.append("§7GitHub: §c")
                        .append(gh.error);
            }
            if (ge.error != null) {
                if (sb.length() > 0)
                    sb.append("\n");
                sb.append("§7Gitee:  §c")
                        .append(ge.error);
            }
            if (failCount >= TRIP_THRESHOLD) {
                circuitTripped = true;
                plugin.getLogger().warning(
                        "[Sdf1_login] 更新检测"
                                + "已熔断，连续失败"
                                + failCount + "次");
            }
            if (sender != null) {
                final String msg =
                        sb.toString();
                final boolean tripped =
                        circuitTripped;
                final int fc = failCount;
                Bukkit.getScheduler()
                        .runTask(plugin, () -> {
                            sender.sendMessage(
                                    "§c§l[更新] "
                                            + "检测失败");
                            sender.sendMessage(msg);
                            sender.sendMessage(
                                    "§e失败次数: "
                                            + fc + "/"
                                            + TRIP_THRESHOLD);
                            if (tripped) {
                                sender.sendMessage(
                                        "§c已熔断，"
                                                + "请重启服务器"
                                                + "重置");
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
                    "[Sdf1_login] 远程版本信号"
                            + " \"" + chosen.version
                            + "\"，自动更新检测已禁用");
            if (sender != null) {
                Bukkit.getScheduler()
                        .runTask(plugin, () ->
                                sender.sendMessage(
                                        "§e[更新] 远程已"
                                                + "禁用自动"
                                                + "更新检测"));
            }
            return;
        }

        // 手动调用：成功才解除熔断和禁用
        if (!isAuto) {
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

        Bukkit.getScheduler()
                .runTask(plugin, () -> {
                    if (sender == null) return;

                    sender.sendMessage(
                            "§e[更新] 检测成功"
                                    + " via: §a" + src);
                    sender.sendMessage(
                            "§e[更新] 本地版本:"
                                    + " §f"
                                    + currentVersion);
                    sender.sendMessage(
                            "§e[更新] 远程版本:"
                                    + " §f" + rv);

                    if (isNewer(rv,
                            currentVersion)) {
                        sender.sendMessage(
                                "§c[更新] 发现新"
                                        + "版本: " + rv);
                        sender.sendMessage(
                                "§a请前往仓库下载"
                                        + "更新:");
                        sender.sendMessage(
                                "§a" + lnk);
                    } else {
                        sender.sendMessage(
                                "§a[更新] 已是"
                                        + "最新版本");
                    }

                    // 显示另一通道的失败原因
                    // （仅信息，不影响结果）
                    CheckResult other =
                            (src.equals("GitHub"))
                                    ? ge : gh;
                    if (!other.isSuccess()
                            && other.error != null) {
                        sender.sendMessage(
                                "§7[更新] "
                                        + other.source
                                        + "不可用: "
                                        + other.error);
                    }
                });
    }


    // ========== 禁用信号判断 ==========

    private boolean isDisableSignal(
            String version) {
        if (version == null) return false;
        String v = version.trim().toLowerCase();
        return "999".equals(v)
                || "关闭".equals(v)
                || "false".equals(v)
                || "禁用".equals(v);
    }

    // ========== HTTP 请求（带超时） ==========

    // ========== 检测结果 ==========

    private static class CheckResult {
        final String version;
        final String source;
        final String link;
        final String error;

        CheckResult(String version,
                    String source,
                    String link,
                    String error) {
            this.version = version;
            this.source = source;
            this.link = link;
            this.error = error;
        }

        boolean isSuccess() {
            return version != null;
        }
    }

// ========== HTTP 请求 ==========

    private CheckResult tryChannel(
            String apiUrl,
            String sourceName,
            String link) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection)
                    url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; "
                            + "Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) "
                            + "Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty(
                    "Accept",
                    "*/*");
            conn.setRequestProperty(
                    "Accept-Language",
                    "zh-CN,zh;q=0.9");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();

            if (code == 404) {
                return new CheckResult(null,
                        sourceName, link,
                        sourceName
                                + ": 未找到 (HTTP 404)");
            }
            if (code == 403) {
                return new CheckResult(null,
                        sourceName, link,
                        sourceName
                                + ": 被限流 (HTTP 403)");
            }
            if (code != 200) {
                return new CheckResult(null,
                        sourceName, link,
                        sourceName
                                + ": HTTP " + code);
            }

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    conn
                                            .getInputStream()));
            StringBuilder sb =
                    new StringBuilder();
            String line;
            while ((line = reader.readLine())
                    != null) {
                sb.append(line);
            }
            reader.close();

            String tag =
                    parseTagName(sb.toString());
            if (tag == null) {
                return new CheckResult(null,
                        sourceName, link,
                        sourceName
                                + ": 无法解析版本号");
            }

            return new CheckResult(tag,
                    sourceName, link, null);

        } catch (java.net
                         .SocketTimeoutException e) {
            return new CheckResult(null,
                    sourceName, link,
                    sourceName + ": 连接超时");
        } catch (java.net.ConnectException e) {
            return new CheckResult(null,
                    sourceName, link,
                    sourceName
                            + ": 连接被拒绝");
        } catch (java.net
                         .UnknownHostException e) {
            return new CheckResult(null,
                    sourceName, link,
                    sourceName
                            + ": DNS解析失败");
        } catch (Exception e) {
            return new CheckResult(null,
                    sourceName, link,
                    sourceName + ": "
                            + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }



    /**
     * 从 JSON 中提取 tag_name
     */
    private String parseTagName(String json) {
        // tags API 返回数组: [{"name":"v1.1",...}]
        // releases API 返回对象: {"tag_name":"v1.1"}

        // 尝试 tag_name（releases格式）
        int idx =
                json.indexOf("\"tag_name\"");
        if (idx >= 0) {
            int colon =
                    json.indexOf(":", idx);
            int q1 =
                    json.indexOf("\"", colon + 1);
            int q2 =
                    json.indexOf("\"", q1 + 1);
            if (q2 > q1) {
                return cleanTag(
                        json.substring(
                                q1 + 1, q2));
            }
        }

        // 尝试 "name"（tags格式）
        idx = json.indexOf("\"name\"");
        if (idx >= 0) {
            int colon =
                    json.indexOf(":", idx);
            int q1 =
                    json.indexOf("\"", colon + 1);
            int q2 =
                    json.indexOf("\"", q1 + 1);
            if (q2 > q1) {
                return cleanTag(
                        json.substring(
                                q1 + 1, q2));
            }
        }

        return null;
    }

    private String cleanTag(String tag) {
        if (tag.startsWith("v")
                || tag.startsWith("V")) {
            tag = tag.substring(1);
        }
        return tag.isEmpty() ? null : tag;
    }


    // ========== 版本比较 ==========

    private boolean isNewer(String remote,
                            String local) {
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int ri = i < r.length
                    ? parseNum(r[i]) : 0;
            int li = i < l.length
                    ? parseNum(l[i]) : 0;
            if (ri > li) return true;
            if (ri < li) return false;
        }
        return false;
    }

    private int parseNum(String s) {
        try {
            return Integer.parseInt(
                    s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}
