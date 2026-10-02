<?php
/**
 * ===== 紧急安全加固模块（2026-09-29 入侵事件响应）=====
 *
 * 第一层：TOTP 二次验证（6 位动态码，兼容 1Password / Microsoft Authenticator /
 *         Google Authenticator 等标准 RFC6238 认证器，仅本地校验、无推送）
 * 第二层：管理面 IP 白名单（ADMIN_IP_WHITELIST 为空 = 仅服务器本机 127.0.0.1
 *         与 boot 引导令牌可访问；不在白名单的请求一律返回 nginx 原生 404 页，
 *         让探测者分不清是文件不存在还是被拦截 —— 最小化信息透露，维护者自己
 *         知道哪里有问题（服务器在他手上）。全新部署首次访问例外：config 的
 *         SEC-UPDATE 区还是空的、web.db 里也没有引导标记 → 先放行，引导部署者
 *         配好白名单 / 访问令牌 / 2FA，配置页一保存即自动转为强制模式）。
 * 第三层：admin.php 动态访问令牌 —— 必须是 admin.php?token=<SEC_ACCESS_TOKEN>
 *         才算"这个文件存在"，否则一律返回 nginx 原生 404 页（与文件不存在时
 *         的表现完全一致，让探测者分不清是文件不在还是自己请求错了）。
 * 第四层：全局限流（2026-09-30）—— 每个客户端 IP 每 60 秒最多 20 次请求，
 *         玩家端与管理端统一生效。白名单 IP（维护者/管理员）、携带正确
 *         SECRET_KEY 的 Java/服务端调用、以及登录轮询等机器接口不计数；
 *         超限请求**不返回任何数据**，直接挂起连接直到 nginx/浏览器超时
 *         自动断开（挂起槽有并发上限，避免把 PHP-FPM worker 占满）。
 *         应急关闭：配置 define('SEC_RATE_LIMIT', false); 或建 db/ratelimit.off。
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
 * - 尚未引导（全新部署第一次访问，见 secIsBootstrapped）→ 本层整体不生效，
 *   否则部署者连配置页都进不去，首次引导无从完成；
 * - $allowBoot=true 时 ?boot=<SEC_BOOT_TOKEN> 也可放行（配置页逃生舱，
 *   boot 令牌本身就是"读得到 config.php 的服务器管理员"凭据）；
 * - 其余一律 nginx 404（不是 403 —— 403 会暴露文件确实存在）。
 */
