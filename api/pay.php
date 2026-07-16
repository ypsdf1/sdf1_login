<?php
/**
 * 债券在线充值 · 支付平台联控（彩虹易支付 RSA 版）
 *
 * 动作：
 *  - create_order : 校验登录态 → 生成订单 → RSA签名 → 返回跳转支付URL
 *  - notify       : 平台异步回调（GET）→ 平台公钥验签 → 金额校验 → 幂等 → 写 web_transactions(recharge)
 *  - query_order  : 前端补单/轮询订单状态
 *
 * ★ 安全：本文件不含任何密钥。密钥仅从 pay_secrets.php（git-ignored，仅定义常量）读取；
 *   本地 txt 文件不会被代码引用，仅作人工本地备份。
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
define('PAY_SIGN_TYPE', 'MD5');   // 提交用 MD5（商户 MD5 密钥 pay_user.key，末尾无点）；用户新私钥数据损坏无法自验，暂用 MD5

// ===== 0.01 元测试配置（先跑通全链路，后续替换为 recharge_tiers 档位） =====
define('PAY_TEST_MONEY', '0.01');   // 元（字符串，与回调比对）
define('PAY_TEST_BONDS', 1);        // 对应债券数量
define('PAY_TEST_NAME', '债券充值-测试0.01元');

// ====================================================================
//  密钥加载（仅使用 pay_secrets.php，严禁引用本地 txt 记录文件）
// ====================================================================
function loadPayKeys() {
    $secretsFile = __DIR__ . '/pay_secrets.php';
    if (@is_file($secretsFile) && @is_readable($secretsFile)) {
        @require_once $secretsFile;
        if (defined('PAY_MCH_PRIVATE_KEY') && defined('PAY_PLATFORM_PUBLIC_KEY')) {
            return [
                'pid'         => defined('PAY_PID') ? PAY_PID : '',
                'private_key' => wrapPem(PAY_MCH_PRIVATE_KEY, 'private'),
                'public_key'  => wrapPem(PAY_PLATFORM_PUBLIC_KEY, 'public'),
                'md5_key'     => defined('PAY_MD5_KEY') ? PAY_MD5_KEY : '',
            ];
        }
    }
    return ['pid' => '', 'private_key' => '', 'public_key' => '', 'md5_key' => ''];
}

/**
 * 获取平台MySQL数据库连接（集中凭据管理）
 * 凭据从 pay_secrets.php 读取，不再硬编码
 */
