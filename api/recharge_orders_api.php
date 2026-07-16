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

// 加载支付密钥（含MySQL凭据）
$secretsFile = __DIR__ . '/pay_secrets.php';
if (@is_file($secretsFile) && @is_readable($secretsFile)) {
    @require_once $secretsFile;
}

/**
 * 获取平台MySQL数据库连接（集中凭据管理）
 * 注意：poller_online.php 也有同名函数，此处用不同名称避免 redeclare 冲突
 */
function getAdminPlatformDB() {
    $host   = defined('PAY_MYSQL_HOST')   ? PAY_MYSQL_HOST   : '127.0.0.1';
    $dbname = defined('PAY_MYSQL_DBNAME') ? PAY_MYSQL_DBNAME : 'caihong';
    $user   = defined('PAY_MYSQL_USER')   ? PAY_MYSQL_USER   : 'kH3C3LLinNwYdTF5';
    $pass   = defined('PAY_MYSQL_PASS')   ? PAY_MYSQL_PASS   : 'sRhsdxrpHBhmSsp8';
    $pdo = new PDO(
        "mysql:host=$host;dbname=$dbname;charset=utf8mb4",
        $user,
        $pass,
        [PDO::ATTR_TIMEOUT => 15, PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
    return $pdo;
}

/**
 * 确保充值商店配置表存在
 */
function ensureShopConfigsTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS shop_configs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_name TEXT NOT NULL DEFAULT '',
        stock INTEGER NOT NULL DEFAULT -1,
        temporary_offer TEXT NOT NULL DEFAULT '',
        offer_expire TEXT NOT NULL DEFAULT '',
        price REAL NOT NULL DEFAULT 0,
        bond_reward TEXT NOT NULL DEFAULT '1-1',
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL DEFAULT 0
    )");
}

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
    case 'shop_config':
        handleShopConfig();
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
    // ★ 使用独立订单库，不和Java共享web.db
    $db = getOrdersDB();
    ensurePayOrdersTable($db);

    // ★ 增量迁移：从 web.db 同步缺失的 pay_orders 到 orders.db（处理历史数据）
    try {
        $webDb = getDB();
        $tableCheck = $webDb->query("SELECT name FROM sqlite_master WHERE type='table' AND name='pay_orders'");
        if ($tableCheck->fetchArray()) {
            $migrateData = $webDb->query("SELECT * FROM pay_orders");
            $ins = $db->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, tier_id, money, bond_amount, status, name, platform_sign, submit_params, created_at, paid_at) VALUES (:no, :tn, :p, :ti, :m, :b, :s, :n, :ps, :sp, :ca, :pa)");
            $migrated = 0;
            while ($row = $migrateData->fetchArray(SQLITE3_ASSOC)) {
                $ins->bindValue(':no', $row['out_trade_no'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':tn', $row['trade_no'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':p', $row['player_name'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':ti', $row['tier_id'] ?? 0, SQLITE3_INTEGER);
                $ins->bindValue(':m', $row['money'] ?? '0', SQLITE3_TEXT);
                $ins->bindValue(':b', $row['bond_amount'] ?? 0, SQLITE3_INTEGER);
                $ins->bindValue(':s', $row['status'] ?? 'created', SQLITE3_TEXT);
                $ins->bindValue(':n', $row['name'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':ps', $row['platform_sign'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':sp', $row['submit_params'] ?? '', SQLITE3_TEXT);
                $ins->bindValue(':ca', $row['created_at'] ?? 0, SQLITE3_INTEGER);
                $ins->bindValue(':pa', $row['paid_at'] ?? 0, SQLITE3_INTEGER);
                $ins->execute();
                if ($db->changes() > 0) $migrated++;
            }
            if ($migrated > 0) {
                debugLog('[handleOrderList] 增量迁移web.db→orders.db', ['migrated' => $migrated]);
            }
        }
    } catch (Exception $e) {
        debugLog('[handleOrderList] 迁移失败', ['error' => $e->getMessage()]);
    }
    
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

    // ★ 使用独立订单库
    $db = getOrdersDB();
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

    // ★ 清空所有已有缓冲区（防止任何HTML/警告污染JSON）
    while (ob_get_level() > 0) { ob_end_clean(); }

    // 包含 poller_online.php（定义函数，不执行）
    require_once __DIR__ . '/poller_online.php';

    // 记录对账开始
    if (function_exists('debugLog')) {
        debugLog('[reconcile] 开始对账', [
            'admin'    => $_SESSION['admin_user'] ?? 'unknown',
            'time'     => date('Y-m-d H:i:s'),
        ]);
    }

    // 开始对账 - 捕获所有输出
    ob_start();
    try {
        pollPaidOrders();
    } catch (\Throwable $e) {
        // 捕获异常，输出JSON错误
        while (ob_get_level() > 0) { ob_end_clean(); }
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['error' => 'poller_exception', 'detail' => $e->getMessage()], JSON_UNESCAPED_UNICODE);
        exit;
    }
    $output = ob_get_clean();

    // 记录原始输出
    if (function_exists('debugLog')) {
        debugLog('[reconcile] pollPaidOrders输出', [
            'output_len' => strlen($output),
            'output'     => substr($output, 0, 500),
        ]);
    }

    // 解析结果
    $result = json_decode($output, true);
    if (!$result || !is_array($result)) {
        if (function_exists('debugLog')) {
            debugLog('[reconcile] JSON解析失败', ['output' => substr($output, 0, 200)]);
        }
        // 确保输出干净的JSON
        while (ob_get_level() > 0) { ob_end_clean(); }
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode(['error' => 'poller_output_invalid', 'raw' => substr($output, 0, 100)], JSON_UNESCAPED_UNICODE);
        exit;
    }

    // 记录解析结果
    if (function_exists('debugLog')) {
        debugLog('[reconcile] 解析结果', [
            'result' => $result,
        ]);
    }

    // 直接输出结果
    while (ob_get_level() > 0) { ob_end_clean(); }
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode($result, JSON_UNESCAPED_UNICODE);
    exit;
}

/**
 * 从平台MySQL同步所有已支付订单到本地
 */
function handleSyncFromPlatform() {
    // ★ 使用独立订单库，不和Java共享web.db（Java高频写入会锁死SQLite）
    $db = getOrdersDB();
    ensurePayOrdersTable($db);
    // 注意：web_transactions 表只在 web.db 中（Java读取），不在 orders.db 中

    try {
        $pdo = getAdminPlatformDB();

        // 查所有已支付订单
        $stmt = $pdo->query("SELECT out_trade_no, trade_no, uid, money, status, param, addtime FROM `pay_order` WHERE status IN (1, 2) ORDER BY addtime DESC LIMIT 200");
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

            // 写 web_transactions（幂等）— 注意：web_transactions 在 web.db 中（Java读取），不在 orders.db
            try {
                $webDb = getDB(); // 获取 web.db 连接
                ensureWebTransactionsTable($webDb);
                $txCheck = $webDb->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
                $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
                $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);
                if (!$txRow) {
                    $txIns = $webDb->prepare("INSERT OR IGNORE INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '管理员同步', '在线充值(支付宝)', :d, 'pending', :t)");
                    $txIns->bindValue(':p', $player, SQLITE3_TEXT);
                    $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
                    $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
                    $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
                    $txIns->execute();
                }
            } catch (Exception $e) {
                // web_transactions 写入失败不影响主流程（poller 会兜底）
                debugLog('[sync_platform] web_transactions写入失败', ['out_trade_no' => $outTradeNo, 'error' => $e->getMessage()]);
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

/**
 * 处理充值商店配置
 */
function handleShopConfig() {
    $db = getOrdersDB();
    ensureShopConfigsTable($db);
    
    $method = getParam('method', 'list');
    
    switch ($method) {
        case 'list':
            handleShopConfigList($db);
            break;
        case 'save':
            handleShopConfigSave($db);
            break;
        case 'delete':
            handleShopConfigDelete($db);
            break;
        case 'toggle':
            handleShopConfigToggle($db);
            break;
        default:
            error('未知操作');
    }
}

/**
 * 获取商店配置列表
 */
function handleShopConfigList($db) {
    $stmt = $db->query("SELECT * FROM shop_configs ORDER BY id DESC");
    $configs = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        // 解析有效期
        $expireInfo = parseExpireDate($row['offer_expire']);
        $row['expire_valid'] = $expireInfo['valid'];
        $row['expire_timestamp'] = $expireInfo['timestamp'];
        $row['expire_display'] = $expireInfo['display'];
        $configs[] = $row;
    }
    success($configs);
}

/**
 * 保存商店配置（添加或编辑）
 */
function handleShopConfigSave($db) {
    $id = intval(getParam('id', 0));
    $itemName = getParam('item_name', '');
    $stock = intval(getParam('stock', -1));
    $temporaryOffer = getParam('temporary_offer', '');
    $offerExpire = getParam('offer_expire', '');
    $price = floatval(getParam('price', 0));
    $bondReward = getParam('bond_reward', '1-1');
    $isActive = intval(getParam('is_active', 1));
    
    // 验证库存
    if ($stock < -1) {
        error('库存不能小于-1');
    }
    
    // 验证售价
    if ($price <= 0) {
        error('售价必须大于0');
    }
    
    // 验证债券范围格式（支持盲盒范围，如"1-10"）
    if (!preg_match('/^\d+(-\d+)?$/', $bondReward)) {
        error('债券范围格式错误，应为数字或范围如"1-10"');
    }
    
    // 解析并验证有效期
    $expireInfo = parseExpireDate($offerExpire);
    if ($offerExpire && !$expireInfo['valid']) {
        error('有效期格式错误，请使用yyyy-MM-dd或中文格式如"2026年7月16日"');
    }
    
    $now = time();
    
    if ($id > 0) {
        // 编辑
        $stmt = $db->prepare("UPDATE shop_configs SET 
            item_name = :name,
            stock = :stock,
            temporary_offer = :offer,
            offer_expire = :expire,
            price = :price,
            bond_reward = :bond,
            is_active = :active,
            updated_at = :updated
            WHERE id = :id");
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    } else {
        // 添加
        $stmt = $db->prepare("INSERT INTO shop_configs 
            (item_name, stock, temporary_offer, offer_expire, price, bond_reward, is_active, created_at, updated_at)
            VALUES (:name, :stock, :offer, :expire, :price, :bond, :active, :created, :updated)");
        $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
    }
    
    $stmt->bindValue(':name', $itemName, SQLITE3_TEXT);
    $stmt->bindValue(':stock', $stock, SQLITE3_INTEGER);
    $stmt->bindValue(':offer', $temporaryOffer, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $offerExpire, SQLITE3_TEXT);
    $stmt->bindValue(':price', $price, SQLITE3_FLOAT);
    $stmt->bindValue(':bond', $bondReward, SQLITE3_TEXT);
    $stmt->bindValue(':active', $isActive, SQLITE3_INTEGER);
    $stmt->bindValue(':updated', $now, SQLITE3_INTEGER);
    
    $stmt->execute();
    
    $savedId = $id > 0 ? $id : $db->lastInsertRowID();
    success(['id' => $savedId], $id > 0 ? '配置已更新' : '配置已添加');
}

/**
 * 删除商店配置
 */
function handleShopConfigDelete($db) {
    $id = intval(getParam('id', 0));
    if ($id <= 0) {
        error('无效的配置ID');
    }
    
    $stmt = $db->prepare("DELETE FROM shop_configs WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();
    
    if ($db->changes() > 0) {
        success([], '配置已删除');
    } else {
        error('配置不存在');
    }
}

/**
 * 切换商店配置上下架状态
 */
function handleShopConfigToggle($db) {
    $id = intval(getParam('id', 0));
    if ($id <= 0) {
        error('无效的配置ID');
    }
    
    $stmt = $db->prepare("UPDATE shop_configs SET is_active = CASE WHEN is_active = 1 THEN 0 ELSE 1 END, updated_at = :updated WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->bindValue(':updated', time(), SQLITE3_INTEGER);
    $stmt->execute();
    
    if ($db->changes() > 0) {
        // 获取新状态
        $check = $db->prepare("SELECT is_active FROM shop_configs WHERE id = :id");
        $check->bindValue(':id', $id, SQLITE3_INTEGER);
        $row = $check->execute()->fetchArray(SQLITE3_ASSOC);
        $newStatus = $row ? $row['is_active'] : 0;
        success(['is_active' => $newStatus], $newStatus ? '已上架' : '已下架');
    } else {
        error('配置不存在');
    }
}

/**
 * 解析有效期日期（支持yyyy-MM-dd和中文格式如"2026年7月16日"）
 */
function parseExpireDate($expireStr) {
    if (empty($expireStr)) {
        return ['valid' => true, 'timestamp' => 0, 'display' => '长期有效'];
    }
    
    $expireStr = trim($expireStr);
    
    // 尝试解析yyyy-MM-dd格式
    if (preg_match('/^\d{4}-\d{1,2}-\d{1,2}$/', $expireStr)) {
        $time = strtotime($expireStr . ' 23:59:59');
        if ($time !== false) {
            return [
                'valid' => true,
                'timestamp' => $time,
                'display' => date('Y-m-d', $time) . ' 到期'
            ];
        }
    }
    
    // 尝试解析中文格式（如"2026年7月16日"）
    if (preg_match('/(\d{4})年(\d{1,2})月(\d{1,2})日/', $expireStr, $matches)) {
        $year = $matches[1];
        $month = $matches[2];
        $day = $matches[3];
        $time = mktime(23, 59, 59, $month, $day, $year);
        if ($time !== false) {
            return [
                'valid' => true,
                'timestamp' => $time,
                'display' => "{$year}-{$month}-{$day} 到期"
            ];
        }
    }
    
    // 尝试使用strtotime解析其他格式
    $time = strtotime($expireStr);
    if ($time !== false && $time > time()) {
        return [
            'valid' => true,
            'timestamp' => $time,
            'display' => date('Y-m-d', $time) . ' 到期'
        ];
    }
    
    return ['valid' => false, 'timestamp' => 0, 'display' => '无效日期'];
}
