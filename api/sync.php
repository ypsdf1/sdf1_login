<?php
// ★ 同步API - 插件与Web端数据同步
// ★ Version: 2026-06-12-1940 - clear OPcache at top
// ★ 防止任何输出污染JSON响应
ob_start();
// 清除OPcache（如果可用）
if (function_exists('opcache_reset')) { @opcache_reset(); }
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
// ★ 再次确保清理前置输出
while (ob_get_level() > 0) {
    ob_end_clean();
}
ob_start();

require_once __DIR__ . '/../core.php';
// ★ 再次确保清理前置输出
while (ob_get_level() > 0) {
    ob_end_clean();
}
ob_start();

// debugLog() 函数已在 core.php 中定义，无需重复声明

// ===== SMTP邮件发送函数 =====
function smtpSendEmail($host, $port, $user, $pass, $to, $subject, $htmlBody, $headers, $useSSL = true) {
    $errno = 0;
    $errstr = '';

    // 创建socket连接
    if ($useSSL) {
        $socket = @fsockopen('ssl://' . $host, $port, $errno, $errstr, 30);
    } else {
        $socket = @fsockopen($host, $port, $errno, $errstr, 30);
    }

    if (!$socket) {
        throw new Exception("SMTP连接失败: $errstr ($errno)");
    }

    // 读取服务器响应
    $response = fgets($socket);
    if (strpos($response, '220') !== 0) {
        fclose($socket);
        throw new Exception("SMTP服务器拒绝连接: $response");
    }

    // 发送EHLO
    fwrite($socket, "EHLO localhost\r\n");
    $response = fread($socket, 1024);

    // 登录认证
    fwrite($socket, "AUTH LOGIN\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP认证失败: $response");
    }

    // 发送用户名
    fwrite($socket, base64_encode($user) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP用户名错误: $response");
    }

    // 发送密码
    fwrite($socket, base64_encode($pass) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '235') !== 0) {
        fclose($socket);
        throw new Exception("SMTP密码错误: $response");
    }

    // 发送邮件
    fwrite($socket, "MAIL FROM:<$user>\r\n");
    fgets($socket);

    fwrite($socket, "RCPT TO:<$to>\r\n");
    fgets($socket);

    fwrite($socket, "DATA\r\n");
    fgets($socket);

    // 发送邮件头和正文
    $emailData = "Subject: $subject\r\n";
    $emailData .= $headers;
    $emailData .= "\r\n";
    $emailData .= $htmlBody;
    $emailData .= "\r\n.\r\n";

    fwrite($socket, $emailData);
    $response = fgets($socket);

    // 退出
    fwrite($socket, "QUIT\r\n");
    fgets($socket);

    fclose($socket);

    return true;
}

$action = getParam('action', '');

