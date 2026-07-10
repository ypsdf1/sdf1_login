package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.File;
import java.util.*;

/**
 * PVP 练习测试场管理器
 *
 * ★ 设计（2026-07-09 按用户需求）：
 *   - 插件首次加载时在独立世界 pvp_test 预生成 30×30 露天环境（FLAT 世界 + worldborder 边界 + 玻璃围墙），
 *     之后复用该世界（不随玩家离开而删除）。
 *   - 玩家输入 /pvp test 打开 GUI 面板：选择【难度】(简单/中等/噩梦) 与【装备】(近战/远程/混合) 后点击确认开始。
 *   - 开始后：备份并清空原背包 → 发放对应练习装备 → 传送至测试场 → 按装备类型生成练习生物
 *     （近战=僵尸 / 远程=小白 / 混合=僵尸+小白），所有练习生物血量统一 40。
 *   - 难度固定伤害（每次攻击）：简单(僵尸4/小白2)、中等(僵尸4/小白3)、噩梦(僵尸8/小白7)。
 *   - 噩梦难度：被玩家击中时随机走位（闪避）。
 *   - 测试场景下所有敌对生物【无视队友攻击】：取消 mob→mob 的目标与伤害、小白箭不伤友军。
 *   - 离开(/pvp leave) 或死亡/退出：清除练习生物、还原背包、传回主世界。
 */
public class PVPTestManager implements Listener {

    private final Main plugin;
    public static final String PVP_TEST_WORLD_NAME = "pvp_test";
    private static final String TEST_GUI_TITLE = "§6§lPVP测试场";

    /** 在场练习的玩家 */
    private final Set<UUID> inTest = new HashSet<>();
    /** 每个玩家选择的难度/装备（GUI 暂存） */
    private final Map<UUID, TestDifficulty> chosenDiff = new HashMap<>();
    private final Map<UUID, TestEquip> chosenEquip = new HashMap<>();
    /** 测试场玩家装备选项（测试世界跳过公平锁定，全员自由选） */
    private final Map<UUID, Boolean> testEnchant = new HashMap<>(); // 主武器是否附魔(锋利V+击退II)
    private final Map<UUID, Boolean> testShield = new HashMap<>();  // 是否拿盾牌
    private final Map<UUID, Integer> testFood = new HashMap<>();   // 熟牛肉数量(1~64，与主场一致)
    /** 自由装备档位（测试世界跳过公平锁定，全员自由选武器/护甲材质） */
    private final Map<UUID, Integer> testWeaponTier = new HashMap<>(); // 0铁 1金 2钻 3下界合金
    private final Map<UUID, Integer> testArmorTier = new HashMap<>();  // 0无 1铁 2金 3钻 4下界合金

    private static final Material[] SWORD_TIERS = {
            Material.IRON_SWORD, Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD };
    private static final Material[] AXE_TIERS = {
            Material.IRON_AXE, Material.GOLDEN_AXE, Material.DIAMOND_AXE, Material.NETHERITE_AXE };
    private static final String[] WEAPON_TIER_NAMES = { "§7铁", "§e金", "§b钻石", "§8下界合金" };
    private static final Material[][] ARMOR_SETS = {
            null,
            { Material.IRON_HELMET, Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS },
            { Material.GOLDEN_HELMET, Material.GOLDEN_CHESTPLATE, Material.GOLDEN_LEGGINGS, Material.GOLDEN_BOOTS },
            { Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS },
            { Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS } };
    private static final String[] ARMOR_TIER_NAMES = { "§7无", "§7铁", "§e金", "§b钻石", "§8下界合金" };
    /** 每个练习生物对应的难度（用于固定伤害 + 噩梦走位判定） */
    private final Map<UUID, TestDifficulty> mobDifficulty = new HashMap<>();
    /** 互殴拦截提示节流(玩家UUID→上次提示时间戳) */
    private final Map<UUID, Long> lastPvpWarn = new HashMap<>();
    /** 每个玩家生成的练习生物 UUID 列表（离开时按玩家清理） */
    private final Map<UUID, List<UUID>> playerMobs = new HashMap<>();
    /** 玩家原背包备份（仅内存，练习场为临时场景） */
    private final Map<UUID, InventoryBackup> inventoryBackups = new HashMap<>();

    private final Random rand = new Random();

    /** 刷怪波次间隔(刻)：10秒（玩家全清当前波次后才间隔刷下一波） */
    private static final long WAVE_INTERVAL_TICKS = 200L;
    /** 已安排"全清后10秒补一波"的玩家(防止重复排程) */
    private final Set<UUID> pendingRespawn = new HashSet<>();
    /** 练习生物 → 所属玩家(用于死亡事件反查触发下一波) */
    private final Map<UUID, UUID> mobOwner = new HashMap<>();

    // ==================== 枚举定义 ====================

    enum TestDifficulty {
        EASY("§a简单", 4.0, 2.0),
        MEDIUM("§e中等", 4.0, 3.0),
        NIGHTMARE("§c噩梦", 8.0, 7.0);

        final String display;
        final double zombieDamage;
        final double skeletonDamage;

        TestDifficulty(String display, double zombieDamage, double skeletonDamage) {
            this.display = display;
            this.zombieDamage = zombieDamage;
            this.skeletonDamage = skeletonDamage;
        }
    }

    enum TestEquip {
        MELEE("§c近战"),
        RANGED("§b远程"),
        MIXED("§6混合");

        final String display;

        TestEquip(String display) {
            this.display = display;
        }
    }

    /** 背包备份结构 */
    private static class InventoryBackup {
        final ItemStack[] contents;
        final ItemStack[] armor;
        final ItemStack offHand;
        final int level;
        final float exp;

        InventoryBackup(ItemStack[] contents, ItemStack[] armor, ItemStack offHand, int level, float exp) {
            this.contents = contents;
            this.armor = armor;
            this.offHand = offHand;
            this.level = level;
            this.exp = exp;
        }
    }

