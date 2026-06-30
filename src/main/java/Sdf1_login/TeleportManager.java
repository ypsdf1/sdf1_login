package Sdf1_login;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传送系统管理器
 * 支持: tpa, tpaccept, tpdeny, tpauto, tpahere, tpaall, tpacancel, 无参tpa(面板)
 * 
 * 架构设计：
 * - 基岩版(通过Geyser)：GUI面板交互，小屏友好
 * - Java版：CLI命令行驱动交互 + 可点击消息按钮
 * - 登录联动：玩家登录时自动开启接受传送
 */
public class TeleportManager {
    private final Main plugin;
    
    // 玩家发出的待处理传送请求 (发件人 → 收件人集合)
    private final Map<String, Set<String>> outgoingRequests = new ConcurrentHashMap<>();
    
    // 玩家收到的待处理传送请求 (收件人 → 发件人集合)
    private final Map<String, Set<String>> incomingRequests = new ConcurrentHashMap<>();
    
    // 自动同意模式玩家
    private final Set<String> autoAcceptPlayers = ConcurrentHashMap.newKeySet();
    
    // 传送冷却时间 (玩家名 → 最后发送时间戳)
    private final Map<String, Long> teleportCooldown = new ConcurrentHashMap<>();
    private static final long TELEPORT_COOLDOWN_MS = 30_000L; // 30秒冷却
    
    // Inventory追踪ID集合（基岩版GUI面板）
    private final Set<String> teleportPanelIds = ConcurrentHashMap.newKeySet();
    
    public TeleportManager(Main plugin) {
        this.plugin = plugin;
    }
    
    // ==================== 判断玩家类型 ====================
    