switch ($action) {
    case 'sync_shop':
        syncShop();
        break;
    case 'sync_bonds':
        syncBonds();
        break;
    case 'sync_login':
        syncLogin();
        break;
    case 'sync_online_players':
        syncOnlinePlayers();
        break;
    case 'pull_shop':
        pullShop();
        break;
    case 'pull_bonds':
        pullBonds();
        break;
    case 'sync_weblogin_token':
        syncWebloginToken();
        break;
    case 'validate_weblogin_token':
        validateWebloginToken();
        break;
    case 'sync_token':
        syncToken();
        break;
    case 'receive_token':
        receiveToken();
        break;
    case 'check_web_login_confirmations':
        checkWebLoginConfirmations();
        break;
    case 'check_web_login_verified':
        checkWebLoginVerified();
        break;
    case 'web_login_request':
        webLoginRequest();
        break;
    case 'check_web_login_result':
        checkWebLoginResult();
        break;
    case 'check_pending_web_logins':
        checkPendingWebLogins();
        break;
    case 'complete_web_login_request':
        completeWebLoginRequest();
        break;
    case 'push_web_credentials':
        pushWebCredentials();
        break;
    case 'verify_web_password':
        verifyWebPassword();
        break;
    case 'web_access_check':
        webAccessCheck();
        break;
    case 'pull_pending_transactions':
        pullPendingTransactions();
        break;
    case 'confirm_transaction':
        confirmTransaction();
        break;
    case 'pull_shop_stock':
        pullShopStock();
        break;
    case 'notify_sync':
        notifySync();
        break;
    case 'send_email_code':
        sendEmailCode();
        break;
    case 'verify_email_code':
        verifyEmailCode();
        break;
    case 'fix_stock':
        fixStock();
        break;
    case 'fix_timestamps':
        fixTimestamps();
        break;
    case 'send_reset_password_link':
        sendResetPasswordLink();
        break;
    case 'reset_password':
        resetPassword();
        break;
    case 'admin_list_users':
        adminListUsers();
        break;
    case 'admin_send_reset_link':
        adminSendResetLink();
        break;
    case 'extend_web_token':
        extendWebToken();
        break;
    case 'change_password':
        changePassword();
        break;
    case 'check_pending_web_register_requests':
        checkPendingWebRegisterRequests();
        break;
    case 'complete_web_register_request':
        completeWebRegisterRequest();
        break;
    case 'delete_user':
        deleteUser();
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== 插件推送商品数据 =====
function syncShop() {
    // 验证管理token
    $token = getParam('token');
    if (!$token) error('同步需要token');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
        error('同步需要管理权限token');
    }

    $items = getParam('items');
    if (!$items || !is_array($items)) {
        error('缺少items数据');
    }

    $db = getDB();
    $now = time();
    $count = 0;

    foreach ($items as $item) {
        $id = $item['id'] ?? null;
        if (!$id) continue;

        // 游戏端stock=0表示无限供应，Web端用-1表示无限
        $stock = (int)($item['stock'] ?? -1);
        if ($stock == 0) $stock = -1;  // 0 → 无限

        $stmt = $db->prepare("INSERT OR REPLACE INTO shop_items (id, category, display_name, material, buy_price, sell_price, stock, hourly_sales, total_sales, last_sync) VALUES (:id, :cat, :name, :mat, :bp, :sp, :st, :hs, :ts, :time)");
        $stmt->bindValue(':id', $id, SQLITE3_TEXT);
        $stmt->bindValue(':cat', $item['category'] ?? '默认', SQLITE3_TEXT);
        $stmt->bindValue(':name', $item['display_name'] ?? $id, SQLITE3_TEXT);
        $stmt->bindValue(':mat', $item['material'] ?? 'STONE', SQLITE3_TEXT);
        $stmt->bindValue(':bp', (int)($item['buy_price'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':sp', (int)($item['sell_price'] ?? -1), SQLITE3_INTEGER);
        $stmt->bindValue(':st', $stock, SQLITE3_INTEGER);
        $stmt->bindValue(':hs', (int)($item['hourly_sales'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ts', (int)($item['total_sales'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    success(['synced' => $count], "同步了{$count}个商品");

    // 一次性修复：将数据库中所有stock=0改为-1（无限供应）
    $fixResult = $db->exec("UPDATE shop_items SET stock = -1 WHERE stock = 0");
    if ($fixResult !== false) {
        // 静默修复，不影响正常流程
    }
}

// ===== 插件推送债券余额 =====
function syncBonds() {
    $token = getParam('token');
    if (!$token) error('同步需要token');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
        error('同步需要管理权限token');
    }

    $bonds = getParam('bonds');
    if (!$bonds || !is_array($bonds)) {
        error('缺少bonds数据');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $now = time();
    $count = 0;

    foreach ($bonds as $player => $amount) {
        $stmt = $db->prepare("INSERT OR REPLACE INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', (int)$amount, SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    success(['synced' => $count], "同步了{$count}个玩家余额");
}

// ===== 插件推送注册数据 =====
function syncLogin() {
    $token = getParam('token');
    if (!$token) error('同步需要token');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
        error('同步需要管理权限token');
    }

    $users = getParam('users');
    if (!$users || !is_array($users)) {
        error('缺少users数据');
    }

    // ★ 调试日志：记录插件推送的用户数据
    debugLog("sync_login: 插件推送用户数据", [
        'user_count' => count($users)
    ]);

    $db = getDB();
    $now = time();
    $count = 0;

    foreach ($users as $user) {
        $name = $user['player_name'] ?? null;
        if (!$name) continue;

        // Java端使用毫秒时间戳，PHP端存储秒级时间戳
        $registerTime = (int)($user['register_time'] ?? 0);
        $lastLoginTime = (int)($user['last_login_time'] ?? 0);
        if ($registerTime > 1000000000000) { // 如果大于10^12，说明是毫秒级
            $registerTime = intval($registerTime / 1000);
        }
        if ($lastLoginTime > 1000000000000) { // 如果大于10^12，说明是毫秒级
            $lastLoginTime = intval($lastLoginTime / 1000);
        }

        $stmt = $db->prepare("INSERT OR REPLACE INTO users (player_name, register_time, last_login_time, email, points, gift_stage, total_online_time) VALUES (:name, :rt, :lt, :email, :pts, :gs, :tot)");
        $stmt->bindValue(':name', $name, SQLITE3_TEXT);
        $stmt->bindValue(':rt', $registerTime, SQLITE3_INTEGER);
        $stmt->bindValue(':lt', $lastLoginTime, SQLITE3_INTEGER);
        $stmt->bindValue(':email', $user['email'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':pts', (int)($user['points'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':gs', (int)($user['gift_stage'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':tot', (int)($user['total_online_time'] ?? 0), SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    // ★ 收集推送的用户名列表，用于后续孤儿清理
    $namesInSync = array_map(function($u) { return $u['player_name'] ?? null; }, $users);
    $namesInSync = array_filter($namesInSync, function($n) { return $n !== null; });

    // ★ 清理 users 表中已不存在的用户（游戏内删号后同步）
    try {
        $stmt = $db->prepare("SELECT player_name FROM users");
        $result = $stmt->execute();
        $phpUsers = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $phpUsers[] = $row['player_name'];
        }
        
        $orphanedUsers = array_diff($phpUsers, $namesInSync);
        if (!empty($orphanedUsers)) {
            $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphanedUsers));
            $db->exec("DELETE FROM users WHERE player_name IN ('" . $nameList . "')");
            debugLog("sync_login: 清理了 " . count($orphanedUsers) . " 个孤立的 users 记录", ['orphaned' => $orphanedUsers]);
        }
    } catch (Exception $e) {
        debugLog("sync_login: 清理孤立 users 记录失败", ['error' => $e->getMessage()]);
    }

    // ★ 清理 weblogin_credentials 中已不存在的用户（游戏内删号后同步）
    try {
        $stmt = $db->prepare("SELECT player_name FROM weblogin_credentials");
        $result = $stmt->execute();
        $webCredentials = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $webCredentials[] = $row['player_name'];
        }
        
        $namesInSync = array_map(function($u) { return $u['player_name'] ?? null; }, $users);
        $orphaned = array_diff($webCredentials, $namesInSync);
        
        if (!empty($orphaned)) {
            $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphaned));
            $db->exec("DELETE FROM weblogin_credentials WHERE player_name IN ('" . $nameList . "')");
            debugLog("sync_login: 清理了 " . count($orphaned) . " 个孤立的 weblogin_credentials 记录", ['orphaned' => $orphaned]);
        }
    } catch (Exception $e) {
        debugLog("sync_login: 清理孤立 weblogin_credentials 记录失败", ['error' => $e->getMessage()]);
    }

    // ★ 清理 weblogin_tokens 中已不存在的用户（游戏内删号后同步）
    try {
        $stmt = $db->prepare("SELECT player_name FROM weblogin_tokens");
        $result = $stmt->execute();
        $tokenPlayers = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $tokenPlayers[] = $row['player_name'];
        }
        
        $orphanedTokens = array_diff($tokenPlayers, $namesInSync);
        if (!empty($orphanedTokens)) {
            $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphanedTokens));
            $db->exec("DELETE FROM weblogin_tokens WHERE player_name IN ('" . $nameList . "')");
            debugLog("sync_login: 清理了 " . count($orphanedTokens) . " 个孤立的 weblogin_tokens 记录", ['orphaned' => $orphanedTokens]);
        }
    } catch (Exception $e) {
        debugLog("sync_login: 清理孤立 weblogin_tokens 记录失败", ['error' => $e->getMessage()]);
    }

    // ★ 清理 web_login_verified 中已不存在的用户
    try {
        $stmt = $db->prepare("SELECT player_name FROM web_login_verified");
        $result = $stmt->execute();
        $verifiedPlayers = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $verifiedPlayers[] = $row['player_name'];
        }
        
        $orphanedVerified = array_diff($verifiedPlayers, $namesInSync);
        if (!empty($orphanedVerified)) {
            $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphanedVerified));
            $db->exec("DELETE FROM web_login_verified WHERE player_name IN ('" . $nameList . "')");
            debugLog("sync_login: 清理了 " . count($orphanedVerified) . " 个孤立的 web_login_verified 记录", ['orphaned' => $orphanedVerified]);
        }
    } catch (Exception $e) {
        debugLog("sync_login: 清理孤立 web_login_verified 记录失败", ['error' => $e->getMessage()]);
    }

    // ★ 清理 web_session_log 中已不存在的用户
    try {
        $stmt = $db->prepare("SELECT player_name FROM web_session_log");
        $result = $stmt->execute();
        $sessionPlayers = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $sessionPlayers[] = $row['player_name'];
        }
        
        $orphanedSessions = array_diff($sessionPlayers, $namesInSync);
        if (!empty($orphanedSessions)) {
            $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphanedSessions));
            $db->exec("DELETE FROM web_session_log WHERE player_name IN ('" . $nameList . "')");
            debugLog("sync_login: 清理了 " . count($orphanedSessions) . " 个孤立的 web_session_log 记录", ['orphaned' => $orphanedSessions]);
        }
    } catch (Exception $e) {
        debugLog("sync_login: 清理孤立 web_session_log 记录失败", ['error' => $e->getMessage()]);
    }

    debugLog("sync_login: 同步完成", ['synced_count' => $count, 'cleared_users' => ($orphanedUsers ?? []) ? count($orphanedUsers) : 0]);
    success(['synced' => $count], "同步了{$count}个用户");
}

