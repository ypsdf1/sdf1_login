package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;


import java.lang.reflect.Method;
import java.util.*;

public class PointsManager {

    private final Main plugin;
    private Object cyInstance = null;
    private Method cyActivationMethod = null;
    private Method cyGetShopItemsMethod = null;

    private final List<Map<String, Object>> cyItems =
            new ArrayList<>();

    public PointsManager(Main plugin) {
        this.plugin = plugin;
        discoverCY();
    }

    private void discoverCY() {
        try {
            org.bukkit.plugin.Plugin p = Bukkit
                    .getPluginManager()
                    .getPlugin("CY_beibao");
            if (p != null && p.isEnabled()) {
                cyInstance = p;
                cyGetShopItemsMethod = p.getClass()
                        .getMethod("onSdf1GetShopItems");
                cyActivationMethod = p.getClass()
                        .getMethod("onSdf1Activation",
                                String.class,
                                int.class, int.class);
                plugin.getLogger().info(
                        "[Sdf1_login] CY_beibao 联控已连接");
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshCYItems() {
        cyItems.clear();
        if (cyInstance == null
                || cyGetShopItemsMethod == null) return;
        try {
            Object result =
                    cyGetShopItemsMethod.invoke(cyInstance);
            if (result == null) return;
            String data = result.toString();
            if (data.isEmpty()) return;
            String[] items = data.split(";");
            for (String itemStr : items) {
                String[] p = itemStr.split("\\|");
                if (p.length < 8) continue;
                Map<String, Object> item =
                        new LinkedHashMap<>();
                item.put("id", p[0]);
                item.put("name", p[1]);
                item.put("days",
                        Integer.parseInt(p[2]));
                item.put("slots",
                        Integer.parseInt(p[3]));
                item.put("price",
                        Integer.parseInt(p[4]));
                item.put("stock",
                        Integer.parseInt(p[5]));
                item.put("icon", p[6]);
                item.put("lifetime",
                        "1".equals(p[7]));
                cyItems.add(item);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Sdf1_login] 拉取CY商品失败: "
                            + e.getMessage());
        }
    }

    private void activateCY(String name,
                            int slots, int days) {
        try {
            if (cyInstance == null
                    || cyActivationMethod == null) return;
            cyActivationMethod.invoke(
                    cyInstance, name, slots, days);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Sdf1_login] CY激活失败: "
                            + e.getMessage());
        }
    }

    public int getPoints(String name) {
        return ((Number) plugin.getDb()
                .getField(name, "points")).intValue();
    }

