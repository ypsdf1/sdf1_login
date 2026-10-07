package Sdf1_login;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OP 白名单（2026-10-07）
 *
 * 配置目录：plugins/Sdf1_login/OP白名单/ —— 里面【任意一个 .json 文件】就是白名单
 * 配置（文件名随意，按文件名排序只读首个；模板 opwl.json 随 jar 释放）。
 * 该 json 同时是配置文件与说明书，除四个真实配置项外的其它字段一律忽略、写回时原样保留。
 *
 * 行为（与 opwl.json 说明书一致）：
 *   - 读得出规则 → 白名单内玩家授 OP；【只授权不主动撤】：白名单外的玩家一律不碰
 *     （原生 OP / 服主不受影响）；本插件授过 OP 的玩家记入 granted（持久化到
 *     .granted.txt），到期时自动撤销（说明书「到期自动撤销」）。
 *   - 读不出规则（无 json / JSON 语法错 / 必需字段类型错）→ 【一次性】卸载全部
 *     已知玩家（含离线）的 OP，只执行一次，修复配置后自动恢复；
 *   - 启用状态=false → 功能休眠，不授权也不卸载；
 *   - 15 秒一次增量热重载：文件指纹（路径+lastModified+长度）有变化才重新解析并打
 *     日志，没变化只做静默的授权应用（新上线玩家 / 到期撤销），保持安静；
 *   - opwl 命令仅限控台（Main 侧对玩家直接静默 return，连帮助都不给）。
 *
 * 授权应用只在主线程跑（scheduler 主线程 + 命令 + join 事件），无并发问题。
 */
public class OpWhiteListManager implements Listener {

    private final Main plugin;
    private final File dir;
    private final File grantedFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** 当前被读的 json（pickJson 的结果，命令写回也写它） */
    private File watched;
    /** 解析成功后的完整配置（null = 解析失败态） */
    private JsonObject cfg;
    /** 解析失败标志：驱动「一次性全量卸载」，持续失败不重复卸 */
    private boolean parseFailed;
    /** 文件指纹（路径 + lastModified + 长度），变化才重载打日志 */
    private String lastPath = "";
    private long lastStamp = Long.MIN_VALUE;
    /** 本插件授过 OP 的玩家（小写名）——兑现「到期自动撤销」的凭据，持久化 */
    private final Set<String> granted = new HashSet<>();

