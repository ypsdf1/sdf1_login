<?php
/**
 * 支付补单器（poller_online.php）— 绕过 Cloudflare WAF 拦截的 HTTP 回调通知
 *
 * 工作原理：
 *   直接读平台 MySQL 中已支付订单(status IN (1,2)) → 与本地 SQLite(pay_orders) 比对 →
 *   将未处理的订单写入 web_transactions(recharge, pending) → 供 Java 高频定时器拉取 → addBonds 到账
 *
 * 触发方式（双保险）：
 *   1. 玩家在 Web 端查询订单时，pay.php 的 query_order 异步调用本脚本（前端触发）
 *   2. Java 定时拉取 web_transactions 前，先调用本脚本（后台定时触发，最可靠）
 *
 * 完全自包含，不依赖 core.php，避免 500 错误。幂等：重复跑不会重复写流水。
 */

error_reporting(E_ALL);
ini_set('display_errors', 0);

// 只在直接执行时清除输出缓冲区，被包含时保留
if (!defined('POLLER_NO_AUTO_RUN')) {
    while (ob_get_level() > 0) { ob_end_clean(); }
}

// ===== 配置 =====
$PLATFORM_DB_HOST = 'localhost';
$PLATFORM_DB_NAME = 'caihong';
$PLATFORM_DB_USER = 'kH3C3LLinNwYdTF5';
$PLATFORM_DB_PASS = 'sRhsdxrpHBhmSsp8';
$PLATFORM_DB_PREFIX = 'pay_';

// SQLite 数据库路径（与 core.php 一致：/caoyuan.ypshidifu.cn/plugin/db/web.db）
$SQLITE_DB_PATH = __DIR__ . '/../db/web.db';

if (!function_exists('debugLog')) {
function debugLog($msg, $ctx = []) {
    $logFile = __DIR__ . '/../db/debug.log';
    $ts = date('Y-m-d H:i:s');
    $entry = "[$ts] $msg";
    if ($ctx) $entry .= ' | Context: ' . json_encode($ctx, JSON_UNESCAPED_UNICODE);
    $entry .= "\n";
    @file_put_contents($logFile, $entry, FILE_APPEND | LOCK_EX);
}
} // end if (!function_exists('debugLog'))

function getSQLite() {
    static $db = null;
    if ($db === null) {
        $db = new SQLite3($GLOBALS['SQLITE_DB_PATH']);
        $db->enableExceptions(true);
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=5000');
        // 确保表存在（幂等兜底）
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
        // 唯一约束：同 detail+type 不允许重复（防 race condition/重复补单）
        @$db->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_web_tx_detail_type ON web_transactions(detail, type)");
    }
    return $db;
}

function getPlatformDB() {
    static $pdo = null;
    if ($pdo === null) {
        try {
            $dsn = "mysql:host={$GLOBALS['PLATFORM_DB_HOST']};dbname={$GLOBALS['PLATFORM_DB_NAME']};charset=utf8mb4";
            $pdo = new PDO($dsn, $GLOBALS['PLATFORM_DB_USER'], $GLOBALS['PLATFORM_DB_PASS'], [
                PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_TIMEOUT => 5,
            ]);
        } catch (Exception $e) {
            debugLog("Platform DB connection failed", ['error' => $e->getMessage()]);
            throw $e; // re-throw to be caught by pollPaidOrders
        }
    }
    return $pdo;
}

/**
 * 债券换算（仅当本地 pay_orders 无 bond_amount 时作为兜底估算）
 * 0.01元 → 1债券；>=1元 → 金额*10债券
 */
function estimateBonds($money) {
    $m = (float)$money;
    if ($m >= 1.0) return (int)($m * 10);
    return max(1, (int)($m * 100));
}

/**
 * 进程锁：防止多个触发源（Java 定时、前端 query_order、管理员手动"立即对账"）
 * 并发跑补单造成重复写入 / 资源争用。非阻塞，若已被占用则跳过本次。
 * 锁文件独立存在，进程退出后内核自动释放。
 */
function pollerAcquireLock() {
    static $fp = null;
    if ($fp === null) {
        $lockPath = __DIR__ . '/../db/poller.lock';
        $fp = @fopen($lockPath, 'c');
    }
    if ($fp === false) return false;
    return flock($fp, LOCK_EX | LOCK_NB);
}

