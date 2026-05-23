package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Files;

public class MenuManager implements Listener {

    private final Main plugin;
    private final List<MenuItem> items =
            new ArrayList<>();

    private final Set<String> chatWait =
            ConcurrentHashMap.newKeySet();
    private final Map<String, String> chatField =
            new ConcurrentHashMap<>();
    private final Map<String, Integer> chatIdx =
            new ConcurrentHashMap<>();


    // ===== 菜单项 =====
    public static class MenuItem {
        public String title = "新菜单项";
        public String command = "";
        public String permType = "玩家";
        public Integer slot = null;
        public ItemStack icon;
        public MenuItem() {
            icon = randomIcon();
        }

        public MenuItem(String t,
                        String c, String p) {
            title = t;
            command = c;
            permType = p;
            icon = randomIcon();
        }

        static ItemStack randomIcon() {
            Material[] pool = {
                    Material.COMPASS,
                    Material.ENDER_PEARL,
                    Material.DIAMOND,
                    Material.GOLD_INGOT,
                    Material.EMERALD,
                    Material.NETHER_STAR,
                    Material.BEACON,
                    Material.ENCHANTING_TABLE,
                    Material.CHEST,
                    Material.FIREWORK_ROCKET,
                    Material.BOOK,
                    Material.ELYTRA,
                    Material.TOTEM_OF_UNDYING
            };
            Material m = pool[
                    (int) (Math.random()
                            * pool.length)];
            ItemStack it =
                    new ItemStack(m);
            ItemMeta im = it.getItemMeta();
            im.setDisplayName("§e默认图标");
            it.setItemMeta(im);
            return it;
        }
    }

    public MenuManager(Main plugin) {
        this.plugin = plugin;
    }

    public List<MenuItem> getItems() {
        return items;
    }

    // ===== 读取菜单.txt =====
    public void loadMenu() {
        items.clear();
        File f = new File(
                plugin.getDataFolder(),
                "菜单.txt");
        if (!f.exists()) createDefault(f);
        parseFile(f);
        plugin.getLogger().info(
                "[Menu] 加载 "
                        + items.size()
                        + " 个菜单项");
    }

