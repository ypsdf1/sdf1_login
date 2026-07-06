package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.text.SimpleDateFormat;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.meta.PotionMeta;




public class ShopManager implements Listener {

    private final Main plugin;
    private final List<ShopCategory> categories =
            new ArrayList<>();
    // ===== 出售限额配置 =====
    private String maxSellMessage = "";
    private boolean maxSellMessageEnabled = false;
    private int maxSellLimit = 15000; // 默认1.5万
    private final Object maxSellLock = new Object();
    private final List<RefundRecord> refundRecords =
            new ArrayList<>();
    private final Map<UUID, Integer> activeDiscount =
            new HashMap<>();
    private final Map<UUID, ShopCategory> viewCat =
            new HashMap<>();
    private final Map<UUID, Integer> viewPage =
            new HashMap<>();
    private final Map<UUID, List<CartItem>> shoppingCarts =
            new HashMap<>();
    private final Set<UUID> cartModeEnabled =
            new HashSet<>();
    private final java.util.Random rng =
            new java.util.Random();
    private final Map<UUID, List<CartItem>> pendingCheckout =
            new HashMap<>();
    private final Set<UUID> couponListening =
            new HashSet<>();
    private final Map<UUID, String> pendingCouponCode =
            new HashMap<>();
    // ===== 物品NBT数据 =====
    private final Map<String, String> itemNbtData =
            new HashMap<>();
    private final Map<String, ItemStack> itemStackCache =
            new HashMap<>();
    // 替换字段
    private final Map<UUID, Long> couponApplyTime = new HashMap<>();



    private static final Material[] SHULKER_COLORS = {
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX,
            Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX
    };
    private static final Map<String, PotionEffectType> EFFECT_MAP = new HashMap<>();
    static {
        // ===== 基础效果 =====
        EFFECT_MAP.put("夜视",     PotionEffectType.NIGHT_VISION);
        EFFECT_MAP.put("隐身",     PotionEffectType.INVISIBILITY);
        EFFECT_MAP.put("迅捷",     PotionEffectType.SPEED);
        EFFECT_MAP.put("速度",     PotionEffectType.SPEED);
        EFFECT_MAP.put("力量",     PotionEffectType.STRENGTH);      // 1.21+ 改名
        EFFECT_MAP.put("治疗",     PotionEffectType.INSTANT_HEALTH); // 1.21+ 改名
        EFFECT_MAP.put("治愈",     PotionEffectType.INSTANT_HEALTH);
        EFFECT_MAP.put("伤害",     PotionEffectType.INSTANT_DAMAGE); // 1.21+ 改名
        EFFECT_MAP.put("剧毒",     PotionEffectType.POISON);
        EFFECT_MAP.put("中毒",     PotionEffectType.POISON);
        EFFECT_MAP.put("回复",     PotionEffectType.REGENERATION);
        EFFECT_MAP.put("再生",     PotionEffectType.REGENERATION);
        EFFECT_MAP.put("生命恢复", PotionEffectType.REGENERATION);
        EFFECT_MAP.put("抗性",     PotionEffectType.RESISTANCE);     // 1.21+ 改名
        EFFECT_MAP.put("抗火",     PotionEffectType.FIRE_RESISTANCE);
        EFFECT_MAP.put("水下呼吸", PotionEffectType.WATER_BREATHING);
        EFFECT_MAP.put("急迫",     PotionEffectType.HASTE);          // 1.21+ 改名
        EFFECT_MAP.put("挖掘疲劳", PotionEffectType.MINING_FATIGUE); // 1.21+ 改名
        EFFECT_MAP.put("虚弱",     PotionEffectType.WEAKNESS);
        EFFECT_MAP.put("失明",     PotionEffectType.BLINDNESS);
        EFFECT_MAP.put("飘浮",     PotionEffectType.LEVITATION);
        EFFECT_MAP.put("缓降",     PotionEffectType.SLOW_FALLING);   // ★ 补充
        EFFECT_MAP.put("迟缓",     PotionEffectType.SLOWNESS);
        EFFECT_MAP.put("饱和",     PotionEffectType.SATURATION);
        EFFECT_MAP.put("伤害吸收", PotionEffectType.ABSORPTION);
        EFFECT_MAP.put("幸运",     PotionEffectType.LUCK);
        EFFECT_MAP.put("不祥",     PotionEffectType.UNLUCK);
        EFFECT_MAP.put("发光",     PotionEffectType.GLOWING);
        EFFECT_MAP.put("黑暗",     PotionEffectType.DARKNESS);
        EFFECT_MAP.put("生命加成", PotionEffectType.HEALTH_BOOST);
        EFFECT_MAP.put("饥饿",     PotionEffectType.HUNGER);
        EFFECT_MAP.put("反胃",     PotionEffectType.NAUSEA);
        EFFECT_MAP.put("海豚关怀", PotionEffectType.DOLPHINS_GRACE);
        EFFECT_MAP.put("跳跃",     PotionEffectType.JUMP_BOOST);
        // ★ 神龟药水是双效果，特殊处理，这里只做名称匹配入口
        EFFECT_MAP.put("神龟",     PotionEffectType.RESISTANCE);
    }

    public static class CartItem {
        String itemId;
        String displayName;
        Material material;
        int unitPrice;
        int quantity;
    }


    private static final int PAGE_SIZE = 28;
    private static final int[] ITEM_SLOTS = {
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
    };

    public ShopManager(Main plugin) {
        this.plugin = plugin;
        loadCategories();
        loadMaxSellConfig();
        startHourlyTask();
        loadItemNbtData();
        startCleanupTask();
    }
    // ===== 切换购物车模式 =====
    // ===== 切换购物车模式 =====
    public boolean toggleCartMode(Player p) {
        UUID uuid = p.getUniqueId();
        if (cartModeEnabled.contains(uuid)) {
            cartModeEnabled.remove(uuid);
            p.sendMessage("§7购物车模式已关闭，恢复正常购买");
            return false;
        } else {
            cartModeEnabled.add(uuid);
            p.sendMessage("§a购物车模式已开启，点击商品加入购物车");
            return true;
        }
    }

    public boolean isCartMode(Player p) {
        return cartModeEnabled.contains(p.getUniqueId());
    }
    private String itemToBase64(ItemStack item) {
        try {
            ByteArrayOutputStream os =
                    new ByteArrayOutputStream();
            BukkitObjectOutputStream bos =
                    new BukkitObjectOutputStream(os);
            bos.writeObject(item);
            bos.close();
            return Base64.getEncoder()
                    .encodeToString(os.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private ItemStack base64ToItem(String b64) {
        try {
            byte[] data =
                    Base64.getDecoder().decode(b64);
            ByteArrayInputStream is =
                    new ByteArrayInputStream(data);
            BukkitObjectInputStream bis =
                    new BukkitObjectInputStream(is);
            ItemStack item =
                    (ItemStack) bis.readObject();
            bis.close();
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadItemNbtData() {
        itemNbtData.clear();
        itemStackCache.clear();
        File f = new File(plugin.getDataFolder(),
                "shop/item_nbt.properties");
        if (!f.exists()) return;
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()
                        || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String id =
                        line.substring(0, eq).trim();
                String b64 =
                        line.substring(eq + 1).trim();
                itemNbtData.put(id, b64);
                ItemStack stack = base64ToItem(b64);
                if (stack != null) {
                    itemStackCache.put(id, stack);
                }
            }
            br.close();
            // plugin.getLogger().info("[Shop] NBT数据: "
            //         + itemStackCache.size() + "条");
        } catch (Exception ignored) {}
    }

    private void saveItemNbtData() {
        File dir = new File(
                plugin.getDataFolder(), "shop");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "item_nbt.properties");
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            pw.println("# 商品完整数据(自动生成)");
            for (Map.Entry<String, String> e
                    : itemNbtData.entrySet()) {
                pw.println(e.getKey() + "="
                        + e.getValue());
            }
            pw.flush();
            pw.close();
        } catch (Exception ignored) {}
    }

    // ===== 加入/移除购物车 =====
    public boolean addToCart(Player p, ShopItem item) {
        UUID uuid = p.getUniqueId();
        List<CartItem> cart = shoppingCarts
                .computeIfAbsent(
                        uuid, k -> new ArrayList<>());
        // ★ 始终用原价，折扣在结算时统一应用
        int price = item.getBuyPrice();
        for (CartItem ci : cart) {
            if (ci.itemId.equals(item.getId())) {
                ci.quantity += 1;
                p.sendMessage("§a+1 §e"
                        + item.getDisplayName()
                        + " §7购物车共§f"
                        + getCartCount(p)
                        + "件 §6需"
                        + getCartTotal(p) + "枚");
                return true;
            }
        }
        CartItem ci = new CartItem();
        ci.itemId = item.getId();
        ci.displayName = item.getDisplayName();
        ci.material = item.getMaterial();
        ci.unitPrice = price;
        ci.quantity = 1;
        cart.add(ci);
        p.sendMessage("§a+1 §e"
                + item.getDisplayName()
                + " §7购物车共§f"
                + getCartCount(p)
                + "件 §6需"
                + getCartTotal(p) + "枚");
        return true;
    }


    public int getCartCount(Player p) {
        List<CartItem> cart =
                shoppingCarts.get(p.getUniqueId());
        if (cart == null) return 0;
        int c = 0;
        for (CartItem ci : cart) c += ci.quantity;
        return c;
    }

    public int getCartTotal(Player p) {
        List<CartItem> cart =
                shoppingCarts.get(p.getUniqueId());
        if (cart == null) return 0;
        int t = 0;
        for (CartItem ci : cart)
            t += ci.unitPrice * ci.quantity;
        return t;
    }

    public void cartAddOne(Player p, int index) {
        List<CartItem> cart =
                shoppingCarts.get(p.getUniqueId());
        if (cart == null || index < 0
                || index >= cart.size()) return;
        cart.get(index).quantity += 1;
    }

    public void cartRemoveOne(Player p, int index) {
        List<CartItem> cart =
                shoppingCarts.get(p.getUniqueId());
        if (cart == null || index < 0
                || index >= cart.size()) return;
        CartItem ci = cart.get(index);
        ci.quantity -= 1;
        if (ci.quantity <= 0) cart.remove(index);
    }

    public void clearCart(Player p) {
        shoppingCarts.remove(p.getUniqueId());
    }

    // ===== 打开购物车页面 =====
    public void openCart(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§a§l购物车");
        List<CartItem> cart = shoppingCarts
                .getOrDefault(p.getUniqueId(),
                        new ArrayList<>());
        for (int i = 0; i < cart.size() && i < 25; i++) {
            CartItem ci = cart.get(i);
            ItemStack is = new ItemStack(ci.material);
            ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setDisplayName("§e" + ci.displayName
                        + " §7x" + ci.quantity);
                im.setLore(java.util.Arrays.asList(
                        "§7单价: §f" + ci.unitPrice + "枚",
                        "§7小计: §6"
                                + (ci.unitPrice * ci.quantity)
                                + "枚",
                        "",
                        "§a左键+1  §c右键-1"));
                is.setItemMeta(im);
            }
            g.setItem(10 + i, is);
        }
        if (cart.isEmpty()) {
            g.setItem(22, mkItem(Material.BARRIER,
                    "§7购物车为空"));
        }
        int total = getCartTotal(p);
        int bal = plugin.getBonds().getBonds(p.getName());
        g.setItem(49, mkItem(Material.EMERALD,
                "§6§l结算: §e" + total + " §6枚债券",
                "§7余额: §f" + bal + "枚",
                bal >= total ? "§a余额充足" : "§c余额不足",
                "", "§a点击结算"));
        g.setItem(48, mkItem(
                Material.LIME_STAINED_GLASS_PANE,
                "§7清空购物车"));
        g.setItem(53, mkItem(Material.ARROW, "§7返回商店"));
     
        p.openInventory(g);
    }