    public PVPTestManager(Main plugin) {
        this.plugin = plugin;
    }

    // ==================== 世界初始化 ====================

    /**
     * 确保测试场世界存在（首次加载时生成；之后若磁盘有残留则直接加载复用）。
     * 在主线程同步执行（Bukkit.createWorld 触发 WorldInitEvent 必须主线程）。
     * FLAT 世界生成极快，不会卡服。
     */
    public void ensurePVPTestWorldExists() {
        File folder = new File(Bukkit.getWorldContainer(), PVP_TEST_WORLD_NAME);
        boolean fresh = !folder.exists();

        World w = Bukkit.getWorld(PVP_TEST_WORLD_NAME);
        if (w == null) {
            plugin.getLogger().info("[PVP测试] 创建/加载测试场世界 " + PVP_TEST_WORLD_NAME
                    + (fresh ? " (首次生成)" : " (从磁盘加载)"));
            WorldCreator c = new WorldCreator(PVP_TEST_WORLD_NAME);
            c.environment(World.Environment.NORMAL);
            c.type(WorldType.FLAT);
            c.generateStructures(false);
            c.seed(20260709L); // 固定种子，保证环境一致
            w = Bukkit.createWorld(c);
        }
        if (w == null) {
            plugin.getLogger().severe("[PVP测试] 无法创建测试场世界!");
            return;
        }

        // 世界规则：常昼 / 无天气 / 关闭自然刷怪（只有我们生成的练习生物）/ PVP 开启
        // ★ KEEP_INVENTORY=false：死亡掉落由事件处理器清空掉落物+复活时还原，避免gamerule与事件冲突
        w.setPVP(true);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, false);
        w.setTime(6000);
        w.setKeepSpawnInMemory(false);

        // ★ 测试场必须非和平：主世界和平时 MC 会在和平难度下瞬间移除所有敌对生物（含插件生成的练习生物）。
        //   此处在世界创建/加载即生效，不依赖进入测试的流程（之前只在 startTest 内设置，导致加载出的世界仍是和平）。
        applyTestWorldDifficulty(w);

        // 30×30 世界边界（中心 0,0，直径 30）
        WorldBorder wb = w.getWorldBorder();
        wb.setCenter(0, 0);
        wb.setSize(30.0);
        wb.setDamageAmount(0.0);   // 仅阻挡不造成伤害
        wb.setWarningDistance(0);

        // 首次生成时搭建边界玻璃墙
        if (fresh) {
            buildBoundary(w);
        }

