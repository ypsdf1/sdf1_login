package Sdf1_login;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 领地系统CLI交互式菜单
 * 提供完整的文字交互界面，参照Dominion风格
 */
public class AreaCLIManager {

    private final Main plugin;
    private final AreaProtection areaProtect;
    private static final int PAGE_SIZE = 10;

    // ★ 记录每个玩家当前所在页面（切换权限后刷新用）
    private final Map<UUID, String[]> playerPageInfo = new HashMap<>();

    public AreaCLIManager(Main plugin, AreaProtection areaProtect) {
        this.plugin = plugin;
        this.areaProtect = areaProtect;
    }

    // ==================== 辅助方法 ====================

    /**
     * ★ 记录玩家当前页面位置
     * @param type 页面类型（visitorperm / members / manage / lands）
     * @param landName 领地名
     * @param page 页码
     */
    private void savePageInfo(Player p, String type, String landName, int page) {
        playerPageInfo.put(p.getUniqueId(), new String[]{type, landName, String.valueOf(page)});
    }

    /**
     * ★ 获取玩家上次所在页面，返回 [type, landName, page] 或 null
     */
    private String[] getPageInfo(Player p) {
        return playerPageInfo.get(p.getUniqueId());
    }

    /**
     * ★ 切换权限后刷新：回到当前所在访客授权页面
     */
    public void refreshCurrentPage(Player p) {
        String[] info = getPageInfo(p);
        if (info != null && "visitorperm".equals(info[0])) {
            showVisitorPerm(p, info[1], Integer.parseInt(info[2]));
        } else if (info != null && "members".equals(info[0])) {
            showMemberList(p, info[1], Integer.parseInt(info[2]));
        } else {
            // 默认回到管理页面
            if (info != null) {
                showLandManage(p, info[1], 1);
            }
        }
    }

    private Component coloredText(String text, NamedTextColor color) {
        return Component.text(text).color(color);
    }

    private Component header(String title) {
        return Component.text("\n§6§l═══════ " + title + " ═══════\n");
    }

    private Component clickable(String label, String description, String command) {
        return Component.empty()
                .append(Component.text("§a[§f" + label + "§a] §7" + description)
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§e点击执行")))
                        .clickEvent(ClickEvent.runCommand(command)));
    }

