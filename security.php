<?php
/**
 * ===== 紧急安全加固模块（2026-09-29 入侵事件响应）=====
 *
 * 第一层：TOTP 二次验证（6 位动态码，兼容 1Password / Microsoft Authenticator /
 *         Google Authenticator 等标准 RFC6238 认证器，仅本地校验、无推送）
 * 第二层：管理面 IP 白名单（ADMIN_IP_WHITELIST 为空 = 仅服务器本机 127.0.0.1
 *         与 boot 引导令牌可访问；任何人一律拒绝）
 * 第三层：admin.php 动态访问令牌 —— 必须是 admin.php?token=<SEC_ACCESS_TOKEN>
 *         才算"这个文件存在"，否则一律返回 nginx 原生 404 页（与文件不存在时
 *         的表现完全一致，让探测者分不清是文件不在还是自己请求错了）。
 *
 * 引导令牌（防自锁逃生舱）：
 *   忘记密码之外的"被锁在外面"场景，用宝塔/SSH 打开 config.php 查看 SEC_BOOT_TOKEN，
 *   访问 admin_2fa_setup.php?boot=<令牌> 即可进入配置页把自己 IP 加入白名单。
 *
 * 依赖：config.php（本文件自动加载）
 * 使用方：core.php（会话增强）、admin.php、admin_login.php、
 *         admin_2fa.php、admin_2fa_setup.php、api/admin.php
 *
 * 注意：本文件必须以 <?php 开头且末尾不写 ?>，避免任何意外输出污染页面/JSON。
 */
require_once __DIR__ . '/config.php';

// ======================================================================
//  基础：客户端 IP
// ======================================================================

/** 客户端真实 IP。只信 REMOTE_ADDR（不取 X-Forwarded-For，防伪造）。 */
function secClientIp() {
    $ip = isset($_SERVER['REMOTE_ADDR']) ? trim((string)$_SERVER['REMOTE_ADDR']) : '';
    if (strncmp($ip, '::ffff:', 7) === 0) $ip = substr($ip, 7);   // IPv4-mapped IPv6 归一
    // ★ Cloudflare 回源场景：nginx 未配 real_ip 时 REMOTE_ADDR 是每次变化的 CF 节点 IP，
    //   真实客户端 IP 在 CF-Connecting-IP 头里。仅当 REMOTE_ADDR 确属 CF 官方网段
    //   才信任该头 —— 攻击者直连源站伪造该头时不属于 CF 网段，伪造无效；
    //   经 CF 访问时 CF 会覆盖客户端传入的同名头，亦无法伪造。
    if ($ip !== '' && secIsCloudflareIp($ip)) {
        foreach (array('HTTP_CF_CONNECTING_IP', 'HTTP_TRUE_CLIENT_IP') as $h) {
            if (!empty($_SERVER[$h])) {
                $c = trim((string)$_SERVER[$h]);
                if (filter_var($c, FILTER_VALIDATE_IP)) {
                    if (strncmp($c, '::ffff:', 7) === 0) $c = substr($c, 7);
                    return $c;
                }
            }
        }
    }
    return $ip;
}

/** IP 是否属于 Cloudflare 官方回源网段（developers.cloudflare.com/ips，IPv4）。 */
function secIsCloudflareIp($ip) {
    static $nets = array(
        '173.245.48.0/20', '103.21.244.0/22', '103.22.200.0/22', '103.31.4.0/22',
        '141.101.64.0/18', '108.162.192.0/18', '190.93.240.0/20', '188.114.96.0/20',
        '197.234.240.0/22', '198.41.128.0/17', '162.158.0.0/15', '104.16.0.0/13',
        '104.24.0.0/14', '172.64.0.0/13', '131.0.72.0/22',
    );
    foreach ($nets as $cidr) {
        if (secCidrMatch($ip, $cidr)) return true;
    }
    return false;
}

function secIsLocalIp($ip) {
    return $ip === '127.0.0.1' || $ip === '::1';
}

