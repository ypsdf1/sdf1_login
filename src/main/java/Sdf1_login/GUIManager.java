package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;




import java.util.*;

public class GUIManager implements Listener {

    private final Main plugin;

    public static final String T_MAIN =
            "§6§l服务器菜单";
    public static final String T_MORE =
            "§6§l更多功能";
    public static final String T_EDITOR =
            "§6§l编辑菜单项";
    public static final String T_BIOME =
            "§b§l目标群系传送";
    public static final String T_MY_INFO =
            "§6§l我的信息";
    public static final String T_GIFT_STAGES =
            "§6§l礼包阶段";
    public static final String T_ADMIN =
            "§6§l管理员面板";
    public static final String T_INVITE =
            "§6§l邀请数据";
    public static final String T_USER_MGMT =
            "§6§l用户管理";
    public static final String T_TASK_CENTER =
            "§6§l任务中心";
    private final Map<UUID, Integer> userMgmtPages = new HashMap<>();
    private static final int UMGMT_PAGE_SIZE = 45;

    // ★ 宝箱物品拦截计数（事不过三）
    private final ConcurrentHashMap<String, Integer>
            treasureMenuBlockCount =
            new ConcurrentHashMap<>();



    public GUIManager(Main plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, T_MAIN);
// ===== 自定义菜单项 =====
        if (plugin.getMenu() != null) {
            List<MenuManager.MenuItem> customs =
                    plugin.getMenu().getItems();

            // 已占用的槽位
            Set<Integer> occupied = new HashSet<>();
            occupied.add(10);
            occupied.add(12);
            occupied.add(14);
            occupied.add(16);
            occupied.add(19);
            occupied.add(21);
            occupied.add(23);
            occupied.add(25);
            occupied.add(29);
            occupied.add(31);
            occupied.add(32);
            occupied.add(34);

            // 自定义区域：第1排(0-8) + 第5排(36-44) + 第6排(45-53)
            int[] customArea = {
                    0, 1, 2, 3, 4, 5, 6, 7, 8,
                    36, 37, 38, 39, 40, 41, 42, 43, 44,
                    45, 46, 47, 48, 49, 50, 51, 52, 53
            };

            int autoIdx = 0;
            for (int ci = 0;
                 ci < customs.size() && ci < 27; ci++) {
                MenuManager.MenuItem mi =
                        customs.get(ci);

                // 权限过滤
                if ("OP".equals(mi.permType)
                        && !p.isOp()
                        && !isAdmin(p)) continue;

                int targetSlot = -1;

                // 玩家指定了格子？
                if (mi.slot != null) {
                    targetSlot = findBestSlot(
                            mi.slot - 1, occupied);
                }

                // 未指定或冲突 → 自动排队
                if (targetSlot < 0) {
                    while (autoIdx < customArea.length) {
                        int s = customArea[autoIdx++];
                        if (!occupied.contains(s)) {
                            targetSlot = s;
                            break;
                        }
                    }
                }

                if (targetSlot < 0
                        || targetSlot >= 54) continue;
                occupied.add(targetSlot);

                // 放置物品
                ItemStack icon =
                        mi.icon.clone();
                ItemMeta im =
                        icon.getItemMeta();
                im.setDisplayName(
                        "§e§l" + mi.title);
                im.setLore(java.util.Arrays.asList(
                        "§7指令: §f"
                                + mi.command,
                        "§7权限: §f"
                                + mi.permType,
                        "",
                        "§a左键执行"));
                icon.setItemMeta(im);
                g.setItem(targetSlot, icon);
            }
        }

// 背景填充
        fillBg(g);
        p.openInventory(g);

        // ===== Row1(9-17): 核心功能 =====
        g.setItem(10, mkItem(Material.PLAYER_HEAD,
                "§a§l我的信息"));
        g.setItem(12, mkItem(Material.PAPER,
                "§b§l邀请数据"));
        g.setItem(14, mkItem(Material.EMERALD,
                "§d§l积分商城"));
        g.setItem(16, mkItem(Material.COMPASS,
                "§b§l群系传送",
                "§7传送到指定群系"));

        // ===== Row2(18-26): 日常功能 =====
        g.setItem(19, mkItem(Material.BOOK,
                "§6工单系统"));
        g.setItem(21, mkItem(Material.EMERALD_BLOCK,
                "§a§l每日签到",
                "§7签到获取积分和惊喜奖励"));
        g.setItem(23, mkItem(Material.ENDER_CHEST,
                "§6§l任务中心"));
        if (p.isOp() || isAdmin(p)) {
            g.setItem(25, mkItem(Material.HOPPER,
                    "§6§l垃圾回收站",
                    "§7查看和取回清理的物品"));


        }
        // ===== Row3(27-35): 管理功能 =====
        // ★ 商店 - 所有人都能看 ★
        g.setItem(27, mkItem(Material.CHEST,
                "§6§l商店",
                "§7浏览、购买、出售物品",
                "§7货币: 债券"));

        if (isAdmin(p)) {
            g.setItem(29, mkItem(
                    Material.REDSTONE_BLOCK,
                    "§c§l管理员面板"));
        }
        if (p.isOp()) {
            g.setItem(31, mkItem(Material.ECHO_SHARD,
                    "§c§l管理菜单",
                    "§7添加、编辑、删除菜单项"));
        }
        // 在 openMain 方法中，Row3 区域加：

        // 余额查询已移除Vault支持，仅显示债券余额
        int myBonds = plugin.getBonds().getBonds(p.getName());
        g.setItem(32, mkItem(Material.EMERALD,
                "§b§l我的钱包",
                "§7查看债券余额与流水记录"));

// ★ 余额操作 - 仅OP和tag玩家 ★
        if (isAdmin(p)) {
            g.setItem(34, mkItem(Material.GOLD_INGOT,
                    "§6§l余额操作",
                    "§7给钱、扣钱"));
        }

        // ★ 领地系统 - 39号位（读取用户偏好）
        int landUiMode = 1;
        try {
            landUiMode = plugin.getDb()
                    .getUiMode(p.getName());
        } catch (Exception ignored) {}
        g.setItem(39, mkItem(Material.GRASS_BLOCK,
                "§a§l领地系统",
                "§7管理你的领地和权限",
                "",
                landUiMode == 0
                        ? "§e点击打开GUI"
                        : "§e点击打开CLI"));



        // ===== Row0(0-8): 自定义菜单项 =====
        if (plugin.getMenu() != null) {
            List<MenuManager.MenuItem> customs =
                    plugin.getMenu().getItems();
            for (int i = 0;
                 i < customs.size() && i < 9;
                 i++) {
                MenuManager.MenuItem mi =
                        customs.get(i);
                if ("OP".equals(mi.permType)
                        && !p.isOp()
                        && !isAdmin(p)) continue;
                ItemStack icon =
                        mi.icon.clone();
                ItemMeta im =
                        icon.getItemMeta();
                im.setDisplayName(
                        "§e§l" + mi.title);
                im.setLore(Arrays.asList(
                        "§7指令: §f"
                                + mi.command,
                        "§7权限: §f"
                                + mi.permType,
                        "",
                        "§a左键执行"));
                icon.setItemMeta(im);
                g.setItem(i, icon);
            }
        }

