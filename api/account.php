<?php
/**
 * 玩家端账号安全 API（双层验证 · 2026-10-22 第44批）
 *
 * ★ 两层验证模型（任何改信息操作前强制完成）：
 *   第一层（强制）：输入一次完整主密码（临时密码不算）→ verify_start 签发 challenge（5分钟）
 *   第二层（三选一）：邮箱验证码 / 认证器动态码(TOTP) / 恢复代码 → verify_step2 换 ticket（10分钟）
 *   变更类操作凭 ticket 执行；ticket 一次性（totp_setup 例外：只校验不消耗，扫码确认要两次调用）。
 *
 * ★ Actions（全部需要 web_token，建议 POST JSON，避免密码进 access log）：
 *   status              账号安全状态（是否绑邮箱/2FA/恢复代码/当前可用第二层方式）
 *   verify_start        第一层：password → challenge + methods
 *   send_email_code     challenge 后向绑定邮箱发 6 位码（60秒间隔 + 10次/小时限频）
 *   verify_step2        challenge + email_code|totp_code|recovery_code → ticket
 *   change_password     ticket + new_password → 改登录密码（顺带清临时密码）
 *   totp_setup          ticket → 生成待确认密钥 + otpauth（不消耗 ticket）
 *   totp_bind           ticket + code → 确认绑定，一次性返回 10 组恢复代码
 *   totp_unbind         ticket → 解绑（恢复代码一并清空）
 *   recovery_list       ticket → 查看恢复代码（明文）
 *   recovery_regenerate ticket → 重新生成恢复代码（旧的立即作废）
 *
 * ★ 安全与取舍（如实记录）：
 *   - security.php 尾部有顶层 secEmergencyGate()/secRateLimitGate()，不能直接 require，
 *     故 secB32 与 secTotp 系列函数以副本内置（来源 security.php:717-841 行，逻辑一字不改）。
 *   - 邮件发送失败绝不回显验证码（回显=只要密码就能过第二层，形同单层）。
 *   - 恢复代码/TOTP 密钥明文存库：「页内查看恢复代码」「展示绑定二维码」功能必须明文，
 *     库文件在站点私有目录，风险在此声明。
 *   - 未绑邮箱且未绑 2FA 的账号没有第二层可过 → status 返回 can_verify=false 引导先绑邮箱。
 *   - 猜测限速：db/acct_state.json —— 密码 5次/5分、第二层 5次/5分、发码 10次/小时+60秒间隔、
 *     绑定动态码 5次/5分；失败计数在验证成功时清零。
 */
// 防止任何输出污染 JSON 响应
ob_start();
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
require_once __DIR__ . '/../core.php';
while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');

if (!function_exists('error') || !function_exists('success') || !function_exists('getParam')) {
    while (ob_get_level() > 0) { @ob_end_clean(); }
    echo json_encode(['success' => false, 'message' => 'core.php load failed'], JSON_UNESCAPED_UNICODE);
    exit;
}

$action = getParam('action', 'status');

try {
    acctEnsureTables();

    switch ($action) {
        case 'status':              acctStatus(); break;
        case 'verify_start':        acctVerifyStart(); break;
        case 'send_email_code':     acctSendEmailCode(); break;
        case 'verify_step2':        acctVerifyStep2(); break;
        case 'change_password':     acctChangePassword(); break;
        case 'totp_setup':          acctTotpSetup(); break;
        case 'totp_bind':           acctTotpBind(); break;
        case 'totp_unbind':         acctTotpUnbind(); break;
        case 'recovery_list':       acctRecoveryList(); break;
        case 'recovery_regenerate': acctRecoveryRegen(); break;
        default:
            error('未知操作: ' . $action);
    }

    try { if (function_exists('walCheckpoint')) { walCheckpoint(); } } catch (\Throwable $ignored) {}

} catch (\Throwable $e) {
    while (ob_get_level() > 0) { @ob_end_clean(); }
    if (function_exists('debugLog')) { debugLog('[account.php] EXCEPTION: ' . $e->getMessage()); }
    error('服务器内部错误: ' . $e->getMessage(), 500);
}

// ===================== TOTP 基础（副本来自 security.php 717-841 行） =====================

/** Base32 解码（含 0/1/8/9 易混字符归一，逻辑同 security.php secB32Decode） */
function acctB32Decode($s) {
    $s = strtoupper((string)$s);
    $s = str_replace(array('0', '1', '8', '9'), array('O', 'I', 'B', 'G'), $s);
    $s = preg_replace('/[^A-Z2-7]/', '', $s);
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    $buf = 0; $bits = 0; $out = '';
    for ($i = 0; $i < strlen($s); $i++) {
        $v = strpos($alphabet, $s[$i]);
        if ($v === false) continue;
        $buf = ($buf << 5) | $v;
        $bits += 5;
        if ($bits >= 8) {
            $bits -= 8;
            $out .= chr(($buf >> $bits) & 0xFF);
        }
    }
    return $out;
}

