package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ★ 第30批：群控黑名单（软折磨）。
 *
 * <p>数据源：{@code plugins/Sdf1_login/black/黑名单.md} 的「配置区域」表格。
 *
 * <p>拉黑时长口径：空 / 0 / 终身 / 永久 = 永久；
 * {@code yyyy-MM-dd HH:mm} = 到期即该时刻；{@code yyyy-MM-dd} = 当天 23:59:59。
 *
 * <p>生效规则：每 5 秒比对文件 mtime+size 做增量重载。
 * 新增条目 → 立即生效（备份并清空背包 + 冻结债券账户 + 撤销领地管理员身份）；
 * 删除条目 → 立即恢复（解冻债券 + 从最新自动备份恢复背包）。
 *
 * <p>指令：{@code /addblack <玩家> [时长] [理由]}、{@code /removeblack <玩家>}。
 */
public class BlacklistManager {

    private final Main plugin;

    /** 黑名单配置文件：plugins/Sdf1_login/black/黑名单.md */
    private File mdFile;

    /** 配置区域起止标记 */
    private static final String ZONE_START = "### ----配置区域开始----";
    private static final String ZONE_END = "### ----配置区域结束";

    /** 内存黑名单：玩家名 → 条目 */
    private final Map<String, BlackEntry> entries = new LinkedHashMap<>();

    /** 已施加处罚的玩家 —— 防重复应用（备份/清空只做一次） */
    private final Set<String> applied = ConcurrentHashMap.newKeySet();

    /** 上次读取的文件签名（mtime + size），用于增量重载判定 */
    private long lastMtime = -1L;
    private long lastSize = -1L;

    /** 写回 md 时使用的行尾风格（true=CRLF） */
    private boolean crlfStyle = true;

    public BlacklistManager(Main plugin) {
        this.plugin = plugin;
    }


    // ==================== 条目模型 ====================

    /** 黑名单条目 */
    public static final class BlackEntry {
        /** 玩家名（原始拼写，写回 md 用） */
        public final String name;
        /** 拉黑时长原文 */
        public final String timeText;
        /** 理由 */
        public final String reason;
        /** 到期毫秒；0 = 永久 */
        public final long expiresMs;
        /** 记录时间毫秒 */
        public final long createdMs;

        BlackEntry(String name, String timeText, String reason,
                   long expiresMs, long createdMs) {
            this.name = name;
            this.timeText = timeText;
            this.reason = reason;
            this.expiresMs = expiresMs;
            this.createdMs = createdMs;
        }

        public boolean isExpired(long nowMs) {
            return expiresMs > 0L && expiresMs <= nowMs;
        }
    }

    // ==================== 生命周期 ====================

    /** 初始化：定位/释放配置文件 → 首次加载 → 启动 5 秒增量重载 */
    public void init() {
        try {
            File dir = new File(plugin.getDataFolder(), "black");
            if (!dir.exists()) dir.mkdirs();
            mdFile = new File(dir, "黑名单.md");
            if (!mdFile.exists()) {
                try (InputStream in = plugin.getResource("black/黑名单.md")) {
                    if (in != null) {
                        Files.copy(in, mdFile.toPath());
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("[黑名单] 模板释放失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[黑名单] 初始化失败: " + e.getMessage());
            return;
        }
        reload();
        // ★ 每 5 秒增量重载（mtime+size 变化才重新解析）
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (mdFile == null || !mdFile.exists()) return;
                long mt = mdFile.lastModified();
                long sz = mdFile.length();
                if (mt != lastMtime || sz != lastSize) {
                    reload();
                }
            } catch (Exception ignored) {}
        }, 100L, 100L);
    }

    public File getMdFile() {
        return mdFile;
    }

    // ==================== 查询 ====================

    /**
     * 该玩家是否在生效中的黑名单里。
     * <p>★ 精确匹配（本服离线模式，大小写不同 = 不同玩家）。
     */
    public boolean isBlacklisted(String playerName) {
        if (playerName == null || playerName.isEmpty()) return false;
        return findEntry(playerName) != null;
    }

    /** 精确查条目（过期返回 null） */
    public BlackEntry findEntry(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;
        BlackEntry e = entries.get(playerName);
        if (e == null) return null;
        if (e.isExpired(System.currentTimeMillis())) {
            entries.remove(playerName);
            return null;
        }
        return e;
    }

