<?php
/**
 * 管理API
 * POST ?action=login       - 管理员登录
 * POST ?action=logout      - 管理员登出
 * GET  ?action=status       - 登录状态
 * POST ?action=deduct       - 扣除债券
 * POST ?action=add_bonds    - 增加债券（给玩家）
 * POST ?action=shop_update  - 更新商品
 * POST ?action=shop_add     - 添加商品
 * POST ?action=shop_remove  - 删除商品
 * GET  ?action=stats        - 全服统计
 * GET  ?action=all_tx       - 全服流水
 * GET  ?action=player_tx    - 指定玩家流水
 * GET  ?action=list_reset_requests - 获取待审核重置请求
 * POST ?action=admin_approve_reset - 批准密码重置
 * POST ?action=admin_reject_reset  - 驳回密码重置
 * GET  ?action=list_users   - 列出所有用户
 */
// 防止任何输出污染JSON响应
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

session_start();

$action = getParam('action', 'status');

switch ($action) {
    case 'login':
        adminDoLogin();
        break;
    case 'logout':
        adminDoLogout();
        break;
    case 'status':
        adminStatus();
        break;
    case 'deduct':
        adminDeduct();
        break;
    case 'add_bonds':
        adminAddBonds();
        break;
    case 'shop_update':
        adminShopUpdate();
        break;
    case 'shop_add':
        adminShopAdd();
        break;
    case 'shop_remove':
        adminShopRemove();
        break;
    case 'stats':
        adminStats();
        break;
    case 'all_tx':
        adminAllTx();
        break;
    case 'player_tx':
        adminPlayerTx();
        break;
    case 'gen_token':
        adminGenToken();
        break;
    case 'notify_sync':
        adminNotifySync();
        break;
    case 'sync_now':
        adminSyncNow();
        break;
    case 'cdk_add':
        adminCdkAdd();
        break;
    case 'cdk_batch':
        adminCdkBatch();
        break;
    case 'list_users':
        adminListUsers();
        break;
    case 'admin_approve_reset':
        adminApproveReset();
        break;
    case 'admin_reject_reset':
        adminRejectReset();
        break;
    case 'list_reset_requests':
        adminListResetRequests();
        break;
    case 'list_online_players':
        adminListOnlinePlayers();
        break;
    case 'list_active_players':
        adminListActivePlayers();
        break;
    case 'get_stats_ex':
        adminGetStatsEx();
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== 登录 =====
function adminDoLogin() {
    $password = getParam('password');
    if (!$password) error('缺少密码');

    if (adminLogin($password)) {
        success(['login_time' => time()], '登录成功');
    } else {
        error('密码错误', 401);
    }
}

function adminDoLogout() {
    session_destroy();
    success(null, '已登出');
}

function adminStatus() {
    success([
        'logged_in' => isAdminLoggedIn(),
        'login_time' => $_SESSION['admin_login_time'] ?? 0
    ]);
}

// ===== 债券操作 =====
function adminDeduct() {
    requireAdminSession();

    $player = getParam('player');
    $amount = (int)getParam('amount', 0);
    $reason = getParam('reason', '管理员扣除');

    if (!$player) error('缺少player');
    if ($amount <= 0) error('扣除金额必须大于0');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    $current = $row ? $row['amount'] : 0;

    if ($current < $amount) {
        error("余额不足: 当前{$current}，需扣除{$amount}");
    }

    $newBalance = $current - $amount;
    $now = time();

    if ($row) {
        $stmt = $db->prepare("UPDATE bond_cache SET amount = :amount, updated_at = :time WHERE player_name = :name");
    } else {
        $stmt = $db->prepare("INSERT INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
    }
    $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $stmt->execute();

    // 记录流水
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, created_at) VALUES (:player, 'admin_deduct', :amount, 'admin', :reason, :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
    $stmt->bindValue(':reason', $reason, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->execute();

    success([
        'player' => $player,
        'amount' => -$amount,
        'balance_before' => $current,
        'balance_after' => $newBalance
    ], '扣除成功');
}

function adminAddBonds() {
    requireAdminSession();

    $player = getParam('player');
    $amount = (int)getParam('amount', 0);
    $reason = getParam('reason', '管理员充值');

    if (!$player) error('缺少player');
    if ($amount <= 0) error('金额必须大于0');

    // 验证玩家名格式（3-16位字母数字下划线）
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $player)) {
        error('玩家名格式不正确（3-16位字母数字下划线）');
    }

    $db = getDB();
    
    // 检查玩家是否在users表中存在
    $checkStmt = $db->prepare("SELECT 1 FROM users WHERE player_name = :player");
    $checkStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $checkResult = $checkStmt->execute();
    if (!$checkResult->fetchArray()) {
        error('玩家不存在: ' . $player);
    }

    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    $current = $row ? $row['amount'] : 0;
    $newBalance = $current + $amount;
    $now = time();

    if ($row) {
        $stmt = $db->prepare("UPDATE bond_cache SET amount = :amount, updated_at = :time WHERE player_name = :name");
    } else {
        $stmt = $db->prepare("INSERT INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
    }
    $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $stmt->execute();

    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, created_at) VALUES (:player, 'admin_give', :amount, 'admin', :reason, :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
    $stmt->bindValue(':reason', $reason, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->execute();

    success([
        'player' => $player,
        'amount' => $amount,
        'balance_before' => $current,
        'balance_after' => $newBalance
    ], '充值成功');
}

// ===== 商品管理 =====
function adminShopUpdate() {
    requireAdminSession();

    $id = getParam('id');
    $buyPrice = getParam('buy_price');
    $sellPrice = getParam('sell_price');
    $stock = getParam('stock');

    if (!$id) error('缺少商品ID');

    $db = getDB();
    $sets = [];
    $params = [':id' => [$id, SQLITE3_TEXT]];

    if ($buyPrice !== null) { $sets[] = 'buy_price = :bp'; $params[':bp'] = [(int)$buyPrice, SQLITE3_INTEGER]; }
    if ($sellPrice !== null) { $sets[] = 'sell_price = :sp'; $params[':sp'] = [(int)$sellPrice, SQLITE3_INTEGER]; }
    if ($stock !== null) { $sets[] = 'stock = :st'; $params[':st'] = [(int)$stock, SQLITE3_INTEGER]; }

    if (empty($sets)) error('没有要更新的字段');

    $sql = "UPDATE shop_items SET " . implode(', ', $sets) . " WHERE id = :id";
    $stmt = $db->prepare($sql);
    foreach ($params as $k => $v) {
        $stmt->bindValue($k, $v[0], $v[1]);
    }
    $stmt->execute();

    success(['id' => $id], '商品更新成功');
}

function adminShopAdd() {
    requireAdminSession();

    $id = getParam('id');
    $category = getParam('category', '默认');
    $name = getParam('display_name');
    $material = getParam('material', 'STONE');
    $buyPrice = (int)getParam('buy_price', 0);
    $sellPrice = (int)getParam('sell_price', -1);
    $stock = (int)getParam('stock', -1);

    if (!$id) error('缺少商品ID');
    if (!$name) error('缺少商品名');

    $db = getDB();
    $stmt = $db->prepare("INSERT OR REPLACE INTO shop_items (id, category, display_name, material, buy_price, sell_price, stock, last_sync) VALUES (:id, :cat, :name, :mat, :bp, :sp, :st, :time)");
    $stmt->bindValue(':id', $id, SQLITE3_TEXT);
    $stmt->bindValue(':cat', $category, SQLITE3_TEXT);
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->bindValue(':mat', $material, SQLITE3_TEXT);
    $stmt->bindValue(':bp', $buyPrice, SQLITE3_INTEGER);
    $stmt->bindValue(':sp', $sellPrice, SQLITE3_INTEGER);
    $stmt->bindValue(':st', $stock, SQLITE3_INTEGER);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    success(['id' => $id], '商品添加成功');
}

function adminShopRemove() {
    requireAdminSession();

    $id = getParam('id');
    if (!$id) error('缺少商品ID');

    $db = getDB();
    $stmt = $db->prepare("DELETE FROM shop_items WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_TEXT);
    $stmt->execute();

    success(['id' => $id], '商品已删除');
}

// ===== 统计 =====
function adminStats() {
    requireAdminSession();

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    // 总债券
    $result = $db->query("SELECT SUM(amount) as total_bonds, COUNT(*) as total_players FROM bond_cache");
    $bonds = $result->fetchArray(SQLITE3_ASSOC);

    // 总CDK
    $result = $db->query("SELECT COUNT(*) as total, SUM(CASE WHEN used=0 THEN 1 ELSE 0 END) as unused FROM cdk");
    $cdk = $result->fetchArray(SQLITE3_ASSOC);

    // 总交易
    $result = $db->query("SELECT COUNT(*) as total FROM web_transactions");
    $tx = $result->fetchArray(SQLITE3_ASSOC);

    // 总商品
    $result = $db->query("SELECT COUNT(*) as total FROM shop_items");
    $shop = $result->fetchArray(SQLITE3_ASSOC);

    // 总注册
    $result = $db->query("SELECT COUNT(*) as total FROM users");
    $users = $result->fetchArray(SQLITE3_ASSOC);

    success([
        'total_bonds' => $bonds['total_bonds'] ?? 0,
        'total_players' => $bonds['total_players'] ?? 0,
        'total_cdk' => $cdk['total'] ?? 0,
        'unused_cdk' => $cdk['unused'] ?? 0,
        'total_transactions' => $tx['total'] ?? 0,
        'total_shop_items' => $shop['total'] ?? 0,
        'total_users' => $users['total'] ?? 0
    ]);
}

function adminAllTx() {
    requireAdminSession();

    $limit = (int)getParam('limit', 100);
    $type = getParam('type');

    $db = getDB();
    if ($type) {
        $stmt = $db->prepare("SELECT * FROM web_transactions WHERE type = :type ORDER BY created_at DESC LIMIT :limit");
        $stmt->bindValue(':type', $type, SQLITE3_TEXT);
        $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    } else {
        $stmt = $db->prepare("SELECT * FROM web_transactions ORDER BY created_at DESC LIMIT :limit");
        $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    }

    $txs = [];
    $result = $stmt->execute();
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $txs[] = $row;
    }

    success($txs);
}

function adminPlayerTx() {
    requireAdminSession();

    $player = getParam('player');
    if (!$player) error('缺少player');

    $limit = (int)getParam('limit', 100);

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_transactions WHERE player_name = :player ORDER BY created_at DESC LIMIT :limit");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);

    $txs = [];
    $result = $stmt->execute();
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $txs[] = $row;
    }

    success($txs);
}

// ===== 生成Token =====
function adminGenToken() {
    requireAdminSession();

    $player = getParam('player', 'admin');
    $purpose = getParam('purpose', 'all');
    $expireSeconds = (int)getParam('expire', 600);

    if (!$player) error('缺少player');

    // 生成随机token
    $token = bin2hex(random_bytes(32));

    $db = getDB();
    $now = time();

    $stmt = $db->prepare("INSERT INTO tokens (token, player_name, purpose, created_at, expires_at, used) VALUES (:token, :player, :purpose, :created, :expires, 0)");
    $stmt->bindValue(':token', $token, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
    $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    success([
        'token' => $token,
        'player' => $player,
        'purpose' => $purpose,
        'expires_at' => $now + $expireSeconds
    ], 'Token生成成功');
}

// ===== 通知插件立即同步 =====
function adminNotifySync() {
    requireAdminSession();

    // 写入同步通知文件
    $notifyFile = __DIR__ . '/../../sync_notify.txt';
    file_put_contents($notifyFile, time());

    success([], '已通知插件立即同步');
}

// ===== 立即同步数据 =====
function adminSyncNow() {
    requireAdminSession();

    // 写入同步通知文件
    $notifyFile = __DIR__ . '/../../sync_notify.txt';
    file_put_contents($notifyFile, time());

    success([], '已触发立即同步');
}

// ===== 添加CDK =====
function adminCdkAdd() {
    requireAdminSession();

    $code = getParam('code');
    $amount = (int)getParam('amount', 0);
    $type = getParam('type', 'bond');
    $description = getParam('description', '');

    if (!$code) error('缺少CDK码');
    if ($amount <= 0) error('金额必须大于0');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (code TEXT PRIMARY KEY, amount INTEGER NOT NULL, type TEXT DEFAULT 'bond', description TEXT, used INTEGER DEFAULT 0, used_by TEXT, used_at INTEGER)");

    // 检查是否已存在
    $checkStmt = $db->prepare("SELECT 1 FROM cdk WHERE code = :code");
    $checkStmt->bindValue(':code', $code, SQLITE3_TEXT);
    $checkResult = $checkStmt->execute();
    if ($checkResult->fetchArray()) {
        error('CDK码已存在');
    }

    $stmt = $db->prepare("INSERT INTO cdk (code, amount, type, description) VALUES (:code, :amount, :type, :desc)");
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
    $stmt->bindValue(':type', $type, SQLITE3_TEXT);
    $stmt->bindValue(':desc', $description, SQLITE3_TEXT);
    $stmt->execute();

    success(['code' => $code, 'amount' => $amount], 'CDK添加成功');
}

// ===== 批量生成CDK =====
function adminCdkBatch() {
    requireAdminSession();

    $amount = (int)getParam('amount', 0);
    $count = (int)getParam('count', 1);

    if ($amount <= 0) error('金额必须大于0');
    if ($count < 1 || $count > 100) error('数量必须在1-100之间');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (code TEXT PRIMARY KEY, amount INTEGER NOT NULL, type TEXT DEFAULT 'bond', description TEXT, used INTEGER DEFAULT 0, used_by TEXT, used_at INTEGER, created_at INTEGER)");

    $codes = [];
    for ($i = 0; $i < $count; $i++) {
        // 生成8位随机CDK码
        $code = strtoupper(substr(md5(uniqid(mt_rand(), true)), 0, 8));
        
        $stmt = $db->prepare("INSERT INTO cdk (code, amount, type, created_at) VALUES (:code, :amount, 'bond', :time)");
        $stmt->bindValue(':code', $code, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        
        $codes[] = $code;
    }

    success(['codes' => $codes, 'count' => count($codes)], '成功生成' . count($codes) . '个CDK');
}

// ===== 列出用户 =====
function adminListUsers() {
    requireAdminSession();

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM users ORDER BY register_time DESC");
    $result = $stmt->execute();

    $users = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $users[] = $row;
    }

    success($users);
}

// ===== 获取待审核重置请求 =====
function adminListResetRequests() {
    requireAdminSession();

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS password_reset_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        requested_email TEXT DEFAULT '',
        status TEXT DEFAULT 'pending',
        admin_approved INTEGER DEFAULT 0,
        admin_email TEXT DEFAULT '',
        created_at INTEGER NOT NULL,
        processed_at INTEGER DEFAULT 0
    )");

    $stmt = $db->prepare("SELECT * FROM password_reset_requests WHERE status = 'pending' ORDER BY created_at DESC");
    $result = $stmt->execute();

    $requests = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $requests[] = $row;
    }

    success($requests);
}

// ===== 批准密码重置请求 =====
function adminApproveReset() {
    requireAdminSession();

    $reqId = (int)getParam('id', 0);
    $adminEmail = getParam('admin_email', '');

    if (!$reqId) error('缺少请求ID');
    if (!$adminEmail || !filter_var($adminEmail, FILTER_VALIDATE_EMAIL)) error('请输入有效的管理员邮箱');

    $db = getDB();

    // 获取请求信息
    $stmt = $db->prepare("SELECT * FROM password_reset_requests WHERE id = :id AND status = 'pending'");
    $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('请求不存在或已处理');

    $player = $row['player_name'];

    // 生成临时密码
    $saltBytes = random_bytes(16);
    $salt = base64_encode($saltBytes);
    $tempPassword = '123456aA';
    $passwordHash = base64_encode(hash('sha256', $saltBytes . $tempPassword, true));

    // 更新玩家数据
    $stmt = $db->prepare("UPDATE users SET password_hash = :hash, password_salt = :salt, email = :email WHERE player_name = :player");
    $stmt->bindValue(':hash', $passwordHash, SQLITE3_TEXT);
    $stmt->bindValue(':salt', $salt, SQLITE3_TEXT);
    $stmt->bindValue(':email', $adminEmail, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->execute();

    // 标记请求为已批准
    $stmt = $db->prepare("UPDATE password_reset_requests SET status = 'approved', admin_approved = 1, admin_email = :admin_email, processed_at = :time WHERE id = :id");
    $stmt->bindValue(':admin_email', $adminEmail, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
    $stmt->execute();

    success(['player' => $player, 'temp_password' => $tempPassword], '已批准，临时密码已设置为: ' . $tempPassword);
}

// ===== 驳回密码重置请求 =====
function adminRejectReset() {
    requireAdminSession();

    $reqId = (int)getParam('id', 0);

    if (!$reqId) error('缺少请求ID');

    $db = getDB();

    // 更新请求状态
    $stmt = $db->prepare("UPDATE password_reset_requests SET status = 'rejected', processed_at = :time WHERE id = :id");
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
    $stmt->execute();

    success(null, '已驳回该请求');
}

// ===== 获取在线玩家列表 =====
function adminListOnlinePlayers() {
    requireAdminSession();

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER DEFAULT 0)");

    $stmt = $db->query("SELECT player_name, login_time FROM online_players ORDER BY login_time DESC");
    $players = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $players[] = $row;
    }

    success($players);
}

// ===== 获取活跃玩家（最近24小时登录过） =====
function adminListActivePlayers() {
    requireAdminSession();

    $db = getDB();
    $cutoff = time() - 86400; // 24小时前

    $stmt = $db->prepare("SELECT player_name, last_login_time, total_online_time FROM users WHERE last_login_time >= :cutoff ORDER BY last_login_time DESC");
    $stmt->bindValue(':cutoff', $cutoff, SQLITE3_INTEGER);
    $result = $stmt->execute();

    $players = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $hours = round(($row['total_online_time'] ?? 0) / 3600, 1);
        $players[] = [
            'player_name' => $row['player_name'],
            'last_login_time' => $row['last_login_time'],
            'total_online_time' => $row['total_online_time'],
            'hours_online' => $hours
        ];
    }

    success($players);
}

// ===== 扩展统计（含在线/活跃玩家） =====
function adminGetStatsEx() {
    requireAdminSession();

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER DEFAULT 0)");

    // 在线玩家数
    $onlineResult = $db->query("SELECT COUNT(*) as cnt FROM online_players");
    $onlineCount = $onlineResult->fetchArray(SQLITE3_ASSOC)['cnt'];

    // 活跃玩家（24小时内）
    $cutoff = time() - 86400;
    $activeResult = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE last_login_time >= :cutoff");
    $activeResult->bindValue(':cutoff', $cutoff, SQLITE3_INTEGER);
    $activeResult->execute();
    $activeCount = $activeResult->fetchArray(SQLITE3_ASSOC)['cnt'];

    // 总用户
    $userResult = $db->query("SELECT COUNT(*) as cnt FROM users");
    $userCount = $userResult->fetchArray(SQLITE3_ASSOC)['cnt'];

    success([
        'total_users' => $userCount,
        'online_count' => $onlineCount,
        'active_count_24h' => $activeCount
    ]);
}
