package Sdf1_login;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
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
 * 配置丢失 → 自动补发（saveResource 自愈）。
 *
 * 键值：轮播最低间隔(秒) / 轮播最大间隔(秒) / 播报模式 / 播报起点(插件安装之前的封禁不播)
 *   播报模式：true=由近到远、false=由远到近、0=关闭；
 *   表格值：由近到远 / 由远到近 / 0。
 *   间隔或模式为 0 → 关闭播报。
 */
public class BanBroadcastManager {

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

    public BanBroadcastManager(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 生命周期 ====================

    public void start() {
        banDir = new File(plugin.getDataFolder(), "ban");
        mdFile = new File(banDir, "规则配置.md");
        jsonFile = findJsonConfig();
        ensureConfigExists();
        reloadConfig();
        scheduleNext();
        long cut0 = resolveStartCutoff();
        plugin.getLogger().info("[封禁播报] 已启动，模式=" + modeName()
                + " 间隔=" + minInterval + "~" + maxInterval + "s 每页" + pageSize + "条"
                + " 起点=" + (cut0 > 0 ? new SimpleDateFormat("yyyy-MM-dd").format(new Date(cut0)) : "不限"));
    }

    public void shutdown() { /* BukkitRunnable 随插件卸载自动取消 */ }

    // ==================== 配置：自愈 + 双模式解析 ====================

    /** 找 ban/ 目录下任意 *.json 配置文件 */
    private File findJsonConfig() {
        File[] fs = banDir == null ? null : banDir.listFiles(
                (d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        return (fs != null && fs.length > 0) ? fs[0] : null;
    }

    /** 配置文件丢失就补一份（自愈） */
    private void ensureConfigExists() {
        jsonFile = findJsonConfig();   // ★ 每轮重查：运行中新增/删除的 json 都要跟上
        try {
            if (jsonFile != null) {
                if (!banDir.exists()) banDir.mkdirs();
                if (!jsonFile.exists()) {
                    plugin.saveResource("ban/" + jsonFile.getName(), false);
                    log("配置文件丢失，已自愈补发: " + jsonFile.getName());
                }
                return;
            }
            if (!mdFile.exists()) {
                banDir.mkdirs();
                plugin.saveResource("ban/规则配置.md", false);
                log("配置文件丢失，已自愈补发: 规则配置.md");
            }
        } catch (Exception e) {
            warn("自愈补发失败: " + e.getMessage());
        }
    }

    /** 重新加载配置（json 优先 → md json块 → md 表格 → 备份重置） */
    public void reloadConfig() {
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
                if (parseJsonText(txt)) { log("已加载 json 配置: " + jsonFile.getName()); return; }
                if (jsonSyntaxOk) {
                    // ★ 第十八批：语法有效但间隔值为空（文档语义「表格/json 二者选一填」）
                    //   → 继承 json 里已解析的播报模式/播报起点，间隔回退 md 表格
                    //   （只取间隔，绝不覆盖已继承的模式/起点）或出厂默认，不判损毁。
                    boolean iv = false;
                    if (mdFile != null && mdFile.exists()) {
                        String md0 = readFile(mdFile);
                        if (md0 != null) iv = parseIntervalTable(md0);
                    }
                    normalize();
                    log("json 配置语法有效，已继承模式/起点（间隔回退"
                            + (iv ? "md 表格" : "出厂默认") + "）: " + jsonFile.getName());
                    return;
                }
                // 语法真损坏 → 备份+移除，继续回退 md 分支（不再就此 return）
                backupAndReset(jsonFile, jsonFile.getName());
                jsonFile = null;
            }
            if (!mdFile.exists()) { ensureConfigExists(); }
            String md = readFile(mdFile);
            if (md != null) {
                String jsonBlock = extractJsonBlock(md);
                if (jsonBlock != null && parseJsonText(jsonBlock)) {
                    log("已加载 md 内嵌 json 配置: 规则配置.md"); return;
                }
                if (parseTableText(md)) { log("已加载 md 表格配置: 规则配置.md"); return; }
            }
            // 全部解码失败 → 备份原文件 + 释放一份新的
            backupAndReset(mdFile, "规则配置.md");
        } catch (Exception e) {
            warn("reloadConfig 异常: " + e.getMessage());
        }
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
            String val = cells[cells.length - 2].trim();
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
            String val = cells[cells.length - 2].trim();
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
            // 运行中把 0 改回正常值可自动恢复播报（无需重启）
            delaySec = 60;
        } else {
            // ★ 每次在[最小间隔,最大间隔]之间随机取一个秒数，不是固定值
            int span = maxInterval - minInterval;
            delaySec = minInterval + (span > 0 ? rnd.nextInt(span + 1) : 0);
            lastDelaySec = delaySec;
        }
        long delayTicks = Math.max(20L, delaySec * 20L);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) return;
                // ★ 服务器没人就闭嘴：无人在线时不播报、不重载、不自愈、不打任何日志，
                //   直接排下一轮（没人时播报和日志打出来也没人看）
                if (Bukkit.getOnlinePlayers().isEmpty()) {
                    scheduleNext();
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
