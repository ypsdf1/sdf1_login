<?php
require_once __DIR__ . '/../security.php';   // 第四层：全局限流（2026-09-30）
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

// ===== 配置（从 pay_secrets.php 加载，集中管理） =====
$secretsFile = __DIR__ . '/pay_secrets.php';
if (@is_file($secretsFile) && @is_readable($secretsFile)) {
    @require_once $secretsFile;
}

// ★ 订单前缀读配置（2026-10-06 多服隔离）：创单端 pay.php 用什么前缀，这里就扫什么前缀。
//   此前写死 'RE'，测试服与生产服共用一个前缀 → 互相捡单串数据。
//   ★★ 改前缀时，pay.php / poller_online.php / pay_poller.php / recharge_orders_api.php
//      四个文件必须一起改（它们现在都读同一个 PAY_ORDER_PREFIX，只要 config.php 改了就同步生效）。
require_once __DIR__ . '/pay_config.php';
$PLATFORM_DB_HOST = defined('PAY_MYSQL_HOST') ? PAY_MYSQL_HOST : '127.0.0.1';
$PLATFORM_DB_NAME = defined('PAY_MYSQL_DBNAME') ? PAY_MYSQL_DBNAME : 'caihong';
$PLATFORM_DB_USER = defined('PAY_MYSQL_USER') ? PAY_MYSQL_USER : 'hbye3AezRNk4r7YA';
$PLATFORM_DB_PASS = defined('PAY_MYSQL_PASS') ? PAY_MYSQL_PASS : '5HtD7Rn3seRAn2BE';
$GLOBALS['PLATFORM_DB_PREFIX'] = 'pay_'; $PLATFORM_DB_PREFIX = 'pay_';

// SQLite 数据库路径
$GLOBALS['SQLITE_DB_PATH'] = __DIR__ . '/../db/web.db';     // web_transactions (Java读写)
$GLOBALS['ORDERS_DB_PATH'] = __DIR__ . '/../db/orders.db';  // pay_orders (PHP读写)

if (!function_exists('debugLog')) {
function debugLog($msg, $ctx = []) {
    $logFile = __DIR__ . '/../db/debug.log';
    $ts = date('Y-m-d H:i:s');
    $entry = "[$ts] $msg";
    if ($ctx) $entry .= ' | Context: ' . json_encode($ctx, JSON_UNESCAPED_UNICODE);
    $entry .= "\n";
    @file_put_contents($logFile, $entry, FILE_APPEND | LOCK_EX);
    // ★ 保留策略（2026-10-05）：只留最近3天 + ≤5MB，任一超标即清理到两条件同时满足
    if (is_file(__DIR__ . '/../log_maintain.php')) {
        @require_once __DIR__ . '/../log_maintain.php';
        if (function_exists('logMaintain')) { @logMaintain($logFile); }
    }
}
} // end if (!function_exists('debugLog'))

function getSQLite() {
    // web.db — Java 高频读写（web_transactions 表）
    static $db = null;
    if ($db === null) {
        $db = new SQLite3($GLOBALS['SQLITE_DB_PATH']);
        $db->enableExceptions(true);
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=5000');
        // web_transactions 表（Java 读取补单）
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
        @$db->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_web_tx_detail_type ON web_transactions(detail, type)");
    }
    return $db;
}

function getOrdersSQLite() {
    // orders.db — PHP 订单库（pay_orders 表），独立于 Java 的 web.db
    static $db = null;
    if ($db === null) {
        $db = new SQLite3($GLOBALS['ORDERS_DB_PATH']);
        $db->enableExceptions(true);
        $db->exec('PRAGMA journal_mode=WAL');
        $db->exec('PRAGMA busy_timeout=5000');
        // pay_orders 表
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
    }
    return $db;
}

