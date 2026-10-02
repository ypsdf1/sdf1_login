<?php
require_once __DIR__ . '/../security.php';   // 第四层：全局限流
/**
 * 支付回调轮询器 — 绕过 Cloudflare WAF，直接读平台 MySQL 获取已支付订单
 * 完全自包含，不依赖 core.php 避免 500 错误
 *
 * ★ 已被 poller_online.php 取代（2026-07-15 起全站无引用），仅保留兼容，勿新增调用。
 * ★ 2026-10-02 与 poller_online.php 同步收窄：只查本方 RE 订单，平台其他订单不碰。
 */
error_reporting(E_ALL);
ini_set('display_errors', 0);

while (ob_get_level() > 0) { ob_end_clean(); }

// ===== 配置 =====
$PLATFORM_DB_HOST = 'localhost';
$PLATFORM_DB_NAME = 'caihong';
$PLATFORM_DB_USER = 'kH3C3LLinNwYdTF5';
$PLATFORM_DB_PASS = 'sRhsdxrpHBhmSsp8';
$PLATFORM_DB_PREFIX = 'pay_';

// SQLite 数据库路径（与 core.php 一致）
$SQLITE_DB_PATH = __DIR__ . '/../db/web.db';
$MD5_KEY = 'Fo01ul2MrO8zRu0ZoUpP2U1kp0uu7rXk';

function debugLog($msg, $ctx = []) {
    $logFile = __DIR__ . '/../db/debug.log';
    $ts = date('Y-m-d H:i:s');
    $entry = "[$ts] $msg";
    if ($ctx) $entry .= ' | Context: ' . json_encode($ctx, JSON_UNESCAPED_UNICODE);
    $entry .= "\n";
    @file_put_contents($logFile, $entry, FILE_APPEND | LOCK_EX);
}

function getSQLite() {
    static $db = null;
    if ($db === null) {
        $db = new SQLite3($GLOBALS['SQLITE_DB_PATH']);
        $db->enableExceptions(true);
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=5000');
        // 确保表存在
        $db->exec("CREATE TABLE IF NOT EXISTS pay_orders (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            out_trade_no TEXT UNIQUE,
            trade_no TEXT,
            player_name TEXT NOT NULL,
            tier_id INTEGER DEFAULT 0,
            money TEXT,
            bond_amount INTEGER DEFAULT 0,
            status TEXT DEFAULT 'created',
            name TEXT,
            platform_sign TEXT,
            submit_params TEXT,
            created_at INTEGER,
            paid_at INTEGER
        )");
        $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT,
            type TEXT,
            amount INTEGER,
            operator TEXT,
            reason TEXT,
            detail TEXT,
            status TEXT DEFAULT 'pending',
            created_at INTEGER
        )");
    }
    return $db;
}

function getPlatformDB() {
    static $pdo = null;
    if ($pdo === null) {
        $dsn = "mysql:host={$GLOBALS['PLATFORM_DB_HOST']};dbname={$GLOBALS['PLATFORM_DB_NAME']};charset=utf8mb4";
        $pdo = new PDO($dsn, $GLOBALS['PLATFORM_DB_USER'], $GLOBALS['PLATFORM_DB_PASS'], [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_TIMEOUT => 5,
        ]);
    }
    return $pdo;
}