function getPlatformDB() {
    $host   = defined('PAY_MYSQL_HOST')   ? PAY_MYSQL_HOST   : '127.0.0.1';
    $dbname = defined('PAY_MYSQL_DBNAME') ? PAY_MYSQL_DBNAME : 'caihong';
    $user   = defined('PAY_MYSQL_USER')   ? PAY_MYSQL_USER   : 'kH3C3LLinNwYdTF5';
    $pass   = defined('PAY_MYSQL_PASS')   ? PAY_MYSQL_PASS   : 'sRhsdxrpHBhmSsp8';
    $pdo = new PDO(
        "mysql:host=$host;dbname=$dbname;charset=utf8mb4",
        $user,
        $pass,
        [PDO::ATTR_TIMEOUT => 10, PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
    return $pdo;
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
 * 请求签名：根据 sign_type 选择算法
 *  - RSA : 取全部非空参数 → 剔除 sign/sign_type → ASCII 升序 → k=v& 拼接 → 私钥 SHA256WithRSA 签名
 *  - MD5 : 同上拼接后追加 &key=商户MD5密钥 → md5（彩虹易支付MD5方式，无需密钥对，可绕过RSA密钥不匹配）
 */
function buildSign($params, $keys) {
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

    $type = $params['sign_type'] ?? PAY_SIGN_TYPE;
    if ($type === 'RSA') {
        $key = opensslLoadKey($keys['private_key'], true);
        if ($key === false) {
            throw new Exception('商户私钥加载失败: ' . openssl_error_string());
        }
        openssl_sign($data, $signature, $key, OPENSSL_ALGO_SHA256);
        if (PHP_VERSION_ID < 80000) {
            @openssl_free_key($key);
        }
        return base64_encode($signature);
    }
    // MD5：平台规则为 md5(待签串 + 商户MD5密钥)，【无】&key= 后缀（与彩虹易支付源码 Payment::makeSign 一致）
    if (empty($keys['md5_key'])) {
        throw new Exception('商户MD5密钥未配置');
    }
    return md5($data . $keys['md5_key']);
}

/**
 * 回调验签：根据回调 sign_type 选择算法（兼容平台RSA/MD5两种回调）
 */
function verifyNotifySign($params, $keys) {
    if (empty($params['sign'])) {
        debugLog('[pay notify] 验签失败: 缺少sign参数', ['params_keys' => array_keys($params)]);
        return false;
    }
    $sign = $params['sign'];
    $filtered = [];
    foreach ($params as $k => $v) {
        // 排除 sign/sign_type（签名字段）和 action（URL路由参数，平台签名不含此项）
        if ($k === 'sign' || $k === 'sign_type' || $k === 'action') continue;
        if ($v === '' || $v === null) continue;
        $filtered[$k] = $v;
    }
    ksort($filtered, SORT_STRING);
    $parts = [];
    foreach ($filtered as $k => $v) {
        $parts[] = $k . '=' . $v;
    }
    $data = implode('&', $parts);

    $type = $params['sign_type'] ?? PAY_SIGN_TYPE;
    if ($type === 'RSA') {
        if (empty($keys['public_key'])) {
            debugLog('[pay notify] 验签失败: RSA但无公钥', ['type' => $type]);
            return false;
        }
        $key = opensslLoadKey($keys['public_key'], false);
        if ($key === false) {
            debugLog('[pay notify] 验签失败: RSA公钥加载失败', ['public_key_start' => substr($keys['public_key'], 0, 50)]);
            return false;
        }
        $ok = openssl_verify($data, base64_decode($sign), $key, OPENSSL_ALGO_SHA256);
        if (PHP_VERSION_ID < 80000) {
            @openssl_free_key($key);
        }
        debugLog('[pay notify] RSA验签结果', ['data' => $data, 'ok' => $ok]);
        return $ok === 1;
    }
    // MD5：与提交签名规则一致，md5(待签串 + 商户MD5密钥)，无 &key= 后缀
    if (empty($keys['md5_key'])) {
        debugLog('[pay notify] 验签失败: MD5但无密钥', ['type' => $type]);
        return false;
    }
    $expected = strtolower(md5($data . $keys['md5_key']));
    $actual = strtolower($sign);
    $ok = ($expected === $actual);
    debugLog('[pay notify] MD5验签', ['data' => $data, 'md5_key' => $keys['md5_key'], 'expected' => $expected, 'actual' => $actual, 'ok' => $ok]);
    return $ok;
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
        submit_params TEXT,
        created_at INTEGER,
        paid_at INTEGER
    )");
    // 旧表可能由早期版本创建、不含 submit_params 列；
    // SQLite 不支持 ALTER ... ADD COLUMN IF NOT EXISTS，故用 PRAGMA 探测后补齐，
    // 否则 CREATE TABLE IF NOT EXISTS 不会加列，导致 UPDATE submit_params 报 “no such column”。
    $hasCol = false;
    $res = $db->query("PRAGMA table_info(pay_orders)");
    while ($col = $res->fetchArray(SQLITE3_ASSOC)) {
        if ($col['name'] === 'submit_params') { $hasCol = true; break; }
    }
    if (!$hasCol) {
        $db->exec("ALTER TABLE pay_orders ADD COLUMN submit_params TEXT");
    }
}

/**
 * 确保充值流水表存在（notify 写入 web_transactions 供 Java 高频定时器拉取）。
 * 该表可能由其它模块创建，这里做幂等兜底，避免新部署时表不存在导致 notify 抛异常返回 fail。
 */
function ensureWebTransactionsTable($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        type TEXT,
        amount INTEGER DEFAULT 0,
        operator TEXT,
        reason TEXT,
        detail TEXT,
        status TEXT DEFAULT 'pending',
        created_at INTEGER
    )");
    // 唯一约束：同 detail+type 不允许重复
    @$db->exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_web_tx_detail_type ON web_transactions(detail, type)");
}

// ====================================================================
//  动作：create_order
// ====================================================================
function createOrder($token, $productId = 0) {
    $keys = loadPayKeys();
    if (empty($keys['pid']) || empty($keys['private_key']) || empty($keys['public_key'])) {
        error('支付密钥未配置，请联系管理员', 500);
    }

    $info = validateTokenSilent($token);
    if (!$info || empty($info['player'])) {
        error('登录状态无效，请重新登录', 401);
    }
    $player = $info['player'];

    $db = getOrdersDB();
    ensurePayOrdersTable($db);

    // ★ 防重复支付：检查玩家是否有未支付的已有订单（仅5分钟内有效）
    $staleThreshold = time() - 300; // 5分钟过期
    $checkStmt = $db->prepare("SELECT out_trade_no, submit_params, money, bond_amount, created_at FROM pay_orders WHERE player_name = :p AND status = 'created' AND created_at >= :stale ORDER BY created_at DESC LIMIT 1");
    $checkStmt->bindValue(':p', $player, SQLITE3_TEXT);
    $checkStmt->bindValue(':stale', $staleThreshold, SQLITE3_INTEGER);
    $existingRow = $checkStmt->execute()->fetchArray(SQLITE3_ASSOC);
    if ($existingRow && !empty($existingRow['submit_params'])) {
        // 重用已有订单（返回原有支付链接，不创建新单）
        $scriptBase = preg_replace('/\?.*$/', '', PAY_NOTIFY_URL);
        $payUrl = $scriptBase . '?action=pay_redirect&out_trade_no=' . urlencode($existingRow['out_trade_no']);
        success([
            'pay_url'      => $payUrl,
            'out_trade_no' => $existingRow['out_trade_no'],
            'money'        => $existingRow['money'],
            'bonds'        => (int)$existingRow['bond_amount'],
            'player'       => $player,
            'reused'       => true,
        ], '检测到您有未支付的订单，已为您恢复支付链接');
    }
    // ★ 清理该玩家30分钟前的陈旧created订单（已被实际支付但本地未更新），避免阻塞后续支付
    $cleanStmt = $db->prepare("UPDATE pay_orders SET status = 'expired' WHERE player_name = :p AND status = 'created' AND created_at < :stale");
    $cleanStmt->bindValue(':p', $player, SQLITE3_TEXT);
    $cleanStmt->bindValue(':stale', $staleThreshold, SQLITE3_INTEGER);
    $cleanStmt->execute();

    // ===== 获取商品信息 =====
    $money = PAY_TEST_MONEY;  // 默认测试价格
    $bonds = PAY_TEST_BONDS;  // 默认测试债券
    $name  = PAY_TEST_NAME;   // 默认测试名称

    if ($productId > 0) {
        // 从shop_configs表获取商品信息
        $stmt = $db->prepare("SELECT * FROM shop_configs WHERE id = :id AND is_active = 1");
        $stmt->bindValue(':id', $productId, SQLITE3_INTEGER);
        $product = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

        if ($product) {
            // 检查库存
            if ($product['stock'] == 0) {
                error('商品已售罄');
            }

            // 使用折扣价格（如果有）
            $actualPrice = $product['price'];
            if (isset($product['discount_price']) && $product['discount_price'] > 0) {
                $actualPrice = $product['discount_price'];
            }
            $money = number_format($actualPrice, 2, '.', '');
            $name = $product['item_name'];

            // 解析债券范围
            $bondReward = $product['bond_reward'];
            if (preg_match('/^(\d+)-(\d+)$/', $bondReward, $m)) {
                // 随机范围内的债券数量
                $bondMin = intval($m[1]);
                $bondMax = intval($m[2]);
                $bonds = mt_rand($bondMin, $bondMax);
            } else {
                $bonds = intval($bondReward);
            }

            // 更新库存（如果不是无限库存）
            if ($product['stock'] > 0) {
                $updateStmt = $db->prepare("UPDATE shop_configs SET stock = stock - 1 WHERE id = :id AND stock > 0");
                $updateStmt->bindValue(':id', $productId, SQLITE3_INTEGER);
                $updateStmt->execute();
            }
        }
    }

    $outTradeNo = 'RE' . date('YmdHis') . sprintf('%04d', mt_rand(0, 9999));

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
        'timestamp'   => (string)time(), // 10位Unix时间戳，单位秒，符合彩虹易支付接口要求
        'sign_type'   => PAY_SIGN_TYPE,
    ];
    $params['sign'] = buildSign($params, $keys);
    $submitParamsJson = json_encode($params, JSON_UNESCAPED_SLASHES);

    // ★ 一次性 INSERT：submit_params 直接写入，避免 INSERT/UPDATE 之间被锁或异常导致空参数
    $now = time();
    $stmt = $db->prepare("INSERT INTO pay_orders (out_trade_no, player_name, tier_id, money, bond_amount, status, name, submit_params, created_at) VALUES (:no, :p, 0, :m, :b, 'created', :n, :sp, :t)");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->bindValue(':p', $player, SQLITE3_TEXT);
    $stmt->bindValue(':m', $money, SQLITE3_TEXT);
    $stmt->bindValue(':b', $bonds, SQLITE3_INTEGER);
    $stmt->bindValue(':n', $name, SQLITE3_TEXT);
    $stmt->bindValue(':sp', $submitParamsJson, SQLITE3_TEXT);
    $stmt->bindValue(':t', $now, SQLITE3_INTEGER);
    $stmt->execute();

    debugLog('[createOrder] 订单创建成功', ['out_trade_no' => $outTradeNo, 'player' => $player, 'submit_params_len' => strlen($submitParamsJson)]);

    // 支付网关要求 POST 提交（$_POST['pid']），不能 GET 直接跳转。
    // 返回本地中转页 URL：浏览器打开后由 JS 自动 POST 表单到网关，规避 GET 传输下 notify_url 内 & 被截断的问题。
    $scriptBase = preg_replace('/\?.*$/', '', PAY_NOTIFY_URL);
    $payUrl = $scriptBase . '?action=pay_redirect&out_trade_no=' . urlencode($outTradeNo);

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

    $params = $_REQUEST;   // 平台回调以 GET 方式把签名参数 append 到 notify_url 后，统一用 $_REQUEST 兼容 GET/POST
    // 记录详细的请求信息用于调试
    $requestInfo = [
        'method' => $_SERVER['REQUEST_METHOD'] ?? 'unknown',
        'remote_addr' => $_SERVER['REMOTE_ADDR'] ?? 'unknown',
        'user_agent' => $_SERVER['HTTP_USER_AGENT'] ?? 'unknown',
        'query_string' => $_SERVER['QUERY_STRING'] ?? '',
        'params_count' => count($params),
        'params_keys' => array_keys($params),
    ];
    debugLog('[pay notify] 收到异步回调', array_merge($requestInfo, $params));

    $keys = loadPayKeys();

    // 1) 验签（兼容平台RSA/MD5回调）
    if (!verifyNotifySign($params, $keys)) {
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

    $db = getOrdersDB();          // orders.db — pay_orders
    ensurePayOrdersTable($db);
    $webDb = getDB();             // web.db — web_transactions
    ensureWebTransactionsTable($webDb);

    $stmt = $db->prepare("SELECT * FROM pay_orders WHERE out_trade_no = :no");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        debugLog('[pay notify] 本地订单不存在: ' . $outTradeNo . '，尝试从平台同步');
        // 本地没有订单 → 尝试从平台MySQL补建
        $platformResult = checkPlatformOrderStatus($outTradeNo);
        if (is_array($platformResult) && $platformResult['status'] === 'paid') {
            $now = time();
            $platformPlayer = $platformResult['player'] ?? ($params['param'] ?? 'unknown');
            $bonds = PAY_TEST_BONDS;
            $ins = $db->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, money, bond_amount, status, name, submit_params, created_at, paid_at) VALUES (:no, :tn, :p, :m, :b, 'paid', :n, '', :t, :t)");
            $ins->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $ins->bindValue(':tn', $platformResult['trade_no'] ?? '', SQLITE3_TEXT);
            $ins->bindValue(':p', $platformPlayer, SQLITE3_TEXT);
            $ins->bindValue(':m', $platformResult['money'] ?? '0.01', SQLITE3_TEXT);
            $ins->bindValue(':b', $bonds, SQLITE3_INTEGER);
            $ins->bindValue(':n', PAY_TEST_NAME, SQLITE3_TEXT);
            $ins->bindValue(':t', $now, SQLITE3_INTEGER);
            $ins->execute();

            // 写 web_transactions → web.db
            $txIns = $webDb->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(callback同步)', '在线充值(支付宝)', :d, 'pending', :t)");
            $txIns->bindValue(':p', $platformPlayer, SQLITE3_TEXT);
            $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
            $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
            $txIns->execute();

            debugLog('[pay notify] callback同步成功', ['out_trade_no' => $outTradeNo, 'player' => $platformPlayer]);
            echo 'success';
            exit;
        }
        // 平台也没有 → 返回success避免死循环
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

    // 5) 标记订单已支付（orders.db）
    $stmt = $db->prepare("UPDATE pay_orders SET status='paid', trade_no=:tn, paid_at=:t WHERE out_trade_no=:no");
    $stmt->bindValue(':tn', $params['trade_no'] ?? '', SQLITE3_TEXT);
    $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->execute();

    // 6) 写 web_transactions(recharge) 供 Java 高频定时器拉取 → addBonds 到账 → web.db
    $stmt = $webDb->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台', '在线充值(支付宝)', :d, 'pending', :t)");
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
//  动作：pay_redirect（本地中转页，浏览器打开后自动 POST 到支付网关）
// ====================================================================
function handlePayRedirect() {
    while (ob_get_level() > 0) { ob_end_clean(); }
    $outTradeNo = getParam('out_trade_no');
    if (!$outTradeNo) {
        debugLog('[pay_redirect] 缺少订单号');
        header('Content-Type: text/html; charset=utf-8');
        echo '缺少订单号';
        exit;
    }
    $db = getOrdersDB();
    ensurePayOrdersTable($db);
    $stmt = $db->prepare("SELECT submit_params, status, player_name FROM pay_orders WHERE out_trade_no = :no");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        debugLog('[pay_redirect] 订单不存在', ['out_trade_no' => $outTradeNo]);
        header('Content-Type: text/html; charset=utf-8');
        echo '订单不存在';
        exit;
    }
    if (empty($row['submit_params'])) {
        debugLog('[pay_redirect] submit_params为空', ['out_trade_no' => $outTradeNo, 'status' => $row['status'], 'player' => $row['player_name']]);
        header('Content-Type: text/html; charset=utf-8');
        echo '支付参数尚未生成，请返回重试';
        exit;
    }
    $params = json_decode($row['submit_params'], true);
    if (!is_array($params)) {
        header('Content-Type: text/html; charset=utf-8');
        echo '支付参数损坏';
        exit;
    }

    // 已支付则直接跳回结果页，不再重复提交
    if ($row['status'] === 'paid') {
        header('Location: ' . PAY_RETURN_URL, true, 302);
        exit;
    }

    $fields = '';
    foreach ($params as $k => $v) {
        $fields .= '<input type="hidden" name="' . htmlspecialchars($k, ENT_QUOTES) . '" value="' . htmlspecialchars($v, ENT_QUOTES) . '">' . "\n";
    }
    header('Content-Type: text/html; charset=utf-8');
    echo '<!DOCTYPE html><html><head><meta charset="utf-8"><title>正在跳转支付...</title></head><body>' .
         '<form id="payform" action="' . htmlspecialchars(PAY_GATEWAY, ENT_QUOTES) . '" method="post">' .
         $fields .
         '</form>' .
         '<script>document.getElementById("payform").submit();</script>' .
         '</body></html>';
    exit;
}

