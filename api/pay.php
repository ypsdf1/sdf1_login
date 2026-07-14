<?php
/**
 * 债券在线充值 · 支付平台联控（彩虹易支付 RSA 版）
 *
 * 动作：
 *  - create_order : 校验登录态 → 生成订单 → RSA签名 → 返回跳转支付URL
 *  - notify       : 平台异步回调（GET）→ 平台公钥验签 → 金额校验 → 幂等 → 写 web_transactions(recharge)
 *  - query_order  : 前端补单/轮询订单状态
 *
 * ★ 安全：本文件不含任何密钥。密钥从 pay_secrets.php（git-ignored，仅定义常量的 .php）或
 *   本地 git-ignored 的「密钥文件_git拉黑.txt」读取；两者均不会进入版本库，也不会被部署脚本上传。
 */

// 防止 PHP 错误/弃用警告以 HTML 形式污染 JSON / notify 纯文本响应
@ini_set('display_errors', '0');
@ini_set('html_errors', '0');
error_reporting(E_ALL);

// 防止任何前置输出污染 JSON / notify 纯文本响应
ob_start();
require_once __DIR__ . '/../core.php';
ob_end_clean();

// ===== 非机密常量（可安全提交） =====
// 彩虹易支付 下单网关
define('PAY_GATEWAY', 'https://zf.ypshidifu.cn/api/pay/submit');
// 异步通知地址（平台服务器回调，必须公网可达）
define('PAY_NOTIFY_URL', 'https://caoyuan.ypshidifu.cn/plugin/api/pay.php?action=notify');
// 同步跳转地址（玩家付款后浏览器跳回）
define('PAY_RETURN_URL', 'https://caoyuan.ypshidifu.cn/plugin/player.php?paid=1');
define('PAY_SIGN_TYPE', 'RSA');

// ===== 0.01 元测试配置（先跑通全链路，后续替换为 recharge_tiers 档位） =====
define('PAY_TEST_MONEY', '0.01');   // 元（字符串，与回调比对）
define('PAY_TEST_BONDS', 1);        // 对应债券数量
define('PAY_TEST_NAME', '债券充值-测试0.01元');

// ====================================================================
//  密钥加载（双来源：本地 txt 优先，服务器 pay_secrets.php 兜底）
// ====================================================================
function loadPayKeys() {
    // 来源 A：本地/部署仓库中的 git-ignored 明文文件（源真相，便于改密）
    $txtCandidates = [
        __DIR__ . '/../密钥文件_git拉黑.txt',
        __DIR__ . '/密钥文件_git拉黑.txt',
    ];
    foreach ($txtCandidates as $f) {
        // @ 抑制：密钥文件可能不存在（正常情况），避免 open_basedir 等警告污染响应输出
        if (@is_file($f) && @is_readable($f)) {
            $parsed = parsePayKeyFile(@file_get_contents($f));
            if (!empty($parsed['merchant_private']) && !empty($parsed['platform_public'])) {
                return [
                    'pid'         => $parsed['pid'],
                    'private_key' => wrapPem($parsed['merchant_private'], 'private'),
                    'public_key'  => wrapPem($parsed['platform_public'], 'public'),
                ];
            }
        }
    }

    // 来源 B：服务器部署用的 pay_secrets.php（git-ignored，仅 define 常量，无输出，web 安全）
    $secretsFile = __DIR__ . '/pay_secrets.php';
    if (@is_file($secretsFile) && @is_readable($secretsFile)) {
        @require_once $secretsFile;
        if (defined('PAY_MCH_PRIVATE_KEY') && defined('PAY_PLATFORM_PUBLIC_KEY')) {
            return [
                'pid'         => defined('PAY_PID') ? PAY_PID : '',
                'private_key' => wrapPem(PAY_MCH_PRIVATE_KEY, 'private'),
                'public_key'  => wrapPem(PAY_PLATFORM_PUBLIC_KEY, 'public'),
            ];
        }
    }

    return ['pid' => '', 'private_key' => '', 'public_key' => ''];
}

/**
 * 解析「密钥文件_git拉黑.txt」格式：
 *   商户ID：1001
 *   平台公钥：<base64>
 *   商户公钥：<base64>
 *   商户私钥：<base64>
 *   MD5密钥：<base64>
 */
