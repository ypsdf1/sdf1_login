package Sdf1_login;

import Sdf1_login.AreaProtection.AreaConfig;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领地方块/容器记录系统 —— 回声碎片（echo_shard）工具。
 * <p>
 * ★ 功能：
 * 1. 领地内方块破坏/放置 → 自动写入 land_block_log
 * 2. 领地内容器打开/拿取 → 自动写入 land_container_log
 * 3. 玩家手持回声碎片右键/左键方块或容器 → 聊天栏打印该坐标的历史记录
 * 4. /landrec give [玩家] → 发放回声碎片（由插件/管理员发放）
 * <p>
 * 记录保留 7 天滚动（由 Main 的每日清理定时器调用 prune）。
 */
public class LandRecordManager implements Listener {

    private final Main plugin;

    /** 回声碎片物品的 NBT 标识 key */
    public static final String ECHO_SHARD_KEY = "sdf1_echo_shard";
    /** 回声碎片物品材质（1.19+ 存在） */
    public static final Material ECHO_SHARD_MATERIAL = Material.ECHO_SHARD;

    /** 记录查询默认条数 */
    private static final int QUERY_LIMIT = 20;
    /** 记录保留天数 */
    public static final int RETENTION_DAYS = 7;

    private final SimpleDateFormat sdf =
            new SimpleDateFormat("MM-dd HH:mm");

    /**
     * 容器打开时的快照：用于关闭时计算"拿了什么"。
     * key = 玩家名（MC 同一时刻只能打开一个容器 GUI，故按玩家唯一即可）。
     */
    private final Map<String, ContainerSnapshot> openSnapshots =
            new ConcurrentHashMap<>();

    static class ContainerSnapshot {
        String landName;
        int landId;
        String world;
        int x, y, z;
        String containerType;
        Map<String, Integer> before = new HashMap<>();
    }