    // ===== 购物车点击处理 =====
    public boolean handleCartClick(Player p, int raw,
                                   boolean left,
                                   boolean right) {
        if (raw == 53) { openShopMain(p); return true; }
        if (raw == 48) {
            clearCart(p);
            p.sendMessage("§7购物车已清空");
            openCart(p);
            return true;
        }
        if (raw == 49) {
            checkoutCartInit(p);
            return true;
        }
        if (raw >= 10 && raw < 35) {
            int idx = raw - 10;
            List<CartItem> cart = shoppingCarts
                    .getOrDefault(p.getUniqueId(),
                            new ArrayList<>());
            if (idx < cart.size()) {
                if (right) cartRemoveOne(p, idx);
                else cartAddOne(p, idx);
                openCart(p);
            }
            return true;
        }
        return true;
    }

    // ===== 结算入口：判断打包方式 =====
    private void checkoutCartInit(Player p) {
        UUID uuid = p.getUniqueId();
        List<CartItem> cart = shoppingCarts.get(uuid);
        if (cart == null || cart.isEmpty()) {
            p.sendMessage("§c购物车为空");
            return;
        }
        int total = getCartTotal(p);
        int bal = plugin.getBonds()
                .getBonds(p.getName());
        if (bal < total) {
            p.sendMessage("§c余额不足，需要 §e"
                    + total + "§c枚");
            return;
        }
        int totalQty = 0;
        for (CartItem ci : cart) totalQty += ci.quantity;
        if (cart.size() == 1 && totalQty <= 64) {
            int couponPct = activeDiscount
                    .getOrDefault(p.getUniqueId(), 0);
            int ecoPct = 0;
            double factor = 1.0;
            if (couponPct > 0)
                factor *= (100 - couponPct) / 100.0;
            deductAndGive(p, cart,
                    (int) Math.round(total * factor),
                    false, null, "", 0,
                    ecoPct, couponPct);
            return;
        }
        pendingCheckout.put(uuid,
                new ArrayList<>(cart));
        openPackingConfirm(p, total);
    }



    // ===== 执行扣款+发货 =====
    private boolean deductAndGive(Player p,
                                  List<CartItem> cart, int baseTotal,
                                  boolean useShulker, Material color,
                                  String packNote, int packFee,
                                  int ecoPct, int couponPct) {

        UUID uuid = p.getUniqueId();
        int originalTotal = 0;
        for (CartItem ci : cart)
            originalTotal += ci.unitPrice * ci.quantity;

        String[] names = new String[cart.size()];
        int[] subs = new int[cart.size()];
        for (int i = 0; i < cart.size(); i++) {
            CartItem ci = cart.get(i);
            names[i] = ci.displayName;
            subs[i] = ci.unitPrice * ci.quantity;
        }

        if (baseTotal < originalTotal) {
            double ratio =
                    (double) baseTotal / originalTotal;
            int sum = 0;
            for (int i = 0; i < subs.length; i++) {
                if (i == subs.length - 1) {
                    subs[i] = baseTotal - sum;
                } else {
                    subs[i] = (int) (subs[i] * ratio);
                    sum += subs[i];
                }
            }
        }

        boolean ok = plugin.getBonds().checkoutCart(
                p.getName(), names, subs);
        if (!ok) {
            p.sendMessage("§c扣款失败");
            return false;
        }

        if (packFee > 0) {
            plugin.getBonds().deductBonds(
                    p.getName(), packFee,
                    "shop_packing", "",
                    "商店系统", "打包费");
        }

        for (CartItem ci : cart) {
            ShopItem si = findItemById(ci.itemId);
            if (si != null) {
                if (si.getStock() >= 0)
                    si.setStock(si.getStock()
                            - ci.quantity);
                si.addSales(ci.quantity);
            }
        }

        int newBal = plugin.getBonds()
                .getBonds(p.getName());


        // ===== 优惠券码 =====
        String usedCouponCode =
                pendingCouponCode.get(uuid);

        // ===== 记录订单 =====
        OrderManager.OrderRecord latestOrder = null;
        try {
            List<OrderManager.OrderItem> orderItems =
                    new ArrayList<>();
            for (CartItem ci : cart) {
                orderItems.add(
                        new OrderManager.OrderItem(
                                ci.displayName,
                                ci.material.name(),
                                ci.unitPrice,
                                ci.unitPrice,
                                ci.quantity));
            }
            String dType = "none";
            String cCode = "";
            if (usedCouponCode != null) {
                dType = "coupon";
                cCode = usedCouponCode;
            } else if (ecoPct > 0) {
                dType = "eco";
            }
            plugin.getOrderManager().recordOrder(
                    p, orderItems, originalTotal,
                    baseTotal + packFee,
                    originalTotal - baseTotal,
                    dType, cCode, packFee,
                    useShulker ? (packFee > 0
                                  ? "custom" : "default")
                            : "none",
                    color != null ? color.name() : "");
            // 获取最新订单
            synchronized (plugin.getOrderManager()
                    .getOrders()) {
                List<OrderManager.OrderRecord> all =
                        plugin.getOrderManager()
                                .getOrders();
                if (!all.isEmpty())
                    latestOrder =
                            all.get(all.size() - 1);
            }
            // 散装打印小票
            if (!useShulker && latestOrder != null) {
                plugin.getOrderManager()
                        .sendReceiptChat(p, latestOrder);
            }
        } catch (Exception ex) {
            // plugin.getLogger().warning(
            //         "[Shop] 记录订单失败: "
            //                 + ex.getMessage());
        }

// ===== 发货 =====
        if (!useShulker) {
            for (CartItem ci : cart) {
                ShopItem si = findItemById(ci.itemId);
                ItemStack give = (si != null)
                        ? getShopStack(si, ci.quantity)
                        : new ItemStack(ci.material, ci.quantity);
                HashMap<Integer, ItemStack> left2 =
                        p.getInventory().addItem(give);
                for (ItemStack drop : left2.values())
                    p.getWorld().dropItemNaturally(
                            p.getLocation(), drop);
            }
        } else {
            List<ItemStack> shulkers =
                    packShulkers(cart, color);

            // ★ 给潜影盒打订单ID标签
            if (latestOrder != null) {
                for (ItemStack sh : shulkers) {
                    ItemMeta shm = sh.getItemMeta();
                    if (shm != null) {
                        List<String> lore = shm.getLore() != null
                                ? new ArrayList<>(shm.getLore())
                                : new ArrayList<>();
                        lore.add("§8#" + latestOrder.orderId);
                        shm.setLore(lore);
                        sh.setItemMeta(shm);
                    }
                }
            }

            // 小票书塞入
            if (!shulkers.isEmpty()
                    && latestOrder != null) {
                ItemStack book = plugin.getOrderManager()
                        .createReceiptBook(latestOrder);
                plugin.getOrderManager()
                        .addBookToShulker(shulkers.get(0), book);
            }
            // 发货
            for (ItemStack sh : shulkers) {
                HashMap<Integer, ItemStack> left2 =
                        p.getInventory().addItem(sh);
                for (ItemStack drop : left2.values())
                    p.getWorld().dropItemNaturally(
                            p.getLocation(), drop);
            }
        }


        // ===== 退款记录 =====
        long now = System.currentTimeMillis();
        if (!useShulker) {
            for (CartItem ci : cart) {
                refundRecords.add(new RefundRecord(
                        p.getUniqueId(), p.getName(),
                        ci.itemId, ci.displayName,
                        ci.material, null,
                        ci.quantity,
                        ci.unitPrice * ci.quantity,
                        now));
            }
        } else {
            String summary = "";
            for (CartItem ci : cart) {
                if (!summary.isEmpty()) summary += "+";
                summary += ci.displayName
                        + "x" + ci.quantity;
            }
            refundRecords.add(new RefundRecord(
                    p.getUniqueId(), p.getName(),
                    "cart_" + now, summary,
                    Material.SHULKER_BOX, color,
                    1, baseTotal, now));
        }

        // ===== 核销优惠券 =====
        if (usedCouponCode != null) {
            activeDiscount.remove(uuid);
            try {
                Plugin sdf1 = Bukkit.getPluginManager()
                        .getPlugin("SDF1");
                if (sdf1 != null && sdf1.isEnabled()) {
                    java.lang.reflect.Method m =
                            sdf1.getClass().getMethod(
                                    "redeemCoupon",
                                    String.class);
                    m.invoke(sdf1, usedCouponCode);
                }
            } catch (Exception ignored) {}
            p.sendMessage("§7优惠券 §e" + usedCouponCode
                    + " §7已核销");
        }

        shoppingCarts.remove(uuid);
        pendingCheckout.remove(uuid);
        return true;
    }

    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) {
        ItemStack item = e.getItemInHand();
        if (item == null) return;
        if (!item.getType().name().contains("SHULKER")) return;
        markShulkerUsed(item);
    }

    // ★ 公共方法：标记潜影盒为已使用
    private void markShulkerUsed(ItemStack item) {
        if (item == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getLore() == null) return;
        for (String line : meta.getLore()) {
            if (line.startsWith("§8#")) {
                try {
                    long orderId = Long.parseLong(
                            line.replace("§8#", "").trim());
                  //  shulkerUsedOrders.add(orderId);
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    @EventHandler
    public void onPlayerDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        ItemStack item = e.getItemDrop().getItemStack();
        if (item != null && item.getType().name().contains("SHULKER")) {
            markShulkerUsed(item);
        }
    }


    // ===== 潠影盒打包（指定颜色）=====
    private List<ItemStack> packShulkers(
            List<CartItem> items, Material color) {
        List<ItemStack> result = new ArrayList<>();
        List<ItemStack> all = new ArrayList<>();
        for (CartItem ci : items) {
            int rem = ci.quantity;
            while (rem > 0) {
                int sz = Math.min(rem, 64);
                all.add(new ItemStack(ci.material, sz));
                rem -= sz;
            }
        }
        while (!all.isEmpty()) {
            ItemStack shulker = new ItemStack(color);
            org.bukkit.inventory.meta.BlockStateMeta meta =
                    (org.bukkit.inventory.meta.BlockStateMeta)
                            shulker.getItemMeta();
            if (meta != null) {
                org.bukkit.block.BlockState state =
                        meta.getBlockState();
                if (state instanceof org.bukkit.block.Container) {
                    org.bukkit.block.Container c =
                            (org.bukkit.block.Container) state;
                    List<ItemStack> remain = new ArrayList<>();
                    for (ItemStack stack : all) {
                        HashMap<Integer, ItemStack> left =
                                c.getInventory().addItem(stack);
                        for (ItemStack l : left.values())
                            remain.add(l);
                    }
                    all = remain;
                    meta.setBlockState(c);
                    shulker.setItemMeta(meta);
                }
            }
            result.add(shulker);
        }

        return result;
    }

    // ===== 打包确认GUI =====
    public void openPackingConfirm(Player p, int baseTotal) {
        Inventory g = Bukkit.createInventory(
                null, 27, "§6§l选择包装方式");
        g.setItem(10, mkItem(Material.CHEST,
                "§a§l不打包",
                "§7直接放入背包",
                "§7适合少量物品"));
        g.setItem(13, mkItem(Material.SHULKER_BOX,
                "§6§l默认打包",
                "§7随机颜色潜影盒",
                "§7加收 §e5 §6枚债券"));
        g.setItem(16, mkItem(Material.ENDER_CHEST,
                "§b§l自选颜色",
                "§7选择你喜欢的潜影盒颜色",
                "§7加收 §e5 §6枚债券"));
        g.setItem(22, mkItem(Material.PAPER,
                "§7消费金额: §e" + baseTotal + "枚"));
     
        p.openInventory(g);
    }

    public boolean handlePackingConfirm(
            Player p, int raw) {
        UUID uuid = p.getUniqueId();
        List<CartItem> cart =
                pendingCheckout.remove(uuid);
        if (cart == null) return false;

        int originalTotal = 0;
        for (CartItem ci : cart)
            originalTotal += ci.unitPrice * ci.quantity;

        int couponPct = activeDiscount
                .getOrDefault(p.getUniqueId(), 0);
        int ecoPct = 0;
        int packFeeCfg = plugin.getConfigMgr().packingFee;   // 彩色潜影盒打包费（配置）
        int greenPct = plugin.getConfigMgr().greenDiscount;  // 环保单减免（不打包时减免）

        if (raw == 10) {
            ecoPct = greenPct; // 不打包（环保单）→ 减免
        }

        double factor = 1.0;
        if (ecoPct > 0)
            factor *= (100 - ecoPct) / 100.0;
        if (couponPct > 0)
            factor *= (100 - couponPct) / 100.0;
        int discounted = (int) Math.round(
                originalTotal * factor);

        int totalQty = 0;
        for (CartItem ci : cart)
            totalQty += ci.quantity;
        boolean multiAndBig = (cart.size() > 1
                && originalTotal >= 100);

        if (raw == 10) {
            p.closeInventory();
            deductAndGive(p, cart, discounted,
                    false, null, "", 0,
                    ecoPct, couponPct);
            return true;
        }
        if (raw == 13) {
            Material c = Material.SHULKER_BOX;
            if (multiAndBig) {
                p.closeInventory();
                deductAndGive(p, cart, discounted,
                        true, c, "", 0,
                        ecoPct, couponPct);
            } else {
                p.closeInventory();
                deductAndGive(p, cart, discounted,
                        true, c, "默认打包", packFeeCfg,
                        ecoPct, couponPct);
            }
            return true;
        }
        if (raw == 16) {
            pendingCheckout.put(uuid, cart);
            openColorSelect(p, discounted);
            return true;
        }
        return true;
    }


    // ===== 自选颜色GUI =====
    public void openColorSelect(Player p, int baseTotal) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§b§l选择潜影盒颜色");
        // ★ 顺序必须与 SHULKER_COLORS 严格一致：索引0 = 原色(SHULKER_BOX)，1=白色 … 16=黑色
        String[] colorNames = {
                "原色","白色","橙色","品红",
                "淡蓝","黄色","淡绿","粉色",
                "灰色","淡灰","青色","紫色",
                "蓝色","棕色","绿色","红色","黑色"
        };
        int packFee = plugin.getConfigMgr().packingFee; // 打包费（彩色潜影盒加收，来自配置）
        for (int i = 0; i < SHULKER_COLORS.length; i++) {
            ItemStack is = new ItemStack(SHULKER_COLORS[i]);
            ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setDisplayName("§e" + colorNames[i]
                        + "§6潜影盒");
                // 原色（SHULKER_BOX）免费；其它颜色按配置加收打包费
                String feeLine = (SHULKER_COLORS[i] == Material.SHULKER_BOX)
                        ? "§a免费（原版默认颜色）"
                        : ("§7加收 §e" + packFee + " §6枚债券");
                im.setLore(java.util.Arrays.asList(
                        feeLine,
                        "",
                        "§a点击选择"));
                is.setItemMeta(im);
            }
            g.setItem(i, is);
        }
        g.setItem(49, mkItem(Material.ARROW, "§7返回"));
        g.setItem(45, mkItem(Material.PAPER,
                "§7消费: §e" + baseTotal
                        + "枚 + §e" + packFee + "§6打包费"));
     
        p.openInventory(g);
    }

    public boolean handleColorSelect(
            Player p, int raw) {
        if (raw == 49) {
            UUID uuid = p.getUniqueId();
            List<CartItem> cart =
                    pendingCheckout.get(uuid);
            if (cart != null) {
                int total = 0;
                for (CartItem ci : cart)
                    total += ci.unitPrice * ci.quantity;
                openPackingConfirm(p, total);
            }
            return true;
        }
        if (raw < 0 || raw >= SHULKER_COLORS.length)
            return false;
        UUID uuid = p.getUniqueId();
        List<CartItem> cart =
                pendingCheckout.remove(uuid);
        if (cart == null) return false;
        int total = 0;
        for (CartItem ci : cart)
            total += ci.unitPrice * ci.quantity;

        int couponPct = activeDiscount
                .getOrDefault(p.getUniqueId(), 0);
        int ecoPct = 0;
        double factor = 1.0;
        if (couponPct > 0)
            factor *= (100 - couponPct) / 100.0;
        int discounted = (int) Math.round(
                total * factor);

        Material selectedColor = SHULKER_COLORS[raw];
        if (selectedColor == Material.SHULKER_BOX) {
            p.closeInventory();
            deductAndGive(p, cart, discounted, true,
                    Material.SHULKER_BOX,
                    "原色打包", 0,
                    ecoPct, couponPct);
        } else {
            p.closeInventory();
            deductAndGive(p, cart, discounted, true,
                    selectedColor, "自选颜色", plugin.getConfigMgr().packingFee,
                    ecoPct, couponPct);
        }
        return true;
    }



    // ===== 加载分类 =====

    public void loadCategories() {
        // plugin.getLogger().info("[Shop] === loadCategories 被调用 ===");  // 过滤调试日志
        categories.clear();
        viewCat.clear();    // ★ 清缓存
        viewPage.clear();

        File dir = new File(
                plugin.getDataFolder(), "shop");
        if (!dir.exists()) dir.mkdirs();

        convertTxtToMd(dir);

        File[] allMd = dir.listFiles(
                (d, n) -> n.endsWith(".md")
                        && !n.equals("coupons.md"));
        if (allMd == null || allMd.length == 0) {
            createDefaultCategories(dir);
            allMd = dir.listFiles(
                    (d, n) -> n.endsWith(".md")
                            && !n.equals("coupons.md"));
        }
        if (allMd == null) {
          plugin.getLogger().warning(
                 "[Shop] shop目录为空");
            return;
        }

   /*     plugin.getLogger().info("[Shop] === 开始加载 ===");
        plugin.getLogger().info("[Shop] 扫描到 "
                + allMd.length + " 个md文件");
*/
        for (File f : allMd) {
         /*   plugin.getLogger().info("[Shop] 读取: "
                    + f.getName());*/
            ShopCategory cat = parseFile(f);
            if (cat != null) {
       /*         plugin.getLogger().info("[Shop] 分类: "
                        + cat.getName()
                        + " 商品数: "
                        + cat.getItems().size());*/
                if (!cat.getItems().isEmpty()) {
                    categories.add(cat);
                }
            }
        }

        int totalItems = 0;
        for (ShopCategory c : categories)
            totalItems += c.getItems().size();

        plugin.getLogger().info("[Shop] === 加载完成 ==="
                + " 分类=" + categories.size()
                + " 商品=" + totalItems);
    }


// ===== txt → md 自动迁移 =====

    private void convertTxtToMd(File dir) {
        File[] txtFiles = dir.listFiles(
                (d, n) -> n.endsWith(".txt")
                        && !n.equals("coupons.txt"));
        if (txtFiles == null) return;
        for (File txt : txtFiles) {
            String mdName = txt.getName()
                    .replace(".txt", ".md");
            File mdFile = new File(dir, mdName);
            if (mdFile.exists()) continue;

            try {
                List<String> lines = new ArrayList<>();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(
                                new FileInputStream(txt),
                                StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null)
                    lines.add(line);
                br.close();

                String catName = txt.getName()
                        .replace(".txt", "");
                List<String> out = new ArrayList<>();
                out.add("# " + catName);
                out.add("");
                out.add("| ID | 品名 | 材质"
                        + " | 购入价 | 售出价"
                        + " | 库存"
                        + " | 本小时销量 | 总销量 |");
                out.add("| --- | --- | ---"
                        + " | --- | --- | ---"
                        + " | --- | --- |");

                for (String l : lines) {
                    l = l.trim();
                    if (l.isEmpty() || l.startsWith("#"))
                        continue;
                    // 旧格式: ID | 名称 | 材质 | ...
                    // 保持原样转换为 md 表格行
                    out.add("| " + l.replace("|", "|")
                            + " |");
                }

                PrintWriter pw = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(mdFile),
                                StandardCharsets.UTF_8));
                for (String o : out)
                    pw.println(o);
                pw.flush();
                pw.close();

                txt.delete();
                // plugin.getLogger().info(
                //         "[Shop] 已迁移: "
                //                 + txt.getName()
                //                 + " → " + mdName);
            } catch (Exception e) {
                // plugin.getLogger().warning(
                //         "[Shop] 迁移失败: "
                //                 + txt.getName());
            }
        }
    }

