package Sdf1_login;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 维护模式（白名单）管理器
 *
 * 配置文件：plugins/Sdf1_login/白名单.txt，分两个段落：
 *   [控制段]   启用 / 开始时间 / 结束时间 / 时长 / 消息 等键值对
 *   [白名单段]  每行一个玩家名（也可用逗号分隔多个）
 *
 * 特性：
 *  - 24 小时硬封顶：无论怎么配，单次维护窗口最长 24 小时，到点自动结束
 *  - 多格式时间：10位秒级时间戳 / 13位毫秒 / yyyy-MM-dd HH:mm / 2026年9月28日14时30分 / 只写 HH:mm(今天)
 *  - 未写时长与结束时间时默认 24 小时
 *  - 消息支持 & 与 § 颜色码、整段 JSON 文本组件、\n 与 &lt;br&gt; 换行
 *  - 变量：username / 用户名、starttime / 开始时间、endtime / 结束时间
 *  - 玩家登录(异步预登录)、加入、退出时热重载配置，改完文件即生效，无需重启
 *  - 拦截发生在异步预登录阶段，不给未授权客户端发送任何世界/区块数据包
 *  - 独立于 MC 自带白名单，互不影响
 */
public class MaintenanceManager implements Listener {

    /** 24小时硬封顶 */
    private static final long MAX_WINDOW_MS = 24L * 60L * 60L * 1000L;
    /** 未配置时长/结束时间时的默认窗口 */
    private static final long DEFAULT_WINDOW_MS = MAX_WINDOW_MS;

    private static final String DEFAULT_MESSAGE =
            "&c&l[服务器维护] &7服务器正在维护中，请稍后再试&7（结束时间: &e{结束时间}&7）";

