package Sdf1_login;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

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
    /** 每个练习生物对应的难度（用于固定伤害 + 噩梦走位判定） */
    private final Map<UUID, TestDifficulty> mobDifficulty = new HashMap<>();
    /** 每个玩家生成的练习生物 UUID 列表（离开时按玩家清理） */
    private final Map<UUID, List<UUID>> playerMobs = new HashMap<>();
    /** 玩家原背包备份（仅内存，练习场为临时场景） */
    private final Map<UUID, InventoryBackup> inventoryBackups = new HashMap<>();

    private final Random rand = new Random();

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

        // 世界规则：常昼 / 无天气 / 关闭自然刷怪（只有我们生成的练习生物）/ 死亡不掉装备 / PVP 开启
        w.setPVP(true);
        w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        w.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        w.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        w.setGameRule(GameRule.KEEP_INVENTORY, true);
        w.setTime(6000);
        w.setKeepSpawnInMemory(false);

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

        // 顶部信息
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta im = info.getItemMeta();
        if (im != null) {
            im.setDisplayName("§6§lPVP测试场设置");
            im.setLore(Arrays.asList(
                    "§7选择难度与装备，点击确认开始练习",
                    "",
                    "§e当前难度: " + diff.display,
                    "§e当前装备: " + equip.display));
            info.setItemMeta(im);
        }
        gui.setItem(4, info);

        setDiffButton(gui, 19, TestDifficulty.EASY, diff);
        setDiffButton(gui, 21, TestDifficulty.MEDIUM, diff);
        setDiffButton(gui, 23, TestDifficulty.NIGHTMARE, diff);

        setEquipButton(gui, 28, TestEquip.MELEE, equip);
        setEquipButton(gui, 30, TestEquip.RANGED, equip);
        setEquipButton(gui, 32, TestEquip.MIXED, equip);

        // 确认开始按钮
        ItemStack start = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta sm = start.getItemMeta();
        if (sm != null) {
            sm.setDisplayName("§a§l确认开始");
            sm.setLore(Arrays.asList(
                    "§7难度: " + diff.display,
                    "§7装备: " + equip.display,
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

    private void setDiffButton(Inventory gui, int slot, TestDifficulty d, TestDifficulty current) {
        boolean selected = (d == current);
        ItemStack btn = new ItemStack(selected ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        ItemMeta m = btn.getItemMeta();
        if (m != null) {
            m.setDisplayName(d.display);
            List<String> lore = new ArrayList<>();
            lore.add("§7僵尸伤害: " + (int) d.zombieDamage);
            lore.add("§7小白伤害: " + (int) d.skeletonDamage);
            if (d == TestDifficulty.NIGHTMARE) lore.add("§c被攻击时随机走位");
            lore.add(selected ? "§a✓ 已选中" : "§e点击选择");
            m.setLore(lore);
            btn.setItemMeta(m);
        }
        gui.setItem(slot, btn);
    }

    private void setEquipButton(Inventory gui, int slot, TestEquip e, TestEquip current) {
        boolean selected = (e == current);
        Material icon = e == TestEquip.MELEE ? Material.IRON_SWORD
                : e == TestEquip.RANGED ? Material.BOW : Material.NETHER_STAR;
        ItemStack btn = new ItemStack(icon);
        ItemMeta m = btn.getItemMeta();
        if (m != null) {
            m.setDisplayName(e.display);
            List<String> lore = new ArrayList<>();
            lore.add(e == TestEquip.MELEE ? "§7练习近战(僵尸)" :
                    e == TestEquip.RANGED ? "§7练习远程(小白)" : "§7练习近战+远程");
            lore.add(selected ? "§a✓ 已选中" : "§e点击选择");
            m.setLore(lore);
            btn.setItemMeta(m);
        }
        gui.setItem(slot, btn);
    }

    public void handleClick(Player p, int slot) {
        if (!p.getOpenInventory().getTitle().equals(TEST_GUI_TITLE)) return;

        if (slot == 19) { chosenDiff.put(p.getUniqueId(), TestDifficulty.EASY); openGUI(p); return; }
        if (slot == 21) { chosenDiff.put(p.getUniqueId(), TestDifficulty.MEDIUM); openGUI(p); return; }
        if (slot == 23) { chosenDiff.put(p.getUniqueId(), TestDifficulty.NIGHTMARE); openGUI(p); return; }
        if (slot == 28) { chosenEquip.put(p.getUniqueId(), TestEquip.MELEE); openGUI(p); return; }
        if (slot == 30) { chosenEquip.put(p.getUniqueId(), TestEquip.RANGED); openGUI(p); return; }
        if (slot == 32) { chosenEquip.put(p.getUniqueId(), TestEquip.MIXED); openGUI(p); return; }
        if (slot == 49) {
            p.closeInventory();
            startTest(p,
                    chosenDiff.getOrDefault(p.getUniqueId(), TestDifficulty.EASY),
                    chosenEquip.getOrDefault(p.getUniqueId(), TestEquip.MELEE));
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

        // 清掉该玩家之前的练习生物（支持重新配置）
        removePlayerMobs(p);

        // 传送至测试场
        p.teleport(getSafeSpawn(w));

        // 备份并清空背包 → 发放练习装备
        backupInventory(p);
        clearInventory(p);
        giveEquip(p, equip);

        inTest.add(p.getUniqueId());

        // 生成练习生物
        spawnMobs(p, w, diff, equip);

        p.sendMessage("§a§l[PVP测试] 已进入测试场! 难度: " + diff.display + "  装备: " + equip.display);
        p.sendMessage("§7练习生物血量统一为40。敌对生物互不攻击(友伤免疫)。输入 /pvp leave 离开。");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        plugin.getLogger().info("[PVP测试] 玩家 " + p.getName()
                + " 进入测试场 (难度=" + diff.name() + ", 装备=" + equip.name() + ")");
    }

    private void giveEquip(Player p, TestEquip equip) {
        ItemStack[] ironArmor = new ItemStack[]{
                new ItemStack(Material.IRON_BOOTS),
                new ItemStack(Material.IRON_LEGGINGS),
                new ItemStack(Material.IRON_CHESTPLATE),
                new ItemStack(Material.IRON_HELMET)};
        ItemStack[] leatherArmor = new ItemStack[]{
                new ItemStack(Material.LEATHER_BOOTS),
                new ItemStack(Material.LEATHER_LEGGINGS),
                new ItemStack(Material.LEATHER_CHESTPLATE),
                new ItemStack(Material.LEATHER_HELMET)};

        switch (equip) {
            case MELEE:
                p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
                p.getInventory().setArmorContents(ironArmor);
                break;
            case RANGED:
                p.getInventory().addItem(new ItemStack(Material.BOW));
                p.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                p.getInventory().setArmorContents(leatherArmor);
                break;
            case MIXED:
                p.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
                p.getInventory().addItem(new ItemStack(Material.BOW));
                p.getInventory().addItem(new ItemStack(Material.ARROW, 64));
                p.getInventory().setArmorContents(ironArmor);
                break;
        }
    }

    private void spawnMobs(Player p, World w, TestDifficulty diff, TestEquip equip) {
        if (equip == TestEquip.MIXED) {
            // 2 僵尸 + 2 小白
            spawnRing(p, w, diff, EntityType.ZOMBIE, 2);
            spawnRing(p, w, diff, EntityType.SKELETON, 2);
        } else if (equip == TestEquip.RANGED) {
            spawnRing(p, w, diff, EntityType.SKELETON, 4);
        } else {
            spawnRing(p, w, diff, EntityType.ZOMBIE, 4);
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
                // 加一个长时效防火效果，直接免疫太阳灼烧，保证练习生物不会自燃。
                mob.addPotionEffect(new PotionEffect(
                        PotionEffectType.FIRE_RESISTANCE,
                        630720000, // ≈1年(游戏刻)，练习生物离开即清理，足够覆盖整次练习
                        0, false, false, false));
                mobDifficulty.put(mob.getUniqueId(), diff);
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
        restoreInventory(p);
        World main = Bukkit.getWorlds().get(0);
        p.teleport(main.getSpawnLocation());
        p.sendMessage("§a§l[PVP测试] 已离开测试场，背包已还原");
    }

    private void removePlayerMobs(Player p) {
        UUID pid = p.getUniqueId();
        List<UUID> list = playerMobs.remove(pid);
        if (list != null) {
            for (UUID uid : list) {
                Entity e = Bukkit.getEntity(uid);
                if (e != null) e.remove();
                mobDifficulty.remove(uid);
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

    /** 死亡：保留背包（KEEP_INVENTORY），送回主世界并还原 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!inTest.contains(p.getUniqueId())) return;
        removePlayerMobs(p);
        inTest.remove(p.getUniqueId());
        chosenDiff.remove(p.getUniqueId());
        chosenEquip.remove(p.getUniqueId());
        World main = Bukkit.getWorlds().get(0);
        e.setRespawnLocation(main.getSpawnLocation());
        // 回到主世界后再还原背包，避免死亡瞬间覆盖
        final Player fp = p;
        Bukkit.getScheduler().runTaskLater(plugin, () -> restoreInventory(fp), 1L);
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
    }
}
