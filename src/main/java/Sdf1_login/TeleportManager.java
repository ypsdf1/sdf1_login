package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

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
 * - Java版：CLI命令行驱动交互，打字更直观
 * - 登录联动：Java玩家登录时自动开启接受传送
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
    
    // Inventory UUIDs currently open as teleport panels
    private final Set<String> teleportPanelIds = ConcurrentHashMap.newKeySet();
    
    // 数据库连接
    public TeleportManager(Main plugin) {
        this.plugin = plugin;
    }
    
    // ==================== 判断玩家类型 ====================
    
    /**
     * 判断是否为基岩版玩家（通过Geyser API）
     * 如果Geyser插件不存在，默认返回false（Java版）
     * 
     * Geyser API 检测顺序：
     * 1. Geyser-Spigot 插件（通过GeyserApi.api()获取实例）
     * 2. 降级为Java版
     */
    private boolean isBedrockPlayer(Player player) {
        // 检查 Geyser 插件是否加载
        Plugin geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
            // 尝试其他可能的插件名称
            geyserPlugin = Bukkit.getPluginManager().getPlugin("Geyser");
            if (geyserPlugin == null || !geyserPlugin.isEnabled()) {
                return false;
            }
        }
        
        try {
            // 使用Geyser API: GeyserApi.api().isBedrockPlayer(UUID)
            Class<?> geyserApiClass = Class.forName("org.geysermc.geyser.api.GeyserApi");
            java.lang.reflect.Method apiMethod = geyserApiClass.getMethod("api");
            Object geyserApi = apiMethod.invoke(null);
            
            java.lang.reflect.Method isBedrockMethod = geyserApiClass.getMethod("isBedrockPlayer", java.util.UUID.class);
            return (boolean) isBedrockMethod.invoke(geyserApi, player.getUniqueId());
        } catch (ClassNotFoundException e) {
            // Geyser API 类未找到，可能是旧版本
            plugin.getLogger().warning("[传送] Geyser API 类未找到: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("[传送] Geyser API 方法未找到: " + e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException e) {
            plugin.getLogger().warning("[传送] Geyser API 调用失败: " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().warning("[传送] Geyser API 检测异常: " + e.getMessage());
        }
        
        // 降级检测：尝试通过Floodgate API（如果Geyser API不可用）
        try {
            Class<?> floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            java.lang.reflect.Method instanceMethod = floodgateApiClass.getMethod("getInstance");
            Object floodgateApi = instanceMethod.invoke(null);
            
            java.lang.reflect.Method isBedrockMethod = floodgateApiClass.getMethod("isFloodgatePlayer", java.util.UUID.class);
            return (boolean) isBedrockMethod.invoke(floodgateApi, player.getUniqueId());
        } catch (ClassNotFoundException e) {
            // Floodgate API 类未找到
        } catch (NoSuchMethodException e) {
            // Floodgate API 方法未找到
        } catch (Exception e) {
            // Floodgate API 调用失败
        }
        
        return false;
    }
    
    // ==================== 命令处理入口 ====================
    
    /**
     * 处理传送相关命令
     * @return true=已处理, false=未处理
     */
    public boolean handleCommand(Player player, String label, String[] args) {
        String lowerLabel = label.toLowerCase();
        
        // ★ 根据玩家类型决定交互方式
        boolean bedrock = isBedrockPlayer(player);
        
        if (args.length == 0) {
            if (bedrock) {
                // 基岩版：打开GUI面板
                openTeleportPanel(player);
            } else {
                // Java版：CLI交互式提示
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
            case "tpauto":
                handleTPAuto(player);
                return true;
            case "tpahere":
                return handleTPAH(player, args[0]);
            case "tpaall":
                return handleTPAll(player);
            case "tpacancel":
                handleTPCancel(player);
                return true;
            default:
                return false;
        }
    }
    
    // ==================== CLI 交互菜单 ====================
    
    /**
     * Java版：打开交互式CLI菜单
     */
    private void openCLITeleportMenu(Player player) {
        String playerName = player.getName();
        Set<String> senders = incomingRequests.get(playerName);
        
        if (senders == null || senders.isEmpty()) {
            player.sendMessage("§a[传送] 你没有任何待处理的传送请求");
            return;
        }
        
        player.sendMessage("§6§l========== 待处理传送请求 ==========");
        int index = 1;
        for (String senderName : senders) {
            Player senderPlayer = Bukkit.getServer().getPlayer(senderName);
            boolean online = senderPlayer != null && senderPlayer.isOnline();
            String status = online ? "§a[在线]" : "§c[离线]";
            player.sendMessage("§e" + index + ". §f" + senderName + " §7" + status);
            index++;
        }
        player.sendMessage("");
        player.sendMessage("§7输入指令:");
        player.sendMessage("§e/tpaccept <序号>     §7接受指定玩家的传送请求");
        player.sendMessage("§e/tpdeny <序号>       §7拒绝指定玩家的传送请求");
        player.sendMessage("§e/tpacancel           §7取消你发出的所有请求");
        player.sendMessage("§6§l======================================");
    }
    
    /**
     * CLI版：接受指定序号的传送请求
     * 内部调用，支持序号或玩家名
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
        
        // 记录发送时间
        teleportCooldown.put(sender, now);
        
        // 存储传送请求到数据库
        saveTeleportRequest(sender, target.getName(), "tpa");
        
        // 添加到内存
        outgoingRequests.computeIfAbsent(sender, k -> ConcurrentHashMap.newKeySet()).add(target.getName());
        incomingRequests.computeIfAbsent(target.getName(), k -> ConcurrentHashMap.newKeySet()).add(sender);
        
        // 通知发送者
        player.sendMessage("§a[传送] 已向 §f" + targetName + " §a发送传送请求");
        
        // ★ 通知接收者：A传送了B，等待B同意
        plugin.getLogger().info("[传送] 向玩家 " + targetName + " 发送传送请求消息");
        target.sendMessage("§e[传送] §f" + sender + " §e请求传送到你身边");
        target.sendMessage("§7────────────────────");
        target.sendMessage("§e【等待接受】§f" + sender + " §e已传送到你面前");
        target.sendMessage("§7────────────────────");
        target.sendMessage("§a使用 §e/tpaccept " + sender + " §a接受，§e/tpdeny " + sender + " §a拒绝");
        
        // 如果是Java版，提示目标玩家如何CLI接受
        if (!isBedrockPlayer(target)) {
            target.sendMessage("§7（输入 §e/tpaccept §7接受所有待处理请求）");
        }
        plugin.getLogger().info("[传送] 已向玩家 " + targetName + " 发送所有消息");
        
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
            }
            // 清理请求（自动接受了）
            removeIncomingRequest(targetName, senderName);
            removeOutgoingRequest(senderName, targetName);
        }
    }
    
    // ==================== TPACCEPT - 接受传送 ====================
    
    private boolean handleTPAccept(Player player, String[] args) {
        String targetName = null;
        if (args.length > 0) {
            // 支持序号或玩家名
            String arg = args[0];
            try {
                int index = Integer.parseInt(arg);
                // CLI模式：使用序号
                return handleTPAcceptCLI(player, index);
            } catch (NumberFormatException e) {
                // 玩家名字符串
                targetName = arg;
            }
        }
        
        if (targetName == null || targetName.isEmpty()) {
            // 有多个待处理请求 → 打开面板
            Set<String> senders = incomingRequests.get(player.getName());
            if (senders == null || senders.isEmpty()) {
                player.sendMessage("§c[传送] 你没有待处理的传送请求");
                return true;
            }
            if (senders.size() == 1) {
                targetName = senders.iterator().next();
            } else {
                // 基岩版打开GUI，Java版打开CLI菜单
                if (isBedrockPlayer(player)) {
                    openTeleportPanel(player);
                } else {
                    openCLITeleportMenu(player);
                }
                return true;
            }
        }
        
        // 验证请求是否存在
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
            // 支持序号或玩家名
            String arg = args[0];
            try {
                int index = Integer.parseInt(arg);
                return handleTPDenyCLI(player, index);
            } catch (NumberFormatException e) {
                targetName = arg;
            }
        }
        
        if (targetName == null || targetName.isEmpty()) {
            // 有多个待处理请求 → 打开面板
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
        
        // 验证请求是否存在
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || !senders.contains(targetName)) {
            player.sendMessage("§c[传送] 没有找到来自 §f" + targetName + " §c的传送请求");
            return true;
        }
        
        Player senderPlayer = Bukkit.getServer().getPlayer(targetName);
        if (senderPlayer != null && senderPlayer.isOnline()) {
            senderPlayer.sendMessage("§c[传送] §f" + player.getName() + " §c拒绝了你的传送请求");
        }
        
        // 清理请求
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
            player.sendMessage("§a[传送] 已开启自动接受传送请求");
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
        target.sendMessage("§e[传送] §f" + sender + " §e请求你传送到他身边");
        target.sendMessage("§a使用 §e/tpaccept §a接受，§e/tpdeny §a拒绝");
        
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
            
            p.sendMessage("§e[传送] §f" + sender + " §e请求全服玩家传送到他身边");
            p.sendMessage("§a使用 §e/tpaccept §a接受，§e/tpdeny §a拒绝");
            
            count++;
            
            // 自动接受检查
            if (autoAcceptPlayers.contains(name)) {
                checkAutoAccept(name, sender);
            }
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
            // 从收件人的待处理列表中移除
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
        
        player.sendMessage("§a[传送] 已取消所有发送的传送请求");
        
        // 清理冷却
        teleportCooldown.remove(sender);
    }
    
    // ==================== 执行传送 ====================
    
    private void executeTeleport(Player from, Player to) {
        // 传送到目标玩家身边（距离1格）
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
    
    private void openTeleportPanel(Player player) {
        Set<String> senders = incomingRequests.get(player.getName());
        if (senders == null || senders.isEmpty()) {
            player.sendMessage("§a[传送] 你没有待处理的传送请求");
            return;
        }
        
        // 创建GUI面板（最多9个请求）
        int rows = Math.min((senders.size() + 8) / 9, 6);
        // 使用Inventory UUID做唯一标识
        String panelId = "tp_gui_" + System.currentTimeMillis() + "_" + player.getUniqueId();
        teleportPanelIds.add(panelId);
        
        // 创建实际Inventory
        Inventory inv = Bukkit.createInventory(null, rows * 9, "§6§l待处理传送请求");
        
        // 填充玻璃板
        ItemStack glass = createGlassPane();
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, glass);
        }
        
        int slot = 0;
        for (String senderName : senders) {
            if (slot >= inv.getSize() - 2) break; // 留出返回按钮位置
            
            Player senderPlayer = Bukkit.getServer().getPlayer(senderName);
            String displayName = senderName;
            String statusColor = "§e";
            String statusText = "§7(离线)";
            
            if (senderPlayer != null && senderPlayer.isOnline()) {
                statusColor = "§a";
                statusText = "§7(在线)";
            }
            
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + displayName);
                List<String> lore = Arrays.asList(
                    statusColor + "状态: " + statusText,
                    "",
                    "§a左键: 接受传送",
                    "§c左键: 拒绝传送"
                );
                meta.setLore(lore);
                item.setItemMeta(meta);
                
                // 添加PotionMeta设置皮肤（如果有在线玩家）
                if (senderPlayer != null && senderPlayer.isOnline()) {
                    meta.setDisplayName("§e" + senderPlayer.getName() + " §a(在线)");
                    item.setItemMeta(meta);
                }
            }
            
            inv.setItem(slot, item);
            slot++;
        }
        
        // 拒绝所有按钮
        ItemStack denyAll = new ItemStack(Material.BARRIER);
        ItemMeta denyMeta = denyAll.getItemMeta();
        if (denyMeta != null) {
            denyMeta.setDisplayName("§c拒绝所有请求");
            denyMeta.setLore(Arrays.asList("§7一次性拒绝所有传送请求"));
            denyAll.setItemMeta(denyMeta);
        }
        inv.setItem(inv.getSize() - 1, denyAll);
        
        // 返回列表按钮
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        if (backMeta != null) {
            backMeta.setDisplayName("§7返回列表");
            back.setItemMeta(backMeta);
        }
        inv.setItem(inv.getSize() - 2, back);
        
        player.openInventory(inv);
    }
    
    // ==================== GUI点击处理 ====================
    
    /**
     * 处理GUI点击事件（从Main转发过来）
     */
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!"§6§l待处理传送请求".equals(event.getView().getTitle())) {
            return;
        }
        
        Inventory panel = event.getInventory();
        if (panel == null) return;
        
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();
        
        // 返回列表
        if (slot == event.getInventory().getSize() - 2) {
            player.closeInventory();
            return;
        }
        
        // 拒绝所有
        if (slot == event.getInventory().getSize() - 1) {
            denyAllRequests(player);
            player.closeInventory();
            return;
        }
        
        // 点击传送请求
        if (slot >= 0 && slot < event.getInventory().getSize() - 2) {
            ItemStack item = event.getCurrentItem();
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                ItemMeta meta = item.getItemMeta();
                String displayName = (meta != null && meta.hasDisplayName()) 
                    ? meta.getDisplayName().replace("§e", "").replace(" §a(在线)", "").replace(" §7(离线)", "").trim() 
                    : "unknown";
                
                // 如果显示的是玩家自己的名字，说明是自己点的，拒绝
                if (displayName.equalsIgnoreCase(player.getName())) {
                    player.sendMessage("§c[传送] 你不能接受/拒绝自己的请求");
                    return;
                }
                
                // 尝试接受请求（如果有参数就用参数，否则用面板选中的）
                handleTPAccept(player, new String[]{displayName});
            }
        }
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
     * 玩家登录时自动开启接受传送
     */
    public void onPlayerLogin(String playerName) {
        autoAcceptPlayers.add(playerName);
    }
    
    /**
     * 玩家登出时关闭接受传送
     */
    public void onPlayerLogout(String playerName) {
        autoAcceptPlayers.remove(playerName);
    }
    
    /**
     * 清理过期的传送请求（例如超过5分钟）
     */
    public void cleanupExpiredRequests() {
        long cutoff = System.currentTimeMillis() - 5 * 60 * 1000; // 5分钟
        
        for (Iterator<Map.Entry<String, Long>> it = teleportCooldown.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> entry = it.next();
            if (entry.getValue() < cutoff) {
                it.remove();
            }
        }
    }
}