// ====================================================================
//  辅助：后台异步触发支付轮询器（非阻塞）
// ====================================================================
function triggerPollerAsync() {
    // ★ 2026-07-15 改用 poller_online.php 补单（绕过 HTTP 回调，CF WAF 拦截 notify）
    $url = 'https://caoyuan.ypshidifu.cn/plugin/api/poller_online.php';
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, 3);
    curl_setopt($ch, CURLOPT_NOSIGNAL, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
    // 不等待结果，后台执行
    curl_exec($ch);
    curl_close($ch);
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
    $db = getOrdersDB();
    ensurePayOrdersTable($db);
    $stmt = $db->prepare("SELECT * FROM pay_orders WHERE out_trade_no=:no AND player_name=:p");
    $stmt->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
    $stmt->bindValue(':p', $info['player'], SQLITE3_TEXT);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

    // ★ 本地订单不存在 → 双路查询（MySQL + 官方API），如果平台已支付则同步到本地
    if (!$row) {
        debugLog('[queryOrder] 本地订单不存在，尝试双路查询同步', ['out_trade_no' => $outTradeNo, 'player' => $info['player']]);

        // 路径1：平台MySQL直查
        debugLog('[queryOrder] 路径1: 平台MySQL直查', ['out_trade_no' => $outTradeNo]);
        $platformResult = checkPlatformOrderStatus($outTradeNo);
        $isPaid = is_array($platformResult) && $platformResult['status'] === 'paid';
        debugLog('[queryOrder] MySQL查询结果', [
            'out_trade_no' => $outTradeNo,
            'result'       => $platformResult,
            'is_paid'      => $isPaid,
        ]);

        // 路径2：MySQL失败或未支付 → 官方API查询
        if (!$isPaid) {
            debugLog('[queryOrder] 路径2: MySQL未找到已支付记录，尝试官方API', ['out_trade_no' => $outTradeNo]);
            $apiResult = queryPlatformAPI($outTradeNo);
            debugLog('[queryOrder] 官方API查询结果', [
                'out_trade_no' => $outTradeNo,
                'result'       => $apiResult,
            ]);
            if (is_array($apiResult) && $apiResult['status'] === 'paid') {
                $platformResult = $apiResult;
                $isPaid = true;
                debugLog('[queryOrder] 官方API查询到已支付', ['out_trade_no' => $outTradeNo]);
            }
        }

        if ($isPaid) {
            // 平台已支付 → 创建本地订单并标记paid
            $now = time();
            $platformPlayer = $platformResult['player'] ?: $info['player'];
            $bonds = PAY_TEST_BONDS;
            $money = $platformResult['money'] ?? PAY_TEST_MONEY;
            $ins = $db->prepare("INSERT OR IGNORE INTO pay_orders (out_trade_no, trade_no, player_name, money, bond_amount, status, name, submit_params, created_at, paid_at) VALUES (:no, :tn, :p, :m, :b, 'paid', :n, '', :t, :t)");
            $ins->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $ins->bindValue(':tn', $platformResult['trade_no'] ?? '', SQLITE3_TEXT);
            $ins->bindValue(':p', $platformPlayer, SQLITE3_TEXT);
            $ins->bindValue(':m', $money, SQLITE3_TEXT);
            $ins->bindValue(':b', $bonds, SQLITE3_INTEGER);
            $ins->bindValue(':n', PAY_TEST_NAME, SQLITE3_TEXT);
            $ins->bindValue(':t', $now, SQLITE3_INTEGER);
            $ins->execute();

            // 写 web_transactions → web.db
            $webDb = getDB();
            $txCheck = $webDb->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
            $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);
            if (!$txRow) {
                $txIns = $webDb->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(查询同步)', '在线充值(支付宝)', :d, 'pending', :t)");
                $txIns->bindValue(':p', $platformPlayer, SQLITE3_TEXT);
                $txIns->bindValue(':a', $bonds, SQLITE3_INTEGER);
                $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
                $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
                $txIns->execute();
            }

            debugLog('[queryOrder] 平台同步成功', ['out_trade_no' => $outTradeNo, 'player' => $platformPlayer]);
            success([
                'out_trade_no' => $outTradeNo,
                'status'       => 'paid',
                'money'        => $money,
                'bonds'        => $bonds,
                'paid_at'      => $now,
                'created_at'   => $now,
            ], '已支付，游戏内债券发放中（若未到账请稍候或联系管理员）');
            return;
        }
        // 平台也没有 → 真的不存在
        debugLog('[queryOrder] 平台也无此订单', ['out_trade_no' => $outTradeNo]);
        error('订单不存在，请确认订单号是否正确');
    }

    // ★ 2026-07-15 核心修复：如果本地还是 created，双路查询（MySQL + 官方API）
    // 不依赖 poller 补单，确保支付成功后立即返回 paid 状态
    $status = $row['status'];
    if ($status !== 'paid') {
        // 路径1：平台MySQL直查
        $platformResult = checkPlatformOrderStatus($outTradeNo);
        $platformPaid = is_array($platformResult) && $platformResult['status'] === 'paid';

        // 路径2：MySQL失败或未支付 → 官方API查询
        if (!$platformPaid) {
            debugLog('[queryOrder] MySQL未支付，尝试官方API', ['out_trade_no' => $outTradeNo]);
            $apiResult = queryPlatformAPI($outTradeNo);
            if (is_array($apiResult) && $apiResult['status'] === 'paid') {
                $platformResult = $apiResult;
                $platformPaid = true;
            }
        }

        if ($platformPaid) {
            // 平台已支付，立即更新本地状态
            $now = time();
            $upd = $db->prepare("UPDATE pay_orders SET status='paid', paid_at=:t WHERE out_trade_no=:no AND status != 'paid'");
            $upd->bindValue(':t', $now, SQLITE3_INTEGER);
            $upd->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
            $upd->execute();

            // 写 web_transactions（幂等）→ web.db
            $webDb = getDB();
            $txCheck = $webDb->prepare("SELECT id FROM web_transactions WHERE detail = :d AND type = 'recharge' LIMIT 1");
            $txCheck->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
            $txRow = $txCheck->execute()->fetchArray(SQLITE3_ASSOC);
            if (!$txRow) {
                $txIns = $webDb->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:p, 'recharge', :a, '支付平台(直查)', '在线充值(支付宝)', :d, 'pending', :t)");
                $txIns->bindValue(':p', $info['player'], SQLITE3_TEXT);
                $txIns->bindValue(':a', (int)$row['bond_amount'], SQLITE3_INTEGER);
                $txIns->bindValue(':d', $outTradeNo, SQLITE3_TEXT);
                $txIns->bindValue(':t', $now, SQLITE3_INTEGER);
                $txIns->execute();
            }

            $status = 'paid';
            debugLog('[queryOrder] 直查平台补单成功', ['out_trade_no' => $outTradeNo, 'player' => $info['player']]);
        } else {
            // 检查是否已过期（5分钟）
            $nowSec = time();
            if ($row['created_at'] && ($nowSec - $row['created_at']) > 300) {
                $upd = $db->prepare("UPDATE pay_orders SET status='expired' WHERE out_trade_no=:no AND status='created'");
                $upd->bindValue(':no', $outTradeNo, SQLITE3_TEXT);
                $upd->execute();
                $status = 'expired';
            }
        }
    }

    $message = '订单创建中';
    if ($status === 'paid') {
        $message = '已支付，游戏内债券发放中（若未到账请稍候或联系管理员）';
    } elseif ($status === 'expired') {
        $message = '订单已过期（超过5分钟），请重新下单';
    }
    success([
        'out_trade_no' => $outTradeNo,
        'status'       => $status,
        'money'        => $row['money'],
        'bonds'        => (int)$row['bond_amount'],
        'paid_at'      => $row['paid_at'],
        'created_at'   => $row['created_at'],
    ], $message);
}

