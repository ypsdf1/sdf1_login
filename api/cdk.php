<?php
/**
 * CDK兑换API v2 - 20260620
 * POST ?action=exchange  - CDK兑换（需token，由插件请求）
 * POST ?action=create    - 创建CDK（需管理token）
 * GET  ?action=list      - CDK列表（需管理token）
 * POST ?action=delete    - 删除CDK（需管理token）
 * POST ?action=batch     - 批量生成CDK（需管理token）
 */
// 防止任何输出污染JSON响应
while (ob_get_level() > 0) { ob_end_clean(); }
require_once __DIR__ . '/../core.php';
// 再次确保清理前置输出
while (ob_get_level() > 0) { ob_end_clean(); }

$action = getParam('action', 'exchange');
$token = getParam('token');

try {
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
} catch (\Throwable $e) {
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => ['code' => 500, 'message' => 'Internal error: ' . $e->getMessage()]], JSON_UNESCAPED_UNICODE);
    exit;
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
    $validated = false;

    if ($token) {
        // 先检查是否为普通管理token
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            $validated = true;
        } else {
            // weblogin token → 走双保险验证
            $accessResult = validateWebAccess($token, 'cdk', $password, $ipAddress);
            if ($accessResult['ok'] || $accessResult['mode'] === 'full_verified') {
                $isPreview = false;
                $validated = true;
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

    // 如果没有token也不是管理token，返回预览
    if (!$validated) {
        $isPreview = true;
    }

    $db = getDB();

    // 查询CDK
    $stmt = $db->prepare("SELECT * FROM cdk WHERE code = :code");
    $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
    $result = $stmt->execute();
    $cdk = $result->fetchArray(SQLITE3_ASSOC);

    if (!$cdk) {
        // ★ 本地没找到 → 写待验证请求，轮询Java Sdf1_login返回结果
        $cdk = tryRemoteCdkValidation($db, $code, $player);
        if (!$cdk) {
            error('CDK不存在: ' . $code);
        }
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
    $isRemote = !empty($cdk['_remote']);

    // ★ 全部DB操作包装在事务中，防止database is locked
    $db->exec('BEGIN IMMEDIATE');
    try {
        // 标记CDK已使用（仅本地CDK需要）
        if (!$isRemote) {
            $stmt = $db->prepare("UPDATE cdk SET used = 1, used_by = :player, used_at = :time WHERE code = :code");
            $stmt->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
            $stmt->execute();
        }

        // ★ 远程CDK：Java端(cdkManager.redeem)已处理加债券，Web端只做记录，不再加bond_cache
        if (!$isRemote) {
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

            // 记录流水 + sync_requests（仅本地CDK需要，远程CDK由Java记录流水）
            $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'cdk_redeem', :amount, :reason, :detail, 'pending', :time)");
            $stmt->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt->bindValue(':amount', $cdk['amount'], SQLITE3_INTEGER);
            $stmt->bindValue(':reason', "CDK兑换: {$code}", SQLITE3_TEXT);
            $stmt->bindValue(':detail', json_encode(['code' => $code, 'remote' => false], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
            $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt->execute();

            $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
            $stmt2 = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
            $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt2->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt2->execute();
        } else {
            // 远程CDK：PHP也更新bond_cache + 写pending交易，与本地兑换逻辑一致
            // 注意：远程CDK已在sdf1侧标记used，PHP只需要同步债券
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

            // 写pending交易，Java端pullPendingTransactions拉取后通知sdf1加券
            $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'cdk_redeem', :amount, :reason, :detail, 'pending', :time)");
            $stmt->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt->bindValue(':amount', $cdk['amount'], SQLITE3_INTEGER);
            $stmt->bindValue(':reason', "CDK远程兑换: {$code}", SQLITE3_TEXT);
            $stmt->bindValue(':detail', json_encode(['code' => $code, 'remote' => true], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
            $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt->execute();

            $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
            $stmt2 = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
            $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt2->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt2->execute();
        }

        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
        @error_log("[cdk] 兑换事务失败: " . $e->getMessage());
    }

    success([
        'code' => $code,
        'amount' => $cdk['amount'],
        'player' => $player,
        'balance_before' => $currentBalance,
        'balance_after' => $newBalance
    ], 'CDK兑换成功');
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
    } catch (\Throwable $e) {
        return false;
    }
}

// ===== 远程CDK验证：写请求表，轮询Java Sdf1_login结果 =====
function tryRemoteCdkValidation($db, $code, $player = '') {
    $db->exec("CREATE TABLE IF NOT EXISTS cdk_validate_requests (
        request_id TEXT PRIMARY KEY,
        code TEXT NOT NULL,
        player_name TEXT DEFAULT '',
        status TEXT DEFAULT 'pending',
        created_at INTEGER NOT NULL
    )");
    // ★ 迁移：给已有表补 player_name 列
    $cols = [];
    $colResult = $db->query("PRAGMA table_info(cdk_validate_requests)");
    while ($col = $colResult->fetchArray(SQLITE3_ASSOC)) { $cols[] = $col['name']; }
    if (!in_array('player_name', $cols)) {
        $db->exec("ALTER TABLE cdk_validate_requests ADD COLUMN player_name TEXT DEFAULT ''");
    }
    $db->exec("CREATE TABLE IF NOT EXISTS cdk_validate_results (
        request_id TEXT PRIMARY KEY,
        code TEXT NOT NULL,
        status TEXT NOT NULL,
        amount INTEGER DEFAULT 0,
        message TEXT DEFAULT '',
        used INTEGER DEFAULT 0,
        used_by TEXT DEFAULT '',
        created_at INTEGER NOT NULL
    )");

    $requestId = strtoupper(bin2hex(random_bytes(8)));
    $now = time();

    // ★ 写入待验证请求 — 用BEGIN IMMEDIATE防止database is locked
    try {
        $db->exec('BEGIN IMMEDIATE');
        $stmt = $db->prepare("INSERT OR REPLACE INTO cdk_validate_requests (request_id, code, player_name, status, created_at) VALUES (:id, :code, :player, 'pending', :time)");
        $stmt->bindValue(':id', $requestId, SQLITE3_TEXT);
        $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        $db->exec('ROLLBACK');
        @error_log("[cdk] 远程验证写入失败: {$e->getMessage()}");
        return null;
    }

    @error_log("[cdk] 远程验证请求: {$requestId} code={$code} player={$player}");

    // ★ 轮询结果（最多8秒，每300ms一次）
    $result = null;
    for ($i = 0; $i < 27; $i++) {
        usleep(300000); // 300ms
        $stmt = $db->prepare("SELECT * FROM cdk_validate_results WHERE request_id = :id");
        $stmt->bindValue(':id', $requestId, SQLITE3_TEXT);
        $rs = $stmt->execute();
        $result = $rs->fetchArray(SQLITE3_ASSOC);
        if ($result) break;
    }

    // 清理请求和结果
    try {
        $db->exec('BEGIN IMMEDIATE');
        $db->exec("DELETE FROM cdk_validate_requests WHERE request_id = '" . SQLite3::escapeString($requestId) . "'");
        if ($result) {
            $db->exec("DELETE FROM cdk_validate_results WHERE request_id = '" . SQLite3::escapeString($requestId) . "'");
        }
        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        $db->exec('ROLLBACK');
    }

    if (!$result) {
        @error_log("[cdk] 远程验证超时: {$requestId}");
        return null;
    }

    @error_log("[cdk] 远程验证结果: status={$result['status']} amount={$result['amount']}");

    if ($result['status'] === 'success') {
        // ★ Java端已标记used并加债券，Web端只做记录不重复加钱
        return [
            'code' => $result['code'],
            'amount' => (int)$result['amount'],
            'type' => 'bond',
            'used' => 0,
            'used_by' => '',
            'created_at' => $now,
            'used_at' => 0,
            '_remote' => true
        ];
    }

    return null;
}
