<?php
/**
 * Minecraft正版验证 - Microsoft OAuth代理
 *
 * 流程：
 * 1. Java调用 create_session → 生成授权URL → 返回给Java
 * 2. 玩家在浏览器打开URL → Microsoft登录 → 回调到本文件callback
 * 3. callback处理授权码 → 交换token → 获取Minecraft profile → 存入SQLite
 * 4. Java轮询 check_session → 返回验证状态和profile
 *
 * Actions:
 *   create_session  - 创建验证会话，返回授权URL
 *   check_session   - 检查会话状态（Java轮询）
 *   callback        - Microsoft OAuth回调（浏览器重定向）
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

// 加载密钥配置
require_once __DIR__ . '/pay_secrets.php';

// ====================================================================
//  工具函数
// ====================================================================

function success($data = null) {
    echo json_encode(['success' => true, 'data' => $data], JSON_UNESCAPED_UNICODE);
    exit;
}

function error($msg, $code = 400) {
    http_response_code($code);
    echo json_encode(['success' => false, 'error' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

function getParam($key, $default = '') {
    return isset($_GET[$key]) ? trim($_GET[$key]) : $default;
}

function postParam($key, $default = '') {
    return isset($_POST[$key]) ? trim($_POST[$key]) : $default;
}

/**
 * 获取SQLite数据库连接（orders.db）
 */
function getDb() {
    $possiblePaths = [
        dirname(__DIR__) . '/data/orders.db',
        dirname(__DIR__) . '/orders.db',
        dirname(__DIR__) . '/../data/orders.db',
        dirname(__DIR__) . '/../orders.db',
    ];
    foreach ($possiblePaths as $path) {
        if (file_exists($path)) {
            $db = new PDO('sqlite:' . $path);
            $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
            $db->exec('PRAGMA journal_mode=WAL');
            return $db;
        }
    }
    // 都不存在，创建在第一个路径
    $dir = dirname($possiblePaths[0]);
    if (!is_dir($dir)) @mkdir($dir, 0755, true);
    $db = new PDO('sqlite:' . $possiblePaths[0]);
    $db->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
    $db->exec('PRAGMA journal_mode=WAL');
    return $db;
}

/**
 * 确保mc_auth_sessions表存在
 */
function ensureTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS mc_auth_sessions (
        session_id TEXT PRIMARY KEY,
        player_name TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'pending',
        mc_uuid TEXT DEFAULT '',
        mc_username TEXT DEFAULT '',
        mc_access_token TEXT DEFAULT '',
        ip_address TEXT DEFAULT '',
        created_at INTEGER NOT NULL,
        verified_at INTEGER DEFAULT 0,
        expires_at INTEGER NOT NULL
    )");
}

/**
 * 生成随机session_id
 */
function generateSessionId() {
    return bin2hex(random_bytes(16));
}

// ====================================================================
//  Microsoft OAuth 工具函数
// ====================================================================

/**
 * 向Microsoft发送POST请求
 */
function msPost($url, $data, $headers = []) {
    // 检查调用者是否已指定Content-Type
    $hasContentType = false;
    foreach ($headers as $h) {
        if (stripos($h, 'Content-Type:') === 0) {
            $hasContentType = true;
            break;
        }
    }

    $defaultHeaders = ['Accept: application/json'];
    if (!$hasContentType) {
        $defaultHeaders[] = 'Content-Type: application/x-www-form-urlencoded';
    }

    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => is_array($data) ? http_build_query($data) : $data,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 30,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_HTTPHEADER => array_merge($defaultHeaders, $headers),
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $error = curl_error($ch);
    curl_close($ch);

    // 详细日志记录
    $log = date('Y-m-d H:i:s') . "\n";
    $log .= "URL: $url\n";
    if (is_array($data)) {
        $log .= "Data: " . http_build_query($data) . "\n";
    } else {
        $log .= "Data: $data\n";
    }
    $log .= "HTTP Code: $httpCode\n";
    $log .= "cURL Error: " . ($error ?: 'none') . "\n";
    $log .= "Response: " . substr($response, 0, 500) . "\n";
    $log .= str_repeat('-', 80) . "\n";
    @file_put_contents(__DIR__ . '/debug_minecraft.log', $log, FILE_APPEND);

    if ($error) {
        return ['error' => "cURL error: $error"];
    }
    return ['code' => $httpCode, 'body' => json_decode($response, true)];
}

