package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Chest;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 箱子搬运（26批，复刻 Chest Transport mod 玩法）
 *
 * 触发条件（全部满足才接管，否则一律交原版）：
 *   1. 领地内（AreaProtection.getArea 命中）
 *   2. 权限级别为 ADMIN / OWNER / MEMBER（领地主/授权成员/管理员）
 *   3. 主手是斧头（*_AXE）且带精准采集魔咒
 *
 * 2 秒窗口语义（2026-10-16 用户确认）：
 *   「连续破坏是2个箱子1组，中间断了，如果是大箱子，就打包被拆的那一半的内容」
 *   - 首破挂起观察 2 秒（40 tick）：不立即给打包箱，删原版掉落
 *   - 2 秒内同玩家再拆一个箱子 → 成组，一并交付 2 个打包箱
 *   - 超时（链断）→ 单独交付那 1 个；大箱子天然只快照被拆那一半
 *     （Chest.getBlockInventory() 只含被拆半边的 27 格）
 *
 * 打包箱：沿用来源箱子材质 + 名称标识 + PDC 存 InventorySerializer
 *   base64 内容；setMaxStackSize(1) 防不同内容堆叠混淆。
 * 放置回填：BlockPlaceEvent 后 1 tick 方块状态就绪再灌回 27 格。
 *
 * ★ 第二十七批线上事故修复（"拆箱物品照炸开"）：
 *   26.2 新增铜箱族（COPPER_CHEST / EXPOSED_ / WEATHERED_ / OXIDIZED_ /
 *   WAXED_ 等 8 种），原判定只认 CHEST / TRAPPED_CHEST → 玩家拆铜箱时
 *   整条判定链第一步就早退，交原版散落。改为「凡 *_CHEST 且非末影箱」全收。
 *   材质一律走字符串判定（不引用 Material.COPPER_CHEST 之类常量）→
 *   跨服务端 build 零风险。
 */
public class ChestCarryManager implements Listener {

    private final Main main;
    private final Plugin plugin;
    private final NamespacedKeyRef carryKey;

    /** 组队窗口 2 秒 = 40 tick */
    private static final long WINDOW_TICKS = 40L;
    private static final long WINDOW_MS = 2000L;

    /** 接管后该位置的掉落抑制时长（tick）：setDropItems 之外的第二道保险 */
    private static final long DROP_SUPPRESS_TICKS = 60L;

    /** PDC key 的小包装（避免与 block data 的 Chest 类名混淆的命名负担） */
    private static final class NamespacedKeyRef {
        final org.bukkit.NamespacedKey key;
        NamespacedKeyRef(Plugin plugin, String name) {
            this.key = new org.bukkit.NamespacedKey(plugin, name);
        }
    }

    /** 首破挂起中的搬运记录 */
    private static final class PendingCarry {
        final UUID player;
        final String worldName;
        final Location dropLoc;     // 超时且玩家离线时的兜底投递点
        final Material sourceMat;   // 来源箱子材质（打包箱沿用，铜箱拆出来还是铜箱）
        final String halfName;      // 小箱子 / 大箱子·左半 / 大箱子·右半
        final ItemStack[] contents; // 被拆半边 27 格快照
        final long ts;              // 挂起时刻（组队窗口判定）

        PendingCarry(UUID player, String worldName, Location dropLoc,
                     Material sourceMat, String halfName, ItemStack[] contents, long ts) {
            this.player = player;
            this.worldName = worldName;
            this.dropLoc = dropLoc;
            this.sourceMat = sourceMat;
            this.halfName = halfName;
            this.contents = contents;
            this.ts = ts;
        }
    }

    /** 玩家 UUID → 挂起中的搬运（每人最多 1 条） */
    private final Map<UUID, PendingCarry> pending = new ConcurrentHashMap<>();

    // ★ 33批五次：拆连箱时 clearChestBlock(twin) 的 setType(AIR) 会触发 Bukkit 给
    //   连接箱另一半再发一次 BlockBreakEvent（"右边自动掉"）→ 合并分支重入翻倍。
    //   用「玩家UUID+被拆方块位置」最近合并时间做幂等守卫，同一玩家 5 秒内重复命中
    //   双连箱合并分支直接跳过（第二次事件里 twin 已被清成空气、findTwin 返回 null
    //   天然不会再进，此处守卫双保险）。
    private final Map<UUID, long[]> lastMergeGuard = new ConcurrentHashMap<>();
    private static final long MERGE_GUARD_MS = 5000L;