/**
 * 直接查平台MySQL检查订单状态（不依赖poller）
 * @return string 'paid'|'created'|'not_found'
 */
function checkPlatformOrderStatus($outTradeNo) {
    try {
        $pdo = getPlatformDB();
        $stmt = $pdo->prepare("SELECT status, trade_no, money, param, addtime FROM `pay_order` WHERE out_trade_no = ? LIMIT 1");
        $stmt->execute([$outTradeNo]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);
        if (!$row) return 'not_found';
        if (in_array($row['status'], [1, 2])) {
            return ['status' => 'paid', 'trade_no' => $row['trade_no'], 'money' => $row['money'], 'player' => $row['param'] ?? ''];
        }
        return 'not_paid';
    } catch (\Throwable $e) {
        debugLog('[checkPlatformOrderStatus] 查询失败', ['error' => $e->getMessage(), 'out_trade_no' => $outTradeNo]);
        return 'error';
    }
}

/**
 * 通过官方支付API查询订单状态（MD5签名）
 * @param string $outTradeNo 商户订单号
 * @return array|bool 成功返回 ['status'=>'paid', 'trade_no'=>..., 'money'=>..., 'player'=>...]，失败返回 false
 */
function queryPlatformAPI($outTradeNo) {
    $keys = getPayKeys();
    if (empty($keys['pid']) || empty($keys['md5_key'])) {
        debugLog('[queryPlatformAPI] 缺少pid或md5_key', ['pid' => $keys['pid'] ?? '']);
        return false;
    }

    try {
        $params = [
            'pid'          => $keys['pid'],
            'out_trade_no' => $outTradeNo,
            'timestamp'    => (string)time(),
            'sign_type'    => 'MD5',
        ];
        $params['sign'] = buildSign($params, $keys);

        debugLog('[queryPlatformAPI] 开始查询', [
            'out_trade_no' => $outTradeNo,
            'pid'          => $keys['pid'],
            'timestamp'    => $params['timestamp'],
            'sign_type'    => 'MD5',
        ]);

        $url = 'https://zf.ypshidifu.cn/api/pay/query';
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => http_build_query($params),
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 15,
            CURLOPT_SSL_VERIFYPEER => false,
            CURLOPT_HTTPHEADER     => ['Content-Type: application/x-www-form-urlencoded'],
        ]);
        $resp = curl_exec($ch);
        $err  = curl_error($ch);
        $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $elapsed = curl_getinfo($ch, CURLINFO_TOTAL_TIME);
        curl_close($ch);

        debugLog('[queryPlatformAPI] curl完成', [
            'http_code'  => $code,
            'elapsed_s'  => round($elapsed, 3),
            'error'      => $err ?: 'none',
            'resp_len'   => strlen($resp ?? ''),
        ]);

        if ($err) {
            debugLog('[queryPlatformAPI] curl失败', ['error' => $err, 'out_trade_no' => $outTradeNo]);
            return false;
        }

        // 记录完整响应（前500字符）
        debugLog('[queryPlatformAPI] 响应内容', [
            'out_trade_no' => $outTradeNo,
            'response'     => substr($resp ?? '', 0, 500),
        ]);

        $data = json_decode($resp, true);
        if (!$data || ($data['code'] ?? -1) !== 0) {
            debugLog('[queryPlatformAPI] 接口返回非成功', [
                'code'         => $data['code'] ?? 'null',
                'msg'          => $data['msg'] ?? '',
                'out_trade_no' => $outTradeNo,
            ]);
            return false;
        }

        // status: 0=未支付, 1=已支付, 2=已退款, 3=已冻结, 4=预授权
        $st = (int)($data['status'] ?? -1);
        debugLog('[queryPlatformAPI] 订单状态', [
            'status'       => $st,
            'status_text'  => ['0'=>'未支付','1'=>'已支付','2'=>'已退款','3'=>'已冻结','4'=>'预授权'][$st] ?? '未知',
            'out_trade_no' => $outTradeNo,
            'trade_no'     => $data['trade_no'] ?? '',
            'money'        => $data['money'] ?? '',
            'param'        => $data['param'] ?? '',
        ]);

        if ($st === 1) {
            return [
                'status'   => 'paid',
                'trade_no' => $data['trade_no'] ?? '',
                'money'    => $data['money'] ?? '',
                'player'   => $data['param'] ?? '',
            ];
        }
        return false;

    } catch (\Throwable $e) {
        debugLog('[queryPlatformAPI] 异常', [
            'error'        => $e->getMessage(),
            'code'         => $e->getCode(),
            'file'         => basename($e->getFile()),
            'line'         => $e->getLine(),
            'out_trade_no' => $outTradeNo,
        ]);
        return false;
    }
}

