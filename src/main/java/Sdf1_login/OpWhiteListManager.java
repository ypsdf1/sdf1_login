package Sdf1_login;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * OP 白名单（2026-10-07）
 *
 * ★ 本插件【接管原生 OP 名单】：白名单文件是 OP 的唯一真相，原版 ops.json 只剩"存档"。
 *   - 加载时：把当前所有原生 OP 自动导入「无固定期限授权列表」并写回文件（一次性接管）；
 *   - 运行时：持 OP 但不在白名单（不在永久表 / 临时已过期 / 时长解析失败）=「非法持有」
 *     → 一律撤销 OP + 按「非法持有报警信息」模板报警（控制台 + 全体在线 OP）；
 *     报警带 {user}/{time} 变量（支持 user/player/用户/玩家、time/时间，中英文写法都认）。
 *   - 白名单内的在线玩家缺 OP 时自动补授（原版 /op 给的人若不在白名单，15 秒内会被撤）。
 *
 * 配置：独立目录 plugins/Sdf1_login/OP白名单/，内含白名单 json（任意 *.json 都认，
 * 按字典序取首个；模板 opwl.json 随 jar 释放）。独立成目录是为了隔离——目录里可放
 * 任意个 json 当配置，互不干扰；没有其它附属名单文件。
 * 该 json 同时是配置文件与说明书，除配置项外的其它字段一律忽略、写回时原样保留。
 *
 * 其他行为：
 *   - 读不出规则（无 json / JSON 语法错 / 必需字段类型错）→ 一次性卸载全部已知 OP，
 *     持续失败不重复执行，修复后自动恢复；
 *   - 启用状态=false → 功能休眠，不授权也不撤销；
 *   - 白名单 json 改写不查注册（强制手段）；opwl add / addtime 子命令查本服注册用户；
 *   - 15 秒一次文件指纹增量热重载：路径+lastModified+长度 变化才重解析并打日志；
 *   - 整点(:15/:30/:45)定点扫描：每 15 分钟做一轮「撤销非法持有 + 补授白名单」+ 心跳日志；
 *   - 2026-10-08：临时OP 存绝对到期时间（exp: 前缀），重载不再重算 → 修复「自动续期」；
 *   - 2026-10-08：上下任提示当事人（在线直发、离线暂存上线补发）；
 *   - 2026-10-08：新增 /opwl check <玩家>（仅注册校验，供 Main 侧 /op 放行判断）。
 *   - opwl 命令仅限控台（Main 侧对玩家静默 return，连帮助都不给）。
 *
 * 所有授权/撤销都在主线程执行（scheduler + 命令 + join 事件），无并发问题。
 */
public class OpWhiteListManager implements Listener {

    /** 报警节流：同一人 60 秒内不重复报警（防刷屏，但仍会撤销） */
    private static final long WARN_COOLDOWN_MS = 60_000L;

    /** ★ 原版 ops.json 备份（2026-10-08 数据安全）：卸载 OP 前必须先留底。
     *   NATIVE_OPS_PREFIX 用于让 pickJson() 把 ops.json 及其备份排除在配置之外；
     *   NATIVE_OPS_BACKUP 是留底文件名（落在 OP白名单/ 目录内）。 */
    private static final String NATIVE_OPS_PREFIX = "ops.json";
    private static final String NATIVE_OPS_BACKUP = "ops.json.bak";

    private final Main plugin;
    private final File dir;
    /** 当前被读的 json（pickJson 的结果，命令写回也写它） */
    private File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 当前白名单文件（= dataFolder/opwl.json） */
    private File watched;
    /** 解析成功后的完整配置（null = 解析失败态） */
    private JsonObject cfg;
    /** 解析失败标志：驱动「一次性全量卸载」，持续失败不重复卸 */
    private boolean parseFailed;
    /** 文件指纹（路径 + lastModified + 长度），变化才重载打日志 */
    private String lastPath = "";
    private long lastStamp = Long.MIN_VALUE;
    /** 报警节流表：小写用户名 → 上次报警毫秒 */
    private final Map<String, Long> lastWarn = new HashMap<>();
    /** 离线待投递提示：小写用户名 → 待发文案（上线时投递，最多留 2 条/人） */
    private final Map<String, java.util.List<String>> pendingMsg = new HashMap<>();