    /**
     * 判断是否为基岩版玩家（通过Geyser/Floodgate API）
     */
    private boolean isBedrockPlayer(Player player) {
        // 检查 Geyser-Spigot 插件
        Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser");
            if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
                return false;
            }
        }
        
        try {
            Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            java.lang.reflect.Method apiMethod = geyserApiClass.getMethod("api");
            Object geyserApi = apiMethod.invoke(null);
            java.lang.reflect.Method isBedrockMethod = geyserApiClass.getMethod("isBedrockPlayer", java.util.UUID.class);
            return (boolean) isBedrockMethod.invoke(geyserApi, player.getUniqueId());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            // Geyser API 不可用
        }
        
        // 降级：Floodgate API
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            java.lang.reflect.Method instanceMethod = floodgateApiClass.getMethod("getInstance");
            Object floodgateApi = instanceMethod.invoke(null);
            java.lang.reflect.Method isBedrockMethod = floodgateApiClass.getMethod("isFloodgatePlayer", java.util.UUID.class);
            return (boolean) isBedrockMethod.invoke(floodgateApi, player.getUniqueId());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
            // Floodgate API 也不可用
        }
        
        return false;
    }
    
    // ==================== 命令处理入口 ====================
    
    /**
     * 处理传送相关命令
     */
    public boolean handleCommand(Player player, String label, String[] args) {
        String lowerLabel = label.toLowerCase();
        boolean bedrock = isBedrockPlayer(player);
        
        // ★ 无参数直接执行的命令（不走面板）
        switch (lowerLabel) {
            case "tpacancel":
                handleTPCancel(player);
                return true;
            case "tpauto":
                handleTPAuto(player);
                return true;
            case "tpaall":
                return handleTPAll(player);
        }
        
        // ★ 需要面板的命令（tpa无参=打开面板，tpaccept无参=选择接受，tpdeny无参=选择拒绝）
        if (args.length == 0) {
            if (bedrock) {
                openTeleportPanel(player);
            } else {
                openCLITeleportMenu(player);
            }
            return true;
        }
        
        switch (lowerLabel) {
            case "tpa":
                return handleTPA(player, args[0]);
            case "tpaccept":
                return handleTPAccept(player, args);
            case "tpdeny":
                return handleTPDeny(player, args);
            case "tpahere":
                return handleTPAH(player, args[0]);
            default:
                return false;
        }
    }
    
    // ==================== Java版可点击消息工具 ====================
    
    /**
     * 发送带可点击[接受][拒绝]按钮的传送请求通知（仅Java版）
     */
    private void sendClickableRequestNotice(Player target, String senderName) {
        if (isBedrockPlayer(target)) {
            // 基岩版用纯文本（GUI面板操作）
            target.sendMessage("§e[传送] §f" + senderName + " §e请求传送到你身边");
            target.sendMessage("§7────────────────────");
            target.sendMessage("§e【等待接受】§f" + senderName + " §e已传送到你面前");
            target.sendMessage("§7────────────────────");
            target.sendMessage("§a使用 §e/tpaccept " + senderName + " §a接受，§e/tpdeny " + senderName + " §a拒绝");
        } else {
            // Java版：Adventure API 可点击消息
            target.sendMessage(Component.text("§e[传送] §f" + senderName + " §e请求传送到你身边"));
            target.sendMessage(Component.text("§7────────────────────"));
            target.sendMessage(Component.text("§e【等待接受】§f" + senderName + " §e已传送到你面前"));
            target.sendMessage(Component.text("§7────────────────────"));
            
            // 可点击按钮行：[✔ 接受]  [✘ 拒绝]
            target.sendMessage(Component.empty()
                .append(Component.text("§a[✔ 接受] ")
                    .clickEvent(ClickEvent.runCommand("/tpaccept " + senderName))
                    .hoverEvent(HoverEvent.showText(Component.text("点击接受 " + senderName + " 的传送请求"))))
                .append(Component.text("  "))
                .append(Component.text("§c[✘ 拒绝] ")
                    .clickEvent(ClickEvent.runCommand("/tpdeny " + senderName))
                    .hoverEvent(HoverEvent.showText(Component.text("点击拒绝 " + senderName + " 的传送请求")))));
            
            target.sendMessage(Component.text("§7或输入 §e/tpaccept §7查看全部待处理请求"));
        }
    }
    
    /**
     * 发送带可点击按钮的tpahere通知
     */
    private void sendClickableTPAHNotice(Player target, String senderName) {
        if (isBedrockPlayer(target)) {
            target.sendMessage("§e[传送] §f" + senderName + " §e请求你传送到他身边");
            target.sendMessage("§a使用 §e/tpaccept §a接受，§e/tpdeny §a拒绝");
        } else {
            target.sendMessage(Component.text("§e[传送] §f" + senderName + " §e请求你传送到他身边"));
            target.sendMessage(Component.empty()
                .append(Component.text("§a[✔ 接受] ")
                    .clickEvent(ClickEvent.runCommand("/tpaccept " + senderName))
                    .hoverEvent(HoverEvent.showText(Component.text("点击接受 " + senderName + " 的请求"))))
                .append(Component.text("  "))
                .append(Component.text("§c[✘ 拒绝] ")
                    .clickEvent(ClickEvent.runCommand("/tpdeny " + senderName))
                    .hoverEvent(HoverEvent.showText(Component.text("点击拒绝 " + senderName + " 的请求")))));
        }
    }
    
    // ==================== CLI 交互菜单 ====================
    
    /**
     * Java版：打开完整CLI操作面板
     * 功能：查看在线玩家发起传送、查看incoming/outgoing、切换自动接收、全服传送
     */
    private void openCLITeleportMenu(Player player) {
        String playerName = player.getName();
        Set<String> incoming = incomingRequests.get(playerName);
        Set<String> outgoing = outgoingRequests.get(playerName);
        boolean autoAccept = autoAcceptPlayers.contains(playerName);
        
        boolean hasIncoming = incoming != null && !incoming.isEmpty();
        boolean hasOutgoing = outgoing != null && !outgoing.isEmpty();
        
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§6§l══════════ 传送系统 ══════════"));
        player.sendMessage(Component.text(""));
        
        // ── 1. 在线玩家列表（可发起传送） ──
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        List<Player> others = new ArrayList<>();
        for (Player p : onlinePlayers) {
            if (!p.getName().equals(playerName)) others.add(p);
        }
        
        if (!others.isEmpty()) {
            player.sendMessage(Component.text("§e§l📡 在线玩家 (点击发起传送):"));
            for (int i = 0; i < others.size(); i++) {
                Player p = others.get(i);
                String pName = p.getName();
                boolean bedrock = isBedrockPlayer(p);
                String tag = bedrock ? " §7[基岩]" : "";
                
                final int idx = i;
                player.sendMessage(Component.empty()
                    .append(Component.text("  §f" + (idx + 1) + ". §a" + pName + tag + " "))
                    .append(Component.text("§b[传送到他] ")
                        .clickEvent(ClickEvent.runCommand("/tpa " + pName))
                        .hoverEvent(HoverEvent.showText(Component.text("请求传送到 " + pName + " 身边"))))
                    .append(Component.text("§d[他传来] ")
                        .clickEvent(ClickEvent.runCommand("/tpahere " + pName))
                        .hoverEvent(HoverEvent.showText(Component.text("请求 " + pName + " 传送到你身边")))));
            }
            player.sendMessage(Component.text(""));
        } else {
            player.sendMessage(Component.text("§7当前没有其他玩家在线"));
            player.sendMessage(Component.text(""));
        }
        
        // ── 2. 收到的传送请求 ──
        if (hasIncoming) {
            player.sendMessage(Component.text("§a§l📥 收到的传送请求:"));
            int index = 1;
            for (String senderName : incoming) {
                Player senderPlayer = Bukkit.getServer().getPlayer(senderName);
                boolean online = senderPlayer != null && senderPlayer.isOnline();
                String status = online ? "§a在线" : "§c离线";
                
                player.sendMessage(Component.empty()
                    .append(Component.text("  §f" + index + ". §e" + senderName + " §7[" + status + "] "))
                    .append(Component.text("§a[✔接受] ")
                        .clickEvent(ClickEvent.runCommand("/tpaccept " + senderName))
                        .hoverEvent(HoverEvent.showText(Component.text("接受 " + senderName + " 的传送请求"))))
                    .append(Component.text("§c[✘拒绝] ")
                        .clickEvent(ClickEvent.runCommand("/tpdeny " + senderName))
                        .hoverEvent(HoverEvent.showText(Component.text("拒绝 " + senderName + " 的传送请求")))));
                index++;
            }
            player.sendMessage(Component.text(""));
        }
        
        // ── 3. 已发出的传送请求 ──
        if (hasOutgoing) {
            player.sendMessage(Component.text("§e§l📤 已发出的传送请求:"));
            int index = 1;
            for (String targetName : outgoing) {
                Player targetPlayer = Bukkit.getServer().getPlayer(targetName);
                boolean online = targetPlayer != null && targetPlayer.isOnline();
                String status = online ? "§a在线" : "§c离线";
                player.sendMessage(Component.empty()
                    .append(Component.text("  §f" + index + ". §e" + targetName + " §7[" + status + "] §7等待接受")));
                index++;
            }
            player.sendMessage(Component.text(""));
        }
        
        // ── 4. 操作按钮区 ──
        player.sendMessage(Component.text("§7§l快捷操作:"));
        
        // 全服传送
        player.sendMessage(Component.empty()
            .append(Component.text("  §e/tpaall "))
            .append(Component.text("§b[请求全服传送到你身边] ")
                .clickEvent(ClickEvent.runCommand("/tpaall"))
                .hoverEvent(HoverEvent.showText(Component.text("向所有在线玩家发送传送请求")))));
        
        // 自动接受开关
        String autoStatus = autoAccept ? "§a已开启" : "§c已关闭";
        player.sendMessage(Component.empty()
            .append(Component.text("  §e/tpauto "))
            .append(Component.text(autoStatus + " §b[切换自动接受] ")
                .clickEvent(ClickEvent.runCommand("/tpauto"))
                .hoverEvent(HoverEvent.showText(Component.text(autoAccept ? "点击关闭自动接受传送" : "点击开启自动接受传送")))));
        
        // 取消已发出的请求
        if (hasOutgoing) {
            player.sendMessage(Component.empty()
                .append(Component.text("  §e/tpacancel "))
                .append(Component.text("§b[取消所有已发出的请求] ")
                    .clickEvent(ClickEvent.runCommand("/tpacancel"))
                    .hoverEvent(HoverEvent.showText(Component.text("取消所有你发出的传送请求")))));
        }
        
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§6§l══════════════════════════════════════"));
        player.sendMessage(Component.text(""));
    }
    
    /**
     * CLI版：接受指定序号的传送请求
     */
    private boolean handleTPAcceptCLI(Player player, int index) {
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || senders.isEmpty()) {
            player.sendMessage("§c[传送] 你没有待处理的传送请求");
            return true;
        }
        
        List<String> sendersList = new ArrayList<>(senders);
        if (index < 1 || index > sendersList.size()) {
            player.sendMessage("§c[传送] 无效的序号 " + index + "（共有 " + sendersList.size() + " 个请求）");
            openCLITeleportMenu(player);
            return true;
        }
        
        String targetName = sendersList.get(index - 1);
        return handleTPAccept(player, new String[]{targetName});
    }
    
    /**
     * CLI版：拒绝指定序号的传送请求
     */
    private boolean handleTPDenyCLI(Player player, int index) {
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || senders.isEmpty()) {
            player.sendMessage("§c[传送] 你没有待处理的传送请求");
            return true;
        }
        
        List<String> sendersList = new ArrayList<>(senders);
        if (index < 1 || index > sendersList.size()) {
            player.sendMessage("§c[传送] 无效的序号 " + index + "（共有 " + sendersList.size() + " 个请求）");
            openCLITeleportMenu(player);
            return true;
        }
        
        String targetName = sendersList.get(index - 1);
        return handleTPDeny(player, new String[]{targetName});
    }
    
    // ==================== TPA - 传送到目标玩家 ====================
    
    private boolean handleTPA(Player player, String targetName) {
        Player target = Bukkit.getServer().getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§c[传送] 玩家 §f" + targetName + " §c不在线");
            return true;
        }
        
        if (target.equals(player)) {
            player.sendMessage("§c[传送] 不能传送到自己");
            return true;
        }
        
        // 检查冷却
        String sender = player.getName();
        long now = System.currentTimeMillis();
        Long lastTime = teleportCooldown.get(sender);
        if (lastTime != null && now - lastTime < TELEPORT_COOLDOWN_MS) {
            long remaining = (TELEPORT_COOLDOWN_MS - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        // 存储到数据库
        saveTeleportRequest(sender, target.getName(), "tpa");
        
        // 添加到内存
        outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(target.getName());
        incomingRequests.computeIfAbsent(target.getName(), k -> ConcurrentHashMap.newKeySet()).add(sender);
        
        // 通知发送者
        player.sendMessage("§a[传送] 已向 §f" + targetName + " §a发送传送请求");
        player.sendMessage("§7等待对方 §e/tpaccept §7或 §e/tpdeny §7处理");
        
        // 通知接收者（可点击消息）
        sendClickableRequestNotice(target, sender);
        
        // 自动接受检查
        checkAutoAccept(targetName, sender);
        
        return true;
    }
    
    // ==================== 自动接受检测 ====================
    
    private void checkAutoAccept(String targetName, String senderName) {
        if (autoAcceptPlayers.contains(targetName)) {
            Player target = Bukkit.getServer().getPlayer(targetName);
            Player sender = Bukkit.getServer().getPlayer(senderName);
            if (target != null && target.isOnline() && sender != null && sender.isOnline()) {
                executeTeleport(sender, target);
                plugin.getLogger().info("[传送] 玩家 " + targetName + " 开启了自动接受，已自动传送");
                sender.sendMessage("§a[传送] §f" + targetName + " §a已自动接受传送请求");
            }
            // 清理请求
            removeIncomingRequest(targetName, senderName);
            removeOutgoingRequest(senderName, targetName);
        }
    }
    
    // ==================== TPACCEPT - 接受传送 ====================
    
    private boolean handleTPAccept(Player player, String[] args) {
        String targetName = null;
        if (args.length > 0) {
            String arg = args[0];
            try {
                int index = Integer.parseInt(arg);
                return handleTPAcceptCLI(player, index);
            } catch (NumberFormatException e) {
                targetName = arg;
            }
        }
        
        if (targetName == null || targetName.isEmpty()) {
            Set<String> senders = incomingRequests.get(player.getName());
            if (senders == null || senders.isEmpty()) {
                player.sendMessage("§c[传送] 你没有待处理的传送请求");
                return true;
            }
            if (senders.size() == 1) {
                targetName = senders.iterator().next();
            } else {
                if (isBedrockPlayer(player)) {
                    openTeleportPanel(player);
                } else {
                    openCLITeleportMenu(player);
                }
                return true;
            }
        }
        
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || !senders.contains(targetName)) {
            player.sendMessage("§c[传送] 没有找到来自 §f" + targetName + " §c的传送请求");
            return true;
        }
        
        Player senderPlayer = Bukkit.getServer().getPlayer(targetName);
        if (senderPlayer == null || !senderPlayer.isOnline()) {
            player.sendMessage("§c[传送] 玩家 §f" + targetName + " §c已下线");
            removeIncomingRequest(player.getName(), targetName);
            removeOutgoingRequest(targetName, player.getName());
            return true;
        }
        
        // 执行传送
        executeTeleport(senderPlayer, player);
        
        // 清理请求
        removeIncomingRequest(player.getName(), targetName);
        removeOutgoingRequest(targetName, player.getName());
        
        player.sendMessage("§a[传送] 已传送到 §f" + targetName + " §a身边");
        senderPlayer.sendMessage("§a[传送] §f" + player.getName() + " §a已接受请求");
        
        // 清理冷却
        teleportCooldown.remove(senderPlayer.getName());
        
        return true;
    }
    
    // ==================== TPDENY - 拒绝传送 ====================
    
    private boolean handleTPDeny(Player player, String[] args) {
        String targetName = null;
        if (args.length > 0) {
            String arg = args[0];
            try {
                int index = Integer.parseInt(arg);
                return handleTPDenyCLI(player, index);
            } catch (NumberFormatException e) {
                targetName = arg;
            }
        }
        
        if (targetName == null || targetName.isEmpty()) {
            Set<String> senders = incomingRequests.get(player.getName());
            if (senders == null || senders.isEmpty()) {
                player.sendMessage("§c[传送] 你没有待处理的传送请求");
                return true;
            }
            if (senders.size() == 1) {
                targetName = senders.iterator().next();
            } else {
                if (isBedrockPlayer(player)) {
                    openTeleportPanel(player);
                } else {
                    openCLITeleportMenu(player);
                }
                return true;
            }
        }
        
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || !senders.contains(targetName)) {
            player.sendMessage("§c[传送] 没有找到来自 §f" + targetName + " §c的传送请求");
            return true;
        }
        
        Player senderPlayer = Bukkit.getServer().getPlayer(targetName);
        if (senderPlayer != null && senderPlayer.isOnline()) {
            senderPlayer.sendMessage("§c[传送] §f" + player.getName() + " §c拒绝了你的传送请求");
        }
        
        removeIncomingRequest(player.getName(), targetName);
        removeOutgoingRequest(targetName, player.getName());
        
        player.sendMessage("§6[传送] 已拒绝来自 §f" + targetName + " §c的传送请求");
        
        return true;
    }
    
    // ==================== TPAUTO - 自动接受传送 ====================
    
    private void handleTPAuto(Player player) {
        if (autoAcceptPlayers.contains(player.getName())) {
            autoAcceptPlayers.remove(player.getName());
            player.sendMessage("§a[传送] 已关闭自动接受传送请求");
        } else {
            autoAcceptPlayers.add(player.getName());
            player.sendMessage("§a[传送] 已开启自动接受传送请求（新加入的玩家默认开启）");
        }
    }
    
    // ==================== TPAHERE - 请求传送到自己身边 ====================
    
    private boolean handleTPAH(Player player, String targetName) {
        Player target = Bukkit.getServer().getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage("§c[传送] 玩家 §f" + targetName + " §c不在线");
            return true;
        }
        
        if (target.equals(player)) {
            player.sendMessage("§c[传送] 不能请求传送到自己身边");
            return true;
        }
        
        // 检查冷却
        String sender = player.getName();
        long now = System.currentTimeMillis();
        Long lastTime = teleportCooldown.get(sender);
        if (lastTime != null && now - lastTime < TELEPORT_COOLDOWN_MS) {
            long remaining = (TELEPORT_COOLDOWN_MS - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        // 存储到数据库
        saveTeleportRequest(sender, target.getName(), "tpahere");
        
        // 添加到内存
        outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(target.getName());
        incomingRequests.computeIfAbsent(target.getName(), k -> ConcurrentHashMap.newKeySet()).add(sender);
        
        player.sendMessage("§a[传送] 已向 §f" + targetName + " §a发送传送到身边的请求");
        player.sendMessage("§7等待对方 §e/tpaccept §7或 §e/tpdeny §7处理");
        
        // 可点击通知
        sendClickableTPAHNotice(target, sender);
        
        // 自动接受检查
        checkAutoAccept(target.getName(), sender);
        
        return true;
    }
    
    // ==================== TPAALL - 请求全服玩家传送 ====================
    
    private boolean handleTPAll(Player player) {
        int count = 0;
        String sender = player.getName();
        long now = System.currentTimeMillis();
        
        // 检查冷却
        Long lastTime = teleportCooldown.get(sender);
        if (lastTime != null && now - lastTime < TELEPORT_COOLDOWN_MS) {
            long remaining = (TELEPORT_COOLDOWN_MS - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        for (Player p : onlinePlayers) {
            String name = p.getName();
            if (name.equalsIgnoreCase(sender)) continue;
            
            // 存储到数据库
            saveTeleportRequest(sender, name, "tpaall");
            
            // 添加到内存
            outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(name);
            incomingRequests.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(sender);
            
            // 可点击通知
            sendClickableRequestNotice(p, sender);
            
            count++;
            
            // 自动接受检查
            checkAutoAccept(name, sender);
        }
        
        player.sendMessage("§a[传送] 已向 §e" + count + " §a名玩家发送传送请求");
        return true;
    }
    
    // ==================== TPACANCEL - 取消已发出的请求 ====================
    
    private void handleTPCancel(Player player) {
        String sender = player.getName();
        Set<String> recipients = outgoingRequests.remove(sender);
        
        if (recipients == null || recipients.isEmpty()) {
            player.sendMessage("§c[传送] 你没有待处理的发送请求");
            return;
        }
        
        for (String recipient : recipients) {
            Set<String> senders = incomingRequests.get(recipient);
            if (senders != null) {
                senders.remove(sender);
                if (senders.isEmpty()) {
                    incomingRequests.remove(recipient);
                }
            }
            
            Player recipientPlayer = Bukkit.getServer().getPlayer(recipient);
            if (recipientPlayer != null && recipientPlayer.isOnline()) {
                recipientPlayer.sendMessage("§c[传送] §f" + sender + " §c取消了传送请求");
            }
        }
        
        player.sendMessage("§a[传送] 已取消 §e" + recipients.size() + " §a个发送的传送请求");
        teleportCooldown.remove(sender);
    }
    
    // ==================== 执行传送 ====================
    
    private void executeTeleport(Player from, Player to) {
        Location loc = to.getLocation();
        loc.setX(loc.getX() + 1.5);
        loc.setY(loc.getY());
        loc.setZ(loc.getZ());
        
        from.teleport(loc);
        from.playSound(from.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        from.getWorld().playEffect(from.getLocation(), Effect.ENDER_SIGNAL, 0);
    }
    
    // ==================== 传送面板 (GUI) ====================
    // 仅基岩版玩家使用
    
    /**
     * 基岩版GUI面板：完整操作（在线玩家传送、incoming接受/拒绝、自动接收开关、全服传送）
     */
    private void openTeleportPanel(Player player) {
        String playerName = player.getName();
        Set<String> incoming = incomingRequests.get(playerName);
        boolean autoAccept = autoAcceptPlayers.contains(playerName);
        
        // 行数：在线玩家 + incoming + 功能按钮
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        List<Player> others = new ArrayList<>();
        for (Player p : onlinePlayers) {
            if (!p.getName().equals(playerName)) others.add(p);
        }
        
        int neededSlots = others.size() + (incoming != null ? incoming.size() : 0) + 4; // +4 for functional buttons
        int rows = Math.max(1, Math.min((neededSlots + 8) / 9, 6));
        int totalSlots = rows * 9;
        
        String panelId = "tp_gui_" + System.currentTimeMillis() + "_" + player.getUniqueId();
        teleportPanelIds.add(panelId);
        
        Inventory inv = Bukkit.createInventory(null, totalSlots, "§6§l传送系统");
        
        // 玻璃板填充
        ItemStack glass = createGlassPane();
        for (int i = 0; i < totalSlots; i++) {
            inv.setItem(i, glass);
        }
        
        int slot = 0;
        
        // ── 在线玩家区（左键tpa，右键tpahere） ──
        if (!others.isEmpty()) {
            for (Player p : others) {
                if (slot >= totalSlots - 9) break; // 留最后1行给功能按钮
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                ItemMeta meta = head.getItemMeta();
                if (meta != null) {
                    boolean bedrock = isBedrockPlayer(p);
                    meta.setDisplayName("§e" + p.getName() + (bedrock ? " §7[基岩]" : " §7[Java]"));
                    meta.setLore(Arrays.asList(
                        "",
                        "§a左键: 请求传送到他身边",
                        "§d右键: 请求他传送到你身边"
                    ));
                    head.setItemMeta(meta);
                }
                inv.setItem(slot, head);
                slot++;
            }
        }
        
        // ── Incoming请求区 ──
        if (incoming != null && !incoming.isEmpty()) {
            for (String senderName : incoming) {
                if (slot >= totalSlots - 9) break;
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                ItemMeta meta = head.getItemMeta();
                if (meta != null) {
                    Player senderPlayer = Bukkit.getServer().getPlayer(senderName);
                    boolean online = senderPlayer != null && senderPlayer.isOnline();
                    meta.setDisplayName("§a📥 " + senderName + (online ? " §7[在线]" : " §7[离线]"));
                    meta.setLore(Arrays.asList(
                        "",
                        "§a左键: 接受传送",
                        "§c右键: 拒绝传送"
                    ));
                    head.setItemMeta(meta);
                }
                inv.setItem(slot, head);
                slot++;
            }
        }
        
        // ── 功能按钮区（最后1行） ──
        int funcStart = totalSlots - 9;
        
        // 自动接收开关
        ItemStack autoItem = new ItemStack(autoAccept ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta autoMeta = autoItem.getItemMeta();
        if (autoMeta != null) {
            autoMeta.setDisplayName(autoAccept ? "§a自动接收: 已开启" : "§c自动接收: 已关闭");
            autoMeta.setLore(Arrays.asList("§7点击切换"));
            autoItem.setItemMeta(autoMeta);
        }
        inv.setItem(funcStart, autoItem);
        
        // 全服传送
        ItemStack tpaAllItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta tpaAllMeta = tpaAllItem.getItemMeta();
        if (tpaAllMeta != null) {
            tpaAllMeta.setDisplayName("§b全服传送");
            tpaAllMeta.setLore(Arrays.asList("§7请求所有玩家传送到你身边"));
            tpaAllItem.setItemMeta(tpaAllMeta);
        }
        inv.setItem(funcStart + 1, tpaAllItem);
        
        // 取消已发出的请求
        Set<String> outgoing = outgoingRequests.get(playerName);
        boolean hasOutgoing = outgoing != null && !outgoing.isEmpty();
        ItemStack cancelItem = new ItemStack(hasOutgoing ? Material.BARRIER : Material.GRAY_DYE);
        ItemMeta cancelMeta = cancelItem.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(hasOutgoing ? "§c取消已发出的请求" : "§7暂无已发出的请求");
            if (hasOutgoing) {
                cancelMeta.setLore(Arrays.asList("§7取消所有你发出的传送请求"));
            }
            cancelItem.setItemMeta(cancelMeta);
        }
        inv.setItem(funcStart + 2, cancelItem);
        
        // 关闭面板
        ItemStack close = new ItemStack(Material.ARROW);
        ItemMeta closeMeta = close.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§7关闭面板");
            close.setItemMeta(closeMeta);
        }
        inv.setItem(totalSlots - 1, close);
        
        player.openInventory(inv);
    }
    
    // ==================== GUI点击处理 ====================
    
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!"§6§l传送系统".equals(event.getView().getTitle())) {
            return;
        }
        
        Inventory panel = event.getInventory();
        if (panel == null) return;
        
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int totalSize = panel.getSize();
        
        // 关闭面板（右下角）
        if (slot == totalSize - 1) {
            player.closeInventory();
            return;
        }
        
        // 功能按钮区（最后1行的前几个）
        int funcStart = totalSize - 9;
        if (slot >= funcStart && slot < funcStart + 3) {
            if (slot == funcStart) {
                // 自动接收开关
                handleTPAuto(player);
                player.closeInventory();
                // 重新打开面板刷新状态
                openTeleportPanel(player);
            } else if (slot == funcStart + 1) {
                // 全服传送
                handleTPAll(player);
                player.closeInventory();
            } else if (slot == funcStart + 2) {
                // 取消已发出的请求
                handleTPCancel(player);
                player.closeInventory();
                openTeleportPanel(player);
            }
            return;
        }
        
        // 玩家头颅点击
        if (slot >= 0 && slot < funcStart) {
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType() != Material.PLAYER_HEAD) return;
            
            ItemMeta meta = item.getItemMeta();
            String displayName = (meta != null && meta.hasDisplayName()) ? meta.getDisplayName() : "";
            
            // 提取玩家名（从显示名中解析）
            String pName = extractPlayerName(displayName);
            if (pName == null || pName.equals(player.getName())) return;
            
            if (displayName.contains("§a📥")) {
                // Incoming请求：左键接受，右键拒绝
                if (event.isLeftClick()) {
                    handleTPAccept(player, new String[]{pName});
                } else if (event.isRightClick()) {
                    handleTPDeny(player, new String[]{pName});
                }
            } else {
                // 在线玩家：左键tpa（传送到他），右键tpahere（他传来）
                if (event.isLeftClick()) {
                    handleTPA(player, pName);
                } else if (event.isRightClick()) {
                    handleTPAH(player, pName);
                }
            }
            
            player.closeInventory();
            openTeleportPanel(player);
        }
    }
    
    /**
     * 从GUI显示名中提取玩家名
     */
    private String extractPlayerName(String displayName) {
        // 格式: "§e玩家名 §7[Java]" 或 "§a📥 玩家名 §7[在线]"
        String cleaned = displayName
            .replace("§e", "").replace("§a", "").replace("§7", "")
            .replace("§d", "").replace("§b", "")
            .replace("📥 ", "")
            .replace(" [Java]", "").replace(" [基岩]", "")
            .replace(" [在线]", "").replace(" [离线]", "")
            .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
    
    private void denyAllRequests(Player player) {
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || senders.isEmpty()) {
            player.sendMessage("§c[传送] 没有待处理的请求");
            return;
        }
        
        for (String senderName : senders) {
            Player senderPlayer = Bukkit.getServer().getPlayer(senderName);
            if (senderPlayer != null && senderPlayer.isOnline()) {
                senderPlayer.sendMessage("§c[传送] §f" + player.getName() + " §c拒绝了所有传送请求");
            }
        }
        
        incomingRequests.remove(player.getName());
        for (String senderName : senders) {
            Set<String> recipients = outgoingRequests.get(senderName);
            if (recipients != null) {
                recipients.remove(player.getName());
                if (recipients.isEmpty()) {
                    outgoingRequests.remove(senderName);
                }
            }
        }
        
        player.sendMessage("§a[传送] 已拒绝所有传送请求");
    }
    
    // ==================== 数据库操作 ====================
    
    private void saveTeleportRequest(String sender, String receiver, String type) {
        try {
            PreparedStatement ps = plugin.getDb().getDb().prepareStatement(
                "INSERT INTO teleport_requests " +
                "(sender, receiver, type, timestamp) VALUES (?, ?, ?, ?)"
            );
            ps.setString(1, sender);
            ps.setString(2, receiver);
            ps.setString(3, type);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) {
            // 静默失败
        }
    }
    
    // ==================== 内存管理 ====================
    
    private void removeIncomingRequest(String receiver, String sender) {
        Set<String> senders = incomingRequests.get(receiver);
        if (senders != null) {
            senders.remove(sender);
            if (senders.isEmpty()) {
                incomingRequests.remove(receiver);
            }
        }
    }
    
    private void removeOutgoingRequest(String sender, String receiver) {
        Set<String> recipients = outgoingRequests.get(sender);
        if (recipients != null) {
            recipients.remove(receiver);
            if (recipients.isEmpty()) {
                outgoingRequests.remove(sender);
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private ItemStack createGlassPane() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            glass.setItemMeta(meta);
        }
        return glass;
    }
    
    // ==================== 登录联动 ====================
    
    /**
     * 玩家登录时调用
     * @param player Player对象（用于检测是否基岩版）
     * 
     * 基岩版玩家通过Geyser加入，免登录，跳过自动接收传送
     */
    public void onPlayerLogin(Player player) {
        if (player == null) return;
        String playerName = player.getName();
        
        // 基岩版玩家跳过：他们免登录，不需要自动接收传送
        if (isBedrockPlayer(player)) {
            plugin.getLogger().info("[传送] 基岩版玩家 " + playerName + " 登录，跳过自动接收传送");
            return;
        }
        
        autoAcceptPlayers.add(playerName);
        plugin.getLogger().info("[传送] Java版玩家 " + playerName + " 登录，已开启自动接收传送");
    }
    
    public void onPlayerLogout(String playerName) {
        autoAcceptPlayers.remove(playerName);
    }
    
    // ==================== Tab补全辅助 ====================
    
    /**
     * 获取玩家收到的待处理传送请求的发送者列表
     */
    public Set<String> getIncomingSenders(String playerName) {
        Set<String> senders = incomingRequests.get(playerName);
        return senders != null ? senders : Collections.emptySet();
    }
}
