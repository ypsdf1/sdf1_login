package Sdf1_login;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ 第35批：公告计分板（侧边栏）。
 *
 * <p><b>数据源</b>：{@code plugins/Sdf1_login/公告/公告.md}
 * 头部 {@code ## ----公告区域开始----} ~ {@code ## ----公告区域结束----}
 * 之间的 ```json 代码块。块的键是<b>排序位</b>，值是公告条目。
 *
 * <p><b>规则</b>：
 * <ul>
 *   <li>键 = 排序位（升序，越小越靠上）；键冲突 → <b>自动进位</b>
 *       （往后找第一个空位）。用 JsonReader 流式解析，
 *       重复键不会被 Gson 的 Map 覆盖掉</li>
 *   <li>msg 为空 → 整条跳过（空白键值自动跳过）</li>
 *   <li>msg 超过 15 个字符 → 截断换行（每行最多 15 字，超出的另起一行）</li>
 *   <li>msg 是 http/https 链接 → 整条公告可点击（打开链接，悬停显示完整地址）</li>
 *   <li>color 支持 16 色英文名 / #RRGGBB / r=0 g=0 b=0；
 *       未填或非法 → 回退 MC 原版白色</li>
 * </ul>
 *
 * <p><b>渲染口径（paper-api 26.2 + 服务端 jar 字节码实测）</b>：
 * <ul>
 *   <li>原版侧边栏<b>不解析 entry 里的 § 颜色码</b>（entry 只是纯文本标识），
 *       所以颜色必须走 {@code Score.customName(Component)} 下发；
 *       entry 改用纯 ASCII 唯一串 {@code n0/n1/...}，天然规避同名塌缩。</li>
 *   <li>侧边栏默认 <b>descending（高分在上）</b> → 分数<b>递减</b>赋值，
 *       排序位小的排最上面。</li>
 *   <li>侧边栏<b>最多只显示 15 行</b> → 超出部分丢弃并告警。</li>
 *   <li>数字格式设 {@code NumberFormat.blank()} → 右侧不露分数。</li>
 * </ul>
 *
 * <p><b>与 PVP 计分板共存</b>：PVP 区域 / 竞技场内由 PVP 模块接管计分板，
 * 本类只在玩家空闲（不在 PVP 区域、不在竞技场）时把公告板挂上去。
 */
public class AnnouncementBoard implements Listener {

    private final Main plugin;

    /** 公告配置文件：plugins/Sdf1_login/公告/公告.md */
    private File mdFile;

    /** 上次解析时的文件修改时间（用于热重载检测） */
    private long lastModified = 0L;

    /** 公告区域标记 */
    private static final String ZONE_START = "## ----公告区域开始----";
    private static final String ZONE_END = "## ----公告区域结束----";

    /** 计分板目标名（objective 名） */
    private static final String OBJECTIVE = "sdf1_notice";

    /** 单行最大字符数（超出截断换行） */
    static final int MAX_LINE_CHARS = 15;

    /** 原版侧边栏最多显示的行数（硬限制） */
    static final int MAX_SIDEBAR_ROWS = 15;

    /** 公告板标题 */
    private static final String TITLE = "§6§l服务器公告";

    /** 16 色英文名（与 JsonMessageGui 口径一致） */
    private static final Set<String> NATIVE_COLORS = new LinkedHashSet<>(Arrays.asList(
            "black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white"));

    /** 链接识别（整条 msg 是链接才算） */
    private static final Pattern URL_PATTERN =
            Pattern.compile("^(https?://\\S+)$", Pattern.CASE_INSENSITIVE);

    /** 解析出的公告条目（排序位 → 条目），按排序位升序 */
    private final Map<Integer, Notice> notices = new LinkedHashMap<>();

    /** 单条公告 */
    public static final class Notice {
        /** 排序位 */
        public final int slot;
        /** 原始文本 */
        public final String msg;
        /** 归一化后的颜色（合法色 / null=白色） */
        public final TextColor color;
        /** 链接地址（非链接时 null） */
        public final String url;

        Notice(int slot, String msg, TextColor color, String url) {
            this.slot = slot;
            this.msg = msg;
            this.color = color;
            this.url = url;
        }
    }

    public AnnouncementBoard(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 生命周期 ====================

    /** 初始化：释放模板 → 解析 → 首次渲染 → 启动热重载监听 */
    public void init() {
        try {
            File dir = new File(plugin.getDataFolder(), "公告");
            if (!dir.exists()) dir.mkdirs();
            mdFile = new File(dir, "公告.md");
            if (!mdFile.exists()) {
                try (InputStream in = plugin.getResource("公告/公告.md")) {
                    if (in != null) {
                        Files.copy(in, mdFile.toPath());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[公告板] 模板释放失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[公告板] 初始化失败: " + e.getMessage());
            return;
        }
        reload();
        applyAll();
        startAutoReload();
    }

    /** 启动文件热重载：每 30 秒比对一次修改时间 */
    private void startAutoReload() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (mdFile == null || !mdFile.exists()) return;
            long mt = mdFile.lastModified();
            if (mt == lastModified) return;
            lastModified = mt;
            reloadAndApply();
            plugin.getLogger().info("[公告板] 检测到公告文件变更，已自动重载");
        }, 600L, 600L);
    }

    /** 重新解析公告.md */
    public void reload() {
        List<Notice> list = parse();
        notices.clear();
        for (int i = 0; i < list.size(); i++) {
            Notice n = list.get(i);
            notices.put(n.slot, n);
        }
        if (mdFile != null && mdFile.exists()) {
            lastModified = mdFile.lastModified();
        }
        plugin.getLogger().info("[公告板] 已加载 " + notices.size() + " 条公告");
    }

    /** 重载并立即刷新所有玩家 */
    public void reloadAndApply() {
        reload();
        applyAll();
    }

    public File getMdFile() {
        return mdFile;
    }

    public int getNoticeCount() {
        return notices.size();
    }

    // ==================== 解析 ====================

    /**
     * 解析公告.md 里的 JSON 块。
     *
     * <p>用 {@link JsonReader} 流式解析而非 {@code JsonObject}：
     * 后者会把重复键覆盖掉，「键冲突自动进位」就无从谈起。
     *
     * @return 按排序位升序的公告列表
     */
    private List<Notice> parse() {
        List<Notice> out = new ArrayList<>();
        if (mdFile == null || !mdFile.exists()) return out;
        String text;
        try {
            text = new String(Files.readAllBytes(mdFile.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("[公告板] 读取失败: " + e.getMessage());
            return out;
        }

        // ★ 变量名避开 e/ex（否则遮蔽 catch 块里的 Exception e）
        int zStart = text.indexOf(ZONE_START);
        int zEnd = text.indexOf(ZONE_END);
        if (zStart < 0 || zEnd < 0 || zEnd <= zStart) {
            plugin.getLogger().warning("[公告板] 未找到公告区域标记（"
                    + ZONE_START + " ~ " + ZONE_END + "），公告板为空");
            return out;
        }
        String zone = text.substring(zStart + ZONE_START.length(), zEnd);

        String json = extractJsonBlock(zone);
        if (json == null || json.trim().isEmpty()) {
            plugin.getLogger().warning("[公告板] 公告区域内没有 json 代码块");
            return out;
        }

        // ---- 流式解析：保留重复键 ----
        List<Notice> raw = new ArrayList<>();
        int autoSlot = 1000000;   // 非数字键的兜底排序位（排在数字键之后）
        JsonReader reader = null;
        try {
            reader = new JsonReader(new StringReader(json));
            reader.setLenient(true);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                String msg = null;
                String colorRaw = null;
                if (reader.peek() == JsonToken.NULL) {
                    reader.skipValue();
                } else {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String vk = reader.nextName();
                        if ("msg".equals(vk)) {
                            if (reader.peek() == JsonToken.NULL) reader.skipValue();
                            else msg = reader.nextString();
                        } else if ("color".equals(vk)) {
                            if (reader.peek() == JsonToken.NULL) reader.skipValue();
                            else colorRaw = reader.nextString();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                }

                if (msg == null) continue;
                msg = msg.trim();
                if (msg.isEmpty()) continue;   // 空白键值自动跳过
                if (colorRaw == null) colorRaw = "";
                else colorRaw = colorRaw.trim();

                int slot;
                try {
                    slot = Integer.parseInt(key.trim());
                } catch (Exception ex) {
                    slot = autoSlot++;
                }

                TextColor color = parseColor(colorRaw);
                String url = null;
                Matcher m = URL_PATTERN.matcher(msg);
                if (m.matches()) url = m.group(1);

                raw.add(new Notice(slot, msg, color, url));
            }
            reader.endObject();
        } catch (Exception ex) {
            plugin.getLogger().warning("[公告板] json 解析失败: " + ex.getMessage());
            return out;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { }
            }
        }

        // ---- 排序（手动选择排序，不用 Comparator/lambda）----
        sortBySlot(raw);

        // ---- 排序位冲突 → 自动进位（往后找第一个空位）----
        Set<Integer> used = new LinkedHashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            Notice n = raw.get(i);
            int s2 = n.slot;
            if (s2 < 0) s2 = 0;
            while (used.contains(s2)) s2++;
            used.add(s2);
            out.add(new Notice(s2, n.msg, n.color, n.url));
        }
        return out;
    }

    /** 按排序位升序（稳定，手动冒泡，规避 JDK26 javac lambda 类型推断崩溃） */
    private static void sortBySlot(List<Notice> list) {
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                if (list.get(j).slot < list.get(i).slot) {
                    Notice tmp = list.get(i);
                    list.set(i, list.get(j));
                    list.set(j, tmp);
                }
            }
        }
    }

    /** 从文本中提取 ```json ... ``` 代码块内容 */
    private static String extractJsonBlock(String zone) {
        int fence = zone.indexOf("```");
        if (fence < 0) return null;
        int bodyStart = zone.indexOf('\n', fence);
        if (bodyStart < 0) return null;
        int fence2 = zone.indexOf("```", bodyStart);
        if (fence2 < 0) return null;
        return zone.substring(bodyStart + 1, fence2);
    }

    /**
     * 颜色解析：16 色英文名 / #RRGGBB / r=0 g=0 b=0。
     *
     * @return 合法色；空或非法返回 null（调用方回退白色）
     */
    static TextColor parseColor(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        // 16 色英文名（NamedTextColor.NAMES 的键就是小写下划线名）
        if (NATIVE_COLORS.contains(t)) {
            NamedTextColor c = NamedTextColor.NAMES.value(t);
            return c != null ? c : NamedTextColor.WHITE;
        }
        // #RRGGBB
        if (t.matches("#?[0-9a-f]{6}")) {
            try {
                return TextColor.fromHexString(t.startsWith("#") ? t : "#" + t);
            } catch (Exception ignored) {
                return null;
            }
        }
        // r=0 g=0 b=0
        Matcher m = Pattern.compile(
                        "(?i)(\\d{1,3})[^0-9]*(\\d{1,3})[^0-9]*(\\d{1,3})")
                .matcher(t);
        if (m.find()) {
            try {
                int r = Integer.parseInt(m.group(1));
                int g = Integer.parseInt(m.group(2));
                int b = Integer.parseInt(m.group(3));
                if (r >= 0 && r <= 255 && g >= 0 && g <= 255 && b >= 0 && b <= 255) {
                    return TextColor.color(r, g, b);
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    // ==================== 渲染 ====================

    /**
     * 把公告板刷给单个玩家（PVP 侧边栏管辖范围内自动避让）。
     *
     * @param p 玩家
     */
    public void applyTo(Player p) {
        applyTo(p, false);
    }

    /**
     * 把公告板刷给单个玩家。
     *
     * @param p     玩家
     * @param force true=无视 PVP 区域/竞技场避让（PVP 模块交还侧边栏时用）
     */
    public void applyTo(Player p, boolean force) {
        if (p == null || !p.isOnline()) return;
        if (!force && isInPVPSide(p)) return;
        try {
            ScoreboardManager mgr = Bukkit.getScoreboardManager();
            if (mgr == null) return;
            Scoreboard sb = mgr.getNewScoreboard();
            Objective obj = sb.registerNewObjective(
                    OBJECTIVE, Criteria.DUMMY, legacy(TITLE));
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            // 右侧不显示分数数字（公告板只展示内容）
            try {
                obj.numberFormat(NumberFormat.blank());
            } catch (Throwable ignored) {
            }

            int row = 0;          // 已渲染行数
            int overflow = 0;     // 超出 15 行被丢弃的行数
            for (Notice n : notices.values()) {
                List<String> lines = splitLine(n.msg);
                for (int li = 0; li < lines.size(); li++) {
                    if (row >= MAX_SIDEBAR_ROWS) {
                        overflow++;
                        continue;
                    }
                    // entry 用纯 ASCII 唯一串（避免同名条目塌缩）
                    String entry = "n" + row;
                    Score sc = obj.getScore(entry);
                    // 侧边栏默认 descending（高分在上）→ 分数递减，排序位小的排最上
                    sc.setScore(MAX_SIDEBAR_ROWS - row);
                    try {
                        sc.customName(buildComponent(n, lines.get(li)));
                    } catch (Throwable ignored) {
                    }
                    row++;
                }
            }
            if (overflow > 0) {
                plugin.getLogger().warning("[公告板] 原版侧边栏最多显示 "
                        + MAX_SIDEBAR_ROWS + " 行，已丢弃 "
                        + overflow + " 行公告（请精简公告.md）");
            }
            p.setScoreboard(sb);
        } catch (Exception e) {
            plugin.getLogger().warning("[公告板] 渲染失败: " + e.getMessage());
        }
    }

    /**
     * 构建条目显示组件。
     *
     * <p>原版侧边栏<b>不解析 entry 里的 § 码</b>，所以颜色/点击事件
     * 全部通过 {@code customName} 下发。msg 内部允许写 § 颜色码
     * （legacy 解析），其优先级高于 color 参数 —— 与原版文本语义一致。
     */
    private static Component buildComponent(Notice n, String line) {
        Component comp = legacy(line);
        if (n.color != null) {
            comp = comp.color(n.color);
        }
        if (n.url != null) {
            comp = comp.clickEvent(ClickEvent.openUrl(n.url))
                    .hoverEvent(HoverEvent.showText(
                            Component.text(n.url)));
        }
        return comp;
    }

    /** legacy § 文本 → Component（解析失败退化为纯文本） */
    private static Component legacy(String s) {
        if (s == null || s.isEmpty()) return Component.empty();
        try {
            return LegacyComponentSerializer.legacySection().deserialize(s);
        } catch (Throwable t) {
            return Component.text(s);
        }
    }

    /** 刷新所有在线玩家 */
    public void applyAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyTo(p);
        }
    }

    /**
     * 玩家是否在 PVP 侧边栏管辖范围内（PVP 区域 / PVP 竞技场）。
     * 公告板据此避让 —— PVP 模块的侧边栏优先。
     */
    private boolean isInPVPSide(Player p) {
        if (p == null) return false;
        try {
            if (plugin.getPVPManager() != null
                    && plugin.getPVPManager().isInPVPRegion(p.getName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (plugin.getPVPArenaManager() != null
                    && plugin.getPVPArenaManager().isInPVPArena(p.getName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ==================== 事件 ====================

    /**
     * 切换世界后重刷公告板（部分世界/插件会重置玩家的计分板）。
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> applyTo(p));
    }

    /**
     * 超过 15 个字符 → 截断换行（每行最多 15 字，超出的另起一行）。
     *
     * @return 该条公告占的行
     */
    static List<String> splitLine(String msg) {
        List<String> out = new ArrayList<>();
        if (msg == null || msg.isEmpty()) return out;
        int idx = 0;
        while (idx < msg.length()) {
            int end = Math.min(idx + MAX_LINE_CHARS, msg.length());
            out.add(msg.substring(idx, end));
            idx = end;
        }
        return out;
    }
}
