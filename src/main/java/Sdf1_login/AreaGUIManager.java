package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
    private static final String T_MEMBER_PERM_LIST = "§6§l成员权限列表";
    private static final String T_PLAYER_PERM = "§6§l成员权限";
    private static final String T_EFFECTS_MGMT = "§b§l效果管理";
    private static final String T_EFFECTS_CLEAR = "§b§l单清效果";
    private static final String T_EFFECTS_GIVE = "§b§l增益效果";

    // 每页显示数量
    private static final int PAGE_SIZE = 45;

    // 玩家当前页码
    private final Map<UUID, Integer> landListPages = new HashMap<>();
    private final Map<UUID, Integer> memberListPages = new HashMap<>();
    private final Map<UUID, Integer> visitorPermPages = new HashMap<>();

    // 玩家当前管理的领地
    private final Map<UUID, String> managingLand = new HashMap<>();

    // ★ 玩家当前编辑的per-player目标玩家
    private final Map<UUID, String> playerPermTarget = new HashMap<>();

    // ★ 效果管理待处理输入：UUID → [landName, inputType]
    private final Map<UUID, String[]> pendingEffectInput = new HashMap<>();

    public AreaGUIManager(Main plugin, AreaProtection areaProtect) {
        this.plugin = plugin;
        this.areaProtect = areaProtect;
    }

    /**
     * 打开区域防护主菜单
     */
    // ★ 管理员面板标题
    private static final String T_ADMIN_PANEL = "§6§l管理员配置面板";
    private static final String T_PUBLIC_BUILDING = "§b§l公共建筑传送";

    public void openMainMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, T_MAIN);

        // ★ 获取圈地工具（位置3）
        inv.setItem(3, createItem(Material.STICK, "§a§l获取圈地工具",
                "§7获取选择区域的木棍工具",
                "",
                "§e点击获取"));

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

        // ★ 公共设施传送（位置41）
        inv.setItem(41, createItem(Material.BELL, "§b§l公共设施传送",
                "§7查看公共建筑列表",
                "§7点击传送到公共设施",
                "",
                "§e点击查看"));

        // ★ 管理员面板：只有tag管理员可见（位置4）
        if (areaProtect.isAreaAdmin(p)) {
            inv.setItem(4, createItem(Material.NETHER_STAR, "§c§l管理员面板",
                    "§7调整区域防护全局配置",
                    "§7每平米价格、最大领地数等",
                    "",
                    "§e点击进入"));
        }

        // ★ 切换到CLI模式（位置42）
        inv.setItem(42, createItem(Material.COMMAND_BLOCK, "§b§l切换到CLI模式",
                "§7切换到命令行交互模式",
                "§7权限管理更便捷，支持批量操作",
                "",
                "§e点击切换"));

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

        // ★ Issue 6: 管理员可以看到所有领地
        if (areaProtect.isAreaAdmin(p)) {
            for (AreaProtection.AreaConfig ac : areaProtect.getAllLands().values()) {
                if (!allManageableLands.contains(ac)) {
                    allManageableLands.add(ac);
                }
            }
        }

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

        // 效果管理（位置16）
        inv.setItem(16, createItem(Material.BREWING_STAND, "§b§l效果管理",
                "§7配置清除/增益效果",
                "§7单清指定效果 · 全清负面",
                "§e点击管理"));

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
                    "§7左键: 编辑权限",
                    "§7右键: 移除成员");
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
     * 打开成员权限列表（per-player权限编辑入口）
     */
    public void openMemberPermList(Player p, String landName) {
        managingLand.put(p.getUniqueId(), landName);
        Inventory inv = Bukkit.createInventory(null, 54, T_MEMBER_PERM_LIST + " - " + landName);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return;
        }

        // 获取成员列表
        Set<String> members = areaProtect.getLandMembers(landName);
        List<String> memberList = new ArrayList<>(members);

        int slot = 0;
        for (String member : memberList) {
            if (slot >= 45) break;

            boolean isOwner = member.equalsIgnoreCase(land.owner);
            String roleTag = isOwner ? " §c[所有者]" : "";

            // 检查是否有自定义权限
            int landId = areaProtect.getLandIdFromDb(landName);
            String permJson = "";
            if (landId > 0) {
                permJson = areaProtect.getPlayerPermJson(landId, member);
            }
            boolean hasCustom = permJson != null && !permJson.isEmpty();
            String customTag = hasCustom ? " §e[自定义]" : "";

            ItemStack playerItem = createItem(Material.PLAYER_HEAD,
                    "§a§l" + member + roleTag + customTag,
                    "§7左键编辑该成员的独立权限",
                    "",
                    "§e点击编辑");
            SkullMeta skull = (SkullMeta) playerItem.getItemMeta();
            if (skull != null) {
                try {
                    Player target = Bukkit.getPlayer(member);
                    if (target != null) skull.setOwningPlayer(target);
                } catch (Exception ignored) {}
                playerItem.setItemMeta(skull);
            }

            inv.setItem(slot, playerItem);
            slot++;
        }

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

        p.openInventory(inv);
    }

    /**
     * 打开某个成员的独立权限编辑页面
     */
    public void openPlayerPerm(Player p, String landName, String targetPlayer) {
        managingLand.put(p.getUniqueId(), landName);
        // 用 playerPermTarget 保存目标玩家
        playerPermTarget.put(p.getUniqueId(), targetPlayer);
        Inventory inv = Bukkit.createInventory(null, 54, T_PLAYER_PERM + " - " + targetPlayer);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在");
            return;
        }

        int landId = areaProtect.getLandIdFromDb(landName);
        if (landId <= 0) {
            p.sendMessage("§c领地数据库ID获取失败");
            return;
        }

        // 获取per-player权限
        Map<String, Boolean> playerPerms = areaProtect.getPlayerPermMap(landId, targetPlayer);
        int slot = 0;

        // ★ 权限检查：只有所有者或管理员能编辑成员权限
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);

        // ★ 管理员状态检测（领地级管理员，非全局tag）
        boolean isAdmin = areaProtect.isLandAdmin(landName, targetPlayer);

        // ★ 管理员提示：非领地主操作管理员时简化显示
        if (isAdmin && !isOwner) {
            inv.setItem(22, createItem(Material.GOLD_BLOCK, "§6§l管理员权限",
                    "§7该玩家是此领地管理员，自动获得所有权限",
                    "§7点击切换管理员身份"));
            // 管理员只保留发光效果权限控制
            boolean glowingDefault = !land.denyGlowing;
            boolean glowingOverride = playerPerms.containsKey("denyGlowing") ? playerPerms.get("denyGlowing") : glowingDefault;
            boolean glowingAllowed = !glowingOverride;
            Material glowingMat = glowingAllowed ? Material.LIME_DYE : Material.GRAY_DYE;
            String glowingStatus = glowingAllowed ? "§a✔ 允许" : "§c✘ 禁止";
            String glowingOverrideTag = playerPerms.containsKey("denyGlowing") ? " §e★自定义" : " §7[默认]";
            inv.setItem(0, createItem(glowingMat,
                    "§e§l玩家发光" + glowingOverrideTag,
                    glowingStatus,
                    "",
                    "§e点击切换"));
            // 跳过权限列表循环
            slot = 45;
        }

        // 权限列表
        String[][] permDefs = {
                {"移动", "denyMove"}, {"放置方块", "denyBlockPlace"}, {"破坏方块", "denyBlockBreak"},
                {"容器管理", "denyContainer"}, {"玩家对战", "denyPVP"}, {"骑乘坐具", "denyMount"}, {"投掷末影珍珠", "denyEnderPearl"},
                {"投掷物", "denyThrownProjectiles"}, {"禁止袭击", "denyRaid"}, {"弓箭射击", "denyBow"},
                {"药水效果", "denyPotion"}, {"点燃", "denyFire"}, {"火焰蔓延", "denyFireSpread"},
                {"禁止拾取", "denyPickup"}, {"丢弃物品", "denyDrop"}, {"爆炸", "denyExplosion"},
                {"摔落伤害", "denyFallDamage"}, {"饥饿", "denyHunger"}, {"所有伤害", "denyAllDamage"},
                {"所有效果", "denyAllEffects"}, {"禁止展示框", "denyItemFrame"}, {"红石电路", "denyRedstoneInteraction"},
                {"禁止门禁", "denyDoorInteraction"}, {"音频", "denyNoteblockJukebox"}, {"拴绳使用", "denyLead"},
                {"农作物收获", "denyCropHarvest"}, {"剪切羊毛", "denyWoolShear"}, {"投喂动物", "denyAnimalFeeding"},
                {"攻击生物", "denyMobAttack"},
                {"玩家发光", "denyGlowing"}, {"和平模式", "peaceMode"}
        };

        for (String[] def : permDefs) {
            if (slot >= 45) break;
            String name = def[0];
            String field = def[1];

            boolean landDefault = getLandDefaultVal(land, field);
            boolean isOverridden = playerPerms.containsKey(field);
            boolean effective = isOverridden ? playerPerms.get(field) : landDefault;

            // ★ denyRaid/denyFire/denyCropHarvest 动态名称
            if (field.equals("denyRaid")) {
                name = landDefault ? "禁止袭击" : "启用袭击";
            }
            if (field.equals("denyFire")) {
                name = landDefault ? "禁止点燃" : "启用点燃";
            }
            if (field.equals("denyCropHarvest")) {
                name = landDefault ? "禁用农作物收获" : "启用农作物收获";
            }
            if (field.equals("denyAnimalFeeding")) {
                name = landDefault ? "禁止投喂" : "允许投喂动物";
            }
            if (field.equals("denyMobAttack")) {
                name = landDefault ? "禁止攻击生物" : "允许攻击生物";
            }

            // enabled = true 表示"允许操作"（即deny=false）
            boolean allowed = !effective;
            Material mat = allowed ? Material.LIME_DYE : Material.GRAY_DYE;
            String status = allowed ? "§a✔ 允许" : "§c✘ 禁止";
            String overrideTag = isOverridden ? " §e★自定义" : " §7[默认]";

            inv.setItem(slot, createItem(mat,
                    "§e§l" + name + overrideTag,
                    status,
                    "",
                    "§e点击切换"));
            slot++;
        }

        // 清除所有自定义权限按钮（位置48）
        inv.setItem(48, createItem(Material.BARRIER, "§c§l清除所有自定义",
                "§7恢复为领地默认权限"));

        // 返回按钮（位置49）
        inv.setItem(49, createItem(Material.ARROW, "§a§l返回成员列表", ""));

        // ★ 管理员按钮（位置47）：显示当前管理员状态
        Material adminMat = isAdmin ? Material.GOLD_BLOCK : Material.IRON_BLOCK;
        String adminStatus = isAdmin ? "§a✔ 管理员" : "§c✘ 普通成员";
        inv.setItem(47, createItem(adminMat, "§6§l管理员权限",
                "§7当前状态: " + adminStatus,
                "",
                "§7管理员自动拥有所有权限",
                "§7使用命令设置: §e/protect setadmin <玩家>",
                "§7使用命令移除: §e/protect unsetadmin <玩家>"));

        p.openInventory(inv);
    }

    /**
     * 获取领地默认deny值（按字段名）
     */
    private boolean getLandDefaultVal(AreaProtection.AreaConfig land, String field) {
        switch (field) {
            case "denyMove": return land.denyMove;
            case "denyBlockPlace": return land.denyBlockPlace;
            case "denyBlockBreak": return land.denyBlockBreak;
            case "denyContainer": return land.denyContainer;
            case "denyPVP": return land.denyPVP;
            case "denyMount": return land.denyMount;
            case "denyEnderPearl": return land.denyEnderPearl;
            case "denyThrownProjectiles": return land.denyThrownProjectiles;
            case "denyRaid": return land.denyRaid;
            case "denyBow": return land.denyBow;
            case "denyPotion": return land.denyPotion;
            case "denyFire": return land.denyFire;
            case "denyFireSpread": return land.denyFireSpread;
            case "denyPickup": return land.denyPickup;
            case "denyDrop": return land.denyDrop;
            case "denyExplosion": return land.denyExplosion;
            case "denyFallDamage": return land.denyFallDamage;
            case "denyHunger": return land.denyHunger;
            case "denyAllDamage": return land.denyAllDamage;
            case "denyAllEffects": return land.denyAllEffects;
            case "denyItemFrame": return land.denyItemFrame;
            case "denyRedstoneInteraction": return land.denyRedstoneInteraction;
            case "denyDoorInteraction": return land.denyDoorInteraction;
            case "denyNoteblockJukebox": return land.denyNoteblockJukebox;
            case "denyLead": return land.denyLead;
            case "denyCropHarvest": return land.denyCropHarvest;
            case "denyWoolShear": return land.denyWoolShear;
            case "denyAnimalFeeding": return land.denyAnimalFeeding;
            case "denyGlowing": return land.denyGlowing;
            case "peaceMode": return land.peaceMode;
            default: return false;
        }
    }

    /**
     * 打开添加成员菜单 - 显示在线玩家 + 自定义输入
     */
    public void openAddMember(Player p, String landName) {
        managingLand.put(p.getUniqueId(), landName);
        Inventory inv = Bukkit.createInventory(null, 54, T_ADD_MEMBER + " - " + landName);

        // 显示在线玩家（排除自身和领地主人）
        List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<String> existingMembers = areaProtect.getLandMembers(landName);
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        String ownerName = land != null ? land.owner : "";

        int slot = 0;
        for (Player target : onlinePlayers) {
            if (slot >= 45) break;
            if (target.getUniqueId().equals(p.getUniqueId())) continue; // ★ 排除自身
            if (target.getName().equalsIgnoreCase(ownerName)) continue; // ★ 排除领地主人
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

        // 自定义输入成员（位置45）
        inv.setItem(45, createItem(Material.PAPER, "§e§l自定义输入成员",
                "§7点击后在聊天栏输入玩家名",
                "§7将添加到该领地"));

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
        perms.add(new PermEntry("容器管理", !land.denyContainer));
        perms.add(new PermEntry("玩家对战", !land.denyPVP));
        perms.add(new PermEntry("骑乘坐具", !land.denyMount));
        perms.add(new PermEntry("投掷末影珍珠", !land.denyEnderPearl));
        perms.add(new PermEntry("投掷物(三叉戟/雪球/风蛋)", !land.denyThrownProjectiles));
        perms.add(new PermEntry(land.denyRaid ? "禁止袭击" : "启用袭击", !land.denyRaid));
        perms.add(new PermEntry("弓箭射击", !land.denyBow));
        perms.add(new PermEntry("药水效果", !land.denyPotion));
        perms.add(new PermEntry(land.denyFire ? "禁止点燃" : "启用点燃", !land.denyFire));
        perms.add(new PermEntry("火焰蔓延", !land.denyFireSpread));
        perms.add(new PermEntry("禁止拾取", !land.denyPickup));
        perms.add(new PermEntry("丢弃物品", !land.denyDrop));
        perms.add(new PermEntry("爆炸", !land.denyExplosion));
        perms.add(new PermEntry("摔落伤害", !land.denyFallDamage));
        perms.add(new PermEntry("饥饿", !land.denyHunger));
        perms.add(new PermEntry("所有伤害", !land.denyAllDamage));
        perms.add(new PermEntry("所有效果", !land.denyAllEffects));
        perms.add(new PermEntry("禁止展示框", !land.denyItemFrame));
        perms.add(new PermEntry("红石电路(中继器/比较器)", !land.denyRedstoneInteraction));
        perms.add(new PermEntry("禁止门禁(门/按钮/压力板)", !land.denyDoorInteraction));
        perms.add(new PermEntry("音频(音符盒/唱片机)", !land.denyNoteblockJukebox));
        perms.add(new PermEntry("拴绳使用", !land.denyLead));
        perms.add(new PermEntry(land.denyCropHarvest ? "禁用农作物收获" : "启用农作物收获", !land.denyCropHarvest));
        perms.add(new PermEntry("剪切羊毛/生物", !land.denyWoolShear));
        perms.add(new PermEntry(land.denyAnimalFeeding ? "禁止投喂" : "允许投喂动物", !land.denyAnimalFeeding));
        perms.add(new PermEntry(land.denyMobAttack ? "禁止攻击生物" : "允许攻击生物", !land.denyMobAttack));
        perms.add(new PermEntry("玩家发光", !land.denyGlowing));
        perms.add(new PermEntry("和平模式", land.peaceMode));
        perms.add(new PermEntry("公共建筑设施", land.isPublicBuilding));
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
            case "容器管理": land.denyContainer = !land.denyContainer; break;
            case "玩家对战": land.denyPVP = !land.denyPVP; break;
            case "骑乘坐具": land.denyMount = !land.denyMount; break;
            case "投掷末影珍珠": land.denyEnderPearl = !land.denyEnderPearl; break;
            case "投掷物(三叉戟/雪球/风蛋)": land.denyThrownProjectiles = !land.denyThrownProjectiles; break;
            case "禁止袭击": land.denyRaid = !land.denyRaid; break;
            case "启用袭击": land.denyRaid = !land.denyRaid; break;
            case "弓箭射击": land.denyBow = !land.denyBow; break;
            case "药水效果": land.denyPotion = !land.denyPotion; break;
            case "点燃": land.denyFire = !land.denyFire; break;
            case "禁止点燃": land.denyFire = !land.denyFire; break;
            case "启用点燃": land.denyFire = !land.denyFire; break;
            case "火焰蔓延": land.denyFireSpread = !land.denyFireSpread; break;
            case "禁止拾取": land.denyPickup = !land.denyPickup; break;
            case "丢弃物品": land.denyDrop = !land.denyDrop; break;
            case "爆炸": land.denyExplosion = !land.denyExplosion; break;
            case "摔落伤害": land.denyFallDamage = !land.denyFallDamage; break;
            case "饥饿": land.denyHunger = !land.denyHunger; break;
            case "所有伤害": land.denyAllDamage = !land.denyAllDamage; break;
            case "所有效果": land.denyAllEffects = !land.denyAllEffects; break;
            case "禁止展示框": land.denyItemFrame = !land.denyItemFrame; break;
            case "红石电路(中继器/比较器)": land.denyRedstoneInteraction = !land.denyRedstoneInteraction; break;
            case "禁止门禁(门/按钮/压力板)": land.denyDoorInteraction = !land.denyDoorInteraction; break;
            case "音频(音符盒/唱片机)": land.denyNoteblockJukebox = !land.denyNoteblockJukebox; break;
            case "拴绳使用": land.denyLead = !land.denyLead; break;
            case "农作物收获": land.denyCropHarvest = !land.denyCropHarvest; break;
            case "禁用农作物收获": land.denyCropHarvest = !land.denyCropHarvest; break;
            case "启用农作物收获": land.denyCropHarvest = !land.denyCropHarvest; break;
            case "剪切羊毛/生物": land.denyWoolShear = !land.denyWoolShear; break;
            case "禁止投喂": case "允许投喂动物": land.denyAnimalFeeding = !land.denyAnimalFeeding; break;
            case "禁止攻击生物": case "允许攻击生物": land.denyMobAttack = !land.denyMobAttack; break;
            case "玩家发光": land.denyGlowing = !land.denyGlowing; break;
            case "和平模式": land.peaceMode = !land.peaceMode; break;
            case "公共建筑设施": land.isPublicBuilding = !land.isPublicBuilding; break;
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
     * 安全传送位置搜索：优先中心点，然后随机40次寻找头顶≥2空气+脚下非实体方块+在领地范围内
     */
    private Location findSafeLocation(World w, int cx, int cz, int yMin, int yMax) {
        // 先检查中心点
        for (int y = Math.min(yMax, w.getMaxHeight()); y >= Math.max(yMin, w.getMinHeight()); y--) {
            org.bukkit.block.Block foot = w.getBlockAt(cx, y, cz);
            if (!foot.getType().isSolid()) continue;
            if (w.getBlockAt(cx, y + 1, cz).getType().isSolid()) continue;
            if (w.getBlockAt(cx, y + 2, cz).getType().isSolid()) continue;
            return new Location(w, cx + 0.5, y + 1, cz + 0.5);
        }
        // 随机40次
        java.util.Random rng = new java.util.Random();
        int x1 = Math.min(cx - 5, cx + 5), x2 = Math.max(cx - 5, cx + 5);
        int z1 = Math.min(cz - 5, cz + 5), z2 = Math.max(cz - 5, cz + 5);
        for (int i = 0; i < 40; i++) {
            int rx = x1 + rng.nextInt(Math.max(1, x2 - x1 + 1));
            int rz = z1 + rng.nextInt(Math.max(1, z2 - z1 + 1));
            for (int y = Math.min(yMax, w.getMaxHeight()); y >= Math.max(yMin, w.getMinHeight()); y--) {
                org.bukkit.block.Block foot = w.getBlockAt(rx, y, rz);
                if (!foot.getType().isSolid()) continue;
                if (w.getBlockAt(rx, y + 1, rz).getType().isSolid()) continue;
                if (w.getBlockAt(rx, y + 2, rz).getType().isSolid()) continue;
                return new Location(w, rx + 0.5, y + 1, rz + 0.5);
            }
        }
        return null; // 找不到安全位置
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
            if (raw == 3) {
                // ★ 获取圈地工具
                p.closeInventory();
                giveWandTool(p);
            } else if (raw == 12) {
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
            } else if (raw == 4 && areaProtect.isAreaAdmin(p)) {
                // ★ 管理员面板
                openAdminPanel(p);
            } else if (raw == 42) {
                // ★ 切换到CLI模式
                p.closeInventory();
                try {
                    plugin.getDb().setUiMode(p.getName(), 1);
                } catch (Exception ignored) {}
                if (plugin.areaCLIManager != null) {
                    plugin.areaCLIManager.showMainMenu(p);
                } else {
                    p.sendMessage("§cCLI管理器未初始化");
                }
            } else if (raw == 41) {
                // ★ 公共设施传送 - 打开GUI直接传送
                event.setCancelled(true);
                openPublicBuildingList(p);
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
                    // ★ 删除领地：走延迟确认流程
                    p.closeInventory();
                    p.performCommand("protect 删除 " + land.name);
                } else if (event.isShiftClick() && event.isLeftClick()) {
                    // 传送 - 优先传送点，否则安全搜索中心
                    p.closeInventory();
                    if (land.warpWorld != null && !land.warpWorld.isEmpty()) {
                        World ww = Bukkit.getWorld(land.warpWorld);
                        if (ww != null) {
                            p.teleport(new Location(ww, land.warpX, land.warpY, land.warpZ, land.warpYaw, land.warpPitch));
                        }
                    } else {
                        int cx = (Math.min(land.x1, land.x2) + Math.max(land.x1, land.x2)) / 2;
                        int cz = (Math.min(land.z1, land.z2) + Math.max(land.z1, land.z2)) / 2;
                        World ww = Bukkit.getWorld(land.world);
                        if (ww != null) {
                            Location safeLoc = findSafeLocation(ww, cx, cz, land.yMin, land.yMax);
                            if (safeLoc != null) p.teleport(safeLoc);
                        }
                    }
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
            } else if (raw == 16) {
                openEffectsManagement(p, landName, 1);
            } else if (raw == 31) {
                // 传送 - 优先传送点，否则安全搜索中心
                p.closeInventory();
                AreaProtection.AreaConfig land = areaProtect.getLand(landName);
                if (land != null) {
                    if (land.warpWorld != null && !land.warpWorld.isEmpty()) {
                        // 有传送点 → 直接传送
                        World ww = Bukkit.getWorld(land.warpWorld);
                        if (ww != null) {
                            p.teleport(new Location(ww, land.warpX, land.warpY, land.warpZ, land.warpYaw, land.warpPitch));
                        }
                    } else {
                        // 无传送点 → 中心点安全搜索
                        int cx = (Math.min(land.x1, land.x2) + Math.max(land.x1, land.x2)) / 2;
                        int cz = (Math.min(land.z1, land.z2) + Math.max(land.z1, land.z2)) / 2;
                        World ww = Bukkit.getWorld(land.world);
                        if (ww != null) {
                            Location safeLoc = findSafeLocation(ww, cx, cz, land.yMin, land.yMax);
                            if (safeLoc != null) p.teleport(safeLoc);
                        }
                    }
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
                    // 左键：编辑该成员的独立权限
                    openPlayerPerm(p, landName, member);
                } else if (event.isRightClick()) {
                    // 右键：移除成员
                    areaProtect.removeLandMember(landName, member);
                    // ★ GUI模式不发送聊天消息
                    openMemberList(p, landName, page);
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
                // ★ GUI模式不发送聊天消息（视觉反馈已在GUI中体现）
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

        // ★ 成员权限列表
        if (title.startsWith(T_MEMBER_PERM_LIST)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            if (raw >= 0 && raw < 45) {
                ItemStack item = event.getView().getTopInventory().getItem(raw);
                if (item != null && item.getType() == Material.PLAYER_HEAD) {
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null && meta.getDisplayName() != null) {
                        String playerName = meta.getDisplayName()
                                .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                                .replaceAll("\\[所有者\\]", "")
                                .replaceAll("\\[自定义\\]", "")
                                .trim();
                        openPlayerPerm(p, landName, playerName);
                    }
                }
            } else if (raw == 48) {
                openLandManage(p, landName);
            }
            return;
        }

        // ★ 成员权限编辑
        if (title.startsWith(T_PLAYER_PERM)) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            String targetPlayer = playerPermTarget.get(p.getUniqueId());
            if (landName == null || targetPlayer == null) return;

            AreaProtection.AreaConfig land = areaProtect.getLand(landName);
            if (land == null) return;

            int landId = areaProtect.getLandIdFromDb(landName);
            if (landId <= 0) return;

            // ★ 管理员权限提示项（slot 22）点击返回
            if (raw == 22) {
                ItemStack item = event.getView().getTopInventory().getItem(raw);
                if (item != null && item.getType() == Material.GOLD_BLOCK) {
                    openMemberList(p, landName, 0);
                    return;
                }
            }

            if (raw >= 0 && raw < 45) {
                // 切换权限
                String[][] permDefs = {
                        {"移动", "denyMove"}, {"放置方块", "denyBlockPlace"}, {"破坏方块", "denyBlockBreak"},
                        {"容器管理", "denyContainer"}, {"玩家对战", "denyPVP"}, {"骑乘坐具", "denyMount"}, {"投掷末影珍珠", "denyEnderPearl"},
                        {"投掷物", "denyThrownProjectiles"}, {"禁止袭击", "denyRaid"}, {"弓箭射击", "denyBow"},
                        {"药水效果", "denyPotion"}, {"点燃", "denyFire"}, {"火焰蔓延", "denyFireSpread"},
                        {"禁止拾取", "denyPickup"}, {"丢弃物品", "denyDrop"}, {"爆炸", "denyExplosion"},
                        {"摔落伤害", "denyFallDamage"}, {"饥饿", "denyHunger"}, {"所有伤害", "denyAllDamage"},
                        {"所有效果", "denyAllEffects"}, {"禁止展示框", "denyItemFrame"}, {"红石电路", "denyRedstoneInteraction"},
                        {"禁止门禁", "denyDoorInteraction"}, {"音频", "denyNoteblockJukebox"}, {"拴绳使用", "denyLead"},
                        {"农作物收获", "denyCropHarvest"}, {"剪切羊毛", "denyWoolShear"}, {"投喂动物", "denyAnimalFeeding"},
                        {"攻击生物", "denyMobAttack"},
                        {"玩家发光", "denyGlowing"}, {"和平模式", "peaceMode"}
                };

                if (raw < permDefs.length) {
                    String field = permDefs[raw][1];
                    // 获取当前effective值
                    Map<String, Boolean> currentPerms = areaProtect.getPlayerPermMap(landId, targetPlayer);
                    boolean currentVal = currentPerms.containsKey(field)
                            ? currentPerms.get(field)
                            : getLandDefaultVal(land, field);
                    boolean newVal = !currentVal;
                    areaProtect.setPlayerPerm(landId, targetPlayer, field, newVal);
                    // ★ GUI模式不发送聊天消息
                    openPlayerPerm(p, landName, targetPlayer);
                }
            } else if (raw == 48) {
                // 清除所有自定义权限
                areaProtect.setPlayerPermJson(landId, targetPlayer, "");
                // ★ GUI模式不发送聊天消息
                openMemberList(p, landName, 0);
            } else if (raw == 47) {
                // ★ 管理员按钮：切换领地管理员状态（非全局tag）
                boolean isTargetAdmin = areaProtect.isLandAdmin(landName, targetPlayer);
                areaProtect.setLandAdmin(landName, targetPlayer, !isTargetAdmin);
                if (!isTargetAdmin) {
                    p.sendMessage("§a已将 §e" + targetPlayer + " §a设置为此领地管理员");
                } else {
                    p.sendMessage("§a已将 §e" + targetPlayer + " §a移除此领地管理员");
                }
                openPlayerPerm(p, landName, targetPlayer);
            } else if (raw == 49) {
                // 返回成员列表
                openMemberList(p, landName, 0);
            }
            return;
        }

        // 效果管理
        if (title.startsWith("§b§l效果管理") || title.startsWith("§b§l单清效果") || title.startsWith("§b§l增益效果")) {
            event.setCancelled(true);
            String landName = managingLand.get(p.getUniqueId());
            if (landName == null) return;

            // 从标题提取subPage
            int subPage = 1;
            if (title.contains("单清效果")) subPage = 2;
            else if (title.contains("增益效果")) subPage = 3;

            handleEffectsManagementClick(p, raw, subPage, landName);
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
                        boolean added = areaProtect.addLandMember(landName, playerName);
                        if (!added) {
                            p.sendMessage("§c§l[区域防护] §f领地主不能作为成员添加");
                        }
                        // ★ GUI模式不发送聊天消息
                        openAddMember(p, landName);
                    }
                }
            } else if (raw == 45) {
                // 自定义输入成员：打开聊天栏等待玩家名
                p.closeInventory();
                p.sendMessage("§e§l[添加成员] §f请输入要添加的玩家名:");
                areaProtect.setPendingAddMemberInput(p.getUniqueId(), landName);
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
                // ★ GUI模式不发送聊天消息
                openLandSettings(p, landName);
            } else if (raw == 13) {
                // 切换和平模式
                land.peaceMode = !land.peaceMode;
                areaProtect.saveAreaToDb(land);
                // ★ GUI模式不发送聊天消息
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
            } else if (raw == 31) {
                // ★ 设置传送点
                p.closeInventory();
                p.performCommand("protect settp " + landName);
            } else if (raw == 48) {
                openLandManage(p, landName);
            }
            return;
        }

        // ★ 管理员面板
        if (T_ADMIN_PANEL.equals(title)) {
            event.setCancelled(true);
            handleAdminPanelClick(p, raw, event.isLeftClick(), event.isRightClick());
            return;
        }

        // ★ 公共建筑传送
        if (T_PUBLIC_BUILDING.equals(title)) {
            event.setCancelled(true);
            if (raw == 49) {
                openMainMenu(p);
                return;
            }
            if (raw < 0 || raw >= 45) return;
            // 获取公共建筑列表
            java.util.List<AreaProtection.AreaConfig> publicLands = new ArrayList<>();
            for (AreaProtection.AreaConfig ac : areaProtect.getAllLands().values()) {
                if (ac.isPublicBuilding) publicLands.add(ac);
            }
            if (raw < publicLands.size()) {
                AreaProtection.AreaConfig target = publicLands.get(raw);
                p.closeInventory();
                // 传送（优先传送点，否则领地中心）
                org.bukkit.Location dest;
                if (target.warpX != 0 || target.warpZ != 0 || target.warpY != 0) {
                    org.bukkit.World w = Bukkit.getWorld(target.warpWorld != null && !target.warpWorld.isEmpty() ? target.warpWorld : p.getWorld().getName());
                    if (w == null) w = p.getWorld();
                    dest = new org.bukkit.Location(w, target.warpX + 0.5, target.warpY, target.warpZ + 0.5, target.warpYaw, target.warpPitch);
                } else {
                    dest = new org.bukkit.Location(p.getWorld(),
                            (target.x1 + target.x2) / 2.0 + 0.5,
                            p.getWorld().getHighestBlockYAt((target.x1 + target.x2) / 2, (target.z1 + target.z2) / 2) + 1,
                            (target.z1 + target.z2) / 2.0 + 0.5);
                }
                p.teleport(dest);
                p.sendMessage("§a已传送至公共建筑: §f" + target.name);
            }
            return;
        }

        // 效果选择列表
        if (title.startsWith("§a§l选择效果 - ")) {
            event.setCancelled(true);
            EffectSelectionState state = pendingEffectSelection.get(p.getUniqueId());
            if (state == null) return;

            if (raw == 48) {
                // 返回按钮
                pendingEffectSelection.remove(p.getUniqueId());
                openEffectsManagement(p, state.landName, state.returnSubPage);
                return;
            }

            if (raw < 0 || raw >= 45) return;

            // 获取效果名
            String effName = getEffectNameBySlot(raw);
            if (effName == null) return;

            AreaProtection.AreaConfig land = areaProtect.getLand(state.landName);
            if (land == null) return;

            if (state.type.equals("clear")) {
                // 添加到清除效果列表
                if (!land.clearEffects.contains(effName)) {
                    land.clearEffects.add(effName);
                    areaProtect.saveAreaToDb(land);
                    p.sendMessage("§a§l[效果管理] §f已添加清除效果: §e" + effName);
                } else {
                    p.sendMessage("§c§l[效果管理] §f该效果已在清除列表中: §e" + effName);
                }
            } else {
                // 添加到增益效果列表
                boolean alreadyExists = false;
                for (String[] ge : land.giveEffects) {
                    if (ge[0].equals(effName)) { alreadyExists = true; break; }
                }
                if (!alreadyExists) {
                    land.giveEffects.add(new String[]{effName, "1", "300"});
                    areaProtect.saveAreaToDb(land);
                    p.sendMessage("§a§l[效果管理] §f已添加增益效果: §a" + effName + " Lv1 300秒");
                } else {
                    p.sendMessage("§c§l[效果管理] §f该增益效果已存在: §e" + effName);
                }
            }

            // 刷新选择列表
            openEffectsSelection(p, state.landName, state.type, state.returnSubPage);
            return;
        }
    }

    /**
     * 根据槽位获取效果名
     */
    private String getEffectNameBySlot(int slot) {
        String[][] allEffects = {
                // 负面效果 (0-15)
                {"缓慢", "slowness"}, {"挖掘疲劳", "mining_fatigue"}, {"瞬间伤害", "instant_damage"},
                {"反胃", "nausea"}, {"失明", "blindness"}, {"饥饿", "hunger"},
                {"虚弱", "weakness"}, {"中毒", "poison"}, {"凋零", "wither"},
                {"飘浮", "levitation"}, {"霉运", "unluck"}, {"黑暗", "darkness"},
                {"蓄风", "wind_charged"}, {"盘丝", "weaving"}, {"渗浆", "oozing"}, {"寄生", "infested"},
                // 中性效果 (16-18)
                {"不祥之兆", "bad_omen"}, {"袭击之兆", "raid_omen"}, {"试炼之兆", "trial_omen"},
                // 正面效果 (19-38)
                {"迅捷", "speed"}, {"急迫", "haste"}, {"力量", "strength"},
                {"瞬间治疗", "instant_health"}, {"跳跃提升", "jump_boost"}, {"生命恢复", "regeneration"},
                {"抗性提升", "resistance"}, {"抗火", "fire_resistance"}, {"水下呼吸", "water_breathing"},
                {"隐身", "invisibility"}, {"夜视", "night_vision"}, {"发光", "glowing"},
                {"生命提升", "health_boost"}, {"伤害吸收", "absorption"}, {"饱和", "saturation"},
                {"幸运", "luck"}, {"村庄英雄", "hero_of_the_village"}, {"缓降", "slow_falling"},
                {"潮涌能量", "conduit_power"}, {"海豚的恩惠", "dolphins_grace"}
        };

        if (slot >= 0 && slot < allEffects.length) {
            return allEffects[slot][0];
        }
        return null;
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
                "§7当前状态: " + (land.peaceMode ? "§a已启用" : "§c已关闭"),
                "§7新生物进入领地后有保护期",
                "",
                "§e点击切换"));

        // 强制游戏模式（位置15）
        String modeStr = land.enforceGameMode != null ? land.enforceGameMode : "无";
        inv.setItem(15, createItem(Material.COMPASS, "§e§l强制游戏模式",
                "§7当前模式: §f" + modeStr,
                "",
                "§e点击切换: 生存→创造→冒险→旁观→无"));

        // ★ 设置传送点（位置31）
        inv.setItem(31, createItem(Material.ENDER_PEARL, "§a§l设置传送点",
                "§7将你当前位置设为领地传送点",
                "",
                "§e点击设置"));

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

        p.openInventory(inv);
    }

    // ==================== 管理员面板 ====================

    /**
     * ★ 打开管理员配置面板
     */
    public void openAdminPanel(Player p) {
        Inventory inv = Bukkit.createInventory(null, 54, T_ADMIN_PANEL);

        // 读取全局配置
        int pricePerSqm = 10;
        int maxLands = 5;
        int defaultHeight = 255;
        int peaceDuration = 3600;
        try {
            String val = areaProtect.getAreaConfigValue("create_price_per_sqm");
            if (val != null) pricePerSqm = Integer.parseInt(val);
            val = areaProtect.getAreaConfigValue("max_lands_per_player");
            if (val != null) maxLands = Integer.parseInt(val);
            val = areaProtect.getAreaConfigValue("default_height");
            if (val != null) defaultHeight = Integer.parseInt(val);
            val = areaProtect.getAreaConfigValue("peace_mode_max_duration");
            if (val != null) peaceDuration = Integer.parseInt(val);
        } catch (Exception ignored) {}

        // 每平米价格（位置11）
        inv.setItem(11, createItem(Material.EMERALD, "§e§l每平米价格",
                "§7当前: §f" + pricePerSqm + " 元/㎡",
                "",
                "§e左键+10 / 右键-10"));

        // 最大领地数（位置13）
        inv.setItem(13, createItem(Material.NAME_TAG, "§e§l每玩家最大领地数",
                "§7当前: §f" + maxLands + " 个",
                "",
                "§e左键+1 / 右键-1"));

        // 默认高度（位置15）
        inv.setItem(15, createItem(Material.BARRIER, "§e§l默认领地高度",
                "§7当前: §f" + defaultHeight + " 格",
                "",
                "§e左键+32 / 右键-32"));

        // 和平模式最大时间（位置22）
        inv.setItem(22, createItem(Material.SHIELD, "§e§l和平模式最长时间",
                "§7当前: §f" + peaceDuration + " 秒",
                "",
                "§e左键+600 / 右键-600"));

        // ★ 用户组管理（位置31）
        UserGroupManager ugm = plugin.getUserGroup();
        int groupCount = (ugm != null) ? ugm.getGroupConfigs().size() : 0;
        inv.setItem(31, createItem(Material.PLAYER_HEAD, "§6§l用户组管理",
                "§7当前: §f" + groupCount + " 个用户组",
                "§7管理用户组的独立价格、上限和默认权限",
                "",
                "§e点击进入管理"));

        // 返回按钮（位置48）
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回主菜单", ""));

        p.openInventory(inv);
    }

    /**
     * ★ 获取圈地工具（木棍）
     */
    private void giveWandTool(Player p) {
        // ★ 5秒冷却
        long now = System.currentTimeMillis();
        Long lastGive = areaProtect.wandCooldownMap.get(p.getUniqueId());
        if (lastGive != null && now - lastGive < 5000) {
            long remaining = (5000 - (now - lastGive)) / 1000 + 1;
            p.sendMessage("§c请等待 " + remaining + " 秒后再获取");
            return;
        }
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§a§l区域选择工具");
            meta.setLore(Arrays.asList(
                    "§7左键点击选择第一个点",
                    "§7右键点击选择第二个点",
                    "§7手持工具输入 /protect 创建 <名称>",
                    "",
                    "§e草原探险 - 区域防护"
            ));
            wand.setItemMeta(meta);
        }
        // 检查背包是否已有
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.BLAZE_ROD
                    && item.hasItemMeta() && item.getItemMeta() != null
                    && item.getItemMeta().hasDisplayName()
                    && item.getItemMeta().getDisplayName().contains("区域选择工具")) {
                p.sendMessage("§e你已拥有圈地工具");
                return;
            }
        }
        p.getInventory().addItem(wand);
        // ★ GUI模式不发送聊天消息
        areaProtect.wandCooldownMap.put(p.getUniqueId(), now);
    }

    /**
     * ★ 处理管理员面板点击 - 改为聊天栏输入模式
     * 点击后打开聊天栏，玩家输入任意数字，比原来大=加钱，比原来小=减钱
     * 支持：纯数字、中文数字、中文大写、罗马数字、英文数字
     */
    private void handleAdminPanelClick(Player p, int raw, boolean leftClick, boolean rightClick) {
        if (raw == 11) {
            // 每平米价格 - 打开聊天栏输入
            p.closeInventory();
            p.sendMessage("§e§l[配置] §f请输入新的每平米创建价格（当前值会自动对比）:");
            p.sendMessage("§7支持: 纯数字123、中文一二三、大写壹佰贰拾叁、罗马I II III、英文one two three");
            // 设置等待状态，监听玩家聊天输入
            areaProtect.setPendingConfigInput(p.getUniqueId(), "create_price_per_sqm");
        } else if (raw == 13) {
            // 最大领地数
            p.closeInventory();
            p.sendMessage("§e§l[配置] §f请输入新的每人最大领地数:");
            areaProtect.setPendingConfigInput(p.getUniqueId(), "max_lands_per_player");
        } else if (raw == 15) {
            // 默认高度
            p.closeInventory();
            p.sendMessage("§e§l[配置] §f请输入新的默认高度:");
            areaProtect.setPendingConfigInput(p.getUniqueId(), "default_height");
        } else if (raw == 22) {
            // 和平模式时间
            p.closeInventory();
            p.sendMessage("§e§l[配置] §f请输入新的和平模式最大时长(秒):");
            areaProtect.setPendingConfigInput(p.getUniqueId(), "peace_mode_max_duration");
        } else if (raw == 48) {
            openMainMenu(p);
        } else if (raw == 31) {
            // ★ 用户组管理 - 打开CLI交互（GUI暂不支持复杂交互）
            p.closeInventory();
            p.performCommand("protect grouplist");
        }
    }

    // ==================== 公共建筑传送 GUI ====================

    /**
     * 打开公共建筑列表，点击直接传送
     */
    public void openPublicBuildingList(Player p) {
        // 收集所有公共建筑
        java.util.List<AreaProtection.AreaConfig> publicLands = new ArrayList<>();
        for (AreaProtection.AreaConfig ac : areaProtect.getAllLands().values()) {
            if (ac.isPublicBuilding) publicLands.add(ac);
        }

        Inventory inv = Bukkit.createInventory(null, 54, T_PUBLIC_BUILDING);

        if (publicLands.isEmpty()) {
            inv.setItem(22, createItem(Material.BARRIER, "§7暂无公共建筑设施",
                    "§7管理员可通过 /protect public <领地标记> 创建"));
        } else {
            int slot = 0;
            for (AreaProtection.AreaConfig ac : publicLands) {
                if (slot >= 45) break;
                String warpInfo = (ac.warpX != 0 || ac.warpZ != 0) ? "§a有传送点" : "§7无传送点（将传送到领地中心）";
                inv.setItem(slot, createItem(Material.BELL, "§b§l" + ac.name,
                        "§7所有者: §f" + ac.owner,
                        "§7传送: " + warpInfo,
                        "",
                        "§e点击传送到此公共建筑"));
                slot++;
            }
        }

        // 返回按钮
        inv.setItem(49, createItem(Material.ARROW, "§c§l返回主菜单", ""));

        p.openInventory(inv);
    }

    // ==================== 效果管理 GUI ====================

    /**
     * 打开效果管理菜单
     */
    public void openEffectsManagement(Player p, String landName, int subPage) {
        managingLand.put(p.getUniqueId(), landName);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage("§c领地不存在: " + landName);
            return;
        }

        // ========== 子菜单1：开关管理 ==========
        if (subPage == 1) {
            Inventory inv = Bukkit.createInventory(null, 54, "§b§l效果管理 - " + landName);

            // 全清负面效果
            Material clearAllMat = land.clearAllBadEffects ? Material.LIME_DYE : Material.GRAY_DYE;
            inv.setItem(11, createItem(clearAllMat, "§e§l清除所有负面效果",
                    "§7当前: " + (land.clearAllBadEffects ? "§a已开启" : "§c已关闭"),
                    "§7进入领地时清除所有负面/中性药水效果",
                    "",
                    "§e点击切换"));

            // 禁止所有效果
            Material denyAllMat = land.denyAllEffects ? Material.RED_DYE : Material.GRAY_DYE;
            inv.setItem(13, createItem(denyAllMat, "§e§l禁止所有效果",
                    "§7当前: " + (land.denyAllEffects ? "§a已开启" : "§c已关闭"),
                    "§7进入领地时禁止接收所有药水效果",
                    "",
                    "§e点击切换"));

            // 单清效果列表
            inv.setItem(15, createItem(Material.MAGMA_CREAM, "§b§l单清指定效果",
                    "§7当前列表: §f" + land.clearEffects.size() + " 个效果",
                    "",
                    "§e点击查看/编辑"));

            // 添加增益效果
            inv.setItem(17, createItem(Material.BREWING_STAND, "§a§l添加增益效果",
                    "§7当前增益: §f" + land.giveEffects.size() + " 个",
                    "",
                    "§e点击查看/编辑"));

            // 返回
            inv.setItem(48, createItem(Material.ARROW, "§c§l返回管理领地", ""));

            p.openInventory(inv);
        }

        // ========== 子菜单2：单清效果列表 ==========
        else if (subPage == 2) {
            Inventory inv = Bukkit.createInventory(null, 54, "§b§l单清效果 - " + landName);

            if (land.clearEffects.isEmpty()) {
                inv.setItem(22, createItem(Material.BARRIER, "§7§l暂无清除效果",
                        "§7使用快捷指令添加: /protect cli effectsclearadd <效果名>",
                        "",
                        "§e效果名可使用中文（如：缓慢、中毒）"));
            } else {
                for (int i = 0; i < Math.min(land.clearEffects.size(), 36); i++) {
                    String eff = land.clearEffects.get(i);
                    inv.setItem(i, createItem(Material.MAGMA_CREAM, "§f" + eff,
                            "§7点击移除此清除效果",
                            "§e序号: " + (i + 1)));
                }
            }

            inv.setItem(48, createItem(Material.ARROW, "§c§l返回效果管理", ""));
            inv.setItem(53, createItem(Material.BOOK, "§a§l添加效果",
                    "§7点击选择要清除的效果"));

            p.openInventory(inv);
        }

        // ========== 子菜单3：增益效果列表 ==========
        else if (subPage == 3) {
            Inventory inv = Bukkit.createInventory(null, 54, "§b§l增益效果 - " + landName);

            if (land.giveEffects.isEmpty()) {
                inv.setItem(22, createItem(Material.BARRIER, "§7§l暂无增益效果",
                        "§7使用快捷指令添加: /protect cli effectsaddadd <效果名> [等级] [秒数]",
                        "",
                        "§e示例: /protect cli effectsaddadd 力量 2 300"));
            } else {
                for (int i = 0; i < Math.min(land.giveEffects.size(), 36); i++) {
                    String[] eff = land.giveEffects.get(i);
                    String desc = eff[0] + (eff.length > 1 ? " Lv" + eff[1] : "") + (eff.length > 2 ? " " + eff[2] + "秒" : "");
                    inv.setItem(i, createItem(Material.GOLDEN_CARROT, "§a" + desc,
                            "§7点击移除此增益效果",
                            "§e序号: " + (i + 1)));
                }
            }

            inv.setItem(48, createItem(Material.ARROW, "§c§l返回效果管理", ""));
            inv.setItem(53, createItem(Material.BOOK, "§a§l添加效果",
                    "§7点击选择要清除的效果"));

            p.openInventory(inv);
        }

        // ========== 子菜单3：增益效果列表 ==========
        else if (subPage == 3) {
            Inventory inv = Bukkit.createInventory(null, 54, "§b§l增益效果 - " + landName);

            if (land.giveEffects.isEmpty()) {
                inv.setItem(22, createItem(Material.BARRIER, "§7§l暂无增益效果",
                        "§7使用快捷指令添加: /protect cli effectsaddadd <效果名> [等级] [秒数]",
                        "",
                        "§e示例: /protect cli effectsaddadd 力量 2 300"));
            } else {
                for (int i = 0; i < Math.min(land.giveEffects.size(), 36); i++) {
                    String[] eff = land.giveEffects.get(i);
                    String desc = eff[0] + (eff.length > 1 ? " Lv" + eff[1] : "") + (eff.length > 2 ? " " + eff[2] + "秒" : "");
                    inv.setItem(i, createItem(Material.GOLDEN_CARROT, "§a" + desc,
                            "§7点击移除此增益效果",
                            "§e序号: " + (i + 1)));
                }
            }

            inv.setItem(48, createItem(Material.ARROW, "§c§l返回效果管理", ""));
            inv.setItem(53, createItem(Material.BOOK, "§a§l添加增益",
                    "§7点击选择要添加的增益效果"));

            p.openInventory(inv);
        }
    }

    /**
     * 处理效果管理GUI点击事件
     */
    private void handleEffectsManagementClick(Player p, int raw, int subPage, String landName) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) return;

        // 子菜单1：开关管理
        if (subPage == 1) {
            if (raw == 11) {
                // 切换清除所有负面效果
                land.clearAllBadEffects = !land.clearAllBadEffects;
                areaProtect.saveAreaToDb(land);
                openEffectsManagement(p, landName, 1);
            } else if (raw == 13) {
                // 切换禁止所有效果
                land.denyAllEffects = !land.denyAllEffects;
                areaProtect.saveAreaToDb(land);
                openEffectsManagement(p, landName, 1);
            } else if (raw == 15) {
                // 打开单清效果列表
                openEffectsManagement(p, landName, 2);
            } else if (raw == 17) {
                // 打开增益效果列表
                openEffectsManagement(p, landName, 3);
            } else if (raw == 48) {
                // 返回管理领地
                openLandManage(p, landName);
            }
        }
        // 子菜单2：单清效果列表
        else if (subPage == 2) {
            if (raw < land.clearEffects.size()) {
                // 移除单清效果
                String removed = land.clearEffects.remove(raw);
                areaProtect.saveAreaToDb(land);
                p.sendMessage("§a§l[效果管理] §f已移除清除效果: §e" + removed);
                openEffectsManagement(p, landName, 2);
            } else if (raw == 48) {
                openEffectsManagement(p, landName, 1);
            } else if (raw == 53) {
                // 打开效果选择列表
                openEffectsSelection(p, landName, "clear", 2);
            }
        }
        // 子菜单3：增益效果列表
        else if (subPage == 3) {
            if (raw < land.giveEffects.size()) {
                // 移除增益效果
                String[] removed = land.giveEffects.remove(raw);
                String desc = removed[0] + (removed.length > 1 ? " Lv" + removed[1] : "") + (removed.length > 2 ? " " + removed[2] + "秒" : "");
                areaProtect.saveAreaToDb(land);
                p.sendMessage("§a§l[效果管理] §f已移除增益效果: §a" + desc);
                openEffectsManagement(p, landName, 3);
            } else if (raw == 48) {
                openEffectsManagement(p, landName, 1);
            } else if (raw == 53) {
                // 打开效果选择列表（与单清一致）
                openEffectsSelection(p, landName, "give", 3);
            }
        }
    }

    /**
     * 打开效果选择列表（可点击选择）
     * @param type "clear"=清除效果, "give"=增益效果
     * @param returnSubPage 返回时的子菜单页码
     */
    private void openEffectsSelection(Player p, String landName, String type, int returnSubPage) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) return;

        String title = "§a§l选择效果 - " + landName;
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // 负面效果
        String[][] badEffects = {
                {"缓慢", "slowness"}, {"挖掘疲劳", "mining_fatigue"}, {"瞬间伤害", "instant_damage"},
                {"反胃", "nausea"}, {"失明", "blindness"}, {"饥饿", "hunger"},
                {"虚弱", "weakness"}, {"中毒", "poison"}, {"凋零", "wither"},
                {"飘浮", "levitation"}, {"霉运", "unluck"}, {"黑暗", "darkness"},
                {"蓄风", "wind_charged"}, {"盘丝", "weaving"}, {"渗浆", "oozing"}, {"寄生", "infested"}
        };

        // 中性效果
        String[][] neutralEffects = {
                {"不祥之兆", "bad_omen"}, {"袭击之兆", "raid_omen"}, {"试炼之兆", "trial_omen"}
        };

        // 正面效果
        String[][] goodEffects = {
                {"迅捷", "speed"}, {"急迫", "haste"}, {"力量", "strength"},
                {"瞬间治疗", "instant_health"}, {"跳跃提升", "jump_boost"}, {"生命恢复", "regeneration"},
                {"抗性提升", "resistance"}, {"抗火", "fire_resistance"}, {"水下呼吸", "water_breathing"},
                {"隐身", "invisibility"}, {"夜视", "night_vision"}, {"发光", "glowing"},
                {"生命提升", "health_boost"}, {"伤害吸收", "absorption"}, {"饱和", "saturation"},
                {"幸运", "luck"}, {"村庄英雄", "hero_of_the_village"}, {"缓降", "slow_falling"},
                {"潮涌能量", "conduit_power"}, {"海豚的恩惠", "dolphins_grace"}
        };

        int slot = 0;
        // 负面效果
        for (String[] eff : badEffects) {
            if (slot >= 45) break;
            boolean alreadyInList = type.equals("clear") && land.clearEffects.contains(eff[0]);
            Material mat = alreadyInList ? Material.GRAY_DYE : Material.RED_DYE;
            String name = alreadyInList ? "§7" + eff[0] + " (已添加)" : "§c" + eff[0];
            inv.setItem(slot, createItem(mat, name, "§7点击添加到清除列表", "§e英文名: " + eff[1]));
            slot++;
        }

        // 中性效果
        for (String[] eff : neutralEffects) {
            if (slot >= 45) break;
            boolean alreadyInList = type.equals("clear") && land.clearEffects.contains(eff[0]);
            Material mat = alreadyInList ? Material.GRAY_DYE : Material.YELLOW_DYE;
            String name = alreadyInList ? "§7" + eff[0] + " (已添加)" : "§e" + eff[0];
            inv.setItem(slot, createItem(mat, name, "§7点击添加到清除列表", "§e英文名: " + eff[1]));
            slot++;
        }

        // 正面效果
        for (String[] eff : goodEffects) {
            if (slot >= 45) break;
            boolean alreadyInList = type.equals("clear") && land.clearEffects.contains(eff[0]);
            Material mat = alreadyInList ? Material.GRAY_DYE : Material.LIME_DYE;
            String name = alreadyInList ? "§7" + eff[0] + " (已添加)" : "§a" + eff[0];
            inv.setItem(slot, createItem(mat, name, "§7点击添加到清除列表", "§e英文名: " + eff[1]));
            slot++;
        }

        // 返回按钮
        inv.setItem(48, createItem(Material.ARROW, "§c§l返回", ""));

        p.openInventory(inv);

        // 存储选择状态
        pendingEffectSelection.put(p.getUniqueId(), new EffectSelectionState(landName, type, returnSubPage));
    }

    // 效果选择状态
    private final java.util.Map<java.util.UUID, EffectSelectionState> pendingEffectSelection = new java.util.HashMap<>();

    private static class EffectSelectionState {
        final String landName;
        final String type;
        final int returnSubPage;

        EffectSelectionState(String landName, String type, int returnSubPage) {
            this.landName = landName;
            this.type = type;
            this.returnSubPage = returnSubPage;
        }
    }
}