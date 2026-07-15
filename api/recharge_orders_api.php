<?php
/**
 * 充值订单API（recharge_orders_api.php）
 * 提供充值订单查询、统计、筛选功能
 */

error_reporting(E_ALL);
ini_set('display_errors', 0);
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

// 检查管理员登录
if (!isAdminLoggedIn()) {
    error('无权限访问', 403);
}

$action = getParam('action', '');

switch ($action) {
    case 'list':
        handleOrderList();
        break;
    case 'detail':
        handleOrderDetail();
        break;
    case 'reconcile':
        handleReconcile();
        break;
    case 'sync_platform':
        handleSyncFromPlatform();
        break;
    default:
        error('未知操作');
}

/**
 * 获取订单列表
 */
function handleOrderList() {
    $db = getDB();
    ensurePayOrdersTable($db);
    
    $player = getParam('player', '');
    $orderNo = getParam('order_no', '');
    $status = getParam('status', '');
    $limit = min(200, max(1, (int)getParam('limit', 50)));
    
    // 构建查询
    $conditions = [];
    $params = [];
    
    if ($player) {
        $conditions[] = "player_name LIKE :player";
        $params[':player'] = '%' . $player . '%';
    }
    if ($orderNo) {
        $conditions[] = "out_trade_no LIKE :order_no";
        $params[':order_no'] = '%' . $orderNo . '%';
    }
    if ($status) {
        $conditions[] = "status = :status";
        $params[':status'] = $status;
    }
    
    $where = '';
    if ($conditions) {
        $where = 'WHERE ' . implode(' AND ', $conditions);
    }
    
    // 查询订单
    $sql = "SELECT * FROM pay_orders $where ORDER BY created_at DESC LIMIT :limit";
    $stmt = $db->prepare($sql);
    foreach ($params as $k => $v) {
        $stmt->bindValue($k, $v, SQLITE3_TEXT);
    }
    $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    
    $orders = [];
    $result = $stmt->execute();
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $orders[] = $row;
    }
    
    // 统计数据
    $statsSql = "SELECT 
        COUNT(*) as total,
        SUM(CASE WHEN status='paid' THEN 1 ELSE 0 END) as paid,
        SUM(CASE WHEN status='created' THEN 1 ELSE 0 END) as created,
        SUM(CASE WHEN status='paid' THEN CAST(money AS REAL) ELSE 0 END) as totalMoney
        FROM pay_orders";
    $statsResult = $db->querySingle($statsSql, true);
    
    $stats = [
        'total' => $statsResult['total'] ?? 0,
        'paid' => $statsResult['paid'] ?? 0,
        'created' => $statsResult['created'] ?? 0,
        'totalMoney' => number_format($statsResult['totalMoney'] ?? 0, 2),
    ];
    
    success(['orders' => $orders, 'stats' => $stats]);
}

/**
 * 获取单个订单详情
 */
function handleOrderDetail() {
    $outTradeNo = getParam('out_trade_no');
    if (!$outTradeNo) {
        error('缺少订单号');
    }
    
    $db = getDB();
    ensurePayOrdersTable($db);
    
    $stmt = $db->prepare("SELECT * FROM pay_orders WHERE out_trade_no = :no");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    
    if (!$row) {
        error('订单不存在');
    }
    
    success(['order' => $row]);
}

/**
 * 手动触发对账（调用 poller_online.php）
 */