    private static final Pattern NUM_PAT = Pattern.compile("\\d+");
    private static final Pattern DUR_PAT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z\\u4e00-\\u9fa5]*)");
    private static final Pattern AMP_COLOR_PAT = Pattern.compile("&([0-9a-fk-orA-FK-OR])");

    private final Main plugin;
    private final File file;

    /** 解析互斥：异步登录线程与主线程(join/quit)都可能触发热重载 */
    private final Object lock = new Object();

    private volatile long lastMtime = -1L;
    private volatile boolean enabled = false;
    private volatile long windowStart = 0L;
    private volatile long windowEnd = 0L;
    private volatile boolean opBypass = true;
    private volatile String rawMessage = DEFAULT_MESSAGE;
    private volatile Set<String> whitelistLower = Collections.emptySet();

    /** 未显式写「开始时间」时记住本轮起点，避免热重载把窗口反复清零 */
    private long implicitStart = 0L;
    /** 到期日志只打一次 */
    private volatile boolean expiryLogged = false;
    /** OP 查询缓存（异步线程里只查一次） */
    private final ConcurrentHashMap<String, Boolean> opCache = new ConcurrentHashMap<>();

    public MaintenanceManager(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "白名单.txt");
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("[维护模式] 创建数据目录失败: " + e.getMessage());
        }
        if (!file.exists()) {
            writeDefaultFile();
        }
        reload(true);
    }

    // ==================== 对外接口 ====================

    /** 维护窗口是否正在生效 */
    public boolean isActive() {
        if (!enabled) return false;
        long now = System.currentTimeMillis();
        return now >= windowStart && now < windowEnd;
    }

    /** 玩家是否在白名单内（大小写不敏感） */
    public boolean isWhitelisted(String name) {
        if (name == null || name.isEmpty()) return false;
        return whitelistLower.contains(name.toLowerCase(Locale.ROOT));
    }

    public long getWindowStart() {
        return windowStart;
    }

    public long getWindowEnd() {
        return windowEnd;
    }

    public Set<String> getWhitelist() {
        return whitelistLower;
    }

    // ==================== 配置文件 ====================

    private void writeDefaultFile() {
        String tpl = String.join("\n",
                "# =========================================================",
                "#  维护模式（白名单）配置文件",
                "#  修改保存后无需重启：玩家尝试登录 / 加入 / 退出时自动热加载",
                "#",
                "#  [控制段]   用「键 = 值」写服务器开关、时间与提示语",
                "#  [白名单段]  严格一行一个玩家名（一行只写一个，不支持逗号/空格分隔）",
                "#               （标题行漏写也能兜底收录，但请尽量保留 [白名单] 标题）",
                "#",
                "#  可用键：",
                "#    启用     = true / false",
                "#    开始时间 = 时间戳 / 2026-09-28 20:00 / 2026年9月28日20时00分（留空=立即开始）",
                "#    结束时间 = 同上（留空则按时长或默认24小时计算）",
                "#    时长     = 24h / 12小时 / 90分钟（单次维护最长 24 小时，硬封顶）",
                "#    OP可绕过 = true / false（OP 是否可以绕过维护直接进服）",
                "#    消息     = 支持 &a§a 颜色码、整段 JSON（对象 {...} 或数组 [...]）、\\n 与 <br> 换行",
                "#               可用变量 {username} {starttime} {endtime}（带花括号，JSON 内同样生效）",
                "# =========================================================",
                "",
                "# ===== 控制段 =====",
                "启用 = false",
                "开始时间 =",
                "结束时间 =",
                "时长 = 24h",
                "OP可绕过 = true",
                "消息 = " + DEFAULT_MESSAGE,
                "",
                "# ===== 白名单段 =====",
                "[白名单]",
                "");
        try {
            Files.write(file.toPath(), tpl.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("[维护模式] 已生成默认配置: " + file.getAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().warning("[维护模式] 写入默认配置失败: " + e.getMessage());
        }
    }

    /** 玩家登录/加入/退出时调用：文件变了就重新解析 */
    public void reloadIfChanged() {
        if (!file.exists()) return;
        long m;
        try {
            m = file.lastModified();
        } catch (Exception e) {
            return;
        }
        if (m == lastMtime) return;
        reload(true);
    }

    private void reload(boolean force) {
        synchronized (lock) {
            long m;
            try {
                m = file.lastModified();
            } catch (Exception e) {
                return;
            }
            if (!force && m == lastMtime) return;

            String text;
            try {
                text = decode(Files.readAllBytes(file.toPath()));
            } catch (IOException e) {
                plugin.getLogger().warning("[维护模式] 读取配置失败: " + e.getMessage());
                return;
            }

            // 行扫描抽成 static parseConfig（含段标题丢失兜底 / 条目清洗），便于单测
            ParsedConfig pc = parseConfig(text, plugin.getLogger());
            Map<String, String> ctl = pc.ctl;
            Set<String> wl = pc.wl;

            boolean wasActive = isActive();
            boolean wasEnabled = this.enabled;

            String enRaw = pick(ctl,
                    "启用", "开启", "开关", "维护", "enable", "enabled", "maintenance", "on");
            boolean en = toBool(enRaw, false);
            Long st = parseTime(pick(ctl, "开始时间", "开始", "起始时间", "start", "starttime", "begintime"));
            Long enT = parseTime(pick(ctl, "结束时间", "结束", "截止时间", "end", "endtime"));
            Long dur = parseDuration(pick(ctl, "时长", "持续时间", "持续", "duration", "hours", "小时"));
            String opRaw = pick(ctl, "OP可绕过", "op可绕过", "OP绕过", "管理员绕过",
                    "opbypass", "op_bypass", "op", "bypass");
            boolean op = toBool(opRaw, true);
            if (enRaw != null && !isBoolWord(enRaw)) {
                plugin.getLogger().warning("[维护模式] 「启用」的值不是可识别的布尔词: ["
                        + enRaw + "]，按 false 处理（请写 true / false）");
            }
            if (opRaw != null && !isBoolWord(opRaw)) {
                plugin.getLogger().warning("[维护模式] 「OP可绕过」的值不是可识别的布尔词: ["
                        + opRaw + "]，按 false 处理（请写 true / false）");
            }
            String msg = pick(ctl, "消息", "提示", "维护消息", "踢出消息", "message", "msg", "kickmessage");
            if (msg == null || msg.isEmpty()) msg = DEFAULT_MESSAGE;

            long now = System.currentTimeMillis();
            if (st == null) {
                // 没写开始时间：沿用上一轮未过期的起点，否则从现在开始
                if (implicitStart > 0L && implicitStart + MAX_WINDOW_MS > now) {
                    st = implicitStart;
                } else {
                    st = now;
                }
            }
            implicitStart = st;

            long end;
            if (enT != null) {
                end = enT;
            } else if (dur != null) {
                end = st + dur;
            } else {
                end = st + DEFAULT_WINDOW_MS;
            }
            // ★ 24小时硬封顶
            if (end > st + MAX_WINDOW_MS) end = st + MAX_WINDOW_MS;
            if (end < st) end = st;

            this.enabled = en;
            this.windowStart = st;
            this.windowEnd = end;
            this.opBypass = op;
            this.rawMessage = msg;
            Set<String> lowered = new LinkedHashSet<>();
            for (String n : wl) lowered.add(n.toLowerCase(Locale.ROOT));
            this.whitelistLower = Collections.unmodifiableSet(lowered);
            this.lastMtime = m;
            if (wasEnabled && !en) {
                this.implicitStart = 0L;   // 手动关掉后，下次开启重新计时
            }
            this.expiryLogged = false;
            this.opCache.clear();

            boolean nowActive = isActive();
            if (nowActive && !wasActive) {
                plugin.getLogger().info("[维护模式] ★ 维护已开启，白名单 " + whitelistLower.size()
                        + " 人，窗口 " + fmt(windowStart) + " ~ " + fmt(windowEnd));
            } else if (nowActive) {
                plugin.getLogger().info("[维护模式] 配置已热重载，白名单 " + whitelistLower.size()
                        + " 人，窗口 " + fmt(windowStart) + " ~ " + fmt(windowEnd));
            } else if (wasActive) {
                plugin.getLogger().info("[维护模式] 维护已结束");
            }
            // ★ 直接把读到的条目与控制段键打出来，线上「配了却进不去」时一眼能对账
            if (nowActive && !whitelistLower.isEmpty()) {
                plugin.getLogger().info("[维护模式] 白名单条目: [" + summarize(whitelistLower) + "]");
            }
            if (nowActive && whitelistLower.isEmpty()) {
                plugin.getLogger().warning("[维护模式] ⚠ 维护已开启但白名单为 0 人！段标题[白名单]识别="
                        + pc.sawWhiteHeader
                        + (opBypass ? "，OP 仍可绕过" : "，且 OP 不可绕过：所有玩家都会被拦在外面")
                        + "；控制段读到的键: " + ctl.keySet() + "，请检查 [白名单] 段标题与条目写法");
            }
        }
    }

    // ==================== 登录拦截 ====================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        // 登录阶段热重载：改完文件直接重连即可生效
        try {
            reloadIfChanged();
        } catch (Throwable t) {
            // 配置读取异常不能影响正常登录
        }

        if (!isActive()) {
            // 到期后只提示一次
            if (enabled && !expiryLogged && System.currentTimeMillis() >= windowEnd) {
                expiryLogged = true;
                plugin.getLogger().info("[维护模式] 维护窗口已到期（" + fmt(windowEnd) + "），自动结束");
            }
            return;
        }

        String name = e.getName();
        if (isWhitelisted(name)) return;
        if (opBypass && isOp(name)) {
            plugin.getLogger().info("[维护模式] 放行OP: " + name);
            return;
        }

        // ★ 无感化拦截：在异步预登录阶段直接断开，客户端只看到自定义提示，
        //   服务端不会为他分配世界/区块，也不产生多余的网络往返
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, buildMessage(name));
        plugin.getLogger().info("[维护模式] 已拦截未在白名单的玩家: " + name
                + "（当前白名单 " + whitelistLower.size() + " 人）");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        try {
            reloadIfChanged();
        } catch (Throwable ignored) {
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        try {
            reloadIfChanged();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 提示消息 ====================

    private Component buildMessage(String playerName) {
        String msg = this.rawMessage;
        long s = this.windowStart;
        long e = this.windowEnd;
        msg = replaceVar(msg, new String[]{"username", "用户名", "player", "玩家名"}, playerName);
        msg = replaceVar(msg, new String[]{"starttime", "开始时间", "start"}, fmt(s));
        msg = replaceVar(msg, new String[]{"endtime", "结束时间", "end"}, fmt(e));
        return format(msg);
    }

    private static String replaceVar(String msg, String[] keys, String value) {
        if (msg == null || msg.isEmpty()) return msg;
        String v = value == null ? "" : value;
        // JSON 消息只替换 {占位符}/%占位符%/<占位符>，不做裸子串替换：
        // 否则正文里的 end/start/player 等英文词会被占位符值覆盖（"end soon" -> "2026-09-28 10:00 soon"）
        boolean bareOk = !looksLikeJson(msg);
        for (String k : keys) {
            msg = msg.replace("{" + k + "}", v);
            msg = msg.replace("%" + k + "%", v);
            msg = msg.replace("<" + k + ">", v);
            if (bareOk) msg = msg.replace(k, v);
        }
        return msg;
    }

    /**
     * 整段是否为 JSON 文本组件：形如 {...} 或 [...]，且含双引号。
     * 「含双引号」用于把 [玩家名]、[公告] 这类普通文本挡在 JSON 分支之外——
     * Gson 默认宽松解析会把 [玩家名] 当字符串数组吃掉，导致正文被吞。
     */
    private static boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        int n = t.length();
        if (n < 2 || t.indexOf('"') < 0) return false;
        char f = t.charAt(0);
        char l = t.charAt(n - 1);
        return (f == '{' && l == '}') || (f == '[' && l == ']');
    }

    /** JSON 内的 &lt;br&gt; 变体（<br> <br/> <br /> </br>，大小写不敏感） */
    private static final Pattern BR_IN_JSON_PAT = Pattern.compile("(?i)</?br\\s*/?>");

    /**
     * 把 JSON 文本里的字面 &lt;br&gt; 换成 JSON 转义换行（反斜杠 + n 两字符）。
     * 必须是两字符序列：真实换行在 JSON 字符串里非法，写进去整段就解析失败了。
     */
    private static String replaceBrInJson(String json) {
        return BR_IN_JSON_PAT.matcher(json).replaceAll("\\\\n");
    }

    /**
     * 消息格式化：
     *  1) 整段是 JSON（对象 {...} 或顶层数组 [...]，后者是 tellraw 常见写法）
     *     → 按文本组件解析（支持 text/color/extra 等）；字面 &lt;br&gt; 会先换成 JSON 转义换行
     *  2) 否则处理 &lt;br&gt; 与字面 \n 换行、&amp; 颜色码与 § 颜色码
     */
    private static Component format(String msg) {
        if (msg == null) msg = "";
        String trimmed = msg.trim();
        if (looksLikeJson(trimmed)) {
            try {
                // ★ JSON 字符串里的字面 <br>/<br/> 同样要变成换行，否则标签会原样显示给玩家。
                //   Gson 只认 JSON 转义（反斜杠 + n），所以替换成两字符序列，而不是真实换行
                //   （真实换行在 JSON 字符串里是非法字符，会让整段解析失败退回纯文本）。
                return GsonComponentSerializer.gson().deserialize(replaceBrInJson(trimmed));
            } catch (Throwable t) {
                // 不是合法 JSON，退回普通文本处理
            }
        }
        String s = msg;
        s = s.replace("<br/>", "\n").replace("<br />", "\n").replace("<br>", "\n");
        s = s.replace("</br>", "\n").replace("<BR>", "\n").replace("<Br>", "\n");
        s = s.replace("\\n", "\n");
        s = AMP_COLOR_PAT.matcher(s).replaceAll("§$1");

        String[] lines = s.split("\n", -1);
        Component out = Component.empty();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out = out.append(Component.newline());
            out = out.append(LegacyComponentSerializer.legacySection().deserialize(lines[i]));
        }
        return out;
    }

    private static String fmt(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
        } catch (Exception e) {
            return String.valueOf(ms);
        }
    }

    // ==================== 工具 ====================

    private boolean isOp(String name) {
        Boolean cached = opCache.get(name);
        if (cached != null) return cached;
        boolean r = false;
        try {
            OfflinePlayer p = Bukkit.getOfflinePlayer(name);
            r = p.isOp();
        } catch (Throwable t) {
            r = false;
        }
        opCache.put(name, r);
        return r;
    }

    /** 段落头：[白名单] / 【白名单】 / 白名单 / ===== 白名单段 ===== / 玩家白名单 / [WHITELIST]（含 = 的行一律不是段落头） */
    private static boolean isWhitelistHeader(String line) {
        String s = line.trim();
        if (s.isEmpty()) return false;
        // ★ 注释形式的段标题也算：用户常把「[白名单]」行删掉，只留下「# ===== 白名单段 =====」这条注释。
        //   先剥掉 # /// 注释前缀再判；键值对类注释（含 =）仍被下面的硬门槛挡住，不会误判成段落头。
        while (s.startsWith("#") || s.startsWith("//")) {
            s = s.substring(s.startsWith("//") ? 2 : 1).trim();
        }
        if (s.isEmpty()) return false;
        // ★ 硬门槛：先剥掉首尾装饰符号（=====、----、::: 等），再看中间是否还剩「=」。
        //   剩下的 = 说明这是控制段的键值对（如「挂机白名单 = xxx」），绝不是段落头；
        //   只有装饰等号的 =====白名单段===== 剥完就干净了，不会被误杀。
        //   没有这道门槛时，控制键一旦被误判成段落头，后续所有行都滑进白名单段，
        //   「启用 = true」就读不到 → 维护开关直接失效。
        String body = s.replaceAll("^[=\\-_:：·*#\\s]+|[=\\-_:：·*#\\s]+$", "");
        if (body.indexOf('=') >= 0) return false;
        // 剥离各类括号与装饰符号（含全角【】〔〕「」『』（）），只留标题正文
        String stripped = s.replaceAll("[\\[\\]\\u3010\\u3011\\u3014\\u3015\\u300c\\u300d\\u300e\\u300f"
                + "\\uff08\\uff09()\\-=_:：·*#\\s]+", "");
        String low = stripped.toLowerCase(Locale.ROOT);
        // 精确匹配常见写法；再兜底 endsWith 兼容「玩家白名单」「ALLOWLIST」这类变体标题
        //（有了上面的 = 门槛，endsWith 已经不会再吞掉控制段键值对）
        return low.equals("白名单") || low.equals("白名单段") || low.equals("白名单列表")
                || low.equals("允许名单") || low.equals("whitelist") || low.equals("allowlist")
                || low.endsWith("白名单") || low.endsWith("whitelist") || low.endsWith("allowlist");
    }

    // ==================== 配置文本解析（static，便于单测） ====================

    /** parseConfig 的结果：控制段键值对 + 白名单条目 + 是否见过 [白名单] 段标题 */
    static final class ParsedConfig {
        final Map<String, String> ctl;
        final Set<String> wl;
        final boolean sawWhiteHeader;

        ParsedConfig(Map<String, String> ctl, Set<String> wl, boolean sawWhiteHeader) {
            this.ctl = ctl;
            this.wl = wl;
            this.sawWhiteHeader = sawWhiteHeader;
        }
    }

    /**
     * 解析配置文本。三层兜底，防止管理员把自己关在门外：
     *  1) 段标题判断在注释跳过之前——「# ===== 白名单段 =====」这种注释形式的标题也认
     *  2) 段外的无键值行（既没有 = 也没有 ：）按玩家名收录——标题行被删时靠它救
     *  3) 条目先清洗零宽字符 / 行内注释 / 列表前缀再收录，避免肉眼看着对却匹配不上
     */
    static ParsedConfig parseConfig(String text, Logger log) {
        Map<String, String> ctl = new LinkedHashMap<>();
        Set<String> wl = new LinkedHashSet<>();
        boolean inWhite = false;
        boolean sawHeader = false;
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (isWhitelistHeader(line)) {
                inWhite = true;
                sawHeader = true;
                continue;
            }
            if (line.startsWith("#") || line.startsWith("//")) continue;
            if (inWhite) {
                addWhitelistEntry(wl, line, log, null);
            } else {
                int eq = line.indexOf('=');
                if (eq < 0) eq = line.indexOf('：');
                if (eq < 0) {
                    addWhitelistEntry(wl, line, log,
                            "该行不在 [白名单] 段内也不是键值对，已按玩家名兜底收录");
                    continue;
                }
                String k = normKey(line.substring(0, eq));
                if (k.isEmpty()) continue;
                ctl.put(k, line.substring(eq + 1).trim());
            }
        }
        if (!sawHeader && !wl.isEmpty()) {
            warn(log, "未识别到 [白名单] 段标题，已按「无键值=玩家名」兜底收录 "
                    + wl.size() + " 个条目");
        }
        return new ParsedConfig(ctl, wl, sawHeader);
    }

    /** 清洗并收录一条白名单条目（note 为附加告警说明，可为 null） */
    private static void addWhitelistEntry(Set<String> wl, String raw, Logger log, String note) {
        String s = stripInvisible(raw);
        if (!s.equals(raw)) {
            warn(log, "白名单条目含不可见字符（零宽/全角空格/不间断空格），已清洗: ["
                    + raw + "] -> [" + s + "]");
        }
        int c = indexOfInlineComment(s);
        if (c >= 0) {
            warn(log, "白名单条目的行内注释已忽略: " + s.substring(c).trim());
            s = s.substring(0, c).trim();
        }
        // 列表装饰前缀：- > * · •（合法 MC 名不会以这些字符开头）
        String noDecor = s.replaceFirst("^[-*>\\u00b7\\u2022]\\s+", "");
        if (!noDecor.equals(s)) {
            warn(log, "白名单条目前缀符号已忽略: [" + s + "] -> [" + noDecor + "]");
            s = noDecor;
        }
        s = s.trim();
        if (s.isEmpty()) return;
        if (note != null) warn(log, note + ": " + raw);
        // 仍坚持「一行一个玩家名」：整行收录，但格式不规范必须能被看见
        if (s.indexOf(',') >= 0 || s.indexOf('\uFF0C') >= 0 || s.indexOf('\u3001') >= 0
                || s.indexOf(';') >= 0 || s.indexOf('\uFF1B') >= 0 || s.indexOf(' ') >= 0
                || s.indexOf('\u3000') >= 0 || s.indexOf('\t') >= 0) {
            warn(log, "白名单条目不符合「一行一个玩家名」，已按整行收录: " + s);
        }
        wl.add(s);
    }

    /** 去掉肉眼看不见却会让匹配失败的字符：零宽/BOM/不间断空格/全角空格 */
    private static String stripInvisible(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF\\u00A0\\u3000]", "");
    }

    /** 行内注释起点（# / ＃ / //），返回 -1 表示没有 */
    private static int indexOfInlineComment(String s) {
        int h = s.indexOf('#');
        if (h < 0) h = s.indexOf('\uFF03');
        if (h < 0) h = s.indexOf("//");
        return h <= 0 ? -1 : h;
    }

    private static void warn(Logger log, String msg) {
        if (log != null) log.warning("[维护模式] " + msg);
    }

    /** 布尔词识别（与 toBool 口径一致），用于把 flase / ture 这类拼写错误提示出来 */
    private static boolean isBoolWord(String v) {
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return false;
        return s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on")
                || s.equals("1") || s.equals("是") || s.equals("开") || s.equals("开启") || s.equals("启用")
                || s.equals("false") || s.equals("no") || s.equals("n") || s.equals("off")
                || s.equals("0") || s.equals("否") || s.equals("关") || s.equals("关闭") || s.equals("停用");
    }

    /** 白名单条目摘要（最多 20 个），日志里直接对账用 */
    private static String summarize(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String n : names) {
            if (i == 20) {
                sb.append(", …共").append(names.size()).append("人");
                return sb.toString();
            }
            if (i++ > 0) sb.append(", ");
            sb.append(n);
        }
        return sb.toString();
    }

    private static String normKey(String k) {
        if (k == null) return "";
        return k.trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("　", "");
    }

    private static String pick(Map<String, String> ctl, String... keys) {
        for (String k : keys) {
            String v = ctl.get(normKey(k));
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    private static boolean toBool(String v, boolean def) {
        if (v == null) return def;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on")
                || s.equals("1") || s.equals("是") || s.equals("开") || s.equals("开启") || s.equals("启用");
    }

    /**
     * 时间解析，支持：
     *  - 10位秒级时间戳 / 13位毫秒时间戳
     *  - 2026-09-28 20:00 / 2026/9/28 20:00:33 / 2026.09.28
     *  - 2026年9月28日20时30分 / 2026年9月28日 20:30
     *  - 只写 20:00（今天该时刻）
     * 解析失败返回 null
     */
    static Long parseTime(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "");
        if (s.isEmpty()) return null;
        if (s.matches("\\d{10}")) return Long.parseLong(s) * 1000L;
        if (s.matches("\\d{13}")) return Long.parseLong(s);

        List<Long> n = new ArrayList<>();
        Matcher m = NUM_PAT.matcher(s);
        while (m.find()) {
            try {
                n.add(Long.parseLong(m.group()));
            } catch (Exception ignore) {
            }
        }
        if (n.isEmpty()) return null;

        String first = String.valueOf(n.get(0));
        if (first.length() == 10) return n.get(0) * 1000L;
        if (first.length() == 13) return n.get(0);

        if (n.size() == 2 && n.get(0) <= 23 && n.get(1) <= 59) {
            LocalDateTime now = LocalDateTime.now();
            return now.withHour(n.get(0).intValue()).withMinute(n.get(1).intValue())
                    .withSecond(0).withNano(0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (n.size() < 3) return null;

        int y = n.get(0).intValue();
        int mo = n.get(1).intValue();
        int d = n.get(2).intValue();
        int h = n.size() > 3 ? n.get(3).intValue() : 0;
        int mi = n.size() > 4 ? n.get(4).intValue() : 0;
        int se = n.size() > 5 ? n.get(5).intValue() : 0;
        if (y < 1970 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31
                || h > 23 || mi > 59 || se > 59) {
            return null;
        }
        try {
            return LocalDateTime.of(y, mo, d, h, mi, se)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** 时长解析：24 / 24h / 12小时 / 90分钟 / 2天，默认单位小时 */
    static Long parseDuration(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        Matcher m = DUR_PAT.matcher(s);
        if (!m.find()) return null;
        double v;
        try {
            v = Double.parseDouble(m.group(1));
        } catch (Exception e) {
            return null;
        }
        String unit = m.group(2) == null ? "" : m.group(2);
        long ms;
        if (unit.contains("毫秒")) ms = 1L;
        else if (unit.startsWith("s") || unit.contains("秒")) ms = 1000L;
        else if (unit.startsWith("m") || unit.contains("分")) ms = 60_000L;
        else if (unit.startsWith("d") || unit.contains("天")) ms = 86_400_000L;
        else ms = 3_600_000L;
        long r = (long) (v * ms);
        return r <= 0 ? null : r;
    }

    /** 优先按 UTF-8 严格解码，失败则按 GBK（Windows 记事本默认编码） */
    private static String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            dec.decode(ByteBuffer.wrap(bytes));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            try {
                return new String(bytes, Charset.forName("GBK"));
            } catch (Exception e2) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }
}