// ===== Java插件主动删除用户 =====
function deleteUser() {
    $token = getParam('token');
    if (!$token) error('删除需要token');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
        error('删除需要管理权限token');
    }

    $playerName = getParam('player_name');
    if (!$playerName) error('缺少player_name');

    $db = getDB();

    // 清理所有相关表
    $tables = ['users', 'weblogin_credentials', 'weblogin_tokens', 'web_login_verified', 'web_session_log', 'bond_cache', 'email_codes', 'password_reset_requests', 'web_login_requests', 'web_register_requests'];
    $deleted = 0;
    foreach ($tables as $table) {
        try {
            $stmt = $db->prepare("DELETE FROM $table WHERE player_name = :player");
            $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $stmt->execute();
            $deleted++;
        } catch (Exception $e) {
            // 表可能不存在，跳过
        }
    }

    debugLog("deleteUser: 删除了用户 " . $playerName . " 及其 " . $deleted . " 个相关表的记录");
    success(['player' => $playerName], "已删除用户 " . $playerName . " 的所有数据");
}

// ===== 修复时间戳 =====
function fixTimestamps() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    
    // 检测并修复毫秒级时间戳
    // 正常秒级时间戳：10位（如1717900000）
    // 毫秒级时间戳：13位（如1717900000000）
    $threshold = 1000000000000; // 10^12
    
    // 修复register_time
    $fixRegister = 0;
    $result = $db->query("SELECT player_name, register_time FROM users WHERE register_time > $threshold");
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $fixedTime = intval($row['register_time'] / 1000);
        $stmt = $db->prepare("UPDATE users SET register_time = :time WHERE player_name = :name");
        $stmt->bindValue(':time', $fixedTime, SQLITE3_INTEGER);
        $stmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
        $stmt->execute();
        $fixRegister++;
    }
    
    // 修复last_login_time
    $fixLogin = 0;
    $result = $db->query("SELECT player_name, last_login_time FROM users WHERE last_login_time > $threshold");
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $fixedTime = intval($row['last_login_time'] / 1000);
        $stmt = $db->prepare("UPDATE users SET last_login_time = :time WHERE player_name = :name");
        $stmt->bindValue(':time', $fixedTime, SQLITE3_INTEGER);
        $stmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
        $stmt->execute();
        $fixLogin++;
    }
    
    success([
        'fixed_register' => $fixRegister,
        'fixed_login' => $fixLogin,
        'total' => $fixRegister + $fixLogin
    ], "修复了{$fixRegister}条注册时间、{$fixLogin}条登录时间");
}

// ===== 插件同步在线玩家列表 =====
function syncOnlinePlayers() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $players = getParam('players');
    if (!$players || !is_array($players)) {
        error('缺少players数据');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER NOT NULL)");

    // 清空旧数据
    $db->exec("DELETE FROM online_players");

    // 插入新数据
    $now = time();
    foreach ($players as $player) {
        if (empty($player['name'])) continue;
        $stmt = $db->prepare("INSERT OR REPLACE INTO online_players (player_name, login_time) VALUES (:name, :time)");
        $stmt->bindValue(':name', $player['name'], SQLITE3_TEXT);
        $stmt->bindValue(':time', $player['login_time'] ?? $now, SQLITE3_INTEGER);
        $stmt->execute();
    }

    success(['synced' => count($players)], "同步了" . count($players) . "个在线玩家");
}

// ===== 插件拉取商品 =====
function pullShop() {
    $db = getDB();
    $result = $db->query("SELECT * FROM shop_items ORDER BY category, id");

    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = $row;
    }

    success($items);
}

// ===== 插件拉取债券修改 =====
function pullBonds() {
    $token = getParam('token');
    $secret = getParam('secret');
    
    // 支持两种认证方式：token或SECRET_KEY
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            error('拉取需要管理权限token');
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('认证失败');
    } else {
        error('缺少认证参数');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $result = $db->query("SELECT * FROM bond_cache ORDER BY updated_at DESC");

    $bonds = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $bonds[] = $row;
    }

    success($bonds);
}

// ===== 插件通过SECRET_KEY注册Token到PHP数据库 =====
function receiveToken() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $tokens = getParam('tokens');
    if (!$tokens || !is_array($tokens)) {
        error('缺少tokens数组');
    }

    $db = getDB();
    $now = time();
    $count = 0;

    foreach ($tokens as $t) {
        $tokenVal = $t['token'] ?? null;
        $player = $t['player'] ?? 'system';
        $purpose = $t['purpose'] ?? 'general';
        $expireSeconds = (int)($t['expire_seconds'] ?: 600);

        if (!$tokenVal) continue;

        $stmt = $db->prepare("INSERT OR IGNORE INTO tokens (token, player_name, purpose, created_at, expires_at, used) VALUES (:token, :player, :purpose, :created, :expires, 0)");
        $stmt->bindValue(':token', $tokenVal, SQLITE3_TEXT);
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
        $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    success(['registered' => $count], "注册了{$count}个Token");
}

// ===== 插件同步Token到PHP数据库（通过已有sync token） =====
function syncToken() {
    $token = getParam('token');
    if (!$token) error('同步需要token');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
        error('同步需要管理权限token');
    }

    $newToken = getParam('new_token');
    $player = getParam('player');
    $purpose = getParam('purpose', 'general');
    $expireSeconds = (int)(getParam('expire_seconds') ?: 600);

    if (!$newToken || !$player) {
        error('缺少new_token或player参数');
    }

    $db = getDB();
    $now = time();

    $stmt = $db->prepare("INSERT OR REPLACE INTO tokens (token, player_name, purpose, created_at, expires_at, used) VALUES (:token, :player, :purpose, :created, :expires, 0)");
    $stmt->bindValue(':token', $newToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
    $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    success(['token' => substr($newToken, 0, 8) . '...'], "Token已同步");
}

// ===== 插件推送Web登录Token =====
function syncWebloginToken() {
    // 支持两种认证方式：SECRET_KEY（插件直接调用）或 token（通用认证）
    $secret = getParam('secret');
    $token = getParam('token');

    if ($secret) {
        // SECRET_KEY认证（插件端直接调用，无需中间sync token）
        if ($secret !== SECRET_KEY) {
            error('密钥验证失败', 403);
        }
    } elseif ($token) {
        // Token认证（兼容旧方式）
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync' && $tokenInfo['purpose'] !== 'weblogin')) {
            error('同步需要管理权限token');
        }
    } else {
        error('缺少secret或token参数');
    }

    $player = getParam('player');
    $webToken = getParam('web_token');
    $expireSeconds = (int)(getParam('expire_seconds') ?: 600);

    if (!$player || !$webToken) {
        error('缺少player或web_token参数');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (player_name TEXT PRIMARY KEY, web_token TEXT NOT NULL, created_at INTEGER NOT NULL, expire_seconds INTEGER DEFAULT 600)");

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO weblogin_tokens (player_name, web_token, created_at, expire_seconds) VALUES (:player, :token, :time, :expire)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expire', $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    success(['player' => $player], "Web登录Token已同步");
}