    public OpWhiteListManager(Main plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "OP白名单");
        this.grantedFile = new File(dir, ".granted.txt");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 模板随 jar 释放（只在不存在时复制，绝不覆盖用户改过的文件）
        File tpl = new File(dir, "opwl.json");
        if (!tpl.exists()) {
            try (InputStream in = plugin.getResource("OP白名单/opwl.json")) {
                if (in != null) {
                    Files.copy(in, tpl.toPath());
                    plugin.getLogger().info("[OP白名单] 已释放模板配置: OP白名单/" + tpl.getName());
                } else {
                    plugin.getLogger().warning("[OPWl白名单] jar 内未找到模板 OP白名单/opwl.json，请手工在目录里放一个 .json");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[OP白名单] 模板释放失败: " + e.getMessage());
            }
        }
        loadGranted();
        reloadInternal(true);
        // 15 秒增量热重载 + 静默授权应用
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 300L, 300L);
    }

    // ================= 配置读取 =================

    /** 目录里任意一个 .json = 白名单文件；文件名按字典序取首个（确定性） */
    private File pickJson() {
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (fs == null || fs.length == 0) {
            return null;
        }
        Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return fs[0];
    }

    private void reloadInternal(boolean log) {
        watched = pickJson();
        loadFrom(watched, log);
        updateStamp();
    }

    private void loadFrom(File f, boolean log) {
        if (f == null) {
            fail("OP白名单目录里没有任何 .json 文件");
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
                plugin.getLogger().info("[OP白名单] 配置已恢复正常，恢复授权管理");
            }
            parseFailed = false;
            if (log) {
                plugin.getLogger().info("[OP白名单] 已重载 " + f.getName() + "："
                        + (isEnabled() ? "启用" : "停用") + "，无固定期限 "
                        + permanentList().size() + " 人，有固定期限 " + timedObject().size() + " 人");
            }
        } catch (Exception e) {
            fail(f.getName() + " 解析失败：" + e.getMessage());
        }
    }

    /** 解析失败 → 一次性卸载全部已知 OP（含离线），持续失败不重复执行 */
    private void fail(String why) {
        cfg = null;
        if (parseFailed) {
            return;
        }
        parseFailed = true;
        int n = 0;
        for (OfflinePlayer op : Bukkit.getOperators()) {
            if (op.isOp()) {
                op.setOp(false);
                n++;
            }
        }
        granted.clear();
        saveGranted();
        plugin.getLogger().warning("[OP白名单] " + why
                + " —— 已一次性卸载全部 " + n + " 名玩家的 OP；修复 json 后自动恢复授权。");
    }

    /** 文件指纹：路径 + lastModified + 长度（任一变化都视为变更） */
    private void updateStamp() {
        File f = (watched != null && watched.exists()) ? watched : pickJson();
        if (f == null) {
            lastPath = "";
            lastStamp = 0L;
            return;
        }
        lastPath = f.getAbsolutePath();
        lastStamp = f.lastModified() + f.length();
    }

    // ================= 15 秒 tick =================

    private void tick() {
        File f = pickJson();
        String p = f == null ? "" : f.getAbsolutePath();
        long st = f == null ? 0L : f.lastModified() + f.length();
        if (!p.equals(lastPath) || st != lastStamp) {
            // 有变化：重新解析 + 打日志（含解析失败的告警）
            watched = f;
            loadFrom(f, true);
            updateStamp();
        }
        // 无论有无变化都做一次静默的授权应用（不打无变化日志，保持安静）
        apply();
    }

    /** 玩家上线：立即应用一次（白名单内马上拿到 OP，不用等下个 15 秒） */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        apply();
    }

    // ================= 授权应用（只授不撤，例外仅两处：到期、解析失败） =================

    private void apply() {
        if (cfg == null || !isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        // 1) 到期自动撤销：只动本插件授过 OP 且当前在「有固定期限」表里、已过期的玩家。
        //    白名单外的其他人（原生 OP 等）一律不碰。
        boolean dirty = false;
        Iterator<String> it = granted.iterator();
        while (it.hasNext()) {
            String key = it.next();
            String timed = findTimed(key);
            if (timed == null) {
                continue;
            }
            long exp = expireOf(timed);
            if (exp > 0 && exp <= now) {
                setOpByName(key, false);
                it.remove();
                dirty = true;
                plugin.getLogger().info("[OP白名单] " + key + " 授权到期，已自动撤销 OP");
            }
        }
        if (dirty) {
            saveGranted();
        }
        // 2) 白名单内在线玩家授权（已是 OP 的跳过；新授的记入 granted）
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                continue;
            }
            if (!isWhitelisted(p.getName())) {
                continue;
            }
            p.setOp(true);
            granted.add(p.getName().toLowerCase(Locale.ROOT));
            saveGranted();
            plugin.getLogger().info("[OP白名单] 已授权 " + p.getName() + " 获得 OP");
        }
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
        String k = timedKeyOf(name);
        if (timedObject().has(k) && !k.equalsIgnoreCase(name) && timedObject().get(k) != null) {
            // key 原样存在
        }
        for (String key : new ArrayList<>(timedObject().keySet())) {
            if (name.equalsIgnoreCase(key)) {
                timedObject().remove(key);
                return true;
            }
        }
        return false;
    }

    /** 是否白名单有效成员：永久 或 有期限且未过期（坏时长视为无效，不授） */
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

    /** 解析为绝对到期毫秒；解析失败返回 -1（调用方按「无效/不过期」保守处理） */
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

    /** 写回 json：保留用户在配置项之外塞的所有字段（说明书/混淆内容），只动配置项 */
    private void saveConfig() {
        try {
            File target = watched;
            if (target == null || !target.exists()) {
                target = new File(dir, "opwl.json");
                watched = target;
            }
            Files.write(target.toPath(), gson.toJson(cfg).getBytes(StandardCharsets.UTF_8));
            updateStamp(); // 命令自己有回馈，抑制下个 tick 再打一次「已重载」
        } catch (Exception e) {
            plugin.getLogger().warning("[OP白名单] 配置写回失败: " + e.getMessage());
        }
    }

    private void loadGranted() {
        granted.clear();
        if (!grantedFile.exists()) {
            return;
        }
        try {
            for (String line : Files.readAllLines(grantedFile.toPath(), StandardCharsets.UTF_8)) {
                String s = line.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty()) {
                    granted.add(s);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[OP白名单] 授权痕迹读取失败: " + e.getMessage());
        }
    }

    private void saveGranted() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : granted) {
                sb.append(s).append('\n');
            }
            Files.write(grantedFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("[OP白名单] 授权痕迹写入失败: " + e.getMessage());
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
                reloadInternal(true);
                apply();
                sender.sendMessage(cfg == null
                        ? "§c重载完成，但配置解析失败（详见控制台告警）"
                        : "§a重载完成：§f" + (isEnabled() ? "启用" : "停用")
                        + "，无固定期限 " + permanentList().size()
                        + " 人，有固定期限 " + timedObject().size() + " 人" +
                          "§d欢迎来到草原探险服务器，ip: mc2.ypshidifu.cn 端口(基岩版需要):30679");
                return;
            }
            default:
                break;
        }
        // 以下子命令需要配置可读
        if (cfg == null) {
            sender.sendMessage("§c配置解析失败，先修复 OP白名单 目录里的 json 再操作（或 /opwl reload）");
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
                boolean moved = removeTimed(name); // 已有临时授权 → 转永久
                if (!hasPermanent(name)) {
                    permanentList().add(new com.google.gson.JsonPrimitive(name));
                }
                saveConfig();
                boolean ok = grantNow(name, true);
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
                removePermanent(name); // 临时授权优先：从永久表移出
                timedObject().addProperty(timedKeyOf(name), dur);
                saveConfig();
                boolean ok = grantNow(name, true);
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
                    boolean revoked = revokeNow(name);
                    sender.sendMessage("§a扣减后 " + name + " 的授权已到期，条目已移除"
                            + (revoked ? "，OP 已撤销" : ""));
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
                boolean revoked = revokeNow(name);
                if (!inPerm && !inTimed && !revoked) {
                    sender.sendMessage("§e" + name + " 本就不在白名单中");
                } else {
                    sender.sendMessage("§a已移除 §f" + name + " §a的授权"
                            + (revoked ? "，OP 已撤销"
                            : ((inPerm || inTimed) ? "（该玩家 OP 非本插件授予，OP 状态未动）" : "")));
                }
                return;
            }
            case "list": {
                if (args.length >= 2) {
                    listOne(sender, args[1].trim());
                    return;
                }
                sender.sendMessage("§e===== OP白名单 §7(" + (watched != null ? watched.getName() : "?") + ") =====");
                sender.sendMessage("§7状态: " + (isEnabled() ? "§a启用" : "§c停用")
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
                            : "§c已过期 " + fmt(e));
                    sender.sendMessage("§7  " + en.getKey() + " → " + state);
                }
                return;
            }
            default:
                sendHelp(sender);
                return;
        }
    }

    // ================= 命令辅助 =================

    /** 立即授予/撤销并记录；返回 false = 档案未知（上线时由 join 自动补授） */
    private boolean grantNow(String name, boolean op) {
        if (!op) {
            return revokeNow(name);
        }
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            p.setOp(true);
            granted.add(name.toLowerCase(Locale.ROOT));
            saveGranted();
            return true;
        }
        OfflinePlayer o = Bukkit.getOfflinePlayer(name);
        if (!o.hasPlayedBefore()) {
            // 档案未知：留到 join 兜底（isWhitelisted && !isOp → 授）
            return false;
        }
        o.setOp(true);
        granted.add(name.toLowerCase(Locale.ROOT));
        saveGranted();
        return true;
    }

    /** 仅撤销本插件授过 OP 的玩家；返回是否真的撤了 */
    private boolean revokeNow(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!granted.contains(key)) {
            return false;
        }
        setOpByName(name, false);
        granted.remove(key);
        saveGranted();
        return true;
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
                        + (e > now ? " §7(剩 " + human(e - now) + ")" : " §c(已过期)"));
            } else {
                s.sendMessage("§7  类型: §f临时 §c（到期时间无法解析: " + t + "）");
            }
        } else {
            s.sendMessage("§7  类型: §c不在白名单");
        }
        Player online = Bukkit.getPlayerExact(name);
        boolean isOp = online != null ? online.isOp() : Bukkit.getOfflinePlayer(name).isOp();
        s.sendMessage("§7  当前OP: " + (isOp ? "§f是" : "§f否")
                + " §7| 本插件授OP记录: " + (granted.contains(name.toLowerCase(Locale.ROOT)) ? "§f是" : "§f否"));
    }

    private void sendHelp(org.bukkit.command.CommandSender s) {
        s.sendMessage("§e===== OP白名单（仅控制台可用） =====");
        s.sendMessage("§f/opwl §7- 本帮助");
        s.sendMessage("§f/opwl add <玩家> §7- 添加无固定期限授权");
        s.sendMessage("§f/opwl addtime <玩家> [时长] §7- 添加临时授权（缺省用默认时长 "
                + defaultDuration() + "）");
        s.sendMessage("§f/opwl removetime <玩家> <扣减时长> §7- 扣减授权时长（如 1h）");
        s.sendMessage("§f/opwl remove <玩家> §7- 移除授权");
        s.sendMessage("§f/opwl list [玩家] §7- 查看白名单 / 单个玩家状态");
        s.sendMessage("§f/opwl reload §7- 重载配置");
        s.sendMessage("§7配置目录: plugins/Sdf1_login/OP白名单/（任意 *.json），15 秒自动热重载");
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
}