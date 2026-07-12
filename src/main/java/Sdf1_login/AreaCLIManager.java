package Sdf1_login;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.*;


/**
 * 领地系统CLI交互式菜单
 * 提供完整的文字交互界面
 */
public class AreaCLIManager {

    private final Main plugin;
    private final AreaProtection areaProtect;
    private static final int PAGE_SIZE = 10;

    // ★ 记录每个玩家当前所在页面（切换权限后刷新用）
    private final Map<UUID, String[]> playerPageInfo = new HashMap<>();

    // ★ 记录效果管理待输入状态
    private final Map<UUID, String[]> pendingEffectInput = new HashMap<>();

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

    public static Component header(String title) {
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
                "/protect 创建"));
        p.sendMessage(clickableAction("📋", "领地列表",
                "/protect cli lands 1"));
        p.sendMessage(clickableAction("🏪", "权限商店",
                "/protect shop list"));
        p.sendMessage(clickableAction("ℹ", "当前领地信息",
                "/protect info"));

        // ★ 公共建筑传送
        p.sendMessage(clickableAction("🏛", "公共设施列表/传送",
                "/protect listpublic"));

        // ★ 管理员配置入口
        if (areaProtect.isAreaAdmin(p)) {
            p.sendMessage(clickableAction("⚙", "全局配置",
                    "/protect cli config"));
            p.sendMessage(clickableAction("👥", "用户组管理",
                    "/protect grouplist"));
        }

        // 设置
        int uiMode = 1;
        try { uiMode = plugin.getDb().getUiMode(p.getName()); } catch (Exception ignored) {}
        p.sendMessage(clickableAction("⚙", "切换到" + (uiMode == 0 ? "GUI" : "CLI") + "模式",
                "/protect uimode " + (uiMode == 0 ? "cli" : "gui")));

        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    // ==================== 全局配置（CLI交互式） ====================

    /**
     * ★ CLI全局配置页面：显示当前配置项，点击可修改
     */
    public void showConfigPage(Player p) {
        p.sendMessage(header("全局配置"));

        String price = areaProtect.getAreaConfigValue("create_price_per_sqm");
        String maxLands = areaProtect.getAreaConfigValue("max_lands_per_player");
        String height = areaProtect.getAreaConfigValue("default_height");
        String peace = areaProtect.getAreaConfigValue("peace_mode_max_duration");

        // 创建价格
        p.sendMessage(Component.empty()
                .append(Component.text("§a创建价格(每㎡): §f" + (price != null ? price : "10") + " "))
                .append(Component.text("§a[+10] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config create_price " + safeParseInt(price, 10, 10)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值（比当前大=加钱，比当前小=减钱）"))))
                .append(Component.text("§c[-10] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config create_price " + safeParseInt(price, 10, -10)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值（比当前大=加钱，比当前小=减钱）"))))
        );

        // 每人最大领地数
        p.sendMessage(Component.empty()
                .append(Component.text("§a每人最大领地数: §f" + (maxLands != null ? maxLands : "5") + " "))
                .append(Component.text("§a[+1] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config max_lands " + safeParseInt(maxLands, 5, 1)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
                .append(Component.text("§c[-1] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config max_lands " + safeParseInt(maxLands, 5, -1)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
        );

        // 默认高度
        p.sendMessage(Component.empty()
                .append(Component.text("§a默认高度: §f" + (height != null ? height : "255") + " "))
                .append(Component.text("§a[+10] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config default_height " + safeParseInt(height, 255, 10)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
                .append(Component.text("§c[-10] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config default_height " + safeParseInt(height, 255, -10)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
        );

        // 和平时长
        p.sendMessage(Component.empty()
                .append(Component.text("§a和平模式最大时长(秒): §f" + (peace != null ? peace : "3600") + " "))
                .append(Component.text("§a[+600] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config peace_duration " + safeParseInt(peace, 3600, 600)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
                .append(Component.text("§c[-600] ")
                        .clickEvent(ClickEvent.suggestCommand("/protect config peace_duration " + safeParseInt(peace, 3600, -600)))
                        .hoverEvent(HoverEvent.showText(Component.text("§e点击后输入新值"))))
        );

        p.sendMessage(Component.text("§7提示: 点击修改值后，可在聊天栏输入任意数字进行精确调整"));
        p.sendMessage(Component.text("§7支持: 纯数字、中文数字、中文大写、罗马数字、英文数字"));

        p.sendMessage(clickableAction("◀", "返回主菜单", "/protect cli menu"));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    private int safeParseInt(String val, int defaultVal, int delta) {
        try {
            if (val != null) return Integer.parseInt(val) + delta;
        } catch (Exception ignored) {}
        return defaultVal + delta;
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
                    "/protect 创建"));
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
        p.sendMessage(clickableAction("👤", "用户管理（含权限编辑）",
                "/protect cli members " + land.name + " 1"));
        p.sendMessage(clickableAction("🔑", "访客授权（全局）",
                "/protect cli visitorperm " + land.name + " 1"));
        p.sendMessage(clickableAction("💥", "效果管理（清除/增益）",
                "/protect cli effectsmgmt " + land.name + " 1"));
        // ★ 新增：设置传送点
        p.sendMessage(clickableAction("📍", "设置传送点（当前位置）",
                "/protect settp " + land.name));
        p.sendMessage(clickableAction("🔄", "过户领地（需要所有者权限）",
                "/protect transfeland " + land.name));
        // ★ 新增：领地公告管理
        p.sendMessage(clickableAction("📢", "领地公告（进出提示消息）",
                "/protect cli announcement " + land.name + " 1"));
        // ★ 新增：领地改名
        long[] cooldown = areaProtect.getRenameCooldown(p);
        String renameHint;
        if (cooldown != null) {
            long remainMin = cooldown[0] / 60;
            long remainSecR = cooldown[0] % 60;
            renameHint = "§c冷却中（已用" + cooldown[1] + "/" + 5 + "次，"
                    + remainMin + "分" + remainSecR + "秒后解锁）";
        } else {
            renameHint = "§a点击输入新名字（1小时限5次）";
        }
        p.sendMessage(clickableAction("✏️", "领地改名",
                "/protect cli rename " + land.name));

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
                boolean isMemberOwner = member.equalsIgnoreCase(land.owner);
                boolean isMemberAdmin = areaProtect.isLandAdmin(land.name, member);
                Component line = Component.empty();
                line = line.append(Component.text("§f" + member));
                if (isMemberOwner) {
                    line = line.append(Component.text(" §c[所有者]"));
                }
                if (isMemberAdmin) {
                    line = line.append(Component.text(" §6[管理员]"));
                }
                line = line.append(Component.text(" "));
                line = line.append(Component.text("§a[编辑权限]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§e设置该成员的独立权限")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli playerperm " + land.name + " " + member + " 1")));
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

        // ★ 删除冷却期间修改权限 → 自动取消删除
        if (areaProtect.hasPendingDelete(landName)) {
            areaProtect.cancelPendingDelete(landName);
            p.sendMessage(Component.text("§e§l[防护] §f检测到权限修改，已自动取消领地删除"));
        }

        // 切换权限
        boolean oldState = false;
        switch (permKey) {
            case "move": oldState = land.denyMove; land.denyMove = !land.denyMove; break;
            case "block_place": oldState = land.denyBlockPlace; land.denyBlockPlace = !land.denyBlockPlace; break;
            case "block_break": oldState = land.denyBlockBreak; land.denyBlockBreak = !land.denyBlockBreak; break;
            case "entity_interact": oldState = land.denyEntityInteract; land.denyEntityInteract = !land.denyEntityInteract; break;
            case "container": oldState = land.denyContainer; land.denyContainer = !land.denyContainer; break;
            case "pvp": oldState = land.denyPVP; land.denyPVP = !land.denyPVP; break;
            case "mount": oldState = land.denyMount; land.denyMount = !land.denyMount; break;
            case "ender_pearl": oldState = land.denyEnderPearl; land.denyEnderPearl = !land.denyEnderPearl; break;
            case "thrown_projectiles": oldState = land.denyThrownProjectiles; land.denyThrownProjectiles = !land.denyThrownProjectiles; break;
            case "raid": oldState = land.denyRaid; land.denyRaid = !land.denyRaid; break;
            case "bow": oldState = land.denyBow; land.denyBow = !land.denyBow; break;
            case "potion": oldState = land.denyPotion; land.denyPotion = !land.denyPotion; break;
            case "fire": oldState = land.denyFire; land.denyFire = !land.denyFire; break;
            case "fire_spread": oldState = land.denyFireSpread; land.denyFireSpread = !land.denyFireSpread; break;
            case "pickup": oldState = land.allowPickup; land.allowPickup = !land.allowPickup; break;
            case "drop": oldState = land.allowDrop; land.allowDrop = !land.allowDrop; break;
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
            case "mob_attack": oldState = land.denyMobAttack; land.denyMobAttack = !land.denyMobAttack; break;
            case "glowing": oldState = land.denyGlowing; land.denyGlowing = !land.denyGlowing; break;
            case "visitor_teleport": oldState = land.allowVisitorTeleport; land.allowVisitorTeleport = !land.allowVisitorTeleport; break;
            case "peace_mode": oldState = land.peaceMode; land.peaceMode = !land.peaceMode; break;
            case "public_building": oldState = land.isPublicBuilding; land.isPublicBuilding = !land.isPublicBuilding; break;
        }

        areaProtect.saveAreaToDb(land);

        String permName = getPermNameByKey(permKey);
        boolean newState = !oldState;
        p.sendMessage("§a已切换 §e" + permName + " §7→ " + (newState ? "§a已启用" : "§c已关闭"));
    }

    /**
     * ★ 兼容旧调用（不含page参数）
     */
    public void togglePerm(Player p, String landName, String permKey) {
        togglePerm(p, landName, permKey, 1);
    }

    // ==================== 数据结构 ====================

    /**
     * ★ 将短key（如"block_break"）映射为deny字段名（如"denyBlockBreak"）
     */
    private static String shortKeyToDenyField(String shortKey) {
        switch (shortKey) {
            case "move": return "denyMove";
            case "block_place": return "denyBlockPlace";
            case "block_break": return "denyBlockBreak";
            case "entity_interact": return "denyEntityInteract";
            case "container": return "denyContainer";
            case "pvp": return "denyPVP";
            case "mount": return "denyMount";
            case "ender_pearl": return "denyEnderPearl";
            case "thrown_projectiles": return "denyThrownProjectiles";
            case "raid": return "denyRaid";
            case "bow": return "denyBow";
            case "potion": return "denyPotion";
            case "fire": return "denyFire";
            case "fire_spread": return "denyFireSpread";
            case "pickup": return "allowPickup";
            case "drop": return "allowDrop";
            case "explosion": return "denyExplosion";
            case "fall_damage": return "denyFallDamage";
            case "hunger": return "denyHunger";
            case "all_damage": return "denyAllDamage";
            case "all_effects": return "denyAllEffects";
            case "item_frame": return "denyItemFrame";
            case "redstone": return "denyRedstoneInteraction";
            case "door": return "denyDoorInteraction";
            case "audio": return "denyNoteblockJukebox";
            case "lead": return "denyLead";
            case "crop_harvest": return "denyCropHarvest";
            case "wool_shear": return "denyWoolShear";
            case "animal_feed": return "denyAnimalFeeding";
            case "mob_attack": return "denyMobAttack";
            case "glowing": return "denyGlowing";
            case "peace_mode": return "peaceMode";
            case "public_building": return "isPublicBuilding";
            case "visitor_teleport": return "allowVisitorTeleport";
            case "tp": return "allowTeleport";
            default: return shortKey;
        }
    }

    static class PermItem {
        String key;
        String name;
        boolean enabled;
        boolean overridden; // 是否有per-player覆盖

        PermItem(String key, String name, boolean enabled) {
            this(key, name, enabled, false);
        }

        PermItem(String key, String name, boolean enabled, boolean overridden) {
            this.key = key;
            this.name = name;
            this.enabled = enabled;
            this.overridden = overridden;
        }
    }

    private List<PermItem> getPermList(AreaProtection.AreaConfig land) {
        List<PermItem> perms = new ArrayList<>();
        perms.add(new PermItem("move", "移动", !land.denyMove));
        perms.add(new PermItem("block_place", "放置方块", !land.denyBlockPlace));
        perms.add(new PermItem("block_break", "破坏方块", !land.denyBlockBreak));
        perms.add(new PermItem("entity_interact", "实体交互(船/矿车/盔甲架/展示框)", !land.denyEntityInteract));
        perms.add(new PermItem("container", "容器管理", !land.denyContainer));
        perms.add(new PermItem("pvp", "玩家对战", !land.denyPVP));
        perms.add(new PermItem("mount", "骑乘坐具", !land.denyMount));
        perms.add(new PermItem("ender_pearl", "投掷末影珍珠", !land.denyEnderPearl));
        perms.add(new PermItem("thrown_projectiles", "投掷物(三叉戟/雪球/风蛋)", !land.denyThrownProjectiles));
        perms.add(new PermItem("raid", land.denyRaid ? "禁止袭击" : "启用袭击", !land.denyRaid));
        perms.add(new PermItem("bow", "弓箭射击", !land.denyBow));
        perms.add(new PermItem("potion", "药水效果", !land.denyPotion));
        perms.add(new PermItem("fire", land.denyFire ? "禁止点燃" : "启用点燃", !land.denyFire));
        perms.add(new PermItem("fire_spread", "火焰蔓延", !land.denyFireSpread));
        perms.add(new PermItem("pickup", "允许拾取", land.allowPickup));
        perms.add(new PermItem("drop", "允许丢弃", land.allowDrop));
        perms.add(new PermItem("explosion", "爆炸", !land.denyExplosion));
        perms.add(new PermItem("fall_damage", "摔落伤害", !land.denyFallDamage));
        perms.add(new PermItem("hunger", "饥饿", !land.denyHunger));
        perms.add(new PermItem("all_damage", "所有伤害", !land.denyAllDamage));
        perms.add(new PermItem("all_effects", "所有效果", !land.denyAllEffects));
        perms.add(new PermItem("item_frame", "禁止展示框", !land.denyItemFrame));
        perms.add(new PermItem("redstone", "红石电路(中继器/比较器)", !land.denyRedstoneInteraction));
        perms.add(new PermItem("door", "禁止门禁(门/按钮/压力板)", !land.denyDoorInteraction));
        perms.add(new PermItem("audio", "音频(音符盒/唱片机)", !land.denyNoteblockJukebox));
        perms.add(new PermItem("lead", "拴绳使用", !land.denyLead));
        perms.add(new PermItem("crop_harvest", land.denyCropHarvest ? "禁用农作物收获" : "启用农作物收获", !land.denyCropHarvest));
        perms.add(new PermItem("wool_shear", "剪切羊毛/生物", !land.denyWoolShear));
        perms.add(new PermItem("animal_feed", land.denyAnimalFeeding ? "禁止投喂" : "允许投喂动物", !land.denyAnimalFeeding));
        perms.add(new PermItem("mob_attack", land.denyMobAttack ? "禁止攻击生物" : "允许攻击生物", !land.denyMobAttack));
        perms.add(new PermItem("glowing", "玩家发光", !land.denyGlowing));
        perms.add(new PermItem("visitor_teleport", "传送", land.allowVisitorTeleport));
        perms.add(new PermItem("peace_mode", "和平模式", land.peaceMode));
        perms.add(new PermItem("public_building", "公共建筑设施", land.isPublicBuilding));
        return perms;
    }

    private String getPermNameByKey(String key) {
        switch (key) {
            case "move": return "移动";
            case "block_place": return "放置方块";
            case "block_break": return "破坏方块";
            case "container": return "容器管理";
            case "pvp": return "玩家对战";
            case "mount": return "骑乘坐具";
            case "ender_pearl": return "投掷末影珍珠";
            case "thrown_projectiles": return "投掷物";
            case "raid": return "禁止袭击";
            case "bow": return "弓箭射击";
            case "potion": return "药水效果";
            case "fire": return "点燃";
            case "fire_spread": return "火焰蔓延";
            case "pickup": return "允许拾取";
            case "drop": return "允许丢弃";
            case "explosion": return "爆炸";
            case "fall_damage": return "摔落伤害";
            case "hunger": return "饥饿";
            case "all_damage": return "所有伤害";
            case "all_effects": return "所有效果";
            case "item_frame": return "禁止展示框";
            case "redstone": return "红石电路";
            case "door": return "禁止门禁";
            case "audio": return "音频";
            case "lead": return "拴绳使用";
            case "crop_harvest": return "农作物收获";
            case "wool_shear": return "剪切羊毛";
            case "animal_feed": return "投喂动物";
            case "mob_attack": return "攻击生物";
            case "glowing": return "玩家发光";
            case "peace_mode": return "和平模式";
            case "public_building": return "公共建筑设施";
            case "visitor_teleport": return "传送";
            case "tp": return "传送";
            default: return key;
        }
    }

    // ==================== Per-Player 独立权限管理 ====================

    /**
     * 显示成员列表（点击成员进入per-player权限编辑）
     */
    public void showMemberPermList(Player p, String landName, int page) {
        savePageInfo(p, "memberperm", landName, page);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        // ★ 权限检查：只有所有者或管理员能编辑成员权限
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        // 获取成员列表
        Set<String> members = areaProtect.getAreaMembers(landName);
        if (members == null || members.isEmpty()) {
            p.sendMessage(header("成员权限: " + landName));
            p.sendMessage(Component.text("§7暂无成员，请先使用 §e/protect addvisitor §7添加"));
            p.sendMessage(Component.empty()
                    .append(Component.text("§a[◀ 返回管理]")
                            .clickEvent(ClickEvent.runCommand(
                                    "/protect cli manage " + land.name + " 1"))));
            return;
        }

        List<String> memberList = new ArrayList<>(members);
        int totalPages = Math.max(1, (int) Math.ceil((double) memberList.size() / PAGE_SIZE));
        page = Math.max(1, Math.min(page, totalPages));

        p.sendMessage(header("成员权限: " + landName + " §7第" + page + "/" + totalPages + "页"));

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, memberList.size());

        for (int i = start; i < end; i++) {
            String member = memberList.get(i);
            boolean isMemberOwner = member.equalsIgnoreCase(land.owner);

            String roleTag = isMemberOwner ? " §c[所有者]" : "";
            Component line = Component.empty();
            line = line.append(Component.text("§e● " + member + roleTag));
            line = line.append(Component.text(" "));
            line = line.append(Component.text("§a[编辑权限]")
                    .hoverEvent(HoverEvent.showText(
                            Component.text("§e设置该成员的独立权限")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli playerperm " + land.name + " " + member + " 1")));
            p.sendMessage(line);
        }

        // 分页
        if (totalPages > 1) {
            Component pagination = Component.empty();
            if (page > 1) {
                pagination = pagination.append(Component.text("§a[◀ 上一页]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli memberperm " + land.name + " " + (page - 1))));
                pagination = pagination.append(Component.text(" "));
            }
            pagination = pagination.append(Component.text("§7" + page + "/" + totalPages));
            if (page < totalPages) {
                pagination = pagination.append(Component.text(" "));
                pagination = pagination.append(Component.text("§a[下一页 ▶]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli memberperm " + land.name + " " + (page + 1))));
            }
            p.sendMessage(pagination);
        }

        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回管理]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli manage " + land.name + " " + page))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    /**
     * 显示某个成员的独立权限编辑页
     */
    public void showPlayerPerm(Player p, String landName, String targetPlayer, int page) {
        savePageInfo(p, "playerperm", landName + ":" + targetPlayer, page);

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        // ★ 权限检查：只有所有者或管理员能编辑成员权限
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        int landId = areaProtect.getLandIdFromDb(landName);
        if (landId <= 0) {
            p.sendMessage(Component.text("§c领地数据库ID获取失败"));
            return;
        }

        // 获取该成员的per-player权限
        Map<String, Boolean> playerPerms = areaProtect.getPlayerPermMap(landId, targetPlayer);

        // ★ 管理员简化面板：仅非领地主且非tag管理员时简化显示
        boolean isTargetAdmin = areaProtect.isLandAdmin(land.name, targetPlayer);
        if (isTargetAdmin && !isOwner && !isAdmin) {
            p.sendMessage(header(targetPlayer + " 的独立权限"));
            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§6§l该玩家是管理员，自动获得所有权限"));
            p.sendMessage(Component.text("§7管理员不受任何权限限制（发光效果除外）"));
            p.sendMessage(Component.text(""));

            // ★ 领地主或tag管理员：可切换管理员身份
            if ((isOwner || isAdmin) && !targetPlayer.equalsIgnoreCase(land.owner)) {
                Component adminLine = Component.empty();
                adminLine = adminLine.append(Component.text("§6✔ §f身份: §6管理员"));
                adminLine = adminLine.append(Component.text(" "));
                adminLine = adminLine.append(Component.text("§c[撤销管理员]")
                        .hoverEvent(HoverEvent.showText(Component.text("§c将该玩家恢复为普通成员")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli toggleadmin " + land.name + " " + targetPlayer + " 1")));
                p.sendMessage(adminLine);
                p.sendMessage(Component.text(""));
            }

            // ★ 玩家发光控制（领地主和管理员都可以操作）
            boolean glowingDefault = !land.denyGlowing;
            boolean glowingOverride = playerPerms.containsKey("glowing") ? playerPerms.get("glowing") : glowingDefault;
            String glowingIcon = glowingOverride ? "§a✔" : "§c✘";
            String glowingState = glowingOverride ? "§a允许" : "§c禁止";
            boolean glowingOverridden = playerPerms.containsKey("glowing");
            String glowingTag = glowingOverridden ? " §e[自定义]" : " §7[领地默认]";
            Component glowLine = Component.empty();
            glowLine = glowLine.append(Component.text(glowingIcon + " §f玩家发光 §7" + glowingState + glowingTag));
            glowLine = glowLine.append(Component.text(" "));
            glowLine = glowLine.append(Component.text("§e[切换]")
                    .hoverEvent(HoverEvent.showText(
                            Component.text("§e切换该管理员的发光效果权限")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli toggleplayerperm " + land.name + " " + targetPlayer + " glowing 1")));
            p.sendMessage(glowLine);
            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§c[清除所有自定义权限]")
                    .hoverEvent(HoverEvent.showText(
                            Component.text("§c将该成员的权限恢复为领地默认")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect clearplayerperm " + land.name + " " + targetPlayer)));
            p.sendMessage(Component.text("§c[返回成员列表]")
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli members " + land.name + " 1")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
            return;
        }

        // ★ 普通成员：完整权限列表
        List<PermItem> perms = getPermListWithOverrides(land, playerPerms);

        int totalPages = Math.max(1, (int) Math.ceil((double) perms.size() / PAGE_SIZE));
        page = Math.max(1, Math.min(page, totalPages));

        p.sendMessage(header(targetPlayer + " 的独立权限"));

        // 显示说明
        p.sendMessage(Component.text("§7§l说明:"));
        p.sendMessage(Component.text("  §a✔ 已启用 §7= 允许该操作"));
        p.sendMessage(Component.text("  §c✘ 已禁用 §7= 禁止该操作"));
        p.sendMessage(Component.text("  §e★ 自定义 §7= 与领地默认不同"));
        p.sendMessage(Component.text(""));

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, perms.size());

        for (int i = start; i < end; i++) {
            PermItem perm = perms.get(i);
            String icon;
            if (perm.overridden) {
                icon = perm.enabled ? "§a★" : "§c★"; // 星号表示per-player覆盖
            } else {
                icon = perm.enabled ? "§a✔" : "§c✘";
            }
            String state = perm.enabled ? "§a允许" : "§c禁止";
            String overrideTag = perm.overridden ? " §e[自定义]" : " §7[领地默认]";

            Component line = Component.empty();
            line = line.append(Component.text(icon + " §f" + perm.name + " §7" + state + overrideTag));
            line = line.append(Component.text(" "));
            line = line.append(Component.text("§e[切换]")
                    .hoverEvent(HoverEvent.showText(
                            Component.text("§e切换该成员的此权限")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli toggleplayerperm " + land.name + " " + targetPlayer + " " + perm.key + " " + page)));
            p.sendMessage(line);
        }

        // 分页
        if (totalPages > 1) {
            Component pagination = Component.empty();
            if (page > 1) {
                pagination = pagination.append(Component.text("§a[◀ 上一页]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli playerperm " + land.name + " " + targetPlayer + " " + (page - 1))));
                pagination = pagination.append(Component.text(" "));
            }
            pagination = pagination.append(Component.text("§7" + page + "/" + totalPages));
            if (page < totalPages) {
                pagination = pagination.append(Component.text(" "));
                pagination = pagination.append(Component.text("§a[下一页 ▶]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli playerperm " + land.name + " " + targetPlayer + " " + (page + 1))));
            }
            p.sendMessage(pagination);
        }

        // ★ 设为管理员按钮（全局置顶，打印在最后=聊天窗口最底部）
        if ((isOwner || isAdmin) && !targetPlayer.equalsIgnoreCase(land.owner)) {
            Component adminLine = Component.empty();
            adminLine = adminLine.append(Component.text("§7✘ §f身份: §7普通成员"));
            adminLine = adminLine.append(Component.text(" "));
            adminLine = adminLine.append(Component.text("§a[设为管理员]")
                    .hoverEvent(HoverEvent.showText(Component.text("§a授予该玩家管理员身份（自动获得所有权限）")))
                    .clickEvent(ClickEvent.runCommand(
                            "/protect cli toggleadmin " + land.name + " " + targetPlayer + " " + page)));
            p.sendMessage(adminLine);
            p.sendMessage(Component.text(""));
        }

        // 清除按钮
        p.sendMessage(Component.empty()
                .append(Component.text("§c[清除所有自定义权限]")
                        .hoverEvent(HoverEvent.showText(
                                Component.text("§c将该成员的权限恢复为领地默认")))
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli clearplayerperm " + land.name + " " + targetPlayer))));

        // ★ 修复：返回成员管理（含删除按钮），不是成员权限列表
        p.sendMessage(Component.empty()
                .append(Component.text("§a[◀ 返回成员列表]")
                        .clickEvent(ClickEvent.runCommand(
                                "/protect cli members " + land.name + " 1"))));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    /**
     * ★ 切换玩家的管理员身份（仅领地主可操作）
     */
    public void toggleAdmin(Player p, String landName, String targetPlayer, int page) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c只有领地所有者或插件管理员才能操作管理员身份"));
            return;
        }

        // 不能操作自己
        if (targetPlayer.equalsIgnoreCase(land.owner)) {
            p.sendMessage(Component.text("§c不能修改自己的管理员身份"));
            return;
        }

        // 检查目标是否为成员（成员名存为小写）
        Set<String> members = areaProtect.getLandMembers(landName);
        if (!members.contains(targetPlayer) && !members.contains(targetPlayer.toLowerCase())) {
            p.sendMessage(Component.text("§c" + targetPlayer + " 不是该领地的成员"));
            return;
        }

        boolean isTargetAdmin = areaProtect.isLandAdmin(land.name, targetPlayer);
        // 切换管理员状态
        areaProtect.setLandAdmin(landName, targetPlayer, !isTargetAdmin);

        if (!isTargetAdmin) {
            p.sendMessage(Component.text("§a已将 " + targetPlayer + " §a设为管理员（自动获得所有权限）"));
        } else {
            p.sendMessage(Component.text("§e已撤销 " + targetPlayer + " §e的管理员身份（恢复为普通成员）"));
        }

        // 返回权限面板
        showPlayerPerm(p, landName, targetPlayer, page);
    }

    /**
     * 切换某个成员的独立权限
     */
    public void togglePlayerPerm(Player p, String landName, String targetPlayer, String permKey, int page) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        // 权限检查
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        int landId = areaProtect.getLandIdFromDb(landName);
        if (landId <= 0) {
            p.sendMessage(Component.text("§c领地数据库ID获取失败"));
            return;
        }

        // 获取当前值，判断是否已有覆盖
        // ★ 使用deny字段名作为key，与getEffectiveDeny保持一致
        String denyField = shortKeyToDenyField(permKey);
        Map<String, Boolean> currentPerms = areaProtect.getPlayerPermMap(landId, targetPlayer);
        boolean currentVal = currentPerms.getOrDefault(denyField, getLandDefaultVal(land, denyField));

        // 切换
        boolean newVal = !currentVal;
        areaProtect.setPlayerPerm(landId, targetPlayer, denyField, newVal);

        String permName = getPermNameByKey(permKey);
        p.sendMessage("§a已设置 §e" + targetPlayer + " §a的 §e" + permName + " §7→ " + (newVal ? "§a允许" : "§c禁止"));

        // 刷新页面
        showPlayerPerm(p, landName, targetPlayer, page);
    }

    /**
     * 清除某成员所有per-player权限覆盖
     */
    public void clearPlayerPerm(Player p, String landName, String targetPlayer) {
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

        int landId = areaProtect.getLandIdFromDb(landName);
        if (landId <= 0) {
            p.sendMessage(Component.text("§c领地数据库ID获取失败"));
            return;
        }

        areaProtect.setPlayerPermJson(landId, targetPlayer, "");
        p.sendMessage("§a已清除 §e" + targetPlayer + " §a的所有自定义权限，恢复为领地默认");

        // 返回成员列表
        showMemberPermList(p, landName, 1);
    }

    /**
     * 获取带per-player覆盖的权限列表
     */
    private List<PermItem> getPermListWithOverrides(AreaProtection.AreaConfig land, Map<String, Boolean> playerPerms) {
        List<PermItem> perms = new ArrayList<>();
        String[][] permDefs = {
                {"move", "移动", "denyMove"},
                {"block_place", "放置方块", "denyBlockPlace"},
                {"block_break", "破坏方块", "denyBlockBreak"},
                {"entity_interact", "实体交互", "denyEntityInteract"},
                {"container", "容器管理", "denyContainer"},
                {"pvp", "玩家对战", "denyPVP"},
                {"mount", "骑乘坐具", "denyMount"},
                {"ender_pearl", "投掷末影珍珠", "denyEnderPearl"},
                {"thrown_projectiles", "投掷物(三叉戟/雪球/风蛋)", "denyThrownProjectiles"},
                {"raid", "禁止袭击", "denyRaid"},
                {"bow", "弓箭射击", "denyBow"},
                {"potion", "药水效果", "denyPotion"},
                {"fire", "点燃", "denyFire"},
                {"fire_spread", "火焰蔓延", "denyFireSpread"},
                {"pickup", "允许拾取", "allowPickup"},
                {"drop", "允许丢弃", "allowDrop"},
                {"explosion", "爆炸", "denyExplosion"},
                {"fall_damage", "摔落伤害", "denyFallDamage"},
                {"hunger", "饥饿", "denyHunger"},
                {"all_damage", "所有伤害", "denyAllDamage"},
                {"all_effects", "所有效果", "denyAllEffects"},
                {"item_frame", "禁止展示框", "denyItemFrame"},
                {"redstone", "红石电路(中继器/比较器)", "denyRedstoneInteraction"},
                {"door", "禁止门禁(门/按钮/压力板)", "denyDoorInteraction"},
                {"audio", "音频(音符盒/唱片机)", "denyNoteblockJukebox"},
                {"lead", "拴绳使用", "denyLead"},
                {"crop_harvest", "农作物收获", "denyCropHarvest"},
                {"wool_shear", "剪切羊毛/生物", "denyWoolShear"},
                {"animal_feed", "投喂动物", "denyAnimalFeeding"},
                {"mob_attack", "攻击生物", "denyMobAttack"},
                {"glowing", "玩家发光", "denyGlowing"},
                {"peace_mode", "和平模式", "peaceMode"},
                {"tp", "传送", "allowTeleport"}
        };

        for (String[] def : permDefs) {
            String key = def[0];
            String name = def[1];
            String field = def[2];
            boolean landDefault = getLandDefaultVal(land, field);

            // 特殊处理：denyRaid的显示名称
            if (field.equals("denyRaid")) {
                name = landDefault ? "禁止袭击" : "启用袭击";
            }
            // ★ denyFire显示名称
            if (field.equals("denyFire")) {
                name = landDefault ? "禁止点燃" : "启用点燃";
            }
            // ★ denyCropHarvest显示名称
            if (field.equals("denyCropHarvest")) {
                name = landDefault ? "禁用农作物收获" : "启用农作物收获";
            }
            // ★ denyAnimalFeeding显示名称
            if (field.equals("denyAnimalFeeding")) {
                name = landDefault ? "禁止投喂" : "允许投喂动物";
            }
            // ★ denyMobAttack显示名称
            if (field.equals("denyMobAttack")) {
                name = landDefault ? "禁止攻击生物" : "允许攻击生物";
            }

            // 如果有per-player覆盖，使用覆盖值；否则使用领地默认
            if (playerPerms.containsKey(field)) {
                boolean playerVal = playerPerms.get(field);
                // 注意：per-player存储的是deny值
                // perm.enabled = true 表示"允许操作"（即deny=false）
                perms.add(new PermItem(key, name, !playerVal, true));
            } else {
                perms.add(new PermItem(key, name, !landDefault, false));
            }
        }
        return perms;
    }

    /**
     * 获取领地默认deny值
     */
    private boolean getLandDefaultVal(AreaProtection.AreaConfig land, String field) {
        switch (field) {
            case "denyMove": return land.denyMove;
            case "denyBlockPlace": return land.denyBlockPlace;
            case "denyBlockBreak": return land.denyBlockBreak;
            case "denyEntityInteract": return land.denyEntityInteract;
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
            case "denyPickup": return land.allowPickup;
            case "denyDrop": return land.allowDrop;
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
            case "isPublicBuilding": return land.isPublicBuilding;
            case "allowVisitorTeleport": return land.allowVisitorTeleport;
            case "allowTeleport": return false; // per-player 默认关闭
            default: return false;
        }
    }

    // ==================== 效果管理 ====================

    /**
     * 显示效果管理菜单
     * 支持：全清负面、单清效果、添加增益
     */
    public void showEffectsManagement(Player p, String landName, int subPage) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        // 权限检查
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        // ========== 子菜单1：清除效果管理 ==========
        if (subPage == 1) {
            p.sendMessage(header("效果管理: " + landName));
            p.sendMessage(Component.text("§7清除所有负面效果: §f" + (land.clearAllBadEffects ? "§a开启" : "§c关闭")));
            p.sendMessage(Component.text("§7禁止所有效果: §f" + (land.denyAllEffects ? "§a开启" : "§c关闭")));

            // 全清负面
            p.sendMessage(Component.text("")
                    .append(Component.text("§a[关闭清除所有负面效果]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击关闭清除所有负面效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsclearall " + landName + " off")));
            p.sendMessage(Component.text("")
                    .append(Component.text("§a[开启清除所有负面效果]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击开启清除所有负面效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsclearall " + landName + " on")));

            // 禁止所有效果
            p.sendMessage(Component.text("")
                    .append(Component.text("§a[关闭禁止所有效果]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击关闭禁止所有效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsdenyall " + landName + " off")));
            p.sendMessage(Component.text("")
                    .append(Component.text("§a[开启禁止所有效果]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击开启禁止所有效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsdenyall " + landName + " on")));

            // 返回
            p.sendMessage(Component.text(""));
            p.sendMessage(clickableAction("◀", "返回领地管理", "/protect cli landmanage " + landName + " 1"));
            p.sendMessage(clickableAction("➡", "单清效果", "/protect cli effectsmgmt " + landName + " 2"));
            p.sendMessage(clickableAction("➡", "添加增益效果", "/protect cli effectsmgmt " + landName + " 3"));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }

        // ========== 子菜单2：单清效果列表 ==========
        else if (subPage == 2) {
            p.sendMessage(header("清除指定效果: " + landName));
            p.sendMessage(Component.text("§7当前单清列表:"));

            if (land.clearEffects.isEmpty()) {
                p.sendMessage(Component.text("§7（空）"));
            } else {
                for (int i = 0; i < land.clearEffects.size(); i++) {
                    String eff = land.clearEffects.get(i);
                    p.sendMessage(Component.empty()
                            .append(Component.text("§f- " + eff))
                            .append(Component.text(" §c[x]"))
                            .hoverEvent(HoverEvent.showText(Component.text("§e点击移除效果 '" + eff + "'")))
                            .clickEvent(ClickEvent.runCommand("/protect cli effectsclearremove " + landName + " " + (i + 1))));
                }
            }

            // ★ 可点击添加按钮 → 跳转到效果选择列表
            p.sendMessage(Component.text("")
                    .append(Component.text("§e[添加效果]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击选择要清除的效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 4")));
            p.sendMessage(Component.text("§a[◀ 返回管理菜单]")
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 1")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }

        // ========== 子菜单3：添加增益效果 ==========
        else if (subPage == 3) {
            p.sendMessage(header("添加增益效果: " + landName));
            p.sendMessage(Component.text("§7当前增益列表:"));

            if (land.giveEffects.isEmpty()) {
                p.sendMessage(Component.text("§7（空）"));
            } else {
                for (int i = 0; i < land.giveEffects.size(); i++) {
                    String[] eff = land.giveEffects.get(i);
                    String desc = eff[0] + (eff.length > 1 ? " Lv" + eff[1] : "") + (eff.length > 2 ? " §f" + eff[2] + "秒" : "");
                    String level = eff.length > 1 ? eff[1] : "1";
                    String duration = eff.length > 2 ? eff[2] : "300";
                    p.sendMessage(Component.empty()
                            .append(Component.text("§a- " + desc + " "))
                            .append(Component.text("§b[等级:" + level + "]")
                                    .hoverEvent(HoverEvent.showText(Component.text("§e点击编辑等级")))
                                    .clickEvent(ClickEvent.runCommand("/protect cli effectsaddedit " + landName + " " + (i + 1) + " level")))
                            .append(Component.text(" "))
                            .append(Component.text("§d[时长:" + duration + "s]")
                                    .hoverEvent(HoverEvent.showText(Component.text("§e点击编辑时长(秒)")))
                                    .clickEvent(ClickEvent.runCommand("/protect cli effectsaddedit " + landName + " " + (i + 1) + " duration")))
                            .append(Component.text(" "))
                            .append(Component.text("§c[x]")
                                    .hoverEvent(HoverEvent.showText(Component.text("§c点击移除增益效果 '" + desc + "'")))
                                    .clickEvent(ClickEvent.runCommand("/protect cli effectsaddremove " + landName + " " + (i + 1)))));
                }
            }

            p.sendMessage(Component.text(""));
            // ★ 可点击添加按钮 → 跳转到增益效果选择列表
            p.sendMessage(Component.text("")
                    .append(Component.text("§e[添加增益]"))
                    .hoverEvent(HoverEvent.showText(Component.text("§e点击选择要添加的增益效果")))
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 5")));
            p.sendMessage(Component.text("§a[◀ 返回管理菜单]")
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 1")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }

        // ========== 子菜单4：选择清除效果（可点击列表） ==========
        else if (subPage == 4) {
            p.sendMessage(header("选择要清除的效果: " + landName));

            // ★ 负面效果（可点击添加到单清列表）
            p.sendMessage(Component.text("§c§l负面效果:"));
            String[][] badEffects = {
                    {"缓慢", "slowness"}, {"挖掘疲劳", "mining_fatigue"}, {"瞬间伤害", "instant_damage"},
                    {"反胃", "nausea"}, {"失明", "blindness"}, {"饥饿", "hunger"},
                    {"虚弱", "weakness"}, {"中毒", "poison"}, {"凋零", "wither"},
                    {"飘浮", "levitation"}, {"霉运", "unluck"}, {"黑暗", "darkness"},
                    {"蓄风", "wind_charged"}, {"盘丝", "weaving"}, {"渗浆", "oozing"}, {"寄生", "infested"}
            };
            for (String[] eff : badEffects) {
                boolean alreadyInList = land.clearEffects.contains(eff[0]);
                String prefix = alreadyInList ? "§7" : "§c";
                String suffix = alreadyInList ? " §7(已添加)" : "";
                Component btn = Component.text(prefix + "§l[+] " + eff[0] + suffix);
                if (!alreadyInList) {
                    btn = btn.hoverEvent(HoverEvent.showText(Component.text("§e点击添加清除效果: " + eff[0])))
                            .clickEvent(ClickEvent.runCommand("/protect cli effectsclearadd " + landName + " " + eff[0]));
                }
                p.sendMessage(btn);
            }

            // ★ 中性效果
            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§e§l中性效果:"));
            String[][] neutralEffects = {
                    {"不祥之兆", "bad_omen"}, {"袭击之兆", "raid_omen"}, {"试炼之兆", "trial_omen"}
            };
            for (String[] eff : neutralEffects) {
                boolean alreadyInList = land.clearEffects.contains(eff[0]);
                String prefix = alreadyInList ? "§7" : "§e";
                String suffix = alreadyInList ? " §7(已添加)" : "";
                Component btn = Component.text(prefix + "§l[+] " + eff[0] + suffix);
                if (!alreadyInList) {
                    btn = btn.hoverEvent(HoverEvent.showText(Component.text("§e点击添加清除效果: " + eff[0])))
                            .clickEvent(ClickEvent.runCommand("/protect cli effectsclearadd " + landName + " " + eff[0]));
                }
                p.sendMessage(btn);
            }

            // ★ 正面效果（也可以添加到清除列表）
            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§a§l正面效果:"));
            String[][] goodEffects = {
                    {"迅捷", "speed"}, {"急迫", "haste"}, {"力量", "strength"},
                    {"瞬间治疗", "instant_health"}, {"跳跃提升", "jump_boost"}, {"生命恢复", "regeneration"},
                    {"抗性提升", "resistance"}, {"抗火", "fire_resistance"}, {"水下呼吸", "water_breathing"},
                    {"隐身", "invisibility"}, {"夜视", "night_vision"}, {"发光", "glowing"},
                    {"生命提升", "health_boost"}, {"伤害吸收", "absorption"}, {"饱和", "saturation"},
                    {"幸运", "luck"}, {"村庄英雄", "hero_of_the_village"}, {"缓降", "slow_falling"},
                    {"潮涌能量", "conduit_power"}, {"海豚的恩惠", "dolphins_grace"}
            };
            for (String[] eff : goodEffects) {
                boolean alreadyInList = land.clearEffects.contains(eff[0]);
                String prefix = alreadyInList ? "§7" : "§a";
                String suffix = alreadyInList ? " §7(已添加)" : "";
                Component btn = Component.text(prefix + "§l[+] " + eff[0] + suffix);
                if (!alreadyInList) {
                    btn = btn.hoverEvent(HoverEvent.showText(Component.text("§e点击添加清除效果: " + eff[0])))
                            .clickEvent(ClickEvent.runCommand("/protect cli effectsclearadd " + landName + " " + eff[0]));
                }
                p.sendMessage(btn);
            }

            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§a[◀ 返回单清效果列表]")
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 2")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }

        // ========== 子菜单5：选择增益效果（可点击列表） ==========
        else if (subPage == 5) {
            p.sendMessage(header("选择要添加的增益效果: " + landName));
            p.sendMessage(Component.text("§7点击效果名称直接添加（默认等级1，持续300秒）"));
            p.sendMessage(Component.text("§7高级: /protect cli effectsaddadd <效果名> [等级] [秒数]"));

            String[][] giveEffectsList = {
                    {"迅捷", "speed"}, {"急迫", "haste"}, {"力量", "strength"},
                    {"瞬间治疗", "instant_health"}, {"跳跃提升", "jump_boost"}, {"生命恢复", "regeneration"},
                    {"抗性提升", "resistance"}, {"抗火", "fire_resistance"}, {"水下呼吸", "water_breathing"},
                    {"隐身", "invisibility"}, {"夜视", "night_vision"}, {"发光", "glowing"},
                    {"生命提升", "health_boost"}, {"伤害吸收", "absorption"}, {"饱和", "saturation"},
                    {"幸运", "luck"}, {"村庄英雄", "hero_of_the_village"}, {"缓降", "slow_falling"},
                    {"潮涌能量", "conduit_power"}, {"海豚的恩惠", "dolphins_grace"}
            };
            for (String[] eff : giveEffectsList) {
                // 检查是否已存在同名增益
                boolean alreadyExists = false;
                for (String[] ge : land.giveEffects) {
                    if (ge[0].equals(eff[0])) { alreadyExists = true; break; }
                }
                String prefix = alreadyExists ? "§7" : "§a";
                String suffix = alreadyExists ? " §7(已添加)" : "";
                Component btn = Component.text(prefix + "§l[+] " + eff[0] + suffix);
                if (!alreadyExists) {
                    btn = btn.hoverEvent(HoverEvent.showText(Component.text("§e点击添加增益: " + eff[0] + " Lv1 300秒")))
                            .clickEvent(ClickEvent.runCommand("/protect cli effectsaddadd " + landName + " " + eff[0] + " 1 300"));
                }
                p.sendMessage(btn);
            }

            p.sendMessage(Component.text(""));
            p.sendMessage(Component.text("§a[◀ 返回增益效果列表]")
                    .clickEvent(ClickEvent.runCommand("/protect cli effectsmgmt " + landName + " 3")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }
    }

    /**
     * 切换「全清负面效果」开关
     */
    public void toggleClearAllBadEffects(Player p, String landName, String newState) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        if (newState.equalsIgnoreCase("on")) {
            land.clearAllBadEffects = true;
            p.sendMessage(Component.text("§a已开启清除所有负面效果"));
        } else {
            land.clearAllBadEffects = false;
            p.sendMessage(Component.text("§a已关闭清除所有负面效果"));
        }
        // 保存到DB
        areaProtect.saveAreaToDb(land);
    }

    /**
     * ★ 开始编辑增益效果的等级或时长（等待玩家输入）
     */
    public void startEditGiveEffect(Player p, String landName, String indexStr, String editField) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) { p.sendMessage(Component.text("§c领地不存在")); return; }
        int idx;
        try { idx = Integer.parseInt(indexStr); } catch (Exception e) { p.sendMessage(Component.text("§c序号无效")); return; }
        if (idx < 1 || idx > land.giveEffects.size()) { p.sendMessage(Component.text("§c序号超出范围")); return; }
        if (!editField.equals("level") && !editField.equals("duration")) {
            p.sendMessage(Component.text("§c编辑类型必须是 level 或 duration"));
            return;
        }
        // 记录待输入状态到 AreaProtection 的 pendingEffectInput
        String inputType = "editGive_" + editField + "_" + idx;
        areaProtect.setPendingEffectInput(p.getUniqueId(), landName, inputType, 3);
        String fieldLabel = editField.equals("level") ? "等级" : "时长(秒)";
        String[] eff = land.giveEffects.get(idx - 1);
        String current = editField.equals("level") ? (eff.length > 1 ? eff[1] : "1") : (eff.length > 2 ? eff[2] : "300");
        p.sendMessage(Component.text("§e请输入新的" + fieldLabel + "（当前: " + current + "），输入取消"));
        p.sendMessage(Component.text("§7§l───────────────────────────────"));
    }

    /**
     * 切换「禁止所有效果」开关
     */
    public void toggleDenyAllEffects(Player p, String landName, String newState) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        if (newState.equalsIgnoreCase("on")) {
            land.denyAllEffects = true;
            p.sendMessage(Component.text("§a已开启禁止所有效果"));
        } else {
            land.denyAllEffects = false;
            p.sendMessage(Component.text("§a已关闭禁止所有效果"));
        }
        areaProtect.saveAreaToDb(land);
    }

    /**
     * 从单清列表中移除指定效果
     */
    public void removeClearEffect(Player p, String landName, int index) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        if (index < 1 || index > land.clearEffects.size()) {
            p.sendMessage(Component.text("§c索引超出范围"));
            return;
        }
        String removed = land.clearEffects.remove(index - 1);
        p.sendMessage(Component.text("§a已移除清除效果: §f" + removed));
        areaProtect.saveAreaToDb(land);
    }

    /**
     * 向单清列表添加效果名
     */
    public void addClearEffect(Player p, String landName, String effName) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        // 去掉"效果"后缀
        String clean = effName;
        if (clean.endsWith("效果")) clean = clean.substring(0, clean.length() - 2);
        clean = clean.trim();
        if (clean.isEmpty()) {
            p.sendMessage(Component.text("§c效果名不能为空"));
            return;
        }
        // 检查是否已存在
        if (land.clearEffects.contains(clean)) {
            p.sendMessage(Component.text("§c该效果已在清除列表中: §f" + clean));
            return;
        }
        land.clearEffects.add(clean);
        p.sendMessage(Component.text("§a已添加清除效果: §f" + clean));
        areaProtect.saveAreaToDb(land);
    }

    /**
     * 从增益列表中移除指定效果
     */
    public void removeGiveEffect(Player p, String landName, int index) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        if (index < 1 || index > land.giveEffects.size()) {
            p.sendMessage(Component.text("§c索引超出范围"));
            return;
        }
        String[] eff = land.giveEffects.remove(index - 1);
        String desc = eff[0] + (eff.length > 1 ? " Lv" + eff[1] : "");
        p.sendMessage(Component.text("§a已移除增益效果: §f" + desc));
        areaProtect.saveAreaToDb(land);
    }

    /**
     * 向增益列表添加效果
     * 参数: 效果名 [等级] [秒数]
     */
    public void addGiveEffect(Player p, String landName, String effName, String levelStr, String durationStr) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }
        // 验证等级
        int level = 1;
        try { level = Integer.parseInt(levelStr); } catch (Exception ignored) {}
        level = Math.max(1, level);

        // 验证秒数
        int duration = 999;
        try { duration = Integer.parseInt(durationStr); } catch (Exception ignored) {}
        duration = Math.max(1, Math.min(duration, 3600));

        // 检查是否已存在
        for (String[] existing : land.giveEffects) {
            if (existing[0].equalsIgnoreCase(effName)) {
                p.sendMessage(Component.text("§c该效果已存在于增益列表中: §f" + existing[0]));
                return;
            }
        }

        land.giveEffects.add(new String[]{effName, String.valueOf(level), String.valueOf(duration)});
        String desc = effName + " Lv" + level + " " + duration + "秒";
        p.sendMessage(Component.text("§a已添加增益效果: §f" + desc));
        areaProtect.saveAreaToDb(land);
    }

    // ==================== 领地公告管理 ====================

    /**
     * 领地公告管理主页面
     * @param subPage 1=主菜单, 2=编辑进入消息, 3=编辑离开消息
     */
    public void showAnnouncementManagement(Player p, String landName, int subPage) {
        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            return;
        }

        // 权限检查
        boolean isOwner = p.getName().equalsIgnoreCase(land.owner);
        boolean isAdmin = areaProtect.isAreaAdmin(p);
        if (!isOwner && !isAdmin) {
            p.sendMessage(Component.text("§c需要领地所有者或管理员权限"));
            return;
        }

        // ========== 主菜单 ==========
        if (subPage == 1) {
            p.sendMessage(header("领地公告: " + landName));
            p.sendMessage(Component.text("§7配置玩家进出领地时的提示消息"));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));

            // 显示当前进入消息（含变量说明）
            String enterMsg = (land.enterMsg != null && !land.enterMsg.isEmpty()) ? land.enterMsg : "§7（未设置，默认: 欢迎(玩家)来到(领地)）";
            p.sendMessage(Component.text("§a进入消息: §f" + enterMsg));
            p.sendMessage(Component.empty()
                    .append(Component.text("§e[编辑进入消息] ")
                            .hoverEvent(HoverEvent.showText(Component.text("§e点击编辑进入领地时的提示消息")))
                            .clickEvent(ClickEvent.runCommand("/protect cli announcement " + landName + " 2")))
                    .append(Component.text("§7点击编辑")));

            p.sendMessage(Component.text(""));

            // 显示当前离开消息（含变量说明）
            String leaveMsg = (land.leaveMsg != null && !land.leaveMsg.isEmpty()) ? land.leaveMsg : "§7（未设置，默认: 感谢(玩家)光临(领地)）";
            p.sendMessage(Component.text("§a离开消息: §f" + leaveMsg));
            p.sendMessage(Component.empty()
                    .append(Component.text("§e[编辑离开消息] ")
                            .hoverEvent(HoverEvent.showText(Component.text("§e点击编辑离开领地时的提示消息")))
                            .clickEvent(ClickEvent.runCommand("/protect cli announcement " + landName + " 3")))
                    .append(Component.text("§7点击编辑")));

            p.sendMessage(Component.text("§7§l───────────────────────────────"));
            p.sendMessage(Component.text("§7变量: §f(玩家) §7= 玩家名, §f(领地) §7= 领地名"));
            p.sendMessage(Component.text("§7支持括号: §f() [] {} 【】 <> 《》"));
            p.sendMessage(Component.text("§7颜色: §f&a绿色 §6金色 §c红色 §7灰色"));

            // 返回按钮
            p.sendMessage(clickableAction("◀", "返回领地管理", "/protect cli manage " + landName));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
        }

        // ========== 编辑进入消息 ==========
        else if (subPage == 2) {
            p.sendMessage(header("编辑进入消息: " + landName));
            p.sendMessage(Component.text("§7当前进入消息: §f" + (land.enterMsg != null && !land.enterMsg.isEmpty() ? land.enterMsg : "（空）")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
            p.sendMessage(Component.text("§e请在聊天栏输入新的进入消息:"));
            p.sendMessage(Component.text("§7输入 §c清空 §7可清除消息，输入 §c取消 §7或 §c0 §7放弃修改"));

            // 标记等待输入
            pendingEffectInput.put(p.getUniqueId(), new String[]{"announcement_enter", landName, ""});
            savePageInfo(p, "announcement", landName, 2);
        }

        // ========== 编辑离开消息 ==========
        else if (subPage == 3) {
            p.sendMessage(header("编辑离开消息: " + landName));
            p.sendMessage(Component.text("§7当前离开消息: §f" + (land.leaveMsg != null && !land.leaveMsg.isEmpty() ? land.leaveMsg : "（空）")));
            p.sendMessage(Component.text("§7§l───────────────────────────────"));
            p.sendMessage(Component.text("§e请在聊天栏输入新的离开消息:"));
            p.sendMessage(Component.text("§7输入 §c清空 §7可清除消息，输入 §c取消 §7或 §c0 §7放弃修改"));

            // 标记等待输入
            pendingEffectInput.put(p.getUniqueId(), new String[]{"announcement_leave", landName, ""});
            savePageInfo(p, "announcement", landName, 3);
        }
    }

    /**
     * 设置待公告输入状态（GUI调用入口）
     * @param type "announcement_enter" 或 "announcement_leave"
     */
    public void setPendingAnnouncementInput(java.util.UUID uuid, String landName, String type) {
        pendingEffectInput.put(uuid, new String[]{type, landName, ""});
    }

    /**
     * 处理公告消息输入（在ChatEvent中调用）
     */
    public boolean handleAnnouncementInput(Player p, String message) {
        String[] pending = pendingEffectInput.get(p.getUniqueId());
        if (pending == null) return false;

        String type = pending[0];
        String landName = pending[1];

        if (!type.startsWith("announcement_")) return false;

        AreaProtection.AreaConfig land = areaProtect.getLand(landName);
        if (land == null) {
            p.sendMessage(Component.text("§c领地不存在: " + landName));
            pendingEffectInput.remove(p.getUniqueId());
            return true;
        }

        String field = type.equals("announcement_enter") ? "enter_msg" : "leave_msg";
        // ★ 取消/0 = 保持原样放弃修改
        if (message.equals("取消") || message.equals("0")) {
            p.sendMessage(Component.text("§e已取消修改，保持原样"));
            pendingEffectInput.remove(p.getUniqueId());
            showAnnouncementManagement(p, landName, 1); // 返回主菜单，不再进入编辑子页面
            return true;
        }
        String value = message.equals("清空") ? "" : message;

        // 保存到内存
        if (type.equals("announcement_enter")) {
            land.enterMsg = value;
        } else {
            land.leaveMsg = value;
        }

        // 保存到DB
        areaProtect.saveAreaToDb(land);

        p.sendMessage(Component.text("§a已保存" + (type.equals("announcement_enter") ? "进入" : "离开") + "消息: §f" + (value.isEmpty() ? "（已清空）" : value)));
        pendingEffectInput.remove(p.getUniqueId());

        // 返回公告管理页面
        showAnnouncementManagement(p, landName, 1);
        return true;
    }
}
