package Sdf1_login;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;



public class QuestTracker {

    private final Plugin plugin;
    private final QuestStorage storage;
    private final Map<String, String>
            playerBiome = new HashMap<>();
    private final Map<String, List<QuestFile>>
            questCache = new HashMap<>();
    private final List<String>
            checkInRecords = new ArrayList<>();
    private final Map<String, String>
            lastCheckInPos = new HashMap<>();

    public void recordCheckIn(
            String player,
            String biomeKey,
            String category,
            String worldName,
            double x, double y, double z) {
        String record = player
                + "|" + worldName
                + "|" + biomeKey
                + "|" + category
                + "|" + String.format(
                "%.1f", x)
                + "|" + String.format(
                "%.1f", y)
                + "|" + String.format(
                "%.1f", z)
                + "|" + System.currentTimeMillis();
        checkInRecords.add(record);
        plugin.getLogger().info(
                "[QuestTracker] 打卡记录: "
                        + record);
    }

    public List<String> getCheckInRecords() {
        return new ArrayList<>(checkInRecords);
    }

    public List<String> getPlayerRecords(
            String player) {
        List<String> list = new ArrayList<>();
        for (String r : checkInRecords) {
            if (r.startsWith(
                    player.toLowerCase()
                            + "|")) {
                list.add(r);
            }
        }
        return list;
    }


    public QuestTracker(Plugin plugin) {
        this.plugin = plugin;
        this.storage = new QuestStorage(
                plugin.getDataFolder());

        loadAllQuests();
    }

    public void debugPlayer(String player) {
        plugin.getLogger().info(
                "[QuestTracker] === "
                        + player + " 任务数据 ===");
    }
    public void shutdown() {
        if (storage != null) {
            storage.close();
        }
    }

    /**
     * 玩家移动时记录当前群系
     */
    public void onPlayerMove(
            String player,
            String biomeName) {
        playerBiome.put(player, biomeName);
    }
    /**
     * 标记玩家已打卡该群系
     */
    // ========== 群系分类映射表 ==========
    private static final Map<String, String>
            BIOME_CATEGORY = new HashMap<>();
    static {
        // 海洋群系
        BIOME_CATEGORY.put("minecraft:ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:deep_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:warm_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:deep_warm_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:lukewarm_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:deep_lukewarm_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:cold_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:deep_cold_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:frozen_ocean", "海洋");
        BIOME_CATEGORY.put("minecraft:deep_frozen_ocean", "海洋");
        // 平原群系
        BIOME_CATEGORY.put("minecraft:plains", "平原");
        BIOME_CATEGORY.put("minecraft:sunflower_plains", "平原");
        BIOME_CATEGORY.put("minecraft:meadow", "平原");
        // 森林群系
        BIOME_CATEGORY.put("minecraft:forest", "森林");
        BIOME_CATEGORY.put("minecraft:flower_forest", "森林");
        BIOME_CATEGORY.put("minecraft:birch_forest", "森林");
        BIOME_CATEGORY.put("minecraft:old_growth_birch_forest", "森林");
        BIOME_CATEGORY.put("minecraft:dark_forest", "森林");
        BIOME_CATEGORY.put("minecraft:taiga", "森林");
        BIOME_CATEGORY.put("minecraft:snowy_taiga", "森林");
        BIOME_CATEGORY.put("minecraft:old_growth_pine_taiga", "森林");
        BIOME_CATEGORY.put("minecraft:old_growth_spruce_taiga", "森林");
        BIOME_CATEGORY.put("minecraft:cherry_grove", "森林");
        BIOME_CATEGORY.put("minecraft:jungle", "森林");
        BIOME_CATEGORY.put("minecraft:sparse_jungle", "森林");
        BIOME_CATEGORY.put("minecraft:bamboo_jungle", "森林");
        // 沙漠群系
        BIOME_CATEGORY.put("minecraft:desert", "沙漠");
        BIOME_CATEGORY.put("minecraft:savanna", "沙漠");
        BIOME_CATEGORY.put("minecraft:savanna_plateau", "沙漠");
        BIOME_CATEGORY.put("minecraft:beach", "沙漠");
        BIOME_CATEGORY.put("minecraft:snowy_beach", "沙漠");
        BIOME_CATEGORY.put("minecraft:windswept_savanna", "沙漠");
        // 恶地群系
        BIOME_CATEGORY.put("minecraft:badlands", "恶地");
        BIOME_CATEGORY.put("minecraft:wooded_badlands", "恶地");
        BIOME_CATEGORY.put("minecraft:eroded_badlands", "恶地");
        // 雪原群系
        BIOME_CATEGORY.put("minecraft:snowy_plains", "雪原");
        BIOME_CATEGORY.put("minecraft:grove", "雪原");
        BIOME_CATEGORY.put("minecraft:ice_spikes", "雪原");
        BIOME_CATEGORY.put("minecraft:jagged_peaks", "雪原");
        BIOME_CATEGORY.put("minecraft:frozen_river", "雪原");
        BIOME_CATEGORY.put("minecraft:frozen_peaks", "雪原");
        BIOME_CATEGORY.put("minecraft:stony_peaks", "雪原");
        BIOME_CATEGORY.put("minecraft:snowy_slopes", "雪原");
        // 沙砾群系
        BIOME_CATEGORY.put("minecraft:windswept_hills", "沙砾");
        BIOME_CATEGORY.put("minecraft:windswept_forest", "沙砾");
        BIOME_CATEGORY.put("minecraft:windswept_gravelly_hills", "沙砾");
        // 沼泽群系
        BIOME_CATEGORY.put("minecraft:swamp", "沼泽");
        BIOME_CATEGORY.put("minecraft:mangrove_swamp", "沼泽");
        // 河流
        BIOME_CATEGORY.put("minecraft:river", "河流");
        // 蘑菇岛
        BIOME_CATEGORY.put("minecraft:mushroom_fields", "蘑菇岛");
        // 地下群系
        BIOME_CATEGORY.put("minecraft:dripstone_caves", "地下");
        BIOME_CATEGORY.put("minecraft:lush_caves", "地下");
        BIOME_CATEGORY.put("minecraft:deep_dark", "地下");
        // 下界群系
        BIOME_CATEGORY.put("minecraft:nether_wastes", "下界");
        BIOME_CATEGORY.put("minecraft:crimson_forest", "下界");
        BIOME_CATEGORY.put("minecraft:warped_forest", "下界");
        BIOME_CATEGORY.put("minecraft:soul_sand_valley", "下界");
        BIOME_CATEGORY.put("minecraft:basalt_deltas", "下界");
        // 末地群系
        BIOME_CATEGORY.put("minecraft:the_end", "末地");
        BIOME_CATEGORY.put("minecraft:the_void", "末地");
        BIOME_CATEGORY.put("minecraft:end_highlands", "末地");
        BIOME_CATEGORY.put("minecraft:end_midlands", "末地");
        BIOME_CATEGORY.put("minecraft:end_barrens", "末地");
    }

    // 所有群系大类列表
    private static final String[] ALL_CATEGORIES =
            {"海洋", "平原", "森林", "沙漠",
                    "恶地", "雪原", "沙砾", "沼泽",
                    "河流", "蘑菇岛", "地下",
                    "下界", "末地"};

