package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TicketManager {

    private final Main plugin;

    public static final String T_TICKET_MAIN =
            "§6§l工单中心";
    public static final String T_TICKET_CREATE =
            "§e§l创建工单";
    public static final String T_TICKET_MY =
            "§b§l我的工单";
    public static final String T_TICKET_ADMIN =
            "§c§l工单管理";
    public static final String T_TICKET_DETAIL =
            "§a§l工单详情";

    public static final String TYPE_BUG =
            "bug反馈";
    public static final String TYPE_HELP =
            "求助";
    public static final String TYPE_REPORT =
            "举报";
    public static final String TYPE_APPLY =
            "申请";
    public static final String TYPE_OTHER =
            "其他";
    public static final String TYPE_DELETE =
            "删号";
    public static final String TYPE_PASSWORD =
            "修改密码";



    private static final Map<String, Integer>
            PRICES = new LinkedHashMap<>();
    static {
        PRICES.put(TYPE_BUG, 10);
        PRICES.put(TYPE_HELP, 5);
        PRICES.put(TYPE_REPORT, 8);
        PRICES.put(TYPE_APPLY, 15);
        PRICES.put(TYPE_OTHER, 5);
        PRICES.put(TYPE_DELETE, 0);
        PRICES.put(TYPE_PASSWORD, 0);
    }


    public TicketManager(Main plugin) {
        this.plugin = plugin;
    }

    // ========== GUI ==========

    public void openMain(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, T_TICKET_MAIN);
        fillBg(g);
        g.setItem(10, mk(Material.PAPER,
                "§e创建工单",
                "§7提交bug、求助、举报等"));
        g.setItem(12, mk(Material.BOOK,
                "§b我的工单",
                "§7查看已提交的工单"));
        g.setItem(14, mk(Material.CHEST,
                "§6工单记录",
                "§7查看历史工单"));
        if (adm(p)) {
            g.setItem(16, mk(Material.REDSTONE,
                    "§c工单管理",
                    "§7管理员专用"));
        }
        if (plugin.getDb()
                .isServiceProvider(
                        p.getName())) {
            g.setItem(18, mk(Material.EMERALD,
                    "§a服务商面板",
                    "§7接单、处理工单"));
        }
        g.setItem(22, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openCreate(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 36, T_TICKET_CREATE);
        fillBg(g);
        g.setItem(10, mk(Material.BOOK,
                "§cbug反馈"));
        g.setItem(11, mk(Material.EMERALD,
                "§a求助"));
        g.setItem(12, mk(Material.BLAZE_POWDER,
                "§6举报"));
        g.setItem(13, mk(Material.NAME_TAG,
                "§e申请"));
        g.setItem(14, mk(Material.PAPER,
                "§7其他"));
        g.setItem(20, mk(Material.BARRIER,
                "§c申请删号",
                "§7申请删除账号"));
        g.setItem(21, mk(Material.NETHER_WART,
                "§d申请改密",
                "§7请求管理员协助改密"));
        g.setItem(31, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openMyTickets(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, T_TICKET_MY);
        fillBg(g);
        List<Map<String, Object>> list =
                plugin.getDb()
                        .getTicketsByPlayer(
                                p.getName());
        fillList(g, list);
        g.setItem(49, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openDetail(Player p, int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        Inventory g = Bukkit.createInventory(
                null, 27, T_TICKET_DETAIL);
        fillBg(g);

        String type = sv(t, "type");
        String status = sv(t, "status");
        String requester = sv(t, "requester");
        String title = sv(t, "title");
        String desc = sv(t, "description");
        String assigned = sv(t, "assigned_to");
        int reward = nv(t, "reward_amount");
        int score = nv(t, "score");
        int confirmed = nv(t, "admin_confirmed");

        g.setItem(10, mk(Material.PAPER,
                "§e§l" + (title.isEmpty()
                        ? type : title),
                "§7类型: §f" + type,
                "§7状态: " + stxt(status),
                "§7提交者: §f" + requester,
                "§7奖励: §e" + reward + " 积分"));

        g.setItem(12, mk(Material.BOOK,
                "§7详细描述",
                desc.isEmpty() ? "§7无描述"
                        : "§7" + (desc.length() > 40
                                  ? desc.substring(0, 40)
                                    + "..." : desc)));

        if (!assigned.isEmpty()) {
            g.setItem(14, mk(Material.PLAYER_HEAD,
                    "§7处理人: §f" + assigned,
                    confirmed == 1
                            ? "§7评分: §e" + score
                            : "§7处理中"));
        } else {
            g.setItem(14, mk(Material.BARRIER,
                    "§7未分配"));
        }

        // 驳回原因
        if ("closed".equals(status)) {
            String reason = sv(t, "reject_reason");
            if (!reason.isEmpty()) {
                g.setItem(13, mk(Material.BARRIER,
                        "§c驳回原因",
                        "§7" + reason));
            }
        }

        // 回复按钮（非关闭/已完结状态）
        if (!"closed".equals(status)
                && !"withdrawn".equals(status)
                && !"resolved".equals(status)) {
            g.setItem(15, mk(
                    Material.WRITABLE_BOOK,
                    "§b回复工单",
                    "§7向对方发送消息"));
            g.setItem(16, mk(Material.BOOK,
                    "§7查看回复记录"));
        }

        // ===== 管理员按钮 =====
        if (adm(p)) {
            if ("pending".equals(status)) {
                // 待分配：可分配给服务商
                g.setItem(19, mk(Material.EMERALD,
                        "§a分配工单",
                        "§7指定服务商处理"));
                // 也可自己处理
                g.setItem(20, mk(Material.LIME_DYE,
                        "§a我来处理",
                        "§7管理员直接处理此工单"));
            }
            if ("processing".equals(status)
                    && !assigned.equals("admin")
                    && !assigned.isEmpty()) {
                // 服务商在处理：可取消指派收回
                g.setItem(19, mk(Material.BARRIER,
                        "§c取消指派",
                        "§7取消服务商指派，收回工单"));
                // 也可自己接手处理
                g.setItem(20, mk(Material.LIME_DYE,
                        "§a我来处理",
                        "§7管理员直接处理此工单"));
            }
            if ("processing".equals(status)
                    && assigned.equals("admin")) {
                // 管理员自己在处理：可标记完结
                g.setItem(19, mk(Material.LIME_DYE,
                        "§a标记完结",
                        "§7确认工单处理完成"));
            }
            if ("replied".equals(status)
                    && assigned.equals("admin")) {
                g.setItem(19, mk(Material.LIME_DYE,
                        "§a标记完结",
                        "§7确认工单处理完成"));
            }
            // 驳回（非已关闭/已完结状态）
            if (!"closed".equals(status)
                    && !"resolved".equals(status)
                    && !"withdrawn".equals(status)) {
                g.setItem(21, mk(Material.RED_DYE,
                        "§c驳回工单",
                        "§7驳回并关闭此工单"));
            }
        }

        // ===== 服务商按钮 =====
        if (plugin.getDb()
                .isServiceProvider(p.getName())
                && !TYPE_DELETE.equals(type)
                && !TYPE_PASSWORD.equals(type)) {
            if ("pending".equals(status)
                    && assigned.isEmpty()) {
                // 待分配无人：抢单
                g.setItem(19, mk(
                        Material.GOLD_INGOT,
                        "§6抢单",
                        "§7接取此工单"));
            }
            if (assigned.equals(p.getName())
                    && ("processing".equals(status)
                    || "replied".equals(status))) {
                // 我在处理：标记完结/上报异常
                g.setItem(19, mk(
                        Material.LIME_DYE,
                        "§a标记完结",
                        "§7上报处理完成"));
                g.setItem(20, mk(
                        Material.REDSTONE,
                        "§c上报异常",
                        "§7报告处理中的问题"));
            }
        }

        g.setItem(22, mk(Material.ARROW,
                "§7返回"));

        ItemStack info = g.getItem(10);
        if (info != null) {
            ItemMeta im = info.getItemMeta();
            if (im != null) {
                im.setCustomModelData(tid);
                info.setItemMeta(im);
            }
        }
        p.openInventory(g);
    }


    public void openAdminList(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, T_TICKET_ADMIN);
        fillBg(g);
        g.setItem(45, mk(Material.EMERALD,
                "§a邀请服务商"));
        g.setItem(46, mk(Material.BARRIER,
                "§c移除服务商"));
        g.setItem(47, mk(Material.CLOCK,
                "§e服务商列表"));
        g.setItem(48, mk(Material.NETHER_STAR,
                "§e直接派单",
                "§7管理员直接创建并派单"));
        List<Map<String, Object>> all =
                new ArrayList<>();
        all.addAll(plugin.getDb()
                .getTicketsByStatus("pending"));
        all.addAll(plugin.getDb()
                .getTicketsByStatus("processing"));
        all.addAll(plugin.getDb()
                .getTicketsByStatus("replied"));
        all.addAll(plugin.getDb()
                .getTicketsByStatus("completed"));
        all.addAll(plugin.getDb()
                .getTicketsByStatus("resolved"));
        all.addAll(plugin.getDb()
                .getTicketsByStatus("closed"));
        fillList(g, all);
        g.setItem(49, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }


    public void openProviderPanel(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§d§l服务商面板");
        fillBg(g);
        g.setItem(10, mk(Material.CHEST,
                "§e抢单大厅",
                "§7查看可接工单"));
        g.setItem(12, mk(Material.CLOCK,
                "§b我的工单",
                "§7我正在处理的工单"));
        g.setItem(14, mk(Material.PAPER,
                "§6历史工单",
                "§7已完成的工单"));
        g.setItem(22, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openGrabList(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§6§l抢单大厅");
        fillBg(g);
        List<Map<String, Object>> pending =
                plugin.getDb()
                        .getTicketsByStatus(
                                "pending");
        List<Map<String, Object>> list =
                new ArrayList<>();
        for (Map<String, Object> t : pending) {
            if (sv(t, "assigned_to").isEmpty()) {
                list.add(t);
            }
        }
        fillList(g, list);
        g.setItem(49, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    public void openMyAssigned(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54,
                "§b§l我处理中的工单");
        fillBg(g);
        List<Map<String, Object>> list =
                plugin.getDb()
                        .getTicketsByProvider(
                                p.getName());
        fillList(g, list);
        g.setItem(49, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    // ========== 业务 ==========

    public int submitTicket(Player p,
                            String type, String title,
                            String desc) {
        int price = PRICES
                .getOrDefault(type, 5);
        int id = plugin.getDb().createTicket(
                type, "pending", "normal",
                p.getName(), title, desc,
                price, "");
        if (id > 0) {
            if (TYPE_DELETE.equals(type)
                    || TYPE_PASSWORD.equals(type)) {
                plugin.getDb().updateTicketField(
                        id, "assigned_to", "admin");
                plugin.getDb().updateTicketStatus(
                        id, "processing");
                p.sendMessage(
                        "§a§l[工单] §f工单 #"
                                + id + " 已提交，"
                                + "等待管理员处理");
            } else {
                p.sendMessage(
                        "§a§l[工单] §f创建成功！"
                                + " #§e" + id
                                + " 奖励: §e"
                                + price + "积分");
            }
            notifyAdmins(p.getName(), id);
        } else {
            p.sendMessage("§c创建失败");
        }
        return id;
    }


    public void adminCreateAndAssign(
            Player p, String type,
            String title, String desc,
            String provider, int price) {
        if (!plugin.getDb()
                .isServiceProvider(provider)) {
            p.sendMessage("§c" + provider
                    + " 不是服务商");
            return;
        }
        int id = plugin.getDb().createTicket(
                type, "processing", "normal",
                p.getName(), title, desc,
                price, "");
        if (id <= 0) {
            p.sendMessage("§c创建失败");
            return;
        }
        plugin.getDb().assignTicket(
                id, provider);
        if (price > 0) {
            plugin.getDb().updateTicketField(
                    id, "reward_amount", price);
        }
        p.sendMessage("§a工单 #" + id
                + " 已创建并分配给 " + provider);
        Player tp = Bukkit.getPlayer(provider);
        if (tp != null && tp.isOnline()) {
            tp.sendMessage(
                    "§e§l[工单] §f管理员指派工单 #"
                            + id + " 给您");
            playAssignedSound(tp);
        }
    }

    public void grabTicket(Player p, int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        if (!"pending".equals(
                sv(t, "status"))) {
            p.sendMessage("§c该工单已被接单");
            return;
        }
        if (!sv(t, "assigned_to").isEmpty()) {
            p.sendMessage("§c该工单已被分配");
            return;
        }
        plugin.getDb().assignTicket(
                tid, p.getName());
        p.sendMessage("§a抢单成功！工单 #" + tid);
        Player rp = Bukkit.getPlayer(
                sv(t, "requester"));
        if (rp != null && rp.isOnline()) {
            rp.sendMessage(
                    "§e§l[工单] §f您的工单 #"
                            + tid + " 已被 "
                            + p.getName()
                            + " 接单");
        }
    }

    public void assignToProvider(Player p,
                                 int tid, String provider,
                                 int price) {
        if (!plugin.getDb()
                .isServiceProvider(provider)) {
            p.sendMessage("§c" + provider
                    + " 不是服务商");
            return;
        }
        plugin.getDb().assignTicket(
                tid, provider);
        if (price > 0) {
            plugin.getDb().updateTicketStatus(
                    tid, "processing");
            plugin.getDb().updateTicketField(
                    tid, "reward_amount", price);
        }
        p.sendMessage("§a工单 #" + tid
                + " 已分配给 " + provider);
        Player tp = Bukkit.getPlayer(provider);
        if (tp != null && tp.isOnline()) {
            tp.sendMessage(
                    "§e§l[工单] §f您有新工单: #"
                            + tid);
            playAssignedSound(tp);
        }
    }

    public void confirmAndScore(Player p,
                                int tid, int score) {
        if (score < 1 || score > 5) {
            p.sendMessage("§c评分1-5");
            return;
        }
        plugin.getDb().confirmTicket(
                tid, score);
        p.sendMessage("§a工单 #" + tid
                + " 已确认，评分: " + score);
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t != null) {
            Player rp = Bukkit.getPlayer(
                    sv(t, "requester"));
            if (rp != null && rp.isOnline()) {
                rp.sendMessage(
                        "§a§l[工单] §f工单 #"
                                + tid
                                + " 已确认完结");
            }
        }
        settleTicket(tid);
    }

    public void completeAsProvider(Player p,
                                   int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        if (!sv(t, "assigned_to")
                .equals(p.getName())) {
            p.sendMessage("§c这不是你的工单");
            return;
        }
        plugin.getDb().completeTicket(tid);
        p.sendMessage("§a工单 #" + tid
                + " 已标记完结，等待确认");
        Player rp = Bukkit.getPlayer(
                sv(t, "requester"));
        if (rp != null && rp.isOnline()) {
            rp.sendMessage(
                    "§a§l[工单] §f工单 #"
                            + tid
                            + " 已处理完成");
        }
    }

    public void rejectTicket(Player p,
                             int tid, String reason) {
        plugin.getDb().updateTicketStatus(
                tid, "closed");
        plugin.getDb().updateTicketField(
                tid, "reject_reason", reason);
        p.sendMessage("§c工单 #" + tid
                + " 已驳回并关闭");
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t != null) {
            Player rp = Bukkit.getPlayer(
                    sv(t, "requester"));
            if (rp != null && rp.isOnline()) {
                rp.sendMessage(
                        "§c§l[工单] §f工单 #"
                                + tid
                                + " 被驳回: "
                                + reason);
            }
        }
    }

    public void replyTicket(Player p,
                            int tid, String message) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        String status = sv(t, "status");
        if ("closed".equals(status)
                || "withdrawn".equals(status)
                || "resolved".equals(status)) {
            p.sendMessage("§c该工单已关闭");
            return;
        }
        plugin.getDb().addTicketReply(
                tid, p.getName(), message);
        p.sendMessage("§a回复成功");
        String requester =
                sv(t, "requester");
        String assigned =
                sv(t, "assigned_to");
        String sender = p.getName();
        if (!requester.equals(sender)) {
            Player rp = Bukkit.getPlayer(
                    requester);
            if (rp != null && rp.isOnline()) {
                rp.sendMessage(
                        "§e§l[工单] §f工单 #"
                                + tid + " 新回复");
            }
        }
        if (!assigned.isEmpty()
                && !assigned.equals(sender)) {
            Player ap = Bukkit.getPlayer(
                    assigned);
            if (ap != null && ap.isOnline()) {
                ap.sendMessage(
                        "§e§l[工单] §f工单 #"
                                + tid + " 新回复");
            }
        }
    }

    public void showReplies(Player p,
                            int tid) {
        List<Map<String, Object>> replies =
                plugin.getDb()
                        .getTicketReplies(tid);
        if (replies.isEmpty()) {
            p.sendMessage("§7暂无回复");
            return;
        }
        p.sendMessage("§e§l=== 回复记录 ===");
        SimpleDateFormat sdf =
                new SimpleDateFormat(
                        "MM-dd HH:mm");
        for (Map<String, Object> r : replies) {
            String sender = sv(r, "sender");
            String msg2 = sv(r, "message");
            long time = ((Number) r.get(
                    "create_time")).longValue();
            p.sendMessage("§7[" + sdf.format(
                    new Date(time)) + "] §f"
                    + sender + "§7: " + msg2);
        }
        p.sendMessage(
                "§e§l=================");
    }

    public void withdrawTicket(Player p,
                               int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        if (!sv(t, "requester")
                .equals(p.getName())
                && !adm(p)) {
            p.sendMessage("§c无权操作");
            return;
        }
        plugin.getDb().updateTicketStatus(
                tid, "withdrawn");
        p.sendMessage("§a工单 #" + tid
                + " 已撤回");
    }

    public void showProviderList(Player p) {
        List<Map<String, Object>> list =
                plugin.getDb()
                        .getAllServiceProviders();
        if (list.isEmpty()) {
            p.sendMessage("§7暂无服务商");
            return;
        }
        p.sendMessage(
                "§e§l=== 服务商列表 ===");
        for (Map<String, Object> pro : list) {
            p.sendMessage("§7- §f"
                    + pro.get("player_name")
                    + " §7角色: §e"
                    + pro.get("role"));
        }
        p.sendMessage(
                "§e§l===================");
    }

    public void autoProcessCompleted() {
        long timeoutMs = 10 * 60 * 1000L;

        // 自动处理删号工单（5分钟自动通过）
        List<Map<String, Object>> deleteTickets =
                plugin.getDb()
                        .getTicketsByStatus(
                                "processing");
        for (Map<String, Object> t : deleteTickets) {
            String dtype = sv(t, "type");
            if (!TYPE_DELETE.equals(dtype)) continue;
            int did = nv(t, "id");
            int dconf = nv(t, "admin_confirmed");
            // 已经处理过了跳过
            if (dconf != 0) continue;
            String dasg = sv(t, "assigned_to");
            // 只处理分配给admin的
            if (!"admin".equals(dasg)) continue;
            long createTime = ((Number) t.get(
                    "create_time")).longValue();
            if (System.currentTimeMillis()
                    - createTime >= 5 * 60 * 1000L) {
                processDeleteAccount(did, t);
            }
        }


        // 已完结未确认→10分钟自动完结
        List<Map<String, Object>> list =
                plugin.getDb()
                        .getUnconfirmedCompleted(
                                timeoutMs);
        for (Map<String, Object> t : list) {
            int id = nv(t, "id");
            plugin.getDb().confirmTicket(id, 3);
            Player rp = Bukkit.getPlayer(
                    sv(t, "requester"));
            if (rp != null && rp.isOnline())
                rp.sendMessage(
                        "§e§l[工单] §f工单 #"
                                + id + " 已自动完结");
            String assigned =
                    sv(t, "assigned_to");
            if (!assigned.isEmpty()) {
                Player pp =
                        Bukkit.getPlayer(assigned);
                if (pp != null && pp.isOnline())
                    pp.sendMessage(
                            "§e§l[工单] §f工单 #"
                                    + id
                                    + " 已自动完结");
            }
            settleTicket(id);
        }

        // 已回复但10分钟无互动→自动完结
        List<Map<String, Object>> stale =
                plugin.getDb()
                        .getRepliedStaleTickets(
                                timeoutMs);
        for (Map<String, Object> t : stale) {
            int id = nv(t, "id");
            plugin.getDb().confirmTicket(id, 3);
            Player rp = Bukkit.getPlayer(
                    sv(t, "requester"));
            if (rp != null && rp.isOnline())
                rp.sendMessage(
                        "§e§l[工单] §f工单 #"
                                + id + " 超时自动完结");
            settleTicket(id);
        }
    }



    // ========== GUI点击 ==========
    public boolean handleClick(Player p,
                               String title, int slot) {

        if (title.equals(T_TICKET_MAIN)) {
            if (slot == 10) openCreate(p);
            else if (slot == 12)
                openMyTickets(p);
            else if (slot == 14)
                openMyTickets(p);
            else if (slot == 16) {
                if (adm(p)) openAdminList(p);
                else p.sendMessage("§c权限不足");
            }
            else if (slot == 18) {
                if (plugin.getDb()
                        .isServiceProvider(p.getName())) {
                    openProviderPanel(p);
                } else {
                    p.sendMessage("§c权限不足");
                }
            }
            else if (slot == 22)
                plugin.getGui().openMain(p);
            return true;
        }

        if (title.equals(T_TICKET_CREATE)) {
            if (slot == 31) {
                openMain(p);
                return true;
            }
            String type = null;
            if (slot == 10) type = TYPE_BUG;
            else if (slot == 11) type = TYPE_HELP;
            else if (slot == 12) type = TYPE_REPORT;
            else if (slot == 13) type = TYPE_APPLY;
            else if (slot == 14) type = TYPE_OTHER;
            else if (slot == 20) type = TYPE_DELETE;
            else if (slot == 21) type = TYPE_PASSWORD;
            if (type != null) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_TITLE;
                plugin.getChatInput()
                        .getState(p)
                        .ticketType = type;
                if (TYPE_DELETE.equals(type)) {
                    p.sendMessage("§e请描述删号原因:");
                } else if (TYPE_PASSWORD.equals(type)) {
                    p.sendMessage("§e请描述需要协助的内容:");
                } else {
                    p.sendMessage("§e请输入工单标题:");
                }
            }
            return true;
        }


        if (title.equals(T_TICKET_MY)) {
            if (slot == 49) {
                openMain(p);
                return true;
            }
            if (slot >= 0 && slot < 45) {
                ItemStack it = p.getOpenInventory()
                        .getTopInventory().getItem(slot);
                int id = gt(it);
                if (id > 0) openDetail(p, id);
            }
            return true;
        }

        if (title.equals(T_TICKET_DETAIL)) {
            return handleClickDetail(p, slot);
        }

        if (title.equals(T_TICKET_ADMIN)) {
            if (slot == 49) {
                openMain(p);
                return true;
            }
            if (slot == 45) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_INVITE_PROVIDER;
                p.sendMessage("§e输入服务商玩家名:");
                return true;
            }
            if (slot == 46) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_REMOVE_PROVIDER;
                p.sendMessage("§e输入要移除的服务商:");
                return true;
            }
            if (slot == 47) {
                showProviderList(p);
                return true;
            }
            if (slot == 48) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_ADMIN_TYPE;
                p.sendMessage("§e选择工单类型:");
                p.sendMessage("§7bug/求助/举报/申请/其他");
                return true;
            }
            if (slot >= 0 && slot < 45) {
                ItemStack it = p.getOpenInventory()
                        .getTopInventory().getItem(slot);
                int id = gt(it);
                if (id > 0) openDetail(p, id);
            }
            return true;
        }

        if (title.equals("§d§l服务商面板")) {
            if (slot == 22) {
                plugin.getGui().openMain(p);
                return true;
            }
            if (slot == 10) openGrabList(p);
            else if (slot == 12) openMyAssigned(p);
            else if (slot == 14) openMyTickets(p);
            return true;
        }

        if (title.equals("§6§l抢单大厅")) {
            if (slot == 49) {
                openProviderPanel(p);
                return true;
            }
            if (slot >= 0 && slot < 45) {
                ItemStack it = p.getOpenInventory()
                        .getTopInventory().getItem(slot);
                int id = gt(it);
                if (id > 0) openDetail(p, id);
            }
            return true;
        }

        if (title.equals("§b§l我处理中的工单")) {
            if (slot == 49) {
                openProviderPanel(p);
                return true;
            }
            if (slot >= 0 && slot < 45) {
                ItemStack it = p.getOpenInventory()
                        .getTopInventory().getItem(slot);
                int id = gt(it);
                if (id > 0) openDetail(p, id);
            }
            return true;
        }

        return false;
    }

    private boolean handleClickDetail(Player p,
                                      int slot) {
        if (slot == 22) {
            openMyTickets(p);
            return true;
        }
        int id = gtFromDetail(p);
        if (id <= 0) return true;
        Map<String, Object> t =
                plugin.getDb().getTicket(id);
        if (t == null) return true;

        String st = sv(t, "status");
        int conf = nv(t, "admin_confirmed");
        String asg = sv(t, "assigned_to");

        // 回复按钮
        if (slot == 15) {
            if (!"closed".equals(st)
                    && !"withdrawn".equals(st)
                    && !"resolved".equals(st)) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_REPLY;
                plugin.getChatInput()
                        .getState(p).ticketId = id;
                p.sendMessage("§e输入回复内容:");
            }
            return true;
        }

        // 查看回复记录
        if (slot == 16) {
            showReplies(p, id);
            return true;
        }

        // slot 19
        if (slot == 19) {
            // 管理员：分配工单
            if (adm(p) && "pending".equals(st)
                    && asg.isEmpty()) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_ASSIGN;
                plugin.getChatInput()
                        .getState(p).ticketId = id;
                p.sendMessage(
                        "§e输入: 服务商名 价格");
                return true;
            }
            // 管理员：取消指派（服务商在处理中）
            if (adm(p) && "processing".equals(st)
                    && !asg.equals("admin")
                    && !asg.isEmpty()) {
                cancelTicket(p, id);
                openDetail(p, id);
                return true;
            }
            // 管理员：自己处理（pending）
            if (adm(p) && "pending".equals(st)
                    && asg.isEmpty()) {
                plugin.getDb().assignTicket(
                        id, "admin");
                plugin.getDb().updateTicketStatus(
                        id, "processing");
                p.sendMessage("§a工单 #" + id
                        + " 已由您接手处理");
                openDetail(p, id);
                return true;
            }
            // 管理员：标记完结（已回复状态）
            if (adm(p) && "replied".equals(st)
                    && asg.equals("admin")) {

                // 管理员：标记完结（已回复状态）
                if (adm(p) && "replied".equals(st)
                        && asg.equals("admin")) {
                    Map<String, Object> tk =
                            plugin.getDb().getTicket(id);
                    String tkType = sv(tk, "type");
                    if (TYPE_DELETE.equals(tkType)) {
                        approveDeleteTicket(p, id);
                        return true;
                    }
                    plugin.getDb().completeTicket(id);
                    p.sendMessage("§a工单 #" + id
                            + " 已标记完结");
                    openDetail(p, id);
                    return true;
                }
            }
            // 服务商：抢单
            if (plugin.getDb()
                    .isServiceProvider(p.getName())
                    && "pending".equals(st)
                    && asg.isEmpty()) {
                grabTicket(p, id);
                openDetail(p, id);
                return true;
            }
            // 服务商：标记完结
            if (plugin.getDb()
                    .isServiceProvider(p.getName())
                    && asg.equals(p.getName())
                    && ("processing".equals(st)
                    || "replied".equals(st))) {
                completeAsProvider(p, id);
                openDetail(p, id);
                return true;
            }
            return true;
        }

        // slot 20
        if (slot == 20) {
            // 管理员：自己处理（pending）
            if (adm(p) && "pending".equals(st)
                    && asg.isEmpty()) {
                plugin.getDb().assignTicket(
                        id, "admin");
                plugin.getDb().updateTicketStatus(
                        id, "processing");
                p.sendMessage("§a工单 #" + id
                        + " 已由您接手处理");
                openDetail(p, id);
                return true;
            }
            // 管理员：自己接手（服务商在处理中）
            if (adm(p) && "processing".equals(st)
                    && !asg.equals("admin")
                    && !asg.isEmpty()) {
                plugin.getDb().assignTicket(
                        id, "admin");
                p.sendMessage("§a工单 #" + id
                        + " 已由您接手处理");
                openDetail(p, id);
                return true;
            }
            // 服务商：上报异常
            if (plugin.getDb()
                    .isServiceProvider(p.getName())
                    && asg.equals(p.getName())
                    && ("processing".equals(st)
                    || "replied".equals(st))) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_REPORT_ISSUE;
                plugin.getChatInput()
                        .getState(p).ticketId = id;
                p.sendMessage("§e输入异常描述:");
                return true;
            }
            return true;
        }

        // slot 21：驳回
        if (slot == 21) {
            if (adm(p) && !"closed".equals(st)
                    && !"resolved".equals(st)
                    && !"withdrawn".equals(st)) {
                p.closeInventory();
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_REJECT;
                plugin.getChatInput()
                        .getState(p).ticketId = id;
                p.sendMessage("§e输入驳回原因:");
            }
            return true;
        }

        return true;
    }

    // ========== 聊天输入 ==========

    public boolean handleChatInput(Player p,
                                   String msg,
                                   ChatInputManager.InputType type) {
        switch (type) {
            case TICKET_TITLE: {
                plugin.getChatInput()
                        .getState(p)
                        .ticketTitle = msg;
                plugin.getChatInput()
                        .getState(p).type =
                        ChatInputManager.InputType
                                .TICKET_DESC;
                p.sendMessage(
                        "§e请输入工单详细描述:");
                return true;
            }
            case TICKET_DESC: {
                String tt = plugin.getChatInput()
                        .getState(p).ticketType;
                String tl = plugin.getChatInput()
                        .getState(p).ticketTitle;
                plugin.getChatInput().reset(p);
                submitTicket(p, tt, tl, msg);
                return true;
            }
            case TICKET_SCORE: {
                int tid2 = plugin.getChatInput()
                        .getState(p).ticketId;
                try {
                    int sc = Integer.parseInt(
                            msg.trim());
                    Map<String, Object> tk =
                            plugin.getDb()
                                    .getTicket(tid2);
                    String st = tk != null
                            ? sv(tk, "status")
                            : "";
                    if ("completed".equals(st)) {
                        plugin.getChatInput()
                                .reset(p);
                        confirmAndScore(
                                p, tid2, sc);
                    } else {
                        plugin.getChatInput()
                                .reset(p);
                        p.sendMessage(
                                "§c工单尚未处理完成");
                    }
                } catch (
                        NumberFormatException e) {
                    plugin.getChatInput()
                            .reset(p);
                    p.sendMessage(
                            "§c请输入数字1-5");
                }
                return true;
            }
            case TICKET_REJECT: {
                int tid2 = plugin.getChatInput()
                        .getState(p).ticketId;
                plugin.getChatInput().reset(p);
                rejectTicket(p, tid2, msg);
                return true;
            }
            case TICKET_ASSIGN: {
                int tid2 = plugin.getChatInput()
                        .getState(p).ticketId;
                String[] parts = msg.trim()
                        .split("\\s+");
                String prov = parts[0];
                int price = 0;
                if (parts.length >= 2) {
                    try {
                        price = Integer.parseInt(
                                parts[1]);
                    } catch (
                            NumberFormatException e) {
                        p.sendMessage(
                                "§c价格格式错误");
                    }
                }
                plugin.getChatInput().reset(p);
                assignToProvider(p, tid2,
                        prov, price);
                runSync(() ->
                        openDetail(p, tid2));
                return true;
            }
            case TICKET_INVITE_PROVIDER: {
                String tgt = msg.trim();
                plugin.getChatInput().reset(p);
                if (!plugin.getDb()
                        .userExists(tgt)) {
                    p.sendMessage(
                            "§c玩家不存在");
                    return true;
                }
                plugin.getDb()
                        .addServiceProvider(
                                tgt, "waiter");
                p.sendMessage("§a已邀请 " + tgt
                        + " 成为服务商");
                return true;
            }
            case TICKET_REMOVE_PROVIDER: {
                String tgt = msg.trim();
                plugin.getChatInput().reset(p);
                if (!plugin.getDb()
                        .isServiceProvider(
                                tgt)) {
                    p.sendMessage("§c" + tgt
                            + " 不是服务商");
                    return true;
                }
                plugin.getDb()
                        .removeServiceProvider(
                                tgt);
                p.sendMessage(
                        "§a已移除 " + tgt);
                return true;
            }
            default:
                return false;
        }
    }

    // ========== 私有 ==========

    private void settleTicket(int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) return;
        String requester =
                sv(t, "requester");
        String assigned =
                sv(t, "assigned_to");
        int reward = nv(t, "reward_amount");
        int score = nv(t, "score");
        ConfigManager cfg =
                plugin.getConfig2();
        if (reward > 0) {
            plugin.getDb().addPoints(
                    requester, reward);
            Player rp = Bukkit.getPlayer(
                    requester);
            if (rp != null && rp.isOnline()) {
                rp.sendMessage(
                        "§e§l[工单] §f获得 "
                                + reward
                                + " 积分奖励");
            }
        }
        if (!assigned.isEmpty()) {
            int pts = (int) (score
                    * cfg.providerPointsPerScore);
            if (pts > 0) {
                plugin.getDb().addPoints(
                        assigned, pts);
            }
            double money =
                    cfg.baseEconomyReward
                            + score
                            * cfg.providerEconomyPerScore;
            boolean ok = false;
            try {
                if (plugin.getEconomy() != null) {
                    OfflinePlayer op =
                            Bukkit.getOfflinePlayer(
                                    assigned);
                    plugin.getEconomy()
                            .depositPlayer(
                                    op, money);
                    ok = true;
                }
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[Sdf1_login] 经济失败: "
                                + e.getMessage());
            }
            Player pp =
                    Bukkit.getPlayer(assigned);
            if (pp != null && pp.isOnline()) {
                pp.sendMessage(
                        "§a§l[工单] §f工单 #"
                                + tid
                                + " 确认完结");
                if (pts > 0)
                    pp.sendMessage("§7积分: §e"
                            + pts);
                if (ok)
                    pp.sendMessage("§7经济: §a$"
                            + String.format(
                            "%.0f", money));
            }
        }
    }

    private void notifyAdmins(String creator,
                              int tid) {
        String tag =
                plugin.getConfig2().adminTag;
        for (Player op :
                Bukkit.getOnlinePlayers()) {
            if (op.getScoreboardTags()
                    .contains(tag)) {
                op.sendMessage(
                        "§e§l[工单] §f" + creator
                                + " 提交了新工单 #"
                                + tid);
            }
        }
    }

    private void playAssignedSound(Player p) {
        for (int i = 0; i < 3; i++) {
            final int d = i * 15;
            Bukkit.getScheduler()
                    .runTaskLater(plugin, () -> {
                        if (p.isOnline()) {
                            p.playSound(p.getLocation(),
                                    Sound
                                            .BLOCK_NOTE_BLOCK_CHIME,
                                    1.0f, 0.79f);
                            p.playSound(p.getLocation(),
                                    Sound
                                            .BLOCK_NOTE_BLOCK_BASS,
                                    1.0f, 0.79f);
                            p.playSound(p.getLocation(),
                                    Sound
                                            .BLOCK_NOTE_BLOCK_PLING,
                                    1.0f, 0.79f);
                        }
                    }, d);
        }
    }

    private void runSync(Runnable task) {
        Bukkit.getScheduler()
                .runTask(plugin, task);
    }

    private boolean adm(Player p) {
        return p.getScoreboardTags().contains(
                plugin.getConfig2().adminTag);
    }

    private void fillBg(Inventory g) {
        ItemStack glass = mk(
                Material.GRAY_STAINED_GLASS_PANE,
                " ");
        for (int i = 0; i < g.getSize(); i++) {
            if (g.getItem(i) == null
                    || g.getItem(i).getType()
                    == Material.AIR) {
                g.setItem(i, glass);
            }
        }
    }

    private void fillList(Inventory g,
                          List<Map<String, Object>> list) {
        if (list.isEmpty()) {
            g.setItem(22, mk(Material.BARRIER,
                    "§7暂无工单"));
            return;
        }
        int slot = 0;
        for (Map<String, Object> t : list) {
            if (slot >= 45) break;
            g.setItem(slot, makeItem(t));
            slot++;
        }
    }
    /**
     * 执行删号流程
     */
    /**
     * 执行删号流程
     */
    public void processDeleteAccount(int tid,
                                     Map<String, Object> t) {
        String requester = sv(t, "requester");
        plugin.getDb().updateTicketStatus(
                tid, "resolved");
        plugin.getDb().updateTicketField(tid,
                "admin_confirmed", 1);
        Player target =
                Bukkit.getPlayer(requester);
        if (target != null && target.isOnline()) {
            plugin.backupInventory(target);
            target.sendMessage(
                    "§a§l[工单] §f您的删号请求已通过");
            target.sendMessage(
                    "§7物品已备份，下次登录可取回");
            target.kickPlayer(
                    "§a删号成功，物品已备份。\n"
                            + "请等待5分钟后重新注册取回物品。");
        } else {
            // 离线：仅标记，不操作数据库
            plugin.getLogger().info(
                    "[Sdf1_login] " + requester
                            + " 删号工单已通过，等待上线执行");
        }
        // 延迟执行删号
        final String name = requester;
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> {
                    plugin.getDb().deleteUser(name);
                    plugin.getLogger().info(
                            "[Sdf1_login] " + name
                                    + " 删号工单已执行");
                }, 100L);
    }

    /**
     * 管理员手动批准删号
     */
    public void approveDeleteTicket(Player p,
                                    int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        if (!TYPE_DELETE.equals(sv(t, "type"))) {
            p.sendMessage("§c这不是删号工单");
            return;
        }
        p.sendMessage("§a工单 #" + tid
                + " 已批准，正在执行删号...");
        processDeleteAccount(tid, t);
    }

    /**
     * 管理员取消工单（服务商异常后）
     */
    /**
     * 管理员取消工单（解除服务商绑定，管理员可自行处理）
     */
    public void cancelTicket(Player p, int tid) {
        Map<String, Object> t =
                plugin.getDb().getTicket(tid);
        if (t == null) {
            p.sendMessage("§c工单不存在");
            return;
        }
        // 解除服务商绑定，状态回退到pending
        plugin.getDb().updateTicketField(
                tid, "assigned_to", "");
        plugin.getDb().updateTicketStatus(
                tid, "pending");
        p.sendMessage("§a工单 #" + tid
                + " 已取消指派，回到待分配状态");
        // 通知原服务商
        String assigned = sv(t, "assigned_to");
        if (!assigned.isEmpty()) {
            Player ap = Bukkit.getPlayer(assigned);
            if (ap != null && ap.isOnline()) {
                ap.sendMessage(
                        "§c§l[工单] §f工单 #"
                                + tid
                                + " 已被管理员取消指派");
            }
        }
    }


    /**
     * 服务商查看历史工单
     */
    public void openProviderHistory(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54,
                "§6§l我的历史工单");
        fillBg(g);
        List<Map<String, Object>> all =
                plugin.getDb()
                        .getTicketsByProviderAll(
                                p.getName());
        fillList(g, all);
        g.setItem(49, mk(Material.ARROW,
                "§7返回"));
        p.openInventory(g);
    }

    private ItemStack makeItem(
            Map<String, Object> t) {
        int id = nv(t, "id");
        String type = sv(t, "type");
        String status = sv(t, "status");
        String requester =
                sv(t, "requester");
        String title = sv(t, "title");
        int reward = nv(t, "reward_amount");
        Material mat;
        switch (status) {
            case "processing":
                mat = Material.YELLOW_DYE;
                break;
            case "replied":
                mat = Material.CYAN_DYE;
                break;
            case "completed":
                mat = Material.LIME_DYE;
                break;
            case "resolved":
                mat = Material.LIME_DYE;
                break;
            case "closed":
                mat = Material.GRAY_DYE;
                break;
            case "rejected":
                mat = Material.RED_DYE;
                break;
            case "withdrawn":
                mat = Material.GRAY_DYE;
                break;
            default:
                mat = Material.ORANGE_DYE;
                break;
        }
        ItemStack item = new ItemStack(mat);
        ItemMeta im = item.getItemMeta();
        if (im != null) {
            im.setDisplayName("§e#" + id
                    + " " + (title.isEmpty()
                    ? type : title));
            List<String> lore =
                    new ArrayList<>();
            lore.add("§7类型: §f" + type);
            lore.add("§7状态: "
                    + stxt(status));
            lore.add("§7提交者: §f"
                    + requester);
            lore.add("§7奖励: §e" + reward
                    + " 积分");
            lore.add("");
            lore.add("§7点击查看详情");
            im.setLore(lore);
            im.setCustomModelData(id);
            item.setItemMeta(im);
        }
        return item;
    }

    private int gt(ItemStack item) {
        if (item == null) return -1;
        ItemMeta im = item.getItemMeta();
        if (im == null) return -1;
        if (im.hasCustomModelData())
            return im.getCustomModelData();
        return -1;
    }

    private int gtFromDetail(Player p) {
        ItemStack it = p.getOpenInventory()
                .getTopInventory().getItem(10);
        return gt(it);
    }

    private String stxt(String status) {
        switch (status) {
            case "pending":
                return "§e待处理";
            case "processing":
                return "§b处理中";
            case "replied":
                return "§9已回复";
            case "completed":
                return "§a待确认";
            case "resolved":
                return "§a已完结";
            case "closed":
                return "§7已关闭";
            case "rejected":
                return "§c已驳回";
            case "withdrawn":
                return "§7已撤回";
            default:
                return "§7" + status;
        }
    }

    private String sv(Map<String, Object> m,
                      String key) {
        Object v = m.get(key);
        return v != null
                ? String.valueOf(v) : "";
    }

    private int nv(Map<String, Object> m,
                   String key) {
        Object v = m.get(key);
        return v instanceof Number
                ? ((Number) v).intValue() : 0;
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
                for (String l : lore) {
                    if (!l.isEmpty())
                        list.add(l);
                }
                if (!list.isEmpty())
                    im.setLore(list);
            }
            it.setItemMeta(im);
        }
        return it;
    }
}