// ====================================================================
//  动作：find_recent_order（localStorage丢失时的兜底：查最近订单）
// ====================================================================
function findRecentOrder($token) {
    $info = validateTokenSilent($token);
    if (!$info || empty($info['player'])) {
        error('登录状态无效', 401);
    }
    $db = getOrdersDB();
    ensurePayOrdersTable($db);
    // 查最近5分钟内的订单（优先created，再paid）
    $fiveMinAgo = time() - 300;
    $stmt = $db->prepare("SELECT out_trade_no, status, created_at, money, bond_amount, paid_at FROM pay_orders WHERE player_name=:p AND created_at > :t ORDER BY created_at DESC LIMIT 5");
    $stmt->bindValue(':p', $info['player'], SQLITE3_TEXT);
    $stmt->bindValue(':t', $fiveMinAgo, SQLITE3_INTEGER);
    $rows = [];
    $result = $stmt->execute();
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $rows[] = $row;
    }
    if (empty($rows)) {
        // 本地没有 → 查平台MySQL
        // 扩大到30分钟
        $thirtyMinAgo = time() - 1800;
        $stmt2 = $db->prepare("SELECT out_trade_no, status, created_at, money, bond_amount, paid_at FROM pay_orders WHERE player_name=:p AND created_at > :t ORDER BY created_at DESC LIMIT 5");
        $stmt2->bindValue(':p', $info['player'], SQLITE3_TEXT);
        $stmt2->bindValue(':t', $thirtyMinAgo, SQLITE3_INTEGER);
        $result2 = $stmt2->execute();
        while ($row = $result2->fetchArray(SQLITE3_ASSOC)) {
            $rows[] = $row;
        }
    }
    success(['orders' => $rows, 'player' => $info['player']]);
}