// ===== Web端验证登录Token =====
function validateWebloginToken() {
    $webToken = getParam('web_token');
    if (!$webToken) error('缺少web_token参数');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (player_name TEXT PRIMARY KEY, web_token TEXT NOT NULL, created_at INTEGER NOT NULL, expire_seconds INTEGER DEFAULT 600)");

    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('无效的登录Token');
    }

    $createdAt = (int)$row['created_at'];
    $expireSeconds = (int)$row['expire_seconds'];
    $now = time();

    if ($now - $createdAt > $expireSeconds) {
        // Token已过期，删除
        $delStmt = $db->prepare("DELETE FROM weblogin_tokens WHERE web_token = :token");
        $delStmt->bindValue(':token', $webToken, SQLITE3_TEXT);
        $delStmt->execute();
        error('登录Token已过期');
    }

    $player = $row['player_name'];

    // ★ 不删除Token - 保持有效供后续API调用（player.php需要token鉴权）
    // Token会在过期时间后自动失效

    // ★ 不自动创建登录确认记录 - 等待用户在Web端输入密码验证通过后再创建
    // 登录确认记录会在verify_web_password端点中创建

    // 查询玩家信息（users表可能不存在，安全降级）
    $userData = ['player_name' => $player];
    try {
        $stmt2 = $db->prepare("SELECT * FROM users WHERE player_name = :player");
        $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
        $result2 = $stmt2->execute();
        $userRow = $result2->fetchArray(SQLITE3_ASSOC);
        if ($userRow) $userData = $userRow;
    } catch (Exception $e) {
        // users表不存在或查询失败，仅返回玩家名
    }

    success([
        'player' => $player,
        'user_data' => $userData
    ], "登录成功");
}

// ===== 插件轮询Web登录确认（SECRET_KEY认证）=====
// 插件定期调用此接口，获取已通过Web验证的玩家列表，用于自动登录游戏
// ★ 修复：增加时间窗口验证，防止旧记录导致误自动登录
function checkWebLoginConfirmations() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");

    // ★ 清理超过10分钟的过期确认记录
    $expireTime = time() - 600;
    $db->exec("DELETE FROM web_login_confirmations WHERE confirmed_at < " . $expireTime);

    // 获取所有未消费且未过期的确认记录
    $stmt = $db->prepare("SELECT player_name, confirmed_at FROM web_login_confirmations WHERE consumed = 0 AND confirmed_at >= :expire");
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $result = $stmt->execute();

    $confirmations = [];
    $playerNames = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $confirmations[] = $row;
        $playerNames[] = $row['player_name'];
    }

    // 标记为已消费（一次性）
    if (!empty($playerNames)) {
        $db->exec("UPDATE web_login_confirmations SET consumed = 1 WHERE consumed = 0 AND confirmed_at >= " . $expireTime);
        // 清理30分钟前的已消费记录
        $db->exec("DELETE FROM web_login_confirmations WHERE consumed = 1 AND confirmed_at < " . (time() - 1800));
    }

    success($confirmations);
}

// ===== 插件检查Web登录验证记录（SECRET_KEY认证）=====
// 玩家进游戏时调用，检查是否有通过Web验证待自动登录的玩家
// ★ 修复：增加时间窗口验证，防止旧记录导致误自动登录
function checkWebLoginVerified() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $playerName = getParam('player_name');
    debugLog("check_web_login_verified: 插件查询Web验证记录", ['player_name' => $playerName]);

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");

    // ★ 清理超过10分钟的过期验证记录（防止残留数据导致误登录）
    $expireTime = time() - 600; // 10分钟
    $db->exec("DELETE FROM web_login_verified WHERE verified_at < " . $expireTime);

    $verified = [];

    if ($playerName) {
        // ★ 检查单个玩家，且验证记录必须在10分钟内
        $stmt = $db->prepare("SELECT player_name, verified_at FROM web_login_verified WHERE player_name = :player AND verified_at >= :expire");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row) {
            $verified[] = $row;
            // 消费验证记录（一次性）
            $delStmt = $db->prepare("DELETE FROM web_login_verified WHERE player_name = :player");
            $delStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $delStmt->execute();
            debugLog("check_web_login_verified: 找到验证记录并消费", ['player_name' => $playerName, 'verified_at' => $row['verified_at']]);
        } else {
            debugLog("check_web_login_verified: 未找到验证记录", ['player_name' => $playerName]);
        }
    } else {
        // 批量获取所有验证记录（用于轮询）
        $stmt = $db->prepare("SELECT player_name, verified_at FROM web_login_verified WHERE verified_at >= :expire");
        $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
        $result = $stmt->execute();
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $verified[] = $row;
        }
        // 全部消费
        if (!empty($verified)) {
            $db->exec("DELETE FROM web_login_verified WHERE verified_at >= " . $expireTime);
            debugLog("check_web_login_verified: 批量消费验证记录", ['count' => count($verified), 'players' => array_column($verified, 'player_name')]);
        } else {
            debugLog("check_web_login_verified: 无验证记录", []);
        }
    }

    success($verified);
}

// ===== Web端提交密码登录请求 =====
// 玩家在Web端输入用户名+密码，PHP存储请求，等待插件验证
function webLoginRequest() {
    $player = getParam('player');
    $password = getParam('password');

    if (!$player || !$password) {
        error('缺少player或password参数');
    }

    // 简单验证玩家名格式（3-16位字母数字下划线）
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $player)) {
        error('玩家名格式不正确（3-16位字母数字下划线）');
    }

    $db = getDB();

    // 检查玩家是否已注册
    if (!isPlayerRegistered($player)) {
        error('玩家未注册，请先在游戏中注册');
    }

    $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password TEXT NOT NULL,
        request_time INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        result_time INTEGER DEFAULT 0
    )");

    // 清理10分钟前的过期请求
    $db->exec("DELETE FROM web_login_requests WHERE request_time < " . (time() - 600));

    // 检查是否有进行中的请求
    $checkStmt2 = $db->prepare("SELECT id FROM web_login_requests WHERE player_name = :player AND status = 'pending'");
    $checkStmt2->bindValue(':player', $player, SQLITE3_TEXT);
    $checkResult2 = $checkStmt2->execute();
    if ($checkResult2->fetchArray()) {
        error('已有进行中的登录请求，请稍后再试');
    }

    $stmt = $db->prepare("INSERT INTO web_login_requests (player_name, password, request_time, status) VALUES (:player, :pwd, :time, 'pending')");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':pwd', $password, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    $requestId = $db->lastInsertRowID();

    success([
        'request_id' => $requestId,
        'player' => $player,
        'message' => '登录请求已提交，等待游戏服务器验证...'
    ], "请求已提交");
}

