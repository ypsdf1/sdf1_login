package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class BondPrinter {

    private final Main plugin;
    private final File outputDir;

    private static final SimpleDateFormat FILE_SDF =
            new SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss");
    private static final SimpleDateFormat CELL_SDF =
            new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss");

    private static final LinkedHashMap<String, String>
            TYPE_NAMES = new LinkedHashMap<>();

    static {
        TYPE_NAMES.put("admin_give", "管理员发放");
        TYPE_NAMES.put("admin_deduct", "管理员扣除");
        TYPE_NAMES.put("transfer_out", "转出");
        TYPE_NAMES.put("transfer_in", "转入");
        TYPE_NAMES.put("redeem", "CDK兑换");
        TYPE_NAMES.put("shop_buy", "商城购买");
        TYPE_NAMES.put("shop_sell", "商城出售");   // ← 补上
        TYPE_NAMES.put("daily_sign", "签到奖励");
        TYPE_NAMES.put("freeze", "冻结");
        TYPE_NAMES.put("unfreeze", "解冻");

    }

    public BondPrinter(Main plugin) {
        this.plugin = plugin;
        this.outputDir = new File(
                plugin.getDataFolder(), "printer");
        if (!this.outputDir.exists()) {
            this.outputDir.mkdirs();
        }
    }

    // ============ 打印单个玩家 ============

    public void printPlayer(String player,
                            CommandSender sender) {
        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin,
                        () -> doPrintPlayer(
                                player, sender));
    }

    private void doPrintPlayer(String player,
                               CommandSender sender) {
        try {
            List<Map<String, Object>> txs =
                    plugin.getBonds()
                            .getAllTransactions(player);

            String fileName = player + "_"
                    + FILE_SDF.format(new Date())
                    + ".xls";
            File file = new File(outputDir, fileName);

            deleteOld(player);

            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file),
                            StandardCharsets.UTF_8));
            try {
                writeXmlHeader(pw,
                        escXml(player));
                writeXmlRow(pw,
                        new String[]{
                                "时间", "类型",
                                "金额", "对象",
                                "操作者", "理由",
                                "操作前余额",
                                "操作后余额"});

                for (Map<String, Object> tx : txs) {
                    writeXmlRow(pw, new String[]{
                            txTime(tx),
                            txTypeName(tx),
                            String.valueOf(
                                    txInt(tx,
                                            "amount")),
                            txStr(tx,
                                    "target_player"),
                            txStr(tx, "operator"),
                            txStr(tx, "reason"),
                            String.valueOf(
                                    txInt(tx,
                                            "balance_before")),
                            String.valueOf(
                                    txInt(tx,
                                            "balance_after"))
                    });
                }

                writeXmlFooter(pw);
                pw.flush();
            } finally {
                pw.close();
            }

            if (sender != null) {
                sender.sendMessage("§a[打印] §f"
                        + player + " §a流水已导出");
                sender.sendMessage("§7文件: §f"
                        + fileName);
                sender.sendMessage("§7共 §f"
                        + txs.size()
                        + " §7条，§724小时后删除");
            }
        } catch (Exception e) {
            if (sender != null) {
                sender.sendMessage("§c导出失败: "
                        + e.getMessage());
            }
            plugin.getLogger().severe(
                    "[Printer] " + e.getMessage());
        }
    }

    // ============ 打印全服 ============

    public void printAll(CommandSender sender) {
        Bukkit.getScheduler()
                .runTaskAsynchronously(plugin,
                        () -> doPrintAll(sender));
    }

    private void doPrintAll(CommandSender sender) {
        try {
            List<String> players =
                    plugin.getBonds()
                            .getAllPlayerNames();
            if (players.isEmpty()) {
                if (sender != null) {
                    sender.sendMessage("§c没有流水数据");
                }
                return;
            }

            String fileName = "全服流水_"
                    + FILE_SDF.format(new Date())
                    + ".xls";
            File file = new File(outputDir, fileName);

            // ===== 收集所有流水 =====
            // key = 时间戳+玩家+金额，用于去重
            // value = 一行数据
            List<String[]> allRows = new ArrayList<>();
            Set<String> seenKeys = new HashSet<>();

            for (String player : players) {
                List<Map<String, Object>> txs =
                        plugin.getBonds()
                                .getAllTransactions(player);
                for (Map<String, Object> tx : txs) {
                    int txId = ((Number) tx.get("id"))
                            .intValue();
                    String dedupKey =
                            String.valueOf(txId);
                    if (!seenKeys.add(dedupKey)) {
                        continue;
                    }

                    String timeStr = txTime(tx);
                    long timeMs = 0;
                    Object t = tx.get("time");
                    if (t != null) {
                        timeMs =
                                ((Number) t).longValue();
                    }

                    allRows.add(new String[]{
                            player,
                            timeStr,
                            txTypeName(tx),
                            String.valueOf(
                                    txInt(tx, "amount")),
                            txStr(tx, "target_player"),
                            txStr(tx, "operator"),
                            txStr(tx, "reason"),
                            String.valueOf(
                                    txInt(tx,
                                            "balance_before")),
                            String.valueOf(
                                    txInt(tx,
                                            "balance_after")),
                            // 额外存时间戳用于排序
                            String.valueOf(timeMs)
                    });
                }
            }

            // ===== 按时间戳排序 =====
            allRows.sort((a, b) -> {
                long ta = Long.parseLong(
                        a[a.length - 1]);
                long tb = Long.parseLong(
                        b[b.length - 1]);
                return Long.compare(ta, tb);
            });

            // ===== 写入文件 =====
            PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(
                            new FileOutputStream(file),
                            StandardCharsets.UTF_8));
            try {
                writeXmlHeader(pw, "全服流水");
                writeXmlRow(pw, new String[]{
                        "玩家", "时间", "类型",
                        "金额", "对象", "操作者",
                        "理由", "操作前余额",
                        "操作后余额"
                });

                for (String[] row : allRows) {
                    // 去掉最后的时间戳列
                    String[] cells =
                            new String[row.length - 1];
                    System.arraycopy(row, 0,
                            cells, 0, cells.length);
                    writeXmlRow(pw, cells);
                }

                writeXmlFooter(pw);
                pw.flush();

                if (sender != null) {
                    sender.sendMessage(
                            "§a[打印] §f全服流水 §a已导出");
                    sender.sendMessage("§7文件: §f"
                            + fileName);
                    sender.sendMessage("§7共 §f"
                            + players.size()
                            + " 名玩家，§f"
                            + allRows.size() + " §7条");
                    sender.sendMessage(
                            "§7按时间从早到晚排序");
                    sender.sendMessage(
                            "§724小时后自动删除");
                }
            } finally {
                pw.close();
            }
        } catch (Exception e) {
            if (sender != null) {
                sender.sendMessage("§c导出失败: "
                        + e.getMessage());
            }
            plugin.getLogger().severe(
                    "[Printer] " + e.getMessage());
        }
    }

    // ============ 清理 ============

    public void cleanupOldFiles() {
        long now = System.currentTimeMillis();
        long maxAge = 24L * 60 * 60 * 1000;
        File[] files = outputDir.listFiles(
                (d, n) -> n.endsWith(".xls"));
        if (files == null) {
            return;
        }
        int count = 0;
        for (File f : files) {
            if (now - f.lastModified() > maxAge) {
                if (f.delete()) {
                    count++;
                }
            }
        }
        if (count > 0) {
            plugin.getLogger().info(
                    "[Printer] 清理 "
                            + count + " 个过期文件");
        }
    }

    private void deleteOld(String player) {
        File[] old = outputDir.listFiles(
                (d, n) -> n.startsWith(player + "_")
                        && n.endsWith(".xls"));
        if (old != null) {
            for (File f : old) {
                f.delete();
            }
        }
    }

    // ============ XML工具 ============

    private void writeXmlHeader(PrintWriter pw,
                                String sheetName) {
        pw.println("<?xml version=\"1.0\"?>");
        pw.println("<?mso-application "
                + "progid=\"Excel.Sheet\"?>");
        pw.println("<Workbook xmlns="
                + "\"urn:schemas-microsoft-com"
                + ":office:spreadsheet\"");
        pw.println("  xmlns:ss="
                + "\"urn:schemas-microsoft-com"
                + ":office:spreadsheet\">");
        pw.println("  <Worksheet ss:Name=\""
                + sheetName + "\">");
        pw.println("    <Table>");
    }

    private void writeXmlRow(PrintWriter pw,
                             String[] cells) {
        pw.println("      <Row>");
        for (String cell : cells) {
            pw.println("        <Cell>"
                    + "<Data ss:Type=\"String\">"
                    + escXml(cell != null ? cell : "")
                    + "</Data></Cell>");
        }
        pw.println("      </Row>");
    }

    private void writeXmlFooter(PrintWriter pw) {
        pw.println("    </Table>");
        pw.println("  </Worksheet>");
        pw.println("</Workbook>");
    }

    private String escXml(String val) {
        if (val == null) {
            return "";
        }
        return val.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String txTime(Map<String, Object> tx) {
        Object t = tx.get("time");
        if (t == null) return "";
        long ms = ((Number) t).longValue();
        return CELL_SDF.format(new Date(ms));
    }

    private String txTypeName(
            Map<String, Object> tx) {
        String type = (String) tx.get("type");
        return TYPE_NAMES.getOrDefault(type, type);
    }

    private int txInt(Map<String, Object> tx,
                      String key) {
        Object v = tx.get(key);
        if (v == null) return 0;
        return ((Number) v).intValue();
    }

    private String txStr(Map<String, Object> tx,
                         String key) {
        Object v = tx.get(key);
        return v != null ? v.toString() : "";
    }
}
