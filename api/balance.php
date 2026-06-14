<?php
/**
 * 余额查询+充值API
 * GET  ?action=query        - 查询余额（需token）
 * POST ?action=recharge     - 充值债券（需token）
 * GET  ?action=transactions - 查看流水（需token）
 */
// 防止任何输出污染JSON响应
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

$action = getParam('action', 'query');
$token = getParam('token');

switch ($action) {
    case 'query':
        balanceQuery($token);
        break;
    case 'recharge':
        balanceRecharge($token);
        break;
    case 'transactions':
        balanceTransactions($token);
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== 查询余额 =====
function balanceQuery($token) {
    $player = getParam('player');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    if (!$player) error('缺少player');

    // ★ 安全验证：余额查询只需token验证即可（view级别）
    $tokenInfo = null;

    // 1. 检查是否为管理后台session
    if (session_status() === PHP_SESSION_NONE) session_start();
    if (isset($_SESSION['admin_auth']) && $_SESSION['admin_auth']) {
        $tokenInfo = ['player' => $player, 'purpose' => 'admin'];
    } elseif ($token) {
        // 2. 检查token
        // 先检查是否为普通管理token
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            // admin token，直接通过
        } else {
            // weblogin token → 走安全验证（view级别，可快速重连）
            $accessResult = validateWebAccess($token, 'view', $password, $ipAddress);
            if ($accessResult['ok']) {
                $player = $accessResult['player'];
                $tokenInfo = ['player' => $player, 'purpose' => 'weblogin'];
            } elseif ($accessResult['mode'] === 'need_password') {
                jsonResponse([
                    'success' => false,
                    'need_password' => true,
                    'player' => $accessResult['player'],
                    'message' => $accessResult['message']
                ], 401);
            } else {
                error($accessResult['message'], 401);
            }
        }
    }

    if (!$tokenInfo) {
        error('查询余额需要有效token', 401);
    }

    // 权限检查：只能查自己的余额，除非是管理员
    if ($tokenInfo['player'] !== $player && $tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all') {
        error('无权查询其他玩家余额');
    }

    // 查询债券余额（从Web端的债券缓存表）
    $db = getDB();

    // 先检查是否有缓存表
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (
        player_name TEXT PRIMARY KEY,
        amount INTEGER DEFAULT 0,
        updated_at INTEGER DEFAULT 0
    )");

    $stmt = $db->prepare("SELECT amount, updated_at FROM bond_cache WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    // 同时查询积分
    $stmt2 = $db->prepare("SELECT points FROM users WHERE player_name = :name");
    $stmt2->bindValue(':name', $player, SQLITE3_TEXT);
    $result2 = $stmt2->execute();
    $row2 = $result2->fetchArray(SQLITE3_ASSOC);

    $bonds = $row ? $row['amount'] : 0;
    $points = $row2 ? $row2['points'] : 0;
    $bondsUpdated = $row ? $row['updated_at'] : 0;

    // 计算数据新鲜度
    $freshness = '';
    if ($bondsUpdated > 0) {
        $age = time() - $bondsUpdated;
        if ($age < 300) $freshness = '刚刚同步';
        elseif ($age < 3600) $freshness = floor($age / 60) . '分钟前同步';
        elseif ($age < 86400) $freshness = floor($age / 3600) . '小时前同步';
        else $freshness = floor($age / 86400) . '天前同步（数据可能过期）';
    } else {
        $freshness = '尚未同步';
    }

    success([
        'player' => $player,
        'bonds' => $bonds,
        'points' => $points,
        'bonds_updated' => $bondsUpdated,
        'freshness' => $freshness
    ]);
}

// ===== 充值债券 =====
function balanceRecharge($token) {
    $player = getParam('player');
    $amount = (int)getParam('amount', 0);
    $operator = getParam('operator', 'web_admin');

    if (!$player) error('缺少player');
    if ($amount <= 0) error('充值金额必须大于0');

    // 需要token
    $tokenInfo = null;
    $isPreview = true;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo) {
            $isPreview = false;
            // 权限检查：只有管理员token才能充值
            if ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all') {
                error('充值需要管理员权限');
            }
            validateAndUseToken($token);
        }
    }

    $db = getDB();

    // 确保缓存表存在
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (
        player_name TEXT PRIMARY KEY,
        amount INTEGER DEFAULT 0,
        updated_at INTEGER DEFAULT 0
    )");

    // 查询当前余额
    $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    $currentBalance = $row ? $row['amount'] : 0;
    $newBalance = $currentBalance + $amount;

    if ($isPreview) {
        preview([
            'player' => $player,
            'amount' => $amount,
            'balance_before' => $currentBalance,
            'balance_after' => $newBalance,
            'operator' => $operator
        ], '预览模式 - 充值不会生效');
    }

    // 执行充值
    if ($row) {
        $stmt = $db->prepare("UPDATE bond_cache SET amount = :amount, updated_at = :time WHERE player_name = :name");
        $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->execute();
    } else {
        $stmt = $db->prepare("INSERT INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
    }

    // 记录流水
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, status, created_at) VALUES (:player, 'admin_recharge', :amount, :reason, 'pending', :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
    $stmt->bindValue(':reason', "管理员充值: {$operator}", SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    success([
        'player' => $player,
        'amount' => $amount,
        'balance_before' => $currentBalance,
        'balance_after' => $newBalance
    ], '充值成功');
}

// ===== 查看流水 =====
function balanceTransactions($token) {
    $player = getParam('player');
    $limit = (int)getParam('limit', 50);

    // 需要token
    $tokenInfo = null;
    if ($token) {
        $tokenInfo = validateToken($token);
    }

    if (!$tokenInfo) {
        error('查看流水需要有效token', 401);
    }

    $db = getDB();

    if ($player) {
        // 查看指定玩家流水
        if ($tokenInfo['player'] !== $player && $tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all') {
            error('无权查看其他玩家流水');
        }
        $stmt = $db->prepare("SELECT * FROM web_transactions WHERE player_name = :player ORDER BY created_at DESC LIMIT :limit");
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    } else {
        // 管理员查看全服流水
        if ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all') {
            error('查看全服流水需要管理员权限');
        }
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

// ===== Weblogin Token验证（用于API调用） =====
function validateWebloginTokenForAPI($webToken) {
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
        $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if (!$row) return false;

        $createdAt = (int)$row['created_at'];
        $expireSeconds = (int)$row['expire_seconds'];
        if (time() - $createdAt > $expireSeconds) return false;

        return [
            'player' => $row['player_name'],
            'purpose' => 'weblogin',
            'created_at' => $createdAt,
            'expires_at' => $createdAt + $expireSeconds
        ];
    } catch (Exception $e) {
        return false;
    }
}
