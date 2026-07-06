<?php
/**
 * 收银台 API（独立页面后端）
 * 支持：收银员登录/登出/会话、玩家查询、订单列表、收银员管理（仅管理员）
 */

// ★ 清除所有缓冲 + 发送 JSON Content-Type
while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
ini_set('memory_limit', '256M');

if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

register_shutdown_function(function() {
    while (ob_get_level() > 0) {
        @ob_end_flush();
    }
});

if (!extension_loaded('sqlite3')) {
    exit(json_encode(['success' => false, 'message' => 'Missing SQLite3'], JSON_UNESCAPED_UNICODE));
}

require_once __DIR__ . '/../core.php';

if (!function_exists('error') || !function_exists('success') || !function_exists('getParam')) {
    exit(json_encode(['success' => false, 'message' => 'core.php load failed'], JSON_UNESCAPED_UNICODE));
}

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

$action = getParam('action', 'status');

try {
switch ($action) {
    case 'login':          cashierDoLogin(); break;
    case 'logout':         cashierDoLogout(); break;
    case 'session':        cashierSession(); break;
    case 'player_check':   cashierPlayerCheck(); break;
    case 'order_list':     cashierOrderList(); break;
    case 'cashier_list':   cashierList(); break;
    case 'add':            cashierAdd(); break;
    case 'edit':           cashierEdit(); break;
    case 'delete':         cashierDelete(); break;
    default:
        exit(json_encode(['success' => false, 'message' => '未知操作: ' . $action], JSON_UNESCAPED_UNICODE));
}
} catch (Exception $e) {
    @error_log('[cashier.php] ' . $e->getMessage());
    exit(json_encode(['success' => false, 'message' => '服务器错误: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
}

// ===== 收银员登录 =====
function cashierDoLogin() {
    $username = trim(getParam('username', ''));
    $password = getParam('password', '');
    if ($username === '' || $password === '') {
        exit(json_encode(['success' => false, 'message' => '请输入账号和密码'], JSON_UNESCAPED_UNICODE));
    }
    if (cashierLogin($username, $password)) {
        $c = getCurrentCashier();
        exit(json_encode([
            'success' => true,
            'data' => [
                'username' => $c['username'],
                'discount_limit_percent' => $c['discount_limit_percent'],
                'can_cash' => (int)($c['can_cash'] ?? 0)
            ]
        ], JSON_UNESCAPED_UNICODE));
    }
    exit(json_encode(['success' => false, 'message' => '账号或密码错误'], JSON_UNESCAPED_UNICODE));
}

// ===== 收银员登出 =====
function cashierDoLogout() {
    cashierLogout();
    exit(json_encode(['success' => true, 'message' => '已退出登录'], JSON_UNESCAPED_UNICODE));
}

// ===== 当前会话信息 =====
function cashierSession() {
    if (isAdminLoggedIn()) {
        exit(json_encode([
            'success' => true,
            'data' => [
                'role' => 'admin',
                'username' => 'admin',
                'discount_limit_percent' => 100
            ]
        ], JSON_UNESCAPED_UNICODE));
    }
    if (isCashierLoggedIn()) {
        $c = getCurrentCashier();
        exit(json_encode([
            'success' => true,
            'data' => [
                'role' => 'cashier',
                'username' => $c['username'],
                'discount_limit_percent' => $c['discount_limit_percent'],
                'can_cash' => (int)($c['can_cash'] ?? 0)
            ]
        ], JSON_UNESCAPED_UNICODE));
    }
    exit(json_encode(['success' => false, 'message' => '未登录'], JSON_UNESCAPED_UNICODE));
}

// ===== 查询目标玩家余额/在线状态（收银员或管理员均可）=====
function cashierPlayerCheck() {
    requireCashierOrAdminSession();
    $player = trim(getParam('player'));
    if (!$player) exit(json_encode(['success' => false, 'message' => '请输入玩家名'], JSON_UNESCAPED_UNICODE));
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $player)) {
        exit(json_encode(['success' => false, 'message' => '玩家名格式不正确（3-16位英文/数字/下划线）'], JSON_UNESCAPED_UNICODE));
    }
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
    try {
        $db->exec("INSERT OR IGNORE INTO bond_cache (player_name, amount, updated_at) VALUES ('" . str_replace("'", "''", $player) . "', 0, " . time() . ")");
        $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        $balance = $row ? (int)$row['amount'] : 0;

        $online = false;
        try {
            $ostmt = $db->prepare("SELECT player_name FROM online_players WHERE player_name = :name");
            $ostmt->bindValue(':name', $player, SQLITE3_TEXT);
            $orow = $ostmt->execute()->fetchArray(SQLITE3_ASSOC);
            $online = $orow ? true : false;
        } catch (\Throwable $e) {}

        exit(json_encode([
            'success' => true,
            'data' => ['player' => $player, 'exists' => true, 'balance' => $balance, 'online' => $online]
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[cashier.php] Player check error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => '查询失败: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 订单列表 =====
function cashierOrderList() {
    $role = requireCashierOrAdminSession();
    $db = getDB();
    $limit = (int)getParam('limit', 100);
    if ($limit <= 0 || $limit > 500) $limit = 100;
    $sql = "SELECT * FROM cashier_orders";
    $params = [];
    // 收银员只能看自己的订单
    if ($role === 'cashier') {
        $c = getCurrentCashier();
        $sql .= " WHERE operator_name = :op";
        $params[':op'] = $c['username'];
    }
    $sql .= " ORDER BY created_at DESC LIMIT " . (int)$limit;
    $stmt = $db->prepare($sql);
    foreach ($params as $k => $v) {
        $stmt->bindValue($k, $v, SQLITE3_TEXT);
    }
    $result = $stmt->execute();
    $rows = [];
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        if (!empty($r['items_detail'])) {
            $r['items_detail'] = json_decode($r['items_detail'], true) ?: [];
        }
        $rows[] = $r;
    }
    exit(json_encode(['success' => true, 'data' => $rows], JSON_UNESCAPED_UNICODE));
}

// ===== 收银员列表（仅管理员）=====
function cashierList() {
    requireAdminSession();
    $db = getDB();
    $result = $db->prepare("SELECT id, username, discount_limit_percent, can_cash, created_at, created_by FROM cashiers ORDER BY id ASC")->execute();
    $rows = [];
    while ($r = $result->fetchArray(SQLITE3_ASSOC)) {
        $rows[] = $r;
    }
    exit(json_encode(['success' => true, 'data' => $rows], JSON_UNESCAPED_UNICODE));
}

// ===== 新增收银员（仅管理员）=====
function cashierAdd() {
    requireAdminSession();
    $username = trim(getParam('username', ''));
    $password = getParam('password', '');
    $discountLimit = (int)getParam('discount_limit_percent', 0);
    $canCash = (int)getParam('can_cash', 0);
    if ($username === '' || $password === '') {
        exit(json_encode(['success' => false, 'message' => '账号和密码不能为空'], JSON_UNESCAPED_UNICODE));
    }
    if (!preg_match('/^[a-zA-Z0-9_]{3,20}$/', $username)) {
        exit(json_encode(['success' => false, 'message' => '账号格式不正确（3-20位英文/数字/下划线）'], JSON_UNESCAPED_UNICODE));
    }
    if ($discountLimit < 0 || $discountLimit > 100) {
        exit(json_encode(['success' => false, 'message' => '折扣上限需在0-100之间'], JSON_UNESCAPED_UNICODE));
    }
    if ($canCash < 0 || $canCash > 1) {
        exit(json_encode(['success' => false, 'message' => '现金收款权限需为0或1'], JSON_UNESCAPED_UNICODE));
    }
    $db = getDB();
    // 查重
    $chk = $db->prepare("SELECT id FROM cashiers WHERE username = :u");
    $chk->bindValue(':u', $username, SQLITE3_TEXT);
    if ($chk->execute()->fetchArray(SQLITE3_ASSOC)) {
        exit(json_encode(['success' => false, 'message' => '账号已存在'], JSON_UNESCAPED_UNICODE));
    }
    $hash = password_hash($password, PASSWORD_DEFAULT);
    $salt = bin2hex(random_bytes(8));
    $stmt = $db->prepare("INSERT INTO cashiers (username, password_hash, salt, discount_limit_percent, can_cash, created_at, created_by) VALUES (:u,:h,:s,:d,:c,:t,'admin')");
    $stmt->bindValue(':u', $username, SQLITE3_TEXT);
    $stmt->bindValue(':h', $hash, SQLITE3_TEXT);
    $stmt->bindValue(':s', $salt, SQLITE3_TEXT);
    $stmt->bindValue(':d', $discountLimit, SQLITE3_INTEGER);
    $stmt->bindValue(':c', $canCash, SQLITE3_INTEGER);
    $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
    $stmt->execute();
    exit(json_encode(['success' => true, 'message' => '收银员创建成功'], JSON_UNESCAPED_UNICODE));
}

// ===== 编辑收银员（仅管理员）：可改密码 / 折扣上限 =====
function cashierEdit() {
    requireAdminSession();
    $id = (int)getParam('id', 0);
    if ($id <= 0) exit(json_encode(['success' => false, 'message' => '缺少id'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    $chk = $db->prepare("SELECT id FROM cashiers WHERE id = :id");
    $chk->bindValue(':id', $id, SQLITE3_INTEGER);
    if (!$chk->execute()->fetchArray(SQLITE3_ASSOC)) {
        exit(json_encode(['success' => false, 'message' => '收银员不存在'], JSON_UNESCAPED_UNICODE));
    }
    $sets = [];
    $params = [':id' => $id];
    $newPassword = getParam('password', '');
    if ($newPassword !== '') {
        $sets[] = "password_hash = :h";
        $params[':h'] = password_hash($newPassword, PASSWORD_DEFAULT);
        $sets[] = "salt = :s";
        $params[':s'] = bin2hex(random_bytes(8));
    }
    if (getParam('discount_limit_percent') !== null && getParam('discount_limit_percent') !== '') {
        $dl = (int)getParam('discount_limit_percent', 0);
        if ($dl < 0 || $dl > 100) {
            exit(json_encode(['success' => false, 'message' => '折扣上限需在0-100之间'], JSON_UNESCAPED_UNICODE));
        }
        $sets[] = "discount_limit_percent = :d";
        $params[':d'] = $dl;
    }
    if (getParam('can_cash') !== null && getParam('can_cash') !== '') {
        $cc = (int)getParam('can_cash', 0);
        if ($cc < 0 || $cc > 1) {
            exit(json_encode(['success' => false, 'message' => '现金收款权限需为0或1'], JSON_UNESCAPED_UNICODE));
        }
        $sets[] = "can_cash = :c";
        $params[':c'] = $cc;
    }
    if (empty($sets)) {
        exit(json_encode(['success' => false, 'message' => '没有可更新的字段'], JSON_UNESCAPED_UNICODE));
    }
    $sql = "UPDATE cashiers SET " . implode(', ', $sets) . " WHERE id = :id";
    $stmt = $db->prepare($sql);
    foreach ($params as $k => $v) {
        $stmt->bindValue($k, $v, is_int($v) ? SQLITE3_INTEGER : SQLITE3_TEXT);
    }
    $stmt->execute();
    exit(json_encode(['success' => true, 'message' => '收银员信息已更新'], JSON_UNESCAPED_UNICODE));
}

// ===== 删除收银员（仅管理员）=====
function cashierDelete() {
    requireAdminSession();
    $id = (int)getParam('id', 0);
    if ($id <= 0) exit(json_encode(['success' => false, 'message' => '缺少id'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    $stmt = $db->prepare("DELETE FROM cashiers WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();
    exit(json_encode(['success' => true, 'message' => '收银员已删除'], JSON_UNESCAPED_UNICODE));
}