function acctB32Encode($data) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
    $out = ''; $buf = 0; $bits = 0;
    for ($i = 0; $i < strlen($data); $i++) {
        $buf = ($buf << 8) | ord($data[$i]);
        $bits += 8;
        while ($bits >= 5) {
            $bits -= 5;
            $out .= $alphabet[($buf >> $bits) & 31];
        }
    }
    if ($bits > 0) $out .= $alphabet[($buf << (5 - $bits)) & 31];
    return $out;
}

/** 生成 20 字节随机密钥（Base32 无填充，认证器通用格式）。 */
function acctNewSecret() {
    return acctB32Encode(random_bytes(20));
}

/** 计算某一时刻的 6 位动态码（SHA1/30秒/RFC6238）。 */
function acctTotp($secret, $time = null, $step = 30, $digits = 6) {
    $key = acctB32Decode($secret);
    if ($key === '') return '';
    $t = ($time === null) ? time() : (int)$time;
    $counter = (int)floor($t / $step);
    $bin = '';
    for ($i = 7; $i >= 0; $i--) {
        $bin .= chr(($counter >> ($i * 8)) & 0xFF);
    }
    $hash = hash_hmac('sha1', $bin, $key, true);
    $offset = ord($hash[19]) & 0x0F;
    $code = ((ord($hash[$offset]) & 0x7F) << 24)
          | (ord($hash[$offset + 1]) << 16)
          | (ord($hash[$offset + 2]) << 8)
          | (ord($hash[$offset + 3]));
    $code = $code % (int)pow(10, $digits);
    return str_pad((string)$code, $digits, '0', STR_PAD_LEFT);
}

/** 校验动态码，window=3 → 接受 ±90 秒时钟漂移（同 security.php secTotpVerify）。 */
function acctTotpVerify($secret, $code, $window = 3) {
    $code = trim((string)$code);
    if (!preg_match('/^\d{6}$/', $code)) return false;
    if (!$secret) return false;
    $now = time();
    for ($i = -$window; $i <= $window; $i++) {
        if (hash_equals(acctTotp($secret, $now + $i * 30), $code)) return true;
    }
    return false;
}

/** 认证器绑定用 otpauth 链接（label 带玩家名，便于多账号区分）。 */
function acctOtpauthUri($secret, $player) {
    $label = rawurlencode('SDF1:' . $player);
    $issuer = rawurlencode('SDF1');
    return 'otpauth://totp/' . $label . '?secret=' . $secret
         . '&issuer=' . $issuer . '&algorithm=SHA1&digits=6&period=30';
}

// ===================== 猜测限速（db/acct_state.json，文件锁） =====================

function acctStateFile() {
    return __DIR__ . '/../db/acct_state.json';
}

/**
 * 记录一次尝试并返回当前锁定剩余秒数（0 = 未锁定）。
 * 逻辑同 security.php secThrottleFail，另加全局过期清理防状态文件膨胀。
 */
function acctThrottleFail($bucket, $key, $max, $win) {
    $f = acctStateFile();
    $dir = dirname($f);
    if (!is_dir($dir)) { @mkdir($dir, 0755, true); }
    $fp = @fopen($f, 'c+');
    if (!$fp) return 0;
    flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $state = json_decode($raw, true);
    if (!is_array($state)) $state = array();
    $now = time();
    $k = $bucket . '|' . $key;
    $arr = isset($state[$k]) && is_array($state[$k]) ? $state[$k] : array();
    $arr[] = $now;
    $fresh = array();
    foreach ($arr as $ts) { if ((int)$ts > $now - $win) $fresh[] = (int)$ts; }
    $state[$k] = $fresh;
    // 全量顺手清理：条目过多或整文件超过 64KB 时剔除窗口外记录
    if (count($state) > 500 || @filesize($f) > 65536) {
        foreach ($state as $sk => $sts) {
            if (!is_array($sts)) { unset($state[$sk]); continue; }
            $keep = array();
            foreach ($sts as $ts) { if ((int)$ts > $now - 3600) $keep[] = (int)$ts; }
            if ($keep) { $state[$sk] = $keep; } else { unset($state[$sk]); }
        }
        if (isset($state[$k])) { $state[$k] = $fresh; }
    }
    ftruncate($fp, 0); rewind($fp);
    fwrite($fp, json_encode($state, JSON_UNESCAPED_UNICODE));
    fflush($fp); flock($fp, LOCK_UN); fclose($fp);
    if (count($fresh) >= $max) {
        return max(1, ($fresh[0] + $win) - $now);
    }
    return 0;
}

