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

        // pack.mcmeta（pack_format=54）
        try {
            Files.write(new File(packDir,
                            "pack.mcmeta").toPath(),
                    ("{\n  \"pack\": {\n"
                            + "    \"pack_format\": 54,\n"
                            + "    \"description\":"
                            + " \"Sdf1 Radio\"\n  }\n}")
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
     * 发送资源包（可选，不强制）
     * 优先用HTTP URL发送，兜底用byte[]
     */
    public void sendResourcePack(Player p) {
        UUID uuid = p.getUniqueId();
        if (pendingPack.contains(uuid)) return;
        pendingPack.add(uuid);
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> {
                    pendingPack.remove(uuid);
                }, 200L);

        String url = getResourcePackUrl();
        if (url == null || url.isEmpty()) {
            pendingPack.remove(uuid);
            return;
        }

        File zip = new File(
                plugin.getDataFolder(),
                "radiopack.zip");
        if (!zip.exists()) {
            pendingPack.remove(uuid);
            return;
        }

        // 异步：下载远程zip → 算hash → 比对本地hash
        final Player fp = p;
        final String finalUrl = url + "?t="
                + System.currentTimeMillis();

        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin, () -> {
                    try {
                        // 1. 本地hash
                        byte[] localData =
                                Files.readAllBytes(
                                        zip.toPath());
                        String localHash =
                                sha1(localData);

                        // 2. 远程hash
                        byte[] remoteData =
                                downloadBytes(finalUrl);
                        String remoteHash =
                                sha1(remoteData);

                        // 3. 比对
                        plugin.getLogger().info(
                                "[Radio] 本地hash: "
                                        + localHash);
                        plugin.getLogger().info(
                                "[Radio] 远程hash: "
                                        + remoteHash);

                        if (!localHash.equals(remoteHash)) {
                            plugin.getLogger().warning(
                                    "[Radio] hash不一致，"
                                            + "跳过发送资源包");
                            plugin.getLogger().warning(
                                    "[Radio] 请更新远程"
                                            + "radiopack.zip");
                            Bukkit.getScheduler()
                                    .runTask(plugin, () -> {
                                        fp.sendMessage(
                                                "§7[Radio] §f资源包"
                                                        + "版本不一致，"
                                                        + "跳过安装");
                                    });
                            return;
                        }

                        // 4. hash一致，发送给客户端
                        Bukkit.getScheduler()
                                .runTask(plugin, () -> {
                                    sendPackWithHash(
                                            fp, finalUrl,
                                            remoteHash);
                                });

                    } catch (Exception e) {
                        plugin.getLogger().warning(
                                "[Radio] 校验失败: "
                                        + e.getMessage());
                    }
                });
    }

    private void sendPackWithHash(
            Player p, String url, String hash) {
        UUID uuid = UUID.randomUUID();

        // Paper 1.21: setResourcePack(UUID, String, String, Component, boolean)
        try {
            Object component = net.kyori.adventure
                    .text.Component.text("Sdf1 Radio");
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class,
                            net.kyori.adventure.text
                                    .Component.class,
                            boolean.class)
                    .invoke(p, uuid, url,
                            hash, component, false);
            plugin.getLogger().info(
                    "[Radio] 发送成功: "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        // 降级：旧版 String 签名
        try {
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class,
                            String.class,
                            boolean.class)
                    .invoke(p, uuid, url,
                            hash, "Sdf1 Radio", false);
            plugin.getLogger().info(
                    "[Radio] 旧版签名成功: "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        // 降级3参
        try {
            p.getClass().getMethod(
                            "setResourcePack",
                            UUID.class,
                            String.class,
                            String.class)
                    .invoke(p, uuid, url, hash);
            plugin.getLogger().info(
                    "[Radio] 3参签名成功: "
                            + p.getName());
            return;
        } catch (Exception ignored) {
        }

        plugin.getLogger().severe(
                "[Radio] 所有签名失败: "
                        + p.getName());
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
        pendingPack.remove(uuid);
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
        if (externalUrl != null
                && !externalUrl.isEmpty()) {
            plugin.getLogger().info(
                    "[Radio] 使用外部地址: "
                            + externalUrl);
            return externalUrl;
        }
        plugin.getLogger().warning(
                "[Radio] 未配置外部地址，"
                        + "跳过资源包");
        return null;
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