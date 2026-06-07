package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFilterManager {

    private final JavaPlugin plugin;
    private final List<String> whitelistUrls =
            new ArrayList<>();
    private final Set<String> whitelistPlayers =
            new TreeSet<>(
                    String.CASE_INSENSITIVE_ORDER);
    private final Map<Integer, String>
            punishmentRules =
            new LinkedHashMap<>();
    private final Map<Integer, Integer>
            punishmentDurations =
            new LinkedHashMap<>();
    private final Map<String, Integer>
            violationCount =
            new ConcurrentHashMap<>();
    private final Map<String, Long>
            mutedPlayers =
            new ConcurrentHashMap<>();
    private final Map<String, String> messages =
            new LinkedHashMap<>();
    private boolean notifyAdmin = true;
    private boolean notifyAll = false;
    private int muteDuration = 300;
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }


    private static final Pattern URL_PATTERN =
            Pattern.compile(
                    "(?i)(?:https?://\\S+)"
                            + "|(?:(?:[a-zA-Z0-9]"
                            + "(?:[a-zA-Z0-9\\-]*"
                            + "[a-zA-Z0-9])?\\.)+"
                            + "[a-zA-Z]{2,})"
                            + "(?:/\\S*)?");
    private static final Pattern DOT_VARIANTS =
            Pattern.compile(
                    "[\uff0e\u3002\u2025\u2026\u00b7]");
    private static final Pattern COLOR_CODES =
            Pattern.compile(
                    "\u00a7[0-9a-fk-orA-FK-OR]");

    private static final Set<String> FILE_EXT;
    static {
        Set<String> s = new HashSet<>(
                Arrays.asList(
                        "txt", "yml", "yaml",
                        "json", "xml", "java",
                        "class", "jar", "png",
                        "jpg", "jpeg", "gif",
                        "bmp", "svg", "ico",
                        "webp", "mp3", "mp4",
                        "avi", "mkv", "wav",
                        "flac", "ogg", "zip",
                        "rar", "7z", "tar",
                        "gz", "cfg", "conf",
                        "ini", "log", "db",
                        "sql", "sh", "bat",
                        "html", "htm", "css",
                        "js", "md", "pdf",
                        "dll", "so", "exe",
                        "bin", "c", "cpp",
                        "py", "rb", "go",
                        "rs", "kt", "lua",
                        "php", "asp", "jsp"));
        FILE_EXT = Collections.unmodifiableSet(s);
    }

    public ChatFilterManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ========== 配置加载 ==========

    public void loadConfig() {
        whitelistUrls.clear();
        whitelistPlayers.clear();
        punishmentRules.clear();
        punishmentDurations.clear();
        messages.clear();

        File f = new File(
                plugin.getDataFolder(), "chat.txt");
        if (!f.exists()) writeDefaultFile(f);
        try (BufferedReader r =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(f),
                                     StandardCharsets.UTF_8))) {
            String section = "";
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF"))
                    line = line.substring(1);
                String t = line.trim();

                if (t.isEmpty()
                        || t.startsWith("#"))
                    continue;
                if (t.equals("白名单")
                        || t.equals("白名单:")) {
                    section = "whitelist";
                    continue;
                }
                if (t.equals("处罚规则")
                        || t.equals("处罚规则:")) {
                    section = "punishment";
                    continue;
                }
                if (t.equals("白名单玩家")
                        || t.equals("白名单玩家:")) {
                    section = "players";
                    continue;
                }
                if (t.contains(":")) {
                    String[] kv = t.split(":", 2);
                    String k = kv[0].trim();
                    String v = kv[1].trim();
                    if (equals("启用过滤")
                            || equals("enabled")) {
                        enabled = parseBool(v);
                        continue;
                    }

                    if (k.equals("通知管理员")) {
                        notifyAdmin = parseBool(v);
                        continue;
                    }
                    if (k.equals("全服通报")) {
                        notifyAll = parseBool(v);
                        continue;
                    }
                    if (k.equals("禁言时长")) {
                        try {
                            muteDuration =
                                    Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                }
                switch (section) {
                    case "whitelist":
                        whitelistUrls.add(
                                t.toLowerCase());
                        break;
                    case "punishment":
                        String[] parts = t.split(":");
                        if (parts.length >= 2) {
                            try {
                                int cnt =
                                        Integer.parseInt(
                                                parts[0]
                                                        .trim());
                                String type =
                                        parts[1].trim()
                                                .toLowerCase();
                                int dur =
                                        parts.length >= 3
                                                ? Integer.parseInt(
                                                parts[2]
                                                .trim())
                                                : muteDuration;
                                punishmentRules.put(
                                        cnt, type);
                                punishmentDurations.put(
                                        cnt, dur);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "players":
                        whitelistPlayers.add(t);
                        break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Sdf1_chat] 配置加载失败: "
                            + e.getMessage());
        }

        // 从消息.txt读取chat_开头的消息
        loadMessages();
    }

    private void loadMessages() {
        File msgFile = new File(
                plugin.getDataFolder(),
                "消息.txt");
        if (!msgFile.exists()) return;
        try (BufferedReader mr =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(
                                             msgFile),
                                     StandardCharsets.UTF_8))) {
            String mline;
            while ((mline = mr.readLine()) != null) {
                mline = mline.trim();
                if (mline.isEmpty()
                        || mline.startsWith("#"))
                    continue;
                // 支持 = 和 : 两种分隔符
                int eqIdx = mline.indexOf('=');
                int coIdx = mline.indexOf(':');
                int splitIdx = -1;
                if (eqIdx >= 0 && coIdx >= 0) {
                    splitIdx = Math.min(eqIdx, coIdx);
                } else if (eqIdx >= 0) {
                    splitIdx = eqIdx;
                } else if (coIdx >= 0) {
                    splitIdx = coIdx;
                }
                if (splitIdx < 0) continue;
                String k = mline.substring(0,
                        splitIdx).trim();
                String v = mline.substring(
                        splitIdx + 1).trim();
                if (k.startsWith("chat_")) {
                    messages.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
    }


    private void writeDefaultFile(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# Sdf1_chat 聊天过滤配置");
            pw.println("启用过滤: " + enabled);
            pw.println("通知管理员: true");
            pw.println("全服通报: false");
            pw.println("禁言时长: 300");
            pw.println();
            pw.println("白名单:");
            pw.println("*.minecraft.net");
            pw.println("baidu.com");
            pw.println("github.com");
            pw.println();
            pw.println("处罚规则:");
            pw.println("1:warn:0");
            pw.println("2:warn:0");
            pw.println("3:mute:300");
            pw.println("5:mute:600");
            pw.println("8:kick:0");
            pw.println("10:ban:0");
            pw.println();
            pw.println("白名单玩家:");
        } catch (IOException ignored) {
        }
    }

    public void saveConfig() {
        File f = new File(
                plugin.getDataFolder(), "chat.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# Sdf1_chat 聊天过滤配置");
            pw.println("通知管理员: " + notifyAdmin);
            pw.println("全服通报: " + notifyAll);
            pw.println("禁言时长: " + muteDuration);
            pw.println();
            pw.println("白名单:");
            for (String u : whitelistUrls)
                pw.println(u);
            pw.println();
            pw.println("处罚规则:");
            for (Map.Entry<Integer, String> entry
                    : punishmentRules.entrySet()) {
                int dur = punishmentDurations
                        .getOrDefault(entry.getKey(),
                                muteDuration);
                pw.println(entry.getKey() + ":"
                        + entry.getValue()
                        + ":" + dur);
            }
            pw.println();
            pw.println("白名单玩家:");
            for (String p : whitelistPlayers)
                pw.println(p);
        } catch (IOException e) {
            plugin.getLogger().severe(
                    "[Sdf1_chat] 保存失败: "
                            + e.getMessage());
        }
    }

    // ========== 消息 ==========

    public String msg(String key,
                      String... args) {
        String template = messages.getOrDefault(
                key, key);
        for (int i = 0; i < args.length - 1;
             i += 2) {
            template = template.replace(
                    "{" + args[i] + "}",
                    args[i + 1]);
        }
        return template;
    }

    // ========== URL检测 ==========

    private String normalizeMessage(String msg) {
        msg = COLOR_CODES.matcher(msg)
                .replaceAll("");
        msg = DOT_VARIANTS.matcher(msg)
                .replaceAll(".");
        return msg;
    }

    public List<String> extractUrls(
            String message) {
        String normalized =
                normalizeMessage(message);
        List<String> urls = new ArrayList<>();
        Matcher m = URL_PATTERN
                .matcher(normalized);
        while (m.find()) {
            String url = m.group().toLowerCase();
            url = url.replaceAll(
                    "[\\s.,;!?，。；！？、）》」』】"
                            + "\\]\\[\\(（]+$",
                    "");
            if (!url.isEmpty()) urls.add(url);
        }
        return urls;
    }

    public boolean isWhitelisted(String url) {
        String clean = url
                .replaceAll("^https?://", "")
                .toLowerCase();
        int colon = clean.indexOf(':');
        int slash = clean.indexOf('/');
        if (colon > 0 && (slash < 0
                || colon < slash)) {
            clean = clean.substring(0, colon);
        }
        slash = clean.indexOf('/');
        if (slash > 0) {
            clean = clean.substring(0, slash);
        }
        for (String entry : whitelistUrls) {
            String e = entry.toLowerCase().trim();
            if (e.startsWith("*.")) {
                String suffix = e.substring(1);
                if (clean.endsWith(suffix)
                        || clean.equals(
                        suffix.substring(1)))
                    return true;
            } else {
                if (clean.equals(e)
                        || clean.endsWith(
                        "." + e))
                    return true;
            }
        }
        return false;
    }

    public boolean isLikelyDomain(String url) {
        int lastDot = url.lastIndexOf('.');
        if (lastDot < 0
                || lastDot >= url.length() - 1)
            return false;
        String ext = url.substring(lastDot + 1)
                .toLowerCase();
        return !FILE_EXT.contains(ext);
    }

    // ========== 玩家操作 ==========

    public boolean isMuted(String name) {
        Long expiry = mutedPlayers.get(name);
        if (expiry == null) return false;
        if (System.currentTimeMillis()
                >= expiry) {
            mutedPlayers.remove(name);
            return false;
        }
        return true;
    }

    public String fmtDuration(int seconds) {
        if (seconds <= 0) return "0秒";
        int day = seconds / 86400;
        int hr = (seconds % 86400) / 3600;
        int min = (seconds % 3600) / 60;
        int sec = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (day > 0) sb.append(day).append("天");
        if (hr > 0)
            sb.append(hr).append("小时");
        if (min > 0)
            sb.append(min).append("分");
        if (sec > 0 || sb.length() == 0)
            sb.append(sec).append("秒");
        return sb.toString();
    }

    public void applyPunishment(Player player,
                                int violation) {
        String type = "warn";
        int duration = muteDuration;
        int maxRule = 0;
        for (Map.Entry<Integer, String> entry
                : punishmentRules.entrySet()) {
            if (violation >= entry.getKey()
                    && entry.getKey() > maxRule) {
                maxRule = entry.getKey();
                type = entry.getValue();
                duration = punishmentDurations
                        .getOrDefault(
                                entry.getKey(),
                                muteDuration);
            }
        }
        if (type.equals("mute")) {
            if (isMuted(player.getName()))
                return;
            mutedPlayers.put(player.getName(),
                    System.currentTimeMillis()
                            + (long) duration
                            * 1000L);
        }
        final String fType = type;
        final int fDur = duration;
        final int fV = violation;
        final String fName = player.getName();
        Bukkit.getScheduler().runTask(plugin,
                () -> {
                    Player p = Bukkit.getPlayer(fName);
                    if (p == null) return;
                    switch (fType) {
                        case "warn":
                            // 警告不处罚，只提示
                            break;
                        case "mute":
                            p.sendMessage(
                                    msg("chat_muted"));
                            break;
                        case "kick":
                            p.kickPlayer(
                                    msg("chat_muted"));
                            break;
                        case "ban":
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    "ban " + fName
                                            + " §c多次发送违规链接");
                            break;
                        case "banip":
                            InetSocketAddress addr =
                                    p.getAddress();
                            if (addr != null) {
                                String ip = addr
                                        .getAddress()
                                        .getHostAddress();
                                Bukkit.dispatchCommand(
                                        Bukkit.getConsoleSender(),
                                        "ban-ip " + ip
                                                + " §c发送违规链接");
                            }
                            break;
                    }
                });
    }

    public void unmutePlayer(String name) {
        mutedPlayers.remove(name);
    }

    public void resetPlayer(String name) {
        mutedPlayers.remove(name);
        violationCount.remove(name);
    }

    public boolean isPlayerWhitelisted(
            String name) {
        return whitelistPlayers.contains(name);
    }

    public boolean isNotifyAdmin() {
        return notifyAdmin;
    }

    public boolean isNotifyAll() {
        return notifyAll;
    }

    public int getViolationCount(String name) {
        return violationCount
                .getOrDefault(name, 0);
    }

    public void incrementViolation(
            String name) {
        violationCount.put(name,
                getViolationCount(name) + 1);
    }

    public List<String> getWhitelistUrls() {
        return whitelistUrls;
    }

    public Set<String> getWhitelistPlayers() {
        return whitelistPlayers;
    }

    public Map<Integer, String>
    getPunishmentRules() {
        return punishmentRules;
    }

    public Map<Integer, Integer>
    getPunishmentDurations() {
        return punishmentDurations;
    }

    public Map<String, Long>
    getMutedPlayers() {
        return mutedPlayers;
    }

    public void setNotifyAdmin(boolean v) {
        notifyAdmin = v;
    }

    public void setNotifyAll(boolean v) {
        notifyAll = v;
    }

    public void setMuteDuration(int v) {
        muteDuration = v;
    }

    public void addUrl(String url) {
        whitelistUrls.add(
                url.toLowerCase().trim());
        saveConfig();
    }

    public boolean removeUrl(String url) {
        boolean r = whitelistUrls.remove(
                url.toLowerCase().trim());
        saveConfig();
        return r;
    }

    public void addWhitelistPlayer(
            String name) {
        whitelistPlayers.add(name);
        saveConfig();
    }

    public boolean removeWhitelistPlayer(
            String name) {
        boolean r =
                whitelistPlayers.remove(name);
        saveConfig();
        return r;
    }

    public void cleanExpired() {
        long now = System.currentTimeMillis();
        mutedPlayers.entrySet().removeIf(
                e -> e.getValue() <= now);
    }

    private boolean parseBool(String v) {
        String s = v.toLowerCase().trim();
        return s.equals("true")
                || s.equals("on")
                || s.equals("yes")
                || s.equals("1")
                || s.contains("开启")
                || s.contains("启用")
                || s.contains("是");
    }
}
