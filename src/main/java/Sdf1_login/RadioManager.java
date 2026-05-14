package Sdf1_login;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.*;

public class RadioManager {

    private final Main plugin;
    private File radioDir;
    private File oggDir;
    private File packDir;
    private File configFile;
    private BukkitTask mainRadioTask;
    private BukkitTask loginRadioTask;
    private final Map<String, Boolean> loginPlayers =
            new ConcurrentHashMap<>();
    private int radioIndex = 0;
    private HttpServer httpServer;
    private byte[] packBytes;
    private String packHash = "";
    private String resourcePackUrl = "";
    private int httpPort = 25566;
    private final List<File> sourceFiles =
            new ArrayList<>();
    private final List<File> oggFiles =
            new ArrayList<>();
    private final Set<UUID> sending =
            Collections.newSetFromMap(
                    new ConcurrentHashMap<>());
    private String adminTag = "admin";

    public RadioManager(Main plugin) {
        this.plugin = plugin;
    }

    // ========== init ==========
    public void init() {
        radioDir = new File(
                plugin.getDataFolder(), "radio");
        oggDir = new File(
                plugin.getDataFolder(),
                "radio/ogg");
        packDir = new File(
                plugin.getDataFolder(),
                "radiopack");
        radioDir.mkdirs();
        oggDir.mkdirs();
        packDir.mkdirs();

        loadRadioConfig();
        doConvert();
        buildPack();
        loadOggFiles();
        startHttpServer();

        plugin.getLogger().info(
                "[Radio] init done: "
                        + oggFiles.size() + " audio(s)");
    }