    /** 已接管但仍需拦掉落的位置（BlockDropItemEvent 兜底，tick 后自动放行） */
    private final Set<Location> suppressDrops = ConcurrentHashMap.newKeySet();

    // ★ 31批修正：玩家 UUID → 首破 40 tick 超时任务句柄（第二半破坏时主动 cancel，
    //   防止 40 tick 超时任务先于第二半破坏跑完，把第一半直接 deliver 掉，导致
    //   第二半破坏时 pending.remove 拿到 null 走首破分支 → 「左半打包右半原版」）
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> timeoutTasks =
            new ConcurrentHashMap<>();

    // ==================== 30批：chest/cfg.json 运维开关（增量热重载） ====================
    /** 运维开关：true=拆箱接管为打包箱；false=全交还原版散落 */
    private volatile boolean cfgEnabled = true;
    /** 运维开关：true=2秒窗口连续拆箱合二为一；false=两个独立打包箱 */
    private volatile boolean cfgMergeTwo = true;
    /** cfg.json 文件 mtime / size 缓存（增量判定：没变就不重新解析） */
    private volatile long cfgMtime = -1L;
    private volatile long cfgSize = -1L;
    /** cfg.json 路径（插件数据目录下） */
    private final java.io.File cfgFile;

    public ChestCarryManager(Main main) {
        this.main = main;
        this.plugin = main;
        this.carryKey = new NamespacedKeyRef(main, "chest_carry_data");
        this.cfgFile = new java.io.File(main.getDataFolder(), "chest/cfg.json");
        reloadCfg();   // 启动即读一次
    }