        // 背景最后填充（不覆盖物品）
        fillBg(g);
        p.openInventory(g);
    }
    private int findBestSlot(
            int requested,
            Set<Integer> occupied) {
        // 目标位空闲直接用
        if (requested >= 0 && requested < 54
                && !occupied.contains(requested)) {
            return requested;
        }
        // BFS上下左右找最近空位
        int row = requested / 9;
        int col = requested % 9;
        boolean[][] visited =
                new boolean[6][9];
        java.util.Queue<int[]> queue =
                new java.util.LinkedList<>();
        queue.add(new int[]{row, col});
        visited[row][col] = true;
        int[][] dirs = {
                {-1, 0}, {1, 0},
                {0, -1}, {0, 1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            for (int[] d : dirs) {
                int nr = cur[0] + d[0];
                int nc = cur[1] + d[1];
                if (nr >= 0 && nr < 6
                        && nc >= 0 && nc < 9
                        && !visited[nr][nc]) {
                    visited[nr][nc] = true;
                    int s = nr * 9 + nc;
                    if (!occupied.contains(s)) {
                        return s;
                    }
                    queue.add(new int[]{nr, nc});
                }
            }
        }
        return -1;
    }

    public void openMenuManager(Player p) {
        List<MenuManager.MenuItem> list =
                plugin.getMenu().getItems();
        Inventory g = Bukkit.createInventory(
                null, 27,
                "§c§l菜单管理");
        fillBg(g);

        for (int i = 0;
             i < list.size() && i < 9; i++) {
            MenuManager.MenuItem mi = list.get(i);
            g.setItem(i, mkItem(
                    mi.icon.getType(),
                    "§e" + mi.title,
                    "§7指令: " + mi.command,
                    "§7权限: " + mi.permType,
                    "",
                    "§a左键编辑",
                    "§7右键切换权限"));
        }

        g.setItem(22, mkItem(Material.LIME_WOOL,
                "§a§l添加菜单项"));

        g.setItem(26, mkItem(Material.ARROW,
                "§7返回"));

        p.openInventory(g);
    }


    public void openBalanceOps(Player p) {
        Inventory g = Bukkit.createInventory(null, 54, "§d§l余额操作");
        fillBg(g);
        // Vault经济支持已移除，仅显示债券余额

        int slot = 0;
        for (Player op : Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            boolean isSelf = op.getName().equals(p.getName());
            int bondBal = plugin.getBonds().getBonds(op.getName());
            g.setItem(slot, mkItem(
                    isSelf ? Material.EMERALD_BLOCK : Material.PLAYER_HEAD,
                    "§e" + op.getName(),
                    "§7债券: §e" + bondBal + " §6枚",
                    "",
                    "§a仅显示债券余额（Vault经济支持已移除）"));
            slot++;
        }

        g.setItem(49, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    public void openShop(Player p) {
        p.closeInventory();
        plugin.getShopManager().openShopMain(p);
    }

    public void openTicket(Player p) {
        p.closeInventory();
        p.performCommand("sdf1_login ticket");
    }
    @EventHandler
    public void onInvClose(

            InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player))
            return;
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        Player p3 = (Player) e.getPlayer();
        String t = e.getView().getTitle();
        if (!(e.getPlayer() instanceof Player)) return;
        Player p2 = (Player) e.getPlayer();

        if (!t.equals(T_EDITOR)) return;

        // ★ 编辑器关闭时清理空项 ★
        List<MenuManager.MenuItem> list =
                plugin.getMenu().getItems();
        if (!list.isEmpty()) {
            MenuManager.MenuItem last =
                    list.get(list.size() - 1);
            if ((last.command == null
                    || last.command.trim().isEmpty()
                    || last.command.trim()
                    .equals("/"))
                    && "新菜单项"
                    .equals(last.title)) {
                list.remove(list.size() - 1);
            }
        }
    }

    public void openSubMenu(
            Player p, String fileName) {
        // ★ 剥离MC颜色代码（§f、§7等），防止污染文件名
        fileName = fileName.replaceAll("\u00A7[0-9a-fk-or]", "").trim();
        String menuTitle =
                "§6§l" + fileName
                        .replace(".txt", "");
        Inventory g = Bukkit.createInventory(
                null, 54, menuTitle);
        fillBg(g);

        java.io.File dir = plugin.getDataFolder();
        java.io.File f = null;
        String cleanName = fileName.trim();

        plugin.getLogger().info(
                "[Menu] openSubMenu: searching '"
                        + cleanName + "' in '"
                        + dir.getAbsolutePath() + "'");

        // ★ 第1步：listFiles遍历匹配
        java.io.File[] allTxt = dir.listFiles(
                (d, n) -> n.toLowerCase()
                        .endsWith(".txt"));
        if (allTxt != null) {
            plugin.getLogger().info(
                    "[Menu] 目录.txt文件数: "
                            + allTxt.length);
            for (java.io.File ff : allTxt) {
                plugin.getLogger().info(
                        "[Menu]   > " + ff.getName()
                                + " size=" + ff.length());
                if (f == null && ff.getName()
                        .equalsIgnoreCase(cleanName)) {
                    f = ff;
                }
            }
        } else {
            plugin.getLogger().warning(
                    "[Menu] listFiles返回null!");
        }

        // ★ 第2步：直接构造路径
        if (f == null) {
            f = new java.io.File(dir, cleanName);
            plugin.getLogger().info(
                    "[Menu] 回退构造路径: "
                            + f.getAbsolutePath()
                            + " exists=" + f.exists()
                            + " size=" + f.length());
        }

        // ★ 第3步：逐个尝试读取方法（不检查exists，直接试）
        List<String> lines = null;
        if (f != null) {
            // 方法1：NIO UTF-8
            try {
                lines = java.nio.file.Files
                        .readAllLines(f.toPath(),
                                java.nio.charset
                                        .StandardCharsets
                                        .UTF_8);
                plugin.getLogger().info(
                        "[Menu] UTF-8读取: "
                                + lines.size() + "行");
            } catch (Throwable t) {
                plugin.getLogger().warning(
                        "[Menu] UTF-8失败: "
                                + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
            }

            // 方法2：NIO GBK
            if (lines == null || lines.isEmpty()) {
                try {
                    lines = java.nio.file.Files
                            .readAllLines(f.toPath(),
                                    java.nio.charset
                                            .Charset
                                            .forName("GBK"));
                    plugin.getLogger().info(
                            "[Menu] GBK读取: "
                                    + lines.size() + "行");
                } catch (Throwable t) {
                    plugin.getLogger().warning(
                            "[Menu] GBK失败: "
                                    + t.getMessage());
                }
            }

            // 方法3：FileInputStream原始字节
            if (lines == null || lines.isEmpty()) {
                try {
                    java.io.FileInputStream fis =
                            new java.io.FileInputStream(f);
                    byte[] raw = fis.readAllBytes();
                    fis.close();
                    if (raw.length > 0) {
                        plugin.getLogger().info(
                                "[Menu] 原始字节: "
                                        + raw.length + "B");
                        // 尝试UTF-8解码
                        String content = new String(raw,
                                java.nio.charset
                                        .StandardCharsets
                                        .UTF_8);
                        java.util.List<String> rawLines =
                                java.util.Arrays.asList(
                                        content.split("\\r?\\n"));
                        lines = rawLines;
                        plugin.getLogger().info(
                                "[Menu] 原始读取: "
                                        + lines.size() + "行");
                    }
                } catch (Throwable t) {
                    plugin.getLogger().warning(
                            "[Menu] 原始读取失败: "
                                    + t.getMessage());
                }
            }
        }

        // ★ 打印前5行调试
        if (lines != null && !lines.isEmpty()) {
            plugin.getLogger().info(
                    "[Menu] 最终行数: "
                            + lines.size());
            for (int i = 0;
                 i < Math.min(5, lines.size());
                 i++) {
                plugin.getLogger().info(
                        "[Menu]   L" + (i + 1)
                                + ": [" + lines.get(i)
                                + "]");
            }
        }

        if (lines == null || lines.isEmpty()) {
            p.sendMessage("§c§l子菜单读取失败§r §7"
                    + fileName);
            p.sendMessage("§7文件: "
                    + (f != null
                    ? f.getAbsolutePath()
                    + " (" + f.length() + "B)"
                    : "null"));
            p.sendMessage("§7存在: "
                    + (f != null ? f.exists() : "N/A"));
            if (allTxt != null) {
                p.sendMessage("§7目录.txt文件:");
                for (java.io.File ff : allTxt) {
                    p.sendMessage("§7  "
                            + ff.getName()
                            + " (" + ff.length()
                            + "B)");
                }
            }
            return;
        }
            List<String[]> items =
                    new ArrayList<>();
            StringBuilder buf =
                    new StringBuilder();
            boolean inItem = false;

            for (String raw : lines) {
                String l = raw.trim();
                if (l.startsWith("#")
                        || l.startsWith("//"))
                    continue;
                if (l.contains("<!--")
                        || l.contains("/*"))
                    continue;
                int h = l.indexOf('#');
                int s = l.indexOf("//");
                if (h >= 0)
                    l = l.substring(0, h)
                            .trim();
                if (s >= 0 && (h < 0 || s < h))
                    l = l.substring(0, s)
                            .trim();
                if (l.equals("{")) {
                    inItem = true;
                    buf.setLength(0);
                    continue;
                }
                if (l.equals("}") && inItem) {
                    String[] parsed =
                            parseSubItem(
                                    buf.toString());
                    if (parsed != null)
                        items.add(parsed);
                    inItem = false;
                    continue;
                }
                if (inItem)
                    buf.append(l).append("\n");
            }

            for (int i = 0;
                 i < items.size() && i < 45;
                 i++) {
                String[] item = items.get(i);
                Material mat = Material.PAPER;
                try {
                    mat = Material.valueOf(
                            item.length > 2
                                    ? item[2]
                                    : "PAPER");
                } catch (Exception ignored) {}
                ItemStack it =
                        new ItemStack(mat);
                ItemMeta im =
                        it.getItemMeta();
                im.setDisplayName(
                        "§e§l" + item[0]);
                im.setLore(Arrays.asList(
                        "§7指令: §f"
                                + item[1]));
                it.setItemMeta(im);
                g.setItem(i, it);
            }

        g.setItem(49, mkItem(
                Material.ARROW,
                "§c返回主菜单"));
        p.openInventory(g);
    }

    private String[] parseSubItem(
            String block) {
        String title = "";
        String cmd = "";
        String icon = "PAPER";
        for (String line : block.split("\n")) {
            line = line.trim();
            String v = "";
            int idx = line.indexOf('\uff1a');
            if (idx < 0)
                idx = line.indexOf(':');
            if (idx >= 0) {
                v = line.substring(idx + 1)
                        .trim();
                if (v.startsWith("\"")
                        && v.endsWith("\""))
                    v = v.substring(1,
                            v.length() - 1);
            }
            if (line.startsWith("标题"))
                title = v;
            else if (line.startsWith("指令")) {
                cmd = v;
                if (!cmd.isEmpty()
                        && !cmd.startsWith("/"))
                    cmd = "/" + cmd;
            } else if (line.startsWith("图标"))
                icon = v;
        }
        if (title.isEmpty()) return null;
        return new String[]{title, cmd, icon};
    }


    public void openBiomeMenu(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, T_BIOME);
        fillBg(g);

        String[][] biomes = {
                {"海洋", "WATER_BUCKET"},
                {"平原", "GRASS_BLOCK"},
                {"森林", "OAK_LOG"},
                {"沙漠", "SAND"},
                {"恶地", "TERRACOTTA"},
                {"雪原", "SNOW_BLOCK"},
                {"沼泽", "LILY_PAD"},
                {"河流", "COD"},
                {"地下", "DEEPSLATE"},
                {"蘑菇岛", "RED_MUSHROOM"},
                {"下界", "NETHERRACK"},
                {"末地", "END_STONE"},
        };
        String[] colors = {
                "§b", "§a", "§2", "§e",
                "§6", "§f", "§a", "§9",
                "§8", "§c", "§4", "§5"
        };

        for (int i = 0;
             i < biomes.length; i++) {
            ItemStack it = new ItemStack(
                    Material.valueOf(
                            biomes[i][1]));
            ItemMeta im = it.getItemMeta();
            im.setDisplayName(
                    colors[i] + biomes[i][0]);
            im.setLore(Arrays.asList(
                    "§7点击传送"));
            it.setItemMeta(im);
            g.setItem(i, it);
        }

        g.setItem(49, mkItem(
                Material.ARROW,
                "§c返回主菜单"));
        p.openInventory(g);
    }

    // 常量
    private static final int BIOME_SCAN_STEP = 32;
    private static final int BIOME_SCAN_MAX = 512;
    private static final int BIOME_BATCH = 30;

    public void teleportToBiome(
            Player p, String category) {
        p.sendMessage("§e正在搜索 "
                + category + " 群系...");

        // 1. 纯计算收集候选坐标（不碰Bukkit API）
        int baseX = p.getLocation().getBlockX();
        int baseZ = p.getLocation().getBlockZ();
        List<int[]> candidates = new ArrayList<>();

        for (int r = BIOME_SCAN_STEP;
             r <= BIOME_SCAN_MAX;
             r += BIOME_SCAN_STEP) {
            for (int dx = -r; dx <= r;
                 dx += BIOME_SCAN_STEP) {
                for (int dz = -r; dz <= r;
                     dz += BIOME_SCAN_STEP) {
                    if (dx * dx + dz * dz > r * r)
                        continue;
                    candidates.add(new int[]{
                            baseX + dx, baseZ + dz});
                }
            }
        }

        // 2. 打乱顺序，避免每次往同一方向搜
        Collections.shuffle(candidates);

        // 3. 主线程分批检测，每tick处理30个坐标
        new BiomeScanTask(plugin, p, category, candidates).runTaskTimer(plugin, 0L, 1L);
    }



    private static String mapBiomeCategory(
            String biome, String worldName) {
        if (biome == null) return "平原";
        String b = biome.toLowerCase();

        // 海洋
        if (b.contains("ocean")) return "海洋";
        // 下界
        if (b.contains("nether")) return "下界";
        // 末地
        if (b.contains("end")) return "末地";
        // 洞穴群系
        if (b.contains("deep_dark")
                || b.contains("lush_caves")
                || b.contains("dripstone_caves")
                || b.contains("stony_peaks"))
            return "地下";
        // 蘑菇岛
        if (b.contains("mushroom")) return "蘑菇岛";
        // 沼泽（先于森林）
        if (b.contains("swamp")) return "沼泽";
        // 沙漠
        if (b.contains("desert")) return "沙漠";
        // 恶地
        if (b.contains("badlands")
                || b.contains("mesa"))
            return "恶地";
        // 雪原
        if (b.contains("snow")
                || b.contains("ice")
                || b.contains("frozen")
                || b.contains("tundra"))
            return "雪原";
        // 河流
        if (b.contains("river")) return "河流";
        // 森林（排除沼泽/恶地/雪原后匹配）
        if (b.contains("forest")
                || b.contains("taiga")
                || b.contains("jungle")
                || b.contains("cherry")
                || b.contains("birch")
                || b.contains("dark_forest")
                || b.contains("mangrove"))
            return "森林";

        return "平原";
    }


    private boolean titleStartsWith6(String t) {
        return t != null
                && t.length() >= 3
                && t.charAt(1) == '6'
                && t.charAt(2) == 'l';
    }



    @EventHandler
    public void onInvDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        String title = e.getView().getTitle();
        plugin.getLogger().info("[GUI] title检查点1: [" + title + "]");

        // 购物车/退款/商店/流水记录页面：禁止一切拖拽
        if ("§a§l购物车".equals(title)
                || "§c§l退款中心".equals(title)
                || "§6§l商店".equals(title)
                || title.startsWith("§e§l流水记录")
                || title.startsWith("§e§l转账记录")
                || title.startsWith("§d§l余额操作")) {
            e.setCancelled(true);
            return;
        }

        // 商店分类页：禁止拖拽
        if (title.startsWith("§6§l") && !title.equals(T_MAIN)
                && !title.equals(T_MY_INFO)
                && !title.equals(T_ADMIN)
                && !title.equals(T_MORE)
                && !title.equals(T_EDITOR)
                && !title.equals(T_BIOME)
                && !title.equals(T_GIFT_STAGES)
                && !title.equals(T_INVITE)
                && !title.equals(T_USER_MGMT)
                && !title.equals(T_TASK_CENTER)) {
            e.setCancelled(true);
            return;
        }

        // 编辑器：图标拖拽替换
        if (!T_EDITOR.equals(title)) return;
        if (e.getRawSlots().contains(12)) {
            List<MenuManager.MenuItem> list =
                    plugin.getMenu().getItems();
            int idx = list.size() - 1;
            if (idx >= 0)
                list.get(idx).icon =
                        e.getOldCursor().clone();
        }
    }


    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player))
            return;
        Player p = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        int raw = event.getRawSlot();

        // ★ 模糊匹配所有订单相关
        if (title.contains("订单")
                || title.contains("购买记录")) {
            event.setCancelled(true);
            if (title.contains("小票配置")) {
                plugin.getOrderManager()
                        .handleReceiptConfigClick(p, raw);
            } else if (title.contains("编辑:")) {
                plugin.getOrderManager()
                        .handleReceiptBuilderClick(p, raw);
            } else if (title.contains("购买记录")) {
                plugin.getOrderManager()
                        .handleMyOrdersClick(
                                p, raw, event.isLeftClick(),
                                event.isShiftClick());
            } else if (title.contains("管理员订单面板")) {
                plugin.getOrderManager()
                        .handleAdminClick(p, raw);
            } else if (title.contains("订单管理")) {
                plugin.getOrderManager()
                        .handleAdminOrdersClick(
                                p, raw, event.isLeftClick(),
                                event.isShiftClick());
            } else if (title.contains("订单中心")) {
                plugin.getOrderManager()
                        .handleCenterClick(p, raw);
            }
            return;
        }

        // ===== 我的信息 — CDK兑换 =====
        if (T_MY_INFO.equals(title) && raw == 16) {
            if (handleCDKClick(p, event)) return;
        }

        // ===== 主菜单 =====
        if (T_MAIN.equals(title)) {
            if (raw == 32) {
                event.setCancelled(true);
                plugin.openMyWallet(p);
                return;
            }
            if (raw == 27) {
                event.setCancelled(true);
                plugin.getShopManager().openShopMain(p);
                return;
            }
            // 自定义菜单项点击由 Main.onInvClick 统一处理
            return;
        }

        // ===== 我的钱包 =====
        if ("§6§l我的钱包".equals(title)) {
            event.setCancelled(true);
            if (raw == 22) { openMain(p); return; }
            if (raw == 12) { plugin.openWalletTransactions(p, 7); return; }
            if (raw == 13) { plugin.openWalletTransactions(p, 14); return; }
            if (raw == 14) { plugin.openWalletTransfers(p); return; }
            return;
        }

        // ===== 流水/转账记录 =====
        if (title.startsWith("§e§l流水记录")
                || title.startsWith("§e§l转账记录")) {
            event.setCancelled(true);
            if (raw == 53) { plugin.openMyWallet(p); return; }
            return;
        }

        // ===== 余额操作 =====
        if ("§d§l余额操作".equals(title)) {
            event.setCancelled(true);
            if (raw == 49) { plugin.openMyWallet(p); return; }
            if (raw < 0 || raw >= 45) return;
            ItemStack item = event.getView()
                    .getTopInventory().getItem(raw);
            if (item == null) return;
            Material mat = item.getType();
            if (mat != Material.PLAYER_HEAD
                    && mat != Material.EMERALD_BLOCK) return;
            ItemMeta im = item.getItemMeta();
            if (im == null
                    || im.getDisplayName() == null) return;
            String targetName = im.getDisplayName()
                    .replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            Player target = plugin.getServer()
                    .getPlayerExact(targetName);
            if (target == null) return;
            String adminTag =
                    plugin.getConfig2().adminTag;
            boolean isAdmin = p.isOp()
                    || (!adminTag.isEmpty()
                    && p.getScoreboardTags()
                    .contains(adminTag));
            if (!isAdmin) return;
            if (event.isLeftClick()
                    && !event.isRightClick()) {
                p.closeInventory();
                p.sendMessage("§e§l[经济操作] §f对 §a"
                        + targetName + " §f进行经济操作");
                p.sendMessage("§7输入 §a+数字 §7给钱，§c-数字 §7扣钱");
                plugin.getCDK().requestInput(p, "econ", targetName);
            } else if (event.isRightClick()
                    && !event.isLeftClick()) {
                p.closeInventory();
                p.sendMessage("§e§l[债券操作] §f对 §a"
                        + targetName + " §f进行债券操作");
                p.sendMessage("§7输入 §a+数字 §7给债券，§c-数字 §7扣债券");
                plugin.getCDK().requestInput(p, "bond", targetName);
            }
            return;
        }

        // ===== 商店主界面 =====
        if ("§6§l商店".equals(title)) {
            event.setCancelled(true);
            plugin.getShopManager().handleMainShopClick(p, raw);
            return;
        }

        // ===== 退款中心 → 重定向订单中心 =====
        if ("§c§l退款中心".equals(title)) {
            event.setCancelled(true);
            plugin.getOrderManager().openOrderCenter(p);
            return;
        }

        // ===== 购物车页面 =====
        if ("§a§l购物车".equals(title)) {
            event.setCancelled(true);
            plugin.getShopManager().handleCartClick(
                    p, raw, event.isLeftClick(),
                    event.isRightClick());
            return;
        }

        // ===== 打包确认 =====
        if ("§6§l选择包装方式".equals(title)) {
            event.setCancelled(true);
            plugin.getShopManager().handlePackingConfirm(p, raw);
            return;
        }

        // ===== 自选颜色 =====
        if ("§b§l选择潜影盒颜色".equals(title)) {
            event.setCancelled(true);
            plugin.getShopManager().handleColorSelect(p, raw);
            return;
        }

        // ===== 我的订单 =====
        if ("§6§l我的订单".equals(title)) {
            event.setCancelled(true);
            plugin.getOrderManager().handleMyOrdersClick(
                    p, raw, event.isLeftClick(),
                    event.isShiftClick());
            return;
        }

        // ===== 管理员订单面板 =====
        if ("§c§l管理员订单面板".equals(title)) {
            event.setCancelled(true);
            plugin.getOrderManager().handleAdminClick(p, raw);
            return;
        }

        // ===== 商店分类页 =====
        ShopManager sm = plugin.getShopManager();
        boolean handled = sm.handleCategoryClick(
                p, title, raw,
                event.isLeftClick(),
                event.isShiftClick());
        if (handled) {
            event.setCancelled(true);
            return;
        }
        // ===== 用户管理面板 =====
        if (T_USER_MGMT.equals(title)) {
            event.setCancelled(true);

            // 点击玩家头像 → 打开详情
            if (raw >= 0 && raw < 45) {
                ItemStack item = event.getView()
                        .getTopInventory().getItem(raw);
                if (item != null
                        && item.getType()
                        == Material.PLAYER_HEAD) {
                    ItemMeta im = item.getItemMeta();
                    if (im != null
                            && im.getDisplayName() != null) {
                        String targetName = im
                                .getDisplayName()
                                .replaceAll(
                                        "§[0-9a-fk-orA-FK-OR]",
                                        "");
                        openUserDetail(p, targetName);
                        return;
                    }
                }
            }

            // 上一页
            if (raw == 45) {
                int cur = getUserMgmtPage(p);
                if (cur > 1) {
                    openUserManagement(p, cur - 1);
                }
                return;
            }

            // 下一页
            if (raw == 51) {
                int cur = getUserMgmtPage(p);
                int total = Math.max(1,
                        (getUserMgmtTotalUsers()
                                + UMGMT_PAGE_SIZE - 1)
                                / UMGMT_PAGE_SIZE);
                if (cur < total) {
                    openUserManagement(p, cur + 1);
                }
                return;
            }

            // 搜索
            if (raw == 46) {
                p.closeInventory();
                p.sendMessage("§e请输入要搜索的玩家名:");
                plugin.getCDK().requestInput(
                        p, "search_user", "");
                return;
            }

            // 返回
            if (raw == 53) {
                openAdmin(p);
                return;
            }

            return;
        }

        // ===== 用户详情面板（新增功能） =====
        if (title.startsWith("§e§l管理: ")) {
            event.setCancelled(true);
            String targetName = title
                    .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                    .replace("管理: ", "").trim();

            // 返回
            if (raw == 22) {
                openUserManagement(p,
                        getUserMgmtPage(p));
                return;
            }

            // 积分操作
            if (raw == 10) {
                if (event.isLeftClick()
                        && !event.isRightClick()) {
                    p.closeInventory();
                    p.sendMessage("§e§l[积分操作] §f对 §a"
                            + targetName + " §f增加积分");
                    p.sendMessage(
                            "§7输入 §a+数字 §7增加积分");
                    plugin.getCDK().requestInput(
                            p, "points", targetName);
                } else if (event.isRightClick()
                        && !event.isLeftClick()) {
                    p.closeInventory();
                    p.sendMessage("§e§l[积分操作] §f对 §a"
                            + targetName + " §f减少积分");
                    p.sendMessage(
                            "§7输入 §c-数字 §7减少积分");
                    plugin.getCDK().requestInput(
                            p, "points", targetName);
                }
                return;
            }

            // 邮箱
            if (raw == 11) {
                p.closeInventory();
                p.sendMessage("§e设置 §a" + targetName
                        + " §e的邮箱:");
                plugin.getCDK().requestInput(
                        p, "set_email", targetName);
                return;
            }

            // 设置密码
            if (raw == 12) {
                p.closeInventory();
                p.sendMessage("§e设置 §a" + targetName
                        + " §e的新密码:");
                plugin.getCDK().requestInput(
                        p, "set_pass", targetName);
                return;
            }

            // 雪球菜单
            if (raw == 14) {
                Player target = Bukkit.getPlayerExact(
                        targetName);
                if (target != null) {
                    plugin.giveMenuSnowball(target);
                    p.sendMessage("§a已向 §e" + targetName
                            + " §a发放菜单雪球");
                } else {
                    p.sendMessage("§c玩家不在线");
                }
                return;
            }

            // 删除用户
            if (raw == 16) {
                openDeleteConfirm(p, targetName);
                return;
            }

            // 债券余额（查询）
            if (raw == 20) {
                int bondBal = plugin.getBonds()
                        .getBonds(targetName);
                p.sendMessage("§e§l[债券查询] §f"
                        + targetName + " §7当前债券: §e"
                        + bondBal + " §6枚");
                return;
            }

            // 债券操作
            if (raw == 21) {
                if (event.isLeftClick()
                        && !event.isRightClick()) {
                    p.closeInventory();
                    p.sendMessage("§e§l[债券操作] §f对 §a"
                            + targetName + " §f给予债券");
                    p.sendMessage(
                            "§7输入 §a+数字 §7给予债券");
                    plugin.getCDK().requestInput(
                            p, "bond", targetName);
                } else if (event.isRightClick()
                        && !event.isLeftClick()) {
                    p.closeInventory();
                    p.sendMessage("§e§l[债券操作] §f对 §a"
                            + targetName + " §f扣除债券");
                    p.sendMessage(
                            "§7输入 §c-数字 §7扣除债券");
                    plugin.getCDK().requestInput(
                            p, "bond", targetName);
                }
                return;
            }

            // 全局白名单
            if (raw == 23) {
                AreaProtection ap =
                        plugin.getAreaProtection();
                if (ap == null) {
                    p.sendMessage("§c区域防护未加载");
                    return;
                }
                boolean isW = ap
                        .isPlayerGlobalWhitelisted(
                                targetName);
                if (event.isLeftClick() && !isW) {
                    ap.addPlayerToGlobalWhitelist(
                            targetName);
                    p.sendMessage("§a已将 §e" + targetName
                            + " §a加入全局白名单");
                    openUserDetail(p, targetName);
                } else if (event.isRightClick() && isW) {
                    ap.removePlayerFromGlobalWhitelist(
                            targetName);
                    p.sendMessage("§c已将 §e" + targetName
                            + " §c移出全局白名单");
                    openUserDetail(p, targetName);
                }
                return;
            }

            // 区域白名单管理
            if (raw == 24) {
                openAreaWhitelistPanel(p, targetName);
                return;
            }

            return;
        }

        // ===== 区域白名单面板 =====
        if (title.startsWith("§b§l区域白名单: ")) {
            event.setCancelled(true);
            String targetName = title
                    .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                    .replace("区域白名单: ", "").trim();

            AreaProtection ap =
                    plugin.getAreaProtection();
            if (ap == null) {
                p.sendMessage("§c区域防护未加载");
                return;
            }

            // 返回
            if (raw == 26) {
                openUserDetail(p, targetName);
                return;
            }

            // 全局白名单（slot 4）
            if (raw == 4) {
                boolean isG = ap
                        .isPlayerGlobalWhitelisted(
                                targetName);
                if (event.isLeftClick() && !isG) {
                    ap.addPlayerToGlobalWhitelist(
                            targetName);
                    p.sendMessage("§a已加入全局白名单");
                    openAreaWhitelistPanel(p, targetName);
                } else if (event.isRightClick() && isG) {
                    ap.removePlayerFromGlobalWhitelist(
                            targetName);
                    p.sendMessage("§c已移出全局白名单");
                    openAreaWhitelistPanel(p, targetName);
                }
                return;
            }

            // 各区域白名单（slot 9-17）
            if (raw >= 9 && raw <= 17) {
                ItemStack item = event.getView()
                        .getTopInventory().getItem(raw);
                if (item == null) return;
                ItemMeta im = item.getItemMeta();
                if (im == null) return;
                String areaName = im.getDisplayName()
                        .replaceAll(
                                "§[0-9a-fk-orA-FK-OR]", "");

                if (event.isLeftClick()) {
                    if (ap.isPlayerAreaWhitelisted(
                            areaName, targetName)) {
                        p.sendMessage("§c已在该区域白名单中");
                        return;
                    }
                    ap.addPlayerToAreaWhitelist(
                            areaName, targetName);
                    p.sendMessage("§a已加入「"
                            + areaName + "」白名单");
                    openAreaWhitelistPanel(p, targetName);
                } else if (event.isRightClick()) {
                    if (!ap.isPlayerAreaWhitelisted(
                            areaName, targetName)) {
                        p.sendMessage("§c不在该区域白名单中");
                        return;
                    }
                    ap.removePlayerFromAreaWhitelist(
                            areaName, targetName);
                    p.sendMessage("§c已移出「"
                            + areaName + "」白名单");
                    openAreaWhitelistPanel(p, targetName);
                }
                return;
            }


            if (raw >= 9 && raw <= 17) {
                ItemStack item = event.getView()
                        .getTopInventory().getItem(raw);
                if (item == null) return;
                ItemMeta im = item.getItemMeta();
                if (im == null) return;
                String areaName = im.getDisplayName()
                        .replaceAll(
                                "§[0-9a-fk-orA-FK-OR]", "");

                if (event.isLeftClick()) {
                    if (ap.isPlayerAreaWhitelisted(
                            areaName, targetName)) {
                        p.sendMessage("§c已在该区域白名单中");
                        return;
                    }
                    ap.addPlayerToAreaWhitelist(
                            areaName, targetName);
                    p.sendMessage("§a已加入「"
                            + areaName + "」白名单");
                    openAreaWhitelistPanel(p, targetName);
                } else if (event.isRightClick()) {
                    if (!ap.isPlayerAreaWhitelisted(
                            areaName, targetName)) {
                        p.sendMessage("§c不在该区域白名单中");
                        return;
                    }
                    ap.removePlayerFromAreaWhitelist(
                            areaName, targetName);
                    p.sendMessage("§c已移出「"
                            + areaName + "」白名单");
                    openAreaWhitelistPanel(p, targetName);
                }
                return;
            }
        }

    }


    public void openEditor(
            Player p, int idx) {
        List<MenuManager.MenuItem> list =
                plugin.getMenu().getItems();
        MenuManager.MenuItem item;
        if (idx < 0 || idx >= list.size()) {
            item = new MenuManager.MenuItem();
            list.add(item);
            idx = list.size() - 1;
        } else {
            item = list.get(idx);
        }
        Inventory g = Bukkit.createInventory(
                null, 27, T_EDITOR);
        fillBg(g);
        g.setItem(10, mkItem(Material.NAME_TAG,
                "§e§l标题",
                "§7当前: §f" + item.title));
        g.setItem(11, mkItem(
                Material.COMMAND_BLOCK,
                "§e§l指令",
                "§7当前: §f" + item.command));
        ItemStack ic = item.icon.clone();
        ItemMeta im = ic.getItemMeta();
        im.setDisplayName("§e§l菜单图标");
        im.setLore(Arrays.asList(
                "§7拖拽替换"));
        ic.setItemMeta(im);
        g.setItem(12, ic);
        g.setItem(20, mkItem(
                "OP".equals(item.permType)
                        ? Material.RED_BANNER
                        : Material.GREEN_BANNER,
                "§e§l权限: " + item.permType,
                "§a左键切换"));
        g.setItem(22, mkItem(
                Material.LIME_STAINED_GLASS_PANE,
                "§a§l保存"));
        g.setItem(24, mkItem(
                Material.RED_STAINED_GLASS_PANE,
                "§c§l取消"));
        p.openInventory(g);
    }


    public void openMyInfo(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, T_MY_INFO);

        fillBg(g);
        Map<String, Object> user = plugin.getDb()
                .getUser(p.getName());
        boolean checkedIn = plugin.getCheckIn()
                .isCheckedInToday(p.getName());
        Material checkMat = checkedIn
                ? Material.EMERALD : Material.COAL;
        String checkName = checkedIn
                ? "§a已签到" : "§7点击签到";
        int streak = ((Number) user.getOrDefault(
                "checkin_streak", 0)).intValue();
        int points = ((Number) user.getOrDefault(
                "points", 0)).intValue();
        int totalDays = ((Number) user.getOrDefault(
                "total_checkin_days", 0)).intValue();
        String email = (String) user.getOrDefault(
                "email", "");
        g.setItem(4, mkItem(Material.NAME_TAG,
                "§e" + p.getName()));
        g.setItem(10, mkItem(checkMat, checkName,
                "§7连续签到: " + streak + "天",
                "§7累计签到: " + totalDays + "天",
                "§7当前积分: " + points));
        g.setItem(11, mkItem(Material.PAPER,
                "§e修改密码", "§7双击修改密码"));
        g.setItem(12, mkItem(Material.CLOCK,
                "§e补签", "§7消耗10积分补签3天内"));
        g.setItem(13, mkItem(Material.ENDER_PEARL,
                "§e邀请码",
                "§7你的邀请码: "
                        + user.getOrDefault(
                        "invite_code", "无"),
                "§7点击生成/查看"));
        g.setItem(14, mkItem(Material.BOOK,
                "§e邮箱: "
                        + (email.isEmpty()
                        ? "未绑定" : email),
                "§7点击绑定邮箱"));
        g.setItem(15, mkItem(Material.EMERALD_BLOCK,
                "§a§l积分商城",
                "§7当前: " + points + "积分"));
        // CDK兑换入口
        g.setItem(16, mkItem(Material.BOOK,
                "§6§lCDK兑换",
                "§7输入兑换码获取债券",
                "",
                "§e点击输入兑换码"));