// ===== Web端轮询密码登录结果 =====
function checkWebLoginResult() {
    $player = getParam('player');
    $requestId = getParam('request_id');

    if (!$player || !$requestId) {
        error('缺少player或request_id参数');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_login_requests WHERE id = :id AND player_name = :player");
    $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('请求不存在');
    }

    if ($row['status'] === 'pending') {
        // 还在等待
        success([
            'status' => 'pending',
            'message' => '等待游戏服务器验证...'
        ]);
    } elseif ($row['status'] === 'success') {
        // 验证成功
        $resultData = json_decode($row['result'], true);
        success([
            'status' => 'success',
            'player' => $player,
            'user_data' => $resultData ?? ['player_name' => $player],
            'message' => '登录成功'
        ]);
    } else {
        // 验证失败
        $resultData = json_decode($row['result'], true);
        success([
            'status' => 'failed',
            'message' => $resultData['error'] ?? '密码错误'
        ]);
    }
}

// ===== 插件轮询待处理的密码登录请求（SECRET_KEY认证）=====
function checkPendingWebLogins() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password TEXT NOT NULL,
        request_time INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        result_time INTEGER DEFAULT 0
    )");

    // 清理10分钟前的过期请求
    $db->exec("DELETE FROM web_login_requests WHERE request_time < " . (time() - 600));

    // 获取所有pending请求（只返回id、player_name、password，不返回密码明文给前端）
    $stmt = $db->prepare("SELECT id, player_name, password FROM web_login_requests WHERE status = 'pending'");
    $result = $stmt->execute();

    $requests = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $requests[] = $row;
    }

    success($requests);
}

// ===== 插件写回密码验证结果（SECRET_KEY认证）=====
function completeWebLoginRequest() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $requestId = getParam('request_id');
    $player = getParam('player');
    $result = getParam('result'); // JSON字符串

    if (!$requestId || !$player) {
        error('缺少request_id或player参数');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT id FROM web_login_requests WHERE id = :id AND player_name = :player");
    $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray();

    if (!$row) {
        error('请求不存在');
    }

    // 判断验证结果
    $resultData = json_decode($result, true);
    $status = isset($resultData['success']) && $resultData['success'] ? 'success' : 'failed';

    $updateStmt = $db->prepare("UPDATE web_login_requests SET status = :status, result = :result, result_time = :time WHERE id = :id");
    $updateStmt->bindValue(':status', $status, SQLITE3_TEXT);
    $updateStmt->bindValue(':result', $result, SQLITE3_TEXT);
    $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $updateStmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
    $updateStmt->execute();

    // 如果验证成功，写入web_login_confirmations和web_login_verified
    if ($status === 'success') {
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
        $confirmStmt = $db->prepare("INSERT OR REPLACE INTO web_login_confirmations (player_name, confirmed_at, consumed) VALUES (:player, :time, 0)");
        $confirmStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $confirmStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $confirmStmt->execute();

        // ★ 记录Web登录验证成功，供玩家进游戏时自动登录（持久化）
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
        $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
        $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $verifiedStmt->execute();
    }

    success(['status' => $status], "结果已写入");
}

// ===== 插件推送玩家密码凭证（SECRET_KEY认证）=====
function pushWebCredentials() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $players = getParam('players');
    if (!$players || !is_array($players)) {
        error('缺少players数组');
    }

    $db = getDB();
    $count = 0;

    foreach ($players as $p) {
        $name = $p['player_name'] ?? null;
        $hash = $p['password_hash'] ?? null;
        $salt = $p['salt'] ?? null;
        $tempHash = $p['temp_password_hash'] ?? null;
        $tempExpire = $p['temp_pw_expire'] ?? 0;

        if (!$name || !$hash || !$salt) continue;

        storeWebLoginCredentials($name, $hash, $salt);

        // ★ 如果有临时密码，单独存储
        if (!empty($tempHash) && !empty($tempExpire)) {
            storeTempPassword($name, $tempHash, $tempExpire);
        }

        $count++;
    }

    success(['pushed' => $count], "推送了{$count}个玩家密码凭证");
}

// ===== Web端验证登录密码 =====
function verifyWebPassword() {
    $webToken = getParam('web_token');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$webToken) error('缺少web_token');
    if (!$password) error('缺少password');

    // 先验证token
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('无效的登录Token');

    $createdAt = (int)$row['created_at'];
    $expireSeconds = (int)$row['expire_seconds'];
    if (time() - $createdAt > $expireSeconds) error('登录Token已过期');

    $playerName = $row['player_name'];

    // ★ 安全检查：玩家必须在游戏中注册过才能登录
    if (!isPlayerRegistered($playerName)) {
        error('玩家未在游戏中注册，请先在游戏中使用 /register 注册账号');
    }

    // 验证密码
    $verifyResult = verifyWebLoginPassword($playerName, $password);
    if ($verifyResult === false) {
        error('密码错误');
    }

    // 标记需要修改密码（如果是临时密码）
    $needPasswordChange = ($verifyResult === 'temp') ? 1 : 0;

    // 密码正确，创建登录确认记录，供插件轮询后自动登录游戏
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
    $confirmStmt = $db->prepare("INSERT OR REPLACE INTO web_login_confirmations (player_name, confirmed_at, consumed) VALUES (:player, :time, 0)");
    $confirmStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $confirmStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $confirmStmt->execute();

    // ★ 记录Web登录验证成功，供玩家进游戏时自动登录（持久化，不随轮询消费而消失）
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
    $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
    $verifiedStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $verifiedStmt->execute();

    // 记录会话
    $sessionToken = recordWebSession($playerName, $ipAddress);

    success([
        'player' => $playerName,
        'session' => $sessionToken,
        'mode' => 'full',
        'need_password_change' => $needPasswordChange
    ], '登录成功');
}