/**
 * 向Microsoft发送GET请求
 */
function msGet($url, $headers = []) {
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 30,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_HTTPHEADER => array_merge([
            'Accept: application/json',
        ], $headers),
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $error = curl_error($ch);
    curl_close($ch);

    // 详细日志记录
    $log = date('Y-m-d H:i:s') . "\n";
    $log .= "GET URL: $url\n";
    $log .= "HTTP Code: $httpCode\n";
    $log .= "cURL Error: " . ($error ?: 'none') . "\n";
    $log .= "Response: " . substr($response, 0, 500) . "\n";
    $log .= str_repeat('-', 80) . "\n";
    @file_put_contents(__DIR__ . '/debug_minecraft.log', $log, FILE_APPEND);

    if ($error) {
        return ['error' => "cURL error: $error"];
    }
    return ['code' => $httpCode, 'body' => json_decode($response, true)];
}

// ====================================================================
//  核心OAuth流程
// ====================================================================

/**
 * 步骤1: 生成Microsoft授权URL
 * ★ 使用Minecraft官方启动器的公共Client ID（00000000402b5328）
 *   此Client ID自带Xbox Live和Minecraft API权限，无需申请
 *   缺点：redirect_uri必须是login.live.com的桌面端页面，玩家需手动复制授权码
 */
function getAuthUrl($sessionId) {
    $clientId = '00000000402b5328'; // Minecraft官方启动器公共客户端ID
    // 桌面端专用redirect_uri（Microsoft展示授权码的页面）
    $redirectUri = 'https://login.live.com/oauth20_desktop.srf';
    $scope = 'XboxLive.signin offline_access';

    $params = http_build_query([
        'client_id' => $clientId,
        'response_type' => 'code',
        'scope' => $scope,
        'redirect_uri' => $redirectUri,
        'state' => $sessionId,
        'prompt' => 'select_account',
    ]);

    return "https://login.live.com/oauth20_authorize.srf?$params";
}

/**
 * 步骤2: 用授权码交换Access Token
 * ★ 公共客户端（00000000402b5328）不需要client_secret
 *   redirect_uri必须与授权时一致
 */
function exchangeToken($code) {
    $clientId = '00000000402b5328'; // Minecraft公共客户端ID
    // 桌面端redirect_uri（与getAuthUrl一致）
    $redirectUri = 'https://login.live.com/oauth20_desktop.srf';

    $result = msPost('https://login.live.com/oauth20_token.srf', [
        'client_id' => $clientId,
        'code' => $code,
        'grant_type' => 'authorization_code',
        'redirect_uri' => $redirectUri,
        'scope' => 'XboxLive.signin offline_access',
    ]);

    if (isset($result['error'])) {
        return ['error' => 'Token exchange failed: ' . $result['error']];
    }
    if ($result['code'] !== 200) {
        return ['error' => 'Token exchange HTTP ' . $result['code']];
    }

    $body = $result['body'];
    if (!isset($body['access_token'])) {
        return ['error' => 'No access_token in response', 'response' => $body];
    }

    return [
        'access_token' => $body['access_token'],
        'refresh_token' => $body['refresh_token'] ?? '',
    ];
}

/**
 * 步骤3: 获取Xbox Live Token
 * ★ RpsTicket格式确认：必须用 "d= {token}" 前缀（来自日志验证）
 */
