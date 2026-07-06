package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PVP竞技场管理器 - 独立世界模式
 * 
 * 流程：
 * 1. 玩家加入PVP世界
 * 2. 备份玩家背包数据
 * 3. 清空背包
 * 4. 提供装备自选
 * 5. 全局击杀计数
 * 6. 玩家退场
 * 7. 回收专属装备
 * 8. 还原玩家背包数据
 */
public class PVPArenaManager implements Listener {

    private final Main plugin;
    private final DatabaseManager db;
    
    // PVP世界名称
    private static final String PVP_WORLD_NAME = "pvp_arena";
    
    // 玩家在PVP世界中的状态
    private final Set<String> inPVPArena = ConcurrentHashMap.newKeySet();
    
    // 玩家背包备份缓存 (玩家名 -> 备份数据)
    private final Map<String, InventoryBackup> inventoryBackups = new ConcurrentHashMap<>();
    
    // PVP装备列表 (管理员可配置)
    private final List<ItemStack> pvpEquipment = new ArrayList<>();
    
    // 装备选择GUI标题
    private static final String EQUIPMENT_GUI_TITLE = "§6§l选择PVP装备";
    
    /**
     * 背包备份数据类
     */
    public static class InventoryBackup {
        public ItemStack[] contents;
        public ItemStack[] armor;
        public ItemStack offHand;
        public int expLevel;
        public float exp;
        
        public InventoryBackup(ItemStack[] contents, ItemStack[] armor, ItemStack offHand, int expLevel, float exp) {
            this.contents = contents != null ? contents.clone() : new ItemStack[36];
            this.armor = armor != null ? armor.clone() : new ItemStack[4];
            this.offHand = offHand;
            this.expLevel = expLevel;
            this.exp = exp;
        }
    }
    
    public PVPArenaManager(Main plugin) {
        this.plugin = plugin;
        this.db = plugin.getDb();
        
        // 初始化默认PVP装备
        initDefaultEquipment();
        
        // 加载配置
        loadConfig();
    }
    
    /**
     * 初始化默认PVP装备
     */
    private void initDefaultEquipment() {
        pvpEquipment.clear();
        
        // 铁甲套装
        pvpEquipment.add(new ItemStack(Material.IRON_HELMET));
        pvpEquipment.add(new ItemStack(Material.IRON_CHESTPLATE));
        pvpEquipment.add(new ItemStack(Material.IRON_LEGGINGS));
        pvpEquipment.add(new ItemStack(Material.IRON_BOOTS));
        
        // 钻石剑
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta swordMeta = sword.getItemMeta();
        if (swordMeta != null) {
            swordMeta.setDisplayName("§b§lPVP钻石剑");
            sword.setItemMeta(swordMeta);
        }
        pvpEquipment.add(sword);
        
        // 弓
        pvpEquipment.add(new ItemStack(Material.BOW));
        
        // 箭 x 64
        ItemStack arrows = new ItemStack(Material.ARROW, 64);
        pvpEquipment.add(arrows);
        
        // 金苹果 x 8
        ItemStack gapple = new ItemStack(Material.GOLDEN_APPLE, 8);
        pvpEquipment.add(gapple);
        
        // 药水
        ItemStack potion = new ItemStack(Material.POTION, 3);
        ItemMeta potionMeta = potion.getItemMeta();
        if (potionMeta != null) {
            potionMeta.setDisplayName("§a§l瞬间治疗药水");
            potion.setItemMeta(potionMeta);
        }
        pvpEquipment.add(potion);
    }
    
    /**
     * 加载配置
     */
    private void loadConfig() {
        // TODO: 从配置文件加载PVP装备列表
        // 目前使用默认装备
    }
    
    /**
     * 保存配置
     */
    public void saveConfig() {
        // TODO: 保存PVP装备列表到配置文件
    }
    