// ===== 解析 md 文件 =====

    private ShopCategory parseFile(File f) {
        String name = f.getName()
                .replace(".md", "");
        ShopCategory cat =
                new ShopCategory(name, f.getName());
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                            new FileInputStream(f),
                            StandardCharsets.UTF_8));
            String line;
            boolean headerPassed = false;
            boolean separatorPassed = false;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                // 空行跳过
                if (line.isEmpty()) continue;

                // 标题行：# 分类名
                if (line.startsWith("#")) {
                    String t = line.replaceFirst(
                            "^#+\\s*", "").trim();
                    if (!t.isEmpty()) {
                        // 更新分类名（用文件内标题）
                        // cat.name 是 final，不改
                    }
                    continue;
                }

                // 表格分隔行：| --- | --- |
                if (line.contains("---")) {
                    separatorPassed = true;
                    continue;
                }

                // 表头行：| ID | 品名 | ...
                if (line.startsWith("|")) {
                    if (!separatorPassed) {
                        headerPassed = true;
                        continue;
                    }
                    // 数据行
                    ShopItem item =
                            parseTableRow(line);
                    if (item != null)
                        cat.addItem(item);
                }
            }
            br.close();
        } catch (Exception e) {
            // plugin.getLogger().warning(
            //         "[Shop] 解析失败: " + f.getName()
            //                 + " 原因: " + e.getMessage());
            return null;
        }
    return cat;
    }

// ===== 解析表格行 =====

    private ShopItem parseTableRow(String row) {
        // plugin.getLogger().info("[Shop]   行: " + row);  // 过滤调试日志

        String trimmed = row.trim();
        if (trimmed.startsWith("|"))
            trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|"))
            trimmed = trimmed.substring(0,
                    trimmed.length() - 1);

        String[] p = trimmed.split("\\|");
        // plugin.getLogger().info("[Shop]   列数=" + p.length);  // 过滤调试日志

        if (p.length < 6) {
            // plugin.getLogger().warning(
            //         "[Shop] ✗ 列数不足, 跳过");
            return null;
        }

        String rawId = p[0].trim();
        String rawName = p[1].trim();
        String rawMat = p[2].trim().toUpperCase();
        String rawBuy = p[3].trim();
        String rawSell = p[4].trim();
        String rawStock = p[5].trim();

        // plugin.getLogger().info("[Shop]   ID="
        //         + rawId + " 名=" + rawName
        //         + " 材质=" + rawMat
        //         + " 购=" + rawBuy + " 售=" + rawSell
        //         + " 库=" + rawStock);  // 过滤调试日志

        // 检查材质是否存在
        Material mat = Material.matchMaterial(rawMat);
        if (mat == null) {
            // plugin.getLogger().warning(
            //         "[Shop] ✗ 无效材质: " + rawMat
            //                 + " (行: " + row + ")");
            return null;
        }

        try {
            ShopItem item = new ShopItem(
                    rawId, rawName, mat,
                    Integer.parseInt(rawBuy),
                    Integer.parseInt(rawSell),
                    Integer.parseInt(rawStock),
                    p.length > 6
                            ? Integer.parseInt(
                            p[6].trim()) : 0,
                    p.length > 7
                            ? Integer.parseInt(
                            p[7].trim()) : 0);
            // plugin.getLogger().info("[Shop] ✓ 解析成功: " + rawId);  // 过滤调试日志
            return item;
        } catch (Exception e) {
            // plugin.getLogger().warning(
            //         "[Shop] ✗ 数字解析失败: "
            //                 + e.getMessage());
            return null;
        }
    }


// ===== 保存为 md =====

    public void saveCategory(ShopCategory cat) {
        File dir = new File(
                plugin.getDataFolder(), "shop");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, cat.getFileName());
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            pw.println("# " + cat.getName());
            pw.println("");
            pw.println("| ID | 品名 | 材质"
                    + " | 购入价 | 售出价"
                    + " | 库存"
                    + " | 本小时销量 | 总销量 |");
            pw.println("| --- | --- | ---"
                    + " | --- | --- | ---"
                    + " | --- | --- |");
            for (ShopItem item : cat.getItems()) {
                pw.println("| "
                        + item.getId()
                        + " | " + item.getDisplayName()
                        + " | " + item.getMaterial().name()
                        + " | " + item.getBuyPrice()
                        + " | " + item.getSellPrice()
                        + " | " + item.getStock()
                        + " | " + item.getHourlySales()
                        + " | " + item.getTotalSales()
                        + " |");
            }
            pw.flush();
            pw.close();
        } catch (Exception e) {
            // plugin.getLogger().warning(
            //         "[Shop] 保存失败: "
            //                 + cat.getFileName());
        }
    }

