package Sdf1_login;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 多源在线翻译工具 —— 将MC英文材料名翻译为中文。
 * 优先使用国内接口(有道/简心)，失败后降级到Google。
 * 结果缓存避免重复请求。
 */
public class MaterialTranslator {

    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private static final ExecutorService executor = Executors.newFixedThreadPool(2);
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // 翻译源优先级：国内接口优先
    private static final List<TranslationSource> SOURCES = Arrays.asList(
            TranslationSource.YOUDAO,
            TranslationSource.JIANXIN,
            TranslationSource.GOOGLE
    );

    /**
     * 翻译源枚举
     */
    private enum TranslationSource {
        YOUDAO("有道翻译"),
        JIANXIN("简心翻译"),
        GOOGLE("Google翻译");

        final String name;

        TranslationSource(String name) {
            this.name = name;
        }
    }

    /**
     * 将材料名（如 GRASS_BLOCK）转为可读英文（Grass Block）
     */
    public static String toReadable(String materialName) {
        if (materialName == null || materialName.isEmpty()) return "";
        return Arrays.stream(materialName.split("_"))
                .map(w -> w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase())
                .reduce((a, b) -> a + " " + b)
                .orElse(materialName);
    }

    /**
     * 同步翻译单个材料名（有缓存则直接返回）
     */
    public static String translate(String materialName) {
        if (materialName == null || materialName.isEmpty()) return "";
        String key = materialName.toUpperCase();
        if (cache.containsKey(key)) return cache.get(key);

        String readable = toReadable(key);
        String zh = translateWithFallback(readable);
        if (zh != null && !zh.isEmpty()) {
            cache.put(key, zh);
            return zh;
        }
        return readable; // fallback 可读英文
    }

    /**
     * 批量异步翻译，返回 future map
     */
    public static CompletableFuture<Map<String, String>> translateBatch(Collection<String> materials) {
        Set<String> unique = new LinkedHashSet<>();
        for (String m : materials) {
            if (m != null && !m.isEmpty() && !cache.containsKey(m.toUpperCase())) {
                unique.add(m);
            }
        }
        if (unique.isEmpty()) {
            Map<String, String> result = new HashMap<>();
            for (String m : materials) {
                if (m != null) result.put(m, cache.getOrDefault(m.toUpperCase(), toReadable(m)));
            }
            return CompletableFuture.completedFuture(result);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String m : unique) {
            futures.add(CompletableFuture.runAsync(() -> translate(m), executor));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    Map<String, String> result = new HashMap<>();
                    for (String m : materials) {
                        if (m != null) {
                            result.put(m, cache.getOrDefault(m.toUpperCase(), toReadable(m)));
                        }
                    }
                    return result;
                });
    }

    /**
     * 多源翻译，按优先级尝试，失败自动降级
     */
    private static String translateWithFallback(String text) {
        for (TranslationSource source : SOURCES) {
            try {
                String result = null;
                switch (source) {
                    case YOUDAO:
                        result = translateViaYoudao(text);
                        break;
                    case JIANXIN:
                        result = translateViaJianxin(text);
                        break;
                    case GOOGLE:
                        result = translateViaGoogle(text);
                        break;
                }
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                // 该源失败，尝试下一个
            }
        }
        return null;
    }

    // ==================== 翻译源实现 ====================

    /**
     * 有道翻译API
     * URL: https://v.api.aa1.cn/api/api-fanyi-yd/index.php
     * 参数: msg=翻译内容, type=1(中→英) / 2(英→中)
     */
    private static String translateViaYoudao(String text) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            // type=2 表示英→中
            String url = "https://v.api.aa1.cn/api/api-fanyi-yd/index.php?msg=" + encoded + "&type=2";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // 返回: {"type":"中英互译","desc":"中文翻译英文","text":"翻译结果"}
                return parseJsonField(resp.body(), "text");
            }
        } catch (Exception e) {
            // 静默降级
        }
        return null;
    }

    /**
     * 简心翻译API
     * URL: https://api.qvqa.cn/api/fanyi
     * 参数: text=翻译内容, source=en, target=zh
     */
    private static String translateViaJianxin(String text) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://api.qvqa.cn/api/fanyi?text=" + encoded + "&source=en&target=zh";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // 返回: {"code":200,"message":"OK","data":{"sourceText":"...","targetText":"翻译结果",...}}
                return parseNestedJsonField(resp.body(), "data", "targetText");
            }
        } catch (Exception e) {
            // 静默降级
        }
        return null;
    }

    /**
     * Google Translate 免费接口
     */
    private static String translateViaGoogle(String text) {
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://clients5.google.com/translate_a/single"
                    + "?client=gtx&sl=en&tl=zh-CN&dt=t&q=" + encoded;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                // 响应格式: [["翻译结果","原文",...],...]
                String body = resp.body();
                int firstQuote = body.indexOf("[[\"");
                if (firstQuote >= 0) {
                    int endQuote = body.indexOf("\"", firstQuote + 3);
                    if (endQuote > firstQuote) {
                        return body.substring(firstQuote + 3, endQuote);
                    }
                }
            }
        } catch (Exception e) {
            // 静默降级
        }
        return null;
    }

    // ==================== JSON解析工具 ====================

    /**
     * 简单JSON字段解析（单层）
     * 从 {"key":"value"} 中提取 value
     */
    private static String parseJsonField(String json, String field) {
        try {
            String pattern = "\"" + field + "\"";
            int idx = json.indexOf(pattern);
            if (idx < 0) return null;

            // 找到冒号后的引号
            int colon = json.indexOf(':', idx + pattern.length());
            if (colon < 0) return null;

            int quoteStart = json.indexOf('"', colon + 1);
            if (quoteStart < 0) return null;

            int quoteEnd = json.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) return null;

            return json.substring(quoteStart + 1, quoteEnd);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 嵌套JSON字段解析
     * 从 {"outer":{"inner":"value"}} 中提取 value
     */
    private static String parseNestedJsonField(String json, String outerField, String innerField) {
        try {
            // 找到外层对象
            String outerPattern = "\"" + outerField + "\"";
            int outerIdx = json.indexOf(outerPattern);
            if (outerIdx < 0) return null;

            // 找到外层对象的开始大括号
            int braceStart = json.indexOf('{', outerIdx);
            if (braceStart < 0) return null;

            // 从外层对象内部解析内层字段
            int depth = 1;
            int i = braceStart + 1;
            while (i < json.length() && depth > 0) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
                i++;
            }
            String innerJson = json.substring(braceStart, i);
            return parseJsonField(innerJson, innerField);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清理缓存（可选）
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * 获取当前使用的翻译源信息（调试用）
     */
    public static String getSourceInfo() {
        StringBuilder sb = new StringBuilder("翻译源优先级: ");
        for (int i = 0; i < SOURCES.size(); i++) {
            TranslationSource src = SOURCES.get(i);
            sb.append(i + 1).append(".").append(src.name);
            if (i < SOURCES.size() - 1) sb.append(" > ");
        }
        return sb.toString();
    }
}
