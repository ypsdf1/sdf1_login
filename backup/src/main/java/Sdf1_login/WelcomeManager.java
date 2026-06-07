package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class WelcomeManager {

    private final Main plugin;
    private final Random random = new Random();
    private final List<String> registerMsgs =
            new ArrayList<>();
    private final List<String> loginMsgs =
            new ArrayList<>();

    public WelcomeManager(Main plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        registerMsgs.clear();
        loginMsgs.clear();
        File f = new File(
                plugin.getDataFolder(),
                "欢迎仪式.txt");
        if (!f.exists()) {
            writeDefault(f);
        }
        try (BufferedReader r =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(f),
                                     StandardCharsets
                                             .UTF_8))) {
            String section = "";
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("\uFEFF"))
                    line = line.substring(1);
                String t = line.trim();
                if (t.isEmpty()
                        || t.startsWith("#"))
                    continue;
                if (t.equals("注册")
                        || t.equals("注册:")) {
                    section = "register";
                    continue;
                }
                if (t.equals("登录")
                        || t.equals("登录:")) {
                    section = "login";
                    continue;
                }
                // 支持 = 和 : 两种分隔
                if (section.isEmpty()) {
                    if (t.startsWith("注册")
                            && (t.contains("=")
                            || t.contains(":"))) {
                        int idx = t.indexOf('=');
                        int idx2 = t.indexOf(':');
                        if (idx < 0) idx = idx2;
                        else if (idx2 >= 0)
                            idx = Math.min(idx, idx2);
                        String v = t.substring(idx + 1)
                                .trim();
                        if (!v.isEmpty())
                            registerMsgs.add(
                                    colorize(v));
                        continue;
                    }
                    if (t.startsWith("登录")
                            && (t.contains("=")
                            || t.contains(":"))) {
                        int idx = t.indexOf('=');
                        int idx2 = t.indexOf(':');
                        if (idx < 0) idx = idx2;
                        else if (idx2 >= 0)
                            idx = Math.min(idx, idx2);
                        String v = t.substring(idx + 1)
                                .trim();
                        if (!v.isEmpty())
                            loginMsgs.add(
                                    colorize(v));
                        continue;
                    }
                    continue;
                }
                if (!t.isEmpty()) {
                    if (section.equals("register"))
                        registerMsgs.add(
                                colorize(t));
                    else
                        loginMsgs.add(
                                colorize(t));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe(
                    "[欢迎] 加载失败: "
                            + e.getMessage());
        }
        plugin.getLogger().info(
                "[欢迎] 加载完成: 注册="
                        + registerMsgs.size()
                        + " 登录=" + loginMsgs.size());
    }

    public void onRegister(Player p) {
        sendRandom(p, registerMsgs, "注册");
    }

    public void onLogin(Player p) {
        sendRandom(p, loginMsgs, "登录");
    }

    private void sendRandom(Player p,
                            List<String> msgs, String tag) {
        if (msgs.isEmpty()) return;
        String msg = msgs.get(
                random.nextInt(msgs.size()));
        msg = msg.replace("{player}",
                p.getName());
        final String finalMsg = msg;
        Bukkit.getScheduler()
                .runTaskLater(plugin,
                        () -> Bukkit
                                .broadcastMessage(
                                        finalMsg), 10L);
    }

    private String colorize(String msg) {
        return msg.replace("&", "\u00a7");
    }

    private void writeDefault(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets
                                .UTF_8))) {
            pw.println("# 欢迎仪式配置");
            pw.println("# &为颜色代码");
            pw.println("# {player} = 玩家名");
            pw.println();
            pw.println("注册:");
            pw.println("&a&l欢迎 &f{player} &a加入服务器！&7[注册]");
            pw.println("&6&l{player} &e已完成注册，欢迎入驻！&7[注册]");
            pw.println("&b&l新成员 &f{player} &b已就位！&7[注册]");
            pw.println();
            pw.println("登录:");
            pw.println("&a&l欢迎回来 &f{player}&a！&7[登录]");
            pw.println("&e&l{player} &e已登录，继续冒险吧！&7[登录]");
            pw.println("&b&l{player} &b回来了！&7[登录]");
        } catch (IOException ignored) {
        }
    }
}