function handleReconcile() {
    // 直接包含 poller_online.php 并调用 pollPaidOrders
    define('POLLER_NO_AUTO_RUN', true);
    require_once __DIR__ . '/poller_online.php';
    
    // 开始对账
    ob_start();
    pollPaidOrders();
    $output = ob_get_clean();
    
    // 解析结果
    $result = json_decode($output, true);
    if (!$result) {
        error('对账脚本执行异常');
    }
    
    // 直接输出结果，不再用 success() 包装（避免双重 JSON）
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($result, JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * 从平台MySQL同步所有已支付订单到本地
 */
function handleSyncFromPlatform() {
    $db = getDB();
    ensurePayOrdersTable($db);
    ensureWebTransactionsTable($db);

    try {
        $pdo = new PDO(
            "mysql:host=localhost;dbname=caihong;charset=utf8mb4",
            'kH3C3LLinNwYdTF5',
            'sRhsdxrpHBhmSsp8',
            [PDO::ATTR_TIMEOUT => 10, PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
        );

        // 查所有已支付订单
        $stmt = $pdo->query("SELECT out_trade_no, trade_no, uid, money, status, param, addtime FROM pay_order WHERE status IN (1, 2) ORDER BY addtime DESC LIMIT 200");
        $platformOrders = $stmt->fetchAll(PDO::FETCH_ASSOC);

        $synced = 0;
        $alreadyPaid = 0;
        $errors = [];
        $now = time();

        foreach ($platformOrders as $po) {
            $outTradeNo = $po['out_trade_no'];
            $player = $po['param'] ?? 'unknown';
            $tradeNo = $po['trade_no'] ?? '';
            $money = (string)$po['money'];
            $bonds = ($money >= '1.0') ? (int)($money * 10) : max(1, (int)($money * 100));

            // 查本地是否已有
            $check = $db->prepare("SELECT id, status FROM pay_orders WHERE out_trade_no = :no");
            $check->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $localRow = $check->execute()->fetchArray(SQLITE3_ASSOC);

            if ($localRow && $localRow['status'] === 'paid') {
                $alreadyPaid++;
                continue;
            }

            if ($localRow) {
                // 已有记录但未标记paid → 更新
                $upd = $db->prepare("UPDATE pay_orders SET status='paid', trade_no=:tn, paid_at=:t WHERE out_trade_no=:no AND status != 'paid'");
                $upd->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
                $upd->bindValue(':t', $now, SQLITE3_INTEGER);
                $upd->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
                $upd->execute();
            } else {
                // 本地无记录 → 创建
                $ins = $db->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, money, bond_amount, status, name, submit_params, created_at, paid_at) VALUES (:no, :tn, :p, :m, :b, 'paid', :n, '', :t, :t)");
                $ins->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
                $ins->bindValue(':tn', $tradeNo, SQLITE3_TEXT);
                $ins->bindValue(':p', $player, SQLITE3_TEXT);
                $ins->bindValue(':m', $money, SQLITE3_TEXT);
                $ins->bindValue(':b', $bonds, SQLITE3_INTEGER);
                $ins->bindValue(':n', '债券充值', SQLITE3_TEXT);
                $ins->bindValue(':t', $now, SQLITE3_INTEGER);
                $ins->execute();
            }

            // 写 web_transactions（幂等）
            $txCheck = $db->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
            $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);
            if (!$txRow) {
                $txIns = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '管理员同步', '在线充值(支付宝)', :d, 'pending', :t)");
                $txIns->bindValue(':p', $player, SQLITE3_TEXT);
                $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
                $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
                $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
                $txIns->execute();
            }

            $synced++;
        }

        success([
            'platform_total' => count($platformOrders),
            'synced' => $synced,
            'already_paid' => $alreadyPaid,
        ], "同步完成：平台{$synced}笔已同步，{$alreadyPaid}笔已存在");

    } catch (\Throwable $e) {
        error('同步失败: ' . $e->getMessage());
    }
}

/**
 * 确保 web_transactions 表存在
 */
function ensureWebTransactionsTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        type TEXT,
        amount INTEGER DEFAULT 0,
        operator TEXT,
        reason TEXT,
        detail TEXT,
        status TEXT DEFAULT 'pending',
        created_at INTEGER
    )");
    @$db->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_web_tx_detail_type ON web_transactions(detail, type)");
}
function ensurePayOrdersTable($db) {
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
    
    // 检查并添加缺失的列
    $hasCol = false;
    $res = $db->query("PRAGMA table_info(pay_orders)");
    while ($col = $res->fetchArray(SQLITE3_ASSOC)) {
        if ($col['name'] === 'submit_params') { $hasCol = true; break; }
    }
    if (!$hasCol) {
        $db->exec("ALTER TABLE pay_orders ADD COLUMN submit_params TEXT");
    }
}
