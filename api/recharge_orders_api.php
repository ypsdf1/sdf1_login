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
    
    success($result);
}

/**
 * 确保 pay_orders 表存在（从 pay.php 复制）
 */
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