    public OpWhiteListManager(Main plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "OP白名单");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 模板随 jar 释放（只在目录里没有 json 时复制，绝不覆盖用户改过的文件）
        File tpl = new File(dir, "opwl.json");
        if (pickJson() == null && !tpl.exists()) {
            try (InputStream in = plugin.getResource("OP白名单/opwl.json")) {
                if (in != null) {
                    Files.copy(in, tpl.toPath());
                    plugin.getLogger().info("[OP白名单] 已释放配置模板: OP白名单/" + tpl.getName());
                } else {
                    plugin.getLogger().warning("[OP白名单] jar 内未找到模板 OP白名单/opwl.json，请手工在目录里放一个 .json");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[OP白名单] 模板释放失败: " + e.getMessage());
            }
        }
        // 接管原生 OP 名单：首次加载先把现有 OP 全导入白名单（一次性）
        reloadInternal(true, true);
        if (cfg != null) {
            importNativeOps();
        }
        // 整点扫描（:15/:30/:45）：周期 9000 tick = 15 分钟
        // 初始延迟 = 距下一个整点的 tick 数（ms/50）
        // 同时保留 15s 文件指纹热重载：改写 json 后最迟 15s 生效
        // 这里把两件事都调度起来：
        //   a) 15s tick 只做 checkAndReload()（指纹变化才重载+打日志，无变化静默 apply）
        //   b) 整点扫描做 checkAndReload() + apply()（撤销非法持有 + 补授权）
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkAndReload, 300L, 300L);
        long delayMs = millisToNextQuarterPoint();
        Bukkit.getScheduler().runTaskTimer(plugin, this::onQuarterPoint,
                (long) delayMs / 50L, 9000L);
    }

    // ================= 配置读取 =================

    /** 目录里任意一个 .json = 白名单文件；文件名按字典序取首个（确定性）。
     *  ★ 2026-10-08：排除 ops.json 及其备份（ops.json*）——那是我们留底的原版 OP 名单，
     *    结构与白名单完全不同，一旦被当成配置解析必然失败，进而误触发全量卸载。 */
    private File pickJson() {
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json")
                && !n.toLowerCase(Locale.ROOT).startsWith(NATIVE_OPS_PREFIX));
        if (fs == null || fs.length == 0) {
            return null;
        }
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return fs[0];
    }

    private void reloadInternal(boolean log, boolean importNative) {
        file = pickJson();
        watched = file;
        loadFrom(file, log);
        updateStamp();
        if (importNative && cfg != null) {
            importNativeOps();
        }
    }

    private void loadFrom(File f, boolean log) {
        if (f == null || !f.exists()) {
            fail("OP白名单目录里没有任何 .json 文件（plugins/Sdf1_login/OP白名单/）");
            return;
        }
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            JsonObject o = JsonParser.parseString(text).getAsJsonObject();
            JsonElement en = o.get("启用状态");
            if (en == null || !en.isJsonPrimitive() || !en.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("缺少布尔字段「启用状态」");
            }
            JsonElement pu = o.get("无固定期限授权列表");
            if (pu == null || !pu.isJsonArray()) {
                throw new IllegalArgumentException("「无固定期限授权列表」必须是数组 []");
            }
            JsonElement td = o.get("有固定期限授权列表");
            if (td == null || !td.isJsonObject()) {
                throw new IllegalArgumentException("「有固定期限授权列表」必须是对象 {}");
            }
            cfg = o;
            if (parseFailed) {
                plugin.getLogger().info("[OP白名单] 配置已恢复正常，恢复接管");
                // ★ 数据第一优先级：卸载前备份的原版 ops.json 在此复原，
                //   让当初被卸载的原版 OP 名单回到服务端（随后 apply() 按白名单再校准）
                restoreNativeOps();
            }
            parseFailed = false;
            if (log) {
                plugin.getLogger().info("[OP白名单] 已重载 opwl.json："
                        + (isWhitelistActive() ? "启用" : "停用") + "，无固定期限 "
                        + permanentList().size() + " 人，有固定期限 " + timedObject().size() + " 人");
            }
        } catch (Exception e) {
            fail(f.getName() + " 解析失败：" + e.getMessage());
        }
    }

    /** 计算距下一个整点(:00/:15/:30/:45)的毫秒数（最小 1ms）。 */
    private static long millisToNextQuarterPoint() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int sec = c.get(java.util.Calendar.SECOND);
        int min = c.get(java.util.Calendar.MINUTE);
        int mod = min % 15;
        // 本小时内的下一个整点分钟数
        int nextMinute;
        if (mod == 0) {
            // 已经正好在整点上（秒数可能为 0..59）
            if (sec == 0) {
                // 正好在整点这一刻，下一个是 15 分钟后
                nextMinute = min + 15;
            } else {
                nextMinute = min; // 本分钟（sec>0）就是整点，差 sec*1000
            }
        } else {
            nextMinute = min - mod + 15;
        }
        long nowMs = System.currentTimeMillis();
        // 算出本小时内的整点毫秒
        long curHourStart = nowMs - (c.get(java.util.Calendar.HOUR_OF_DAY) * 3600_000L
                + min * 60_000L + sec * 1000L + c.get(java.util.Calendar.MILLISECOND));
        long target = curHourStart + nextMinute * 60_000L;
        long delta = target - nowMs;
        if (delta <= 0) {
            delta += 15 * 60_000L; // 跨小时，取 15 分钟后
        }
        return delta;
    }

    /**
     * ★ 接管原生 OP：把当前所有 OP 且不在白名单的玩家，自动导入「无固定期限授权列表」。
     * 幂等——已在白名单的不动；已撤销的非法持有者不再是 OP，下次接管不会把他加回来。
     */
    private void importNativeOps() {
        List<String> add = new ArrayList<>();
        for (OfflinePlayer op : new ArrayList<>(Bukkit.getOperators())) {
            String n = op.getName();
            if (n == null || n.isEmpty() || !op.isOp()) {
                continue;
            }
            if (hasPermanent(n) || findTimed(n) != null) {
                continue;
            }
            add.add(n);
        }
        if (add.isEmpty()) {
            return;
        }
        for (String n : add) {
            permanentList().add(new JsonPrimitive(n));
        }
        saveConfig();
        plugin.getLogger().info("[OP白名单] 已接管原生 OP 名单：导入 " + add.size()
                + " 人为无固定期限授权 → " + String.join(", ", add));
    }{}

    /**
     * 解析失败 → 一次性卸载全部已知 OP（含离线），持续失败不重复执行。
     *
     * ★ 2026-10-08 数据第一优先级：卸载前必须先把【原版 ops.json】备份到我们目录。
     *   备份失败 → 一律保留现有 OP（宁可暂时不接管，也绝不能让管理员丢失全部原始 OP）；
     *   此时不置 parseFailed，留待下次解析失败时自动重试备份。
     */
    private void fail(String why) {
        cfg = null;
        if (parseFailed) {
            return;
        }
        // 第一步：先留底原版 ops.json（备份失败则直接放弃本次卸载）
        if (!backupNativeOps()) {
            lastWarn.clear();
            plugin.getLogger().warning("[OP白名单] " + why
                    + " —— 但原版 ops.json 备份失败，已【保留】全部现有 OP，本次不卸载。"
                    + "（数据优先，待备份成功后再接管；json 修复后仍会自动恢复）");
            return;
        }
        parseFailed = true;
        int n = 0;
        for (OfflinePlayer op : new ArrayList<>(Bukkit.getOperators())) {
            if (op.isOp()) {
                op.setOp(false);
                n++;
            }
        }
        // 第二步：清空原版 ops.json（setOp(false) 由服务端落盘，这里兜底确保真的清空）
        try {
            File opsFile = nativeOpsFile();
            if (opsFile.exists() && opsFile.length() > 0) {
                Files.write(opsFile.toPath(), "[]".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[OP白名单] 原版 ops.json 清空失败（不影响主流程）: "
                    + e.getMessage());
        }
        lastWarn.clear();
        plugin.getLogger().warning("[OP白名单] " + why
                + " —— 已一次性卸载全部 " + n + " 名玩家的 OP（原版 ops.json 已先备份为 "
                + NATIVE_OPS_BACKUP + "）；修复 json 后自动恢复接管并复原原版 OP。");
    }

    /** 原版 ops.json（服务端根目录，MC 自己维护的 OP 名单） */
    private File nativeOpsFile() {
        try {
            return new File(Bukkit.getWorldContainer(), "ops.json");
        } catch (Throwable t) {
            return new File("ops.json");
        }
    }

    /** 我们目录下的原版 ops.json 备份（pickJson 已排除，不会被当成配置解析） */
    private File nativeOpsBackup() {
        return new File(dir, NATIVE_OPS_BACKUP);
    }

    /**
     * ★ 卸载 OP 之前留底原版 ops.json。
     * 返回 true  = 备份成功，或原版本就没有内容（无数据可丢）→ 允许继续清空
     * 返回 false = 备份失败 → 调用方必须保留原 OP，绝不产生不可逆丢失
     */
    private boolean backupNativeOps() {
        File src = nativeOpsFile();
        File dst = nativeOpsBackup();
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            // 原版 ops.json 不存在或为空 = 没有内容可丢；已有历史备份则原样保留（绝不用空文件覆盖）
            if (!src.exists() || src.length() == 0) {
                return true;
            }
            Files.copy(src.toPath(), dst.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (dst.length() != src.length()) {
                plugin.getLogger().severe("[OP白名单] 原版 ops.json 备份校验失败（字节数不一致: 源 "
                        + src.length() + " / 备份 " + dst.length() + "）→ 本次不卸载 OP，保留原名单");
                return false;
            }
            plugin.getLogger().info("[OP白名单] 原版 ops.json 已备份 → OP白名单/"
                    + NATIVE_OPS_BACKUP + "（" + dst.length() + " 字节）");
            return true;
        } catch (Exception e) {
            plugin.getLogger().severe("[OP白名单] 原版 ops.json 备份失败: " + e.getMessage()
                    + " → 按「数据第一优先级」，本次【不卸载】任何人的 OP");
            return false;
        }
    }

    /**
     * ★ json 修复后复原原版 OP：把留底的 ops.json 还原回服务端。
     *   备份缺失/不可读时静默跳过，绝不阻断恢复接管。
     */
    private void restoreNativeOps() {
        File bak = nativeOpsBackup();
        if (!bak.exists() || bak.length() == 0) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(bak.toPath()), StandardCharsets.UTF_8).trim();
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonArray()) {
                plugin.getLogger().warning("[OP白名单] 备份 " + NATIVE_OPS_BACKUP
                        + " 不是 JSON 数组，跳过复原");
                return;
            }
            int n = 0;
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject o = e.getAsJsonObject();
                java.util.UUID id = null;
                if (o.has("id") && o.get("id").isJsonPrimitive()) {
                    try {
                        id = java.util.UUID.fromString(o.get("id").getAsString());
                    } catch (Exception ignore) { /* id 非法则退回按名字复原 */ }
                }
                String name = (o.has("name") && o.get("name").isJsonPrimitive())
                        ? o.get("name").getAsString() : null;
                OfflinePlayer op = (id != null)
                        ? Bukkit.getOfflinePlayer(id)
                        : (name != null && !name.isEmpty() ? Bukkit.getOfflinePlayer(name) : null);
                if (op == null || op.isOp()) {
                    continue;
                }
                op.setOp(true);
                n++;
            }
            plugin.getLogger().info("[OP白名单] 已从备份复原原版 OP " + n + " 人（"
                    + NATIVE_OPS_BACKUP + "），随后按白名单校准");
        } catch (Exception ex) {
            plugin.getLogger().warning("[OP白名单] 从备份复原原版 OP 失败: " + ex.getMessage());
        }
    }

    /** 文件指纹：路径 + lastModified + 长度（任一变化都视为变更） */
    private void updateStamp() {
        File f = pickJson();
        if (f == null) {
            lastPath = "";
            lastStamp = 0L;
            return;
        }
        lastPath = f.getAbsolutePath();
        lastStamp = f.lastModified() + f.length();
    }

    // ================= 整点扫描（15/30/45 定点） =================
    // 文件指纹热重载保留在 checkAndReload() 内：每个扫描点先比指纹，
    // 有变化才重新解析 + 打日志；没变化就静默应用。

    /** 检查文件指纹，有变化则重载，然后静默应用。 */
    private void checkAndReload() {
        File f = pickJson();
        String p = f == null ? "" : f.getAbsolutePath();
        long st = f == null ? 0L : f.lastModified() + f.length();
        if (!p.equals(lastPath) || st != lastStamp) {
            file = f;
            watched = f;
            loadFrom(f, true); // 有变化：重新解析 + 打日志（含解析失败告警）
            updateStamp();
        }
        // 无论有无变化都做一次静默应用（撤销非法持有 + 补授权）
        apply();
    }

    /** 每个整点（:15/:30/:45）执行一次扫描 + 补授 + 心跳日志。 */
    private void onQuarterPoint() {
        checkAndReload();
        plugin.getLogger().info("[OP白名单] 扫描点 " + nowHM()
                + " | 无固定期限 " + (cfg == null ? "?" : String.valueOf(permanentList().size()))
                + " | 有固定期限 " + (cfg == null ? "?" : String.valueOf(timedObject().size()))
                + " | 持OP未入白 " + countIllegal());
    }

    private static String nowHM() {
        return new SimpleDateFormat("HH:mm").format(new Date());
    }

    /** 当前持 OP 但不在白名单的人数（只数不撤，供心跳日志） */
    private int countIllegal() {
        if (cfg == null || !isWhitelistActive()) {
            return 0;
        }
        int n = 0;
        for (OfflinePlayer op : Bukkit.getOperators()) {
            String nm = op.getName();
            if (nm != null && !nm.isEmpty() && op.isOp() && !isWhitelisted(nm)) {
                n++;
            }
        }
        return n;
    }

    /** 玩家上线：立即检查指纹 + 应用一次 + 投递离线暂存的上下任提示 */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        checkAndReload(); // 顺带一次指纹检查，改完 json 就上线立即生效
        flushPendingMsgs(e.getPlayer());
    }

    // ================= 接管：撤销非法持有 + 补授权 =================

    private void apply() {
        if (cfg == null || !isWhitelistActive()) {
            return;
        }
        long now = System.currentTimeMillis();
        // 1) 非法持有扫描：持 OP 但不在白名单 → 撤销 + 报警
        for (OfflinePlayer op : new ArrayList<>(Bukkit.getOperators())) {
            String n = op.getName();
            if (n == null || n.isEmpty() || !op.isOp()) {
                continue;
            }
            if (isWhitelisted(n)) {
                continue;
            }
            setOpByName(n, false);
            warnIllegal(n, now, describeIllegal(n));
            // 卸任提示当事人（在线直发，离线暂存）
            notifyOpChange(n, false, "自动撤销（不在白名单：" + describeIllegal(n) + "）");
        }
        // 2) 白名单内在线玩家补授（已是 OP 的跳过）
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || !isWhitelisted(p.getName())) {
                continue;
            }
            p.setOp(true);
            plugin.getLogger().info("[OP白名单] 已授权 " + p.getName() + " 获得 OP");
            // 上任提示当事人
            notifyOpChange(p.getName(), true,
                    hasPermanent(p.getName()) ? "长期OP（无固定期限）"
                            : "临时OP（到期 " + expDisplay(findTimed(p.getName())) + "）");
        }
    }

    /** 撤销非法持有 + 报警（控制台 + 全体在线 OP），带 60 秒节流 */
    private void warnIllegal(String name, long now, String reason) {
        String key = name.toLowerCase(Locale.ROOT);
        long last = lastWarn.getOrDefault(key, 0L);
        if (last > 0 && now - last < WARN_COOLDOWN_MS) {
            return;
        }
        lastWarn.put(key, now);
        String alarm = renderAlarm(name, now);
        plugin.getLogger().warning("[OP白名单] 非法持有已撤销 | 原因: " + reason + " | " + stripColor(alarm));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                p.sendMessage(alarm);
            }
        }
    }

    /** 报警模板渲染：{user}/{player}/{用户}/{玩家} 与 {time}/{时间} 变量（大小写不敏感） */
    private String renderAlarm(String user, long now) {
        String tpl = alarmTemplate();
        String time = fmtFull(now);
        String s = tpl;
        for (String k : new String[]{"user", "player", "用户", "玩家", "username"}) {
            s = s.replaceAll("(?i)\\{" + k + "\\}", Matcher.quoteReplacement(user));
        }
        for (String k : new String[]{"time", "时间", "date", "日期"}) {
            s = s.replaceAll("(?i)\\{" + k + "\\}", Matcher.quoteReplacement(time));
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 报警模板：配置项「非法持有报警信息」，空则用内置默认（中英双语） */
    private String alarmTemplate() {
        if (cfg != null && cfg.has("非法持有报警信息")
                && cfg.get("非法持有报警信息").isJsonPrimitive()) {
            String s = cfg.get("非法持有报警信息").getAsString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "§8[§cOP白名单§8] §f{user} §7不持有授权却持有 OP，已于 §f{time} §7自动撤销"
                + " §8| §8[OPWL] §f{user} §7held OP illegally, revoked at §f{time}";
    }

    /** 撤销原因（仅日志用，便于排查是哪一类"不在白名单"） */
    private String describeIllegal(String name) {
        if (findTimed(name) != null) {
            return "临时授权已过期或时长无法解析（" + findTimed(name) + "）";
        }
        return "不在白名单（" + (Bukkit.getPlayerExact(name) != null ? "在线" : "离线") + "）";
    }

    private void setOpByName(String name, boolean op) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            p.setOp(op);
            return;
        }
        Bukkit.getOfflinePlayer(name).setOp(op);
    }

    // ================= 配置字段辅助 =================

    /** 内部启用判断（配置有效且「启用状态」= true） */
    private boolean isWhitelistActive() {
        return cfg != null && cfg.get("启用状态").getAsBoolean();
    }

    /** 公开：白名单是否启用（供 Main 侧 /op 拦截分支；解析失败/未配置 = false = 未接管） */
    public boolean isEnabled() {
        return isWhitelistActive();
    }

    private JsonArray permanentList() {
        return cfg.getAsJsonArray("无固定期限授权列表");
    }

    private JsonObject timedObject() {
        return cfg.getAsJsonObject("有固定期限授权列表");
    }

    /** 大小写不敏感地找无固定期限条目 */
    private boolean hasPermanent(String name) {
        for (JsonElement e : permanentList()) {
            if (e.isJsonPrimitive() && name.equalsIgnoreCase(e.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** 大小写不敏感地找有固定期限条目，返回其值（时长/到期时间原文），无则 null */
    private String findTimed(String name) {
        for (Map.Entry<String, JsonElement> en : timedObject().entrySet()) {
            if (name.equalsIgnoreCase(en.getKey()) && en.getValue().isJsonPrimitive()) {
                return en.getValue().getAsString();
            }
        }
        return null;
    }

    /** 有固定期限条目里实际存储的规范 key（大小写不敏感），无则返回入参 */
    private String timedKeyOf(String name) {
        for (String k : timedObject().keySet()) {
            if (name.equalsIgnoreCase(k)) {
                return k;
            }
        }
        return name;
    }

    private boolean removePermanent(String name) {
        Iterator<JsonElement> it = permanentList().iterator();
        while (it.hasNext()) {
            JsonElement e = it.next();
            if (e.isJsonPrimitive() && name.equalsIgnoreCase(e.getAsString())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    private boolean removeTimed(String name) {
        for (String key : new ArrayList<>(timedObject().keySet())) {
            if (name.equalsIgnoreCase(key)) {
                timedObject().remove(key);
                return true;
            }
        }
        return false;
    }

    /** 是否白名单有效成员：永久 或 有期限且未过期（坏时长视为无效 → 算非法持有） */
    private boolean isWhitelisted(String name) {
        if (cfg == null) {
            return false;
        }
        if (hasPermanent(name)) {
            return true;
        }
        String t = findTimed(name);
        if (t == null) {
            return false;
        }
        long e = expireOf(t);
        return e > 0 && e > System.currentTimeMillis();
    }

    /** 解析为绝对到期毫秒；解析失败返回 -1（调用方按「无效」保守处理）。
     *  2026-10-08 起新写入用「exp:<毫秒时间戳>」（绝对时间，重载不重算 → 不再自动续期）；
     *  旧格式（相对时长「1m」、绝对日期「2026年7月9日」）仍按解析时刻重算。 */
    private long expireOf(String v) {
        if (v != null && v.startsWith("exp:")) {
            try {
                return Long.parseLong(v.substring(4).trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }
        try {
            return DurationParser.parseToExpireMs(v);
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 把绝对到期毫秒序列化成存储值 */
    private static String expStore(long ms) {
        return "exp:" + ms;
    }

    /** 显示到期时间（人类可读） */
    private String expDisplay(String v) {
        long e = expireOf(v);
        return e > 0 ? fmt(e) : "无法解析(" + v + ")";
    }

    private String defaultDuration() {
        if (cfg != null && cfg.has("默认时长") && cfg.get("默认时长").isJsonPrimitive()) {
            String s = cfg.get("默认时长").getAsString().trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "24h";
    }

    // ================= 持久化 =================

    /** 写回 json：保留配置项之外的所有说明书/混淆字段，只动配置项 */
    private void saveConfig() {
        try {
            File target = (watched != null && watched.exists()) ? watched : pickJson();
            if (target == null) {
                target = new File(dir, "opwl.json");
                watched = target;
            }
            Files.write(target.toPath(), gson.toJson(cfg).getBytes(StandardCharsets.UTF_8));
            updateStamp(); // 命令自己有回馈，抑制下个 tick 再打一次「已重载」
        } catch (Exception e) {
            plugin.getLogger().warning("[OP白名单] 配置写回失败: " + e.getMessage());
        }
    }

    // ================= opwl 命令（仅控台；Main 已拦玩家） =================

    public void onCommand(org.bukkit.command.CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].trim().toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "help":
            case "?":
                sendHelp(sender);
                return;
            case "reload": {
                reloadInternal(true, false);
                apply();
                sender.sendMessage(cfg == null
                        ? "§c重载完成，但配置解析失败（详见控制台告警）"
                        : "§a重载完成：§f" + (isWhitelistActive() ? "启用" : "停用")
                        + "，无固定期限 " + permanentList().size()
                        + " 人，有固定期限 " + timedObject().size() + " 人");
                sender.sendMessage("§7提示：原生 OP 只在插件【首次加载】时导入；之后请用 /opwl add 或改 json 加人");
                return;
            }
            default:
                break;
        }
        // 以下子命令需要配置可读
        if (cfg == null) {
            sender.sendMessage("§c配置解析失败，先修复 OP白名单/ 目录里的 json 再操作（或 /opwl reload）");
            return;
        }
        boolean readOnly = "list".equals(sub);
        if (!isWhitelistActive() && !readOnly) {
            sender.sendMessage("§cOP白名单已停用（「启用状态」= false），先启用再操作");
            return;
        }
        switch (sub) {
            case "add": {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl add <玩家> §7（被授权人须为本服注册用户；"
                            + "非注册请直接改 §fOP白名单/opwl.json§7 强制授权）");
                    return;
                }
                String name = args[1].trim();
                if (!isRegistered(name)) {
                    sender.sendMessage("§c" + name + " 不是本服注册用户，拒绝授权");
                    sender.sendMessage("§7强制授权：请直接编辑 §fOP白名单/opwl.json§7，"
                            + "把名字加进「无固定期限授权列表」或「有固定期限授权列表」（json 为强制手段，不查注册）");
                    return;
                }
                boolean moved = removeTimed(name);
                if (!hasPermanent(name)) {
                    permanentList().add(new JsonPrimitive(name));
                }
                saveConfig();
                lastWarn.remove(name.toLowerCase(Locale.ROOT));
                boolean ok = setOpNow(name);
                if (ok) {
                    notifyOpChange(name, true,
                            moved ? "长期OP（原临时授权已转为永久）" : "长期OP（无固定期限）");
                }
                sender.sendMessage("§a已添加 §f" + name + " §a为无固定期限授权"
                        + (moved ? "（已把原临时授权转为永久）" : "")
                        + (ok ? "，OP 已授予" : "（玩家不在本机档案中，上线时自动授予）"));
                return;
            }
            case "addtime": {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl addtime <玩家> [时长] §7（缺省 "
                            + defaultDuration() + "；被授权人须为本服注册用户；"
                            + "非注册请直接改 json 强制授权）");
                    return;
                }
                String name = args[1].trim();
                if (!isRegistered(name)) {
                    sender.sendMessage("§c" + name + " 不是本服注册用户，拒绝授权");
                    sender.sendMessage("§7强制授权：请直接编辑 §fOP白名单/opwl.json§7，"
                            + "在「有固定期限授权列表」里写 \"" + name + "\": \"<时长>\"（json 为强制手段，不查注册）");
                    return;
                }
                String dur = args.length >= 3 ? joinFrom(args, 2) : defaultDuration();
                long exp;
                try {
                    exp = DurationParser.parseToExpireMs(dur);
                } catch (Exception e) {
                    sender.sendMessage("§c时长无法解析: " + dur + " §7（" + e.getMessage() + "）");
                    return;
                }
                if (exp <= System.currentTimeMillis()) {
                    sender.sendMessage("§c该时长的到期时间已在过去: " + dur);
                    return;
                }
                removePermanent(name);
                // 2026-10-08 修复：存绝对到期时间（exp: 前缀），重载不再重算 → 不再自动续期
                timedObject().addProperty(timedKeyOf(name), expStore(exp));
                saveConfig();
                lastWarn.remove(name.toLowerCase(Locale.ROOT));
                boolean wasOp = isOpNow(name);
                boolean ok = setOpNow(name);
                if (ok) {
                    notifyOpChange(name, true,
                            wasOp ? "临时OP（续期，到期 " + fmt(exp) + "）"
                                  : "临时OP（新授予，到期 " + fmt(exp) + "）");
                }
                sender.sendMessage("§a已给 §f" + name + " §a添加临时OP §7（时长 " + dur
                        + "，到期 " + fmt(exp) + "，已固化为绝对时间、不再续期）"
                        + (ok ? "，OP 已授予" : "，上线时自动授予"));
                return;
            }
            case "removetime": {
                if (args.length < 3) {
                    sender.sendMessage("§e用法: §f/opwl removetime <玩家> <扣减时长> §7（例: /opwl removetime "
                            + (args.length > 1 ? args[1] : "user") + " 1h）");
                    return;
                }
                String name = args[1].trim();
                if (hasPermanent(name)) {
                    sender.sendMessage("§c" + name + " 是永久授权，没有时长可扣；取消请用 /opwl remove " + name);
                    return;
                }
                String cur = findTimed(name);
                if (cur == null) {
                    sender.sendMessage("§c" + name + " 没有临时授权，无时长可扣");
                    return;
                }
                long oldExp = expireOf(cur);
                if (oldExp <= 0) {
                    sender.sendMessage("§c当前授权的到期时间无法解析: " + cur + "（请手工修正 json）");
                    return;
                }
                String cut = joinFrom(args, 2);
                long delta;
                try {
                    delta = DurationParser.parseToExpireMs(cut) - System.currentTimeMillis();
                } catch (Exception e) {
                    sender.sendMessage("§c扣减时长无法解析: " + cut);
                    return;
                }
                if (delta <= 0) {
                    sender.sendMessage("§c扣减时长无法解析（需要相对时长，如 30m / 1h / 两小时）: " + cut);
                    return;
                }
                long newExp = oldExp - delta;
                if (newExp <= System.currentTimeMillis()) {
                    removeTimed(name);
                    saveConfig();
                    if (isOpNow(name)) {
                        setOpByName(name, false);
                        notifyOpChange(name, false, "临时OP被扣减至到期，已撤销");
                    }
                    sender.sendMessage("§a扣减后 " + name + " 的临时OP已到期，条目已移除，OP 已撤销");
                } else {
                    // 2026-10-08：扣减后写回 exp: 绝对时间（不再存解析后的日期字符串）
                    timedObject().addProperty(timedKeyOf(name), expStore(newExp));
                    saveConfig();
                    sender.sendMessage("§a" + name + " 到期时间已调整为 §f" + fmt(newExp)
                            + " §7（剩余 " + human(newExp - System.currentTimeMillis()) + "）");
                }
                return;
            }
            case "remove": {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl remove <玩家>");
                    return;
                }
                String name = args[1].trim();
                boolean inPerm = removePermanent(name);
                boolean inTimed = removeTimed(name);
                saveConfig();
                boolean wasOp = isOpNow(name);
                setOpByName(name, false);
                if (wasOp) {
                    notifyOpChange(name, false, inTimed ? "临时OP被移除" : "长期OP被移除");
                }
                lastWarn.remove(name.toLowerCase(Locale.ROOT));
                if (!inPerm && !inTimed && !wasOp) {
                    sender.sendMessage("§e" + name + " 本就不在白名单中，也没有 OP");
                } else {
                    sender.sendMessage("§a已移除 §f" + name + " §a的授权"
                            + (wasOp ? "，OP 已撤销" : ""));
                    sender.sendMessage("§7提示：若要长用请 /opwl add " + name + "，否则他的 OP 会被自动接管撤销");
                }
                return;
            }
            case "list": {
                if (args.length >= 2) {
                    listOne(sender, args[1].trim());
                    return;
                }
                sender.sendMessage("§e===== OP白名单（接管原生 OP） =====");
                sender.sendMessage("§7配置: §fOP白名单/" + (watched != null ? watched.getName() : "?")
                        + " §7| 状态: "
                        + (isWhitelistActive() ? "§a启用" : "§c停用")
                        + " §7| 解析: " + (parseFailed ? "§c失败" : "§a正常"));
                JsonArray pu = permanentList();
                StringBuilder sb = new StringBuilder("§e无固定期限(" + pu.size() + "): ");
                boolean first = true;
                for (JsonElement e : pu) {
                    if (!e.isJsonPrimitive()) {
                        continue;
                    }
                    sb.append(first ? "§f" : "§7, §f").append(e.getAsString());
                    first = false;
                }
                sender.sendMessage(sb.toString());
                JsonObject td = timedObject();
                sender.sendMessage("§e有固定期限(" + td.size() + "):");
                long now = System.currentTimeMillis();
                if (td.size() == 0) {
                    sender.sendMessage("§7  （空）");
                }
                for (Map.Entry<String, JsonElement> en : td.entrySet()) {
                    String v = en.getValue().isJsonPrimitive() ? en.getValue().getAsString() : "?";
                    long e = expireOf(v);
                    String state = e <= 0 ? "§c到期时间无法解析: " + v
                            : (e > now ? "§7到期 §f" + fmt(e) + " §7(剩 " + human(e - now) + ")"
                            : "§c已过期 " + fmt(e) + "（OP 将被撤销）");
                    sender.sendMessage("§7  " + en.getKey() + " → " + state);
                }
                // 额外：谁在偷偷持有 OP（白名单外的）
                List<String> illegal = new ArrayList<>();
                for (OfflinePlayer op : new ArrayList<>(Bukkit.getOperators())) {
                    String n = op.getName();
                    if (n != null && !n.isEmpty() && op.isOp() && !isWhitelisted(n)) {
                        illegal.add(n);
                    }
                }
                sender.sendMessage("§e当前持 OP 但不在白名单(" + illegal.size() + "): "
                        + (illegal.isEmpty() ? "§a无" : "§c" + String.join("§7, §c", illegal)));
                return;
            }
            case "check": {
                // 仅注册校验（不查白名单）：供 Main 侧 /op 放行判断
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl check <玩家> §7（仅查本服注册，不查白名单）");
                    return;
                }
                String name = args[1].trim();
                boolean reg = isRegistered(name);
                sender.sendMessage((reg ? "§a" : "§c") + name + " "
                        + (reg ? "是本服注册用户" : "不是本服注册用户（/op 放行将拦截）"));
                return;
            }
            default:
                sendHelp(sender);
        }
    }

    // ================= 上下任提示（当事人视角） =================

    /** 提示当事人 OP 上任/卸任。在线直发；离线暂存 pendingMsg，上线时补发。 */
    public void notifyOpChange(String name, boolean isGrant, String what) {
        if (name == null || name.isEmpty()) return;
        Player p = Bukkit.getPlayerExact(name);
        String msg = isGrant
                ? "§a[OP白名单] 你已获得 §f" + what + "§a。"
                : "§c[OP白名单] 你的 §f" + what + " §c已撤销，OP 权限已收回。";
        if (p != null) {
            p.sendMessage(msg);
        } else {
            java.util.List<String> buf = pendingMsg.computeIfAbsent(
                    name.toLowerCase(Locale.ROOT), k -> new java.util.ArrayList<>());
            if (buf.size() >= 2) buf.remove(0);
            buf.add(msg);
            plugin.getLogger().info("[OP白名单] 离线暂存提示给 " + name + ": " + stripColor(msg));
        }
    }

    /** 上线时投递暂存的提示 */
    public void flushPendingMsgs(Player p) {
        if (p == null) return;
        java.util.List<String> buf =
                pendingMsg.remove(p.getName().toLowerCase(Locale.ROOT));
        if (buf == null || buf.isEmpty()) return;
        for (String m : buf) {
            p.sendMessage(m);
        }
    }

    // ================= 注册检查 =================

    /** 查被授权人是不是本服务器注册用户（DatabaseManager.userExistsIgnoreCase）。
     *  查不到 / db 未就绪 一律返回 false（保守拒绝，让运维改 json 兜底）。 */
    private boolean isRegistered(String name) {
        if (name == null || name.isEmpty()) return false;
        DatabaseManager db = plugin.getDb();
        if (db == null) return false;
        try {
            return db.userExistsIgnoreCase(name);
        } catch (Throwable t) {
            plugin.getLogger().warning("[OP白名单] 注册检查异常: " + t.getMessage());
            return false;
        }
    }

    // ================= 命令辅助 =================

    /** 立即授 OP；返回 false = 档案未知（上线时由 join 自动补授） */
    private boolean setOpNow(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            p.setOp(true);
            return true;
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(name);
        if (!o.hasPlayedBefore()) {
            return false;
        }
        o.setOp(true);
        return true;
    }

    private boolean isOpNow(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.isOp() : Bukkit.getOfflinePlayer(name).isOp();
    }

    private void listOne(org.bukkit.command.CommandSender s, String name) {
        s.sendMessage("§e" + name + " 授权状态：");
        String t = findTimed(name);
        if (hasPermanent(name)) {
            s.sendMessage("§7  类型: §f无固定期限");
        } else if (t != null) {
            long e = expireOf(t);
            long now = System.currentTimeMillis();
            if (e > 0) {
                s.sendMessage("§7  类型: §f临时 §7到期 §f" + fmt(e)
                        + (e > now ? " §7(剩 " + human(e - now) + ")" : " §c(已过期，OP 会被撤销)")
                        + (t.startsWith("exp:") ? "" : " §8(旧格式，下次修改后固化为绝对时间)"));
            } else {
                s.sendMessage("§7  类型: §f临时 §c（到期时间无法解析: " + t + "，OP 会被撤销）");
            }
        } else {
            s.sendMessage("§7  类型: §c不在白名单（持有 OP 会被自动撤销并报警）");
        }
        s.sendMessage("§7  当前OP: " + (isOpNow(name) ? "§f是" : "§f否"));
    }

    private void sendHelp(org.bukkit.command.CommandSender s) {
        s.sendMessage("§e===== OP白名单（仅控制台可用，接管原生 OP） =====");
        s.sendMessage("§f/opwl §7- 本帮助");
        s.sendMessage("§f/opwl add <玩家> §7- 添加无固定期限授权");
        s.sendMessage("§f/opwl addtime <玩家> [时长] §7- 添加临时授权（缺省 " + defaultDuration() + "）");
        s.sendMessage("§f/opwl removetime <玩家> <扣减时长> §7- 扣减授权时长（如 1h）");
        s.sendMessage("§f/opwl remove <玩家> §7- 移除授权并撤销其 OP");
        s.sendMessage("§f/opwl list [玩家] §7- 查看白名单 / 非法持有者 / 单人状态");
        s.sendMessage("§f/opwl reload §7- 重载配置");
        s.sendMessage("§f/opwl check <玩家> §7- 仅查本服注册（/op 放行判断用，不查白名单）");
        s.sendMessage("§7上下任提示: 临时/长期 OP 授予或撤销时当事人会收到提示（离线者上线时补发）");
        s.sendMessage("§7临时OP 到期时间固化为绝对时间（exp: 前缀），重载不会续期");
        s.sendMessage("§7配置目录: plugins/Sdf1_login/OP白名单/（独立目录，白名单 json 全在里面）");
        s.sendMessage("§7扫描节奏: 每 15 秒一次文件指纹热重载 + 整点(:15/:30/:45)定点扫描/补授权");
        s.sendMessage("§7注册限制: /opwl add 只授权本服注册用户；非注册请改 json（强制手段，不查注册）");
        s.sendMessage("§7报警模板变量: §f{user}§7=玩家名 §f{time}§7=发现时间（中英文写法均可）");
        s.sendMessage("§d欢迎来到草原探险服务器，ip: mc2.ypshidifu.cn 端口(基岩版需要):30679");
    }

    /** args[idx..] 用空格拼回（兼容 "one hour" 这类带空格时长） */
    private String joinFrom(String[] args, int idx) {
        StringBuilder sb = new StringBuilder();
        for (int i = idx; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString().trim();
    }

    private static String fmt(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ms));
    }

    private static String fmtFull(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
    }

    /** 剩余时长人性化：X天X小时X分 */
    private static String human(long ms) {
        long min = ms / 60000L;
        if (min < 1) {
            return "不到1分钟";
        }
        long d = min / 1440L;
        long h = (min % 1440L) / 60L;
        long m = min % 60L;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("天");
        }
        if (h > 0) {
            sb.append(h).append("小时");
        }
        if (m > 0 && d == 0) {
            sb.append(m).append("分");
        }
        return sb.length() == 0 ? "不到1分钟" : sb.toString();
    }

    /** 日志里去掉颜色符号，避免控制台刷屏乱码 */
    private static String stripColor(String s) {
        return s == null ? "" : s.replaceAll("(?i)&[0-9a-k]", "").replaceAll("\u00a7[0-9a-k]", "");
    }
}