    // 所有群系分类的条件关键词映射
    private static final Map<String, String>
            CATEGORY_KEYWORDS = new HashMap<>();
    static {
        CATEGORY_KEYWORDS.put("海洋", "海洋");
        CATEGORY_KEYWORDS.put("平原", "平原");
        CATEGORY_KEYWORDS.put("森林", "森林");
        CATEGORY_KEYWORDS.put("沙漠", "沙漠");
        CATEGORY_KEYWORDS.put("恶地", "恶地");
        CATEGORY_KEYWORDS.put("雪原", "雪原");
        CATEGORY_KEYWORDS.put("沙砾", "沙砾");
        CATEGORY_KEYWORDS.put("沼泽", "沼泽");
        CATEGORY_KEYWORDS.put("河流", "河流");
        CATEGORY_KEYWORDS.put("蘑菇岛", "蘑菇岛");
        CATEGORY_KEYWORDS.put("地下", "地下");
        CATEGORY_KEYWORDS.put("下界", "下界");
        CATEGORY_KEYWORDS.put("末地", "末地");
        // 条件文本中的别名映射
        CATEGORY_KEYWORDS.put("地狱", "下界");
        CATEGORY_KEYWORDS.put("下届", "下界");
        CATEGORY_KEYWORDS.put("主岛", "末地");
    }

    /**
     * 用世界维度名分类（最可靠）
     * 世界名含 the_nether → 下界
     * 世界名含 the_end → 末地
     * 其余 → 主世界
     */
    public String mapBiomeCategory(
            String rawKey, String worldName) {
        // 下界维度优先用世界名
        if (worldName != null) {
            String w = worldName.toLowerCase();
            if (w.contains("nether"))
                return "下界";
            if (w.contains("end"))
                return "末地";
        }
        // 用群系映射表
        if (rawKey != null) {
            String cat =
                    BIOME_CATEGORY.get(
                            rawKey.toLowerCase());
            if (cat != null) return cat;
        }
        return "主世界";
    }


// ========== 解析为区块 ==========

    private List<Section> parseIntoSections(
            List<String> lines) {
        boolean hasHash = false;
        boolean hasDash = false;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("#")) hasHash = true;
            if (t.startsWith("----")
                    || t.startsWith("====")
                    || t.startsWith("****"))
                hasDash = true;
        }

        if (hasHash) {
            return parseHashSections(lines);
        } else if (hasDash) {
            List<String> converted =
                    new ArrayList<>();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("----")
                        || t.startsWith("====")
                        || t.startsWith("****")) {
                    String title = t
                            .replaceAll(
                                    "^[-=*]+\\s*",
                                    "")
                            .replaceAll(
                                    "\\s*[-=*]+$",
                                    "")
                            .trim();
                    if (title.isEmpty())
                        continue;
                    converted.add("# " + title);
                } else {
                    converted.add(l);
                }
            }
            return parseHashSections(converted);
        }
        return new ArrayList<>();
    }

    private List<Section> parseHashSections(
            List<String> lines) {
        List<Section> sections =
                new ArrayList<>();
        String curTitle = null;
        List<String> curLines =
                new ArrayList<>();

        for (String raw : lines) {
            String t = raw.trim();
            if (t.startsWith("#")) {
                if (curTitle != null) {
                    sections.add(new Section(
                            curTitle,
                            new ArrayList<>(
                                    curLines)));
                }
                curTitle = t.replaceFirst(
                        "^#+\\s*", "").trim();
                curLines = new ArrayList<>();
            } else if (t.isEmpty()) {
                // skip
            } else {
                String cond = t.replaceFirst(
                        "^-\\s+", "").trim();
                if (!cond.isEmpty()
                        && curTitle != null) {
                    curLines.add(cond);
                }
            }
        }
        if (curTitle != null) {
            sections.add(new Section(
                    curTitle, curLines));
        }
        return sections;
    }

    private List<Section> buildFlatSections(
            List<String> lines,
            String category) {
        List<String> conditions =
                new ArrayList<>();
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String cond = t.replaceFirst(
                    "^-\\s+", "").trim();
            if (!cond.isEmpty()) {
                conditions.add(cond);
            }
        }
        if (conditions.isEmpty())
            return new ArrayList<>();
        List<Section> list =
                new ArrayList<>();
        list.add(new Section(
                "第一阶段", conditions));
        return list;
    }

    // ========== 文件加载入口 ==========

    private void loadAllQuests() {
        loadCategory("新人任务");
        loadCategory("主线任务");
        loadCategory("支线任务");
    }

    private void loadCategory(String name) {
        plugin.getLogger().info(
                "[QuestTracker] " + name
                        + ": 扫描中...");
        File folder = new File(
                plugin.getDataFolder(),
                name);
        String path = "plugins/Sdf1_login/"
                + name + "/";

        if (!folder.exists()) {
            folder.mkdirs();
            plugin.getLogger().warning(
                    "[QuestTracker] 已创建目录: "
                            + path);
            questCache.put(name,
                    new ArrayList<>());
            return;
        }

        File[] mdFiles = folder.listFiles(
                (d, n) -> n.endsWith(".md"));

        if (mdFiles == null
                || mdFiles.length == 0) {
            plugin.getLogger().warning(
                    "[QuestTracker] " + path
                            + " 下无 .md 文件");
            questCache.put(name,
                    new ArrayList<>());
            return;
        }
        Arrays.sort(mdFiles);

        plugin.getLogger().info(
                "[QuestTracker] " + name
                        + ": 发现 " + mdFiles.length
                        + " 个文件，开始扫描...");

        // 合并所有文件的阶段
        TreeMap<Integer, QuestFile> merged =
                new TreeMap<>();
        int fileCount = 0;

        for (int fi = 0;
             fi < mdFiles.length; fi++) {
            File file = mdFiles[fi];
            List<String> lines =
                    readFileLines(file);

            if (lines.isEmpty()) {
                plugin.getLogger().warning(
                        "[QuestTracker] "
                                + file.getName()
                                + ": 空文件，跳过");
                continue;
            }

            List<Section> sections =
                    parseIntoSections(lines);

            if (sections.isEmpty()) {
                sections = buildFlatSections(
                        lines, name);
            }

            if (sections.isEmpty()) {
                plugin.getLogger().warning(
                        "[QuestTracker] "
                                + file.getName()
                                + ": 无法识别，跳过");
                continue;
            }

            // 每个文件独立处理阶段号
            int defaultNum = fi + 1;
            buildSingleFileStages(
                    sections, name,
                    merged, defaultNum);

            fileCount++;
        }

        if (merged.isEmpty()) {
            plugin.getLogger().warning(
                    "[QuestTracker] " + name
                            + ": 无有效任务数据");
            questCache.put(name,
                    new ArrayList<>());
            return;
        }

        // 日志
        for (Map.Entry<Integer, QuestFile> en
                : merged.entrySet()) {
            int num = en.getKey() >= 1000
                    ? en.getKey() - 999
                    : en.getKey();
            plugin.getLogger().info(
                    "[QuestTracker] " + name
                            + " 阶段" + num
                            + " \""
                            + en.getValue().displayName
                            + "\" → 条件"
                            + en.getValue()
                            .conditions.size()
                            + "项, 详情"
                            + en.getValue()
                            .details.size()
                            + "项, 奖励"
                            + en.getValue()
                            .rewards.size()
                            + "项");
        }

        List<QuestFile> list =
                new ArrayList<>(
                        merged.values());
        questCache.put(name, list);

        plugin.getLogger().info(
                "[QuestTracker] " + name
                        + ": 解析完成 → "
                        + list.size() + " 个阶段"
                        + " (来自 " + fileCount
                        + " 个文件)");
    }

    private Integer extractStageNumber(
            String title) {
        if (title == null) return null;
        String t = title.toLowerCase().trim();

        // 含阶段/stage/关/chapter 关键词
        boolean isStage =
                t.contains("阶段")
                        || t.contains("stage")
                        || t.contains("关卡")
                        || t.contains("章节")
                        || t.contains("chapter");

        if (isStage) {
            String digits = title
                    .replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    return Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
            return parseChineseNumber(title);
        }

        // 无关键词但以数字开头（如 "1. 挖掘"）
        String trimmed = t
                .replaceAll("^[.、\\s]+", "")
                .trim();
        if (trimmed.length() > 0
                && Character.isDigit(
                trimmed.charAt(0))) {
            String num = trimmed
                    .replaceAll("[^0-9]", "");
            if (!num.isEmpty()) {
                try {
                    return Integer.parseInt(num);
                } catch (NumberFormatException e) {
                    // fall through
                }
            }
        }

        return null;
    }