// ===== 创建示例分类 =====

    private void createDefaultCategories(File dir) {
        writeMd(dir, "建筑材料.md",
                "# 建筑材料\n"
                        + "\n"
                        + "| ID | 品名 | 材质"
                        + " | 购入价 | 售出价"
                        + " | 库存"
                        + " | 本小时销量 | 总销量 |\n"
                        + "| --- | --- | ---"
                        + " | --- | --- | ---"
                        + " | --- | --- |\n"
                        + "| STONE | 石头 | STONE"
                        + " | 10 | -1 | -1 | 0 | 0 |\n"
                        + "| COBBLESTONE | 圆石 | COBBLESTONE"
                        + " | 5 | -1 | -1 | 0 | 0 |\n"
                        + "| OAK_PLANKS | 橡木木板 | OAK_PLANKS"
                        + " | 8 | 3 | -1 | 0 | 0 |\n"
                        + "| BRICKS | 砖块 | BRICKS"
                        + " | 12 | -1 | -1 | 0 | 0 |\n"
                        + "| GLASS | 玻璃 | GLASS"
                        + " | 15 | 6 | -1 | 0 | 0 |\n"
                        + "| QUARTZ_BLOCK | 石英块 | QUARTZ_BLOCK"
                        + " | 20 | 8 | -1 | 0 | 0 |\n");

        writeMd(dir, "装备工具.md",
                "# 装备工具\n"
                        + "\n"
                        + "| ID | 品名 | 材质"
                        + " | 购入价 | 售出价"
                        + " | 库存"
                        + " | 本小时销量 | 总销量 |\n"
                        + "| --- | --- | ---"
                        + " | --- | --- | ---"
                        + " | --- | --- |\n"
                        + "| IRON_PICKAXE | 铁镐 | IRON_PICKAXE"
                        + " | 50 | 20 | -1 | 0 | 0 |\n"
                        + "| DIAMOND_PICKAXE | 钻石镐 | DIAMOND_PICKAXE"
                        + " | 500 | 200 | -1 | 0 | 0 |\n"
                        + "| IRON_SWORD | 铁剑 | IRON_SWORD"
                        + " | 40 | 16 | -1 | 0 | 0 |\n"
                        + "| DIAMOND_SWORD | 钻石剑 | DIAMOND_SWORD"
                        + " | 400 | 160 | -1 | 0 | 0 |\n"
                        + "| ELYTRA | 鞘翅 | ELYTRA"
                        + " | 2000 | 800 | -1 | 0 | 0 |\n");

        writeMd(dir, "食物补给.md",
                "# 食物补给\n"
                        + "\n"
                        + "| ID | 品名 | 材质"
                        + " | 购入价 | 售出价"
                        + " | 库存"
                        + " | 本小时销量 | 总销量 |\n"
                        + "| --- | --- | ---"
                        + " | --- | --- | ---"
                        + " | --- | --- |\n"
                        + "| COOKED_BEEF | 熟牛排 | COOKED_BEEF"
                        + " | 15 | 6 | -1 | 0 | 0 |\n"
                        + "| GOLDEN_APPLE | 金苹果 | GOLDEN_APPLE"
                        + " | 500 | 200 | -1 | 0 | 0 |\n"
                        + "| BREAD | 面包 | BREAD"
                        + " | 8 | 3 | -1 | 0 | 0 |\n");

        // plugin.getLogger().info(
        //         "[Shop] 已创建3个示例分类(md)");
    }

    private void writeMd(File dir, String name,
                         String content) {
        File f = new File(dir, name);
        if (f.exists()) return;
        try {
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(f),
                            StandardCharsets.UTF_8));
            pw.print(content);
            pw.flush();
            pw.close();
        } catch (Exception e) {
            // plugin.getLogger().warning(
            //         "[Shop] 创建失败: " + name);
        }
    }

    /**
     * 从商品名称+材质智能推断完整物品
     *
     * "夜视药水" + POTION
     *   → PotionEffect(NIGHT_VISION, 600, 0)
     *
     * "喷溅型治疗药水" + SPLASH_POTION
     *   → PotionEffect(HEALING, 600, 0)
     *
     * "长效夜视之箭" + TIPPED_ARROW
     *   → PotionEffect(NIGHT_VISION, 1800, 0)
     *
     * "强效力量喷溅药水" + SPLASH_POTION
     *   → PotionEffect(STRENGTH, 600, 1)
     */
    private ItemStack inferFromName(
            String name, Material mat) {
        // 只处理药水和箭
        if (mat != Material.POTION
                && mat != Material.SPLASH_POTION
                && mat != Material.LINGERING_POTION
                && mat != Material.TIPPED_ARROW) {
            return null;
        }

        // 1. 找效果类型
        PotionEffectType effectType = null;
        for (Map.Entry<String, PotionEffectType> e
                : EFFECT_MAP.entrySet()) {
            if (name.contains(e.getKey())) {
                effectType = e.getValue();
                break;
            }
        }
        if (effectType == null) return null;

        // 2. 找时长
        int duration = 600;
        if (name.contains("长效") || name.contains("长时")
                || name.contains("持久") || name.contains("长命")) {
            duration = 1800;
        }
// ★ 瞬间效果药水：duration = 1
        if (name.contains("治疗") || name.contains("治愈")
                || name.contains("伤害")) {
            duration = 1;
        }

        // 3. 找等级
        int amplifier = 0;
        if (name.contains("强效")
                || name.contains("强化")
                || name.contains("加强")) {
            amplifier = 1;
        }

        // 4. 构造完整物品
        try {
            ItemStack item = new ItemStack(mat);
            PotionMeta meta =
                    (PotionMeta) item.getItemMeta();

            // ★ 神龟药水：双效果
            if (name.contains("神龟")) {
                meta.addCustomEffect(
                        new PotionEffect(
                                PotionEffectType.RESISTANCE,
                                duration, amplifier + 2),
                        true);
                meta.addCustomEffect(
                        new PotionEffect(
                                PotionEffectType.SLOWNESS,
                                duration, amplifier + 3),
                        true);
            } else {
                // 普通药水：单效果
                meta.addCustomEffect(
                        new PotionEffect(effectType,
                                duration, amplifier),
                        true);
            }

            item.setItemMeta(meta);
            return item;
        } catch (Exception e) {
            return null;
        }
    }

        public void saveAll() {
            for (ShopCategory cat : categories)
                saveCategory(cat);
            saveMaxSellConfig();
        }

        // ===== maxSell 配置持久化 =====
        /** 加载 maxSell 配置从插件数据文件夹下的 maxsell_config.txt */
        private void loadMaxSellConfig() {
            File cfgFile = new File(plugin.getDataFolder(), "maxsell_config.txt");
            try {
                if (cfgFile.exists()) {
                    BufferedReader br = new BufferedReader(new FileReader(cfgFile));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("maxSellLimit=")) {
                            try {
                                maxSellLimit = Integer.parseInt(line.substring("maxSellLimit=".length()));
                            } catch (NumberFormatException ignored) {}
                        } else if (line.startsWith("maxSellMessageEnabled=")) {
                            maxSellMessageEnabled = Boolean.parseBoolean(line.substring("maxSellMessageEnabled=".length()));
                        } else if (line.startsWith("maxSellMessage=")) {
                            maxSellMessage = line.substring("maxSellMessage=".length());
                        }
                    }
                    br.close();
                } else {
                    // 配置文件不存在，使用默认值并保存
                    maxSellLimit = 15000;
                    maxSellMessage = "[玩家]本小时已出售(used),剩余<u>[limit]</u>。下次重置时间:{下次重置时间}";
                    maxSellMessageEnabled = true;
                    saveMaxSellConfig();
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[Shop] 加载maxsell配置失败: " + e.getMessage());
                maxSellLimit = 15000;
                maxSellMessage = "[玩家]本小时已出售(used),剩余<u>[limit]</u>。下次重置时间:{下次重置时间}";
                maxSellMessageEnabled = true;
            }
        }

        /** 保存 maxSell 配置到插件数据文件夹下的 maxsell_config.txt */
        private void saveMaxSellConfig() {
            synchronized (maxSellLock) {
                try {
                    File dir = new File(plugin.getDataFolder(), "shop");
                    if (!dir.exists()) dir.mkdirs();
                    File cfgFile = new File(dir.getParentFile(), "maxsell_config.txt");
                    BufferedWriter bw = new BufferedWriter(new FileWriter(cfgFile, StandardCharsets.UTF_8));
                    bw.write("maxSellLimit=" + maxSellLimit);
                    bw.newLine();
                    bw.write("maxSellMessageEnabled=" + maxSellMessageEnabled);
                    bw.newLine();
                    bw.write("maxSellMessage=" + maxSellMessage);
                    bw.close();
                } catch (Exception e) {
                    plugin.getLogger().warning("[Shop] 保存maxsell配置失败: " + e.getMessage());
                }
            }
        }
    public ItemStack getShopStack(
            ShopItem item, int amount) {
        // 优先级1: 手动捕获的NBT数据
        ItemStack cached =
                itemStackCache.get(item.getId());
        if (cached != null) {
            ItemStack copy = cached.clone();
            copy.setAmount(amount);
            return copy;
        }
        // 优先级2: 根据名称智能推断
        ItemStack inferred = inferFromName(
                item.getDisplayName(),
                item.getMaterial());
        if (inferred != null) {
            ItemStack copy = inferred.clone();
            copy.setAmount(amount);
            return copy;
        }
        // 优先级3: 纯材质（默认）
        return new ItemStack(
                item.getMaterial(), amount);
    }

    // ===== 购买 =====

    public boolean buyItem(Player p, ShopItem item,
                           int amount) {
        // plugin.getLogger().info("[Shop] ★ buyItem item=" + (item != null ? item.getId() : "null") + " amount=" + amount);
        // plugin.getLogger().info("buyItem被调用");
        if (item == null) return false;
        if (item.getStock() >= 0
                && item.getStock() < amount) {
            p.sendMessage("§c库存不足，剩余: "
                    + item.getStock());
            return false;
        }
        if (p.getInventory().firstEmpty() == -1
                && !p.getInventory().contains(
                item.getMaterial(), 1)) {
            p.sendMessage("§c背包已满");
            return false;
        }

        int unit = item.getBuyPrice();
        int disc = activeDiscount
                .getOrDefault(p.getUniqueId(), 0);
        int total = Math.max(0,
                unit * amount * (100 - disc) / 100);

        int balance = plugin.getBonds()
                .getBonds(p.getName());
        if (balance < total) {
            p.sendMessage("§c债券不足，需要 §e"
                    + total + "§c枚，当前 §e"
                    + balance + "§c枚");
            return false;
        }

        boolean ok = plugin.getBonds().deductBonds(
                p.getName(), total,
                "shop_buy", item.getId(),
                "商店系统", "购买"
                        + item.getDisplayName());
        if (!ok) {
            p.sendMessage("§c扣款失败");
            return false;
        }

        ItemStack give = getShopStack(item, amount);
        HashMap<Integer, ItemStack> left =
                p.getInventory().addItem(give);
        for (ItemStack drop : left.values())
            p.getWorld().dropItemNaturally(
                    p.getLocation(), drop);

        if (item.getStock() >= 0)
            item.setStock(
                    item.getStock() - amount);
        item.addSales(amount);

        mergeOrAddRefund(p, item, amount, total);
        Long applyTime = couponApplyTime.get(p.getUniqueId());
        if (applyTime != null
                && System.currentTimeMillis() - applyTime > 300000L) {
            // 超过5分钟，自动失效
            pendingCouponCode.remove(p.getUniqueId());
            activeDiscount.remove(p.getUniqueId());
            couponApplyTime.remove(p.getUniqueId());
            p.sendMessage("§7优惠券已过期，请重新输入");
            disc = 0;  // 重置折扣
        }


        String discMsg = disc > 0
                ? " §7(优惠" + disc + "%)" : "";
        p.sendMessage("§a购买成功: §e"
                + item.getDisplayName()
                + " x" + amount
                + " §a-" + total + "枚债券"
                + discMsg);
        if (disc > 0)
            activeDiscount.remove(
                    p.getUniqueId());
        // ★ 直购成功后核销优惠券
        String usedCode = pendingCouponCode
                .remove(p.getUniqueId());
        if (usedCode != null) {
            activeDiscount.remove(p.getUniqueId());
            couponApplyTime.remove(p.getUniqueId());
            try {
                Plugin sdf1 = Bukkit.getPluginManager()
                        .getPlugin("SDF1");
                if (sdf1 != null && sdf1.isEnabled()) {
                    java.lang.reflect.Method m =
                            sdf1.getClass().getMethod(
                                    "redeemCoupon",
                                    String.class);
                    m.invoke(sdf1, usedCode);
                }
            } catch (Exception ignored) {}
            p.sendMessage("§7优惠券 §e" + usedCode
                    + " §7已核销");
            couponApplyTime.remove(p.getUniqueId());
        }
        // ★ 记录订单（包裹try-catch，失败不影响购买）
        try {
            String usedCouponCode =
                    pendingCouponCode.get(p.getUniqueId());
            List<OrderManager.OrderItem> orderItems =
                    new ArrayList<>();
            orderItems.add(new OrderManager.OrderItem(
                    item.getDisplayName(),
                    item.getMaterial().name(),
                    item.getBuyPrice(),
                    item.getBuyPrice(), amount));
            plugin.getOrderManager().recordOrder(
                    p, orderItems,
                    item.getBuyPrice() * amount, total,
                    item.getBuyPrice() * amount - total,
                    usedCouponCode != null ? "coupon" : "none",
                    usedCouponCode != null ? usedCouponCode : "",
                    0, "none", "");
            // 打印小票
            List<OrderManager.OrderRecord> all =
                    plugin.getOrderManager().getOrders();
            if (!all.isEmpty()) {
                plugin.getOrderManager().sendReceiptChat(
                        p, all.get(all.size() - 1));
            }
        } catch (Exception ex) {
            // plugin.getLogger().warning(
            //         "[Shop] 记录订单失败: " + ex.getMessage());
        }

        return true;
    }


    public void handleRefundItemClick(Player p, int raw) {
        if (raw < 10 || raw >= 45) return;
        int target = raw - 10;
        int cnt = 0;
        synchronized (refundRecords) {
            Iterator<RefundRecord> it =
                    refundRecords.iterator();
            while (it.hasNext()) {
                RefundRecord r = it.next();
                if (!r.playerUUID.equals(p.getUniqueId()))
                    continue;
                if (r.isExpired()) { it.remove(); continue; }
                if (cnt == target) {
                    requestRefund(p, r);
                    return;
                }
                cnt++;
            }
        }
    }


    // ===== 出售 =====

    /**
     * HTML解码器，支持:
     * - &颜色代码 (如 &c红色, &a绿色)
     * - <b>加粗</b>, <u>下划线</u>, <i>斜体</i>
     * - <br>换行
     * - <p style="...">段落</p> (支持color属性)
     */
    private String decodeHtml(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;

        // 1. 先处理 &颜色代码 → §颜色代码
        result = result.replace("&0", "§0");
        result = result.replace("&1", "§1");
        result = result.replace("&2", "§2");
        result = result.replace("&3", "§3");
        result = result.replace("&4", "§4");
        result = result.replace("&5", "§5");
        result = result.replace("&6", "§6");
        result = result.replace("&7", "§7");
        result = result.replace("&8", "§8");
        result = result.replace("&9", "§9");
        result = result.replace("&a", "§a");
        result = result.replace("&b", "§b");
        result = result.replace("&c", "§c");
        result = result.replace("&d", "§d");
        result = result.replace("&e", "§e");
        result = result.replace("&f", "§f");
        // 样式代码
        result = result.replace("&k", "§k");
        result = result.replace("&l", "§l");
        result = result.replace("&m", "§m");
        result = result.replace("&n", "§n");
        result = result.replace("&o", "§o");
        result = result.replace("&r", "§r");

        // 2. 处理HTML标签
        // <u>标签：§n 开始下划线，§r 切断（只作用于标签内）
        // <b>标签：§l 开始加粗，§r 切断
        // <i>标签：§o 开始斜体，§r 切断
        result = result.replaceAll("<u>(.*?)</u>", "§n$1§r");
        result = result.replaceAll("<b>(.*?)</b>", "§l$1§r");
        result = result.replaceAll("<i>(.*?)</i>", "§o$1§r");
        // 3. 处理 <p style="color:#xxx"> 或 <p style="color:xxx">
        java.util.regex.Pattern pPattern = java.util.regex.Pattern.compile(
            "<p\\s+style=[\"']color:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z_]+|rgb\\([^)]+\\)|rgba\\([^)]+\\)|hsl\\([^)]+\\)|hsla\\([^)]+\\))\\s*;?[\"']\\s*>(.*?)</p>",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher pMatcher = pPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (pMatcher.find()) {
            String color = pMatcher.group(1);
            String text = pMatcher.group(2);
            String mcColor = convertToMcColor(color);
            pMatcher.appendReplacement(sb, mcColor + text + "§r");
        }
        pMatcher.appendTail(sb);
        result = sb.toString();
        result = result.replaceAll("<br>", "\n");
        result = result.replaceAll("<br/>", "\n");

        // 4. 处理 <p>段落</p> (无style) → 换行分隔
        result = result.replaceAll("<p>(.*?)</p>", "$1\n");

        // 5. 移除残留的style标签属性
        result = result.replaceAll("<p[^>]*>", "");
        result = result.replaceAll("</p>", "");

        return result;
    }

    /**
     * 将颜色值转换为Minecraft颜色代码
     * 支持: #RRGGBB 十六进制, 或颜色名称
     */
    private String convertToMcColor(String color) {
        if (color == null || color.isEmpty()) return "";
        // 处理 rgb/rgba 颜色函数
        String lowerColor = color.toLowerCase();
        if (lowerColor.startsWith("rgb(") || lowerColor.startsWith("rgba(")) {
            try {
                // 提取括号内的内容
                int start = color.indexOf('(') + 1;
                int end = color.lastIndexOf(')');
                if (start > 0 && end > start) {
                    String content = color.substring(start, end);
                    String[] parts = content.split(",");
                    if (parts.length >= 3) {
                        int r = Integer.parseInt(parts[0].trim());
                        int g = Integer.parseInt(parts[1].trim());
                        int b = Integer.parseInt(parts[2].trim());
                        // 转换为十六进制
                        String hex = String.format("#%02x%02x%02x", r, g, b);
                        // 递归调用，使用十六进制逻辑
                        return convertToMcColor(hex);
                    }
                }
            } catch (Exception e) {
                // 解析失败，返回默认白色
                return "§f";
            }
        }
        // 处理 hsl/hsla 颜色函数（简单映射到最接近的Minecraft颜色）
        if (lowerColor.startsWith("hsl(") || lowerColor.startsWith("hsla(")) {
            // 由于HSL解析复杂，暂时返回默认白色
            return "§f";
        }
        // 十六进制颜色
        if (color.startsWith("#")) {
            String hex = color.substring(1).toLowerCase();
            if (hex.equals("ff5555") || hex.equals("ff0000")) return "§c";
            if (hex.equals("55ff55") || hex.equals("00ff00")) return "§a";
            if (hex.equals("5555ff") || hex.equals("0000ff")) return "§9";
            if (hex.equals("ffff55") || hex.equals("ffff00")) return "§e";
            if (hex.equals("ffffff")) return "§f";
            if (hex.equals("000000")) return "§0";
            if (hex.equals("a0a0a0") || hex.equals("808080")) return "§7";
            if (hex.equals("ffaa00")) return "§6";
            if (hex.equals("55ffff")) return "§b";
            if (hex.equals("ff55ff")) return "§d";
            if (hex.equals("aa0000")) return "§4";
            if (hex.equals("0000aa")) return "§1";
            if (hex.equals("00aa00")) return "§2";
            if (hex.equals("00aaaa")) return "§3";
            if (hex.equals("aa00aa")) return "§5";
            if (hex.equals("555555")) return "§8";
            return "§f"; // 默认白色
        }
        // 颜色名称
        String name = color.toLowerCase().replace("_", "");
        if (name.equals("red") || name.equals("darkred") || name.equals("dark_red") || name.equals("crimson") || name.equals("salmon") || name.equals("tomato") || name.equals("coral")) return "§c";
        if (name.equals("green") || name.equals("darkgreen") || name.equals("dark_green") || name.equals("lime") || name.equals("chartreuse") || name.equals("olive") || name.equals("olivegreen")) return "§a";
        if (name.equals("blue") || name.equals("darkblue") || name.equals("dark_blue") || name.equals("darkaqua") || name.equals("navy") || name.equals("indigo")) return "§9";
        if (name.equals("yellow") || name.equals("gold") || name.equals("goldenrod")) return "§e";
        if (name.equals("white") || name.equals("silver") || name.equals("snow") || name.equals("ivory")) return "§f";
        if (name.equals("black") || name.equals("onyx") || name.equals("jetblack")) return "§0";
        if (name.equals("gray") || name.equals("grey") || name.equals("darkgray") || name.equals("darkgrey") || name.equals("dimgray") || name.equals("dimgrey")) return "§7";
        if (name.equals("aqua") || name.equals("lightblue") || name.equals("turquoise") || name.equals("teal")) return "§b";
        if (name.equals("lightpurple") || name.equals("purple") || name.equals("magenta") || name.equals("plum") || name.equals("orchid") || name.equals("violet")) return "§d";
        if (name.equals("darkred")) return "§4";
        if (name.equals("darkblue")) return "§1";
        if (name.equals("darkgreen")) return "§2";
        if (name.equals("darkaqua") || name.equals("cyan")) return "§3";
        if (name.equals("darkpurple")) return "§5";
        if (name.equals("darkgray") || name.equals("darkgrey")) return "§8";
        if (name.equals("lightgray") || name.equals("lightgrey")) return "§7";
        if (name.equals("orange") || name.equals("orangered")) return "§6";
        if (name.equals("pink") || name.equals("hotpink") || name.equals("lightpink")) return "§d";
        if (name.equals("brown") || name.equals("saddlebrown") || name.equals("sienna") || name.equals("chocolate")) return "§4";
        return "§f"; // 默认白色
    }

    // ===== 占位符替换 =====
    private String replacePlaceholders(String msg, String player, int used, int limit, String time) {
        if (msg == null || msg.isEmpty()) return msg;
        // 支持中英文占位符，任意括号
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]玩家[(\\(\\)\\[\\]\\{\\}【】)]", player);
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]player[(\\(\\)\\[\\]\\{\\}【】)]", player);
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]已出售金额[(\\(\\)\\[\\]\\{\\}【】)]", String.valueOf(used));
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]used[(\\(\\)\\[\\]\\{\\}【】)]", String.valueOf(used));
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]已使用额度[(\\(\\)\\[\\]\\{\\}【】)]", String.valueOf(used));
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]剩余额度[(\\(\\)\\[\\]\\{\\}【】)]", String.valueOf(limit));
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]limit[(\\(\\)\\[\\]\\{\\}【】)]", String.valueOf(limit));
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]下次重置时间[(\\(\\)\\[\\]\\{\\}【】)]", time);
        msg = msg.replaceAll("[(\\(\\)\\[\\]\\{\\}【】)]time[(\\(\\)\\[\\]\\{\\}【】)]", time);
        //同时支持%player%等格式
        msg = msg.replace("%player%", player);
        msg = msg.replace("%used%", String.valueOf(used));
        msg = msg.replace("%limit%", String.valueOf(limit));
        msg = msg.replace("%time%", time);
        return msg;
    }

    public boolean sellItem(Player p, ShopItem item,
                            int amount) {
        if (item == null) return false;

        // ★ 先判断售价，禁止出售的不收物品
        if (item.getEffectiveSellPrice() <= 0) {
            p.sendMessage("§c该商品不可出售");
            return false;
        }

        // ★ 出售限额检查
        if (maxSellLimit != -1) {
            int todaySold = plugin.getBonds().getTodaySellTotal(p.getName());
            int remaining = maxSellLimit - todaySold;
            int saleAmount = item.getEffectiveSellPrice() * amount;
            if (saleAmount > remaining) {
                if (maxSellMessageEnabled && maxSellMessage != null && !maxSellMessage.isEmpty()) {
                    String msg = decodeHtml(maxSellMessage);
                    // 计算下次重置时间（最早交易 + 1小时）
                    long earliestTime = plugin.getBonds().getEarliestSellTimeInWindow(p.getName());
                    String resetTime;
                    if (earliestTime > 0) {
                        resetTime = new java.text.SimpleDateFormat("HH:mm")
                                .format(new java.util.Date(earliestTime + 3600000L));
                    } else {
                        resetTime = "现在";
                    }
                    msg = replacePlaceholders(msg, p.getName(), todaySold, remaining, resetTime);
                    p.sendMessage(msg);
                }
                // 提示消息关闭时不打印任何消息
                return false;
            }
        }

        if (!p.getInventory().containsAtLeast(
                getShopStack(item, 1), amount)) {
            p.sendMessage("§c你没有足够的 §e"
                    + item.getDisplayName());
            return false;
        }

        int total = item.getEffectiveSellPrice() * amount;

        p.getInventory().removeItem(
                getShopStack(item, amount));
        plugin.getBonds().addBonds(
                p.getName(), total, "shop_sell",
                item.getId(), "商店系统",
                "出售" + item.getDisplayName());
        if (item.getStock() >= 0)
            item.setStock(item.getStock() + amount);
        p.sendMessage("§a出售成功: §e"
                + item.getDisplayName()
                + " x" + amount
                + " §a+" + total + "枚债券");
        return true;
    }

    // ===== 退款合并：30秒内同商品合并为一个订单 =====
    private boolean mergeOrAddRefund(Player p, ShopItem item,
                                     int amount, int total) {
        long now = System.currentTimeMillis();
        synchronized (refundRecords) {
            for (int i = refundRecords.size() - 1; i >= 0; i--) {
                RefundRecord r = refundRecords.get(i);
                boolean same = r.playerUUID.equals(p.getUniqueId())
                        && r.itemId.equals(item.getId())
                        && (now - r.timestamp) < 30000L;
                if (same) {
                    // final 字段不能改，只能删旧建新
                    int newAmount = r.amount + amount;
                    int newTotal = r.totalPaid + total;
                    refundRecords.remove(i);
                    refundRecords.add(new RefundRecord(
                            r.playerUUID, r.playerName,
                            r.itemId, r.itemName,
                            r.material, newAmount,
                            newTotal, now));
                    return true;
                }
            }
        }
        refundRecords.add(new RefundRecord(
                p.getUniqueId(), p.getName(),
                item.getId(), item.getDisplayName(),
                item.getMaterial(), amount, total, now));
        return false;
    }



    // ===== 退款 =====

    public boolean requestRefund(Player p, RefundRecord rec) {
        if (rec == null) return false;
        if (!rec.playerUUID.equals(p.getUniqueId())) {
            p.sendMessage("§c这不是你的购买记录");
            return false;
        }
        if (rec.isExpired()) {
            p.sendMessage("§c已超过5分钟退款期限");
            synchronized (refundRecords) {
                refundRecords.remove(rec);
            }
            return false;
        }

        // 检测物品/潜影盒是否还在
        synchronized (refundRecords) {
            if (rec.shulkerMaterial != null) {
                int boxCount = countShulkers(p, rec.shulkerMaterial);
                if (boxCount < 1) {
                    p.sendMessage("§c潜影盒已移出背包，无法退款");
                    refundRecords.remove(rec);
                    return false;
                }
            } else {
                int have = countDirect(p, rec.material);
                if (have < rec.amount) {
                    p.sendMessage("§c物品已转移或消耗，无法退款");
                    refundRecords.remove(rec);
                    return false;
                }
            }
        }

        // 退款：扣物品/潜影盒
        if (rec.shulkerMaterial != null) {
            // 盒装：扣一个潜影盒
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack is = p.getInventory().getItem(i);
                if (is != null && is.getType() == rec.shulkerMaterial) {
                    p.getInventory().clear(i);
                    break;
                }
            }
        } else {
            // 普通：扣商品
            p.getInventory().removeItem(
                    new ItemStack(rec.material, rec.amount));
        }

        // 退款：加债券
        plugin.getBonds().addBonds(
                p.getName(), rec.totalPaid,
                "refund", rec.itemId,
                "商店系统", "退款" + rec.itemName);

        // 恢复库存
        ShopItem si = findItemById(rec.itemId);
        if (si != null && si.getStock() >= 0)
            si.setStock(si.getStock() + rec.amount);

        synchronized (refundRecords) {
            refundRecords.remove(rec);
        }

        p.sendMessage("§a退款成功: §e" + rec.itemName
                + " §a+" + rec.totalPaid + "枚债券");
        return true;
    }




    public void setPlayerDiscount(Player p,
                                  int pct) {
        activeDiscount.put(
                p.getUniqueId(), pct);
        p.sendMessage("§a已设置 " + pct
                + "% 折扣，下次购买生效");
    }

    // ===== 管理员操作 =====

    public boolean addItemToCategory(
            String catName, String id,
            String displayName, Material mat,
            int buyPrice, int sellPrice,
            int stock) {
        ShopCategory cat =
                findCategory(catName);
        if (cat == null) return false;
        if (cat.getItem(id) != null) return false;
        cat.addItem(new ShopItem(id, displayName,
                mat, buyPrice, sellPrice,
                stock, 0, 0));
        saveCategory(cat);
        return true;
    }

    public boolean removeItem(String id) {
        for (ShopCategory cat : categories) {
            ShopItem item = cat.getItem(id);
            if (item != null) {
                cat.getItems().remove(item);
                saveCategory(cat);
                return true;
            }
        }
        return false;
    }

    public boolean setStock(String id, int stock) {
        ShopItem item = findItemById(id);
        if (item == null) return false;
        item.setStock(stock);
        for (ShopCategory cat : categories) {
            if (cat.getItem(id) != null) {
                saveCategory(cat);
                return true;
            }
        }
        return false;
    }

    // ===== PHP同步：更新商品价格 =====
    public boolean updateItemPrice(String id, int buyPrice, int sellPrice) {
        ShopItem item = findItemById(id);
        if (item == null) return false;
        item.setBuyPrice(buyPrice);
        item.setSellPrice(sellPrice);
        for (ShopCategory cat : categories) {
            if (cat.getItem(id) != null) {
                saveCategory(cat);
                return true;
            }
        }
        return false;
    }

    // ===== PHP同步：批量更新商品价格和库存 =====
    public int updateItemPrices(List<Map<String, Object>> items) {
        int updated = 0;
        for (Map<String, Object> data : items) {
            String id = (String) data.get("id");
            if (id == null) continue;

            ShopItem item = findItemById(id);
            if (item == null) continue;

            boolean changed = false;
            if (data.containsKey("buy_price")) {
                int newBuyPrice = ((Number) data.get("buy_price")).intValue();
                if (item.getBuyPrice() != newBuyPrice) {
                    item.setBuyPrice(newBuyPrice);
                    changed = true;
                }
            }
            if (data.containsKey("sell_price")) {
                int newSellPrice = ((Number) data.get("sell_price")).intValue();
                if (item.getSellPrice() != newSellPrice) {
                    item.setSellPrice(newSellPrice);
                    changed = true;
                }
            }
            if (data.containsKey("stock")) {
                int newStock = ((Number) data.get("stock")).intValue();
                if (item.getStock() != newStock) {
                    item.setStock(newStock);
                    changed = true;
                }
            }
            if (changed) {
                // 找到并保存对应的分类
                for (ShopCategory cat : categories) {
                    if (cat.getItem(id) != null) {
                        saveCategory(cat);
                        updated++;
                        break;
                    }
                }
            }
        }
        return updated;
    }

    public ShopCategory findCategory(String n) {
        for (ShopCategory c : categories)
            if (c.getName().equals(n)) return c;
        return null;
    }

    public ShopItem findItemById(String id) {
        for (ShopCategory c : categories) {
            ShopItem item = c.getItem(id);
            if (item != null) return item;
        }
        return null;
    }

    public List<ShopCategory> getCategories() {
        return categories;
    }

    // ===== 命令处理 =====

    public boolean handleCommand(
            CommandSender s, String[] a) {
        if (a.length <= 1) {
            if (s instanceof Player) {
                openShopMain((Player) s);
            } else {
                s.sendMessage("§c仅玩家可使用");
            }
            return true;
        }

        String sub = a[1].toLowerCase();

        if (sub.equals("reload")) {
            loadCategories();
            loadMaxSellConfig();
            s.sendMessage("§a商店已重载，共 "
                    + categories.size()
                    + " 个分类");
            return true;
        }

        if (sub.equals("setmax")) {
            if (a.length < 3) {
                s.sendMessage(
                        "§e用法: shop setmax <金额(-1无限)>");
                return true;
            }
            try {
                int limit = Integer.parseInt(a[2]);
                maxSellLimit = limit;
                saveMaxSellConfig();
                if (limit == -1) {
                    s.sendMessage("§a出售限额已设为无限");
                } else {
                    s.sendMessage("§a出售限额已设为 §e" + limit + " §a枚债券/小时");
                }
            } catch (NumberFormatException e) {
                s.sendMessage("§c无效数字: " + a[2]);
            }
            return true;
        }

        if (sub.equals("maxmsg")) {
            if (a.length < 3) {
                s.sendMessage(
                        "§e用法: shop maxmsg <内容/关闭/默认>");
                return true;
            }
            // 拼接剩余参数作为消息内容
            String msg = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            if (msg.equalsIgnoreCase("关闭") || msg.equalsIgnoreCase("off")) {
                maxSellMessageEnabled = false;
                saveMaxSellConfig();
                s.sendMessage("§a出售限额提示已关闭");
            } else if (msg.equalsIgnoreCase("默认") || msg.equalsIgnoreCase("default")) {
                maxSellMessage = "[玩家]本小时已出售(used),剩余<u>[limit]</u>。下次重置时间:{下次重置时间}";
                maxSellMessageEnabled = true;
                saveMaxSellConfig();
                s.sendMessage("§a出售限额提示已设为默认内容");
            } else {
                maxSellMessage = msg;
                maxSellMessageEnabled = true;
                saveMaxSellConfig();
                s.sendMessage("§a出售限额提示已设置");
            }
            return true;
        }

        if (sub.equals("add")) {
            if (a.length < 7) {
                s.sendMessage(
                        "§e用法: shop add <分类>"
                                + " <ID> <名称> <材质>"
                                + " <购入价>"
                                + " [售出价-1自动]"
                                + " [库存-1无限]");
                return true;
            }
            Material mat;
            try {
                mat = Material.valueOf(
                        a[5].toUpperCase());
            } catch (Exception e) {
                s.sendMessage(
                        "§c无效材质: " + a[5]);
                return true;
            }
            int price = Integer.parseInt(a[6]);
            int sell = a.length > 7
                    ? Integer.parseInt(a[7]) : -1;
            int stock = a.length > 8
                    ? Integer.parseInt(a[8]) : -1;
            if (addItemToCategory(a[2], a[3],
                    a[4], mat, price, sell, stock)) {
                s.sendMessage("§a已添加: "
                        + a[4] + " → " + a[2]);
            } else {
                s.sendMessage("§c添加失败"
                        + "(分类不存在或ID重复)");
            }
            return true;
        }

        if (sub.equals("remove")) {
            if (a.length < 3) {
                s.sendMessage(
                        "§e用法: shop remove <ID>");
                return true;
            }
            if (removeItem(a[2])) {
                s.sendMessage(
                        "§a已移除: " + a[2]);
            } else {
                s.sendMessage(
                        "§c未找到: " + a[2]);
            }
            return true;
        }

        if (sub.equals("set")) {
            if (a.length < 4) {
                s.sendMessage(
                        "§e用法: shop set <ID>"
                                + " <库存数(-1无限)>");
                return true;
            }
            int stock = Integer.parseInt(a[3]);
            if (setStock(a[2], stock)) {
                s.sendMessage("§a已设置 "
                        + a[2] + " 库存为 "
                        + stock);
            } else {
                s.sendMessage(
                        "§c未找到: " + a[2]);
            }
            return true;
        }

        // 环保单减免：shop setgreen <1-10> （10=不减免，1-9.99=按比例减免%）
        if (sub.equals("setgreen")) {
            if (a.length < 3) {
                s.sendMessage(
                        "§e用法: shop setgreen <1-10>"
                                + " （10=不减免，1-9.99=按比例减免%）");
                return true;
            }
            try {
                double v = Double.parseDouble(a[2]);
                int gv = (int) Math.floor(v);
                if (gv < 0) gv = 0;
                if (gv >= 10) gv = 0; // 10 视为不减免
                plugin.getConfigMgr().greenDiscount = gv;
                plugin.webManager.pushShopConfig("green_discount", String.valueOf(gv));
                s.sendMessage("§a环保单减免已设置为 " + gv
                        + "%（10=不减免，已同步至Web配置）");
            } catch (NumberFormatException e) {
                s.sendMessage("§c参数必须为数字");
            }
            return true;
        }

        if (sub.equals("pro")) {
            if (a.length < 4) {
                s.sendMessage(
                        "§e用法: shop pro"
                                + " <玩家名> <折扣%>");
                return true;
            }
            Player t =
                    Bukkit.getPlayerExact(a[2]);
            if (t == null) {
                s.sendMessage("§c玩家不在线");
                return true;
            }
            int pct = Integer.parseInt(a[3]);
            setPlayerDiscount(t, pct);
            s.sendMessage("§a已对 " + a[2]
                    + " 设置 " + pct + "% 折扣");
            return true;
        }


        s.sendMessage("§e商店子命令:"
                + " reload / add / remove"
                + " / set / pro ");
        return true;
    }
    @EventHandler(priority = EventPriority.LOW)
    public void onCouponChat(
            org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!couponListening.contains(uuid)) return;
        e.setCancelled(true);
        couponListening.remove(uuid);
        String code = e.getMessage().trim();
        if (code.isEmpty()) {
            p.sendMessage("§c已取消输入");
            return;
        }
        // ★ 预处理：标准化输入
        String normalized = normalizeCouponInput(code);
        if (normalized.isEmpty()) {
            p.sendMessage("§c优惠券码无效（输入为空或仅含特殊字符）");
            couponListening.remove(uuid);
            return;
        }

        p.sendMessage("§7正在验证优惠券: §e" + code);

        int discount = -1;
        try {
            Plugin sdf1 = Bukkit.getPluginManager()
                    .getPlugin("SDF1");
            if (sdf1 != null && sdf1.isEnabled()) {
                java.lang.reflect.Method m =
                        sdf1.getClass().getMethod(
                                "validateCoupon",
                                String.class);
                Object result = m.invoke(sdf1, normalized);
                discount = (result != null)
                        ? (Integer) result : -1;
            }
        } catch (Exception ex) {
            p.sendMessage("§c验证异常: "
                    + ex.getMessage());
            return;
        }
        if (discount <= 0) {
            p.sendMessage("§c优惠券无效或已使用");
            return;
        }
        int offPct = 100 - discount;
        int current = activeDiscount
                .getOrDefault(uuid, 0);
        if (offPct > current) {
            pendingCouponCode.put(uuid, normalized);
            activeDiscount.put(uuid, offPct);
            couponApplyTime.put(uuid, System.currentTimeMillis());
            String zhe;
            if (discount % 10 == 0)
                zhe = (discount / 10) + "折";
            else
                zhe = (discount / 10.0) + "折";
            p.sendMessage("§6§l[优惠券] §a验证通过!");
            p.sendMessage("§7折扣: §e" + zhe
                    + " §7(减" + offPct + "%)");
            p.sendMessage("§7结算时自动核销");
            final UUID u = uuid;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player pl = Bukkit.getPlayer(u);
                if (pl != null && pl.isOnline()) {
                    openShopMain(pl);
                }
            });
        } else {
            String curZhe;
            if ((100 - current) % 10 == 0)
                curZhe = ((100 - current) / 10) + "折";
            else
                curZhe = ((100 - current) / 10.0) + "折";
            p.sendMessage("§c当前已有更优折扣: "
                    + curZhe + "，本次不生效");
        }
    }

    /**
     * 优惠券输入标准化
     * 全角→半角、去分隔符、转小写
     * "AAA-BBB-CCCC" → "aaaabbbbcccc"
     * "ＡＡＡ－ＢＢＢ" → "aaabbb"
     * "aaa_bbb_cccc" → "aaabbbcccc"
     */
    private String normalizeCouponInput(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 全角数字→半角
            if (c >= '\uFF10' && c <= '\uFF19') {
                sb.append((char)(c - 0xFF10 + '0'));
            }
            // 全角字母→半角
            else if (c >= '\uFF21' && c <= '\uFF3A') {
                sb.append((char)(c - 0xFF21 + 'A'));
            }
            else if (c >= '\uFF41' && c <= '\uFF5A') {
                sb.append((char)(c - 0xFF41 + 'a'));
            }
            // ★ 保留中文字符（Unicode CJK统一汉字范围）
            else if ((c >= '\u4E00' && c <= '\u9FFF')
                    || (c >= '\u3400' && c <= '\u4DBF')) {
                sb.append(c);
            }
            // 保留半角字母和数字
            else if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
            // 分隔符（- _ 空格等）丢弃
        }
        return sb.toString().toLowerCase();
    }





    private String formatZhe(int discount) {
        if (discount % 10 == 0) {
            return (discount / 10) + "折";
        }
        return (discount / 10.0) + "折";
    }

    public boolean redeemCoupon(Player p, String code) {
        int discount = -1;
        try {
            Plugin sdf1 = Bukkit.getPluginManager()
                    .getPlugin("SDF1");
            if (sdf1 != null && sdf1.isEnabled()) {
                java.lang.reflect.Method m =
                        sdf1.getClass().getMethod(
                                "redeemCoupon",
                                String.class);
                Object result = m.invoke(sdf1, code);
                discount = (result != null)
                        ? (Integer) result : -1;
            }
        } catch (Exception e) {
            return false;
        }
        if (discount <= 0) return false;
        int offPct = 100 - discount;
        int cur = activeDiscount
                .getOrDefault(p.getUniqueId(), 0);
        if (offPct > cur) {
            activeDiscount.put(p.getUniqueId(), offPct);
        }
        return true;
    }

    /**
     * 商店主界面点击"优惠券"按钮后调用
     * 开启聊天监听，等待玩家输入优惠券码
     */
    public void openCouponInput(Player p) {
        UUID uuid = p.getUniqueId();
        // 先取消已有的待核销优惠券
        pendingCouponCode.remove(uuid);
        couponListening.add(uuid);
        // 关闭UI
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.closeInventory();
        });
        p.sendMessage("§6§l[优惠券] §e请在聊天栏输入优惠券码");
        p.sendMessage("§715秒内输入，过期自动取消");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (couponListening.remove(uuid)) {
                Player pl = Bukkit.getPlayer(uuid);
                if (pl != null && pl.isOnline()) {
                    pl.sendMessage("§c优惠券输入已超时取消");
                }
            }
        }, 300L);
    }


    // ===== GUI: 主界面 =====

    public void openShopMain(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§6§l商店");
        int si = 0;
        for (ShopCategory cat : categories) {
            if (si >= ITEM_SLOTS.length) break;
            ItemStack is = new ItemStack(
                    Material.CHEST);
            ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setDisplayName(
                        "§e§l" + cat.getName());
                im.setLore(Arrays.asList(
                        "§7商品: §f"
                                + cat.getItems().size()
                                + " 种",
                        "",
                        "§a点击进入"));
                is.setItemMeta(im);
            }
            g.setItem(ITEM_SLOTS[si], is);
            si++;
        }
        // ★ 改为跳转订单中心
        g.setItem(49, mkItem(Material.CLOCK,
                "§6§l订单中心",
                "§7查看订单记录、申请退款",
                "", "§a点击进入"));

        // 在 openShopMain 方法中，原来的 slot 49/53 附近加：

        g.setItem(48, mkItem(
                isCartMode(p)
                        ? Material.LIME_WOOL
                        : Material.GRAY_WOOL,
                isCartMode(p)
                        ? "§a§l购物车模式 §7[开]"
                        : "§7§l购物车模式 §7[关]",
                isCartMode(p)
                        ? "§a当前: 点击商品加入购物车"
                        : "§7当前: 直接购买",
                "§7点击切换"));
        g.setItem(50, mkItem(Material.CHEST,
                "§6§l购物车 §7[" + getCartCount(p) + "件]",
                "§7总价: §6" + getCartTotal(p) + "枚",
                "",
                "§a点击打开购物车"));

        // 在现有按钮之后，p.openInventory(g) 之前加：
        g.setItem(49, mkItem(Material.CLOCK,
                "§6§l订单中心",
                "§7查看购物订单记录",
                "", "§a点击进入"));

        // 优惠券按钮 - slot 47
        String discStatus = "§7";
        int curDisc = activeDiscount
                .getOrDefault(p.getUniqueId(), 0);
        if (curDisc > 0) {
            discStatus = "§a当前: " + formatZhe(100 - curDisc);
        }
        g.setItem(47, mkItem(Material.NAME_TAG,
                "§6§l优惠券",
                discStatus,
                "",
                "§a点击输入优惠券码",
                "§7一次性使用，核销后失效"));

        g.setItem(53, mkItem(Material.ARROW,
                "§7返回"));
     
        p.openInventory(g);
    }

    // ===== GUI: 分类 =====
    public void openCategory(Player p,
                             ShopCategory cat,
                             int page) {
        // ★ 每次重新获取最新对象 ★
        ShopCategory fresh = null;
        for (ShopCategory c : categories) {
            if (c.getName().equals(cat.getName())) {
                fresh = c;
                break;
            }
        }
        if (fresh == null) {
            openShopMain(p);
            return;
        }
        cat = fresh;
        viewCat.put(p.getUniqueId(), cat);

        int total = (int) Math.ceil(
                (double) cat.getItems().size()
                        / PAGE_SIZE);
        if (total < 1) total = 1;
        if (page < 0) page = 0;
        if (page >= total) page = total - 1;
        viewPage.put(p.getUniqueId(), page);

        Inventory g = Bukkit.createInventory(
                null, 54,
                "§6§l" + cat.getName());


        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE,
                cat.getItems().size());

        for (int i = start; i < end; i++) {
            int idx = i - start;
            if (idx >= ITEM_SLOTS.length) break;

            // ★ item 保留原名，代表 ShopItem 数据
            ShopItem item = cat.getItems().get(i);

            // ★ is 是带完整元数据的 ItemStack（用于GUI图标）
            ItemStack is = getShopStack(item, 1);
            ItemMeta im = is.getItemMeta();

            if (im != null) {
                im.setDisplayName("§e§l"
                        + item.getDisplayName());
                List<String> lore = new ArrayList<>();
                lore.add("§7购入: §a"
                        + item.getBuyPrice()
                        + "§7枚债券");
                lore.add("§7售出: §e"
                        + item.getEffectiveSellPrice()
                        + "§7枚债券");
                if (item.getStock() >= 0)
                    lore.add("§7库存: §f"
                            + item.getStock());
                else
                    lore.add("§7库存: §a无限");
                lore.add("");
                int disc = activeDiscount
                        .getOrDefault(p.getUniqueId(), 0);
                if (disc > 0) {
                    int dis = item.getBuyPrice()
                            * (100 - disc) / 100;
                    lore.add("§7优惠价: §6"
                            + dis + "枚"
                            + " §7(-" + disc + "%)");
                }
                lore.add("§a左键买1  §6右键卖1");
                lore.add("§aShift+左键买64"
                        + " §6Shift+右键卖64");
                im.setLore(lore);
                is.setItemMeta(im);
            }

            // ★ 用 is（ItemStack）放入GUI格子
            g.setItem(ITEM_SLOTS[idx], is);
        }


        g.setItem(4, mkItem(Material.BOOK,
                "§e§l" + cat.getName(),
                "§7共 " + cat.getItems().size()
                        + " 种商品"));
        g.setItem(48, page > 0
                ? mkItem(Material.ARROW,
                "§7上一页")
                : mkItem(Material
                         .BLACK_STAINED_GLASS_PANE, " "));
        g.setItem(49, mkItem(Material.PAPER,
                "§e" + (page + 1) + "/" + total));
        g.setItem(50, page < total - 1
                ? mkItem(Material.ARROW,
                "§7下一页")
                : mkItem(Material
                         .BLACK_STAINED_GLASS_PANE, " "));
        g.setItem(53, mkItem(Material.ARROW,
                "§7返回商店"));
     
        p.openInventory(g);
    }

    // ===== GUI: 退款 =====

    public void openRefundPanel(Player p) {
        Inventory g = Bukkit.createInventory(
                null, 54, "§c§l退款中心");

        // 位移检测：比对上次记录的背包内容
        validateRefunds(p);

        // 过期清理
        synchronized (refundRecords) {
            refundRecords.removeIf(RefundRecord::isExpired);
        }

        int idx = 0;
        synchronized (refundRecords) {
            for (RefundRecord r : refundRecords) {
                if (idx >= 45) break;
                if (!r.playerUUID.equals(p.getUniqueId())) continue;
                if (r.isExpired()) continue;

                // 盒装用潜影盒图标，普通用商品图标
                Material iconMat = (r.shulkerMaterial != null)
                        ? r.shulkerMaterial : r.material;

                ItemStack is = new ItemStack(iconMat);
                ItemMeta im = is.getItemMeta();
                if (im != null) {
                    int secs = r.getSecondsLeft();
                    im.setDisplayName("§e" + r.itemName
                            + (r.shulkerMaterial != null
                            ? " §7[盒装]" : " x" + r.amount));

                    List<String> lore = new ArrayList<>();
                    lore.add("§7订单: §f#" + r.timestamp);
                    lore.add("§7支付: §a" + r.totalPaid
                            + "§7枚债券");

                    if (r.shulkerMaterial != null) {
                        int boxCount = countShulkers(p,
                                r.shulkerMaterial);
                        lore.add("§7潜影盒: §f"
                                + (boxCount >= 1
                                ? "§a在背包中"
                                : "§c已移出"));
                    } else {
                        int have = countDirect(p, r.material);
                        lore.add("§7物品: §f" + have
                                + "/" + r.amount);
                    }

                    lore.add("§7剩余: §e" + secs + "秒");
                    lore.add("");
                    lore.add("§c点击退款");
                    im.setLore(lore);
                }
                is.setItemMeta(im);
                g.setItem(10 + idx, is);
                idx++;
            }
        }

        if (idx == 0) {
            g.setItem(22, mkItem(Material.BARRIER,
                    "§7暂无可退款记录"));
        }

        g.setItem(49, mkItem(Material.ARROW, "§7返回"));
     
        p.openInventory(g);
    }
    // 统计背包中某物品数量
    private int countInInventory(Player p, Material mat) {
        int count = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == mat) {
                count += is.getAmount();
            }
        }
        // 潜影盒也要检查里面
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null
                    && is.getType().name()
                    .endsWith("_SHULKER_BOX")) {
                count += is.getAmount();
            }
        }
        return count;
    }

    // 只统计背包中直接可见的数量（不含盒内）
    // 新代码：增加按完整物品匹配的方法
    private int countDirect(Player p, Material mat) {
        int count = 0;
        for (ItemStack is :
                p.getInventory().getContents()) {
            if (is != null && is.getType() == mat)
                count += is.getAmount();
        }
        return count;
    }

    private int countDirectByStack(Player p,
                                   ItemStack target) {
        int count = 0;
        for (ItemStack is :
                p.getInventory().getContents()) {
            if (is != null && is.isSimilar(target))
                count += is.getAmount();
        }
        return count;
    }
    // 统计背包中潜影盒数量
    private int countShulkers(Player p, Material shulker) {
        if (shulker == null) return Integer.MAX_VALUE;
        int count = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == shulker)
                count += is.getAmount();
        }
        return count;
    }

    // 退款检测：位移 OR 消耗 → 不能退
    private void validateRefunds(Player p) {
        synchronized (refundRecords) {
            Iterator<RefundRecord> it =
                    refundRecords.iterator();
            while (it.hasNext()) {
                RefundRecord r = it.next();
                if (!r.playerUUID.equals(p.getUniqueId()))
                    continue;
                if (r.isExpired()) {
                    it.remove();
                    continue;
                }

                if (r.shulkerMaterial != null) {
                    // 盒装：检测潜影盒是否还在背包
                    int now = countShulkers(
                            p, r.shulkerMaterial);
                    if (now < 1) {
                        it.remove();
                        p.sendMessage("§c[退款] " + r.itemName
                                + " 潜影盒已移出背包，无法退款");
                    }
                } else {
                    // 普通：检测商品是否还在背包
                    int now = countDirect(p, r.material);
                    if (now < r.amount) {
                        it.remove();
                        p.sendMessage("§c[退款] " + r.itemName
                                + " 物品已被使用或移出，无法退款");
                    }
                }
            }
        }
    }


    public int getPlayerPage(java.util.UUID uuid) {
        return viewPage.getOrDefault(uuid, 0);
    }

    public ShopCategory getPlayerCategory(java.util.UUID uuid) {
        return viewCat.get(uuid);
    }

    // ===== 供GUIManager调用：商店主界面点击 =====
    public boolean handleMainShopClick(Player p, int raw) {
        if (raw == 49) { plugin.getOrderManager().openOrderCenter(p); return true; }

        if (raw == 48) {
            toggleCartMode(p);
            openShopMain(p);
            return true;
        }
        if (raw == 50) {
            openCart(p);
            return true;
        }
        if (raw == 53) {
            plugin.getGui().openMain(p);
            return true;
        }

        // ★ 新增：优惠券按钮
        if (raw == 47) {
            openCouponInput(p);
            return true;
        }
        if (raw == 49) { plugin.getOrderManager().openOrderCenter(p); return true;}
            int idx = slotToIndex(raw);
        if (idx < 0 || idx >= categories.size())
            return true;
        openCategory(p, categories.get(idx), 0);
        return true;
    }

    // ===== 供GUIManager调用：退款中心点击 =====
    public boolean handleRefundClick(Player p, int raw) {
        if (raw == 49) { openShopMain(p); return true; }
        return true;
    }

    // ===== 供GUIManager调用：分类页点击 =====
    public boolean handleCategoryClick(Player p, String title,
                                       int raw, boolean left,
                                       boolean shift) {
    //    plugin.getLogger().info("[Shop] ★ handleCategoryClick raw=" + raw);

        ShopManager sm = this;
        ShopCategory match = null;
        for (ShopCategory c : categories) {
            if (title.equals("§6§l" + c.getName())) {
                match = c;
                break;
            }
        }
        if (match == null) {
   //         plugin.getLogger().info("[Shop] ★ 匹配失败, title=[" + title + "]");
            return false;
        }
   //     plugin.getLogger().info("[Shop] ★ 匹配到: " + match.getName());

        viewCat.put(p.getUniqueId(), match);

        if (raw == 53) { openShopMain(p); return true; }
        int pg = getPlayerPage(p.getUniqueId());
        if (raw == 48) { openCategory(p, match, pg - 1); return true; }
        if (raw == 50) { openCategory(p, match, pg + 1); return true; }

        int itemIdx = slotToIndex(raw);
        if (itemIdx < 0) {
            // plugin.getLogger().info("[Shop] ★ slot=" + raw + " 不是商品格");
            return true;
        }
        int actual = itemIdx + pg * PAGE_SIZE;
        if (actual < 0 || actual >= match.getItems().size()) {
        //    plugin.getLogger().info("[Shop] ★ 超出范围 actual=" + actual + " size=" + match.getItems().size());
            return true;
        }
        ShopItem si = match.getItems().get(actual);
   //     plugin.getLogger().info("[Shop] ★ 准备购买: " + si.getId() + " left=" + left);

        int amount = shift ? 64 : 1;
        if (left) {
            if (shift && isCartMode(p)) { addToCart(p, si); }
            else if (shift) { buyItem(p, si, 64); }
            else if (isCartMode(p)) { addToCart(p, si); }
            else { buyItem(p, si, 1); }
        } else {
            if (shift) { sellAllOf(p, si); }
            else { sellItem(p, si, 1); }
        }
        openCategory(p, match, pg);
        return true;
    }

    // ===== 批量出售某商品全部数量 =====
    private void sellAllOf(Player p, ShopItem item) {
        if (item == null) return;
        // ★ 补上禁止出售检查
        if (item.getEffectiveSellPrice() <= 0) {
            p.sendMessage("§c该商品不可出售");
            return;
        }
        if (item == null) return;
        Material mat = item.getMaterial();
        int total = 0;
        int count = 0;
        // 遍历背包清点
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType() == mat) {
                total += is.getAmount();
                count++;
            }
        }
        // ★ 出售限额检查
        if (maxSellLimit != -1 && total > 0) {
            int todaySold = plugin.getBonds().getTodaySellTotal(p.getName());
            int remaining = maxSellLimit - todaySold;
            int saleAmount = item.getEffectiveSellPrice() * total;
            if (saleAmount > remaining) {
                if (maxSellMessageEnabled && maxSellMessage != null && !maxSellMessage.isEmpty()) {
                    String msg = decodeHtml(maxSellMessage);
                    long earliestTime = plugin.getBonds().getEarliestSellTimeInWindow(p.getName());
                    String resetTime;
                    if (earliestTime > 0) {
                        resetTime = new java.text.SimpleDateFormat("HH:mm")
                                .format(new java.util.Date(earliestTime + 3600000L));
                    } else {
                        resetTime = "现在";
                    }
                    msg = replacePlaceholders(msg, p.getName(), todaySold, remaining, resetTime);
                    p.sendMessage(msg);
                }
                // 提示消息关闭时不打印任何消息
                return;
            }
        }

        if (total <= 0) {
            p.sendMessage("§c你没有可出售的 §e"
                    + item.getDisplayName());
            return;
        }
        // 清除背包中该物品
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType() == mat) {
                p.getInventory().clear(i);
            }
        }
        int money = item.getEffectiveSellPrice() * total;
        plugin.getBonds().addBonds(
                p.getName(), money, "shop_sell",
                item.getId(), "商店系统",
                "批量出售" + item.getDisplayName()
                        + " x" + total);
        if (item.getStock() >= 0)
            item.setStock(item.getStock() + total);
        p.sendMessage("§a批量出售: §e"
                + item.getDisplayName()
                + " x" + total
                + " §a+" + money + "枚债券");
    }


    // ===== 定时 =====

    private void startHourlyTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // ★ 汇报交给 SalesStatsManager
                if (plugin.getSalesStats() != null) {
                    plugin.getSalesStats().report();
                }
                // 重置小时销量
                for (ShopCategory cat : categories)
                    for (ShopItem item : cat.getItems())
                        item.setHourlySales(0);
                saveAll();
            }
        }.runTaskTimer(plugin, 72000L, 72000L);
    }


    private void startCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                synchronized (refundRecords) {
                    refundRecords.removeIf(
                            RefundRecord::isExpired);
                }
            }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    // ===== 工具 =====

    private int slotToIndex(int slot) {
        for (int i = 0; i < ITEM_SLOTS.length; i++)
            if (ITEM_SLOTS[i] == slot) return i;
        return -1;
    }

    private ItemStack mkItem(Material mat,
                             String name,
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
