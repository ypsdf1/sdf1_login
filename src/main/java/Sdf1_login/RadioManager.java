package Sdf1_login;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import java.util.Collections;
import org.bukkit.NamespacedKey;
import java.util.Set;


public class RadioManager {

    private final Main plugin;
    private File radioDir;
    private File oggDir;
    private File packDir;
    private BukkitTask mainRadioTask;
    private final Map<String, Boolean> loginPlayers =
            new ConcurrentHashMap<>();
    private String externalUrl = "";
    private int httpPort = 443;
    private final Set<UUID> pendingPack =
            Collections.newSetFromMap(
                    new ConcurrentHashMap<>());
    private String lastPlayedSound = "";

    private int radioIndex = 0;

    // ========== 登录阶段推送（管理员手动触发） ==========
    public void startLoginRadio(Player p) {
        loginPlayers.put(p.getName(), true);
    }

    public void stopLoginRadio(Player p) {
        loginPlayers.remove(p.getName());
    }

    public void stopAllLoginRadio() {
        loginPlayers.clear();
    }

    private final List<File> mainFiles = new ArrayList<>();
    private final List<File> loginFiles = new ArrayList<>();

    public RadioManager(Main plugin) {
        this.plugin = plugin;
        this.radioDir = new File(
                plugin.getDataFolder(), "radio");
        this.oggDir = new File(
                plugin.getDataFolder(), "radio/ogg");
        this.packDir = new File(
                plugin.getDataFolder(), "radiopack");
        radioDir.mkdirs();
        oggDir.mkdirs();
        packDir.mkdirs();
        loadRadioConfig();
    }
    // ========== 读取 radio_config.txt ==========
    private void loadRadioConfig() {
        File cfg = new File(
                plugin.getDataFolder(),
                "radio_config.txt");

        // 不存在则创建默认配置
        if (!cfg.exists()) {
            createRadioConfig(cfg);
            plugin.getLogger().info(
                    "[Radio] 已生成 radio_config.txt");
        }

        // 读取
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(cfg),
                        java.nio.charset
                                .StandardCharsets
                                .UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")
                        || line.trim().isEmpty())
                    continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq)
                        .trim();
                String val = line.substring(eq + 1)
                        .trim();
                if (key.equals("resource-pack-url")) {
                    externalUrl = val;
                } else if (key.equals("http-port")) {
                    try {
                        httpPort = Integer.parseInt(
                                val);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] 读取radio_config.txt失败: "
                            + e.getMessage());
        }

        // 443是HTTPS端口，内嵌HTTP不能用
        if (httpPort == 443) httpPort = 8080;

        plugin.getLogger().info(
                "[Radio] 配置: externalUrl=["
                        + externalUrl + "] httpPort="
                        + httpPort);
    }

    private void createRadioConfig(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        java.nio.charset
                                .StandardCharsets
                                .UTF_8))) {
            pw.println("# ============ Sdf1 Radio Config ============");
            pw.println("#");
            pw.println("# resource-pack-url");
            pw.println("#   填外部地址（HTTP或HTTPS均可）");
            pw.println("#   例: https://example.com/radiopack.zip");
            pw.println("#   留空 = 使用内置HTTP服务");
            pw.println("#");
            pw.println("# http-port");
            pw.println("#   内置HTTP端口（仅resource-pack-url为空时生效）");
            pw.println("#");
            pw.println("# ============ Settings ===============");
            pw.println();
            pw.println("resource-pack-url=");
            pw.println("http-port=8080");
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] 创建radio_config.txt失败: "
                            + e.getMessage());
        }
    }

    // ========== 初始化 ==========
    public void init() {
        loadRadioConfig();
        convertNonOgg();
        loadAllOggs();
        buildResourcePack();
        startHttpServer();  // 启动HTTP
        plugin.getLogger().info(
                "[Radio] 初始化完成: 全服="
                        + mainFiles.size()
                        + " 登录=" + loginFiles.size());
    }


    // ========== 转换非ogg文件 ==========
    private void convertNonOgg() {
        convertDir(radioDir);
        convertDir(new File(radioDir, "login"));
        convertDir(new File(radioDir, "reg"));
    }

    private void convertDir(File src) {
        if (!src.exists() || !src.isDirectory()) return;
        File[] files = src.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".mp3")
                    || l.endsWith(".wav")
                    || l.endsWith(".flac");
        });
        if (files == null) return;

        for (File f : files) {
            String oggName = f.getName()
                    .replaceAll("\\.(mp3|wav|flac)$",
                            ".ogg");
            File oggFile = new File(src, oggName);
            if (oggFile.exists()
                    && oggFile.lastModified()
                    >= f.lastModified()) {
                continue;
            }
            // 调用ffmpeg转换
            if (convertWithFfmpeg(f, oggFile)) {
                plugin.getLogger().info(
                        "[Radio] 转换成功: "
                                + f.getName());
            } else {
                plugin.getLogger().warning(
                        "[Radio] 转换失败: "
                                + f.getName()
                                + " (需要安装ffmpeg)");
            }
        }
    }

    private boolean convertWithFfmpeg(
            File input, File output) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", input.getAbsolutePath(),
                    "-c:a", "libvorbis",
                    "-q:a", "5",
                    output.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished =
                    p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return output.exists()
                    && output.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ========== 加载ogg文件列表 ==========
    private void loadAllOggs() {
        mainFiles.clear();
        loginFiles.clear();
        loadOggsFlat(radioDir, mainFiles);
        loadOggsFlat(
                new File(radioDir, "login"),
                loginFiles);
        loadOggsFlat(
                new File(radioDir, "reg"),
                loginFiles);
    }

    private void loadOggsFlat(
            File dir, List<File> list) {
        if (!dir.exists() || !dir.isDirectory())
            return;
        File[] files = dir.listFiles(
                (d, n) -> n.toLowerCase()
                        .endsWith(".ogg"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) list.add(f);
    }
    /**
     * 解析 pack.png：优先读取插件目录，
     * 没有则搜索图片并用ffmpeg转码
     */
    private File resolvePackIcon() {
        File dataDir = plugin.getDataFolder();
        File packPng = new File(dataDir, "pack.png");

        if (packPng.exists()
                && packPng.length() > 0) {
            plugin.getLogger().info(
                    "[Radio] 找到 pack.png ("
                            + packPng.length()
                            + " bytes)");
            return packPng;
        }

        // 搜索可转换的图片文件
        String[] exts = {"png", "jpg", "jpeg",
                "bmp", "webp", "tiff", "gif"};
        File[] found = dataDir.listFiles(
                (dir, name) -> {
                    String low = name.toLowerCase();
                    for (String ext : exts) {
                        if (low.endsWith("." + ext)
                                && !name.equals(
                                "pack.png")) {
                            return true;
                        }
                    }
                    return false;
                });

        if (found != null && found.length > 0) {
            File src = found[0];
            plugin.getLogger().info(
                    "[Radio] 找到图片: "
                            + src.getName()
                            + " → 转码为 pack.png");
            if (convertToPackPng(src, packPng)) {
                return packPng;
            }
            plugin.getLogger().warning(
                    "[Radio] 转码失败");
            return null;
        }

        plugin.getLogger().warning(
                "[Radio] 未找到 pack.png 或可转换图片");
        return null;
    }

    /**
     * ffmpeg 将任意图片转为 256x256 PNG
     */
    private boolean convertToPackPng(
            File input, File output) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", input.getAbsolutePath(),
                    "-vf",
                    "scale='min(256,iw)'"
                            + ":'min(256,ih)':"
                            + "force_original_aspect_ratio"
                            + "=decrease,"
                            + "pad=256:256:"
                            + "(ow-iw)/2:"
                            + "(oh-ih)/2:"
                            + "color=0x00000000",
                    "-c:v", "png",
                    output.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(
                    30, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            return output.exists()
                    && output.length() > 0;
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] ffmpeg转码失败: "
                            + e.getMessage());
            return false;
        }
    }

    // ========== 资源包构建 ==========
    private void buildResourcePack() {
        deleteDir(packDir);
        packDir.mkdirs();

        List<File> allOggs = new ArrayList<>();
        collectOggs(radioDir, allOggs);

        if (allOggs.isEmpty()) {
            plugin.getLogger().warning(
                    "[Radio] radio/ 目录下"
                            + "无 .ogg 文件，跳过资源包");
            return;
        }

        // 正确路径：assets/minecraft/sounds/custom/
        File soundsDir = new File(packDir,
                "assets/minecraft/sounds/custom");
        soundsDir.mkdirs();

        // 生成 sounds.json
        StringBuilder json =
                new StringBuilder("{\n");
        for (int i = 0; i < allOggs.size(); i++) {
            File ogg = allOggs.get(i);
            String simpleName =
                    ogg.getName()
                            .replace(".ogg", "");
            File dest = new File(soundsDir,
                    simpleName + ".ogg");
            try {
                Files.copy(ogg.toPath(),
                        dest.toPath(),
                        StandardCopyOption
                                .REPLACE_EXISTING);
            } catch (Exception e) {
                // ignore
            }
            // 音频事件名: custom.xxx
            // 文件路径: custom/xxx
            json.append("  \"custom.")
                    .append(simpleName)
                    .append("\": {\n");
            json.append("    \"sounds\": [{\n");
            json.append("      \"name\":"
                            + " \"custom/")
                    .append(simpleName)
                    .append("\",\n");
            json.append(
                    "      \"stream\": true\n");
            json.append("    }]\n");
            json.append("  }");
            if (i < allOggs.size() - 1)
                json.append(",");
            json.append("\n");
        }
        json.append("}");

        // sounds.json 放在 assets/minecraft/ 下
        try {
            Files.write(new File(packDir,
                            "assets/minecraft/sounds.json")
                            .toPath(),
                    json.toString()
                            .getBytes("UTF-8"));
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] sounds.json写入失败");
        }