/** IPv4 CIDR 匹配，支持 "1.2.3.4/24" 写法（家用动态 IP 段适用）。 */
function secCidrMatch($ip, $cidr) {
    $parts = explode('/', $cidr, 2);
    if (count($parts) !== 2 || !is_numeric($parts[1])) return false;
    $bits = (int)$parts[1];
    if ($bits < 0 || $bits > 32) return false;
    $ipLong = ip2long($ip);
    $snLong = ip2long($parts[0]);
    if ($ipLong === false || $snLong === false) return false;
    $mask = $bits === 0 ? 0 : ((-1 << (32 - $bits)) & 0xFFFFFFFF);
    return ($ipLong & $mask) === ($snLong & $mask);
}

/**
 * IP 白名单判定（第二层）。
 * 规则：127.0.0.1/::1 永远放行（服务器本机逃生舱）；
 *      白名单为空 → 除本机外任何人一律拒绝（含 IP 缺失的异常请求）。
 */
function secIpAllowed() {
    $ip = secClientIp();
    if ($ip === '') {
        secLog('ip_unknown', 'REMOTE_ADDR 缺失，按拒绝处理');
        return false;
    }
    if (secIsLocalIp($ip)) return true;
    if (!defined('ADMIN_IP_WHITELIST') || !is_array(ADMIN_IP_WHITELIST)) return false;
    foreach (ADMIN_IP_WHITELIST as $entry) {
        $entry = trim((string)$entry);
        if ($entry === '') continue;
        if ($entry === $ip) return true;
        if (strpos($entry, '/') !== false && secCidrMatch($ip, $entry)) return true;
    }
    return false;
}

// ======================================================================
//  审计日志（入侵排查用）
// ======================================================================

function secLog($event, $detail = '') {
    $dir = __DIR__ . '/db';
    if (!is_dir($dir)) { @mkdir($dir, 0755, true); }
    $line = '[' . date('Y-m-d H:i:s') . '] ip=' . secClientIp() . ' event=' . $event
          . ($detail !== '' ? ' | ' . $detail : '') . "\n";
    @file_put_contents($dir . '/security.log', $line, FILE_APPEND | LOCK_EX);
}

// ======================================================================
//  boot 引导令牌（防自锁：读得到 config.php 的管理员 = 服务器管理员）
// ======================================================================

function secBootToken() {
    return defined('SEC_BOOT_TOKEN') ? (string)SEC_BOOT_TOKEN : '';
}

function secEnsureSession() {
    if (session_status() === PHP_SESSION_NONE) {
        @session_start();
    }
}

/** boot 会话是否有效（令牌正确 且 与建立会话时的 IP 一致）。 */
function secBootSessionActive() {
    if (secBootToken() === '') return false;
    if (empty($_SESSION['sec_boot_ok'])) return false;
    return !empty($_SESSION['sec_boot_ip']) && $_SESSION['sec_boot_ip'] === secClientIp();
}

/**
 * 尝试用 URL/POST 的 boot 参数解锁（成功则建立 boot 会话）；
 * 已有有效 boot 会话也返回 true。
 */
function secBootAuthenticate() {
    if (secBootToken() === '') return false;
    secEnsureSession();
    $t = '';
    if (isset($_GET['boot'])) $t = (string)$_GET['boot'];
    elseif (isset($_POST['boot'])) $t = (string)$_POST['boot'];
    if ($t !== '' && hash_equals(secBootToken(), $t)) {
        $_SESSION['sec_boot_ok'] = 1;
        $_SESSION['sec_boot_ip'] = secClientIp();
        secLog('boot_unlock', 'boot 令牌解锁配置页');
        return true;
    }
    if ($t !== '') {
        secLog('boot_fail', 'boot 令牌不匹配');
    }
    return secBootSessionActive();
}

// ======================================================================
//  动态访问令牌（第三层：admin.php?token= 放行，否则 nginx 原生 404）
// ======================================================================

/** 后台入口令牌（config.php 的 SEC_ACCESS_TOKEN；为空字符串 = 本层未启用）。 */
function secToken() {
    return defined('SEC_ACCESS_TOKEN') ? trim((string)SEC_ACCESS_TOKEN) : '';
}

