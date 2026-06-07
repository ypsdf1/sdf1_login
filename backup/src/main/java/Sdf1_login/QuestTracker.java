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

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
// 如果 Bonds 在 Sdf1_login 包下：
import Sdf1_login.BondManager;

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
        // 岩岸/沙滩
        BIOME_CATEGORY.put("minecraft:stony_shore",
                "石岸");
// 海岸
        BIOME_CATEGORY.put("minecraft:mushroom_fields",
                "蘑菇岛");
// 苍白之园
        BIOME_CATEGORY.put(
                "minecraft:pale_garden", "森林");
// 末地小型岛屿
        BIOME_CATEGORY.put(
                "minecraft:small_end_islands", "末地");
// 主世界结构归类
        BIOME_CATEGORY.put("minecraft:village", "平原");
        BIOME_CATEGORY.put(
                "minecraft:desert_pyramid", "沙漠");
        BIOME_CATEGORY.put(
                "minecraft:jungle_pyramid", "森林");
        BIOME_CATEGORY.put(
                "minecraft:swamp_hut", "沼泽");

        // 末地群系
        BIOME_CATEGORY.put("minecraft:the_end", "末地");
        BIOME_CATEGORY.put("minecraft:the_void", "末地");
        BIOME_CATEGORY.put("minecraft:end_highlands", "末地");
        BIOME_CATEGORY.put("minecraft:end_midlands", "末地");
        BIOME_CATEGORY.put("minecraft:end_barrens", "末地");


    }

    // 所有群系大类列表
    private static final String[]
            ALL_CATEGORIES = {
            "海洋", "平原", "森林", "沙漠",
            "恶地", "雪原", "沙砾", "沼泽",
            "河流", "石岸", "蘑菇岛", "地下",
            "下界", "末地"
    };

    // ===== 结构映射 =====
    private static final Map<String, String>
            STRUCTURE_CATEGORY =
            new HashMap<>();
    static {
        // 主世界结构
        STRUCTURE_CATEGORY.put(
                "minecraft:village", "村庄");
        STRUCTURE_CATEGORY.put(
                "minecraft:desert_pyramid",
                "沙漠");
        STRUCTURE_CATEGORY.put(
                "minecraft:jungle_pyramid",
                "森林");
        STRUCTURE_CATEGORY.put(
                "minecraft:swamp_hut", "沼泽");
        STRUCTURE_CATEGORY.put(
                "minecraft:igloo", "雪原");
        STRUCTURE_CATEGORY.put(
                "minecraft:mansion", "森林");
        STRUCTURE_CATEGORY.put(
                "minecraft:pillager_outpost",
                "平原");
        STRUCTURE_CATEGORY.put(
                "minecraft:mineshaft", "地下");
        STRUCTURE_CATEGORY.put(
                "minecraft:stronghold", "地下");
        STRUCTURE_CATEGORY.put(
                "minecraft:ancient_city",
                "地下");
        STRUCTURE_CATEGORY.put(
                "minecraft:trail_ruins",
                "平原");
        STRUCTURE_CATEGORY.put(
                "minecraft:trial_chambers",
                "地下");
        STRUCTURE_CATEGORY.put(
                "minecraft:ocean_ruin", "海洋");
        STRUCTURE_CATEGORY.put(
                "minecraft:shipwreck", "海洋");
        STRUCTURE_CATEGORY.put(
                "minecraft:buried_treasure",
                "海洋");
        STRUCTURE_CATEGORY.put(
                "minecraft:ruined_portal",
                "平原");
        STRUCTURE_CATEGORY.put(
                "minecraft:fossil", "平原");
        STRUCTURE_CATEGORY.put(
                "minecraft:ancient_city",
                "地下");
        // 下界结构
        STRUCTURE_CATEGORY.put(
                "minecraft:nether_fortress",
                "下界");
        STRUCTURE_CATEGORY.put(
                "minecraft:bastion_remnant",
                "下界");
        STRUCTURE_CATEGORY.put(
                "minecraft:nether_fossil",
                "下界");
        STRUCTURE_CATEGORY.put(
                "minecraft:basalt_pillars",
                "下界");
    }

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
        CATEGORY_KEYWORDS.put("石岸", "石岸");
        CATEGORY_KEYWORDS.put("沙滩", "石岸");
        CATEGORY_KEYWORDS.put("海岸", "石岸");

    }

    /**
     * 用世界维度名分类（最可靠）
     * 世界名含 the_nether → 下界
     * 世界名含 the_end → 末地
     * 其余 → 主世界
     */
    public String mapBiomeCategory(
            String rawKey, String worldName) {
        // 优先用世界名判断维度
        if (worldName != null) {
            String w =
                    worldName.toLowerCase();
            if (w.contains("nether"))
                return "下界";
            if (w.contains("end"))
                return "末地";
        }

        // 用群系映射表
        if (rawKey != null) {
            String lower =
                    rawKey.toLowerCase();
            // 带 minecraft: 前缀的直接查
            String cat =
                    BIOME_CATEGORY.get(lower);
            if (cat != null)
                return cat;

            // 不带前缀的尝试补全
            String prefixed =
                    "minecraft:" + lower;
            cat = BIOME_CATEGORY.get(prefixed);
            if (cat != null)
                return cat;

            // 模糊匹配群系名
            for (Map.Entry<String, String> entry
                    : BIOME_CATEGORY.entrySet()) {
                if (entry.getKey()
                        .contains(lower)
                        || lower.contains(
                        entry.getKey()
                                .replace(
                                        "minecraft:",
                                        ""))) {
                    return entry.getValue();
                }
            }
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
     * 含经济内容的走 Vault API或债券
     */
    public void claimRewards(
            Player player,
            QuestFile qf) {
        if (qf == null
                || qf.rewards == null
                || qf.rewards.isEmpty()) {
            player.sendMessage("§c无可领取的奖励");
            return;
        }

        Main main = (Main) plugin;
        // ★ 读取配置：economy = Vault，bonds = 债券（默认）
        boolean useEconomy = "economy".equalsIgnoreCase(
                main.getConfigMgr().rewardChannel);

        // Vault 经济引用（仅 useEconomy 时使用）
        net.milkbowl.vault.economy.Economy econ = null;
        if (useEconomy) {
            econ = main.getEconomy();
        }

        int sentCount = 0;

        for (String reward : qf.rewards) {
            String rt = reward.trim();
            if (rt.isEmpty()) continue;

            // 检查可选奖励标记
            if (hasOptionalMarker(rt)) {
                if (!areAllConditionsMet(player.getName(), qf)) {
                    player.sendMessage(
                            "§7[可选奖励] 需完成所有可选条件: " + rt);
                    continue;
                }
            }

            // 按 + 分割子项
            String[] parts = rt.split("[+，]");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;

                // 尝试识别经济金额
                double amt = extractEconomyAmount(part);
                if (amt > 0) {
                    // ★ 根据配置分发
                    if (useEconomy && econ != null) {
                        // —— Vault 经济 ——
                        double before =
                                econ.getBalance(player);
                        econ.depositPlayer(player, amt);
                        double after =
                                econ.getBalance(player);
                        player.sendMessage(
                                "§a§l[任务奖励] §f" + part
                                        + " §7(余额: §e"
                                        + String.format("%.2f", before)
                                        + " §7→ §a"
                                        + String.format("%.2f", after)
                                        + "§7)");
                        if (plugin instanceof Main) {
                            ((Main) plugin).getCommission()
                                    .payCommission(
                                            player.getName(), amt);
                        }
                    } else {
                        // —— 债券（默认） ——
                        int bondAmt =
                                (int) Math.round(amt);
                        BondManager bonds = main.getBonds();
                        if (bonds != null) {
                            int before = bonds.getBonds(
                                    player.getName());
                            bonds.addBonds(
                                    player.getName(),
                                    bondAmt,
                                    "quest_reward",
                                    qf.displayName,
                                    "任务系统",
                                    "任务奖励：" + qf.displayName);
                            int after = bonds.getBonds(
                                    player.getName());
                            player.sendMessage(
                                    "§a§l[任务奖励] §f" + part
                                            + " §7(债券: §e"
                                            + before
                                            + " §7→ §a"
                                            + after + "§7)");
                        } else {
                            player.sendMessage(
                                    "§c债券系统不可用");
                        }
                    }
                    sentCount++;
                    continue;
                }

                // 尝试发物品
                java.util.List<ItemStack> items =
                        parseRewardItems(part);
                if (!items.isEmpty()) {
                    for (ItemStack is : items) {
                        player.getInventory().addItem(is);
                    }
                    player.sendMessage(
                            "§a§l[任务奖励] §f" + part);
                    sentCount++;
                    continue;
                }

                // 非经济非物品 → 仅消息
                player.sendMessage(
                        "§a§l[任务奖励] §f" + part);
                sentCount++;
            }
        }

        plugin.getLogger().info(
                "[QuestTracker] " + player.getName()
                        + " 预取了 \"" + qf.displayName
                        + "\" 共 " + sentCount + "项奖励");
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
                        || t.contains("发钱")
                        || t.contains("债券");  // ★ 新增


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
            if (key.contains("村民")
                    && key.contains("交易")) {
                int needed = parseNumberFromText(key);
                if (needed <= 0) needed = 1;
                int cur = getCounter(
                        player, "VILLAGER_TRADE");
                return "§7村民交易: §e"
                        + cur + "/" + needed;
            }

        }

        // ===== 击杀泛解析（支持中文列表） =====
// 匹配 "击杀10只骷髅、僵尸、苦力怕" 等
        if (key.contains("击杀") && key.contains("只")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;

            String mobPart = key;
            java.util.regex.Matcher killM =
                    java.util.regex.Pattern
                            .compile("击杀\\d+只(.+)")
                            .matcher(key);
            if (killM.find()) {
                mobPart = killM.group(1).trim();
            }

            String[] mobNames = mobPart
                    .split("[、，,，]+");
            int totalKills = 0;
            StringBuilder names = new StringBuilder();
            for (String name : mobNames) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                String entityType =
                        chineseMobToEntity(trimmed);
                if (!entityType.isEmpty()) {
                    totalKills += getCounter(
                            player,
                            "KILL:" + entityType);
                    if (names.length() > 0)
                        names.append("/");
                    names.append(trimmed);
                }
            }
            return "§7击杀" + names + ": §e"
                    + totalKills + "/" + needed;
        }