function getXboxToken($msAccessToken) {
    $rpsTicket = 'd=' . $msAccessToken;

    $log = date('Y-m-d H:i:s') . "\n";
    $log .= "=== Xbox Token (d=前缀) ===\n";

    $result = msPost('https://user.auth.xboxlive.com/user/authenticate', json_encode([
        'Properties' => [
            'AuthMethod' => 'RPS',
            'SiteName' => 'user.auth.xboxlive.com',
            'RpsTicket' => $rpsTicket,
        ],
        'RelyingParty' => 'http://auth.xboxlive.com',
        'TokenType' => 'JWT',
    ]), [
        'Content-Type: application/json',
        'Accept: application/json',
    ]);

    $log .= "HTTP Code: " . ($result['code'] ?? 'N/A') . "\n";
    $log .= "Response: " . json_encode($result['body'] ?? $result['error'] ?? 'N/A') . "\n";
    $log .= str_repeat('-', 80) . "\n";
    @file_put_contents(__DIR__ . '/debug_minecraft.log', $log, FILE_APPEND);

    if (isset($result['error'])) {
        return ['error' => 'Xbox auth failed: ' . $result['error']];
    }
    if ($result['code'] !== 200) {
        return ['error' => "Xbox auth HTTP {$result['code']}: " . json_encode($result['body'] ?? [])];
    }

    $body = $result['body'];
    if (!isset($body['Token'])) {
        return ['error' => 'No Token in response: ' . json_encode($body)];
    }

    return [
        'token' => $body['Token'],
        'uhs' => $body['IssueClaims']['uhs'] ?? ($body['DisplayClaims']['xui'][0]['uhs'] ?? ''),
    ];
}

/**
 * 步骤4: 获取XSTS Token
 */
function getXstsToken($xboxToken, $uhs) {
    $result = msPost('https://xsts.auth.xboxlive.com/xsts/authorize', json_encode([
        'Properties' => [
            'SandboxId' => 'RETAIL',
            'UserTokens' => [$xboxToken],
        ],
        'RelyingParty' => 'rp://api.minecraftservices.com/',
        'TokenType' => 'JWT',
    ]), [
        'Content-Type: application/json',
        'Accept: application/json',
    ]);

    if (isset($result['error'])) {
        return ['error' => 'XSTS auth failed: ' . $result['error']];
    }
    if ($result['code'] !== 200) {
        $body = $result['body'] ?? [];
        $errCode = $body['XErr'] ?? 'unknown';
        return ['error' => "XSTS auth HTTP {$result['code']}, XErr: {$errCode}", 'response' => $body];
    }

    $body = $result['body'];
    if (!isset($body['Token'])) {
        return ['error' => 'No XSTS token', 'response' => $body];
    }

    return [
        'token' => $body['Token'],
        'uhs' => $body['IssueClaims']['uhs'] ?? $uhs,
    ];
}

/**
 * 步骤5: 获取Minecraft Access Token
 */
function getMinecraftToken($xstsToken, $uhs) {
    $result = msPost('https://api.minecraftservices.com/authentication/login_with_xbox', json_encode([
        'identityToken' => "XBL3.0 x={$uhs};{$xstsToken}",
    ]), [
        'Content-Type: application/json',
        'Accept: application/json',
    ]);

    if (isset($result['error'])) {
        return ['error' => 'MC token failed: ' . $result['error']];
    }
    if ($result['code'] !== 200) {
        return ['error' => 'MC token HTTP ' . $result['code'], 'response' => $result['body']];
    }

    $body = $result['body'];
    if (!isset($body['access_token'])) {
        return ['error' => 'No MC access_token', 'response' => $body];
    }

    return ['access_token' => $body['access_token']];
}

/**
 * 步骤6: 获取Minecraft Profile
 */
function getMinecraftProfile($mcAccessToken) {
    $result = msGet('https://api.minecraftservices.com/minecraft/profile', [
        'Authorization: Bearer ' . $mcAccessToken,
    ]);

    if (isset($result['error'])) {
        return ['error' => 'MC profile failed: ' . $result['error']];
    }
    if ($result['code'] === 404) {
        return ['error' => 'No Minecraft profile found (account may not own the game)'];
    }
    if ($result['code'] !== 200) {
        return ['error' => 'MC profile HTTP ' . $result['code'], 'response' => $result['body']];
    }

    $body = $result['body'];
    if (!isset($body['id']) || !isset($body['name'])) {
        return ['error' => 'Invalid MC profile', 'response' => $body];
    }

    // 格式化UUID（加横杠）
    $rawUuid = $body['id'];
    $formattedUuid = substr($rawUuid, 0, 8) . '-'
        . substr($rawUuid, 8, 4) . '-'
        . substr($rawUuid, 12, 4) . '-'
        . substr($rawUuid, 16, 4) . '-'
        . substr($rawUuid, 20, 12);

    return [
        'uuid' => $formattedUuid,
        'name' => $body['name'],
        'skins' => $body['skins'] ?? [],
    ];
}

