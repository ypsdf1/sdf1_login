<?php
/**
 * CDK兑换API
 * POST ?action=exchange  - CDK兑换（需token，由插件请求）
 * POST ?action=create    - 创建CDK（需管理token）
 * GET  ?action=list      - CDK列表（需管理token）
 * POST ?action=delete    - 删除CDK（需管理token）
 * POST ?action=batch     - 批量生成CDK（需管理token）
 */
// 防止任何输出污染JSON响应
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

$action = getParam('action', 'exchange');
$token = getParam('token');

switch ($action) {
    case 'exchange':
        cdkExchange($token);
        break;
    case 'create':
        cdkCreate($token);
        break;
    case 'list':
        cdkList($token);
        break;
    case 'delete':
        cdkDelete($token);
        break;
    case 'batch':
        cdkBatch($token);
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== CDK兑换 =====
function cdkExchange($token) {
    $code = getParam('code');
    $player = getParam('player');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$code) error('缺少code');
    if (!$player) error('缺少player');

    // ★ 双保险验证：CDK兑换需要密码确认
    $isPreview = true;

    if ($token) {
        // 先检查是否为普通管理token
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            if (validateToken($token)) {
                validateAndUseToken($token);
            }
        } else {
            // weblogin token → 走双保险验证
            $accessResult = validateWebAccess($token, 'cdk', $password, $ipAddress);
            if ($accessResult['ok']) {
                $isPreview = false;
                $player = $accessResult['player'];
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

    $db = getDB();

    // 查询CDK
    $stmt = $db->prepare("SELECT * FROM cdk WHERE code = :code");
    $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
    $result = $stmt->execute();
    $cdk = $result->fetchArray(SQLITE3_ASSOC);

    if (!$cdk) {
        error('CDK不存在: ' . $code);
    }

    if ($cdk['used'] == 1) {
        error('CDK已被使用: ' . $cdk['used_by']);
    }

    // 查询当前余额
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (
        player_name TEXT PRIMARY KEY,
        amount INTEGER DEFAULT 0,
        updated_at INTEGER DEFAULT 0
    )");

    $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
    $stmt->bindValue(':name', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    $currentBalance = $row ? $row['amount'] : 0;
    $newBalance = $currentBalance + $cdk['amount'];

    if ($isPreview) {
        preview([
            'code' => $code,
            'amount' => $cdk['amount'],
            'type' => $cdk['type'],
            'player' => $player,
            'balance_before' => $currentBalance,
            'balance_after' => $newBalance,
            'valid' => true
        ], '预览模式 - CDK兑换不会生效');
    }

    // 执行兑换
    $now = time();

    // 标记CDK已使用
    $stmt = $db->prepare("UPDATE cdk SET used = 1, used_by = :player, used_at = :time WHERE code = :code");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
    $stmt->execute();

    // 更新余额
    if ($row) {
        $stmt = $db->prepare("UPDATE bond_cache SET amount = :amount, updated_at = :time WHERE player_name = :name");
        $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->execute();
    } else {
        $stmt = $db->prepare("INSERT INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $newBalance, SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
    }

    // 记录流水
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'cdk_redeem', :amount, :reason, :detail, 'pending', :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $cdk['amount'], SQLITE3_INTEGER);
    $stmt->bindValue(':reason', "CDK兑换: {$code}", SQLITE3_TEXT);
    $stmt->bindValue(':detail', json_encode(['code' => $code], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->execute();

    success([
        'code' => $code,
        'amount' => $cdk['amount'],
        'player' => $player,
        'balance_before' => $currentBalance,
        'balance_after' => $newBalance
    ], 'CDK兑换成功');

    // ★ 立即通知Java插件拉取交易
    $notifyUrl = "http://127.0.0.1:" . CALLBACK_PORT . "/api/notify_sync?secret=" . SECRET_KEY;
    @file_get_contents($notifyUrl, false, stream_context_create(['http' => ['method' => 'POST', 'timeout' => 2]]));
    $baseUrl = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http') . '://' . $_SERVER['HTTP_HOST'] . WEBSUB_DIR;
    $notifyUrl2 = $baseUrl . "/api/sync.php?action=notify_sync&secret=" . SECRET_KEY;
    @file_get_contents($notifyUrl2, false, stream_context_create(['http' => ['method' => 'POST', 'timeout' => 2]]));
}

// ===== 创建CDK =====
function cdkCreate($token) {
    $amount = (int)getParam('amount', 0);
    $type = getParam('type', 'bond');
    $code = getParam('code');

    if ($amount <= 0) error('金额必须大于0');

    // 需要管理token
    $tokenInfo = null;
    $isPreview = true;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            validateAndUseToken($token);
        }
    }

    if (!$code) {
        $code = strtoupper(bin2hex(random_bytes(4))); // 8位随机码
    }

    $db = getDB();
    $now = time();

    // 检查是否已存在
    $stmt = $db->prepare("SELECT 1 FROM cdk WHERE code = :code");
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $result = $stmt->execute();
    if ($result->fetchArray()) {
        error('CDK已存在: ' . $code);
    }

    if ($isPreview) {
        preview([
            'code' => $code,
            'amount' => $amount,
            'type' => $type
        ], '预览模式 - CDK创建不会生效');
    }

    $stmt = $db->prepare("INSERT INTO cdk (code, amount, type, created_at) VALUES (:code, :amount, :type, :time)");
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
    $stmt->bindValue(':type', $type, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->execute();

    success(['code' => $code, 'amount' => $amount, 'type' => $type], 'CDK创建成功');
}

// ===== CDK列表 =====
function cdkList($token) {
    $tokenInfo = null;
    $isPreview = true;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
        }
    }

    $db = getDB();
    $result = $db->query("SELECT code, amount, type, used, used_by, created_at, used_at FROM cdk ORDER BY created_at DESC LIMIT 200");

    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }

    if ($isPreview) {
        preview($list, '预览模式');
    } else {
        success($list);
    }
}

// ===== 删除CDK =====
function cdkDelete($token) {
    $code = getParam('code');
    if (!$code) error('缺少code');

    $tokenInfo = null;
    $isPreview = true;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            validateAndUseToken($token);
        }
    }

    $db = getDB();

    if ($isPreview) {
        preview(['code' => $code], '预览模式 - 删除不会生效');
    }

    $stmt = $db->prepare("DELETE FROM cdk WHERE code = :code");
    $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
    $stmt->execute();

    success(['code' => $code], 'CDK已删除');
}