/** 只读查询：当前是否被锁定，返回剩余秒数（0 = 未锁定）。 */
function acctThrottleLocked($bucket, $key, $max, $win) {
    $f = acctStateFile();
    if (!file_exists($f)) return 0;
    $fp = @fopen($f, 'r');
    if (!$fp) return 0;
    flock($fp, LOCK_SH);
    $raw = stream_get_contents($fp);
    flock($fp, LOCK_UN); fclose($fp);
    $state = json_decode($raw, true);
    if (!is_array($state)) return 0;
    $k = $bucket . '|' . $key;
    if (!isset($state[$k]) || !is_array($state[$k])) return 0;
    $now = time();
    $fresh = array();
    foreach ($state[$k] as $ts) { if ((int)$ts > $now - $win) $fresh[] = (int)$ts; }
    if (count($fresh) >= $max) {
        return max(1, ($fresh[0] + $win) - $now);
    }
    return 0;
}

/** 验证成功后清除该 key 的失败计数。 */
function acctThrottleReset($bucket, $key) {
    $f = acctStateFile();
    if (!file_exists($f)) return;
    $fp = @fopen($f, 'r+');
    if (!$fp) return;
    flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $state = json_decode($raw, true);
    if (is_array($state)) {
        $k = $bucket . '|' . $key;
        if (isset($state[$k])) {
            unset($state[$k]);
            ftruncate($fp, 0); rewind($fp);
            fwrite($fp, json_encode($state, JSON_UNESCAPED_UNICODE));
            fflush($fp);
        }
    }
    flock($fp, LOCK_UN); fclose($fp);
}

// ===================== 建表 =====================

function acctEnsureTables() {
    $db = getDB();
    // 2FA 状态：totp_secret / recovery_codes 明文存（页内查看与二维码展示必须明文，见文件头声明）
    $db->exec("CREATE TABLE IF NOT EXISTS web_account_2fa (
        player_name TEXT PRIMARY KEY,
        totp_secret TEXT DEFAULT '',
        recovery_codes TEXT DEFAULT '',
        bound_at INTEGER DEFAULT 0,
        updated_at INTEGER DEFAULT 0
    )");
    // 验证会话：challenge（第一层后，5分钟）/ ticket（两层过后，10分钟一次性）
    $db->exec("CREATE TABLE IF NOT EXISTS web_account_verify (
        token TEXT PRIMARY KEY,
        player_name TEXT NOT NULL,
        kind TEXT NOT NULL,
        methods TEXT DEFAULT '',
        ip TEXT DEFAULT '',
        fail_count INTEGER DEFAULT 0,
        used INTEGER DEFAULT 0,
        expire_at INTEGER NOT NULL,
        created_at INTEGER DEFAULT 0
    )");
    // 顺手清理过期超过 1 天的会话行
    $db->exec("DELETE FROM web_account_verify WHERE expire_at < " . (time() - 86400));
}

// ===================== 公共辅助 =====================

/** 邮箱脱敏：123@456.com → xx@456.com（同 sync.php maskEmail，仅显示域名） */
function acctMaskEmail($email) {
    if (empty($email)) return '';
    $parts = explode('@', $email);
    if (count($parts) !== 2) return 'xx@' . ($parts[0] ?? '*');
    return 'xx@' . $parts[1];
}

/**
 * web_token → 玩家名。缺 token / 过期 / 封禁 / 拉黑 / 未注册一律直接 error()。
 * @return string player_name
 */
function acctAuthPlayer() {
    $webToken = getParam('web_token');
    if (!$webToken) error('缺少web_token参数，请重新登录', 401);
    $info = validateTokenSilent($webToken);
    if (!$info) error('登录已过期，请重新登录', 401);
    $player = $info['player'];
    $ban = playerWebBanReason($player);
    if ($ban !== null) error('账号已被封禁：' . $ban, 403);
    $blk = playerWebBlacklistReason($player);
    if ($blk !== null) error('你已被拉黑，无法进行账号安全操作', 403);
    if (!isPlayerRegistered($player)) error('玩家未注册', 403);
    return $player;
}