    /** 归一化查询（大小写不敏感兜底，仅用于 md 写回定位） */
    public BlackEntry findEntryLoose(String playerName) {
        if (playerName == null) return null;
        BlackEntry exact = findEntry(playerName);
        if (exact != null) return exact;
        String want = AreaProtection.normName(playerName);
        for (BlackEntry e : new ArrayList<>(entries.values())) {
            if (AreaProtection.normName(e.name).equals(want)) return e;
        }
        return null;
    }

    /** 当前生效中的黑名单条目快照（供耻辱台播报） */
    public List<BlackEntry> getActiveEntries() {
        long now = System.currentTimeMillis();
        List<BlackEntry> out = new ArrayList<>();
        for (BlackEntry e : new ArrayList<>(entries.values())) {
            if (e.isExpired(now)) {
                entries.remove(e.name);
                continue;
            }
            out.add(e);
        }
        return out;
    }

    public int size() {
        return getActiveEntries().size();
    }

    // ==================== 增删（写回 md） ====================

    /**
     * 添加黑名单条目并写回 md。
     *
     * @param timeText 拉黑时长（空/0/终身=永久，日期或日期+时间=到期）
     * @param reason   理由（可空）
     * @return null=成功，否则为错误原因
     */
    public String addBlacklist(String playerName, String timeText, String reason) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return "玩家名不能为空";
        }
        String name = playerName.trim();
        if (mdFile == null) return "黑名单文件未初始化";

        List<String> lines = readLines();
        if (lines == null) return "读取黑名单文件失败";

        // 定位配置区域 + 表头
        int[] zone = locateZone(lines);
        if (zone == null) return "黑名单文件缺少配置区域标记";
        int zoneStart = zone[0], zoneEnd = zone[1];
        int headerIdx = locateHeader(lines, zoneStart, zoneEnd);
        if (headerIdx < 0) return "黑名单文件缺少表头（| 玩家名字 | 拉黑时长 | 理由 |）";

        // 去掉同名旧行（大小写不敏感）
        String want = AreaProtection.normName(name);
        List<String> cleaned = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (i > zoneStart && i < zoneEnd && isRowOf(lines.get(i), want)) {
                continue;
            }
            cleaned.add(lines.get(i));
        }
        lines = cleaned;

        // 行数变了 → 重新定位
        zone = locateZone(lines);
        if (zone == null) return "黑名单文件缺少配置区域标记";
        zoneStart = zone[0];
        zoneEnd = zone[1];
        headerIdx = locateHeader(lines, zoneStart, zoneEnd);
        if (headerIdx < 0) return "黑名单文件缺少表头";

        // 插入点 = 表头 + 分隔行之后
        int insertAt = headerIdx + 1;
        if (insertAt < zoneEnd && isSeparatorRow(lines.get(insertAt))) {
            insertAt++;
        }
        String safeTime = (timeText == null || timeText.trim().isEmpty()) ? "终身" : timeText.trim();
        String safeReason = (reason == null || reason.trim().isEmpty()) ? "违反服务器规则" : reason.trim();
        // 理由里的竖线会破坏表格 → 替换
        safeReason = safeReason.replace("|", "／").replace("\r", " ").replace("\n", " ");
        lines.add(insertAt, "| " + name + " | " + safeTime + " | " + safeReason + " |");

        if (!writeLines(lines)) return "写回黑名单文件失败";
        reload();
        return null;
    }

    /**
     * 移除黑名单条目并写回 md。
     *
     * @return null=成功（或本来就没有），否则为错误原因
     */
    public String removeBlacklist(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return "玩家名不能为空";
        }
        if (mdFile == null) return "黑名单文件未初始化";
        List<String> lines = readLines();
        if (lines == null) return "读取黑名单文件失败";

        int[] zone = locateZone(lines);
        if (zone == null) return "黑名单文件缺少配置区域标记";
        int zoneStart = zone[0], zoneEnd = zone[1];

        String want = AreaProtection.normName(playerName.trim());
        List<String> out = new ArrayList<>();
        boolean removed = false;
        for (int i = 0; i < lines.size(); i++) {
            if (i > zoneStart && i < zoneEnd && isRowOf(lines.get(i), want)) {
                removed = true;
                continue;
            }
            out.add(lines.get(i));
        }
        if (!removed) {
            // 文件里没有 → 仍要解冻（可能只冻了账户没进表）
            restoreBlacklist(playerName.trim());
            return null;
        }
        if (!writeLines(out)) return "写回黑名单文件失败";
        reload();
        return null;
    }

    // ==================== 解析 ====================

    /** 重新解析 md 并应用差异（新增→处罚，移除→恢复） */
    public synchronized void reload() {
        if (mdFile == null) return;
        Map<String, BlackEntry> parsed = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        List<String> lines = readLines();
        if (lines != null) {
            int[] zone = locateZone(lines);
            if (zone != null) {
                int zoneStart = zone[0], zoneEnd = zone[1];
                for (int i = zoneStart + 1; i < zoneEnd; i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("|")) continue;
                    if (isSeparatorRow(line)) continue;
                    String[] cells = splitRow(line);
                    if (cells.length < 1) continue;
                    String name = cells[0].trim();
                    if (name.isEmpty() || name.contains("玩家名字")) continue;
                    String timeText = cells.length > 1 ? cells[1].trim() : "";
                    String reason = cells.length > 2 ? cells[2].trim() : "";
                    long expires = parseExpireMs(timeText, now);
                    if (expires > 0 && expires <= now) continue;   // 已过期
                    BlackEntry old = parsed.get(name);
                    long created = old != null ? old.createdMs : now;
                    parsed.put(name, new BlackEntry(name, timeText, reason, expires, created));
                }
            }
        }

        // ===== 差异应用 =====
        boolean changed = parsed.size() != entries.size();
        // 1) 新增 → 施加处罚
        for (BlackEntry e : parsed.values()) {
            if (!entries.containsKey(e.name)) {
                applyBlacklist(e);
                changed = true;
            } else {
                BlackEntry old = entries.get(e.name);
                if (old == null || old.expiresMs != e.expiresMs
                        || !old.timeText.equals(e.timeText)
                        || !old.reason.equals(e.reason)) {
                    changed = true;   // 条目内容改了（理由/时长）
                }
            }
        }
        // 2) 移除 → 恢复
        for (BlackEntry e : new ArrayList<>(entries.values())) {
            if (!parsed.containsKey(e.name)) {
                restoreBlacklist(e.name);
                changed = true;
            }
        }
        entries.clear();
        entries.putAll(parsed);

        if (mdFile.exists()) {
            lastMtime = mdFile.lastModified();
            lastSize = mdFile.length();
        }
        plugin.getLogger().info("[黑名单] 已重载，当前生效 " + parsed.size() + " 人");
        // ★ 第32批（#208）：名单变化 → 立即回响PHP（异步；syncBlacklist 内部 hash 门控兜底）
        if (changed) {
            try {
                WebManager wm = plugin.webManager;
                if (wm != null) wm.forceSyncBlacklist();
            } catch (Exception ignored) {}
        }
    }

    /**
     * 拉黑时长 → 到期毫秒；0 = 永久。
     * <p>空 / 0 / 终身 / 永久 / forever → 永久；
     * {@code yyyy-MM-dd HH:mm[:ss]} → 该时刻；
     * {@code yyyy-MM-dd} → 当天 23:59:59。
     */
    public static long parseExpireMs(String timeText, long now) {
        if (timeText == null) return 0L;
        String t = timeText.trim();
        if (t.isEmpty()) return 0L;
        if ("0".equals(t) || "终身".equals(t) || "永久".equals(t)
                || "forever".equalsIgnoreCase(t) || "never".equalsIgnoreCase(t)) {
            return 0L;
        }
        String[] pats = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm"};
        for (String p : pats) {
            long v = parseStrict(p, t);
            if (v > 0L) return v;
        }
        // 仅日期 → 当天 23:59:59
        String[] datePats = {"yyyy-MM-dd", "yyyy/MM/dd"};
        for (String p : datePats) {
            long v = parseStrict(p, t);
            if (v > 0L) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(v);
                c.set(Calendar.HOUR_OF_DAY, 23);
                c.set(Calendar.MINUTE, 59);
                c.set(Calendar.SECOND, 59);
                c.set(Calendar.MILLISECOND, 999);
                return c.getTimeInMillis();
            }
        }
        // 解析不了 → 永久（宁可拉黑也不放过）
        return 0L;
    }

    private static long parseStrict(String pattern, String value) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.ROOT);
            sdf.setLenient(false);
            ParsePosition pos = new ParsePosition(0);
            Date d = sdf.parse(value, pos);
            if (d != null && pos.getIndex() == value.length()) return d.getTime();
        } catch (Exception ignored) {}
        return 0L;
    }

    // ==================== 处罚 / 恢复 ====================

    /**
     * 施加拉黑处罚：备份并清空背包 + 冻结债券账户 + 撤销领地管理员身份（降级访客）。
     */
    public void applyBlacklist(BlackEntry e) {
        if (e == null) return;
        final String name = e.name;
        if (!applied.add(name)) {
            return;   // 已施加过，不重复备份/清空
        }
        plugin.getLogger().info("[黑名单] 拉黑生效: " + name
                + "（" + e.timeText + " / " + e.reason + "）");

        // 1) 冻结债券账户（按拉黑到期时间；永久=0）
        try {
            BondManager bm = plugin.getBonds();
            if (bm != null) bm.freezeAccount(name, e.expiresMs);
        } catch (Exception ex) {
            plugin.getLogger().warning("[黑名单] 冻结债券失败 (" + name + "): " + ex.getMessage());
        }

        // 2) 撤销其在所有领地的管理员身份（降级访客）
        try {
            AreaProtection ap = plugin.getAreaProtection();
            if (ap != null) {
                int n = ap.demoteBlacklistAdmins(name);
                if (n > 0) {
                    plugin.getLogger().info("[黑名单] 已撤销 " + name + " 的 " + n + " 个领地管理员身份");
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[黑名单] 撤销领地权限失败 (" + name + "): " + ex.getMessage());
        }

        // 3) 在线玩家：备份并清空背包
        Player p = Bukkit.getPlayerExact(name);
        if (p == null || !p.isOnline()) return;
        backupAndClear(p, name);
    }

    /**
     * 解除拉黑：解冻债券 + 从最新自动备份恢复背包。
     */
    public void restoreBlacklist(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        if (!applied.remove(playerName)) {
            // 未必处罚过，但仍确保解冻
            try {
                BondManager bm = plugin.getBonds();
                if (bm != null) bm.unfreezeAccount(playerName);
            } catch (Exception ignored) {}
            return;
        }
        plugin.getLogger().info("[黑名单] 拉黑解除: " + playerName);
        try {
            BondManager bm = plugin.getBonds();
            if (bm != null) bm.unfreezeAccount(playerName);
        } catch (Exception ex) {
            plugin.getLogger().warning("[黑名单] 解冻债券失败 (" + playerName + "): " + ex.getMessage());
        }
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null || !p.isOnline()) return;
        restoreInventory(p, playerName);
    }

    /**
     * 备份并清空背包。
     * <p>已有备份时不覆盖（保留拉黑时刻那一份），只清空。
     */
    private void backupAndClear(final Player p, final String name) {
        boolean hasBackup = false;
        try {
            hasBackup = plugin.getDb() != null
                    && plugin.getDb().getLatestAutoBackup(name) != null;
        } catch (Exception ignored) {}

        if (!hasBackup) {
            final ItemStack[] contents = p.getInventory().getContents();
            final ItemStack[] armor = p.getInventory().getArmorContents();
            final ItemStack[] extra = p.getInventory().getExtraContents();
            final int level = p.getLevel();
            final double exp = p.getExp();
            final String c64, a64, e64;
            try {
                c64 = InventorySerializer.toBase64(contents);
                a64 = InventorySerializer.toBase64(armor);
                e64 = InventorySerializer.toBase64(extra);
            } catch (IOException ex) {
                plugin.getLogger().warning("[黑名单] 背包序列化失败 (" + name + "): " + ex.getMessage());
                return;
            }
            // ★ 落库是阻塞 DB 写 → 异步；写完再回主线程清空（保证备份的是拉黑前快照）
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.getDb().saveAutoBackup(name, c64, a64, e64, level, exp);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[黑名单] 背包备份落库失败 (" + name + "): " + t.getMessage());
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player cur = Bukkit.getPlayerExact(name);
                    if (cur == null || !cur.isOnline()) return;
                    clearInventory(cur);
                    cur.sendMessage("§c§l[罪人] §f你已被拉黑，背包已清空（已备份）");
                    cur.sendMessage("§7背包物品将在解除拉黑后自动返还");
                });
            });
            return;
        }
        // 已有备份 → 只清空，不覆盖
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player cur = Bukkit.getPlayerExact(name);
            if (cur == null || !cur.isOnline()) return;
            clearInventory(cur);
            cur.sendMessage("§c§l[罪人] §f你已被拉黑，背包已清空");
        });
    }

    /** 从最新自动备份恢复背包 */
    private void restoreInventory(Player p, String name) {
        String[] data;
        try {
            data = plugin.getDb() == null ? null : plugin.getDb().getLatestAutoBackup(name);
        } catch (Exception ex) {
            plugin.getLogger().warning("[黑名单] 读取背包备份失败 (" + name + "): " + ex.getMessage());
            return;
        }
        if (data == null) {
            p.sendMessage("§e§l[罪人] §f拉黑已解除，但没有找到可返还的背包备份");
            return;
        }
        try {
            ItemStack[] contents = InventorySerializer.fromBase64(data[0]);
            ItemStack[] armor = InventorySerializer.fromBase64(data[1]);
            ItemStack[] extra = InventorySerializer.fromBase64(data[2]);
            p.getInventory().setContents(contents);
            p.getInventory().setArmorContents(armor);
            p.getInventory().setExtraContents(extra);
            if (data.length > 3) {
                try { p.setLevel(Integer.parseInt(data[3])); } catch (Exception ignored) {}
            }
            if (data.length > 4) {
                try { p.setExp((float) Double.parseDouble(data[4])); } catch (Exception ignored) {}
            }
            p.sendMessage("§a§l[罪人] §f拉黑已解除，背包已返还");
        } catch (IOException ex) {
            plugin.getLogger().warning("[黑名单] 背包反序列化失败 (" + name + "): " + ex.getMessage());
            p.sendMessage("§e§l[罪人] §f拉黑已解除，但背包返还失败，请联系管理员");
        }
    }

    private void clearInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.getInventory().setExtraContents(null);
        p.setLevel(0);
        p.setExp(0);
    }

    // ==================== md 读写工具 ====================

    /** 读全部行（去掉行尾 \r） */
    private List<String> readLines() {
        if (mdFile == null || !mdFile.exists()) return null;
        List<String> out = new ArrayList<>();
        try {
            byte[] raw = Files.readAllBytes(mdFile.toPath());
            crlfStyle = new String(raw, StandardCharsets.UTF_8).contains("\r\n");
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(raw), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.add(line);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[黑名单] 读取失败: " + e.getMessage());
            return null;
        }
        return out;
    }

    /** 写回全部行（保持原行尾风格） */
    private boolean writeLines(List<String> lines) {
        if (mdFile == null) return false;
        String sep = crlfStyle ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append(sep);
        }
        try {
            File parent = mdFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(mdFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[黑名单] 写入失败: " + e.getMessage());
            return false;
        }
    }

    /** 定位配置区域，返回 {起始行, 结束行}；找不到返回 null */
    private int[] locateZone(List<String> lines) {
        int zoneStart = -1, zoneEnd = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(ZONE_START)) {
                zoneStart = i;
            } else if (t.startsWith(ZONE_END) && zoneStart >= 0) {
                zoneEnd = i;
                break;
            }
        }
        if (zoneStart < 0 || zoneEnd < 0) return null;
        return new int[]{zoneStart, zoneEnd};
    }

    /** 定位表头行索引；找不到返回 -1 */
    private int locateHeader(List<String> lines, int zoneStart, int zoneEnd) {
        for (int i = zoneStart + 1; i < zoneEnd; i++) {
            String t = lines.get(i).trim();
            if (t.startsWith("|") && t.contains("玩家名字")) {
                return i;
            }
        }
        return -1;
    }

    /** 表格行是否属于该玩家（第一列匹配，大小写不敏感） */
    private boolean isRowOf(String line, String wantNorm) {
        String[] cells = splitRow(line.trim());
        if (cells.length < 1) return false;
        String c0 = cells[0].trim();
        if (c0.isEmpty() || c0.contains("玩家名字")) return false;
        return AreaProtection.normName(c0).equals(wantNorm);
    }

    /** 分隔行判断（| --- | --- |） */
    private boolean isSeparatorRow(String line) {
        String t = line.trim();
        if (!t.startsWith("|")) return false;
        String[] cells = splitRow(t);
        for (String c : cells) {
            String v = c.trim();
            if (!v.isEmpty() && !v.startsWith("---") && !v.matches("[-:]+")) return false;
        }
        return true;
    }

    /** 拆表格行（去掉首尾竖线，按 | 切分） */
    private String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        return t.split("\\|", -1);
    }
}
