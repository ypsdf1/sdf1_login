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

    /** 已接管但仍需拦掉落的位置（BlockDropItemEvent 兜底，tick 后自动放行） */
    private final Set<Location> suppressDrops = ConcurrentHashMap.newKeySet();

    public ChestCarryManager(Main main) {
        this.main = main;
        this.plugin = main;
        this.carryKey = new NamespacedKeyRef(main, "chest_carry_data");
    }

    // ==================== 拆箱：拦截 → 挂起/成组 ====================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestBreak(BlockBreakEvent e) {
        Material mat = e.getBlock().getType();
        if (!isChestMaterial(mat)) return;
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
        AreaProtection.PermissionLevel lv = ap.getPermissionLevel(p, ac);
        if (lv != AreaProtection.PermissionLevel.ADMIN
                && lv != AreaProtection.PermissionLevel.OWNER
                && lv != AreaProtection.PermissionLevel.MEMBER) return;

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
        PendingCarry prev = pending.remove(id);
        long now = System.currentTimeMillis();
        if (prev != null && (now - prev.ts) <= WINDOW_MS) {
            // 成组：2 箱 1 组，一并交付
            deliver(p, prev);
            deliver(p, new PendingCarry(id, b.getWorld().getName(),
                    p.getLocation(), mat, halfName, snap, now));
        } else {
            // 首破：挂起观察 2 秒，等第二破成组或超时单独交付
            PendingCarry cur = new PendingCarry(id, b.getWorld().getName(),
                    p.getLocation(), mat, halfName, snap, now);
            pending.put(id, cur);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                PendingCarry pc = pending.get(id);
                if (pc != cur) return; // 已成组交付或被新记录替换
                pending.remove(id);
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline()) {
                    deliver(online, pc);
                } else {
                    // 玩家离线兜底：打包箱掉在原地
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

    /** 交付一个打包箱给玩家（背包满则掉脚下），并提示 */
    private void deliver(Player p, PendingCarry c) {
        try {
            ItemStack packed = buildPackedChest(c.sourceMat, c.halfName, c.contents);
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(packed);
            if (!left.isEmpty()) {
                left.values().forEach(it ->
                        p.getWorld().dropItemNaturally(p.getLocation(), it));
            }
            p.sendMessage("§a[箱子搬运] §7已打包 §f" + c.halfName
                    + "§7（" + countItems(c.contents) + " 件物品），放下自动回填");
        } catch (Exception ex) {
            // 序列化失败兜底：内容直接掉落，绝不吞物
            p.sendMessage("§c[箱子搬运] §7打包失败，物品已直接掉落");
            for (ItemStack it : c.contents) {
                if (it != null && !it.getType().isAir()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), it);
                }
            }
            plugin.getLogger().warning("[箱子搬运] 打包失败: " + ex.getMessage());
        }
    }

    /** 构造打包箱物品：名称标识 + 防堆叠 + PDC 内容（沿用来源箱子材质） */
    private ItemStack buildPackedChest(Material sourceMat, String halfName,
                                       ItemStack[] contents) throws Exception {
        ItemStack it = new ItemStack(sourceMat != null ? sourceMat : Material.CHEST);
        ItemMeta meta = it.getItemMeta();
        String matLabel = sourceMat == null ? "木箱"
                : sourceMat.name().toLowerCase(java.util.Locale.ROOT);
        meta.setDisplayName("§b§l打包箱 §7(" + halfName + " §8· " + matLabel + ")");
        List<String> lore = new ArrayList<>();
        lore.add("§7内含 §f" + countItems(contents) + " §7件物品");
        lore.add("§a放置后 §7自动回填内容");
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

    private static int countItems(ItemStack[] a) {
        int n = 0;
        for (ItemStack i : a) {
            if (i != null && !i.getType().isAir()) n += i.getAmount();
        }
        return n;
    }
}
