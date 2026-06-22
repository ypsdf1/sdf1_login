package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 区域防护GUI管理器
 * 提供领地管理的图形界面
 */
public class AreaGUIManager implements Listener {

    private final Main plugin;
    private final AreaProtection areaProtect;

    // 菜单标题
    private static final String T_MAIN = "§6§l区域防护主菜单";
    private static final String T_LAND_LIST = "§6§l我的领地";
    private static final String T_LAND_MANAGE = "§6§l管理领地";
    private static final String T_MEMBER_LIST = "§6§l成员列表";
    private static final String T_VISITOR_PERM = "§6§l访客权限";
    private static final String T_ADD_MEMBER = "§6§l添加成员";
    private static final String T_LAND_SETTINGS = "§6§l领地设置";

    // 每页显示数量
    private static final int PAGE_SIZE = 45;

    // 玩家当前页码
    private final Map<UUID, Integer> landListPages = new HashMap<>();
    private final Map<UUID, Integer> memberListPages = new HashMap<>();
    private final Map<UUID, Integer> visitorPermPages = new HashMap<>();

    // 玩家当前管理的领地
    private final Map<UUID, String> managingLand = new HashMap<>();

    public AreaGUIManager(Main plugin, AreaProtection areaProtect) {
        this.plugin = plugin;
        this.areaProtect = areaProtect;
    }

