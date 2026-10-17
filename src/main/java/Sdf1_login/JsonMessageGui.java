package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /json 消息构造器 GUI（26批 任务170）。
 *
 * <p><b>功能</b>：
 * <ul>
 *   <li>27 格小箱子面板：3 种模式（tellraw 原生 JSON 消息 / 大标题 / 小标题）</li>
 *   <li>颜色行 = 自定义颜色输入 + 调色板（解码配置.md 自定义色优先，内置常用色补足）</li>
 *   <li>文字经聊天栏捕获（GUI 内开不了聊天，先关面板再输入）</li>
 *   <li>发送 = 以玩家自己身份 dispatchCommand（tellraw/title 天然受原版权限约束，
 *       OP/管理员可全服广播，普通玩家与直接敲原版指令行为一致）</li>
 * </ul>
 *
 * <p><b>触发</b>：/json 指令；或原版 title/tellraw 报错时由 Main.onUnknownCommand
 * 调 {@link #tryTakeover} 接管并提取用户原文预填。
 *
 * <p><b>颜色合法性</b>（仅枚举 MC 原生支持，Wiki 所列）：
 * 16 色英文名 / #RRGGBB 十六进制 / R G B 数值；其余一律「颜色配置无效」。
 *
 * <p><b>解码配置.md</b>：启动自动释放到插件目录，表格 split 取 cells[length-1]，
 * 「配置区域开始/结束」标记之间逐行解析；无效色收集后提示。
 */
public class JsonMessageGui implements Listener {

    private final Main plugin;
    static final String TITLE = "§6§lJSON消息构造器";

    // ==================== 模式 ====================
    private static final int MODE_TELLRAW = 0;
    private static final int MODE_TITLE = 1;
    private static final int MODE_SUBTITLE = 2;
    // ★ 30批：actionbar 模式（玩家 /title @a actionbar 报错被接管）
    private static final int MODE_ACTIONBAR = 3;

    // ==================== MC 原生 16 色（Wiki 所列） ====================
    private static final Set<String> NATIVE_COLORS = new LinkedHashSet<>(Arrays.asList(
            "black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white"));

    /** 内置常用色（调色板补位用） */
    private static final List<String> BUILTIN_COMMON = Arrays.asList(
            "red", "gold", "yellow", "green",
            "aqua", "blue", "light_purple", "white");

    // ==================== 格位布局 ====================
    private static final int SLOT_MODE_TELLRAW = 1;
    private static final int SLOT_MODE_TITLE = 3;
    private static final int SLOT_MODE_SUBTITLE = 5;
    // ★ 30批：actionbar 模式项（空位 24，避开风格键 / 调色板 / 发送键）
    private static final int SLOT_MODE_ACTIONBAR = 24;
    private static final int SLOT_HELP = 7;
    private static final int SLOT_CUSTOM_COLOR = 9;
    private static final int SLOT_PALETTE_FIRST = 10;   // 10-17 共 8 格调色板
    private static final int SLOT_PALETTE_LAST = 17;
    private static final int SLOT_PREVIEW = 21;
    private static final int SLOT_INPUT_TEXT = 22;
    private static final int SLOT_CLEAR = 25;
    private static final int SLOT_SEND = 26;

    // ★ 27批：样式按钮（原空闲格）—— 加粗/斜体/下划线/删除线
    private static final int SLOT_STYLE_BOLD = 18;
    private static final int SLOT_STYLE_ITALIC = 19;
    private static final int SLOT_STYLE_UNDERLINE = 20;
    private static final int SLOT_STYLE_STRIKE = 23;

    // ==================== 每玩家会话 ====================
    private static final class Session {
        int mode = MODE_TELLRAW;
        /** 已确认的文本段（含 § 颜色码） */
        final StringBuilder text = new StringBuilder();
        /** 当前等待聊天捕获的输入类型：null=无 */
        String capture; // "text" | "customcolor"
        /**
         * ★ 27批：先点颜色、后输入内容时的待应用颜色（MC legacy 码，如 "§a"）。
         *   线上事故：颜色只是 append 进 text，"先选色再打字"时色码落在文字前面
         *   被 toTellrawJson 切成一个空 extra 段 → 屏幕上完全看不到颜色（显示白）。
         *   改为暂存颜色，等文字进来时前置到该段文字之前。
         */
        String pendingColor;
        /** 已开启的样式开关：1=加粗 2=斜体 4=下划线 8=删除线 */
        int styleFlags;
        Session(int mode) { this.mode = mode; }
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** 调色板（md 自定义色优先，内置常用色补足到 8 格） */
    private final List<String> palette = new ArrayList<>();
    /** md 中解析出的无效色（提示用） */
    private final List<String> invalidColors = new ArrayList<>();

    public JsonMessageGui(Main plugin) {
        this.plugin = plugin;
        loadPalette();
    }

    // ==================== 解码配置.md 解析 ====================

    /** 重新加载调色板：md 自定义色（配置区域标记间表格 cells[length-1]）+ 内置补足 */
    private void loadPalette() {
        palette.clear();
        invalidColors.clear();
        File f = new File(plugin.getDataFolder(), "json解码配置/解码配置.md");
        if (f.exists()) {
            boolean inRegion = false;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.startsWith("##")) {
                        // 标题行携带区域开关
                        inRegion = t.contains("配置区域开始");
                        if (t.contains("配置区域结束")) inRegion = false;
                        continue;
                    }
                    if (!inRegion) continue;
                    if (!t.startsWith("|")) continue;
                    String[] cells = t.split("\\|");
                    if (cells.length < 2) continue;
                    String raw = cells[cells.length - 1].trim();
                    // 表头/分隔行跳过
                    if (raw.isEmpty() || raw.equals("---")
                            || raw.equals("颜色代码")) continue;
                    if (isValidColor(raw)) {
                        String norm = normalizeColor(raw);
                        if (!palette.contains(norm)) palette.add(norm);
                    } else {
                        invalidColors.add(raw);
                        plugin.getLogger().warning(
                                "[JSON构造器] 颜色配置无效: " + raw);
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().warning(
                        "[JSON构造器] 解码配置.md 读取失败: " + ex.getMessage());
            }
        }
        // 内置常用色补足到 8 格
        for (String c : BUILTIN_COMMON) {
            if (palette.size() >= SLOT_PALETTE_LAST - SLOT_PALETTE_FIRST + 1) break;
            if (!palette.contains(c)) palette.add(c);
        }
    }

    // ==================== 颜色校验（仅 MC 原生支持） ====================

    /** 16色英文名 / #RRGGBB / R G B 数值 → 合法；其余 false */
    static boolean isValidColor(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return false;
        if (NATIVE_COLORS.contains(t)) return true;
        // 十六进制 #RRGGBB（也接受不带#的6位hex）
        if (t.matches("#?[0-9a-f]{6}")) return true;
        // R G B：R255 G128 B0（大小写不敏感，逗号/空格分隔均可）
        if (t.matches("(?i)r\\s*\\d{1,3}\\s*[,;，；]?\\s*g\\s*\\d{1,3}\\s*[,;，；]?\\s*b\\s*\\d{1,3}")) {
            Matcher m = Pattern.compile("(?i)(\\d{1,3})[^0-9]*(\\d{1,3})[^0-9]*(\\d{1,3})")
                    .matcher(t);
            if (m.find()) {
                return inByte(m.group(1)) && inByte(m.group(2)) && inByte(m.group(3));
            }
        }
        return false;
    }

    private static boolean inByte(String n) {
        int v = Integer.parseInt(n);
        return v >= 0 && v <= 255;
    }

    /** 合法色归一化：hex 统一带#小写；RGB 转 #RRGGBB；英文名小写 */
    static String normalizeColor(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (NATIVE_COLORS.contains(t)) return t;
        if (t.matches("#?[0-9a-f]{6}")) {
            return t.startsWith("#") ? t : ("#" + t);
        }
        Matcher m = Pattern.compile("(?i)(\\d{1,3})[^0-9]*(\\d{1,3})[^0-9]*(\\d{1,3})")
                .matcher(t);
        if (m.find()) {
            return String.format("#%02x%02x%02x",
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
        }
        return t;
    }

    // ==================== 入口 ====================

    /** /json 指令入口 */
    public void open(Player p) {
        Session s = sessions.computeIfAbsent(
                p.getUniqueId(), k -> new Session(MODE_TELLRAW));
        s.capture = null;
        if (!invalidColors.isEmpty()) {
            p.sendMessage("§e[JSON构造器] §7解码配置.md 中 §c"
                    + invalidColors.size() + " §7个颜色配置无效（仅支持 MC 原生色名/十六进制/RGB），已忽略");
        }
        p.openInventory(build(s));
    }

    /**
     * 原版 title/tellraw 报错接管（挂在 Main.onUnknownCommand）。
     *
     * <p>★ 29批（用户要求，回退28批「不直接打开」的决定）：Main 侧 suppressUnknown
     * 压掉原生报错后由本方法接管 ——「预填原文 + 标准最小化写法示例 + /json CLI
     * 可点击超链接 + 直接 openInventory 打开构造器」。
     *
     * @param body 已去掉开头斜杠的命令行
     * @return true = 已预填并接管（原生报错由 Main 压制），false = 不归本构造器管
     */
    public boolean tryTakeover(Player p, String body) {
        if (body.isEmpty()) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        int mode;
        String payload;
        if (lower.startsWith("tellraw ")) {
            mode = MODE_TELLRAW;
            // 28批：目标选择器（@a/@p/玩家名）是指令参数，不是用户文本
            payload = stripTarget(body.substring(8).trim());
        } else if (lower.startsWith("title ")) {
            // title <target> title|subtitle|actionbar <json...>
            String rest = body.substring(6).trim();
            int sp1 = rest.indexOf(' ');
            if (sp1 < 0) return false;
            String afterTarget = rest.substring(sp1 + 1).trim();
            int sp2 = afterTarget.indexOf(' ');
            String kind = (sp2 < 0 ? afterTarget : afterTarget.substring(0, sp2))
                    .toLowerCase(Locale.ROOT);
            if (kind.equals("title")) {
                mode = MODE_TITLE;
            } else if (kind.equals("subtitle")) {
                mode = MODE_SUBTITLE;
            } else if (kind.equals("actionbar")) {
                // ★ 30批：actionbar 也接管（不再「return false 交还原版报错」）
                mode = MODE_ACTIONBAR;
            } else {
                // 未知 kind（比如 /title @a clear、reset）→ 非 JSON 载荷，交还原版
                return false;
            }
            payload = sp2 < 0 ? "" : afterTarget.substring(sp2 + 1).trim();
        } else {
            return false;
        }

        // 提取用户原文（JSON 里 text 字段，或原样串）
        String extracted = extractUserText(payload);
        Session s = sessions.computeIfAbsent(p.getUniqueId(), k -> new Session(mode));
        s.mode = mode;
        s.capture = null;
        s.text.setLength(0);
        s.pendingColor = null;
        s.styleFlags = 0;
        if (!extracted.isEmpty()) s.text.append(extracted);

        // ★ 29批：延迟 1 tick —— 先让 suppressUnknown 压掉的原生报错彻底落地，
        //   再发「标准最小化示例 + /json CLI 连接」并直接打开构造器（回退28批不打开的决定）
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            p.sendMessage(net.kyori.adventure.text.Component
                    .text("[JSON构造器] ", net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .append(net.kyori.adventure.text.Component.text(
                            "原文已预填，构造器已打开。标准写法示例：",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(net.kyori.adventure.text.Component.text(
                            exampleCmdOf(mode),
                            net.kyori.adventure.text.format.NamedTextColor.WHITE))
                    .append(net.kyori.adventure.text.Component.newline())
                    .append(net.kyori.adventure.text.Component.text(
                            "CLI 连接（备用入口）：",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(net.kyori.adventure.text.Component.text(
                            "[/json]",
                            net.kyori.adventure.text.format.NamedTextColor.YELLOW,
                            net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/json"))
                            .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                                    net.kyori.adventure.text.Component.text("打开 JSON 构造器（原文已预填）")))));
            open(p);
        });
        return true;
    }

    /** 29批：各模式的标准最小化写法示例（压制原生报错后由本构造器给出） */
    private static String exampleCmdOf(int mode) {
        switch (mode) {
            case MODE_TITLE:     return "/title @a title {\"text\":\"标题\"}";
            case MODE_SUBTITLE:  return "/title @a subtitle {\"text\":\"副标题\"}";
            // ★ 30批：actionbar 示例
            case MODE_ACTIONBAR: return "/title @a actionbar {\"text\":\"提示\"}";
            default:             return "/tellraw @a {\"text\":\"你好\"}";
        }
    }

    /**
     * 28批：从 tellraw 载荷里剥掉目标选择器——原版结构 tellraw <target> <json>，
     * @a/@p/玩家名属于指令参数，不能被构造成用户文本（用户实测 @a 被塞进了 text）。
     */
    private static String stripTarget(String payload) {
        if (payload.isEmpty() || payload.startsWith("{")) return payload;
        int sp = payload.indexOf(' ');
        if (sp < 0) return looksLikeSelector(payload) ? "" : payload;
        String first = payload.substring(0, sp);
        String rest = payload.substring(sp + 1).trim();
        if (looksLikeSelector(first)) return rest; // tellraw @a 文本
        if (rest.startsWith("{")) return rest;     // tellraw 玩家名 {"text":...}
        return payload;
    }

    /** 28批：@a/@p/@e/@r/@s/@n（可带 [..] 过滤参数）→ 是选择器，不是正文 */
    private static boolean looksLikeSelector(String t) {
        return t.length() >= 2 && t.charAt(0) == '@'
                && Character.isLetter(t.charAt(1))
                && (t.length() == 2 || (t.charAt(2) == '[' && t.endsWith("]")));
    }

    /** 从 tellraw/title 的 JSON 载荷里提取用户原文 text；解析不了就原样返回 */
    static String extractUserText(String payload) {
        if (payload == null || payload.isEmpty()) return "";
        // 最常见 {"text":"..."} 或 {"text":"...","extra":[...]}
        Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(payload);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String seg = m.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
            sb.append(seg);
        }
        if (sb.length() > 0) return sb.toString();
        // 不是JSON（比如玩家直接打了中文）→ 原样
        if (!payload.startsWith("{")) return payload;
        return "";
    }

    // ==================== GUI 构建 ====================

    private Inventory build(Session s) {
        Inventory inv = Bukkit.createInventory(null, 27, TITLE);

        inv.setItem(SLOT_MODE_TELLRAW, modeItem(
                Material.PAPER, "tellraw 原生JSON消息",
                s.mode == MODE_TELLRAW,
                "全服广播一条 JSON 消息", "对应 /tellraw @a ..."));
        inv.setItem(SLOT_MODE_TITLE, modeItem(
                Material.GOLD_BLOCK, "大标题",
                s.mode == MODE_TITLE,
                "全服屏幕中央大字", "对应 /title @a title ..."));
        inv.setItem(SLOT_MODE_SUBTITLE, modeItem(
                Material.NAME_TAG, "小标题",
                s.mode == MODE_SUBTITLE,
                "全服屏幕中央小字（大字下方）", "对应 /title @a subtitle ..."));
        // ★ 30批：actionbar 模式项（槽位 24，避开其它键）
        inv.setItem(SLOT_MODE_ACTIONBAR, modeItem(
                Material.CLOCK, "底部提示栏(actionbar)",
                s.mode == MODE_ACTIONBAR,
                "屏幕底部行动条（聊天栏上方）", "对应 /title @a actionbar ..."));

        inv.setItem(SLOT_HELP, simple(Material.BOOK,
                "§f使用说明",
                "§71. 选模式 → 2. 点「输入内容」",
                "§7在聊天栏打字（自动收回面板）",
                "§3点颜色§7：空内容时记住颜色，打字时带上；",
                "§7已有内容时整段重新染色",
                "§3四个样式按钮§7：加粗/斜体/下划线/删除线",
                "§7发送成功会把 JSON 原样回给你（仅本人可见）",
                "§7发送以你自己的身份执行原版指令，",
                "§7权限与直接敲 /tellraw、/title 完全一致"));

        inv.setItem(SLOT_CUSTOM_COLOR, simple(Material.BLAZE_POWDER,
                "§b自定义颜色",
                "§7点击后在聊天栏输入颜色：",
                "§f#RRGGBB §7/ §fR255 G128 B0 §7/ §fred",
                "§c仅支持 MC 原生色，否则提示「颜色配置无效」"));

        // 调色板 10-17
        for (int i = 0; i < (SLOT_PALETTE_LAST - SLOT_PALETTE_FIRST + 1); i++) {
            int slot = SLOT_PALETTE_FIRST + i;
            if (i < palette.size()) {
                String c = palette.get(i);
                inv.setItem(slot, simple(colorIcon(c),
                        colorName(c) + "§7：点击上色",
                        "§7代码 §f" + c));
            } else {
                inv.setItem(slot, simple(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        "§8空位", "§8（解码配置.md 可加自定义色）"));
            }
        }

        // ★ 27批：样式开关 4 连（点一下切换开/关，直接作用于后续文字）
        inv.setItem(SLOT_STYLE_BOLD, styleItem(Material.BLAZE_POWDER,
                "加粗", 1, s.styleFlags));
        inv.setItem(SLOT_STYLE_ITALIC, styleItem(Material.FEATHER,
                "斜体", 2, s.styleFlags));
        inv.setItem(SLOT_STYLE_UNDERLINE, styleItem(Material.NAME_TAG,
                "下划线", 4, s.styleFlags));
        inv.setItem(SLOT_STYLE_STRIKE, styleItem(Material.STICK,
                "删除线", 8, s.styleFlags));

        inv.setItem(SLOT_PREVIEW, previewItem(s));
        inv.setItem(SLOT_INPUT_TEXT, simple(Material.WRITABLE_BOOK,
                "§a输入内容",
                "§7点击后在聊天栏输入文字",
                "§7（不会真的发到公屏）",
                (s.pendingColor != null
                        ? "§7当前色：§f" + legacyPreview(s.pendingColor)
                        : "§8尚未选色（可先选颜色再打字）")));
        inv.setItem(SLOT_CLEAR, simple(Material.LAVA_BUCKET,
                "§c清空内容", "§7清空已输入的文本"));
        inv.setItem(SLOT_SEND, simple(Material.EMERALD_BLOCK,
                "§a§l发送", "§7以你的身份执行对应原版指令"));
        return inv;
    }

    private ItemStack previewItem(Session s) {
        String shown = s.text.length() == 0 ? "§8（空）" : clip(s.text.toString(), 40);
        return simple(Material.HONEYCOMB, "§e当前内容预览", "§r" + shown,
                styleStateLine(s));
    }

    /** 样式开启动态一行（让玩家看得见当前样式开关） */
    private static String styleStateLine(Session s) {
        StringBuilder sb = new StringBuilder("§8样式: ");
        int f = s.styleFlags;
        sb.append((f & 1) != 0 ? "§a粗" : "§7粗").append(' ')
          .append((f & 2) != 0 ? "§a斜" : "§7斜").append(' ')
          .append((f & 4) != 0 ? "§a下划" : "§7下划").append(' ')
          .append((f & 8) != 0 ? "§a删除" : "§7删除");
        return sb.toString();
    }

    /** legacy § 码 → 可读标签（仅 GUI 文案用） */
    private static String legacyPreview(String legacy) {
        if (legacy == null || legacy.isEmpty()) return "（无）";
        return legacy.replace('§', '&');
    }

    /** 把纯文本按 legacy 色码渲染（仅用于聊天栏回显） */
    private static String colorPreview(String plain, String colorCode) {
        if (colorCode == null || colorCode.isEmpty()) return "§r";
        return colorCode + "§r";
    }

    private static String clip(String s, int max) {
        String flat = s.replace('§', '&');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static String colorName(String code) {
        if (code.startsWith("#")) return "§" + hexToClosestLegacy(code) + "自定义" + code;
        // 16色名 → 对应 legacy 色显示
        String legacy = nativeToLegacy(code);
        return "§" + legacy + code;
    }

    private static String nativeToLegacy(String name) {
        switch (name) {
            case "black": return "0";
            case "dark_blue": return "1";
            case "dark_green": return "2";
            case "dark_aqua": return "3";
            case "dark_red": return "4";
            case "dark_purple": return "5";
            case "gold": return "6";
            case "gray": return "7";
            case "dark_gray": return "8";
            case "blue": return "9";
            case "green": return "a";
            case "aqua": return "b";
            case "red": return "c";
            case "light_purple": return "d";
            case "yellow": return "e";
            default: return "f";
        }
    }

    /** hex → 最接近的 legacy 色码（图标显示用） */
    private static char hexToClosestLegacy(String hex) {
        String h = hex.substring(1);
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        if (r < 64 && g < 64 && b < 64) return '8';
        if (r > 192 && g > 192 && b > 192) return 'f';
        if (r > g && r > b) return g > 128 ? '6' : 'c';
        if (g > r && g > b) return 'a';
        if (b > r && b > g) return '9';
        return '7';
    }

    /** 调色板色对应的图标材料（16色用染料；hex用玻璃板） */
    private static Material colorIcon(String code) {
        switch (code) {
            case "black": return Material.BLACK_DYE;
            case "dark_blue": return Material.BLUE_DYE;
            case "dark_green": return Material.GREEN_DYE;
            case "dark_aqua": return Material.CYAN_DYE;
            case "dark_red": return Material.RED_DYE;
            case "dark_purple": return Material.PURPLE_DYE;
            case "gold": return Material.ORANGE_DYE;
            case "gray": return Material.GRAY_DYE;
            case "dark_gray": return Material.LIGHT_GRAY_DYE;
            case "blue": return Material.LIGHT_BLUE_DYE;
            case "green": return Material.LIME_DYE;
            case "aqua": return Material.LIGHT_BLUE_DYE;
            case "red": return Material.RED_DYE;
            case "light_purple": return Material.MAGENTA_DYE;
            case "yellow": return Material.YELLOW_DYE;
            case "white": return Material.WHITE_DYE;
            default: return Material.WHITE_STAINED_GLASS_PANE;
        }
    }

    /** 样式开关按钮：亮起=已开，灰暗=未开 */
    private static ItemStack styleItem(Material mat, String name, int bit, int flags) {
        boolean on = (flags & bit) != 0;
        return simple(mat,
                (on ? "§a✔ " : "§7") + (on ? "§l" : "") + name,
                on ? "§a已开启 §7— 再次点击关闭" : "§e点击开启",
                "§7对应 JSON 字段 §f" + styleJsonField(bit),
                "§7样式作用于之后输入的文字");
    }

    private static String styleJsonField(int bit) {
        switch (bit) {
            case 1: return "\"bold\":true";
            case 2: return "\"italic\":true";
            case 4: return "\"underlined\":true";
            default: return "\"strikethrough\":true";
        }
    }

    private static ItemStack modeItem(Material mat, String name, boolean selected,
                                      String... lore) {
        ItemStack it = simple(mat, (selected ? "§a✔ " : "§7") + name, lore);
        ItemMeta meta = it.getItemMeta();
        List<String> l = new ArrayList<>(meta.getLore() == null
                ? java.util.Collections.emptyList() : meta.getLore());
        l.add(selected ? "§a（当前模式）" : "§e点击切换");
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack simple(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null && lore.length > 0) {
            meta.setLore(Arrays.asList(lore));
        }
        it.setItemMeta(meta);
        return it;
    }

    // ==================== 点击处理 ====================

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        switch (slot) {
            case SLOT_MODE_TELLRAW:
                s.mode = MODE_TELLRAW; refresh(p, s); break;
            case SLOT_MODE_TITLE:
                s.mode = MODE_TITLE; refresh(p, s); break;
            case SLOT_MODE_SUBTITLE:
                s.mode = MODE_SUBTITLE; refresh(p, s); break;
            case SLOT_MODE_ACTIONBAR:
                s.mode = MODE_ACTIONBAR; refresh(p, s); break;
            case SLOT_CUSTOM_COLOR:
                beginCapture(p, s, "customcolor"); break;
            case SLOT_INPUT_TEXT:
                beginCapture(p, s, "text"); break;
            case SLOT_STYLE_BOLD:
                s.styleFlags ^= 1;
                p.sendMessage(styleMsg("加粗", (s.styleFlags & 1) != 0));
                refresh(p, s); break;
            case SLOT_STYLE_ITALIC:
                s.styleFlags ^= 2;
                p.sendMessage(styleMsg("斜体", (s.styleFlags & 2) != 0));
                refresh(p, s); break;
            case SLOT_STYLE_UNDERLINE:
                s.styleFlags ^= 4;
                p.sendMessage(styleMsg("下划线", (s.styleFlags & 4) != 0));
                refresh(p, s); break;
            case SLOT_STYLE_STRIKE:
                s.styleFlags ^= 8;
                p.sendMessage(styleMsg("删除线", (s.styleFlags & 8) != 0));
                refresh(p, s); break;
            case SLOT_CLEAR:
                s.text.setLength(0);
                s.pendingColor = null;
                s.styleFlags = 0;
                p.sendMessage("§e[JSON构造器] §7内容/颜色/样式已全部清空");
                refresh(p, s); break;
            case SLOT_SEND:
                doSend(p, s); break;
            default:
                if (slot >= SLOT_PALETTE_FIRST && slot <= SLOT_PALETTE_LAST) {
                    int idx = slot - SLOT_PALETTE_FIRST;
                    if (idx < palette.size()) {
                        pickColor(p, s, palette.get(idx));
                    }
                }
                break;
        }
    }

    /** 拖拽进GUI一律无效 */
    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (TITLE.equals(e.getView().getTitle())) e.setCancelled(true);
    }

    private void refresh(Player p, Session s) {
        Inventory inv = build(s);
        // 原地重建：先关再开会闪，直接替换内容更稳
        Inventory cur = p.getOpenInventory().getTopInventory();
        if (cur != null && cur.getSize() == 27
                && TITLE.equals(p.getOpenInventory().getTitle())) {
            cur.setContents(inv.getContents());
        } else {
            p.openInventory(inv);
        }
    }

    // ==================== 聊天捕获 ====================

    private void beginCapture(Player p, Session s, String type) {
        s.capture = type;
        p.closeInventory();
        if (type.equals("text")) {
            p.sendMessage("§e[JSON构造器] §7请在聊天栏输入内容（§c不会发到公屏§7），"
                    + "§f空消息§7或§f/qj取消§7可放弃");
        } else {
            p.sendMessage("§e[JSON构造器] §7请在聊天栏输入颜色，如 §f#ff0000§7、"
                    + "§fR255 G0 B0§7、§fred§7；§f空消息§7可放弃");
        }
    }

    /**
     * 聊天捕获。★ AsyncPlayerChatEvent 在异步线程，
     * 严禁在此直接开库存/改背包 —— 一律 runTask 切主线程。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCaptureChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.capture == null) return;
        // 捕获期间该玩家的话一律不上公屏
        e.setCancelled(true);
        String type = s.capture;
        String msg = e.getMessage();
        s.capture = null;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (msg.isEmpty() || msg.equals("/qj")) {
                p.sendMessage("§e[JSON构造器] §7已取消输入");
            } else if (type.equals("text")) {
                // ★ 27批：先把待用颜色/样式冠在这段文字之前 ——
                //   这样「先选色后打字」也能真正带上颜色（此前色码裸挂在段首被丢弃）
                String style = styleToLegacy(s.styleFlags);
                if (s.pendingColor != null || !style.isEmpty()) {
                    s.text.append(s.pendingColor == null ? "" : s.pendingColor);
                    s.text.append(style);
                }
                s.text.append(msg);
                p.sendMessage("§a[JSON构造器] §7已加入内容："
                        + colorPreview(msg, s.pendingColor) + clip(msg, 60));
            } else {
                // 自定义颜色
                if (isValidColor(msg)) {
                    applyCustomColor(p, s, normalizeColor(msg));
                } else {
                    p.sendMessage("§c[JSON构造器] 颜色配置无效：§f" + msg
                            + "§7（仅支持 MC 原生16色名 / #RRGGBB / R G B）");
                }
            }
            p.openInventory(build(s));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }

    /** 颜色 → legacy § 码串（hex 用 §x§R§R§G§G§B§B） */
    private static String colorToLegacy(String color) {
        StringBuilder sb = new StringBuilder();
        if (color.startsWith("#")) {
            sb.append('§').append('x');
            for (char c : color.substring(1).toCharArray()) {
                sb.append('§').append(Character.toLowerCase(c));
            }
        } else {
            sb.append('§').append(nativeToLegacy(color));
        }
        return sb.toString();
    }

    /** 已开启样式的 legacy 码串（加粗§l/斜体§o/下划线§n/删除线§m） */
    private static String styleToLegacy(int flags) {
        StringBuilder sb = new StringBuilder();
        if ((flags & 1) != 0) sb.append('§').append('l');
        if ((flags & 2) != 0) sb.append('§').append('o');
        if ((flags & 4) != 0) sb.append('§').append('n');
        if ((flags & 8) != 0) sb.append('§').append('m');
        return sb.toString();
    }

    private static String styleMsg(String name, boolean on) {
        return "§a[JSON构造器] §7样式 §f" + name + " §7已"
                + (on ? "§a开启" : "§c关闭");
    }

    /**
     * ★ 27批 线上事故修复：「设置绿色，输出白色」。
     *
     * <p>原实现把颜色码直接 append 到 text 末尾。当玩家「先输入文字、再点颜色」时，
     * 色码只是给已发出的文字「下个位置」上色——对已输入的内容没有任何效果；
     * 而「先点颜色、再输入文字」时色码落在文字之前，被 toTellrawJson 切成一个空的
     * extra 段被丢弃，表现同样是全白。两种顺序都拿不到颜色。
     *
     * <p>现语义改为「选色是状态，不是追加」：
     * <ul>
     *   <li>text 为空 → 只记 pendingColor，等输入时前置到该段文字；</li>
     *   <li>text 已有内容 → 重写整个内容：每个可见文字段冠以「当前色 + 当前样式」；</li>
     *   <li>已开样式同时以 § 码形式注入，保证 toTellrawJson 能转成 JSON 布尔字段。</li>
     * </ul>
     */
    private void pickColor(Player p, Session s, String color) {
        String code = colorToLegacy(color);
        String style = styleToLegacy(s.styleFlags);
        if (s.text.length() == 0) {
            s.pendingColor = code;
            p.sendMessage("§a[JSON构造器] §7已选色 §f" + color
                    + "§7，输入内容时自动带上");
            refresh(p, s);
            return;
        }
        // 先剥掉旧色码得到裸文本，再按新颜色整体重建
        String plain = stripCodes(s.text.toString());
        s.pendingColor = code;
        applyColorToPlain(p, s, plain, code, style);
        p.sendMessage("§a[JSON构造器] §7已把已有内容染成 §f" + color);
        refresh(p, s);
    }

    /** 去掉所有 § 颜色/样式码，得到裸文本（重染色前先剥干净） */
    private static String stripCodes(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 把裸文本按给定颜色 + 样式重建为 text（每段一个「色码+样式码+文字」） */
    private void applyColorToPlain(Player p, Session s, String plain,
                                   String colorCode, String style) {
        s.text.setLength(0);
        if (plain.isEmpty()) {
            s.pendingColor = colorCode;
            return;
        }
        // 按行切段，逐段冠色 —— 换行也能保持颜色
        String[] lines = plain.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) s.text.append('\n');
            if (lines[i].isEmpty()) continue;
            s.text.append(colorCode).append(style).append(lines[i]);
        }
    }

    /** 自定义颜色输入校验通过后统一走这里（与调色板同语义） */
    private void applyCustomColor(Player p, Session s, String color) {
        pickColor(p, s, color);
    }

    // ==================== 发送 ====================

    /**
     * 把本次实际执行的原版指令 + 生成的 JSON 原样回显给执行者（仅本人可见）。
     * 长 JSON 按 180 字符一行折行，避免超长行被客户端吞掉；
     * 同时给出该 JSON 的最小可复制形态，方便照着敲进游戏。
     */
    private void echoJson(Player p, String cmd, String json) {
        p.sendMessage("§8§m                    ");
        p.sendMessage("§b[JSON构造器] §7以下是你刚发送的 JSON（§f仅你可见§7，可直接复制）：");
        p.sendMessage("§8指令 §7: §f/" + cmd);
        for (String line : wrap(json, 180)) {
            p.sendMessage("§8JSON §7: §e" + line);
        }
        // ★ 28批（用户要求，参考原版 /seed）：JSON 整段做成可点击复制超链接，
        //   一键拷进系统剪贴板；上面的折行只便于阅读，复制以这条为准
        p.sendMessage(net.kyori.adventure.text.Component
                .text("[点击复制完整JSON]", net.kyori.adventure.text.format.NamedTextColor.AQUA,
                        net.kyori.adventure.text.format.TextDecoration.UNDERLINED)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(json))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        net.kyori.adventure.text.Component.text("复制到系统剪贴板"))));
        p.sendMessage("§8§m                    ");
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (int i = 0; i < s.length(); i += width) {
            out.add(s.substring(i, Math.min(s.length(), i + width)));
        }
        return out;
    }

    private void doSend(Player p, Session s) {
        if (s.text.length() == 0) {
            p.sendMessage("§c[JSON构造器] 内容为空，先点「输入内容」");
            return;
        }
        String json = toTellrawJson(s.text.toString());
        String cmd;
        switch (s.mode) {
            case MODE_TITLE:
                cmd = "title @a title " + json;
                break;
            case MODE_SUBTITLE:
                cmd = "title @a subtitle " + json;
                break;
            // ★ 30批：actionbar 模式
            case MODE_ACTIONBAR:
                cmd = "title @a actionbar " + json;
                break;
            default:
                cmd = "tellraw @a " + json;
                break;
        }
        p.closeInventory();
        // 以玩家自己身份执行：权限模型与原版指令完全一致
        boolean ok = false;
        try {
            ok = Bukkit.dispatchCommand(p, cmd);
            // ★ 28批：原版 subtitle 单发只「设置」不显示，必须有 title 同屏才呈现；
            //   先设 subtitle、再发空 title 作显示载体，小标题立即全屏显示
            if (ok && s.mode == MODE_SUBTITLE) {
                Bukkit.dispatchCommand(p, "title @a title {\"text\":\"\"}");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning(
                    "[JSON构造器] 指令执行异常: " + t.getMessage());
        }
        if (ok) {
            p.sendMessage("§a[JSON构造器] §7已发送（"
                    + (s.mode == MODE_TITLE ? "大标题"
                    : s.mode == MODE_SUBTITLE ? "小标题·已附带空大字同屏显示"
                    : s.mode == MODE_ACTIONBAR ? "actionbar 底部提示栏"
                    : "tellraw消息") + "）");
            // ★ 27批（用户要求）：把生成的 JSON 原样回给执行者，便于学习 JSON 写法。
            //   只私发给本人，不刷公屏；分多行发，长度也不受聊天栏截断影响。
            echoJson(p, cmd, json);
        } else {
            p.sendMessage("§c[JSON构造器] 发送失败：权限不足或指令被拒绝");
            echoJson(p, cmd, json);   // 失败也回显，方便改完权限再试
        }
    }

    /**
     * 文本（含 § 颜色码）→ tellraw JSON。
     * § 颜色切段：每段一个 extra 元素；§x hex 保留为 color:#hex；
     * §l/§o/§n/§m/§k/§r 样式码透传为 bold/italic/underlined/strikethrough/obfuscated/reset。
     */
    static String toTellrawJson(String text) {
        StringBuilder out = new StringBuilder("{\"text\":\"\",\"extra\":[");
        boolean first = true;

        List<String[]> parts = new ArrayList<>(); // {text,color,bits}
        StringBuilder sb = new StringBuilder();
        String color = null;
        int flags = 0; // 1 bold 2 italic 4 under 8 strike 16 obf

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(++i));
                if (sb.length() > 0) {
                    parts.add(new String[]{sb.toString(), color, String.valueOf(flags)});
                    sb.setLength(0);
                }
                switch (code) {
                    case '0': color = "black"; flags = 0; break;
                    case '1': color = "dark_blue"; flags = 0; break;
                    case '2': color = "dark_green"; flags = 0; break;
                    case '3': color = "dark_aqua"; flags = 0; break;
                    case '4': color = "dark_red"; flags = 0; break;
                    case '5': color = "dark_purple"; flags = 0; break;
                    case '6': color = "gold"; flags = 0; break;
                    case '7': color = "gray"; flags = 0; break;
                    case '8': color = "dark_gray"; flags = 0; break;
                    case '9': color = "blue"; flags = 0; break;
                    case 'a': color = "green"; flags = 0; break;
                    case 'b': color = "aqua"; flags = 0; break;
                    case 'c': color = "red"; flags = 0; break;
                    case 'd': color = "light_purple"; flags = 0; break;
                    case 'e': color = "yellow"; flags = 0; break;
                    case 'f': color = "white"; flags = 0; break;
                    case 'x': {
                        // §x§R§R§G§G§B§B
                        StringBuilder hx = new StringBuilder("#");
                        while (i + 2 < text.length()
                                && text.charAt(i + 1) == '§'
                                && isHexChar(text.charAt(i + 2))) {
                            hx.append(text.charAt(i + 2));
                            i += 2;
                        }
                        color = hx.length() == 7 ? hx.toString() : color;
                        break;
                    }
                    case 'l': flags |= 1; break;
                    case 'o': flags |= 2; break;
                    case 'n': flags |= 4; break;
                    case 'm': flags |= 8; break;
                    case 'k': flags |= 16; break;
                    case 'r': color = null; flags = 0; break;
                    default: break; // 未知码忽略
                }
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '"' || c == '\\') {
                // JSON 字符串转义，防玩家输入引号/反斜杠打坏整条指令
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) {
            parts.add(new String[]{sb.toString(), color, String.valueOf(flags)});
        }

        for (String[] seg : parts) {
            if (!first) out.append(',');
            first = false;
            out.append("{\"text\":\"").append(seg[0]).append("\"");
            if (seg[1] != null) out.append(",\"color\":\"").append(seg[1]).append('"');
            int f = Integer.parseInt(seg[2]);
            if ((f & 1) != 0) out.append(",\"bold\":true");
            if ((f & 2) != 0) out.append(",\"italic\":true");
            if ((f & 4) != 0) out.append(",\"underlined\":true");
            if ((f & 8) != 0) out.append(",\"strikethrough\":true");
            if ((f & 16) != 0) out.append(",\"obfuscated\":true");
            out.append('}');
        }
        out.append("]}");
        return out.toString();
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