    private Component clickableAction(String emoji, String label, String command) {
        return Component.empty()
                .append(Component.text("§a" + emoji + " [§f" + label + "§a]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§e点击执行")))
                        .clickEvent(ClickEvent.runCommand(command)));
    }

    // ==================== 主菜单 ====================

    public void showMainMenu(Player p) {
        p.sendMessage(header("领地系统"));

        p.sendMessage(clickableAction("⛏", "获取圈地工具",
                "/protect 工具"));
        p.sendMessage(clickableAction("⚔", "创建领地",
                "/protect cli create"));
        p.sendMessage(clickableAction("📋", "领地列表",
                "/protect cli lands 1"));
        p.sendMessage(clickableAction("🏪", "权限商店",
                "/protect shop list"));
        p.sendMessage(clickableAction("ℹ", "当前领地信息",
                "/protect info"));

        // 设置
        int uiMode = 1;
        try { uiMode = plugin.getDb().getUiMode(p.getName()); } catch (Exception ignored) {}
        p.sendMessage(clickableAction("⚙", "切换到" + (uiMode == 0 ? "GUI" : "CLI") + "模式",
                "/protect uimode " + (uiMode == 0 ? "cli" : "gui")));

        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    // ==================== 领地列表 ====================

    public void showLandList(Player p, int page) {
        savePageInfo(p, "lands", "", page);

        String playerName = p.getName();
        List<AreaProtection.AreaConfig> lands = areaProtect.getLandsByOwner(playerName);

        // 也显示管理员领地
        List<AreaProtection.AreaConfig> allLands = new ArrayList<>(lands);
        for (AreaProtection.AreaConfig ac : areaProtect.getAllLands().values()) {
            if (areaProtect.isAreaAdmin(p) && !allLands.contains(ac)) {
                allLands.add(ac);
            }
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) allLands.size() / PAGE_SIZE));
        page = Math.max(1, Math.min(page, totalPages));

        p.sendMessage(header("你的领地列表 [" + page + "/" + totalPages + "]"));

        if (allLands.isEmpty()) {
            p.sendMessage(Component.text("§7暂无领地，点击创建:"));
            p.sendMessage(clickableAction("⚔", "创建领地",
                    "/protect cli create"));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
            return;
        }

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allLands.size());

        for (int i = start; i < end; i++) {
            AreaProtection.AreaConfig land = allLands.get(i);
            boolean isOwner = playerName.equalsIgnoreCase(land.owner);

            Component line = Component.empty();
            line = line.append(Component.text("§a" + (i + 1) + ". §e" + land.name + " "));
            line = line.append(Component.text("§7(" + land.owner + ") "));

            if (isOwner || areaProtect.isAreaAdmin(p)) {
                line = line.append(Component.text("§b[管理]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§e点击管理此领地")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli manage " + land.name + " 1")));
                line = line.append(Component.text(" "));
                line = line.append(Component.text("§b[传送]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§e点击传送到领地")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect tp " + land.name)));
                line = line.append(Component.text(" "));
                if (areaProtect.hasPendingDelete(land.name)) {
                    line = line.append(Component.text("§e[取消删除]")
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("§e点击取消删除此领地")))
                            .clickEvent(ClickEvent.runCommand(
                                    "/protect 取消删除 " + land.name)));
                } else {
                    line = line.append(Component.text("§c[删除]")
                            .hoverEvent(HoverEvent.showText(
                                    Component.text("§c点击删除此领地")))
                            .clickEvent(ClickEvent.runCommand(
                                    "/protect 删除 " + land.name)));
                }
            }

            p.sendMessage(line);
        }

        // 分页
        Component pagination = Component.empty();
        if (page > 1) {
            pagination = pagination.append(Component.text("§a[◀ 上一页]")
                    .hoverEvent(HoverEvent.showText(Component.text("§e上一页")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli lands " + (page - 1))));
            pagination = pagination.append(Component.text(" "));
        }
        pagination = pagination.append(Component.text("§7第" + page + "/" + totalPages + "页"));
        if (page < totalPages) {
            pagination = pagination.append(Component.text(" "));
            pagination = pagination.append(Component.text("§a[下一页 ▶]")
                    .hoverEvent(HoverEvent.showText(Component.text("§e下一页")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli lands " + (page + 1))));
        }
        p.sendMessage(pagination);

        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回主菜单]")
                        .hoverEvent(HoverEvent.showText(Component.text("§e返回")))
                        .clickEvent(ClickEvent.runCommand("/protect cli menu"))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    // ==================== 领地管理 ====================

    public void showLandManage(Player p, String landName, int permPage) {
        savePageInfo(p, "manage", landName, 1);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        p.sendMessage(header("管理领地: " + landName));
        p.sendMessage(Component.text("§7所有者: §f" + land.owner));
        p.sendMessage(Component.text("§7世界: §f" + land.world));
        p.sendMessage(Component.text("§7坐标: §f(" + land.x1 + "," + land.z1 + ") → (" + land.x2 + "," + land.z2 + ")"));
        p.sendMessage(Component.text("§7高度: §f" + land.yMin + " - " + land.yMax));
        p.sendMessage(Component.text("§7规则数: §f" + land.ruleCount()));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));

        // ★ 细分管理选项
        p.sendMessage(clickableAction("👤", "用户管理",
                "/protect cli members " + land.name + " 1"));
        p.sendMessage(clickableAction("🔑", "访客授权",
                "/protect cli visitorperm " + land.name + " 1"));
        // ★ 新增：设置传送点
        p.sendMessage(clickableAction("📍", "设置传送点（当前位置）",
                "/protect settp " + land.name));

        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回列表]")
                        .hoverEvent(HoverEvent.showText(Component.text("§e返回领地列表")))
                        .clickEvent(ClickEvent.runCommand("/protect cli lands 1"))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    // ==================== 用户管理 ====================

    public void showMemberList(Player p, String landName, int page) {
        savePageInfo(p, "members", landName, page);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        Set<String> members = areaProtect.getLandMembers(landName);
        List<String> memberList = new ArrayList<>(members);
        int totalPages = Math.max(1, (int) Math.ceil((double) memberList.size() / PAGE_SIZE));
        page = Math.max(1, Math.min(page, totalPages));

        p.sendMessage(header("用户管理: " + landName));

        if (memberList.isEmpty()) {
            p.sendMessage(Component.text("§7暂无成员"));
        } else {
            int start = (page - 1) * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, memberList.size());
            for (int i = start; i < end; i++) {
                String member = memberList.get(i);
                Component line = Component.empty();
                line = line.append(Component.text("§f" + member));
                line = line.append(Component.text(" "));
                line = line.append(Component.text("§c[移除]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§c点击移除此成员")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect removemember " + land.name + " " + member)));
                p.sendMessage(line);
            }
        }

        // 分页
        if (totalPages > 1) {
            Component pagination = Component.empty();
            if (page > 1) {
                pagination = pagination.append(Component.text("§a[◀ 上一页]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli members " + land.name + " " + (page - 1))));
                pagination = pagination.append(Component.text(" "));
            }
            pagination = pagination.append(Component.text("§7第" + page + "/" + totalPages + "页"));
            if (page < totalPages) {
                pagination = pagination.append(Component.text(" "));
                pagination = pagination.append(Component.text("§a[下一页 ▶]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli members " + land.name + " " + (page + 1))));
            }
            p.sendMessage(pagination);
        }

        // ★ 修复占位符：使用 suggest_command 让玩家可以编辑命令输入玩家名
        p.sendMessage(Component.empty()
                .append(Component.text("§a[添加成员] ")
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后在聊天框输入玩家名")))
                        .clickEvent(ClickEvent.suggestCommand(
                                "/protect addvisitor " + land.name + " ")))
                .append(Component.text("§7点击后输入玩家名")));
        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回管理]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli manage " + land.name + " 1"))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    // ==================== 访客授权 ====================

    public void showVisitorPerm(Player p, String landName, int page) {
        savePageInfo(p, "visitorperm", landName, page);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        p.sendMessage(header("访客授权: " + landName));
        showPermPage(p, land, page);

        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回管理]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli manage " + land.name + " 1"))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    private void showPermPage(Player p, AreaProtection.AreaConfig land, int page) {
        List<PermItem> perms = getPermList(land);
        int totalPages = Math.max(1, (int) Math.ceil((double) perms.size() / PAGE_SIZE));
        page = Math.max(1, Math.min(page, totalPages));

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, perms.size());

        for (int i = start; i < end; i++) {
            PermItem perm = perms.get(i);
            String icon = perm.enabled ? "§a✔" : "§c✘";
            String state = perm.enabled ? "§a已启用" : "§c已禁用";

            Component line = Component.empty();
            line = line.append(Component.text(icon + " §f" + perm.name + " §7" + state));
            line = line.append(Component.text(" "));
            line = line.append(Component.text("§e[切换]")
                    .hoverEvent(HoverEvent.showText(
                            Component.text("§e点击切换此权限状态")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli toggle " + land.name + " " + perm.key + " " + page)));
            p.sendMessage(line);
        }

        // 分页
        if (totalPages > 1) {
            Component pagination = Component.empty();
            if (page > 1) {
                pagination = pagination.append(Component.text("§a[◀ 上一页]")
                        .hoverEvent(HoverEvent.showText(Component.text("§e上一页")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli visitorperm " + land.name + " " + (page - 1))));
                pagination = pagination.append(Component.text(" "));
            }
            pagination = pagination.append(Component.text("§7第" + page + "/" + totalPages + "页"));
            if (page < totalPages) {
                pagination = pagination.append(Component.text(" "));
                pagination = pagination.append(Component.text("§a[下一页 ▶]")
                        .hoverEvent(HoverEvent.showText(Component.text("§e下一页")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli visitorperm " + land.name + " " + (page + 1))));
            }
            p.sendMessage(pagination);
        }
    }

    // ==================== 切换权限 ====================

    public void togglePerm(Player p, String landName, String permKey, int page) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        // 切换权限
        boolean oldState = false;
        switch (permKey) {
            case "move": oldState = land.denyMove; land.denyMove = !land.denyMove; break;
            case "block_place": oldState = land.denyBlockPlace; land.denyBlockPlace = !land.denyBlockPlace; break;
            case "block_break": oldState = land.denyBlockBreak; land.denyBlockBreak = !land.denyBlockBreak; break;
            case "pvp": oldState = land.denyPVP; land.denyPVP = !land.denyPVP; break;
            case "mount": oldState = land.denyMount; land.denyMount = !land.denyMount; break;
            case "ender_pearl": oldState = land.denyEnderPearl; land.denyEnderPearl = !land.denyEnderPearl; break;
            case "thrown_projectiles": oldState = land.denyThrownProjectiles; land.denyThrownProjectiles = !land.denyThrownProjectiles; break;
            case "raid": oldState = land.denyRaid; land.denyRaid = !land.denyRaid; break;
            case "bow": oldState = land.denyBow; land.denyBow = !land.denyBow; break;
            case "potion": oldState = land.denyPotion; land.denyPotion = !land.denyPotion; break;
            case "fire": oldState = land.denyFire; land.denyFire = !land.denyFire; break;
            case "fire_spread": oldState = land.denyFireSpread; land.denyFireSpread = !land.denyFireSpread; break;
            case "pickup": oldState = land.denyPickup; land.denyPickup = !land.denyPickup; break;
            case "drop": oldState = land.denyDrop; land.denyDrop = !land.denyDrop; break;
            case "explosion": oldState = land.denyExplosion; land.denyExplosion = !land.denyExplosion; break;
            case "fall_damage": oldState = land.denyFallDamage; land.denyFallDamage = !land.denyFallDamage; break;
            case "hunger": oldState = land.denyHunger; land.denyHunger = !land.denyHunger; break;
            case "all_damage": oldState = land.denyAllDamage; land.denyAllDamage = !land.denyAllDamage; break;
            case "all_effects": oldState = land.denyAllEffects; land.denyAllEffects = !land.denyAllEffects; break;
            case "item_frame": oldState = land.denyItemFrame; land.denyItemFrame = !land.denyItemFrame; break;
            case "redstone": oldState = land.denyRedstoneInteraction; land.denyRedstoneInteraction = !land.denyRedstoneInteraction; break;
            case "door": oldState = land.denyDoorInteraction; land.denyDoorInteraction = !land.denyDoorInteraction; break;
            case "audio": oldState = land.denyNoteblockJukebox; land.denyNoteblockJukebox = !land.denyNoteblockJukebox; break;
            case "lead": oldState = land.denyLead; land.denyLead = !land.denyLead; break;
            case "crop_harvest": oldState = land.denyCropHarvest; land.denyCropHarvest = !land.denyCropHarvest; break;
            case "wool_shear": oldState = land.denyWoolShear; land.denyWoolShear = !land.denyWoolShear; break;
            case "animal_feed": oldState = land.denyAnimalFeeding; land.denyAnimalFeeding = !land.denyAnimalFeeding; break;
            case "glowing": oldState = land.denyGlowing; land.denyGlowing = !land.denyGlowing; break;
            case "peace_mode": oldState = land.peaceMode; land.peaceMode = !land.peaceMode; break;
        }

        areaProtect.saveAreaToDb(land);

        String permName = getPermNameByKey(permKey);
        boolean newState = !oldState;
        p.sendMessage("§a已切换 §e" + permName + " §7→ " + (newState ? "§a已启用" : "§c已禁用"));
    }

    /**
     * ★ 兼容旧调用（不含page参数）
     */
    public void togglePerm(Player p, String landName, String permKey) {
        togglePerm(p, landName, permKey, 1);
    }

    // ==================== 数据结构 ====================

    private static class PermItem {
        String key;
        String name;
        boolean enabled;

        PermItem(String key, String name, boolean enabled) {
            this.key = key;
            this.name = name;
            this.enabled = enabled;
        }
    }

    private List<PermItem> getPermList(AreaProtection.AreaConfig land) {
        List<PermItem> perms = new ArrayList<>();
        perms.add(new PermItem("move", "移动", !land.denyMove));
        perms.add(new PermItem("block_place", "放置方块", !land.denyBlockPlace));
        perms.add(new PermItem("block_break", "破坏方块", !land.denyBlockBreak));
        perms.add(new PermItem("pvp", "玩家对战", !land.denyPVP));
        perms.add(new PermItem("mount", "骑乘坐具", !land.denyMount));
        perms.add(new PermItem("ender_pearl", "投掷末影珍珠", !land.denyEnderPearl));
        perms.add(new PermItem("thrown_projectiles", "投掷物(三叉戟/雪球/风蛋)", !land.denyThrownProjectiles));
        perms.add(new PermItem("raid", "袭击侦测", !land.denyRaid));
        perms.add(new PermItem("bow", "弓箭射击", !land.denyBow));
        perms.add(new PermItem("potion", "药水效果", !land.denyPotion));
        perms.add(new PermItem("fire", "点燃", !land.denyFire));
        perms.add(new PermItem("fire_spread", "火焰蔓延", !land.denyFireSpread));
        perms.add(new PermItem("pickup", "拾取物品", !land.denyPickup));
        perms.add(new PermItem("drop", "丢弃物品", !land.denyDrop));
        perms.add(new PermItem("explosion", "爆炸", !land.denyExplosion));
        perms.add(new PermItem("fall_damage", "摔落伤害", !land.denyFallDamage));
        perms.add(new PermItem("hunger", "饥饿", !land.denyHunger));
        perms.add(new PermItem("all_damage", "所有伤害", !land.denyAllDamage));
        perms.add(new PermItem("all_effects", "所有效果", !land.denyAllEffects));
        perms.add(new PermItem("item_frame", "展示框交互", !land.denyItemFrame));
        perms.add(new PermItem("redstone", "红石电路(中继器/比较器)", !land.denyRedstoneInteraction));
        perms.add(new PermItem("door", "门禁(门/按钮/压力板)", !land.denyDoorInteraction));
        perms.add(new PermItem("audio", "音频(音符盒/唱片机)", !land.denyNoteblockJukebox));
        perms.add(new PermItem("lead", "拴绳使用", !land.denyLead));
        perms.add(new PermItem("crop_harvest", "农作物收获", !land.denyCropHarvest));
        perms.add(new PermItem("wool_shear", "剪切羊毛/生物", !land.denyWoolShear));
        perms.add(new PermItem("animal_feed", "投喂动物", !land.denyAnimalFeeding));
        perms.add(new PermItem("glowing", "玩家发光", !land.denyGlowing));
        perms.add(new PermItem("peace_mode", "和平模式", !land.peaceMode));
        return perms;
    }

    private String getPermNameByKey(String key) {
        switch (key) {
            case "move": return "移动";
            case "block_place": return "放置方块";
            case "block_break": return "破坏方块";
            case "pvp": return "玩家对战";
            case "mount": return "骑乘坐具";
            case "ender_pearl": return "投掷末影珍珠";
            case "thrown_projectiles": return "投掷物";
            case "raid": return "袭击侦测";
            case "bow": return "弓箭射击";
            case "potion": return "药水效果";
            case "fire": return "点燃";
            case "fire_spread": return "火焰蔓延";
            case "pickup": return "拾取物品";
            case "drop": return "丢弃物品";
            case "explosion": return "爆炸";
            case "fall_damage": return "摔落伤害";
            case "hunger": return "饥饿";
            case "all_damage": return "所有伤害";
            case "all_effects": return "所有效果";
            case "item_frame": return "展示框交互";
            case "redstone": return "红石电路";
            case "door": return "门禁";
            case "audio": return "音频";
            case "lead": return "拴绳使用";
            case "crop_harvest": return "农作物收获";
            case "wool_shear": return "剪切羊毛";
            case "animal_feed": return "投喂动物";
            case "glowing": return "玩家发光";
            case "peace_mode": return "和平模式";
            default: return key;
        }
    }
}