// ===== Web端安全验证入口（双保险）=====
function webAccessCheck() {
    $webToken = getParam('web_token');
    $accessAction = getParam('access_action', 'view');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$webToken) error('缺少web_token');

    debugLog("web_access_check: 开始验证", [
        'token' => substr($webToken, 0, 16) . '...',
        'action' => $accessAction,
        'ip' => $ipAddress,
        'has_password' => !empty($password)
    ]);

    $result = validateWebAccess($webToken, $accessAction, $password, $ipAddress);

    debugLog("web_access_check: 验证结果", [
        'ok' => $result['ok'],
        'mode' => $result['mode'] ?? 'unknown',
        'player' => $result['player'] ?? 'unknown',
        'registered' => $result['registered'] ?? false,
        'online' => $result['online'] ?? false,
        'message' => $result['message'] ?? ''
    ]);

    if ($result['ok']) {
        success([
            'player' => $result['player'] ?? '',
            'mode' => $result['mode'] ?? '',
            'session' => $result['session'] ?? '',
            'online' => $result['online'] ?? false,
            'registered' => $result['registered'] ?? false
        ], $result['message']);
    } else {
        if ($result['mode'] === 'need_password') {
            // 需要密码但未提供
            jsonResponse([
                'success' => false,
                'need_password' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } elseif ($result['mode'] === 'need_game_login') {
            // 游戏里未登录
            jsonResponse([
                'success' => false,
                'need_game_login' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } elseif ($result['mode'] === 'need_register') {
            // 玩家未注册
            jsonResponse([
                'success' => false,
                'need_register' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } else {
            error($result['message'], 401);
        }
    }
}

// ===== 插件拉取待处理的交易 =====
function pullPendingTransactions() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT, detail TEXT, status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");

    // 检查processed_at字段是否存在（旧库可能缺失）
    $hasProcessedAt = false;
    $stmt = $db->query("PRAGMA table_info(web_transactions)");
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        if ($row['name'] === 'processed_at') { $hasProcessedAt = true; break; }
    }
    if (!$hasProcessedAt) {
        $db->exec("ALTER TABLE web_transactions ADD COLUMN processed_at INTEGER");
    }

    // 先恢复超时的processing交易（超过60秒未确认的恢复为pending）
    if ($hasProcessedAt) {
        $db->exec("UPDATE web_transactions SET status = 'pending' WHERE status = 'processing' AND processed_at < " . (time() - 60));
    }

    // 获取所有pending的交易
    $stmt = $db->prepare("SELECT id, player_name, type, amount, reason, detail FROM web_transactions WHERE status = 'pending' ORDER BY created_at ASC");
    $result = $stmt->execute();
    $transactions = [];
    $ids = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $transactions[] = $row;
        $ids[] = (int)$row['id'];
    }

    // 立即标记为processing（防止5秒后重复拉取）
    if ($hasProcessedAt && !empty($ids)) {
        $idList = implode(',', $ids);
        $db->exec("UPDATE web_transactions SET status = 'processing', processed_at = " . time() . " WHERE id IN ($idList)");
    }

    success(['transactions' => $transactions, 'count' => count($transactions)]);
}

// ===== 插件确认交易已处理 =====
function confirmTransaction() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $txId = getParam('tx_id');
    if (!$txId) error('缺少tx_id');

    $db = getDB();
    // 检查processed_at字段是否存在
    $hasProcessedAt = false;
    $stmt = $db->query("PRAGMA table_info(web_transactions)");
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        if ($row['name'] === 'processed_at') { $hasProcessedAt = true; break; }
    }

    if ($hasProcessedAt) {
        $stmt = $db->prepare("UPDATE web_transactions SET status = 'processed', processed_at = :time WHERE id = :id AND status IN ('pending', 'processing')");
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    } else {
        $stmt = $db->prepare("UPDATE web_transactions SET status = 'processed' WHERE id = :id AND status IN ('pending', 'processing')");
    }
    $stmt->bindValue(':id', (int)$txId, SQLITE3_INTEGER);
    $stmt->execute();

    success(['tx_id' => $txId], '交易已确认');
}

// ===== 插件拉取Web端修改的商品库存 =====
function pullShopStock() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $stmt = $db->prepare("SELECT id, stock, last_sync FROM shop_items");
    $result = $stmt->execute();
    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = $row;
    }

    success(['items' => $items, 'count' => count($items)]);
}

// ===== 通知插件立即同步数据 =====
function notifySync() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    // 写入同步通知文件
    $notifyFile = __DIR__ . '/../../sync_notify.txt';
    file_put_contents($notifyFile, time());

    success([], '已通知插件同步');
}

// ===== 发送邮箱验证码 =====
function sendEmailCode() {
    $player = getParam('player');
    $email = getParam('email');

    if (!$player) error('缺少player参数');
    if (!$email) error('缺少email参数');
    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) error('邮箱格式不正确');

    // 使用config.php中的SMTP配置
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    if (empty($smtpHost) || empty($smtpUser) || empty($smtpPass)) {
        error('SMTP配置不完整');
    }

    // 生成6位验证码
    $code = str_pad(mt_rand(0, 999999), 6, '0', STR_PAD_LEFT);
    $expireTime = time() + 300; // 5分钟有效

    // 存储验证码到数据库
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS email_codes (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, email TEXT NOT NULL, code TEXT NOT NULL, expire_at INTEGER NOT NULL, used INTEGER DEFAULT 0, created_at INTEGER NOT NULL)");

    // 清理过期验证码
    $db->exec("DELETE FROM email_codes WHERE expire_at < " . time());

    // 检查是否有未过期的验证码
    $checkStmt = $db->prepare("SELECT id FROM email_codes WHERE player_name = :player AND email = :email AND expire_at > :time AND used = 0");
    $checkStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $checkStmt->bindValue(':email', $email, SQLITE3_TEXT);
    $checkStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $checkResult = $checkStmt->execute();
    if ($checkResult->fetchArray()) {
        error('验证码已发送，请查看邮箱（5分钟内有效）');
    }

    // 插入新验证码
    $stmt = $db->prepare("INSERT INTO email_codes (player_name, email, code, expire_at, created_at) VALUES (:player, :email, :code, :expire, :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    try {
        // 构建邮件内容
        $subject = "【" . $senderName . "】登录验证码";
        $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
                . "<h2 style='color:#333;text-align:center'>🔐 登录验证码</h2>"
                . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
                . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b> 的验证码：</p>"
                . "<p style='font-size:32px;font-weight:bold;color:#4CAF50;letter-spacing:8px;margin:0'>" . $code . "</p>"
                . "</div>"
                . "<p style='font-size:12px;color:#999;text-align:center'>验证码5分钟内有效，请勿泄露给他人</p>"
                . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
                . "</div>";

        $headers = "MIME-Version: 1.0\r\n"
                . "Content-type: text/html; charset=UTF-8\r\n"
                . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

        // 使用PHPMailer或自定义SMTP发送
        $sent = smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);

        if ($sent) {
            success(['player' => $player, 'email' => $email, 'code' => $code], '验证码已发送到邮箱');
        } else {
            // SMTP失败，返回验证码供调试
            success(['player' => $player, 'email' => $email, 'code' => $code], '邮件发送中（验证码：' . $code . '）');
        }
    } catch (Exception $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 验证邮箱验证码 =====
function verifyEmailCode() {
    $player = getParam('player');
    $email = getParam('email');
    $code = getParam('code');
    $webToken = getParam('web_token');

    if (!$player) error('缺少player参数');
    if (!$email) error('缺少email参数');
    if (!$code) error('缺少验证码');
    if (!$webToken) error('缺少web_token');

    // ★ 安全检查：玩家必须在游戏中注册过
    if (!isPlayerRegistered($player)) {
        error('玩家未在游戏中注册，请先在游戏中使用 /register 注册账号');
    }

    // 验证web_token
    $tokenInfo = validateToken($webToken);
    if (!$tokenInfo) error('无效的登录token');
    
    // 验证玩家名是否匹配
    if ($tokenInfo['player'] !== $player) error('玩家名不匹配');

    $db = getDB();
    
    // 查找未使用且未过期的验证码
    $stmt = $db->prepare("SELECT * FROM email_codes WHERE player_name = :player AND email = :email AND code = :code AND expire_at > :time AND used = 0");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('验证码无效或已过期');
    }

    // 标记验证码已使用
    $updateStmt = $db->prepare("UPDATE email_codes SET used = 1 WHERE id = :id");
    $updateStmt->bindValue(':id', $row['id'], SQLITE3_INTEGER);
    $updateStmt->execute();

    // 验证码正确，创建登录确认记录
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
    $confirmStmt = $db->prepare("INSERT OR REPLACE INTO web_login_confirmations (player_name, confirmed_at, consumed) VALUES (:player, :time, 0)");
    $confirmStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $confirmStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $confirmStmt->execute();

    // ★ 记录Web登录验证成功，供玩家进游戏时自动登录（持久化，不随轮询消费而消失）
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
    $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
    $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $verifiedStmt->execute();

    // 记录会话
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    $sessionToken = recordWebSession($player, $ipAddress);

    success([
        'player' => $player,
        'session' => $sessionToken,
        'mode' => 'email_verified'
    ], '邮箱验证成功');
}

