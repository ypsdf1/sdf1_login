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
    private final List<String> blacklist = new ArrayList<>();

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
                        parseModelConfig(modelConfig);
                    }
                }

                // 解析全局配置（配置部分 → "2"）
                if (config.has("2")) {
                    JsonObject section2 = config.getAsJsonObject("2");
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

            plugin.getLogger().info("[AI聊天] 配置加载完成: " + modelEndpoints.size() + "个模型, "
                    + aliasToFullModel.size() + "个别名, 黑名单" + blacklist.size() + "条");

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

    private void parseModelConfig(JsonObject modelConfig) {
        // 遍历所有供应商配置
        for (Map.Entry<String, JsonElement> entry : modelConfig.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject supplierConfig = entry.getValue().getAsJsonObject();

            // 判断是内置还是自定义
            boolean isCustom = supplierConfig.has("baseurl");
            boolean isBuiltin = supplierConfig.has("供应商名字");

            if (!isCustom && !isBuiltin) continue;

            String baseUrl;
            String apiKey = getStr(supplierConfig, "apikey");
            String maxToken = getStr(supplierConfig, "max_token");
            if (maxToken == null || maxToken.isEmpty()) maxToken = defaultContextLength;

            if (isCustom) {
                baseUrl = getStr(supplierConfig, "baseurl");
            } else {
                String providerName = getStr(supplierConfig, "供应商名字");
                baseUrl = findProviderUrl(providerName);
            }

            if (baseUrl == null || baseUrl.isEmpty() || apiKey == null || apiKey.isEmpty()) {
                continue;
            }

            // 确保 baseUrl 以 / 结尾（用于拼接 chat/completions）
            if (!baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "/";
            }

            ModelEndpoint endpoint = new ModelEndpoint();
            endpoint.baseUrl = baseUrl;
            endpoint.apiKey = apiKey;
            endpoint.maxToken = maxToken;

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

            // 默认使用
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

        // 先在国产下找
        if (providersConfig != null && providersConfig.has("国产")) {
            String url = findUrlInObject(providersConfig.getAsJsonObject("国产"), parts);
            if (url != null) return url;
        }
        // 再在非国产下找
        if (providersConfig != null && providersConfig.has("非国产")) {
            String url = findUrlInObject(providersConfig.getAsJsonObject("非国产"), parts);
            if (url != null) return url;
        }
        return null;
    }

    private String findUrlInObject(JsonObject obj, String[] parts) {
        JsonElement el = obj.get(parts[0].trim());
        if (el == null) return null;
        if (parts.length == 1) {
            return el.isJsonPrimitive() ? el.getAsString() : null;
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
     */
    public String resolveModelAlias(String alias) {
        if (aliasToFullModel.containsKey(alias)) {
            return aliasToFullModel.get(alias);
        }
        // 如果不是简称，尝试直接作为模型全名
        if (modelEndpoints.containsKey(alias)) {
            return alias;
        }
        return null;
    }

    /**
     * 获取可用模型简称列表
     */
    public List<String> getAvailableModels() {
        return new ArrayList<>(aliasToFullModel.keySet());
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
                player.sendMessage("§c[AI聊天] §f未知模型: " + modelAlias);
                List<String> models = getAvailableModels();
                if (!models.isEmpty()) {
                    player.sendMessage("§7可用模型: " + String.join(", ", models));
                } else {
                    player.sendMessage("§7未配置模型，请先在 openai/models.json 和 openai/提示词.txt 中配置");
                }
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

        // 异步发送请求
        CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint.baseUrl + "chat/completions"))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + endpoint.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .timeout(Duration.ofSeconds(60))
                        .build();

                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception e) {
                plugin.getLogger().warning("[AI聊天] 请求异常: " + e.getMessage());
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

        // max_tokens
        try {
            int mt = Integer.parseInt(maxToken);
            if (mt > 0) {
                body.addProperty("max_tokens", mt);
            }
        } catch (NumberFormatException ignored) {}

        return gson.toJson(body);
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
    }

    // ==================== 聊天处理 ====================

    /**
     * 处理玩家聊天消息
     * 检测 @//模型名/ 前缀触发直接提问
     */
    public void handlePlayerChat(Player p, String message) {
        // 记录到聊天缓冲区
        String chatLine = p.getDisplayName() + ": " + message;
        recentChatBuffer.add(chatLine);
        if (recentChatBuffer.size() > MAX_BUFFER) {
            recentChatBuffer.remove(0);
        }

        // 检测 @//模型名/ 前缀
        if (message.startsWith("@//")) {
            // 格式: @//模型名/内容
            int slashIdx = message.indexOf('/', 3);
            if (slashIdx > 0) {
                String modelAlias = message.substring(3, slashIdx);
                String prompt = message.substring(slashIdx + 1);
                if (prompt.isEmpty()) {
                    p.sendMessage("§c[AI聊天] §f格式错误，应为 @//模型名/内容");
                    return;
                }
                sendToModel(modelAlias, prompt, p, false);
            } else {
                p.sendMessage("§c[AI聊天] §f格式错误，应为 @//模型名/内容");
            }
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

            ModelEndpoint endpoint = modelEndpoints.get(defaultModelFull);
            if (endpoint == null) return;

            // 构建自动互动提示词
            String recentChat = String.join("\n", recentChatBuffer);
            String prompt = "以下是服务器最近的聊天记录:\n" + recentChat + "\n\n请根据聊天内容，自然地参与对话或回答问题。";

            plugin.getLogger().info("[AI聊天] 触发自动互动");

            // 异步发送
            String systemPrompt = buildSystemPrompt();
            String jsonBody = buildChatRequest(defaultModelFull, systemPrompt, prompt, endpoint.maxToken);

            CompletableFuture.supplyAsync(() -> {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(endpoint.baseUrl + "chat/completions"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Authorization", "Bearer " + endpoint.apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .timeout(Duration.ofSeconds(60))
                            .build();

                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    plugin.getLogger().warning("[AI聊天] 自动互动请求异常: " + e.getMessage());
                    return null;
                }
            }).thenAccept(response -> {
                if (response == null || response.statusCode() != 200) {
                    handleRequestFailure("[自动] " + prompt.substring(0, Math.min(100, prompt.length())),
                            "自动互动失败: HTTP " + (response != null ? response.statusCode() : "N/A"));
                    return;
                }
                String reply = parseChatResponse(response.body());
                if (reply != null) {
                    Bukkit.broadcastMessage("§d§l[AI] §f" + reply);
                    recordIssue("[自动] " + prompt.substring(0, Math.min(100, prompt.length())), reply);
                    lastAiReplyTime = System.currentTimeMillis();
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
