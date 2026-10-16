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
    private static final int SLOT_HELP = 7;
    private static final int SLOT_CUSTOM_COLOR = 9;
    private static final int SLOT_PALETTE_FIRST = 10;   // 10-17 共 8 格调色板
    private static final int SLOT_PALETTE_LAST = 17;
    private static final int SLOT_PREVIEW = 21;
    private static final int SLOT_INPUT_TEXT = 22;
    private static final int SLOT_CLEAR = 25;
    private static final int SLOT_SEND = 26;

    // ==================== 每玩家会话 ====================
    private static final class Session {
        int mode = MODE_TELLRAW;
        /** 已确认的文本段（含 § 颜色码） */
        final StringBuilder text = new StringBuilder();
        /** 当前等待聊天捕获的输入类型：null=无 */
        String capture; // "text" | "customcolor"
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
     * @param body 已去掉开头斜杠的命令行
     * @return true = 已接管（打开GUI预填原文），false = 不归本构造器管
     */
    public boolean tryTakeover(Player p, String body) {
        if (body.isEmpty()) return false;
        String lower = body.toLowerCase(Locale.ROOT);
        int mode;
        String payload;
        if (lower.startsWith("tellraw ")) {
            mode = MODE_TELLRAW;
            payload = body.substring(8).trim();
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
            } else {
                return false; // actionbar 不在3模式内
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
        if (!extracted.isEmpty()) s.text.append(extracted);

        p.openInventory(build(s));
        p.sendMessage("§e[JSON构造器] §7已接管原版报错，你的原文已填入，"
                + "选颜色/改内容后点§a发送§7即可（也可继续§f/json§7打开本面板）");
        return true;
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

        inv.setItem(SLOT_HELP, simple(Material.BOOK,
                "§f使用说明",
                "§71. 选模式 → 2. 点「输入内容」",
                "§7在聊天栏打字（自动收回面板）",
                "§3点击颜色按钮§7可给当前内容上色",
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

        inv.setItem(SLOT_PREVIEW, previewItem(s));
        inv.setItem(SLOT_INPUT_TEXT, simple(Material.WRITABLE_BOOK,
                "§a输入内容",
                "§7点击后在聊天栏输入文字",
                "§7（不会真的发到公屏）"));
        inv.setItem(SLOT_CLEAR, simple(Material.LAVA_BUCKET,
                "§c清空内容", "§7清空已输入的文本"));
        inv.setItem(SLOT_SEND, simple(Material.EMERALD_BLOCK,
                "§a§l发送", "§7以你的身份执行对应原版指令"));
        return inv;
    }

    private ItemStack previewItem(Session s) {
        String shown = s.text.length() == 0 ? "§8（空）" : clip(s.text.toString(), 40);
        return simple(Material.HONEYCOMB, "§e当前内容预览", "§r" + shown);
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
            case SLOT_CUSTOM_COLOR:
                beginCapture(p, s, "customcolor"); break;
            case SLOT_INPUT_TEXT:
                beginCapture(p, s, "text"); break;
            case SLOT_CLEAR:
                s.text.setLength(0);
                p.sendMessage("§e[JSON构造器] §7内容已清空");
                refresh(p, s); break;
            case SLOT_SEND:
                doSend(p, s); break;
            default:
                if (slot >= SLOT_PALETTE_FIRST && slot <= SLOT_PALETTE_LAST) {
                    int idx = slot - SLOT_PALETTE_FIRST;
                    if (idx < palette.size()) {
                        appendColor(p, s, palette.get(idx));
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
                s.text.append(msg);
                p.sendMessage("§a[JSON构造器] §7已加入内容：§f" + clip(msg, 60));
            } else {
                // 自定义颜色
                if (isValidColor(msg)) {
                    appendColor(p, s, normalizeColor(msg));
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

    /** 颜色写入当前文本（legacy § 码，hex 用 §x§R§R§G§G§B§B） */
    private void appendColor(Player p, Session s, String color) {
        if (color.startsWith("#")) {
            s.text.append('§').append('x');
            for (char c : color.substring(1).toCharArray()) {
                s.text.append('§').append(Character.toLowerCase(c));
            }
        } else {
            s.text.append('§').append(nativeToLegacy(color));
        }
        p.sendMessage("§a[JSON构造器] §7已上色 §f" + color);
        refresh(p, s);
    }

    // ==================== 发送 ====================

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
            default:
                cmd = "tellraw @a " + json;
                break;
        }
        p.closeInventory();
        // 以玩家自己身份执行：权限模型与原版指令完全一致
        boolean ok = false;
        try {
            ok = Bukkit.dispatchCommand(p, cmd);
        } catch (Throwable t) {
            plugin.getLogger().warning(
                    "[JSON构造器] 指令执行异常: " + t.getMessage());
        }
        if (ok) {
            p.sendMessage("§a[JSON构造器] §7已发送（"
                    + (s.mode == MODE_TITLE ? "大标题"
                    : s.mode == MODE_SUBTITLE ? "小标题" : "tellraw消息") + "）");
        } else {
            p.sendMessage("§c[JSON构造器] 发送失败：权限不足或指令被拒绝");
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