// ====================================================================
//  动作：get_shop_products — 获取上架商品列表（前端充值页面使用）
// ====================================================================
/**
 * 解析有效期日期（支持yyyy-MM-dd和中文格式如"2026年7月16日"）
 */
function parseExpireDate($expireStr) {
    if (empty($expireStr)) {
        return ['valid' => true, 'timestamp' => 0, 'display' => '长期有效'];
    }
    $expireStr = trim($expireStr);

    // yyyy-MM-dd
    if (preg_match('/^\d{4}-\d{1,2}-\d{1,2}$/', $expireStr)) {
        $time = strtotime($expireStr . ' 23:59:59');
        if ($time !== false) {
            return ['valid' => true, 'timestamp' => $time, 'display' => date('Y-m-d', $time) . ' 到期'];
        }
    }

    // 中文格式（如"2026年7月16日"）
    if (preg_match('/(\d{4})年(\d{1,2})月(\d{1,2})日/', $expireStr, $matches)) {
        $time = mktime(23, 59, 59, intval($matches[2]), intval($matches[3]), intval($matches[1]));
        if ($time !== false) {
            $now = time();
            return [
                'valid'     => $time >= $now,
                'timestamp' => $time,
                'display'   => date('Y-m-d', $time) . ($time >= $now ? ' 到期' : ' 已过期')
            ];
        }
    }

    // 无法解析 → 视为无效
    return ['valid' => false, 'timestamp' => 0, 'display' => '日期格式错误: ' . $expireStr];
}

