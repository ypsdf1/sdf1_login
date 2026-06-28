package Sdf1_login;

import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class CommissionManager {

    private final Main main;
    private final Logger logger;
    private final List<double[]> rules =
            new ArrayList<>();

    public CommissionManager(Main main) {
        this.main = main;
        this.logger = main.getLogger();
        loadRules();
    }

    public void loadRules() {
        rules.clear();
        File f = new File(
                main.getDataFolder(),
                "提成规则.txt");
        if (!f.exists()) {
            writeDefault(f);
        }
        try (BufferedReader br =
                     new BufferedReader(
                             new InputStreamReader(
                                     new FileInputStream(f),
                                     StandardCharsets
                                             .UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()
                        || line.startsWith("#"))
                    continue;
                // 格式: 邀请人数:提成百分比
                // 如 "5:15" = 邀请5人提成15%
                String[] parts =
                        line.split(":");
                if (parts.length >= 2) {
                    try {
                        int count = Integer.parseInt(
                                parts[0].trim());
                        double rate = Double
                                .parseDouble(
                                        parts[1]
                                                .trim()) / 100.0;
                        rules.add(new double[]{
                                count, rate});
                    } catch (Exception ignored) {
                    }
                }
            }
            // 按人数排序（从大到小）
            rules.sort((a, b) ->
                    Double.compare(b[0], a[0]));
            logger.info(
                    "[Commission] 加载 "
                            + rules.size() + " 条提成规则");
        } catch (Exception e) {
            logger.warning(
                    "[Commission] 加载失败: "
                            + e.getMessage());
        }
    }

    private void writeDefault(File f) {
        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(
                        new FileOutputStream(f),
                        StandardCharsets.UTF_8))) {
            pw.println("# 邀请提成规则");
            pw.println("# 格式: 邀请人数:提成百分比");
            pw.println("# 邀请10人提成20%");
            pw.println("10:20");
            pw.println("# 邀请5人提成15%");
            pw.println("5:15");
            pw.println("# 邀请1人提成10%");
            pw.println("1:10");
        } catch (IOException ignored) {
        }
    }

    /**
     * 获取邀请人的提成比例
     * 根据已邀请人数匹配最高规则
     */
    public double getCommissionRate(
            String inviter) {
        int inviteCount = getInviteCount(
                inviter);
        for (double[] rule : rules) {
            if (inviteCount >= (int) rule[0]) {
                return rule[1];
            }
        }
        return 0;
    }

    /**
     * 获取邀请人数
     */
    public int getInviteCount(
            String inviter) {
        try {
            Object val =
                    main.getDb().getField(
                            inviter,
                            "monthly_invite_count");
            if (val instanceof Number) {
                return ((Number) val).intValue();
            }
            Object total =
                    main.getDb().getField(
                            inviter,
                            "total_invite_count");
            if (total instanceof Number) {
                return ((Number) total).intValue();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 发放提成
     * 被邀请人获得奖励时，邀请人获得提成
     */
    public void payCommission(
            String invitee,
            double originalAmount) {
        // 查找邀请人
        String inviter = getInviter(invitee);
        if (inviter == null
                || inviter.isEmpty()) return;

        double rate = getCommissionRate(
                inviter);
        if (rate <= 0) return;

        double commission =
                originalAmount * rate;
        if (commission <= 0) return;

        // Economy已移除，佣金提成功能不可用
        // 邀请人可通过债券系统获取奖励

        // 通知邀请人
        Player inviterP =
                main.getServer()
                        .getPlayer(inviter);
        if (inviterP != null
                && inviterP.isOnline()) {
            inviterP.sendMessage(
                    "§a§l[邀请提成] §f"
                            + invitee + " 完成任务，"
                            + "你获得 §e"
                            + String.format("%.2f",
                            commission)
                            + " §f游戏币提成 ("
                            + (int)(rate * 100) + "%)");
        }

        logger.info(
                "[Commission] 提成: "
                        + inviter + " ← "
                        + invitee + " | "
                        + String.format("%.2f",
                        originalAmount)
                        + " × "
                        + (int)(rate * 100) + "% = "
                        + String.format("%.2f",
                        commission));
    }

    /**
     * 获取邀请人
     */
    private String getInviter(String player) {
        try {
            Object val =
                    main.getDb().getField(
                            player,
                            "invited_by");
            if (val != null
                    && !val.toString()
                    .isEmpty()) {
                return val.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 判断金额是否包含经济内容
     */
    public boolean hasEconomyContent(
            String text) {
        String t = text.toLowerCase();
        return t.contains("游戏币")
                || t.contains("金币")
                || t.contains("积分")
                || t.contains("块钱")
                || t.contains("元")
                || t.contains("$")
                || t.contains("经济")
                || t.contains("余额");
    }
}
