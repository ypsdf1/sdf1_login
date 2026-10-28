package Sdf1_login;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ★ 第58批功能2: AI聊天介入
 *
 * 核心逻辑:
 *   - 兼容 openai 协议，根据模型名取 api 地址和 key
 *   - @//模型名/ 前缀触发直接提问
 *   - 空闲时 1-10 分钟自动抓聊天互动
 *   - 黑名单拦截不浪费 token
 *   - 对话留档 issue.json
 */
public class AIChatManager {

    private final Main plugin;

    // 配置数据
    private JsonObject modelsConfig;
    private JsonObject providersConfig;
    private final Map<String, String> aliasToFullModel = new HashMap<>();  // 简称 → 模型全名
    private final Map<String, ModelEndpoint> modelEndpoints = new HashMap<>();  // 模型全名 → 端点
    private final Map<String, String> providerToModel = new HashMap<>();  // 厂家名 → 默认模型全名（@//厂家名/）
    private final List<String> blacklist = new ArrayList<>();

    // ★ 互动指标跳过日志静默：首次跳过记一条，之后只在状态变化（从≥→<）时再记
    private boolean prevOnlineSufficient = true;

    // ★ 自动互动日志静默：只在状态变化（成功→失败 或 失败→成功）时记一条
    //   正常触发不打印，失败只一行简要错误，避免日志被刷屏
    private boolean prevAutoChatFailed = false;

    // 默认模型
    private String defaultModelFull = "";

    // 人设
    private String serverName = "";
    private String assistantName = "";
    private String writingStyle = "";

    // 发言间隔（分钟）
    private int minIntervalMin = 1;
    private int maxIntervalMin = 10;

    // 默认上下文长度
    private String defaultContextLength = "200000";

    // 自定义供应商 URL 是否自动拼接 chat/completions（自动补齐开关，默认 true）
    private boolean autoAppendCompletions = true;

    // 互动指标：在线玩家数量达到此值才触发自动互动（手动 @// 不受影响）
    private int interactionMetric = 1;

    // 空闲调度器
    private BukkitTask autoChatTask = null;
    private long lastAiReplyTime = 0;
    private final AtomicBoolean autoChatRunning = new AtomicBoolean(false);

    // 最近聊天记录缓冲区
    private final List<String> recentChatBuffer = new ArrayList<>();
    private static final int MAX_BUFFER = 30;

    // 对话留档
    private JsonObject issueRecord = new JsonObject();
    private File issueFile = null;

    // HTTP Client
    private HttpClient httpClient;

    // 最近提问的玩家（防止频繁请求）
    private final Map<String, Long> lastRequestTime = new ConcurrentHashMap<>();

    // 模型端点
    static class ModelEndpoint {
        String baseUrl;
        String apiKey;
        String maxToken;
        boolean isCustom;  // true=自定义供应商(受自动补齐开关控制), false=内置供应商(总是拼接)
    }

    public AIChatManager(Main plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        loadConfig();
    }

    // ==================== 配置加载 ====================

    private void loadConfig() {
        loadConfig(false);
    }