/** 生成新令牌（32 位十六进制，128 bit 随机）。 */
function secNewToken() {
    return bin2hex(random_bytes(16));
}

/**
 * 复刻 nginx 默认 404 页面（原样字节）。
 * 目的：不给探测者任何"服务器上有这个文件"的线索 —— 拿不到令牌时的 admin.php
 *      与一个根本不存在的 .php 文件表现完全相同。
 */
function secNginx404() {
    while (ob_get_level() > 0) { @ob_end_clean(); }
    http_response_code(404);
    header('Content-Type: text/html');
    header('X-Content-Type-Options: nosniff');
    echo "<html>\r\n"
       . "<head><title>404 Not Found</title></head>\r\n"
       . "<body>\r\n"
       . "<center><h1>404 Not Found</h1></center>\r\n"
       . "<hr><center>nginx</center>\r\n"
       . "</body>\r\n"
       . "</html>\r\n";
    exit;
}

/**
 * 第三层闸门：只有 admin.php?token=<SEC_ACCESS_TOKEN> 才放行。
 * - 令牌未配置（SEC_ACCESS_TOKEN 为空/不存在）→ 直接放行，向后兼容；
 * - $allowBoot=true 时 ?boot=<SEC_BOOT_TOKEN> 也可放行（配置页逃生舱，
 *   boot 令牌本身就是"读得到 config.php 的服务器管理员"凭据）；
 * - 其余一律 nginx 404（不是 403 —— 403 会暴露文件确实存在）。
 */
function secTokenGate($allowBoot = false) {
    $want = secToken();
    if ($want === '') return;
    $got = '';
    if (isset($_GET['token'])) $got = (string)$_GET['token'];
    elseif (isset($_POST['token'])) $got = (string)$_POST['token'];
    if ($got !== '' && hash_equals($want, $got)) return;
    if ($allowBoot && secBootAuthenticate()) return;
    if (function_exists('secLog')) {
        secLog('token_block', 'path=' . (isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '-'));
    }
    secNginx404();
}

/** 拼出带令牌的后台入口 URL（页面内跳转/展示用；服务器端持有令牌，故可直接拼）。 */
function secTokenUrl($page = 'admin.php', $extra = '') {
    $t = secToken();
    if ($t === '') return $page . $extra;
    return $page . (strpos($page, '?') === false ? '?' : '&') . 'token=' . rawurlencode($t) . $extra;
}

// ======================================================================
//  页面 / API 闸门（第二层 IP 白名单的挂载点）
// ======================================================================

/** 页面级闸门。$allowBoot=true 时接受 boot 令牌解锁（仅配置页使用）。 */
function secGatePage($allowBoot = false) {
    // ★ boot 必须先于 IP 判定：IP 已在白名单时原逻辑直接 return，
    //   会导致 admin_2fa_setup.php?boot=... 在"自己 IP 已加白"的情况下进不了引导模式。
    if ($allowBoot && secBootAuthenticate()) return;
    if (secIpAllowed()) return;
    secLog('ip_block_page', 'path=' . (isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '-'));
    while (ob_get_level() > 0) { @ob_end_clean(); }
    http_response_code(403);
    header('Content-Type: text/html; charset=utf-8');
    $ip = secClientIp() !== '' ? secClientIp() : '(未知)';
    echo '<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
       . '<meta name="viewport" content="width=device-width,initial-scale=1">'
       . '<title>403 - 访问被拒绝</title><style>'
       . 'body{background:#0d1117;color:#e6edf3;font-family:Segoe UI,system-ui,sans-serif;'
       . 'display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}'
       . '.b{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:36px;'
       . 'width:460px;max-width:92%}h1{color:#f85149;font-size:22px;margin:0 0 10px}'
       . '.ip{font-family:monospace;color:#58a6ff;background:#0d1117;border:1px solid #30363d;'
       . 'border-radius:6px;padding:8px 12px;margin:12px 0;font-size:14px}'
       . 'p{color:#8b949e;font-size:13px;line-height:1.7;margin:8px 0}'
       . 'code{color:#d29922;font-family:monospace}</style></head><body><div class="b">'
       . '<h1>⛔ 403 访问被拒绝</h1>'
       . '<p>管理后台已启用 <b style="color:#e6edf3">IP 白名单</b>（未配置白名单时仅服务器本机可访问）。</p>'
       . '<div class="ip">你的 IP：' . htmlspecialchars($ip, ENT_QUOTES, 'UTF-8') . '（不在白名单内）</div>'
       . '<p>管理员恢复访问的两种方式：<br>'
       . '1. 通过宝塔/SSH 编辑 <code>plugin/config.php</code>，把上面的 IP 加进 '
       . '<code>ADMIN_IP_WHITELIST</code>；<br>'
       . '2. 或用同一文件中的 <code>SEC_BOOT_TOKEN</code> 访问：<br>'
       . '<code>你的域名/plugin/admin_2fa_setup.php?boot=令牌</code></p>'
       . '<p style="color:#f85149;font-size:12px">本页拒绝记录已写入 db/security.log</p>'
       . '</div></body></html>';
    exit;
}