// ===== 批量生成CDK =====
function cdkBatch($token) {
    $amount = (int)getParam('amount', 0);
    $count = (int)getParam('count', 1);
    $type = getParam('type', 'bond');

    if ($amount <= 0) error('金额必须大于0');
    if ($count < 1 || $count > 100) error('数量范围: 1-100');

    $tokenInfo = null;
    $isPreview = true;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            validateAndUseToken($token);
        }
    }

    $db = getDB();
    $now = time();
    $codes = [];

    for ($i = 0; $i < $count; $i++) {
        $code = strtoupper(bin2hex(random_bytes(4)));
        $codes[] = $code;

        if (!$isPreview) {
            $stmt = $db->prepare("INSERT INTO cdk (code, amount, type, created_at) VALUES (:code, :amount, :type, :time)");
            $stmt->bindValue(':code', $code, SQLITE3_TEXT);
            $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
            $stmt->bindValue(':type', $type, SQLITE3_TEXT);
            $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt->execute();
        }
    }

    if ($isPreview) {
        preview([
            'codes' => $codes,
            'amount' => $amount,
            'count' => $count
        ], '预览模式 - 批量创建不会生效');
    } else {
        success([
            'codes' => $codes,
            'amount' => $amount,
            'count' => $count
        ], "成功创建{$count}个CDK");
    }
}

// ===== Weblogin Token验证（用于CDK兑换）=====
function validateWebloginTokenForCDK($webToken) {
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