    /**
     * 加载配置
     * @param quiet true=静默模式（重读时不打印"配置加载完成"），false=初始加载（打印）
     */
    private void loadConfig(boolean quiet) {
        try {
            // 确保 openai 目录存在
            File openaiDir = new File(plugin.getDataFolder(), "openai");
            if (!openaiDir.exists()) {
                openaiDir.mkdirs();
                copyResource("openai/models.json", new File(openaiDir, "models.json"));
                copyResource("openai/供应商.json", new File(openaiDir, "供应商.json"));
                copyResource("openai/提示词.txt", new File(openaiDir, "提示词.txt"));
                copyResource("openai/issue.json", new File(openaiDir, "issue.json"));
                plugin.getLogger().info("[AI聊天] 已复制默认配置文件到 " + openaiDir.getAbsolutePath());
            }

            // 读取配置
            File modelsFile = new File(openaiDir, "models.json");
            File providersFile = new File(openaiDir, "供应商.json");
            File promptFile = new File(openaiDir, "提示词.txt");
            issueFile = new File(openaiDir, "issue.json");

            if (modelsFile.exists()) {
                modelsConfig = JsonParser.parseString(Files.readString(modelsFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            if (providersFile.exists()) {
                providersConfig = JsonParser.parseString(Files.readString(providersFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            }

            // 读取人设
            if (modelsConfig != null && modelsConfig.has("人设部分")) {
                JsonObject persona = modelsConfig.getAsJsonObject("人设部分");
                serverName = getStr(persona, "服务器名字");
                assistantName = getStr(persona, "助理名字");
                writingStyle = getStr(persona, "写法");
            }

            // 读取配置部分
            if (modelsConfig != null && modelsConfig.has("配置部分")) {
                JsonObject config = modelsConfig.getAsJsonObject("配置部分");

                // 解析模型配置（配置部分 → "1" → "模型配置"）
                if (config.has("1")) {
                    JsonObject section1 = config.getAsJsonObject("1");
                    if (section1.has("模型配置")) {
                        JsonObject modelConfig = section1.getAsJsonObject("模型配置");
                        parseModelConfig(modelConfig, quiet);
                    }
                }

                // 解析全局配置（配置部分 → "2"）
                if (config.has("2")) {
                    JsonObject section2 = config.getAsJsonObject("2");
                    // 自动补齐开关：自定义供应商 baseurl 是否自动拼接 chat/completions
                    if (section2.has("自动补齐")) {
                        JsonElement autoEl = section2.get("自动补齐");
                        if (autoEl != null && autoEl.isJsonPrimitive()) {
                            String av = autoEl.getAsString();
                            // 支持 true/false 字符串与布尔
                            autoAppendCompletions = "true".equalsIgnoreCase(av);
                        }
                    }
                    defaultContextLength = getStr(section2, "默认上下文长度");
                    if (defaultContextLength == null || defaultContextLength.isEmpty()) {
                        defaultContextLength = "200000";
                    }

                    // 发言间隔
                    if (section2.has("发言间隔")) {
                        JsonObject interval = section2.getAsJsonObject("发言间隔");
                        String minStr = getStr(interval, "最短");
                        String maxStr = getStr(interval, "最长");
                        if (minStr != null && !minStr.isEmpty()) {
                            try { minIntervalMin = Integer.parseInt(minStr); } catch (NumberFormatException ignored) {}
                        }
                        if (maxStr != null && !maxStr.isEmpty()) {
                            try { maxIntervalMin = Integer.parseInt(maxStr); } catch (NumberFormatException ignored) {}
                        }
                    }

                    // 黑名单
                    if (section2.has("黑名单")) {
                        JsonElement bl = section2.get("黑名单");
                        if (bl.isJsonArray()) {
                            for (JsonElement el : bl.getAsJsonArray()) {
                                if (el.isJsonPrimitive()) {
                                    blacklist.add(el.getAsString());
                                }
                            }
                        }
                    }

                    // 互动指标：在线玩家数量达到此值才触发自动互动
                    if (section2.has("互动指标")) {
                        JsonObject metricObj = section2.getAsJsonObject("互动指标");
                        if (metricObj.has("指标")) {
                            try {
                                interactionMetric = metricObj.get("指标").getAsInt();
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            // 读取提示词.txt — 解析 models=简称 格式
            aliasToFullModel.clear();
            if (promptFile.exists()) {
                List<String> lines = Files.readAllLines(promptFile.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.startsWith("models=")) {
                        String content = line.substring("models=".length()).trim();
                        if (content.contains("=")) {
                            // models=模型全名=简称
                            String[] parts = content.split("=", 2);
                            aliasToFullModel.put(parts[1].trim(), parts[0].trim());
                        } else {
                            // models=简称 → 简称直接作为模型全名
                            aliasToFullModel.put(content, content);
                        }
                    }
                }
            }

            // 读取对话留档
            if (issueFile.exists()) {
                issueRecord = JsonParser.parseString(Files.readString(issueFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            }

            if (!quiet) {
                plugin.getLogger().info("[AI聊天] 配置加载完成: " + modelEndpoints.size() + "个模型, "
                        + aliasToFullModel.size() + "个别名, 黑名单" + blacklist.size() + "条");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("[AI聊天] 配置加载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void copyResource(String resourcePath, File target) {
        try {
            InputStream is = plugin.getResource(resourcePath);
            if (is == null) return;
            Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            is.close();
        } catch (Exception e) {
            plugin.getLogger().warning("[AI聊天] 复制资源文件失败: " + resourcePath + " - " + e.getMessage());
        }
    }

    private void parseModelConfig(JsonObject modelConfig, boolean quiet) {
        // ★ 重读前清理，避免旧配置残留（putIfAbsent 不会覆盖已存在的键）
        modelEndpoints.clear();
        providerToModel.clear();

        // 遍历所有供应商配置
        for (Map.Entry<String, JsonElement> entry : modelConfig.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject supplierConfig = entry.getValue().getAsJsonObject();

            // 判断是内置还是自定义
            boolean isCustom = supplierConfig.has("baseurl");
            boolean isBuiltin = supplierConfig.has("供应商名字");

            if (!isCustom && !isBuiltin) {
                if (!quiet) plugin.getLogger().info("[AI聊天] 配置跳过[" + key + "]: 既无baseurl也无供应商名字");
                continue;
            }

            String baseUrl;
            String apiKey = getStr(supplierConfig, "apikey");
            String maxToken = getStr(supplierConfig, "max_token");
            if (maxToken == null || maxToken.isEmpty()) maxToken = defaultContextLength;

            if (isCustom) {
                baseUrl = getStr(supplierConfig, "baseurl");
            } else {
                String providerName = getStr(supplierConfig, "供应商名字");
                // ★ 空模板（供应商名字=''）不是有效配置：用户没配内置供应商，直接跳过
                //   之前没有这个判断 → 每秒重读配置都拿空名字去找 URL → 3条warning刷屏
                if (providerName == null || providerName.isEmpty()) {
                    if (!quiet) plugin.getLogger().info("[AI聊天] 配置跳过[" + key + "]: 内置供应商名字为空（模板占位，忽略）");
                    continue;
                }
                baseUrl = findProviderUrl(providerName);
                if (baseUrl == null || baseUrl.isEmpty()) {
                    if (!quiet) {
                        plugin.getLogger().warning("[AI聊天] 配置跳过[" + key + "]: 内置供应商 '" + providerName + "' 在 供应商.json 中找不到URL");
                        plugin.getLogger().warning("[AI聊天] 诊断: 请确认 供应商.json 中存在路径 '" + providerName + "'，且路径拼写完全正确");
                    }
                }
            }

            if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
                if (!quiet) plugin.getLogger().warning("[AI聊天] 配置跳过[" + key + "]: baseUrl=" + baseUrl + ", apiKey=" + apiKey);
                continue;
            }

            // 存储原始 baseUrl（尾斜杠统一在发送时按自动补齐开关处理）
            ModelEndpoint endpoint = new ModelEndpoint();
            endpoint.baseUrl = baseUrl;
            endpoint.apiKey = apiKey;
            endpoint.maxToken = maxToken;
            endpoint.isCustom = isCustom;

            // 解析可用模型列表
            if (supplierConfig.has("可用模型")) {
                JsonElement models = supplierConfig.get("可用模型");
                if (models.isJsonArray()) {
                    for (JsonElement modelEl : models.getAsJsonArray()) {
                        if (modelEl.isJsonPrimitive()) {
                            String modelName = modelEl.getAsString();
                            modelEndpoints.put(modelName, endpoint);
                        }
                    }
                }
            }

            // ★ 注册厂家名 → 默认模型全名（@//厂家名/ 快捷引用）
            // 内置供应商：从「供应商名字」路径的每一段都注册为厂家名别名
            //   如 "非国产/agnes/中国版" → 非国产/agnes/中国版 三个名字都能 @// 调用
            //   如 "mimo/token-plan" → mimo/token-plan 都能调用
            // 自定义供应商：别名走「提示词.txt」的 models=全名=别名 映射（aliasToFullModel），这里不重复注册
            if (isBuiltin) {
                String providerName = getStr(supplierConfig, "供应商名字");
                if (providerName != null && !providerName.isEmpty()) {
                    String defaultUse = getStr(supplierConfig, "默认使用");
                    String firstModel = null;
                    if (defaultUse != null && !defaultUse.isEmpty()) {
                        firstModel = defaultUse;
                    } else if (supplierConfig.has("可用模型")) {
                        JsonElement models = supplierConfig.get("可用模型");
                        if (models.isJsonArray() && models.getAsJsonArray().size() > 0) {
                            firstModel = models.getAsJsonArray().get(0).getAsString();
                        }
                    }
                    if (firstModel != null) {
                        // 注册所有路径段为厂家名别名（包括顶层分组名）
                        // 如 "非国产/agnes/中国版" → 非国产、agnes、中国版 都能 @// 调用
                        // ★ 第58批修复：顶层分组名（如"非国产"）也要注册，否则 @//非国产/ 报"未知厂家"
                        String[] segs = providerName.split("/");
                        for (int i = 0; i < segs.length; i++) {
                            String name = segs[i].trim();
                            if (!name.isEmpty()) {
                                providerToModel.putIfAbsent(name, firstModel);
                            }
                        }
                    }
                }
            }

            // 默认使用（全局默认模型）
            String defaultUse = getStr(supplierConfig, "默认使用");
            if (defaultUse != null && !defaultUse.isEmpty() && defaultModelFull.isEmpty()) {
                defaultModelFull = defaultUse;
            }
        }
    }

    // ==================== 供应商URL查找 ====================

    private String findProviderUrl(String providerPath) {
        if (providerPath == null || providerPath.isEmpty()) return null;
        String[] parts = providerPath.split("/");
        String first = parts[0].trim();

        // 第1段是顶层分组名（如「国产」「非国产」）→ 直接在该分组里找
        if (providersConfig != null && providersConfig.has(first)) {
            String[] rest = Arrays.copyOfRange(parts, 1, parts.length);
            return findUrlInObject(providersConfig.getAsJsonObject(first), rest);
        }

        // 第1段不是分组名 → 在所有顶层分组里尝试匹配（支持省略组名配置）
        // 如 "deepseek" → 在国产/非国产里找 "deepseek" 键
        if (providersConfig != null) {
            for (Map.Entry<String, JsonElement> entry : providersConfig.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    String url = findUrlInObject(entry.getValue().getAsJsonObject(), parts);
                    if (url != null) return url;
                }
            }
        }
        return null;
    }

    /**
     * 从字符串中提取 token：中文段 + 英文/数字段。
     * "讯飞maas" → [讯飞, maas]
     * "讯飞星辰MaaS" → [讯飞星辰, maas]
     * "mimo/按量计费" → [mimo, 按量计费]
     */
    private java.util.List<String> extractTokens(String s) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+")
                .matcher(s.toLowerCase());
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    /**
     * 大小写不敏感地获取 JsonObject 中的元素。
     * 四层匹配策略：
     *   1. 精确匹配
     *   2. 大小写不敏感完全匹配（讯飞星辰maas vs 讯飞星辰MaaS）
     *   3. ★ token 级匹配：把 key 拆成中文段+英文段，
     *      entry key 的对应 token 包含 key 的所有 token → 匹配
     *      如 key="讯飞maas" → tokens=[讯飞, maas]
     *      entry "讯飞星辰MaaS" → tokens=[讯飞星辰, maas]
     *      讯飞⊆讯飞星辰 ✅ + maas⊆maas ✅ → 匹配
     *      解决用户写简称/缩写（讯飞maas）匹配不上完整键名（讯飞星辰MaaS）的问题
     *   4. 兜底：去空格/下划线/连字符后的包含匹配
     */
    private JsonElement getElementIgnoreCase(JsonObject obj, String key) {
        // 1. 精确匹配
        JsonElement el = obj.get(key);
        if (el != null) return el;

        String lower = key.toLowerCase();

        // 2. 大小写不敏感完全匹配
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (entry.getKey().toLowerCase().equals(lower)) {
                return entry.getValue();
            }
        }

        // 3. ★ token 级匹配：中文段+英文段拆分
        java.util.List<String> keyTokens = extractTokens(key);
        if (!keyTokens.isEmpty()) {
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                java.util.List<String> entryTokens = extractTokens(entry.getKey());
                if (entryTokens.isEmpty()) continue;
                boolean allFound = true;
                for (String kt : keyTokens) {
                    boolean found = false;
                    for (String et : entryTokens) {
                        if (et.contains(kt)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        allFound = false;
                        break;
                    }
                }
                if (allFound) return entry.getValue();
            }
        }

        // 4. 兜底：去空格/下划线/连字符后的包含匹配
        String keyClean = key.toLowerCase().replaceAll("[\\s_\\-]", "");
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String entryClean = entry.getKey().toLowerCase().replaceAll("[\\s_\\-]", "");
            if (entryClean.contains(keyClean) || keyClean.contains(entryClean)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * 深度递归：在嵌套 JsonObject 中找第一个 http URL。
     * 不管嵌套多少层，找到第一个 http 开头的字符串值就返回。
     * 用于路径终点是对象而非直接 URL 的场景（如 讯飞/讯飞星辰MaaS 没有指定子项）。
     */
    private String findFirstUrlInObject(JsonObject obj) {
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            JsonElement el = entry.getValue();
            if (el.isJsonPrimitive()) {
                String val = el.getAsString();
                if (val != null && val.startsWith("http")) return val;
            } else if (el.isJsonObject()) {
                String url = findFirstUrlInObject(el.getAsJsonObject());
                if (url != null) return url;
            }
        }
        return null;
    }

    private String findUrlInObject(JsonObject obj, String[] parts) {
        JsonElement el = getElementIgnoreCase(obj, parts[0].trim());
        if (el == null) return null;
        if (parts.length == 1) {
            // 路径终点：如果是字符串 → 直接返回 URL
            if (el.isJsonPrimitive()) return el.getAsString();
            // ★ 深度递归：如果是对象 → 继续往下钻找第一个 URL
            //   如 "讯飞/讯飞星辰MaaS" 没指定子项，钻入 MaaS 对象找第一个 URL
            if (el.isJsonObject()) return findFirstUrlInObject(el.getAsJsonObject());
            return null;
        }
        if (el.isJsonObject()) {
            String[] rest = Arrays.copyOfRange(parts, 1, parts.length);
            return findUrlInObject(el.getAsJsonObject(), rest);
        }
        return null;
    }

    // ==================== 模型名解析 ====================

    /**
     * 将简称解析为模型全名
     * 支持三种格式：
     * 1. 提示词.txt 里定义的简称（models=全名=简称）
     * 2. 厂家名快捷引用（@//agnes/ → 该供应商的默认模型）
     * 3. 直接使用模型全名
     */
    public String resolveModelAlias(String alias) {
        if (alias == null || alias.isEmpty()) return null;
        // ★ 第58批修复：优先级调整为 模型全名 > 简称 > 厂家名
        //   避免自定义供应商的模型名（如 oc/mimo-v2.6-flash-free）被内置厂家名快捷引用拦截
        // 1. 直接作为模型全名（精确匹配，优先级最高）
        if (modelEndpoints.containsKey(alias)) {
            return alias;
        }
        // 2. 简称 → 模型全名（提示词.txt 的 models=全名=简称）
        if (aliasToFullModel.containsKey(alias)) {
            return aliasToFullModel.get(alias);
        }
        // 3. 厂家名 → 默认模型全名（内置供应商快捷引用，最后兜底）
        if (providerToModel.containsKey(alias)) {
            return providerToModel.get(alias);
        }
        return null;
    }

    /**
     * 获取可用模型列表（别名 + 厂家名 + 模型全名）
     */
    public List<String> getAvailableModels() {
        // 合并别名、厂家名、模型全名，去重
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.addAll(aliasToFullModel.keySet());
        all.addAll(providerToModel.keySet());
        all.addAll(modelEndpoints.keySet());
        return new ArrayList<>(all);
    }

    // ==================== HTTP 请求 ====================

    /**
     * 向指定模型发送请求（异步）
     * @param modelAlias 模型简称
     * @param prompt 用户输入
     * @param player 发起请求的玩家（用于私聊回复）
     * @param broadcast 是否广播回复（自动互动时为 true）
     */
    public void sendToModel(String modelAlias, String prompt, Player player, boolean broadcast) {
        // ★ 每次发请求前重读配置，获取最新的模型、人设、黑名单等信息
        // 避免模型429、403等报错后还用旧配置继续发请求
        loadConfig();

        // 黑名单检查
        if (isBlacklisted(prompt)) {
            plugin.getLogger().info("[AI聊天] 黑名单拦截: " + prompt.substring(0, Math.min(50, prompt.length())));
            if (player != null) {
                player.sendMessage("§c[AI聊天] §f输入内容命中黑名单，请求已拦截");
            }
            return;
        }

        // 频率限制（同一玩家10秒内只能请求一次）
        if (player != null) {
            Long last = lastRequestTime.get(player.getName());
            if (last != null && System.currentTimeMillis() - last < 10000) {
                player.sendMessage("§c[AI聊天] §f请等待10秒后再提问");
                return;
            }
            lastRequestTime.put(player.getName(), System.currentTimeMillis());
        }

        // 解析模型名
        String modelFull = resolveModelAlias(modelAlias);
        if (modelFull == null) {
            if (player != null) {
                player.sendMessage("§c[AI聊天] §f未知模型/厂家: " + modelAlias);
                List<String> models = getAvailableModels();
                List<String> providers = new ArrayList<>(providerToModel.keySet());
                StringBuilder sb = new StringBuilder();
                sb.append("§7可用厂家: ").append(String.join(", ", providers));
                if (!models.isEmpty()) {
                    sb.append("\n§7可用模型: ").append(String.join(", ", models));
                } else {
                    sb.append("\n§7未配置模型，请先在 openai/models.json 和 openai/提示词.txt 中配置");
                }
                player.sendMessage(sb.toString());
            }
            return;
        }

        ModelEndpoint endpoint = modelEndpoints.get(modelFull);
        if (endpoint == null) {
            if (player != null) {
                player.sendMessage("§c[AI聊天] §f模型配置不存在: " + modelFull);
            }
            return;
        }

        // 构建请求体
        String systemPrompt = buildSystemPrompt();
        String jsonBody = buildChatRequest(modelFull, systemPrompt, prompt, endpoint.maxToken);
        String requestUrl = buildRequestUrl(endpoint);

        // ★ 400调试日志：请求发出前打印完整信息
        plugin.getLogger().info("[AI聊天-调试] === 请求开始 ===");
        plugin.getLogger().info("[AI聊天-调试] 模型全名: " + modelFull);
        plugin.getLogger().info("[AI聊天-调试] 请求URL: " + requestUrl);
        plugin.getLogger().info("[AI聊天-调试] 自定义供应商: " + endpoint.isCustom);
        plugin.getLogger().info("[AI聊天-调试] 自动补齐开关: " + autoAppendCompletions);
        plugin.getLogger().info("[AI聊天-调试] 请求体JSON: " + jsonBody);
        plugin.getLogger().info("[AI聊天-调试] === 请求结束 ===");

        // 异步发送请求
        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(requestUrl))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + endpoint.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                // ★ 完整异常日志：区分连接超时/响应超时/DNS/SSL/拒绝连接
                String excClass = e.getClass().getName();
                String excMsg = e.getMessage();
                plugin.getLogger().warning("[AI聊天-调试] === 请求异常 ===");
                plugin.getLogger().warning("[AI聊天-调试] 异常类型: " + excClass);
                plugin.getLogger().warning("[AI聊天-调试] 异常消息: " + excMsg);
                plugin.getLogger().warning("[AI聊天-调试] 模型全名: " + modelFull);
                plugin.getLogger().warning("[AI聊天-调试] 请求URL: " + requestUrl);
                // 打印栈帧前5层
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < Math.min(5, st.length); i++) {
                    plugin.getLogger().warning("[AI聊天-调试] 栈: " + st[i].toString());
                }
                // ★ 诊断分类：帮助快速定位问题层级
                if (e instanceof java.net.http.HttpConnectTimeoutException) {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: 连接超时——请求未发出，目标地址不可达或DNS解析慢");
                } else if (e instanceof java.net.http.HttpTimeoutException) {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: 响应超时——请求已发出，上游60秒内未返回（可能限流/处理慢/挂死）");
                } else if (e instanceof java.net.ConnectException) {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: 连接被拒——目标端口未监听或防火墙拦截");
                } else if (e instanceof java.net.UnknownHostException) {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: DNS解析失败——域名不存在或DNS配置问题");
                } else if (e instanceof javax.net.ssl.SSLException) {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: SSL/TLS握手失败——证书问题或协议不匹配");
                } else {
                    plugin.getLogger().warning("[AI聊天-调试] 诊断: 其他异常——详见上方类型和消息");
                }
                plugin.getLogger().warning("[AI聊天-调试] === 请求异常结束 ===");
                return null;
            }
        }).thenAccept(response -> {
            if (response == null) {
                handleRequestFailure(prompt, "请求异常: 网络错误");
                if (player != null) {
                    player.sendMessage("§c[AI聊天] §f请求失败，请稍后重试");
                }
                return;
            }

            if (response.statusCode() != 200) {
                String errBody = response.body();
                // ★ 400调试日志：打印完整响应头和响应体
                plugin.getLogger().warning("[AI聊天-调试] === 请求失败 ===");
                plugin.getLogger().warning("[AI聊天-调试] HTTP状态码: " + response.statusCode());
                plugin.getLogger().warning("[AI聊天-调试] 模型全名: " + modelFull);
                plugin.getLogger().warning("[AI聊天-调试] 请求URL: " + requestUrl);
                plugin.getLogger().warning("[AI聊天-调试] 请求体JSON: " + jsonBody);
                // 打印完整响应头
                response.headers().map().forEach((name, values) -> {
                    plugin.getLogger().warning("[AI聊天-调试] 响应头 " + name + ": " + String.join(", ", values));
                });
                plugin.getLogger().warning("[AI聊天-调试] 响应体: " + errBody);
                plugin.getLogger().warning("[AI聊天-调试] === 请求失败结束 ===");

                handleRequestFailure(prompt, "HTTP " + response.statusCode() + ": " + errBody);
                if (player != null) {
                    player.sendMessage("§c[AI聊天] §f请求失败: HTTP " + response.statusCode());
                }
                return;
            }

            // 解析响应
            String reply = parseChatResponse(response.body());
            if (reply == null) {
                handleRequestFailure(prompt, "解析失败: " + response.body());
                if (player != null) {
                    player.sendMessage("§c[AI聊天] §f响应解析失败");
                }
                return;
            }

            // 发送回复
            if (broadcast) {
                Bukkit.broadcastMessage("§d§l[AI] §f" + reply);
            } else if (player != null) {
                player.sendMessage("§d§l[AI] §f" + reply);
            }

            // 记录留档
            recordIssue(prompt, reply);
            lastAiReplyTime = System.currentTimeMillis();
        });
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        if (!serverName.isEmpty()) {
            sb.append("你在一个名为\"").append(serverName).append("\"的Minecraft服务器中。\n");
        }
        if (!assistantName.isEmpty()) {
            sb.append("你是服务器助理\"").append(assistantName).append("\"。\n");
        }
        sb.append("你需要用简洁友好的语气回答玩家的问题。\n");
        if (!writingStyle.isEmpty()) {
            sb.append("写法要求: ").append(writingStyle).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建请求 URL
     * 自动补齐=true：baseUrl 以 / 结尾后拼接 chat/completions（默认）
     * 自动补齐=false：baseUrl 已是完整端点（如 .../v1/chat/completions），直接用
     */
    private String buildRequestUrl(ModelEndpoint endpoint) {
        String url = endpoint.baseUrl;
        // 内置供应商的 baseUrl 总是基础地址，必须拼接；自定义供应商受自动补齐开关控制
        boolean needAppend = !endpoint.isCustom || autoAppendCompletions;
        // ★ 防御性：即使开关为开，若 URL 已含 chat/completions 也绝不再补（避免双重地址）
        if (needAppend && !url.contains("chat/completions")) {
            if (!url.endsWith("/")) url += "/";
            url += "chat/completions";
        }
        return url;
    }

    /**
     * 构建聊天请求体（OpenAI 兼容格式）
     * 注意：不发送 max_tokens 字段，让 API 使用默认输出上限
     * maxToken 参数仅用于本地上下文长度阈值判断（在 checkAutoChat 中做压缩）
     */
    private String buildChatRequest(String model, String systemPrompt, String userPrompt, String maxToken) {
        Gson gson = new Gson();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        body.add("messages", messages);

        return gson.toJson(body);
    }

    /**
     * 上下文压缩：当聊天记录总长度超过阈值时，截断旧消息只保留最近的
     * 返回压缩后的聊天记录字符串
     */
    private String compressChatBuffer(String maxToken) {
        int threshold;
        try {
            threshold = Integer.parseInt(maxToken);
        } catch (NumberFormatException e) {
            threshold = 200000;
        }
        if (threshold <= 0) threshold = 200000;

        String joined = String.join("\n", recentChatBuffer);
        if (joined.length() <= threshold) {
            return joined; // 未超阈值，直接返回
        }

        // 超阈值：从尾部往前保留消息，直到达到阈值的 80%（留余量给提示词模板）
        int targetLen = (int) (threshold * 0.8);
        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        // 从最新消息开始往前累加
        for (int i = recentChatBuffer.size() - 1; i >= 0; i--) {
            String line = recentChatBuffer.get(i);
            int lineLen = line.length() + 1; // +1 for newline
            if (totalLen + lineLen > targetLen) break;
            if (sb.length() > 0) sb.insert(0, "\n");
            sb.insert(0, line);
            totalLen += lineLen;
        }
        plugin.getLogger().info("[AI聊天] 上下文压缩: 原始" + joined.length() + "字符 → 压缩至" + sb.length() + "字符 (阈值" + threshold + ")");
        return sb.toString();
    }

    private String parseChatResponse(String responseBody) {
        try {
            JsonObject resp = JsonParser.parseString(responseBody).getAsJsonObject();
            if (resp.has("choices")) {
                JsonArray choices = resp.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject first = choices.get(0).getAsJsonObject();
                    if (first.has("message")) {
                        JsonObject message = first.getAsJsonObject("message");
                        if (message.has("content")) {
                            return message.get("content").getAsString();
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleRequestFailure(String prompt, String errorDetail) {
        plugin.getLogger().warning("[AI聊天] 请求失败: " + errorDetail);
        recordIssue(prompt, "[请求失败] " + errorDetail);
        // ★ 死循环防护：失败也更新时间戳，间隔内不会再触发
        lastAiReplyTime = System.currentTimeMillis();
    }

    // ==================== 聊天处理 ====================

    /**
     * 处理玩家聊天消息
     * 检测 @厂家名/内容 或 @//厂家名/内容 前缀触发直接提问
     * 格式: @厂家名/内容  或  @//厂家名/内容  或  @厂家名  或  @//厂家名
     */
    public void handlePlayerChat(Player p, String message) {
        // 记录到聊天缓冲区
        String chatLine = p.getDisplayName() + ": " + message;
        recentChatBuffer.add(chatLine);
        if (recentChatBuffer.size() > MAX_BUFFER) {
            recentChatBuffer.remove(0);
        }

        // 检测 @// 或 @ 前缀
        // @// 前缀：用 // 分隔模型名和内容，模型名可以包含单斜杠（如 oc/mimo-v2.6-flash-free）
        // @ 前缀：用 / 或空格分隔（旧行为）
        String prefix = "";
        int prefixLen = 0;
        if (message.startsWith("@//")) {
            prefix = "@//";
            prefixLen = 3;
        } else if (message.startsWith("@")) {
            prefix = "@";
            prefixLen = 1;
        }

        if (!prefix.isEmpty()) {
            String afterPrefix = message.substring(prefixLen);
            String modelAlias;
            String prompt;

            if (prefix.equals("@//")) {
                // ★ @// 前缀：用 // 分隔模型名和内容，模型名可以包含单斜杠
                //   如 @//oc/mimo-v2.6-flash-free//你好 → modelAlias=oc/mimo-v2.6-flash-free, prompt=你好
                int sepIdx = afterPrefix.indexOf("//");
                if (sepIdx > 0) {
                    modelAlias = afterPrefix.substring(0, sepIdx).trim();
                    prompt = afterPrefix.substring(sepIdx + 2).trim();
                } else {
                    int spaceIdx = afterPrefix.indexOf(' ');
                    if (spaceIdx > 0) {
                        modelAlias = afterPrefix.substring(0, spaceIdx).trim();
                        prompt = afterPrefix.substring(spaceIdx + 1).trim();
                    } else {
                        modelAlias = afterPrefix.trim();
                        prompt = "";
                    }
                }
            } else {
                // @ 前缀（单）：旧行为，用 / 或空格分隔
                int slashIdx = afterPrefix.indexOf('/');
                int spaceIdx = afterPrefix.indexOf(' ');
                if (slashIdx > 0) {
                    modelAlias = afterPrefix.substring(0, slashIdx).trim();
                    prompt = afterPrefix.substring(slashIdx + 1).trim();
                } else if (spaceIdx > 0) {
                    modelAlias = afterPrefix.substring(0, spaceIdx).trim();
                    prompt = afterPrefix.substring(spaceIdx + 1).trim();
                } else {
                    modelAlias = afterPrefix.trim();
                    prompt = "";
                }
            }

            if (modelAlias.isEmpty()) {
                p.sendMessage("§c[AI聊天] §f格式错误，应为 @//模型名//内容");
                return;
            }
            if (prompt.isEmpty()) {
                // 不带内容时用默认提示：让模型根据人设自我介绍
                prompt = "请用一句话介绍一下你自己，以及你能为服务器玩家做什么。";
            }
            sendToModel(modelAlias, prompt, p, false);
        }
    }

    // ==================== 空闲调度器 ====================

    public void startAutoChatScheduler() {
        if (autoChatTask != null) {
            autoChatTask.cancel();
        }
        // 每1秒检查一次
        autoChatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                checkAutoChat();
            } catch (Exception e) {
                plugin.getLogger().warning("[AI聊天] 自动互动异常: " + e.getMessage());
            }
        }, 20L, 20L);
        plugin.getLogger().info("[AI聊天] 空闲自动互动调度器已启动");
    }

    public void stopAutoChatScheduler() {
        if (autoChatTask != null) {
            autoChatTask.cancel();
            autoChatTask = null;
        }
    }

    private void checkAutoChat() {
        if (!autoChatRunning.compareAndSet(false, true)) return;

        try {
            // 检查是否到了自动互动时间
            long intervalMs = (minIntervalMin + (int)(Math.random() * (maxIntervalMin - minIntervalMin + 1))) * 60000L;

            if (System.currentTimeMillis() - lastAiReplyTime < intervalMs) return;
            if (recentChatBuffer.isEmpty()) return;
            if (defaultModelFull.isEmpty()) return;

            // ★ 每次发请求前重读配置（静默模式，不打印日志避免刷屏）
            loadConfig(true);

            // ★ 互动指标：在线玩家数量达标才触发自动互动
            // ★ 日志静默：调度器每1秒跑一次，跳过时不再每秒刷屏。
            //   仅在状态变化（在线玩家从达标→不达标）时记一条，避免日志被刷屏。
            int onlineCount = Bukkit.getOnlinePlayers().size();
            if (onlineCount < interactionMetric) {
                if (prevOnlineSufficient) {
                    // 之前达标，现在不达标了：状态变化，记一条
                    plugin.getLogger().info("[AI聊天] 在线玩家 " + onlineCount + " < 互动指标 " + interactionMetric + "，停止自动互动");
                }
                prevOnlineSufficient = false;
                return;
            }
            // 在线玩家刚从不足→达标：记一条恢复
            if (!prevOnlineSufficient) {
                plugin.getLogger().info("[AI聊天] 在线玩家 " + onlineCount + " ≥ 互动指标 " + interactionMetric + "，恢复自动互动");
            }
            prevOnlineSufficient = true;

            ModelEndpoint endpoint = modelEndpoints.get(defaultModelFull);
            if (endpoint == null) return;

            // 构建自动互动提示词 — 用压缩方法（超阈值时截断旧消息）
            String recentChat = compressChatBuffer(endpoint.maxToken);
            String prompt = "以下是服务器最近的聊天记录:\n" + recentChat + "\n\n请根据聊天内容，自然地抛出一个话题或问题，引导大家参与讨论，带动服务器的活跃气氛。不要直接点名任何玩家，而是抛出一个开放性的话题让大家都能参与。";

            // ★ 死循环防护：触发前先更新时间戳，请求挂起/超时/失败都不会被下一秒重复触发
            lastAiReplyTime = System.currentTimeMillis();

            // ★ 日志静默：正常触发不打印"触发自动互动"，只在失败时记一条简要错误
            //   状态变化（失败→成功 或 成功→失败）时记一条恢复/告警

            // 异步发送
            String systemPrompt = buildSystemPrompt();
            String jsonBody = buildChatRequest(defaultModelFull, systemPrompt, prompt, endpoint.maxToken);
            String requestUrl = buildRequestUrl(endpoint);

            CompletableFuture.supplyAsync(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(requestUrl))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Authorization", "Bearer " + endpoint.apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .timeout(Duration.ofSeconds(60))
                            .build();

                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // ★ 完整异常诊断（与 sendToModel 一致）：区分连接超时/响应超时/DNS/SSL/拒绝连接
                    plugin.getLogger().warning("[AI聊天-调试] === 自动互动请求异常 ===");
                    plugin.getLogger().warning("[AI聊天-调试] 异常类型: " + e.getClass().getName());
                    plugin.getLogger().warning("[AI聊天-调试] 异常消息: " + e.getMessage());
                    plugin.getLogger().warning("[AI聊天-调试] 模型全名: " + defaultModelFull);
                    plugin.getLogger().warning("[AI聊天-调试] 请求URL: " + requestUrl);
                    StackTraceElement[] st = e.getStackTrace();
                    for (int i = 0; i < Math.min(5, st.length); i++) {
                        plugin.getLogger().warning("[AI聊天-调试] 栈: " + st[i].toString());
                    }
                    if (e instanceof java.net.http.HttpConnectTimeoutException) {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: 连接超时——请求未发出，目标地址不可达或DNS解析慢");
                    } else if (e instanceof java.net.http.HttpTimeoutException) {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: 响应超时——请求已发出，上游60秒内未返回（可能限流/处理慢/挂死）");
                    } else if (e instanceof java.net.ConnectException) {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: 连接被拒——目标端口未监听或防火墙拦截");
                    } else if (e instanceof java.net.UnknownHostException) {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: DNS解析失败——域名不存在或DNS配置问题");
                    } else if (e instanceof javax.net.ssl.SSLException) {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: SSL/TLS握手失败——证书问题或协议不匹配");
                    } else {
                        plugin.getLogger().warning("[AI聊天-调试] 诊断: 其他异常——详见上方类型和消息");
                    }
                    plugin.getLogger().warning("[AI聊天-调试] === 自动互动请求异常结束 ===");
                    return null;
                }
            }).thenAccept(response -> {
                if (response == null) {
                    plugin.getLogger().warning("[AI聊天-调试] 自动互动请求失败: 网络异常/超时");
                    plugin.getLogger().warning("[AI聊天-调试] 模型全名: " + defaultModelFull);
                    plugin.getLogger().warning("[AI聊天-调试] 请求URL: " + requestUrl);
                    plugin.getLogger().warning("[AI聊天-调试] 请求体JSON: " + jsonBody);
                    handleRequestFailure("[自动] " + prompt.substring(0, Math.min(100, prompt.length())), "自动互动失败: 请求异常");
                    prevAutoChatFailed = true;
                    return;
                }
                if (response.statusCode() != 200) {
                    // ★ 完整错误日志：请求头 + HTTP 返回一起打出来，定位上游是否真寄了
                    String errBody = response.body();
                    plugin.getLogger().warning("[AI聊天-调试] === 自动互动请求失败 ===");
                    plugin.getLogger().warning("[AI聊天-调试] HTTP状态码: " + response.statusCode());
                    plugin.getLogger().warning("[AI聊天-调试] 模型全名: " + defaultModelFull);
                    plugin.getLogger().warning("[AI聊天-调试] 请求URL: " + requestUrl);
                    plugin.getLogger().warning("[AI聊天-调试] 请求体JSON: " + jsonBody);
                    response.headers().map().forEach((name, values) -> {
                        plugin.getLogger().warning("[AI聊天-调试] 响应头 " + name + ": " + String.join(", ", values));
                    });
                    plugin.getLogger().warning("[AI聊天-调试] 响应体: " + errBody);
                    plugin.getLogger().warning("[AI聊天-调试] === 自动互动请求失败结束 ===");
                    handleRequestFailure("[自动] " + prompt.substring(0, Math.min(100, prompt.length())),
                            "自动互动失败: HTTP " + response.statusCode());
                    prevAutoChatFailed = true;
                    return;
                }

                String reply = parseChatResponse(response.body());
                if (reply != null) {
                    Bukkit.broadcastMessage("§d§l[AI] §f" + reply);
                    recordIssue("[自动] " + prompt.substring(0, Math.min(100, prompt.length())), reply);
                    lastAiReplyTime = System.currentTimeMillis();

                    // ★ 状态变化：失败→成功，记一条恢复日志
                    if (prevAutoChatFailed) {
                        plugin.getLogger().info("[AI聊天] 自动互动恢复正常");
                        prevAutoChatFailed = false;
                    }
                } else {
                    plugin.getLogger().warning("[AI聊天] 自动互动响应解析失败");
                    handleRequestFailure("[自动] " + prompt.substring(0, Math.min(100, prompt.length())),
                            "自动互动失败: 响应解析失败");
                    prevAutoChatFailed = true;
                }
            });
        } finally {
            autoChatRunning.set(false);
        }
    }

    // ==================== 黑名单 ====================

    public boolean isBlacklisted(String content) {
        if (content == null || content.isEmpty()) return false;
        for (String kw : blacklist) {
            if (kw != null && !kw.isEmpty() && content.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 对话留档 ====================

    private void recordIssue(String question, String answer) {
        if (issueFile == null) return;
        try {
            issueRecord.addProperty(question, answer);
            // 原子写入
            File tmp = new File(issueFile.getParent(), "issue.tmp.json");
            Files.write(tmp.toPath(), issueRecord.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), issueFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            plugin.getLogger().warning("[AI聊天] 对话留档失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private String getStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return "";
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return "";
        return el.isJsonPrimitive() ? el.getAsString() : "";
    }

    // ==================== 生命周期 ====================

    public void onEnable() {
        startAutoChatScheduler();
    }

    public void onDisable() {
        stopAutoChatScheduler();
    }
}
