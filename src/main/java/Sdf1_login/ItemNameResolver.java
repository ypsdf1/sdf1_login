package Sdf1_login;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * 物品名翻译中间人（2026-10-11）。
 * <p>
 * 玩家在聊天栏输入物品 ID 时，不是人人都能背下 MC 每个物品的英文 ID。
 * 本类把中文名（如「钻石」）翻译为标准英文 Material 名（如 diamond），
 * 入库统一用英文标准名，回收/匹配时按英文名比对。
 * <p>
 * 解析顺序：
 * 1. 英文直通（Material.matchMaterial，支持 minecraft: 前缀与 legacy 名）
 * 2. 中文精确查表
 * 3. 归一化后中文查表（去空格、「minecraft:」前缀、大小写不敏感）
 * 4. 唯一前缀匹配（输入「钻」唯一命中「钻石」时可用；多候选则拒绝并提示）
 */
public final class ItemNameResolver {

    private ItemNameResolver() {}

    /** 中文名 → 英文 Material 名（大写）。与 Sdf1_game 寻宝 ITEM_CN 口径对齐。 */
    private static final Map<String, String> CN2EN = new HashMap<>();

    static {
        // ===== 货币/矿物/锭 =====
        CN2EN.put("钻石", "DIAMOND");
        CN2EN.put("绿宝石", "EMERALD");
        CN2EN.put("铁锭", "IRON_INGOT");
        CN2EN.put("金锭", "GOLD_INGOT");
        CN2EN.put("铜锭", "COPPER_INGOT");
        CN2EN.put("下界合金锭", "NETHERITE_INGOT");
        CN2EN.put("下界合金碎片", "NETHERITE_SCRAP");
        CN2EN.put("铁粒", "IRON_NUGGET");
        CN2EN.put("金粒", "GOLD_NUGGET");
        CN2EN.put("煤炭", "COAL");
        CN2EN.put("木炭", "CHARCOAL");
        CN2EN.put("红石", "REDSTONE");
        CN2EN.put("红石粉", "REDSTONE");
        CN2EN.put("青金石", "LAPIS_LAZULI");
        CN2EN.put("石英", "QUARTZ");
        CN2EN.put("远古残骸", "ANCIENT_DEBRIS");
        CN2EN.put("钻石矿石", "DIAMOND_ORE");
        CN2EN.put("绿宝石矿石", "EMERALD_ORE");
        CN2EN.put("金矿石", "GOLD_ORE");
        CN2EN.put("铁矿石", "IRON_ORE");
        CN2EN.put("煤矿石", "COAL_ORE");
        CN2EN.put("红石矿石", "REDSTONE_ORE");
        CN2EN.put("下界金矿石", "NETHER_GOLD_ORE");

        // ===== 木材 =====
        CN2EN.put("木棍", "STICK");
        CN2EN.put("橡木木板", "OAK_PLANKS");
        CN2EN.put("云杉木板", "SPRUCE_PLANKS");
        CN2EN.put("白桦木板", "BIRCH_PLANKS");
        CN2EN.put("丛林木板", "JUNGLE_PLANKS");
        CN2EN.put("金合欢木板", "ACACIA_PLANKS");
        CN2EN.put("深色橡木木板", "DARK_OAK_PLANKS");
        CN2EN.put("红树木板", "MANGROVE_PLANKS");
        CN2EN.put("樱花木板", "CHERRY_PLANKS");
        CN2EN.put("竹板", "BAMBOO_PLANKS");
        CN2EN.put("橡木原木", "OAK_LOG");
        CN2EN.put("云杉原木", "SPRUCE_LOG");
        CN2EN.put("白桦原木", "BIRCH_LOG");
        CN2EN.put("丛林原木", "JUNGLE_LOG");
        CN2EN.put("金合欢原木", "ACACIA_LOG");
        CN2EN.put("深色橡木原木", "DARK_OAK_LOG");
        CN2EN.put("红树木", "MANGROVE_LOG");
        CN2EN.put("樱花木", "CHERRY_LOG");
        CN2EN.put("诡异菌柄", "WARPED_STEM");
        CN2EN.put("绯红菌柄", "CRIMSON_STEM");

        // ===== 工具 =====
        CN2EN.put("木镐", "WOODEN_PICKAXE");
        CN2EN.put("石镐", "STONE_PICKAXE");
        CN2EN.put("铁镐", "IRON_PICKAXE");
        CN2EN.put("金镐", "GOLDEN_PICKAXE");
        CN2EN.put("钻石镐", "DIAMOND_PICKAXE");
        CN2EN.put("下界合金镐", "NETHERITE_PICKAXE");
        CN2EN.put("木锹", "WOODEN_SHOVEL");
        CN2EN.put("石锹", "STONE_SHOVEL");
        CN2EN.put("铁锹", "IRON_SHOVEL");
        CN2EN.put("金锹", "GOLDEN_SHOVEL");
        CN2EN.put("钻石锹", "DIAMOND_SHOVEL");
        CN2EN.put("下界合金锹", "NETHERITE_SHOVEL");
        CN2EN.put("木斧", "WOODEN_AXE");
        CN2EN.put("石斧", "STONE_AXE");
        CN2EN.put("铁斧", "IRON_AXE");
        CN2EN.put("金斧", "GOLDEN_AXE");
        CN2EN.put("钻石斧", "DIAMOND_AXE");
        CN2EN.put("下界合金斧", "NETHERITE_AXE");
        CN2EN.put("木锄", "WOODEN_HOE");
        CN2EN.put("石锄", "STONE_HOE");
        CN2EN.put("铁锄", "IRON_HOE");
        CN2EN.put("金锄", "GOLDEN_HOE");
        CN2EN.put("钻石锄", "DIAMOND_HOE");
        CN2EN.put("下界合金锄", "NETHERITE_HOE");
        CN2EN.put("剪刀", "SHEARS");
        CN2EN.put("钓鱼竿", "FISHING_ROD");
        CN2EN.put("打火石", "FLINT_AND_STEEL");
        CN2EN.put("燧石", "FLINT");
        CN2EN.put("指南针", "COMPASS");
        CN2EN.put("时钟", "CLOCK");
        CN2EN.put("望远镜", "SPYGLASS");
        CN2EN.put("刷怪蛋", "SPAWN_EGG");

        // ===== 武器 =====
        CN2EN.put("木剑", "WOODEN_SWORD");
        CN2EN.put("石剑", "STONE_SWORD");
        CN2EN.put("铁剑", "IRON_SWORD");
        CN2EN.put("金剑", "GOLDEN_SWORD");
        CN2EN.put("钻石剑", "DIAMOND_SWORD");
        CN2EN.put("下界合金剑", "NETHERITE_SWORD");
        CN2EN.put("弓", "BOW");
        CN2EN.put("箭", "ARROW");
        CN2EN.put("箭矢", "ARROW");
        CN2EN.put("十字弩", "CROSSBOW");
        CN2EN.put("三叉戟", "TRIDENT");
        CN2EN.put("盾牌", "SHIELD");
        CN2EN.put("烈焰棒", "BLAZE_ROD");
        CN2EN.put("旋风棒", "BREEZE_ROD");
        CN2EN.put("旋风之杖", "BREEZE_ROD");

        // ===== 防具 =====
        CN2EN.put("皮革头盔", "LEATHER_HELMET");
        CN2EN.put("皮革外套", "LEATHER_CHESTPLATE");
        CN2EN.put("皮革护腿", "LEATHER_LEGGINGS");
        CN2EN.put("皮革靴子", "LEATHER_BOOTS");
        CN2EN.put("锁链头盔", "CHAINMAIL_HELMET");
        CN2EN.put("锁链胸甲", "CHAINMAIL_CHESTPLATE");
        CN2EN.put("锁链护腿", "CHAINMAIL_LEGGINGS");
        CN2EN.put("锁链靴子", "CHAINMAIL_BOOTS");
        CN2EN.put("铁头盔", "IRON_HELMET");
        CN2EN.put("铁胸甲", "IRON_CHESTPLATE");
        CN2EN.put("铁护腿", "IRON_LEGGINGS");
        CN2EN.put("铁靴子", "IRON_BOOTS");
        CN2EN.put("金头盔", "GOLDEN_HELMET");
        CN2EN.put("金胸甲", "GOLDEN_CHESTPLATE");
        CN2EN.put("金护腿", "GOLDEN_LEGGINGS");
        CN2EN.put("金靴子", "GOLDEN_BOOTS");
        CN2EN.put("钻石头盔", "DIAMOND_HELMET");
        CN2EN.put("钻石胸甲", "DIAMOND_CHESTPLATE");
        CN2EN.put("钻石护甲", "DIAMOND_CHESTPLATE");
        CN2EN.put("钻石护腿", "DIAMOND_LEGGINGS");
        CN2EN.put("钻石靴子", "DIAMOND_BOOTS");
        CN2EN.put("下界合金头盔", "NETHERITE_HELMET");
        CN2EN.put("下界合金胸甲", "NETHERITE_CHESTPLATE");
        CN2EN.put("下界合金护腿", "NETHERITE_LEGGINGS");
        CN2EN.put("下界合金靴子", "NETHERITE_BOOTS");
        CN2EN.put("钻石头盔", "DIAMOND_HELMET");
        CN2EN.put("鞘翅", "ELYTRA");
        CN2EN.put("不死图腾", "TOTEM_OF_UNDYING");
        CN2EN.put("图腾", "TOTEM_OF_UNDYING");

        // ===== 食物 =====
        CN2EN.put("苹果", "APPLE");
        CN2EN.put("金苹果", "GOLDEN_APPLE");
        CN2EN.put("附魔金苹果", "ENCHANTED_GOLDEN_APPLE");
        CN2EN.put("面包", "BREAD");
        CN2EN.put("熟牛排", "COOKED_BEEF");
        CN2EN.put("牛排", "COOKED_BEEF");
        CN2EN.put("生猪排", "PORKCHOP");
        CN2EN.put("熟猪排", "COOKED_PORKCHOP");
        CN2EN.put("生鸡肉", "CHICKEN");
        CN2EN.put("熟鸡肉", "COOKED_CHICKEN");
        CN2EN.put("生羊肉", "MUTTON");
        CN2EN.put("熟羊肉", "COOKED_MUTTON");
        CN2EN.put("生牛肉", "BEEF");
        CN2EN.put("曲奇", "COOKIE");
        CN2EN.put("蛋糕", "CAKE");
        CN2EN.put("胡萝卜", "CARROT");
        CN2EN.put("马铃薯", "POTATO");
        CN2EN.put("烤马铃薯", "BAKED_POTATO");
        CN2EN.put("西瓜片", "MELON_SLICE");
        CN2EN.put("南瓜派", "PUMPKIN_PIE");
        CN2EN.put("糖", "SUGAR");
        CN2EN.put("碗", "BOWL");
        CN2EN.put("蘑菇煲", "MUSHROOM_STEW");
        CN2EN.put("甜浆果", "SWEET_BERRIES");
        CN2EN.put("蜂蜜瓶", "HONEY_BOTTLE");

        // ===== 建筑/常见方块 =====
        CN2EN.put("石头", "STONE");
        CN2EN.put("圆石", "COBBLESTONE");
        CN2EN.put("泥土", "DIRT");
        CN2EN.put("草方块", "GRASS_BLOCK");
        CN2EN.put("沙子", "SAND");
        CN2EN.put("红沙", "RED_SAND");
        CN2EN.put("砂砾", "GRAVEL");
        CN2EN.put("粘土球", "CLAY_BALL");
        CN2EN.put("粘土", "CLAY");
        CN2EN.put("雪块", "SNOW_BLOCK");
        CN2EN.put("冰", "ICE");
        CN2EN.put("黑曜石", "OBSIDIAN");
        CN2EN.put("下界砖", "NETHER_BRICKS");
        CN2EN.put("萤石", "GLOWSTONE");
        CN2EN.put("萤石粉", "GLOWSTONE_DUST");
        CN2EN.put("海晶石", "PRISMARINE");
        CN2EN.put("工作台", "CRAFTING_TABLE");
        CN2EN.put("熔炉", "FURNACE");
        CN2EN.put("箱子", "CHEST");
        CN2EN.put("木桶", "BARREL");
        CN2EN.put("末影箱", "ENDER_CHEST");
        CN2EN.put("潜影盒", "SHULKER_BOX");
        CN2EN.put("铁砧", "ANVIL");
        CN2EN.put("附魔台", "ENCHANTING_TABLE");
        CN2EN.put("酿造台", "BREWING_STAND");
        CN2EN.put("信标", "BEACON");
        CN2EN.put("床", "RED_BED");
        CN2EN.put("红色床", "RED_BED");
        CN2EN.put("门", "OAK_DOOR");
        CN2EN.put("橡木门", "OAK_DOOR");
        CN2EN.put("活塞", "PISTON");
        CN2EN.put("粘性活塞", "STICKY_PISTON");
        CN2EN.put("红石火把", "REDSTONE_TORCH");
        CN2EN.put("拉杆", "LEVER");
        CN2EN.put("按钮", "STONE_BUTTON");
        CN2EN.put("压力板", "STONE_PRESSURE_PLATE");
        CN2EN.put("铁块", "IRON_BLOCK");
        CN2EN.put("金块", "GOLD_BLOCK");
        CN2EN.put("钻石块", "DIAMOND_BLOCK");
        CN2EN.put("绿宝石块", "EMERALD_BLOCK");
        CN2EN.put("下界合金块", "NETHERITE_BLOCK");
        CN2EN.put("TNT", "TNT");
        CN2EN.put("苦力怕头颅", "CREEPER_HEAD");
        CN2EN.put("爬行者头颅", "CREEPER_HEAD");
        CN2EN.put("骷髅头颅", "SKELETON_SKULL");
        CN2EN.put("僵尸头颅", "ZOMBIE_HEAD");
        CN2EN.put("龙蛋", "DRAGON_EGG");

        // ===== 材料/杂项 =====
        CN2EN.put("末影珍珠", "ENDER_PEARL");
        CN2EN.put("末影之眼", "ENDER_EYE");
        CN2EN.put("恶魂之泪", "GHAST_TEAR");
        CN2EN.put("潜影壳", "SHULKER_SHELL");
        CN2EN.put("粘液球", "SLIME_BALL");
        CN2EN.put("粘液块", "SLIME_BLOCK");
        CN2EN.put("烈焰粉", "BLAZE_POWDER");
        CN2EN.put("岩浆膏", "MAGMA_CREAM");
        CN2EN.put("熔浆膏", "MAGMA_CREAM");
        CN2EN.put("下界疣", "NETHER_WART");
        CN2EN.put("地狱疣", "NETHER_WART");
        CN2EN.put("发酵蛛眼", "FERMENTED_SPIDER_EYE");
        CN2EN.put("蜘蛛眼", "SPIDER_EYE");
        CN2EN.put("玻璃瓶", "GLASS_BOTTLE");
        CN2EN.put("水瓶", "POTION");
        CN2EN.put("药水", "POTION");
        CN2EN.put("喷溅型药水", "SPLASH_POTION");
        CN2EN.put("滞留型药水", "LINGERING_POTION");
        CN2EN.put("附魔书", "ENCHANTED_BOOK");
        CN2EN.put("书", "BOOK");
        CN2EN.put("皮革", "LEATHER");
        CN2EN.put("羽毛", "FEATHER");
        CN2EN.put("线", "STRING");
        CN2EN.put("火药", "GUNPOWDER");
        CN2EN.put("小麦", "WHEAT");
        CN2EN.put("种子", "WHEAT_SEEDS");
        CN2EN.put("树苗", "OAK_SAPLING");
        CN2EN.put("仙人掌", "CACTUS");
        CN2EN.put("甘蔗", "SUGAR_CANE");
        CN2EN.put("南瓜", "PUMPKIN");
        CN2EN.put("西瓜", "MELON");
        CN2EN.put("海带", "KELP");
        CN2EN.put("海带干", "DRIED_KELP");
        CN2EN.put("经验瓶", "EXPERIENCE_BOTTLE");
        CN2EN.put("烟花火箭", "FIREWORK_ROCKET");
        CN2EN.put("火焰弹", "FIRE_CHARGE");
        CN2EN.put("告示牌", "OAK_SIGN");
        CN2EN.put("旗帜", "WHITE_BANNER");
        CN2EN.put("物品展示框", "ITEM_FRAME");
        CN2EN.put("盔甲架", "ARMOR_STAND");
        CN2EN.put("矿车", "MINECART");
        CN2EN.put("船", "OAK_BOAT");
        CN2EN.put("鞍", "SADDLE");
        CN2EN.put("马铠", "IRON_HORSE_ARMOR");
        CN2EN.put("铁马铠", "IRON_HORSE_ARMOR");
        CN2EN.put("金马铠", "GOLDEN_HORSE_ARMOR");
        CN2EN.put("钻石马铠", "DIAMOND_HORSE_ARMOR");
    }