function getShopProducts() {
    $db = getOrdersDB();

    // 确保shop_configs表存在
    $db->exec("CREATE TABLE IF NOT EXISTS shop_configs (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        item_name TEXT NOT NULL DEFAULT '',
        stock INTEGER NOT NULL DEFAULT -1,
        temporary_offer TEXT NOT NULL DEFAULT '',
        offer_expire TEXT NOT NULL DEFAULT '',
        price REAL NOT NULL DEFAULT 0,
        discount_price REAL NOT NULL DEFAULT 0,
        bond_reward TEXT NOT NULL DEFAULT '1-1',
        is_active INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL DEFAULT 0
    )");
    // 兼容旧表：检查并添加discount_price字段
    $hasCol = false;
    $cols = $db->query("PRAGMA table_info(shop_configs)");
    while ($col = $cols->fetchArray(SQLITE3_ASSOC)) {
        if ($col['name'] === 'discount_price') { $hasCol = true; break; }
    }
    if (!$hasCol) {
        $db->exec("ALTER TABLE shop_configs ADD COLUMN discount_price REAL NOT NULL DEFAULT 0");
    }

    // 查询上架商品（is_active=1）
    $stmt = $db->query("SELECT * FROM shop_configs WHERE is_active = 1 ORDER BY id ASC");
    $products = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        // ★ 售罄(stock=0)不隐藏：仍返回给前端展示，仅禁止购买。
        //   只有 is_active=0（下架）才不返回（见上方 WHERE 条件）。

        // 解析债券范围（支持 "1-10" 格式）
        $bondReward = $row['bond_reward'];
        if (preg_match('/^(\d+)-(\d+)$/', $bondReward, $m)) {
            $bondMin = intval($m[1]);
            $bondMax = intval($m[2]);
        } else {
            $bondMin = $bondMax = intval($bondReward);
        }

        // 解析有效期
        $expireInfo = parseExpireDate($row['offer_expire']);

        // 计算实际显示价格（如果有折扣价格且折扣价格>0）
        $displayPrice = $row['price'];
        if (isset($row['discount_price']) && $row['discount_price'] > 0) {
            $displayPrice = $row['discount_price'];
        }

        $products[] = [
            'id' => $row['id'],
            'item_name' => $row['item_name'],
            'stock' => $row['stock'],
            'temporary_offer' => $row['temporary_offer'],
            'offer_expire' => $row['offer_expire'],
            'expire_valid' => $expireInfo['valid'],
            'expire_display' => $expireInfo['display'],
            'price' => $row['price'],
            'discount_price' => $row['discount_price'] ?? 0,
            'display_price' => $displayPrice,
            'bond_reward' => $bondReward,
            'bond_min' => $bondMin,
            'bond_max' => $bondMax,
            'is_active' => $row['is_active']
        ];
    }

    success(['products' => $products]);
}

// ====================================================================
//  入口分发
// ====================================================================
$action = getParam('action', '');
try {
    switch ($action) {
        case 'create_order':
            createOrder(getParam('token'), intval(getParam('product_id', 0)));
            break;
        case 'notify':
            handleNotify();   // 内部已 exit
            break;
        case 'pay_redirect':
            handlePayRedirect();   // 内部已 exit
            break;
        case 'sync_poller':
            syncPoller(getParam('token'));
            break;
        case 'query_order':
            queryOrder(getParam('token'));
            break;
        case 'find_recent_order':
            findRecentOrder(getParam('token'));
            break;
        case 'get_shop_products':
            getShopProducts();
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
