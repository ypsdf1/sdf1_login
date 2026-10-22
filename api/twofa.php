<?php
/**
 * 玩家 2FA (TOTP) 二维码展示中转 + 绑定状态双向同步（第47批）—— PHP 只做显示器与镜像
 *
 * 2FA 的动态码校验逻辑在 Java 端与 PHP 端各自本地完成（同一 Base32 密钥），
 * 这里负责：
 *   1) 暂存 otpauth 载荷供玩家浏览器渲染二维码，10 分钟自动过期
 *   2) 以 web_account_2fa 为持久镜像，供 Java 端拉取/推送绑定状态
 *      （修复：网页端绑定后游戏内 /2fa 显示"未绑定"）
 *
 * Java -> PHP（secret 校验）：
 *   push_qr     存一条二维码载荷（token -> otpauth URI）
 *   pull_state  拉取该玩家网页端绑定状态（bound/secret/bound_at/updated_at）
 *   push_state  推送 Java 端绑定/解绑结果（网页端无记录或记录更旧时落库）
 * 玩家浏览器（持有随机 token 即可读）：
 *   get         按 t 取回 otpauth URI
 *
 * 同步语义见 Java 端 TwoFactorManager.applyWebState()：
 *   单向导入（网页绑定游戏未绑定）/ 解绑传播（网页解绑且更晚）/
 *   密钥冲突取时间新者；恢复代码两端格式不同（网页16位明文 vs 游戏6位哈希），各自维护。
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

/** token 形态：64 位十六进制（Java SecureRandom 256bit）。
 *  WAF 实测（2026-10-01 矩阵含重复验证）：查询参数名 `token` + ≥32位纯hex 会被
 *  CF 源站 404 拦截；参数名 `t` 任意长度(16/32/48/64)均可过，POST body 亦可过。
 *  故取数接口固定用 `t=`（`token=` 仅作兼容回退）。 */
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
            // 主参数名必须是 t：`token=<32+位hex>` 会被源站 WAF 404，`t=<hex>` 实测全长度可过
            $token = (string) getParam('t', '');
            if ($token === '') $token = (string) getParam('token', '');
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

        // ===== Java -> PHP：拉取网页端 2FA 绑定状态（第47批双向同步） =====
        case 'pull_state': {
            twofaRequireSecret();
            $player = (string) getParam('player', '');
            if ($player === '' || strlen($player) > 32) error('玩家名不合法', 400);

            $db = getDB();
            $stmt = $db->prepare("SELECT totp_secret, bound_at, updated_at
                FROM web_account_2fa WHERE player_name = :p");
            $stmt->bindValue(':p', $player, SQLITE3_TEXT);
            $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

            if (!$row) {
                success(['exists' => false, 'bound' => false,
                         'secret' => '', 'bound_at' => 0, 'updated_at' => 0]);
            }
            $bound = ((int) $row['bound_at'] > 0 && (string) $row['totp_secret'] !== '');
            success([
                'exists'     => true,
                'bound'      => $bound,
                // 明文密钥仅回给持 SECRET_KEY 的 Java 服务端，绝不出现在浏览器接口
                'secret'     => $bound ? (string) $row['totp_secret'] : '',
                'bound_at'   => (int) $row['bound_at'],
                'updated_at' => (int) $row['updated_at'],
            ]);
            break;
        }

        // ===== Java -> PHP：推送 Java 端绑定/解绑结果（第47批双向同步） =====
        case 'push_state': {
            twofaRequireSecret();
            $player  = (string) getParam('player', '');
            $bound   = (string) getParam('bound', '') === '1';
            // ★ TOTP 密钥参数名必须是 twofa_secret：'secret' 已被通信密钥占用
            $secret  = (string) getParam('twofa_secret', '');
            $boundAt = (int) getParam('bound_at', '0');
            if ($player === '' || strlen($player) > 32) error('玩家名不合法', 400);
            if ($bound && !preg_match('/^[A-Z2-7]{16,64}$/', $secret)) {
                error('密钥格式错误', 400);
            }

            $db = getDB();
            $stmt = $db->prepare("SELECT bound_at, recovery_codes
                FROM web_account_2fa WHERE player_name = :p");
            $stmt->bindValue(':p', $player, SQLITE3_TEXT);
            $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
            $existingAt = $row ? (int) $row['bound_at'] : -1;   // -1 = 无记录

            $now = time();
            if ($bound) {
                // 仅当网页端无记录、未绑定、或绑定时间更旧时覆盖；
                // 网页端已绑定且不比我们旧 → 不动（网页端用户主动操作优先）
                $webNewer = ($existingAt > 0 && $boundAt > 0 && $existingAt >= $boundAt);
                if ($webNewer) {
                    success(['applied' => false, 'reason' => 'web_side_newer']);
                }
                // 游戏端恢复代码是 SHA-256 哈希且格式与网页端（16位明文 JSON）不兼容，
                // 推送时清空网页端恢复代码列表，避免网页端用旧代码通过第二层验证
                $stmt = $db->prepare("INSERT INTO web_account_2fa
                        (player_name, totp_secret, recovery_codes, bound_at, updated_at)
                      VALUES (:p, :s, '', :b, :u)
                      ON CONFLICT(player_name) DO UPDATE SET
                        totp_secret = :s, recovery_codes = '',
                        bound_at = :b, updated_at = :u");
                $stmt->bindValue(':p', $player, SQLITE3_TEXT);
                $stmt->bindValue(':s', $secret, SQLITE3_TEXT);
                $stmt->bindValue(':b', $boundAt > 0 ? $boundAt : $now, SQLITE3_INTEGER);
                $stmt->bindValue(':u', $now, SQLITE3_INTEGER);
                $stmt->execute();
                success(['applied' => true, 'action' => 'bound']);
            } else {
                // 解绑：仅当网页端记录不比我们新时清空（防覆盖网页端更晚的重新绑定）
                $webNewer = ($existingAt > 0 && $boundAt > 0 && $existingAt > $boundAt);
                if ($webNewer) {
                    success(['applied' => false, 'reason' => 'web_side_newer']);
                }
                if ($existingAt === 0) {
                    success(['applied' => false, 'reason' => 'already_unbound']);
                }
                if ($existingAt > 0) {
                    $stmt = $db->prepare("UPDATE web_account_2fa SET
                        totp_secret = '', recovery_codes = '', bound_at = 0, updated_at = :u
                        WHERE player_name = :p");
                    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
                    $stmt->bindValue(':u', $now, SQLITE3_INTEGER);
                    $stmt->execute();
                }
                success(['applied' => true, 'action' => 'unbound']);
            }
            break;
        }

        default:
            error('未知操作', 400);
    }
} catch (\Throwable $e) {
    debugLog('twofa异常: ' . $e->getMessage(), ['action' => $action]);
    error('服务器内部错误', 500);
}