    /**
     * 每次拆箱前调：mtime/size 没变直接 return；变了才重新解析。
     * 解析失败 → 保持现状（运维改坏一个字段不该让箱子搬运整个失效）。
     * ★ 键名全中文（与 opwl.json 项目惯例对齐，Gson 自动 UTF-8 解码中文键）
     */
    private void reloadCfg() {
        if (!cfgFile.exists()) {
            // 资源未释放（旧版升级场景）→ 用内置默认
            if (cfgMtime < 0) { cfgMtime = 0; cfgSize = 0; }
            cfgEnabled = true; cfgMergeTwo = true;
            return;
        }
        long mt = cfgFile.lastModified();
        long sz = cfgFile.length();
        if (cfgMtime > 0 && mt == cfgMtime && sz == cfgSize) return; // 没变
        boolean ok = false;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new java.io.FileInputStream(cfgFile),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            String text = sb.toString().trim();
            if (text.isEmpty()) { ok = true; return; } // 空文件=保持默认
            // Gson 解析中文键（与 OP白名单/opwl.json 同款解析方式，Gson 自动 UTF-8 解码中文键）
            com.google.gson.JsonObject obj =
                    new com.google.gson.Gson().fromJson(text,
                            com.google.gson.JsonObject.class);
            if (obj == null) { ok = true; return; }
            cfgEnabled = readBool(obj, "开关", true);
            cfgMergeTwo = readBool(obj, "是否合并两个箱子", true);
            ok = true;
            main.getLogger().info("[箱子搬运] chest/cfg.json 已重载: 开关="
                    + cfgEnabled + ", 是否合并两个箱子=" + cfgMergeTwo);
        } catch (Exception ex) {
            main.getLogger().warning("[箱子搬运] chest/cfg.json 解析失败，保持现状: "
                    + ex.getMessage());
        }
        if (ok) {
            cfgMtime = mt; cfgSize = sz;
        }
    }

    /** 从 JSON 对象读 bool（键=中文；支持 true/false 或 0/1；缺省/解析失败=def） */
    private static boolean readBool(com.google.gson.JsonObject obj,
                                    String key, boolean def) {
        com.google.gson.JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return def;
        try {
            if (el.isJsonPrimitive()) {
                String tok = el.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                if (tok.equals("true") || tok.equals("1")) return true;
                if (tok.equals("false") || tok.equals("0")) return false;
            }
        } catch (Exception ignored) { }
        return def;
    }

    /** 配置「是否合并两个箱子」开关（false=两个独立打包箱，true=合二为一） */
    private boolean cfgMergeTwo() { return cfgMergeTwo; }

    // ==================== 拆箱：拦截 → 挂起/成组 ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestBreak(BlockBreakEvent e) {
        Material mat = e.getBlock().getType();
        if (!isChestMaterial(mat)) return;
        // ★ 30批：每次拆箱增量热重载 cfg.json（mtime+size 没变就跳过解析）
        reloadCfg();
        if (!cfgEnabled) return; // 运维关闭 → 全交还原版散落
        Player p = e.getPlayer();

        // 手持：主手必须是精准采集斧头，否则交原版
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) return;
        if (!hand.getType().name().endsWith("_AXE")) return;
        if (!hand.containsEnchantment(Enchantment.SILK_TOUCH)) return;

        // 领地 + 权限：非领地、或非 ADMIN/OWNER/MEMBER 一律交原版
        AreaProtection ap = main.areaProtection;
        if (ap == null) return;
        Block b = e.getBlock();
        AreaProtection.AreaConfig ac =
                ap.getArea(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        if (ac == null) return;
        // ★ 30批：权限判定改为「容器管理权限」——
        //   原版链路里 容器管理 = denyContainer 的四级豁免链（getEffectiveDeny）：
        //   ① 领地主/插件管理员(ADMIN/OWNER) 恒豁免 → 不拦 → 拿打包箱；
        //   ② per-player 独立权限（截图里"容器管理 允许"那一栏）→ 显式配置命中 →
        //      被授权容器管理的普通玩家/成员/访客 也豁免 → 拿打包箱；
        //   ③ 已授权成员(MEMBER) 豁免；
        //   ④ 白名单访客(VISITOR) 豁免（denyContainer 非 denyMove，访客回退 false）；
        //   ⑤ 普通访客/路人才回退领地默认 denyContainer。
        //   旧判定只看 getPermissionLevel 属于 ADMIN/OWNER/MEMBER，把"被授权容器管理
        //   的访客"（level=VISITOR）一票否决交还原版散落 —— 本次改正。
        if (ap.getEffectiveDeny(p, ac, "denyContainer")) return;

        // ★ 27批：走 Container 接口 + getSnapshotInventory() ——
        //   26.2 的 Chest 只覆盖木箱/陷阱箱，铜箱族同样实现 Container；
        //   getSnapshotInventory() 返回「被拆那半边」的独立快照（大箱子天然只含半边 27 格），
        //   不用像 getBlockInventory() 那样先判断是否双箱。
        BlockState st = b.getState();
        if (!(st instanceof org.bukkit.block.Container)) {
            plugin.getLogger().fine("[箱子搬运] 非容器方块，跳过: " + mat);
            return;
        }
        ItemStack[] snap;
        try {
            snap = ((org.bukkit.block.Container) st).getSnapshotInventory().getContents();
        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "[箱子搬运] 读取箱子快照失败: " + ex.getMessage());
            return;
        }

        // 全空箱不接管：交原版掉箱子本体，无物品洪水
        if (isEmpty(snap)) return;

        // 大/小箱子归属名（TRAPPED_CHEST 的 block data 也是 Chest 类）
        String halfName = "小箱子";
        org.bukkit.block.data.BlockData bd = b.getBlockData();
        if (bd instanceof Chest) {
            Chest.Type t = ((Chest) bd).getType();
            if (t == Chest.Type.LEFT) halfName = "大箱子·左半";
            else if (t == Chest.Type.RIGHT) halfName = "大箱子·右半";
        }


        // 接管：删原版大量掉落（setDropItems + BlockDropItemEvent 双保险）
        e.setDropItems(false);
        markSuppressDrop(b.getLocation());

        UUID id = p.getUniqueId();
        // ★ 32批根因修正：双连箱挥斧只命中一半时，Bukkit 只对「被破那半边」
        //   触发一次 BlockBreakEvent，邻接孤半不会掉落（原版：另一半存在就不掉），
        //   2 秒窗口里第二事件不来 → 首破超时单独交付半箱，孤半内容留地。
        //   实测用户截图实锤「1个打包箱 + 1个原版普通箱」即此。
        //   改：拆双连箱时读「双半全量内容」直接打包交付 + 主动清掉邻接孤半
        //   （孤半内容已并入打包箱、清方块不产物；双半都 setType(AIR,false,false)
        //    物理移除防孤半连接态回弹成普通箱）。不再用 breakBlock（对双箱连接态
        //    有副作用）。小箱（无 twin）维持旧 2 秒窗口逻辑。
        boolean isBigChest = halfName.startsWith("大箱子");
        Block twin = findTwin(b);
        // ★ 33批：合并口径=「紧挨着就算」—— 连大箱（isBig）、独立小箱紧挨（!isBig）
        //   只要有水平邻接箱（twin）且 mergeTwo 开 就合并，不再强制 isBigChest。
        //   双连箱根因（挥斧只破一半）+ 独立小箱挨放，统一走此分支。
        // ★ 32批诊断：双连箱合并不生效——打全量判定链，定位断在哪一环
        int invSize = -1;
        int twinInvSize = -1;
        try {
            BlockState st2 = b.getState();
            if (st2 instanceof org.bukkit.block.Container) {
                invSize = ((org.bukkit.block.Container) st2).getInventory().getSize();
            }
        } catch (Throwable ignored) { }
        try {
            if (twin != null) {
                BlockState st3 = twin.getState();
                if (st3 instanceof org.bukkit.block.Container) {
                    twinInvSize = ((org.bukkit.block.Container) st3).getInventory().getSize();
                }
            }
        } catch (Throwable ignored) { }
        plugin.getLogger().info("[箱子搬运][诊断] " + p.getName()
                + " 拆 b=" + b.getX() + "," + b.getY() + "," + b.getZ()
                + " 数据类型=" + (bd instanceof Chest ? ((Chest) bd).getType().name() : "无Chest")
                + " halfName=" + halfName + " isBig=" + isBigChest
                + " invSize=" + invSize
                + " snap格数=" + snap.length
                + " 邻接twin=" + (twin != null
                        ? (twin.getX() + "," + twin.getY() + "," + twin.getZ() + "("
                            + twin.getType().name() + ",invSize=" + twinInvSize + ")") : "无")
                + " mergeTwo=" + cfgMergeTwo());
        if (twin != null && cfgMergeTwo()) {
            // ★ 33批五次：幂等守卫 —— clearChestBlock 的 setType(AIR) 会让 Bukkit 对
            //   连接箱另一半再发一次 BlockBreakEvent（"右边自动掉"）→ 合并分支重入
            //   翻倍。这里 5 秒内同一玩家命中双连箱合并分支直接跳过（第二次事件里
            //   twin 已被清成空气、findTwin 返回 null 天然不会再进，此处双保险）。
            long nowMs = System.currentTimeMillis();
            long[] guard = lastMergeGuard.get(id);
            if (guard != null && (nowMs - guard[0]) < MERGE_GUARD_MS
                    && guard[1] == b.getX() && guard[2] == b.getY() && guard[3] == b.getZ()) {
                plugin.getLogger().fine("[箱子搬运][诊断] 幂等跳过（5s 内同位置二次事件）");
                return;
            }
            lastMergeGuard.put(id, new long[]{nowMs, b.getX(), b.getY(), b.getZ()});

            plugin.getLogger().info("[箱子搬运][诊断] >>> 进入邻接箱合并分支 deliver合并箱 "
                    + halfName + "+" + halfNameOf(twin));
            // 双半全量内容（被破半 selfSnap 27 + 孤半 getSnapshotInventory 27；
            //   连箱=54 全量、独立小箱=各 27 独立；一条路径、不翻倍、不误删）
            ItemStack[] merged = newMergedContents(b, twin, snap, isBigChest);
            // ★ 33批根因：孤半 twin 若被玩家/原版再触发 BlockBreakEvent，
            //   内容会经 BlockDropItemEvent 散落 → 这里补登记 suppressDrop 拦住。
            markSuppressDrop(twin.getLocation());
            // 被破半 b 已在 onChestBreak 前 markSuppressDrop（上方 markSuppressDrop(b)），
            //   不再主动 setType(AIR) 物理移除孤半（那是二次事件+散落的源头）；
            //   孤半留给玩家自然处理（再挥斧拆掉 → 走旧 2 秒窗口分支，或留着）。
            // 直接交付双半合并箱（不走 2 秒窗口 —— 用户诉求"不管多快都合并"）
            PendingCarry cur = new PendingCarry(id, b.getWorld().getName(),
                    p.getLocation(), mat, halfName + "+" + halfNameOf(twin),
                    merged, nowMs);
            deliver(p, cur);
            return;
        }
        PendingCarry prev = pending.remove(id);
        long now = System.currentTimeMillis();
        PendingCarry cur = new PendingCarry(id, b.getWorld().getName(),
                p.getLocation(), mat, halfName, snap, now);
        // ★ 诊断日志（32批）：定位双木箱合并不生效根因（首破 vs 成组）
        //   注：数据类型/邻接twin 全量判定已挪到「双连箱分支前置」诊断，
        //   这里只记 首破 vs 成组 的时序，避免重复刷屏
        plugin.getLogger().info("[箱子搬运][诊断] " + p.getName()
                + " 拆 " + halfName + " snap=" + snap.length + "格 "
                + (prev != null ? ("prev=" + prev.halfName + " 间隔=" + (now - prev.ts) + "ms") : "首破")
                + " mergeTwo=" + cfgMergeTwo());
        if (prev != null && (now - prev.ts) <= WINDOW_MS) {
            // ★ 31批修正：成组时 cancel 首破挂起的 40 tick 超时任务
            //   （超时任务跑完会把第一半直接 deliver 掉，导致第二半破坏拿不到 prev）
            org.bukkit.scheduler.BukkitTask oldTask = timeoutTasks.remove(id);
            if (oldTask != null) oldTask.cancel();
            // ★ 30批：成组合二为一 —— 只看时间窗口（2秒内），不看塞满。
            //   两个半边的非空物品顺序拼接成「一个」打包箱（变长数组，
            //   放置时按容器实际大小回填，大箱子≈54 格场景全部收进一个箱）。
            if (cfgMergeTwo()) {
                deliver(p, prev, cur);
            } else {
                // 运维把「是否合并两个箱子」关了 → 仍走旧逻辑：两个独立打包箱
                deliver(p, prev);
                deliver(p, cur);
            }
        } else {
            // 首破：挂起观察 2 秒，等第二破成组或超时单独交付
            pending.put(id, cur);
            // ★ 31批：保存超时任务句柄，第二半破坏时主动 cancel
            org.bukkit.scheduler.BukkitTask t =
                    plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        PendingCarry pc = pending.get(id);
                        if (pc != cur) return; // 已成组交付或被新记录替换
                        pending.remove(id);
                        timeoutTasks.remove(id);
                        Player online = Bukkit.getPlayer(id);
                        if (online != null && online.isOnline()) {
                            deliver(online, pc);
                        } else {
                            // 玩家离线兜底：打包箱掉在原地（变长内容 → 自适应放置回填）
                            try {
                                World w = Bukkit.getWorld(pc.worldName);
                                if (w != null) {
                                    w.dropItemNaturally(pc.dropLoc,
                                            buildPackedChest(pc.sourceMat, pc.halfName, pc.contents));
                                }
                            } catch (Exception ex) {
                                plugin.getLogger().warning(
                                        "[箱子搬运] 离线兜底掉落失败: " + ex.getMessage());
                            }
                        }
                    }, WINDOW_TICKS);
            if (t != null) timeoutTasks.put(id, t);
        }
    }

    // ==================== 放置：回填内容 ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestPlace(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (it == null) return;
        if (!isChestMaterial(it.getType())) return;
        if (!it.hasItemMeta()) return;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(carryKey.key, PersistentDataType.STRING)) return;
        String data = pdc.get(carryKey.key, PersistentDataType.STRING);
        if (data == null || data.isEmpty()) return;

        Block placed = e.getBlockPlaced();

        // ★ 29批验收修正（用户定稿）：放置侧不拦任何人 —— 非授权用户也交还原版正常
        //   放下并回填。隔离靠下游链路：非授权用户「拆箱」在 onChestBreak 判定链
        //   （非 OWNER/ADMIN/MEMBER 交原版）→ 箱子掉原版掉落、内容散落一地 →
        //   配合领地禁止拾取（deny_pickup）物品留在原地拿不走，领地主可回收；
        //   开箱由 deny_container 拦。曾实现「无权限取消放置」被用户否决。

        // 1 tick 后方块状态就绪再回填（含与邻半合并成大箱子的情况）
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            BlockState st = placed.getState();
            if (!(st instanceof org.bukkit.block.Container)) return;
            // ★ 29批第2项：放下的是「原版普通箱子」——物品的自定义名可能被方块继承成
            //   CustomName（准星指向时显示「打包箱」），这里清掉并落盘，再回填内容。
            //   update(force=true, applyPhysics=false)：强制写回世界、不触发邻块物理。
            try {
                // Paper 26：BlockState 已无 setCustomName —— 方块实体名在 Nameable 上
                //（Container ← LockableTileState ← Nameable），setter 是 adventure
                // 的 customName(Component)，null 直传无歧义
                ((org.bukkit.Nameable) st).customName(null);
                st.update(true, false);
            } catch (Exception ex) {
                // 个别容器不支持改名就忽略，回填照常进行
            }
            st = placed.getState();   // update 后旧快照失效，重新取
            if (!(st instanceof org.bukkit.block.Container)) return;
            // ★ 33批五次：内容 >27 件的打包箱用的是 TRAPPED_CHEST（54 格容器），
            //   但陷阱箱放置后不会自动接双箱 → 这里主动连上邻接 27 格陷阱箱凑成
            //   54 格，否则 28~54 件会被 27 格截断（用户实测"54 件放出来是小箱"）。
            try {
                int contentCount = 0;
                ItemStack[] items0 = InventorySerializer.fromBase64(data);
                for (ItemStack it0 : items0) {
                    if (it0 != null && !it0.getType().isAir()) contentCount += it0.getAmount();
                }
                if (contentCount > 27) {
                    org.bukkit.block.data.type.Chest chestData =
                            (org.bukkit.block.data.type.Chest) placed.getBlockData();
                    // 尝试水平四向接双箱（与已有邻接陷阱箱/木箱共用 54 格库存）
                    World w = placed.getWorld();
                    int px = placed.getX(), py = placed.getY(), pz = placed.getZ();
                    Block[][] cands = {
                            {w.getBlockAt(px + 1, py, pz), w.getBlockAt(px - 1, py, pz)},
                            {w.getBlockAt(px, py, pz + 1), w.getBlockAt(px, py, pz - 1)},
                    };
                    for (Block[] row : cands) {
                        for (Block c : row) {
                            if (c != null && isChestMaterial(c.getType())
                                    && (c.getType() == org.bukkit.Material.TRAPPED_CHEST
                                        || c.getType() == org.bukkit.Material.CHEST)) {
                                // 把刚放的陷阱箱 block data 接到已有箱上
                                chestData.setType(Chest.Type.LEFT);
                                placed.setBlockData(chestData);
                                plugin.getLogger().fine(
                                        "[箱子搬运] 54格容器自动接双箱于 "
                                                + c.getX() + "," + c.getY() + "," + c.getZ());
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                // 接双箱失败不影响回填（回填按放置后容器实际大小自然截断）
                plugin.getLogger().fine("[箱子搬运] 接双箱失败(不影响回填): " + ex.getMessage());
            }
            try {
                ItemStack[] items = InventorySerializer.fromBase64(data);
                Inventory inv = ((org.bukkit.block.Container) st).getInventory();
                inv.clear();
                int n = Math.min(items.length, inv.getSize());
                for (int i = 0; i < n; i++) {
                    if (items[i] != null) inv.setItem(i, items[i]);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("[箱子搬运] 放置回填失败: " + ex.getMessage());
            }
        });
    }

    // ==================== 内部工具 ====================

    /** 交付打包箱给玩家（背包满则掉脚下），并提示。支持 1~N 个半边快照合并成一个箱 */
    private void deliver(Player p, PendingCarry... carries) {
        try {
            // 合并：顺序拼接各半边的非空物品（变长，保留原顺序）
            List<ItemStack> merged = new ArrayList<>();
            for (PendingCarry c : carries) {
                for (ItemStack it : c.contents) {
                    if (it != null && !it.getType().isAir()) merged.add(it);
                }
            }
            StringBuilder label = new StringBuilder();
            for (PendingCarry c : carries) {
                if (label.length() > 0) label.append('、');
                label.append(c.halfName);
            }
            // 合并物超过 27 格 → 仍打包成一个物理箱子（放置时按容器实际大小回填，
            // 多余 28+ 物品按放置后容器大小自然截断；用户实测大箱子≈54 格场景）
            ItemStack[] mergedArr = merged.toArray(new ItemStack[0]);
            String halfLabel = label.length() == 0 ? "小箱子" : label.toString();
            ItemStack packed = buildPackedChest(carries[0].sourceMat, halfLabel, mergedArr);
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(packed);
            if (!left.isEmpty()) {
                left.values().forEach(it ->
                        p.getWorld().dropItemNaturally(p.getLocation(), it));
            }
            p.sendMessage("§a[箱子搬运] §7已打包 §f" + halfLabel
                    + "§7（" + mergedArr.length + " 件物品），放下自动回填");
        } catch (Exception ex) {
            // 序列化失败兜底：内容直接掉落，绝不吞物
            p.sendMessage("§c[箱子搬运] §7打包失败，物品已直接掉落");
            for (PendingCarry c : carries) {
                for (ItemStack it : c.contents) {
                    if (it != null && !it.getType().isAir()) {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    }
                }
            }
            plugin.getLogger().warning("[箱子搬运] 打包失败: " + ex.getMessage());
        }
    }

    /** 构造打包箱物品：名称标识 + 防堆叠 + PDC 内容。
     *  ★ 33批五次：内容数 >27 → 用 TRAPPED_CHEST（54 格陷阱箱，放置后自动接成
     *    双箱塞得下全量）；≤27 件沿用来源箱子材质（木箱/铜箱保持原版观感）。 */
    private ItemStack buildPackedChest(Material sourceMat, String halfName,
                                       ItemStack[] contents) throws Exception {
        int count = countItems(contents);
        boolean big = count > 27;
        Material containerMat = big ? Material.TRAPPED_CHEST
                : (sourceMat != null ? sourceMat : Material.CHEST);
        ItemStack it = new ItemStack(containerMat);
        ItemMeta meta = it.getItemMeta();
        String matLabel = containerMat.name().toLowerCase(java.util.Locale.ROOT);
        meta.setDisplayName("§b§l打包箱 §7(" + halfName + " §8· " + matLabel
                + (big ? " §f54格" : "") + ")");
        List<String> lore = new ArrayList<>();
        lore.add("§7内含 §f" + count + " §7件物品");
        lore.add(big ? "§e放置后自动接双箱(54格)回填" : "§a放置后 §7自动回填内容");
        meta.setLore(lore);
        // 不同内容绝不堆叠，否则 PDC 串味
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(carryKey.key,
                PersistentDataType.STRING, InventorySerializer.toBase64(contents));
        it.setItemMeta(meta);
        return it;
    }

    /**
     * 可搬运的箱子材质：凡 *_CHEST 且非末影箱全收。
     * 26.2 起存在铜箱族（COPPER_CHEST / EXPOSED_ / WEATHERED_ / OXIDIZED_ / WAXED_ 等），
     * 用枚举名后缀判定可一并覆盖，且不引用具体常量，跨服务端 build 不会 NoSuchFieldError。
     */
    private static boolean isChestMaterial(Material mat) {
        if (mat == null || mat.isAir()) return false;
        String n = mat.name();
        // 28批：裸 CHEST 也要认——"CHEST".endsWith("_CHEST")=false，曾把普通木箱一票否决
        return n.equals("CHEST") || (n.endsWith("_CHEST") && !n.equals("ENDER_CHEST"));
    }

    /** 登记一个待抑制掉落的位置（tick 后自动放行，避免误伤其它掉落） */
    private void markSuppressDrop(Location loc) {
        suppressDrops.add(loc);
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> suppressDrops.remove(loc), DROP_SUPPRESS_TICKS);
    }

    /**
     * 掉落兜底抑制：部分版本/服务器路径下箱子内容经 loot table 走
     * BlockDropItemEvent 抛出，setDropItems(false) 拦不住 —— 这里补一刀，
     * 只对本次已接管（登记过）的方块位置生效，其它掉落一律不碰。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestDropItem(BlockDropItemEvent e) {
        if (suppressDrops.isEmpty()) return;
        if (suppressDrops.remove(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    private static boolean isEmpty(ItemStack[] a) {
        if (a == null || a.length == 0) return true;
        for (ItemStack i : a) {
            if (i != null && !i.getType().isAir()) return false;
        }
        return true;
    }

    /**
     * ★ 32批：物理移除一个箱子方块（setType(AIR, false) 两参）。
     *   Paper 26 的 Block.setType 只有 (Material) / (Material, boolean) 两参，
     *   无 (Material, boolean, boolean)。drops=false 不产掉落；被破半边此刻已
     *   是空气（BlockBreakEvent 后）setType 幂等无害；孤半是真正的连接态方块，
     *   清掉防其变回普通箱。
     */
    private void clearChestBlock(Block bl) {
        try {
            if (bl != null && bl.getType() != org.bukkit.Material.AIR) {
                bl.setType(org.bukkit.Material.AIR, false);
            }
        } catch (Throwable ex) {
            plugin.getLogger().fine("[箱子搬运] 清箱子方块失败: " + ex.getMessage());
        }
    }

    /**
     * ★ 33批四次修正：取邻接箱「双半全量」内容。
     *   彻底弃用 getInventory()（BlockBreakEvent 时刻连接双箱库存未同步、读回全空
     *   是 33 批三次实测实锤的「0件」根因）。统一双半都用 getSnapshotInventory：
     *   - 连箱双半：被破半 selfSnap(27) + 孤半 snapshotInventory(27) = 54 全量
     *   - 独立小箱：被破半 27 + 邻接半 27 = 各 27 格独立库存
     *   一条路径，不用 isSimilar、不用 getInventory。
     */
    private ItemStack[] newMergedContents(Block self, Block twin, ItemStack[] selfSnap, boolean bigHalf) {
        List<ItemStack> merged = new ArrayList<>();
        if (selfSnap != null) {
            for (ItemStack it : selfSnap) {
                if (it != null && !it.getType().isAir()) merged.add(it);
            }
        }
        // 孤半/邻接半：用 getSnapshotInventory（唯一可靠，不受连接态库存同步影响）
        ItemStack[] twinSnap = readSnapshotInventory(twin);
        if (twinSnap != null) {
            for (ItemStack it : twinSnap) {
                if (it != null && !it.getType().isAir()) merged.add(it);
            }
        }
        return merged.toArray(new ItemStack[0]);
    }

    /** 读方块 27 格快照库存（getSnapshotInventory，BlockBreakEvent 内可靠） */
    private static ItemStack[] readSnapshotInventory(Block bl) {
        try {
            BlockState st = bl.getState();
            if (st instanceof org.bukkit.block.Container) {
                return ((org.bukkit.block.Container) st)
                        .getSnapshotInventory().getContents();
            }
        } catch (Throwable ignored) { }
        return new ItemStack[0];
    }

    /** 取方块半边归属名（Chest.Type.LEFT/RIGHT），用于打包箱标签 */
    private String halfNameOf(Block bl) {
        try {
            org.bukkit.block.data.BlockData bd = bl.getBlockData();
            if (bd instanceof Chest) {
                Chest.Type t = ((Chest) bd).getType();
                if (t == Chest.Type.LEFT) return "大箱子·左半";
                if (t == Chest.Type.RIGHT) return "大箱子·右半";
            }
        } catch (Throwable ignored) { }
        return "小箱子";
    }

    /**
     * ★ 32批：找被破坏箱子的任意水平邻接箱（同 y，x±1 或 z±1，材质须也是箱子）。
     *   用于「被破坏那半」主动取走邻接孤半，合并成完整箱。
     *   33批扩展：从只查 x±1 扩到四向（x±1、z±1）——双连箱可能沿 z 方向连接，
     *   且「独立小箱紧挨着」也是水平邻接。任意一个邻接箱都算 twin。
     */
    private Block findTwin(Block self) {
        World w = self.getWorld();
        int x = self.getX(), y = self.getY(), z = self.getZ();
        Block[][] candidates = {
                {w.getBlockAt(x + 1, y, z), w.getBlockAt(x - 1, y, z)},
                {w.getBlockAt(x, y, z + 1), w.getBlockAt(x, y, z - 1)},
        };
        for (Block[] row : candidates) {
            for (Block c : row) {
                if (c != null && isChestMaterial(c.getType())) return c;
            }
        }
        return null;
    }

    /** 读方块内容快照：优先 getInventory（连接双箱=54 格全量），失败回退 27 格快照 */
    private static ItemStack[] readContents(Block bl) {
        try {
            BlockState st = bl.getState();
            if (st instanceof org.bukkit.block.Container) {
                org.bukkit.inventory.Inventory inv =
                        ((org.bukkit.block.Container) st).getInventory();
                if (inv != null) {
                    int n = inv.getSize();
                    if (n > 0) return inv.getStorageContents();
                }
            }
        } catch (Throwable ignored) { }
        try {
            BlockState st = bl.getState();
            if (st instanceof org.bukkit.block.Container) {
                return ((org.bukkit.block.Container) st)
                        .getSnapshotInventory().getContents();
            }
        } catch (Throwable ignored) { }
        return new ItemStack[0];
    }

    private static int countItems(ItemStack[] a) {
        int n = 0;
        for (ItemStack i : a) {
            if (i != null && !i.getType().isAir()) n += i.getAmount();
        }
        return n;
    }
}