// ========== 领取记录 ==========

    /**
     * 标记已领取某阶段奖励
     */
    public void markClaimed(
            String player, QuestFile qf) {
        if (player == null || qf == null)
            return;
        // 用阶段编号代替显示名，保证稳定
        int num = getStageNumber(qf);
        String key = "claimed_"
                + qf.fileName + "_" + num;
        storage.setCondition(player, key);
    }

    public boolean hasClaimed(
            String player, QuestFile qf) {
        if (player == null || qf == null)
            return false;
        int num = getStageNumber(qf);
        String key = "claimed_"
                + qf.fileName + "_" + num;
        return toBool(
                storage.getCondition(
                        player, key));
    }

    private int parseChineseNumber(
            String title) {
        String[] cn = {"一", "二", "三",
                "四", "五", "六", "七",
                "八", "九", "十"};
        if (title.contains("十")) {
            for (int j = 0; j < 9; j++) {
                if (title.contains(cn[j])) {
                    return 10 + j + 1;
                }
            }
            return 10;
        }
        for (int i = 0; i < 9; i++) {
            if (title.contains(cn[i])) {
                return i + 1;
            }
        }
        return 0;
    }

    // ========== md 解析核心 ==========

    /**
     * 读取文件所有行
     */
    private List<String> readFileLines(
            File file) {
        List<String> lines =
                new ArrayList<>();
        try {
            BufferedReader br =
                    new BufferedReader(
                            new FileReader(file));
            String line;
            while ((line = br.readLine())
                    != null) {
                lines.add(line);
            }
            br.close();
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[QuestTracker] 读取 "
                            + file.getName()
                            + " 失败: "
                            + e.getMessage());
        }
        return lines;
    }

    /**
     * 自动检测格式并解析单个文件
     */
    private List<QuestFile> parseMdFile(
            File file, String category) {
        List<String> lines =
                readFileLines(file);
        if (lines.isEmpty())
            return new ArrayList<>();

        // 检测分隔符
        boolean hasHash = false;
        boolean hasDash = false;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith("#"))
                hasHash = true;
            if (t.startsWith("----")
                    || t.startsWith("====")
                    || t.startsWith("****"))
                hasDash = true;
        }

        if (hasHash) {
            // # 分隔格式
            return parseSections(
                    lines, category);
        } else if (hasDash) {
            // ---- 分隔格式：转换为 # 后复用
            List<String> converted =
                    new ArrayList<>();
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("----")
                        || t.startsWith("====")
                        || t.startsWith("****")) {
                    String title = t
                            .replaceAll(
                                    "^[-=*]+\\s*",
                                    "")
                            .replaceAll(
                                    "\\s*[-=*]+$",
                                    "")
                            .trim();
                    if (title.isEmpty())
                        title = "separator";
                    converted.add("# " + title);
                } else {
                    converted.add(l);
                }
            }
            return parseSections(
                    converted, category);
        } else {
            // 无分隔符：整文件一个阶段
            return parseFlat(lines, category);
        }
    }

    /**
     * # 分隔格式 → 区块 → 阶段
     */
    private List<QuestFile> parseSections(
            List<String> lines,
            String category) {
        // 拆分为区块
        List<Section> sections =
                new ArrayList<>();
        String curTitle = null;
        List<String> curLines =
                new ArrayList<>();

        for (String raw : lines) {
            String t = raw.trim();

            // # 开头 → 新区块
            if (t.startsWith("#")) {
                if (curTitle != null) {
                    sections.add(
                            new Section(
                                    curTitle,
                                    new ArrayList<>(
                                            curLines)));
                }
                curTitle = t.replaceFirst(
                        "^#+\\s*", "").trim();
                curLines = new ArrayList<>();
            }
            // 空行/分隔线 → 跳过
            else if (t.isEmpty()
                    || t.startsWith("----")
                    || t.startsWith("====")
                    || t.startsWith("***")) {
                // skip
            }
            // 普通行 → 区块内容
            else {
                String cond = t.replaceFirst(
                        "^-\\s+", "").trim();
                if (!cond.isEmpty()) {
                    curLines.add(cond);
                }
            }
        }
        // 保存最后一个区块
        if (curTitle != null) {
            sections.add(
                    new Section(
                            curTitle, curLines));
        }

        // 直接转为 QuestFile 列表
        List<QuestFile> result =
                new ArrayList<>();
        int num = 1;
        for (Section sec : sections) {
            QuestFile qf = new QuestFile();
            qf.displayName =
                    extractDisplayName(
                            sec.title);
            qf.fileName = category;
            qf.conditions =
                    new ArrayList<>(sec.lines);
            qf.details = new ArrayList<>();
            qf.rewards = new ArrayList<>();
            result.add(qf);
            num++;
        }
        return result;
    }

        /**
         * 无分隔符：整文件 = 一个阶段
         */
    private List<QuestFile> parseFlat(
            List<String> lines,
            String category) {
        List<QuestFile> list =
                new ArrayList<>();
        QuestFile qf = makeDefaultStage(
                category);
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String cond = t.replaceFirst(
                    "^-\\s+", "").trim();
            if (!cond.isEmpty()) {
                qf.conditions.add(cond);
            }
        }
        if (!qf.conditions.isEmpty()) {
            list.add(qf);
        }
        return list;
    }