// 显示当前债券余额
        int bondBal = plugin.getBonds().getBonds(p.getName());
        g.setItem(20, mkItem(Material.EMERALD,
                "§e§l我的债券: " + bondBal + "枚",
                "§7债券可通过签到、CDK、转账获得"));



        // 图标说明
        g.setItem(30, mkItem(Material.PAPER,
                "§e§l自定义菜单图标",
                "§7将物品放入右侧槽位",
                "§7即可保存为你的专属图标",
                "§7下次加入自动发放"));
        // ===== 菜单图标区 =====
        g.setItem(30, mkItem(Material.PAPER,
                "§e§l自定义菜单图标",
                "§7Shift+点击背包物品放入右侧槽位",
                "§7即可保存为你的专属图标",
                "§7下次加入自动发放"));

        String iconB64 = plugin.getDb()
                .getMenuIcon(p.getName());
        if (iconB64 != null && !iconB64.isEmpty()) {
            ItemStack iconItem =
                    deserializeItem(iconB64);
            if (iconItem != null) {
                g.setItem(31, iconItem);
            } else {
                putIconPlaceholder(g);
            }
        } else {
            putIconPlaceholder(g);
        }

        g.setItem(32, mkItem(Material.WOODEN_SHOVEL,
                "§c清除图标",
                "§7点击可清除已保存的图标"));
        String ugLabel = "§7(玩家)";
        if (plugin.getUserGroup() != null) {
            UserGroupManager.UserGroupConfig ugCfg =
                    plugin.getUserGroup().getHighestGroup(p.getName());
            if (ugCfg != null) {
                ugLabel = ugCfg.displayColor + ugCfg.displayName;
            }
        }
        g.setItem(22, mkItem(Material.PAPER, "§e§l我的用户组", "§7当前: " + ugLabel));

        g.setItem(26, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    /**
     * 图标槽的空占位物品
     */
    private static final String ICON_SLOT_DEFAULT =
            "§7默认菜单（雪球）";

    private void putIconPlaceholder(Inventory g) {
        ItemStack empty = new ItemStack(
                Material.SNOWBALL);
        ItemMeta m = empty.getItemMeta();
        m.setDisplayName(ICON_SLOT_DEFAULT);
        m.setLore(Arrays.asList(
                "§7Shift+点击背包物品放入",
                "§7保存后下次登录自动发放"));
        empty.setItemMeta(m);
        g.setItem(31, empty);
    }


    /**
     * 处理"我的信息"GUI中菜单图标槽的点击
     * 在已有的 onInventoryClick 方法中，
     * 匹配到 T_MY_INFO 标题后调用此方法
     *
     * 返回 true 表示已处理（事件应取消）
     * 返回 false 表示未处理（继续原有逻辑）
     */
    public boolean handleMyInfoIconClick(
            Player p,
            InventoryClickEvent event) {

        event.setCancelled(true);
        Inventory topInv = event.getView()
                .getTopInventory();
        int raw = event.getRawSlot();

        if (raw == 32) {
            doClearIcon(p, topInv);
            return true;
        }
        if (raw != 31) return true;

        ItemStack cursor = event.getCursor();
        boolean hasCursor = cursor != null
                && cursor.getType() != Material.AIR;

        // Shift-click：从背包放入
        if (event.isShiftClick()
                && event.getClickedInventory()
                == p.getInventory()) {
            int srcSlot = event.getSlot();
            ItemStack src =
                    p.getInventory().getItem(srcSlot);
            if (src != null
                    && src.getType() != Material.AIR
                    && !isCustomMenuTrigger(src)) {
                doPlaceIcon(p, topInv, src, srcSlot);
            }
            return true;
        }

        // 普通点击 + 光标有物品 → 放入
        if (hasCursor
                && !isCustomMenuTrigger(cursor)) {
            doPlaceIcon(p, topInv, cursor, -1);
            return true;
        }

        // 普通点击 + 光标空 + 非占位 → 清除
        if (!hasCursor) {
            ItemStack cur = topInv.getItem(31);
            if (!isPlaceholder(cur)) {
                doClearIcon(p, topInv);
            }
        }
        return true;
    }
    /**
     * 处理"我的信息"GUI中CDK兑换按钮的点击
     * 返回 true 表示已处理
     */
    public boolean handleCDKClick(Player p, InventoryClickEvent event) {
        String title = event.getView().getTitle();
        int raw = event.getRawSlot();

        if (!T_MY_INFO.equals(title)) return false;

        if (raw == 16) {
            event.setCancelled(true);
            p.closeInventory();

            org.bukkit.plugin.Plugin sdf1 =
                    org.bukkit.Bukkit.getPluginManager().getPlugin("Sdf1");

            // 情况1：插件未安装
            if (sdf1 == null) {
                p.sendMessage("§c§l[CDK] §f口令兑换系统尚未安装，请联系管理员");
                return true;
            }

            // 情况2：插件已安装但未启用
            if (!sdf1.isEnabled()) {
                p.sendMessage("§c§l[CDK] §f口令兑换系统未启用，请联系管理员");
                return true;
            }

            // 情况3：正常接入 — 反射调用SDF1的startListening
            try {
                Class<?> mainClass = sdf1.getClass();
                java.lang.reflect.Method method =
                        mainClass.getDeclaredMethod("startListening", Player.class);
                method.setAccessible(true);
                method.invoke(sdf1, p);
                // startListening内部已发送 "已开启口令监听 (15秒)" 提示
            } catch (NoSuchMethodException e) {
                p.sendMessage("§c§l[CDK] §f口令系统版本过低，请联系管理员更新");
            } catch (Exception e) {
                p.sendMessage("§c§l[CDK] §f兑换系统异常，请稍后再试");
            }

            return true;
        }
        return false;
    }


    // ===== 放入自定义图标 =====
    private void doPlaceIcon(Player p,
                             Inventory topInv,
                             ItemStack item, int removeSlot) {

        // ★ 拦截宝箱自定义物品（事不过三原则）
        if (handleTreasureItemBlock(p, item)) {
            return;
        }

        int amount = item.getAmount();
        removeSnowballFromInventory(p); //没收雪球菜单
        // 1. 从来源移除
        if (removeSlot >= 0) {
            // Shift-click：从背包格子移除
            p.getInventory()
                    .setItem(removeSlot, null);
        } else {
            // 普通点击：从光标移除
            p.getOpenInventory().setCursor(null);
        }

        // 2. 退回多余（amount-1）到背包
        if (amount > 1) {
            ItemStack excess = item.clone();
            excess.setAmount(amount - 1);
            java.util.HashMap<Integer,
                    ItemStack> leftover =
                    p.getInventory()
                            .addItem(excess);
            for (ItemStack drop :
                    leftover.values()) {
                p.getWorld().dropItemNaturally(
                        p.getLocation(), drop);
            }
        }

        // 3. 存原件（数量=1）到DB
        ItemStack original = item.clone();
        original.setAmount(1);
        String b64 = serializeItem(original);
        if (b64 == null) {
            p.sendMessage("§c保存失败");
            return;
        }
        plugin.getDb().saveMenuIcon(
                p.getName(), b64,
                original.getType().name());

        // 4. 创建带标记的菜单物品
        ItemStack menu = original.clone();
        ItemMeta mm = menu.getItemMeta();
        if (mm != null) {
            String oldName =
                    mm.getDisplayName();
            if (oldName == null
                    || oldName.isEmpty()
                    || oldName.equals(
                    menu.getType().name())) {
                oldName = "自定义物品";
            }
            mm.setDisplayName(
                    "\u00a7e\u00a7l[菜单] \u00a7f"
                            + oldName);
            List<String> lore = mm.hasLore()
                    ? new ArrayList<>(mm.getLore())
                    : new ArrayList<>();
            lore.add(0,
                    "\u00a77右键打开主菜单");
            mm.setLore(lore);
            mm.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(
                            plugin, "menu_trigger"),
                    org.bukkit.persistence
                            .PersistentDataType
                            .STRING,
                    "true");
            menu.setItemMeta(mm);
        }

        // 5. 发放菜单物品
        int slot = p.getInventory()
                .firstEmpty();
        if (slot >= 0) {
            p.getInventory().setItem(slot, menu);
        } else {
            p.getWorld().dropItemNaturally(
                    p.getLocation(), menu);
        }

        // 6. 更新GUI
        topInv.setItem(31, original);
        p.sendMessage("§a已保存菜单图标: "
                + original.getType().name());
        p.updateInventory();
    }



    // ===== 清除自定义图标 =====
    private void doClearIcon(Player p,
                             Inventory topInv) {

        String dbIcon = plugin.getDb()
                .getMenuIcon(p.getName());

        // 1. 没收自定义菜单物品
        removeCustomMenuFromInventory(p);

        // 2. 删DB
        plugin.getDb().deleteMenuIcon(
                p.getName());

        // 3. 强制清空slot 31
        topInv.setItem(31, null);

        // 4. 发雪球
        plugin.giveMenuSnowball(p);

        // 5. 还原件
        if (dbIcon != null && !dbIcon.isEmpty()) {
            ItemStack original =
                    deserializeItem(dbIcon);
            if (original != null) {
                p.getInventory()
                        .addItem(original);
            }
        }

        // 6. 重新设置占位符
        putIconPlaceholder(topInv);
        p.sendMessage("§c已清除菜单图标");
        p.updateInventory();
    }


    /** ★ 检测宝箱自定义物品（lore 含 §0§k 或 CUSTOM 标记） */
    boolean isTreasureCustomItem(ItemStack item) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        List<String> lore = meta.getLore();
        if (lore == null) return false;
        for (String line : lore) {
            if (line.contains("\u00a70\u00a7k")
                    || line.contains("CUSTOM")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 宝箱自定义物品拦截（事不过三原则）
     * @param player 玩家
     * @param item 要存入的物品
     * @return true表示已拦截（调用方应return），false表示未拦截（继续原逻辑）
     */
    public boolean handleTreasureItemBlock(Player player, ItemStack item) {
        if (!isTreasureCustomItem(item)) {
            return false; // 不是宝箱物品，不拦截
        }

        String key = player.getUniqueId() + ":" + item.getType().name();
        int count = treasureMenuBlockCount.getOrDefault(key, 0) + 1;
        treasureMenuBlockCount.put(key, count);

        if (count < 3) {
            // 第1次或第2次：警告
            player.sendMessage("§c§l[宝箱物品] §e该物品是宝箱自定义物品，无法存入菜单！");
            player.sendMessage("§c§l[宝箱物品] §e第" + count + "/3次警告，第三次将没收物品！");
            return true;
        } else {
            // 第3次：没收物品
            // 1. 从玩家背包移除该物品
            boolean removed = false;
            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack invItem = player.getInventory().getItem(i);
                if (invItem != null && invItem.isSimilar(item)) {
                    player.getInventory().setItem(i, null);
                    removed = true;
                    break;
                }
            }
            // 2. 如果背包没找到，可能在光标上（普通点击/拖拽场景）
            if (!removed) {
                org.bukkit.inventory.InventoryView view = player.getOpenInventory();
                ItemStack cursor = view.getCursor();
                if (cursor != null && cursor.isSimilar(item)) {
                    view.setCursor(null);
                    removed = true;
                }
            }
            // 3. 清零计数器
            treasureMenuBlockCount.remove(key);
            // 4. 发送没收提示
            player.sendMessage("§c§l[宝箱物品] §e你多次尝试存入宝箱物品，物品已被没收！");
            player.sendMessage("§c§l[宝箱物品] §c事不过三，这是第三次警告！");
            // 5. 记录日志
            plugin.getLogger().warning("[宝箱物品] 玩家 " + player.getName() + " 第三次尝试存入宝箱物品，物品已没收: " + item.getType().name());
            return true;
        }
    }


// ===== 辅助方法 =====

    // 只匹配带[菜单]的非雪球物品
    private boolean isCustomMenuTrigger(
            ItemStack item) {
        if (item == null) return false;
        if (item.getType() == Material.SNOWBALL)
            return false;
        ItemMeta im = item.getItemMeta();
        if (im == null) return false;
        if (im.hasDisplayName()
                && im.getDisplayName()
                .contains("[菜单]")) {
            return true;
        }
        return false;
    }

    // 只移除自定义菜单物品（不动雪球）
    private void removeCustomMenuFromInventory(
            Player p) {
        for (int i = 0;
             i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory()
                    .getItem(i);
            if (isCustomMenuTrigger(it)) {
                p.getInventory().setItem(i, null);
                return;
            }
        }
    }

    // 只移除雪球菜单
    private void removeSnowballFromInventory(
            Player p) {
        for (int i = 0;
             i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory()
                    .getItem(i);
            if (it != null
                    && it.getType()
                    == Material.SNOWBALL
                    && it.hasItemMeta()) {
                ItemMeta im = it.getItemMeta();
                if (im.hasLore()
                        && im.getLore() != null) {
                    for (String ln : im.getLore()) {
                        if (ln.contains(
                                "\u00a78")) {
                            p.getInventory()
                                    .setItem(i,
                                            null);
                            return;
                        }
                    }
                }
            }
        }
    }

    // 已有方法，不需要改
    private boolean isPlaceholder(
            ItemStack item) {
        if (item == null) return true;
        if (item.getType() == Material.AIR)
            return true;
        // fillBg 可能覆盖了slot 31
        if (item.getType()
                == Material.GRAY_STAINED_GLASS_PANE)
            return true;
        ItemMeta im = item.getItemMeta();
        if (im == null) return true;
        if ("\u00a77默认菜单（雪球）"
                .equals(im.getDisplayName()))
            return true;
        if (im.hasLore() && im.getLore() != null
                && im.getLore().size() >= 2
                && im.getLore().get(0)
                .contains("Shift+点击"))
            return true;
        return false;
    }


// ===== 辅助方法 =====

    private boolean isMenuTriggerItem(
            ItemStack item) {
        if (item == null) return false;
        ItemMeta im = item.getItemMeta();
        if (im == null) return false;
        // 自定义图标：显示名含[菜单]
        if (im.hasDisplayName()
                && im.getDisplayName()
                .contains("[菜单]")) {
            return true;
        }
        // 默认雪球
        if (item.getType() == Material.SNOWBALL
                && im.hasLore()
                && im.getLore() != null) {
            for (String ln : im.getLore()) {
                if (ln.contains("\u00a77右键打开主菜单"))
                    return true;
            }
        }
        return false;
    }

    /*private boolean isPlaceholder(ItemStack item) {
        if (item == null) return true;
        if (item.getType() == Material.AIR)
            return true;
        ItemMeta im = item.getItemMeta();
        if (im == null) return true;
        if ("\u00a77默认菜单（雪球）"
                .equals(im.getDisplayName())) {
            return true;
        }
        if (im.hasLore() && im.getLore() != null
                && im.getLore().size() >= 2
                && im.getLore().get(0)
                .contains("Shift+点击")) {
            return true;
        }
        return false;
    }*/

    private void removeMenuTriggerFromInventory(
            Player p) {
        for (int i = 0;
             i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory()
                    .getItem(i);
            if (isMenuTriggerItem(it)) {
                p.getInventory().setItem(i, null);
                return;
            }
        }
    }

    private void removeCustomTriggerFromInventory(
            Player p) {
        for (int i = 0;
             i < p.getInventory().getSize(); i++) {
            ItemStack it = p.getInventory()
                    .getItem(i);
            if (it == null) continue;
            ItemMeta im = it.getItemMeta();
            if (im == null) continue;
            if (im.hasDisplayName()
                    && im.getDisplayName()
                    .contains("[菜单]")) {
                p.getInventory().setItem(i, null);
                return;
            }
        }
    }


    // 在 openGiftStages 方法之前添加：
    public void openGiftStages(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_GIFT_STAGES);
        fillBg(g);

        QuestTracker qt =
                plugin.getQuestTracker();
        if (qt == null) {
            g.setItem(22, mkItem(
                    Material.BARRIER,
                    "§7任务系统未加载"));
            p.openInventory(g);
            return;
        }

        List<QuestTracker.QuestFile> quests =
                qt.getQuests("新人任务");
        String playerName = p.getName();

        int max = quests.size();
        for (int i = 0;
             i < max && i < 9; i++) {
            QuestTracker.QuestFile qf =
                    quests.get(i);
            boolean completed =
                    qt.isStageCompleted(
                            playerName, qf);
            boolean claimed =
                    qt.hasClaimed(
                            playerName, qf);

            Material mat;
            String status;
            if (completed && claimed) {
                mat = Material.LIME_WOOL;
                status = "§a已领取";
            } else if (completed) {
                mat = Material.YELLOW_WOOL;
                status = "§e可领取";
            } else {
                mat = Material.RED_WOOL;
                status = "§c未达标";
            }

            List<String> lore =
                    new ArrayList<>();
            lore.add("§7" + status);
            for (String cond :
                    qf.conditions) {
                String progress =
                        qt.getProgress(
                                playerName, cond);
                lore.add("§7" + progress);
            }

            g.setItem(10 + i, mkItem(mat,
                    "§e" + qf.displayName,
                    lore.toArray(
                            new String[0])));
        }
        g.setItem(26, mkItem(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    /**
     * 获取任务列表（带真实完成状态）
     * 使用 QuestTracker 解析 .md 文件
     */
    public List<Map<String, Object>> getQuestList(
            String category) {
        QuestTracker qt = plugin.getQuestTracker();
        if (qt == null) return new ArrayList<>();
        List<QuestTracker.QuestFile> quests =
                qt.getQuests(category);
        List<Map<String, Object>> list =
                new ArrayList<>();
        for (QuestTracker.QuestFile qf : quests) {
            Map<String, Object> map =
                    new LinkedHashMap<>();
            map.put("name", qf.displayName);
            map.put("conditions", qf.conditions);
            map.put("rewards", qf.rewards);
            map.put("questFile", qf);
            list.add(map);
        }
        return list;
    }

    /**
     * 打开账号删除选择面板
     * 展示该玩家IP下所有账号，供选择删除
     */
    public void openAccountDelete(Player p) {
        String ip = plugin.getPlayerIP(p);
        if (ip == null) {
            p.sendMessage("§c无法获取你的IP地址");
            return;
        }

        List<Map<String, Object>> accounts =
                plugin.getDb().getAccountDetailsByIP(ip);

        // 54格大箱子
        org.bukkit.inventory.Inventory g =
                org.bukkit.Bukkit.createInventory(
                        null, 54,
                        "§c§l选择要删除的账号");

        // 填充玻璃面板
        org.bukkit.inventory.ItemStack glass =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material
                                .RED_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta gm =
                glass.getItemMeta();
        if (gm != null) {
            gm.setDisplayName(" ");
            glass.setItemMeta(gm);
        }
        for (int i = 0; i < 54; i++) {
            g.setItem(i, glass);
        }

        // 展示账号列表（从0号位开始放）
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm");
        int slot = 0;
        for (Map<String, Object> acc : accounts) {
            if (slot >= 45) break;
            String accName = (String) acc
                    .get("player_name");
            long regTime = ((Number) acc
                    .get("register_time")).longValue();
            String dateStr = regTime > 0
                    ? sdf.format(
                    new java.util.Date(regTime))
                    : "未知";

            // 当前登录的账号用不同颜色标记
            boolean isCurrent =
                    accName.equalsIgnoreCase(
                            p.getName());

            org.bukkit.inventory.ItemStack item =
                    new org.bukkit.inventory.ItemStack(
                            isCurrent
                                    ? org.bukkit.Material
                                      .RED_WOOL
                                    : org.bukkit.Material
                                      .ORANGE_WOOL);
            org.bukkit.inventory.meta.ItemMeta im =
                    item.getItemMeta();
            if (im != null) {
                im.setDisplayName(
                        (isCurrent ? "§c" : "§e")
                                + accName);
                im.setLore(java.util.Arrays.asList(
                        "§7注册时间: " + dateStr,
                        "§7IP: " + ip,
                        isCurrent
                                ? "§c§l（当前账号）"
                                : "§a点击选择删除此账号"));
                item.setItemMeta(im);
            }
            g.setItem(slot, item);
            slot++;
        }

        // 49号位：返回
        org.bukkit.inventory.ItemStack back =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material.ARROW);
        org.bukkit.inventory.meta.ItemMeta backMeta =
                back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§7返回");
            back.setItemMeta(backMeta);
        }
        g.setItem(49, back);

        p.openInventory(g);
    }

    /**
     * 打开删除确认面板
     */
    public void openDeleteConfirm(Player p,
                                  String targetName) {
        org.bukkit.inventory.Inventory g =
                org.bukkit.Bukkit.createInventory(
                        null, 27,
                        "§c§l确认删除: " + targetName);

        org.bukkit.inventory.ItemStack glass =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material
                                .GRAY_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta gm =
                glass.getItemMeta();
        if (gm != null) {
            gm.setDisplayName(" ");
            glass.setItemMeta(gm);
        }
        for (int i = 0; i < 27; i++) {
            g.setItem(i, glass);
        }

        // 11号位：确认删除
        org.bukkit.inventory.ItemStack confirm =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material.LIME_WOOL);
        org.bukkit.inventory.meta.ItemMeta cm =
                confirm.getItemMeta();
        if (cm != null) {
            cm.setDisplayName("§a§l确认删除");
            cm.setLore(java.util.Arrays.asList(
                    "§7删除账号: §c" + targetName,
                    "§c此操作不可撤销！"));
            confirm.setItemMeta(cm);
        }
        g.setItem(11, confirm);

        // 15号位：取消
        org.bukkit.inventory.ItemStack cancel =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material.RED_WOOL);
        org.bukkit.inventory.meta.ItemMeta cml =
                cancel.getItemMeta();
        if (cml != null) {
            cml.setDisplayName("§c§l取消");
            cancel.setItemMeta(cml);
        }
        g.setItem(15, cancel);
        // ===== 图标槽31 =====
        g.setItem(30, mkItem(Material.PAPER,
                "§e§l自定义菜单图标",
                "§7Shift+点击背包物品放入右侧槽位",
                "§7即可保存为你的专属图标",
                "§7下次加入自动发放"));

        putIconPlaceholder(g);

        String iconB64 = plugin.getDb()
                .getMenuIcon(p.getName());
        if (iconB64 != null && !iconB64.isEmpty()) {
            ItemStack iconItem =
                    deserializeItem(iconB64);
            if (iconItem != null) {
                g.setItem(31, iconItem);
            }
        }

        g.setItem(32, mkItem(Material.BARRIER,
                "§c清除图标",
                "§7点击可清除已保存的图标"));
    }
        public void openShopAdmin(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§c§l商城管理");
        ItemStack glass = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glm = glass.getItemMeta();
        if (glm != null) {
            glm.setDisplayName(" ");
            glass.setItemMeta(glm);
        }
        for (int i = 0; i < 54; i++)
            g.setItem(i, glass);
        g.setItem(49, mkItem(Material.ARROW,
                "§7返回商城"));
        g.setItem(50, mkItem(Material.EMERALD,
                "§a添加商品",
                "§7格式: /sdf1_login shopadd <名称> <价格> <命令>"));
        g.setItem(51, mkItem(Material.BARRIER,
                "§c删除商品",
                "§7格式: /sdf1_login shopdel <序号>"));
        p.openInventory(g);
    }


    public void openAdmin(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_ADMIN);
        fillBg(g);
        g.setItem(4, mkItem(Material.NAME_TAG,
                "§e§l管理员面板"));
        g.setItem(10, mkItem(Material.REDSTONE,
                "§6SMTP配置",
                "§7配置邮件服务器"));
        g.setItem(11, mkItem(Material.ARMOR_STAND,
                "§a用户管理",
                "§7管理玩家数据"));
        g.setItem(12, mkItem(Material.PLAYER_HEAD,
                "§a§l我的信息"));
        g.setItem(14, mkItem(Material.EMERALD,
                "§d§l积分商城"));
        boolean afkOn = plugin.getConfig2().afkEnabled;
        int afkMin = plugin.getConfig2().afkTimeout / 60;
        g.setItem(16, mkItem(
                afkOn ? Material.REDSTONE_TORCH
                        : Material.TORCH,
                afkOn ? "§a§l挂机踢出: 开启"
                        : "§c§l挂机踢出: 关闭",
                "§7左键: 切换开关",
                "§7右键: 设置时长",
                "§7当前: " + afkMin + "分钟"));
        String ugLabel = "§7(玩家)";
        if (plugin.getUserGroup() != null) {
            UserGroupManager.UserGroupConfig ugCfg =
                    plugin.getUserGroup().getHighestGroup(p.getName());
            if (ugCfg != null) {
                ugLabel = ugCfg.displayColor + ugCfg.displayName;
            }
        }
        g.setItem(22, mkItem(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                "§e§l我的用户组",
                "§7当前: " + ugLabel));

        g.setItem(24, mkItem(Material.ARROW, "§7返回"));
        // openAdmin 方法中加：
        p.openInventory(g);
        }



    public void openInvite(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_INVITE);
        fillBg(g);
        String code = (String) plugin.getDb()
                .getField(p.getName(), "invite_code");
        if (code == null || code.isEmpty()) {
            code = plugin.getInvite().generateCode(p);
        }
        int monthly = ((Number) plugin.getDb()
                .getField(p.getName(),
                        "monthly_invite_count"))
                .intValue();
        g.setItem(10, mkItem(Material.PAPER,
                "§b§l我的邀请码",
                "§7" + code,
                "",
                "§e点击刷新邀请码"));
        g.setItem(12, mkItem(Material.PLAYER_HEAD,
                "§a本月邀请",
                "§7已邀请 §e" + monthly + " §7人"));
        g.setItem(16, mkItem(Material.BOOK,
                "§e输入邀请码",
                "§7绑定他人的邀请码",
                "",
                "§e点击输入"));
        String ugLabel = "§7(玩家)";
        UserGroupManager.UserGroupConfig ugCfg =
                plugin.getUserGroup().getHighestGroup(p.getName());
        if (ugCfg != null) {
            ugLabel = ugCfg.displayColor + ugCfg.displayName;
        }
        g.setItem(22, mkItem(Material.ENCHANTED_BOOK,
                "§e§l我的用户组",
                "§7当前: " + ugLabel));

        g.setItem(26, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }


    private String getInviteRate(int count) {
        if (count > 15) return "60%";
        if (count > 10) return "35%";
        if (count > 5) return "15%";
        if (count > 0) return "10%";
        return "0%";
    }

    public void openUserManagement(Player p) {
        openUserManagement(p, 1);
    }

    public void openUserManagement(Player p, int page) {
        userMgmtPages.put(p.getUniqueId(), page);
        Inventory g = Bukkit.createInventory(null, 54, T_USER_MGMT);
        fillBg(g);

        List<Map<String, Object>> users = plugin.getDb().getAllUsers();
        int totalPages = Math.max(1, (users.size() + UMGMT_PAGE_SIZE - 1) / UMGMT_PAGE_SIZE);
        if (page > totalPages) page = totalPages;
        userMgmtPages.put(p.getUniqueId(), page);

        int start = (page - 1) * UMGMT_PAGE_SIZE;
        int end = Math.min(start + UMGMT_PAGE_SIZE, users.size());
        int slot = 0;
        for (int i = start; i < end; i++) {
            if (slot >= UMGMT_PAGE_SIZE) break;
            Map<String, Object> user = users.get(i);
            String name = (String) user.get("player_name");
            int pts = ((Number) user.getOrDefault("points", 0)).intValue();
            int stage = ((Number) user.getOrDefault("gift_stage", 0)).intValue();
            g.setItem(slot, mkItem(Material.PLAYER_HEAD,
                    "§e" + name,
                    "§7积分: " + pts + "  礼包阶段: " + stage,
                    "§7双击管理"));
            slot++;
        }

        // ===== 底部导航栏 =====
        // 上一页
        g.setItem(45, page > 1
                ? mkItem(Material.ARROW, "§a上一页")
                : mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));

        // 页码信息
        g.setItem(49, mkItem(Material.PAPER,
                "§e第" + page + "/" + totalPages + "页",
                "§7共 " + users.size() + " 名玩家"));

        // 下一页
        g.setItem(51, page < totalPages
                ? mkItem(Material.ARROW, "§a下一页")
                : mkItem(Material.GRAY_STAINED_GLASS_PANE, " "));

        // 搜索按钮
        g.setItem(46, mkItem(Material.ENDER_PEARL,
                "§b§l搜索玩家",
                "§7点击后输入玩家名",
                "§7快速定位指定玩家"));

        // 返回
        g.setItem(53, mkItem(Material.ARROW, "§7返回"));

        p.openInventory(g);
    }

    public int getUserMgmtPage(Player p) {
        return userMgmtPages.getOrDefault(p.getUniqueId(), 1);
    }

    public int getUserMgmtTotalUsers() {
        return plugin.getDb().getAllUsers().size();
    }

    public void openTaskCenter(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_TASK_CENTER);
        fillBg(g);
        g.setItem(10, mkItem(Material.BOOK,
                "§e§l新人任务",
                "§7完成新手阶段获取奖励"));
        g.setItem(12, mkItem(Material.MAP,
                "§a§l群系打卡",
                "§7在不同群系打卡完成任务",
                "§7打卡范围: 32格去重"));
        g.setItem(14, mkItem(Material.COMPASS,
                "§b§l主线任务",
                "§7查看主线任务列表"));
        g.setItem(16, mkItem(Material.PAPER,
                "§a§l支线任务",
                "§7查看支线任务列表"));
        g.setItem(18, mkItem(Material.BOOK,
                "§6工单系统",
                "§7提交bug、求助、举报"));
        g.setItem(20, mkItem(Material.EMERALD,
                "§a§l每日签到",
                "§7签到获取积分和奖励"));
        g.setItem(22, mkItem(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }



    public void openTaskList(Player p, String folderName) {
        Inventory g = Bukkit.createInventory(null, 54,
                "§d§l" + folderName);
        fillBg(g);

        // [FIX] 使用 QuestTracker 获取真实任务数据
        QuestTracker qt = plugin.getQuestTracker();
        if (qt == null) {
            g.setItem(22, mkItem(Material.BARRIER,
                    "§7任务系统未加载"));
            p.openInventory(g);
            return;
        }

        List<QuestTracker.QuestFile> quests =
                qt.getQuests(folderName);
        String playerName = p.getName();

        if (quests.isEmpty()) {
            g.setItem(22, mkItem(Material.BARRIER,
                    "§7暂无任务"));
            p.openInventory(g);
            return;
        }

        int slot = 0;
        for (QuestTracker.QuestFile qf : quests) {
            if (slot >= 45) break;

            boolean completed =
                    qt.isStageCompleted(playerName, qf);
            boolean claimed =
                    qt.hasClaimed(playerName, qf);

            Material mat;
            String status;
            if (completed && claimed) {
                mat = Material.LIME_WOOL;
                status = "§a✓ 已完成并领取";
            } else if (completed) {
                mat = Material.YELLOW_WOOL;
                status = "§e✓ 已完成 - 可领取奖励";
            } else {
                mat = Material.RED_WOOL;
                status = "§c未完成";
            }

            List<String> lore = new ArrayList<>();
            lore.add(status);
            lore.add("§7───── 条件 ─────");

            // 显示每个条件及完成状态
            for (String cond : qf.conditions) {
                String progress =
                        qt.getProgress(playerName, cond);
                if (qt.isOptional(cond)) {
                    lore.add("§7[可选] " + progress);
                } else {
                    lore.add("§e" + progress);
                }
            }

            if (!qf.rewards.isEmpty()) {
                lore.add("");
                lore.add("§7───── 奖励 ─────");
                for (String r : qf.rewards) {
                    lore.add("§a" + r);
                }
            }

            g.setItem(slot, mkItem(mat,
                    "§e" + qf.displayName,
                    lore.toArray(new String[0])));
            slot++;
        }


        g.setItem(53, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }


    public void openUserDetail(Player p, String target) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§e§l管理: " + target);
        fillBg(g);
        Map<String, Object> user = plugin.getDb()
                .getUser(target);
        int pts = ((Number) user.getOrDefault(
                "points", 0)).intValue();
        String email = (String) user.getOrDefault(
                "email", "");

        // ===== 原有按钮 =====
        g.setItem(4, mkItem(Material.NAME_TAG,
                "§e" + target));
        g.setItem(10, mkItem(Material.EMERALD,
                "§a§l积分: " + pts + "分",
                "§7左键: 增加积分",
                "§7右键: 减少积分"));
        g.setItem(11, mkItem(Material.PAPER,
                "§e邮箱: "
                        + (email.isEmpty() ? "无" : email)));
        g.setItem(12, mkItem(Material.BOOK,
                "§e设置密码"));
        g.setItem(16, mkItem(Material.BARRIER,
                "§c删除用户", "§7二次验证删除"));
        g.setItem(14, mkItem(
                Material.SNOWBALL,
                "§e§l雪球菜单",
                "§7点击为该玩家发放菜单雪球"));

        // ===== 新增：债券余额查询（复制现有接口） =====
        int bondBal = plugin.getBonds().getBonds(target);
        g.setItem(20, mkItem(Material.SUGAR,
                "§e§l债券余额: " + bondBal + "枚",
                "§7点击查看详细债券记录"));

        // ===== 新增：债券操作（支持直接增减） =====
        g.setItem(21, mkItem(Material.GOLD_INGOT,
                "§6§l债券操作",
                "§7左键: 给予债券",
                "§7右键: 扣除债券"));

        // ===== 新增：区域保护 - 全局白名单 =====
        AreaProtection areaProt = plugin.getAreaProtection();
        boolean isGlobalWhite = areaProt != null
                && areaProt.isPlayerGlobalWhitelisted(target);
        g.setItem(23, mkItem(
                isGlobalWhite
                        ? Material.LIME_BANNER
                        : Material.RED_BANNER,
                "§a§l全局白名单",
                "§7当前状态: "
                        + (isGlobalWhite ? "§a已在白名单" : "§c未在白名单"),
                "",
                "§a左键: 添加到全局白名单",
                "§c右键: 从全局白名单移除"));

        // ===== 新增：区域保护 - 区域白名单 =====
        g.setItem(24, mkItem(Material.MAP,
                "§b§l区域白名单管理",
                "§7管理该玩家的区域白名单",
                "§e点击打开区域白名单面板"));

        // ===== 返回按钮 =====
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }



    /**
     * 打开区域白名单管理面板
     * 显示该玩家在各区域白名单中的状态
     * 支持直接添加/移除
     */
    public void openAreaWhitelistPanel(
            Player p, String target) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§b§l区域白名单: " + target);
        fillBg(g);

        AreaProtection areaProt = plugin.getAreaProtection();
        if (areaProt == null) {
            g.setItem(13, mkItem(Material.BARRIER,
                    "§7区域防护未加载"));
            p.openInventory(g);
            return;
        }

        // 全局白名单状态
        boolean isGlobalWhite =
                areaProt.isPlayerGlobalWhitelisted(target);
        g.setItem(4, mkItem(
                isGlobalWhite
                        ? Material.LIME_BANNER
                        : Material.RED_BANNER,
                "§e§l全局白名单",
                "§7状态: "
                        + (isGlobalWhite ? "§a已在白名单" : "§c未在白名单"),
                "",
                "§a左键添加  §c右键移除"));

        // 遍历所有已配置的区域
        Set<String> allAreas = areaProt.getAllAreaNames();
        int slot = 9;
        for (String areaName : allAreas) {
            if (slot >= 18) break;

            boolean inArea = areaProt
                    .isPlayerAreaWhitelisted(areaName, target);

            Material mat = inArea
                    ? Material.LIME_WOOL
                    : Material.RED_WOOL;
            String status = inArea
                    ? "§a已在白名单"
                    : "§c未在白名单";

            g.setItem(slot, mkItem(mat,
                    "§e" + areaName,
                    "§7状态: " + status,
                    "",
                    "§a左键添加  §c右键移除"));
            slot++;
        }

        if (allAreas.isEmpty()) {
            g.setItem(13, mkItem(Material.BARRIER,
                    "§7暂无配置的区域"));
        }

        // 返回按钮
        g.setItem(26, mkItem(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }


    /**
     * 打开账号请求面板
     * 玩家通过 /oa 命令触发
     */
    public void openAccountRequest(Player p) {
        org.bukkit.inventory.Inventory g =
                org.bukkit.Bukkit.createInventory(
                        null, 27,
                        "§e§l账号请求");

        // 填充玻璃面板
        org.bukkit.inventory.ItemStack glass =
                new org.bukkit.inventory.ItemStack(
                        org.bukkit.Material
                                .YELLOW_STAINED_GLASS_PANE);
        org.bukkit.inventory.meta.ItemMeta gm =
                glass.getItemMeta();
        if (gm != null) {
            gm.setDisplayName(" ");
            glass.setItemMeta(gm);
        }
        for (int i = 0; i < 27; i++) {
            g.setItem(i, glass);
        }

        // 10号位：申请删除账号
        g.setItem(10, makeItem(
                org.bukkit.Material.BARRIER,
                "§c§l申请删除账号",
                "§7点击后确认删除您的账号",
                "§7此操作不可撤销"));

        // 13号位：找回密码
        g.setItem(13, makeItem(
                org.bukkit.Material.TRIPWIRE_HOOK,
                "§e§l找回密码",
                "§7通过绑定邮箱获取临时密码",
                "§7需要先绑定邮箱"));

        // 16号位：联系管理员
        g.setItem(16, makeItem(
                org.bukkit.Material.EMERALD,
                "§a§l联系管理员",
                "§7遇到问题？向管理员提交工单"));

        // 22号位：返回主菜单
        g.setItem(22, makeItem(
                org.bukkit.Material.ARROW,
                "§7返回"));

        p.openInventory(g);
    }

    /**
     * 创建物品的工具方法
     */
    private org.bukkit.inventory.ItemStack makeItem(
            org.bukkit.Material mat,
            String displayName,
            String... lore) {
        org.bukkit.inventory.ItemStack item =
                new org.bukkit.inventory.ItemStack(mat);
        org.bukkit.inventory.meta.ItemMeta meta =
                item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            if (lore.length > 0) {
                meta.setLore(
                        java.util.Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }


    private boolean isAdmin(Player p) {
        return p.getScoreboardTags().contains(
                plugin.getConfig2().adminTag);
    }

    private void fillBg(Inventory inv) {
        ItemStack glass = new ItemStack(
                Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, glass);
            }
        }
    }

// ===== 本地序列化，不依赖MenuIconManager =====

    public String serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream();
            BukkitObjectOutputStream os =
                    new BukkitObjectOutputStream(baos);
            os.writeObject(item);
            os.close();
            return Base64.getEncoder()
                    .encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    private ItemStack deserializeItem(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] data =
                    Base64.getDecoder().decode(b64);
            ByteArrayInputStream bais =
                    new ByteArrayInputStream(data);
            BukkitObjectInputStream is =
                    new BukkitObjectInputStream(bais);
            Object obj = is.readObject();
            is.close();
            if (obj instanceof ItemStack)
                return (ItemStack) obj;
        } catch (Exception e) {
        }
        return null;
    }

    private static final String ICON_SLOT_EMPTY =
            "§7图标槽（空）";

    private ItemStack mkItem(Material mat, String name,
                             String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0)
                im.setLore(Arrays.asList(lore));
            it.setItemMeta(im);
        }
        return it;
    }

    /** 命名静态内部类替代匿名BukkitRunnable，修复Paper 26 ClassLoader兼容性 */
    private static class BiomeScanTask extends BukkitRunnable {
        private final Main plugin;
        private final Player p;
        private final String category;
        private final java.util.List<int[]> candidates;
        private int idx = 0;

        BiomeScanTask(Main plugin, Player p, String category, java.util.List<int[]> candidates) {
            this.plugin = plugin;
            this.p = p;
            this.category = category;
            this.candidates = candidates;
        }

        @Override
        public void run() {
            World w = p.getWorld();
            int end = Math.min(idx + BIOME_BATCH, candidates.size());

            for (int i = idx; i < end; i++) {
                int x = candidates.get(i)[0];
                int z = candidates.get(i)[1];

                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;

                int surfaceY = w.getHighestBlockYAt(x, z);
                String key = w.getBlockAt(x, surfaceY, z)
                        .getBiome().getKey().toString();
                String cat = mapBiomeCategory(key, w.getName());

                if (category.equals(cat)) {
                    this.cancel();
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Location tp = new Location(w, x + 0.5, surfaceY + 1, z + 0.5);
                        p.teleport(tp);
                        p.sendMessage("§a已传送到 " + category + " (" + x + "," + (surfaceY + 1) + "," + z + ")");
                    });
                    return;
                }
            }

            idx = end;
            if (idx >= candidates.size()) {
                this.cancel();
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage("§c附近未找到 " + category + "，请换个位置再试"));
            }
        }
    }
}