function parsePayKeyFile($content) {
    $lines = preg_split('/\r\n|\r|\n/', (string)$content);
    $blocks = [];
    $cur = null;
    foreach ($lines as $ln) {
        if (preg_match('/^([\x{4e00}-\x{9fa5}A-Za-z0-9 ]+)\s*[：:]\s*(.*)$/u', $ln, $m)) {
            $cur = trim($m[1]);
            $blocks[$cur] = $m[2];
        } elseif ($cur !== null) {
            $blocks[$cur] .= $ln;
        }
    }
    $clean = function ($v) { return preg_replace('/\s+/', '', $v ?? ''); };
    return [
        'pid'             => trim($blocks['商户ID'] ?? ''),
        'platform_public' => $clean($blocks['平台公钥'] ?? ''),
        'merchant_public' => $clean($blocks['商户公钥'] ?? ''),
        'merchant_private'=> $clean($blocks['商户私钥'] ?? ''),
        'md5'             => trim($blocks['MD5密钥'] ?? ''),
    ];
}

/** 将裸 base64 包装为 PEM。私钥自动探测 PKCS#1(RSA PRIVATE KEY) / PKCS#8(PRIVATE KEY)，公钥用 SubjectPublicKeyInfo(PUBLIC KEY) */
function wrapPem($b64, $type) {
    $b64 = preg_replace('/\s+/', '', $b64);
    if ($b64 === '') return '';
    // 探测 DER：PKCS#8(PRIVATE KEY) 与 PKCS#1(RSA PRIVATE KEY) 都以
    //   SEQUENCE{INTEGER 0(version)} 开头（即 30 82 .. 02 01 00），无法靠前 7 字节区分。
    // 关键在 version 之后紧跟的标签：PKCS#8 是 AlgorithmIdentifier 的 SEQUENCE(0x30)，
    // PKCS#1 是 modulus 的 INTEGER(0x02)。据此决定用哪个 PEM 头（opensslLoadKey 仍有兜底重试）。
    $header = 'PUBLIC KEY';
    if ($type === 'private') {
        $raw = @base64_decode($b64, true);
        $isPkcs1 = ($raw !== false && strlen($raw) > 8
            && $raw[0] === "\x30" && $raw[1] === "\x82"
            && $raw[4] === "\x02" && $raw[5] === "\x01" && $raw[6] === "\x00"
            && $raw[7] === "\x02");
        $header = $isPkcs1 ? 'RSA PRIVATE KEY' : 'PRIVATE KEY';
    }
    $body = trim(chunk_split($b64, 64, "\n"));
    return "-----BEGIN $header-----\n$body\n-----END $header-----\n";
}

/**
 * 容错加载密钥：首次用给定 PEM 加载失败后，自动在 PKCS#1<->PKCS#8 头之间切换重试，
 * 彻底规避「密钥格式与 PEM 头不匹配」导致的签名/验签失败（支付链路关键路径，必须健壮）。
 */
function opensslLoadKey($pem, $isPrivate) {
    $fn = $isPrivate ? 'openssl_pkey_get_private' : 'openssl_pkey_get_public';
    $key = @$fn($pem);
    if ($key !== false) return $key;
    if (!$isPrivate) return false;
    // 私钥：在 RSA PRIVATE KEY <-> PRIVATE KEY 之间切换重试
    $alt = preg_replace('/-----BEGIN RSA PRIVATE KEY-----/', "-----BEGIN PRIVATE KEY-----", $pem);
    $alt = preg_replace('/-----END RSA PRIVATE KEY-----/', "-----END PRIVATE KEY-----", $alt);
    if ($alt !== $pem && ($key = @$fn($alt)) !== false) return $key;
    $alt = preg_replace('/-----BEGIN PRIVATE KEY-----/', "-----BEGIN RSA PRIVATE KEY-----", $pem);
    $alt = preg_replace('/-----END PRIVATE KEY-----/', "-----END RSA PRIVATE KEY-----", $alt);
    if ($alt !== $pem && ($key = @$fn($alt)) !== false) return $key;
    return false;
}

/**
 * RSA(SHA256withRSA) 请求签名：取全部非空参数 → 剔除 sign/sign_type → 参数名 ASCII 升序 → 原始值 k=v& 拼接 → 私钥签名
 */
function buildSign($params, $privateKeyPem) {
    $filtered = [];
    foreach ($params as $k => $v) {
        if ($k === 'sign' || $k === 'sign_type') continue;
        if ($v === '' || $v === null) continue;
        $filtered[$k] = $v;
    }
    ksort($filtered, SORT_STRING);
    $parts = [];
    foreach ($filtered as $k => $v) {
        $parts[] = $k . '=' . $v;
    }
    $data = implode('&', $parts);

    $key = opensslLoadKey($privateKeyPem, true);
    if ($key === false) {
        throw new Exception('商户私钥加载失败: ' . openssl_error_string());
    }
    openssl_sign($data, $signature, $key, OPENSSL_ALGO_SHA256);
    // PHP 8.0+ 已弃用 openssl_free_key，资源会自动释放；显式调用会触发弃用警告，污染 JSON 响应
    if (PHP_VERSION_ID < 80000) {
        @openssl_free_key($key);
    }
    return base64_encode($signature);
}