    public Inventory createShopGUI(Player p) {
        int points = getPoints(p.getName());
        Inventory inv = Bukkit.createInventory(
                null, 54,
                "§d§l积分商城 | 当前积分: " + points);
        fillBg(inv);
        inv.setItem(10, mkItem(Material.EMERALD,
                "§a游戏币充值",
                "§7消耗积分兑换游戏币",
                "§7100积分 = $1000",
                "",
                "§e点击兑换"));
        inv.setItem(12, mkItem(Material.ENDER_CHEST,
                "§b背包空间",
                "§7消耗积分兑换背包格子",
                "§750积分 = 9格永久空间",
                "",
                "§e点击兑换"));
        inv.setItem(14, mkItem(Material.EXPERIENCE_BOTTLE,
                "§e经验瓶",
                "§7消耗积分兑换经验",
                "§720积分 = 10级经验",
                "",
                "§e点击兑换"));
        if (cyInstance != null) {
            inv.setItem(31, mkItem(Material.CHEST,
                    "§6背包商城",
                    "§7来自CY_beibao的商品",
                    "",
                    "§e点击查看"));
        }
        inv.setItem(49, mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    public Inventory createCYShopGUI(Player p) {
        refreshCYItems();
        int points = getPoints(p.getName());
        int size = Math.max(54,
                ((cyItems.size() / 9) + 1) * 9);
        if (size > 54) size = 54;
        Inventory inv = Bukkit.createInventory(
                null, size,
                "§d§lCY背包商城 | 当前积分: " + points);
        fillBg(inv);
        for (int i = 0; i < cyItems.size()
                && i < 45; i++) {
            Map<String, Object> item = cyItems.get(i);
            String name = (String) item.get("name");
            int price = ((Number) item.get("price"))
                    .intValue();
            int stock = ((Number) item.get("stock"))
                    .intValue();
            int days = ((Number) item.get("days"))
                    .intValue();
            int slots = ((Number) item.get("slots"))
                    .intValue();
            boolean lifetime = (boolean) item
                    .get("lifetime");

            List<String> lore = new ArrayList<>();
            lore.add("§7价格: §e" + price + " 积分");
            if (lifetime) {
                lore.add("§7格子: §e终身 +" + slots);
            } else {
                lore.add("§7格子: §e+" + slots);
                lore.add("§7时间: §e" + days + "天");
            }
            if (stock == 0) {
                lore.add("§7库存: §a无限");
            } else if (stock == -1) {
                lore.add("§7库存: §c已下架");
            } else {
                lore.add("§7库存: §e" + stock);
            }
            lore.add("");
            if (points >= price && stock != -1) {
                lore.add("§e点击购买");
            } else if (stock == -1) {
                lore.add("§c已下架");
            } else {
                lore.add("§c积分不足");
            }
            inv.setItem(i, mkItem(Material.CHEST,
                    "§a" + name,
                    lore.toArray(new String[0])));
        }
        inv.setItem(size - 1,
                mkItem(Material.ARROW, "§7返回"));
        return inv;
    }

    public void addItem(String name,
                        int price, String cmd2) {
        // 追加到商品配置文件
        File file = new File(
                plugin.getDataFolder(),
                "ShopItems.txt");
        try {
            java.io.FileWriter fw =
                    new java.io.FileWriter(file, true);
            fw.write(name + ":" + price
                    + ":" + cmd2 + "\n");
            fw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void removeItem(int index) {
        File file = new File(
                plugin.getDataFolder(),
                "ShopItems.txt");
        try {
            java.util.List<String> lines =
                    new java.util.ArrayList<>();
            java.io.BufferedReader br =
                    new java.io.BufferedReader(
                            new java.io.FileReader(
                                    file));
            String line;
            int i = 0;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()
                        || line.startsWith("#")) {
                    lines.add(line);
                    continue;
                }
                if (i != index) {
                    lines.add(line);
                }
                i++;
            }
            br.close();
            java.io.PrintWriter pw =
                    new java.io.PrintWriter(file);
            for (String l : lines) pw.println(l);
            pw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public void handleClick(Player p, int slot) {
        int points = getPoints(p.getName());
        switch (slot) {
            case 10:
                if (points >= 100) {
                    plugin.getDb().deductPoints(
                            p.getName(), 100);
                    if (plugin.getEconomy() != null) {
                        plugin.getEconomy()
                                .depositPlayer(p, 1000);
                    }
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_purchase_success")
                            .replace("{count}", "100"));
                } else {
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_insufficient")
                            .replace("{points}",
                                    String.valueOf(points)));
                }
                break;
            case 12:
                if (points >= 50) {
                    plugin.getDb().deductPoints(
                            p.getName(), 50);
                    activateCY(p.getName(), 9, 0);
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_purchase_success")
                            .replace("{count}", "50"));
                } else {
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_insufficient")
                            .replace("{points}",
                                    String.valueOf(points)));
                }
                break;
            case 14:
                if (points >= 20) {
                    plugin.getDb().deductPoints(
                            p.getName(), 20);
                    p.setLevel(p.getLevel() + 10);
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_purchase_success")
                            .replace("{count}", "20"));
                } else {
                    p.sendMessage(plugin.getConfig2()
                            .msg("points_insufficient")
                            .replace("{points}",
                                    String.valueOf(points)));
                }
                break;
            case 31:
                if (cyInstance != null) {
                    p.openInventory(
                            createCYShopGUI(p));
                }
                break;
        }
    }

    public void handleCYClick(Player p, int slot) {
        if (slot < 0 || slot >= cyItems.size()
                || slot >= 45) return;
        Map<String, Object> item = cyItems.get(slot);
        int price = ((Number) item.get("price"))
                .intValue();
        int stock = ((Number) item.get("stock"))
                .intValue();
        int days = ((Number) item.get("days"))
                .intValue();
        int slots = ((Number) item.get("slots"))
                .intValue();
        boolean lifetime = (boolean) item
                .get("lifetime");

        if (stock == -1) {
            p.sendMessage("§c该商品已下架");
            return;
        }
        int points = getPoints(p.getName());
        if (points < price) {
            p.sendMessage(plugin.getConfig2()
                    .msg("points_insufficient")
                    .replace("{points}",
                            String.valueOf(points)));
            return;
        }

        plugin.getDb().deductPoints(p.getName(), price);
        int actDays = lifetime ? 0 : days;
        activateCY(p.getName(), slots, actDays);

        String tl = lifetime
                ? "终身" : (days + "天");
        p.sendMessage("§a购买成功！获得 "
                + tl + " +" + slots + "格 背包空间");
    }

    private void fillBg(Inventory inv) {
        ItemStack gl = mkItem(
                Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++)
            inv.setItem(i, gl);
    }

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
