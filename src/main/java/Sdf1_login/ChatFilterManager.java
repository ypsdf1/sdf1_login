package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatFilterManager {

    private final JavaPlugin plugin;
    private final List<String> whitelistUrls =
            new ArrayList<>();
    private final Set<String> whitelistPlayers =
            new TreeSet<>(
                    String.CASE_INSENSITIVE_ORDER);
    private final Map<Integer, String>
            punishmentRules =
            new LinkedHashMap<>();
    private final Map<Integer, Integer>
            punishmentDurations =
            new LinkedHashMap<>();
    private final Map<String, Integer>
            violationCount =
            new ConcurrentHashMap<>();
    private final Map<String, Long>
            mutedPlayers =
            new ConcurrentHashMap<>();
    // ★ 禁言理由（内存镜像，持久化到 users.muted_until / mute_reason）
    private final Map<String, String>
            muteReasons =
            new ConcurrentHashMap<>();
    private final Map<String, String> messages =
            new LinkedHashMap<>();
    // ★ 广告机检测：记录每个玩家发送链接的频率
    private final Map<String, List<Long>> linkSendHistory =
            new ConcurrentHashMap<>();
    // ★ 挂机检测
    private final Map<String, Long> playerLastActiveTime =
            new ConcurrentHashMap<>();
    // ★ 管理员邮件地址
    private String adminEmail = "admin@example.com";
    private boolean notifyAdmin = true;
    private boolean notifyAll = false;
    private int muteDuration = 300;
    private boolean enabled = true;
    
    // ★ 非法域名后缀列表（热更新）
    private final Set<String> illegalDomainSuffixes = new HashSet<>();
    
    // ★ 敏感词列表
    private final List<String> sensitiveWords = new ArrayList<>();
    
    /** 默认预设敏感词（writeDefaultFile / 配置补全用） */
    private static final List<String> DEFAULT_SENSITIVE_WORDS = Arrays.asList(
            // 英文粗口
            "fuck", "shit", "bitch", "asshole", "damn", "bastard",
            "suck", "dick", "piss", "crap", "screw",
            // 拼音/缩写粗口
            "sb", "tmd", "nmb", "mdzz", "qnmb", "wqnmlgb", "cnm", "nmsl", "wcnm",
            "cao", "caonima", "草", "艹", "操",
            // 中文粗口
            "你妈", "他妈", "你娘", "他娘", "你妹", "他爹",
            "傻逼", "傻b", "煞笔", "沙比",
            "草泥马", "操你", "干你", "去你",
            "垃圾", "废物", "废柴", "蠢货", "智障", "脑残", "白痴",
            "王八蛋", "狗屎", "放屁", "滚", "死全家",
            "变态", "色狼", "恶心", "不要脸",
            // 常见拼音变体
            "nima", "wocao", "wokao", "woqu", "fuck", "fuc", "fck",
            "caonim", "caoma", "qnm", "qnmd",
            // 广告/推广
            "加群", "qq群", "q群", "加我q", "加qq");
    
    // ============================================================
    // ★ 新玩家验证码系统
    // ============================================================
    
    /** 已验证过的玩家名单（永久，跨session持久化到内存） */
    private final Set<String> verifiedPlayers = ConcurrentHashMap.newKeySet();
    
    /** 验证码数据存储（public供Main.java访问清理） */
    public final Map<String, VerificationData> verificationData = new ConcurrentHashMap<>();
    
    /** 验证码类型对外公开 */
    public enum VerificationType {
        MATH, // 数学题
        GUI   // GUI物品选择
    }
    
    static class VerificationData {
        long createTime;
        int a, b, op; // 0=加法, 1=减法, 2=乘法
        String guiTargets; // GUI模式下需要选择的物品名列表（"|"分隔）
        VerificationType type; // 验证码类型
        boolean completed;
        
        VerificationData(int a, int b, int op, VerificationType type) {
            this.createTime = System.currentTimeMillis();
            this.a = a; this.b = b; this.op = op;
            this.type = type;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - createTime > 30000;
        }
    }

    /** 验证码结果：通过/失败 */
    public enum VerificationResult {
        NEED_VERIFICATION, // 需要验证码
        PENDING,           // 验证码进行中
        VERIFIED,          // 已验证
        FAILED             // 验证失败（消息吞噬）
    }

    /** 验证码答案缓存，供外部判断是否为答案 */
    public boolean isPendingVerificationAnswer(String playerName, String msg) {
        VerificationData vd = verificationData.get(playerName);
        if (vd == null || vd.completed) return false;
        // 尝试作为答案检查
        VerificationResult result = checkMathAnswer(playerName, msg);
        return result != VerificationResult.FAILED; // 通过或进行中都是有效的答案输入
    }

    /**
     * 检查玩家是否需要验证码
     */
    public VerificationResult checkNewPlayerVerification(Player p) {
        String name = p.getName();
        
        // ★ 永久已验证的玩家 → 直接放行
        if (verifiedPlayers.contains(name)) {
            return VerificationResult.VERIFIED;
        }

        // 正在验证中（等待答案）
        if (verificationData.containsKey(name)) {
            VerificationData vd = verificationData.get(name);
            if (vd.completed) {
                verifiedPlayers.add(name);
                verificationData.remove(name);
                return VerificationResult.VERIFIED;
            }
            // 验证码是否过期 — 过期后自动放行，不无限卡玩家
            if (vd.isExpired()) {
                verificationData.remove(name);
                verifiedPlayers.add(name);
                return VerificationResult.VERIFIED;
            }
            return VerificationResult.PENDING;
        }

        // 检查玩家加入时间是否≤1小时
        if (isNewPlayerUnder1Hour(p)) {
            return VerificationResult.NEED_VERIFICATION;
        }
        
        // 老玩家：直接永久验证通过
        verifiedPlayers.add(name);
        return VerificationResult.VERIFIED;
    }

    /** 全局共享的Random实例，避免频繁创建产生相同序列 */
    private static final ThreadLocal<Random> sharedRandom = ThreadLocal.withInitial(Random::new);
    
    /**
     * 生成随机验证码：数学题或GUI随机50%概率
     */
    public void generateMathVerification(String playerName) {
        Random rand = sharedRandom.get();
        // 50% 概率数学题，50% 概率GUI
        if (rand.nextBoolean()) {
            generateMathChallenge(playerName, rand);
        } else {
            generateGUIChallenge(playerName, rand);
        }
    }
    
    /** 生成数学题验证码 */
    private void generateMathChallenge(String playerName, Random rand) {
        int op = rand.nextInt(3); // 0=加, 1=减, 2=乘
        int a, b;

        switch (op) {
            case 0: a = rand.nextInt(10) + 1; b = rand.nextInt(10) + 1; break;
            case 1: a = rand.nextInt(10) + 2; b = rand.nextInt(a) + 1; break;
            default: a = rand.nextInt(10) + 1; b = rand.nextInt(10) + 1; break;
        }
        
        VerificationData vd = new VerificationData(a, b, op, VerificationType.MATH);
        verificationData.put(playerName, vd);

        String opSymbol = switch (op) {
            case 0 -> "+";
            case 1 -> "-";
            default -> "×";
        };
        
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = org.bukkit.Bukkit.getPlayer(playerName);
            if (p != null && p.isOnline()) {
                p.sendMessage("§e§l[验证码] §f请回答: §e" + a + " " + opSymbol + " " + b + " = ?");
                p.sendMessage("§7(30秒内输入答案)");
            }
        });
    }
    
    /** 中文物品名映射表 */
    private static final Map<String, String> MATERIAL_CN = new HashMap<>();
    static {
        MATERIAL_CN.put("EMERALD", "绿宝石");
        MATERIAL_CN.put("DIAMOND", "钻石");
        MATERIAL_CN.put("IRON_INGOT", "铁锭");
        MATERIAL_CN.put("GOLD_INGOT", "金锭");
        MATERIAL_CN.put("STONE", "石头");
        MATERIAL_CN.put("COBBLESTONE", "圆石");
        MATERIAL_CN.put("SAND", "沙子");
        MATERIAL_CN.put("GRAVEL", "砂砾");
        MATERIAL_CN.put("REDSTONE", "红石粉");
        MATERIAL_CN.put("LAPIS_LAZULI", "青金石");
        MATERIAL_CN.put("QUARTZ", "石英");
        MATERIAL_CN.put("APPLE", "苹果");
        MATERIAL_CN.put("BOWL", "碗");
        MATERIAL_CN.put("CAKE", "蛋糕");
        MATERIAL_CN.put("BOOK", "书");
        MATERIAL_CN.put("FLINT", "燧石");
        MATERIAL_CN.put("FLINT_AND_STEEL", "打火石");
        MATERIAL_CN.put("LEATHER_HELMET", "皮革头盔");
        MATERIAL_CN.put("LEATHER_CHESTPLATE", "皮革上衣");
        MATERIAL_CN.put("LEATHER_LEGGINGS", "皮革护腿");
        MATERIAL_CN.put("LEATHER_BOOTS", "皮革靴子");
        MATERIAL_CN.put("GOLDEN_APPLE", "附魔金苹果");
        MATERIAL_CN.put("OBSIDIAN", "黑曜石");
        MATERIAL_CN.put("DIAMOND_HELMET", "钻石头盔");
        MATERIAL_CN.put("DIAMOND_CHESTPLATE", "钻石护甲");
        MATERIAL_CN.put("DIAMOND_LEGGINGS", "钻石护腿");
        MATERIAL_CN.put("DIAMOND_BOOTS", "钻石靴子");
        MATERIAL_CN.put("IRON_HELMET", "铁头盔");
        MATERIAL_CN.put("IRON_CHESTPLATE", "铁护甲");
        MATERIAL_CN.put("IRON_LEGGINGS", "铁护腿");
        MATERIAL_CN.put("IRON_BOOTS", "铁靴子");
        MATERIAL_CN.put("WOODEN_SWORD", "木剑");
        MATERIAL_CN.put("STONE_SWORD", "石剑");
        MATERIAL_CN.put("IRON_SWORD", "铁剑");
        MATERIAL_CN.put("GOLDEN_SWORD", "金剑");
        MATERIAL_CN.put("DIAMOND_SWORD", "钻石剑");
        MATERIAL_CN.put("GREEN_CONCRETE", "绿色混凝土");
        MATERIAL_CN.put("LIME_CONCRETE", "淡绿色混凝土");
        MATERIAL_CN.put("JUNGLE_PLANKS", "丛林木板");
        MATERIAL_CN.put("ACACIA_PLANKS", "金合欢木板");
        MATERIAL_CN.put("BLAZE_ROD", "烈焰棒");
        MATERIAL_CN.put("MAGMA_CREAM", "熔浆膏");
        MATERIAL_CN.put("CLAY_BALL", "黏土球");
        MATERIAL_CN.put("MUSIC_DISC_13", "音乐唱片C13");
        MATERIAL_CN.put("MUSIC_DISC_PIGSTEP", "音乐唱片PigStep");
        MATERIAL_CN.put("END_CRYSTAL", "末影水晶");
        MATERIAL_CN.put("GLOWSTONE", "萤石粉");
        MATERIAL_CN.put("DRAGON_EGG", "龙蛋");
        MATERIAL_CN.put("NETHERITE_SCRAP", "下界合金碎片");
        MATERIAL_CN.put("CREEPER_HEAD", "爬行者头颅");
        MATERIAL_CN.put("GOLDEN_APPLE", "金苹果");
        MATERIAL_CN.put("ENCHANTED_GOLDEN_APPLE", "附魔金苹果");
    }
    
    /** 生成中文物品名 */
    private String toChineseName(String materialName) {
        String upper = materialName.toUpperCase();
        return MATERIAL_CN.getOrDefault(upper, materialName.replace("_", " "));
    }

    /** 生成GUI验证码：N个物品+1个正确答案，30秒超时 */
    private void generateGUIChallenge(String playerName, Random rand) {
        // 丰富物品池，保证足够多样性
        Material[] materials = {Material.STONE, Material.COBBLESTONE, Material.GRAVEL, 
                               Material.SAND, Material.CLAY_BALL, Material.REDSTONE, 
                               Material.LAPIS_LAZULI, Material.QUARTZ, Material.IRON_INGOT,
                               Material.GOLD_INGOT, Material.DIAMOND, Material.EMERALD,
                               Material.GREEN_CONCRETE, Material.LIME_CONCRETE,
                               Material.JUNGLE_PLANKS, Material.ACACIA_PLANKS,
                               Material.BLAZE_ROD, Material.CREEPER_HEAD, Material.DRAGON_EGG,
                               Material.NETHERITE_SCRAP, Material.END_CRYSTAL, Material.GLOWSTONE,
                               Material.MAGMA_CREAM, Material.MUSIC_DISC_13, Material.MUSIC_DISC_PIGSTEP,
                               Material.BOOK, Material.BOWL, Material.CAKE, Material.FLINT,
                               Material.FLINT_AND_STEEL, Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
                               Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS, Material.APPLE,
                               Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
                               Material.OBSIDIAN, Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE,
                               Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS, Material.IRON_HELMET,
                               Material.IRON_CHESTPLATE, Material.IRON_LEGGINGS, Material.IRON_BOOTS,
                               Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
                               Material.GOLDEN_SWORD, Material.DIAMOND_SWORD};
        
        // 随机选1个正确答案
        Material correctItem = materials[rand.nextInt(materials.length)];
        String chineseName = toChineseName(correctItem.name());
        
        // 选N-1个干扰项（5~10个物品，其中1个正确，其余干扰）
        int totalItems = 5 + rand.nextInt(6); // 5~10
        List<Material> distractorPool = new ArrayList<>();
        for (Material m : materials) {
            if (m != correctItem) distractorPool.add(m);
        }
        Collections.shuffle(distractorPool, rand);
        
        List<Material> itemsToPlace = new ArrayList<>();
        itemsToPlace.add(correctItem);
        for (int i = 0; i < Math.min(totalItems - 1, distractorPool.size()); i++) {
            itemsToPlace.add(distractorPool.get(i));
        }
        
        // 随机打乱物品位置
        Collections.shuffle(itemsToPlace, rand);
        
        // 存储：正确答案的Material名（用英文做校验），显示用中文名
        VerificationData vd = new VerificationData(0, 0, 0, VerificationType.GUI);
        vd.guiTargets = correctItem.name(); // 校验用英文
        vd.type = VerificationType.GUI;
        verificationData.put(playerName, vd);
        
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = org.bukkit.Bukkit.getPlayer(playerName);
            if (p != null && p.isOnline()) {
                try {
                    // 在GUI标题中明确显示中文目标物品名
                    Inventory inv = Bukkit.createInventory(null, 27, "§e§l点击" + chineseName + "完成验证");
                    
                    // 填充灰色玻璃半透明方块作为背景
                    ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                    ItemMeta gm = glass.getItemMeta();
                    if (gm != null) { gm.setDisplayName(" "); glass.setItemMeta(gm); }
                    for (int i = 0; i < 27; i++) inv.setItem(i, glass);
                    
                    // 随机打乱物品放置位置（避开边框slot 0-8, 18-26, 9,14,19）
                    List<Integer> availableSlots = Arrays.asList(9, 10, 11, 13, 14, 15, 17, 19, 20, 21);
                    Collections.shuffle(availableSlots, rand);
                    
                    int itemIdx = 0;
                    for (int slot : availableSlots) {
                        if (itemIdx >= itemsToPlace.size()) break;
                        Material mat = itemsToPlace.get(itemIdx);
                        String cnName = toChineseName(mat.name());
                        inv.setItem(slot, mkItem(mat, "§f" + cnName, ""));
                        itemIdx++;
                    }
                    
                    p.openInventory(inv);
                } catch (Exception ex) {
                    plugin.getLogger().warning("[验证码] GUI挑战生成失败: " + ex.getMessage());
                    // 降级为数学题
                    generateMathChallenge(playerName, rand);
                }
            }
        });
    }
    
    // 辅助方法：创建带显示名的物品
    private ItemStack mkItem(Material mat, String displayName, String lore) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(displayName);
            List<String> lores = new ArrayList<>();
            lores.add(lore);
            meta.setLore(lores);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public VerificationResult checkAnswer(String playerName, String answerStr) {
        VerificationData vd = verificationData.get(playerName);
        if (vd == null) return VerificationResult.VERIFIED;
        
        if (vd.isExpired()) {
            verificationData.remove(playerName);
            verifiedPlayers.add(playerName);
            return VerificationResult.VERIFIED;
        }
        
        if (vd.type == VerificationType.MATH) {
            return checkMathAnswer(playerName, answerStr);
        }
        
        // GUI验证码 — 聊天输入不作为答案，返回PENDING等待GUI点击
        return VerificationResult.PENDING;
    }

    /**
     * 检查玩家回答是否正确
     * @deprecated 使用 checkAnswer 代替
     */
    @Deprecated
    public VerificationResult checkMathAnswer(String playerName, String answerStr) {
        VerificationData vd = verificationData.get(playerName);
        if (vd == null) return VerificationResult.VERIFIED; // 无验证码 → 放行

        if (vd.isExpired()) {
            verificationData.remove(playerName);
            verifiedPlayers.add(playerName); // 过期后不再要求
            return VerificationResult.VERIFIED;
        }

        if (vd.type == VerificationType.MATH) {
            try {
                int ans = Integer.parseInt(answerStr.trim());
                int expected;
                switch (vd.op) {
                    case 0 -> expected = vd.a + vd.b;
                    case 1 -> expected = vd.a - vd.b;
                    default -> expected = vd.a * vd.b;
                }
                
                if (ans == expected) {
                    vd.completed = true;
                    verificationData.remove(playerName);
                    verifiedPlayers.add(playerName); // 永久记录 - 验证通过
                    return VerificationResult.VERIFIED;
                } else {
                    // ★ 回答错误 → 不清除验证码也不加入verifiedPlayers，让玩家重新答题
                    return VerificationResult.FAILED;
                }
            } catch (NumberFormatException e) {
                // 非数字答案 → 同样不放行，重新答题
                return VerificationResult.FAILED;
            }
        }
        
        // GUI验证码 — answerStr 为空时表示未回答
        if (answerStr == null || answerStr.isEmpty()) {
            return VerificationResult.PENDING;
        }
        
        return VerificationResult.FAILED;
    }
    
    /**
     * 处理GUI物品点击验证码
     * @param playerName 玩家名
     * @param clickedItemName 点击的物品Material名（如"STONE"）
     * @return 验证结果
     */
    public VerificationResult checkGUIClick(String playerName, String clickedItemName) {
        String name = playerName;
        VerificationData vd = verificationData.get(name);
        if (vd == null) return VerificationResult.VERIFIED;
        if (!VerificationType.GUI.equals(vd.type)) return VerificationResult.PENDING;
        
        if (vd.isExpired()) {
            verificationData.remove(name);
            verifiedPlayers.add(name);
            return VerificationResult.VERIFIED;
        }
        
        // ★ 关键修复：点击的物品名必须等于目标物品名才通过
        if (clickedItemName != null && clickedItemName.equals(vd.guiTargets)) {
            vd.completed = true;
            verificationData.remove(name);
            verifiedPlayers.add(name);
            return VerificationResult.VERIFIED;
        }
        
        // 点击错误物品 → 消息拦截
        return VerificationResult.FAILED;
    }

    // ★ 临时缓存玩家未验证的消息（N+1 机制：缓存列表，答对第N题后释放N条）
    public final Map<String, List<String>> pendingMessages = new ConcurrentHashMap<>();

    /** 缓存玩家消息，等验证通过时代为广播 */
    public void cachePendingMessage(String playerName, String message) {
        pendingMessages.computeIfAbsent(playerName, k -> new ArrayList<>()).add(message);
    }

    /** 广播所有缓存的消息（N+1机制：广播缓存的N-1条 + 当前第N条） */
    public void broadcastCachedMessages(String playerName) {
        List<String> messages = pendingMessages.remove(playerName);
        if (messages != null) {
            for (String msg : messages) {
                Bukkit.broadcastMessage(msg);
            }
        }
    }

    /** 清除玩家缓存的消息（验证失败时调用） */
    public void clearPendingMessages(String playerName) {
        pendingMessages.remove(playerName);
    }

    /**
     * 清理过期的验证码
     */
    public void cleanupExpiredVerifications() {
        long now = System.currentTimeMillis();
        verificationData.entrySet().removeIf(e -> {
            if (e.getValue().isExpired() && !e.getValue().completed) {
                return true;
            }
            return false;
        });
    }

    /**
     * 检查玩家是否为新玩家（register_time 在1小时内）
     */
    public boolean isNewPlayerUnder1Hour(Player p) {
        if (p == null || !p.isOnline()) return false;
        try {
            Main mainPlugin = (Main) plugin;
            DatabaseManager db = mainPlugin.getDb();
            if (db == null) return false; // 查不到就不拦截，直接放行
            
            // register_time 在数据库存的是秒级时间戳
            Object regTimeObj = db.getField(p.getName(), "register_time");
            if (regTimeObj == null) return false; // 没注册记录 → 放行
            
            long regTimeSeconds = ((Number) regTimeObj).longValue();
            if (regTimeSeconds == 0) return false; // 注册时间为0 → 放行
            
            long regTimeMs = regTimeSeconds * 1000;
            long elapsed = System.currentTimeMillis() - regTimeMs;
            boolean isNew = elapsed < 3600000;
            
            // 日志调试：打印注册时间差值
            if (isNew) {
                plugin.getLogger().info("[验证码] 新玩家: " + p.getName() + " 注册" + (elapsed/1000) + "秒前");
            }
            
            return isNew;
        } catch (Exception e) {
            return false; // 出错时默认放行，不卡玩家
        }
    }


    // ==================== 广告机检测 ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        enabled = v;
    }


    private static final Pattern URL_PATTERN =
            Pattern.compile(
                    "(?i)"
                    // 1. 标准URL: http://xxx.com/...
                    + "(?:https?://\\S+)"
                    // 2. 点分隔域名: xxx.com, my.site.cn
                    + "|(?:(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,})(?:/\\S*)?"
                    // 3. 下划线分隔域名: xxx_com, my_site.cn, 123_ypshidifu.cn
                    + "|(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?)"
                    + "(?:[_.][a-zA-Z0-9](?:[a-zA-Z0-9\\-]*[a-zA-Z0-9])?)*"
                    + "[_.][a-zA-Z]{2,}");
    private static final Pattern DOT_VARIANTS =
            Pattern.compile(
                    "[\uff0e\u3002\u2025\u2026\u00b7]");
    private static final Pattern COLOR_CODES =
            Pattern.compile(
                    "\u00a7[0-9a-fk-orA-FK-OR]");

    private static final Set<String> FILE_EXT;
    static {
        Set<String> s = new HashSet<>(
                Arrays.asList(
                        "txt", "yml", "yaml",
                        "json", "xml", "java",
                        "class", "jar", "png",
                        "jpg", "jpeg", "gif",
                        "bmp", "svg", "ico",
                        "webp", "mp3", "mp4",
                        "avi", "mkv", "wav",
                        "flac", "ogg", "zip",
                        "rar", "7z", "tar",
                        "gz", "cfg", "conf",
                        "ini", "log", "db",
                        "sql", "sh", "bat",
                        "html", "htm", "css",
                        "js", "md", "pdf",
                        "dll", "so", "exe",
                        "bin", "c", "cpp",
                        "py", "rb", "go",
                        "rs", "kt", "lua",
                        "php", "asp", "jsp"));
        FILE_EXT = Collections.unmodifiableSet(s);
    }

    public ChatFilterManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ========== 配置加载 ==========

    public void loadConfig() {
        whitelistUrls.clear();
        whitelistPlayers.clear();
        punishmentRules.clear();
        punishmentDurations.clear();
        messages.clear();
        sensitiveWords.clear(); // 重新加载前先清空

        File f = new File(
                plugin.getDataFolder(), "chat.txt");
        if (!f.exists()) writeDefaultFile(f);
        boolean hasSensitiveSection = false; // 记录文件是否已有敏感词列表节
        try (BufferedReader r =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(f),
                                     StandardCharsets.UTF_8))) {
            String section = "";
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF"))
                    line = line.substring(1);
                String t = line.trim();

                if (t.isEmpty()
                        || t.startsWith("#"))
                    continue;
                if (t.equals("白名单")
                        || t.equals("白名单:")) {
                    section = "whitelist";
                    continue;
                }
                if (t.equals("处罚规则")
                        || t.equals("处罚规则:")) {
                    section = "punishment";
                    continue;
                }
                if (t.equals("白名单玩家")
                        || t.equals("白名单玩家:")) {
                    section = "players";
                    continue;
                }
                if (t.equals("敏感词列表")
                        || t.equals("敏感词列表:")) {
                    section = "sensitive";
                    hasSensitiveSection = true;
                    continue;
                }
                if (t.contains(":")) {
                    String[] kv = t.split(":", 2);
                    String k = kv[0].trim();
                    String v = kv[1].trim();
                    if (equals("启用过滤")
                            || equals("enabled")) {
                        enabled = parseBool(v);
                        continue;
                    }

                    if (k.equals("通知管理员")) {
                        notifyAdmin = parseBool(v);
                        continue;
                    }
                    if (k.equals("全服通报")) {
                        notifyAll = parseBool(v);
                        continue;
                    }
                    if (k.equals("禁言时长")) {
                        try {
                            muteDuration =
                                    Integer.parseInt(v);
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                }
                switch (section) {
                    case "whitelist":
                        whitelistUrls.add(
                                t.toLowerCase());
                        break;
                    case "punishment":
                        String[] parts = t.split(":");
                        if (parts.length >= 2) {
                            try {
                                int cnt =
                                        Integer.parseInt(
                                                parts[0]
                                                        .trim());
                                String type =
                                        parts[1].trim()
                                                .toLowerCase();
                                int dur =
                                        parts.length >= 3
                                                ? Integer.parseInt(
                                                parts[2]
                                                .trim())
                                                : muteDuration;
                                punishmentRules.put(
                                        cnt, type);
                                punishmentDurations.put(
                                        cnt, dur);
                            } catch (Exception ignored) {
                            }
                        }
                        break;
                    case "players":
                        whitelistPlayers.add(t);
                        break;
                    case "sensitive":
                        sensitiveWords.add(t.toLowerCase());
                        break;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Sdf1_chat] 配置加载失败: "
                            + e.getMessage());
        }

        // ★ 配置补全：敏感词列表节缺失时追加预设值
        appendMissingConfigSections(f, hasSensitiveSection);

        // 从消息.txt读取chat_开头的消息
        loadMessages();
    }

    /**
     * 检测 chat.txt 是否缺少配置节，缺失则在文件末尾追加预设内容。
     * 已有该节则跳过补全（不覆盖用户已有配置）。
     */
    private void appendMissingConfigSections(File f, boolean hasSensitiveSection) {
        if (hasSensitiveSection) return; // 已有敏感词列表节，跳过
        // 追加敏感词列表节到文件末尾
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f, true),
                        StandardCharsets.UTF_8))) {
            pw.println();
            pw.println("敏感词列表:");
            pw.println("# ★ 以下为自动补全的预设敏感词，可自行增删");
            for (String sw : DEFAULT_SENSITIVE_WORDS) {
                pw.println(sw);
            }
            // 同步到内存
            sensitiveWords.addAll(DEFAULT_SENSITIVE_WORDS.stream().map(String::toLowerCase).toList());
            plugin.getLogger().info("[Sdf1_chat] 配置文件缺少敏感词列表节，已在末尾追加" + DEFAULT_SENSITIVE_WORDS.size() + "条预设");
        } catch (IOException e) {
            plugin.getLogger().warning("[Sdf1_chat] 配置补全写入失败: " + e.getMessage());
        }
    }

    private void loadMessages() {
        File msgFile = new File(
                plugin.getDataFolder(),
                "消息.txt");
        if (!msgFile.exists()) return;
        try (BufferedReader mr =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(
                                             msgFile),
                                     StandardCharsets.UTF_8))) {
            String mline;
            while ((mline = mr.readLine()) != null) {
                mline = mline.trim();
                if (mline.isEmpty()
                        || mline.startsWith("#"))
                    continue;
                // 支持 = 和 : 两种分隔符
                int eqIdx = mline.indexOf('=');
                int coIdx = mline.indexOf(':');
                int splitIdx = -1;
                if (eqIdx >= 0 && coIdx >= 0) {
                    splitIdx = Math.min(eqIdx, coIdx);
                } else if (eqIdx >= 0) {
                    splitIdx = eqIdx;
                } else if (coIdx >= 0) {
                    splitIdx = coIdx;
                }
                if (splitIdx < 0) continue;
                String k = mline.substring(0,
                        splitIdx).trim();
                String v = mline.substring(
                        splitIdx + 1).trim();
                if (k.startsWith("chat_")) {
                    messages.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
    }


    private void writeDefaultFile(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# Sdf1_chat 聊天过滤配置");
            pw.println("启用过滤: " + enabled);
            pw.println("通知管理员: true");
            pw.println("全服通报: false");
            pw.println("禁言时长: 300");
            pw.println();
            pw.println("白名单:");
            pw.println("*.minecraft.net");
            pw.println("baidu.com");
            pw.println("github.com");
            pw.println();
            pw.println("处罚规则:");
            pw.println("1:warn:0");
            pw.println("2:warn:0");
            pw.println("3:mute:300");
            pw.println("5:mute:600");
            pw.println("8:kick:0");
            pw.println("10:ban:0");
            pw.println();
            pw.println("白名单玩家:");
            pw.println();
            pw.println("敏感词列表:");
            pw.println("# 每行一个敏感词，触发后消息仅发送者可见，计数器+1");
            pw.println("# 达到处罚规则中次数后自动处罚并归零计数器");
            for (String sw : DEFAULT_SENSITIVE_WORDS) {
                pw.println(sw);
            }
        } catch (IOException ignored) {
        }
    }

    public void saveConfig() {
        File f = new File(
                plugin.getDataFolder(), "chat.txt");
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# Sdf1_chat 聊天过滤配置");
            pw.println("通知管理员: " + notifyAdmin);
            pw.println("全服通报: " + notifyAll);
            pw.println("禁言时长: " + muteDuration);
            pw.println();
            pw.println("白名单:");
            for (String u : whitelistUrls)
                pw.println(u);
            pw.println();
            pw.println("处罚规则:");
            for (Map.Entry<Integer, String> entry
                    : punishmentRules.entrySet()) {
                int dur = punishmentDurations
                        .getOrDefault(entry.getKey(),
                                muteDuration);
                pw.println(entry.getKey() + ":"
                        + entry.getValue()
                        + ":" + dur);
            }
            pw.println();
            pw.println("白名单玩家:");
            for (String p : whitelistPlayers)
                pw.println(p);
            pw.println();
            pw.println("敏感词列表:");
            for (String s : sensitiveWords)
                pw.println(s);
        } catch (IOException e) {
            plugin.getLogger().severe(
                    "[Sdf1_chat] 保存失败: "
                            + e.getMessage());
        }
    }

    // ========== 消息 ==========

    public String msg(String key,
                      String... args) {
        String template = messages.getOrDefault(
                key, key);
        for (int i = 0; i < args.length - 1;
             i += 2) {
            template = template.replace(
                    "{" + args[i] + "}",
                    args[i + 1]);
        }
        return template;
    }

    // ========== URL检测 ==========

    private String normalizeMessage(String msg) {
        msg = COLOR_CODES.matcher(msg)
                .replaceAll("");
        msg = DOT_VARIANTS.matcher(msg)
                .replaceAll(".");
        return msg;
    }

    public List<String> extractUrls(
            String message) {
        String normalized =
                normalizeMessage(message);
        List<String> urls = new ArrayList<>();
        Matcher m = URL_PATTERN
                .matcher(normalized);
        while (m.find()) {
            String url = m.group().toLowerCase();
            url = url.replaceAll(
                    "[\\s.,;!?，。；！？、）》」』】"
                            + "\\]\\[\\(（]+$",
                    "");
            if (!url.isEmpty()) urls.add(url);
        }
        return urls;
    }

    public boolean isWhitelisted(String url) {
        String clean = url
                .replaceAll("^https?://", "")
                .toLowerCase();
        int colon = clean.indexOf(':');
        int slash = clean.indexOf('/');
        if (colon > 0 && (slash < 0
                || colon < slash)) {
            clean = clean.substring(0, colon);
        }
        slash = clean.indexOf('/');
        if (slash > 0) {
            clean = clean.substring(0, slash);
        }
        for (String entry : whitelistUrls) {
            String e = entry.toLowerCase().trim();
            if (e.startsWith("*.")) {
                String suffix = e.substring(1);
                if (clean.endsWith(suffix)
                        || clean.equals(
                        suffix.substring(1)))
                    return true;
            } else {
                if (clean.equals(e)
                        || clean.endsWith(
                        "." + e))
                    return true;
            }
        }
        return false;
    }

    public boolean isLikelyDomain(String url) {
        int lastDot = url.lastIndexOf('.');
        if (lastDot < 0
                || lastDot >= url.length() - 1)
            return false;
        String ext = url.substring(lastDot + 1)
                .toLowerCase();
        return !FILE_EXT.contains(ext);
    }

    // ========== 玩家操作 ==========

    public boolean isMuted(String name) {
        Long expiry = mutedPlayers.get(name);
        if (expiry == null) return false;
        if (System.currentTimeMillis()
                >= expiry) {
            mutedPlayers.remove(name);
            muteReasons.remove(name);
            clearMuteDb(name);
            return false;
        }
        return true;
    }

    public String fmtDuration(int seconds) {
        if (seconds <= 0) return "0秒";
        int day = seconds / 86400;
        int hr = (seconds % 86400) / 3600;
        int min = (seconds % 3600) / 60;
        int sec = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (day > 0) sb.append(day).append("天");
        if (hr > 0)
            sb.append(hr).append("小时");
        if (min > 0)
            sb.append(min).append("分");
        if (sec > 0 || sb.length() == 0)
            sb.append(sec).append("秒");
        return sb.toString();
    }

    /**
     * 应用处罚规则。返回应用的处罚类型: "warn"/"mute"/"kick"/"ban"/"banip"
     */
    public String applyPunishment(Player player,
                                int violation) {
        return applyPunishment(player, violation,
                "触发敏感词", "系统");
    }

    /**
     * 应用处罚规则（可指定原因和执行人）。
     * 自动通知管理员，kick/ban/banip 全服广播，mute 给详细消息。
     */
    public String applyPunishment(Player player,
                                int violation,
                                String reason,
                                String issuer) {
        String type = "warn";
        int duration = muteDuration;
        int maxRule = 0;
        for (Map.Entry<Integer, String> entry
                : punishmentRules.entrySet()) {
            if (violation >= entry.getKey()
                    && entry.getKey() > maxRule) {
                maxRule = entry.getKey();
                type = entry.getValue();
                duration = punishmentDurations
                        .getOrDefault(
                                entry.getKey(),
                                muteDuration);
            }
        }
        if (type.equals("mute")) {
            if (isMuted(player.getName()))
                return "already_muted";
            long expireMs = System.currentTimeMillis()
                    + (long) duration * 1000L;
            mutedPlayers.put(player.getName(),
                    expireMs);
            if (reason != null && !reason.isEmpty())
                muteReasons.put(player.getName(),
                        reason);
        }
        final String fType = type;
        final int fDur = duration;
        final String fName = player.getName();
        final String fReason = reason != null
                ? reason : "触发敏感词";
        final String fIssuer = issuer != null
                ? issuer : "系统";
        Bukkit.getScheduler().runTask(plugin,
                () -> {
                    Player p = Bukkit.getPlayer(fName);
                    if (p == null) return;
                    long now = System.currentTimeMillis();
                    long endMs = now
                            + (long) fDur * 1000L;
                    String startStr = new SimpleDateFormat(
                            "yyyy年M月d日 HH点mm分")
                            .format(new Date(now));
                    String endStr = new SimpleDateFormat(
                            "yyyy年M月d日 HH点mm分")
                            .format(new Date(endMs));
                    String durStr = fmtDuration(fDur);
                    switch (fType) {
                        case "warn":
                            break;
                        case "mute":
                            p.sendMessage("§c§l你因为" + fReason
                                    + "被" + fIssuer
                                    + "执行禁言" + durStr
                                    + "，处罚开始时间："
                                    + startStr
                                    + " 处罚到期时间："
                                    + endStr);
                            break;
                        case "kick":
                            p.kickPlayer("§c你因" + fReason
                                    + "被踢出服务器");
                            break;
                        case "ban":
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    "ban " + fName
                                            + " §c" + fReason);
                            break;
                        case "banip":
                            InetSocketAddress addr =
                                    p.getAddress();
                            if (addr != null) {
                                String ip = addr
                                        .getAddress()
                                        .getHostAddress();
                                Bukkit.dispatchCommand(
                                        Bukkit.getConsoleSender(),
                                        "ban-ip " + ip
                                                + " §c" + fReason);
                            }
                            break;
                    }
                    // 非 warn → 通知在线管理员
                    if (!"warn".equals(fType)) {
                        String adminMsg = "§c[聊天监控] §f玩家 "
                                + fName + " 因" + fReason
                                + "被" + fIssuer + "处罚："
                                + fType;
                        if ("mute".equals(fType))
                            adminMsg += " " + durStr;
                        for (Player op :
                                Bukkit.getOnlinePlayers()) {
                            if (op.hasPermission(
                                    "sdf1.admin")
                                    || op.isOp()) {
                                op.sendMessage(adminMsg);
                            }
                        }
                    }
                    // kick/ban/banip → 全服公告
                    if (fType.equals("kick")
                            || fType.equals("ban")
                            || fType.equals("banip")) {
                        Bukkit.broadcastMessage(
                                "§c[公告] §f玩家 " + fName
                                        + " 因" + fReason
                                        + "被" + fIssuer
                                        + "处罚");
                    }
                });
        return type;
    }

    public void unmutePlayer(String name) {
        mutedPlayers.remove(name);
        muteReasons.remove(name);
        clearMuteDb(name);
    }

    // ========== 禁言持久化（重启后仍生效） ==========

    /** 禁言指定玩家，到期时间戳为 expireMs（绝对毫秒） */
    public void mutePlayer(String name, long expireMs,
                           String reason) {
        mutedPlayers.put(name, expireMs);
        if (reason != null && !reason.isEmpty())
            muteReasons.put(name, reason);
        persistMute(name, expireMs, reason);
        plugin.getLogger().info(
                "[聊天] 已禁言 " + name + " 至 "
                        + new java.util.Date(expireMs));
    }

    private void persistMute(String name, long expireMs,
                             String reason) {
        try {
            Main mainPlugin = (Main) plugin;
            DatabaseManager db = mainPlugin.getDb();
            if (db == null) return;
            db.setField(name, "muted_until", expireMs);
            db.setField(name, "mute_reason",
                    reason == null ? "" : reason);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void clearMuteDb(String name) {
        try {
            Main mainPlugin = (Main) plugin;
            DatabaseManager db = mainPlugin.getDb();
            if (db == null) return;
            db.setField(name, "muted_until", 0);
            db.setField(name, "mute_reason", "");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 插件启动时从数据库加载仍有效的禁言 */
    public void loadMutes() {
        try {
            Main mainPlugin = (Main) plugin;
            DatabaseManager db = mainPlugin.getDb();
            if (db == null) return;
            for (Object[] row : db.getActiveMutes()) {
                String name = (String) row[0];
                long expire = (Long) row[1];
                String reason = (String) row[2];
                mutedPlayers.put(name, expire);
                if (reason != null
                        && !reason.isEmpty())
                    muteReasons.put(name, reason);
            }
            if (!mutedPlayers.isEmpty())
                plugin.getLogger().info(
                        "[聊天] 已加载 " + mutedPlayers.size()
                                + " 条有效禁言");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public String getMuteReason(String name) {
        return muteReasons.get(name);
    }

    /** 返回当前仍有效的禁言到期时间戳(ms)，已过期或未禁言返回 0 */
    public long getMuteExpire(String name) {
        Long expiry = mutedPlayers.get(name);
        if (expiry == null) return 0;
        if (System.currentTimeMillis() >= expiry) return 0;
        return expiry;
    }

    public void resetPlayer(String name) {
        mutedPlayers.remove(name);
        violationCount.remove(name);
    }

    public boolean isPlayerWhitelisted(
            String name) {
        return whitelistPlayers.contains(name);
    }

    public boolean isNotifyAdmin() {
        return notifyAdmin;
    }

    public boolean isNotifyAll() {
        return notifyAll;
    }

    public int getViolationCount(String name) {
        return violationCount
                .getOrDefault(name, 0);
    }

    public void incrementViolation(
            String name) {
        violationCount.put(name,
                getViolationCount(name) + 1);
    }

    public List<String> getWhitelistUrls() {
        return whitelistUrls;
    }

    public List<String> getSensitiveWords() {
        return sensitiveWords;
    }

    public Set<String> getWhitelistPlayers() {
        return whitelistPlayers;
    }

    public Map<Integer, String>
    getPunishmentRules() {
        return punishmentRules;
    }

    public Map<Integer, Integer>
    getPunishmentDurations() {
        return punishmentDurations;
    }

    public Map<String, Long>
    getMutedPlayers() {
        return mutedPlayers;
    }

    public void setNotifyAdmin(boolean v) {
        notifyAdmin = v;
    }

    public void setNotifyAll(boolean v) {
        notifyAll = v;
    }

    public void setMuteDuration(int v) {
        muteDuration = v;
    }

    public void addUrl(String url) {
        whitelistUrls.add(
                url.toLowerCase().trim());
        saveConfig();
    }

    public boolean removeUrl(String url) {
        boolean r = whitelistUrls.remove(
                url.toLowerCase().trim());
        saveConfig();
        return r;
    }

    public void addSensitiveWord(String word) {
        sensitiveWords.add(word.toLowerCase().trim());
        saveConfig();
    }

    public boolean removeSensitiveWord(String word) {
        boolean r = sensitiveWords.remove(word.toLowerCase().trim());
        saveConfig();
        return r;
    }

    public void addWhitelistPlayer(
            String name) {
        whitelistPlayers.add(name);
        saveConfig();
    }

    public boolean removeWhitelistPlayer(
            String name) {
        boolean r =
                whitelistPlayers.remove(name);
        saveConfig();
        return r;
    }

    public void cleanExpired() {
        long now = System.currentTimeMillis();
        mutedPlayers.entrySet().removeIf(
                e -> e.getValue() <= now);
    }

    private boolean parseBool(String v) {
        String s = v.toLowerCase().trim();
        return s.equals("true")
                || s.equals("on")
                || s.equals("yes")
                || s.equals("1")
                || s.contains("开启")
                || s.contains("启用")
                || s.contains("是");
    }

    // ==================== 广告机检测 ====================

    /**
     * 检查消息是否包含第三方推广内容（邮件、游戏内推广等）
     */
    public boolean isAdvertisingContent(String msg) {
        String lower = msg.toLowerCase();
        // 检测关键词
        String[] keywords = {"群", "群号", "QQ", "qq群", "加群", "交流群", "服务器", "开服", "联机", "白嫖", "免费送", "折扣", "打折", "代充", "充值优惠", "外挂", "脚本", "辅助", "破解"};
        for (String kw : keywords) {
            if (lower.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 记录玩家发送链接的行为，检测广告机
     * @return 如果检测到广告机返回true
     */
    public boolean checkAdBotBehavior(String playerName, String msg) {
        long now = System.currentTimeMillis();
        
        // 提取URL
        List<String> urls = extractUrls(msg);
        if (urls.isEmpty()) return false;
        
        // 检查是否全部非白名单
        boolean hasNonWhitelisted = false;
        for (String url : urls) {
            if (!isWhitelisted(url)) {
                hasNonWhitelisted = true;
                break;
            }
        }
        if (!hasNonWhitelisted) return false;
        
        // 检查是否包含推广关键词
        if (!isAdvertisingContent(msg)) return false;
        
        // 记录链接发送时间
        linkSendHistory.computeIfAbsent(playerName, k -> new ArrayList<>()).add(now);
        
        // 清理超过10分钟前的记录
        List<Long> history = linkSendHistory.get(playerName);
        history.removeIf(t -> (now - t) > 600000);
        
        // 10分钟内发送超过3次非白名单链接 → 广告机
        if (history.size() >= 3) {
            linkSendHistory.remove(playerName);
            return true; // 检测到广告机
        }
        
        return false;
    }

    /**
     * 标记玩家活跃（用于挂机检测）
     */
    public void markPlayerActive(String playerName) {
        playerLastActiveTime.put(playerName, System.currentTimeMillis());
    }

    /**
     * 检查玩家是否长时间挂机（超过5分钟）
     */
    public boolean isPlayerIdle(String playerName, long idleThresholdMs) {
        Long lastActive = playerLastActiveTime.get(playerName);
        if (lastActive == null) return true;
        return (System.currentTimeMillis() - lastActive) > idleThresholdMs;
    }

    /**
     * 发送管理员通知邮件
     */
    public void sendAdminNotification(String subject, String body) {
        // 异步发送，不阻塞主线程
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                EmailManager emailMgr = ((Main) plugin).getEmail();
                if (emailMgr != null) {
                    emailMgr.sendBody(adminEmail, subject, body);
                    plugin.getLogger().info("[广告机检测] 已发送管理员通知: " + subject);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[广告机检测] 发送管理员通知失败: " + e.getMessage());
            }
        });
    }

    // ==================== 非法域名后缀管理 ====================

    /**
     * 加载非法域名后缀配置文件（支持热更新）
     * 配置文件: plugins/Sdf1_login/illegal_domains.txt
     * 每行一个后缀，如: com, cn, test 等
     */
    public void loadIllegalDomains() {
        illegalDomainSuffixes.clear();
        File f = new File(plugin.getDataFolder(), "illegal_domains.txt");
        if (!f.exists()) {
            plugin.getLogger().info("[非法域名] 配置文件不存在，已加载默认内置后缀");
            // 内置默认后缀作为兜底
            for (String s : new String[]{"com", "cn", "net", "org", "io", "xyz", "top", "club", "site", "online", "store", "tech", "info", "biz"}) {
                illegalDomainSuffixes.add(s.toLowerCase());
            }
            return;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // 去掉可能的 * 前缀
                String suffix = line.replaceFirst("^\\*", "").toLowerCase();
                if (!suffix.isEmpty()) {
                    illegalDomainSuffixes.add(suffix);
                }
            }
            plugin.getLogger().info("[非法域名] 已加载 " + illegalDomainSuffixes.size() + " 个非法后缀（来自配置文件）");
        } catch (Exception e) {
            plugin.getLogger().warning("[非法域名] 加载失败，使用内置默认后缀: " + e.getMessage());
            // 加载失败时用内置兜底
            for (String s : new String[]{"com", "cn", "net", "org", "io", "xyz", "top", "club", "site", "online", "store", "tech", "info", "biz"}) {
                illegalDomainSuffixes.add(s.toLowerCase());
            }
        }
    }

    /**
     * 检查名称是否包含非法域名格式（如 baidu_com, mysite.cn 等）
     */
    public boolean containsIllegalDomain(String name) {
        if (name == null || name.isEmpty()) return false;
        String lowerName = name.toLowerCase();
        
        // 支持两种格式：1) dot分隔 xxx.com  2) underscore分隔 xxx_com
        for (String separator : new String[]{".", "_"}) {
            int idx = lowerName.lastIndexOf(separator);
            if (idx < 0) continue;
            
            String suffix = lowerName.substring(idx + 1);
            
            // 去掉可能的前缀嵌套
            if (suffix.contains(separator)) {
                suffix = suffix.substring(suffix.lastIndexOf(separator) + 1);
            }
            
            for (String domainSuffix : illegalDomainSuffixes) {
                if (suffix.equals(domainSuffix) && !suffix.isEmpty()) {
                    // 前面必须有主体部分（排除纯后缀如 .com 本身）
                    String before = lowerName.substring(0, idx);
                    if (!before.isEmpty() && before.length() >= 2) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * 检查消息是否包含敏感词
     * @return 匹配到的敏感词列表（空列表表示无匹配）
     */
    public List<String> checkSensitiveWords(String msg) {
        if (msg == null || msg.isEmpty() || sensitiveWords.isEmpty())
            return Collections.emptyList();
        String lower = msg.toLowerCase();
        List<String> matched = new ArrayList<>();
        for (String sw : sensitiveWords) {
            if (lower.contains(sw)) {
                matched.add(sw);
            }
        }
        return matched;
    }

    /** 重置玩家违规计数 */
    public void resetViolationCount(String name) {
        violationCount.remove(name);
    }

    /**
     * 定时检查空闲玩家
     */
    public void checkIdlePlayers() {
        long idleThreshold = 5 * 60 * 1000L; // 5分钟
        org.bukkit.Bukkit.getOnlinePlayers().forEach(p -> {
            if (isPlayerIdle(p.getName(), idleThreshold)) {
                // 不再发送空闲消息，仅刷新计时
                playerLastActiveTime.put(p.getName(), System.currentTimeMillis()); // 刷新计时
            }
        });
    }
}
