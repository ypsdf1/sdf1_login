package Sdf1_login;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * ★ 第二十七批：数据源改为原版 banned-players.json（用户明令「这是 MC 唯一的封禁数据来源」）。
 *   线上事故：旧实现读 Bukkit.getBanList(NAME) 的内存名单，而内存名单在重启/被
   Web 后台批量改写后与磁盘 banned-players.json 严重不一致（文件里 [] 空，内存里
   却有几十条）→ 玩家看到「一个都没封却公示一堆人」。
 *   现改为：每轮播报都重新读 <worldContainer>/banned-players.json 到内存，
 *   解析每条的 created，过滤掉插件数据目录创建时间之前的全部历史封禁；
 *   文件缺失 / 语法坏 / 过滤后为空 → 一律闭嘴不播，绝不拿兜底假数据说话。
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

    // ==================== 27批：banned-players.json 数据模型 ====================

    /** banned-players.json 的一条记录（只取公示需要的字段） */
    private static final class JsonBan {
        final String name;
        final String reason;
        final String source;
        final long createdMs;
        /** 到期毫秒；0 = 永久 */
        final long expiresMs;

        JsonBan(String name, String reason, String source,
                long createdMs, long expiresMs) {
            this.name = name;
            this.reason = reason;
            this.source = source;
            this.createdMs = createdMs;
            this.expiresMs = expiresMs;
        }
    }

    /** 上次成功读到的原始文件签名（内容+长度），用于「数据有没有变」判定与日志 */
    private String lastFileSig = null;
    private int lastFileRawCount = 0;
    private int lastFileKeptCount = 0;

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
        File bf0 = bannedPlayersFile();
        plugin.getLogger().info("[封禁播报] 已启动，模式=" + modeName()
                + " 间隔=" + minInterval + "~" + maxInterval + "s 每页" + pageSize + "条"
                + " 起点=" + (cut0 > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(cut0)) : "不限")
                + " 数据源=" + (bf0 == null ? "未定位" : bf0.getPath()));
        List<JsonBan> boot = collectBans();
        log("启动首筛：banned-players.json 有效封禁 " + boot.size() + " 条");
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
     *
     * <p>★ 第二十七批：自动模式取插件数据目录(plugins/Sdf1_login)的创建时间
     * = 插件安装上服务器的时间，插件上服之前的全部历史封禁一律不播。
     *
     * <p>★ 第二十八批：数据目录可能被重建/迁移/重装——creationTime 变成当天，
     * 把之后创建的全部生效封禁一并滤光（实测起点=2026-10-16 → 2026-09-30
     * 的封禁被滤 → 播报误报「当前无生效封禁」）。故自动起点加下限保护：
     * 绝不晚于「距今 180 天」——既挡住古早历史封禁（半年前的一律不播），
     * 又保证目录被重建时近半年内的生效封禁不被误杀。
     * 手动配置的播报起点（startCutoffAuto=false）不受此保护，完全按配置走。
     *
     * <p>旧实现有「距今不足 7 天就放弃过滤」的守卫（当年担心 mtime 兜底不可信），
     * 结果插件刚装上服务器时自动退化成「不过滤」，把上线前几年的历史封禁全播了出来
     * —— 这正是用户看到的「一个都没封却公示一堆人」。
     * 现改为无条件采用 creationTime：只有文件系统根本给不出创建时间（返回 0 /
     * 抛异常）才退化为不过滤，宁可少播也不误播历史数据。
     */
    private long resolveStartCutoff() {
        if (!startCutoffAuto) return startCutoffMs;
        if (autoCutoffMs < 0) {
            autoCutoffMs = 0;
            long folderCreated = 0;
            try {
                File df = plugin.getDataFolder();
                if (df != null && df.exists()) {
                    long t = java.nio.file.Files.readAttributes(df.toPath(),
                            java.nio.file.attribute.BasicFileAttributes.class)
                            .creationTime().toMillis();
                    if (t > 0) folderCreated = t;
                }
            } catch (Exception e) {
                warn("读取插件数据目录创建时间失败，本次不过滤历史封禁: "
                        + e.getMessage());
            }
            if (folderCreated > 0) {
                long cap = System.currentTimeMillis() - MAX_AUTO_CUTOFF_WINDOW_MS;
                autoCutoffMs = Math.min(folderCreated, cap);
                if (folderCreated > cap) {
                    warn("插件数据目录创建时间 " + fmtCutoff(folderCreated)
                            + " 晚于180天前（目录可能被重建/迁移），"
                            + "自动起点回退为 " + fmtCutoff(cap)
                            + "，近半年内的生效封禁照常播报"
                            + "（可在 ban/规则配置.md 手动填「播报起点」精确控制）");
                }
            }
            if (autoCutoffMs <= 0) {
                warn("取不到插件数据目录创建时间，历史封禁过滤已关闭"
                        + "（可在 ban/规则配置.md 里手动填「播报起点」锁定时间）");
            }
        }
        return autoCutoffMs;
    }

    /** 28批：自动播报起点最多回溯 180 天（约半年）——数据目录重建时起点不得过新 */
    private static final long MAX_AUTO_CUTOFF_WINDOW_MS = 180L * 24 * 3600 * 1000;

    private static String fmtCutoff(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date(ms));
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

    /**
     * ★ 27批（用户明令的唯一数据源）：每轮都重新读原版 banned-players.json，
     * 加载到内存做筛选 —— 不再依赖任何内存名单，杜绝「文件是空的却公示一堆人」。
     *
     * <p>流程：定位文件 → 读全文 → 内存解析 → 过滤（过期 / created 早于播报起点）
     *   → 返回。文件缺失、为空、语法坏、过滤后一条不剩，一律返回空列表（=闭嘴）。
     *
     * <p>created/expires 两种格式都兼容：
     *   ISO「2026-10-16T13:32:58Z」、带时区「2026-10-16 21:06:20 +0800」、
     *   「yyyy-MM-dd HH:mm:ss」、以及纯数字时间戳（秒/毫秒）；
     *   「forever」/空 = 永久。解析不了的条目按 created=0 处理，
     *   起点过滤时会被当成历史封禁排除（宁可漏播不误播）。
     */
    private List<JsonBan> collectBans() {
        List<JsonBan> list = new ArrayList<>();
        File f = bannedPlayersFile();
        if (f == null || !f.exists()) {
            log("未找到 banned-players.json，本轮闭嘴不播");
            lastFileSig = null;
            lastFileRawCount = 0;
            lastFileKeptCount = 0;
            return list;
        }
        String txt = readFile(f);
        if (txt == null || txt.trim().isEmpty() || txt.trim().equals("[]")) {
            log("banned-players.json 为空（" + f.length() + " 字节），本轮闭嘴不播");
            lastFileSig = sigOf(f, txt);
            lastFileRawCount = 0;
            lastFileKeptCount = 0;
            return list;
        }

        // 内存加载：解析成 JsonBan 列表
        List<JsonBan> raw = new ArrayList<>();
        try {
            com.google.gson.JsonElement root = JsonParser.parseString(txt);
            if (!root.isJsonArray()) {
                warn("banned-players.json 根节点不是数组，本轮闭嘴不播");
                lastFileSig = sigOf(f, txt);
                return list;
            }
            long nowMs = System.currentTimeMillis();
            for (com.google.gson.JsonElement el : root.getAsJsonArray()) {
                if (el == null || !el.isJsonObject()) continue;
                com.google.gson.JsonObject o = el.getAsJsonObject();
                String name = str(o, "name");
                if (name.isEmpty()) name = str(o, "uuid");
                if (name.isEmpty()) continue;
                raw.add(new JsonBan(name, str(o, "reason"), str(o, "source"),
                        parseBanTime(str(o, "created")),
                        parseBanTime(str(o, "expires"))));
            }
        } catch (Exception e) {
            warn("banned-players.json 解析失败（" + e.getMessage()
                    + "），本轮闭嘴不播");
            lastFileSig = sigOf(f, txt);
            return list;
        }

        // 内存筛选：未过期 + created 晚于播报起点（起点=插件数据目录创建时间）
        long nowMs2 = System.currentTimeMillis();
        long cutoff = resolveStartCutoff();
        int expiredCnt = 0, historyCnt = 0, timeUnknownCnt = 0;
        for (JsonBan b : raw) {
            if (b.expiresMs > 0 && b.expiresMs <= nowMs2) { expiredCnt++; continue; }
            if (b.createdMs <= 0) { timeUnknownCnt++; }              // created 解析不了
            if (cutoff > 0 && b.createdMs < cutoff) { historyCnt++; continue; }
            list.add(b);
        }

        // 「每次都检查是不是最新」：文件签名变了才打日志，避免刷屏
        String sig = sigOf(f, txt);
        lastFileRawCount = raw.size();
        lastFileKeptCount = list.size();
        if (!sig.equals(lastFileSig)) {
            lastFileSig = sig;
            log("已重读 banned-players.json: 原始 " + raw.size() + " 条"
                    + " → 过滤后 " + list.size() + " 条（起点 "
                    + (cutoff > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(cutoff)) : "§c未取到·不过滤")
                    + "；过期 " + expiredCnt + "，历史 " + historyCnt
                    + "，时间未知 " + timeUnknownCnt + "，文件 " + sig + "）");
        }
        return list;
    }

    /** 原版封禁名单文件：<worldContainer>/banned-players.json（MC 唯一封禁数据源） */
    private File bannedPlayersFile() {
        try {
            File wc = Bukkit.getWorldContainer();
            if (wc != null) {
                return new File(wc, "banned-players.json");
            }
        } catch (Exception e) {
            warn("取世界容器失败: " + e.getMessage());
        }
        return plugin.getDataFolder() == null ? null
                : new File(plugin.getDataFolder().getParentFile() == null
                    ? plugin.getDataFolder()
                    : plugin.getDataFolder().getParentFile().getParentFile(),
                    "banned-players.json");
    }

    /** 文件内容签名（长度+哈希），用于「数据有没有变」判定 */
    private String sigOf(File f, String txt) {
        int h = txt == null ? 0 : txt.hashCode();
        return f.length() + "B/" + Integer.toHexString(h);
    }

    private static String str(com.google.gson.JsonObject o, String k) {
        try {
            if (o == null || !o.has(k) || o.get(k).isJsonNull()) return "";
            String s = o.get(k).getAsString();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * banned-players.json 的时间字段 → 毫秒；解析不了返回 0。
     * 兼容：ISO8601(带 Z/时区) / "yyyy-MM-dd HH:mm:ss Z" / "yyyy-MM-dd HH:mm:ss"
     *      / "yyyy-MM-dd" / 纯数字（10位=秒，13位=毫秒）。
     */
    private long parseBanTime(String s) {
        if (s == null) return 0L;
        String v = s.trim();
        if (v.isEmpty()) return 0L;
        if (v.equalsIgnoreCase("forever") || v.equals("永久")) return 0L;
        // 纯数字时间戳
        if (v.matches("\\d{10,13}")) {
            long n = Long.parseLong(v);
            return v.length() <= 10 ? n * 1000L : n;
        }
        String[] pats = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss Z",
                "yyyy-MM-dd HH:mm:ss ZZZZ",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd"};
        for (String pt : pats) {
            try {
                java.text.ParsePosition pos = new java.text.ParsePosition(0);
                Date d = new SimpleDateFormat(pt, Locale.ROOT).parse(v, pos);
                if (d != null && pos.getIndex() == v.length()) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }

    /**
     * ★ 第30批：黑名单数据源（群控黑名单 = 软折磨，与封禁公示并列）。
     * 复用 JsonBan 结构，source 标 "黑名单"，理由取黑名单条目的理由。
     */
    private List<JsonBan> collectBlacklistBans() {
        List<JsonBan> out = new ArrayList<>();
        BlacklistManager bl = plugin.getBlacklist();
        if (bl == null) return out;
        try {
            for (BlacklistManager.BlackEntry e : bl.getActiveEntries()) {
                if (e == null || e.name == null || e.name.isEmpty()) continue;
                out.add(new JsonBan(e.name,
                        e.reason != null && !e.reason.isEmpty()
                                ? e.reason : "违反服务器规则",
                        "黑名单",
                        e.createdMs > 0 ? e.createdMs : System.currentTimeMillis(),
                        e.expiresMs));
            }
        } catch (Exception ex) {
            warn("读取黑名单失败: " + ex.getMessage());
        }
        return out;
    }

    /** 轮播当前页并广播给全服 */
    private void broadcastOnce() {
        List<JsonBan> bans = collectBans();
        // ★ 第30批：并入黑名单玩家（独立数据源，不受 banned-players.json 是否存在影响）
        int blackCount = 0;
        try {
            List<JsonBan> bl = collectBlacklistBans();
            blackCount = bl.size();
            bans.addAll(bl);
        } catch (Exception ignored) {}
        if (bans.isEmpty()) { log("当前无生效封禁，跳过本轮"); return; }

        // 由近到远：最新封禁在前；由远到近：最早封禁在前
        // ★ 第33批(#213)：黑名单条目一律置顶（source="黑名单"），
        //   防止其被分页轮播淹没 —— 短时拉黑等不到自己那一页。
        bans.sort((a, b) -> {
            boolean ab = "黑名单".equals(a.source);
            boolean bb = "黑名单".equals(b.source);
            if (ab != bb) return ab ? -1 : 1;
            return mode == 1
                    ? Long.compare(b.createdMs, a.createdMs)     // 由近到远
                    : Long.compare(a.createdMs, b.createdMs);    // 由远到近
        });

        int total = bans.size();
        int start = cursor % total;
        int end = Math.min(start + pageSize, total);
        List<JsonBan> page = bans.subList(start, end);
        cursor = end % total;

        String head = "§c§l⚠ §6违规封禁公示 §7(共§f" + total + "§7人, 第§f"
                + (start / pageSize + 1) + "§7/" + ((total + pageSize - 1) / pageSize) + "§7页)";
        Bukkit.broadcastMessage(head);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (JsonBan e : page) {
            String target = e.name;
            String reason = (e.reason != null && !e.reason.isEmpty())
                    ? e.reason : "违反服务器规则";
            String when = e.createdMs > 0 ? df.format(new Date(e.createdMs)) : "时间未知";
            String expStr = e.expiresMs > 0 ? ("§7至§f" + df.format(new Date(e.expiresMs)))
                    : "§c永久";
            Bukkit.broadcastMessage("§c☠ §f" + target + " §7- " + reason
                    + " §8| §7封于§f" + when + " §8| " + expStr);
        }
        Bukkit.broadcastMessage("§7§o请各位玩家引以为戒，遵守服务器规则。");
        log("本轮已播报 " + page.size() + "/" + total + " 条封禁"
                + "（含黑名单 " + blackCount + " 条）"
                + "（本次随机间隔 " + lastDelaySec + "s，区间 "
                + minInterval + "~" + maxInterval + "s；banned-players.json 原始 "
                + lastFileRawCount + " 条，过滤后 " + lastFileKeptCount + " 条）");
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
        File bf = bannedPlayersFile();
        List<JsonBan> now = collectBans();
        to.sendMessage("§a[封禁播报] §7数据源=§f" + (bf == null ? "未定位"
                : bf.getName() + (bf.exists() ? "(§f" + bf.length() + "§7字节)" : "(§c不存在§7)"))
                + " §7有效封禁=§f" + now.size() + "§7条"
                + (now.isEmpty() ? " §8(无数据，保持沉默)" : ""));
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
