package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

public class OrderManager implements Listener {
    private final Main plugin;
    private final List<OrderRecord> orders =
            Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, Integer> userViewPage = new HashMap<>();
    private final Map<UUID, Integer> adminViewPage = new HashMap<>();
    private final Map<UUID, UUID> adminViewTarget = new HashMap<>();
    private final Set<UUID> partialRefundListening = new HashSet<>();
    private final Map<UUID, OrderRecord> partialRefundTarget = new HashMap<>();
    private final Map<UUID, String> receiptBuilderSection = new HashMap<>();
    private String tplHeader = "";
    private String tplItemLine = "{品名} x{数量}  {原价}→{执行价}  小计{小计}枚";
    private String tplDiscountLine = "优惠: -{优惠额}枚 ({优惠类型})";
    private String tplPackLine = "打包: +{打包费}枚 ({打包颜色})";
    private String tplSummary = "原价{原价合计} → 实付{实付}";
    private String tplFooter = "感谢惠顾，欢迎再来！";
    private static final long RETENTION_30D = 30L * 24 * 60 * 60 * 1000;
    private static final long SELF_REFUND_WINDOW = 5 * 60 * 1000;
    private static final int PAGE_SIZE = 21;
    private static final int[] SLOTS = {
            10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    // ===== 字段 =====
    private final Set<Long> shulkerUsedOrders =
            Collections.synchronizedSet(new HashSet<>());
    // ===== 待审批的退货退款 =====
    private final Map<Long, PendingReturn> pendingReturns =
            new HashMap<>();
    // ===== 退货申请确认 =====
    private final Map<UUID, Long> returnConfirmWaiting =
            new HashMap<>(); // uuid -> orderId
    private static final int RETURN_CONFIRM_TIMEOUT = 600; // 30秒


    private static class PendingReturn {
        long orderId;
        UUID playerUUID;
        String playerName;
        int totalPaid;
        long createTime;
        String packColor;
        String packType;
        List<OrderItem> items;

        PendingReturn(OrderRecord r) {
            this.orderId = r.orderId;
            this.playerUUID = r.uuid;
            this.playerName = r.player;
            this.totalPaid = r.totalPaid;
            this.createTime = System.currentTimeMillis();
            this.packColor = r.packColor;
            this.packType = r.packType;
            this.items = new ArrayList<>(r.items);
        }
    }


    public static class OrderItem {
        public String name; public String mat;
        public int originalPrice; public int finalPrice; public int qty;
        public String nbt; // 物品NBT快照(Base64 of ItemStack.serializeAsBytes)，退款时用于精确匹配含NBT商品；null/空=无NBT或旧数据
        public OrderItem(String n,String m,int op,int fp,int q){
            name=n;mat=m;originalPrice=op;finalPrice=fp;qty=q;nbt=null;}
        public OrderItem(String n,String m,int op,int fp,int q,String nbtB64){
            name=n;mat=m;originalPrice=op;finalPrice=fp;qty=q;nbt=nbtB64;}
        public int subtotal(){return finalPrice*qty;}
        public int originalSubtotal(){return originalPrice*qty;}
        /** 还原下单时的真实物品（含NBT），用于退款时精确匹配；无快照或还原失败返回null */
        public org.bukkit.inventory.ItemStack toTemplateStack() {
            if (nbt == null || nbt.isEmpty()) return null;
            try {
                byte[] b = java.util.Base64.getDecoder().decode(nbt);
                return org.bukkit.inventory.ItemStack.deserializeBytes(b);
            } catch (Exception e) { return null; }
        }
    }
    public static class OrderRecord {
        public long orderId; public UUID uuid; public String player;
        public List<OrderItem> items; public int totalOriginal; public int totalPaid;
        public int discount; public String discountType; public String couponCode;
        public int packFee; public String packType; public String packColor;
        public long timestamp; public long printTime; public int status;
        public int refundedAmount; public String refundType;
    }

    public OrderManager(Main plugin) {
        this.plugin = plugin;
        loadReceiptConfig();
        loadOrders();
        startCleanupTask();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    public List<OrderRecord> getOrders() { return orders; }

    // ===== 小票模板 =====
    private void loadReceiptConfig() {
        File f = new File(plugin.getDataFolder(), "小票设置.txt");
        if (!f.exists()) createDefaultReceiptConfig(f);
        Map<String,String> m = loadMap(f);
        tplHeader = m.getOrDefault("头部", tplHeader);
        tplItemLine = m.getOrDefault("商品行", tplItemLine);
        tplDiscountLine = m.getOrDefault("优惠行", tplDiscountLine);
        tplPackLine = m.getOrDefault("打包行", tplPackLine);
        tplSummary = m.getOrDefault("汇总", tplSummary);
        tplFooter = m.getOrDefault("页脚", tplFooter);
    }
    private void saveReceiptConfig() {
        File f = new File(plugin.getDataFolder(), "小票设置.txt");
        List<String> L = new ArrayList<>();
        L.add("# 小票模板配置");
        L.add("头部=" + tplHeader);
        L.add("商品行=" + tplItemLine);
        L.add("优惠行=" + tplDiscountLine);
        L.add("打包行=" + tplPackLine);
        L.add("汇总=" + tplSummary);
        L.add("页脚=" + tplFooter);
        writeLines(f, L);
    }
    private void createDefaultReceiptConfig(File f) {
        List<String> L = new ArrayList<>();
        L.add("# 小票模板配置");
        L.add("头部=§6§l{商店名}§r\n购物小票\n订单#{订单号}  {订单时间}\n玩家: {玩家}");
        L.add("商品行=§e{品名}§r x{数量}  {原价}→{执行价}  小计{小计}枚");
        L.add("优惠行=优惠: §a-{优惠额}枚§r ({优惠类型}{优惠券码})");
        L.add("打包行=打包: §c+{打包费}枚§r ({打包颜色})");
        L.add("汇总=原价{原价合计}枚 → 实付§6{实付}枚§r  余额{余额}枚");
        L.add("页脚=感谢惠顾，欢迎再来！");
        writeLines(f, L);
    }

    // ===== 订单记录 =====
    public void recordOrder(Player p, List<OrderItem> items,
                            int totalOriginal, int totalPaid, int discount,
                            String discountType, String couponCode, int packFee,
                            String packType, String packColor) {
        OrderRecord r = new OrderRecord();
        r.orderId = System.currentTimeMillis();
        r.uuid = p.getUniqueId(); r.player = p.getName();
        r.items = items; r.totalOriginal = totalOriginal;
        r.totalPaid = totalPaid; r.discount = discount;
        r.discountType = discountType; r.couponCode = couponCode;
        r.packFee = packFee; r.packType = packType;
        r.packColor = packColor; r.timestamp = r.orderId;
        r.printTime = 0; r.status = 0; r.refundedAmount = 0;
        r.refundType = null;
        orders.add(r); saveOrders();
    }
    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null) return;
        if (!item.getType().name().contains("SHULKER")) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) return;