    private void parseFile(File f) {
        plugin.getLogger().info("[Menu] ===== 扫描 =====");
        plugin.getLogger().info("[Menu] 路径: "
                + f.getAbsolutePath());
        plugin.getLogger().info("[Menu] 大小: "
                + f.length());

        try {
            List<String> lines = Files.readAllLines(
                    f.toPath(), StandardCharsets.UTF_8);
            plugin.getLogger().info("[Menu] 总行数: "
                    + lines.size());

            boolean inBlock = false;
            boolean inHtml = false;
            StringBuilder buf = new StringBuilder();
            boolean inItem = false;
            int parsed = 0;

            for (int lineNum = 1;
                 lineNum <= lines.size();
                 lineNum++) {
                String raw = lines.get(lineNum - 1);
                String l = raw.trim();

                plugin.getLogger().info(
                        "[Menu] L" + lineNum
                                + ": 原文[" + l.length()
                                + "字符] " + l);

                // ===== 第1优先：跨行块注释 =====
                if (inBlock) {
                    if (l.contains("*/")) {
                        inBlock = false;
                        l = l.substring(
                                        l.indexOf("*/") + 2)
                                .trim();
                        plugin.getLogger().info(
                                "[Menu] L" + lineNum
                                        + ": 块注释结束");
                    } else {
                        plugin.getLogger().info(
                                "[Menu] L" + lineNum
                                        + ": [块注释中]");
                        continue;
                    }
                }

                // ===== 第2优先：跨行HTML注释 =====
                if (inHtml) {
                    if (l.contains("-->")) {
                        inHtml = false;
                        l = l.substring(
                                        l.indexOf("-->") + 3)
                                .trim();
                    } else {
                        plugin.getLogger().info(
                                "[Menu] L" + lineNum
                                        + ": [HTML注释中]");
                        continue;
                    }
                }

                // ===== 第3优先：整行注释（最高裁决） =====
                // 以 # 或 // 开头的行，直接跳过
                // 不管后面包含什么 /* <!-- 等
                if (l.startsWith("#")
                        || l.startsWith("//")) {
                    plugin.getLogger().info(
                            "[Menu] L" + lineNum
                                    + ": [整行注释,跳过]");
                    continue;
                }

                // ===== 第4优先：块注释开始 =====
                if (l.contains("/*")) {
                    l = l.substring(0, l.indexOf("/*"))
                            .trim();
                    inBlock = true;
                    plugin.getLogger().info(
                            "[Menu] L" + lineNum
                                    + ": 进入/*块注释"
                                    + " 剩余[" + l + "]");
                    if (l.isEmpty()) {
                        continue;
                    }
                }

                // ===== 第5优先：HTML注释开始 =====
                if (l.contains("<!--")) {
                    l = l.substring(0,
                            l.indexOf("<!--")).trim();
                    inHtml = true;
                    plugin.getLogger().info(
                            "[Menu] L" + lineNum
                                    + ": 进入<!--注释");
                    if (l.isEmpty()) {
                        continue;
                    }
                }

                // ===== 第6优先：行内注释 =====
                int hashPos = l.indexOf("#");
                int slashPos = l.indexOf("//");
                if (hashPos >= 0) {
                    l = l.substring(0, hashPos).trim();
                }
                if (slashPos >= 0) {
                    if (hashPos < 0
                            || slashPos < hashPos) {
                        l = l.substring(0, slashPos)
                                .trim();
                    }
                }

                if (l.isEmpty()) {
                    plugin.getLogger().info(
                            "[Menu] L" + lineNum
                                    + ": 空");
                    continue;
                }

                plugin.getLogger().info(
                        "[Menu] L" + lineNum
                                + ": [保留] " + l);

                if (l.equals("{")) {
                    inItem = true;
                    buf.setLength(0);
                    plugin.getLogger().info(
                            "[Menu] === 块开始 ===");
                    continue;
                }

                if (l.equals("}")) {
                    if (inItem) {
                        plugin.getLogger().info(
                                "[Menu] === 块结束 ===");
                        parseBlock(buf.toString());
                        parsed++;
                        plugin.getLogger().info(
                                "[Menu] 当前菜单项: "
                                        + items.size());
                    }
                    inItem = false;
                    continue;
                }

                if (inItem) {
                    buf.append(l).append("\n");
                }
            }

            plugin.getLogger().info(
                    "[Menu] ===== 完毕 =====");
            plugin.getLogger().info(
                    "[Menu] 块数: " + parsed);
            plugin.getLogger().info(
                    "[Menu] 菜单项: "
                            + items.size());

        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Menu] 异常: "
                            + e.getMessage());
        }
    }


    private void parseBlock(String block) {
        String title = "";
        String cmd = "";
        String perm = "玩家";
        String iconMat = "";
        int slotNum = -1;

        for (String line : block.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            String v = "";
            int idx = line.indexOf("：");
            if (idx < 0) idx = line.indexOf(":");
            if (idx >= 0) {
                v = line.substring(idx + 1).trim();
                if (v.startsWith("\"")
                        && v.endsWith("\"")
                        && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
            }

            if (line.startsWith("标题")) {
                title = v;
            } else if (line.startsWith("指令")) {
                cmd = v;
                if (!cmd.isEmpty()
                        && !cmd.startsWith("/")) {
                    cmd = "/" + cmd;
                }
            } else if (line.startsWith("权限")) {
                perm = v;
            } else if (line.startsWith("图标")) {
                iconMat = v;
            } else if (line.startsWith("格子")) {
                try {
                    int s = Integer.parseInt(v);
                    if (s >= 1 && s <= 54) {
                        slotNum = s;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (title.isEmpty()) return;

        MenuItem mi = new MenuItem(title, cmd, perm);
        if (slotNum >= 1 && slotNum <= 54) {
            mi.slot = slotNum;
        }
        if (!iconMat.isEmpty()) {
            try {
                String matName = iconMat
                        .replace("minecraft:", "")
                        .replace("bukkit:", "")
                        .trim().toUpperCase();
                Material m =
                        Material.valueOf(matName);
                mi.icon = new ItemStack(m);
                ItemMeta im =
                        mi.icon.getItemMeta();
                im.setDisplayName(
                        "§e" + title);
                mi.icon.setItemMeta(im);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[Menu] 无法解析: "
                                + iconMat);
            }
        }
        items.add(mi);
    }


    private void createDefault(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets
                                .UTF_8))) {
            pw.println("# 服务器菜单");
            pw.println("# {}定义菜单项");
            pw.println("# 支持"
                    + " # // /* */ <!-- -->");
            pw.println();
            pw.println("{");
            pw.println("  标题：主城传送");
            pw.println("  指令：/spawn");
            pw.println("  权限类型：玩家");
            pw.println("  图标：COMPASS");
            pw.println("}");
            pw.println();
            pw.println("{");
            pw.println("  标题：随机传送");
            pw.println("  指令：/rtp");
            pw.println("  权限类型：玩家");
            pw.println("  图标：ENDER_PEARL");
            pw.println("}");
        } catch (Exception ignored) {
        }
    }

    // ===== 保存 =====
    public void saveMenu() {
        File f = new File(
                plugin.getDataFolder(),
                "菜单.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# 服务器菜单");
            pw.println();
            for (MenuItem mi : items) {
                pw.println("{");
                pw.println("  标题："
                        + mi.title);
                pw.println("  指令："
                        + mi.command);
                pw.println("  权限类型："
                        + mi.permType);
                // ★ 保存实际材质名 ★
                pw.println("  图标："
                        + mi.icon.getType().name());
                pw.println("}");
                pw.println();
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Menu] 保存失败: "
                            + e.getMessage());
        }
    }

    // ===== 聊天编辑 =====
    public boolean isEditing(String name) {
        return chatWait.contains(name);
    }

    public void editTitle(
            Player p, int idx) {
        chatIdx.put(p.getName(), idx);
        chatWait.add(p.getName());
        chatField.put(p.getName(), "title");
        p.closeInventory();
        p.sendMessage("§e请输入菜单标题:");
    }

    public void editCommand(
            Player p, int idx) {
        chatIdx.put(p.getName(), idx);
        chatWait.add(p.getName());
        chatField.put(p.getName(), "command");
        p.closeInventory();
        p.sendMessage("§e请输入指令"
                + "(自动补全/):");
    }

    public void onChat(Player p, String input) {
        String name = p.getName();
        if (!chatWait.remove(name)) return;

        String field = chatField.remove(name);
        Integer idx = chatIdx.get(name);
        if (idx == null || idx >= items.size()) return;

        MenuItem mi = items.get(idx);

        if ("title".equals(field)) {
            mi.title = input;
            p.sendMessage("§a标题: " + input);
        } else if ("command".equals(field)) {
            if (!input.startsWith("/"))
                input = "/" + input;
            mi.command = input;
            p.sendMessage("§a指令: " + input);
        }

        Bukkit.getScheduler().runTask(plugin, () ->
                plugin.gui.openEditor(p, idx));
    }
}