function secTokenGate($allowBoot = false) {
    // 引导模式：config 里还没有任何令牌、web.db 里也没有引导标记 → 本层先不生效。
    if (!secIsBootstrapped()) return;
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
//  引导标记（bootstrap marker）—— 判断"这站是否已经用过后台配置页"
// ======================================================================
//
// 判据（任一成立 = 已引导，闸门转为强制模式）：
//   1) config.php 的 SEC-UPDATE 区已有 SEC_BOOT_TOKEN / SEC_ACCESS_TOKEN
//      （线上既有站点早就写过 → 改动上线即刻生效，不存在暴露窗口）；
//   2) web.db 的 sec_bootstrap 标记表有行 —— admin_2fa_setup.php 每次保存都由
//      secWriteConfig() 顺手写入（Java 插件产生的业务数据不算数）。
//
// 两者皆空 = 全新部署的第一次访问 → 引导模式：先放行，让部署者把自己的
//   IP 白名单 / 访问令牌 / 2FA 配齐，配置页一保存即自动转强制。
// 读库异常（文件损坏、被锁死、打不开）→ 从严按"已引导"处理：宁可把本机之外的
//   访问挡成 404，也不把闸门敞开；127.0.0.1 与 boot 令牌永远不受影响。
//
// 为什么不用"库里有没有数据"当判据：Java 插件建站第一天就会往 web.db 写业务
//   数据，按数据量判断会把还没配置完的部署者直接锁死在门外，故用显式标记。

/** 打开 web.db（仅供引导标记读写）。$write=true 时允许新建文件；失败返回 null。 */
function secBootstrapDb($write = false) {
    if (!class_exists('SQLite3')) return null;
    if (!defined('DB_PATH') || DB_PATH === '') return null;
    $path = DB_PATH;
    if ($write) {
        $dir = dirname($path);
        if (!is_dir($dir)) { @mkdir($dir, 0755, true); }
        $flags = SQLITE3_OPEN_READWRITE | SQLITE3_OPEN_CREATE;
    } else {
        if (!file_exists($path)) return null;
        $flags = SQLITE3_OPEN_READWRITE;
    }
    try {
        $db = new SQLite3($path, $flags);
        // ★ 刻意不调用 enableExceptions()：PHP 8.4+ 该方法只保留异常模式，传 false
        //   会触发 E_DEPRECATED —— 本函数跑在闸门判定里，任何提前输出都会把后面
        //   secNginx404() 的状态行挤掉（headers already sent → 404 变 200）。
        //   两种版本差异由调用方兜住：老版本返回 false、新版本抛异常，
        //   而下面所有查询都带 @ 并处在 try/catch 里。
        @$db->exec('PRAGMA busy_timeout=3000');
        return $db;
    } catch (Throwable $e) {
        secLog('bootstrap_db_open_fail', $e->getMessage());
        return null;
    }
}

/**
 * 写入引导标记（secWriteConfig 成功后调用）。
 * 失败只记日志、不抛错 —— 判据 1（config 已有令牌）足以兜底，不能因为写标记
 * 失败就把一次好好的配置保存变成报错。
 */
function secMarkBootstrapped($note = '') {
    $db = secBootstrapDb(true);
    if ($db === null) {
        secLog('bootstrap_mark_fail', 'open failed path=' . (defined('DB_PATH') ? DB_PATH : '(undefined)'));
        return false;
    }
    try {
        $ok = $db->exec('CREATE TABLE IF NOT EXISTS sec_bootstrap ('
            . 'id INTEGER PRIMARY KEY CHECK (id = 1),'
            . 'marked_at INTEGER NOT NULL,'
            . 'note TEXT)');
        if ($ok === false) {
            secLog('bootstrap_mark_fail', 'create table: ' . $db->lastErrorMsg());
            $db->close();
            return false;
        }
        $t = (string)time();
        $n = str_replace("'", "''", substr((string)$note, 0, 200));
        $ok = $db->exec("INSERT OR REPLACE INTO sec_bootstrap (id, marked_at, note) VALUES (1, {$t}, '{$n}')");
        if ($ok === false) {
            secLog('bootstrap_mark_fail', 'insert: ' . $db->lastErrorMsg());
            $db->close();
            return false;
        }
        $db->close();
        secLog('bootstrap_marked', 'note=' . $note);
        return true;
    } catch (Throwable $e) {
        secLog('bootstrap_mark_fail', $e->getMessage());
        return false;
    }
}

/** 是否已引导（双判据说明见本段注释）。结果在单次请求内缓存。 */
function secIsBootstrapped() {
    static $cached = null;
    if ($cached !== null) return $cached;

    // 判据 1：config 里已经有引导 / 访问令牌（分发版是空的，线上早就写过）
    if (secBootToken() !== '' || secToken() !== '') {
        $cached = true;
        return true;
    }
    // 判据 2：web.db 还没建 = 全新部署第一次访问 → 引导模式
    if (!defined('DB_PATH') || DB_PATH === '' || !file_exists(DB_PATH)) {
        $cached = false;
        return false;
    }
    $db = secBootstrapDb(false);
    if ($db === null) {
        $cached = true;              // 有库却打不开 → 从严按已引导
        return true;
    }
    try {
        $m = @$db->query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'sec_bootstrap' LIMIT 1");
        if ($m === false) {
            $db->close();
            $cached = true;          // 连 sqlite_master 都读不了 → 从严
            return true;
        }
        if ($m->fetchArray() === false) {
            $db->close();
            $cached = false;         // 标记表还没建 = 没人保存过配置 → 引导模式
            return false;
        }
        $r = @$db->query('SELECT marked_at FROM sec_bootstrap LIMIT 1');
        if ($r === false) {
            $db->close();
            $cached = true;          // 从严
            return true;
        }
        $has = ($r->fetchArray() !== false);
        $db->close();
        $cached = $has;
        return $cached;
    } catch (Throwable $e) {
        secLog('bootstrap_read_fail', $e->getMessage());
        $cached = true;              // 读库异常 → 从严按已引导
        return true;
    }
}

// ======================================================================
//  页面 / API 闸门（第二层 IP 白名单的挂载点）
// ======================================================================

/**
 * 页面级闸门。$allowBoot=true 时接受 boot 令牌解锁（仅配置页使用）。
 * - 已引导 且 不在 IP 白名单 → nginx 原生 404（不是 403：403 会告诉探测者
 *   "这个文件确实存在"，与最小化信息透露原则冲突）；
 * - 未引导（全新部署第一次访问）→ 放行；后台主入口 admin.php 不直接渲染，
 *   改跳到 admin_2fa_setup.php 完成白名单 / 令牌 / 2FA 的首次配置。
 */
function secGatePage($allowBoot = false) {
    // ★ boot 必须先于 IP 判定：IP 已在白名单时原逻辑直接 return，
    //   会导致 admin_2fa_setup.php?boot=... 在"自己 IP 已加白"的情况下进不了引导模式。
    if ($allowBoot && secBootAuthenticate()) return;
    if (secIpAllowed()) return;

    // 引导模式：全新部署的第一次访问（config 无令牌、web.db 无引导标记）
    // → 先放行，让部署者完成首次配置；后台主入口改跳配置页，这就是"引导"的落点。
    if (!secIsBootstrapped()) {
        secLog('ip_gate_open_bootstrap', 'path=' . (isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '-'));
        $script = basename(isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '');
        if ($script === 'admin.php') {
            header('Location: admin_2fa_setup.php');
            exit;
        }
        return;
    }

    // 已引导且不在白名单 → nginx 原生 404（绝不回 403：403 等于承认文件存在）。
    // 维护者不需要提示页 —— 服务器在他手上，他知道该查 db/security.log；
    // 试探者只能看到与"文件不存在"完全一致的表现。恢复入口写在 config.php 注释里
    // （boot 引导令牌访问 admin_2fa_setup.php）。
    secLog('ip_block_page', 'path=' . (isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '-'));
    secNginx404();
}

/**
 * API 级闸门。与页面闸门同规则：已引导且不在白名单 → nginx 原生 404；
 * 引导模式 → 放行（各动作自己仍有登录 / 口令 / 人机验证把关）。
 * 不再回 JSON 403 —— 403 等于告诉探测者"这个接口确实存在"，
 * 而 404 与"服务器上根本没有这个文件"表现完全一致。
 */
function secGateApi() {
    if (secIpAllowed()) return;
    if (!secIsBootstrapped()) {
        secLog('api_gate_open_bootstrap', 'action=' . (isset($_GET['action']) ? $_GET['action'] : '-'));
        return;
    }
    secLog('ip_block_api', 'action=' . (isset($_GET['action']) ? $_GET['action'] : '-'));
    secNginx404();
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
 * 读取 config.php 的 SEC-UPDATE 区当前各 define 原始字面量（写前快照 / 写后自检用）。
 * @return array|null 键=常量名，值=define 第二参数的原文；区段缺失或不可读返回 null
 */
function secReadUpdateBlock() {
    $path = secConfigPath();
    $src = @file_get_contents($path);
    if ($src === false) return null;
    $bPos = strpos($src, 'SEC-UPDATE-BEGIN');
    $ePos = strpos($src, 'SEC-UPDATE-END');
    if ($bPos === false || $ePos === false || $ePos < $bPos) return null;
    $blk = substr($src, $bPos, $ePos - $bPos);
    $out = array();
    foreach (explode("\n", $blk) as $line) {
        if (preg_match('/^define\(\'([A-Z0-9_]+)\',\s*(.*)\);\s*$/', trim($line), $m)) {
            $out[$m[1]] = $m[2];
        }
    }
    return $out;
}

/**
 * 二次验证状态的语义指纹：'on|set' / 'off|empty' / null（读不到）。
 * 用语义而不是字面量比对，避免 true/1 这类等价写法误判。
 */
function secTwoFaState($blk) {
    if (!is_array($blk)) return null;
    if (!array_key_exists('SEC_2FA_ENABLED', $blk)) return null;
    $on = trim($blk['SEC_2FA_ENABLED']);
    $enabled = ($on === 'true' || $on === '1'
             || ((strncmp($on, '\'', 1) === 0 || strncmp($on, '"', 1) === 0) && trim($on, '\'"') !== ''));
    $sec = array_key_exists('SEC_2FA_SECRET', $blk) ? trim($blk['SEC_2FA_SECRET']) : '';
    $hasSecret = !($sec === '' || $sec === "''" || $sec === '""' || $sec === 'null');
    return ($enabled ? 'on' : 'off') . '|' . ($hasSecret ? 'set' : 'empty');
}

/**
 * 还原 config.php 到写入前的最近一次自动备份（secWriteConfig 每次写前必备份）。
 * @return bool 是否还原成功
 */
function secRestoreLastConfigBackup($op) {
    $baks = glob(__DIR__ . '/db/config_bak/config.*.php');
    if (!is_array($baks) || count($baks) === 0) return false;
    sort($baks);
    $last = $baks[count($baks) - 1];
    $src = @file_get_contents($last);
    if ($src === false) return false;
    $path = secConfigPath();
    if (@file_put_contents($path, $src, LOCK_EX) === false) return false;
    if (function_exists('opcache_invalidate')) @opcache_invalidate($path, true);
    secLog('config_rollback', $op . ' restored from ' . basename($last));
    return true;
}

/**
 * 二次验证护栏：给「本轮操作本来就不该动 2FA」的动作（重置访问令牌、改白名单）用。
 * 用写入前的快照和写入后的磁盘实际状态比对，一旦 2FA 被打开/关闭或密钥被清空，
 * 立即从写前备份回滚并抛错 —— 结构上保证"重置访问密钥绝不会顺带关掉二次验证"。
 *
 * @param array|null $before 写入前 secReadUpdateBlock() 的快照
 * @param string     $op     操作名（日志用）
 * @throws Exception 检测到 2FA 状态被改动时（此时已尝试回滚）
 */
function secGuardTwoFaUnchanged($before, $op) {
    $b = secTwoFaState($before);
    if ($b === null) return;              // 快照不可得：护栏不拦，避免误伤
    $a = secTwoFaState(secReadUpdateBlock());
    if ($a === null) return;
    if ($b === $a) {
        secLog('twofa_guard_ok', $op . ' 2FA=' . $a . ' 未变');
        return;
    }
    $restored = secRestoreLastConfigBackup($op);
    secLog('twofa_guard_trip', $op . ' 2FA ' . $b . ' -> ' . $a . ' | rollback=' . ($restored ? 'ok' : 'FAIL'));
    throw new Exception('安全护栏触发：' . $op . ' 意外改动了二次验证配置，已'
        . ($restored ? '自动回滚，二次验证未被关闭。' : '回滚失败，请立刻用引导令牌进本页检查 2FA 状态。'));
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
    // ★ 引导标记：配置一落盘 = "这站已经用过后台面板"，secIsBootstrapped() 的判据 2
    //   从此成立（判据 1 是 config 里已有令牌，两者互为兜底）。
    secMarkBootstrapped('secWriteConfig:' . implode(',', array_keys($kv)));
    return true;
}

// ======================================================================
//  第四层：全局限流（每 IP 每 60 秒最多 20 次；白名单 IP 不限）
//  本文件被任何入口 require 时自动执行一次，玩家端与管理端统一生效。
// ======================================================================

/** 应急开关：config 里 SEC_RATE_LIMIT===false，或存在 db/ratelimit.off 时停用。 */
function secRateLimitOff() {
    if (defined('SEC_RATE_LIMIT') && SEC_RATE_LIMIT === false) return true;
    return file_exists(__DIR__ . '/db/ratelimit.off');
}

/** 当前脚本名（api/sync.php → sync.php）。 */
function secRateScript() {
    $s = '';
    if (!empty($_SERVER['SCRIPT_NAME'])) $s = (string)$_SERVER['SCRIPT_NAME'];
    elseif (!empty($_SERVER['PHP_SELF'])) $s = (string)$_SERVER['PHP_SELF'];
    elseif (!empty($_SERVER['REQUEST_URI'])) $s = (string)parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
    $s = str_replace('\\', '/', (string)$s);
    return basename($s);
}

/**
 * 轮询/机器接口豁免表：脚本名 => 免计数的 action 列表。
 * 这些是前端每 0.5~5 秒一次的登录轮询与支付补单器，超过 20 次/分钟是正常行为，
 * 若也计数会让玩家无法登录、支付无法补单。
 * 数组里放空字符串 '' 表示整个脚本豁免。
 */
function secRateExemptMap() {
    static $map = array(
        'sync.php'           => array('check_web_login_result', 'web_access_check'),
        'pay.php'            => array('query_order'),
        'land_api.php'       => array('get_add_visitor_status', 'get_visitor_status', 'add_visitor_status'),
        'minecraft_auth.php' => array('check_session'),
        'poller_online.php'  => array(''),   // 支付补单器：只被服务端/Java 触发，自带进程锁
    );
    return $map;
}

/** 当前请求是否命中轮询豁免表。 */
function secRateExempt() {
    $map = secRateExemptMap();
    $script = secRateScript();
    if (!isset($map[$script])) return false;
    $list = $map[$script];
    if (count($list) === 1 && $list[0] === '') return true;
    $action = '';
    if (isset($_REQUEST['action'])) $action = trim((string)$_REQUEST['action']);
    return $action !== '' && in_array($action, $list, true);
}

/**
 * 是否携带了正确的 SECRET_KEY（Java/服务端内部调用，不计数）。
 * 取值顺序与各接口一致：GET / POST / JSON 请求体。
 */
function secRateHasSecret() {
    if (!defined('SECRET_KEY') || SECRET_KEY === '') return false;
    $key = (string)SECRET_KEY;
    $cands = array();
    if (isset($_GET['secret'])) $cands[] = (string)$_GET['secret'];
    if (isset($_POST['secret'])) $cands[] = (string)$_POST['secret'];
    if (isset($_REQUEST['secret'])) $cands[] = (string)$_REQUEST['secret'];
    foreach ($cands as $c) {
        if ($c !== '' && hash_equals($key, $c)) return true;
    }
    // 个别接口把 secret 放在 JSON 请求体里（Content-Type: application/json）
    $ct = isset($_SERVER['CONTENT_TYPE']) ? (string)$_SERVER['CONTENT_TYPE'] : '';
    if ($ct !== '' && stripos($ct, 'json') !== false && empty($_POST)) {
        $len = isset($_SERVER['CONTENT_LENGTH']) ? (int)$_SERVER['CONTENT_LENGTH'] : 0;
        if ($len > 0 && $len <= 262144) {
            $raw = file_get_contents('php://input');
            if (is_string($raw) && $raw !== '') {
                $j = json_decode($raw, true);
                if (is_array($j)) {
                    foreach (array('secret', 'key') as $k) {
                        if (isset($j[$k]) && is_string($j[$k]) && $j[$k] !== ''
                            && hash_equals($key, $j[$k])) return true;
                    }
                }
            }
        }
    }
    return false;
}

/**
 * 计一次数，返回该 IP 当前 60 秒窗口内的请求数（含本次）。
 * 计数与判断放在同一把文件锁里，避免“读到没超、写入后已超”的竞态。
 */
function secRateCount($ip, $max, $win) {
    $f = secStateFile();
    if (!is_dir(dirname($f))) { @mkdir(dirname($f), 0755, true); }
    $fp = @fopen($f, 'c+');
    if (!$fp) return 0;
    flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $state = json_decode($raw, true);
    if (!is_array($state)) $state = array();
    $now = time();
    $k = 'rate|' . $ip;
    $arr = (isset($state[$k]) && is_array($state[$k])) ? $state[$k] : array();
    $arr[] = $now;
    $fresh = array();
    foreach ($arr as $ts) { if ((int)$ts > $now - $win) $fresh[] = (int)$ts; }
    // 单 IP 最多留 60 条：即便被刷爆，也只按窗口内的条数判超限，不撑爆文件
    if (count($fresh) > 60) $fresh = array_slice($fresh, -60);
    $state[$k] = $fresh;
    // 文件过大时顺手清掉其它已过期 IP 的桶，防止长期堆积
    if (strlen((string)$raw) > 262144) {
        foreach ($state as $kk => $vv) {
            if (!is_array($vv)) { unset($state[$kk]); continue; }
            $alive = false;
            foreach ($vv as $ts) { if ((int)$ts > $now - $win) { $alive = true; break; } }
            if (!$alive) unset($state[$kk]);
        }
    }
    ftruncate($fp, 0); rewind($fp);
    fwrite($fp, json_encode($state, JSON_UNESCAPED_UNICODE));
    fflush($fp); flock($fp, LOCK_UN); fclose($fp);
    return count($fresh);
}

/** 挂起槽文件（记录当前正在被挂起的连接，防止占满 PHP-FPM worker）。 */
function secHangFile() {
    return __DIR__ . '/db/sec_hang.json';
}

/**
 * 申请一个挂起槽：每 IP 最多 2 个、全局最多 4 个。
 * 返回槽 id（成功）或 ''（槽满）。槽位带 300 秒过期自愈，进程被强杀也不会永久泄漏。
 */
function secHangAcquire($ip) {
    $f = secHangFile();
    if (!is_dir(dirname($f))) { @mkdir(dirname($f), 0755, true); }
    $fp = @fopen($f, 'c+');
    if (!$fp) return '';
    flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $list = json_decode($raw, true);
    if (!is_array($list)) $list = array();
    $now = time();
    $mine = 0; $total = 0; $keep = array();
    foreach ($list as $e) {
        if (!is_array($e) || !isset($e['ip'], $e['t'])) continue;
        if ((int)$e['t'] < $now - 300) continue;      // 过期自愈
        $keep[] = $e;
        $total++;
        if ((string)$e['ip'] === (string)$ip) $mine++;
    }
    $id = '';
    if ($mine < 2 && $total < 4) {
        $id = uniqid('h', true);
        $keep[] = array('ip' => (string)$ip, 't' => $now, 'id' => $id);
    }
    ftruncate($fp, 0); rewind($fp);
    fwrite($fp, json_encode($keep, JSON_UNESCAPED_UNICODE));
    fflush($fp); flock($fp, LOCK_UN); fclose($fp);
    return $id;
}

/** 释放挂起槽。 */
function secHangRelease($id) {
    if (!is_string($id) || $id === '') return;
    $f = secHangFile();
    if (!file_exists($f)) return;
    $fp = @fopen($f, 'r+');
    if (!$fp) return;
    flock($fp, LOCK_EX);
    $raw = stream_get_contents($fp);
    $list = json_decode($raw, true);
    if (is_array($list)) {
        $out = array();
        foreach ($list as $e) {
            if (is_array($e) && isset($e['id']) && (string)$e['id'] === $id) continue;
            $out[] = $e;
        }
        if (count($out) !== count($list)) {
            ftruncate($fp, 0); rewind($fp);
            fwrite($fp, json_encode($out, JSON_UNESCAPED_UNICODE));
            fflush($fp);
        }
    }
    flock($fp, LOCK_UN); fclose($fp);
}

/**
 * 超限处理：一个字节都不返回，挂起连接直到超时/对端断开。
 * - 挂起槽满时立即结束（同样不输出任何内容），避免把 worker 占满导致全站不可用；
 * - 挂起期间关闭 session 写锁，否则同用户的下一个请求会被 session 文件锁卡死；
 * - 上限 90 秒：nginx fastcgi_read_timeout 默认 60 秒会先返回 504 断开。
 */
function secRateSuspend($ip) {
    $id = secHangAcquire($ip);
    if ($id === '') { exit; }
    @set_time_limit(0);
    if (session_status() === PHP_SESSION_ACTIVE) { @session_write_close(); }
    $start = time();
    while (time() - $start < 90) {
        if (connection_aborted()) break;
        @sleep(1);
    }
    secHangRelease($id);
    exit;
}

/**
 * 该请求是否持有"不该被限流挂死"的凭据。本层的定位是拦陌生流量，
 * 不能反过来把自己人（部署者 / 已认证管理员）挂成白屏。
 *
 * 四类放行：
 *  1) 引导模式（config 无令牌、web.db 无引导标记 = 全新部署还没配置）——
 *     此时 IP 白名单层与令牌层闸门本来就整体放行（secGatePage / secTokenGate），
 *     限流若照常生效就等于"唯一还开着的闸只拦自己人"：后台页面 10 秒一轮询，
 *     轻松打满 20 次/分钟 → secRateSuspend() 一个字节都不返回 → 整站白屏，
 *     连配置页的白名单都提交不进去（2026-10-02 /test12 实测：200 + 0 字节，
 *     带 token 与不带 token 表现完全一样 —— 因为限流先于令牌闸门执行）。
 *  2) 持有效后台入口令牌（第三层已凭据，secTokenGate 反正会放行）；
 *  3) 持有效 boot 会话 / ?boot= 参数匹配（配置页逃生舱凭据）；
 *  4) 已登录后台的会话（admin.php 的轮询子请求只带 cookie 不带 token，
 *     不认它的话管理员会看着自己的后台被第四层挂死）。
 *
 * 反向边界：以上都拿不到的普通访客流量照旧计数 —— 限流对陌生人一点没松。
 */
function secRateTrusted() {
    // 1) 引导模式：还没配任何安全项，先把配置做完，配置一保存闸门自动转强制
    if (!secIsBootstrapped()) return true;
    // 2) 后台入口令牌（GET / POST 与 secTokenGate 取值顺序一致）
    $t = secToken();
    if ($t !== '') {
        $got = '';
        if (isset($_GET['token'])) $got = (string)$_GET['token'];
        elseif (isset($_POST['token'])) $got = (string)$_POST['token'];
        if ($got !== '' && hash_equals($t, $got)) return true;
    }
    // 3) boot 令牌 / 4) 后台会话：只有请求本身就带着 boot 参数或会话 cookie 时
    //    才去开 session —— 否则给每一个路过的访客都平白 session_start() 一次。
    //    （没有会话 cookie 就不可能存在有效会话，直接跳过是安全的。）
    $hasBootParam = isset($_GET['boot']) || isset($_POST['boot']);
    if ($hasBootParam || !empty($_COOKIE[session_name()])) {
        secEnsureSession();
        if ($hasBootParam && secBootAuthenticate()) return true;   // ?boot= 或已建立的 boot 会话
        if (!empty($_SESSION['admin_auth'])) return true;          // 后台登录会话（伪造 cookie 无效）
        if (secBootSessionActive()) return true;
    }
    return false;
}

/** 第四层入口：include security.php 时自动执行一次。 */
function secRateLimitGate() {
    if (PHP_SAPI === 'cli') return;                 // 命令行脚本不参与限流
    if (empty($_SERVER['REMOTE_ADDR'])) return;      // 无来源地址（CLI/内部）不参与
    if (secRateLimitOff()) return;                   // 应急开关
    $ip = secClientIp();
    if ($ip === '') return;
    if (secIpAllowed()) return;                      // 第二层白名单 IP = 维护者/管理员，不限
    if (secRateHasSecret()) return;                  // Java/服务端调用（带正确 SECRET_KEY）
    if (secRateExempt()) return;                     // 登录轮询、支付补单等机器接口
    if (secRateTrusted()) return;                    // 引导模式 / 令牌 / boot / 后台会话
    $n = secRateCount($ip, 20, 60);
    if ($n <= 20) return;
    if ($n === 21) { secLog('rate_blocked', '超过 20 次/分钟，开始挂起连接不返回数据'); }
    secRateSuspend($ip);
}

secRateLimitGate();