    public LandRecordManager(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 回声碎片物品 ====================

    /** 创建一枚回声碎片（带 NBT 标识，便于精准识别） */
    public ItemStack createEchoShard() {
        ItemStack item = new ItemStack(ECHO_SHARD_MATERIAL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b§l回声碎片");
            meta.setLore(Arrays.asList(
                    "§7右键/左键 方块或容器",
                    "§7查看领地内的破坏/拿取记录",
                    "§8（领地监控工具）"));
            NamespacedKey key =
                    new NamespacedKey(plugin, ECHO_SHARD_KEY);
            meta.getPersistentDataContainer().set(
                    key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** 判断物品是否为回声碎片（材质 + NBT 双重校验） */
    public boolean isEchoShard(ItemStack item) {
        if (item == null || item.getType() != ECHO_SHARD_MATERIAL)
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        NamespacedKey key =
                new NamespacedKey(plugin, ECHO_SHARD_KEY);
        return meta.getPersistentDataContainer().has(
                key, PersistentDataType.BYTE);
    }

    /** 直接发放一枚回声碎片给玩家（GUI 菜单调用） */
    public void giveEchoShard(Player p) {
        ItemStack shard = createEchoShard();
        HashMap<Integer, ItemStack> left =
                p.getInventory().addItem(shard);
        if (!left.isEmpty()) {
            p.sendMessage("§c§l[回声碎片] §f你的背包已满，无法发放");
        } else {
            p.sendMessage("§a§l[回声碎片] §f你获得了§b回声碎片§f，右键/左键方块或容器查看领地记录");
        }
    }

    // ==================== 记录写入（由 AreaProtection 钩子调用）====================

    /** 记录方块破坏 */
    public void recordBlockBreak(AreaConfig ac, Player p,
                                 Block block) {
        recordBlock(ac, p, block, "break");
    }

    /** 记录方块放置 */
    public void recordBlockPlace(AreaConfig ac, Player p,
                                 Block block) {
        recordBlock(ac, p, block, "place");
    }

    /** 记录红石元器件状态变化（压力板、按钮、拉杆等） */
    public void recordRedstoneStateChange(AreaConfig ac, Player p,
                                         Block block) {
        recordBlock(ac, p, block, "state_change");
    }

    /** ★ 记录告示牌放置/编辑（含内容） */
    public void recordSignEdit(AreaConfig ac, Player p, Block block,
                               String[] lines, boolean isEdit) {
        try {
            int landId = plugin.areaProtection.getLandIdFromDb(ac.name);
            // 拼接4行内容，空行跳过
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                if (line != null && !line.isEmpty()) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(line);
                }
            }
            String detail = sb.toString();
            String action = isEdit ? "sign_edit" : "sign_place";
            plugin.getDb().recordLandBlockWithDetail(
                    ac.name, landId,
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    p.getName(), action,
                    block.getType().name(), detail);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void recordBlock(AreaConfig ac, Player p, Block block,
                             String action) {
        try {
            int landId = plugin.areaProtection.getLandIdFromDb(
                    ac.name);
            plugin.getDb().recordLandBlock(
                    ac.name, landId,
                    block.getWorld().getName(),
                    block.getX(), block.getY(), block.getZ(),
                    p.getName(), action,
                    block.getType().name());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 记录容器打开，并保存打开瞬间容器快照（用于关闭时计算拿取差量）。
     * 仅在容器确实被允许打开时调用（钩子位于 AreaProtection
     * 权限/取消检查之后）。
     */
    public void recordContainerOpen(AreaConfig ac, Player p,
                                    Location loc, String containerType,
                                    Inventory inv) {
        try {
            int landId = plugin.areaProtection.getLandIdFromDb(
                    ac.name);
            plugin.getDb().recordLandContainer(
                    ac.name, landId,
                    loc.getWorld().getName(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                    p.getName(), "open", containerType, "", 0);

            ContainerSnapshot snap = new ContainerSnapshot();
            snap.landName = ac.name;
            snap.landId = landId;
            snap.world = loc.getWorld().getName();
            snap.x = loc.getBlockX();
            snap.y = loc.getBlockY();
            snap.z = loc.getBlockZ();
            snap.containerType = containerType;
            snap.before = scanInventory(inv);
            openSnapshots.put(p.getName(), snap);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ==================== 容器关闭：计算拿取差量 ====================

    @EventHandler
    public void onContainerClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        ContainerSnapshot snap = openSnapshots.remove(p.getName());
        if (snap == null) return;

        Map<String, Integer> after = scanInventory(e.getInventory());

        // 1. 拿取：before > after
        for (Map.Entry<String, Integer> en : snap.before.entrySet()) {
            int taken = en.getValue() - after.getOrDefault(en.getKey(), 0);
            if (taken > 0) {
                recordContainerChange(snap, p.getName(), "take",
                        en.getKey(), taken);
            }
        }

        // 2. 存入：after > before
        for (Map.Entry<String, Integer> en : after.entrySet()) {
            int put = en.getValue() - snap.before.getOrDefault(en.getKey(), 0);
            if (put > 0) {
                recordContainerChange(snap, p.getName(), "put",
                        en.getKey(), put);
            }
        }
    }

    private void recordContainerChange(ContainerSnapshot snap,
                                       String playerName, String action,
                                       String detail, int amount) {
        try {
            plugin.getDb().recordLandContainer(
                    snap.landName, snap.landId,
                    snap.world, snap.x, snap.y, snap.z,
                    playerName, action, snap.containerType,
                    detail, amount);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /** 扫描容器库存，按"类型名"聚合数量 */
    private Map<String, Integer> scanInventory(Inventory inv) {
        Map<String, Integer> map = new HashMap<>();
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType() == Material.AIR)
                continue;
            map.merge(it.getType().name(),
                    it.getAmount(), Integer::sum);
        }
        return map;
    }

    // ==================== 回声碎片查询（右键/左键）====================

    @EventHandler
    public void onShardInteract(PlayerInteractEvent e) {
        ItemStack item = e.getItem();
        if (item == null || !isEchoShard(item)) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_BLOCK && a != Action.LEFT_CLICK_BLOCK
                && a != Action.RIGHT_CLICK_AIR
                && a != Action.LEFT_CLICK_AIR) return;

        Player p = e.getPlayer();
        // 阻止误触方块/打开容器
        e.setCancelled(true);

        Block target = e.getClickedBlock();
        if (target == null) {
            // 视线选中的方块（非 deprecated 的 getTargetBlockExact）
            Block sight = p.getTargetBlockExact(6);
            if (sight != null && sight.getType() != Material.AIR)
                target = sight;
        }
        if (target == null) {
            p.sendMessage("§c§l[回声碎片] §f你面前没有可查询的方块或容器");
            return;
        }
        queryAndPrint(p, target.getLocation());
    }

    private void queryAndPrint(Player p, Location loc) {
        String world = loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(),
                z = loc.getBlockZ();

        List<Map<String, Object>> blocks =
                plugin.getDb().getLandBlockLogAt(
                        world, x, y, z, QUERY_LIMIT);
        List<Map<String, Object>> containers =
                plugin.getDb().getLandContainerLogAt(
                        world, x, y, z, QUERY_LIMIT);

        if (blocks.isEmpty() && containers.isEmpty()) {
            p.sendMessage("§6§l[回声碎片] §f坐标 ("
                    + x + "," + y + "," + z + ") 暂无领地记录");
            return;
        }

        // 收集所有需要翻译的材料名
        Set<String> materials = new LinkedHashSet<>();
        for (Map<String, Object> r : blocks) {
            materials.add((String) r.get("material"));
        }
        for (Map<String, Object> r : containers) {
            if ("take".equals(r.get("action")) || "put".equals(r.get("action"))) {
                materials.add((String) r.get("detail"));
            }
            // 容器类型（如 CHEST）也要翻译
            String ct = (String) r.get("container_type");
            if (ct != null && !ct.isEmpty()) {
                materials.add(ct);
            }
        }

        // 异步批量翻译
        MaterialTranslator.translateBatch(materials)
                .thenAccept(translations -> {
                    // 回到主线程发送消息
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        printResults(p, x, y, z, blocks, containers, translations);
                    });
                });
    }

    private void printResults(Player p, int x, int y, int z,
                              List<Map<String, Object>> blocks,
                              List<Map<String, Object>> containers,
                              Map<String, String> translations) {
        p.sendMessage("§6§l[回声碎片] §e坐标 ("
                + x + "," + y + "," + z + ") 的记录：");

        if (!blocks.isEmpty()) {
            p.sendMessage("§b— 方块记录 —");
            for (Map<String, Object> r : blocks) {
                String action = (String) r.get("action");
                String act;
                if ("break".equals(action)) {
                    act = "§c破坏";
                } else if ("place".equals(action)) {
                    act = "§a放置";
                } else if ("state_change".equals(action)) {
                    act = "§e状态变化";
                } else if ("sign_place".equals(action)) {
                    act = "§a放置告示牌";
                } else if ("sign_edit".equals(action)) {
                    act = "§b编辑告示牌";
                } else {
                    act = "§7" + action;
                }
                String mat = (String) r.get("material");
                String zh = translations.getOrDefault(mat, MaterialTranslator.toReadable(mat));
                StringBuilder msg = new StringBuilder();
                msg.append(" §7").append(fmt((Long) r.get("time")))
                   .append(" §f").append(r.get("player_name"))
                   .append(" ").append(act)
                   .append(" §e").append(mat).append(" §a").append(zh);
                // ★ 告示牌内容显示
                String detail = (String) r.get("detail");
                if (detail != null && !detail.isEmpty()
                        && ("sign_place".equals(action) || "sign_edit".equals(action))) {
                    msg.append(" §7[§f").append(detail).append("§7]");
                }
                p.sendMessage(msg.toString());
            }
        }

        if (!containers.isEmpty()) {
            p.sendMessage("§b— 容器记录 —");
            for (Map<String, Object> r : containers) {
                String act = (String) r.get("action");
                if ("open".equals(act)) {
                    String ct = (String) r.get("container_type");
                    String ctZh = translations.getOrDefault(ct, MaterialTranslator.toReadable(ct));
                    p.sendMessage(" §7" + fmt((Long) r.get("time"))
                            + " §f" + r.get("player_name")
                            + " §d打开§7[" + ct + "] §a" + ctZh);
                } else if ("take".equals(act)) {
                    String mat = (String) r.get("detail");
                    String zh = translations.getOrDefault(mat, MaterialTranslator.toReadable(mat));
                    p.sendMessage(" §7" + fmt((Long) r.get("time"))
                            + " §f" + r.get("player_name")
                            + " §c拿取 §e" + mat + " §a" + zh
                            + " §7x" + r.get("amount"));
                } else if ("put".equals(act)) {
                    String mat = (String) r.get("detail");
                    String zh = translations.getOrDefault(mat, MaterialTranslator.toReadable(mat));
                    p.sendMessage(" §7" + fmt((Long) r.get("time"))
                            + " §f" + r.get("player_name")
                            + " §a存入 §e" + mat + " §a" + zh
                            + " §7x" + r.get("amount"));
                }
            }
        }
    }

    private String fmt(long ms) {
        return sdf.format(new Date(ms));
    }

    // ==================== 命令：/landrec ====================

    public boolean handleCommand(CommandSender sender,
                                 String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0];
        if (sub.equalsIgnoreCase("give")) {
            // 发放权限：给自己（self）任意玩家可发；发给他人需 OP 或领地管理员
            boolean isOp = sender.isOp();
            boolean isAreaAdmin = (sender instanceof Player)
                    && plugin.areaProtection.isAreaAdmin((Player) sender);

            Player target;
            if (args.length >= 2) {
                if (!isOp && !isAreaAdmin) {
                    sender.sendMessage("§c§l[回声碎片] §f你无权向他人发放回声碎片");
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§c§l[回声碎片] §f玩家 " + args[1] + " 不在线");
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage("§c§l[回声碎片] §f请指定要发放的玩家");
                return true;
            }
            giveEchoShard(target);
            if (target != sender)
                sender.sendMessage("§a§l[回声碎片] §f已发放回声碎片给 " + target.getName());
            return true;
        }
        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l[回声碎片] §e指令：");
        sender.sendMessage(" §7/landrec give §f- 给自己发放一枚回声碎片");
        sender.sendMessage(" §7/landrec give <玩家> §f- 向他人发放（需 OP/领地管理员）");
        sender.sendMessage(" §7手持回声碎片右键/左键方块或容器 §f- 查看该处记录");
    }
}
