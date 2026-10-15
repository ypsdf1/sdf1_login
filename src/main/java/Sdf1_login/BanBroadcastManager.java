package Sdf1_login;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ 第十六批：游戏内轮询封禁播报（耻辱台）
 *
 * 配置文件（plugins/Sdf1_login/ban/ 目录）：
 *   - *.json      直接作为配置文件；
 *   - 规则配置.md  优先解 ```json 代码块，解不出再解 markdown 表格；
 *   - 解码全失败  → 备份原文件 + 释放一份新的。
 * 配置丢失 → 自动补发（saveResource 自愈）；
 * 已存在的 md → 安全升级自动补齐模板新增行（safeUpgradeDoc，第二十一批）。
 *
 * 键值：轮播最低间隔(秒) / 轮播最大间隔(秒) / 播报模式 / 播报起点(插件安装之前的封禁不播)
 *   播报模式：true=由近到远、false=由远到近、0=关闭；
 *   表格值：由近到远 / 由远到近 / 0。
 *   间隔或模式为 0 → 关闭播报。
 */
public class BanBroadcastManager implements Listener {

    private final Main plugin;
    private final Random rnd = new Random();

    private File banDir;
    private File mdFile;
    private File jsonFile;           // *.json 配置（存在则优先）

    private int minInterval = 60;
    private int maxInterval = 180;
    private int mode = 1;            // 0=关闭 1=由近到远 2=由远到近
    private int pageSize = 6;        // 每轮播报条数（耻辱台一页，一次发6个）
    private int lastDelaySec = 0;     // 本轮实际排定的随机间隔秒数（播报日志用）
    private long startCutoffMs = 0;   // 播报起点(毫秒)：只播该时刻之后创建的封禁
    private boolean startCutoffAuto = true;  // 起点未配置 → 自动取插件数据目录创建时间(=插件安装时间)
    private long autoCutoffMs = -1;    // 自动起点缓存(-1=未取)
    private boolean jsonSyntaxOk = false; // json 文本语法是否有效（区分「语法坏」与「语法好但值空」）
    private String lastCfgSig = null;             // 上轮配置签名（增量重载：无变更不打日志）
    private volatile boolean timerRunning = false;   // 已有待触发的排程（防重复拉起）
    private volatile boolean stoppedByEmpty = false; // 全员下线导致的停机（等玩家上线恢复）
    private static BanBroadcastManager instance;     // 单插件单实例，供 WebManager 取播报起点

    public BanBroadcastManager(Main plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /** 单例访问（WebManager 推送封禁时取播报起点） */
    public static BanBroadcastManager get() { return instance; }

    /**
     * 播报起点(毫秒)，0=不过滤。与游戏内播报同一套语义（配置的播报起点 / 自动=插件数据目录创建时间），
     * 供 PHP 端封禁公示过滤「插件上服务器之前的历史封禁」——两端同源，不各配一套。
     */
    public long getStartCutoff() { return resolveStartCutoff(); }

    // ==================== 生命周期 ====================

    public void start() {
        banDir = new File(plugin.getDataFolder(), "ban");
        mdFile = new File(banDir, "规则配置.md");
        jsonFile = findJsonConfig();
        ensureConfigExists();
        reloadConfig();
        scheduleNext();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // ★ 二十二批：错位配置检测 —— 曾有人把 规则配置.md/json 放到
        //   plugins/ban/（插件只读 plugins/Sdf1_login/ban/），表现为
        //   「安全升级没生效」。发现即警告并指明正确路径。
        try {
            File wrongDir = new File(
                    plugin.getDataFolder().getParentFile(),
                    "ban");
            if (wrongDir.isDirectory()) {
                String[] wrongs = wrongDir.list((d, n) ->
                        n.equals("规则配置.md")
                                || n.toLowerCase(Locale.ROOT)
                                        .endsWith(".json"));
                if (wrongs != null && wrongs.length > 0) {
                    warn("检测到错位配置目录 plugins/ban/（含 "
                            + String.join(", ", wrongs)
                            + "）——本插件只读取 "
                            + banDir.getPath()
                            + "，该目录内的配置不会生效！");
                }
            }
        } catch (Exception ignored) {}
        long cut0 = resolveStartCutoff();
        plugin.getLogger().info("[封禁播报] 已启动，模式=" + modeName()
                + " 间隔=" + minInterval + "~" + maxInterval + "s 每页" + pageSize + "条"
                + " 起点=" + (cut0 > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(cut0)) : "不限"));
    }

    public void shutdown() { /* BukkitRunnable 随插件卸载自动取消 */ }

    /**
     * ★ 第十九批：定时器生命周期 —— 停止条件只有两个：全员下线、服务器关机/插件卸载。
     * 配置为「关闭」不停定时器：只静默本轮，下一轮醒来重读配置，
     * 改回开启（或把间隔从 0 恢复）即自动续播，无需重启。
     * 全员下线时 scheduleNext 的空服分支停掉排程，第一个玩家上线由此重新拉起。
     * （start/runTaskLater/PlayerJoinEvent 都在主线程，状态无并发问题）
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!stoppedByEmpty || timerRunning) return;
        stoppedByEmpty = false;
        scheduleNext();
    }

    // ==================== 配置：自愈 + 双模式解析 ====================

    /** 找 ban/ 目录下任意 *.json 配置文件 */
    private File findJsonConfig() {
        File[] fs = banDir == null ? null : banDir.listFiles(
                (d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        return (fs != null && fs.length > 0) ? fs[0] : null;
    }

    /** 配置文件丢失就补一份（自愈）；已存在的 md 走安全升级补齐模板新增行 */
    private void ensureConfigExists() {
        jsonFile = findJsonConfig();   // ★ 每轮重查：运行中新增/删除的 json 都要跟上
        try {
            if (jsonFile != null) {
                if (!banDir.exists()) banDir.mkdirs();
                if (!jsonFile.exists()) {
                    plugin.saveResource("ban/" + jsonFile.getName(), false);
                    log("配置文件丢失，已自愈补发: " + jsonFile.getName());
                }
            } else if (!mdFile.exists()) {
                banDir.mkdirs();
                plugin.saveResource("ban/规则配置.md", false);
                log("配置文件丢失，已自愈补发: 规则配置.md");
            }
            // ★ 第二十一批：已存在的 md 安全升级 —— saveResource(...,false) 对已存在文件
            //   永不覆盖，jar 模板新增的配置行（如「播报起点」）借此补进原文件；
            //   说明文字与用户改过的值原样保留（safeUpgradeDoc：先 .bak 逐字节校验，
            //   备份失败整个取消不动原文件；原子写失败自动还原；无新增行幂等不写文件）。
            //   有变更由 safeUpgradeDoc 打一条明细，无变更安静 —— 对齐增量日志要求。
            if (mdFile != null && mdFile.exists()) {
                java.util.List<String> tpl = ConfigManager.readJarResourceLines(
                        plugin.getResource("ban/规则配置.md"));
                if (tpl != null) {
                    plugin.getConfig2().safeUpgradeDoc(mdFile, tpl, "封禁播报规则");
                }
            }
        } catch (Exception e) {
            warn("自愈补发失败: " + e.getMessage());
        }
    }

    /** 重新加载配置（json 优先 → md json块 → md 表格 → 备份重置）。
     *  ★ 第二十批：增量重载 —— 生效配置与上一轮逐项对比：
     *  无变更一个字不打（安静执行随机计时，到点该干嘛干嘛）；
     *  有变更才打一条明细（模式/间隔/起点/来源 哪里从什么变成什么）。 */
    public void reloadConfig() {
        // 进门快照（对比基线）：上一轮生效值
        final int oMin = minInterval, oMax = maxInterval, oMode = mode;
        final long oCut = startCutoffMs;
        final boolean oAuto = startCutoffAuto;
        final String oldSig = lastCfgSig;
        String src = null;   // 本轮配置源（变更明细用）
        try {
            // ★ 懒重载（每次播报窗口执行一次）：先回出厂默认再解析文件 ——
            //   文件里新改的值、被删掉/清空的项都能如实读到（读到的就是文件里最新的）；
            //   开关(播报模式)决定这次报/不报，最新间隔用于播报完后的下次排程计时。
            minInterval = 60;
            maxInterval = 180;
            mode = 1;
            startCutoffAuto = true;
            startCutoffMs = 0;
            jsonFile = findJsonConfig();   // ★ 每轮重查（不再只在 null 时查一次）
            if (jsonFile != null && jsonFile.exists()) {
                String txt = readFile(jsonFile);
                if (parseJsonText(txt)) {
                    src = "json:" + jsonFile.getName();
                } else if (jsonSyntaxOk) {
                    // ★ 第十八批：语法有效但间隔值为空（文档语义「表格/json 二者选一填」）
                    //   → 继承 json 里已解析的播报模式/播报起点，间隔回退 md 表格
                    //   （只取间隔，绝不覆盖已继承的模式/起点）或出厂默认，不判损毁。
                    boolean iv = false;
                    if (mdFile != null && mdFile.exists()) {
                        String md0 = readFile(mdFile);
                        if (md0 != null) iv = parseIntervalTable(md0);
                    }
                    normalize();
                    src = "json继承" + (iv ? "+md间隔" : "+默认间隔") + ":" + jsonFile.getName();
                } else {
                    // 语法真损坏 → 备份+移除，继续回退 md 分支（不再就此 return）
                    backupAndReset(jsonFile, jsonFile.getName());
                    jsonFile = null;
                }
            }
            if (src == null) {
                if (!mdFile.exists()) { ensureConfigExists(); }
                String md = readFile(mdFile);
                if (md != null) {
                    String jsonBlock = extractJsonBlock(md);
                    if (jsonBlock != null && parseJsonText(jsonBlock)) {
                        src = "md内嵌json";
                    } else if (parseTableText(md)) {
                        src = "md表格";
                    }
                }
                if (src == null) {
                    // 全部解码失败 → 备份原文件 + 释放一份新的（低频真事件，仍打日志）
                    backupAndReset(mdFile, "规则配置.md");
                    src = "出厂重置";
                }
            }
        } catch (Exception e) {
            warn("reloadConfig 异常: " + e.getMessage());
            src = "异常";
        }
        // ★ 增量日志：签名 = 配置源 + 全部生效值；与上一轮完全一致 → 安静
        String sig = src + "|" + mode + "|" + minInterval + "|" + maxInterval + "|"
                + (startCutoffAuto ? "auto" : startCutoffMs);
        if (sig.equals(oldSig)) { return; }
        lastCfgSig = sig;
        if (oldSig == null) { return; }   // 首轮只记基线（start()「已启动」总览已覆盖，不重复刷屏）
        StringBuilder sb = new StringBuilder("配置变更(").append(src).append("):");
        if (oMode != mode) {
            sb.append(" 模式 ").append(modeNameOf(oMode)).append("→").append(modeName());
        }
        if (oMin != minInterval || oMax != maxInterval) {
            sb.append(" 间隔 ").append(oMin).append("~").append(oMax).append("s→")
              .append(minInterval).append("~").append(maxInterval).append("s");
        }
        if (oAuto != startCutoffAuto || oCut != startCutoffMs) {
            sb.append(" 起点 ").append(cutText(oAuto, oCut))
              .append("→").append(cutText(startCutoffAuto, startCutoffMs));
        }
        String oldSrc = oldSig.substring(0, oldSig.indexOf('|'));
        if (!oldSrc.equals(src)) {
            sb.append(" 来源 ").append(oldSrc).append("→").append(src);
        }
        log(sb.toString());
    }

    /** 模式码→文字（增量日志对比旧值用；modeName() 取当前字段） */
    private String modeNameOf(int m) {
        return m == 0 ? "关闭" : (m == 1 ? "由近到远" : "由远到近");
    }

    /** 起点状态→文字（增量日志用）：auto=自动 / 时间戳=日期 / 0=不限 */
    private String cutText(boolean auto, long ms) {
        if (auto) return "自动";
        return ms > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(ms)) : "不限";
    }

    /** 从 md 提取 ```json 代码块内容（不含围栏） */
    private String extractJsonBlock(String md) {
        Pattern p = Pattern.compile("```json\\s*\\n(.*?)\\n\\s*```", Pattern.DOTALL);
        Matcher m = p.matcher(md);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 解析 json 文本（键：最小间隔/最大间隔/播报模式）。
     * 出厂 规则配置.md 的 json 块值为空字符串而表格有真值（文档语义：二者选一填），
     * 因此仅当 json 里出现「非空间隔值」才认 json 生效；
     * 只有播报模式、没有任何间隔键的纯 json 也认生效（间隔走默认）。
     * @return true = json 配置生效；false = 视为未配置，调用方回退表格
     */
    private boolean parseJsonText(String txt) {
        jsonSyntaxOk = false;
        try {
            JsonObject o = JsonParser.parseString(txt).getAsJsonObject();
            jsonSyntaxOk = true;   // 语法解析成功（值是否为空另行判定）
            boolean hasIntervalKey = o.has("最小间隔") || o.has("最大间隔")
                    || o.has("轮播最低间隔") || o.has("轮播最大间隔");
            boolean anyInterval = false;
            Integer v;
            if ((v = jsonInt(o, "最小间隔")) != null) { minInterval = v; anyInterval = true; }
            if ((v = jsonInt(o, "最大间隔")) != null) { maxInterval = v; anyInterval = true; }
            if ((v = jsonInt(o, "轮播最低间隔")) != null) { minInterval = v; anyInterval = true; }
            if ((v = jsonInt(o, "轮播最大间隔")) != null) { maxInterval = v; anyInterval = true; }
            boolean modePresent = false;
            if (o.has("播报模式") && !o.get("播报模式").isJsonNull()) {
                mode = parseMode(o.get("播报模式").getAsString());
                modePresent = true;
            }
            if (o.has("播报起点") && !o.get("播报起点").isJsonNull()) {
                parseStartCutoff(o.get("播报起点").getAsString());
            }
            if (anyInterval) { normalize(); return true; }
            // 没有可用间隔值：纯「只有播报模式且无间隔键」的 json 也算生效
            if (modePresent && !hasIntervalKey) { normalize(); return true; }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** json 取间隔值：键存在且非空才返回（空串/null → null，留给表格或默认值） */
    private Integer jsonInt(JsonObject o, String key) {
        try {
            if (!o.has(key) || o.get(key).isJsonNull()) return null;
            String s = o.get(key).getAsString();
            if (s == null || s.trim().isEmpty()) return null;
            int r = toSec(s);
            return r >= 0 ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 md 表格（| 名字 | 说明 | 参数值 |） */
    private boolean parseTableText(String md) {
        boolean any = false;
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.startsWith("| ---") || t.startsWith("|---")) continue;
            String[] cells = t.split("\\|");
            if (cells.length < 4) continue;
            String key = cells[1].trim();
            String val = cells[cells.length - 1].trim();
            if (key.equals("轮播最低间隔")) { minInterval = toSec(val); any = true; }
            else if (key.equals("轮播最大间隔")) { maxInterval = toSec(val); any = true; }
            else if (key.equals("播报模式")) { mode = parseMode(val); any = true; }
            else if (key.equals("播报起点")) { parseStartCutoff(val); any = true; }
        }
        if (any) normalize();
        return any;
    }

    /** 只从 md 表格取间隔值（不碰播报模式/播报起点 —— 继承 json 时仅回退间隔用） */
    private boolean parseIntervalTable(String md) {
        boolean any = false;
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|") || t.startsWith("| ---") || t.startsWith("|---")) continue;
            String[] cells = t.split("\\|");
            if (cells.length < 4) continue;
            String key = cells[1].trim();
            String val = cells[cells.length - 1].trim();
            if (key.equals("轮播最低间隔")) { minInterval = toSec(val); any = true; }
            else if (key.equals("轮播最大间隔")) { maxInterval = toSec(val); any = true; }
        }
        if (any) {
            if (minInterval < 0) minInterval = 0;
            if (maxInterval < 0) maxInterval = 0;
            if (maxInterval > 0 && minInterval > maxInterval) maxInterval = minInterval;
        }
        return any;
    }

    /** 播报模式文本/布尔/数字 → 0/1/2 */
    private int parseMode(String s) {
        if (s == null) return 1;
        String v = s.trim();
        // ★ 第十八批遗留修复：0 = 关闭（表格文档语义）。
        //   原实现把 "0" 和 "false" 合并映射成 2（由远到近），
        //   导致表格填 0 永远关不掉、一直播报 —— 现拆开各归各位。
        if (v.equals("0")) return 0;                                // 0=关闭
        if (v.equalsIgnoreCase("false")) return 2;                  // false=由远到近
        if (v.equals("1") || v.equalsIgnoreCase("true")) return 1;  // true=由近到远
        if (v.contains("关闭")) return 0;                            // 容错：直接写「关闭」
        if (v.contains("近到远")) return 1;                          // 由近到远
        if (v.contains("远到近")) return 2;                          // 由远到近
        return 1;
    }

    /** 秒数文本 → int（容错"60s"/"60秒"/"60"） */
    private int toSec(String s) {
        String v = s == null ? "" : s.trim().replaceAll("[^0-9]", "");
        if (v.isEmpty()) return 0;
        try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
    }

    /** 归一化：模式为0 或 最大最小间隔都为0 → 关闭；最大<最低 → 补齐（对齐配置说明语义） */
    private void normalize() {
        if (minInterval < 0) minInterval = 0;
        if (maxInterval < 0) maxInterval = 0;
        if (maxInterval > 0 && minInterval > maxInterval) maxInterval = minInterval;
        if (mode != 0 && mode != 1 && mode != 2) mode = 1;
        if (mode == 0 || (minInterval == 0 && maxInterval == 0)) mode = 0;
    }

    /**
     * 解析「播报起点」：插件安装之前的封禁（历史封禁）不播报。
     * 支持 yyyy-MM-dd[ HH:mm[:ss]] 日期、10/13 位时间戳；0/留空 = 自动
     * （自动模式取插件数据目录创建时间，即插件安装上服务器的时间）
     */
    private void parseStartCutoff(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty() || v.equals("0")) {
            startCutoffAuto = true;
            startCutoffMs = 0;
            return;
        }
        if (v.matches("\\d{10,13}")) {
            long n = Long.parseLong(v);
            startCutoffMs = v.length() <= 10 ? n * 1000L : n;
            startCutoffAuto = false;
            return;
        }
        String[] pats = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        for (String p : pats) {
            try {
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                Date d = new SimpleDateFormat(p, Locale.ROOT).parse(v, pos);
                if (d != null && pos.getIndex() == v.length()) {
                    startCutoffMs = d.getTime();
                    startCutoffAuto = false;
                    return;
                }
            } catch (Exception ignored) { }
        }
        warn("播报起点无法解析，回退自动: " + v);
        startCutoffAuto = true;
        startCutoffMs = 0;
    }

    /**
     * 取播报起点(毫秒)：>0 表示只播该时刻之后创建的封禁。
     * 自动模式取插件数据目录(plugins/Sdf1_login)的创建时间 = 插件安装时间；
     * 文件系统给不出可信创建时间时（缓存值距今不足 7 天，多半是 mtime 兜底）
     * → 返回 0 不过滤，避免把插件安装之后的封禁也一并排除。
     */
    private long resolveStartCutoff() {
        if (!startCutoffAuto) return startCutoffMs;
        if (autoCutoffMs < 0) {
            autoCutoffMs = 0;
            try {
                File df = plugin.getDataFolder();
                if (df != null && df.exists()) {
                    long t = java.nio.file.Files.readAttributes(df.toPath(),
                            java.nio.file.attribute.BasicFileAttributes.class)
                            .creationTime().toMillis();
                    if (t > 0 && System.currentTimeMillis() - t > 7L * 24 * 3600 * 1000) {
                        autoCutoffMs = t;
                    }
                }
            } catch (Exception ignored) { }
        }
        return autoCutoffMs;
    }

    // ==================== 调度 ====================

    /** 排下一次窗口期：随机 [min,max] 秒后播报，再自我reschedule */
    private void scheduleNext() {
        int delaySec;
        if (mode == 0) {
            // 播报关闭时仍每 60 秒自检一次：配置文件被删则自愈补发、
            // 运行中把 0 改回正常值可自动恢复播报（无需重启）。
            // ★ 关闭 ≠ 停机：定时器只在全员下线/关服时才停（第十九批）
            delaySec = 60;
        } else {
            // ★ 每次在[最小间隔,最大间隔]之间随机取一个秒数，不是固定值
            int span = maxInterval - minInterval;
            delaySec = minInterval + (span > 0 ? rnd.nextInt(span + 1) : 0);
            lastDelaySec = delaySec;
        }
        long delayTicks = Math.max(20L, delaySec * 20L);
        timerRunning = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                timerRunning = false;
                if (!plugin.isEnabled()) return;
                // ★ 服务器没人就停机：全员下线是定时器的两个停止条件之一（另一个是关服），
                //   不播报、不重载、不自愈、不打任何日志，直接停排程 ——
                //   第一个玩家上线时 onPlayerJoin 重新拉起（配置为关闭同样只静默本轮）
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    stoppedByEmpty = true;
                    return;
                }
                // ★ 懒重载：每次播报窗口重读配置 —— 开关决定这次报/不报，
                //   最新间隔用于播报完后的下次排程计时；顺带自愈补发丢失的配置文件
                ensureConfigExists();
                reloadConfig();
                if (mode != 0) {
                    try { broadcastOnce(); }
                    catch (Exception e) { warn("播报异常: " + e.getMessage()); }
                }
                scheduleNext();
            }
        }.runTaskLater(plugin, delayTicks);
    }

    // ==================== 播报 ====================

    /** 取当前生效封禁条目（未过期），按创建时间排序 */
    @SuppressWarnings("unchecked")
    private List<BanEntry<?>> collectBans() {
        List<BanEntry<?>> list = new ArrayList<>();
        try {
            BanList nb = Bukkit.getBanList(BanList.Type.NAME);
            // getEntries() 实际返回 Set，按 Collection 中转强转（防 ClassCastException）
            // ★ 播报起点：插件安装之前的封禁（历史封禁）不播报，与插件管辖范围对齐
            long cutoff = resolveStartCutoff();
            for (BanEntry<?> e : (Collection<BanEntry<?>>) (Collection<?>) nb.getEntries()) {
                Date exp = e.getExpiration();
                if (exp != null && exp.getTime() <= System.currentTimeMillis()) continue;
                Date created = e.getCreated();
                if (cutoff > 0 && created != null && created.getTime() < cutoff) continue;
                list.add(e);
            }
        } catch (Exception e) {
            warn("读取封禁名单失败: " + e.getMessage());
        }
        return list;
    }

    /** 轮播当前页并广播给全服 */
    private void broadcastOnce() {
        List<BanEntry<?>> bans = collectBans();
        if (bans.isEmpty()) { log("当前无生效封禁，跳过本轮"); return; }

        // 由近到远：最新封禁在前；由远到近：最早封禁在前
        bans.sort((a, b) -> {
            long ta = a.getCreated() != null ? a.getCreated().getTime() : 0L;
            long tb = b.getCreated() != null ? b.getCreated().getTime() : 0L;
            return mode == 1 ? Long.compare(tb, ta) : Long.compare(ta, tb);
        });

        int total = bans.size();
        int start = cursor % total;
        int end = Math.min(start + pageSize, total);
        List<BanEntry<?>> page = bans.subList(start, end);
        cursor = end % total;

        String head = "§c§l⚠ §6违规封禁公示 §7(共§f" + total + "§7人, 第§f"
                + (start / pageSize + 1) + "§7/" + ((total + pageSize - 1) / pageSize) + "§7页)";
        Bukkit.broadcastMessage(head);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (BanEntry<?> e : page) {
            String target = e.getTarget();
            String reason = e.getReason() != null && !e.getReason().isEmpty()
                    ? e.getReason() : "违反服务器规则";
            String when;
            Date created = e.getCreated();
            when = created != null ? df.format(created) : "未知时间";
            Date exp = e.getExpiration();
            String expStr = exp != null ? ("§7至§f" + df.format(exp)) : "§c永久";
            Bukkit.broadcastMessage("§c☠ §f" + target + " §7- " + reason
                    + " §8| §7封于§f" + when + " §8| " + expStr);
        }
        Bukkit.broadcastMessage("§7§o请各位玩家引以为戒，遵守服务器规则。");
        log("本轮已播报 " + page.size() + "/" + total + " 条封禁（本次随机间隔 "
                + lastDelaySec + "s，区间 " + minInterval + "~" + maxInterval + "s）");
    }

    /** 轮播游标（下一页起点） */
    private int cursor = 0;

    // ==================== 管理员命令 ====================

    /** 手动重载配置并汇报当前生效值 */
    public void report(CommandSender to) {
        reloadConfig();
        long cut = resolveStartCutoff();
        to.sendMessage("§a[封禁播报] §7模式=§f" + modeName()
                + " §7间隔=§f" + minInterval + "~" + maxInterval + "s"
                + " §7每页=§f" + pageSize + "条"
                + " §7起点=§f" + (cut > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(cut)) : "不限")
                + (startCutoffAuto ? "§7(自动)" : "")
                + (mode == 0 ? " §c(已关闭)" : ""));
    }

    private String modeName() {
        return mode == 0 ? "关闭" : (mode == 1 ? "由近到远" : "由远到近");
    }

    // ==================== 工具 ====================

    private String readFile(File f) {
        if (f == null || !f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            warn("读取失败 " + f.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** 备份原配置文件 + 释放一份新的（自愈） */
    private void backupAndReset(File f, String resourceName) {
        try {
            if (f != null && f.exists()) {
                String bak = f.getAbsolutePath() + ".bak." + System.currentTimeMillis();
                copyFile(f, new File(bak));
                warn("配置解码失败，已备份原文件: " + new File(bak).getName());
            }
            // 先删旧文件再从 jar 释放新的
            if (f != null && f.exists()) f.delete();
            plugin.saveResource("ban/" + resourceName, false);
            log("已释放一份新配置: " + resourceName);
        } catch (IllegalArgumentException e) {
            // jar 里没有同名模板（如用户自建 cfg.json）：原文件已备份并移除，
            // 不算失败 —— 调用方会继续回退 md / 出厂默认
            warn("jar 内无模板资源 ban/" + resourceName
                    + "，原文件已备份并移除，回退其它配置源");
        } catch (Exception e) {
            warn("备份/重置失败: " + e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void log(String s) { plugin.getLogger().info("[封禁播报] " + s); }
    private void warn(String s) { plugin.getLogger().warning("[封禁播报] " + s); }
}
