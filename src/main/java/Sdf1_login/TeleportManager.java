package Sdf1_login;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
public class TeleportManager implements Listener {
    private final Main plugin;
    
    // 玩家发出的待处理传送请求 (发件人 → 收件人集合)
    private final Map<String, Set<String>> outgoingRequests = new ConcurrentHashMap<>();
    
    // 玩家收到的待处理传送请求 (收件人 → 发件人集合)
    private final Map<String, Set<String>> incomingRequests = new ConcurrentHashMap<>();
    
    // 自动同意模式玩家
    private final Set<String> autoAcceptPlayers = ConcurrentHashMap.newKeySet();
    
    // 传送冷却时间 (玩家名 → 最后发送时间戳)
    private final Map<String, Long> teleportCooldown = new ConcurrentHashMap<>();

    // 请求过期时间 (请求ID/发送者→ 创建时间戳)，配置化
    private final Map<String, Long> teleportRequestTimes = new ConcurrentHashMap<>();
    private volatile long teleportCooldownMs = 30_000L; // 可配置的冷却时间

    // ★ 管理员配置状态 (玩家名 → 配置状态)
    // 状态值: "valid" = 等待输入有效时间, "interval" = 等待输入间隔时间
    private final Map<String, String> adminConfigState = new ConcurrentHashMap<>();
    
    // Inventory追踪ID集合（基岩版GUI面板）
    private final Set<String> teleportPanelIds = ConcurrentHashMap.newKeySet();
    
    public TeleportManager(Main plugin) {
        this.plugin = plugin;
    }
    
    /**
     * 获取传送请求有效时间（秒），从ConfigManager读取
     */
    private int getTpRequestValidSeconds() {
        if (plugin.getConfigMgr() != null) return plugin.getConfigMgr().tpRequestValidSeconds;
        return 90;
    }
    
    /**
     * 获取传送发送间隔（秒），从ConfigManager读取
     */
    private int getTpSendIntervalSeconds() {
        if (plugin.getConfigMgr() != null) return plugin.getConfigMgr().tpSendIntervalSeconds;
        return 10;
    }
    
    /**
     * 检查传送请求是否过期（由配置决定）
     */
    private boolean isTeleportRequestExpired(String key) {
        Long createTime = teleportRequestTimes.get(key);
        if (createTime == null) return true;
        long elapsed = (System.currentTimeMillis() - createTime) / 1000;
        return elapsed > getTpRequestValidSeconds();
    }
    