/** 取主密码凭证行（只认 password_hash+salt，同 core.php 3053 行验证模板）。 */
function acctCredentials($player) {
    $db = getDB();
    $stmt = $db->prepare("SELECT password_hash, salt, temp_password_hash, temp_pw_expire FROM weblogin_credentials WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    return $row ? $row : null;
}

/** 第一层密码比对：只认主密码（"完整密码"语义，临时密码不算）。 */
function acctPwdCheck($cred, $pwd) {
    if (!$cred || !isset($cred['password_hash']) || $cred['password_hash'] === '') return false;
    $saltBytes = saltDecode((string)$cred['salt']);   // ★第48批：容错解盐（core.php saltDecode）
    if ($saltBytes === false || $saltBytes === '') return false;
    $calc = base64_encode(hash('sha256', $saltBytes . $pwd, true));
    return hash_equals(b64Clean((string)$cred['password_hash']), $calc);   // ★第52批：存量 \/ 脏值容错
}

function acctEmailOf($player) {
    $db = getDB();
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :p");
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    return ($row && isset($row['email'])) ? (string)$row['email'] : '';
}

function acctTwoFa($player) {
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_account_2fa WHERE player_name = :p");
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    return $row ? $row : null;
}

/** 当前实时可用的第二层方式：email / totp / recovery。 */
function acctMethods($player, $email) {
    $m = array();
    if ($email !== '') $m[] = 'email';
    $t = acctTwoFa($player);
    if ($t && (int)$t['bound_at'] > 0 && (string)$t['totp_secret'] !== '') {
        $m[] = 'totp';
        if ((string)$t['recovery_codes'] !== '') $m[] = 'recovery';
    }
    return $m;
}

/**
 * 取回并校验 challenge/ticket，不通过直接 error()。
 * @param string $token 会话 token
 * @param string $kind  challenge|ticket
 * @param string $player 期望玩家名（跨账号使用会话直接拒绝）
 */
function acctLoadToken($token, $kind, $player) {
    $label = ($kind === 'challenge') ? '第一层验证会话' : '验证凭据';
    if (!$token) error('缺少' . $label . '，请重新开始验证', 401);
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_account_verify WHERE token = :t AND kind = :k");
    $stmt->bindValue(':t', $token, SQLITE3_TEXT);
    $stmt->bindValue(':k', $kind, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    if (!$row) error($label . '不存在，请重新开始验证', 401);
    if ((int)$row['used'] !== 0) error($label . '已使用，请重新开始验证', 401);
    if ((int)$row['expire_at'] < time()) error($label . '已过期，请重新开始验证', 401);
    if ($player !== null && $row['player_name'] !== $player) error($label . '与当前账号不匹配', 403);
    return $row;
}

function acctConsumeToken($token) {
    $db = getDB();
    $stmt = $db->prepare("UPDATE web_account_verify SET used = 1 WHERE token = :t");
    $stmt->bindValue(':t', $token, SQLITE3_TEXT);
    $stmt->execute();
}

/** 签发新会话，并作废该玩家所有未使用的旧会话（同一时间只保留一个有效会话）。 */
function acctIssueToken($player, $kind, $ttl, $methodsJson, $ip) {
    $db = getDB();
    // 作废旧会话只标记不删除（保留审计痕迹；过期行由 acctEnsureTables 定期清理）
    $upd = $db->prepare("UPDATE web_account_verify SET used = 1 WHERE player_name = :p AND used = 0");
    $upd->bindValue(':p', $player, SQLITE3_TEXT);
    $upd->execute();
    $token = bin2hex(random_bytes(24));
    $stmt = $db->prepare("INSERT INTO web_account_verify (token, player_name, kind, methods, ip, fail_count, used, expire_at, created_at)
                          VALUES (:t, :p, :k, :m, :ip, 0, 0, :e, :c)");
    $stmt->bindValue(':t', $token, SQLITE3_TEXT);
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $stmt->bindValue(':k', $kind, SQLITE3_TEXT);
    $stmt->bindValue(':m', $methodsJson, SQLITE3_TEXT);
    $stmt->bindValue(':ip', (string)$ip, SQLITE3_TEXT);
    $stmt->bindValue(':e', time() + (int)$ttl, SQLITE3_INTEGER);
    $stmt->bindValue(':c', time(), SQLITE3_INTEGER);
    $stmt->execute();
    return $token;
}

function acctClientIp() {
    return isset($_SERVER['REMOTE_ADDR']) ? (string)$_SERVER['REMOTE_ADDR'] : '';
}

/** 恢复代码字母表：26 字母 + 234567（32 字符）；0/1/8/9/O/I 全部排除，输入按易混归一。 */
function acctRecoveryAlpha() {
    return 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
}

function acctGenRecoveryCodes() {
    $alpha = acctRecoveryAlpha();
    $n = strlen($alpha);
    $codes = array();
    for ($i = 0; $i < 10; $i++) {
        $s = '';
        for ($j = 0; $j < 16; $j++) {
            $s .= $alpha[random_int(0, $n - 1)];
        }
        $codes[] = substr($s, 0, 4) . '-' . substr($s, 4, 4) . '-' . substr($s, 8, 4) . '-' . substr($s, 12, 4);
    }
    return $codes;
}

/** 恢复代码归一：大写 + 易混字符归一（0→O 1→I 8→B 9→G）+ 去掉非字母数字。 */
function acctNormRecovery($s) {
    $s = strtoupper((string)$s);
    $s = str_replace(array('0', '1', '8', '9'), array('O', 'I', 'B', 'G'), $s);
    $s = preg_replace('/[^A-Z0-9]/', '', $s);
    return $s;
}

function acctSaveTwoFa($player, $secret, $codesJson, $boundAt) {
    $db = getDB();
    $stmt = $db->prepare("INSERT INTO web_account_2fa (player_name, totp_secret, recovery_codes, bound_at, updated_at)
                          VALUES (:p, :s, :r, :b, :u)
                          ON CONFLICT(player_name) DO UPDATE SET totp_secret = :s, recovery_codes = :r, bound_at = :b, updated_at = :u");
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $stmt->bindValue(':s', (string)$secret, SQLITE3_TEXT);
    $stmt->bindValue(':r', (string)$codesJson, SQLITE3_TEXT);
    $stmt->bindValue(':b', (int)$boundAt, SQLITE3_INTEGER);
    $stmt->bindValue(':u', time(), SQLITE3_INTEGER);
    $stmt->execute();
}

// ===================== Actions =====================

/** 账号安全状态：前端渲染面板 + 第二层可用方式清单 */
function acctStatus() {
    $player = acctAuthPlayer();
    $email = acctEmailOf($player);
    $t = acctTwoFa($player);
    $bound = $t && (int)$t['bound_at'] > 0 && (string)$t['totp_secret'] !== '';
    $hasRecovery = $bound && $t && (string)$t['recovery_codes'] !== '';
    $methods = acctMethods($player, $email);

    $hint = '';
    if (!$methods) {
        $hint = '账号未绑定邮箱，也未绑定二次验证器 —— 无法完成第二层验证。请先在游戏中使用 /bindemail 绑定邮箱，否则无法修改密码等账号信息。';
    }

    success([
        'player' => $player,
        'has_email' => ($email !== ''),
        'email_masked' => acctMaskEmail($email),
        'twofa_bound' => (bool)$bound,
        'has_recovery' => (bool)$hasRecovery,
        'pending_setup' => ($t && (int)$t['bound_at'] === 0 && (string)$t['totp_secret'] !== ''),
        'methods' => $methods,
        'can_verify' => (bool)$methods,
        'hint' => $hint,
    ], 'ok');
}

/** 第一层：完整主密码 → challenge（5分钟）+ 第二层可用方式快照 */
function acctVerifyStart() {
    $player = acctAuthPlayer();
    $pwd = (string)getParam('password', '');
    if ($pwd === '') error('请输入密码');

    $locked = acctThrottleLocked('pwd', $player, 5, 300);
    if ($locked > 0) error('密码错误次数过多，请 ' . $locked . ' 秒后再试', 429);

    $cred = acctCredentials($player);
    if (!$cred) error('账号未设置密码，请先在游戏中完成注册');
    if (!acctPwdCheck($cred, $pwd)) {
        $remain = acctThrottleFail('pwd', $player, 5, 300);
        debugLog('[account.php] 第一层密码错误', ['player' => $player, 'ip' => acctClientIp()]);
        if ($remain > 0) error('密码错误（连续错误 5 次将锁定 5 分钟），' . $remain . ' 秒后可重试');
        error('密码错误');
    }
    acctThrottleReset('pwd', $player);

    $email = acctEmailOf($player);
    $methods = acctMethods($player, $email);
    if (!$methods) {
        error('无法完成第二层验证：账号未绑定邮箱且未绑定二次验证器，请先在游戏中使用 /bindemail 绑定邮箱');
    }

    $challenge = acctIssueToken($player, 'challenge', 300, json_encode($methods, JSON_UNESCAPED_UNICODE), acctClientIp());

    success([
        'challenge' => $challenge,
        'methods' => $methods,
        'email_masked' => acctMaskEmail($email),
        'expire_in' => 300,
    ], '第一层验证通过');
}

/** challenge 后发送邮箱验证码（60秒间隔 + 10次/小时；发送失败绝不回显验证码） */
function acctSendEmailCode() {
    $player = acctAuthPlayer();
    $challenge = (string)getParam('challenge', '');
    $ch = acctLoadToken($challenge, 'challenge', $player);

    $methods = json_decode((string)$ch['methods'], true);
    if (!is_array($methods) || !in_array('email', $methods, true)) {
        error('当前验证会话未启用邮箱验证');
    }

    $email = acctEmailOf($player);
    if ($email === '') error('账号未绑定邮箱');

    $locked = acctThrottleLocked('mail', $player, 10, 3600);
    if ($locked > 0) error('发送过于频繁，请 ' . $locked . ' 秒后再试', 429);

    // 60 秒发送间隔（查该玩家最近一条验证码的时间）
    $db = getDB();
    $stmt = $db->prepare("SELECT created_at FROM email_codes WHERE player_name = :p ORDER BY id DESC LIMIT 1");
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $last = $result->fetchArray(SQLITE3_ASSOC);
    if ($last) {
        $gap = time() - (int)$last['created_at'];
        if ($gap < 60) error('发送过于频繁，请 ' . (60 - $gap) . ' 秒后再试', 429);
    }

    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;
    if (empty($smtpHost) || empty($smtpUser) || empty($smtpPass)) {
        error('SMTP配置不完整，无法发送邮件验证');
    }

    $code = str_pad((string)random_int(0, 999999), 6, '0', STR_PAD_LEFT);
    $expireTime = time() + 300;

    // 旧验证码全部作废：任何时刻只有最新一条有效
    $upd = $db->prepare("UPDATE email_codes SET used = 1 WHERE player_name = :p AND used = 0");
    $upd->bindValue(':p', $player, SQLITE3_TEXT);
    $upd->execute();

    $ins = $db->prepare("INSERT INTO email_codes (player_name, email, code, expire_at, used, created_at)
                         VALUES (:p, :e, :c, :x, 0, :t)");
    $ins->bindValue(':p', $player, SQLITE3_TEXT);
    $ins->bindValue(':e', $email, SQLITE3_TEXT);
    $ins->bindValue(':c', $code, SQLITE3_TEXT);
    $ins->bindValue(':x', $expireTime, SQLITE3_INTEGER);
    $ins->bindValue(':t', time(), SQLITE3_INTEGER);
    $ins->execute();

    $subject = "【" . $senderName . "】账号安全验证";
    $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
            . "<h2 style='color:#333;text-align:center'>🛡️ 账号安全验证</h2>"
            . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
            . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b> 的验证码：</p>"
            . "<p style='font-size:32px;font-weight:bold;color:#4CAF50;letter-spacing:8px;margin:0'>" . $code . "</p>"
            . "</div>"
            . "<p style='font-size:12px;color:#999;text-align:center'>验证码5分钟内有效，用于修改账号信息前的第二层验证</p>"
            . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请立刻修改密码并忽略此邮件</p>"
            . "</div>";
    $headers = "MIME-Version: 1.0\r\n"
            . "Content-type: text/html; charset=UTF-8\r\n"
            . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

    try {
        $sent = smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);
    } catch (\Throwable $e) {
        $sent = false;
        debugLog('[account.php] 邮件异常: ' . $e->getMessage(), ['player' => $player]);
    }

    if (!$sent) {
        // 发送失败立即作废该验证码，且绝不回显（回显=密码单层即可通过）
        $del = $db->prepare("UPDATE email_codes SET used = 1 WHERE player_name = :p AND code = :c");
        $del->bindValue(':p', $player, SQLITE3_TEXT);
        $del->bindValue(':c', $code, SQLITE3_TEXT);
        $del->execute();
        debugLog('[account.php] 邮件发送失败', ['player' => $player]);
        error('邮件发送失败，请稍后重试');
    }

    acctThrottleFail('mail', $player, 10, 3600);

    success([
        'masked_email' => acctMaskEmail($email),
        'resend_in' => 60,
        'code_expire_in' => 300,
    ], '验证码已发送至 ' . acctMaskEmail($email));
}

/** 第二层：邮箱码 / TOTP 动态码 / 恢复代码 → ticket（10分钟，一次性） */
function acctVerifyStep2() {
    $player = acctAuthPlayer();
    $challenge = (string)getParam('challenge', '');
    $ch = acctLoadToken($challenge, 'challenge', $player);

    $emailCode = (string)getParam('email_code', '');
    $totpCode  = (string)getParam('totp_code', '');
    $recCode   = (string)getParam('recovery_code', '');

    $picked = 0; $which = '';
    if ($emailCode !== '') { $picked++; $which = 'email'; }
    if ($totpCode !== '')  { $picked++; $which = 'totp'; }
    if ($recCode !== '')   { $picked++; $which = 'recovery'; }
    if ($picked !== 1) error('请只填写一种第二层验证方式');

    $snapshot = json_decode((string)$ch['methods'], true);
    if (!is_array($snapshot)) $snapshot = array();
    if (!in_array($which, $snapshot, true)) error('当前验证会话未启用该验证方式');

    $db = getDB();
    $ok = false;

    if ($which === 'email') {
        // 只认未使用且未过期的最新一条（发送侧已把旧码全部作废）
        $stmt = $db->prepare("SELECT id FROM email_codes WHERE player_name = :p AND code = :c AND used = 0 AND expire_at > :t ORDER BY id DESC LIMIT 1");
        $stmt->bindValue(':p', $player, SQLITE3_TEXT);
        $stmt->bindValue(':c', $emailCode, SQLITE3_TEXT);
        $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row) {
            $upd = $db->prepare("UPDATE email_codes SET used = 1 WHERE id = :id");
            $upd->bindValue(':id', (int)$row['id'], SQLITE3_INTEGER);
            $upd->execute();
            $ok = true;
        }
    } elseif ($which === 'totp') {
        $t = acctTwoFa($player);
        if ($t && (int)$t['bound_at'] > 0 && (string)$t['totp_secret'] !== '') {
            $ok = acctTotpVerify((string)$t['totp_secret'], $totpCode);
        }
    } elseif ($which === 'recovery') {
        $t = acctTwoFa($player);
        if ($t && (int)$t['bound_at'] > 0 && (string)$t['recovery_codes'] !== '') {
            $codes = json_decode((string)$t['recovery_codes'], true);
            if (is_array($codes)) {
                $norm = acctNormRecovery($recCode);
                foreach ($codes as $idx => $one) {
                    if ($norm !== '' && hash_equals(acctNormRecovery($one), $norm)) {
                        unset($codes[$idx]);
                        $left = array_values($codes);
                        // 核销用掉的恢复代码（一次性），列表为空时存空串使 has_recovery 归 false
                        acctSaveTwoFa($player, (string)$t['totp_secret'],
                            $left ? json_encode($left, JSON_UNESCAPED_UNICODE) : '', (int)$t['bound_at']);
                        $ok = true;
                        break;
                    }
                }
            }
        }
    }

    if (!$ok) {
        $remain = acctThrottleFail('s2', $player, 5, 300);
        debugLog('[account.php] 第二层验证失败', ['player' => $player, 'way' => $which, 'ip' => acctClientIp()]);
        if ($remain > 0) error('验证失败（连续失败 5 次锁定 5 分钟），' . $remain . ' 秒后可重试', 429);
        error('验证失败');
    }
    acctThrottleReset('s2', $player);

    // 两层全过 → 签发 ticket（10分钟一次性）；acctIssueToken 会同时作废本 challenge 与旧会话
    $ticket = acctIssueToken($player, 'ticket', 600, '', acctClientIp());

    success([
        'ticket' => $ticket,
        'expire_in' => 600,
    ], '第二层验证通过');
}


/** 凭 ticket 修改登录密码：新盐新哈希，顺带清临时密码（ticket 先核销后落库，防并发重复使用） */
function acctChangePassword() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $newPwd = (string)getParam('new_password', '');
    $confirm = (string)getParam('confirm_password', '');
    if ($newPwd === '') error('请输入新密码');
    if ($confirm !== '' && $confirm !== $newPwd) error('两次输入的新密码不一致');
    // ★第48批#245：与游戏 PasswordUtils.validate 同一把尺子（6-20位+大写+小写+数字）
    $ruleErr = pwdRuleError($newPwd);
    if ($ruleErr !== null) error($ruleErr);

    $cred = acctCredentials($player);
    if (!$cred) error('账号未设置密码');
    if (acctPwdCheck($cred, $newPwd)) error('新密码不能与当前密码相同');

    // 一次性凭据核销（上面格式/新旧比对全部通过后才走到这里）
    acctConsumeToken($ticket);

    $newSaltBytes = random_bytes(16);
    $newSalt = base64_encode($newSaltBytes);
    $newHash = base64_encode(hash('sha256', $newSaltBytes . $newPwd, true));

    $db = getDB();
    $upd = $db->prepare("UPDATE weblogin_credentials SET password_hash = :h, salt = :s, temp_password_hash = '', temp_pw_expire = 0 WHERE player_name = :p");
    $upd->bindValue(':h', $newHash, SQLITE3_TEXT);
    $upd->bindValue(':s', $newSalt, SQLITE3_TEXT);
    $upd->bindValue(':p', $player, SQLITE3_TEXT);
    $upd->execute();

    // 回读核对（不依赖 changes()，SQLite/MySQL 双模式同形）
    $after = acctCredentials($player);
    if (!$after || !hash_equals(b64Clean((string)$after['password_hash']), $newHash)) {   // ★第52批：回读值清洗后比对
        debugLog('[account.php] 改密写入核对失败', ['player' => $player, 'ip' => acctClientIp()]);
        error('密码写入失败，请重试', 500);
    }

    debugLog('[account.php] 登录密码已修改', ['player' => $player, 'ip' => acctClientIp()]);
    success(['message' => '密码修改成功'], '密码修改成功');
}

/**
 * 生成待确认的 TOTP 密钥 + otpauth 链接。
 * 不消耗 ticket —— 扫码与输码确认是两次调用，共用同一张 ticket；confirmed 前 bound_at=0 不计入可用方式。
 */
function acctTotpSetup() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $t = acctTwoFa($player);
    if ($t && (int)$t['bound_at'] > 0 && (string)$t['totp_secret'] !== '') {
        error('已绑定二次验证器，如需更换请先解绑');
    }

    $secret = acctNewSecret();
    acctSaveTwoFa($player, $secret, '', 0); // 待确认：旧二维码/未确认密钥作废

    success([
        'secret' => $secret,
        'otpauth' => acctOtpauthUri($secret, $player),
        'server_time' => time(),
    ], '密钥已生成，请用认证器扫码后输入动态码确认');
}

/** 输 6 位动态码确认绑定 → 一次性返回 10 组恢复代码，并核销 ticket */
function acctTotpBind() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $code = (string)getParam('code', '');
    if ($code === '') error('请输入认证器中的6位动态码');

    $locked = acctThrottleLocked('bind', $player, 5, 300);
    if ($locked > 0) error('动态码错误次数过多，请 ' . $locked . ' 秒后再试', 429);

    $t = acctTwoFa($player);
    if (!$t || (string)$t['totp_secret'] === '') error('请先生成绑定二维码');
    if ((int)$t['bound_at'] > 0) error('已绑定二次验证器，如需更换请先解绑');

    if (!acctTotpVerify((string)$t['totp_secret'], $code)) {
        $remain = acctThrottleFail('bind', $player, 5, 300);
        debugLog('[account.php] 绑定动态码错误', ['player' => $player, 'ip' => acctClientIp()]);
        if ($remain > 0) error('动态码错误，请确认认证器时间正常（' . $remain . ' 秒后可重试）', 429);
        error('动态码错误，请确认认证器时间正常');
    }
    acctThrottleReset('bind', $player);

    $codes = acctGenRecoveryCodes();
    acctSaveTwoFa($player, (string)$t['totp_secret'], json_encode($codes, JSON_UNESCAPED_UNICODE), time());
    acctConsumeToken($ticket);

    debugLog('[account.php] 二次验证绑定成功', ['player' => $player, 'ip' => acctClientIp()]);

    success([
        'recovery_codes' => $codes,
        'message' => '绑定成功，恢复代码只显示这一次，请立即保存',
    ], '绑定成功');
}