// sounds.json 写入后...

// ===== 新增：打包 pack.png 作为资源包图标 =====
        File iconPng = resolvePackIcon();
        if (iconPng != null && iconPng.exists()) {
            try {
                Files.copy(iconPng.toPath(),
                        new File(packDir, "pack.png")
                                .toPath(),
                        StandardCopyOption
                                .REPLACE_EXISTING);
                plugin.getLogger().info(
                        "[Radio] pack.png 已加入资源包");
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "[Radio] pack.png复制失败: "
                                + e.getMessage());
            }
        }

        // pack.mcmeta（pack_format=54）
        try {
            Files.write(new File(packDir,
                            "pack.mcmeta").toPath(),
                    ("{\n  \"pack\": {\n"
                            + "    \"pack_format\": 54,\n"
                            + "    \"description\":"
                            + " \"§d§l草原探险服务器资源包，请勿拒绝资源包\"\n  }\n}")
                            .getBytes("UTF-8"));
        } catch (Exception e) {
            // ignore
        }

        try {
            File zipFile = new File(
                    plugin.getDataFolder(),
                    "radiopack.zip");
            packZip(packDir, zipFile);
            plugin.getLogger().info(
                    "[Radio] 资源包生成: "
                            + allOggs.size() + " 个音频");
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] zip打包失败: "
                            + e.getMessage());
        }
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private void collectOggs(
            File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory())
                collectOggs(f, list);
            else if (f.getName().endsWith(".ogg"))
                list.add(f);
        }
    }

    private void packZip(File root,
                         File zipFile) throws Exception {
        try (ZipOutputStream zos =
                     new ZipOutputStream(
                             new FileOutputStream(
                                     zipFile))) {
            zipDir(root, root, zos);
        }
    }

    private void zipDir(File root,
                        File current,
                        ZipOutputStream zos)
            throws Exception {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                zipDir(root, f, zos);
            } else {
                String path = root.toURI()
                        .relativize(f.toURI())
                        .getPath();
                zos.putNextEntry(new ZipEntry(path));
                zos.write(Files.readAllBytes(
                        f.toPath()));
                zos.closeEntry();
            }
        }
    }

    // ========== 资源包发送 ==========

    /**
     * 发送资源包（带限流，不禁重试）
     */
    private final Map<UUID, Long> lastSendTime =
            new ConcurrentHashMap<>();

    public void sendResourcePack(Player p) {
        UUID uuid = p.getUniqueId();

        // 最短间隔 5秒（不是10秒的全锁死）
        Long last = lastSendTime.get(uuid);
        if (last != null
                && System.currentTimeMillis() - last
                < 5000L) {
            plugin.getLogger().info(
                    "[Radio] 跳过(5秒内): "
                            + p.getName());
            return;
        }
        lastSendTime.put(uuid,
                System.currentTimeMillis());

        String url = getResourcePackUrl();
        if (url == null || url.isEmpty()) {
            plugin.getLogger().warning(
                    "[Radio] URL为空，跳过: "
                            + p.getName());
            return;
        }

        File zip = new File(
                plugin.getDataFolder(),
                "radiopack.zip");
        if (!zip.exists()) {
            plugin.getLogger().warning(
                    "[Radio] radiopack.zip不存在，"
                            + "跳过: " + p.getName());
            return;
        }

        try {
            byte[] data =
                    Files.readAllBytes(zip.toPath());
            String hash = sha1(data);
            long fileSize = zip.length();

            plugin.getLogger().info(
                    "[Radio] 发送: " + p.getName()
                            + " url=" + url
                            + " size=" + fileSize
                            + " hash=" + hash);

            sendPackWithHash(p, url, hash);

        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Radio] 发送异常: "
                            + p.getName());
            e.printStackTrace();
        }
    }



    private void sendPackWithHash(
            Player p,
            String url,
            String hash) {

        UUID uuid = UUID.fromString(
                "a1b2c3d4-e5f6-7890-abcd-"
                        + "ef1234567890");

        // 尝试5参 + Component
        try {
            Object component =
                    net.kyori.adventure.text
                            .Component.text(
                                    "§d§l请下载自定义资源包");
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class,
                            net.kyori.adventure
                                    .text.Component
                                    .class,
                            boolean.class)
                    .invoke(
                            p, uuid, url,
                            hash, component,
                            true);
            plugin.getLogger().info(
                    "[Radio] 成功(5参): "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        // 尝试5参 + String描述
        try {
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class,
                            String.class,
                            boolean.class)
                    .invoke(
                            p, uuid, url,
                            hash,
                            "Sdf1 Radio",
                            true);
            plugin.getLogger().info(
                    "[Radio] 成功(5参String): "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        // 尝试3参
        try {
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class)
                    .invoke(
                            p, uuid, url,
                            hash);
            plugin.getLogger().info(
                    "[Radio] 成功(3参): "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        // ★ 全部失败 → 通知玩家 + 熔断
        plugin.getLogger().severe(
                "[Radio] 所有签名失败: "
                        + p.getName()
                        + " 客户端版本: "
                        + p.getProtocolVersion());
        p.sendMessage(
                "§e§l[Radio] §f资源包发送失败，"
                        + "§7部分功能可能异常");
        p.sendMessage(
                "§7可尝试: §e/rejoin §7或重启客户端");
    }



    private void sendPackFromLocal(
            Player p, String url) {
        File zip = new File(
                plugin.getDataFolder(),
                "radiopack.zip");
        if (!zip.exists()) return;
        try {
            byte[] data =
                    Files.readAllBytes(
                            zip.toPath());
            String hash = sha1(data);
            sendPackWithHash(p, url, hash);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] 降级发送失败");
        }
    }

    // 从URL下载字节
    private byte[] downloadBytes(String urlStr)
            throws Exception {
        java.net.URL url =
                new java.net.URL(urlStr);
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection)
                        url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        try (java.io.InputStream is =
                     conn.getInputStream()) {
            return is.readAllBytes();
        }
    }

    // SHA-1计算
    private String sha1(byte[] data)
            throws Exception {
        java.security.MessageDigest md =
                java.security.MessageDigest
                        .getInstance("SHA-1");
        md.update(data);
        byte[] hash = md.digest();
        StringBuilder sb =
                new StringBuilder();
        for (byte b : hash)
            sb.append(String.format(
                    "%02x", b));
        return sb.toString();
    }

    public void clearPending(UUID uuid) {
        lastSendTime.remove(uuid);
    }



    // ========== 内嵌HTTP服务 ==========
    private com.sun.net.httpserver.HttpServer
            httpServer;

    private void startHttpServer() {
        // 如果配置了外部地址则不启动
        if (plugin.getConfig2() != null
                && !plugin.getConfig2()
                .broadcastServerUrl
                .isEmpty()) {
            return;
        }
        try {
            int port = 8080;
            if (plugin.getConfig2() != null) {
                port = plugin.getConfig2().httpPort;
            }
            httpServer =
                    com.sun.net.httpserver
                            .HttpServer.create(
                                    new java.net
                                            .InetSocketAddress(
                                            port), 0);
            httpServer.createContext(
                    "/radiopack.zip",
                    exchange -> {
                        File zip = new File(
                                plugin.getDataFolder(),
                                "radiopack.zip");
                        if (zip.exists()) {
                            byte[] data =
                                    Files.readAllBytes(
                                            zip.toPath());
                            exchange.getResponseHeaders()
                                    .set("Content-Type",
                                            "application/zip");
                            exchange.getResponseHeaders()
                                    .set("Access-Control"
                                                    + "-Allow-Origin",
                                            "*");
                            exchange.sendResponseHeaders(
                                    200, data.length);
                            exchange.getResponseBody()
                                    .write(data);
                            exchange.getResponseBody()
                                    .close();
                        } else {
                            byte[] empty =
                                    "not found".getBytes();
                            exchange.sendResponseHeaders(
                                    404, empty.length);
                            exchange.getResponseBody()
                                    .write(empty);
                            exchange.getResponseBody()
                                    .close();
                        }
                    });
            httpServer.setExecutor(null);
            httpServer.start();
            plugin.getLogger().info(
                    "[Radio] HTTP服务: http://0.0.0.0:"
                            + port + "/radiopack.zip");
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[Radio] HTTP启动失败: "
                            + e.getMessage());
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    public String getResourcePackUrl() {
        // 有外部配置 → 随机生成URL
        if (externalUrl != null
                && !externalUrl.trim().isEmpty()) {
            return generateRandomUrl(externalUrl);
        }
        // 无配置 → 用内置HTTP
        int port = 8080;
        if (plugin.getConfig2() != null) {
            port = plugin.getConfig2().httpPort;
        }
        return "http://127.0.0.1:"
                + port + "/radiopack.zip";
    }


    /**
     * 从配置的域名列表中随机选一个
     * 生成随机、带时间戳的完整资源包URL
     *
     * 规则:
     *   *.example.com
     *     → 随机子域+时间戳
     *     例: a3f7k2.example.com/pack.zip?t=xxx&v=b9c1e4
     *
     *   cdn.example.com (无*)
     *     → 直接使用+时间戳
     *     例: cdn.example.com/pack.zip?t=xxx
     */
    private String generateRandomUrl(
            String raw) {
        String[] parts = raw.split("\\|");
        String pick =
                parts[(int)(Math.random()
                        * parts.length)].trim();

        // 去掉协议 https:// http://
        String cleaned = pick
                .replaceFirst("^https?://", "");
        // 去掉路径 /path/a?v=1
        if (cleaned.contains("/")) {
            cleaned = cleaned.substring(
                    0, cleaned.indexOf('/'));
        }

        // 随机子域
        String hex = Long.toHexString(
                Double.doubleToLongBits(
                        Math.random()));
        String rand = hex.length() > 6
                ? hex.substring(2, 8) : hex;
        rand = rand.replaceAll(
                "[^a-z0-9]", "x");

        String host;
        if (cleaned.startsWith("*")) {
            host = rand
                    + cleaned.substring(1);
        } else {
            host = cleaned;
        }

        String ts = String.valueOf(
                System.currentTimeMillis());
        String token = Integer.toHexString(
                (int)(Math.random()
                        * 0xFFFFFF));

        String url = "https://" + host
                + "/radiopack.zip"
                + "?t=" + ts
                + "&v=" + token;

        plugin.getLogger().info(
                "[Radio] 生成URL: "
                        + pick + " → " + url);
        return url;
    }



    // ========== 全服广播 ==========
    public void startMainRadio() {
        stopMainRadio();
        if (mainFiles.isEmpty()) {
            plugin.getLogger().warning(
                    "[Radio] 无 .ogg 文件");
            return;
        }
        plugin.getLogger().info(
                "[Radio] 启动全服广播 ("
                        + mainFiles.size() + " 个文件)");
        mainRadioTask = new BukkitRunnable() {
            @Override
            public void run() {
                playNext();
            }
        }.runTaskTimer(plugin, 100L, 2400L);
    }

    private void playNext() {
        if (mainFiles.isEmpty()) return;
        File file = mainFiles.get(
                radioIndex % mainFiles.size());
        String name = file.getName()
                .replace(".ogg", "");
        lastPlayedSound = "custom." + name;
        String key = "minecraft:" + lastPlayedSound;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), key,
                    SoundCategory.RECORDS,
                    2.0f, 1.0f);
        }
        Bukkit.broadcastMessage(
                "§6§l[广播] §f正在播放: §e" + name);
        radioIndex++;
    }

    // ========== 按名称播放 ==========
    public void playToAll(String name) {
        String key = findSongKey(name);
        if (key == null) return;
        lastPlayedSound = "custom." + name;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), key,
                    SoundCategory.RECORDS,
                    2.0f, 1.0f);
        }
        Bukkit.broadcastMessage(
                "§6§l[广播] §f正在播放: §e" + name);
    }


    public void playToPlayer(
            Player p, String name) {
        String key = findSongKey(name);
        if (key == null) {
            p.sendMessage("§c未找到: " + name);
            return;
        }
        p.playSound(p.getLocation(), key,
                SoundCategory.RECORDS,
                2.0f, 1.0f);
    }

    public void playToLoginPlayers(String name) {
        String key = findSongKey(name);
        if (key == null) return;
        for (String pName
                : loginPlayers.keySet()) {
            Player lp = Bukkit.getPlayer(pName);
            if (lp != null && lp.isOnline()) {
                lp.playSound(lp.getLocation(),
                        key,
                        SoundCategory.RECORDS,
                        1.5f, 1.0f);
            }
        }
    }


    private String findSongKey(String name) {
        List<File> all = new ArrayList<>();
        collectOggs(radioDir, all);
        for (File f : all) {
            String fn = f.getName()
                    .replace(".ogg", "");
            if (fn.equalsIgnoreCase(name)) {
                return "minecraft:custom." + name;
            }
        }
        plugin.getLogger().warning(
                "[Radio] 未找到歌曲: " + name);
        return null;
    }


    public boolean hasSong(String name) {
        return findSongKey(name) != null;
    }

    // ========== 手动播放 ==========
    public boolean playSpecific(
            Player sender, String name) {
        List<File> all = new ArrayList<>();
        collectOggs(radioDir, all);
        for (File f : all) {
            String fn = f.getName()
                    .replace(".ogg", "");
            if (fn.equalsIgnoreCase(name)) {
                String key = "sdf1radio:" + name;
                for (Player p
                        : Bukkit.getOnlinePlayers()) {
                    p.playSound(p.getLocation(),
                            key,
                            SoundCategory.RECORDS,
                            2.0f, 1.0f);
                }
                Bukkit.broadcastMessage(
                        "§6§l[广播] §f正在播放: §e"
                                + name);
                return true;
            }
        }
        if (sender != null)
            sender.sendMessage("§c未找到: " + name);
        return false;
    }
    public void stopMainRadio() {
        if (mainRadioTask != null) {
            mainRadioTask.cancel();
            mainRadioTask = null;
        }
        if (lastPlayedSound != null
                && !lastPlayedSound.isEmpty()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.stopSound(lastPlayedSound,
                        SoundCategory.RECORDS);
            }
            lastPlayedSound = "";
        }
    }

// 在 init() 或 registerEvents 中保存引用：
// this.listener = new RadioDownloadListener(plugin);
private RadioDownloadListener radioListener;



    // ========== 重载 ==========
    public void reload() {
        stopMainRadio();
        stopAllLoginRadio();
        stopHttpServer();
        loadAllOggs();
        buildResourcePack();
        startHttpServer();
        loadRadioConfig();
    }
}