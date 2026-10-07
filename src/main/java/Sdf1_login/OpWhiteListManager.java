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
 * 配置：单文件 plugins/Sdf1_login/opwl.json（模板随 jar 释放，不再有子目录/附属文件）。
 * 该 json 同时是配置文件与说明书，除配置项外的其它字段一律忽略、写回时原样保留。
 *
 * 其他行为：
 *   - 读不出规则（无 json / JSON 语法错 / 必需字段类型错）→ 一次性卸载全部已知 OP，
 *     持续失败不重复执行，修复后自动恢复；
 *   - 启用状态=false → 功能休眠，不授权也不撤销；
 *   - 15 秒一次增量热重载：文件指纹（路径+lastModified+长度）变化才重解析并打日志；
 *   - opwl 命令仅限控台（Main 侧对玩家静默 return，连帮助都不给）。
 *
 * 所有授权/撤销都在主线程执行（scheduler + 命令 + join 事件），无并发问题。
 */
public class OpWhiteListManager implements Listener {

    /** 报警节流：同一人 60 秒内不重复报警（防刷屏，但仍会撤销） */
    private static final long WARN_COOLDOWN_MS = 60_000L;

    private final Main plugin;
    private final File file;
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

    public OpWhiteListManager(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "opwl.json");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        // 模板随 jar 释放（只在不存在时复制，绝不覆盖用户改过的文件）
        if (!file.exists()) {
            try (InputStream in = plugin.getResource("opwl.json")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                    plugin.getLogger().info("[OP白名单] 已释放配置模板: plugins/Sdf1_login/opwl.json");
                } else {
                    plugin.getLogger().warning("[OP白名单] jar 内未找到模板 opwl.json，请手工放置该文件");
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
        // 15 秒增量热重载 + 静默应用
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 300L, 300L);
    }

    // ================= 配置读取 =================

    private void reloadInternal(boolean log, boolean importNative) {
        watched = file;
        loadFrom(file, log);
        updateStamp();
        if (importNative && cfg != null) {
            importNativeOps();
        }
    }

    private void loadFrom(File f, boolean log) {
        if (f == null || !f.exists()) {
            fail("OP白名单配置文件不存在（plugins/Sdf1_login/opwl.json）");
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
            }
            parseFailed = false;
            if (log) {
                plugin.getLogger().info("[OP白名单] 已重载 opwl.json："
                        + (isEnabled() ? "启用" : "停用") + "，无固定期限 "
                        + permanentList().size() + " 人，有固定期限 " + timedObject().size() + " 人");
            }
        } catch (Exception e) {
            fail("opwl.json 解析失败：" + e.getMessage());
        }
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
    }

    /** 解析失败 → 一次性卸载全部已知 OP（含离线），持续失败不重复执行 */
    private void fail(String why) {
        cfg = null;
        if (parseFailed) {
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
        lastWarn.clear();
        plugin.getLogger().warning("[OP白名单] " + why
                + " —— 已一次性卸载全部 " + n + " 名玩家的 OP；修复 json 后自动恢复接管。");
    }

    /** 文件指纹：路径 + lastModified + 长度（任一变化都视为变更） */
    private void updateStamp() {
        lastPath = file.getAbsolutePath();
        lastStamp = file.exists() ? file.lastModified() + file.length() : 0L;
    }

    // ================= 15 秒 tick =================

    private void tick() {
        long st = file.exists() ? file.lastModified() + file.length() : 0L;
        if (!lastPath.equals(file.getAbsolutePath()) || st != lastStamp) {
            loadFrom(file, true); // 有变化：重新解析 + 打日志（含解析失败告警）
            updateStamp();
        }
        // 无论有无变化都做一次静默应用（撤销非法持有 + 补授权），保持安静
        apply();
    }

    /** 玩家上线：立即应用一次（白名单内马上拿到 OP，不等下个 15 秒） */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply();
    }

    // ================= 接管：撤销非法持有 + 补授权 =================

    private void apply() {
        if (cfg == null || !isEnabled()) {
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
        }
        // 2) 白名单内在线玩家补授（已是 OP 的跳过）
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() || !isWhitelisted(p.getName())) {
                continue;
            }
            p.setOp(true);
            plugin.getLogger().info("[OP白名单] 已授权 " + p.getName() + " 获得 OP");
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

    private boolean isEnabled() {
        return cfg != null && cfg.get("启用状态").getAsBoolean();
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

    /** 解析为绝对到期毫秒；解析失败返回 -1（调用方按「无效」保守处理） */
    private long expireOf(String v) {
        try {
            return DurationParser.parseToExpireMs(v);
        } catch (Exception e) {
            return -1L;
        }
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
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Files.write(file.toPath(), gson.toJson(cfg).getBytes(StandardCharsets.UTF_8));
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
                        : "§a重载完成：§f" + (isEnabled() ? "启用" : "停用")
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
            sender.sendMessage("§c配置解析失败，先修复 plugins/Sdf1_login/opwl.json 再操作（或 /opwl reload）");
            return;
        }
        boolean readOnly = "list".equals(sub);
        if (!isEnabled() && !readOnly) {
            sender.sendMessage("§cOP白名单已停用（「启用状态」= false），先启用再操作");
            return;
        }
        switch (sub) {
            case "add": {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl add <玩家>");
                    return;
                }
                String name = args[1].trim();
                boolean moved = removeTimed(name);
                if (!hasPermanent(name)) {
                    permanentList().add(new JsonPrimitive(name));
                }
                saveConfig();
                lastWarn.remove(name.toLowerCase(Locale.ROOT));
                boolean ok = setOpNow(name);
                sender.sendMessage("§a已添加 §f" + name + " §a为无固定期限授权"
                        + (moved ? "（已把原临时授权转为永久）" : "")
                        + (ok ? "，OP 已授予" : "（玩家不在本机档案中，上线时自动授予）"));
                return;
            }
            case "addtime": {
                if (args.length < 2) {
                    sender.sendMessage("§e用法: §f/opwl addtime <玩家> [时长] §7（缺省用默认时长 "
                            + defaultDuration() + "）");
                    return;
                }
                String name = args[1].trim();
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
                timedObject().addProperty(timedKeyOf(name), dur);
                saveConfig();
                lastWarn.remove(name.toLowerCase(Locale.ROOT));
                boolean ok = setOpNow(name);
                sender.sendMessage("§a已给 §f" + name + " §a添加临时授权 §7（时长 " + dur
                        + "，到期 " + fmt(exp) + "）" + (ok ? "，OP 已授予" : "，上线时自动授予"));
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
                    setOpByName(name, false);
                    sender.sendMessage("§a扣减后 " + name + " 的授权已到期，条目已移除，OP 已撤销");
                } else {
                    String s = fmt(newExp);
                    timedObject().addProperty(timedKeyOf(name), s);
                    saveConfig();
                    sender.sendMessage("§a" + name + " 到期时间已调整为 §f" + s
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
                sender.sendMessage("§7配置: §fplugins/Sdf1_login/opwl.json §7| 状态: "
                        + (isEnabled() ? "§a启用" : "§c停用")
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
            default:
                sendHelp(sender);
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
                        + (e > now ? " §7(剩 " + human(e - now) + ")" : " §c(已过期，OP 会被撤销)"));
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
        s.sendMessage("§7配置: plugins/Sdf1_login/opwl.json（单文件，白名单全在里面），15 秒自动热重载");
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