    /**
     * 打开区域防护主菜单
     */
    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, T_MAIN);

        // 创建领地（位置12）
        inv.setItem(12, createItem(Material.GRASS_BLOCK, "§a§l创建领地",
                "§7在你周围创建一个新的领地",
                "",
                "§e点击开始创建"));

        // 领地列表（位置14）
        inv.setItem(14, createItem(Material.CHEST, "§a§l领地列表",
                "§7列出你的所有领地（含可管理领地）",
                "",
                "§e点击查看"));

        // 权限模板（位置22）
        inv.setItem(22, createItem(Material.PAPER, "§a§l权限模板",
                "§7通过预先设置的权限模板",
                "§7快速设置领地成员的权限",
                "",
                "§e点击管理"));

        // 文档链接（位置40）
        inv.setItem(40, createItem(Material.BOOK, "§a§l文档链接",
                "§7在浏览器中打开插件文档页面",
                "",
                "§e点击查看"));

        // 返回按钮（位置49）
        inv.setItem(49, createItem(Material.ARROW, "§c§l返回主菜单", ""));

        p.openInventory(inv);
    }

    /**
     * 打开领地列表菜单
     */
    public void openLandList(Player p, int page) {
        landListPages.put(p.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(null, 54, T_LAND_LIST);

        String playerName = p.getName();
        List<AreaProtection.AreaConfig> playerLands = areaProtect.getLandsByOwner(playerName);

        // 也显示玩家有权限管理的领地（管理员领地）
        List<AreaProtection.AreaConfig> allManageableLands = new ArrayList<>(playerLands);

        int totalPages = (int) Math.ceil((double) allManageableLands.size() / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allManageableLands.size());

        for (int i = start; i < end; i++) {
            AreaProtection.AreaConfig land = allManageableLands.get(i);
            int slot = i - start;

            ItemStack landItem = createItem(Material.GRASS_BLOCK, "§a§l" + land.name,
                    "§7所有者: §f" + land.owner,
                    "§7世界: §f" + land.world,
                    "§7坐标: §f(" + land.x1 + "," + land.z1 + ") → (" + land.x2 + "," + land.z2 + ")",
                    "",
                    "§e左键: 管理领地",
                    "§c右键: 删除领地",
                    "§bShift+左键: 传送");

            inv.setItem(slot, landItem);
        }

        // 分页控制
        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, "§e§l上一页", "§7点击返回上一页"));
        }
        inv.setItem(49, createItem(Material.PAPER, "§7§l" + (page + 1) + "/" + totalPages, ""));
        if (page < totalPages - 1) {
            inv.setItem(53, createItem(Material.ARROW, "§e§l下一页", "§7点击进入下一页"));
        }

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回", ""));

        p.openInventory(inv);
    }

    /**
     * 打开管理领地菜单
     */
    public void openLandManage(Player p, String landName) {
        managingLand.put(p.getUniqueId(), landName);
        Inventory inv = Bukkit.createInventory(null, 54, T_LAND_MANAGE + " - " + landName);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return;
        }

        // 成员列表（位置11）
        inv.setItem(11, createItem(Material.PLAYER_HEAD, "§a§l成员列表",
                "§7管理领地的成员",
                "",
                "§e点击查看"));

        // 访客权限（位置13）
        inv.setItem(13, createItem(Material.REDSTONE_TORCH, "§a§l访客权限",
                "§7设置访客可以做什么",
                "",
                "§e点击配置"));

        // 领地设置（位置15）
        inv.setItem(15, createItem(Material.REDSTONE, "§a§l领地设置",
                "§7配置领地的基本属性",
                "",
                "§e点击设置"));

        // 领地信息（位置22）
        inv.setItem(22, createItem(Material.BOOK, "§e§l领地信息",
                "§7所有者: §f" + land.owner,
                "§7坐标: §f(" + land.x1 + "," + land.z1 + ") → (" + land.x2 + "," + land.z2 + ")",
                "§7高度: §f" + land.yMin + " - " + land.yMax));

        // 传送（位置31）
        inv.setItem(31, createItem(Material.ENDER_PEARL, "§a§l传送到领地", "§7点击传送"));

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回领地列表", ""));

        p.openInventory(inv);
    }

    /**
     * 打开成员列表菜单
     */
    public void openMemberList(Player p, String landName, int page) {
        managingLand.put(p.getUniqueId(), landName);
        memberListPages.put(p.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(null, 54, T_MEMBER_LIST + " - " + landName);

        Set<String> members = areaProtect.getLandMembers(landName);
        List<String> memberList = new ArrayList<>(members);

        int totalPages = (int) Math.ceil((double) memberList.size() / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, memberList.size());

        for (int i = start; i < end; i++) {
            String member = memberList.get(i);
            int slot = i - start;

            ItemStack memberItem = createItem(Material.PLAYER_HEAD, "§a§l" + member,
                    "§7左键: 移除成员",
                    "§7右键: 传送到玩家");
            SkullMeta skull = (SkullMeta) memberItem.getItemMeta();
            if (skull != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(member));
                memberItem.setItemMeta(skull);
            }

            inv.setItem(slot, memberItem);
        }

        // 添加成员按钮（位置45）
        inv.setItem(45, createItem(Material.EMERALD, "§a§l添加成员", "§7点击添加新成员"));

        // 分页控制
        if (page > 0) {
            inv.setItem(46, createItem(Material.ARROW, "§e§l上一页", "§7点击返回上一页"));
        }
        inv.setItem(49, createItem(Material.PAPER, "§7§l" + (page + 1) + "/" + totalPages, ""));
        if (page < totalPages - 1) {
            inv.setItem(53, createItem(Material.ARROW, "§e§l下一页", "§7点击进入下一页"));
        }

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

        p.openInventory(inv);
    }

    /**
     * 打开访客权限菜单
     */
    public void openVisitorPerm(Player p, String landName, int page) {
        managingLand.put(p.getUniqueId(), landName);
        visitorPermPages.put(p.getUniqueId(), page);
        Inventory inv = Bukkit.createInventory(null, 54, T_VISITOR_PERM + " - " + landName);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return;
        }

        // 权限列表（分页显示）
        List<PermEntry> perms = getPermEntries(land);

        int totalPages = (int) Math.ceil((double) perms.size() / PAGE_SIZE);
        if (totalPages == 0) totalPages = 1;

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, perms.size());

        for (int i = start; i < end; i++) {
            PermEntry perm = perms.get(i);
            int slot = i - start;

            Material mat = perm.enabled ? Material.LIME_DYE : Material.GRAY_DYE;
            String status = perm.enabled ? "§a✔ 已启用" : "§c✘ 已禁用";

            inv.setItem(slot, createItem(mat, "§e§l" + perm.name,
                    status,
                    "",
                    "§e点击切换状态"));
        }

        // 分页控制
        if (page > 0) {
            inv.setItem(45, createItem(Material.ARROW, "§e§l上一页", "§7点击返回上一页"));
        }
        inv.setItem(49, createItem(Material.PAPER, "§7§l" + (page + 1) + "/" + totalPages, ""));
        if (page < totalPages - 1) {
            inv.setItem(53, createItem(Material.ARROW, "§e§l下一页", "§7点击进入下一页"));
        }

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

        p.openInventory(inv);
    }

    /**
     * 打开添加成员菜单
     */
    public void openAddMember(Player p, String landName) {
        managingLand.put(p.getUniqueId(), landName);
        Inventory inv = Bukkit.createInventory(null, 54, T_ADD_MEMBER + " - " + landName);

        // 显示在线玩家
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<String> existingMembers = areaProtect.getLandMembers(landName);

        int slot = 0;
        for (Player target : onlinePlayers) {
            if (slot >= 45) break;
            if (existingMembers.contains(target.getName())) continue;

            ItemStack playerItem = createItem(Material.PLAYER_HEAD, "§a§l" + target.getName(),
                    "§7点击添加该玩家");
            SkullMeta skull = (SkullMeta) playerItem.getItemMeta();
            if (skull != null) {
                skull.setOwningPlayer(target);
                playerItem.setItemMeta(skull);
            }

            inv.setItem(slot, playerItem);
            slot++;
        }

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回成员列表", ""));

        p.openInventory(inv);
    }

    /**
     * 获取权限条目列表
     */
    private List<PermEntry> getPermEntries(AreaProtection.AreaConfig land) {
        List<PermEntry> perms = new ArrayList<>();
        perms.add(new PermEntry("移动", !land.denyMove));
        perms.add(new PermEntry("放置方块", !land.denyBlockPlace));
        perms.add(new PermEntry("破坏方块", !land.denyBlockBreak));
        perms.add(new PermEntry("PVP", !land.denyPVP));
        perms.add(new PermEntry("骑乘坐具", !land.denyMount));
        perms.add(new PermEntry("投掷末影珍珠", !land.denyEnderPearl));
        perms.add(new PermEntry("Raid", !land.denyRaid));
        perms.add(new PermEntry("点燃", !land.denyFire));
        perms.add(new PermEntry("拾取物品", !land.denyPickup));
        perms.add(new PermEntry("丢弃物品", !land.denyDrop));
        perms.add(new PermEntry("弓箭", !land.denyBow));
        perms.add(new PermEntry("药水", !land.denyPotion));
        perms.add(new PermEntry("爆炸", !land.denyExplosion));
        perms.add(new PermEntry("摔落伤害", !land.denyFallDamage));
        perms.add(new PermEntry("饥饿", !land.denyHunger));
        perms.add(new PermEntry("所有伤害", !land.denyAllDamage));
        return perms;
    }

    /**
     * 权限条目内部类
     */
    private static class PermEntry {
        String name;
        boolean enabled;

        PermEntry(String name, boolean enabled) {
            this.name = name;
            this.enabled = enabled;
        }
    }

    /**
     * 切换权限状态
     */
    private void togglePerm(AreaProtection.AreaConfig land, String permName) {
        switch (permName) {
            case "移动": land.denyMove = !land.denyMove; break;
            case "放置方块": land.denyBlockPlace = !land.denyBlockPlace; break;
            case "破坏方块": land.denyBlockBreak = !land.denyBlockBreak; break;
            case "PVP": land.denyPVP = !land.denyPVP; break;
            case "骑乘坐具": land.denyMount = !land.denyMount; break;
            case "投掷末影珍珠": land.denyEnderPearl = !land.denyEnderPearl; break;
            case "Raid": land.denyRaid = !land.denyRaid; break;
            case "点燃": land.denyFire = !land.denyFire; break;
            case "拾取物品": land.denyPickup = !land.denyPickup; break;
            case "丢弃物品": land.denyDrop = !land.denyDrop; break;
            case "弓箭": land.denyBow = !land.denyBow; break;
            case "药水": land.denyPotion = !land.denyPotion; break;
            case "爆炸": land.denyExplosion = !land.denyExplosion; break;
            case "摔落伤害": land.denyFallDamage = !land.denyFallDamage; break;
            case "饥饿": land.denyHunger = !land.denyHunger; break;
            case "所有伤害": land.denyAllDamage = !land.denyAllDamage; break;
        }
        areaProtect.saveAreaToDb(land);
    }

    /**
     * 创建物品
     */
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 处理菜单点击事件
     */
    @EventHandler
    public void onInvClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        String title = event.getView().getTitle();
        int raw = event.getRawSlot();

        // 区域防护主菜单
        if (T_MAIN.equals(title)) {
            event.setCancelled(true);
            if (raw == 12) {
                // 创建领地 - 给予选择工具
                p.closeInventory();
                p.performCommand("protect 工具");
            } else if (raw == 14) {
                openLandList(p, 0);
            } else if (raw == 22) {
                p.closeInventory();
                p.sendMessage("§7权限模板功能开发中...");
            } else if (raw == 40) {
                p.closeInventory();
                p.sendMessage("§a文档链接: https://wiki.ypshidifu.cn");
            } else if (raw == 49) {
                plugin.getGui().openMain(p);
            }
            return;
        }

        // 领地列表
        if (T_LAND_LIST.equals(title)) {
            event.setCancelled(true);
            if (raw < 0 || raw >= 54) return;

            // 获取当前页的领地
            int page = landListPages.getOrDefault(p.getUniqueId(), 0);
            List<AreaProtection.AreaConfig> lands = areaProtect.getLandsByOwner(p.getName());
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, lands.size());

            if (raw >= 0 && raw < (end - start)) {
                // 点击领地
                AreaProtection.AreaConfig land = lands.get(start + raw);
                if (event.isLeftClick() && !event.isShiftClick()) {
                    openLandManage(p, land.name);
                } else if (event.isRightClick()) {
                    // 删除领地
                    areaProtect.deleteLand(land.name);
                    p.sendMessage("§a已删除领地: " + land.name);
                    openLandList(p, page);
                } else if (event.isShiftClick() && event.isLeftClick()) {
                    // 传送
                    p.closeInventory();
                    Location tpLoc = new Location(
                            Bukkit.getWorld(land.world),
                            (land.x1 + land.x2) / 2.0,
                            land.yMin,
                            (land.z1 + land.z2) / 2.0
                    );
                    p.teleport(tpLoc);
                    p.sendMessage("§a已传送到领地: " + land.name);
                }
            } else if (raw == 45 && page > 0) {
                openLandList(p, page - 1);
            } else if (raw == 53 && page < (int) Math.ceil((double) lands.size() / PAGE_SIZE) - 1) {
                openLandList(p, page + 1);
            } else if (raw == 48) {
                openMainMenu(p);
            }
            return;
        }

        // 管理领地
        if (title.startsWith(T_LAND_MANAGE)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            if (raw == 11) {
                openMemberList(p, landName, 0);
            } else if (raw == 13) {
                openVisitorPerm(p, landName, 0);
            } else if (raw == 15) {
                openLandSettings(p, landName);
            } else if (raw == 31) {
                // 传送
                p.closeInventory();
                AreaProtection.AreaConfig land = areaProtect.getLand(landName);
                if (land != null) {
                    Location tpLoc = new Location(
                            Bukkit.getWorld(land.world),
                            (land.x1 + land.x2) / 2.0,
                            land.yMin,
                            (land.z1 + land.z2) / 2.0
                    );
                    p.teleport(tpLoc);
                    p.sendMessage("§a已传送到领地: " + landName);
                }
            } else if (raw == 48) {
                openLandList(p, 0);
            }
            return;
        }

        // 成员列表
        if (title.startsWith(T_MEMBER_LIST)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            int page = memberListPages.getOrDefault(p.getUniqueId(), 0);
            Set<String> members = areaProtect.getLandMembers(landName);
            List<String> memberList = new ArrayList<>(members);
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, memberList.size());

            if (raw >= 0 && raw < (end - start)) {
                String member = memberList.get(start + raw);
                if (event.isLeftClick()) {
                    // 移除成员
                    areaProtect.removeLandMember(landName, member);
                    p.sendMessage("§a已移除成员: " + member);
                    openMemberList(p, landName, page);
                } else if (event.isRightClick()) {
                    // 传送到玩家
                    Player target = Bukkit.getPlayer(member);
                    if (target != null) {
                        p.closeInventory();
                        p.teleport(target.getLocation());
                        p.sendMessage("§a已传送到: " + member);
                    } else {
                        p.sendMessage("§c玩家不在线");
                    }
                }
            } else if (raw == 45) {
                openAddMember(p, landName);
            } else if (raw == 46 && page > 0) {
                openMemberList(p, landName, page - 1);
            } else if (raw == 53 && page < (int) Math.ceil((double) memberList.size() / PAGE_SIZE) - 1) {
                openMemberList(p, landName, page + 1);
            } else if (raw == 48) {
                openLandManage(p, landName);
            }
            return;
        }

        // 访客权限
        if (title.startsWith(T_VISITOR_PERM)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            AreaProtection.AreaConfig land = areaProtect.getLand(landName);
            if (land == null) return;

            int page = visitorPermPages.getOrDefault(p.getUniqueId(), 0);
            List<PermEntry> perms = getPermEntries(land);
            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, perms.size());

            if (raw >= 0 && raw < (end - start)) {
                PermEntry perm = perms.get(start + raw);
                togglePerm(land, perm.name);
                p.sendMessage("§a已切换权限: " + perm.name);
                openVisitorPerm(p, landName, page);
            } else if (raw == 45 && page > 0) {
                openVisitorPerm(p, landName, page - 1);
            } else if (raw == 53 && page < (int) Math.ceil((double) perms.size() / PAGE_SIZE) - 1) {
                openVisitorPerm(p, landName, page + 1);
            } else if (raw == 48) {
                openLandManage(p, landName);
            }
            return;
        }

        // 添加成员
        if (title.startsWith(T_ADD_MEMBER)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            if (raw >= 0 && raw < 45) {
                ItemStack item = event.getView().getTopInventory().getItem(raw);
                if (item != null && item.getType() == Material.PLAYER_HEAD) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.getDisplayName() != null) {
                        String playerName = meta.getDisplayName()
                                .replaceAll("§[0-9a-fk-orA-FK-OR]", "");
                        areaProtect.addLandMember(landName, playerName);
                        p.sendMessage("§a已添加成员: " + playerName);
                        openAddMember(p, landName);
                    }
                }
            } else if (raw == 48) {
                openMemberList(p, landName, 0);
            }
            return;
        }

        // 领地设置
        if (title.startsWith(T_LAND_SETTINGS)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            AreaProtection.AreaConfig land = areaProtect.getLand(landName);
            if (land == null) return;

            if (raw == 11) {
                // 切换公告
                land.enableAnnounce = !land.enableAnnounce;
                areaProtect.saveAreaToDb(land);
                p.sendMessage("§a领地公告: " + (land.enableAnnounce ? "§a已启用" : "§c已禁用"));
                openLandSettings(p, landName);
            } else if (raw == 13) {
                // 切换和平模式
                land.peaceMode = !land.peaceMode;
                areaProtect.saveAreaToDb(land);
                p.sendMessage("§a和平模式: " + (land.peaceMode ? "§a已启用" : "§c已禁用"));
                openLandSettings(p, landName);
            } else if (raw == 15) {
                // 切换强制游戏模式
                if (land.enforceGameMode == null || land.enforceGameMode.isEmpty()) {
                    land.enforceGameMode = "SURVIVAL";
                } else if ("SURVIVAL".equals(land.enforceGameMode)) {
                    land.enforceGameMode = "CREATIVE";
                } else if ("CREATIVE".equals(land.enforceGameMode)) {
                    land.enforceGameMode = "ADVENTURE";
                } else if ("ADVENTURE".equals(land.enforceGameMode)) {
                    land.enforceGameMode = "SPECTATOR";
                } else {
                    land.enforceGameMode = null;
                }
                areaProtect.saveAreaToDb(land);
                String modeStr = land.enforceGameMode != null ? land.enforceGameMode : "无";
                p.sendMessage("§a强制游戏模式: §f" + modeStr);
                openLandSettings(p, landName);
            } else if (raw == 48) {
                openLandManage(p, landName);
            }
            return;
        }
    }

    /**
     * 打开领地设置菜单
     */
    private void openLandSettings(Player p, String landName) {
        managingLand.put(p.getUniqueId(), landName);
        Inventory inv = Bukkit.createInventory(null, 54, T_LAND_SETTINGS + " - " + landName);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return;
        }

        // 公告开关（位置11）
        Material announceMat = land.enableAnnounce ? Material.LIME_DYE : Material.GRAY_DYE;
        inv.setItem(11, createItem(announceMat, "§e§l领地公告",
                "§7当前状态: " + (land.enableAnnounce ? "§a已启用" : "§c已禁用"),
                "",
                "§e点击切换"));

        // 和平模式（位置13）
        Material peaceMat = land.peaceMode ? Material.LIME_DYE : Material.GRAY_DYE;
        inv.setItem(13, createItem(peaceMat, "§e§l和平模式",
                "§7当前状态: " + (land.peaceMode ? "§a已启用" : "§c已禁用"),
                "§7新生物进入领地后有保护期",
                "",
                "§e点击切换"));

        // 强制游戏模式（位置15）
        String modeStr = land.enforceGameMode != null ? land.enforceGameMode : "无";
        inv.setItem(15, createItem(Material.COMPASS, "§e§l强制游戏模式",
                "§7当前模式: §f" + modeStr,
                "",
                "§e点击切换: 生存→创造→冒险→旁观→无"));

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

        p.openInventory(inv);
    }
}