/** API 级闸门（JSON 403）。 */
function secGateApi() {
    if (secIpAllowed()) return;
    secLog('ip_block_api', 'action=' . (isset($_GET['action']) ? $_GET['action'] : '-'));
    while (ob_get_level() > 0) { @ob_end_clean(); }
    http_response_code(403);
    header('Content-Type: application/json; charset=utf-8');
    header('X-Content-Type-Options: nosniff');
    echo json_encode(array(
        'success' => false,
        'code' => 403,
        'need_ip' => true,
        'ip' => secClientIp(),
        'message' => '访问被拒绝：IP 不在管理白名单内'
    ), JSON_UNESCAPED_UNICODE);
    exit;
}

// ======================================================================
//  会话增强（core.php 的 requireAdminSession / isAdminLoggedIn 调用）
// ======================================================================

function secEpoch() {
    return defined('SEC_EPOCH') ? (int)SEC_EPOCH : 0;
}

/** 会话是否晚于安全纪元（部署加固时把之前的活会话全部作废，踢掉入侵者）。 */
function secSessionEpochOk() {
    $t = isset($_SESSION['admin_login_time']) ? (int)$_SESSION['admin_login_time'] : 0;
    return $t >= secEpoch();
}

/** 当前管理会话是否欠二次验证（已启用 2FA 且尚未通过）。 */
function secSessionNeed2FA() {
    if (!defined('SEC_2FA_ENABLED') || !SEC_2FA_ENABLED) return false;
    if (empty($_SESSION['admin_auth'])) return false;
    return empty($_SESSION['admin_2fa_ok']);
}

// ======================================================================
//  TOTP（RFC 6238 / SHA1 / 30 秒 / 6 位）
// ======================================================================

/**
 * Base32 解码（RFC 4648）。
 * 兼容手工录入的常见脏字符：空格/制表符/连字符/下划线/填充 '=' 一律剔除；
 * 手机键盘最容易敲错的 0/1/8/9 按等价字符归一（0→O、1→I、8→B、9→G），
 * 否则这些字符会被静默丢弃导致密钥变短、之后所有动态码永远对不上。
 */
