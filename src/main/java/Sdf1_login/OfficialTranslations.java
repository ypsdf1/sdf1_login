package Sdf1_login;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * MC 官方中文翻译加载器。<br>
 * <br>
 * 工作流程优先级：<br>
 * 1. 本地缓存 (plugins/Sdf1_login/zh_cn.json) —— 同步加载，开机即用<br>
 * 2. Mojang API —— 异步下载最新版，写缓存并刷入内存<br>
 * 3. 全部失败 —— 返回 null，调用者走机翻兜底<br>
 * <br>
 * zh_cn.json 键示例：<br>
 * {@code block.minecraft.grass_block → "草方块"}<br>
 * {@code item.minecraft.diamond_sword → "钻石剑"}<br>
 * <br>
 * 查找策略：直接 key → block.minecraft.* → item.minecraft.*
 */
public class OfficialTranslations {

    private static final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();
    private static final Gson gson = new Gson();

    // ---------- 对外 API ----------

    /**
     * 初始化（在 onEnable 调用）。<br>
     * 纯同步：先读本地缓存 &rarr; 没有则从 Mojang API 下载并写缓存。<br>
     * 缓存文件由部署脚本随 JAR 一起上传，确保服务器不直连 Mojang 也能加载。
     */
    public static void init(Main plugin) {
        File cacheFile = new File(plugin.getDataFolder(), "zh_cn.json");

        // 1. 本地缓存 —— 同步加载
        if (cacheFile.isFile()) {
            try {
                String content = Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8);
                loadFromJson(content);
                plugin.getLogger().info("[官方翻译] 已加载本地缓存 zh_cn.json（" + map.size() + " 条）");
                return; // 缓存命中，完毕
            } catch (Exception e) {
                plugin.getLogger().warning("[官方翻译] 本地缓存损坏: " + e.getMessage());
            }
        }

        // 2. 无缓存或损坏 → 同步从 Mojang API 下载
        plugin.getLogger().info("[官方翻译] 本地缓存不存在/损坏，从 Mojang API 同步下载...");
        try {
            String version = getMinecraftVersion();
            if (version == null) throw new RuntimeException("无法获取服务器版本号");
            String json = downloadFromMojang(version);
            if (json == null) throw new RuntimeException("下载返回空");
            Files.writeString(cacheFile.toPath(), json, StandardCharsets.UTF_8);
            loadFromJson(json);
            plugin.getLogger().info("[官方翻译] Mojang 下载成功（" + map.size() + " 条），已缓存至 " + cacheFile.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("[官方翻译] 同步下载失败: " + e.getMessage() + "，走机翻兜底");
            plugin.getLogger().log(Level.FINE, "[官方翻译] 同步下载详情", e);
        }
    }

    /**
     * 查找官方翻译。<br>
     * 传入原始 Material 名（如 {@code GRASS_BLOCK}），自动尝试多种 key 模式。
     *
     * @param materialName 原始 Material 名（大小写无关）
     * @return 中文翻译，或 null（未命中）
     */
    public static String lookUp(String materialName) {
        if (materialName == null || materialName.isEmpty()) return null;
        String lower = materialName.toLowerCase(Locale.ROOT);

        // 日志：当前 map 状态
        Bukkit.getLogger().info("[翻译] officialLookUp: " + materialName
                + " | map已加载=" + isLoaded() + " 条目数=" + map.size());

        // 1. 精确匹配（部分 key 可直接用）
        String val = map.get(lower);
        if (val != null) {
            Bukkit.getLogger().info("[翻译] officialLookUp 命中(精确): " + lower + " → " + val);
            return val;
        }

        // 2. block.minecraft.<name>
        val = map.get("block.minecraft." + lower);
        if (val != null) {
            Bukkit.getLogger().info("[翻译] officialLookUp 命中(block): " + lower + " → " + val);
            return val;
        }

        // 3. item.minecraft.<name>
        val = map.get("item.minecraft." + lower);
        if (val != null) {
            Bukkit.getLogger().info("[翻译] officialLookUp 命中(item): " + lower + " → " + val);
            return val;
        }

        Bukkit.getLogger().info("[翻译] officialLookUp 未命中: " + lower + "，将走机翻兜底");
        return null; // 未命中 -> 调用者走机翻
    }

    /** 当前加载的条目数（调试用） */
    public static int size() {
        return map.size();
    }

    /** 是否已完成至少一次加载（缓存或下载） */
    public static boolean isLoaded() {
        return !map.isEmpty();
    }

    // ---------- 内部 ----------

    /** 解析 zh_cn.json 字符串灌入内存 */
    static void loadFromJson(String json) {
        JsonObject obj = gson.fromJson(json, JsonObject.class);
        if (obj == null) return;
        map.clear();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
    }

    /** 从 Mojang API 拉取 zh_cn.json（完整链：manifest → version → asset index → object） */
    static String downloadFromMojang(String version) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // Step 1: Version manifest
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://launchermeta.mojang.com/mc/game/version_manifest.json"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Sdf1Login/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        JsonObject manifest = gson.fromJson(resp.body(), JsonObject.class);

        // 在 versions 数组中找目标版本
        String versionUrl = null;
        JsonArray versions = manifest.getAsJsonArray("versions");
        if (versions != null) {
            for (int i = 0; i < versions.size(); i++) {
                JsonObject v = versions.get(i).getAsJsonObject();
                if (version.equals(v.get("id").getAsString())) {
                    versionUrl = v.get("url").getAsString();
                    break;
                }
            }
        }
        if (versionUrl == null) return null;

        // Step 2: Version JSON (获取 assetIndex url)
        req = HttpRequest.newBuilder()
                .uri(URI.create(versionUrl))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Sdf1Login/1.0")
                .GET()
                .build();
        resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        JsonObject versionJson = gson.fromJson(resp.body(), JsonObject.class);
        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        if (assetIndex == null) return null;
        String assetIndexUrl = assetIndex.get("url").getAsString();

        // Step 3: Asset index (定位 zh_cn.json 的 hash)
        req = HttpRequest.newBuilder()
                .uri(URI.create(assetIndexUrl))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Sdf1Login/1.0")
                .GET()
                .build();
        resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        JsonObject assets = gson.fromJson(resp.body(), JsonObject.class);
        JsonObject objects = assets.getAsJsonObject("objects");
        if (objects == null) return null;

        JsonObject langEntry = objects.getAsJsonObject("minecraft/lang/zh_cn.json");
        if (langEntry == null) return null;
        String hash = langEntry.get("hash").getAsString();
        if (hash == null || hash.length() < 2) return null;

        // Step 4: 下载 zh_cn.json
        String langUrl = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
        req = HttpRequest.newBuilder()
                .uri(URI.create(langUrl))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sdf1Login/1.0")
                .GET()
                .build();
        resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
            return resp.body();
        }
        return null;
    }

    /** 从 Bukkit API 获取纯版本号，如 "1.21.4" */
    private static String getMinecraftVersion() {
        String ver = Bukkit.getBukkitVersion();
        if (ver == null) return null;
        int dash = ver.indexOf('-');
        return dash > 0 ? ver.substring(0, dash) : ver;
    }
}