// ===== 修复库存数据（stock=0→-1） =====
function fixStock() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    // 将所有stock=0改为-1（游戏端0表示无限供应）
    $before = $db->querySingle("SELECT COUNT(*) FROM shop_items WHERE stock = 0");
    $db->exec("UPDATE shop_items SET stock = -1 WHERE stock = 0");
    $after = $db->querySingle("SELECT COUNT(*) FROM shop_items WHERE stock = 0");

    success([
        'before_zero' => (int)$before,
        'after_zero' => (int)$after,
        'fixed' => (int)$before - (int)$after
    ], "修复了{$before}个商品的库存");
}

// ===== 发送重置密码链接 =====
function sendResetPasswordLink() {
    $player = getParam('player');

    if (!$player) error('缺少player参数');

    $db = getDB();

    // 检查玩家是否注册
    if (!isPlayerRegistered($player)) {
        error('玩家未注册');
    }

    // 从数据库获取玩家邮箱
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row || !$row['email'] || $row['email'] === '') {
        // 玩家未绑定邮箱，创建待管理员审核的记录
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
        
        $stmt = $db->prepare("INSERT INTO password_reset_requests (player_name, status, created_at) VALUES (:player, 'pending', :time)");
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        
        error('该玩家未绑定邮箱，需要管理员审核。管理员将在后台验明身份后处理您的请求。');
    }
    $email = $row['email'];

    // 生成重置token
    $resetToken = bin2hex(random_bytes(32));
    $expireTime = time() + 1800; // 30分钟有效

    // 存储重置token
    $db->exec("CREATE TABLE IF NOT EXISTS password_reset_tokens (token TEXT PRIMARY KEY, player_name TEXT NOT NULL, expire_at INTEGER NOT NULL)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO password_reset_tokens (token, player_name, expire_at) VALUES (:token, :player, :expire)");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->execute();

    // 发送重置邮件
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    $subject = "【" . $senderName . "】密码重置";
    $resetUrl = WEBSUB_DIR . 'reset_password.php?token=' . $resetToken;
    $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
            . "<h2 style='color:#333;text-align:center'>🔑 密码重置</h2>"
            . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
            . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b>，您请求了密码重置</p>"
            . "<p style='font-size:13px;color:#666;margin-bottom:20px'>点击下方按钮重置密码（30分钟内有效）</p>"
            . "<a href='" . $resetUrl . "' style='display:inline-block;padding:12px 24px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:6px;font-weight:bold'>重置密码</a>"
            . "</div>"
            . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
            . "</div>";

    $headers = "MIME-Version: 1.0\r\n"
            . "Content-type: text/html; charset=UTF-8\r\n"
            . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

    try {
        smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);
        success(['player' => $player, 'email' => $email], '重置链接已发送到邮箱');
    } catch (Exception $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 重置密码 =====
function resetPassword() {
    $resetToken = getParam('reset_token');
    $newPassword = getParam('new_password');

    if (!$resetToken) error('缺少reset_token参数');
    if (!$newPassword) error('缺少new_password参数');
    if (strlen($newPassword) < 6) error('密码长度至少6位');

    $db = getDB();

    // 验证重置token
    $stmt = $db->prepare("SELECT * FROM password_reset_tokens WHERE token = :token");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('无效的重置链接');

    if (time() > $row['expire_at']) {
        error('重置链接已过期');
    }

    $player = $row['player_name'];

    // 生成新的密码凭证
    $saltBytes = random_bytes(16);
    $salt = base64_encode($saltBytes);
    $passwordHash = base64_encode(hash('sha256', $saltBytes . $newPassword, true));

    // 更新密码凭证
    storeWebLoginCredentials($player, $passwordHash, $salt);

    // 删除重置token
    $stmt = $db->prepare("DELETE FROM password_reset_tokens WHERE token = :token");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->execute();

    success(['player' => $player], '密码重置成功');
}

// ===== 管理员列出用户 =====
function adminListUsers() {
    $token = getParam('token');
    if (!$token) error('缺少token参数');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all')) {
        error('需要管理员权限');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM users ORDER BY register_time DESC");
    $result = $stmt->execute();

    $users = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $users[] = $row;
    }

    success($users);
}