/**
 * 完整OAuth流程（从授权码到Minecraft Profile）
 */
function completeAuthFlow($code) {
    // 步骤2: 交换MS Token
    $tokenResult = exchangeToken($code);
    if (isset($tokenResult['error'])) return $tokenResult;

    // 步骤3: Xbox Live Token
    $xboxResult = getXboxToken($tokenResult['access_token']);
    if (isset($xboxResult['error'])) return $xboxResult;

    // 步骤4: XSTS Token
    $xstsResult = getXstsToken($xboxResult['token'], $xboxResult['uhs']);
    if (isset($xstsResult['error'])) return $xstsResult;

    // 步骤5: Minecraft Token
    $mcResult = getMinecraftToken($xstsResult['token'], $xstsResult['uhs']);
    if (isset($mcResult['error'])) return $mcResult;

    // 步骤6: Minecraft Profile
    $profileResult = getMinecraftProfile($mcResult['access_token']);
    if (isset($profileResult['error'])) return $profileResult;

    return [
        'uuid' => $profileResult['uuid'],
        'name' => $profileResult['name'],
        'access_token' => $mcResult['access_token'],
    ];
}

// ====================================================================
//  Action处理
// ====================================================================

// ★ 检测Microsoft OAuth回调（带code+state，没有action参数）
$code = getParam('code');
$state = getParam('state');
$msError = getParam('error');
if (!empty($code) && !empty($state)) {
    // 自动路由到callback处理
    $action = 'callback';
} elseif (!empty($msError)) {
    // Microsoft返回错误（如invalid_scope）→ 路由到callback显示错误
    $action = 'callback';
} else {
    $action = getParam('action');
}

$db = getDb();
ensureTable($db);

