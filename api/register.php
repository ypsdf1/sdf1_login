<?php
/**
 * 注册API
 * POST ?action=check    - 检查用户名是否已注册
 * POST ?action=register - 注册新账号（需token）
 * GET  ?action=query     - 查询账号信息（需token）
 */
// 防止任何输出污染JSON响应
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

$action = getParam('action', 'check');
$token = getParam('token');

switch ($action) {
    case 'check':
        registerCheck();
        break;
    case 'register':
        registerAccount($token);
        break;
    case 'web_register':
        webRegister();
        break;
    case 'query':
        registerQuery($token);
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== 检查用户名 =====
function registerCheck() {
    $player = getParam('player');
    if (!$player) error('缺少player参数');

    $db = getDB();
    // 只查询users表实际存在的列
    $stmt = $db->prepare("SELECT player_name, register_time, email FROM users WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    success([
        'exists' => !!$row,
        'player' => $player,
        'info' => $row ?: null
    ]);
}

// ===== 注册账号 =====
function registerAccount($token) {
    $player = getParam('player');
    $passwordHash = getParam('password_hash');
    $salt = getParam('salt');
    $email = getParam('email', '');
    $ip = getParam('ip', '');

    if (!$player) error('缺少player');
    if (!$passwordHash) error('缺少password_hash');
    if (!$salt) error('缺少salt');

    // 验证token（注册需要token）
    $isPreview = true;
    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo) {
            $isPreview = false;
            validateAndUseToken($token);
        }
    }

    $db = getDB();

    // 检查是否已注册
    $stmt = $db->prepare("SELECT 1 FROM users WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    if ($result->fetchArray()) {
        error('账号已存在: ' . $player);
    }

    if ($isPreview) {
        preview([
            'player' => $player,
            'email' => $email,
            'would_register' => true
        ], '预览模式 - 注册不会生效');
    }

    // 执行注册
    $now = time();
    $stmt = $db->prepare("INSERT INTO users (player_name, register_time, email) VALUES (:name, :time, :email)");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->execute();

    // 记录
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, created_at) VALUES (:player, 'register', 0, 'Web端注册', :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->execute();

    success(['player' => $player], '注册成功');
}

// ===== Web注册请求（由Web端提交，等待插件同步）=====
function webRegister() {
    $webToken = getParam('web_token');
    $password = getParam('password');
    $email = getParam('email', '');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$webToken) error('缺少web_token');
    if (!$password) error('缺少password');
    if (strlen($password) < 6) error('密码长度至少6位');

    // 验证web_token
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $tokenRow = $result->fetchArray(SQLITE3_ASSOC);

    if (!$tokenRow) error('无效的Web登录Token');

    $createdAt = (int)($tokenRow['created_at'] ?? 0);
    $expireSeconds = (int)($tokenRow['expire_seconds'] ?? 600);
    if (time() - $createdAt > $expireSeconds) error('Web登录Token已过期');

    $playerName = $tokenRow['player_name'];

    // 检查是否已注册
    if (isPlayerRegistered($playerName)) {
        error('玩家已注册: ' . $playerName);
    }

    // 生成密码凭证（与插件相同的SHA-256算法）
    $saltBytes = random_bytes(16);
    $salt = base64_encode($saltBytes);
    $passwordHash = base64_encode(hash('sha256', $saltBytes . $password, true));

    // ★ 创建web_register_requests表记录（等待插件同步）
    $db->exec("CREATE TABLE IF NOT EXISTS web_register_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        salt TEXT NOT NULL,
        email TEXT DEFAULT '',
        ip_address TEXT DEFAULT '',
        created_at INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        processed_at INTEGER DEFAULT 0
    )");

    // 插入注册请求
    $stmt = $db->prepare("INSERT INTO web_register_requests (player_name, password_hash, salt, email, ip_address, created_at) VALUES (:player, :hash, :salt, :email, :ip, :time)");
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt->bindValue(':hash', $passwordHash, SQLITE3_TEXT);
    $stmt->bindValue(':salt', $salt, SQLITE3_TEXT);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->bindValue(':ip', $ipAddress, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    $requestId = $db->lastInsertRowID();

    success([
        'player' => $playerName,
        'request_id' => $requestId,
        'email' => $email
    ], '注册请求已提交，等待游戏同步');
}