        // 预加载出生点区块
        w.loadChunk(w.getSpawnLocation().getChunk());
        plugin.getLogger().info("[PVP测试] 测试场世界就绪 (FLAT露天, 30x30边界, 自然刷怪已关闭)");
    }

    /**
     * 测试场难度：必须非和平，否则 MC 在和平难度下会瞬间移除所有敌对生物（含插件 spawnEntity 生成的练习生物）。
     * 主世界和平 → 强制困难；主世界非和平 → 沿用主世界难度。
     * 在「世界创建/加载」「开始测试」「全部离场」三处都会调用，保证不依赖进入流程也能生效。
     */
    private void applyTestWorldDifficulty(World w) {
        if (w == null) return;
        World main = Bukkit.getWorlds().get(0);
        Difficulty d = (main != null && main.getDifficulty() == Difficulty.PEACEFUL)
                ? Difficulty.HARD
                : (main != null ? main.getDifficulty() : Difficulty.NORMAL);
        w.setDifficulty(d);
    }

    /** 在边界(±15)搭建 4 格高玻璃墙，作为露天练习场的可见边界 */
    private void buildBoundary(World w) {
        int r = 15;
        int baseY = w.getHighestBlockYAt(0, 0) + 1;
        for (int i = -r; i <= r; i++) {
            for (int h = 0; h < 4; h++) {
                setBlock(w, i, baseY + h, -r, Material.GLASS);
                setBlock(w, i, baseY + h, r, Material.GLASS);
                setBlock(w, -r, baseY + h, i, Material.GLASS);
                setBlock(w, r, baseY + h, i, Material.GLASS);
            }
        }
        plugin.getLogger().info("[PVP测试] 已搭建 30x30 边界玻璃墙");
    }

    private void setBlock(World w, int x, int y, int z, Material m) {
        Block b = w.getBlockAt(x, y, z);
        if (b.getType().isAir()) b.setType(m);
    }

    /** 测试场安全出生点（FLAT 世界地表上方） */
    private Location getSafeSpawn(World w) {
        int y = w.getHighestBlockYAt(0, 0) + 1;
        return new Location(w, 0.5, y, 0.5);
    }

    // ==================== GUI ====================

    public void openGUI(Player p) {
        Inventory gui = Bukkit.createInventory(null, 54, TEST_GUI_TITLE);

        TestDifficulty diff = chosenDiff.getOrDefault(p.getUniqueId(), TestDifficulty.EASY);
        TestEquip equip = chosenEquip.getOrDefault(p.getUniqueId(), TestEquip.MELEE);
        // 测试世界跳过公平锁定，全员自由选装备（附魔/盾牌/熟牛肉数量），默认附魔关、盾牌开、熟牛肉16
        testEnchant.putIfAbsent(p.getUniqueId(), false);
        testShield.putIfAbsent(p.getUniqueId(), true);
        testFood.putIfAbsent(p.getUniqueId(), 16);
        testWeaponTier.putIfAbsent(p.getUniqueId(), 0);
        testArmorTier.putIfAbsent(p.getUniqueId(), 0);
        boolean enchant = testEnchant.get(p.getUniqueId());
        boolean shield = testShield.get(p.getUniqueId());
        int food = testFood.get(p.getUniqueId());
        int weaponTier = testWeaponTier.get(p.getUniqueId());
        int armorTier = testArmorTier.get(p.getUniqueId());

        // ★ 顶部预览（0-7）：当前所选装备
        gui.setItem(0, previewItem(SWORD_TIERS[weaponTier], "§b§l" + WEAPON_TIER_NAMES[weaponTier] + "§l剑", "§7主武器·必发"));
        gui.setItem(1, previewItem(AXE_TIERS[weaponTier], "§b§l" + WEAPON_TIER_NAMES[weaponTier] + "§l斧", "§7主武器·必发"));
        if (armorTier > 0 && ARMOR_SETS[armorTier] != null) {
            gui.setItem(2, previewItem(ARMOR_SETS[armorTier][0], "§b§l" + ARMOR_TIER_NAMES[armorTier] + "§l盔", "§7护甲·全套4件"));
            gui.setItem(3, previewItem(ARMOR_SETS[armorTier][1], "§b§l" + ARMOR_TIER_NAMES[armorTier] + "§l胸甲", "§7护甲·全套4件"));
            gui.setItem(4, previewItem(ARMOR_SETS[armorTier][2], "§b§l" + ARMOR_TIER_NAMES[armorTier] + "§l腿甲", "§7护甲·全套4件"));
            gui.setItem(5, previewItem(ARMOR_SETS[armorTier][3], "§b§l" + ARMOR_TIER_NAMES[armorTier] + "§l靴", "§7护甲·全套4件"));
        } else {
            for (int k = 2; k <= 5; k++) gui.setItem(k, grayPreview("§7§l无护甲", "§7不穿护甲"));
        }
        if (shield) gui.setItem(6, previewItem(Material.SHIELD, "§a§l盾牌 ✓", "§7已选·副手装备"));
        else gui.setItem(6, grayPreview("§7盾牌 ✗", "§7未选（可开启）"));
        gui.setItem(7, previewItem(Material.COOKED_BEEF, "§c§l熟牛肉 x" + food, "§7补血普通食物"));

        // 信息面板（8）
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoM = info.getItemMeta();
        if (infoM != null) {
            infoM.setDisplayName("§6§lPVP测试场设置");
            List<String> lore = new ArrayList<>();
            lore.add("§7直接点击选择装备/难度/场景，点击确认开始");
            lore.add("");
            lore.add("§e当前难度: " + diff.display);
            lore.add("§e练习场景: " + equip.display);
            lore.add("§e主武器: " + WEAPON_TIER_NAMES[weaponTier] + "§b剑 + " + WEAPON_TIER_NAMES[weaponTier] + "§b斧");
            lore.add("§e护甲: " + ARMOR_TIER_NAMES[armorTier] + (armorTier > 0 ? "§b全套4件" : ""));
            lore.add("§e附魔: " + (enchant ? "§a开启" : "§c关闭"));
            lore.add("§e盾牌: " + (shield ? "§a装备" : "§c不装备"));
            lore.add("§e熟牛肉: §a" + food + " §7个");
            infoM.setLore(lore);
            info.setItemMeta(infoM);
        }
        gui.setItem(8, info);

        // 分组标签
        gui.setItem(9, labelItem("§6§l主武器材质", "§7点击直接选择，自动切换"));
        gui.setItem(18, labelItem("§6§l护甲材质", "§7点击直接选择（含「无」）"));
        gui.setItem(27, labelItem("§6§l难度 / 场景", "§7点击直接选择"));
        gui.setItem(36, labelItem("§6§l其他选项", "§7附魔 / 盾牌"));

        // ★ 武器材质 4 档（10-13）：铁/金/钻/合金（直选）
        for (int t = 0; t < 4; t++) {
            gui.setItem(10 + t, makeWeaponTierButton(SWORD_TIERS[t], WEAPON_TIER_NAMES[t] + "§l剑", t, weaponTier));
        }
        // ★ 护甲材质 5 档（19-23）：无/铁/金/钻/合金（直选，tier=0 为不穿）
        for (int t = 0; t < 5; t++) {
            gui.setItem(19 + t, makeArmorTierButton(t, armorTier));
        }
        // ★ 难度 3 档（28/29/30）：简单/中等/噩梦（直选）
        gui.setItem(28, makeDiffButton(TestDifficulty.EASY, diff));
        gui.setItem(29, makeDiffButton(TestDifficulty.MEDIUM, diff));
        gui.setItem(30, makeDiffButton(TestDifficulty.NIGHTMARE, diff));
        // ★ 场景 3 档（32/33/34）：近战/远程/混合（直选）
        gui.setItem(32, makeSceneButton(TestEquip.MELEE, equip));
        gui.setItem(33, makeSceneButton(TestEquip.RANGED, equip));
        gui.setItem(34, makeSceneButton(TestEquip.MIXED, equip));

        // ★ 附魔开关（37）
        ItemStack enchBtn = new ItemStack(enchant ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta em = enchBtn.getItemMeta();
        if (em != null) {
            em.setDisplayName(enchant ? "§a§l附魔: 开" : "§c§l附魔: 关");
            em.setLore(Arrays.asList("§7锋利V + 击退II（铁剑/铁斧）", enchant ? "§a点击关闭" : "§a点击开启"));
            enchBtn.setItemMeta(em);
        }
        gui.setItem(37, enchBtn);

        // ★ 盾牌开关（38）
        ItemStack shBtn = new ItemStack(shield ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta sm2 = shBtn.getItemMeta();
        if (sm2 != null) {
            sm2.setDisplayName(shield ? "§a§l盾牌: 装备" : "§c§l盾牌: 不装备");
            sm2.setLore(Arrays.asList("§7副手装备", shield ? "§a点击取消" : "§a点击装备"));
            shBtn.setItemMeta(sm2);
        }
        gui.setItem(38, shBtn);

        // ★ 熟牛肉快捷数量（45-48 = 8/16/32/64，点哪个直接设为哪个）
        int[] foodOpts = {8, 16, 32, 64};
        for (int i = 0; i < 4; i++) {
            gui.setItem(45 + i, makeFoodButton(foodOpts[i], food));
        }

        // 确认开始按钮（49）
        ItemStack start = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta sm = start.getItemMeta();
        if (sm != null) {
            sm.setDisplayName("§a§l确认开始");
            sm.setLore(Arrays.asList(
                    "§7难度: " + diff.display,
                    "§7场景: " + equip.display,
                    "§7武器: " + WEAPON_TIER_NAMES[weaponTier] + "§b剑 + " + WEAPON_TIER_NAMES[weaponTier] + "§b斧",
                    "§7护甲: " + ARMOR_TIER_NAMES[armorTier] + (armorTier > 0 ? "§b全套4件" : ""),
                    "§7附魔: " + (enchant ? "§d开启" : "§7关闭"),
                    "§7盾牌: " + (shield ? "§a装备" : "§7不装备"),
                    "§7熟牛肉 x" + food,
                    "",
                    "§e点击进入测试场"));
            start.setItemMeta(sm);
        }
        gui.setItem(49, start);

        // 装饰填充
        for (int i = 0; i < 54; i++) {
            if (gui.getItem(i) == null) {
                gui.setItem(i, new ItemStack(Material.GRAY_STAINED_GLASS_PANE));
            }
        }
        p.openInventory(gui);
    }

    private ItemStack previewItem(Material mat, String name, String desc) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(Arrays.asList(desc));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack grayPreview(String name, String desc) {
        return previewItem(Material.GRAY_STAINED_GLASS_PANE, name, desc);
    }

    private ItemStack labelItem(String name, String desc) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(name);
            m.setLore(Arrays.asList(desc));
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack makeWeaponTierButton(Material mat, String label, int tier, int selectedTier) {
        boolean selected = (tier == selectedTier);
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(label + (selected ? " §a✔" : ""));
            List<String> lore = new ArrayList<>();
            lore.add("§7主武器材质(剑+斧)");
            lore.add(selected ? "§a当前选择，点击取消" : "§a点击选择此材质");
            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack makeArmorTierButton(int tier, int selectedTier) {
        boolean selected = (tier == selectedTier);
        Material icon = (tier > 0 && ARMOR_SETS[tier] != null) ? ARMOR_SETS[tier][1] : Material.LEATHER_CHESTPLATE;
        ItemStack it = new ItemStack(icon);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName(ARMOR_TIER_NAMES[tier] + (selected ? " §a✔" : ""));
            List<String> lore = new ArrayList<>();
            if (tier == 0) lore.add("§7不穿护甲");
            else lore.add("§7全套护甲(4件)");
            lore.add(selected ? "§a当前选择，点击取消" : "§a点击选择此材质");
            m.setLore(lore);
            it.setItemMeta(m);
        }
        return it;
    }

    private ItemStack makeDiffButton(TestDifficulty d, TestDifficulty current) {
        boolean selected = (d == current);
        ItemStack btn = new ItemStack(selected ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta m = btn.getItemMeta();
        if (m != null) {
            m.setDisplayName(d.display + (selected ? " §a✔" : ""));
            List<String> lore = new ArrayList<>();
            lore.add("§7僵尸伤害: " + (int) d.zombieDamage);
            lore.add("§7小白伤害: " + (int) d.skeletonDamage);
            if (d == TestDifficulty.NIGHTMARE) lore.add("§c被攻击时随机走位");
            lore.add(selected ? "§a✓ 已选中" : "§e点击选择");
            m.setLore(lore);
            btn.setItemMeta(m);
        }
        return btn;
    }

    private ItemStack makeSceneButton(TestEquip e, TestEquip current) {
        boolean selected = (e == current);
        Material icon = e == TestEquip.MELEE ? Material.IRON_SWORD
                : e == TestEquip.RANGED ? Material.BOW : Material.NETHER_STAR;
        ItemStack btn = new ItemStack(icon);
        ItemMeta m = btn.getItemMeta();
        if (m != null) {
            m.setDisplayName(e.display + (selected ? " §a✔" : ""));
            List<String> lore = new ArrayList<>();
            lore.add(e == TestEquip.MELEE ? "§7练习近战(僵尸)" :
                    e == TestEquip.RANGED ? "§7练习远程(小白)" : "§7练习近战+远程");
            lore.add(selected ? "§a✓ 已选中" : "§e点击选择");
            m.setLore(lore);
            btn.setItemMeta(m);
        }
        return btn;
    }

    private ItemStack makeFoodButton(int foodVal, int currentFood) {
        boolean selected = (foodVal == currentFood);
        ItemStack it = new ItemStack(Material.COOKED_BEEF);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.setDisplayName("§c§l熟牛肉 x" + foodVal + (selected ? " §a✔" : ""));
            m.setLore(Arrays.asList("§7补血普通食物", selected ? "§a当前数量" : "§a点击直接设为 " + foodVal + " 个"));
            it.setItemMeta(m);
        }
        return it;
    }

    public void handleClick(Player p, int slot, boolean shift) {
        if (!p.getOpenInventory().getTitle().equals(TEST_GUI_TITLE)) return;

        UUID id = p.getUniqueId();

        // ★ 武器材质直选（10-13）：铁/金/钻/合金，点哪个直接切换
        if (slot >= 10 && slot <= 13) {
            testWeaponTier.put(id, slot - 10);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            openGUI(p); return;
        }
        // ★ 护甲材质直选（19-23）：无/铁/金/钻/合金
        if (slot >= 19 && slot <= 23) {
            testArmorTier.put(id, slot - 19);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            openGUI(p); return;
        }
        // ★ 难度直选（28/29/30）
        if (slot == 28) { chosenDiff.put(id, TestDifficulty.EASY); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        if (slot == 29) { chosenDiff.put(id, TestDifficulty.MEDIUM); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        if (slot == 30) { chosenDiff.put(id, TestDifficulty.NIGHTMARE); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        // ★ 场景直选（32/33/34）
        if (slot == 32) { chosenEquip.put(id, TestEquip.MELEE); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        if (slot == 33) { chosenEquip.put(id, TestEquip.RANGED); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        if (slot == 34) { chosenEquip.put(id, TestEquip.MIXED); p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f); openGUI(p); return; }
        // ★ 附魔开关（37）
        if (slot == 37) {
            testEnchant.put(id, !testEnchant.getOrDefault(id, false));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            openGUI(p); return;
        }
        // ★ 盾牌开关（38）
        if (slot == 38) {
            testShield.put(id, !testShield.getOrDefault(id, true));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            openGUI(p); return;
        }
        // ★ 熟牛肉直选（45-48 = 8/16/32/64）
        if (slot >= 45 && slot <= 48) {
            int[] foodOpts = {8, 16, 32, 64};
            testFood.put(id, foodOpts[slot - 45]);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            openGUI(p); return;
        }
        // ★ 确认开始（49）
        if (slot == 49) {
            p.closeInventory();
            startTest(p,
                    chosenDiff.getOrDefault(p.getUniqueId(), TestDifficulty.EASY),
                    chosenEquip.getOrDefault(p.getUniqueId(), TestEquip.MELEE));
            return;
        }
        // 标签 / 预览区（0-8, 9, 18, 27, 36）：仅音效
        if ((slot >= 0 && slot <= 8) || slot == 9 || slot == 18 || slot == 27 || slot == 36) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }
    }

    // ==================== 开始 / 离开 ====================

    public void startTest(Player p, TestDifficulty diff, TestEquip equip) {
        World w = Bukkit.getWorld(PVP_TEST_WORLD_NAME);
        if (w == null) {
            p.sendMessage("§c§l[PVP测试] 测试场世界未加载，请联系管理员");
            return;
        }

        // 已经在测试场则不允许重复进入（防止备份被覆盖）
        if (inTest.contains(p.getUniqueId())) {
            p.sendMessage("§e§l[PVP测试] 你已经在测试场中，无需重复进入");
            return;
        }

        // 兜底：死亡/断线后未正常恢复则先恢复，防止把场外/场内残留装备再带进去
        if (inventoryBackups.containsKey(p.getUniqueId())) {
            p.sendMessage("§e§l[PVP测试] 检测到未恢复背包，正在恢复...");
            restoreInventory(p);
            removePlayerMobs(p);
            chosenDiff.remove(p.getUniqueId());
            chosenEquip.remove(p.getUniqueId());
            pendingRespawn.remove(p.getUniqueId());
        }

        boolean enchant = testEnchant.getOrDefault(p.getUniqueId(), false);
        boolean shield = testShield.getOrDefault(p.getUniqueId(), true);
        int wt = testWeaponTier.getOrDefault(p.getUniqueId(), 0);
        int at = testArmorTier.getOrDefault(p.getUniqueId(), 0);

        // 清掉该玩家之前的练习生物（支持重新配置）
        removePlayerMobs(p);

        // 传送至测试场
        p.teleport(getSafeSpawn(w));

        // 备份并清空背包 → 发放练习装备
        backupInventory(p);
        clearInventory(p);
        giveEquip(p, equip);

        // 主世界难度（仅用于下方日志；测试场难度由 applyTestWorldDifficulty 统一处理）
        World main = Bukkit.getWorlds().get(0);
        // ★ 测试场难度必须非和平：否则插件生成的练习生物会被 MC 和平难度瞬间移除。详见 applyTestWorldDifficulty。
        applyTestWorldDifficulty(w);

        inTest.add(p.getUniqueId());

        // 立即来一波（之后仅在玩家清空当前波次后，间隔10秒再补下一波）
        spawnWaveFor(p);

        int beef = testFood.getOrDefault(p.getUniqueId(), 16);
        p.sendMessage("§a§l[PVP测试] 已进入测试场! 难度: " + diff.display + "  场景: " + equip.display
                + "  武器:" + WEAPON_TIER_NAMES[wt] + "  护甲:" + ARMOR_TIER_NAMES[at]
                + "  附魔:" + (enchant ? "§d开" : "§7关") + "  盾牌:" + (shield ? "§a有" : "§7无") + "  熟牛肉:" + beef);
        p.sendMessage("§7主武器: " + WEAPON_TIER_NAMES[wt] + "§b剑 + " + WEAPON_TIER_NAMES[wt] + "§b斧"
                + (at > 0 ? " + " + ARMOR_TIER_NAMES[at] + "§b护甲" : "")
                + (enchant ? "（附魔）" : "（未附魔）") + (shield ? " +盾牌" : ""));
        p.sendMessage("§7练习生物血量统一为40。敌对生物互不攻击(友伤免疫)。");
        p.sendMessage("§7清空当前波次后，每10秒刷新下一波；背包已附熟牛肉回血。测试场内不可互殴，只能打练习生物。输入 /pvp leave 离开。");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        plugin.getLogger().info("[PVP测试] 玩家 " + p.getName()
                + " 进入测试场 (难度=" + diff.name() + ", 装备=" + equip.name()
                + ", 主世界难度=" + main.getDifficulty().name() + ")");
    }

    private void giveEquip(Player p, TestEquip equip) {
        // ★ 2026-07-09 改版：主武器 = 剑 + 斧（都给，材质按所选档位）；附魔=锋利V+击退II；盾牌可选；护甲按所选档位(可为无)。
        boolean enchant = testEnchant.getOrDefault(p.getUniqueId(), false);
        boolean shield = testShield.getOrDefault(p.getUniqueId(), true);
        int wt = testWeaponTier.getOrDefault(p.getUniqueId(), 0);
        int at = testArmorTier.getOrDefault(p.getUniqueId(), 0);

        p.getInventory().addItem(makeTestWeapon(SWORD_TIERS[wt], "§b§l练习剑", enchant));
        p.getInventory().addItem(makeTestWeapon(AXE_TIERS[wt], "§b§l练习斧", enchant));

        if (at > 0 && ARMOR_SETS[at] != null) {
            Material[] set = ARMOR_SETS[at];
            // ★ ARMOR_SETS 顺序: [头盔, 胸甲, 护腿, 靴子]
            //   setArmorContents 顺序: [靴子, 护腿, 胸甲, 头盔] — 必须反转
            ItemStack[] armor = new ItemStack[4];
            for (int i = 0; i < 4; i++) {
                ItemStack piece = new ItemStack(set[i]);
                ItemMeta m = piece.getItemMeta();
                if (m != null) {
                    m.setDisplayName("§a§l练习护甲");
                    m.setLore(Arrays.asList("§7" + ARMOR_TIER_NAMES[at].replace("§", "") + "套装"));
                    piece.setItemMeta(m);
                }
                armor[3 - i] = piece; // 反转: armor[0]=靴子, armor[3]=头盔
            }
            p.getInventory().setArmorContents(armor);
        }

        if (shield) {
            ItemStack sh = new ItemStack(Material.SHIELD);
            ItemMeta m = sh.getItemMeta();
            if (m != null) {
                m.setDisplayName("§a§l练习盾牌");
                m.setLore(Arrays.asList("§7格挡近战攻击"));
                sh.setItemMeta(m);
            }
            p.getInventory().setItemInOffHand(sh);
        }

        // 回血类食物：熟牛肉(维持饱食度)，数量与主场一致可调(1~64)，无金苹果(与主场一致)
        int beef = testFood.getOrDefault(p.getUniqueId(), 16);
        p.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, beef));
    }

    private ItemStack makeTestWeapon(Material mat, String name, boolean enchant) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (enchant) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.SHARPNESS, 5, true);
                meta.addEnchant(org.bukkit.enchantments.Enchantment.KNOCKBACK, 2, true);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 单玩家的一波：
     *   单场景(近战/远程) → 3只/组；混合场景 → 6只/组(3僵尸+3小白)。
     * 仅在该玩家在场生物未超上限时补一波。
     */
    private void spawnWaveFor(Player p) {
        if (!inTest.contains(p.getUniqueId())) return;
        TestDifficulty diff = chosenDiff.get(p.getUniqueId());
        if (diff == null) return;
        TestEquip equip = chosenEquip.get(p.getUniqueId());
        if (equip == null) equip = TestEquip.MELEE;
        World w = p.getWorld();
        if (equip == TestEquip.MIXED) {
            spawnRing(p, w, diff, EntityType.ZOMBIE, 3);
            spawnRing(p, w, diff, EntityType.SKELETON, 3);
        } else if (equip == TestEquip.RANGED) {
            spawnRing(p, w, diff, EntityType.SKELETON, 3);
        } else {
            spawnRing(p, w, diff, EntityType.ZOMBIE, 3);
        }
    }

    /**
     * 玩家全清当前波次 → 发送提示 + 可点击超链接(返回床)，并安排 10秒后刷下一波。
     * 仅在玩家在场、尚未安排、且当前确实无存活练习生物时触发；避免重复排程。
     */
    private void checkCleared(Player p) {
        if (!inTest.contains(p.getUniqueId())) return;
        if (pendingRespawn.contains(p.getUniqueId())) return;
        if (aliveMobCount(p) > 0) return;

        pendingRespawn.add(p.getUniqueId());
        sendClearedMessage(p);

        // 间隔10秒后刷新下一波（若届时仍在场且无存活生物）
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingRespawn.remove(p.getUniqueId());
            if (inTest.contains(p.getUniqueId()) && aliveMobCount(p) == 0) {
                spawnWaveFor(p);
            }
        }, WAVE_INTERVAL_TICKS);
    }

    /** 全清提示 + 可点击超链接：点击直接返回出生点(床) */
    private void sendClearedMessage(Player p) {
        Component link = Component.text("[点击返回出生点(床)]")
                .color(NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand("/pvp back"))
                .hoverEvent(HoverEvent.showText(Component.text("§7点击直接传送回你的床/出生点")));
        p.sendMessage(Component.text("§a§l[PVP测试] §f已清空当前波次！§e10秒后刷新下一波。 ")
                .append(link));
    }

    /** 练习生物死亡 → 反查所属玩家，检测是否全清以触发下一波 */
    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        UUID owner = mobOwner.get(ent.getUniqueId());
        if (owner == null) return;
        Player p = Bukkit.getPlayer(owner);
        if (p != null && p.isOnline()) {
            checkCleared(p);
        }
    }

    /**
     * 返回出生点(床)：若在测试场则先清理并还原背包，再传送至床(无床则主世界出生点)。
     * 供可点击超链接 /pvp back 调用。
     */
    public void handleBack(Player p) {
        if (inTest.contains(p.getUniqueId())) {
            removePlayerMobs(p);
            restoreInventory(p);
            inTest.remove(p.getUniqueId());
            chosenDiff.remove(p.getUniqueId());
            chosenEquip.remove(p.getUniqueId());
            pendingRespawn.remove(p.getUniqueId());
            checkAllLeft();
        }
        Location bed = p.getBedSpawnLocation();
        World main = Bukkit.getWorlds().get(0);
        Location dest = (bed != null) ? bed : main.getSpawnLocation();
        p.teleport(dest);
        if (bed != null) {
            p.sendMessage("§a§l[PVP测试] 已传送回你的床(出生点)");
        } else {
            p.sendMessage("§e你尚未设置床，已传送回主世界出生点");
        }
    }

    /** 统计某玩家在场且存活的练习生物数量 */
    private int aliveMobCount(Player p) {
        int n = 0;
        List<UUID> list = playerMobs.get(p.getUniqueId());
        if (list != null) {
            for (UUID uid : list) {
                Entity e = Bukkit.getEntity(uid);
                if (e != null && !e.isDead()) n++;
            }
        }
        return n;
    }

    /** 全部退场后还原测试场难度(和平模式下临时提困难，此时回到主世界难度) */
    private void checkAllLeft() {
        if (inTest.isEmpty()) {
            World w = Bukkit.getWorld(PVP_TEST_WORLD_NAME);
            if (w != null) {
                // 复位为"非和平"难度（主世界和平→困难，否则沿用主世界）。保持测试场永远可刷练习生物。
                applyTestWorldDifficulty(w);
                plugin.getLogger().info("[PVP测试] 测试场已无人，难度复位(保证非和平): " + w.getDifficulty().name());
            }
        }
    }

    private void spawnRing(Player p, World w, TestDifficulty diff, EntityType type, int count) {
        Location base = p.getLocation();
        for (int i = 0; i < count; i++) {
            double ang = (Math.PI * 2.0 * i) / count;
            double rad = 5.0;
            double x = base.getX() + Math.cos(ang) * rad;
            double z = base.getZ() + Math.sin(ang) * rad;
            // 钳制在边界内 (±14)
            x = Math.max(-14, Math.min(14, x));
            z = Math.max(-14, Math.min(14, z));
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            Location loc = new Location(w, x, y, z);
            Entity ent = w.spawnEntity(loc, type);
            if (ent instanceof LivingEntity mob) {
                mob.setMaxHealth(40.0);
                mob.setHealth(40.0);
                mob.setRemoveWhenFarAway(false);
                if (mob instanceof Mob m) {
                    m.setPersistent(true);
                    m.setTarget(p);
                }
                // 亡灵生物(僵尸/小白)在白天会被太阳灼烧(SUNBURN)。
                // 原版机制：亡灵生物戴帽子时，太阳灼烧会先消耗帽子耐久；
                // 给一顶【无限耐久】的皮革帽(附魔/属性使其不加护甲值)，帽子永不破损 → 永久免灼烧。
                ItemStack hat = new ItemStack(Material.LEATHER_HELMET);
                ItemMeta hm = hat.getItemMeta();
                if (hm != null) {
                    hm.setUnbreakable(true);
                    // 抵消皮革帽自带的1点护甲，使其不增加玩家对练习生物的伤害减免(纯用于挡太阳)
                    hm.addAttributeModifier(Attribute.ARMOR,
                            new AttributeModifier(
                                    "pvp_test_no_armor", -1.0,
                                    AttributeModifier.Operation.ADD_NUMBER));
                    hat.setItemMeta(hm);
                }
                EntityEquipment eq = mob.getEquipment();
                if (eq != null) {
                    eq.setHelmet(hat);
                    eq.setHelmetDropChance(0f); // 不会被打掉/掉落
                }
                mobDifficulty.put(mob.getUniqueId(), diff);
                mobOwner.put(mob.getUniqueId(), p.getUniqueId());
                playerMobs.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).add(mob.getUniqueId());
            }
        }
    }

    public void leaveTest(Player p) {
        if (!inTest.contains(p.getUniqueId())) {
            p.sendMessage("§e你不在PVP测试场");
            return;
        }
        removePlayerMobs(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        pendingRespawn.remove(p.getUniqueId());
        restoreInventory(p);
        World main = Bukkit.getWorlds().get(0);
        p.teleport(main.getSpawnLocation());
        p.sendMessage("§a§l[PVP测试] 已离开测试场，背包已还原");
        checkAllLeft();
    }

    private void removePlayerMobs(Player p) {
        UUID pid = p.getUniqueId();
        List<UUID> list = playerMobs.remove(pid);
        if (list != null) {
            for (UUID uid : list) {
                Entity e = Bukkit.getEntity(uid);
                if (e != null) e.remove();
                mobDifficulty.remove(uid);
                mobOwner.remove(uid);
            }
        }
    }

    // ==================== 背包备份/还原 ====================

    private void backupInventory(Player p) {
        inventoryBackups.put(p.getUniqueId(), new InventoryBackup(
                p.getInventory().getContents().clone(),
                p.getInventory().getArmorContents().clone(),
                p.getInventory().getItemInOffHand() != null ? p.getInventory().getItemInOffHand().clone() : null,
                p.getLevel(),
                p.getExp()));
        plugin.getLogger().info("[PVP测试] 已备份玩家 " + p.getName() + " 的背包");
    }

    private void restoreInventory(Player p) {
        InventoryBackup b = inventoryBackups.remove(p.getUniqueId());
        if (b == null) {
            plugin.getLogger().warning("[PVP测试] 未找到玩家 " + p.getName() + " 的背包备份");
            return;
        }
        p.getInventory().setContents(b.contents);
        p.getInventory().setArmorContents(b.armor);
        p.getInventory().setItemInOffHand(b.offHand);
        p.setLevel(b.level);
        p.setExp(b.exp);
        plugin.getLogger().info("[PVP测试] 已还原玩家 " + p.getName() + " 的背包");
    }

    private void clearInventory(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
    }

    // ==================== 查询 ====================

    public boolean isInTest(Player p) {
        return inTest.contains(p.getUniqueId());
    }

    public boolean isInTestWorld(Player p) {
        return isTestWorld(p.getWorld());
    }

    private boolean isTestWorld(World w) {
        return w != null && w.getName().equals(PVP_TEST_WORLD_NAME);
    }

    private boolean isTestMob(Entity e) {
        return e instanceof LivingEntity && mobDifficulty.containsKey(e.getUniqueId());
    }

    private LivingEntity getAttackerMob(Entity damager) {
        if (damager instanceof LivingEntity le && isTestMob(le)) return le;
        if (damager instanceof Arrow arrow) {
            ProjectileSource src = arrow.getShooter();
            if (src instanceof LivingEntity le && isTestMob(le)) return le;
        }
        return null;
    }

    // ==================== 事件监听 ====================

    /** 固定伤害 + 友伤免疫 + 噩梦随机走位 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!isTestWorld(e.getEntity().getWorld())) return;

        Entity victim = e.getEntity();
        Entity damager = e.getDamager();

        // 0) 测试场内禁止玩家互殴：玩家→玩家(含玩家发射的抛射物) 伤害全部取消，只能攻击练习生物
        if (victim instanceof Player) {
            Player attacker = null;
            if (damager instanceof Player) attacker = (Player) damager;
            else if (damager instanceof Projectile) {
                ProjectileSource src = ((Projectile) damager).getShooter();
                if (src instanceof Player) attacker = (Player) src;
            }
            if (attacker != null) {
                e.setCancelled(true);
                warnNoPvp(attacker);
                return;
            }
        }

        // 1) 友伤免疫：练习生物之间互不承担伤害（含小白的箭）
        boolean victimIsMob = isTestMob(victim);
        boolean damagerIsMob = isTestMob(damager) || isTestArrowFromMob(damager);
        if (victimIsMob && damagerIsMob) {
            e.setCancelled(true);
            return;
        }

        // 2) 玩家被练习生物攻击 → 按难度固定伤害
        LivingEntity attacker = getAttackerMob(damager);
        if (attacker != null && victim instanceof Player) {
            TestDifficulty diff = mobDifficulty.get(attacker.getUniqueId());
            if (diff != null) {
                double dmg;
                if (attacker instanceof Zombie) dmg = diff.zombieDamage;
                else if (attacker instanceof Skeleton) dmg = diff.skeletonDamage;
                else dmg = e.getDamage();
                e.setDamage(dmg);
            }
        }

        // 3) 噩梦难度：被玩家击中时随机走位（闪避）
        if (victimIsMob && damager instanceof Player) {
            TestDifficulty diff = mobDifficulty.get(victim.getUniqueId());
            if (diff == TestDifficulty.NIGHTMARE && victim instanceof LivingEntity mob) {
                double ang = rand.nextDouble() * Math.PI * 2.0;
                double sp = 0.45;
                mob.setVelocity(new Vector(Math.cos(ang) * sp, 0.25, Math.sin(ang) * sp));
            }
        }
    }

    /** 测试场内互殴被拦截时的节流提示 */
    private void warnNoPvp(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastPvpWarn.get(p.getUniqueId());
        if (last == null || now - last > 2500) {
            p.sendMessage("§c§l[PVP测试] 测试场内不能互相攻击，请攻击练习生物。");
            lastPvpWarn.put(p.getUniqueId(), now);
        }
    }

    private boolean isTestArrowFromMob(Entity damager) {
        if (damager instanceof Arrow arrow) {
            ProjectileSource src = arrow.getShooter();
            return src instanceof LivingEntity le && isTestMob(le);
        }
        return false;
    }

    /** 练习生物只锁定玩家，互不锁定（跳过原版 mob→mob 仇恨规则） */
    @EventHandler
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!isTestWorld(e.getEntity().getWorld())) return;
        if (isTestMob(e.getEntity()) && e.getTarget() != null && isTestMob(e.getTarget())) {
            e.setCancelled(true);
        }
    }

    /** 死亡：清空测试装备掉落物 + 强制不保留背包。
     *  KEEP_INVENTORY=false(gamerule) 保证服务器会清空背包；
     *  e.setKeepInventory(false) 双重保险；e.getDrops().clear() 防掉落物落地。
     *  ★ 不在 DeathEvent 里直接 clearInventory（Paper快照机制可能导致无效），
     *    改由 onRespawn 统一从备份还原。 */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!inTest.contains(p.getUniqueId())) return;
        // 清空所有掉落物（测试装备不落地）
        e.getDrops().clear();
        // 不保留经验
        e.setKeepLevel(false);
        e.setDroppedExp(0);
        // 不保留背包（配合 gamerule=false 双重保险）
        e.setKeepInventory(false);
    }

    /** 死亡：保留背包（KEEP_INVENTORY），送回主世界并还原 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!inTest.contains(p.getUniqueId())) return;
        removePlayerMobs(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        pendingRespawn.remove(p.getUniqueId());
        World main = Bukkit.getWorlds().get(0);
        e.setRespawnLocation(main.getSpawnLocation());
        // 复活瞬间立即还原原背包，杜绝利用快速重进保留/携带测试场装备
        restoreInventory(p);
        checkAllLeft();
    }

    /** 退出：清理练习生物并还原背包 */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!inTest.contains(p.getUniqueId())) return;
        removePlayerMobs(p);
        restoreInventory(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        pendingRespawn.remove(p.getUniqueId());
        checkAllLeft();
    }

    /** 登录：若上次在测试场断线（或非正常离开），强制还原背包并送回主世界 */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // 情况1：测试场世界里有记录或身上有测试场装备残留
        boolean inTestWorld = isTestWorld(p.getWorld());
        boolean hasBackup = inventoryBackups.containsKey(p.getUniqueId());
        if (!inTestWorld && !hasBackup && !inTest.contains(p.getUniqueId())) return;

        if (hasBackup) {
            restoreInventory(p);
        }
        removePlayerMobs(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        pendingRespawn.remove(p.getUniqueId());

        if (inTestWorld) {
            World main = Bukkit.getWorlds().get(0);
            p.teleport(main.getSpawnLocation());
            p.sendMessage("§c§l[PVP测试] 检测到你在测试场非正常断线，已送你回主世界并恢复背包");
        }
        checkAllLeft();
    }

    /** 通过其他方式离开测试世界（如指令传送）→ 视为离开 */
    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!inTest.contains(p.getUniqueId())) return;
        if (isTestWorld(p.getWorld())) return; // 仍在测试场
        removePlayerMobs(p);
        restoreInventory(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        pendingRespawn.remove(p.getUniqueId());
        checkAllLeft();
    }
}
