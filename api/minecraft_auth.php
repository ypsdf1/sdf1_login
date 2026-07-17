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
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_POST => true,
        CURLOPT_POSTFIELDS => is_array($data) ? http_build_query($data) : $data,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 30,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_HTTPHEADER => array_merge([
            'Content-Type: application/x-www-form-urlencoded',
            'Accept: application/json',
        ], $headers),
    ]);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $error = curl_error($ch);
    curl_close($ch);

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
 */
function getAuthUrl($sessionId) {
    $clientId = MS_CLIENT_ID;
    $redirectUri = MS_REDIRECT_URI;
    $scope = 'service::user.auth.xboxlive.com::MBI_SSL';

    $params = http_build_query([
        'client_id' => $clientId,
        'response_type' => 'code',
        'scope' => $scope,
        'redirect_uri' => $redirectUri,
        'state' => $sessionId,
    ]);

    return "https://login.live.com/oauth20_authorize.srf?$params";
}

/**
 * 步骤2: 用授权码交换Access Token
 */
function exchangeToken($code) {
    $result = msPost('https://login.live.com/oauth20_token.srf', [
        'client_id' => MS_CLIENT_ID,
        'client_secret' => MS_CLIENT_SECRET,
        'code' => $code,
        'grant_type' => 'authorization_code',
        'redirect_uri' => MS_REDIRECT_URI,
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
 */
function getXboxToken($msAccessToken) {
    $result = msPost('https://user.auth.xboxlive.com/user/authenticate', json_encode([
        'Properties' => [
            'AuthMethod' => 'RPS',
            'SiteName' => 'user.auth.xboxlive.com',
            'RpsTicket' => $msAccessToken,
        ],
        'RelyingParty' => 'http://auth.xboxlive.com',
        'TokenType' => 'JWT',
    ]), [
        'Content-Type: application/json',
        'Accept: application/json',
    ]);

    if (isset($result['error'])) {
        return ['error' => 'Xbox auth failed: ' . $result['error']];
    }
    if ($result['code'] !== 200) {
        return ['error' => 'Xbox auth HTTP ' . $result['code']];
    }

    $body = $result['body'];
    if (!isset($body['Token'])) {
        return ['error' => 'No Xbox token', 'response' => $body];
    }

    return [
        'token' => $body['Token'],
        'uhs' => $body['IssueClaims']['uhs'] ?? '',
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
if (!empty($code) && !empty($state)) {
    // 自动路由到callback处理
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

        // 检查MS_CLIENT_SECRET是否配置
        if (empty(MS_CLIENT_SECRET)) {
            error('Microsoft OAuth未配置：缺少MS_CLIENT_SECRET，请在pay_secrets.php中设置');
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

    default:
        error('未知action: ' . $action);
}