// ===== 查询账号信息 =====
function registerQuery($token) {
    $player = getParam('player');
    if (!$player) error('缺少player');

    $db = getDB();
    // 只查询users表实际存在的列
    $stmt = $db->prepare("SELECT player_name, register_time, last_login_time, email, points, gift_stage, total_online_time FROM users WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('账号不存在: ' . $player);
    }

    // 处理Java插件推送的毫秒级时间戳，转换为秒级
    if ($row['register_time'] && $row['register_time'] > 1000000000000) {
        $row['register_time'] = (int)($row['register_time'] / 1000);
    }
    if ($row['last_login_time'] && $row['last_login_time'] > 1000000000000) {
        $row['last_login_time'] = (int)($row['last_login_time'] / 1000);
    }

    // ★ 获取债券余额
    $bonds = 0;
    try {
        $bondStmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
        $bondStmt->bindValue(':name', $player, SQLITE3_TEXT);
        $bondResult = $bondStmt->execute();
        $bondRow = $bondResult->fetchArray(SQLITE3_ASSOC);
        if ($bondRow) $bonds = (int)($bondRow['amount'] ?? 0);
    } catch (Exception $e) {}

    // ★ 获取累计在线天数（不重复日期计数）
    $totalOnlineDays = 0;
    try {
        $odStmt = $db->prepare("SELECT COUNT(DISTINCT login_date) as cnt FROM player_daily_logins WHERE player_name = :name");
        $odStmt->bindValue(':name', $player, SQLITE3_TEXT);
        $odResult = $odStmt->execute();
        $odRow = $odResult->fetchArray(SQLITE3_ASSOC);
        if ($odRow) $totalOnlineDays = (int)($odRow['cnt'] ?? 0);
    } catch (Exception $e) {}

    // ★ 获取累计签到天数
    $totalCheckinDays = 0;
    try {
        $ciStmt = $db->prepare("SELECT COUNT(DISTINCT checkin_date) as cnt FROM player_checkins WHERE player_name = :name");
        $ciStmt->bindValue(':name', $player, SQLITE3_TEXT);
        $ciResult = $ciStmt->execute();
        $ciRow = $ciResult->fetchArray(SQLITE3_ASSOC);
        if ($ciRow) $totalCheckinDays = (int)($ciRow['cnt'] ?? 0);
    } catch (Exception $e) {}

    // 合并额外数据
    $row['bonds'] = $bonds;
    $row['total_online_days'] = $totalOnlineDays;
    $row['total_checkin_days'] = $totalCheckinDays;

    // 邮箱脱敏
    if (!empty($row['email'])) {
        $parts = explode('@', $row['email']);
        if (count($parts) === 2) {
            $row['masked_email'] = str_repeat('*', min(strlen($parts[0]), 3)) . '@' . $parts[1];
        } else {
            $row['masked_email'] = '***';
        }
    } else {
        $row['masked_email'] = '';
    }

    // ★ 严格校验：必须有有效token且token对应的玩家必须与查询的玩家匹配（除非是管理员token）
    if (!$token) {
        // 无token：隐藏敏感信息
        $previewRow = [
            'player_name' => $row['player_name'],
            'register_time' => $row['register_time'],
            'last_login_time' => $row['last_login_time'],
            'email' => '',
            'masked_email' => '',
            'points' => 0,
            'gift_stage' => 0,
            'total_online_time' => 0,
            'bonds' => 0,
            'total_online_days' => 0,
            'total_checkin_days' => 0
        ];
        preview($previewRow, '预览模式 - 部分信息已隐藏');
        return;
    }

    $tokenInfo = validateTokenSilent($token);
    if (!$tokenInfo) {
        error('无效token，请先登录', 401);
    }

    $tokenPurpose = $tokenInfo['purpose'] ?? '';

    if ($tokenPurpose === 'admin' || $tokenPurpose === 'all') {
        // 管理员token：返回完整信息
        success($row);
    } elseif ($tokenInfo['player'] === $player) {
        // 普通token：只能查自己的完整信息
        success($row);
    } else {
        // 不是自己的账号，返回脱敏信息
        $maskedRow = [
            'player_name' => $row['player_name'],
            'register_time' => $row['register_time'],
            'last_login_time' => $row['last_login_time'],
            'email' => '',
            'masked_email' => '***',
            'points' => 0,
            'gift_stage' => 0,
            'total_online_time' => 0,
            'bonds' => 0,
            'total_online_days' => 0,
            'total_checkin_days' => 0
        ];
        preview($maskedRow, '预览模式 - 您只能查看自己的账号信息');
    }
}