/**
 * RSA(SHA256withRSA) 回调验签：用平台公钥验证（兼容平台新增的扩展字段——取回调全部非空参数参与）
 */
function verifyNotifySign($params, $publicKeyPem) {
    if (empty($params['sign']) || empty($publicKeyPem)) return false;
    $sign = $params['sign'];
    $filtered = [];
    foreach ($params as $k => $v) {
        if ($k === 'sign' || $k === 'sign_type') continue;
        if ($v === '' || $v === null) continue;
        $filtered[$k] = $v;
    }
    ksort($filtered, SORT_STRING);
    $parts = [];
    foreach ($filtered as $k => $v) {
        $parts[] = $k . '=' . $v;
    }
    $data = implode('&', $parts);

    $key = opensslLoadKey($publicKeyPem, false);
    if ($key === false) return false;
    $ok = openssl_verify($data, base64_decode($sign), $key, OPENSSL_ALGO_SHA256);
    // PHP 8.0+ 已弃用 openssl_free_key，资源会自动释放
    if (PHP_VERSION_ID < 80000) {
        @openssl_free_key($key);
    }
    return $ok === 1;
}

// ====================================================================
//  数据表
// ====================================================================
function ensurePayOrdersTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS pay_orders (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        out_trade_no TEXT UNIQUE,
        trade_no TEXT,
        player_name TEXT NOT NULL,
        tier_id INTEGER DEFAULT 0,
        money TEXT,
        bond_amount INTEGER DEFAULT 0,
        status TEXT DEFAULT 'created',
        name TEXT,
        platform_sign TEXT,
        created_at INTEGER,
        paid_at INTEGER
    )");
}

// ====================================================================
//  动作：create_order
// ====================================================================
function createOrder($token) {
    $keys = loadPayKeys();
    if (empty($keys['pid']) || empty($keys['private_key']) || empty($keys['public_key'])) {
        error('支付密钥未配置，请联系管理员', 500);
    }

    $info = validateTokenSilent($token);
    if (!$info || empty($info['player'])) {
        error('登录状态无效，请重新登录', 401);
    }
    $player = $info['player'];

    $db = getDB();
    ensurePayOrdersTable($db);

    // ===== 0.01 元测试档位（固定映射：0.01 元 → 1 债券） =====
    $money = PAY_TEST_MONEY;
    $bonds = PAY_TEST_BONDS;
    $name  = PAY_TEST_NAME;

    $outTradeNo = 'RE' . date('YmdHis') . sprintf('%04d', mt_rand(0, 9999));

    $stmt = $db->prepare("INSERT INTO pay_orders (out_trade_no, player_name, tier_id, money, bond_amount, status, name, created_at) VALUES (:no, :p, 0, :m, :b, 'created', :n, :t)");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $stmt->bindValue(':m', $money, SQLITE3_TEXT);
    $stmt->bindValue(':b', $bonds, SQLITE3_INTEGER);
    $stmt->bindValue(':n', $name, SQLITE3_TEXT);
    $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
    $stmt->execute();

    // 构造提交参数（原始值，不做 urlencode；sign 在原始值上计算）
    $params = [
        'pid'         => $keys['pid'],
        'type'        => 'alipay',
        'out_trade_no'=> $outTradeNo,
        'notify_url'  => PAY_NOTIFY_URL,
        'return_url'  => PAY_RETURN_URL,
        'name'        => $name,
        'money'       => $money,
        'param'       => $player,
        'timestamp'   => date('c'), // ISO 8601 格式，符合彩虹易支付 V2 接口规范
        'sign_type'   => PAY_SIGN_TYPE,
    ];
    $params['sign'] = buildSign($params, $keys['private_key']);

    // GET 跳转（浏览器跳转付款）。http_build_query 仅做传输层 urlencode，平台解码后还原为原始值参与验签。
    $payUrl = PAY_GATEWAY . '?' . http_build_query($params);

    success([
        'pay_url'      => $payUrl,
        'out_trade_no' => $outTradeNo,
        'money'        => $money,
        'bonds'        => $bonds,
        'player'       => $player,
    ], '订单创建成功，正在跳转支付');
}

