package Sdf1_login;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;


public class GiftManager {

    private final File giftFolder;
    private final Main plugin;
    private final List<Map<String, Object>> stages =
            new ArrayList<>();

    public GiftManager(Main plugin) {
        this.plugin = plugin;
        this.giftFolder = new File(
                plugin.getDataFolder(), "新人礼包");
    }

    public void loadStages() {
        stages.clear();
        if (!giftFolder.exists()) {
            giftFolder.mkdirs();
            writeDefaultStages();
        }
        File[] files = giftFolder.listFiles(
                (dir, name) -> name.endsWith(".md"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            stages.add(parseMarkdown(f));
        }
    }

    private Map<String, Object> parseMarkdown(File f) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("stage", stages.size() + 1);
        stage.put("name",
                f.getName().replace(".md", ""));
        stage.put("rewards", new ArrayList<String>());
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            List<String> rewards = new ArrayList<>();
            while ((line = r.readLine()) != null) {
                if (line.contains("礼包内容")
                        || line.contains("奖励")
                        || line.contains("任务内容")) {
                    int colon = line.indexOf(':');
                    if (colon < 0)
                        colon = line.indexOf('\uff1a');
                    if (colon >= 0) {
                        rewards.add(
                                line.substring(colon + 1)
                                        .trim());
                    }
                }
            }
            stage.put("rewards", rewards);
            r.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return stage;
    }
    private void writeStage(String name, String content) {
        File f = new File(giftFolder, name);
        if (f.exists()) return;
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.print(content);
        } catch (IOException ignored) {}
    }

    private void writeDefaultStages() {
        writeStage("第一阶段.md",
                "# 第一阶段\n\n## 领取条件\n\n- 无前级条件\n\n## 礼包内容\n\n- 皮革套装 × 1\n- 木制武器工具套装 × 1\n");
        writeStage("第二阶段.md",
                "# 第二阶段\n\n## 领取条件\n\n- 在线时长>1小时\n\n## 礼包内容\n\n- 500 游戏币\n");
        writeStage("第三阶段.md",
                "# 第三阶段\n\n## 领取条件\n\n- 破坏100个方块\n- 在线>1天\n\n## 礼包内容\n\n- 1500 游戏币\n- 铁质工具套装 × 1\n- 铁质防具 × 1\n");
        writeStage("第四阶段.md",
                "# 第四阶段\n\n## 领取条件\n\n- 破坏/放置300方块\n- 在线≥7天\n\n## 礼包内容\n\n- 钻石套装 × 1\n- 2000 游戏币\n");
    }

    /** 从任意文件夹加载任务列表 */
    public List<Map<String, Object>> loadTasksFromFolder(
            File folder) {
        List<Map<String, Object>> tasks =
                new ArrayList<>();
        if (!folder.exists() || !folder.isDirectory())
            return tasks;
        File[] files = folder.listFiles(
                (dir, name) -> name.endsWith(".md"));
        if (files == null) return tasks;
        Arrays.sort(files);
        for (File f : files) {
            Map<String, Object> task =
                    new LinkedHashMap<>();
            task.put("name",
                    f.getName().replace(".md", ""));
            task.put("conditions",
                    new ArrayList<String>());
            task.put("rewards",
                    new ArrayList<String>());
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(f),
                                StandardCharsets.UTF_8));
                String line;
                List<String> current = null;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("# ")) {
                        task.put("name",
                                line.substring(2)
                                        .trim());
                    } else if (line.contains("条件")
                            || line.contains("领取条件")
                            || line.contains("完成条件")) {
                        current = (List<String>) task
                                .get("conditions");
                    } else if (line.contains("内容")
                            || line.contains("奖励")
                            || line.contains("任务内容")
                            || line.contains("礼包内容")) {
                        current = (List<String>) task
                                .get("rewards");
                    } else if (line.startsWith("- ")
                            && current != null) {
                        current.add(line.substring(2)
                                .trim());
                    }
                }
                r.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            tasks.add(task);
        }
        return tasks;
    }

    public boolean canClaim(Player p, int stage) {
        String name = p.getName();
        int curStage = ((Number) plugin.getDb()
                .getField(name, "gift_stage"))
                .intValue();
        String claimed = (String) plugin.getDb()
                .getField(name, "gift_claimed");
        if (claimed == null) claimed = "";
        return curStage >= stage
                && !claimed.contains(
                String.valueOf(stage));
    }


    public void claimReward(Player p, int stage) {
        if (!canClaim(p, stage)) return;
        Map<String, Object> user = plugin.getDb()
                .getUser(p.getName());
        String claimed = (String) user.getOrDefault(
                "gift_claimed", "");
        claimed += "[" + stage + "]";
        plugin.getDb().setField(
                p.getName(), "gift_claimed", claimed);
        plugin.getDb().setField(
                p.getName(), "gift_stage",
                Math.max(stage,
                        ((Number) user.getOrDefault(
                                "gift_stage", 0))
                                .intValue()));

        if (stage == 1) {
            giveItem(p, Material.LEATHER_HELMET);
            giveItem(p, Material.LEATHER_CHESTPLATE);
            giveItem(p, Material.LEATHER_LEGGINGS);
            giveItem(p, Material.LEATHER_BOOTS);
            giveItem(p, Material.WOODEN_SWORD);
            giveItem(p, Material.WOODEN_PICKAXE);
            giveItem(p, Material.WOODEN_AXE);
            giveItem(p, Material.WOODEN_SHOVEL);
        } else if (stage == 2) {
            if (plugin.getEconomy() != null) {
                double before = plugin.getEconomy()
                        .getBalance(p);
                plugin.getEconomy()
                        .depositPlayer(p, 500);
                double after = plugin.getEconomy()
                        .getBalance(p);
                p.sendMessage("§a§l[礼包] §f游戏币: §e"
                        + (long) before
                        + "§f → §a" + (long) after);
            }
        } else if (stage == 3) {
            if (plugin.getEconomy() != null) {
                double before = plugin.getEconomy()
                        .getBalance(p);
                plugin.getEconomy()
                        .depositPlayer(p, 1500);
                double after = plugin.getEconomy()
                        .getBalance(p);
                p.sendMessage("§a§l[礼包] §f游戏币: §e"
                        + (long) before
                        + "§f → §a" + (long) after);
            }
            giveItem(p, Material.IRON_PICKAXE);
            giveItem(p, Material.IRON_AXE);
            giveItem(p, Material.IRON_SWORD);
            giveItem(p, Material.IRON_HELMET);
            giveItem(p, Material.IRON_CHESTPLATE);
            giveItem(p, Material.IRON_LEGGINGS);
            giveItem(p, Material.IRON_BOOTS);
        } else if (stage == 4) {
            if (plugin.getEconomy() != null) {
                double before = plugin.getEconomy()
                        .getBalance(p);
                plugin.getEconomy()
                        .depositPlayer(p, 2000);
                double after = plugin.getEconomy()
                        .getBalance(p);
                p.sendMessage("§a§l[礼包] §f游戏币: §e"
                        + (long) before
                        + "§f → §a" + (long) after);
            }
            giveItem(p, Material.DIAMOND_HELMET);
            giveItem(p, Material.DIAMOND_CHESTPLATE);
            giveItem(p, Material.DIAMOND_LEGGINGS);
            giveItem(p, Material.DIAMOND_BOOTS);
        }
        p.sendMessage(plugin.getConfig2().msg(
                "gift_claimed"));
    }

    private void giveItem(Player p, Material mat) {
        p.getInventory().addItem(new ItemStack(mat));
    }

    public List<Map<String, Object>> getStages() {
        return stages;
    }

    public int getMaxStage() {
        return stages.size();
    }
}
