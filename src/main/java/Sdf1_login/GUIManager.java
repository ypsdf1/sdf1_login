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
import java.util.Base64;



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

        // ★ 余额查询 - 所有人都能看 ★
        double myBal = 0;
        try {
            var reg = plugin.getServer()
                    .getServicesManager()
                    .getRegistration(
                            net.milkbowl.vault.economy
                                    .Economy.class);
            if (reg != null)
                myBal = reg.getProvider()
                        .getBalance(p);
        } catch (Exception ignored) {}
        g.setItem(32, mkItem(Material.DIAMOND,
                "§a§l余额查询",
                "§7我的余额: §a$"
                        + String.format("%.2f", myBal)));

// ★ 余额操作 - 仅OP和tag玩家 ★
        if (isAdmin(p)) {
            g.setItem(34, mkItem(Material.GOLD_INGOT,
                    "§6§l余额操作",
                    "§7给钱、扣钱"));
        }



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
            // ★ 直接用 mi.icon，不要随机 ★
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
        Inventory g = Bukkit.createInventory(
                null, 54,
                "§d§l余额操作");
        fillBg(g);

        net.milkbowl.vault.economy.Economy eco = null;
        try {
            var reg = plugin.getServer()
                    .getServicesManager()
                    .getRegistration(
                            net.milkbowl
                                    .vault
                                    .economy
                                    .Economy
                                    .class);
            if (reg != null) eco = reg.getProvider();
        } catch (Exception ignored) {}

        int slot = 0;
        for (Player op :
                Bukkit.getOnlinePlayers()) {
            if (slot >= 45) break;
            double bal = 0;
            if (eco != null) bal =
                    eco.getBalance(op);

            boolean isSelf =
                    op.getName().equals(p.getName());

            g.setItem(slot, mkItem(
                    isSelf ? Material.EMERALD_BLOCK
                            : Material.PLAYER_HEAD,
                    "§e" + op.getName(),
                    "§7余额: §a$"
                            + String.format(
                            "%.2f", bal),
                    "",
                    "§a左键给钱  §c右键扣钱"));
            slot++;
        }

        g.setItem(49, mkItem(Material.ARROW,
                "§7返回"));

        p.openInventory(g);
    }

    public void openShop(Player p) {
        p.closeInventory();
        p.performCommand("sdf1_login shop");
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
        Player p = (Player) e.getPlayer();
        String t = e.getView().getTitle();

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
        String menuTitle =
                "§6§l" + fileName
                        .replace(".txt", "");
        Inventory g = Bukkit.createInventory(
                null, 54, menuTitle);
        fillBg(g);

        java.io.File f = new java.io.File(
                plugin.getDataFolder(), fileName);
        if (!f.exists()) {
            p.sendMessage("§c文件不存在: "
                    + fileName);
            return;
        }
        try {
            List<String> lines =
                    java.nio.file.Files
                            .readAllLines(
                                    f.toPath(),
                                    java.nio.charset
                                            .StandardCharsets
                                            .UTF_8);
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
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Menu] 子菜单读取失败: "
                            + e.getMessage());
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
        final int[] idx = {0};

        new BukkitRunnable() {
            @Override
            public void run() {
                World w = p.getWorld();
                int end = Math.min(
                        idx[0] + BIOME_BATCH,
                        candidates.size());

                for (int i = idx[0]; i < end; i++) {
                    int x = candidates.get(i)[0];
                    int z = candidates.get(i)[1];

                    // 跳过未加载区块
                    if (!w.isChunkLoaded(x >> 4, z >> 4))
                        continue;

                    // 获取地表高度
                    int surfaceY =
                            w.getHighestBlockYAt(x, z);

                    // 在地表Y取群系（避免地下误判）
                    String key = w.getBlockAt(
                                    x, surfaceY, z)
                            .getBiome()
                            .getKey().toString();
                    String cat =
                            mapBiomeCategory(
                                    key, w.getName());

                    if (category.equals(cat)) {
                        this.cancel();
                        Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    Location tp =
                                            new Location(
                                                    w,
                                                    x + 0.5,
                                                    surfaceY + 1,
                                                    z + 0.5);
                                    p.teleport(tp);
                                    p.sendMessage(
                                            "§a已传送到 "
                                                    + category
                                                    + " ("
                                                    + x + ","
                                                    + (surfaceY + 1)
                                                    + ","
                                                    + z + ")");
                                });
                        return;
                    }
                }

                idx[0] = end;
                if (idx[0] >= candidates.size()) {
                    this.cancel();
                    Bukkit.getScheduler()
                            .runTask(plugin, () ->
                                    p.sendMessage(
                                            "§c附近未找到 "
                                                    + category
                                                    + "，请换个位置再试"));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }



    private String mapBiomeCategory(
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
    public void onInvDrag(
            InventoryDragEvent e) {
        if (!(e.getWhoClicked()
                instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (!T_EDITOR.equals(
                e.getView().getTitle())) return;
        if (e.getRawSlots().contains(12)) {
            List<MenuManager.MenuItem> list =
                    plugin.getMenu().getItems();
            int idx = list.size() - 1;
            if (idx >= 0)
                list.get(idx).icon =
                        e.getOldCursor().clone();
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

    // ===== 放入自定义图标 =====
    private void doPlaceIcon(Player p,
                             Inventory topInv,
                             ItemStack item, int removeSlot) {

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
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        // openAdmin 方法中加：
        g.setItem(15, mkItem(Material.EMERALD_BLOCK,
                "§d§l余额操作",
                "§7给钱、扣钱"));

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
        Inventory g = Bukkit.createInventory(
                null, 54, T_USER_MGMT);
        fillBg(g);
        List<Map<String, Object>> users = plugin.getDb()
                .getAllUsers();
        int slot = 0;
        for (Map<String, Object> user : users) {
            if (slot >= 45) break;
            String name = (String) user.get("player_name");
            int pts = ((Number) user.getOrDefault(
                    "points", 0)).intValue();
            int stage = ((Number) user.getOrDefault(
                    "gift_stage", 0)).intValue();
            g.setItem(slot, mkItem(Material.PLAYER_HEAD,
                    "§e" + name,
                    "§7积分: " + pts
                            + "  礼包阶段: " + stage,
                    "§7双击管理"));
            slot++;
        }
        g.setItem(53, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
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
        g.setItem(4, mkItem(Material.NAME_TAG,
                "§e" + target));
        g.setItem(10, mkItem(Material.EMERALD,
                "§a积分: " + pts,
                "§7点击设置积分"));
        g.setItem(11, mkItem(Material.PAPER,
                "§e邮箱: "
                        + (email.isEmpty() ? "无" : email)));
        g.setItem(12, mkItem(Material.BOOK,
                "§e设置密码"));
        g.setItem(16, mkItem(Material.BARRIER,
                "§c删除用户", "§7二次验证删除"));
        // 雪球菜单检测（slot 14）
        g.setItem(14, mkItem(
                Material.SNOWBALL,
                "§e§l雪球菜单",
                "§7点击为该玩家发放菜单雪球"));

        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
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
}