function getPlatformDB() {
    try {
        // ★ 直接用常量（从 pay_secrets.php 加载），不依赖全局变量
        $host   = defined('PAY_MYSQL_HOST')   ? PAY_MYSQL_HOST   : '127.0.0.1';
        $dbname = defined('PAY_MYSQL_DBNAME') ? PAY_MYSQL_DBNAME : 'caihong';
        $user   = defined('PAY_MYSQL_USER')   ? PAY_MYSQL_USER   : 'hbye3AezRNk4r7YA';
        $pass   = defined('PAY_MYSQL_PASS')   ? PAY_MYSQL_PASS   : '5HtD7Rn3seRAn2BE';

        // ★ 2026-10-05 日志收敛：连接过程（正在连接/DSN/连接成功）属于每次轮询的
        //   常规路径，一律不写日志——补单器约 20 秒跑一次，这 3 行是 debug.log
        //   膨胀的元凶之一。只有下面 catch 的"连接失败"（报错）才写日志。
        $start = microtime(true);
        $dsn = "mysql:host=$host;dbname=$dbname;charset=utf8mb4";
        $pdo = new PDO($dsn, $user, $pass, [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_TIMEOUT => 15,
        ]);
        return $pdo;
    } catch (\Throwable $e) {
        $elapsed = round((microtime(true) - ($start ?? microtime(true))) * 1000);
        debugLog("[poller] MySQL连接失败", [
            'error'     => $e->getMessage(),
            'code'      => $e->getCode(),
            'file'      => $e->getFile(),
            'line'      => $e->getLine(),
            'elapsed_ms'=> $elapsed,
            'dsn'       => $dsn ?? 'undefined',
            'user'      => $user ?? 'undefined',
        ]);
        throw $e;
    }
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
    global $PLATFORM_DB_PREFIX;
    $startTime = microtime(true);
    // ★ 2026-10-05 日志收敛：不再无条件写"补单开始"。没有合适订单时下面直接
    //   静默 return（只回 JSON，不写日志）；只有真正补到单（成功）或出错才写。

    // 并发保护：已有补单进程在跑则直接跳过，杜绝重复补单
    if (!pollerAcquireLock()) {
        echo json_encode(['result' => 'already_running', 'detail' => '另一个补单进程正在运行，本次跳过']);
        return;
    }

    $sqlite = getOrdersSQLite();      // orders.db（pay_orders）
    $webSqlite = getSQLite();          // web.db（web_transactions）

    try {
        $pdo = getPlatformDB();
    } catch (\Throwable $e) {
        debugLog('[poller_online] 平台数据库连接失败', [
            'error'   => $e->getMessage(),
            'code'    => $e->getCode(),
            'file'    => $e->getFile(),
            'line'    => $e->getLine(),
            'context' => 'pollPaidOrders',
        ]);
        echo json_encode([
            'error'   => 'platform_db_connect_failed',
            'detail'  => $e->getMessage(),
            'code'    => $e->getCode(),
            'file'    => basename($e->getFile()),
            'line'    => $e->getLine(),
        ]);
        return;
    }

    // ★ 2026-10-02 只查后端自己的订单：平台 MySQL 是多商户共用库，`pay_order` 里
    //   绝大多数是其他商户/项目的单（纯数字单号）。以前不加过滤整表拉，平台每来一单
    //   就被我们"捡走"、凭空写进本地 pay_orders + web_transactions，产生玩家=unknown
    //   的垃圾充值单（Java 每次拉交易都刷屏处理它们），还顺手把别人的订单 notify
    //   改成 1 —— 纯属多管闲事。本方单号由 pay.php createOrder 生成，前缀取自
    //   config.php 的 PAY_ORDER_PREFIX（生产 'RE' / 测试服 'RT' 等，各部署各配），
    //   平台侧据此过滤，PHP 侧再兜底校验一次；平台其他订单一概不读不写。
    //   仍不过滤 notify：平台可能已标记 notify=1 但本地 DB 未更新（HTTP 回调被
    //   CF WAF 拦截），是否补单以本地 pay_orders 状态为准。
    $prefix = $PLATFORM_DB_PREFIX;
    // ★ 2026-10-06：扫描前缀改为读配置（原来写死 'RE'）
    // ★ 2026-10-07：前缀第一来源改为站点根 pay.md「前缀码」；扫描改为「当前前缀+历史前缀」
    //   双条件（payOrderPrefixLikeAny），手工改过前缀码后，改动前已生成的在途单仍能补回。
    $stmt = $pdo->prepare("SELECT out_trade_no, trade_no, uid, money, status, notify, param, version, addtime FROM `{$prefix}order` WHERE (" . payOrderPrefixLikeAny() . ") AND status IN (1, 2) ORDER BY addtime ASC LIMIT 50");
    $stmt->execute();
    $allOrders = $stmt->fetchAll(PDO::FETCH_ASSOC);

    if (empty($allOrders)) {
        echo json_encode(['result' => 'no_paid_orders_on_platform']);
        return;
    }

    // 只保留本方订单（前缀兜底校验，与创单端同一份配置），再过滤掉本地已处理(paid)的
    $orders = [];
    $foreign = 0;
    foreach ($allOrders as $o) {
        $outNo = (string)$o['out_trade_no'];
        if (!payOrderIsOurs($outNo)) {
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

        // 写 web_transactions（幂等：INSERT OR IGNORE 防止竞态条件重复插入）→ web.db
        $txIns = $webSqlite->prepare("INSERT OR IGNORE INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(补单)', '在线充值(支付宝)', :d, 'pending', :t)");
        $txIns->bindValue(':p', $playerName, SQLITE3_TEXT);
        $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
        $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
        $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
        $txIns->execute();

        // 检查是否是新插入的（affected_rows > 0 表示新插入，=0 表示已存在被忽略）
        if ($webSqlite->changes() > 0) {
            debugLog('[poller_online] 充值交易已写入 web_transactions', [
                'player' => $playerName, 'bonds' => $bonds,
                'out_trade_no' => $outTradeNo, 'money' => $money,
            ]);
        }

        markNotified($pdo, $tradeNo);
        $processed++;
    }

    $elapsed = round((microtime(true) - $startTime) * 1000);
    // ★ 2026-10-05 日志收敛：只在本轮真正补到单（processed > 0）时写"补单完成"；
    //   没有实际补单动作则静默，不再每轮刷一条。
    if ($processed > 0) {
        debugLog('[poller] 补单完成', ['processed' => $processed, 'skipped' => $skipped, 'elapsed_ms' => $elapsed]);
    }

    echo json_encode([
        'result'   => 'ok',
        'processed' => $processed,
        'skipped'   => $skipped,
        'foreign_skipped' => $foreign,
        'total'     => count($orders),
        'elapsed_ms' => $elapsed,
    ]);
}

function markNotified($pdo, $tradeNo) {
    global $PLATFORM_DB_PREFIX;
    try {
        $prefix = $PLATFORM_DB_PREFIX;
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