    /**
     * 清理已过期的传送请求
     */
    private void cleanupExpiredRequests(String playerName) {
        int validSec = getTpRequestValidSeconds();
        long now = System.currentTimeMillis();
        
        // 清理过期的incoming
        Set<String> incoming = incomingRequests.get(playerName);
        if (incoming != null) {
            incoming.removeIf(sender -> {
                String key = playerName + ":" + sender;
                Long t = teleportRequestTimes.get(key);
                return t != null && (now - t) > (validSec * 1000L);
            });
        }
        
        // 清理过期的outgoing
        Set<String> outgoing = outgoingRequests.get(playerName);
        if (outgoing != null) {
            outgoing.removeIf(target -> {
                String key = playerName + ":" + target + ":out";
                Long t = teleportRequestTimes.get(key);
                return t != null && (now - t) > (validSec * 1000L);
            });
        }
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

        // ★ 进入命令时先清理过期请求
        cleanupExpiredRequests(player.getName());

        // ★ 检查是否处于管理员配置状态
        if (adminConfigState.containsKey(player.getName())) {
            // 将整个命令作为输入处理（如果是空参数，则使用空字符串）
            String input = args.length > 0 ? String.join(" ", args) : "";
            if (handleAdminConfigInput(player, input)) {
                return true;
            }
        }
        
        // ★ 无参数直接执行的命令（不走面板）
        switch (lowerLabel) {
            case "tpacancel":
                handleTPCancel(player);
                return true;
            case "tpauto":
                return handleTPAuto(player, args);
            case "tpaall":
                return handleTPAll(player);
        }
        
        // ★ 需要面板的命令（tpa无参=打开面板，tpaccept无参=选择接受，tpdeny无参=选择拒绝）
        if (args.length == 0) {
            switch (lowerLabel) {
                case "tpa":
                    // 只有/tpa无参才区分Java/基岩面板
                    if (bedrock) {
                        openTeleportPanel(player);
                    } else {
                        openCLITeleportMenu(player);
                    }
                    return true;
                case "tpaccept":
                    return handleTPAccept(player, args);
                case "tpdeny":
                    return handleTPDeny(player, args);
                default:
                    break;
            }
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
        
        // ── 5. 管理员配置区（仅显示给插件管理员） ──
        boolean isAdmin = player.getScoreboardTags().contains(plugin.getConfig2().adminTag);
        if (isAdmin) {
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§6§l🔧 管理员配置"));
            player.sendMessage(Component.text("§7当前配置:"));
            player.sendMessage(Component.text("  §e请求有效时间: §f" + plugin.getConfigMgr().tpRequestValidSeconds + " 秒"));
            player.sendMessage(Component.text("  §e发送间隔: §f" + plugin.getConfigMgr().tpSendIntervalSeconds + " 秒"));
            player.sendMessage(Component.text(""));
            player.sendMessage(Component.text("§7管理命令:"));
            player.sendMessage(Component.empty()
                .append(Component.text("  §e/tpauto show "))
                .append(Component.text("§b[查看当前配置] ")
                    .clickEvent(ClickEvent.runCommand("/tpauto show"))
                    .hoverEvent(HoverEvent.showText(Component.text("查看传送系统全局配置")))));
            player.sendMessage(Component.empty()
                .append(Component.text("  §e/tpauto valid <时间> "))
                .append(Component.text("§b[设置请求有效时间] ")
                    .clickEvent(ClickEvent.runCommand("/tpauto valid "))
                    .hoverEvent(HoverEvent.showText(Component.text("设置请求有效时间，支持: 90 | 1:30 | 一分钟三十秒")))));
            player.sendMessage(Component.empty()
                .append(Component.text("  §e/tpauto interval <时间> "))
                .append(Component.text("§b[设置发送间隔] ")
                    .clickEvent(ClickEvent.runCommand("/tpauto interval "))
                    .hoverEvent(HoverEvent.showText(Component.text("设置发送间隔时间，支持: 10 | 0:10 | 十秒")))));
            player.sendMessage(Component.text(""));
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
        if (lastTime != null && now - lastTime < teleportCooldownMs) {
            long remaining = (teleportCooldownMs - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        // 存储到数据库
        saveTeleportRequest(sender, target.getName(), "tpa");
        
        // 添加到内存
        outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(target.getName());
        incomingRequests.computeIfAbsent(target.getName(), k -> ConcurrentHashMap.newKeySet()).add(sender);
        // 记录请求创建时间
        teleportRequestTimes.put(sender + ":" + target.getName(), now);
        
        // 通知发送者（Java版带撤回按钮，基岩版纯文本）
        if (isBedrockPlayer(player)) {
            player.sendMessage("§a[传送] 已向 §f" + targetName + " §a发送传送请求");
            // 显示请求剩余有效时间
            int validSec = getTpRequestValidSeconds();
            player.sendMessage("§7请求有效 §e" + validSec + " §7秒，间隔 §e" + getTpSendIntervalSeconds() + " §7秒");
            player.sendMessage("§7使用 §e/tpacancel §7撤回请求");
        } else {
            player.sendMessage(Component.empty()
                .append(Component.text("§a[传送] 已向 §f" + targetName + " §a发送传送请求"))
            );
            player.sendMessage(Component.empty()
                .append(Component.text("§7等待对方处理 "))
                .append(Component.text("§c[撤回]")
                    .clickEvent(ClickEvent.runCommand("/tpacancel"))
                    .hoverEvent(HoverEvent.showText(Component.text("点击撤回传送请求")))));
        }
        
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
            // ★ 优先当玩家名处理，只有在纯数字且作为序号能找到时才当CLI处理
            Player possiblePlayer = Bukkit.getServer().getPlayer(arg);
            if (possiblePlayer != null && possiblePlayer.isOnline()) {
                targetName = arg;
            } else {
                try {
                    int index = Integer.parseInt(arg);
                    return handleTPAcceptCLI(player, index);
                } catch (NumberFormatException e) {
                    targetName = arg;
                }
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
            // ★ 优先当玩家名处理，在线则直接走玩家名逻辑
            Player possiblePlayer = Bukkit.getServer().getPlayer(arg);
            if (possiblePlayer != null && possiblePlayer.isOnline()) {
                targetName = arg;
            } else {
                try {
                    int index = Integer.parseInt(arg);
                    return handleTPDenyCLI(player, index);
                } catch (NumberFormatException e) {
                    targetName = arg;
                }
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
    
    private boolean handleTPAuto(Player player, String[] args) {
        String name = player.getName();

        // ★ 识别插件管理员（检查Tag）
        boolean isAdmin = player.getScoreboardTags().contains(plugin.getConfig2().adminTag);

        if (args == null || args.length == 0) {
            // 无参：管理员显示配置菜单，普通玩家切换自动接受
            if (isAdmin) {
                openAdminConfigMenu(player);
                return true;
            } else {
                handleTPAutoToggle(player, name);
                return true;
            }
        }

        // ★ 管理员可以通过CLI参数配置传送参数
        if (isAdmin) {
            // 检查是否为 config 命令
            if ("config".equalsIgnoreCase(args[0])) {
                openAdminConfigMenu(player);
                return true;
            }
            handleTPAdminConfig(player, args);
            return true;
        }

        // 普通玩家有多个参数但非管理员 → 切换
        handleTPAutoToggle(player, name);
        return true;
    }
    
    private void handleTPAutoToggle(Player player, String name) {
        if (autoAcceptPlayers.contains(name)) {
            autoAcceptPlayers.remove(name);
            removeAutoAcceptFromDB(name);
            player.sendMessage("§a[传送] 已关闭自动接受传送请求");
        } else {
            autoAcceptPlayers.add(name);
            saveAutoAcceptToDB(name);
            player.sendMessage("§a[传送] 已开启自动接受传送请求");
        }
    }
    
    /**
     * 管理员通过CLI配置传送参数
     * 支持语法：/tpauto valid 90、/tpauto interval 10、/tpauto valid 1:30、/tpauto interval 一分钟三十秒
     */
    private boolean handleTPAdminConfig(Player player, String[] args) {
        if (args.length != 2) {
            player.sendMessage("§6[传送管理] §7用法:");
            player.sendMessage("§e  /tpauto valid <时间>  — 设置请求有效时间");
            player.sendMessage("§e  /tpauto interval <时间>  — 设置发送间隔");
            player.sendMessage("§e  /tpauto show  — 查看当前配置");
            player.sendMessage("§7支持语法: 90 | 1:30 | 1.30 | 一分钟三十秒 | Ninety | One minute thirty seconds 等");
            return true;
        }
        
        String key = args[0].toLowerCase();
        String val = args[1];
        int seconds = plugin.getConfigMgr().parseIntFromString(val);
        
        if ("valid".equals(key)) {
            plugin.getConfigMgr().tpRequestValidSeconds = seconds;
            plugin.getConfigMgr().saveSettings();
            player.sendMessage("§a[传送管理] 请求有效时间已设置为 §e" + seconds + " 秒");
        } else if ("interval".equals(key)) {
            plugin.getConfigMgr().tpSendIntervalSeconds = seconds;
            teleportCooldownMs = seconds * 1000L;
            plugin.getConfigMgr().saveSettings();
            player.sendMessage("§a[传送管理] 发送间隔已设置为 §e" + seconds + " 秒");
        } else if ("show".equals(key)) {
            player.sendMessage("§6[传送管理] §7当前配置:");
            player.sendMessage("§e  请求有效时间: §f" + plugin.getConfigMgr().tpRequestValidSeconds + " 秒");
            player.sendMessage("§e  发送间隔: §f" + plugin.getConfigMgr().tpSendIntervalSeconds + " 秒");
        } else {
            player.sendMessage("§c[传送管理] 未知参数: " + key);
            player.sendMessage("§e  可用参数: valid, interval, show");
        }
        return true;
    }

    /**
     * 显示管理员配置菜单（交互式）
     */
    private void openAdminConfigMenu(Player player) {
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§6§l══════════ 传送系统配置 ══════════"));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§7当前配置:"));
        player.sendMessage(Component.text("  §e1. 请求有效时间: §f" + plugin.getConfigMgr().tpRequestValidSeconds + " 秒"));
        player.sendMessage(Component.text("  §e2. 发送间隔: §f" + plugin.getConfigMgr().tpSendIntervalSeconds + " 秒"));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§e请选择操作 (输入数字):"));
        player.sendMessage(Component.text("  §a[1] §b设置请求有效时间"));
        player.sendMessage(Component.text("  §a[2] §b设置发送间隔"));
        player.sendMessage(Component.text("  §a[3] §b查看当前配置"));
        player.sendMessage(Component.text("  §a[4] §b退出配置"));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§7或直接输入: §e/tpauto valid <时间> §7或 §e/tpauto interval <时间>"));
        player.sendMessage(Component.text("§7支持语法: 90 | 1:30 | 一分钟三十秒 | Ninety | One minute thirty seconds 等"));
        player.sendMessage(Component.text(""));
        player.sendMessage(Component.text("§6§l══════════════════════════════════════"));
        player.sendMessage(Component.text(""));

        // 设置配置状态，等待玩家输入数字
        adminConfigState.put(player.getName(), "waiting_for_choice");
    }

    /**
     * 处理管理员配置状态下的输入
     * @return true 如果处理了输入，false 如果没有处于配置状态
     */
    private boolean handleAdminConfigInput(Player player, String input) {
        String state = adminConfigState.get(player.getName());
        if (state == null) {
            return false;
        }

        String name = player.getName();
        String trimmedInput = input.trim();

        if ("waiting_for_choice".equals(state)) {
            // 等待选择操作
            if ("1".equals(trimmedInput) || "valid".equalsIgnoreCase(trimmedInput)) {
                adminConfigState.put(name, "waiting_for_valid");
                player.sendMessage("§e[传送管理] 请输入请求有效时间 (支持: 90 | 1:30 | 一分钟三十秒 等):");
                return true;
            } else if ("2".equals(trimmedInput) || "interval".equalsIgnoreCase(trimmedInput)) {
                adminConfigState.put(name, "waiting_for_interval");
                player.sendMessage("§e[传送管理] 请输入发送间隔时间 (支持: 10 | 0:10 | 十秒 等):");
                return true;
            } else if ("3".equals(trimmedInput) || "show".equalsIgnoreCase(trimmedInput)) {
                player.sendMessage("§6[传送管理] §7当前配置:");
                player.sendMessage("§e  请求有效时间: §f" + plugin.getConfigMgr().tpRequestValidSeconds + " 秒");
                player.sendMessage("§e  发送间隔: §f" + plugin.getConfigMgr().tpSendIntervalSeconds + " 秒");
                adminConfigState.remove(name);
                return true;
            } else if ("4".equals(trimmedInput) || "exit".equalsIgnoreCase(trimmedInput) || "quit".equalsIgnoreCase(trimmedInput)) {
                player.sendMessage("§7[传送管理] 已退出配置模式");
                adminConfigState.remove(name);
                return true;
            } else {
                player.sendMessage("§c[传送管理] 无效的选择，请输入 1、2、3 或 4");
                return true;
            }
        } else if ("waiting_for_valid".equals(state)) {
            // 等待输入有效时间
            int seconds = plugin.getConfigMgr().parseIntFromString(trimmedInput);
            if (seconds > 0) {
                plugin.getConfigMgr().tpRequestValidSeconds = seconds;
                plugin.getConfigMgr().saveSettings();
                player.sendMessage("§a[传送管理] 请求有效时间已设置为 §e" + seconds + " 秒");
            } else {
                player.sendMessage("§c[传送管理] 无效的时间格式，请重新输入");
                return true;
            }
            adminConfigState.remove(name);
            return true;
        } else if ("waiting_for_interval".equals(state)) {
            // 等待输入间隔时间
            int seconds = plugin.getConfigMgr().parseIntFromString(trimmedInput);
            if (seconds > 0) {
                plugin.getConfigMgr().tpSendIntervalSeconds = seconds;
                teleportCooldownMs = seconds * 1000L;
                plugin.getConfigMgr().saveSettings();
                player.sendMessage("§a[传送管理] 发送间隔已设置为 §e" + seconds + " 秒");
            } else {
                player.sendMessage("§c[传送管理] 无效的时间格式，请重新输入");
                return true;
            }
            adminConfigState.remove(name);
            return true;
        }

        return false;
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
        if (lastTime != null && now - lastTime < teleportCooldownMs) {
            long remaining = (teleportCooldownMs - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        // 存储到数据库
        saveTeleportRequest(sender, target.getName(), "tpahere");
        
        // 添加到内存
        outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(target.getName());
        incomingRequests.computeIfAbsent(target.getName(), k -> ConcurrentHashMap.newKeySet()).add(sender);
        
        // 通知发送者
        if (isBedrockPlayer(player)) {
            player.sendMessage("§a[传送] 已向 §f" + targetName + " §a发送传送到身边的请求");
            player.sendMessage("§7使用 §e/tpacancel §7撤回请求");
        } else {
            player.sendMessage(Component.empty()
                .append(Component.text("§a[传送] 已向 §f" + targetName + " §a发送传送到身边的请求"))
            );
            player.sendMessage(Component.empty()
                .append(Component.text("§7等待对方处理 "))
                .append(Component.text("§c[撤回]")
                    .clickEvent(ClickEvent.runCommand("/tpacancel"))
                    .hoverEvent(HoverEvent.showText(Component.text("点击撤回传送请求")))));
        }
        
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
        if (lastTime != null && now - lastTime < teleportCooldownMs) {
            long remaining = (teleportCooldownMs - (now - lastTime)) / 1000;
            player.sendMessage("§c[传送] 请等待 §e" + remaining + " §c秒后再发送传送请求");
            return true;
        }
        
        teleportCooldown.put(sender, now);
        
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        for (Player p : onlinePlayers) {
            String name = p.getName();
            if (name.equalsIgnoreCase(sender)) continue;
            
            // ★ tpaall不触发autoAccept，让每位玩家都能手动接受/拒绝
            if (autoAcceptPlayers.contains(name)) {
                // autoAccept玩家：直接传送不发通知
                saveTeleportRequest(sender, name, "tpaall");
                if (p.isOnline() && player.isOnline()) {
                    executeTeleport(player, p);
                    player.sendMessage("§a[传送] §f" + name + " §a已自动接受全服传送");
                    p.sendMessage("§a[传送] §f" + sender + " §a请求全服传送，已自动接受");
                }
                count++;
                continue;
            }
            
            // 存储到数据库
            saveTeleportRequest(sender, name, "tpaall");
            
            // 添加到内存
            outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(name);
            incomingRequests.computeIfAbsent(name, k -> ConcurrentHashMap.newKeySet()).add(sender);
            
            // 可点击通知
            sendClickableRequestNotice(p, sender);
            
            count++;
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
    // 仅基岩版玩家使用 — 三级导航

    /**
     * Level 1 — 主面板（27格）
     * slot 0: 进入玩家列表
     * slot 1: incoming数量
     * slot 3: 自动接收开关
     * slot 4: 全服传送
     * slot 5: 取消已发出请求
     * slot 8: 关闭
     */
    private void openTeleportPanel(Player player) {
        String playerName = player.getName();
        Set<String> incoming = incomingRequests.get(playerName);
        boolean autoAccept = autoAcceptPlayers.contains(playerName);
        int incomingCount = (incoming != null) ? incoming.size() : 0;

        Inventory inv = Bukkit.createInventory(null, 27, "§6§l传送系统");

        // 玻璃板填充
        ItemStack glass = createGlassPane();
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        // slot 0: 玩家列表入口
        ItemStack listBtn = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta listMeta = listBtn.getItemMeta();
        if (listMeta != null) {
            listMeta.setDisplayName("§e§l玩家列表");
            listMeta.setLore(Arrays.asList(
                "",
                "§7点击查看所有在线玩家",
                "§7并发起传送请求"
            ));
            listBtn.setItemMeta(listMeta);
        }
        inv.setItem(0, listBtn);

        // slot 1: incoming请求
        ItemStack incomingBtn = new ItemStack(incomingCount > 0 ? Material.LIME_WOOL : Material.GRAY_WOOL);
        ItemMeta incomingMeta = incomingBtn.getItemMeta();
        if (incomingMeta != null) {
            incomingMeta.setDisplayName("§a📥 待处理请求: " + incomingCount);
            if (incomingCount > 0) {
                List<String> lore = new ArrayList<>();
                lore.add("");
                for (String s : incoming) lore.add("§7• " + s);
                lore.add("");
                lore.add("§a请前往玩家列表处理");
                incomingMeta.setLore(lore);
            } else {
                incomingMeta.setLore(Arrays.asList("§7暂无待处理的传送请求"));
            }
            incomingBtn.setItemMeta(incomingMeta);
        }
        inv.setItem(1, incomingBtn);

        // slot 3: 自动接收开关
        ItemStack autoItem = new ItemStack(autoAccept ? Material.LIME_WOOL : Material.RED_WOOL);
        ItemMeta autoMeta = autoItem.getItemMeta();
        if (autoMeta != null) {
            autoMeta.setDisplayName(autoAccept ? "§a自动接收: 已开启" : "§c自动接收: 已关闭");
            autoMeta.setLore(Arrays.asList("§7点击切换"));
            autoItem.setItemMeta(autoMeta);
        }
        inv.setItem(3, autoItem);

        // slot 4: 全服传送
        ItemStack tpaAllItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta tpaAllMeta = tpaAllItem.getItemMeta();
        if (tpaAllMeta != null) {
            tpaAllMeta.setDisplayName("§b全服传送");
            tpaAllMeta.setLore(Arrays.asList("§7请求所有玩家传送到你身边"));
            tpaAllItem.setItemMeta(tpaAllMeta);
        }
        inv.setItem(4, tpaAllItem);

        // slot 5: 取消已发出请求
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
        inv.setItem(5, cancelItem);

        // slot 8: 关闭
        ItemStack close = new ItemStack(Material.ARROW);
        ItemMeta closeMeta = close.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§7关闭面板");
            close.setItemMeta(closeMeta);
        }
        inv.setItem(8, close);

        player.openInventory(inv);
    }

    /**
     * Level 2 — 玩家列表（27格，25人/页，slot 18和26翻页）
     * slot 0-17, 19-25: 玩家头颅（最多25个）
     * slot 18: 上一页
     * slot 26: 下一页
     */
    private void openPlayerListPanel(Player player, int page) {
        String playerName = player.getName();

        // 收集除自己外的在线玩家
        List<Player> others = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getName().equals(playerName)) others.add(p);
        }

        // 加入incoming请求者（可能已离线但请求仍在）
        Set<String> incoming = incomingRequests.get(playerName);
        Map<String, Boolean> incomingOnlineMap = new HashMap<>();
        if (incoming != null) {
            for (String s : incoming) {
                Player sp = Bukkit.getServer().getPlayer(s);
                incomingOnlineMap.put(s, sp != null && sp.isOnline());
                // 不重复添加已在线的
                boolean alreadyIn = false;
                for (Player op : others) {
                    if (op.getName().equals(s)) { alreadyIn = true; break; }
                }
                // 离线的incoming请求者也加入列表
                if (!alreadyIn) {
                    // 用虚拟方式标记：不加入others，仅在incoming区显示
                }
            }
        }

        int totalPlayers = others.size();
        int perPage = 25;
        int totalPages = Math.max(1, (int) Math.ceil((double) totalPlayers / perPage));
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        Inventory inv = Bukkit.createInventory(null, 27, "§6§l传送系统");

        // 玻璃板填充
        ItemStack glass = createGlassPane();
        for (int i = 0; i < 27; i++) inv.setItem(i, glass);

        // 放置玩家头颅 — slot 0-17, 19-25（跳过18和26）
        int[] headSlots = new int[25];
        int idx = 0;
        for (int s = 0; s < 27; s++) {
            if (s == 18 || s == 26) continue;
            headSlots[idx++] = s;
        }

        int start = page * perPage;
        int end = Math.min(start + perPage, totalPlayers);
        for (int i = start; i < end; i++) {
            Player p = others.get(i);
            int slot = headSlots[i - start];
            boolean bedrock = isBedrockPlayer(p);
            boolean hasIncoming = incoming != null && incoming.contains(p.getName());

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + p.getName() + (bedrock ? " §7[基岩]" : " §7[Java]"));
                List<String> lore = new ArrayList<>();
                lore.add("");
                if (hasIncoming) {
                    lore.add("§a📥 该玩家向你发起了传送请求");
                    lore.add("");
                    lore.add("§a左键: 请求传送到他身边");
                    lore.add("§d右键: 请求他传送到你身边");
                } else {
                    lore.add("§a左键: 请求传送到他身边");
                    lore.add("§d右键: 请求他传送到你身边");
                }
                meta.setLore(lore);
                head.setItemMeta(meta);
            }
            inv.setItem(slot, head);
        }

        // 空位提示
        if (totalPlayers == 0) {
            ItemStack empty = new ItemStack(Material.BARRIER);
            ItemMeta emptyMeta = empty.getItemMeta();
            if (emptyMeta != null) {
                emptyMeta.setDisplayName("§7暂无其他玩家在线");
                empty.setItemMeta(emptyMeta);
            }
            inv.setItem(12, empty);
        }

        // slot 18: 上一页
        ItemStack prevBtn = new ItemStack(page > 0 ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta prevMeta = prevBtn.getItemMeta();
        if (prevMeta != null) {
            prevMeta.setDisplayName(page > 0
                ? "§e§l← 上一页 (§f" + (page + 1) + "§e/§f" + totalPages + "§e)"
                : "§7已经是第一页");
            if (page > 0) prevMeta.setLore(Arrays.asList("§7点击翻到上一页"));
            prevBtn.setItemMeta(prevMeta);
        }
        inv.setItem(18, prevBtn);

        // slot 26: 下一页
        ItemStack nextBtn = new ItemStack(page < totalPages - 1 ? Material.ARROW : Material.GRAY_DYE);
        ItemMeta nextMeta = nextBtn.getItemMeta();
        if (nextMeta != null) {
            nextMeta.setDisplayName(page < totalPages - 1
                ? "§e§l下一页 (§f" + (page + 1) + "§e/§f" + totalPages + "§e) →"
                : "§7已经是最后一页");
            if (page < totalPages - 1) nextMeta.setLore(Arrays.asList("§7点击翻到下一页"));
            nextBtn.setItemMeta(nextMeta);
        }
        inv.setItem(26, nextBtn);

        player.openInventory(inv);
    }

    /**
     * Level 3 — 玩家操作面板（9格）
     * 点击某个玩家后进入
     */
    private void openPlayerActionPanel(Player player, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 9, "§6§l传送系统");

        ItemStack glass = createGlassPane();
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);

        // slot 0: 目标玩家头颅
        Player target = Bukkit.getServer().getPlayer(targetName);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta headMeta = head.getItemMeta();
        if (headMeta != null) {
            boolean bedrock = target != null && isBedrockPlayer(target);
            headMeta.setDisplayName("§e" + targetName + (bedrock ? " §7[基岩]" : " §7[Java]"));
            boolean online = target != null && target.isOnline();
            headMeta.setLore(Arrays.asList(
                "",
                online ? "§a● 在线" : "§c● 离线"
            ));
            head.setItemMeta(headMeta);
        }
        inv.setItem(0, head);

        // 检查是否有来自该玩家的incoming请求
        Set<String> incoming = incomingRequests.get(player.getName());
        boolean hasIncoming = incoming != null && incoming.contains(targetName);

        // slot 3: 请求传送到他身边（左键tpa）
        ItemStack tpaItem = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tpaMeta = tpaItem.getItemMeta();
        if (tpaMeta != null) {
            tpaMeta.setDisplayName("§a请求传送到他身边");
            tpaMeta.setLore(Arrays.asList(
                "§7向 " + targetName + " 发送传送请求",
                "§7请求传送到他的位置"
            ));
            tpaItem.setItemMeta(tpaMeta);
        }
        inv.setItem(3, tpaItem);

        // slot 4: 请求他传送到你身边（右键tpahere）
        ItemStack tpaHereItem = new ItemStack(Material.COMPASS);
        ItemMeta tpaHereMeta = tpaHereItem.getItemMeta();
        if (tpaHereMeta != null) {
            tpaHereMeta.setDisplayName("§d请求他传送到你身边");
            tpaHereMeta.setLore(Arrays.asList(
                "§7向 " + targetName + " 发送传送请求",
                "§7请求他传送到你的位置"
            ));
            tpaHereItem.setItemMeta(tpaHereMeta);
        }
        inv.setItem(4, tpaHereItem);

        // slot 5: 接受/拒绝该玩家的请求（如果有）
        if (hasIncoming) {
            ItemStack acceptItem = new ItemStack(Material.LIME_WOOL);
            ItemMeta acceptMeta = acceptItem.getItemMeta();
            if (acceptMeta != null) {
                acceptMeta.setDisplayName("§a✓ 接受传送");
                acceptMeta.setLore(Arrays.asList(
                    "§7接受 " + targetName + " 的传送请求"
                ));
                acceptItem.setItemMeta(acceptMeta);
            }
            inv.setItem(5, acceptItem);

            ItemStack denyItem = new ItemStack(Material.RED_WOOL);
            ItemMeta denyMeta = denyItem.getItemMeta();
            if (denyMeta != null) {
                denyMeta.setDisplayName("§c✗ 拒绝传送");
                denyMeta.setLore(Arrays.asList(
                    "§7拒绝 " + targetName + " 的传送请求"
                ));
                denyItem.setItemMeta(denyMeta);
            }
            inv.setItem(6, denyItem);
        }

        // slot 7: 返回玩家列表
        ItemStack backBtn = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backBtn.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§7← 返回玩家列表");
            backBtn.setItemMeta(backMeta);
        }
        inv.setItem(7, backBtn);

        // slot 8: 关闭
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta closeMeta = close.getItemMeta();
        if (closeMeta != null) {
            closeMeta.setDisplayName("§7关闭面板");
            close.setItemMeta(closeMeta);
        }
        inv.setItem(8, close);

        player.openInventory(inv);
    }
    
    // ==================== GUI点击处理 ====================
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String viewTitle = event.getView().getTitle();
        if (!"§6§l传送系统".equals(viewTitle)) {
            return;
        }

        Inventory panel = event.getInventory();
        if (panel == null) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        int totalSize = panel.getSize();

        if (slot < 0) return; // 玩家背包区域

        // ── 27格面板：区分 Level 1（主面板）和 Level 2（玩家列表） ──
        if (totalSize == 27) {
            // Level 2 特征：slot 18 是箭头/灰色染料 → 翻页按钮
            ItemStack slot18 = panel.getItem(18);
            if (slot18 != null && (slot18.getType() == Material.ARROW || slot18.getType() == Material.GRAY_DYE)
                    && slot18.hasItemMeta() && slot18.getItemMeta().hasDisplayName()
                    && (slot18.getItemMeta().getDisplayName().contains("上一页") || slot18.getItemMeta().getDisplayName().contains("第一页"))) {
                handlePlayerListClick(event, player, panel, slot);
            } else {
                handleMainPanelClick(event, player, slot);
            }
            return;
        }

        // ── 9格面板：Level 3（玩家操作面板） ──
        if (totalSize == 9) {
            handlePlayerActionClick(event, player, panel, slot);
            return;
        }
    }

    /**
     * Level 1 主面板点击处理
     */
    private void handleMainPanelClick(InventoryClickEvent event, Player player, int slot) {
        switch (slot) {
            case 0: // 进入玩家列表
                event.setCancelled(true);
                openPlayerListPanel(player, 0);
                break;
            case 1: // incoming请求提示（不可操作）
                event.setCancelled(true);
                break;
            case 3: // 自动接收开关
                event.setCancelled(true);
                handleTPAuto(player, new String[]{});
                openTeleportPanel(player);
                break;
            case 4: // 全服传送
                event.setCancelled(true);
                handleTPAll(player);
                player.closeInventory();
                break;
            case 5: // 取消已发出请求
                event.setCancelled(true);
                handleTPCancel(player);
                openTeleportPanel(player);
                break;
            case 8: // 关闭
                event.setCancelled(true);
                player.closeInventory();
                break;
            default:
                event.setCancelled(true);
                break;
        }
    }

    /**
     * Level 2 玩家列表点击处理
     */
    private void handlePlayerListClick(InventoryClickEvent event, Player player, Inventory panel, int slot) {
        event.setCancelled(true);

        // slot 18: 上一页
        if (slot == 18) {
            int currentPage = extractPageFromArrow(panel.getItem(18));
            if (currentPage > 0) {
                openPlayerListPanel(player, currentPage - 1);
            }
            return;
        }

        // slot 26: 下一页
        if (slot == 26) {
            int currentPage = extractPageFromArrow(panel.getItem(18));
            int totalPages = extractTotalPagesFromArrow(panel.getItem(26));
            if (currentPage < totalPages - 1) {
                openPlayerListPanel(player, currentPage + 1);
            }
            return;
        }

        // 其他slot: 玩家头颅
        if (slot >= 0 && slot < 27 && slot != 18 && slot != 26) {
            ItemStack item = panel.getItem(slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD) return;

            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return;
            String displayName = meta.getDisplayName();

            // 排除纯玻璃板/空项
            String pName = extractPlayerName(displayName);
            if (pName == null || pName.equals(player.getName())) return;

            // 进入该玩家的操作面板
            openPlayerActionPanel(player, pName);
        }
    }

    /**
     * Level 3 玩家操作面板点击处理
     */
    private void handlePlayerActionClick(InventoryClickEvent event, Player player, Inventory panel, int slot) {
        event.setCancelled(true);

        // 从 slot 0 的头颅名提取目标玩家
        ItemStack headItem = panel.getItem(0);
        if (headItem == null) return;
        ItemMeta headMeta = headItem.getItemMeta();
        if (headMeta == null || !headMeta.hasDisplayName()) return;
        String targetName = extractPlayerName(headMeta.getDisplayName());
        if (targetName == null) return;

        switch (slot) {
            case 3: // 请求传送到他身边（tpa）
                handleTPA(player, targetName);
                player.closeInventory();
                break;
            case 4: // 请求他传送到你身边（tpahere）
                handleTPAH(player, targetName);
                player.closeInventory();
                break;
            case 5: // 接受传送（incoming）
                handleTPAccept(player, new String[]{targetName});
                player.closeInventory();
                break;
            case 6: // 拒绝传送（incoming）
                handleTPDeny(player, new String[]{targetName});
                player.closeInventory();
                break;
            case 7: // 返回玩家列表
                openPlayerListPanel(player, 0);
                break;
            case 8: // 关闭
                player.closeInventory();
                break;
            default:
                break;
        }
    }

    /**
     * 从翻页箭头的显示名提取当前页码（从1开始）
     * 格式: "§e§l← 上一页 (§f2§e/§f5§e)"
     */
    private int extractPageFromArrow(ItemStack arrow) {
        if (arrow == null || !arrow.hasItemMeta()) return 0;
        ItemMeta meta = arrow.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return 0;
        String name = meta.getDisplayName();
        // 提取第一个括号中的数字: (§f2§e/§f5§e)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(§f(\\d+)§e/§f(\\d+)§e\\)").matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(1)) - 1; // 转为0-indexed
        }
        return 0;
    }