function pollPaidOrders() {
    $sqlite = getSQLite();

    try {
        $pdo = getPlatformDB();
    } catch (\Throwable $e) {
        debugLog('[pay_poller] 平台数据库连接失败: ' . $e->getMessage());
        echo json_encode(['error' => 'platform_db_connect_failed', 'detail' => $e->getMessage()]);
        return;
    }

    // ★ 2026-10-02 只查后端自己的订单：平台 MySQL 是多商户共用库，不加过滤整表拉
    //   会把其他商户/项目的单（纯数字单号）也当成本地充值单，凭空造出玩家=unknown
    //   的垃圾订单。本方单号固定 RE 前缀（pay.php createOrder 生成），平台侧据此过滤，
    //   PHP 侧再兜底校验；平台其他订单一概不读不写（含不改它的 notify）。
    //   仍不过滤 notify：平台可能标记 notify=1 但本地 DB 未更新（回调时 DB 写入异常）。
    $prefix = $GLOBALS['PLATFORM_DB_PREFIX'];
    $stmt = $pdo->query("SELECT out_trade_no, trade_no, uid, money, status, notify, param, version FROM {$prefix}order WHERE out_trade_no LIKE 'RE%' AND status IN (1, 2) ORDER BY addtime ASC LIMIT 50");
    $allOrders = $stmt->fetchAll(PDO::FETCH_ASSOC);

    if (empty($allOrders)) {
        echo json_encode(['result' => 'no_paid_orders_on_platform']);
        return;
    }

    // 只保留本方订单（RE 前缀兜底校验），再过滤掉本地已处理的
    $orders = [];
    $foreign = 0;
    foreach ($allOrders as $o) {
        $outNo = (string)$o['out_trade_no'];
        if (strncmp($outNo, 'RE', 2) !== 0) {
            $foreign++;   // 非本方订单：不建单、不写流水、不改它的 notify
            continue;
        }
        $check = $sqlite->prepare("SELECT status FROM pay_orders WHERE out_trade_no = :no");
        $check->bindValue(':no', $outNo, SQLITE3_TEXT);
        $row = $check->execute()->fetchArray(SQLITE3_ASSOC);
        if (!$row || $row['status'] !== 'paid') {
            $orders[] = $o;
        }
    }

    if (empty($orders)) {
        echo json_encode(['result' => 'no_pending_orders', 'platform_paid_count' => count($allOrders), 'foreign_skipped' => $foreign]);
        return;
    }

    // 获取 uid→player_name 映射
    $uidMap = [];
    foreach ($orders as $o) {
        $uidMap[$o['uid']] = $o['param'] ?? 'unknown';
    }

    $processed = 0;
    $skipped = 0;

    foreach ($orders as $order) {
        $outTradeNo = $order['out_trade_no'];
        $tradeNo = $order['trade_no'];
        $playerName = $order['param'] ?? 'unknown';
        $money = (string)$order['money'];
        $bonds = ((float)$money >= 1.0) ? (int)((float)$money * 10) : max(1, (int)((float)$money * 100));

        // 检查本地是否已处理（双重保险）
        $check = $sqlite->prepare("SELECT status FROM pay_orders WHERE out_trade_no = :no");
        $check->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
        $row = $check->execute()->fetchArray(SQLITE3_ASSOC);

        if ($row && $row['status'] === 'paid') {
            $skipped++;
            markNotified($pdo, $tradeNo);
            continue;
        }

        debugLog('[pay_poller] 处理订单', ['out_trade_no' => $outTradeNo, 'player' => $playerName, 'money' => $money, 'bonds' => $bonds, 'platform_notify' => $order['notify']]);

        // 插入或更新本地订单
        if (!$row) {
            $ins = $sqlite->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, money, bond_amount, status, created_at, paid_at) VALUES (:no, :tn, :p, :m, :b, 'paid', :t, :t)");
            $ins->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $ins->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
            $ins->bindValue(':p', $playerName, SQLITE3_TEXT);
            $ins->bindValue(':m', $money, SQLITE3_TEXT);
            $ins->bindValue(':b', $bonds, SQLITE3_INTEGER);
            $ins->bindValue(':t', time(), SQLITE3_INTEGER);
            $ins->execute();
        } else {
            $upd = $sqlite->prepare("UPDATE pay_orders SET status='paid', trade_no=:tn, paid_at=:t WHERE out_trade_no=:no AND status != 'paid'");
            $upd->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
            $upd->bindValue(':t', time(), SQLITE3_INTEGER);
            $upd->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $upd->execute();
        }

        // 写 web_transactions（幂等）
        $txCheck = $sqlite->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
        $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
        $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);

        if (!$txRow) {
            $txIns = $sqlite->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(轮询)', '在线充值(支付宝)', :d, 'pending', :t)");
            $txIns->bindValue(':p', $playerName, SQLITE3_TEXT);
            $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
            $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txIns->bindValue(':t', time(), SQLITE3_INTEGER);
            $txIns->execute();

            debugLog('[pay_poller] 充值交易已写入', ['player' => $playerName, 'bonds' => $bonds, 'out_trade_no' => $outTradeNo, 'money' => $money]);
        }

        markNotified($pdo, $tradeNo);
        $processed++;
    }

    echo json_encode(['result' => 'ok', 'processed' => $processed, 'skipped' => $skipped, 'foreign_skipped' => $foreign, 'total' => count($orders)]);
}

function markNotified($pdo, $tradeNo) {
    try {
        $prefix = $GLOBALS['PLATFORM_DB_PREFIX'];
        $stmt = $pdo->prepare("UPDATE {$prefix}order SET notify = 1 WHERE trade_no = ? AND notify = 0");
        $stmt->execute([$tradeNo]);
    } catch (\Throwable $e) {
        debugLog('[pay_poller] 标记notify失败: ' . $e->getMessage(), ['trade_no' => $tradeNo]);
    }
}

// ===== 路由 =====
header('Content-Type: application/json; charset=utf-8');
pollPaidOrders();
?>