function pollPaidOrders() {
    // 并发保护：已有补单进程在跑则直接跳过，杜绝重复补单
    if (!pollerAcquireLock()) {
        echo json_encode(['result' => 'already_running', 'detail' => '另一个补单进程正在运行，本次跳过']);
        return;
    }

    $sqlite = getSQLite();

    try {
        $pdo = getPlatformDB();
    } catch (\Throwable $e) {
        debugLog('[poller_online] 平台数据库连接失败: ' . $e->getMessage());
        echo json_encode(['error' => 'platform_db_connect_failed', 'detail' => $e->getMessage()]);
        return;
    }

    // 查所有已支付订单 (status=1 或 status=2)。不过滤 notify：
    // 平台可能已标记 notify=1 但本地的 DB 未更新（HTTP 回调被 CF WAF 拦截），
    // 必须靠本地 SQLite 的 status='created' 来判断是否需要补单。
    $prefix = $GLOBALS['PLATFORM_DB_PREFIX'];
    $stmt = $pdo->query("SELECT out_trade_no, trade_no, uid, money, status, notify, param, version, addtime FROM {$prefix}order WHERE status IN (1, 2) ORDER BY addtime ASC LIMIT 50");
    $allOrders = $stmt->fetchAll(PDO::FETCH_ASSOC);

    if (empty($allOrders)) {
        echo json_encode(['result' => 'no_paid_orders_on_platform']);
        return;
    }

    // 过滤掉本地已处理(paid)的
    $orders = [];
    foreach ($allOrders as $o) {
        $check = $sqlite->prepare("SELECT status FROM pay_orders WHERE out_trade_no = :no");
        $check->bindValue(':no', $o['out_trade_no'], SQLITE3_TEXT);
        $row = $check->execute()->fetchArray(SQLITE3_ASSOC);
        if (!$row || $row['status'] !== 'paid') {
            $orders[] = $o;
        }
    }

    if (empty($orders)) {
        echo json_encode(['result' => 'no_pending_orders', 'platform_paid_count' => count($allOrders)]);
        return;
    }

    $processed = 0;
    $skipped = 0;
    $now = time();

    foreach ($orders as $order) {
        $outTradeNo = $order['out_trade_no'];
        $tradeNo    = $order['trade_no'];
        $playerName = $order['param'] ?? 'unknown';
        $money      = (string)$order['money'];

        // 查本地 pay_orders（拿到正确的 bond_amount，创建订单时按档位设置）
        $check = $sqlite->prepare("SELECT status, bond_amount FROM pay_orders WHERE out_trade_no = :no");
        $check->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
        $row = $check->execute()->fetchArray(SQLITE3_ASSOC);

        if ($row && $row['status'] === 'paid') {
            $skipped++;
            markNotified($pdo, $tradeNo);
            continue;
        }

        // 债券数：优先用本地订单记录的 bond_amount（最准确），否则估算
        $bonds = 0;
        if ($row && !empty($row['bond_amount'])) {
            $bonds = (int)$row['bond_amount'];
        } else {
            $bonds = estimateBonds($money);
        }

        debugLog('[poller_online] 补单处理', [
            'out_trade_no'   => $outTradeNo,
            'player'         => $playerName,
            'money'          => $money,
            'bonds'          => $bonds,
            'platform_notify'=> $order['notify'],
            'has_local_order'=> $row ? 1 : 0,
        ]);

        // 写/更新本地 pay_orders
        if (!$row) {
            $ins = $sqlite->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, money, bond_amount, status, created_at, paid_at) VALUES (:no, :tn, :p, :m, :b, 'paid', :t, :t)");
            $ins->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $ins->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
            $ins->bindValue(':p',  $playerName, SQLITE3_TEXT);
            $ins->bindValue(':m',  $money, SQLITE3_TEXT);
            $ins->bindValue(':b',  $bonds, SQLITE3_INTEGER);
            $ins->bindValue(':t',  $now, SQLITE3_INTEGER);
            $ins->execute();
        } else {
            $upd = $sqlite->prepare("UPDATE pay_orders SET status='paid', trade_no=:tn, paid_at=:t, bond_amount=:b WHERE out_trade_no=:no AND status != 'paid'");
            $upd->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
            $upd->bindValue(':t',  $now, SQLITE3_INTEGER);
            $upd->bindValue(':b',  $bonds, SQLITE3_INTEGER);
            $upd->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $upd->execute();
        }

        // 写 web_transactions（幂等：按 detail=out_trade_no + type=recharge 去重）
        $txCheck = $sqlite->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
        $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
        $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);

        if (!$txRow) {
            $txIns = $sqlite->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(补单)', '在线充值(支付宝)', :d, 'pending', :t)");
            $txIns->bindValue(':p', $playerName, SQLITE3_TEXT);
            $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
            $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
            $txIns->execute();

            debugLog('[poller_online] 充值交易已写入 web_transactions', [
                'player' => $playerName, 'bonds' => $bonds,
                'out_trade_no' => $outTradeNo, 'money' => $money,
            ]);
        }

        markNotified($pdo, $tradeNo);
        $processed++;
    }

    echo json_encode([
        'result'   => 'ok',
        'processed' => $processed,
        'skipped'   => $skipped,
        'total'     => count($orders),
    ]);
}

function markNotified($pdo, $tradeNo) {
    try {
        $prefix = $GLOBALS['PLATFORM_DB_PREFIX'];
        $stmt = $pdo->prepare("UPDATE {$prefix}order SET notify = 1 WHERE trade_no = ? AND notify = 0");
        $stmt->execute([$tradeNo]);
    } catch (\Throwable $e) {
        debugLog('[poller_online] 标记notify失败: ' . $e->getMessage(), ['trade_no' => $tradeNo]);
    }
}

// ===== 入口 =====
// 若被其它脚本 include 并定义了 POLLER_NO_AUTO_RUN，则只暴露函数（getPlatformDB 等），
// 不在此处自动执行补单（由调用方自行决定何时触发）。
if (!defined('POLLER_NO_AUTO_RUN')) {
    header('Content-Type: application/json; charset=utf-8');
    try {
        pollPaidOrders();
    } catch (\Throwable $e) {
        debugLog('[poller_online] 未捕获异常', ['error' => $e->getMessage(), 'file' => $e->getFile(), 'line' => $e->getLine()]);
        echo json_encode(['error' => 'uncaught_exception', 'detail' => $e->getMessage()]);
    }
}