    /**
     * 从下一页箭头提取总页数
     */
    private int extractTotalPagesFromArrow(ItemStack arrow) {
        if (arrow == null || !arrow.hasItemMeta()) return 1;
        ItemMeta meta = arrow.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return 1;
        String name = meta.getDisplayName();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(§f(\\d+)§e/§f(\\d+)§e\\)").matcher(name);
        if (m.find()) {
            return Integer.parseInt(m.group(2));
        }
        return 1;
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
        
        // ★ 从数据库读取持久化的autoAccept设置
        if (isAutoAcceptInDB(playerName)) {
            autoAcceptPlayers.add(playerName);
            plugin.getLogger().info("[传送] Java版玩家 " + playerName + " 登录，自动接收传送=开启(持久化)");
        } else {
            plugin.getLogger().info("[传送] Java版玩家 " + playerName + " 登录，自动接收传送=关闭");
        }
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
    
    // ==================== autoAccept 持久化 ====================
    
    /**
     * 初始化数据库表（在onEnable中调用）
     */
    public void initDatabase() {
        try {
            plugin.getDb().getDb().prepareStatement(
                "CREATE TABLE IF NOT EXISTS teleport_auto_accept (" +
                "player_name TEXT PRIMARY KEY)"
            ).executeUpdate();
            plugin.getLogger().info("[传送] teleport_auto_accept 表就绪");
        } catch (SQLException e) {
            plugin.getLogger().severe("[传送] 初始化teleport_auto_accept失败: " + e.getMessage());
        }
        loadAllAutoAccept();
    }
    
    private void loadAllAutoAccept() {
        try {
            ResultSet rs = plugin.getDb().getDb().prepareStatement(
                "SELECT player_name FROM teleport_auto_accept"
            ).executeQuery();
            int count = 0;
            while (rs.next()) {
                autoAcceptPlayers.add(rs.getString("player_name"));
                count++;
            }
            rs.close();
            plugin.getLogger().info("[传送] 加载 " + count + " 个自动接受传送的玩家");
        } catch (SQLException e) {
            plugin.getLogger().severe("[传送] 加载autoAccept数据失败: " + e.getMessage());
        }
    }
    
    private void saveAutoAcceptToDB(String playerName) {
        try {
            PreparedStatement ps = plugin.getDb().getDb().prepareStatement(
                "INSERT OR REPLACE INTO teleport_auto_accept (player_name) VALUES (?)"
            );
            ps.setString(1, playerName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) { }
    }
    
    private void removeAutoAcceptFromDB(String playerName) {
        try {
            PreparedStatement ps = plugin.getDb().getDb().prepareStatement(
                "DELETE FROM teleport_auto_accept WHERE player_name = ?"
            );
            ps.setString(1, playerName);
            ps.executeUpdate();
            ps.close();
        } catch (SQLException e) { }
    }
    
    private boolean isAutoAcceptInDB(String playerName) {
        try {
            PreparedStatement ps = plugin.getDb().getDb().prepareStatement(
                "SELECT 1 FROM teleport_auto_accept WHERE player_name = ?"
            );
            ps.setString(1, playerName);
            ResultSet rs = ps.executeQuery();
            boolean exists = rs.next();
            rs.close();
            ps.close();
            return exists;
        } catch (SQLException e) {
            return false;
        }
    }
}