        for (String line : meta.getLore()) {
            if (line.startsWith("§8#")) {
                try {
                    long orderId = Long.parseLong(
                            line.replace("§8#", "").trim());
                    shulkerUsedOrders.add(orderId);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    // ===== 小票聊天栏 =====
    /**
     * 聊天栏打印小票（给定参数 Player + OrderRecord）
     * 不要改动方法签名
     */
    public void sendReceiptChat(Player p, OrderRecord r) {
        if (p == null || r == null) return;

        SimpleDateFormat df =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        p.sendMessage("§7§m                              ");
        p.sendMessage("§6§l======== 商店购物小票 ========");

        // 退款标记
        String statusTag = getStatusTag(r);
        if (!statusTag.isEmpty()) {
            p.sendMessage(statusTag);
        }

        p.sendMessage("§7订单号: §f#" + r.orderId);
        p.sendMessage("§7下单时间: §f"
                + df.format(new Date(r.timestamp)));
        p.sendMessage("§7玩家: §f" + r.player);
        p.sendMessage("§7------------------------------");

        for (OrderItem it : r.items) {
            String price;
            if (it.originalPrice != it.finalPrice) {
                price = "§f" + it.originalPrice
                        + "§7→§6" + it.finalPrice;
            } else {
                price = "§6" + it.finalPrice;
            }
            p.sendMessage("§7  §e" + it.name
                    + " §7x" + it.qty
                    + "  " + price + "枚"
                    + "  §6小计" + it.subtotal() + "枚");
        }

        p.sendMessage("§7------------------------------");
        p.sendMessage("§7原价: §f" + r.totalOriginal + "枚");

        if (r.discount > 0) {
            int pct = (r.totalOriginal > 0)
                    ? (r.discount * 100 / r.totalOriginal)
                    : 0;
            String dt = "coupon".equals(r.discountType)
                    ? "§7(优惠券 -" + pct + "%)"
                    : "eco".equals(r.discountType)
                      ? "§7(环保 -" + pct + "%)"
                      : "§7(-" + pct + "%)";
            p.sendMessage("§7优惠: §a-" + r.discount
                    + "枚 " + dt);
        }

        if (r.packFee > 0) {
            String cn = r.packColor != null
                    ? r.packColor
                      .replace("_SHULKER_BOX", "")
                      .replace("SHULKER_BOX", "原色")
                    : "默认";
            p.sendMessage("§7打包: §c+" + r.packFee
                    + "枚 §7(" + cn + "潜影盒)");
        } else {
            p.sendMessage("§7包装: 直接放入背包");
        }

        p.sendMessage("§7------------------------------");
        p.sendMessage("§7实付: §6" + r.totalPaid + "枚");

        int bal = 0;
        try {
            bal = plugin.getBonds().getBonds(r.player);
        } catch (Exception ignored) {}
        p.sendMessage("§7余额: §a" + bal + "枚");

        // 退款标记
        if (!statusTag.isEmpty()) {
            p.sendMessage(statusTag);
        }

        p.sendMessage("§6§l==============================");
        r.printTime = System.currentTimeMillis();
    }

    /**
     * 生成书本小票内容
     */
    private String buildReceiptText(OrderRecord r) {
        String statusTag = getStatusTag(r);
        SimpleDateFormat df =
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder();

        sb.append("§6§l===== 商店小票 =====\n");

        // 退款标记
        String tag = getStatusTag(r);
        if (!tag.isEmpty()) sb.append(tag).append("\n");

        sb.append("订单号: #").append(r.orderId).append("\n");
        sb.append("时间: ").append(
                df.format(new Date(r.timestamp))).append("\n");
        sb.append("玩家: ").append(r.player).append("\n");
        sb.append("------------------------------\n");

        for (OrderItem it : r.items) {
            String pr = (it.originalPrice != it.finalPrice)
                    ? it.originalPrice + "->" + it.finalPrice
                    : String.valueOf(it.finalPrice);
            sb.append(it.name).append(" x").append(it.qty)
                    .append("  ").append(pr).append("枚")
                    .append("  小计").append(it.subtotal())
                    .append("枚\n");
        }

        sb.append("------------------------------\n");
        sb.append("原价: ").append(r.totalOriginal)
                .append("枚\n");

        if (r.discount > 0) {
            int pct = (r.totalOriginal > 0)
                    ? (r.discount * 100 / r.totalOriginal)
                    : 0;
            String dt = "coupon".equals(r.discountType)
                    ? "优惠券(-" + pct + "%)"
                    : "环保(-" + pct + "%)";
            sb.append("优惠: -").append(r.discount)
                    .append("枚 ").append(dt).append("\n");
        }

        if (r.packFee > 0) {
            sb.append("打包: +").append(r.packFee)
                    .append("枚\n");
        }

        sb.append("------------------------------\n");
        sb.append("实付: ").append(r.totalPaid).append("枚\n");

        int bal = 0;
        try {
            bal = plugin.getBonds().getBonds(r.player);
        } catch (Exception ignored) {}
        sb.append("余额: ").append(bal).append("枚\n");

        // 退款标记
        if (!statusTag.isEmpty()) sb.append("\n").append(tag).append("\n");

        return sb.toString();
    }

    /**
     * 退款状态标记文本
     */
    private String getStatusTag(OrderRecord r) {
        switch (r.status) {
            case 2:
                return "§c§l<<<<<<< 已全额退款 >>>>>>>"
                        + "\n§c退款金额: "
                        + r.refundedAmount + "枚";
            case 3:
                return "§c§l<<<<<<< 已部分退款 >>>>>>>"
                        + "\n§c退款金额: "
                        + r.refundedAmount + "枚";
            case 4:
                return "§c§l<<<<<<< 退款申请已拒绝 >>>>>>>";
            default:
                return "";
        }
    }

    /**
     * 创建小票书
     */
    public ItemStack createReceiptBook(OrderRecord r) {
        String text = buildReceiptText(r);
        String[] lines = text.split("\n");

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) book.getItemMeta();
        bm.setTitle("购物小票 #" + r.orderId);
        bm.setAuthor("商店");

        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            // ★ 保留颜色代码
            page.append(line).append("\n");
            lineCount++;
            if (lineCount >= 13) {
                bm.addPage(page.toString().trim());
                page = new StringBuilder();
                lineCount = 0;
            }
        }
        if (page.length() > 0) {
            bm.addPage(page.toString().trim());
        }

        book.setItemMeta(bm);
        return book;
    }

    // ===== 退款工具 =====
    private boolean withinSelfRefundWindow(OrderRecord r) { return System.currentTimeMillis() - r.timestamp < SELF_REFUND_WINDOW; }
    private int countDirectItems(Player p, String matName) {
        Material m = Material.matchMaterial(matName); if (m == null) return 0;
        int c = 0; for (ItemStack is : p.getInventory().getContents()) if (is != null && is.getType() == m) c += is.getAmount();
        return c;
    }
    /** 精确按材质+ItemMeta(NBT)计数，用于含NBT商品（如附魔书）退款校验 */
    private int countSimilarItems(Player p, org.bukkit.inventory.ItemStack tmpl) {
        if (tmpl == null) return 0;
        int c = 0; for (ItemStack is : p.getInventory().getContents()) if (is != null && is.isSimilar(tmpl)) c += is.getAmount();
        return c;
    }
    private int countShulkers(Player p, String matName) {
        Material m = Material.matchMaterial(matName); if (m == null) return 0;
        int c = 0; for (ItemStack is : p.getInventory().getContents()) if (is != null && is.getType() == m) c += is.getAmount();
        return c;
    }
    /**
     * 收走订单物品：优先按记录中的NBT快照精确匹配（材质+ItemMeta一致），
     * 确保含NBT商品（附魔书等）既退钱又收回正确的实物，而非"仅退款"。
     * 无快照（旧订单/普通物品）则按材质兜底。
     */
    private void removeItems(Player p, OrderItem it) {
        org.bukkit.inventory.ItemStack tmpl = it.toTemplateStack();
        if (tmpl != null) {
            tmpl.setAmount(it.qty);
            p.getInventory().removeItem(tmpl); // removeItem 按 isSimilar 匹配，精确收走含NBT实物
        } else {
            Material m = Material.matchMaterial(it.mat);
            if (m != null) p.getInventory().removeItem(new ItemStack(m, it.qty));
        }
    }
    private boolean removeOneShulker(Player p,
                                     String materialName) {
        // 先尝试精确匹配
        Material mat = Material.matchMaterial(materialName);
        if (mat != null) {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack is = p.getInventory().getItem(i);
                if (is != null && is.getType() == mat) {
                    p.getInventory().clear(i);
                    return true;
                }
            }
        }
        // 匹配不到，移除任意一个潜影盒
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType().name()
                    .endsWith("_SHULKER_BOX")) {
                p.getInventory().clear(i);
                return true;
            }
        }
        return false;
    }
    public String validateRefund(OrderRecord r, Player p) {
        if (r.status != 0)
            return "订单状态: " + getStatusName(r.status);

        String pt = r.packType != null ? r.packType : "none";
        boolean isShulker = pt.equals("custom")
                || pt.equals("default")
                || pt.equals("shulker");

        if (isShulker) {
            // ★ 检查：潜影盒是否被放置过
            if (shulkerUsedOrders.contains(r.orderId)) {
                return "§c潜影盒已被使用过，无法自助退款";
            }

            // ★ 检查：背包里是否有同一订单的潜影盒
            for (int i = 0; i < 36; i++) {
                ItemStack is = p.getInventory().getItem(i);
                if (is == null) continue;
                if (!is.getType().name().contains("SHULKER"))
                    continue;
                ItemMeta im = is.getItemMeta();
                if (im == null || im.getLore() == null)
                    continue;
                for (String line : im.getLore()) {
                    if (line.equals("§8#" + r.orderId)) {
                        return null; // 找到了，可退
                    }
                }
            }
            return "§c对应的潜影盒已不在背包中";
        } else {
            // ★ 散装：检测物品数量（含NBT商品按材质+ItemMeta精确校验，必须"有货且有正确NBT"）
            for (OrderItem it : r.items) {
                org.bukkit.inventory.ItemStack tmpl = it.toTemplateStack();
                int have = (tmpl != null) ? countSimilarItems(p, tmpl) : countDirectItems(p, it.mat);
                if (have < it.qty)
                    return "§c" + it.name + "不足(有"
                            + have + "/" + it.qty + ")";
            }
        }
        return null;
    }



    private void notifyPlayer(UUID uuid, String msg) { Player p = Bukkit.getPlayer(uuid); if (p != null && p.isOnline()) p.sendMessage(msg); }
    private void notifyAdmins(Player p, OrderRecord r) {
        String m = "§e[订单] " + p.getName() + " 申请退款 #" + r.orderId + " §6" + r.totalPaid + "枚";
        for (Player a : Bukkit.getOnlinePlayers()) if (isAdmin(a) && !a.equals(p)) a.sendMessage(m);
    }
    private String getStatusName(int s) { switch(s){case 0:return "正常";case 1:return "申请中";case 2:return "已退";case 3:return "部分退";case 4:return "已拒绝";default:return "未知";} }
    private boolean isAdmin(Player p) {
        if (p.isOp()) return true;
        try { ConfigManager cm = plugin.getConfigMgr(); if (cm == null) return false; return p.hasPermission("sdf1.admin") || p.hasPermission("sdf1." + cm.adminTag); } catch (Exception e) { return p.hasPermission("sdf1.admin"); }
    }

    // ===== 订单中心 =====
    public void openOrderCenter(Player p) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l订单中心");
        g.setItem(13, mkItem(Material.BOOK, "§e§l购买记录", "§7查看历史订单", "", "§a点击进入"));
        if (isAdmin(p)) { g.setItem(31, mkItem(Material.NETHER_STAR, "§c§l管理员面板", "§7在线玩家购物记录", "§7支持退款操作", "", "§a点击进入"));
            g.setItem(33, mkItem(Material.CRAFTING_TABLE, "§e§l小票配置", "§7配置小票模板样式", "", "§a点击进入")); }
        g.setItem(49, mkItem(Material.ARROW, "§7返回商店"));
        p.openInventory(g);
    }

    // ===== 购买记录 =====
    public void openMyOrders(Player p, int page) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l购买记录");
        List<OrderRecord> list = getPlayerAllOrders(p.getUniqueId());
        int total = Math.max(1, (int) Math.ceil((double) list.size() / PAGE_SIZE));
        page = Math.max(0, Math.min(page, total - 1));
        userViewPage.put(p.getUniqueId(), page);
        int start = page * PAGE_SIZE; int end = Math.min(start + PAGE_SIZE, list.size());
        for (int i = start; i < end; i++) {
            int si = i - start; if (si >= SLOTS.length) break;
            OrderRecord r = list.get(i);
            Material icon; String st; String act;
            if (r.status != 0) { icon = r.status == 1 ? Material.PAPER : Material.BARRIER; st = "§7状态: " + getStatusName(r.status); act = "§b左键: 补打小票"; }
            else if (withinSelfRefundWindow(r)) { icon = Material.EMERALD; st = "§7状态: §a可退款(5分钟内)"; act = "§b左键: 补打小票\n§a右键: 退款退货\n§eShift+右键: 仅退款"; }
            else { icon = Material.BOOK; st = "§7状态: §7已过自助退款期"; act = "§b左键: 补打小票\n§e右键: 申请退款退货\n§6Shift+右键: 申请仅退款"; }
            ItemStack is = new ItemStack(icon); ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setDisplayName("§e订单 #" + r.orderId);
                List<String> lore = new ArrayList<>(); lore.add("§7" + fmtDate(r.timestamp));
                for (OrderItem it : r.items) lore.add("§7  §e" + it.name + " §7x" + it.qty + " §6" + it.subtotal() + "枚");
                lore.add("§7─────────"); lore.add("§7原价: §f" + r.totalOriginal + "枚");
                if (r.discount > 0) lore.add("§7优惠: §a-" + r.discount + "枚");
                lore.add("§7实付: §6" + r.totalPaid + "枚"); lore.add(st); lore.add("");
                for (String line : act.split("\n")) lore.add(line);
                im.setLore(lore); is.setItemMeta(im);
            }
            g.setItem(SLOTS[si], is);
        }
        if (list.isEmpty()) g.setItem(31, mkItem(Material.BARRIER, "§7暂无订单记录"));
        addPageButtons(g, page, total);
        p.openInventory(g);
    }

    // ===== 管理员面板 =====
    public void openAdminPanel(Player p, int page) {
        if (!isAdmin(p)) { p.sendMessage("§c权限不足"); return; }
        Inventory g = Bukkit.createInventory(null, 54, "§c§l管理员订单面板");
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        int total = Math.max(1, (int) Math.ceil((double) online.length / PAGE_SIZE));
        page = Math.max(0, Math.min(page, total - 1)); adminViewPage.put(p.getUniqueId(), page);
        int start = page * PAGE_SIZE; int end = Math.min(start + PAGE_SIZE, online.length);
        // ★ 替换 openAdminPanel 中的物品创建部分
        for (int i = start; i < end; i++) {
            int si = i - start;
            if (si >= SLOTS.length) break;
            Player t = online[i];
            List<OrderRecord> tl = getPlayerOrdersWithin(
                    t.getUniqueId(), RETENTION_30D);
            int ts = 0;
            for (OrderRecord r : tl) ts += r.totalPaid;

            ItemStack is = new ItemStack(Material.PLAYER_HEAD);
            org.bukkit.inventory.meta.SkullMeta sm =
                    (org.bukkit.inventory.meta.SkullMeta)
                            is.getItemMeta();
            if (sm != null) {
                sm.setOwner(t.getName()); // ★ 设置头颅皮肤
                sm.setDisplayName("§e" + t.getName());
                sm.setLore(Arrays.asList(
                        "§7订单: §f" + tl.size() + "笔",
                        "§7消费: §6" + ts + "枚",
                        "", "§a点击进入"));
                is.setItemMeta(sm); // ★ 这行之前缺失了
            }
            g.setItem(SLOTS[si], is);
        }

        if (online.length == 0) g.setItem(31, mkItem(Material.BARRIER, "§7无在线玩家"));
        addPageButtons(g, page, total); p.openInventory(g);
    }

    // ===== 管理员订单 =====
    public void openAdminOrders(Player p, UUID targetUUID, int page) {
        Inventory g = Bukkit.createInventory(null, 54, "§c§l订单管理");
        String name = Bukkit.getOfflinePlayer(targetUUID).getName();
        List<OrderRecord> list = getPlayerOrdersWithin(targetUUID, RETENTION_30D);
        int total = Math.max(1, (int) Math.ceil((double) list.size() / PAGE_SIZE));
        page = Math.max(0, Math.min(page, total - 1));
        adminViewPage.put(p.getUniqueId(), page); adminViewTarget.put(p.getUniqueId(), targetUUID);
        int start = page * PAGE_SIZE; int end = Math.min(start + PAGE_SIZE, list.size());
        for (int i = start; i < end; i++) {
            int si = i - start; if (si >= SLOTS.length) break;
            OrderRecord r = list.get(i);
            Material icon = r.status == 2 || r.status == 4 ? Material.BARRIER : r.status == 1 ? Material.PAPER : Material.BOOK;
            ItemStack is = new ItemStack(icon); ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setDisplayName("§e订单 #" + r.orderId);
                List<String> lore = new ArrayList<>(); lore.add("§7" + fmtDate(r.timestamp));
                for (OrderItem it : r.items) lore.add("§7  §e" + it.name + " §7x" + it.qty + " §6" + it.subtotal() + "枚");
                lore.add("§7─────────"); lore.add("§7实付: §6" + r.totalPaid + "枚");
                if (r.status == 1) { lore.add("§7状态: §e申请中(" + nn(r.refundType) + ")"); lore.add(""); lore.add("§a右键: 批准"); lore.add("§c左键: 拒绝"); }
                else if (r.status == 2) lore.add("§7已退: §a" + r.refundedAmount + "枚");
                else if (r.status == 3) lore.add("§7部分退: §a" + r.refundedAmount + "枚");
                else if (r.status == 4) lore.add("§7已拒绝");
                else { lore.add(""); lore.add("§a右键: 整单退款"); lore.add("§e左键: 部分退款(输入%)"); }
                im.setLore(lore); is.setItemMeta(im);
            }
            g.setItem(SLOTS[si], is);
        }
        if (list.isEmpty()) g.setItem(31, mkItem(Material.BARRIER, "§7该玩家暂无订单"));
        addPageButtons(g, page, total);
        g.setItem(49, mkItem(org.bukkit.Material.PAPER, name + " §e" + (page + 1) + "/" + total));
        p.openInventory(g);
    }
    private void addPageButtons(Inventory g, int page, int total) {
        g.setItem(48, page > 0 ? mkItem(Material.ARROW, "§7上一页") : mkItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        if (g.getItem(49) == null) g.setItem(49, mkItem(Material.PAPER, "§e" + (page + 1) + "/" + total));
        g.setItem(50, page < total - 1 ? mkItem(Material.ARROW, "§7下一页") : mkItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        g.setItem(53, mkItem(Material.ARROW, "§7返回"));
    }

    // ===== 小票配置 =====
    public void openReceiptConfig(Player p) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l小票配置");
        g.setItem(10, mkItem(Material.PAPER, "§e§l头部", "§7" + brief(tplHeader), "", "§a点击编辑"));
        g.setItem(11, mkItem(Material.PAPER, "§e§l商品行", "§7" + brief(tplItemLine), "", "§a点击编辑"));
        g.setItem(12, mkItem(Material.PAPER, "§e§l优惠行", "§7" + brief(tplDiscountLine), "", "§a点击编辑"));
        g.setItem(14, mkItem(Material.PAPER, "§e§l打包行", "§7" + brief(tplPackLine), "", "§a点击编辑"));
        g.setItem(15, mkItem(Material.PAPER, "§e§l汇总", "§7" + brief(tplSummary), "", "§a点击编辑"));
        g.setItem(16, mkItem(Material.PAPER, "§e§l页脚", "§7" + brief(tplFooter), "", "§a点击编辑"));
        g.setItem(22, mkItem(Material.BOOK, "§6§l预览(随机价格)", "§7预览小票效果"));
        g.setItem(48, mkItem(Material.LIME_WOOL, "§a§l保存")); g.setItem(49, mkItem(Material.ARROW, "§7返回"));
        g.setItem(50, mkItem(Material.RED_WOOL, "§c§l重置"));
        p.openInventory(g);
    }
    public void openReceiptBuilder(Player p, String section) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l编辑: " + sectionName(section));
        g.setItem(10, mkItem(Material.EMERALD, "§a{品名}")); g.setItem(11, mkItem(Material.EMERALD, "§a{原价}"));
        g.setItem(12, mkItem(Material.EMERALD, "§a{执行价}")); g.setItem(13, mkItem(Material.EMERALD, "§a{数量}"));
        g.setItem(14, mkItem(Material.EMERALD, "§a{小计}")); g.setItem(15, mkItem(Material.EMERALD, "§a{商店名}"));
        g.setItem(16, mkItem(Material.EMERALD, "§a{玩家}")); g.setItem(19, mkItem(Material.DIAMOND, "§b{订单号}"));
        g.setItem(20, mkItem(Material.DIAMOND, "§b{订单时间}")); g.setItem(21, mkItem(Material.DIAMOND, "§b{打印时间}"));
        g.setItem(22, mkItem(Material.DIAMOND, "§b{日期}")); g.setItem(23, mkItem(Material.DIAMOND, "§b{时间}"));
        g.setItem(24, mkItem(Material.DIAMOND, "§b{原价合计}")); g.setItem(25, mkItem(Material.DIAMOND, "§b{实付}"));
        g.setItem(28, mkItem(Material.GOLD_INGOT, "§6{优惠额}")); g.setItem(29, mkItem(Material.GOLD_INGOT, "§6{优惠类型}"));
        g.setItem(30, mkItem(Material.GOLD_INGOT, "§6{优惠券码}")); g.setItem(31, mkItem(Material.GOLD_INGOT, "§6{打包费}"));
        g.setItem(32, mkItem(Material.GOLD_INGOT, "§6{打包颜色}")); g.setItem(33, mkItem(Material.GOLD_INGOT, "§6{余额}"));
        g.setItem(37, mkItem(Material.STONE, "§f═══")); g.setItem(38, mkItem(Material.STONE, "§f───"));
        g.setItem(39, mkItem(Material.STONE, "§f═════════════")); g.setItem(40, mkItem(Material.PAPER, "§e换行(\\n)"));
        g.setItem(47, mkItem(Material.RED_STAINED_GLASS_PANE, "§c删末尾")); g.setItem(48, mkItem(Material.RED_WOOL, "§c清空"));
        g.setItem(49, mkItem(Material.ARROW, "§7返回")); g.setItem(50, mkItem(Material.LIME_WOOL, "§a保存"));
        g.setItem(51, mkItem(Material.BLUE_WOOL, "§9重置"));
        p.openInventory(g);
    }
    // ===== 点击处理 =====
    public boolean handleCenterClick(Player p, int raw) {
        if (raw == 13) { openMyOrders(p, 0); return true; }
        if (raw == 31 && isAdmin(p)) { openAdminPanel(p, 0); return true; }
        if (raw == 33 && isAdmin(p)) { openReceiptConfig(p); return true; }
        if (raw == 49) { plugin.getShopManager().openShopMain(p); return true; }
        return true;
    }
    public boolean handleMyOrdersClick(Player p, int raw, boolean left, boolean shift) {
        List<OrderRecord> list = getPlayerAllOrders(p.getUniqueId());
        int page = userViewPage.getOrDefault(p.getUniqueId(), 0);
        if (raw == 48) { openMyOrders(p, page - 1); return true; }
        if (raw == 50) { openMyOrders(p, page + 1); return true; }
        if (raw == 53) { openOrderCenter(p); return true; }
        int si = slotIndex(raw); if (si < 0) return true;
        int actual = si + page * PAGE_SIZE; if (actual < 0 || actual >= list.size()) return true;
        OrderRecord r = list.get(actual);
        if (left) {
            try { ItemStack book = createReceiptBook(r);
                HashMap<Integer, ItemStack> ov = p.getInventory().addItem(book);
                for (ItemStack d : ov.values()) p.getWorld().dropItemNaturally(p.getLocation(), d);
                r.printTime = System.currentTimeMillis(); p.sendMessage("§a已补打小票书: #" + r.orderId);
            } catch (Exception e) { p.sendMessage("§c补打失败"); }
        } else {
            if (r.status != 0) {
                p.sendMessage("§c状态: " + getStatusName(r.status));
            } else if (withinSelfRefundWindow(r)) {
                if (shift) {
                    // ★ Shift+右键：仅退款申请（需管理员审批）
                    r.status = 1;
                    r.refundType = "refund_only_apply";
                    saveOrders();
                    p.sendMessage("§e已提交仅退款申请，等待管理员审批");
                    notifyAdmins(p, r);
                } else {
                    // ★ 普通右键：5分钟内直接退（自动通过）
                    String reason = validateRefund(r, p);
                    if (reason != null) {
                        p.sendMessage("§c" + reason
                                + "，Shift+右键可申请仅退款");
                    } else {
                        // 扣货
                        String pt = r.packType != null
                                ? r.packType : "none";
                        boolean isShulker = pt.equals("custom")
                                || pt.equals("default")
                                || pt.equals("shulker");
                        if (isShulker) {
                            for (int i = 0; i < 36; i++) {
                                ItemStack is = p.getInventory()
                                        .getItem(i);
                                if (is != null && is.getType()
                                        .name().contains("SHULKER")) {
                                    p.getInventory().clear(i);
                                    break;
                                }
                            }
                        } else {
                            for (OrderItem it : r.items)
                                removeItems(p, it);
                        }
                        // 退钱
                        plugin.getBonds().addBonds(
                                r.player, r.totalPaid);
                        r.status = 2;
                        r.refundedAmount = r.totalPaid;
                        r.refundType = "auto_return";
                        saveOrders();
                        p.sendMessage("§a退款退货成功: §6"
                                + r.totalPaid + "枚");
                    }
                }


            } else {
                if (r.status != 0) {
                    p.sendMessage("§c状态: "
                            + getStatusName(r.status));
                } else if (withinSelfRefundWindow(r)) {
                    if (shift) {
                        // 仅退款申请
                        r.status = 1;
                        r.refundType = "refund_only_apply";
                        saveOrders();
                        p.sendMessage("§e已提交仅退款申请，"
                                + "等待管理员审批");
                        notifyAdmins(p, r);
                    } else {
                        // 5分钟内退货退款
                        String reason = validateRefund(r, p);
                        if (reason != null) {
                            // ★ 不满足条件：询问是否申请
                            p.sendMessage("§c" + reason);
                            askReturnConfirm(p, r);
                        } else {
                            // 满足条件：自动退
                            doAutoReturn(p, r);
                        }
                    }
                } else {
                    if (shift) {
                        // 申请仅退款
                        r.status = 1;
                        r.refundType = "refund_only_apply";
                        saveOrders();
                        p.sendMessage("§e已提交仅退款申请");
                        notifyAdmins(p, r);
                    } else {
                        // ★ 申请退货退款：先检查条件
                        String reason = validateRefund(r, p);
                        if (reason != null) {
                            p.sendMessage("§c" + reason);
                        }
                        // ★ 无论是否满足条件都询问
                        askReturnConfirm(p, r);
                    }
                }
            }
        }
            if (left) {
            // 已退款订单仍然允许补打小票，但小票内会显示退款标记
            try {
                ItemStack book = createReceiptBook(r);
                HashMap<Integer, ItemStack> overflow =
                        p.getInventory().addItem(book);
                for (ItemStack drop : overflow.values())
                    p.getWorld().dropItemNaturally(
                            p.getLocation(), drop);
                r.printTime = System.currentTimeMillis();
                if (r.status != 0) {
                    p.sendMessage("§e已补打小票(含退款标记): #"
                            + r.orderId);
                } else {
                    p.sendMessage("§a已补打小票书: #"
                            + r.orderId);
                }
            } catch (Exception e) {
                p.sendMessage("§c补打失败");
            }
        }

        final int fp = page; Bukkit.getScheduler().runTask(plugin, () -> openMyOrders(p, fp));
        return true;
    }
    public boolean handleAdminClick(Player p, int raw) {
        Player[] online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        int page = adminViewPage.getOrDefault(p.getUniqueId(), 0);
        if (raw == 48) { openAdminPanel(p, page - 1); return true; }
        if (raw == 50) { openAdminPanel(p, page + 1); return true; }
        if (raw == 53) { openOrderCenter(p); return true; }
        int si = slotIndex(raw); if (si < 0) return true;
        int actual = si + page * PAGE_SIZE; if (actual < 0 || actual >= online.length) return true;
        openAdminOrders(p, online[actual].getUniqueId(), 0); return true;
    }
    public boolean handleAdminOrdersClick(Player p, int raw, boolean left, boolean shift) {
        UUID tu = adminViewTarget.get(p.getUniqueId());
        if (tu == null) return false;
        List<OrderRecord> list = getPlayerOrdersWithin(tu, RETENTION_30D);
        int page = adminViewPage.getOrDefault(p.getUniqueId(), 0);
        if (raw == 48) {
            openAdminOrders(p, tu, page - 1);
            return true;
        }
        if (raw == 50) {
            openAdminOrders(p, tu, page + 1);
            return true;
        }
        if (raw == 53) {
            openAdminPanel(p, 0);
            return true;
        }
        int si = slotIndex(raw);
        if (si < 0) return true;
        int actual = si + page * PAGE_SIZE;
        if (actual < 0 || actual >= list.size()) return true;
        OrderRecord r = list.get(actual);
        if (r.status == 2 || r.status == 3 || r.status == 4) {
            p.sendMessage("§c状态: " + getStatusName(r.status));
            return true;
        }
        if (left) {
            if (r.status == 1) {
                r.status = 0;
                r.refundType = null;
                saveOrders();
                p.sendMessage("§c已拒绝: #" + r.orderId);
                notifyPlayer(r.uuid, "§c退款被拒绝");
                openAdminOrders(p, tu, page);
            } else if (r.status == 0) {
                partialRefundListening.add(p.getUniqueId());
                partialRefundTarget.put(p.getUniqueId(), r);
                p.closeInventory();
                p.sendMessage("§e输入退款百分比(1-100): #" + r.orderId + " 实付" + r.totalPaid + "枚");
            }
        } else {
            if (r.status == 0) {
                // ★ 管理员全额退款：同步收回物品（玩家在线时），避免"只退钱不退货"
                Player target = Bukkit.getPlayer(r.uuid);
                if (target != null && target.isOnline()) {
                    String pt = r.packType != null ? r.packType : "none";
                    boolean isShulker = pt.equals("custom")
                            || pt.equals("default") || pt.equals("shulker");
                    if (isShulker) {
                        for (int i = 0; i < 36; i++) {
                            ItemStack is = target.getInventory().getItem(i);
                            if (is == null) continue;
                            if (!is.getType().name().contains("SHULKER")) continue;
                            ItemMeta im = is.getItemMeta();
                            boolean tagged = false;
                            if (im != null && im.getLore() != null) {
                                for (String line : im.getLore()) {
                                    if (line.equals("§8#" + r.orderId)) { tagged = true; break; }
                                }
                            }
                            if (tagged) { target.getInventory().clear(i); break; }
                        }
                    } else {
                        for (OrderItem it : r.items) removeItems(target, it);
                    }
                }
                plugin.getBonds().addBonds(r.player, r.totalPaid);
                r.status = 2;
                r.refundedAmount = r.totalPaid;
                r.refundType = "auto_return";
                saveOrders();
                p.sendMessage("§a全额退款(含退货) §e" + r.player + " §6" + r.totalPaid + "枚");
                notifyPlayer(r.uuid, "§a[订单] 退款退货成功，§6" + r.totalPaid + "枚已退还");
                openAdminOrders(p, tu, page);
            }
        }
        // ★ 替换 handleAdminOrdersClick 中的审批逻辑
        if (left) {
            // 左键 = 拒绝
            if (r.status == 1) {
                r.status = 0;
                r.refundType = null;
                saveOrders();
                p.sendMessage("§c已拒绝退款: #" + r.orderId);
                notifyPlayer(r.uuid, "§c退款申请已被拒绝");
                openAdminOrders(p, tu, page);
            }
        } else {
            // 右键 = 批准
            if (r.status == 1) {
                String rt = r.refundType != null
                        ? r.refundType : "";

                // ★ 退款退货申请 → 检查潜影盒
                if (rt.equals("refund_return_apply")) {
                    String pt = r.packType != null
                            ? r.packType : "none";
                    boolean isShulker = pt.equals("custom")
                            || pt.equals("default")
                            || pt.equals("shulker");

                    if (isShulker) {
                        // 检查玩家背包里有没有带订单标签的盒子
                        Player target = Bukkit.getPlayer(r.uuid);
                        if (target == null || !target.isOnline()) {
                            p.sendMessage("§c玩家不在线，无法执行退货退款");
                            openAdminOrders(p, tu, page);
                            return true;
                        }

                        boolean foundTagged = false;
                        boolean foundAny = false;
                        for (int i = 0; i < 36; i++) {
                            ItemStack is = target.getInventory()
                                    .getItem(i);
                            if (is == null) continue;
                            if (!is.getType().name()
                                    .contains("SHULKER")) continue;
                            foundAny = true;
                            ItemMeta im = is.getItemMeta();
                            if (im != null && im.getLore() != null) {
                                for (String line : im.getLore()) {
                                    if (line.equals("§8#" + r.orderId)) {
                                        foundTagged = true;
                                        // 收走盒子
                                        target.getInventory().clear(i);
                                        break;
                                    }
                                }
                            }
                            if (foundTagged) break;
                        }

                        if (foundTagged) {
                            // ★ 有带标记的盒子 → 收走 + 退钱
                            plugin.getBonds().addBonds(
                                    r.player, r.totalPaid);
                            r.status = 2;
                            r.refundedAmount = r.totalPaid;
                            saveOrders();
                            p.sendMessage("§a已退货退款 §e"
                                    + r.player + " §6"
                                    + r.totalPaid + "枚（盒子已收走）");
                            notifyPlayer(r.uuid,
                                    "§a[订单] 退货退款成功，§6"
                                            + r.totalPaid + "枚已退还");
                            openAdminOrders(p, tu, page);
                            return true;
                        } else if (foundAny) {
                            // ★ 有盒子但没标记 → 等管理员决定
                            pendingReturns.put(r.orderId,
                                    new PendingReturn(r));
                            p.closeInventory();
                            p.sendMessage("§e§l[退货审批] 订单 #"
                                    + r.orderId);
                            p.sendMessage("§7玩家背包有潜影盒但"
                                    + "无订单标记，可能已被替换");
                            p.sendMessage("§a输入 §e1 §a= 收走盒子并退款");
                            p.sendMessage("§e输入 §e2 §e= 仅退款（不收盒子）");
                            p.sendMessage("§c输入 §c3 §c= 拒绝退款");
                            p.sendMessage("§72分钟内未操作自动拒绝");
                            // 启动2分钟超时
                            startReturnTimeout(r.orderId);
                            return true;
                        } else {
                            // ★ 背包里没有任何盒子
                            p.sendMessage("§c玩家背包里没有潜影盒");
                            p.sendMessage("§7可拒绝并通知玩家"
                                    + "申请仅退款或把货拿回来");
                            openAdminOrders(p, tu, page);
                            return true;
                        }
                    }
                    // ★ 散装退货退款：扣货+退钱
                    Player target = Bukkit.getPlayer(r.uuid);
                    if (target != null && target.isOnline()) {
                        for (OrderItem it : r.items)
                            removeItems(target, it);
                    }
                    plugin.getBonds().addBonds(r.player, r.totalPaid);
                    r.status = 2;
                    r.refundedAmount = r.totalPaid;
                    saveOrders();
                    p.sendMessage("§a已退货退款 §e" + r.player
                            + " §6" + r.totalPaid + "枚");
                    notifyPlayer(r.uuid,
                            "§a[订单] 退货退款成功，§6"
                                    + r.totalPaid + "枚");
                    openAdminOrders(p, tu, page);
                    return true;
                }

                // ★ 仅退款申请 / 5分钟内自动退款
                plugin.getBonds().addBonds(r.player, r.totalPaid);
                r.status = 2;
                r.refundedAmount = r.totalPaid;
                saveOrders();
                p.sendMessage("§a已批准退款 §e" + r.player
                        + " §6" + r.totalPaid + "枚");
                notifyPlayer(r.uuid,
                        "§a[订单] 退款已批准，§6"
                                + r.totalPaid + "枚已退还");
                openAdminOrders(p, tu, page);
            }
        }
        return true;
    }

    /**
     * 询问用户是否提交退货退款申请
     */
    private void askReturnConfirm(Player p, OrderRecord r) {
        returnConfirmWaiting.put(p.getUniqueId(), r.orderId);
        p.sendMessage("");
        p.sendMessage("§e§l[退货退款申请]");
        p.sendMessage("§7订单 #" + r.orderId
                + " 实付" + r.totalPaid + "枚");
        p.sendMessage("§a输入 §e1 §a= 提交退货退款申请");
        p.sendMessage("§c输入 §e2 §c= 取消");
        p.sendMessage("§730秒内未操作自动取消");
        p.sendMessage("");

        // 30秒超时
        final UUID uuid = p.getUniqueId();
        final long oid = r.orderId;
        new BukkitRunnable() {
            @Override
            public void run() {
                Long waiting = returnConfirmWaiting.get(uuid);
                if (waiting != null && waiting == oid) {
                    returnConfirmWaiting.remove(uuid);
                    Player pl = Bukkit.getPlayer(uuid);
                    if (pl != null && pl.isOnline()) {
                        pl.sendMessage("§7退货退款申请已超时取消");
                    }
                }
            }
        }.runTaskLater(plugin, RETURN_CONFIRM_TIMEOUT);
    }

    /**
     * 执行5分钟内自动退货退款
     */
    private void doAutoReturn(Player p, OrderRecord r) {
        String pt = r.packType != null ? r.packType : "none";
        boolean isShulker = pt.equals("custom")
                || pt.equals("default")
                || pt.equals("shulker");

        if (isShulker) {
            // 找并收走带标签的盒子
            boolean found = false;
            for (int i = 0; i < 36; i++) {
                ItemStack is = p.getInventory().getItem(i);
                if (is == null) continue;
                if (!is.getType().name().contains("SHULKER"))
                    continue;
                ItemMeta im = is.getItemMeta();
                if (im != null && im.getLore() != null) {
                    for (String line : im.getLore()) {
                        if (line.equals("§8#" + r.orderId)) {
                            p.getInventory().clear(i);
                            found = true;
                            break;
                        }
                    }
                }
                if (found) break;
            }
            // 没找到标签盒子，收任意潜影盒
            if (!found) {
                for (int i = 0; i < 36; i++) {
                    ItemStack is = p.getInventory().getItem(i);
                    if (is != null && is.getType().name()
                            .contains("SHULKER")) {
                        p.getInventory().clear(i);
                        break;
                    }
                }
            }
        } else {
            for (OrderItem it : r.items)
                removeItems(p, it);
        }

        plugin.getBonds().addBonds(r.player, r.totalPaid);
        r.status = 2;
        r.refundedAmount = r.totalPaid;
        r.refundType = "auto_return";
        saveOrders();
        p.sendMessage("§a退款退货成功: §6"
                + r.totalPaid + "枚");
    }


    // ===== 2分钟超时 =====
    private void startReturnTimeout(long orderId) {
        new BukkitRunnable() {
            @Override
            public void run() {
                PendingReturn pr = pendingReturns
                        .remove(orderId);
                if (pr == null) return; // 已处理
                Player admin = Bukkit.getPlayer(pr.playerUUID);
                if (admin != null && admin.isOnline()) {
                    admin.sendMessage("§c[退货审批] 订单 #"
                            + orderId + " 超时，自动拒绝");
                }
                // 通知玩家
                notifyPlayer(pr.playerUUID,
                        "§c[订单] 退货退款申请已超时拒绝");
                // 恢复订单状态
                OrderRecord r = findOrder(orderId);
                if (r != null) {
                    r.status = 0;
                    r.refundType = null;
                    saveOrders();
                }
            }
        }.runTaskLater(plugin, 2400L); // 2分钟
    }

    // ===== 查找订单 =====
    private OrderRecord findOrder(long orderId) {
        synchronized (orders) {
            for (OrderRecord r : orders) {
                if (r.orderId == orderId) return r;
            }
        }
        return null;
    }


    public boolean handleReceiptConfigClick(Player p, int raw) {
        if (raw == 49) { openOrderCenter(p); return true; }
        if (raw == 48) { saveReceiptConfig(); p.sendMessage("§a已保存"); openReceiptConfig(p); return true; }
        if (raw == 50) { createDefaultReceiptConfig(new File(plugin.getDataFolder(), "小票设置.txt")); loadReceiptConfig(); p.sendMessage("§e已重置"); openReceiptConfig(p); return true; }
        if (raw == 22) { sendRandomPreview(p); return true; }
        String sec = null;
        if (raw == 10) sec = "header"; else if (raw == 11) sec = "itemLine"; else if (raw == 12) sec = "discountLine";
        else if (raw == 14) sec = "packLine"; else if (raw == 15) sec = "summary"; else if (raw == 16) sec = "footer";
        if (sec != null) { receiptBuilderSection.put(p.getUniqueId(), sec); openReceiptBuilder(p, sec); }
        return true;
    }
    public boolean handleReceiptBuilderClick(Player p, int raw) {
        String sec = receiptBuilderSection.get(p.getUniqueId()); if (sec == null) return false;
        String token = getTokenBySlot(raw);
        if (token != null) { String cur = getSection(sec); setSection(sec, cur.isEmpty() ? token : cur + token); openReceiptBuilder(p, sec); return true; }
        if (raw == 47) { String c = getSection(sec); int lb = c.lastIndexOf('}'); if (lb >= 0) { int ls = c.lastIndexOf('{', lb); if (ls >= 0) setSection(sec, c.substring(0, ls)); } else setSection(sec, c.substring(0, Math.max(0, c.length() - 8))); openReceiptBuilder(p, sec); return true; }
        if (raw == 48) { setSection(sec, ""); openReceiptBuilder(p, sec); return true; }
        if (raw == 49) { receiptBuilderSection.remove(p.getUniqueId()); openReceiptConfig(p); return true; }
        if (raw == 50) { saveReceiptConfig(); receiptBuilderSection.remove(p.getUniqueId()); p.sendMessage("§a已保存"); openReceiptConfig(p); return true; }
        if (raw == 51) { setSection(sec, getDefaultVal(sec)); openReceiptBuilder(p, sec); return true; }
        return true;
    }

    // ===== 聊天事件 =====
    @EventHandler
    public void onAdminChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer(); UUID uuid = p.getUniqueId();
        // ★ 在 onAdminChat 方法开头添加