switch ($action) {

    // ===== Java调用：创建验证会话 =====
    case 'create_session': {
        $player = getParam('player');
        $secret = getParam('secret');
        $ip = $_SERVER['REMOTE_ADDR'] ?? '';

        if (empty($player)) error('缺少player参数');
        if ($secret !== MC_AUTH_SECRET) error('密钥错误', 403);

        // 检查MS_CLIENT_ID是否配置（公共客户端不需要secret）
        if (empty(MS_CLIENT_ID)) {
            error('Microsoft OAuth未配置：缺少MS_CLIENT_ID，请在pay_secrets.php中设置');
        }

        // 清理该玩家的旧会话
        $stmt = $db->prepare("DELETE FROM mc_auth_sessions WHERE player_name = ? AND status != 'verified'");
        $stmt->execute([$player]);

        // 创建新会话
        $sessionId = generateSessionId();
        $now = time();
        $expiresAt = $now + 600; // 10分钟过期

        $stmt = $db->prepare("INSERT INTO mc_auth_sessions (session_id, player_name, status, ip_address, created_at, expires_at) VALUES (?, ?, 'pending', ?, ?, ?)");
        $stmt->execute([$sessionId, $player, $ip, $now, $expiresAt]);

        // 生成授权URL
        $authUrl = getAuthUrl($sessionId);

        success([
            'session_id' => $sessionId,
            'auth_url' => $authUrl,
            'paste_url' => 'https://caoyuan.ypshidifu.cn/plugin/api/minecraft_auth.php?action=paste_code_page&session_id=' . $sessionId,
            'expires_in' => 600,
        ]);
        break;
    }

    // ===== Java调用：检查会话状态 =====
    case 'check_session': {
        $sessionId = getParam('session_id');
        $secret = getParam('secret');

        if (empty($sessionId)) error('缺少session_id参数');
        if ($secret !== MC_AUTH_SECRET) error('密钥错误', 403);

        // 查询会话
        $stmt = $db->prepare("SELECT * FROM mc_auth_sessions WHERE session_id = ?");
        $stmt->execute([$sessionId]);
        $session = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$session) {
            error('会话不存在');
        }

        // 检查是否过期
        if ($session['status'] === 'pending' && time() > $session['expires_at']) {
            $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'expired' WHERE session_id = ?");
            $stmt->execute([$sessionId]);
            $session['status'] = 'expired';
        }

        $result = [
            'status' => $session['status'],
            'player_name' => $session['player_name'],
        ];

        if ($session['status'] === 'verified') {
            $result['mc_uuid'] = $session['mc_uuid'];
            $result['mc_username'] = $session['mc_username'];
            $result['verified_at'] = $session['verified_at'];
        } elseif ($session['status'] === 'failed') {
            $result['error'] = '验证失败，请重试';
        }

        success($result);
        break;
    }

    // ===== 浏览器回调：处理Microsoft OAuth =====
    case 'callback': {
        $code = getParam('code');
        $state = getParam('state'); // session_id
        $msError = getParam('error');

        // Microsoft返回错误
        if ($msError) {
            $errorDesc = getParam('error_description', 'Microsoft登录被拒绝');
            if ($state) {
                $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'failed' WHERE session_id = ?");
                $stmt->execute([$state]);
            }
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>验证失败</title>
            <style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#fff;}
            .card{background:#16213e;padding:40px;border-radius:16px;text-align:center;max-width:400px;}
            .icon{font-size:48px;margin-bottom:16px;}</style></head>
            <body><div class='card'><div class='icon'>❌</div><h2>验证失败</h2><p>" . htmlspecialchars($errorDesc) . "</p>
            <p style='color:#888;font-size:14px;'>请关闭此页面并返回游戏重试</p></div></body></html>";
            exit;
        }

        if (empty($code) || empty($state)) {
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>参数错误</title></head><body><h1>参数错误</h1></body></html>";
            exit;
        }

        // 查询会话
        $stmt = $db->prepare("SELECT * FROM mc_auth_sessions WHERE session_id = ?");
        $stmt->execute([$state]);
        $session = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$session) {
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>会话不存在</title></head><body><h1>会话不存在或已过期</h1></body></html>";
            exit;
        }

        if (time() > $session['expires_at']) {
            $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'expired' WHERE session_id = ?");
            $stmt->execute([$state]);
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>会话过期</title></head><body><h1>会话已过期，请返回游戏重新发起验证</h1></body></html>";
            exit;
        }

        // 执行完整OAuth流程
        $result = completeAuthFlow($code);

        if (isset($result['error'])) {
            // 验证失败
            $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'failed' WHERE session_id = ?");
            $stmt->execute([$state]);
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>验证失败</title>
            <style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#fff;}
            .card{background:#16213e;padding:40px;border-radius:16px;text-align:center;max-width:400px;}
            .icon{font-size:48px;margin-bottom:16px;}</style></head>
            <body><div class='card'><div class='icon'>❌</div><h2>验证失败</h2><p>" . htmlspecialchars($result['error']) . "</p>
            <p style='color:#888;font-size:14px;'>请关闭此页面并返回游戏重试</p></div></body></html>";
            exit;
        }

        // 验证成功！
        $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'verified', mc_uuid = ?, mc_username = ?, mc_access_token = ?, verified_at = ? WHERE session_id = ?");
        $stmt->execute([$result['uuid'], $result['name'], $result['access_token'], time(), $state]);

        // 显示成功页面
        $mcName = htmlspecialchars($result['name']);
        $mcUuid = htmlspecialchars($result['uuid']);
        echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>验证成功</title>
        <style>body{font-family:sans-serif;display:flex;justify-content:center;align-items:center;height:100vh;margin:0;background:#1a1a2e;color:#fff;}
        .card{background:#16213e;padding:40px;border-radius:16px;text-align:center;max-width:400px;}
        .icon{font-size:48px;margin-bottom:16px;}
        .name{font-size:24px;font-weight:bold;color:#4ade80;margin:8px 0;}
        .uuid{font-size:12px;color:#888;word-break:break-all;}</style></head>
        <body><div class='card'>
        <div class='icon'>✅</div>
        <h2>正版验证成功！</h2>
        <div class='name'>$mcName</div>
        <div class='uuid'>$mcUuid</div>
        <p style='margin-top:20px;color:#4ade80;'>请关闭此页面返回游戏</p>
        <p style='color:#888;font-size:14px;'>你的正版身份已验证，将自动登录服务器</p>
        </div></body></html>";
        exit;
    }

    // ===== 玩家手动粘贴授权码（公共客户端流程） =====
    case 'verify_code': {
        // 兼容JSON和表单两种POST方式
        $json = json_decode(file_get_contents('php://input'), true);
        $sessionId = postParam('session_id') ?: ($json['session_id'] ?? '');
        $code = postParam('code') ?: ($json['code'] ?? '');
        $secret = postParam('secret') ?: ($json['secret'] ?? '');

        if (empty($sessionId)) error('缺少session_id参数');
        if (empty($code)) error('缺少code参数（授权码）');

        // 密钥验证：Java调用需要secret，浏览器表单用session_id自身认证
        if (!empty($secret)) {
            if ($secret !== MC_AUTH_SECRET) error('密钥错误', 403);
        }

        // 查询会话
        $stmt = $db->prepare("SELECT * FROM mc_auth_sessions WHERE session_id = ?");
        $stmt->execute([$sessionId]);
        $session = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$session) error('会话不存在或已过期');
        if ($session['status'] !== 'pending') error('会话状态异常: ' . $session['status']);
        if (time() > $session['expires_at']) {
            $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'expired' WHERE session_id = ?");
            $stmt->execute([$sessionId]);
            error('会话已过期，请重新发起验证');
        }

        // 执行完整OAuth流程
        $result = completeAuthFlow($code);

        if (isset($result['error'])) {
            $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'failed' WHERE session_id = ?");
            $stmt->execute([$sessionId]);
            error('验证失败: ' . $result['error']);
        }

        // 验证成功
        $stmt = $db->prepare("UPDATE mc_auth_sessions SET status = 'verified', mc_uuid = ?, mc_username = ?, mc_access_token = ?, verified_at = ? WHERE session_id = ?");
        $stmt->execute([$result['uuid'], $result['name'], $result['access_token'], time(), $sessionId]);

        success([
            'status' => 'verified',
            'mc_uuid' => $result['uuid'],
            'mc_username' => $result['name'],
        ]);
        break;
    }

    // ===== Java调用：直接验证（用于验证Minecraft账号是否正版） =====
    case 'verify_premium': {
        $player = getParam('player');
        $secret = getParam('secret');

        if (empty($player)) error('缺少player参数');
        if ($secret !== MC_AUTH_SECRET) error('密钥错误', 403);

        // 通过Mojang API验证用户名是否对应正版账号
        $url = "https://api.mojang.com/users/profiles/minecraft/" . urlencode($player);
        $result = msGet($url);

        if (isset($result['error'])) {
            error('Mojang API请求失败: ' . $result['error']);
        }

        if ($result['code'] === 200 && isset($result['body']['id'])) {
            $rawUuid = $result['body']['id'];
            $formattedUuid = substr($rawUuid, 0, 8) . '-'
                . substr($rawUuid, 8, 4) . '-'
                . substr($rawUuid, 12, 4) . '-'
                . substr($rawUuid, 16, 4) . '-'
                . substr($rawUuid, 20, 12);

            success([
                'is_premium' => true,
                'uuid' => $formattedUuid,
                'name' => $result['body']['name'] ?? $player,
            ]);
        } else {
            success([
                'is_premium' => false,
                'uuid' => null,
                'name' => $player,
            ]);
        }
        break;
    }

    // ===== 网页粘贴授权码页面（公共客户端流程） =====
    case 'paste_code_page': {
        $sessionId = getParam('session_id');
        if (empty($sessionId)) {
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>参数错误</title></head><body><h1>缺少session_id参数</h1></body></html>";
            exit;
        }

        // 验证session存在
        $stmt = $db->prepare("SELECT * FROM mc_auth_sessions WHERE session_id = ?");
        $stmt->execute([$sessionId]);
        $session = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$session) {
            echo "<!DOCTYPE html><html><head><meta charset='utf-8'><title>会话不存在</title></head><body><h1>会话不存在或已过期</h1></body></html>";
            exit;
        }

        $playerName = htmlspecialchars($session['player_name']);
        echo <<<HTML
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Minecraft正版验证 - 粘贴授权码</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;justify-content:center;align-items:center;min-height:100vh;background:#0f0f23;color:#e0e0e0}
.card{background:#1a1a3e;padding:40px;border-radius:16px;text-align:center;max-width:420px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,0.4)}
h2{margin-bottom:8px;color:#4ade80}
.player{color:#888;font-size:14px;margin-bottom:24px}
.steps{text-align:left;background:#0f0f23;border-radius:8px;padding:16px;margin-bottom:24px;font-size:14px;line-height:1.8}
.steps li{margin-left:20px;color:#bbb}
.steps li b{color:#e0e0e0}
.input-group{margin-bottom:16px}
input[type=text]{width:100%;padding:14px 16px;font-size:16px;font-family:monospace;background:#0f0f23;border:2px solid #333;border-radius:8px;color:#4ade80;text-align:center;letter-spacing:2px;outline:none;transition:border-color 0.2s}
input[type=text]:focus{border-color:#4ade80}
button{width:100%;padding:14px;font-size:16px;font-weight:bold;background:#4ade80;color:#0f0f23;border:none;border-radius:8px;cursor:pointer;transition:all 0.2s}
button:hover{background:#22c55e;transform:translateY(-1px)}
button:disabled{opacity:0.5;cursor:not-allowed;transform:none}
.msg{margin-top:16px;font-size:14px;min-height:20px}
.msg.ok{color:#4ade80}
.msg.err{color:#f87171}
</style>
</head>
<body>
<div class="card">
<h2>🎮 正版验证</h2>
<p class="player">玩家: <b>$playerName</b></p>
<ol class="steps">
<li>在上方Microsoft页面<b>复制授权码</b></li>
<li>将授权码<b>粘贴</b>到下方输入框</li>
<li>点击"提交验证"</li>
</ol>
<div class="input-group">
<input type="text" id="codeInput" placeholder="粘贴授权码到这里" autofocus autocomplete="off">
</div>
<button id="submitBtn" onclick="submitCode()">提交验证</button>
<div class="msg" id="msg"></div>
</div>
<script>
var sessionId="{$sessionId}";
function submitCode(){
var code=document.getElementById('codeInput').value.trim();
if(!code){showMsg('请输入授权码','err');return}
var btn=document.getElementById('submitBtn');
btn.disabled=true;btn.textContent='验证中...';
var xhr=new XMLHttpRequest();
xhr.open('POST','minecraft_auth.php?action=verify_code',true);
xhr.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
xhr.onreadystatechange=function(){
if(xhr.readyState===4){
try{var d=JSON.parse(xhr.responseText);
if(d.success){showMsg('✅ 验证成功！请关闭此页面返回游戏','ok');}
else{showMsg('❌ '+(d.error||'验证失败'),'err');btn.disabled=false;btn.textContent='提交验证';}
}catch(e){showMsg('❌ 服务器响应异常','err');btn.disabled=false;btn.textContent='提交验证';}
}};
xhr.send('session_id='+encodeURIComponent(sessionId)+'&code='+encodeURIComponent(code));
}
function showMsg(t,c){var m=document.getElementById('msg');m.textContent=t;m.className='msg '+c;}
</script>
</body>
</html>
HTML;
        exit;
    }

    default:
        error('未知action: ' . $action);
}
