package Sdf1_login;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.text.SimpleDateFormat;
import java.util.*;

public class GarbageManager {

    private final Main plugin;
    private Connection db;
    private int cleanupInterval = 1800;
    private int maxRounds = 1;
    private boolean enabled = true;
    private int currentRound = 0;
    private final Map<String, Integer>
            recyclePages = new HashMap<>();


    public GarbageManager(Main plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            File dbFile = new File(
                    plugin.getDataFolder(),
                    "garbage.db");
            db = DriverManager.getConnection(
                    "jdbc:sqlite:"
                            + dbFile.getAbsolutePath());
            Statement st = db.createStatement();
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "items ("
                    + "id INTEGER PRIMARY KEY "
                    + "AUTOINCREMENT,"
                    + "item_data TEXT NOT NULL,"
                    + "amount INTEGER DEFAULT 1,"
                    + "round INTEGER DEFAULT 0,"
                    + "create_time INTEGER NOT NULL"
                    + ")");
            st.execute("CREATE TABLE IF NOT EXISTS "
                    + "config ("
                    + "key TEXT PRIMARY KEY,"
                    + "value TEXT NOT NULL"
                    + ")");
            st.close();
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Garbage] DB失败: "
                            + e.getMessage());
        }
    }

    public void loadConfig() {
        // 从 ConfigManager 读取
        cleanupInterval =
                plugin.getConfig2()
                        .garbageInterval;
        enabled =
                plugin.getConfig2()
                        .garbageEnabled;
        maxRounds =
                plugin.getConfig2()
                        .garbageMaxRounds;

        // 从数据库读取当前轮次
        try {
            String val = getConfigValue("round");
            if (val != null)
                currentRound =
                        Integer.parseInt(val);
        } catch (Exception ignored) {
        }

        plugin.getLogger().info(
                "[Garbage] 间隔: "
                        + cleanupInterval + "秒, "
                        + "轮次保留: "
                        + maxRounds + "轮, "
                        + "启用: " + enabled);
    }


    private String getConfigValue(String key) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "SELECT value FROM config "
                            + "WHERE key=?");
            ps.setString(1, key);
            ResultSet rs = ps.executeQuery();
            String val = rs.next()
                    ? rs.getString("value") : null;
            rs.close();
            ps.close();
            return val;
        } catch (Exception e) {
            return null;
        }
    }

    private void setConfigValue(String key,
                                String value) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "INSERT OR REPLACE INTO config "
                            + "(key, value) "
                            + "VALUES (?, ?)");
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void startAutoCleanup() {
        if (!enabled) return;
        new BukkitRunnable() {
            int countdown = 0;

            @Override
            public void run() {
                // 在线人数≤0时暂停
                if (Bukkit.getOnlinePlayers()
                        .isEmpty()) {
                    countdown = 0;
                    return;
                }

                // 重新读取配置（支持热更）
                cleanupInterval =
                        plugin.getConfig2()
                                .garbageInterval;

                int remain =
                        cleanupInterval - countdown;
                if (remain == 300)
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e5分钟");
                else if (remain == 180)
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e3分钟");
                else if (remain == 60)
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e1分钟");
                else if (remain == 30)
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e30秒");
                else if (remain == 10)
                    broadcast("§e§l[垃圾站] §f距离清理还有 §e10秒");
                else if (remain <= 5 && remain > 0)
                    broadcast("§e§l[垃圾站] §c" + remain + "§f...");
                else if (remain == 0) {
                    doCleanup();
                    countdown = 0;
                    return;
                }
                countdown++;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void doCleanup() {
        // 删除过期轮次的物品
        int deleteRound =
                currentRound - maxRounds;
        if (deleteRound >= 0) {
            int deleted = deleteOldItems(
                    deleteRound);
            if (deleted > 0) {
                broadcast("§7[垃圾站] §f永久清理了 §e"
                        + deleted + " §f个过期物品");
            }
        }

        // 递增轮次
        currentRound++;
        setConfigValue("round",
                String.valueOf(currentRound));

        // 收集当前掉落物
        int count = collectItems();
        broadcast("§a§l[垃圾站] §f清理完成，"
                + "共清理 §e" + count
                + " §f个掉落物，存入回收站");

        // 打印下次清理时间
        long nextMs = System.currentTimeMillis()
                + (long) cleanupInterval * 1000L;
        String nextTime =
                new SimpleDateFormat("HH:mm:ss")
                        .format(new Date(nextMs));
        broadcast("§e§l[垃圾站] §f下次清理时间: §a"
                + nextTime);
    }

    public int collectItems() {
        int count = 0;
        for (org.bukkit.World w :
                Bukkit.getWorlds()) {
            Collection<Item> items =
                    w.getEntitiesByClass(Item.class);
            for (Item item : items) {
                ItemStack stack =
                        item.getItemStack();
                if (stack == null
                        || stack.getType()
                        == Material.AIR)
                    continue;
                saveItem(stack);
                item.remove();
                count++;
            }
        }
        return count;
    }

    public void saveItem(ItemStack stack) {
        try {
            String data = stackToBase64(stack);
            if (data.isEmpty()) return;
            PreparedStatement ps = db.prepareStatement(
                    "INSERT INTO items "
                            + "(item_data, amount, "
                            + "round, create_time) "
                            + "VALUES (?,?,?,?)");
            ps.setString(1, data);
            ps.setInt(2, stack.getAmount());
            ps.setInt(3, currentRound);
            ps.setLong(4,
                    System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private int deleteOldItems(int beforeRound) {
        try {
            PreparedStatement ps = db.prepareStatement(
                    "DELETE FROM items "
                            + "WHERE round <= ?");
            ps.setInt(1, beforeRound);
            int rows = ps.executeUpdate();
            ps.close();
            return rows;
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== 序列化 ==========

    private String stackToBase64(ItemStack stack) {
        try {
            Map<String, Object> map =
                    stack.serialize();
            String json = new Gson().toJson(map);
            return Base64.getEncoder()
                    .encodeToString(
                            json.getBytes(
                                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack base64ToStack(String base64) {
        try {
            byte[] bytes = Base64.getDecoder()
                    .decode(base64);
            String json = new String(bytes,
                    StandardCharsets.UTF_8);
            Type type = new TypeToken<
                    Map<String, Object>>() {}
                    .getType();
            Map<String, Object> map =
                    new Gson().fromJson(json, type);
            map = fixNumberTypes(map);
            return ItemStack.deserialize(map);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fixNumberTypes(
            Map<String, Object> map) {
        Map<String, Object> result =
                new LinkedHashMap<>();
        for (Map.Entry<String, Object> e
                : map.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Double) {
                double d = (Double) v;
                if (d == (long) d) {
                    result.put(e.getKey(), (long) d);
                } else {
                    result.put(e.getKey(), d);
                }
            } else if (v instanceof Map) {
                result.put(e.getKey(),
                        fixNumberTypes(
                                (Map<String, Object>) v));
            } else if (v instanceof List) {
                List<Object> list =
                        new ArrayList<>();
                for (Object item : (List<?>) v) {
                    if (item instanceof Map) {
                        list.add(fixNumberTypes(
                                (Map<String, Object>) item));
                    } else {
                        list.add(item);
                    }
                }
                result.put(e.getKey(), list);
            } else {
                result.put(e.getKey(), v);
            }
        }
        return result;
    }

    // ========== 回收站GUI ==========
    public void openRecycle(Player p) {
        openRecyclePage(p, 0);
    }

    public void openRecyclePage(Player p, int page) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§6§l垃圾回收站");

        List<Map<String, Object>> allItems =
                getStoredItems(999);
        int total = allItems.size();
        int pageSize = 40;
        int totalPages = Math.max(1,
                (int) Math.ceil(
                        (double) total / pageSize));

        // 修正页码
        if (page < 0) page = 0;
        if (page >= totalPages)
            page = totalPages - 1;

        recyclePages.put(p.getName(), page);

        // 填充物品（0-39 格 = 第1页，40-79 = 第2页...）
        int start = page * pageSize;
        int end = Math.min(start + pageSize,
                total);
        int slot = 0;
        for (int i = start; i < end; i++) {
            if (slot >= 40) break;
            Map<String, Object> item =
                    allItems.get(i);
            int id = ((Number) item.get("id"))
                    .intValue();
            String data = (String) item
                    .get("item_data");
            int amount = ((Number) item
                    .get("amount")).intValue();
            ItemStack stack = base64ToStack(data);
            if (stack != null) {
                stack.setAmount(amount);
                ItemMeta im =
                        stack.getItemMeta();
                if (im != null) {
                    List<String> lore =
                            new ArrayList<>();
                    lore.add("§7ID: #" + id);
                    lore.add("§a点击取出");
                    im.setLore(lore);
                    stack.setItemMeta(im);
                }
                g.setItem(slot, stack);
            }
            slot++;
        }

        // 49 格：上一页
        if (page > 0) {
            g.setItem(49, mk(Material.ARROW,
                    "§e上一页 §7("
                            + page + "/"
                            + totalPages + ")"));
        } else {
            g.setItem(49, mk(Material.ARROW,
                    "§e第一页 §7("
                            + "1/" + totalPages + ")"));
        }

        // 50 格：统计信息
        g.setItem(50, mk(Material.CHEST,
                "§e回收站统计",
                "§7总存量: §f" + total + " 个",
                "§7当前页: §f" + (page + 1)
                        + "/" + totalPages));

        // 51 格：下一页
        boolean canNext = page < totalPages - 1;
        if (canNext) {
            g.setItem(51, mk(Material.ARROW,
                    "§e下一页 §7("
                            + (page + 2) + "/"
                            + totalPages + ")"));
        } else {
            g.setItem(51, mk(Material.ARROW,
                    "§7已是最后一页"));
        }

        // 52 格：返回
        g.setItem(52, mk(Material.ARROW,
                "§7返回",
                "§7返回主菜单"));

        p.openInventory(g);
    }

    public int getRecyclePage(String name) {
        return recyclePages
                .getOrDefault(name, 0);
    }


    public boolean handleClick(Player p,
                               String title, int slot) {
        if (!title.equals("§6§l垃圾回收站"))
            return false;
        // 49：上一页
        if (slot == 49) {
            int page = recyclePages
                    .getOrDefault(p.getName(), 0);
            if (page > 0)
                openRecyclePage(p, page - 1);
            return true;
        }
        // 51：下一页
        if (slot == 51) {
            int page = recyclePages
                    .getOrDefault(p.getName(), 0);
            openRecyclePage(p, page + 1);
            return true;
        }
        // 52：返回
        if (slot == 52) {
            plugin.getGui().openMain(p);
            return true;
        }

        // 50/52/53：不处理
        if (slot >= 49 && slot <= 53)
            return true;
        return true;
    }

    private List<Map<String, Object>>
    getStoredItems(int limit) {
        List<Map<String, Object>> list =
                new ArrayList<>();
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "SELECT id, item_data, "
                                    + "amount "
                                    + "FROM items "
                                    + "ORDER BY "
                                    + "create_time "
                                    + "DESC LIMIT ?");
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> row =
                        new LinkedHashMap<>();
                row.put("id",
                        rs.getInt("id"));
                row.put("item_data",
                        rs.getString("item_data"));
                row.put("amount",
                        rs.getInt("amount"));
                list.add(row);
            }
            rs.close();
            ps.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public boolean removeItem(int id) {
        try {
            PreparedStatement ps =
                    db.prepareStatement(
                            "DELETE FROM items "
                                    + "WHERE id=?");
            ps.setInt(1, id);
            int rows = ps.executeUpdate();
            ps.close();
            return rows > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public int getItemCount() {
        try {
            Statement st = db.createStatement();
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM items");
            int count = 0;
            if (rs.next())
                count = rs.getInt(1);
            rs.close();
            st.close();
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    private void broadcast(String msg) {
        // 同步打印到控制台
        plugin.getLogger().info(
                msg.replaceAll("§[0-9a-fk-orA-FK-OR]", ""));
        // 同步打印到聊天栏
        Bukkit.broadcastMessage(msg);
    }


    private ItemStack mk(Material mat,
                         String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(name);
            if (lore.length > 0) {
                List<String> list =
                        new ArrayList<>();
                for (String l : lore)
                    if (!l.isEmpty()) list.add(l);
                if (!list.isEmpty())
                    im.setLore(list);
            }
            it.setItemMeta(im);
        }
        return it;
    }

    public void close() {
        try {
            if (db != null && !db.isClosed())
                db.close();
        } catch (Exception ignored) {
        }
    }
}