// ===== 管理员发送重置链接 =====
function adminSendResetLink() {
    $token = getParam('token');
    $player = getParam('player');
    $email = getParam('email');

    if (!$player) error('缺少player参数');
    if (!$email) error('缺少email参数');

    // 支持两种认证方式：token认证 或 管理员会话认证
    $authenticated = false;
    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $authenticated = true;
        }
    }
    // 尝试管理员认证（通过admin_sessions表）
    if (!$authenticated) {
        requireAdminSession();
        $authenticated = true;
    }

    if (!$authenticated) {
        error('需要管理员权限');
    }

    $db = getDB();

    // 检查玩家是否存在
    if (!isPlayerRegistered($player)) {
        error('玩家不存在');
    }

    // 绑定邮箱（如果玩家没有邮箱）
    $emailStmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $emailStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $emailResult = $emailStmt->execute();
    $emailRow = $emailResult->fetchArray(SQLITE3_ASSOC);
    
    if (!$emailRow || !$emailRow['email'] || $emailRow['email'] === '') {
        $updateEmailStmt = $db->prepare("UPDATE users SET email = :email WHERE player_name = :player");
        $updateEmailStmt->bindValue(':email', $email, SQLITE3_TEXT);
        $updateEmailStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $updateEmailStmt->execute();
    }

    // 生成重置token
    $resetToken = bin2hex(random_bytes(32));
    $expireTime = time() + 1800; // 30分钟有效

    // 存储重置token
    $db->exec("CREATE TABLE IF NOT EXISTS password_reset_tokens (token TEXT PRIMARY KEY, player_name TEXT NOT NULL, expire_at INTEGER NOT NULL)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO password_reset_tokens (token, player_name, expire_at) VALUES (:token, :player, :expire)");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->execute();

    // 发送重置邮件
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    $subject = "【" . $senderName . "】密码重置";
    $resetUrl = WEBSUB_DIR . 'reset_password.php?token=' . $resetToken;
    $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
            . "<h2 style='color:#333;text-align:center'>🔑 密码重置</h2>"
            . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
            . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b>，您请求了密码重置</p>"
            . "<p style='font-size:13px;color:#666;margin-bottom:20px'>点击下方按钮重置密码（30分钟内有效）</p>"
            . "<a href='" . $resetUrl . "' style='display:inline-block;padding:12px 24px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:6px;font-weight:bold'>重置密码</a>"
            . "</div>"
            . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
            . "</div>";

    $headers = "MIME-Version: 1.0\r\n"
            . "Content-type: text/html; charset=UTF-8\r\n"
            . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

    try {
        smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);
        success(['player' => $player, 'email' => $email], '重置链接已发送到邮箱');
    } catch (Exception $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 修改密码 =====
function changePassword() {
    $player = getParam('player');
    $oldPwd = getParam('old_password');
    $newPwd = getParam('new_password');

    if (!$player || !$oldPwd || !$newPwd) {
        error('缺少必要参数');
    }

    // 验证新密码格式
    if (strlen($newPwd) < 6 || strlen($newPwd) > 20) {
        error('密码长度必须为6-20位');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT password_hash, password_salt FROM weblogin_credentials WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('玩家不存在');
    }

    // 验证旧密码
    $saltBytes = base64_decode($row['password_salt'], true);
    if ($saltBytes === false || $saltBytes === '') {
        error('密码盐值无效');
    }

    $oldHashComputed = base64_encode(hash('sha256', $saltBytes . $oldPwd, true));
    if ($oldHashComputed !== $row['password_hash']) {
        error('当前密码错误');
    }

    // 生成新盐值
    $newSaltBytes = random_bytes(16);
    $newSalt = base64_encode($newSaltBytes);

    // 计算新密码哈希
    $newHash = base64_encode(hash('sha256', $saltBytes . $newPwd, true));
    
    // 实际上应该用新盐值，但为了简单先用旧盐值计算
    // 正确做法：$newHash = base64_encode(hash('sha256', $newSaltBytes . $newPwd, true));
    $newHash = base64_encode(hash('sha256', $newSaltBytes . $newPwd, true));

    // 更新密码
    $updateStmt = $db->prepare("UPDATE weblogin_credentials SET password_hash = :hash, salt = :salt, temp_password_hash = '', temp_pw_expire = 0 WHERE player_name = :player");
    $updateStmt->bindValue(':hash', $newHash, SQLITE3_TEXT);
    $updateStmt->bindValue(':salt', $newSalt, SQLITE3_TEXT);
    $updateStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $updateStmt->execute();

    success(['message' => '密码修改成功'], '密码修改成功');
}

// ===== 延长Web登录Token有效期 =====
function extendWebToken() {
    $webToken = getParam('web_token');

    if (!$webToken) error('缺少web_token参数');

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('无效的登录Token');

    $playerName = $row['player_name'];

    // 延长有效期：重置created_at为当前时间
    $updateStmt = $db->prepare("UPDATE weblogin_tokens SET created_at = :time WHERE web_token = :token");
    $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $updateStmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $updateStmt->execute();

    success(['player' => $playerName], 'Token有效期已延长');
}

// ===== 插件轮询待处理的Web注册请求（SECRET_KEY认证）=====
function checkPendingWebRegisterRequests() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    // ★ 不再使用 CREATE TABLE IF NOT EXISTS，表结构已在 initTables 中创建
    try {
        $db->exec("SELECT id FROM web_register_requests LIMIT 0");
    } catch (Exception $e) {
        // 表不存在则创建（仅首次）
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
    }

    // 清理30分钟前的过期请求（防止永久堆积）
    $db->exec("DELETE FROM web_register_requests WHERE created_at < " . (time() - 1800));

    // 获取所有pending请求
    $stmt = $db->prepare("SELECT id, player_name, password_hash, salt, email, ip_address, created_at FROM web_register_requests WHERE status = 'pending' ORDER BY created_at ASC");
    $result = $stmt->execute();

    $requests = [];
    $ids = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $requests[] = $row;
        $ids[] = (int)$row['id'];
    }

    // 立即标记为processing（防止重复拉取）
    if (!empty($ids)) {
        $idList = implode(',', $ids);
        $db->exec("UPDATE web_register_requests SET status = 'processing', processed_at = " . time() . " WHERE id IN ($idList)");
    }

    success(['requests' => $requests, 'count' => count($requests)]);
}

// ===== 插件确认注册请求已处理（SECRET_KEY认证）=====
function completeWebRegisterRequest() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $requestId = getParam('request_id');
    $result = getParam('result'); // 'success' 或 'failed'
    $errorMsg = getParam('error', '');

    if (!$requestId) error('缺少request_id');

    $db = getDB();
    $status = ($result === 'success') ? 'completed' : 'failed';

    $stmt = $db->prepare("UPDATE web_register_requests SET status = :status, processed_at = :time WHERE id = :id");
    $stmt->bindValue(':status', $status, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
    $stmt->execute();

    // 如果注册成功，将用户数据写入users表
    if ($result === 'success') {
        // 获取注册请求的详细信息
        $stmt = $db->prepare("SELECT player_name, email, ip_address, created_at FROM web_register_requests WHERE id = :id");
        $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        
        if ($row) {
            // 检查users表中是否已存在该用户
            $checkStmt = $db->prepare("SELECT 1 FROM users WHERE player_name = :name");
            $checkStmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
            $checkResult = $checkStmt->execute();
            
            if (!$checkResult->fetchArray()) {
                // 用户不存在，插入新记录
                $insertStmt = $db->prepare("INSERT INTO users (player_name, register_time, email) VALUES (:name, :time, :email)");
                $insertStmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
                $insertStmt->bindValue(':time', $row['created_at'], SQLITE3_INTEGER);
                $insertStmt->bindValue(':email', $row['email'], SQLITE3_TEXT);
                $insertStmt->execute();
                
                debugLog("completeWebRegisterRequest: 将用户写入users表", [
                    'player' => $row['player_name'],
                    'register_time' => $row['created_at'],
                    'email' => $row['email']
                ]);
            }
        }
    }

    success(['request_id' => $requestId, 'status' => $status]);
}
