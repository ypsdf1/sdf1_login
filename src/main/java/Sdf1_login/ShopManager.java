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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.NamespacedKey;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;




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
    // ===== 二手交易商店（2026-10-11 /shopbuy）=====
    private final List<SecondHandListing> secondHandListings =
            new ArrayList<>();
    // 待定价上架：玩家UUID → 已暂存的物品（等待聊天输入售价）
    private final Map<UUID, ItemStack> pendingPublishItem =
            new HashMap<>();
    private final Set<UUID> publishListening =
            new HashSet<>();
    // 发布第二步：UUID → 商品描述（等输入售价时暂存）
    private final Map<UUID, String> pendingPublishDesc = new HashMap<>();
    // 发布步骤：1=等商品描述，2=等售价
    private final Map<UUID, Integer> publishStep = new HashMap<>();
    // ===== 批量上架 GUI（2026-10-11 任务2）=====
    /** 批量上架 GUI 标题 */
    private static final String T_SH_PUBLISH = "§6§l二手上架";
    // 批量定价队列：UUID → 尚未定价的物品（当前件同时暂存在 pendingPublishItem）
    private final Map<UUID, java.util.LinkedList<ItemStack>> publishQueue =
            new HashMap<>();
    // 批量总件数（用于进度提示）
    private final Map<UUID, Integer> publishQueueTotal = new HashMap<>();
    // ===== 编辑模式（2026-10-11 任务1）：UUID → 正在编辑的商品ID =====
    private final Map<UUID, Long> editListingId = new HashMap<>();
    // ★ 会话令牌（2026-10-11 第五批）：进入新会话/新步骤时 +1，
    //   旧的超时计时器触发时令牌不符即作废（防止级联误杀新会话）
    private final Map<UUID, Integer> publishSessionSeq = new HashMap<>();
    // ===== 出售偏好（2026-10-11 任务4）：手续费承担模式 =====
    /** 奸商模式：手续费由买方出（买家支付 = 售价 + 手续费） */
    public static final String FEE_MODE_BUYER = "BUYER";
    /** 正常模式：手续费由卖方出（卖家到手 = 售价 - 手续费） */
    public static final String FEE_MODE_SELLER = "SELLER";
    // ===== 二手交易全局配置（shop/二手交易规则.md，用户组可覆盖数量/最低价）=====
    // 每人同时最多上架数（普通玩家默认值，来自配置文件）
    private int shMaxListings = 1;
    // 最低售价（锁死 >=0，来自配置文件）
    private int shMinPrice = 0;
    // 手续费比例（0.05 = 5%，钳制 [0,10]）
    private double shFeeRate = 0.05;
    // 手续费支付方：true=买方出（订单加费），false=卖方出（到手价已含税）
    private boolean shFeeByBuyer = true;
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

    // ===== 附魔书：中文附魔名 → Bukkit Enchantment =====
    private static final Map<String, Enchantment> ENCHANT_MAP = new HashMap<>();
    static {
        regEnchant("锋利", "sharpness");
        regEnchant("亡灵杀手", "smite");
        regEnchant("节肢杀手", "bane_of_arthropods");
        regEnchant("击退", "knockback");
        regEnchant("火焰附加", "fire_aspect");
        regEnchant("掠夺", "looting");
        regEnchant("横扫之刃", "sweeping_edge");
        regEnchant("保护", "protection");
        regEnchant("火焰保护", "fire_protection");
        regEnchant("摔落保护", "feather_falling");
        regEnchant("爆炸保护", "blast_protection");
        regEnchant("弹射物保护", "projectile_protection");
        regEnchant("水下呼吸", "respiration");
        regEnchant("深海探索者", "depth_strider");
        regEnchant("冰霜行者", "frost_walker");
        regEnchant("荆棘", "thorns");
        regEnchant("精准采集", "silk_touch");
        regEnchant("效率", "efficiency");
        regEnchant("耐久", "unbreaking");
        regEnchant("时运", "fortune");
        regEnchant("经验修补", "mending");
        regEnchant("力量", "power");
        regEnchant("冲击", "punch");
        regEnchant("火焰", "flame");
        regEnchant("无限", "infinity");
        regEnchant("穿透", "piercing");
        regEnchant("激流", "riptide");
        regEnchant("忠诚", "loyalty");
        regEnchant("引雷", "channeling");
        regEnchant("多重射击", "multishot");
        regEnchant("快速装填", "quick_charge");
        regEnchant("海之眷顾", "luck_of_the_sea");
        regEnchant("饵钓", "lure");
        regEnchant("绑定诅咒", "binding_curse");
        regEnchant("消失诅咒", "vanishing_curse");
        regEnchant("穿刺", "impaling");
    }
    private static void regEnchant(String cn, String key) {
        try {
            // ★ 优先用现代 Registry API（paper-api 新版 Enchantment.getByKey 可能返回 null，导致 ENCHANT_MAP 为空→附魔书全空壳）
            Enchantment e = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
            if (e == null) e = Enchantment.getByKey(NamespacedKey.minecraft(key));
            if (e != null) ENCHANT_MAP.put(cn, e);
        } catch (Throwable ignored) {}
    }

    public static class CartItem {
        String itemId;
        String displayName;
        Material material;
        int unitPrice;
        int quantity;
    }

    // ===== 二手交易商店：一条玩家上架的商品（2026-10-11）=====
    public static class SecondHandListing {
        long id;              // 自增ID，用于唯一标识与下架
        String seller;        // 上架玩家名
        int price;            // 售价（债券），不低于最低售价
        String desc;          // 卖家描述（可空，≤20字符，2026-10-11）
        String itemB64;       // 完整 ItemStack 的 Base64 序列化
        long createdAt;       // 上架时间戳（毫秒）
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
        loadSecondHandConfig();
        startShCfgReloadTask();
        loadSecondHand();
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
                                  String packNote, double packFee,
                                  double ecoPct, int couponPct) {

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
                    p.getName(), (int) Math.round(packFee),
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
                ShopItem si = findItemById(ci.itemId);
                ItemStack giveStack = (si != null) ? getShopStack(si, ci.quantity) : null;
                // ★ 记录真实物品(含NBT，如附魔书)的快照，供退款时精确匹配收回实物
                String nbtB64 = (giveStack != null) ? java.util.Base64.getEncoder().encodeToString(giveStack.serializeAsBytes()) : "";
                orderItems.add(
                        new OrderManager.OrderItem(
                                ci.displayName,
                                ci.material.name(),
                                ci.unitPrice,
                                ci.unitPrice,
                                ci.quantity, nbtB64));
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
                    (int) (baseTotal + Math.round(packFee)),
                    originalTotal - baseTotal,
                    dType, cCode, (int) Math.round(packFee),
                    useShulker ? (packFee > 0
                                  ? "custom" : "default")
                            : "none",
                    color != null ? color.name() : "");
            // ★ 交易记录库（2026-10-11 任务5）：购物车订单逐条入库
            TradeLogManager cartTlog = plugin.getTradeLog();
            if (cartTlog != null) {
                for (int ti = 0; ti < cart.size(); ti++) {
                    CartItem tci = cart.get(ti);
                    cartTlog.recordNormalOrder(p.getName(), tci.displayName,
                            tci.quantity, tci.unitPrice, subs[ti],
                            couponPct, "cart");
                }
            }
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
        double ecoPct = 0;
        double packFeeCfg = plugin.getConfigMgr().packingFee;   // 彩色潜影盒打包费（配置）
        double greenPct = plugin.getConfigMgr().greenDiscount;  // 环保单折扣率（折数）：10=不打折，9.9=9.9折

        if (raw == 10) { // 不打包（环保单）→ 减免
            ecoPct = greenPct;
        }

        double factor = 1.0;
        // ★ green_discount 语义为“折扣率（折数）”：10=不打折，9.9=9.9折(支付99%)，9.8=9.8折(支付98%)
        if (ecoPct > 0)
            factor *= ecoPct / 10.0;
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
        double packFee = plugin.getConfigMgr().packingFee; // 打包费（彩色潜影盒加收，来自配置）
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
                        && !n.equals("coupons.md")
                        && !n.contains(".web")); // ★ 跳过 .web.md 文件（PHP专属商品，游戏内不加载）
        if (allMd == null || allMd.length == 0) {
            createDefaultCategories(dir);
            allMd = dir.listFiles(
                    (d, n) -> n.endsWith(".md")
                            && !n.equals("coupons.md")
                            && !n.contains(".web")); // ★ 跳过 .web.md（PHP专属商品）
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
            // ★ 容错：若 material 字段无效但 ID 符合附魔书命名模式（如 MENDING_I、SHARPNESS_III），
            //   则默认为 ENCHANTED_BOOK 而非静默跳过（否则该商品在 Java 端不存在 → PHP 扣款后无法发货）
            if (rawId.matches("(?i)^[A-Z][A-Z0-9]*_[IVX12]+$") || rawId.matches("(?i).*_(I{1,3}|II|III|IV|V|[1-5])$")) {
                mat = Material.ENCHANTED_BOOK;
                plugin.getLogger().warning("[Shop] 材质无效但ID符合附魔书格式，自动修正: id=" + rawId + " 原material=" + rawMat + " → ENCHANTED_BOOK");
            } else {
                return null;
            }
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

    /**
     * 保存单个分类到 .md 文件（合并感知，杜绝覆盖管理员手工新增内容）。
     *
     * ★ 2026-07-07 修复（严重缺陷）：原实现用 PrintWriter 对 .md 做整文件覆盖重写，
     *   仅写出内存 categories 列表中的商品。若管理员在服务器运行期间于磁盘上手工新增了
     *   商品（或修改了某个在内存中未被加载的分类文件），则整文件重写会把这些磁盘新增内容
     *   抹掉；服务器 stop 时 onDisable→saveAll 全量重写会进一步把新增分类/商品“回滚”，
     *   表现为“数据不同步 / 新商品被自动删除”。
     *
     * 新方案：写文件前先读取磁盘上已有的同文件，把“磁盘中存在但内存中不存在”的商品行原样
     * 保留（追加在内存商品之后），从而减少整文件覆盖带来的内容丢失。
     * 说明：整体新增的“独立分类 .md 文件”本来就不在内存 categories 中，saveAll 不会触碰它，
     * 因此是天然安全的；本修复主要堵住“在已有分类里手工加商品被覆盖”的漏洞。
     */
    /**
     * 保存单个分类到 .md 文件。
     *
     * ★ 2026-07-07 改为“不再写盘”（按用户明确要求“彻底删除复写”）：
     *   商品目录由管理员手工维护磁盘 .md 文件，插件只在内存中管理并单向同步给 PHP
     *   （buildCatalogJson → WebManager.pushShopCatalog）。任何运行时写盘都会抹掉
     *   管理员手工新增/修改的内容，故此处改为空操作，杜绝复写。
     */
    public void saveCategory(ShopCategory cat) {
        // 不再写盘：保留方法签名仅为兼容既有调用方（add/remove/set/PHP同步回调）。
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
    // ===== 附魔书：由显示名推断存储附魔 =====
    private static final java.util.regex.Pattern ROMAN_PAT =
            java.util.regex.Pattern.compile("(?i)\\b(X{0,3}(?:IX|IV|V|I{1,3}))\\b");
    private static final java.util.regex.Pattern ARABIC_PAT =
            java.util.regex.Pattern.compile("\\d+");
    private static final java.util.regex.Pattern CN_PAT =
            java.util.regex.Pattern.compile("[零一二三四五六七八九十百]+");

    private ItemStack inferEnchantedBook(String name) {
        try {
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
            if (meta == null) return null;
            boolean added = false;
            for (Map.Entry<String, Enchantment> e : ENCHANT_MAP.entrySet()) {
                if (!name.contains(e.getKey())) continue;
                // 若已有更具体的附魔词包含本词（如"火焰保护"命中时跳过"保护"），避免重复计入
                boolean shadowed = false;
                for (Map.Entry<String, Enchantment> o : ENCHANT_MAP.entrySet()) {
                    if (o != e && o.getKey().length() > e.getKey().length()
                            && o.getKey().contains(e.getKey()) && name.contains(o.getKey())) {
                        shadowed = true;
                        break;
                    }
                }
                if (shadowed) continue;
                int lvl = parseEnchantLevel(name, e.getKey());
                meta.addStoredEnchant(e.getValue(), lvl, true);
                added = true;
            }
            if (!added) return null;
            book.setItemMeta(meta);
            return book;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 由商品ID权威重建附魔书（ID格式 <enchantKey>_<level>，如 MENDING_I / SHARPNESS_V / FLAME_I / BREACH_IV / WIND_BURST_III）。
     * ID 使用标准 Bukkit 附魔键，比显示名可靠（显示名多变：火矢/破甲/致密/风爆/水下速掘 等不匹配关键词）。
     */
    private ItemStack buildEnchantedBookFromId(String id) {
        if (id == null) return null;
        int us = id.lastIndexOf('_');
        if (us <= 0 || us >= id.length() - 1) return null;
        String keyPart = id.substring(0, us).toLowerCase();
        String lvlPart = id.substring(us + 1).trim();
        if (lvlPart.isEmpty()) return null;
        org.bukkit.enchantments.Enchantment ench = null;
        try {
            ench = org.bukkit.Registry.ENCHANTMENT.get(NamespacedKey.minecraft(keyPart));
        } catch (Throwable ignored) {}
        if (ench == null) {
            try { ench = Enchantment.getByKey(NamespacedKey.minecraft(keyPart)); } catch (Throwable ignored) {}
        }
        if (ench == null) return null;
        int lvl = parseLevelFromRomanOrArabic(lvlPart);
        if (lvl < 1) lvl = 1;
        int max = ench.getMaxLevel();
        if (max > 0 && lvl > max) lvl = max;
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        if (meta == null) return null;
        meta.addStoredEnchant(ench, lvl, true);
        book.setItemMeta(meta);
        return book;
    }

    private int parseLevelFromRomanOrArabic(String s) {
        if (s == null || s.isEmpty()) return 1;
        String t = s.trim();
        java.util.regex.Matcher m = ROMAN_PAT.matcher(t);
        if (m.find()) { int v = romanToInt(m.group()); if (v >= 1) return v; }
        java.util.regex.Matcher m2 = ARABIC_PAT.matcher(t);
        if (m2.find()) {
            try { int v = Integer.parseInt(m2.group()); if (v >= 1) return v; } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    /**
     * 若传入的附魔书是“空壳”（无存储附魔），则尝试用商品ID权威重建附魔NBT。
     * 用于 Web 商城发放附魔书兜底，避免显示名解析失败/ENCHANT_MAP 为空导致空壳。
     * ★ 若 ID 重建也失败（解析失败），返回 null —— 由上层整单取消并退款，绝不发放空壳。
     */
    public ItemStack ensureEnchantedBookNbt(ItemStack itemStack, String itemId, int amount) {
        // ★ 单件购买（PHP shop_buy，name=null）时 itemStack 为 null：
        //   附魔书在 .web.md 中未被 findItemById 收录，前两路均取不到实物，
        //   故直接按商品ID权威建书，不再整单退款。
        if (itemStack == null) {
            ItemStack b = buildEnchantedBookFromId(itemId);
            if (b != null) {
                b.setAmount(amount);
                plugin.getLogger().info("[Shop] 单件附魔书由ID重建NBT(无实物兜底): " + itemId);
                return b;
            }
            plugin.getLogger().warning("[Shop] 单件附魔书无法由ID重建(解析失败)，取消发放: " + itemId);
            return null;
        }
        if (itemStack.getType() != Material.ENCHANTED_BOOK) return itemStack;
        ItemMeta m = itemStack.getItemMeta();
        boolean empty = !(m instanceof EnchantmentStorageMeta)
                || ((EnchantmentStorageMeta) m).getStoredEnchants().isEmpty();
        if (!empty) return itemStack; // 已是带附魔的有效书
        // 空壳 → 用商品ID权威重建
        ItemStack rebuilt = buildEnchantedBookFromId(itemId);
        if (rebuilt != null) {
            rebuilt.setAmount(amount);
            plugin.getLogger().info("[Shop] 附魔书空壳已用ID重建NBT: " + itemId);
            return rebuilt;
        }
        // ★ 解析失败：不再发放空壳，返回 null 交由上层整单取消/退款
        plugin.getLogger().warning("[Shop] 附魔书无法由ID重建(解析失败)，取消发放: " + itemId);
        return null;
    }

    private int parseEnchantLevel(String name, String keyword) {
        int idx = name.indexOf(keyword);
        if (idx < 0) return 1;
        String after = org.bukkit.ChatColor.stripColor(
                name.substring(idx + keyword.length()));
        java.util.regex.Matcher m = ROMAN_PAT.matcher(after);
        if (m.find()) return romanToInt(m.group());
        java.util.regex.Matcher m2 = ARABIC_PAT.matcher(after);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group()); } catch (NumberFormatException ignored) {}
        }
        java.util.regex.Matcher m3 = CN_PAT.matcher(after);
        if (m3.find()) return cnToInt(m3.group());
        return 1;
    }

    private int romanToInt(String s) {
        s = s.toUpperCase();
        int total = 0, prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v;
            switch (s.charAt(i)) {
                case 'I': v = 1; break;
                case 'V': v = 5; break;
                case 'X': v = 10; break;
                case 'L': v = 50; break;
                case 'C': v = 100; break;
                default: v = 0;
            }
            total += (v < prev) ? -v : v;
            prev = v;
        }
        return Math.max(1, total);
    }

    private int cnToInt(String s) {
        int total = 0, num = 0;
        for (int i = 0; i < s.length(); i++) {
            int d;
            switch (s.charAt(i)) {
                case '零': d = 0; break;
                case '一': d = 1; break;
                case '二': d = 2; break;
                case '三': d = 3; break;
                case '四': d = 4; break;
                case '五': d = 5; break;
                case '六': d = 6; break;
                case '七': d = 7; break;
                case '八': d = 8; break;
                case '九': d = 9; break;
                case '十': d = 10; break;
                case '百': d = 100; break;
                default: d = 0;
            }
            if (d == 10 || d == 100) {
                num = (num == 0 ? 1 : num) * d;
                total += num;
                num = 0;
            } else {
                num = d;
            }
        }
        total += num;
        return Math.max(1, total);
    }

    private ItemStack inferFromName(
            String name, Material mat) {
        // 附魔书：解析名称中的附魔词与等级，写入 EnchantmentStorageMeta
        if (mat == Material.ENCHANTED_BOOK) {
            return inferEnchantedBook(name);
        }
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
            File cfgFile = new File(new File(plugin.getDataFolder(), "shop"), "maxsell_config.txt");
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
                    File cfgFile = new File(dir, "maxsell_config.txt");
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

    /**
     * 根据商品显示名智能推断并构建物品（用于收银台购物车按名称重建附魔书等带NBT物品）。
     * 当 PHP 购物车携带真实显示名（含附魔关键词）时，可绕过"按材质匹配取目录首个附魔书导致NBT丢失"的问题。
     */
    public ItemStack getShopStackByName(String name, Material mat, int amount) {
        if (name == null || name.isEmpty()) {
            return new ItemStack(mat, amount);
        }
        ItemStack inferred = inferFromName(name, mat);
        if (inferred != null) {
            ItemStack copy = inferred.clone();
            copy.setAmount(amount);
            return copy;
        }
        return new ItemStack(mat, amount);
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
        // ★ 交易记录库（2026-10-11 任务5）：直购订单入库
        TradeLogManager dirTlog = plugin.getTradeLog();
        if (dirTlog != null) {
            dirTlog.recordNormalOrder(p.getName(), item.getDisplayName(),
                    amount, unit, total, disc, "direct");
        }
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
            // ★ 记录真实物品(含NBT)快照，供退款时精确匹配收回实物（避免"仅退款"）
            String nbtB64 = (give != null) ? java.util.Base64.getEncoder().encodeToString(give.serializeAsBytes()) : "";
            orderItems.add(new OrderManager.OrderItem(
                    item.getDisplayName(),
                    item.getMaterial().name(),
                    item.getBuyPrice(),
                    item.getBuyPrice(), amount, nbtB64));
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

    /**
     * 计算玩家在当前出售限额下还能卖多少单位（按单价）。
     * @return -1 表示不限额（任意数量都可卖）；0 表示已达上限无法再卖；
     *         其余为正整数，即 remaining / unitPrice 的可卖单位数。
     */
    private int getMaxSellableUnits(Player p, int unitPrice) {
        if (maxSellLimit == -1) return -1;       // 关闭限额
        if (unitPrice <= 0) return -1;           // 单价为0视为不限制
        int todaySold = plugin.getBonds().getTodaySellTotal(p.getName());
        int remaining = maxSellLimit - todaySold;
        if (remaining <= 0) return 0;            // 已达上限
        return remaining / unitPrice;            // 限额内可卖单位数
    }

    /**
     * 发送出售限额提示（含已售/剩余/下次重置时间占位符）。
     * 提示未开启或内容为空时静默不打印。
     */
    private void sendSellLimitMessage(Player p) {
        if (!(maxSellMessageEnabled && maxSellMessage != null && !maxSellMessage.isEmpty())) return;
        int todaySold = plugin.getBonds().getTodaySellTotal(p.getName());
        int remaining = maxSellLimit - todaySold;
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

    public boolean sellItem(Player p, ShopItem item,
                            int amount) {
        if (item == null) return false;

        // ★ 先判断售价，禁止出售的不收物品
        if (item.getEffectiveSellPrice() <= 0) {
            p.sendMessage("§c该商品不可出售");
            return false;
        }

        // ★ 出售限额检查（部分出售：超过限额只卖限额内部分，剩余不动）
        int unitPrice = item.getEffectiveSellPrice();
        int maxUnits = getMaxSellableUnits(p, unitPrice);   // -1 不限
        if (maxUnits == 0) {
            // 已达上限，无法再卖
            sendSellLimitMessage(p);
            return false;
        }
        int actual = (maxUnits < 0) ? amount : Math.min(amount, maxUnits);
        if (actual <= 0) return false;

        if (!p.getInventory().containsAtLeast(
                getShopStack(item, 1), actual)) {
            p.sendMessage("§c你没有足够的 §e"
                    + item.getDisplayName());
            return false;
        }

        int total = unitPrice * actual;

        p.getInventory().removeItem(
                getShopStack(item, actual));
        plugin.getBonds().addBonds(
                p.getName(), total, "shop_sell",
                item.getId(), "商店系统",
                "出售" + item.getDisplayName());
        if (item.getStock() >= 0)
            item.setStock(item.getStock() + actual);

        if (maxUnits >= 0 && actual < amount) {
            // 部分出售：提示剩余未出售
            p.sendMessage("§a出售成功: §e"
                    + item.getDisplayName()
                    + " x" + actual
                    + " §a+" + total + "枚债券");
            p.sendMessage("§e剩余 " + (amount - actual) + " 个因出售限额未出售，已留在背包");
            sendSellLimitMessage(p);
        } else {
            p.sendMessage("§a出售成功: §e"
                    + item.getDisplayName()
                    + " x" + actual
                    + " §a+" + total + "枚债券");
        }
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
            // 普通：扣商品（用实际发放的物品做精确匹配，避免带NBT/改名物品删不掉导致"退货不扣货"）
            ShopItem si = findItemById(rec.itemId);
            ItemStack target = (si != null)
                    ? getShopStack(si, rec.amount)
                    : new ItemStack(rec.material, rec.amount);
            p.getInventory().removeItem(target);
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

    /**
     * 将游戏内完整商品目录序列化为 JSON（供定时同步到 PHP Web 端）
     *
     * 结构：
     * {
     *   "categories": [
     *     { "name":"分类名", "file":"文件名.md",
     *       "items":[ { "id":"...", "display_name":"...", "material":"DIAMOND",
     *                   "buy_price":100, "sell_price":50 }, ... ] },
     *     ...
     *   ]
     * }
     * 注意：仅同步目录结构（分类/商品/价格），库存由 PHP 端动态管理，不在本 JSON 中覆盖。
     */
    public String buildCatalogJson() {
        JsonArray cats = new JsonArray();

        // 第一遍：内存中的普通分类（不含 .web.md）
        for (ShopCategory cat : categories) {
            JsonObject catObj = new JsonObject();
            catObj.addProperty("name", cat.getName());
            catObj.addProperty("file", cat.getFileName());
            JsonArray items = new JsonArray();
            for (ShopItem it : cat.getItems()) {
                JsonObject itObj = new JsonObject();
                itObj.addProperty("id", it.getId());
                itObj.addProperty("display_name", it.getDisplayName());
                itObj.addProperty("material", it.getMaterial() == null ? "STONE" : it.getMaterial().name());
                itObj.addProperty("buy_price", it.getBuyPrice());
                itObj.addProperty("sell_price", it.getSellPrice());
                items.add(itObj);
            }
            catObj.add("items", items);
            cats.add(catObj);
        }

        // ★ 第二遍：扫描 .web.md 文件（PHP专属商品，游戏内不加载但需同步给 PHP）
        File shopDir = new File(plugin.getDataFolder(), "shop");
        if (shopDir.exists()) {
            File[] webMdFiles = shopDir.listFiles((d, n) -> n.endsWith(".web.md"));
            if (webMdFiles != null) {
                for (File webMd : webMdFiles) {
                    ShopCategory webCat = parseFile(webMd);
                    if (webCat != null && !webCat.getItems().isEmpty()) {
                        JsonObject catObj = new JsonObject();
                        catObj.addProperty("name", webCat.getName());
                        catObj.addProperty("file", webMd.getName()); // 含 .web.md 后缀，PHP 可据此区分来源
                        JsonArray items = new JsonArray();
                        for (ShopItem it : webCat.getItems()) {
                            JsonObject itObj = new JsonObject();
                            itObj.addProperty("id", it.getId());
                            itObj.addProperty("display_name", it.getDisplayName());
                            itObj.addProperty("material", it.getMaterial() == null ? "STONE" : it.getMaterial().name());
                            itObj.addProperty("buy_price", it.getBuyPrice());
                            itObj.addProperty("sell_price", it.getSellPrice());
                            items.add(itObj);
                        }
                        catObj.add("items", items);
                        cats.add(catObj);
                    }
                }
            }
        }

        JsonObject root = new JsonObject();
        root.add("categories", cats);
        return root.toString();
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
            // ★ 重载后立即把最新目录异步同步到 PHP（游戏内增删分类/商品 → Web 端镜像）
            if (plugin.webManager != null) {
                new BukkitRunnable() {
                    @Override public void run() { plugin.webManager.pushShopCatalog(); }
                }.runTaskAsynchronously(plugin);
            }
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

        // 打包费配置：shop packmoney <金额> （与 /sdf1_login set packmoney 等效，支持小数）
        if (sub.equals("packmoney")) {
            if (a.length < 3) {
                s.sendMessage("§e用法: shop packmoney <金额(0~9999，支持小数)>");
                return true;
            }
            try {
                double amt = Double.parseDouble(a[2]);
                if (amt < 0 || amt > 9999) {
                    s.sendMessage("§c打包费需在 0~9999 之间");
                    return true;
                }
                plugin.getConfigMgr().packingFee = amt;
                plugin.webManager.pushShopConfig("packmoney", String.valueOf(amt));
                s.sendMessage("§a打包费已设置为 " + amt + " 债券（已同步至Web配置）");
            } catch (NumberFormatException e) {
                s.sendMessage("§c金额必须为数字");
            }
            return true;
        }

        // 环保单折扣：shop green <0~10> （与 shop setgreen 等效，便于在 shop 子命令下管理，支持小数）
        // 语义：green=10 不打折；green=9.9 表示 9.9 折（支付 99%）；green=9.8 表示 9.8 折（支付 98%）
        if (sub.equals("green")) {
            if (a.length < 3) {
                s.sendMessage("§e用法: shop green <0~10>（10=不打折，9.9=9.9折，支持小数如 9.6 / 3.33）");
                return true;
            }
            try {
                double v = Double.parseDouble(a[2]);
                if (v < 0) v = 0;
                if (v >= 10) v = 10; // 10 视为不打折（满价）
                plugin.getConfigMgr().greenDiscount = v;
                plugin.webManager.pushShopConfig("green_discount", String.valueOf(v));
                s.sendMessage("§a环保单折扣已设置为 " + v + " 折（10=不打折，9.9=9.9折/99%价，已同步至Web配置）");
            } catch (NumberFormatException e) {
                s.sendMessage("§c参数必须为数字");
            }
            return true;
        }

        // 环保单折扣：shop setgreen <0~10> （10=不打折，9.9=9.9折）
        if (sub.equals("setgreen")) {
            if (a.length < 3) {
                s.sendMessage(
                        "§e用法: shop setgreen <0~10>"
                                + " （10=不打折，9.9=9.9折，支持小数如 9.6）");
                return true;
            }
            try {
                double v = Double.parseDouble(a[2]);
                if (v < 0) v = 0;
                if (v >= 10) v = 10; // 10 视为不打折
                plugin.getConfigMgr().greenDiscount = v;
                plugin.webManager.pushShopConfig("green_discount", String.valueOf(v));
                s.sendMessage("§a环保单折扣已设置为 " + v
                        + " 折（10=不打折，已同步至Web配置）");
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
                + " / set / setgreen(green) / packmoney / pro ");
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

        // ★ 二手商店入口（2026-10-11 /shopbuy）- slot 51
        g.setItem(51, mkItem(Material.EMERALD,
                "§6§l二手商店",
                "§7玩家自由上架、自由定价",
                "§7封底价格: §60 §7枚债券",
                "",
                "§a点击进入"));

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
        // ★ 二手商店入口（2026-10-11）
        if (raw == 51) {
            openSecondHandShop(p, 0);
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

    // ===== 从背包按材质移除最多 want 个物品，返回实际移除数量（剩余不动）=====
    private int removeItemsOfMaterial(Player p, Material mat, int want) {
        if (want <= 0) return 0;
        int removed = 0;
        for (int i = 0; i < p.getInventory().getSize() && removed < want; i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType() == mat) {
                int have = is.getAmount();
                int take = Math.min(have, want - removed);
                if (take >= have) {
                    p.getInventory().clear(i);
                } else {
                    is.setAmount(have - take);
                    p.getInventory().setItem(i, is);
                }
                removed += take;
            }
        }
        return removed;
    }

    // ===== 批量出售某商品全部数量（限额内部分出售，剩余不动）=====
    private void sellAllOf(Player p, ShopItem item) {
        if (item == null) return;
        // ★ 补上禁止出售检查
        if (item.getEffectiveSellPrice() <= 0) {
            p.sendMessage("§c该商品不可出售");
            return;
        }
        Material mat = item.getMaterial();
        int total = 0;
        // 遍历背包清点
        for (int i = 0; i < p.getInventory().getSize(); i++) {
            ItemStack is = p.getInventory().getItem(i);
            if (is != null && is.getType() == mat) {
                total += is.getAmount();
            }
        }

        if (total <= 0) {
            p.sendMessage("§c你没有可出售的 §e"
                    + item.getDisplayName());
            return;
        }

        // ★ 出售限额检查（部分出售：只卖限额内部分，背包剩余不动）
        int unitPrice = item.getEffectiveSellPrice();
        int maxUnits = getMaxSellableUnits(p, unitPrice);   // -1 不限
        int actual;
        if (maxUnits < 0) {
            actual = total;
        } else if (maxUnits == 0) {
            // 已达上限，无法再卖
            sendSellLimitMessage(p);
            return;
        } else {
            actual = Math.min(total, maxUnits);
        }

        // 只移除 actual 个，保留剩余在背包
        int removed = removeItemsOfMaterial(p, mat, actual);
        if (removed <= 0) {
            p.sendMessage("§c你没有可出售的 §e"
                    + item.getDisplayName());
            return;
        }
        int money = unitPrice * removed;
        plugin.getBonds().addBonds(
                p.getName(), money, "shop_sell",
                item.getId(), "商店系统",
                "批量出售" + item.getDisplayName()
                        + " x" + removed);
        if (item.getStock() >= 0)
            item.setStock(item.getStock() + removed);

        if (maxUnits >= 0 && removed < total) {
            // 部分出售：提示剩余未出售
            p.sendMessage("§a批量出售: §e"
                    + item.getDisplayName()
                    + " x" + removed
                    + " §a+" + money + "枚债券");
            p.sendMessage("§e剩余 " + (total - removed) + " 个因出售限额未出售，已留在背包");
            sendSellLimitMessage(p);
        } else {
            p.sendMessage("§a批量出售: §e"
                    + item.getDisplayName()
                    + " x" + removed
                    + " §a+" + money + "枚债券");
        }
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
                // 重置小时销量（仅内存，无需写盘）
                for (ShopCategory cat : categories)
                    for (ShopItem item : cat.getItems())
                        item.setHourlySales(0);
                // ★ 2026-07-07 移除：不再调用 saveAll() 周期性复写 .md 商品文件
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

    // ==================== 二手交易商店（2026-10-11 /shopbuy） ====================

    /** 配置区域快照（项目 → 文件原值），增量热重载 diff 基准（2026-10-11 问题4） */
    private final java.util.Map<String, String> shCfgSnapshot = new java.util.LinkedHashMap<>();

    /**
     * 释放并解析二手交易配置（shop/二手交易规则.md，配置+说明合二为一）。<br>
     * 只读取「## ----配置区域开始----」到「## ----配置区域结束----」之间的表格行，
     * 其余内容（说明书）一律忽略。文件不存在时从 jar 释放，解析失败用默认值。
     */
    private void loadSecondHandConfig() {
        java.util.Map<String, String> cfg = readSecondHandCfgMap();
        for (java.util.Map.Entry<String, String> e : cfg.entrySet()) {
            applySecondHandCfgItem(e.getKey(), e.getValue());
        }
        clampSecondHandCfg();
        shCfgSnapshot.clear();
        shCfgSnapshot.putAll(cfg);
        plugin.getLogger().info("[二手商店] 配置: 上架上限=" + shMaxListings
                + " 最低售价=" + shMinPrice + " 手续费=" + feeRateLabel()
                + (shFeeByBuyer ? "（买方出）" : "（卖方出）"));
    }

    /** 读配置区域表格行 → 项目→值（说明区忽略）；文件不存在先从 jar 释放。 */
    private java.util.Map<String, String> readSecondHandCfgMap() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        File f = new File(plugin.getDataFolder(), "shop/二手交易规则.md");
        if (!f.exists()) {
            try {
                plugin.saveResource("shop/二手交易规则.md", false);
            } catch (Exception e) {
                plugin.getLogger().warning("[二手商店] 配置文件释放失败: " + e.getMessage());
            }
        }
        if (!f.exists()) return m; // 释放失败 → 空表
        boolean inCfg = false;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("##") && t.contains("配置区域开始")) { inCfg = true; continue; }
                if (t.startsWith("##") && t.contains("配置区域结束")) break;
                if (!inCfg || !t.startsWith("|")) continue;
                String[] cols = t.split("\\|", -1);
                if (cols.length < 4) continue; // | 项目 | 解释 | 值 |
                m.put(cols[1].trim(), cols[cols.length - 2].trim());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[二手商店] 配置解析失败: " + e.getMessage());
        }
        return m;
    }

    /** 配置钳制（锁死低价为0；手续费 10 以内） */
    private void clampSecondHandCfg() {
        if (shMaxListings < 0) shMaxListings = 0;
        if (shMinPrice < 0) shMinPrice = 0;
        if (shFeeRate < 0) shFeeRate = 0;
        if (shFeeRate > 10) shFeeRate = 10;
    }

    /**
     * ★ 增量热重载（2026-10-11 问题4）：只比对配置区域（说明区忽略），
     * 无变更一律闭嘴；有变更才播报「哪里变了（旧值 → 新值）」并应用。<br>
     * 触发点：① 每整点 0/15/30/45 分定时扫描；
     * ② 例外——买家购买成功 / 卖家上架成功时立刻触发一次对齐数据。
     */
    private void reloadSecondHandConfigIncremental(String trigger) {
        java.util.Map<String, String> m = readSecondHandCfgMap();
        if (m.isEmpty()) return;
        java.util.List<String> changes = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            String old = shCfgSnapshot.get(e.getKey());
            if (old == null) {
                changes.add(e.getKey() + "（新增）= " + e.getValue());
                applySecondHandCfgItem(e.getKey(), e.getValue());
            } else if (!old.equals(e.getValue())) {
                changes.add(e.getKey() + ": " + old + " → " + e.getValue());
                applySecondHandCfgItem(e.getKey(), e.getValue());
            }
        }
        for (String k : shCfgSnapshot.keySet()) {
            if (!m.containsKey(k)) changes.add(k + "（配置中移除，保持现值）");
        }
        shCfgSnapshot.clear();
        shCfgSnapshot.putAll(m);
        if (changes.isEmpty()) return; // 平时闭嘴
        clampSecondHandCfg();
        plugin.getLogger().info("[二手商店] 配置增量热重载(" + trigger + ")，"
                + changes.size() + " 项变更: " + String.join("；", changes));
    }

    /** 每整点 0/15/30/45 分扫描一次配置文件（初始对齐到下一个 15 分钟边界） */
    private void startShCfgReloadTask() {
        final long periodMs = 15 * 60 * 1000L;
        long now = System.currentTimeMillis();
        long next = (now / periodMs + 1) * periodMs;
        long delayTicks = Math.max(1L, (next - now) / 50L);
        new BukkitRunnable() {
            @Override
            public void run() {
                reloadSecondHandConfigIncremental("定时扫描");
            }
        }.runTaskTimer(plugin, delayTicks, periodMs / 50L);
    }

    /** 按项目名应用单条配置（只认配置区域内的表格行） */
    private void applySecondHandCfgItem(String key, String val) {
        try {
            if (key.contains("可上架")) {
                shMaxListings = Integer.parseInt(val);
            } else if (key.contains("最低售价")) {
                shMinPrice = Integer.parseInt(val);
            } else if (key.contains("手续费支付方")) {
                shFeeByBuyer = !val.contains("卖方");
            } else if (key.contains("手续费")) {
                double r = parseFeeRate(val);
                if (r < 0) {
                    plugin.getLogger().warning("[二手商店] 手续费率无法解析: "
                            + val + "，保持原值 " + shFeeRate);
                } else {
                    shFeeRate = r;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[二手商店] 配置项解析失败: " + key + " = " + val);
        }
    }

    /**
     * 手续费率解析（2026-10-11 兼容千分位写法）：<br>
     *   "5%" → 0.05，"5‰" → 0.005，"0.05" → 0.05（纯数字原值）。<br>
     * 解析失败返回 -1（调用方保留原值并告警）。
     */
    private static double parseFeeRate(String s) {
        String t = s.trim();
        try {
            if (t.endsWith("‰")) {
                return Double.parseDouble(t.substring(0, t.length() - 1).trim()) / 1000.0;
            }
            if (t.endsWith("%")) {
                return Double.parseDouble(t.substring(0, t.length() - 1).trim()) / 100.0;
            }
            return Double.parseDouble(t);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 当前费率友好显示：0.05→"5%"，0.005→"5‰"，其它→原数字 */
    public String feeRateLabel() {
        if (shFeeRate == 0) return "0";
        double pct = shFeeRate * 100;
        long p = Math.round(pct);
        if (Math.abs(pct - p) < 1e-9) return p + "%";
        double perm = shFeeRate * 1000;
        long q2 = Math.round(perm);
        if (Math.abs(perm - q2) < 1e-9) return q2 + "‰";
        return String.valueOf(shFeeRate);
    }

    /** 手续费金额（四舍五入；price<=0 或费率为 0 → 0） */
    private int shFeeOf(int price) {
        if (price <= 0 || shFeeRate <= 0) return 0;
        return (int) Math.round(price * shFeeRate);
    }

    /** 玩家实际上架数量上限（用户组优先，组值 -1=跟随全局） */
    private int shMaxListingsFor(String player) {
        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm == null) return shMaxListings;
        return ugm.getPlayerShopMaxListings(player, shMaxListings);
    }

    /** 玩家实际最低售价（用户组优先，组值 -1=跟随全局） */
    private int shMinPriceFor(String player) {
        UserGroupManager ugm = plugin.getUserGroup();
        if (ugm == null) return shMinPrice;
        return ugm.getPlayerShopMinPrice(player, shMinPrice);
    }

    // ===== 出售偏好（2026-10-11 任务4：发布页一键切换奸商/正常） =====

    /** 玩家实际手续费承担模式：交易记录库有偏好则用偏好，否则回退全局配置。 */
    public String shFeeModeFor(String player) {
        TradeLogManager tl = plugin.getTradeLog();
        if (tl != null) {
            String m = tl.getSellingPref(player);
            if (FEE_MODE_BUYER.equals(m) || FEE_MODE_SELLER.equals(m)) return m;
        }
        return shFeeByBuyer ? FEE_MODE_BUYER : FEE_MODE_SELLER;
    }

    /** 覆盖玩家出售偏好（写入交易记录库）。 */
    public void setShFeeModeFor(String player, String mode) {
        TradeLogManager tl = plugin.getTradeLog();
        if (tl != null) tl.setSellingPref(player, mode);
    }

    /** 偏好显示名：奸商模式 / 正常模式 */
    public static String feeModeLabel(String mode) {
        return FEE_MODE_BUYER.equals(mode) ? "奸商模式" : "正常模式";
    }

    /** 按偏好计算一笔二手交易的手续费明细 */
    private int[] shFeeSplit(String seller, int price) {
        int fee = shFeeOf(price);
        String mode = shFeeModeFor(seller);
        if (FEE_MODE_BUYER.equals(mode)) {
            // 奸商模式：买方出 → 买家多付 fee，卖家全额到手
            return new int[]{fee, price + fee, price};
        }
        // 正常模式：卖方出 → 买家付原价，卖家到手扣 fee
        return new int[]{fee, price, Math.max(0, price - fee)};
    }

    private File secondHandFile() {
        File dir = new File(plugin.getDataFolder(), "shop");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "secondhand.txt");
    }

    /** 载入二手商品（新格式: id|seller|price|createdAt|desc|base64；旧格式无desc段） */
    private void loadSecondHand() {
        secondHandListings.clear();
        File f = secondHandFile();
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", 6);
                SecondHandListing l = new SecondHandListing();
                try {
                    l.id = Long.parseLong(parts[0]);
                    l.price = Integer.parseInt(parts[2]);
                    l.createdAt = Long.parseLong(parts[3]);
                } catch (Exception e) { continue; }
                l.seller = parts[1];
                if (parts.length >= 6) {          // 新格式带描述
                    l.desc = parts[4];
                    l.itemB64 = parts[5];
                } else if (parts.length == 5) {   // 旧格式无描述
                    l.desc = "";
                    l.itemB64 = parts[4];
                } else {
                    continue;
                }
                secondHandListings.add(l);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[二手商店] 载入失败: " + e.getMessage());
        }
    }

    /** 保存二手商品（6段格式） */
    private void saveSecondHand() {
        try (BufferedWriter bw = new BufferedWriter(
                new OutputStreamWriter(
                        new FileOutputStream(secondHandFile()), StandardCharsets.UTF_8))) {
            bw.write("# id|seller|price|createdAt|desc|base64");
            bw.newLine();
            for (SecondHandListing l : secondHandListings) {
                String desc = l.desc == null ? "" : l.desc.replace("|", "");
                bw.write(l.id + "|" + l.seller + "|" + l.price
                        + "|" + l.createdAt + "|" + desc + "|" + l.itemB64);
                bw.newLine();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[二手商店] 保存失败: " + e.getMessage());
        }
    }

    private long nextSecondHandId() {
        long max = 0;
        for (SecondHandListing l : secondHandListings) {
            if (l.id > max) max = l.id;
        }
        return max + 1;
    }

    // ===== 打开二手商店主界面 =====
    public void openSecondHandShop(Player p, int page) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l二手商店");
        int total = (int) Math.ceil((double) secondHandListings.size() / PAGE_SIZE);
        if (total < 1) total = 1;
        if (page < 0) page = 0;
        if (page >= total) page = total - 1;
        secondHandPages.put(p.getUniqueId(), page);
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, secondHandListings.size());

        for (int i = start; i < end; i++) {
            int idx = i - start;
            if (idx >= ITEM_SLOTS.length) break;
            SecondHandListing l = secondHandListings.get(i);
            ItemStack is = base64ToItem(l.itemB64);
            if (is == null) continue;
            ItemMeta im = is.getItemMeta();
            if (im != null) {
                // 2026-10-11 第四批：①展示卖家描述 ②手续费仅奸商模式显示
                //   （正常模式买家只付标价、无额外费用，隐藏手续费行）
                java.util.List<String> lore = new java.util.ArrayList<>();
                lore.add("§7卖家: §f" + l.seller);
                lore.add("§7售价: §6" + l.price + " §7枚债券");
                if (l.desc != null && !l.desc.isEmpty()) {
                    lore.add("§7描述: §f" + l.desc);
                }
                if (FEE_MODE_BUYER.equals(shFeeModeFor(l.seller))) {
                    int[] shsp = shFeeSplit(l.seller, l.price);
                    lore.add(shsp[0] > 0
                            ? "§7手续费: §6" + shsp[0] + " §7（奸商模式）§7你需支付 §6" + shsp[1]
                            : "§7无手续费");
                }
                lore.add("§7上架时间: §f" + new SimpleDateFormat("MM-dd HH:mm")
                        .format(new Date(l.createdAt)));
                lore.add("");
                lore.add("§a左键购买");
                im.setLore(lore);
                is.setItemMeta(im);
            }
            g.setItem(ITEM_SLOTS[idx], is);
        }

        g.setItem(4, mkItem(Material.BOOK, "§e§l二手商店",
                "§7共 §f" + secondHandListings.size() + " §7件商品",
                "§7玩家自由上架，自由定价"));

        g.setItem(45, mkItem(Material.EMERALD, "§a§l上架物品",
                "§7将主手物品上架出售",
                "§7封底价格: §60 §7枚债券",
                "",
                plugin.getBonds().isFrozen(p.getName())
                        ? "§c债券账户被冻结，不可上架"
                        : "§e点击上架"));

        g.setItem(46, mkItem(Material.CHEST, "§e§l我的上架",
                "§7查看并管理自己上架的商品",
                "", "§e点击进入"));

        g.setItem(48, page > 0
                ? mkItem(Material.ARROW, "§7上一页")
                : mkItem(Material.BLACK_STAINED_GLASS_PANE, " "));
        g.setItem(49, mkItem(Material.PAPER, "§7" + (page + 1) + "/" + total));
        g.setItem(50, page < total - 1
                ? mkItem(Material.ARROW, "§7下一页")
                : mkItem(Material.BLACK_STAINED_GLASS_PANE, " "));

        g.setItem(53, mkItem(Material.ARROW, "§7返回"));

        p.openInventory(g);
    }

    // ===== 打开我的上架 =====
    public void openSecondHandMine(Player p) {
        Inventory g = Bukkit.createInventory(null, 54, "§6§l我的二手上架");
        String me = p.getName();
        int si = 0;
        for (SecondHandListing l : secondHandListings) {
            if (!me.equals(l.seller)) continue;
            if (si >= ITEM_SLOTS.length) break;
            ItemStack is = base64ToItem(l.itemB64);
            if (is == null) continue;
            ItemMeta im = is.getItemMeta();
            if (im != null) {
                im.setLore(Arrays.asList(
                        "§7售价: §6" + l.price + " §7枚债券",
                        l.desc == null || l.desc.isEmpty()
                                ? "§7描述: §f无"
                                : "§7描述: §f" + l.desc,
                        "",
                        "§e左键编辑（售价 & 描述）",
                        "§c右键下架（退还物品）"));
                is.setItemMeta(im);
            }
            g.setItem(ITEM_SLOTS[si], is);
            si++;
        }
        g.setItem(4, mkItem(Material.BOOK, "§e§l我的上架",
                "§7共 §f" + si + " §7件商品"));
        g.setItem(49, mkItem(Material.ARROW, "§7返回二手商店"));
        p.openInventory(g);
    }

    // ===== 二手商店主界面点击 =====
    public boolean handleSecondHandClick(Player p, int raw, boolean left) {
        if (raw == 53) { openShopMain(p); return true; }
        if (raw == 45) { openPublishGui(p); return true; }
        if (raw == 46) { openSecondHandMine(p); return true; }
        if (raw == 48) {
            int pg = getSecondHandPage(p.getUniqueId());
            openSecondHandShop(p, pg - 1);
            return true;
        }
        if (raw == 50) {
            int pg = getSecondHandPage(p.getUniqueId());
            openSecondHandShop(p, pg + 1);
            return true;
        }
        if (!left) return true;
        int idx = slotToIndex(raw);
        if (idx < 0) return true;
        int pg = getSecondHandPage(p.getUniqueId());
        int actual = idx + pg * PAGE_SIZE;
        if (actual < 0 || actual >= secondHandListings.size()) return true;
        buySecondHand(p, secondHandListings.get(actual));
        return true;
    }

    // ===== 我的上架点击（2026-10-11 任务1：左键编辑 / 右键下架） =====
    public boolean handleSecondHandMineClick(Player p, int raw,
                                             boolean left, boolean right) {
        if (raw == 49) { openSecondHandShop(p, 0); return true; }
        int idx = slotToIndex(raw);
        if (idx < 0) return true;
        SecondHandListing target = null;
        if (left || right) {
            String me = p.getName();
            int count = 0;
            for (SecondHandListing l : secondHandListings) {
                if (!me.equals(l.seller)) continue;
                if (count == idx) { target = l; break; }
                count++;
            }
        }
        if (target == null) return true;

        // ===== 右键：下架并退还物品 =====
        if (right && !left) {
            ItemStack is = base64ToItem(target.itemB64);
            if (is != null) {
                HashMap<Integer, ItemStack> left2 = p.getInventory().addItem(is);
                for (ItemStack drop : left2.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), drop);
                }
            }
            secondHandListings.remove(target);
            saveSecondHand();
            p.sendMessage("§a§l[二手商店] §f已下架并退还物品");
            openSecondHandMine(p);
            return true;
        }

        // ===== 左键：编辑售价 & 描述 =====
        if (left && !right) {
            startEditSecondHand(p, target);
            return true;
        }
        return true;
    }

    // ===== 编辑商品（2026-10-11 任务1）：聊天两步改 描述 → 售价 =====
    public void startEditSecondHand(Player p, SecondHandListing l) {
        if (l == null) return;
        if (plugin.getBonds().isFrozen(p.getName())) {
            p.sendMessage("§c§l[二手商店] §f债券账户被冻结，不可编辑商品");
            return;
        }
        UUID uuid = p.getUniqueId();
        editListingId.put(uuid, l.id);
        pendingPublishDesc.remove(uuid);
        publishListening.add(uuid);
        publishStep.put(uuid, 1);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) p.closeInventory();
        });
        p.sendMessage("§6§l[二手商店] §e正在编辑商品 §f#" + l.id
                + "§e（当前售价 §6" + l.price + "§e，描述 §f"
                + (l.desc == null || l.desc.isEmpty() ? "无" : l.desc) + "§e）");
        p.sendMessage("§e请在聊天栏输入新的商品描述（§f无§e清空描述，§c取消§e放弃编辑）");
        p.sendMessage("§715秒内无输入视为超时并放弃编辑");
        schedulePublishTimeout(p);
    }

    private SecondHandListing findSecondHandListing(long id) {
        for (SecondHandListing l : secondHandListings) {
            if (l.id == id) return l;
        }
        return null;
    }

    /** 编辑模式聊天输入（step1=描述，step2=售价） */
    private void handleEditInput(Player p, UUID uuid, int step, String msg) {
        Long id = editListingId.get(uuid);
        SecondHandListing target = id == null ? null : findSecondHandListing(id);
        if (target == null) {
            clearEditState(uuid);
            p.sendMessage("§c[二手商店] 该商品已不存在（可能已售出或被下架），编辑结束");
            return;
        }
        if (msg.equals("取消")) {
            clearEditState(uuid);
            p.sendMessage("§7[二手商店] 已放弃编辑，商品信息保持不变");
            if (p.isOnline()) openSecondHandMine(p);
            return;
        }
        if (step == 1) {
            // ===== 第一步：描述 =====
            String desc = msg.replace("|", "").trim();
            if (desc.equals("无") || desc.equalsIgnoreCase("skip")) {
                desc = "";
            } else if (desc.length() > 20) {
                p.sendMessage("§c§l[二手商店] §f描述最多 20 个字符（当前 " + desc.length()
                        + "），请重新输入（§f无§e清空描述）");
                return; // 保持等待
            }
            pendingPublishDesc.put(uuid, desc);
            publishStep.put(uuid, 2);
            int minPrice = shMinPriceFor(p.getName());
            p.sendMessage("§6§l[二手商店] §e请输入新的售价（整数，最低 §f" + minPrice
                    + "§e，当前 §6" + target.price + "§e）");
            schedulePublishTimeout(p);
            return;
        }

        // ===== 第二步：售价 =====
        publishListening.remove(uuid);
        publishStep.remove(uuid);
        String desc2 = pendingPublishDesc.remove(uuid);
        editListingId.remove(uuid);
        int minPrice = shMinPriceFor(p.getName());
        int price;
        try {
            price = Integer.parseInt(msg.trim());
        } catch (Exception ex) {
            p.sendMessage("§c[二手商店] 售价必须是整数，编辑已取消");
            if (p.isOnline()) openSecondHandMine(p);
            return;
        }
        if (price < minPrice) {
            p.sendMessage("§c[二手商店] 售价不能低于最低售价 §6" + minPrice + "§c，编辑已取消");
            if (p.isOnline()) openSecondHandMine(p);
            return;
        }
        target.price = price;
        if (desc2 != null) target.desc = desc2;
        saveSecondHand();
        p.sendMessage("§a§l[二手商店] §f商品 #" + target.id + " 已更新: 售价 §6" + price
                + " §7描述: §f" + (target.desc == null || target.desc.isEmpty()
                ? "无" : target.desc));
        if (p.isOnline()) openSecondHandMine(p);
    }

    /** 清除编辑状态（不改动商品数据） */
    private void clearEditState(UUID uuid) {
        publishListening.remove(uuid);
        publishStep.remove(uuid);
        pendingPublishDesc.remove(uuid);
        editListingId.remove(uuid);
    }

    // ===== 记录/读取二手商店页码 =====
    private final Map<UUID, Integer> secondHandPages = new HashMap<>();
    private int getSecondHandPage(UUID uuid) {
        return secondHandPages.getOrDefault(uuid, 0);
    }

    /**
     * 二手商店禁售判定（2026-10-11）。
     * 严禁上架：① SN 自定义物品（PDC sdf1_sn / 4 类管控物品）
     *          ② Sdf1_game 宝箱物品（lore 含 §0§k / CUSTOM 隐藏标记）
     * @return 不可上架的原因；null 表示可上架
     */
    private String secondHandBlockReason(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return null;
        // ① SN 物品
        try {
            if (plugin.getSnManager() != null
                    && plugin.getSnManager().isSnItem(item)) {
                return "SN 绑定物品不可交易";
            }
        } catch (Throwable ignored) {
        }
        // ② Sdf1_game 宝箱物品（lore 隐藏标记 §0§kCUSTOM|...）
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasLore()) {
                java.util.List<String> lore = meta.getLore();
                if (lore != null) {
                    for (String line : lore) {
                        if (line == null) continue;
                        if (line.contains("§0§k")
                                || line.contains("CUSTOM")) {
                            return "宝箱自定义物品不可交易";
                        }
                    }
                }
            }
        }
        return null;
    }

    // ===== 批量上架 GUI（2026-10-11 任务2：不再读取主手，改为独立 GUI） =====

    /** 统计玩家当前已上架件数 */
    private int countSecondHandMine(String name) {
        int c = 0;
        for (SecondHandListing l : secondHandListings) {
            if (name.equals(l.seller)) c++;
        }
        return c;
    }

    /** 构建批量上架 GUI（0-44 放物品，45-53 底栏按钮） */
    private Inventory buildPublishGui(Player p) {
        Inventory g = Bukkit.createInventory(null, 54, T_SH_PUBLISH);
        String mode = shFeeModeFor(p.getName());
        int mine = countSecondHandMine(p.getName());
        int max = shMaxListingsFor(p.getName());
        int left = Math.max(0, max - mine);
        g.setItem(45, mkItem(Material.BOOK, "§e§l批量上架",
                "§7把要卖的物品放进上方格子（可放多件）",
                "§7随后点击 §a发布上架 §7逐件定价",
                "",
                "§7当前还可上架: §f" + left + " §7件",
                "§8SN物品与宝箱自定义物品不可上架"));
        g.setItem(49, mkItem(Material.EMERALD, "§a§l发布上架",
                "§7为放入的物品逐件输入售价",
                "§7已发布的物品直接进入销售，不再退还",
                "",
                left <= 0 ? "§c已达上架上限，无法发布" : "§e点击开始"));
        g.setItem(50, mkItem(
                FEE_MODE_BUYER.equals(mode) ? Material.GOLD_NUGGET : Material.EMERALD,
                "§e§l出售偏好: " + feeModeLabel(mode),
                FEE_MODE_BUYER.equals(mode)
                        ? "§7奸商模式: 买家支付 售价+手续费"
                        : "§7正常模式: 手续费从卖家到手价扣除",
                "",
                "§e点击切换为 " + feeModeLabel(
                        FEE_MODE_BUYER.equals(mode) ? FEE_MODE_SELLER : FEE_MODE_BUYER)));
        g.setItem(53, mkItem(Material.ARROW, "§7返回二手商店"));
        return g;
    }

    /** 打开批量上架 GUI */
    public void openPublishGui(Player p) {
        if (plugin.getBonds().isFrozen(p.getName())) {
            p.sendMessage("§c§l[二手商店] §f债券账户被冻结，不可上架/出售商品");
            return;
        }
        int mine = countSecondHandMine(p.getName());
        int max = shMaxListingsFor(p.getName());
        if (mine >= max) {
            if (max <= 0) {
                p.sendMessage("§c§l[二手商店] §f你的用户组暂无上架权限");
            } else {
                p.sendMessage("§c§l[二手商店] §f最多同时上架 §e" + max
                        + " §f件商品（当前已上架 §e" + mine + "§f）");
            }
            return;
        }
        p.openInventory(buildPublishGui(p));
    }

    /** 刷新发布 GUI：保留 0-44 已放入的物品（切换偏好时用，避免吞物品） */
    private void refreshPublishGui(Player p) {
        Inventory old = p.getOpenInventory().getTopInventory();
        if (old == null
                || !T_SH_PUBLISH.equals(p.getOpenInventory().getTitle())) {
            openPublishGui(p);
            return;
        }
        ItemStack[] keep = new ItemStack[45];
        for (int i = 0; i < 45; i++) {
            ItemStack it = old.getItem(i);
            if (it != null && it.getType() != Material.AIR) keep[i] = it.clone();
        }
        Inventory g = buildPublishGui(p);
        for (int i = 0; i < 45; i++) {
            if (keep[i] != null) g.setItem(i, keep[i]);
        }
        p.openInventory(g);
    }

    /** 一键切换出售偏好（任务4：奸商模式 ⇄ 正常模式） */
    public void toggleFeeMode(Player p) {
        String cur = shFeeModeFor(p.getName());
        String next = FEE_MODE_BUYER.equals(cur) ? FEE_MODE_SELLER : FEE_MODE_BUYER;
        setShFeeModeFor(p.getName(), next);
        p.sendMessage("§a§l[二手商店] §f出售偏好已切换为 §e" + feeModeLabel(next)
                + "§f（" + (FEE_MODE_BUYER.equals(next)
                ? "手续费由买家支付" : "手续费从卖家到手价扣除") + "）");
        refreshPublishGui(p);
    }

    /** 批量上架 GUI 点击（由事件处理器调用） */
    private void handlePublishGuiButton(Player p, int raw) {
        if (raw == 53) {
            p.closeInventory();
            openSecondHandShop(p, 0);
            return;
        }
        if (raw == 50) {
            toggleFeeMode(p);
            return;
        }
        if (raw == 49) {
            startBatchPublish(p);
        }
    }

    /** 开始批量发布：取出 GUI 内物品 → 逐件进入定价流程 */
    private void startBatchPublish(Player p) {
        if (plugin.getBonds().isFrozen(p.getName())) {
            p.sendMessage("§c§l[二手商店] §f债券账户被冻结，不可上架/出售商品");
            return;
        }
        UUID uuid = p.getUniqueId();
        // ★ 第五批：入口先清理残留会话（任何原因中断后遗留的暂存/计时器），
        //   保证批量入口永远不会被旧状态锁死
        if (publishListening.contains(uuid) || pendingPublishItem.containsKey(uuid)
                || publishStep.containsKey(uuid) || editListingId.containsKey(uuid)) {
            ItemStack stranded = pendingPublishItem.remove(uuid);
            if (stranded != null) returnItem(p, stranded);
            publishListening.remove(uuid);
            publishStep.remove(uuid);
            pendingPublishDesc.remove(uuid);
            editListingId.remove(uuid);
            nextPublishSession(uuid);
            p.sendMessage("§7[二手商店] 已重置上次未完成的上架流程"
                    + (stranded != null ? "（暂存物品已退还）" : ""));
        }
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top == null
                || !T_SH_PUBLISH.equals(p.getOpenInventory().getTitle())) {
            p.sendMessage("§c§l[二手商店] §f发布界面已失效，请重新打开");
            return;
        }

        int mine = countSecondHandMine(p.getName());
        int max = shMaxListingsFor(p.getName());
        int left = max - mine;
        if (left <= 0) {
            p.sendMessage("§c§l[二手商店] §f最多同时上架 §e" + max + " §f件商品");
            return;
        }

        java.util.LinkedList<ItemStack> items = new java.util.LinkedList<>();
        List<ItemStack> overflow = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ItemStack it = top.getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            top.setItem(i, null); // 先取空，避免关闭事件重复退还
            if (items.size() < left) items.add(it.clone());
            else overflow.add(it.clone());
        }
        for (ItemStack ov : overflow) returnItem(p, ov);
        if (!overflow.isEmpty()) {
            p.sendMessage("§c§l[二手商店] §f超出上架上限，§c" + overflow.size()
                    + " §f件物品已退还");
        }
        if (items.isEmpty()) {
            p.sendMessage("§c§l[二手商店] §f请先把要上架的物品放进格子");
            return;
        }
        p.closeInventory();
        java.util.LinkedList<ItemStack> oldQ = publishQueue.remove(uuid);
        if (oldQ != null && !oldQ.isEmpty()) {
            for (ItemStack it : oldQ) returnItem(p, it);
            p.sendMessage("§7[二手商店] 已退还上次批量遗留的 §f" + oldQ.size() + " §7件物品");
        }
        publishQueue.put(uuid, items);
        publishQueueTotal.put(uuid, items.size());
        p.sendMessage("§6§l[二手商店] §e开始批量上架，共 §f" + items.size() + " §e件");
        beginNextPublishItem(p, uuid);
    }

    /** 取队列下一件进入定价（禁售物品自动跳过并退还） */
    private void beginNextPublishItem(Player p, UUID uuid) {
        java.util.LinkedList<ItemStack> q = publishQueue.get(uuid);
        if (q == null || q.isEmpty()) {
            publishQueue.remove(uuid);
            publishQueueTotal.remove(uuid);
            p.sendMessage("§a§l[二手商店] §f全部商品已上架完成");
            if (p.isOnline()) openSecondHandShop(p, 0);
            return;
        }
        // ★ 第五批：每件定价前复检上架上限——中途达到上限（组变更/他人代发等）
        //   立即中止并退还剩余，防止超发或流程悬空
        int mineNow = countSecondHandMine(p.getName());
        int maxNow = shMaxListingsFor(p.getName());
        if (mineNow >= maxNow) {
            abortPublish(p, uuid, null);
            int back = q.size();
            p.sendMessage("§c§l[二手商店] §f已达上架上限 §6" + maxNow
                    + " §f件，批量中止，剩余 §e" + back + " §f件已退还");
            if (p.isOnline()) openSecondHandShop(p, 0);
            return;
        }
        ItemStack it = q.peek();
        String blocked = secondHandBlockReason(it);
        if (blocked != null) {
            q.poll();
            returnItem(p, it);
            p.sendMessage("§c[二手商店] 跳过不可上架物品 §e"
                    + ItemNameResolver.toChinese(it.getType()) + " §c(" + blocked + ")，已退还");
            beginNextPublishItem(p, uuid);
            return;
        }
        int total = publishQueueTotal.getOrDefault(uuid, q.size());
        int idx = total - q.size() + 1;
        // ★ 当前件立即出队（2026-10-11 修复重复退还）：此前 peek 不出队，
        //   当前件同时留在队列与 pendingPublishItem，超时/中止会退两份
        q.poll();
        pendingPublishItem.put(uuid, it);
        publishListening.add(uuid);
        publishStep.put(uuid, 1);
        p.sendMessage("§6§l[二手商店] §e第 §f" + idx + "/" + total
                + " §e件（§f" + ItemNameResolver.toChinese(it.getType()) + " x" + it.getAmount()
                + "§e）请在聊天栏输入商品描述（§f无§e跳过，§c取消§e中止批量）");
        schedulePublishTimeout(p);
    }

    // ===== 批量上架 GUI 事件：点击 =====
    @EventHandler
    public void onPublishGuiClick(
            org.bukkit.event.inventory.InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!T_SH_PUBLISH.equals(e.getView().getTitle())) return;
        Player p = (Player) e.getWhoClicked();
        int raw = e.getRawSlot();
        // 0-44 上架区与玩家背包区：放行，允许自由存取物品
        if (raw < 45 || raw > 53) return;
        // 45-53 底栏按钮：拦截并处理
        e.setCancelled(true);
        handlePublishGuiButton(p, raw);
    }

    // ===== 批量上架 GUI 事件：拖拽 =====
    @EventHandler
    public void onPublishGuiDrag(
            org.bukkit.event.inventory.InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!T_SH_PUBLISH.equals(e.getView().getTitle())) return;
        // 只允许拖进 0-44 上架区，落到 45-53 底栏一律拦截
        for (int raw : e.getRawSlots()) {
            if (raw >= 45 && raw <= 53) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ===== 批量上架 GUI 事件：关闭（退还未发布的物品） =====
    @EventHandler
    public void onPublishGuiClose(
            org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        if (!T_SH_PUBLISH.equals(e.getView().getTitle())) return;
        Player p = (Player) e.getPlayer();
        int back = 0;
        for (int i = 0; i < 45; i++) {
            ItemStack it = e.getInventory().getItem(i);
            if (it == null || it.getType() == Material.AIR) continue;
            back++;
            returnItem(p, it.clone());
            e.getInventory().setItem(i, null);
        }
        if (back > 0) {
            p.sendMessage("§7[二手商店] 已退还 §f" + back
                    + " §7件未发布的物品（已发布的商品保持销售）");
        }
    }

    // ===== 批量上架：玩家退出（2026-10-11 第五批：退还未定价物品防吞物+清状态） =====
    @EventHandler
    public void onPublishQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        ItemStack back = pendingPublishItem.remove(uuid);
        java.util.LinkedList<ItemStack> q = publishQueue.remove(uuid);
        publishQueueTotal.remove(uuid);
        publishListening.remove(uuid);
        publishStep.remove(uuid);
        pendingPublishDesc.remove(uuid);
        editListingId.remove(uuid);
        nextPublishSession(uuid); // 作废挂起的超时计时器
        if (back != null) returnItem(p, back);
        if (q != null) {
            for (ItemStack it : q) returnItem(p, it);
        }
    }

    /** 递增玩家的发布会话令牌：此前所有挂起的超时计时器立即作废 */
    private int nextPublishSession(UUID uuid) {
        int v = publishSessionSeq.getOrDefault(uuid, 0) + 1;
        publishSessionSeq.put(uuid, v);
        return v;
    }

    /**
     * 上架聊天输入超时（15秒）：清状态并退还暂存物品。每个步骤完成会重新计时。<br>
     * ★ 第五批修复：计时器带会话令牌——触发时若玩家已进入新会话/新步骤
     *   （令牌不符）直接作废，不再误杀正在进行的批量上架。此前每步各挂一个
     *   15秒计时器且互不作废，旧计时器会在 15 秒后杀死任何新开始的会话，
     *   表现为批量上架莫名中止、反复重试反复被"超时"，像被永久锁死。
     */
    private void schedulePublishTimeout(Player p) {
        final UUID uuid = p.getUniqueId();
        final int token = nextPublishSession(uuid); // 作废此前所有计时器
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Integer cur = publishSessionSeq.get(uuid);
            if (cur == null || cur != token) return; // 已被新会话/新步骤取代
            if (publishListening.remove(uuid)) {
                publishStep.remove(uuid);
                pendingPublishDesc.remove(uuid);
                ItemStack back = pendingPublishItem.remove(uuid);
                boolean editing = editListingId.remove(uuid) != null;
                Player pl = Bukkit.getPlayer(uuid);
                if (back != null && pl != null && pl.isOnline()) {
                    returnItem(pl, back);
                }
                // ★ 批量队列剩余一并退还（2026-10-11 任务2）
                java.util.LinkedList<ItemStack> q = publishQueue.remove(uuid);
                publishQueueTotal.remove(uuid);
                int extra = 0;
                if (q != null) {
                    extra = q.size();
                    if (pl != null && pl.isOnline()) {
                        for (ItemStack it : q) returnItem(pl, it);
                    }
                }
                if (pl != null && pl.isOnline()) {
                    if (editing) {
                        pl.sendMessage("§c[二手商店] 编辑超时，已放弃修改");
                        openSecondHandMine(pl);
                    } else {
                        pl.sendMessage("§c[二手商店] 上架超时，物品已退还"
                                + (extra > 0 ? "（含批量剩余 " + extra + " 件）" : ""));
                    }
                }
            }
        }, 300L);
    }

    // ===== 聊天输入售价 =====
    @EventHandler(priority = EventPriority.LOW)
    public void onPublishChat(org.bukkit.event.player.AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        if (!publishListening.contains(uuid)) return;
        e.setCancelled(true);
        // ★ 编辑模式分流（2026-10-11 任务1）：改售价/描述走独立状态机
        if (editListingId.containsKey(uuid)) {
            int editStep = publishStep.getOrDefault(uuid, 1);
            final String editMsg = e.getMessage().trim();
            Bukkit.getScheduler().runTask(plugin,
                    () -> handleEditInput(p, uuid, editStep, editMsg));
            return;
        }
        int step = publishStep.getOrDefault(uuid, 2);
        // 不在此处移除暂存：第一步完成后还要进入第二步（等售价）
        ItemStack item = pendingPublishItem.get(uuid);
        if (item == null) {
            publishListening.remove(uuid);
            publishStep.remove(uuid);
            pendingPublishDesc.remove(uuid);
            return;
        }
        // ★ AsyncPlayerChatEvent 在异步线程触发：退还物品/入库/开 GUI 都必须切主线程
        //   （2026-10-11 生产报错 InventoryOpenEvent may only be triggered synchronously）
        final String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handlePublishInput(p, uuid, item, step, msg));
    }

    /**
     * 主线程处理上架聊天输入（两步状态机）。<br>
     * step=1 输入商品描述（≤20字，「无」跳过，超长原地重试）；step=2 输入售价
     * （低于最低售价拒绝），二次校验后入库并开商店界面。
     */
    private void handlePublishInput(Player p, UUID uuid, ItemStack item, int step, String msg) {
        if (step == 1) {
            // ===== 第一步：商品描述 =====
            if (msg.equals("取消")) {
                abortPublish(p, uuid, item);
                p.sendMessage("§7[二手商店] 已取消上架，物品退还");
                return;
            }
            String desc = msg.replace("|", "").trim(); // | 会破坏存储格式
            if (desc.equals("无") || desc.equalsIgnoreCase("skip")) {
                desc = "";
            } else if (desc.length() > 20) {
                p.sendMessage("§c§l[二手商店] §f描述最多 20 个字符（当前 " + desc.length()
                        + "），请重新输入（输入 §f无 §7跳过）");
                return; // 保持等待状态
            }
            pendingPublishDesc.put(uuid, desc);
            publishStep.put(uuid, 2);
            int minPrice = shMinPriceFor(p.getName());
            p.sendMessage("§6§l[二手商店] §e请在聊天栏输入售价（债券，整数，最低 §f" + minPrice + "§e）");
            p.sendMessage("§715秒内输入，超时自动退还物品");
            schedulePublishTimeout(p); // 第二步重新计时
            return;
        }

        // ===== 第二步：售价 =====
        publishListening.remove(uuid);
        publishStep.remove(uuid);
        String desc2 = pendingPublishDesc.remove(uuid);
        int minPrice = shMinPriceFor(p.getName());
        if (msg.isEmpty() || msg.equals("取消")) {
            abortPublish(p, uuid, item);
            p.sendMessage("§7[二手商店] 已取消上架，物品退还");
            return;
        }
        int price;
        try {
            price = Integer.parseInt(msg);
        } catch (Exception ex) {
            abortPublish(p, uuid, item);
            p.sendMessage("§c[二手商店] 售价必须是整数，已取消上架并退还物品");
            return;
        }
        // ★ 锁死低价：负数或低于最低售价一律拒绝（2026-10-11）
        if (price < minPrice) {
            abortPublish(p, uuid, item);
            p.sendMessage("§c[二手商店] 售价不能低于最低售价 §6" + minPrice + " §c，已退还物品");
            return;
        }
        pendingPublishItem.remove(uuid);

        // ★ 第五批：入库前复检上限——中途已达上限退还不超发
        int mineNow = countSecondHandMine(p.getName());
        int maxNow = shMaxListingsFor(p.getName());
        if (mineNow >= maxNow) {
            abortPublish(p, uuid, item);
            p.sendMessage("§c[二手商店] 已达上架上限 §6" + maxNow + " §c件，物品已退还");
            return;
        }

        // ★ 入库前二次校验：SN / 宝箱物品严禁上架（2026-10-11）
        String blocked2 = secondHandBlockReason(item);
        if (blocked2 != null) {
            abortPublish(p, uuid, item);
            p.sendMessage("§c[二手商店] 该物品不可上架: " + blocked2 + "，物品已退还");
            return;
        }

        SecondHandListing l = new SecondHandListing();
        l.id = nextSecondHandId();
        l.seller = p.getName();
        l.price = price;
        l.desc = desc2 == null ? "" : desc2;
        l.itemB64 = itemToBase64(item);
        l.createdAt = System.currentTimeMillis();
        if (l.itemB64 == null || l.itemB64.isEmpty()) {
            abortPublish(p, uuid, item);
            p.sendMessage("§c[二手商店] 物品序列化失败，已退还");
            return;
        }
        secondHandListings.add(l);
        saveSecondHand();
        // ★ 上架成功立刻触发一次增量热重载，把配置数据对齐（2026-10-11 问题4 例外）
        reloadSecondHandConfigIncremental("上架触发");
        p.sendMessage("§a§l[二手商店] §f上架成功: §e" + ItemNameResolver.toChinese(item.getType())
                + " §7x" + item.getAmount() + " §6" + price + " §7枚债券");
        // ★ 手续费展示按玩家出售偏好计算（2026-10-11 任务3/4）
        int fee = shFeeOf(price);
        if (fee > 0) {
            String mode = shFeeModeFor(p.getName());
            int buyerPay = FEE_MODE_BUYER.equals(mode) ? price + fee : price;
            int sellerGot = FEE_MODE_SELLER.equals(mode)
                    ? Math.max(0, price - fee) : price;
            p.sendMessage("§7手续费 §6" + fee + " §7枚债券（" + feeModeLabel(mode)
                    + "）买家实付 §6" + buyerPay + " §7，卖家到手 §6" + sellerGot);
        }

        // ★ 批量上架：继续处理队列中的下一件（2026-10-11 任务2）
        java.util.LinkedList<ItemStack> q = publishQueue.get(uuid);
        if (q != null && !q.isEmpty()) {
            p.sendMessage("§7进度: 还剩 §f" + q.size() + " §7件待定价");
            beginNextPublishItem(p, uuid);
        } else {
            publishQueue.remove(uuid);
            publishQueueTotal.remove(uuid);
            p.sendMessage("§a§l[二手商店] §f全部商品已上架完成");
            if (p.isOnline()) openSecondHandShop(p, 0);
        }
    }

    /** 取消上架：清全部暂存状态并退还物品（批量模式下连同队列剩余一并退还） */
    private void abortPublish(Player p, UUID uuid, ItemStack item) {
        publishListening.remove(uuid);
        publishStep.remove(uuid);
        pendingPublishDesc.remove(uuid);
        pendingPublishItem.remove(uuid);
        editListingId.remove(uuid);
        returnItem(p, item);
        // ★ 批量上架（2026-10-11 任务2）：中止时把队列里未定价的物品全部退还
        java.util.LinkedList<ItemStack> q = publishQueue.remove(uuid);
        publishQueueTotal.remove(uuid);
        if (q != null && !q.isEmpty()) {
            int back = q.size() + (item != null ? 1 : 0);
            for (ItemStack it : q) returnItem(p, it);
            p.sendMessage("§7[二手商店] 批量上架已中止，共退还 §f" + back + " §7件物品");
        }
    }

    private void returnItem(Player p, ItemStack item) {
        if (item == null) return;
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack drop : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), drop);
        }
    }

    // ===== 购买二手商品 =====
    private void buySecondHand(Player p, SecondHandListing l) {
        if (l == null) return;
        // 卖家自己不可购买
        if (p.getName().equals(l.seller)) {
            p.sendMessage("§c§l[二手商店] §f不能购买自己上架的商品");
            return;
        }
        // 冻结拦截：买家
        if (plugin.getBonds().isFrozen(p.getName())) {
            p.sendMessage("§c§l[二手商店] §f债券账户被冻结，不可购买/出售商品");
            return;
        }
        // 冻结拦截：卖家（出售方）也被冻结则不可成交
        if (plugin.getBonds().isFrozen(l.seller)) {
            p.sendMessage("§c§l[二手商店] §f卖家债券账户被冻结，该商品暂不可购买");
            return;
        }
        // ★ 手续费真正扣款（2026-10-11 任务3 修复「手续费蒸发」严重bug）：
        //   奸商模式(买方出)：买家支付 price+手续费，卖家全额到手 price
        //   正常模式(卖方出)：买家支付 price，卖家到手 price-手续费
        //   手续费为平台抽成，不进入任何玩家账户。
        int[] shSplit = shFeeSplit(l.seller, l.price);
        final int fee = shSplit[0];
        final int buyerPaid = shSplit[1];
        final int sellerGot = shSplit[2];
        final String feeMode = shFeeModeFor(l.seller);
        // 扣买家钱（0 债券直接放行）
        if (buyerPaid > 0) {
            boolean ok = plugin.getBonds().deductBonds(
                    p.getName(), buyerPaid, "shop_secondhand",
                    l.seller, "二手商店",
                    "购买二手商品#" + l.id
                            + (fee > 0 ? "（含手续费" + fee + "）" : ""));
            if (!ok) {
                p.sendMessage("§c§l[二手商店] §f债券不足或扣款失败"
                        + (fee > 0 ? "，需 §6" + buyerPaid + " §f枚（含手续费）" : ""));
                return;
            }
        }
        // 给卖家钱（已按偏好扣除手续费）
        if (sellerGot > 0) {
            plugin.getBonds().addBonds(
                    l.seller, sellerGot, "shop_secondhand",
                    p.getName(), "二手商店",
                    "出售二手商品#" + l.id
                            + (fee > 0 ? "（已扣手续费" + fee + "）" : ""));
        }
        // 发货
        ItemStack is = base64ToItem(l.itemB64);
        if (is == null) {
            p.sendMessage("§c§l[二手商店] §f商品数据损坏，已退款");
            if (l.price > 0) {
                plugin.getBonds().addBonds(
                        p.getName(), buyerPaid, "shop_secondhand_refund",
                        l.seller, "二手商店", "商品损坏退款#" + l.id);
            }
            secondHandListings.remove(l);
            saveSecondHand();
            return;
        }
        // ★ 防御历史脏数据：SN / 宝箱物品即使已挂出也不可成交（2026-10-11）
        String blockedBuy = secondHandBlockReason(is);
        if (blockedBuy != null) {
            if (l.price > 0) {
                plugin.getBonds().addBonds(
                        p.getName(), buyerPaid, "shop_secondhand_refund",
                        l.seller, "二手商店", "违禁商品下架退款#" + l.id);
            }
            secondHandListings.remove(l);
            saveSecondHand();
            p.sendMessage("§c§l[二手商店] §f该商品为违禁物品（" + blockedBuy + "），已自动下架并退款");
            Player seller = Bukkit.getPlayer(l.seller);
            if (seller != null && seller.isOnline()) {
                seller.sendMessage("§c§l[二手商店] §f你的商品 #" + l.id + " 含违禁物品（"
                        + blockedBuy + "），已自动下架");
            }
            openSecondHandShop(p, 0);
            return;
        }
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(is);
        for (ItemStack drop : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), drop);
        }
        secondHandListings.remove(l);
        saveSecondHand();
        // ★ 购买成功立刻触发一次增量热重载，把配置数据对齐（2026-10-11 问题4 例外）
        reloadSecondHandConfigIncremental("购买触发");
        p.sendMessage("§a§l[二手商店] §f购买成功: §e" + ItemNameResolver.toChinese(is.getType())
                + " §7x" + is.getAmount() + " §6" + l.price + " §7枚债券");
        if (fee > 0) {
            p.sendMessage("§7手续费 §6" + fee + " §7（" + feeModeLabel(feeMode)
                    + "）买家实付 §6" + buyerPaid + " §7，卖家到手 §6" + sellerGot);
        }
        // ★ 交易记录入库（2026-10-11 任务5）
        TradeLogManager shTlog = plugin.getTradeLog();
        if (shTlog != null) {
            shTlog.recordSecondHandOrder(l.id, p.getName(), l.seller,
                    is.getType().name(), is.getAmount(), l.price,
                    fee, feeMode, buyerPaid, sellerGot);
        }
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null && seller.isOnline()) {
            seller.sendMessage("§a§l[二手商店] §f你的商品已售出: §e" + ItemNameResolver.toChinese(is.getType())
                    + " §7x" + is.getAmount() + " §6" + l.price + " §7枚债券"
                    + (fee > 0 ? "§7，到手 §6" + sellerGot
                    + " §7（已扣手续费 §6" + fee + "§7）" : ""));
        }
        openSecondHandShop(p, getSecondHandPage(p.getUniqueId()));
    }
}