function secB32Decode($s) {
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

function secB32Encode($data) {
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
function secNewSecret() {
    return secB32Encode(random_bytes(20));
}

/** 计算某一时刻的 6 位动态码。 */
function secTotp($secret, $time = null, $step = 30, $digits = 6) {
    $key = secB32Decode($secret);
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

/**
 * 校验动态码。
 * 默认 window=3 → 接受 [now-90s, now+90s] 共 7 个时间步（210 秒跨度），
 * 容忍手机与服务器之间 ±90 秒的时钟漂移；限速（5 次/15 分钟）仍在，
 * 窗口放大不会带来可利用的暴力破解空间。
 */
function secTotpVerify($secret, $code, $window = 3) {
    $code = trim((string)$code);
    if (!preg_match('/^\d{6}$/', $code)) return false;
    if (!$secret) return false;
    $now = time();
    for ($i = -$window; $i <= $window; $i++) {
        if (hash_equals(secTotp($secret, $now + $i * 30), $code)) return true;
    }
    return false;
}

/**
 * 诊断：在 ±$range 个时间步内找这个码到底对应哪个时刻。
 * @return int|null 该码相对服务器当前时间的偏移秒数（30 的倍数）；完全无匹配返回 null。
 *
 * 用途 —— 验证失败时把"密钥录错了"和"设备时钟不对"这两种原因区分开：
 *   返回非 null → 认证器密钥是对的，只是设备时钟偏了（提示快/慢多少秒）；
 *   返回 null   → 码根本不属于这个密钥（手工录入密钥出错 / 绑定了旧密钥），
 *                 应引导用户回到配置页重新扫码绑定。
 */
function secTotpDiagnose($secret, $code, $range = 20) {
    $code = trim((string)$code);
    if (!preg_match('/^\d{6}$/', $code)) return null;
    if (!$secret) return null;
    $now = time();
    for ($i = -$range; $i <= $range; $i++) {
        if (hash_equals(secTotp($secret, $now + $i * 30), $code)) return $i * 30;
    }
    return null;
}

/**
 * 把诊断结果转成给管理员看的一句话。
 * @param int|null $off secTotpDiagnose 的返回值
 */
function secTotpDiagnoseMsg($off) {
    if ($off === null) {
        return '这个 6 位码不属于本后台的密钥 —— 多半是手工输入 Base32 密钥时敲错了字符，'
             . '或认证器里添加的是旧密钥。请到「安全配置」页重新生成密钥并扫码绑定。';
    }
    $n = abs($off);
    $dir = $off > 0 ? '快' : '慢';
    return '认证器密钥是对的，但你的设备时钟比服务器' . $dir . '了约 ' . $n . ' 秒（已超出 ±90 秒容错范围）。'
         . '请到手机「设置 → 系统 → 日期和时间」打开「自动设置/网络提供时间」，'
         . '等它校准到与服务器一致后（本页会显示服务器时间，可直接比对）再重试。';
}

/** 供认证器手动录入的 otpauth 链接（不外联、不生成第三方二维码）。 */
function secOtpauthUri($secret) {
    $label = rawurlencode('SDF1-Admin');
    $issuer = rawurlencode('SDF1');
    return 'otpauth://totp/' . $label . '?secret=' . $secret
         . '&issuer=' . $issuer . '&algorithm=SHA1&digits=6&period=30';
}

// ======================================================================
//  限速（db/sec_state.json，文件锁；防 6 位码/密码暴力破解）
// ======================================================================

function secStateFile() {
    return __DIR__ . '/db/sec_state.json';
}

/**
 * 记录一次失败并返回当前锁定状态。
 * @return int 剩余锁定秒数（0 = 未锁定）
 */
function secThrottleFail($bucket, $key, $max, $win) {
    $f = secStateFile();
    if (!is_dir(dirname($f))) { @mkdir(dirname($f), 0755, true); }
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
    // 顺手清理窗口外的旧记录
    $fresh = array();
    foreach ($arr as $ts) { if ((int)$ts > $now - $win) $fresh[] = (int)$ts; }
    $state[$k] = $fresh;
    ftruncate($fp, 0); rewind($fp);
    fwrite($fp, json_encode($state, JSON_UNESCAPED_UNICODE));
    fflush($fp); flock($fp, LOCK_UN); fclose($fp);
    if (count($fresh) >= $max) {
        return max(1, ($fresh[0] + $win) - $now);
    }
    return 0;
}

/** 只读查询：当前是否被锁定，返回剩余秒数（0 = 未锁定）。 */
function secThrottleLocked($bucket, $key, $max, $win) {
    $f = secStateFile();
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

/** 成功后清除该 key 的失败计数。 */
function secThrottleReset($bucket, $key) {
    $f = secStateFile();
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

// ======================================================================
//  config.php 标记区回写（2FA 与白名单的持久化）
// ======================================================================

function secConfigPath() {
    return __DIR__ . '/config.php';
}

/** 值导出为单行 PHP 字面量。 */
function secExportVal($v) {
    if (is_bool($v)) return $v ? 'true' : 'false';
    if (is_int($v) || is_float($v)) return (string)$v;
    if (is_array($v)) {
        $items = array();
        foreach ($v as $x) $items[] = secExportVal($x);
        return 'array(' . implode(', ', $items) . ')';
    }
    return var_export((string)$v, true);
}

/**
 * 整段重写 config.php 的 SEC-UPDATE 标记区。
 * - 未在 $kv 中出现的既有 define 一律按旧值保留（防丢 SEC_EPOCH / SEC_BOOT_TOKEN）
 * - 写前备份到 db/config_bak/（保留最近 5 份），tmp+rename 原子替换
 * @throws Exception 失败时抛出（调用方给用户回显）
 */
function secWriteConfig(array $kv) {
    $path = secConfigPath();
    $src = @file_get_contents($path);
    if ($src === false) throw new Exception('读取 config.php 失败');

    $bPos = strpos($src, 'SEC-UPDATE-BEGIN');
    $ePos = strpos($src, 'SEC-UPDATE-END');
    if ($bPos === false || $ePos === false || $ePos < $bPos) {
        throw new Exception('config.php 缺少 SEC-UPDATE 标记区，拒绝写入');
    }
    $bStart = strrpos(substr($src, 0, $bPos), "\n");
    $bStart = ($bStart === false) ? 0 : $bStart + 1;
    $eEnd = strpos($src, "\n", $ePos);
    $eEnd = ($eEnd === false) ? strlen($src) : $eEnd + 1;

    // 解析旧区既有 define（单行约定）
    $old = substr($src, $bStart, $eEnd - $bStart);
    $kept = array();
    foreach (explode("\n", $old) as $line) {
        if (preg_match('/^define\(\'([A-Z0-9_]+)\',\s*(.*)\);\s*$/', trim($line), $m)) {
            $kept[$m[1]] = $m[2];     // 保留原始字面量文本
        }
    }
    foreach ($kv as $k => $v) {
        $kept[$k] = secExportVal($v);
    }

    // 固定输出顺序（可读性），未知 key 追加在后
    $order = array('SEC_BOOT_TOKEN', 'SEC_ACCESS_TOKEN', 'SEC_EPOCH', 'ADMIN_IP_WHITELIST', 'SEC_2FA_ENABLED', 'SEC_2FA_SECRET');
    $lines = array('// >>>SEC-UPDATE-BEGIN 本区段由 admin_2fa_setup.php 自动维护，请勿手工改动标记行');
    foreach ($order as $k) {
        if (isset($kept[$k])) { $lines[] = 'define(\'' . $k . '\', ' . $kept[$k] . ');'; unset($kept[$k]); }
    }
    foreach ($kept as $k => $lit) {
        $lines[] = 'define(\'' . $k . '\', ' . $lit . ');';
    }
    $lines[] = '// >>>SEC-UPDATE-END';
    $newBlock = implode("\n", $lines) . "\n";

    $new = substr($src, 0, $bStart) . $newBlock . substr($src, $eEnd);

    // 备份（保留最近 5 份）
    $bakDir = __DIR__ . '/db/config_bak';
    if (!is_dir($bakDir)) { @mkdir($bakDir, 0755, true); }
    $bak = $bakDir . '/config.' . date('YmdHis') . '.php';
    @file_put_contents($bak, $src, LOCK_EX);
    $baks = glob($bakDir . '/config.*.php');
    if (is_array($baks) && count($baks) > 5) {
        sort($baks);
        $extra = count($baks) - 5;
        for ($i = 0; $i < $extra; $i++) { @unlink($baks[$i]); }
    }

    // 原子替换
    $tmp = $path . '.tmp.' . getmypid();
    if (@file_put_contents($tmp, $new, LOCK_EX) === false) {
        throw new Exception('写入临时文件失败（目录不可写？）');
    }
    if (!@rename($tmp, $path)) {
        @unlink($tmp);
        throw new Exception('替换 config.php 失败');
    }
    if (function_exists('opcache_invalidate')) { @opcache_invalidate($path, true); }
    secLog('config_write', 'keys=' . implode(',', array_keys($kv)));
    return true;
}