    // ========== radio_config.txt ==========
    private void loadRadioConfig() {
        configFile = new File(
                plugin.getDataFolder(),
                "radio_config.txt");
        if (!configFile.exists()) {
            writeDefaultConfig();
        }
        try (BufferedReader br =
                     new BufferedReader(
                             new FileReader(
                                     configFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()
                        || line.startsWith("#"))
                    continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq)
                        .trim();
                String val = line.substring(eq + 1)
                        .trim();
                if (key.equals(
                        "resource-pack-url")) {
                    resourcePackUrl = val;
                } else if (key.equals(
                        "http-port")) {
                    try {
                        httpPort = Integer
                                .parseInt(val);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] config error: "
                            + e.getMessage());
        }
        loadAdminTag();
        plugin.getLogger().info("[Radio] config: url="
                + (resourcePackUrl.isEmpty()
                ? "(none)" : resourcePackUrl)
                + " port=" + httpPort);
    }
    /**
     * 从插件设置.txt读取管理标签
     */
    private void loadAdminTag() {
        File settingsFile = new File(
                plugin.getDataFolder(),
                "插件设置.txt");
        if (!settingsFile.exists()) return;
        try (BufferedReader br =
                     new BufferedReader(
                             new FileReader(
                                     settingsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()
                        || line.startsWith("#"))
                    continue;
                if (line.startsWith("管理标签=")
                        || line.startsWith("管理标签 =")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        adminTag = line
                                .substring(eq + 1)
                                .trim();
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        plugin.getLogger().info(
                "[Radio] admin tag: " + adminTag);
    }

    /**
     * 检查玩家是否有管理标签
     * 控制台不受限制
     */
    public boolean hasRadioPermission(
            CommandSender sender) {
        // 控制台直接通过
        if (!(sender instanceof Player)) {
            return true;
        }
        Player p = (Player) sender;

        // 检查Bukkit权限节点
        // 格式: sdf1.admin
        if (p.hasPermission("sdf1.admin")) {
            return true;
        }

        // 检查玩家名是否在admin列表中
        // 你可以扩展这里对接你的标签系统
        return false;
    }

    private void writeDefaultConfig() {
        try {
            plugin.getDataFolder().mkdirs();
            String c =
                    "# ============ Sdf1 Radio "
                            + "Config ============\n"
                            + "#\n"
                            + "# resource-pack-url\n"
                            + "#   填外部HTTPS地址\n"
                            + "#   留空 = 使用内置HTTP"
                            + "服务\n"
                            + "#\n"
                            + "# http-port\n"
                            + "#   内置HTTP端口"
                            + " (仅url为空时生效)\n"
                            + "#\n"
                            + "# ============ Settings"
                            + " ==============\n"
                            + "\n"
                            + "resource-pack-url=\n"
                            + "http-port=25566\n";
            Files.write(configFile.toPath(),
                    c.getBytes("UTF-8"));
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] config create fail");
        }
    }

    // ========== convert ==========
    private void doConvert() {
        sourceFiles.clear();
        scanSources(radioDir);
        for (File src : sourceFiles) {
            String name = src.getName().replaceAll(
                    "\\.(ogg|mp3|wav|flac)$",
                    ".ogg");
            File dest = new File(oggDir, name);
            if (dest.exists()
                    && dest.lastModified()
                    >= src.lastModified()) continue;
            if (src.getName().toLowerCase()
                    .endsWith(".ogg")) {
                fileCopy(src, dest);
                plugin.getLogger().info(
                        "[Radio] copy: "
                                + src.getName());
            } else if (runFfmpeg(src, dest)) {
                plugin.getLogger().info(
                        "[Radio] convert: "
                                + src.getName());
            } else {
                plugin.getLogger().warning(
                        "[Radio] skip(need ffmpeg): "
                                + src.getName());
            }
        }
    }

    private void scanSources(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()
                    && !f.getName().equals("ogg")) {
                scanSources(f);
            } else if (f.isFile()) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".ogg")
                        || n.endsWith(".mp3")
                        || n.endsWith(".wav")
                        || n.endsWith(".flac")) {
                    sourceFiles.add(f);
                }
            }
        }
    }

    private void fileCopy(File src, File dest) {
        try {
            dest.getParentFile().mkdirs();
            Files.copy(src.toPath(),
                    dest.toPath(),
                    StandardCopyOption
                            .REPLACE_EXISTING);
        } catch (Exception ignored) {}
    }

    private boolean runFfmpeg(File src, File dest) {
        try {
            dest.getParentFile().mkdirs();
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-i",
                    src.getAbsolutePath(),
                    "-c:a", "libvorbis",
                    "-q:a", "5",
                    dest.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return dest.exists();
        } catch (Exception e) {
            return false;
        }
    }

    // ========== pack ==========
    private void buildPack() {
        deleteDir(packDir);
        packDir.mkdirs();
        List<File> allOggs = new ArrayList<>();
        scanOggs(oggDir, allOggs);
        if (allOggs.isEmpty()) {
            plugin.getLogger().warning(
                    "[Radio] no ogg found");
            return;
        }

        File soundsDir = new File(packDir,
                "assets/minecraft/sounds/custom");
        soundsDir.mkdirs();

        Map<String, File> unique =
                new LinkedHashMap<>();
        for (File ogg : allOggs) {
            String name = ogg.getName()
                    .replace(".ogg", "");
            unique.putIfAbsent(
                    name.toLowerCase(), ogg);
        }

        StringBuilder json =
                new StringBuilder("{\n");
        int idx = 0, total = unique.size();
        for (Map.Entry<String, File> e
                : unique.entrySet()) {
            String name = e.getKey();
            File ogg = e.getValue();
            File dest = new File(soundsDir,
                    name + ".ogg");
            try {
                Files.copy(ogg.toPath(),
                        dest.toPath(),
                        StandardCopyOption
                                .REPLACE_EXISTING);
                plugin.getLogger().info(
                        "[Radio] pack: " + name
                                + " (" + dest.length()
                                + " bytes)");
            } catch (Exception ex) {
                plugin.getLogger().warning(
                        "[Radio] copy fail: "
                                + name);
                continue;
            }
            json.append("  \"custom.")
                    .append(name).append("\": {\n");
            json.append(
                    "    \"replace\": false,\n");
            json.append(
                    "    \"sounds\": [{\n");
            json.append(
                            "      \"name\": \"custom/")
                    .append(name).append("\",\n");
            json.append(
                    "      \"stream\": true\n");
            json.append("    }]\n");
            json.append("  }");
            idx++;
            if (idx < total) json.append(",");
            json.append("\n");
        }
        json.append("}\n");

        try {
            Files.write(new File(packDir,
                            "assets/minecraft/sounds.json")
                            .toPath(),
                    json.toString()
                            .getBytes("UTF-8"));
            plugin.getLogger().info(
                    "[Radio] sounds.json:\n"
                            + json.toString());
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] sounds.json fail");
        }

        try {
            String meta =
                    "{\"pack\":{\"pack_format\":54,"
                            + "\"description\":\"Sdf1 Radio\""
                            + "}}\n";
            Files.write(new File(packDir,
                            "pack.mcmeta").toPath(),
                    meta.getBytes("UTF-8"));
        } catch (Exception ignored) {}

        File zipFile = new File(
                plugin.getDataFolder(),
                "radiopack.zip");
        try {
            zipPack(packDir, zipFile);
            packBytes = Files.readAllBytes(
                    zipFile.toPath());
            MessageDigest md =
                    MessageDigest.getInstance(
                            "SHA-1");
            md.update(packBytes);
            StringBuilder sb =
                    new StringBuilder();
            for (byte b : md.digest())
                sb.append(String.format(
                        "%02x", b));
            packHash = sb.toString();
            plugin.getLogger().info(
                    "[Radio] zip: "
                            + zipFile.length()
                            + " bytes, SHA1="
                            + packHash);
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] zip fail: "
                            + e.getMessage());
        }
    }

    private void scanOggs(File dir, List<File> list) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory())
                scanOggs(f, list);
            else if (f.getName()
                    .endsWith(".ogg"))
                list.add(f);
        }
    }

    private void loadOggFiles() {
        oggFiles.clear();
        scanOggs(oggDir, oggFiles);
    }

    private void deleteDir(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory())
                    deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private void zipPack(File root, File zipFile)
            throws Exception {
        try (ZipOutputStream zos =
                     new ZipOutputStream(
                             new FileOutputStream(
                                     zipFile))) {
            addZipEntries(root, root, zos);
        }
    }

    private void addZipEntries(File root, File cur,
                               ZipOutputStream zos)
            throws Exception {
        File[] files = cur.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                addZipEntries(root, f, zos);
            } else {
                String path = root.toURI()
                        .relativize(f.toURI())
                        .getPath();
                zos.putNextEntry(
                        new ZipEntry(path));
                zos.write(Files.readAllBytes(
                        f.toPath()));
                zos.closeEntry();
            }
        }
    }

    // ========== HTTP server ==========
    private void startHttpServer() {
        if (packBytes == null) return;
        if (!resourcePackUrl.isEmpty()) {
            plugin.getLogger().info(
                    "[Radio] skip HTTP "
                            + "(external URL)");
            return;
        }
        try {
            httpServer = HttpServer.create(
                    new InetSocketAddress(
                            "0.0.0.0",
                            httpPort), 0);
            httpServer.createContext(
                    "/radiopack.zip",
                    exchange -> {
                        if (packBytes != null) {
                            exchange
                                    .getResponseHeaders()
                                    .set("Content-Type",
                                            "application/zip");
                            exchange
                                    .sendResponseHeaders(
                                            200,
                                            packBytes.length);
                            exchange
                                    .getResponseBody()
                                    .write(packBytes);
                            exchange
                                    .getResponseBody()
                                    .close();
                        } else {
                            exchange
                                    .sendResponseHeaders(
                                            404, 0);
                            exchange.close();
                        }
                    });
            httpServer.setExecutor(null);
            httpServer.start();
            String ip = "127.0.0.1";
            try {
                ip = InetAddress.getLocalHost()
                        .getHostAddress();
            } catch (Exception ignored) {}
            plugin.getLogger().info(
                    "[Radio] HTTP on port "
                            + httpPort);
            plugin.getLogger().info(
                    "[Radio] url: http://" + ip
                            + ":" + httpPort
                            + "/radiopack.zip");
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "[Radio] HTTP fail: "
                            + e.getMessage());
        }
    }

    // ========== send resource pack ==========
    public void sendResourcePack(Player p) {
        if (packBytes == null) return;
        UUID pid = p.getUniqueId();

        if (sending.contains(pid)) {
            plugin.getLogger().info(
                    "[Radio] skip duplicate: "
                            + p.getName());
            return;
        }
        sending.add(pid);

        UUID uuid = UUID.randomUUID();

        String url = resourcePackUrl;
        if (url.isEmpty()) {
            String ip = "127.0.0.1";
            try {
                ip = InetAddress.getLocalHost()
                        .getHostAddress();
            } catch (Exception ignored) {}
            url = "http://" + ip + ":" + httpPort
                    + "/radiopack.zip";
        }

        byte[] hashBytes;
        try {
            MessageDigest md =
                    MessageDigest.getInstance(
                            "SHA-1");
            hashBytes = md.digest(packBytes);
        } catch (Exception e) {
            sending.remove(pid);
            return;
        }

        plugin.getLogger().info(
                "[Radio] url=" + url);
        plugin.getLogger().info(
                "[Radio] sha1=" + packHash
                        + " (" + hashBytes.length
                        + " bytes)");
        plugin.getLogger().info(
                "[Radio] size=" + packBytes.length);

        try {
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m
                    : p.getClass().getMethods()) {
                if (!m.getName().equals(
                        "setResourcePack"))
                    continue;
                Class<?>[] pt =
                        m.getParameterTypes();
                if (pt.length == 5
                        && pt[0] == UUID.class
                        && pt[1] == String.class
                        && pt[2] == byte[].class
                        && pt[3] == String.class
                        && pt[4] == boolean.class) {
                    target = m;
                    break;
                }
            }

            if (target == null) {
                plugin.getLogger().warning(
                        "[Radio] no method");
                sending.remove(pid);
                return;
            }

            target.invoke(p, uuid, url,
                    hashBytes,
                    "Sdf1 Radio", false);
            plugin.getLogger().info(
                    "[Radio] sent to "
                            + p.getName());

        } catch (Exception e) {
            Throwable c = e.getCause() != null
                    ? e.getCause() : e;
            plugin.getLogger().warning(
                    "[Radio] failed: "
                            + c.getMessage());
        }

        org.bukkit.Bukkit.getScheduler()
                .runTaskLater(plugin, () -> {
                    sending.remove(pid);
                }, 100L);
    }

    // ========== play custom ==========
    public boolean playCustom(String name) {
        return tryPlay(name, null);
    }

    public boolean playCustomTo(String name,
                                Player target) {
        return tryPlay(name, target);
    }

    private boolean tryPlay(String name,
                            Player target) {
        for (File f : oggFiles) {
            String fn = f.getName()
                    .replace(".ogg", "");
            if (fn.equalsIgnoreCase(name)) {
                String key =
                        "minecraft:custom." + name;
                if (target != null) {
                    target.playSound(
                            target.getLocation(),
                            key,
                            SoundCategory.RECORDS,
                            2.0f, 1.0f);
                } else {
                    for (Player p : Bukkit
                            .getOnlinePlayers()) {
                        p.playSound(
                                p.getLocation(),
                                key,
                                SoundCategory.RECORDS,
                                2.0f, 1.0f);
                    }
                    broadcast(
                            "§6§l[广播] §f正在播放: §e"
                                    + name);
                }
                return true;
            }
        }
        return false;
    }

    // ========== command ==========
    public void handleCommand(CommandSender sender,
                              String[] args) {
        // 无权限 → 静默，不做任何事
        if (!hasRadioPermission(sender)) {
            return;
        }

        if (args.length == 0) {
            stopMainRadio();
            stopAllLoginRadio();
            broadcast("§6§l[广播] §f已停止播放");
            return;
        }
        String arg = args[0].trim();

        // /radio reload
        if (arg.equalsIgnoreCase("reload")) {
            stopMainRadio();
            stopAllLoginRadio();
            loadRadioConfig();
            doConvert();
            buildPack();
            loadOggFiles();
            startHttpServer();
            sender.sendMessage("§a[Radio] reloaded");
            return;
        }

        // /radio list
        if (arg.equalsIgnoreCase("list")) {
            sender.sendMessage("§6音频列表 ("
                    + oggFiles.size() + "):");
            for (File f : oggFiles)
                sender.sendMessage("  §e"
                        + f.getName()
                        .replace(".ogg", ""));
            return;
        }

        // /radio random [玩家]
        if (arg.equalsIgnoreCase("random")) {
            if (oggFiles.isEmpty()) {
                sender.sendMessage(
                        "§c没有可用的音频文件");
                return;
            }
            File pick = oggFiles.get(
                    (int) (Math.random()
                            * oggFiles.size()));
            String name = pick.getName()
                    .replace(".ogg", "");
            if (args.length >= 2) {
                Player target = Bukkit
                        .getPlayer(args[1]);
                if (target != null
                        && target.isOnline()) {
                    sendResourcePack(target);
                    playCustomTo(name, target);
                    target.sendMessage(
                            "§6§l[Radio] §f正在播放: §e"
                                    + name);
                    sender.sendMessage(
                            "§a[Radio] 已向 "
                                    + target.getName()
                                    + " 播放: " + name);
                } else {
                    sender.sendMessage(
                            "§c玩家不在线: "
                                    + args[1]);
                }
            } else {
                playCustom(name);
            }
            return;
        }

        // === /radio 音频名 [玩家名] ===
        String audioArg = arg;
        String playerArg = null;
        if (args.length >= 2) {
            playerArg = args[1].trim();
        }

        Player target = null;
        if (playerArg != null) {
            target = Bukkit.getPlayer(playerArg);
            if (target == null
                    || !target.isOnline()) {
                sender.sendMessage(
                        "§c玩家不在线: " + playerArg);
                return;
            }
        }

        // 尝试 custom
        String base = audioArg.replaceAll(
                "\\.(ogg|mp3|wav|flac)$", "");
        if (target != null) {
            sendResourcePack(target);
        }
        if (tryPlay(base, target)) {
            if (target != null) {
                target.sendMessage(
                        "§6§l[Radio] §f正在播放: §e"
                                + base);
                sender.sendMessage(
                        "§a[Radio] 已向 "
                                + target.getName()
                                + " 播放: " + base);
            }
            return;
        }

        sender.sendMessage(
                "§c未找到: " + audioArg
                        + " §7/radio list");
    }

    // ========== timer ==========
    public void startMainRadio() {
        stopMainRadio();
        mainRadioTask = new BukkitRunnable() {
            public void run() {
                playNext();
            }
        }.runTaskTimer(plugin, 100L, 2400L);
    }

    private void playNext() {
        if (!oggFiles.isEmpty()) {
            File f = oggFiles.get(
                    radioIndex % oggFiles.size());
            playCustom(f.getName()
                    .replace(".ogg", ""));
            radioIndex++;
        }
    }

    public void stopMainRadio() {
        if (mainRadioTask != null) {
            mainRadioTask.cancel();
            mainRadioTask = null;
        }
    }

    public void startLoginRadio(Player p) {
        loginPlayers.put(p.getName(), true);
        if (loginRadioTask != null) return;
        loginRadioTask = new BukkitRunnable() {
            public void run() {
                if (loginPlayers.isEmpty()) {
                    cancel();
                    loginRadioTask = null;
                    return;
                }
                if (oggFiles.isEmpty()) return;
                int idx = (int)
                        (System.currentTimeMillis()
                                / 12000L
                                % oggFiles.size());
                for (String name
                        : loginPlayers.keySet()) {
                    Player lp = Bukkit
                            .getPlayer(name);
                    if (lp != null
                            && lp.isOnline()) {
                        String key =
                                "minecraft:custom."
                                        + oggFiles.get(idx)
                                        .getName()
                                        .replace(".ogg", "");
                        lp.playSound(
                                lp.getLocation(),
                                key,
                                SoundCategory.RECORDS,
                                1.5f, 1.0f);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 60L);
    }

    public void stopLoginRadio(Player p) {
        loginPlayers.remove(p.getName());
        if (loginPlayers.isEmpty()
                && loginRadioTask != null) {
            loginRadioTask.cancel();
            loginRadioTask = null;
        }
    }

    public void stopAllLoginRadio() {
        loginPlayers.clear();
        if (loginRadioTask != null) {
            loginRadioTask.cancel();
            loginRadioTask = null;
        }
    }

    public void stopAll() {
        stopMainRadio();
        stopAllLoginRadio();
        if (httpServer != null)
            httpServer.stop(0);
    }

    private void broadcast(String msg) {
        Bukkit.broadcastMessage(msg);
    }
}
