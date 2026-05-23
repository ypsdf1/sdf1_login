package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

public class CypayCommand
        implements CommandExecutor, TabCompleter {

    private final Main plugin;
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss");

    public CypayCommand(Main plugin) {
        this.plugin = plugin;
    }

    private boolean hasAdminPerm(CommandSender s) {
        if (s.isOp()) return true;
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        String tag =
                plugin.getConfig2().adminTag;
        return !tag.isEmpty()
                && p.getScoreboardTags().contains(tag);
    }

    private int parseAmount(String s) {
        return Integer.parseInt(
                s.trim().replaceAll("[+]", ""));
    }

    private List<String> filter(
            List<String> list, String prefix) {
        String low = prefix.toLowerCase();
        return list.stream()
                .filter(x -> x.toLowerCase()
                        .startsWith(low))
                .collect(Collectors.toList());
    }

    @Override
    public boolean onCommand(CommandSender sender,
                             Command cmd,
                             String label,
                             String[] args) {

        // ===== /cypay — 查自己 =====
        if (args.length == 0) {
            String name = sender instanceof Player
                    ? sender.getName() : "Console";
            int bonds =
                    plugin.getBonds().getBonds(name);
            String status = plugin.getBonds()
                    .getAccountStatus(name);
            sender.sendMessage(
                    "§e§l[债券] §f" + name
                            + " 余额: §a" + bonds);
            sender.sendMessage(
                    "§e§l[债券] §f状态: "
                            + ("frozen".equals(status)
                            ? "§c冻结中"
                            : "§a正常"));
            return true;
        }

        // ===== /cypay import =====
        if (args.length == 2
                && "import".equalsIgnoreCase(
                args[0])) {
            if (!hasAdminPerm(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            String file = args[1];
            if (!file.endsWith(".txt"))
                file += ".txt";
            int result = plugin.getCDK()
                    .importFromFile(file);
            if (result == -1)
                sender.sendMessage("§c文件不存在: "
                        + file);
            else
                sender.sendMessage("§a导入完成: "
                        + result + " 条");
            return true;
        }

        // ===== /cypay freeze =====
        if (args.length == 2
                && "freeze".equalsIgnoreCase(
                args[0])) {
            if (!hasAdminPerm(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            String target = args[1];
            if (plugin.getBonds().isFrozen(target)) {
                sender.sendMessage("§c" + target
                        + " 已处于冻结状态");
                return true;
            }
            plugin.getBonds().freezeAccount(target);
            sender.sendMessage("§a已冻结 " + target
                    + " 的债券账户");
            Player tp = plugin.getServer()
                    .getPlayerExact(target);
            if (tp != null)
                tp.sendMessage(
                        "§c§l[系统] 你的债券账户"
                                + "已被管理员冻结");
            return true;
        }

        // ===== /cypay unfreeze =====
        if (args.length == 2
                && "unfreeze".equalsIgnoreCase(
                args[0])) {
            if (!hasAdminPerm(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            String target = args[1];
            if (!plugin.getBonds()
                    .isFrozen(target)) {
                sender.sendMessage("§c" + target
                        + " 未被冻结");
                return true;
            }
            plugin.getBonds().unfreezeAccount(target);
            sender.sendMessage("§a已解冻 " + target
                    + " 的债券账户");
            Player tp = plugin.getServer()
                    .getPlayerExact(target);
            if (tp != null)
                tp.sendMessage(
                        "§a§l[系统] 你的债券账户"
                                + "已被解冻");
            return true;
        }

        // ===== /cypay water [days] =====
        if (args.length >= 2
                && "water".equalsIgnoreCase(
                args[0])) {
            if (!hasAdminPerm(sender)) {
                sender.sendMessage("§c权限不足");
                return true;
            }
            String target = args[1];
            int days = 7;
            if (args.length >= 3) {
                try {
                    days = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {
                }
            }
            days = Math.min(days,
                    BondManager.ADMIN_MAX_WATER_DAYS);

            if (days <= 0) days = 1;

            List<Map<String, Object>> txs =
                    plugin.getBonds()
                            .getTransactions(
                                    target, days);

            sender.sendMessage(
                    "§e§l===== " + target
                            + " 流水记录 (近"
                            + days + "天) =====");
            sender.sendMessage("§f当前余额: §a"
                    + plugin.getBonds()
                    .getBonds(target)
                    + "  §f状态: "
                    + (plugin.getBonds()
                    .isFrozen(target)
                    ? "§c冻结" : "§a正常"));

            if (txs.isEmpty()) {
                sender.sendMessage("§7  (暂无记录)");
            } else {
                for (Map<String, Object> tx : txs) {
                    sender.sendMessage(
                            formatTxLine(tx));
                }
            }
            sender.sendMessage(
                    "§e§l=========================="
                            + "==========");
            return true;
        }
        // ===== /cypay printer [player] =====
        if ("printer".equalsIgnoreCase(args[0])) {
            if (!hasAdminPerm(sender)
                    && !(sender instanceof Player)
                    == false) {
                // 控制台也可以用
                if (!(sender instanceof Player))
                    ; // 放行
                else if (!hasAdminPerm(sender)) {
                    sender.sendMessage("§c权限不足");
                    return true;
                }
            }
            if (args.length == 1) {
                // 打印全服
                sender.sendMessage(
                        "§e正在导出全服流水...");
                plugin.getBonds(); // 确保实例就绪
                if (plugin.getBondPrinter() != null) {
                    plugin.getBondPrinter()
                            .printAll(sender);
                } else {
                    sender.sendMessage("§cPrinter未初始化");
                }
                return true;
            }
            if (args.length == 2) {
                // 打印指定玩家
                String target = args[1];
                sender.sendMessage("§e正在导出 "
                        + target + " 的流水...");
                if (plugin.getBondPrinter() != null) {
                    plugin.getBondPrinter()
                            .printPlayer(target, sender);
                } else {
                    sender.sendMessage("§cPrinter未初始化");
                }
                return true;
            }
        }

        // ===== /cypay give/remove =====
        if (args.length == 3) {
            String action = args[0].toLowerCase();
            if ("give".equals(action)
                    || "remove".equals(action)) {
                if (!hasAdminPerm(sender)) {
                    sender.sendMessage("§c权限不足");
                    return true;
                }
                String target = args[1];
                try {
                    int amount =
                            parseAmount(args[2]);
                    if (amount <= 0) {
                        sender.sendMessage(
                                "§c金额必须大于0");
                        return true;
                    }
                    String txType =
                            "give".equals(action)
                                    ? BondManager
                                      .TX_ADMIN_GIVE
                                    : BondManager
                                      .TX_ADMIN_DEDUCT;
                    String opName =
                            sender instanceof Player
                                    ? sender.getName()
                                    : "Console";

                    if ("give".equals(action)) {
                        plugin.getBonds().addBonds(
                                target, amount,
                                txType, "",
                                opName, "管理员发放");
                        sender.sendMessage(
                                "§a已给 " + target
                                        + " " + amount
                                        + " 债券");
                        Player tp = plugin.getServer()
                                .getPlayerExact(target);
                        if (tp != null)
                            tp.sendMessage(
                                    "§a管理员给了你 "
                                            + amount
                                            + " 债券");
                    } else {
                        if (plugin.getBonds()
                                .deductBonds(target,
                                        amount,
                                        txType, "",
                                        opName,
                                        "管理员扣除")) {
                            sender.sendMessage(
                                    "§a已扣除 " + target
                                            + " " + amount
                                            + " 债券");
                            Player tp = plugin
                                    .getServer()
                                    .getPlayerExact(
                                            target);
                            if (tp != null)
                                tp.sendMessage(
                                        "§c管理员扣除了"
                                                + "你的 "
                                                + amount
                                                + " 债券");
                        } else {
                            sender.sendMessage(
                                    "§c" + target
                                            + " 债券不足"
                                            + "或账户冻结");
                        }
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage(
                            "§c金额格式错误");
                }
                return true;
            }
        }

        // ===== /cypay <player> <amount> — 仅玩家转账 =====
        if (args.length == 2) {
            try {
                int amount = parseAmount(args[1]);
                if (amount <= 0) {
                    sender.sendMessage(
                            "§c金额必须大于0");
                    return true;
                }
                // ★ 控制台禁止转账
                if (!(sender instanceof Player)) {
                    sender.sendMessage(
                            "§c控制台不能转账，请使用 "
                                    + "/cypay give <玩家> <金额>");
                    return true;
                }
                Player p = (Player) sender;
                String target = args[0];
                BondManager.TransferResult r =
                        plugin.getBonds().transfer(
                                p.getName(),
                                target, amount);
                if (r.success) {
                    p.sendMessage("§e§l[债券转账] §f向 §a"
                            + target + " §f转出 §c"
                            + amount + " §f枚债券");
                    Player tp = plugin.getServer()
                            .getPlayerExact(target);
                    if (tp != null)
                        tp.sendMessage("§e§l[债券转账] §f收到 §a"
                                + p.getName()
                                + " §f转入 §a"
                                + amount + " §f枚债券");
                } else {
                    p.sendMessage("§c" + r.message
                            + "，当前: "
                            + plugin.getBonds()
                            .getBonds(p.getName()));
                }
                return true;
            } catch (NumberFormatException ignored) {
            }
        }

            // ===== /cypay <player> — 查指定玩家 =====
        if (args.length == 1) {
            String target = args[0];
            if (hasAdminPerm(sender)) {
                int bonds = plugin.getBonds()
                        .getBonds(target);
                String status = plugin.getBonds()
                        .getAccountStatus(target);
                sender.sendMessage(
                        "§e§l[债券] §f" + target
                                + " 余额: §a"
                                + bonds);
                sender.sendMessage(
                        "§e§l[债券] §f状态: "
                                + ("frozen".equals(
                                status)
                                ? "§c冻结中"
                                : "§a正常"));
            } else {
                int bonds = plugin.getBonds()
                        .getBonds(
                                sender.getName());
                sender.sendMessage(
                        "§e§l[债券] §f你的余额: §a"
                                + bonds);
            }
            return true;
        }

        showHelp(sender);
        return true;
    }

    // ===== 流水行格式化 =====

    private String formatTxLine(
            Map<String, Object> tx) {
        String type = (String) tx.get("type");
        int amount = ((Number) tx.get("amount"))
                .intValue();
        String tgt = (String) tx.get("target_player");
        String reason = (String) tx.get("reason");
        int bal = ((Number) tx.get("balance_after"))
                .intValue();
        long time = ((Number) tx.get("time"))
                .longValue();

        boolean pos = amount > 0;
        String sign = pos ? "+" : "";
        String color = pos ? "§a" : "§c";

        StringBuilder sb = new StringBuilder();
        sb.append("§7[")
                .append(SDF.format(new Date(time)))
                .append("] ")
                .append(formatTxType(type))
                .append(" ")
                .append(color).append(sign)
                .append(amount).append("§7");
        if (tgt != null && !tgt.isEmpty())
            sb.append(" §e↔ ").append(tgt);
        if (reason != null && !reason.isEmpty())
            sb.append(" §7(").append(reason)
                    .append(")");
        sb.append(" §7余额:§f").append(bal);
        return sb.toString();
    }

    private String formatTxType(String type) {
        if (type == null) return "§7未知";
        switch (type) {
            case BondManager.TX_ADMIN_GIVE:
                return "§a管理员发放";
            case BondManager.TX_ADMIN_DEDUCT:
                return "§c管理员扣除";
            case BondManager.TX_TRANSFER_OUT:
                return "§c转出";
            case BondManager.TX_TRANSFER_IN:
                return "§a转入";
            case BondManager.TX_REDEEM:
                return "§aCDK兑换";
            case BondManager.TX_SHOP_BUY:
                return "§e商城购买";
            case BondManager.TX_DAILY_SIGN:
                return "§a签到奖励";
            case BondManager.TX_FREEZE:
                return "§c冻结";
            case BondManager.TX_UNFREEZE:
                return "§a解冻";
            default:
                return "§7" + type;
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§e§l债券命令帮助:");
        sender.sendMessage(
                "§e/cypay §7- 查询自己余额");
        sender.sendMessage(
                "§e/cypay <玩家> §7- 查余额(管理员)");
        sender.sendMessage(
                "§e/cypay <玩家> <金额> §7- 转账");
        if (hasAdminPerm(sender)) {
            sender.sendMessage(
                    "§e/cypay give <玩家> <金额> §7- 给债券");
            sender.sendMessage(
                    "§e/cypay remove <玩家> <金额> §7- 扣债券");
            sender.sendMessage(
                    "§e/cypay import <文件> §7- 导入CDK");
            sender.sendMessage(
                    "§e/cypay freeze <玩家> §7- 冻结");
            sender.sendMessage(
                    "§e/cypay unfreeze <玩家> §7- 解冻");
            sender.sendMessage(
                    "§e/cypay water <玩家> [天数] §7- 流水");
            sender.sendMessage(
                    "§e/cypay printer §7- 导出全服流水xlsx");
            sender.sendMessage(
                    "§e/cypay printer <玩家> §7- 导出指定玩家xlsx");

        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command,
            String label, String[] args) {
        List<String> list = new ArrayList<>();

        if (args.length == 1) {
            String last = args[0].toLowerCase();
            if (hasAdminPerm(sender)) {
                list.add("give");
                list.add("remove");
                list.add("import");
                list.add("freeze");
                list.add("unfreeze");
                list.add("water");
                list.add("printer");

                File cdkDir = new File(
                        plugin.getDataFolder(),
                        "cdk");
                if (cdkDir.exists()) {
                    File[] files = cdkDir.listFiles(
                            (d, n) -> n
                                    .endsWith(".txt"));
                    if (files != null)
                        for (File f : files)
                            list.add(f.getName());
                }
            }
            for (Player o :
                    Bukkit.getOnlinePlayers())
                list.add(o.getName());
            return filter(list, last);
        }

        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if ("give".equals(first)
                    || "remove".equals(first)
                    || "freeze".equals(first)
                    || "unfreeze".equals(first)
                    || "water".equals(first)) {
                for (Player o :
                        Bukkit.getOnlinePlayers())
                    list.add(o.getName());
                return filter(list,
                        args[1].toLowerCase());
            }
            list.add("10");
            list.add("50");
            list.add("100");
            return filter(list,
                    args[1].toLowerCase());
        }

        if (args.length == 3) {
            String first = args[0].toLowerCase();
            if ("give".equals(first)
                    || "remove".equals(first)) {
                list.add("10");
                list.add("50");
                list.add("100");
                list.add("999");
                return filter(list,
                        args[2].toLowerCase());
            }
            if ("water".equals(first)) {
                list.add("1");
                list.add("3");
                list.add("7");
                list.add("14");
                return filter(list,
                        args[2].toLowerCase());
            }
        }

        return list;
    }
}