    /**
     * 解析玩家输入为 Material。
     *
     * @param input 玩家输入（英文 ID / minecraft: 前缀 / 中文名）
     * @return Material；无法解析返回 null
     */
    public static Material resolve(String input) {
        if (input == null) return null;
        String raw = input.trim();
        if (raw.isEmpty()) return null;

        // 1. 英文直通（含 minecraft: 前缀与 legacy 名）
        Material m = Material.matchMaterial(raw);
        if (m != null) return m;

        // 2. 中文精确查表
        String en = CN2EN.get(raw);
        if (en != null) {
            m = Material.matchMaterial(en);
            if (m != null) return m;
        }

        // 3. 归一化后再查（去空格、去 minecraft: 前缀、小写）
        String norm = raw.replace(" ", "").toLowerCase();
        if (!norm.equals(raw)) {
            m = Material.matchMaterial(norm);
            if (m != null) return m;
            en = CN2EN.get(norm);
            if (en != null) {
                m = Material.matchMaterial(en);
                if (m != null) return m;
            }
        }

        // 4. 唯一前缀匹配（输入「钻」时，若表内只有一个候选以它开头才放行）
        String cnNorm = raw.replace(" ", "");
        String firstKey = null, firstVal = null;
        int hits = 0;
        for (Map.Entry<String, String> e : CN2EN.entrySet()) {
            if (e.getKey().startsWith(cnNorm)) {
                hits++;
                if (hits == 1) { firstKey = e.getKey(); firstVal = e.getValue(); }
            }
        }
        if (hits == 1 && firstVal != null) {
            m = Material.matchMaterial(firstVal);
            if (m != null) {
                // 提示玩家实际匹配到的完整名
                return m;
            }
        }
        return null;
    }

    /** 解析并附带「实际匹配到的中文名」用于提示（如输入「钻」→ 显示「钻石」）。 */
    public static Material resolveWithHint(String input, StringBuilder hintOut) {
        if (input == null) return null;
        String raw = input.trim();
        // 唯一前缀命中时把完整中文名写进 hint
        String cnNorm = raw.replace(" ", "");
        String firstKey = null;
        int hits = 0;
        for (String k : CN2EN.keySet()) {
            if (k.startsWith(cnNorm) && !k.equals(cnNorm)) {
                hits++;
                if (hits == 1) firstKey = k;
            }
        }
        if (hits == 1 && firstKey != null && hintOut != null) {
            hintOut.append(firstKey);
        }
        return resolve(input);
    }

    /** 英文标准名 → 中文显示名（找不到回退为原名）。 */
    public static String toChinese(Material mat) {
        if (mat == null) return "";
        String name = mat.name();
        for (Map.Entry<String, String> e : CN2EN.entrySet()) {
            if (e.getValue().equals(name)) return e.getKey();
        }
        // 回退：下划线转空格的 Title Case
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part.substring(0, 1).toUpperCase())
              .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