// 单一KILL:xxx:N 格式（原有逻辑保留）
        if (key.startsWith("KILL:")) {
            String[] p = key.split(":");
            if (p.length >= 3) {
                int needed = parseInt(p[2]);
                int cur = getCounter(player,
                        "KILL:" + p[1]);
                return "§7击杀" + p[1] + ": §e"
                        + cur + "/" + needed;
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
        // ===== 泛解析：击杀类进度 =====
        if (key.contains("击杀") || key.contains("猎杀")
                || key.contains("消灭")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            List<String> mobTypes = extractMobTypes(key);
            if (!mobTypes.isEmpty()) {
                int total = 0;
                StringBuilder names =
                        new StringBuilder();
                for (int i = 0;
                     i < mobTypes.size(); i++) {
                    total += getCounter(player,
                            "KILL:" + mobTypes.get(i));
                    if (i > 0) names.append("/");
                    names.append(reverseMobName(
                            mobTypes.get(i)));
                }
                return "§7击杀" + names + ": §e"
                        + total + "/" + needed;
            }
        }

        // ===== 泛解析：村民交易进度 =====
        if (key.contains("交易")
                && (key.contains("村民")
                || key.contains("与村民"))) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int cur = getCounter(player, "TRADE");
            return "§7村民交易: §e"
                    + cur + "/" + needed;
        }
        // ===== 泛解析：采集/挖矿进度 =====
        if (key.contains("采集")
                || key.contains("挖")) {
            int needed =
                    parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            List<String> matTypes =
                    extractMaterialTypes(key);
            if (!matTypes.isEmpty()) {
                int total = 0;
                StringBuilder names =
                        new StringBuilder();
                for (int i = 0;
                     i < matTypes.size(); i++) {
                    total += getCounter(
                            player,
                            "MINE:" + matTypes
                                    .get(i));
                    if (i > 0)
                        names.append("/");
                    names.append(
                            translateMaterialName(
                                    matTypes
                                            .get(i)));
                }
                return "§7采集" + names
                        + ": §e" + total
                        + "/" + needed;
            }
        }

        // ===== 泛解析：合成进度 =====
        if (key.contains("合成") || key.contains("制作")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int cur = getCounter(player, "CRAFT");
            return "§7合成: §e" + cur + "/" + needed;
        }

        // ===== 泛解析：钓鱼进度 =====
        if (key.contains("钓鱼") || key.contains("钓")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            int cur = getCounter(player, "FISH");
            return "§7钓鱼: §e" + cur + "/" + needed;
        }

        return "§7" + key;
    }
    private String translateMaterialName(
            String name) {
        switch (name) {
            case "COAL_ORE":
                return "煤矿石";
            case "DEEPSLATE_COAL_ORE":
                return "深层煤矿石";
            case "IRON_ORE":
                return "铁矿石";
            case "DEEPSLATE_IRON_ORE":
                return "深层铁矿石";
            case "COPPER_ORE":
                return "铜矿石";
            case "DEEPSLATE_COPPER_ORE":
                return "深层铜矿石";
            case "GOLD_ORE":
                return "金矿石";
            case "DEEPSLATE_GOLD_ORE":
                return "深层金矿石";
            case "DIAMOND_ORE":
                return "钻石矿石";
            case "DEEPSLATE_DIAMOND_ORE":
                return "深层钻石矿石";
            case "EMERALD_ORE":
                return "绿宝石矿石";
            case "DEEPSLATE_EMERALD_ORE":
                return "深层绿宝石矿石";
            case "LAPIS_ORE":
                return "青金石矿石";
            case "DEEPSLATE_LAPIS_ORE":
                return "深层青金石矿石";
            case "REDSTONE_ORE":
                return "红石矿石";
            case "DEEPSLATE_REDSTONE_ORE":
                return "深层红石矿石";
            case "NETHER_GOLD_ORE":
                return "下界金矿石";
            case "NETHER_QUARTZ_ORE":
                return "下界石英矿石";
            case "ANCIENT_DEBRIS":
                return "远古残骸";
            case "COAL":
                return "煤炭";
            case "RAW_IRON":
                return "粗铁";
            case "RAW_GOLD":
                return "粗金";
            case "RAW_COPPER":
                return "粗铜";
            case "DIAMOND":
                return "钻石";
            case "EMERALD":
                return "绿宝石";
            case "LAPIS_LAZULI":
                return "青金石";
            case "REDSTONE":
                return "红石";
            case "QUARTZ":
                return "石英";
            default:
                return name;
        }
    }

    /**
     * 中文物种名 → Bukkit EntityType 名
     * 支持击杀泛解析的模糊匹配
     */
    private String chineseMobToEntity(String cn) {
        String n = cn.toLowerCase().trim();

        // 直接别名表
        Map<String, String> mobMap = new HashMap<>();
        mobMap.put("骷髅", "SKELETON");
        mobMap.put("僵尸", "ZOMBIE");
        mobMap.put("苦力怕", "CREEPER");
        mobMap.put("蜘蛛", "SPIDER");
        mobMap.put("洞穴蜘蛛", "CAVE_SPIDER");
        mobMap.put("小白", "SKELETON");
        mobMap.put("末影人", "ENDERMAN");
        mobMap.put("enderman", "ENDERMAN");
        mobMap.put("猪灵", "PIGLIN");
        mobMap.put("猪灵蛮兵", "PIGLIN_BRUTE");
        mobMap.put("恶魂", "GHAST");
        mobMap.put("凋灵骷髅", "WITHER_SKELETON");
        mobMap.put("烈焰人", "BLAZE");
        mobMap.put("史莱姆", "SLIME");
        mobMap.put("岩浆怪", "MAGMA_CUBE");
        mobMap.put("女巫", "WITCH");
        mobMap.put("蠹虫", "SILVERFISH");
        mobMap.put("潜影贝", "SHULKER");
        mobMap.put("守卫者", "GUARDIAN");
        mobMap.put("远古守卫者", "ELDER_GUARDIAN");
        mobMap.put("溺尸", "DROWNED");
        mobMap.put("僵尸村民", "ZOMBIE_VILLAGER");
        mobMap.put("尸壳", "HUSK");
        mobMap.put("流浪者", "STRAY");
        mobMap.put("幻翼", "PHANTOM");
        mobMap.put("掠夺者", "PILLAGER");
        mobMap.put("卫道士", "VINDICATOR");
        mobMap.put("唤魔者", "EVOKER");
        mobMap.put("恼鬼", "VEX");
        mobMap.put("劫掠兽", "RAVAGER");
        mobMap.put("蜘蛛骑士", "SPIDER_JOCKEY");
        mobMap.put("凋灵", "WITHER");
        mobMap.put("末影龙", "ENDER_DRAGON");

        // 精确匹配
        if (mobMap.containsKey(n)) {
            return mobMap.get(n);
        }

        // 模糊匹配（包含关系）
        for (Map.Entry<String, String> en
                : mobMap.entrySet()) {
            if (n.contains(en.getKey())
                    || en.getKey().contains(n)) {
                return en.getValue();
            }
        }

        // 尝试直接用英文名
        try {
            return org.bukkit.entity.EntityType
                    .valueOf(n.toUpperCase()
                            .replace(" ", "_"))
                    .name();
        } catch (Exception e) {
            return "";
        }
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
        // 结构检测（覆盖纯生物群系分类）
        // 地下检测：Y低于表面则归类为地下
// 覆盖废弃矿井/试炼密室/要塞/远古城市等
        try {
            org.bukkit.World uw =
                    Bukkit.getWorld(worldName);
            if (uw != null) {
                int surfaceY = uw
                        .getHighestBlockYAt(
                                (int) Math.floor(x),
                                (int) Math.floor(z));
                if (y < surfaceY - 8) {
                    category = "地下";
                }
            }
        } catch (Exception ignored) {}


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

    /*
     * 打卡入口：报告坐标 → 32格去重 → 匹配条件 → 记录
     */
    public String checkInAtPosition(
            String player, String worldName,
            double x, double y, double z) {

        List<double[]> history =
                loadCheckInPositions(player);
        for (double[] pos : history) {
            double dist = Math.sqrt(
                    Math.pow(x - pos[0], 2)
                            + Math.pow(y - pos[1], 2)
                            + Math.pow(z - pos[2], 2));
            if (dist <= 32) {
                return "§c距离上次打卡点太近（"
                        + String.format(
                        "%.0f", dist)
                        + "格）";
            }
        }

        // ===== 获取群系和分类 =====
        String biome = "minecraft:plains";
        String category = "平原";
        try {
            org.bukkit.World w =
                    Bukkit.getWorld(worldName);
            if (w != null) {
                org.bukkit.Location loc =
                        new org.bukkit.Location(
                                w,
                                (int) Math.floor(x),
                                (int) Math.floor(y),
                                (int) Math.floor(z));
                biome = loc.getBlock()
                        .getBiome().getKey()
                        .toString();
                category = mapBiomeCategory(
                        biome, worldName);

                int surfaceY = w
                        .getHighestBlockYAt(
                                (int) Math.floor(x),
                                (int) Math.floor(z));
                if (y < surfaceY - 8) {
                    category = "地下";
                }

                if ("下界".equals(category)
                        || w.getEnvironment()
                        == org.bukkit.World
                        .Environment.NETHER) {
                    category = "下界";
                } else if ("末地".equals(category)
                        || w.getEnvironment()
                        == org.bukkit.World
                        .Environment.THE_END) {
                    category = "末地";
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning(
                    "[QuestTracker] 群系读取失败: "
                            + ex.getMessage());
        }

        plugin.getLogger().info(
                "[QuestTracker] 打卡: "
                        + player
                        + " world=" + worldName
                        + " biome=" + biome
                        + " cat=" + category
                        + " pos="
                        + String.format(
                        "%.0f,%.0f,%.0f",
                        x, y, z));

        saveCheckInPosition(
                player, x, y, z);
        recordCheckIn(
                player, biome, category,
                worldName, x, y, z);
        List<String> matched =
                checkInBiome(
                        player, biome,
                        worldName, x, y, z);

        StringBuilder sb = new StringBuilder();
        sb.append("§a打卡成功！群系: §e")
                .append(category);
        if (!matched.isEmpty()) {
            sb.append("\n§a完成任务条件:");
            for (String m : matched) {
                String qn =
                        findQuestName(m);
                sb.append("\n§7• ");
                if (qn != null) {
                    sb.append("§e[")
                            .append(qn)
                            .append("§7] §f");
                }
                sb.append(m);
            }
        }
        return sb.toString();
    }


        /**
         * 根据条件文本查找所属任务名
         */
    private String findQuestName(
            String condition) {
        for (String cat : Arrays.asList(
                "新人任务",
                "主线任务",
                "支线任务")) {
            for (QuestFile qf :
                    getQuests(cat)) {
                if (qf.conditions.contains(
                        condition)) {
                    return qf.displayName;
                }
            }
        }
        return null;
    }


    /**
     * 加载玩家所有历史打卡坐标
     * 格式: x1,y1,z1;x2,y2,z2;...
     */
    private List<double[]> loadCheckInPositions(
            String player) {
        List<double[]> list = new ArrayList<>();
        Object val = storage.getCondition(
                player, "checkin_all_pos");
        if (val == null) return list;
        String data = val.toString();
        if (data.isEmpty()) return list;
        String[] records = data.split(";");
        for (String r : records) {
            String[] parts = r.split(",");
            if (parts.length >= 3) {
                try {
                    list.add(new double[]{
                            Double.parseDouble(parts[0]),
                            Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2])});
                } catch (NumberFormatException e) {
                    // 跳过损坏数据
                }
            }
        }
        return list;
    }

    /**
     * 保存打卡坐标（追加到已有记录）
     */
    private void saveCheckInPosition(
            String player, double x,
            double y, double z) {
        List<double[]> list =
                loadCheckInPositions(player);
        list.add(new double[]{x, y, z});
        StringBuilder sb = new StringBuilder();
        for (double[] pos : list) {
            if (sb.length() > 0) sb.append(";");
            sb.append(pos[0]).append(",")
                    .append(pos[1]).append(",")
                    .append(pos[2]);
        }
        storage.setConditionRaw(
                player,
                "checkin_all_pos",
                sb.toString());
    }
    // ========== 中文生物名 → EntityType 映射 ==========
    private static final Map<String, String>
            MOB_NAME_MAP = new HashMap<>();
    static {
        MOB_NAME_MAP.put("洞穴蜘蛛", "CAVE_SPIDER");
        MOB_NAME_MAP.put("骷髅", "SKELETON");
        MOB_NAME_MAP.put("僵尸", "ZOMBIE");
        MOB_NAME_MAP.put("苦力怕", "CREEPER");
        MOB_NAME_MAP.put("蜘蛛", "SPIDER");
        MOB_NAME_MAP.put("末影人", "ENDERMAN");
        MOB_NAME_MAP.put("女巫", "WITCH");
        MOB_NAME_MAP.put("烈焰人", "BLAZE");
        MOB_NAME_MAP.put("僵尸猪灵",
                "ZOMBIFIED_PIGLIN");
        MOB_NAME_MAP.put("猪灵", "PIGLIN");
        MOB_NAME_MAP.put("恶魂", "GHAST");
        MOB_NAME_MAP.put("凋灵骷髅",
                "WITHER_SKELETON");
        MOB_NAME_MAP.put("史莱姆", "SLIME");
        MOB_NAME_MAP.put("岩浆怪", "MAGMA_CUBE");
        MOB_NAME_MAP.put("溺尸", "DROWNED");
        MOB_NAME_MAP.put("尸壳", "HUSK");
        MOB_NAME_MAP.put("流髑", "STRAY");
        MOB_NAME_MAP.put("幻翼", "PHANTOM");
        MOB_NAME_MAP.put("守卫者", "GUARDIAN");
        MOB_NAME_MAP.put("远古守卫者",
                "ELDER_GUARDIAN");
        MOB_NAME_MAP.put("掠夺者", "PILLAGER");
        MOB_NAME_MAP.put("卫道士", "VINDICATOR");
        MOB_NAME_MAP.put("唤魔者", "EVOKER");
        MOB_NAME_MAP.put("恼鬼", "VEX");
        MOB_NAME_MAP.put("劫掠兽", "RAVAGER");
        MOB_NAME_MAP.put("潜影贝", "SHULKER");
        MOB_NAME_MAP.put("末影螨", "ENDERMITE");
        MOB_NAME_MAP.put("凋灵", "WITHER");
        MOB_NAME_MAP.put("末影龙", "ENDER_DRAGON");
        MOB_NAME_MAP.put("监守者", "WARDEN");
        MOB_NAME_MAP.put("嗅探兽", "SNIFFER");
        MOB_NAME_MAP.put("僵尸村民",
                "ZOMBIE_VILLAGER");
    }

    /**
     * 从条件文本中提取所有匹配的生物类型
     * 按名称长度降序匹配，避免短名误匹配
     */
    private List<String> extractMobTypes(
            String text) {
        List<String> types = new ArrayList<>();
        List<Map.Entry<String, String>> sorted =
                new ArrayList<>(
                        MOB_NAME_MAP.entrySet());
        sorted.sort((a, b) ->
                b.getKey().length()
                        - a.getKey().length());
        String remaining = text;
        for (Map.Entry<String, String> entry
                : sorted) {
            if (remaining.contains(
                    entry.getKey())) {
                if (!types.contains(
                        entry.getValue())) {
                    types.add(entry.getValue());
                }
                remaining = remaining.replace(
                        entry.getKey(), "");
            }
        }
        return types;
    }

    /**
     * EntityType → 中文名
     */
    private String reverseMobName(
            String entityType) {
        for (Map.Entry<String, String> entry
                : MOB_NAME_MAP.entrySet()) {
            if (entry.getValue()
                    .equals(entityType)) {
                return entry.getKey();
            }
        }
        return entityType;
    }

    /**
     * 从条件文本中提取矿物名
     * "采集任意矿物(煤炭除外)1组"
     * → [COAL_ORE, IRON_ORE, GOLD_ORE, ...]
     */
    private List<String> extractMaterialTypes(
            String text) {
        List<String> types = new ArrayList<>();
        // 排除列表
        List<String> excludes =
                new ArrayList<>();
        if (text.contains("煤炭除外")) {
            excludes.add("COAL");
            excludes.add("COAL_ORE");
            excludes.add("DEEPSLATE_COAL_ORE");
        }
        // 通配符："矿物" "矿石"
        if (text.contains("矿物")
                || text.contains("矿石")) {
            String[] ores = {
                    "COAL_ORE",
                    "DEEPSLATE_COAL_ORE",
                    "IRON_ORE",
                    "DEEPSLATE_IRON_ORE",
                    "COPPER_ORE",
                    "DEEPSLATE_COPPER_ORE",
                    "GOLD_ORE",
                    "DEEPSLATE_GOLD_ORE",
                    "DIAMOND_ORE",
                    "DEEPSLATE_DIAMOND_ORE",
                    "EMERALD_ORE",
                    "DEEPSLATE_EMERALD_ORE",
                    "LAPIS_ORE",
                    "DEEPSLATE_LAPIS_ORE",
                    "REDSTONE_ORE",
                    "DEEPSLATE_REDSTONE_ORE",
                    "NETHER_GOLD_ORE",
                    "NETHER_QUARTZ_ORE",
                    "ANCIENT_DEBRIS"
            };
            for (String ore : ores) {
                if (!excludes.contains(ore)) {
                    types.add(ore);
                }
            }
            return types;
        }
        // 单个矿物名
        if (text.contains("煤炭"))
            types.add("COAL");
        if (text.contains("铁矿")
                || text.contains("铁锭"))
            types.add("RAW_IRON");
        if (text.contains("金矿")
                || text.contains("金锭"))
            types.add("RAW_GOLD");
        if (text.contains("钻石"))
            types.add("DIAMOND");
        if (text.contains("绿宝石"))
            types.add("EMERALD");
        if (text.contains("青金石"))
            types.add("LAPIS_LAZULI");
        if (text.contains("红石"))
            types.add("REDSTONE");
        if (text.contains("铜"))
            types.add("RAW_COPPER");
        if (text.contains("下界石英")
                || text.contains("石英"))
            types.add("QUARTZ");
        return types;
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

        if (key.contains("村民")
                && key.contains("交易")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            return getCounter(
                    player, "VILLAGER_TRADE")
                    >= needed;
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
        // ===== 泛解析：击杀类中文条件 =====
        if (key.contains("击杀") || key.contains("猎杀")
                || key.contains("消灭")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            List<String> mobTypes = extractMobTypes(key);
            if (!mobTypes.isEmpty()) {
                int total = 0;
                for (String mobType : mobTypes) {
                    total += getCounter(player,
                            "KILL:" + mobType);
                }
                return total >= needed;
            }
        }

        // ===== 泛解析：村民交易条件 =====
        if (key.contains("交易")
                && (key.contains("村民")
                || key.contains("与村民"))) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            return getCounter(player, "TRADE")
                    >= needed;
        }

        // ===== 泛解析：采集/挖矿类条件 =====
        if (key.contains("采集") || key.contains("挖")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            List<String> matTypes =
                    extractMaterialTypes(key);
            if (!matTypes.isEmpty()) {
                int total = 0;
                for (String mat : matTypes) {
                    total += getCounter(player,
                            "MINE:" + mat);
                }
                return total >= needed;
            }
        }

        // ===== 泛解析：合成类条件 =====
        if (key.contains("合成") || key.contains("制作")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            return getCounter(player, "CRAFT")
                    >= needed;
        }

        // ===== 泛解析：钓鱼类条件 =====
        if (key.contains("钓鱼") || key.contains("钓")) {
            int needed = parseNumberFromText(key);
            if (needed <= 0) needed = 1;
            return getCounter(player, "FISH")
                    >= needed;
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

    public void onVillagerTrade(String player) {
        String key = "VILLAGER_TRADE";
        int cur = getCounter(player, key);
        setCounter(player, key, cur + 1);
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
