package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.io.File;

import java.util.*;

public class GUIManager {

    private final Main plugin;

    public static final String T_MAIN =
            "§6§l登录面板";
    public static final String T_MY_INFO =
            "§a§l我的信息";
    public static final String T_ADMIN =
            "§c§l管理员面板";
    public static final String T_USER_MGMT =
            "§e§l用户管理";
    public static final String T_INVITE =
            "§b§l邀请数据";
    public static final String T_TASK_CENTER = "§d§l任务中心";
    public static final String T_GIFT_STAGES = "§d§l新人任务";


    public GUIManager(Main plugin) {
        this.plugin = plugin;
    }

    public void openMain(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_MAIN);
        fillBg(g);
        g.setItem(10, mkItem(Material.PLAYER_HEAD,
                "§a§l我的信息"));
        g.setItem(12, mkItem(Material.PAPER,
                "§b§l邀请数据"));
        g.setItem(14, mkItem(Material.EMERALD,
                "§d§l积分商城"));
        g.setItem(16, mkItem(Material.CHEST,
                "§e§l新人礼包"));
        if (isAdmin(p)) {
            g.setItem(22, mkItem(Material.REDSTONE_BLOCK,
                    "§c§l管理员面板"));
        }
        p.openInventory(g);
    }

    public void openMyInfo(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_MY_INFO);
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
        g.setItem(26, mkItem(Material.ARROW, "§7返回"));
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
        g.setItem(12, mkItem(Material.COMPASS,
                "§b§l主线任务",
                "§7查看主线任务列表"));
        g.setItem(14, mkItem(Material.PAPER,
                "§a§l支线任务",
                "§7查看支线任务列表"));
        g.setItem(16, mkItem(Material.EMERALD,
                "§d§l每日签到",
                "§7签到获取积分"));
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }
    public void openGiftStages(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_GIFT_STAGES);
        fillBg(g);
        int max = plugin.getGift().getMaxStage();
        for (int i = 0; i < max && i < 9; i++) {
            int stage = i + 1;
            boolean claimed = plugin.getDb()
                    .getField(p.getName(),
                            "gift_claimed") != null
                    && ((String) plugin.getDb()
                    .getField(p.getName(),
                            "gift_claimed"))
                    .contains("[" + stage + "]");
            boolean can = plugin.getGift()
                    .canClaim(p, stage);
            Material mat = claimed ? Material.LIME_WOOL
                    : (can ? Material.YELLOW_WOOL
                       : Material.RED_WOOL);
            String status = claimed ? "§a已领取"
                    : (can ? "§e可领取" : "§c未达标");
            g.setItem(10 + i, mkItem(mat,
                    "§e第" + stage + "阶段",
                    "§7" + status));
        }
        g.setItem(26, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    public void openTaskList(Player p, String folderName) {
        File folder = new File(
                plugin.getDataFolder(), folderName);
        List<Map<String, Object>> tasks =
                plugin.getGift()
                        .loadTasksFromFolder(folder);
        int size = Math.max(54,
                ((tasks.size() / 9) + 1) * 9);
        if (size > 54) size = 54;
        Inventory g = Bukkit.createInventory(null, size,
                "§d§l" + folderName);
        fillBg(g);
        for (int i = 0; i < tasks.size()
                && i < 45; i++) {
            Map<String, Object> task = tasks.get(i);
            String name = (String) task.get("name");
            List<String> conds =
                    (List<String>) task.get("conditions");
            List<String> rewards =
                    (List<String>) task.get("rewards");
            List<String> lore = new ArrayList<>();
            lore.add("§7───── 条件 ─────");
            if (conds != null)
                for (String c : conds)
                    lore.add("§e" + c);
            lore.add("");
            lore.add("§7───── 奖励 ─────");
            if (rewards != null)
                for (String r2 : rewards)
                    lore.add("§a" + r2);
            g.setItem(i, mkItem(Material.BOOK,
                    "§e" + name,
                    lore.toArray(new String[0])));
        }
        if (tasks.isEmpty()) {
            g.setItem(22, mkItem(Material.BARRIER,
                    "§7暂无任务"));
        }
        g.setItem(size - 1,
                mkItem(Material.ARROW, "§7返回"));
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
        g.setItem(22, mkItem(Material.ARROW, "§7返回"));
        p.openInventory(g);
    }

    private boolean isAdmin(Player p) {
        return p.getScoreboardTags().contains(
                plugin.getConfig2().adminTag);
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