// ========== Vault 经济奖励发放 ==========

    /**
     * 发放阶段奖励
     * 含经济内容的走 Vault API
     */
    public void claimRewards(
            Player player,
            QuestFile qf) {
        if (qf == null
                || qf.rewards == null
                || qf.rewards.isEmpty()) {
            player.sendMessage(
                    "§c无可领取的奖励");
            return;
        }

        net.milkbowl.vault.economy.Economy econ =
                ((Main) plugin).getEconomy();

        int sentCount = 0;

        for (String reward : qf.rewards) {
            String rt = reward.trim();
            if (rt.isEmpty()) continue;

            // 检查可选奖励标记
            if (hasOptionalMarker(rt)) {
                if (!areAllConditionsMet(player.getName(), qf))
                    player.sendMessage(
                            "§7[可选奖励] "
                                    + "需完成所有可选条件: "
                                    + rt);
                    continue;
                }


            // 按 + 分割子项
            String[] parts =
                    rt.split("[+，]");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                // 尝试发经济
                double amt =
                        extractEconomyAmount(part);
                if (amt > 0 && econ != null) {
                    double before =
                            econ.getBalance(player);
                    econ.depositPlayer(
                            player, amt);
                    player.sendMessage(
                            "§a§l[任务奖励] §f"
                                    + part
                                    + " §7(余额: §e"
                                    + String.format(
                                    "%.2f", before)
                                    + " §7→ §a"
                                    + String.format(
                                    "%.2f",
                                    econ.getBalance(
                                            player))
                                    + "§7)");
                    // 通知提成系统
                    if (plugin instanceof Main) {
                        ((Main) plugin)
                                .getCommission()
                                .payCommission(
                                        player
                                                .getName(),
                                        amt);
                    }

                    sentCount++;
                    continue;
                }

                // 尝试发物品
                java.util.List<ItemStack> items =
                        parseRewardItems(part);
                if (!items.isEmpty()) {
                    for (ItemStack is : items) {
                        player.getInventory()
                                .addItem(is);
                    }
                    player.sendMessage(
                            "§a§l[任务奖励] §f"
                                    + part);
                    sentCount++;
                    continue;
                }

                // 非经济非物品 → 仅消息
                player.sendMessage(
                        "§a§l[任务奖励] §f"
                                + part);
                sentCount++;
            }
        }

        plugin.getLogger().info(
                "[QuestTracker] "
                        + player.getName()
                        + " 领取了 \""
                        + qf.displayName
                        + "\" 共 " + sentCount
                        + " 项奖励");
    }
    /**
     * 检查奖励文本是否含可选标记
     */
    private boolean hasOptionalMarker(
            String text) {
        return text.contains("(可选)")
                || text.contains("(进阶)")
                || text.contains("【可选】")
                || text.contains("【进阶】");
    }

    /**
     * 检查阶段所有条件（含可选）是否全部满足
     * 用于决定可选奖励是否发放
     */
    private boolean areAllConditionsMet(
            String player, QuestFile qf) {
        if (qf.conditions == null
                || qf.conditions.isEmpty())
            return true;
        for (String cond : qf.conditions) {
            if (!isConditionMet(player, cond))
                return false;
        }
        return true;
    }

    /**
     * 从奖励文本中提取经济金额
     * 支持: "500游戏币" "500" "金币500"
     * "$500" "1000-5000"(随机) "1000~5000"
     */
    private double extractEconomyAmount(
            String text) {
        String t = text.replaceAll(" ", "");

        // 必须包含经济关键词才算经济奖励
        boolean hasEcon =
                t.contains("游戏币")
                        || t.contains("金币")
                        || t.contains("积分")
                        || t.contains("块钱")
                        || t.contains("元")
                        || t.contains("$")
                        || t.contains("经济")
                        || t.contains("余额")
                        || t.contains("发钱");

        if (!hasEcon) return 0;

        // 范围: "1000-5000" 或 "1000~5000"
        Matcher rm = Pattern
                .compile("(\\d+)[-~](\\d+)")
                .matcher(t);
        if (rm.find()) {
            double min = Double.parseDouble(
                    rm.group(1));
            double max = Double.parseDouble(
                    rm.group(2));
            return min + Math.random()
                    * (max - min);
        }

        // 纯数字（含 $ 前缀）
        Matcher nm = Pattern
                .compile("\\$?(\\d+(\\.\\d+)?)")
                .matcher(t);
        if (nm.find()) {
            return Double.parseDouble(
                    nm.group(1));
        }

        return 0;
    }
    /**
     * 解析奖励物品文本
     * "皮革套装*1" → 4件皮革装备
     * "木剑*1" → 1把木剑
     * "钻石*64" → 64颗钻石
     */
    private List<ItemStack> parseRewardItems(
            String text) {
        List<ItemStack> result =
                new ArrayList<>();

        // 去掉条件前缀
        // 如 "完成注册登录：皮革套装*1"
        if (text.contains("：")) {
            text = text.substring(
                    text.indexOf("：") + 1);
        }
        if (text.contains(":")) {
            text = text.substring(
                    text.indexOf(":") + 1);
        }

        // 按 + 分割多个物品
        String[] parts =
                text.split("[+，]");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            // 跳过经济文本
            if (extractEconomyAmount(part) > 0)
                continue;

            int amount = 1;
            String itemName = part;

            // 解析 *N 数量
            if (part.contains("*")) {
                String[] sp =
                        part.split("\\*");
                itemName = sp[0].trim();
                try {
                    amount = Integer.parseInt(
                            sp[1].trim());
                } catch (Exception e) {
                    amount = 1;
                }
            }

            List<Material> mats =
                    matchMaterials(itemName);
            for (Material m : mats) {
                int give = m.name()
                        .contains("HELMET")
                        || m.name()
                        .contains("CHESTPLATE")
                        || m.name()
                        .contains("LEGGINGS")
                        || m.name()
                        .contains("BOOTS")
                        ? 1 : amount;
                result.add(
                        new ItemStack(m, give));
            }
        }
        return result;
    }

    /**
     * 中文物品名 → Material 列表
     * 套装返回多个部件
     */
    private List<Material> matchMaterials(
            String name) {
        List<Material> list =
                new ArrayList<>();
        String n = name.toLowerCase().trim();

        // 套装
        if (n.contains("皮革套装")
                || n.contains("皮套装")) {
            list.add(Material.LEATHER_HELMET);
            list.add(Material.LEATHER_CHESTPLATE);
            list.add(Material.LEATHER_LEGGINGS);
            list.add(Material.LEATHER_BOOTS);
            return list;
        }
        if (n.contains("铁套装")
                || n.contains("铁甲")) {
            list.add(Material.IRON_HELMET);
            list.add(Material.IRON_CHESTPLATE);
            list.add(Material.IRON_LEGGINGS);
            list.add(Material.IRON_BOOTS);
            return list;
        }
        if (n.contains("钻石套装")
                || n.contains("钻甲")) {
            list.add(Material.DIAMOND_HELMET);
            list.add(Material.DIAMOND_CHESTPLATE);
            list.add(Material.DIAMOND_LEGGINGS);
            list.add(Material.DIAMOND_BOOTS);
            return list;
        }
        // 木制工具套装
        if (n.contains("木制工具")
                || n.contains("木工具")) {
            list.add(Material.WOODEN_SWORD);
            list.add(Material.WOODEN_PICKAXE);
            list.add(Material.WOODEN_AXE);
            list.add(Material.WOODEN_SHOVEL);
            return list;
        }
        if (n.contains("石制工具")
                || n.contains("石工具")) {
            list.add(Material.STONE_SWORD);
            list.add(Material.STONE_PICKAXE);
            list.add(Material.STONE_AXE);
            list.add(Material.STONE_SHOVEL);
            return list;
        }
        if (n.contains("铁工具")) {
            list.add(Material.IRON_SWORD);
            list.add(Material.IRON_PICKAXE);
            list.add(Material.IRON_AXE);
            list.add(Material.IRON_SHOVEL);
            return list;
        }

        // 单个物品
        if (n.contains("木剑"))
            list.add(Material.WOODEN_SWORD);
        else if (n.contains("木镐"))
            list.add(Material.WOODEN_PICKAXE);
        else if (n.contains("铁剑"))
            list.add(Material.IRON_SWORD);
        else if (n.contains("铁镐"))
            list.add(Material.IRON_PICKAXE);
        else if (n.contains("钻石剑"))
            list.add(Material.DIAMOND_SWORD);
        else if (n.contains("钻石镐"))
            list.add(Material.DIAMOND_PICKAXE);
        else if (n.contains("钻石"))
            list.add(Material.DIAMOND);
        else if (n.contains("铁锭"))
            list.add(Material.IRON_INGOT);
        else if (n.contains("金锭")
                || n.contains("金块"))
            list.add(Material.GOLD_INGOT);
        else if (n.contains("煤炭"))
            list.add(Material.COAL);
        else if (n.contains("木棍"))
            list.add(Material.STICK);
        else if (n.contains("弓"))
            list.add(Material.BOW);
        else if (n.contains("箭"))
            list.add(Material.ARROW);
        else if (n.contains("床"))
            list.add(Material.RED_BED);
        else {
            // 尝试直接匹配 Material 名
            try {
                list.add(Material.valueOf(
                        n.toUpperCase()
                                .replaceAll("[^A-Z_]",
                                        "")));
            } catch (Exception e) {
                // 未知物品
            }
        }
        return list;
    }

    private QuestFile getOrCreateStage(
            TreeMap<Integer, QuestFile> map,
            int key,
            String category) {
        if (!map.containsKey(key)) {
            QuestFile qf = new QuestFile();
            qf.displayName = "阶段 " + key;
            qf.fileName = category;
            qf.conditions = new ArrayList<>();
            qf.details = new ArrayList<>();
            qf.rewards = new ArrayList<>();
            map.put(key, qf);
        }
        return map.get(key);
    }

    /**
     * 单个文件的区块 → 阶段
     * defaultStageNum: 文件无阶段标题时的默认编号
     */
    private void buildSingleFileStages(
            List<Section> sections,
            String category,
            TreeMap<Integer, QuestFile> stageMap,
            int defaultStageNum) {
        Integer curKey = null;

        for (Section sec : sections) {
            Integer stageNum =
                    extractStageNumber(sec.title);

            if (stageNum != null) {
                curKey = stageNum;
            } else if (curKey == null) {
                curKey = defaultStageNum;
            }

            QuestFile qf = getOrCreateStage(
                    stageMap, curKey, category);

            String displayName =
                    extractDisplayName(sec.title);

            // 设置显示名
            if (!displayName.isEmpty()
                    && qf.displayName
                    .startsWith("阶段 ")) {
                qf.displayName = displayName;
            }

            // 路由
            if (stageNum != null
                    && sec.lines.isEmpty()) {
                if (!displayName.isEmpty()) {
                    qf.displayName = displayName;
                }
            }
            else if (isRewardTitle(sec.title)) {
                // 奖励 → 只进 rewards
                qf.rewards.addAll(sec.lines);
            }
            else if (isDetailsTitle(sec.title)) {
                // 详情 → 进 details + conditions
                qf.details.addAll(sec.lines);
                for (String line : sec.lines) {
                    if (!qf.conditions
                            .contains(line)) {
                        qf.conditions.add(line);
                    }
                }
            }
            else if (isConditionsTitle(
                    sec.title)) {
                // 条件 → 只进 conditions
                qf.conditions.addAll(sec.lines);
            }
            else {
                // 其他 → 条件
                qf.conditions.addAll(sec.lines);
            }
        }
    }

    /**
     * "主线任务-斗志昂扬" → "斗志昂扬"
     * "支线-钓鱼" → "钓鱼"
     * "阶段 1" → "阶段 1"
     * "第一阶段" → "第一阶段"
     */
    private String extractDisplayName(
            String title) {
        if (title == null) return "";
        // 按 - 或 — 或 – 分割
        String[] parts = title.split(
                "\\s*[-—–]\\s*", 2);
        if (parts.length >= 2) {
            String right = parts[1].trim();
            if (!right.isEmpty()) {
                return right;
            }
        }
        return title;
    }
    private DatabaseManager getDb() {
        return ((Main) plugin).getDb();
    }

    private boolean isDetailsTitle(
            String title) {
        String t = title.toLowerCase();
        return t.contains("任务详情")
                || t.contains("详情")
                || t.contains("任务说明")
                || t.contains("任务目标")
                || t.contains("description")
                || t.contains("任务要求");
    }

    private boolean isConditionsTitle(
            String title) {
        String t = title.toLowerCase();
        return t.contains("解锁条件")
                || t.contains("条件")
                || t.contains("前置条件")
                || t.contains("前置要求")
                || t.contains("解锁")
                || t.contains("condition");
    }


    private void ensureStage(
            TreeMap<Integer, QuestFile> map,
            String category,
            QuestFile current,
            int counter) {
        if (current != null) return;
        // 用大数字确保排在有编号阶段后面
        int key = 1000 + counter;
        QuestFile qf = new QuestFile();
        qf.displayName = "阶段 " + (counter + 1);
        qf.fileName = category;
        qf.conditions = new ArrayList<>();
        qf.rewards = new ArrayList<>();
        map.put(key, qf);
        // 更新调用方的引用（通过返回值）
        // 这里用 map 操作，current 无法直接更新
        // 所以改用返回值方式
    }
    /**
     * 自动将条件中的奖励内容移到 rewards
     */
    private void separateRewards(QuestFile qf) {
        List<String> remaining =
                new ArrayList<>();
        for (String line : qf.conditions) {
            if (isRewardContent(line)) {
                if (!qf.rewards.contains(line)) {
                    qf.rewards.add(line);
                }
            } else {
                remaining.add(line);
            }
        }
        qf.conditions = remaining;
    }
    private boolean checkStageDependency(
            String player,
            String condition) {
        Matcher m = Pattern.compile(
                        "第([一二三四五六七八九十\\d]+)"
                                + "阶段")
                .matcher(condition);

        while (m.find()) {
            String numStr = m.group(1);
            int stageNum =
                    parseChineseNumber(numStr);
            if (stageNum == 0) {
                try {
                    stageNum =
                            Integer.parseInt(numStr);
                } catch (Exception e) {
                    return false;
                }
            }

            boolean stageDone = false;
            for (List<QuestFile> quests
                    : questCache.values()) {
                for (QuestFile qf : quests) {
                    Integer qfNum =
                            extractStageNumber(
                                    qf.displayName);
                    if (qfNum != null
                            && qfNum == stageNum) {
                        if (isStageComplete(player,
                                qf.conditions)) {
                            stageDone = true;
                        }
                        break;
                    }
                }
                if (stageDone) break;
            }

            if (!stageDone) return false;
        }
        return true;
    }

    /**
     * 判断一行内容是否为奖励
     */
    private boolean isRewardContent(
            String line) {
        String l = line.toLowerCase();
        // 含货币关键词
        if (l.contains("游戏币")
                || l.contains("金币")
                || l.contains("积分")
                || l.contains("钻石")
                || l.contains("发钱")
                || l.contains("发放")
                || l.contains("经验")
                || l.contains("等级")
                || l.contains("经济")
                || l.contains("余额")
                || l.contains("块钱"))
            return true;
        // 纯数字+单位（如 "500游戏币"）
        if (l.matches(
                ".*\\d+\\s*(游戏币|金币|元|积分|"
                        + "钻石|块钱|经验|级|经济|余额).*"))
            return true;
        // $前缀（如 "$500"）
        if (l.matches(".*\\$\\d+.*"))
            return true;
        // 范围格式（如 "1000-5000" 或 "1000~5000"）
        if (l.matches(".*\\d+[-~]\\d+.*")
                && !l.contains("阶段")
                && !l.contains("stage"))
            return true;
        return false;
    }

    // ========== 标题分类 ==========

    private boolean isStageTitle(
            String title) {
        String t = title.toLowerCase();
        return t.contains("阶段")
                || t.contains("stage")
                || t.contains("第一")
                || t.contains("第二")
                || t.contains("第三")
                || t.contains("第四")
                || t.contains("第五");
    }

    private boolean isRewardTitle(
            String title) {
        String t = title.toLowerCase();
        return t.contains("奖励")
                || t.contains("奖品")
                || t.contains("报酬")
                || t.contains("reward")
                || t.contains("奖赏")
                || t.contains("礼包内容")
                || t.contains("礼包")
                || t.contains("物品奖励");
    }


    private QuestFile makeDefaultStage(
            String category) {
        QuestFile qf = new QuestFile();
        qf.displayName = "第一阶段";
        qf.fileName = category;
        qf.conditions = new ArrayList<>();
        qf.rewards = new ArrayList<>();
        return qf;
    }

    private static class Section {
        final String title;
        final List<String> lines;
        Section(String title,
                List<String> lines) {
            this.title = title;
            this.lines = lines;
        }
    }

    // ========== 公开查询 ==========

    public List<QuestFile> getQuests(
            String category) {
        List<QuestFile> list =
                questCache.get(category);
        return list != null ? list
                : new ArrayList<>();
    }

    public int getCompletedStageCount(
            String player,
            List<QuestFile> quests) {
        int count = 0;
        if (quests == null) return 0;
        for (QuestFile qf : quests) {
            if (isStageComplete(player,
                    qf.conditions)) {
                count++;
            }
        }
        return count;
    }

    public QuestFile getCurrentStage(
            String player,
            List<QuestFile> quests) {
        if (quests == null) return null;
        for (QuestFile qf : quests) {
            if (!isStageComplete(
                    player, qf.conditions)) {
                return qf;
            }
        }
        return null;
    }

    public int getStageNumber(
            List<QuestFile> quests,
            QuestFile qf) {
        if (quests == null || qf == null)
            return 0;
        return quests.indexOf(qf) + 1;
    }

    public int getStageNumber(
            QuestFile qf) {
        if (qf == null) return 0;
        List<QuestFile> quests =
                getQuests(qf.fileName);
        return getStageNumber(quests, qf);
    }

    public boolean isStageCompleted(
            String player,
            QuestFile qf) {
        if (qf == null) return false;
        return isStageComplete(
                player, qf.conditions);
    }

    public List<String> getUnmetConditions(
            String player,
            List<String> conditions) {
        List<String> unmet =
                new ArrayList<>();
        if (conditions == null) return unmet;
        for (String cond : conditions) {
            if (isOptional(cond)) continue;
            if (!isConditionMet(
                    player, cond)) {
                unmet.add(
                        getProgress(
                                player, cond));
            }
        }
        return unmet;
    }
    public String getProgress(
            String player, String rawCond) {
        if (player == null || rawCond == null)
            return "§7未知";
        String key = stripOptional(rawCond);

        if (key.contains("打卡")
                && key.contains("群系")) {
            String biome =
                    extractBiome(key);
            if ("ALL".equals(biome)) {
                int total = getCounter(
                        player, "biome_ALL");
                return "§7群系打卡: §e"
                        + total + "/1";
            }
            if (!biome.isEmpty()) {
                int catCount = getCounter(
                        player,
                        "biomecat_" + biome);
                return "§7打卡 " + biome
                        + "群系: §e"
                        + catCount + "/1";
            }
            int total = getCounter(
                    player, "biome_ALL");
            return "§7群系打卡: §e"
                    + total + "/1";
        }

        if (key.contains("完成")
                && key.contains("阶段")) {
            boolean done = checkStageDependency(
                    player, key);
            return done
                    ? "§a✓ " + key
                    : "§c✗ " + key;
        }

        if ("完成注册登录".equals(key)) {
            boolean done = getDb() != null
                    && getDb().userExists(
                    player);
            return done
                    ? "§a✓ 完成注册登录"
                    : "§c✗ 完成注册登录";
        }

        if (key.contains("绑定Email")
                || key.contains("绑定邮箱")) {
            Object val =
                    getDb().getField(
                            player, "email");
            boolean done = val != null
                    && !val.toString().isEmpty();
            return done
                    ? "§a✓ 绑定Email"
                    : "§c✗ 绑定Email";
        }

        if (key.contains("在线")
                && key.contains("小时")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            long cur = getCounterLong(
                    player, "PLAYTIME");
            return "§7在线: §e"
                    + formatMs(cur) + "/"
                    + needed + "小时";
        }

        if (key.contains("打招呼")
                || key.contains("打个招呼")
                || key.contains("发消息")
                || key.contains("聊天")) {
            int cur = getCounter(
                    player, "CHAT");
            return "§7聊天: §e"
                    + cur + "/1";
        }

        if ((key.contains("破坏")
                || key.contains("放置"))
                && key.contains("方块")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int total = getCounter(
                    player, "MINE:ALL")
                    + getCounter(
                    player, "PLACE:ALL");
            return "§7方块操作: §e"
                    + total + "/" + needed;
        }

        if (key.contains("连续")
                && key.contains("登录")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            Object val =
                    getDb().getField(
                            player,
                            "checkin_streak");
            int streak = val instanceof Number
                    ? ((Number) val).intValue()
                    : 0;
            return "§7连续登录: §e"
                    + streak + "/" + needed + "天";
        }

        if (key.startsWith("MINE:")) {
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                int needed =
                        parseInt(parts[2]);
                int cur = getCounter(
                        player,
                        "MINE:" + parts[1]);
                return "§7挖 " + parts[1]
                        + ": §e" + cur
                        + "/" + needed;
            }
        }

        if (key.startsWith("KILL:")) {
            String[] parts = key.split(":");
            if (parts.length >= 3) {
                int needed =
                        parseInt(parts[2]);
                int cur = getCounter(
                        player,
                        "KILL:" + parts[1]);
                return "§7杀 " + parts[1]
                        + ": §e" + cur
                        + "/" + needed;
            }
        }

        if (key.startsWith("KILL_PLAYER")) {
            String[] parts = key.split(":");
            int needed = parts.length >= 2
                    ? parseInt(parts[1]) : 1;
            int cur = getCounter(
                    player, "KILL_PLAYER");
            return "§7击杀玩家: §e"
                    + cur + "/" + needed;
        }

        if (key.startsWith("CRAFT")) {
            String[] parts = key.split(":");
            int needed = parts.length >= 2
                    ? parseInt(parts[1]) : 1;
            int cur = getCounter(
                    player, "CRAFT");
            return "§7合成: §e"
                    + cur + "/" + needed;
        }

        if (key.startsWith("FISH")) {
            String[] parts = key.split(":");
            int needed = parts.length >= 2
                    ? parseInt(parts[1]) : 1;
            int cur = getCounter(
                    player, "FISH");
            return "§7钓鱼: §e"
                    + cur + "/" + needed;
        }

        if (key.startsWith("PLAYTIME")) {
            String[] parts = key.split(":");
            long needed = parts.length >= 2
                    ? Long.parseLong(parts[1])
                    : 0;
            long cur = getCounterLong(
                    player, "PLAYTIME");
            return "§7在线: §e"
                    + formatMs(cur) + "/"
                    + formatMs(needed);
        }

        if (key.contains("上报")
                && key.contains("村庄")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int cur = getCounter(
                    player, "REPORT_VILLAGE");
            return "§7上报村庄: §e"
                    + cur + "/" + needed;
        }

        if (key.contains("探索")
                && key.contains("村庄")) {
            int cur = getCounter(
                    player, "REPORT_VILLAGE");
            return "§7探索村庄: §e"
                    + cur + "/1";
        }

        if (key.contains("7天")
                || key.contains("七天")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int streak = getIntField(
                    player,
                    "checkin_streak");
            if (streak == 0) {
                streak = getIntField(
                        player, "streak");
            }
            if (streak == 0) {
                streak = getIntField(
                        player,
                        "total_checkin_days");
            }
            return "§7近7天登录: §e"
                    + streak + "/"
                    + needed + "天";
        }

        if (key.contains("新手任务")
                && key.contains("全部")) {
            List<QuestFile> quests =
                    getQuests("新人任务");
            int done = 0;
            for (QuestFile qf : quests) {
                if (isStageComplete(
                        player,
                        qf.conditions))
                    done++;
            }
            return "§7新手任务: §e"
                    + done + "/"
                    + quests.size();
        }

        if (key.contains("主线任务")
                && key.contains("全部")) {
            List<QuestFile> quests =
                    getQuests("主线任务");
            int done = 0;
            for (QuestFile qf : quests) {
                if (isStageComplete(
                        player,
                        qf.conditions))
                    done++;
            }
            return "§7主线任务: §e"
                    + done + "/"
                    + quests.size();
        }

        return "§7" + key;
    }


    public List<String> checkInBiome(
            String player,
            String rawBiomeKey,
            String worldName,
            double x, double y, double z) {
        List<String> matched =
                new ArrayList<>();

        // 32格去重
        String posKey =
                player + "_lastPos";
        String curPos = worldName
                + "|" + String.format(
                "%.0f", x / 32)
                + "|" + String.format(
                "%.0f", y / 32)
                + "|" + String.format(
                "%.0f", z / 32);
        String lastPos =
                lastCheckInPos.get(posKey);
        if (curPos.equals(lastPos)) {
            plugin.getLogger().info(
                    "[QuestTracker] 打卡忽略: "
                            + player
                            + " 32格内重复");
            return matched;
        }
        lastCheckInPos.put(posKey, curPos);

        String category =
                mapBiomeCategory(
                        rawBiomeKey, worldName);

        // 记录总打卡
        int allCur = getCounter(
                player, "biome_ALL");
        setCounter(player, "biome_ALL",
                allCur + 1);

        // 记录分类打卡
        if (!category.isEmpty()
                && !"主世界"
                .equals(category)) {
            String catKey =
                    "biomecat_" + category;
            int catCur = getCounter(
                    player, catKey);
            setCounter(player, catKey,
                    catCur + 1);
        }

        // 记录具体群系
        String bioKey =
                "biome_" + rawBiomeKey;
        int bioCur = getCounter(
                player, bioKey);
        setCounter(player, bioKey,
                bioCur + 1);

        // 检查所有任务条件是否匹配
        List<String> categories =
                Arrays.asList(
                        "新人任务",
                        "主线任务",
                        "支线任务");

        for (String cat : categories) {
            List<QuestFile> quests =
                    getQuests(cat);
            for (QuestFile qf : quests) {
                if (isStageCompleted(
                        player, qf))
                    continue;
                for (String cond
                        : qf.conditions) {
                    if (isOptional(cond))
                        continue;
                    String condKey =
                            stripOptional(cond);
                    if (!condKey
                            .contains("打卡")
                            || !condKey
                            .contains("群系"))
                        continue;
                    String target =
                            extractBiome(condKey);
                    boolean hit = false;
                    if ("ALL".equals(target)
                            || "风景点"
                            .equals(target)) {
                        hit = true;
                    } else if (!target.isEmpty()
                            && target.equals(
                            category)) {
                        hit = true;
                    }
                    if (hit && !matched
                            .contains(cond)) {
                        matched.add(cond);
                    }
                }
            }
        }

        return matched;
    }


    /**
     * 从条件文本中提取目标群系分类
     * "打卡上报地狱任意群系" → "下界"
     * "打卡任意海洋群系" → "海洋"
     * "打卡任意群系" → "ALL"
     * "打卡任意风景点" → ""
     */
    private String extractBiome(String text) {
        if (text == null) return "";
        // 逐个匹配分类关键词
        for (Map.Entry<String, String> en
                : CATEGORY_KEYWORDS.entrySet()) {
            if (text.contains(en.getKey())) {
                return en.getValue();
            }
        }
        if (text.contains("风景点")
                || text.contains("风景")) {
            return "ALL";
        }

        // 有"群系"但无具体分类 → ALL
        if (text.contains("群系")) {
            return "ALL";
        }
        return "";
    }


    // ========== 标记条件 ==========

    public void markConditionComplete(
            String player,
            String conditionName) {
        if (player == null
                || conditionName == null)
            return;
        storage.setCondition(
                player, conditionName);
    }

    // ========== 事件回调 ==========

    public void onBlockMined(
            String player, String material) {
        String key = "MINE:" + material;
        setCounter(player, key,
                getCounter(player, key) + 1);
        // 总计数
        setCounter(player, "MINE:ALL",
                getCounter(player, "MINE:ALL")
                        + 1);
    }
    public boolean onReportVillage(
            String player,
            String worldName,
            double x, double y, double z) {
        String dbKey = "village_lastPos";
        String curPos = worldName
                + "|" + String.format(
                "%.0f", x / 32)
                + "|" + String.format(
                "%.0f", y / 32)
                + "|" + String.format(
                "%.0f", z / 32);
        Object lastObj =
                storage.getCondition(
                        player, dbKey);
        String lastPos = lastObj != null
                ? lastObj.toString() : "";
        if (curPos.equals(lastPos)) {
            return false;
        }
        storage.setConditionRaw(
                player, dbKey, curPos);

        String key = "REPORT_VILLAGE";
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
        return true;
    }

    public void onMobKill(
            String player, String mobType) {
        String key = "KILL:" + mobType;
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
    }

    public void onPlayerKill(
            String player) {
        String key = "KILL_PLAYER";
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
    }

    public void onItemCrafted(
            String player) {
        String key = "CRAFT";
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
    }

    public void onFishCaught(
            String player) {
        String key = "FISH";
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
    }

    public void onPlayTime(
            String player, long ms) {
        String key = "PLAYTIME";
        long cur = getCounterLong(
                player, key);
        setCounter(player, key, cur + ms);
    }

    // ========== 内部：条件判定 ==========

    private boolean isStageComplete(
            String player,
            List<String> conditions) {
        if (conditions == null
                || conditions.isEmpty())
            return true;
        for (String cond : conditions) {
            if (isOptional(cond)) continue;
            if (!isConditionMet(
                    player, cond))
                return false;
        }
        return true;
    }

    public boolean isConditionMet(
            String player,
            String rawCond) {
        if (player == null
                || rawCond == null)
            return false;

        String key = stripOptional(rawCond);

        // 无条件类文本直接通过
        if (key.equals("无前级条件")
                || key.equals("无条件")
                || key.equals("无")
                || key.equals("none")
                || key.equals("无需条件")
                || key.equals("无解锁条件")) {
            return true;
        }

        if (key.contains("打卡")
                && (key.contains("群系")
                || key.contains("风景点"))) {
            String biome =
                    extractBiome(key);
            if ("ALL".equals(biome)) {
                int total = getCounter(
                        player, "biome_ALL");
                return total >= 1;
            }
            if (!biome.isEmpty()) {
                int catCount = getCounter(
                        player,
                        "biomecat_" + biome);
                return catCount >= 1;
            }
            return getCounter(
                    player, "biome_ALL") >= 1;
        }

        if (key.contains("完成")
                && key.contains("阶段")) {
            return checkStageDependency(
                    player, key);
        }

        if ("完成注册登录".equals(key)) {
            return getDb() != null
                    && getDb().userExists(
                    player);
        }

        if (key.contains("绑定Email")
                || key.contains("绑定邮箱")) {
            Object val =
                    getDb().getField(
                            player, "email");
            return val != null
                    && !val.toString().isEmpty();
        }

        if (key.contains("在线")
                && key.contains("小时")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            long cur = getCounterLong(
                    player, "PLAYTIME");
            return cur >= needed * 3600000L;
        }

        if (key.contains("打招呼")
                || key.contains("打个招呼")
                || key.contains("发消息")
                || key.contains("聊天")) {
            return getCounter(
                    player, "CHAT") >= 1;
        }

        if ((key.contains("破坏")
                || key.contains("放置"))
                && key.contains("方块")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int total = getCounter(
                    player, "MINE:ALL")
                    + getCounter(
                    player, "PLACE:ALL");
            return total >= needed;
        }

        if (key.contains("连续")
                && key.contains("登录")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            Object val =
                    getDb().getField(
                            player,
                            "checkin_streak");
            int streak = val instanceof Number
                    ? ((Number) val).intValue()
                    : 0;
            return streak >= needed;
        }

        if (key.startsWith("MINE:")) {
            String[] p = key.split(":");
            if (p.length >= 3) {
                return getCounter(player,
                        "MINE:" + p[1])
                        >= parseInt(p[2]);
            }
        }

        if (key.startsWith("KILL:")) {
            String[] p = key.split(":");
            if (p.length >= 3) {
                return getCounter(player,
                        "KILL:" + p[1])
                        >= parseInt(p[2]);
            }
        }

        if (key.startsWith("KILL_PLAYER")) {
            String[] p = key.split(":");
            int n = p.length >= 2
                    ? parseInt(p[1]) : 1;
            return getCounter(player,
                    "KILL_PLAYER") >= n;
        }

        if (key.startsWith("CRAFT")) {
            String[] p = key.split(":");
            int n = p.length >= 2
                    ? parseInt(p[1]) : 1;
            return getCounter(
                    player, "CRAFT") >= n;
        }

        if (key.startsWith("FISH")) {
            String[] p = key.split(":");
            int n = p.length >= 2
                    ? parseInt(p[1]) : 1;
            return getCounter(
                    player, "FISH") >= n;
        }

        if (key.startsWith("PLAYTIME")) {
            String[] p = key.split(":");
            long n = p.length >= 2
                    ? Long.parseLong(p[1]) : 0;
            return getCounterLong(player,
                    "PLAYTIME") >= n;
        }

        if (key.contains("上报")
                && key.contains("村庄")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            return getCounter(
                    player, "REPORT_VILLAGE")
                    >= needed;
        }

        if (key.contains("探索")
                && key.contains("村庄")) {
            return getCounter(
                    player, "REPORT_VILLAGE")
                    >= 1;
        }

        if (key.contains("7天")
                || key.contains("七天")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int streak = getIntField(
                    player,
                    "checkin_streak");
            if (streak == 0) {
                streak = getIntField(
                        player, "streak");
            }
            if (streak == 0) {
                streak = getIntField(
                        player,
                        "total_checkin_days");
            }
            return streak >= needed;
        }

        if (key.contains("新手任务")
                && key.contains("全部")) {
            List<QuestFile> quests =
                    getQuests("新人任务");
            for (QuestFile qf : quests) {
                if (!isStageComplete(
                        player,
                        qf.conditions))
                    return false;
            }
            return true;
        }

        if (key.contains("主线任务")
                && key.contains("全部")) {
            List<QuestFile> quests =
                    getQuests("主线任务");
            for (QuestFile qf : quests) {
                if (!isStageComplete(
                        player,
                        qf.conditions))
                    return false;
            }
            return true;
        }

        return false;
    }

    public void debugLogin(String player) {
        try {
            var user = getDb().getUser(player);
            if (user != null) {
                plugin.getLogger().info(
                        "[QuestTracker] "
                                + "=== " + player
                                + " 数据库全部字段 ===");
                for (var entry
                        : user.entrySet()) {
                    plugin.getLogger().info(
                            "[QuestTracker] "
                                    + entry.getKey()
                                    + " = "
                                    + entry.getValue()
                                    + " ("
                                    + (entry.getValue()
                                    != null
                                    ? entry.getValue()
                                      .getClass()
                                      .getSimpleName()
                                    : "null")
                                    + ")");
                }
            } else {
                plugin.getLogger().info(
                        "[QuestTracker] "
                                + "getUser返回null: "
                                + player);
            }
        } catch (Exception e) {
            plugin.getLogger().info(
                    "[QuestTracker] "
                            + "debug异常: "
                            + e.getMessage());
        }
    }

    // ========== 内部：计数器 ==========

    private int getCounter(
            String player, String key) {
        Object val = storage.getCondition(
                player, "cnt_" + key);
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return 0;
    }
    private int getIntField(
            String player, String field) {
        try {
            Object val = getDb().getField(
                    player, field);
            if (val instanceof Number) {
                return ((Number) val).intValue();
            }
            if (val != null) {
                return Integer.parseInt(
                        val.toString().trim());
            }
        } catch (Exception e) {
            // 忽略
        }
        return 0;
    }

    private long getCounterLong(
            String player, String key) {
        Object val = storage.getCondition(
                player, "cnt_" + key);
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        return 0;
    }

    private void setCounter(
            String player, String key,
            int value) {
        storage.setConditionRaw(
                player, "cnt_" + key,
                String.valueOf(value));
    }

    private void setCounter(
            String player, String key,
            long value) {
        storage.setConditionRaw(
                player, "cnt_" + key,
                String.valueOf(value));
    }

    // ========== 工具 ==========

    private boolean toBool(Object val) {
        if (val == null) return false;
        if (val instanceof Boolean)
            return (Boolean) val;
        if (val instanceof Number)
            return ((Number) val)
                    .intValue() != 0;
        String s = String.valueOf(val);
        return "1".equals(s)
                || "true"
                .equalsIgnoreCase(s);
    }

    public boolean isOptional(String cond) {
        if (cond == null) return false;
        String c = cond;
        return c.contains("(进阶)")
                || c.contains("(可选)")
                || c.contains("，可选)")
                || c.contains(",可选)")
                || c.contains("【可选】")
                || c.contains("【进阶】");
    }


    private String stripOptional(String cond) {
        if (cond == null) return "";
        return cond.replace("(进阶)", "")
                .replace("(可选)", "")
                .replace("，可选)", "")
                .replace(",可选)", "")
                .replace("【可选】", "")
                .replace("【进阶】", "")
                .trim();
    }

    private int parseInt(String s) {

        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
    private Object getDbField(
            String player, String field) {
        Main main = (Main) plugin;
        return main.getDb().getField(
                player, field);
    }

    /**
     * 从文本中提取第一个数字
     * "在线时间≥1小时" → 1
     * "破坏/放置方块100个" → 100
     */
    private int parseNumberFromText(
            String text) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern
                        .compile("(\\d+)")
                        .matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(
                        m.group(1));
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }
    public void onBlockPlaced(
            String player, String material) {
        String key = "PLACE:" + material;
        setCounter(player, key,
                getCounter(player, key) + 1);
        setCounter(player, "PLACE:ALL",
                getCounter(player, "PLACE:ALL")
                        + 1);
    }

    public void onPlayerChat(
            String player) {
        String key = "CHAT";
        setCounter(player, key,
                getCounter(player, key) + 1);
    }

    private String formatMs(long ms) {
        long sec = ms / 1000;
        long min = sec / 60;
        long hr = min / 60;
        if (hr > 0)
            return hr + "小时"
                    + (min % 60) + "分";
        if (min > 0)
            return min + "分"
                    + (sec % 60) + "秒";
        return sec + "秒";
    }

    // ========== 数据结构 ==========

    public static class QuestFile {
        public String displayName = "";
        public String fileName = "";
        public List<String> details =
                new ArrayList<>();
        public List<String> rewards =
                new ArrayList<>();
        public List<String> conditions =
                new ArrayList<>();

        public boolean hasConditions() {
            return conditions != null
                    && !conditions.isEmpty();
        }
    }
}