// ===== 退货审批输入 =====
        for (Map.Entry<Long, PendingReturn> entry :
                new HashMap<>(pendingReturns).entrySet()) {
            Long pendingOrderId = entry.getKey();
            PendingReturn pr = entry.getValue();
            if (pr.playerUUID.equals(p.getUniqueId())
                    || isAdmin(p)) {
                try {
                    int choice = Integer.parseInt(
                            e.getMessage().trim());
                    pendingReturns.remove(pendingOrderId);
                    OrderRecord r = findOrder(pendingOrderId);
                    if (r == null) {
                        p.sendMessage("§c订单不存在");
                        return;
                    }

                    if (choice == 1) {
                        // ★ 收走盒子并退款
                        Player target = Bukkit.getPlayer(
                                pr.playerUUID);
                        if (target != null && target.isOnline()) {
                            for (int i = 0; i < 36; i++) {
                                ItemStack is = target.getInventory()
                                        .getItem(i);
                                if (is != null && is.getType().name()
                                        .contains("SHULKER")) {
                                    ItemMeta im = is.getItemMeta();
                                    if (im != null && im.getLore() != null) {
                                        for (String line : im.getLore()) {
                                            if (line.equals("§8#" + pendingOrderId)) {
                                                target.getInventory().clear(i);
                                                break;
                                            }
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                        plugin.getBonds().addBonds(pr.playerName, pr.totalPaid);
                        r.status = 2;
                        r.refundedAmount = pr.totalPaid;
                        saveOrders();
                        p.sendMessage("§a已退货退款 §e" + pr.playerName
                                + " §6" + pr.totalPaid + "枚（盒子已收走）");
                        notifyPlayer(pr.playerUUID,
                                "§a[订单] 退货退款成功，§6"
                                        + pr.totalPaid + "枚已退还");

                    } else if (choice == 2) {
                        // ★ 仅退款（不收盒子）
                        plugin.getBonds().addBonds(pr.playerName, pr.totalPaid);
                        r.status = 2;
                        r.refundedAmount = pr.totalPaid;
                        saveOrders();
                        p.sendMessage("§e已仅退款 §e" + pr.playerName
                                + " §6" + pr.totalPaid + "枚（未收盒子）");
                        notifyPlayer(pr.playerUUID,
                                "§e[订单] 仅退款成功，§6"
                                        + pr.totalPaid + "枚已退还"
                                        + "，盒子无需退回");

                    } else if (choice == 3) {
                        // ★ 拒绝
                        r.status = 0;
                        r.refundType = null;
                        saveOrders();
                        p.sendMessage("§c已拒绝退款 #" + pendingOrderId);
                        notifyPlayer(pr.playerUUID,
                                "§c[订单] 退货退款已被拒绝"
                                        + "，您可以申请仅退款"
                                        + "或把货拿回来后重新申请");
                    } else {
                        p.sendMessage("§c请输入 1、2 或 3");
                        pendingReturns.put(pendingOrderId, pr);
                    }
                    return;

                } catch (NumberFormatException ignored) {
                    // 不是数字，跳过
                }
            }
        }
// ★ 在 onAdminChat 方法开头，处理用户退货确认
        if (returnConfirmWaiting.containsKey(uuid)) {
            String msg = e.getMessage().trim();
            if (msg.equals("1")) {
                // ★ 确认提交
                Long orderId = returnConfirmWaiting.remove(uuid);
                if (orderId != null) {
                    OrderRecord r = findOrder(orderId);
                    if (r != null) {
                        r.status = 1;
                        r.refundType = "refund_return_apply";
                        saveOrders();
                        p.sendMessage("§e已提交退货退款申请，"
                                + "等待管理员审批");
                        notifyAdmins(p, r);
                    }
                }
                e.setCancelled(true);
                return;
            } else if (msg.equals("2")) {
                // ★ 取消
                returnConfirmWaiting.remove(uuid);
                p.sendMessage("§7已取消退货退款申请");
                e.setCancelled(true);
                return;
            } else {
                // 输入其他内容：取消等待
                returnConfirmWaiting.remove(uuid);
                // 不 return，让消息正常发送
            }
        }

        if (partialRefundListening.contains(uuid)) {
            e.setCancelled(true); partialRefundListening.remove(uuid);
            OrderRecord t = partialRefundTarget.remove(uuid); if (t == null) return;
            try { int pct = Integer.parseInt(e.getMessage().trim()); if (pct < 1 || pct > 100) { p.sendMessage("§c1-100"); return; }
                int ref = t.totalPaid * pct / 100; if (ref <= 0) ref = 1;
                plugin.getBonds().addBonds(t.player, ref); t.status = 3; t.refundedAmount = ref; saveOrders();
                p.sendMessage("§a部分退款 §e" + t.player + " §6" + ref + "枚(" + pct + "%)");
                notifyPlayer(t.uuid, "§a退款 §6" + ref + "枚");
                final UUID tu = adminViewTarget.get(uuid); final int pg = adminViewPage.getOrDefault(uuid, 0);
                Bukkit.getScheduler().runTask(plugin, () -> openAdminOrders(p, tu, pg));
            } catch (NumberFormatException ex) { p.sendMessage("§c请输入数字"); } catch (Exception ex) { plugin.getLogger().log(Level.WARNING, "[Order]退款异常", ex); }
            return;
        }
        if (receiptBuilderSection.containsKey(uuid)) {
            e.setCancelled(true); String sec = receiptBuilderSection.remove(uuid); if (sec == null) return;
            String msg = e.getMessage().trim();
            if ("cancel".equalsIgnoreCase(msg)) { Bukkit.getScheduler().runTask(plugin, () -> openReceiptConfig(p)); return; }
            setSection(sec, msg.replace("\\n", "\n")); p.sendMessage("§a已更新 §e" + sec);
            Bukkit.getScheduler().runTask(plugin, () -> openReceiptConfig(p));
        }
    }

    // ===== 数据查询 =====
    private List<OrderRecord> getPlayerAllOrders(UUID uuid) {
        List<OrderRecord> r = new ArrayList<>();
        synchronized (orders) { for (OrderRecord o : orders) if (o.uuid.equals(uuid) && System.currentTimeMillis() - o.timestamp < RETENTION_30D) r.add(o); }
        Collections.reverse(r); return r;
    }
    private List<OrderRecord> getPlayerOrdersWithin(UUID uuid, long ms) {
        List<OrderRecord> r = new ArrayList<>(); long cut = System.currentTimeMillis() - ms;
        synchronized (orders) { for (OrderRecord o : orders) if (o.uuid.equals(uuid) && o.timestamp >= cut) r.add(o); }
        Collections.reverse(r); return r;
    }

    // ===== 持久化 =====
    private void loadOrders() {
        File f = new File(plugin.getDataFolder(), "orders.dat"); if (!f.exists()) return;
        try { BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            OrderRecord cur = null; String line;
            while ((line = br.readLine()) != null) { line = line.trim();
                if (line.equals("==")) { cur = new OrderRecord(); cur.items = new ArrayList<>(); }
                else if (cur != null && line.contains("=")) { int eq = line.indexOf('='); String k = line.substring(0, eq); String v = line.substring(eq + 1);
                    switch (k) { case "id": cur.orderId = Long.parseLong(v); cur.timestamp = cur.orderId; break; case "uuid": cur.uuid = UUID.fromString(v); break;
                        case "player": cur.player = v; break; case "totalOriginal": cur.totalOriginal = Integer.parseInt(v); break; case "totalPaid": cur.totalPaid = Integer.parseInt(v); break;
                        case "discount": cur.discount = Integer.parseInt(v); break; case "discountType": cur.discountType = v; break; case "couponCode": cur.couponCode = v; break;
                        case "packFee": cur.packFee = Integer.parseInt(v); break; case "packType": cur.packType = v; break; case "packColor": cur.packColor = v; break;
                        case "status": cur.status = Integer.parseInt(v); break; case "refundedAmount": cur.refundedAmount = Integer.parseInt(v); break;
                        case "printTime": cur.printTime = Long.parseLong(v); break; case "refundType": cur.refundType = v; break;
                        case "item": String[] pp = v.split("\\|"); if (pp.length >= 5) { OrderItem oi = new OrderItem(pp[0], pp[1], Integer.parseInt(pp[2]), Integer.parseInt(pp[3]), Integer.parseInt(pp[4])); if (pp.length >= 6) oi.nbt = pp[5]; cur.items.add(oi); } break; }
                } else if (line.isEmpty() && cur != null && cur.uuid != null) { orders.add(cur); cur = null; }
            } if (cur != null && cur.uuid != null) orders.add(cur); br.close();
        } catch (Exception e) { plugin.getLogger().warning("[Order]加载失败: " + e.getMessage()); }
    }
    private void saveOrders() {
        File f = new File(plugin.getDataFolder(), "orders.dat");
        try { PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
            synchronized (orders) { for (OrderRecord r : orders) { pw.println("==");
                pw.println("id=" + r.orderId); pw.println("uuid=" + r.uuid); pw.println("player=" + r.player);
                pw.println("totalOriginal=" + r.totalOriginal); pw.println("totalPaid=" + r.totalPaid);
                pw.println("discount=" + r.discount); pw.println("discountType=" + nn(r.discountType));
                pw.println("couponCode=" + nn(r.couponCode)); pw.println("packFee=" + r.packFee);
                pw.println("packType=" + nn(r.packType)); pw.println("packColor=" + nn(r.packColor));
                pw.println("status=" + r.status); pw.println("refundedAmount=" + r.refundedAmount);
                pw.println("printTime=" + r.printTime); pw.println("refundType=" + nn(r.refundType));
                for (OrderItem it : r.items) pw.println("item=" + it.name + "|" + it.mat + "|" + it.originalPrice + "|" + it.finalPrice + "|" + it.qty + "|" + (it.nbt == null ? "" : it.nbt));
            } } pw.flush(); pw.close();
        } catch (Exception e) { plugin.getLogger().warning("[Order]保存失败: " + e.getMessage()); }
    }
    private void startCleanupTask() { new BukkitRunnable() { public void run() { synchronized (orders) { if (orders.removeIf(OrderManager.this::isExpired)) saveOrders(); } } }.runTaskTimer(plugin, 36000L, 36000L); }
    /**
     * 将小票书塞入潜影盒
     */
    public void addBookToShulker(ItemStack shulker, ItemStack book) {
        try {
            org.bukkit.inventory.meta.BlockStateMeta bm =
                    (org.bukkit.inventory.meta.BlockStateMeta)
                            shulker.getItemMeta();
            if (bm == null) return;
            org.bukkit.block.BlockState st = bm.getBlockState();
            if (st instanceof org.bukkit.block.Container) {
                ((org.bukkit.block.Container) st)
                        .getInventory().addItem(book);
                bm.setBlockState(st);
                shulker.setItemMeta(bm);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Order] 塞书失败: " + e.getMessage());
        }
    }

    // ===== 工具 =====
    private String nn(String s) { return s != null ? s : ""; }
    private boolean isExpired(OrderRecord r) { return System.currentTimeMillis() - r.timestamp > RETENTION_30D; }
    private String fmtDate(long ts) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)); }
    private int slotIndex(int raw) { for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == raw) return i; return -1; }
    private String brief(String s) { if (s == null || s.isEmpty()) return "(空)"; String c = sc(s); return c.length() > 30 ? c.substring(0, 30) + "..." : c; }
    private String sectionName(String s) { switch(s){case "header":return "头部";case "itemLine":return "商品行";case "discountLine":return "优惠行";case "packLine":return "打包行";case "summary":return "汇总";case "footer":return "页脚";default:return s;} }
    private String sc(String s) {
        if (s == null) return "";
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }
    private String getSection(String s) { switch(s){case "header":return tplHeader;case "itemLine":return tplItemLine;case "discountLine":return tplDiscountLine;case "packLine":return tplPackLine;case "summary":return tplSummary;case "footer":return tplFooter;default:return "";} }
    private void setSection(String s, String v) { switch(s){case "header":tplHeader=v;break;case "itemLine":tplItemLine=v;break;case "discountLine":tplDiscountLine=v;break;case "packLine":tplPackLine=v;break;case "summary":tplSummary=v;break;case "footer":tplFooter=v;break;} }
    private String getDefaultVal(String s) { switch(s){case "header":return "§6§l{商店名}§r\n购物小票\n订单#{订单号}  {订单时间}\n玩家: {玩家}";case "itemLine":return "§e{品名}§r x{数量}  {原价}→{执行价}  小计{小计}枚";case "discountLine":return "优惠: §a-{优惠额}枚§r ({优惠类型}{优惠券码})";case "packLine":return "打包: §c+{打包费}枚§r ({打包颜色})";case "summary":return "原价{原价合计}枚 → 实付§6{实付}枚§r  余额{余额}枚";case "footer":return "感谢惠顾，欢迎再来！";default:return "";} }
    private String getTokenBySlot(int raw) { switch(raw){case 10:return "{品名}";case 11:return "{原价}";case 12:return "{执行价}";case 13:return "{数量}";case 14:return "{小计}";case 15:return "{商店名}";case 16:return "{玩家}";case 19:return "{订单号}";case 20:return "{订单时间}";case 21:return "{打印时间}";case 22:return "{日期}";case 23:return "{时间}";case 24:return "{原价合计}";case 25:return "{实付}";case 28:return "{优惠额}";case 29:return "{优惠类型}";case 30:return "{优惠券码}";case 31:return "{打包费}";case 32:return "{打包颜色}";case 33:return "{余额}";case 37:return "═══";case 38:return "───";case 39:return "═════════════";case 40:return "\\n";default:return null;} }
    private ItemStack mkItem(Material mat, String name, String... lore) { ItemStack it = new ItemStack(mat); ItemMeta im = it.getItemMeta(); if (im != null) { im.setDisplayName(name); if (lore.length > 0) im.setLore(Arrays.asList(lore)); it.setItemMeta(im); } return it; }
    private Map<String, String> loadMap(File f) { Map<String, String> m = new LinkedHashMap<>(); try { BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)); String line; while ((line = br.readLine()) != null) { line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue; int eq = line.indexOf('='); if (eq > 0) m.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim()); } br.close(); } catch (Exception ignored) {} return m; }
    private void writeLines(File f, List<String> lines) { try { PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)); for (String l : lines) pw.println(l); pw.flush(); pw.close(); } catch (Exception ignored) {} }
    private void sendRandomPreview(Player p) { String[] n = {"钻石剑","钻石镐","铁剑","金苹果"}; String[] m = {"DIAMOND_SWORD","DIAMOND_PICKAXE","IRON_SWORD","GOLDEN_APPLE"}; Random r = new Random(); List<OrderItem> items = new ArrayList<>(); for (int i = 0; i < 2 + r.nextInt(3); i++) { int idx = r.nextInt(n.length); items.add(new OrderItem(n[idx], m[idx], 100 + r.nextInt(5000), 100 + r.nextInt(5000), 1 + r.nextInt(10))); } OrderRecord o = new OrderRecord(); o.orderId = System.currentTimeMillis(); o.uuid = p.getUniqueId(); o.player = p.getName(); o.items = items; o.totalOriginal = 0; for (OrderItem it : items) o.totalOriginal += it.originalSubtotal(); o.totalPaid = o.totalOriginal; o.timestamp = o.orderId; o.status = 0; p.sendMessage("§6§l===== 小票预览(随机) ====="); sendReceiptChat(p, o); p.sendMessage("§6§l===== 预览结束 ====="); }
}