/**
 * 解绑二次验证（恢复代码一并清空）。
 * 前端必须先警告：若账号未绑定邮箱，解绑后将没有任何第二层方式、无法再改账号信息。
 */
function acctTotpUnbind() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $t = acctTwoFa($player);
    if (!$t || (int)$t['bound_at'] <= 0) error('当前未绑定二次验证器');

    acctSaveTwoFa($player, '', '', 0);
    acctConsumeToken($ticket);

    debugLog('[account.php] 二次验证已解绑', ['player' => $player, 'ip' => acctClientIp()]);
    success(['message' => '已解绑二次验证器，恢复代码同时作废'], '已解绑');
}

/** 查看恢复代码（明文返回 —— 页内查看功能要求；核销 ticket） */
function acctRecoveryList() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $t = acctTwoFa($player);
    if (!$t || (int)$t['bound_at'] <= 0 || (string)$t['recovery_codes'] === '') {
        error('尚未生成恢复代码');
    }
    $codes = json_decode((string)$t['recovery_codes'], true);
    if (!is_array($codes)) $codes = array();

    acctConsumeToken($ticket);
    debugLog('[account.php] 恢复代码被查看', ['player' => $player, 'ip' => acctClientIp()]);
    success(['recovery_codes' => $codes, 'updated_at' => (int)$t['updated_at']], 'ok');
}

/** 重新生成恢复代码：旧的立即作废，新代码只返回这一次（核销 ticket） */
function acctRecoveryRegen() {
    $player = acctAuthPlayer();
    $ticket = (string)getParam('ticket', '');
    acctLoadToken($ticket, 'ticket', $player);

    $t = acctTwoFa($player);
    if (!$t || (int)$t['bound_at'] <= 0 || (string)$t['totp_secret'] === '') {
        error('请先绑定二次验证器');
    }

    $codes = acctGenRecoveryCodes();
    acctSaveTwoFa($player, (string)$t['totp_secret'], json_encode($codes, JSON_UNESCAPED_UNICODE), (int)$t['bound_at']);
    acctConsumeToken($ticket);

    debugLog('[account.php] 恢复代码已重新生成', ['player' => $player, 'ip' => acctClientIp()]);
    success(['recovery_codes' => $codes], '恢复代码已重新生成，旧代码全部作废');
}
