<?php
/**
 * 玩家 2FA (TOTP) 二维码展示中转 —— PHP 只做显示器
 *
 * 2FA 的全部逻辑（生成密钥 / 动态码校验 / 绑定 / 解绑）都在 Java 端本地完成，
 * 这里只负责暂存 otpauth 载荷供玩家浏览器渲染二维码，10 分钟自动过期。
 *
 * Java -> PHP（secret 校验）：
 *   push_qr   存一条二维码载荷（token -> otpauth URI）
 * 玩家浏览器（持有随机 token 即可读）：
 *   get       按 t 取回 otpauth URI
 */

while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

require_once __DIR__ . '/../core.php';

/** 载荷有效期（秒），与 Java 端 twofa_pending_at 的 10 分钟对齐 */
define('TWOFA_QR_TTL', 600);

function twofaEnsureTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS web_twofa_qr (
        token TEXT PRIMARY KEY,
        player TEXT NOT NULL,
        otpauth TEXT NOT NULL,
        expires_at INTEGER NOT NULL
    )");
}

function twofaClean($db) {
    $db->exec("DELETE FROM web_twofa_qr WHERE expires_at < " . time());
}

/** Java 侧通信校验（与 api/sn.php 同款） */
function twofaRequireSecret() {
    $secret = getParam('secret', '');
    if ($secret === '' || $secret !== SECRET_KEY) {
        error('密钥错误', 403);
    }
}

/** token 形态：64 位十六进制（Java SecureRandom 256bit） */
function twofaValidToken($t) {
    return is_string($t) && preg_match('/^[a-f0-9]{32,128}$/', $t) === 1;
}

$action = getParam('action', '');

try {
    switch ($action) {

        // ===== Java -> PHP：存二维码载荷 =====
        case 'push_qr': {
            twofaRequireSecret();
            $token   = (string) getParam('token', '');
            $player  = (string) getParam('player', '');
            $otpauth = (string) getParam('otpauth', '');

            if (!twofaValidToken($token)) error('token格式错误', 400);
            if ($player === '' || strlen($player) > 32) error('玩家名不合法', 400);
            if (strpos($otpauth, 'otpauth://totp/') !== 0 || strlen($otpauth) > 512) {
                error('otpauth载荷不合法', 400);
            }

            $db = getDB();
            twofaEnsureTable($db);
            twofaClean($db);

            $stmt = $db->prepare("INSERT OR REPLACE INTO web_twofa_qr
                (token, player, otpauth, expires_at) VALUES (:t, :p, :o, :e)");
            $stmt->bindValue(':t', $token, SQLITE3_TEXT);
            $stmt->bindValue(':p', $player, SQLITE3_TEXT);
            $stmt->bindValue(':o', $otpauth, SQLITE3_TEXT);
            $stmt->bindValue(':e', time() + TWOFA_QR_TTL, SQLITE3_INTEGER);
            $stmt->execute();

            success(['expires_in' => TWOFA_QR_TTL], 'stored');
            break;
        }

        // ===== 玩家浏览器：取二维码载荷 =====
        case 'get': {
            $token = (string) getParam('t', '');
            if (!twofaValidToken($token)) error('参数错误', 400);

            $db = getDB();
            twofaEnsureTable($db);
            twofaClean($db);

            $stmt = $db->prepare("SELECT player, otpauth, expires_at
                FROM web_twofa_qr WHERE token = :t");
            $stmt->bindValue(':t', $token, SQLITE3_TEXT);
            $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

            if (!$row) error('二维码不存在或已过期', 404);

            success([
                'player'     => $row['player'],
                'otpauth'    => $row['otpauth'],
                'expires_at' => (int) $row['expires_at'],
            ]);
            break;
        }

        default:
            error('未知操作', 400);
    }
} catch (\Throwable $e) {
    debugLog('twofa异常: ' . $e->getMessage(), ['action' => $action]);
    error('服务器内部错误', 500);
}