    /**
     * 检查并创建PVP世界
     */
    public void ensurePVPWorldExists() {
        World pvpWorld = Bukkit.getWorld(PVP_WORLD_NAME);
        if (pvpWorld == null) {
            // 创建PVP世界 (使用超平坦世界)
            WorldCreator creator = new WorldCreator(PVP_WORLD_NAME);
            creator.environment(World.Environment.NORMAL);
            creator.generator(new org.bukkit.generator.ChunkGenerator() {
                @Override
                public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
                    return createChunkData(world);
                }
            });
            
            pvpWorld = creator.createWorld();
            
            if (pvpWorld != null) {
                // 设置PVP世界规则
                pvpWorld.setPVP(true);
                pvpWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                pvpWorld.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                pvpWorld.setTime(6000); // 中午
                
                plugin.getLogger().info("[PVP] 已创建PVP竞技场世界: " + PVP_WORLD_NAME);
            } else {
                plugin.getLogger().severe("[PVP] 无法创建PVP竞技场世界!");
            }
        }
    }
    
    /**
     * 玩家加入PVP世界
     */
    public void onPlayerEnterPVPWorld(Player player) {
        String playerName = player.getName();
        
        // 检查是否已经在PVP世界
        if (inPVPArena.contains(playerName)) {
            return;
        }
        
        // 标记玩家进入PVP世界
        inPVPArena.add(playerName);
        
        // 备份玩家背包
        backupInventory(player);
        
        // 清空玩家背包
        clearInventory(player);
        
        // 打开装备选择GUI
        openEquipmentSelection(player);
        
        player.sendMessage("§a§l欢迎来到PVP竞技场!");
        player.sendMessage("§7请选择你的装备套装");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        
        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 进入PVP竞技场");
    }
    
    /**
     * 玩家退出PVP世界
     */
    public void onPlayerExitPVPWorld(Player player) {
        String playerName = player.getName();
        
        // 检查是否在PVP世界
        if (!inPVPArena.contains(playerName)) {
            return;
        }
        
        // 标记玩家离开PVP世界
        inPVPArena.remove(playerName);
        
        // 回收PVP装备
        回收PVPEquipment(player);
        
        // 恢复玩家背包
        restoreInventory(player);
        
        player.sendMessage("§e§l你已离开PVP竞技场");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        
        plugin.getLogger().info("[PVP] 玩家 " + playerName + " 离开PVP竞技场");
    }
    
    /**
     * 备份玩家背包
     */
    private void backupInventory(Player player) {
        String playerName = player.getName();
        
        InventoryBackup backup = new InventoryBackup(
            player.getInventory().getContents(),
            player.getInventory().getArmorContents(),
            player.getInventory().getItemInOffHand(),
            player.getLevel(),
            player.getExp()
        );
        
        inventoryBackups.put(playerName, backup);
        
        // 同时保存到数据库
        db.saveInventoryBackup(
            playerName,
            serializeItems(backup.contents),
            serializeItems(backup.armor),
            serializeItem(backup.offHand),
            backup.expLevel,
            backup.exp
        );
        
        plugin.getLogger().info("[PVP] 已备份玩家 " + playerName + " 的背包");
    }
    
    /**
     * 恢复玩家背包
     */
    private void restoreInventory(Player player) {
        String playerName = player.getName();
        
        // 先从缓存获取
        InventoryBackup backup = inventoryBackups.remove(playerName);
        
        // 缓存没有则从数据库获取
        if (backup == null) {
            String[] data = db.getInventoryBackup(playerName);
            if (data != null) {
                backup = new InventoryBackup(
                    deserializeItems(data[0]),
                    deserializeItems(data[1]),
                    deserializeItem(data[2]),
                    Integer.parseInt(data[3]),
                    Float.parseFloat(data[4])
                );
                
                // 删除数据库备份
                db.deleteInventoryBackup(playerName);
            }
        }
        
        if (backup != null) {
            // 恢复背包
            player.getInventory().setContents(backup.contents);
            player.getInventory().setArmorContents(backup.armor);
            player.getInventory().setItemInOffHand(backup.offHand);
            player.setLevel(backup.expLevel);
            player.setExp(backup.exp);
            
            player.sendMessage("§a§l背包已恢复");
            plugin.getLogger().info("[PVP] 已恢复玩家 " + playerName + " 的背包");
        } else {
            plugin.getLogger().warning("[PVP] 未找到玩家 " + playerName + " 的背包备份");
        }
    }
    
    /**
     * 清空玩家背包
     */
    private void clearInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setLevel(0);
        player.setExp(0.0f);
    }
    
    /**
     * 回收PVP装备
     */
    private void 回收PVPEquipment(Player player) {
        // 移除带有PVP标记的物品
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && isPVPEquipment(contents[i])) {
                contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
        
        // 移除护甲中的PVP装备
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] != null && isPVPEquipment(armor[i])) {
                armor[i] = null;
            }
        }
        player.getInventory().setArmorContents(armor);
        
        // 移除副手
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && isPVPEquipment(offHand)) {
            player.getInventory().setItemInOffHand(null);
        }
    }
    
    /**
     * 检查物品是否是PVP装备
     */
    private boolean isPVPEquipment(ItemStack item) {
        if (item == null) return false;
        
        // 检查是否是PVP装备材料
        Material type = item.getType();
        return type == Material.IRON_HELMET ||
               type == Material.IRON_CHESTPLATE ||
               type == Material.IRON_LEGGINGS ||
               type == Material.IRON_BOOTS ||
               type == Material.DIAMOND_SWORD ||
               type == Material.BOW ||
               type == Material.ARROW ||
               type == Material.GOLDEN_APPLE ||
               type == Material.POTION;
    }
    
    /**
     * 打开装备选择GUI
     */
    public void openEquipmentSelection(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, EQUIPMENT_GUI_TITLE);
        
        // 添加PVP装备到GUI
        for (int i = 0; i < pvpEquipment.size() && i < 45; i++) {
            gui.setItem(i, pvpEquipment.get(i).clone());
        }
        
        // 添加确认按钮
        ItemStack confirm = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        if (confirmMeta != null) {
            confirmMeta.setDisplayName("§a§l确认选择");
            confirm.setItemMeta(confirmMeta);
        }
        gui.setItem(49, confirm);
        
        // 添加全选按钮
        ItemStack selectAll = new ItemStack(Material.NETHER_STAR);
        ItemMeta selectAllMeta = selectAll.getItemMeta();
        if (selectAllMeta != null) {
            selectAllMeta.setDisplayName("§b§l一键装备全套");
            selectAll.setItemMeta(selectAllMeta);
        }
        gui.setItem(53, selectAll);
        
        player.openInventory(gui);
    }
    
    /**
     * 处理装备选择GUI点击
     */
    public boolean handleEquipmentClick(Player player, int slot) {
        if (!player.getOpenInventory().getTitle().equals(EQUIPMENT_GUI_TITLE)) {
            return false;
        }
        
        String playerName = player.getName();
        
        // 检查是否在PVP世界
        if (!inPVPArena.contains(playerName)) {
            return false;
        }
        
        // 确认选择按钮
        if (slot == 49) {
            player.closeInventory();
            player.sendMessage("§a§l装备选择完成，开始战斗!");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            return true;
        }
        
        // 一键装备全套按钮
        if (slot == 53) {
            equipFullSet(player);
            player.closeInventory();
            player.sendMessage("§a§l已装备全套PVP装备!");
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            return true;
        }
        
        // 装备物品
        if (slot >= 0 && slot < pvpEquipment.size()) {
            ItemStack item = pvpEquipment.get(slot).clone();
            
            // 根据物品类型自动装备
            Material type = item.getType();
            if (type == Material.IRON_HELMET) {
                player.getInventory().setHelmet(item);
            } else if (type == Material.IRON_CHESTPLATE) {
                player.getInventory().setChestplate(item);
            } else if (type == Material.IRON_LEGGINGS) {
                player.getInventory().setLeggings(item);
            } else if (type == Material.IRON_BOOTS) {
                player.getInventory().setBoots(item);
            } else {
                // 其他物品放入背包
                player.getInventory().addItem(item);
            }
            
            player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1.0f, 1.0f);
            return true;
        }
        
        return false;
    }
    
    /**
     * 装备全套
     */
    private void equipFullSet(Player player) {
        for (ItemStack item : pvpEquipment) {
            ItemStack clone = item.clone();
            Material type = clone.getType();
            
            if (type == Material.IRON_HELMET) {
                player.getInventory().setHelmet(clone);
            } else if (type == Material.IRON_CHESTPLATE) {
                player.getInventory().setChestplate(clone);
            } else if (type == Material.IRON_LEGGINGS) {
                player.getInventory().setLeggings(clone);
            } else if (type == Material.IRON_BOOTS) {
                player.getInventory().setBoots(clone);
            } else {
                player.getInventory().addItem(clone);
            }
        }
    }
    
    /**
     * 玩家加入事件
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // 检查玩家是否在PVP世界
        if (player.getWorld().getName().equals(PVP_WORLD_NAME)) {
            inPVPArena.add(player.getName());
            
            // 延迟恢复背包（等待世界完全加载）
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                restoreInventory(player);
            }, 20L);
        }
    }
    
    /**
     * 玩家退出事件
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String playerName = player.getName();
        
        // 如果玩家在PVP世界，备份并清空
        if (inPVPArena.contains(playerName)) {
            backupInventory(player);
            inventoryBackups.remove(playerName);
        }
    }
    
    /**
     * 序列化物品数组
     */
    private String serializeItems(ItemStack[] items) {
        // TODO: 实现物品序列化
        return "";
    }
    
    /**
     * 反序列化物品数组
     */
    private ItemStack[] deserializeItems(String data) {
        // TODO: 实现物品反序列化
        return new ItemStack[0];
    }
    
    /**
     * 序列化单个物品
     */
    private String serializeItem(ItemStack item) {
        // TODO: 实现物品序列化
        return "";
    }
    
    /**
     * 反序列化单个物品
     */
    private ItemStack deserializeItem(String data) {
        // TODO: 实现物品反序列化
        return null;
    }
    
    /**
     * 检查玩家是否在PVP世界
     */
    public boolean isInPVPArena(String playerName) {
        return inPVPArena.contains(playerName);
    }
    
    /**
     * 获取PVP世界
     */
    public World getPVPWorld() {
        return Bukkit.getWorld(PVP_WORLD_NAME);
    }
    
    /**
     * 获取PVP装备列表
     */
    public List<ItemStack> getPVPEquipment() {
        return Collections.unmodifiableList(pvpEquipment);
    }
    
    /**
     * 添加PVP装备
     */
    public void addPVPEquipment(ItemStack item) {
        pvpEquipment.add(item);
        saveConfig();
    }
    
    /**
     * 清除所有玩家的PVP状态
     */
    public void clearAllPVPArenaStates() {
        inPVPArena.clear();
        inventoryBackups.clear();
    }
}