// ====================================================================
//  动作：notify（平台异步回调，必须输出纯文本 success）
// ====================================================================
function handleNotify() {
    // 清空输出缓冲，确保最终只输出 success/fail
    while (ob_get_level() > 0) { ob_end_clean(); }

    $params = $_GET;
    debugLog('[pay notify] 收到异步回调', $params);

    $keys = loadPayKeys();

    // 1) 验签（平台公钥）
    if (!verifyNotifySign($params, $keys['public_key'])) {
        debugLog('[pay notify] 验签失败，疑似伪造', ['out_trade_no' => $params['out_trade_no'] ?? '']);
        echo 'fail';
        exit;
    }

    // 2) 交易状态必须是成功
    if (($params['trade_status'] ?? '') !== 'TRADE_SUCCESS') {
        debugLog('[pay notify] 交易状态非成功: ' . ($params['trade_status'] ?? ''), ['out_trade_no' => $params['out_trade_no'] ?? '']);
        // 非成功状态（如 WAIT_BUYER_PAY）返回 fail，让平台继续重试直到成功
        echo 'fail';
        exit;
    }

    $outTradeNo = $params['out_trade_no'] ?? '';
    if ($outTradeNo === '') {
        echo 'fail';
        exit;
    }

    $db = getDB();
    ensurePayOrdersTable($db);

    $stmt = $db->prepare("SELECT * FROM pay_orders WHERE out_trade_no = :no");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        debugLog('[pay notify] 订单不存在: ' . $outTradeNo);
        // 订单未知也返回 success，避免平台死循环重试无效通知
        echo 'success';
        exit;
    }

    // 3) 金额校验：回调 money 必须与订单完全一致（防篡改少付多充）
    if (function_exists('bccomp')) {
        $moneyOk = bccomp((string)$row['money'], (string)($params['money'] ?? ''), 2) === 0;
    } else {
        $moneyOk = ((string)$row['money'] === (string)($params['money'] ?? ''));
    }
    if (!$moneyOk) {
        debugLog('[pay notify] 金额不匹配（疑似篡改）', ['order_money' => $row['money'], 'notify_money' => $params['money'] ?? '']);
        echo 'fail';
        exit;
    }

    // 4) 幂等：已支付则直接返回 success，绝不重复写 web_transactions
    if ($row['status'] === 'paid') {
        echo 'success';
        exit;
    }

    // 5) 标记订单已支付
    $stmt = $db->prepare("UPDATE pay_orders SET status='paid', trade_no=:tn, paid_at=:t WHERE out_trade_no=:no");
    $stmt->bindValue(':tn', $params['trade_no'] ?? '', SQLITE3_TEXT);
    $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->execute();

    // 6) 写 web_transactions(recharge) 供 Java 高频定时器拉取 → addBonds 到账
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台', '在线充值(支付宝)', :d, 'pending', :t)");
    $stmt->bindValue(':p', $row['player_name'], SQLITE3_TEXT);
    $stmt->bindValue(':a', (int)$row['bond_amount'], SQLITE3_INTEGER);
    $stmt->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
    $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
    $stmt->execute();

    debugLog('[pay notify] 充值交易已写入', ['player' => $row['player_name'], 'bonds' => $row['bond_amount'], 'out_trade_no' => $outTradeNo]);
    echo 'success';
    exit;
}

// ====================================================================
//  动作：query_order（前端补单 / 轮询）
// ====================================================================
function queryOrder($token) {
    $info = validateTokenSilent($token);
    if (!$info || empty($info['player'])) {
        error('登录状态无效', 401);
    }
    $outTradeNo = getParam('out_trade_no');
    if (!$outTradeNo) {
        error('缺少订单号');
    }
    $db = getDB();
    ensurePayOrdersTable($db);
    $stmt = $db->prepare("SELECT * FROM pay_orders WHERE out_trade_no=:no AND player_name=:p");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->bindValue(':p', $info['player'], SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        error('订单不存在');
    }
    $status = $row['status'];
    $message = '订单创建中';
    if ($status === 'paid') {
        $message = '已支付，游戏内债券发放中（若未到账请稍候或联系管理员）';
    }
    success([
        'out_trade_no' => $outTradeNo,
        'status'       => $status,
        'money'        => $row['money'],
        'bonds'        => (int)$row['bond_amount'],
        'paid_at'      => $row['paid_at'],
    ], $message);
}

// ====================================================================
//  入口分发
// ====================================================================
$action = getParam('action', '');
try {
    switch ($action) {
        case 'create_order':
            createOrder(getParam('token'));
            break;
        case 'notify':
            handleNotify();   // 内部已 exit
            break;
        case 'query_order':
            queryOrder(getParam('token'));
            break;
        default:
            error('未知操作: ' . $action);
    }
    // ★ WAL checkpoint：释放WAL锁，减少 database is locked 概率
    try { walCheckpoint(); } catch (\Throwable $ignored) {}
} catch (\Throwable $e) {
    if ($action === 'notify') {
        // notify 异常时返回 fail，让平台重试
        while (ob_get_level() > 0) { ob_end_clean(); }
        echo 'fail';
        exit;
    }
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['error' => ['code' => 500, 'message' => 'Internal error: ' . $e->getMessage()]], JSON_UNESCAPED_UNICODE);
    exit;
}
