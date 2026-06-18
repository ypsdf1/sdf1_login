<?php
/**
 * 管理API
 */

// ★ 清除所有缓冲 + 发送 JSON Content-Type
while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();
// 确保不会有任何前置输出污染JSON
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');
header('Pragma: no-cache');

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
ini_set('memory_limit', '256M');

if (function_exists('opcache_reset')) { @opcache_reset(); }
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__, true); }

if (!extension_loaded('sqlite3')) {
    exit(json_encode(['success' => false, 'message' => 'Missing SQLite3'], JSON_UNESCAPED_UNICODE));
}

require_once __DIR__ . '/../core.php';

if (!function_exists('error') || !function_exists('success') || !function_exists('getParam')) {
    exit(json_encode(['success' => false, 'message' => 'core.php load failed'], JSON_UNESCAPED_UNICODE));
}

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

$action = getParam('action', 'status');

switch ($action) {
    case 'login':        adminDoLogin(); break;
    case 'logout':       adminDoLogout(); break;
    case 'status':       adminStatus(); break;
    case 'deduct':       adminDeduct(); break;
    case 'add_bonds':    adminAddBonds(); break;
    case 'shop_update':  adminShopUpdate(); break;
    case 'shop_add':     adminShopAdd(); break;
    case 'shop_remove':  adminShopRemove(); break;
    case 'stats':        adminStats(); break;
    case 'all_tx':       adminAllTx(); break;
    case 'player_tx':    adminPlayerTx(); break;
    case 'gen_token':    adminGenToken(); break;
    case 'cdk_batch':    adminGenCDK(); break;
    case 'notify_sync':  adminNotifySync(); break;
    case 'sync_now':     adminSyncNow(); break;
    case 'push_shop':    adminPushShop(); break;
    case 'push_bonds':   adminPushBonds(); break;
    case 'list_users':   adminListUsers(); break;
    case 'admin_approve_reset': adminApproveReset(); break;
    case 'admin_reject_reset':  adminRejectReset(); break;
    case 'list_reset_requests': adminListResetRequests(); break;
    case 'list_online_names':  adminListOnlineNames(); break;
    case 'list_online_players': adminListOnlinePlayers(); break;
    case 'clean_collect_ips':  adminCleanAndCollectIps(); break;
    case 'batch_query_ips':    adminBatchQueryIps(); break;
    case 'list_active_players': adminListActivePlayers(); break;
    case 'get_stats_ex':        adminGetStatsEx(); break;
    case 'list_same_ip':        adminListSameIp(); break;
    case 'list_users_paginated': adminListUsersPaginated(); break;
    default:              exit(json_encode(['success' => false, 'message' => 'Unknown action: ' . $action], JSON_UNESCAPED_UNICODE));
}

// ===== 各函数定义 =====
function adminDoLogin() {
    $password = getParam('password');
    if (!$password) exit(json_encode(['success' => false, 'message' => 'Missing password'], JSON_UNESCAPED_UNICODE));
    if (adminLogin($password)) {
        exit(json_encode(['success' => true, 'data' => ['login_time' => time()], 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
    }
    exit(json_encode(['success' => false, 'message' => '密码错误', 'code' => 401], JSON_UNESCAPED_UNICODE));
}

function adminDoLogout() {
    session_destroy();
    exit(json_encode(['success' => true, 'message' => 'logout'], JSON_UNESCAPED_UNICODE));
}

function adminStatus() {
    exit(json_encode([
        'success' => true,
        'data' => ['logged_in' => isAdminLoggedIn(), 'login_time' => $_SESSION['admin_login_time'] ?? 0],
        'message' => 'ok'
    ], JSON_UNESCAPED_UNICODE));
}

function adminDeduct() {
    requireAdminSession();
    $player = getParam('player');
    $amount = (int)getParam('amount', 0);
    if (!$player) exit(json_encode(['success' => false, 'message' => 'Missing player'], JSON_UNESCAPED_UNICODE));
    if ($amount <= 0) exit(json_encode(['success' => false, 'message' => 'Invalid amount'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
    try {
        $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        $current = $row ? $row['amount'] : 0;
        if ($current < $amount) exit(json_encode(['success' => false, 'message' => "Insufficient: $current"], JSON_UNESCAPED_UNICODE));
        $newAmount = $current - $amount;
        $stmt = $db->prepare("UPDATE bond_cache SET amount = :amount, updated_at = :time WHERE player_name = :name");
        $stmt->bindValue(':amount', $newAmount, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK', 'remaining' => $newAmount], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminAddBonds() {
    requireAdminSession();
    $player = getParam('player');
    $amount = (int)getParam('amount', 0);
    if (!$player) exit(json_encode(['success' => false, 'message' => 'Missing player'], JSON_UNESCAPED_UNICODE));
    if ($amount <= 0) exit(json_encode(['success' => false, 'message' => 'Invalid amount'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
    try {
        $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        $current = $row ? $row['amount'] : 0;
        $newAmount = $current + $amount;
        $stmt = $db->prepare("INSERT OR REPLACE INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $newAmount, SQLITE3_INTEGER);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK', 'new_amount' => $newAmount], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminShopUpdate() {
    requireAdminSession();
    $db = getDB();
    try {
        $stmt = $db->prepare("UPDATE shop_items SET display_name=:dn, buy_price=:bp, sell_price=:sp, stock=:st WHERE id=:id");
        $stmt->bindValue(':dn', getParam('display_name'));
        $stmt->bindValue(':bp', getParam('buy_price'));
        $stmt->bindValue(':sp', getParam('sell_price'));
        $stmt->bindValue(':st', getParam('stock'));
        $stmt->bindValue(':id', getParam('item_id'));
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminShopAdd() {
    requireAdminSession();
    $db = getDB();
    try {
        $stmt = $db->prepare("INSERT OR REPLACE INTO shop_items VALUES (:id,:cat,:dn,:mat,:bp,:sp,:st,0,0,0)");
        $stmt->bindValue(':id', getParam('item_id'));
        $stmt->bindValue(':cat', getParam('category', 'weapon'));
        $stmt->bindValue(':dn', getParam('display_name', ''));
        $stmt->bindValue(':mat', getParam('material', 'stone_sword'));
        $stmt->bindValue(':bp', getParam('buy_price', 0));
        $stmt->bindValue(':sp', getParam('sell_price', -1));
        $stmt->bindValue(':st', getParam('stock', -1));
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminShopRemove() {
    requireAdminSession();
    $db = getDB();
    try {
        $stmt = $db->prepare("DELETE FROM shop_items WHERE id=:id");
        $stmt->bindValue(':id', getParam('item_id'));
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminStats() {
    requireAdminSession();
    $db = getDB();
    try {
        $r = [];
        // 每个SQL独立try-catch，防止某个表不存在导致整体报错
        try {
            $row = $db->query("SELECT SUM(amount) as t FROM bond_cache")->fetchArray(SQLITE3_ASSOC);
            $r['bonds'] = (int)($row['t'] ?? 0);
        } catch (Exception $e) { $r['bonds'] = 0; }
        try {
            $row = $db->query("SELECT COUNT(*) as t FROM cdk WHERE used=0")->fetchArray(SQLITE3_ASSOC);
            $r['cdk'] = (int)($row['t'] ?? 0);
        } catch (Exception $e) { $r['cdk'] = 0; }
        try {
            $row = $db->query("SELECT COUNT(*) as t FROM web_transactions")->fetchArray(SQLITE3_ASSOC);
            $r['tx'] = (int)($row['t'] ?? 0);
        } catch (Exception $e) { $r['tx'] = 0; }
        try {
            $row = $db->query("SELECT COUNT(*) as t FROM shop_items")->fetchArray(SQLITE3_ASSOC);
            $r['shop'] = (int)($row['t'] ?? 0);
        } catch (Exception $e) { $r['shop'] = 0; }
        try {
            $row = $db->query("SELECT COUNT(*) as t FROM users")->fetchArray(SQLITE3_ASSOC);
            $r['users'] = (int)($row['t'] ?? 0);
        } catch (Exception $e) { $r['users'] = 0; }
        exit(json_encode(['success' => true, 'data' => $r], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminAllTx() {
    requireAdminSession();
    $db = getDB();
    try {
        $result = $db->query("SELECT * FROM web_transactions ORDER BY created_at DESC LIMIT 100");
        $txs = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        exit(json_encode(['success' => true, 'data' => $txs], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminPlayerTx() {
    requireAdminSession();
    $player = getParam('player');
    if (!$player) exit(json_encode(['success' => false, 'message' => 'Missing player'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM web_transactions WHERE player_name=:p ORDER BY created_at DESC LIMIT 50");
        $stmt->bindValue(':p', $player);
        $result = $stmt->execute();
        $txs = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        exit(json_encode(['success' => true, 'data' => $txs], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminGenToken() {
    requireAdminSession();
    $player = getParam('player');
    if (!$player) exit(json_encode(['success' => false, 'message' => 'Missing player'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    try {
        $token = bin2hex(random_bytes(32));
        $expires = time() + TOKEN_EXPIRE_SECONDS;
        $stmt = $db->prepare("INSERT OR REPLACE INTO tokens VALUES (:t,:p,:pur,:c,:e,0,0)");
        $stmt->bindValue(':t', $token);
        $stmt->bindValue(':p', $player);
        $stmt->bindValue(':pur', getParam('purpose', 'general'));
        $stmt->bindValue(':c', time());
        $stmt->bindValue(':e', $expires);
        $stmt->execute();
        exit(json_encode(['success' => true, 'data' => ['token' => $token]], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 批量生成CDK =====
function adminGenCDK() {
    requireAdminSession();
    $amount = (int)getParam('amount', 0);
    $count = (int)getParam('count', 0);
    if (!$amount || !$count) exit(json_encode(['success' => false, 'message' => '缺少amount或count参数'], JSON_UNESCAPED_UNICODE));
    if ($count > 100) exit(json_encode(['success' => false, 'message' => '单次最多生成100个CDK'], JSON_UNESCAPED_UNICODE));

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (
        code TEXT PRIMARY KEY,
        amount INTEGER NOT NULL,
        used INTEGER DEFAULT 0,
        used_by TEXT DEFAULT '',
        created_at INTEGER NOT NULL
    )");

    $generated = [];
    $now = time();
    try {
        $db->exec('BEGIN TRANSACTION');
        for ($i = 0; $i < $count; $i++) {
            // 生成6位随机CDK码
            $code = strtoupper(substr(str_shuffle('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'), 0, 6));
            $stmt = $db->prepare("INSERT OR IGNORE INTO cdk (code, amount, used, created_at) VALUES (:code, :amount, 0, :time)");
            $stmt->bindValue(':code', $code, SQLITE3_TEXT);
            $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
            $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
            $stmt->execute();
            $generated[] = $code;
        }
        $db->exec('COMMIT');

        exit(json_encode([
            'success' => true,
            'data' => ['codes' => $generated],
            'message' => '生成了' . count($generated) . '个CDK'
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        exit(json_encode(['success' => false, 'message' => '数据库错误: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

function adminNotifySync() {
    requireAdminSession();
    $player = getParam('player');
    if (!$player) exit(json_encode(['success' => false, 'message' => 'Missing player'], JSON_UNESCAPED_UNICODE));
    exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
}

function adminSyncNow() {
    requireAdminSession();
    exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
}

function adminPushShop() {
    requireAdminSession();
    exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
}

function adminPushBonds() {
    requireAdminSession();
    exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
}

// ============================================================
// IP归属地查询 - 四线国内免费大厂API + 统一中文输出
// 线路1: 太平洋电脑网 (pconline.com.cn - DNSPod底层) ← 首选，最快最稳
// 线路2: 百度OpenData  (baidu.com) ← 备用1
// 线路3: aa1.cn IP查百度 (aa1.cn) ← 备用2
// 线路4: IP9免费查询 (ip9.com.cn) ← 备用3，串行请求（免费版60次/分钟限制）
// 统一格式: "省份城市 运营商" 或 "省份 运营商"（无城市时）
// 特殊：机房/数据中心只显示机房所在省份 + 运营商
// ============================================================

/**
 * 清理旧格式 IP 归属缓存（懒清理，按需执行）
 * 只在查询到新 IP 时才清理数据库中对应 IP 的旧缓存
 * 避免全表扫描导致后台卡顿
 */
function cleanOldIpLocationForIp($ip, $db) {
    if (!$ip || $ip === '-' || $ip === '127.0.0.1') return;
    try {
        $stmt = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip LIMIT 1");
        $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && isset($row['location'])) {
            $cachedLoc = $row['location'];
            // ★ 只清理无效格式（内网IP、查询失败、短横线），不清理有效格式
            // 有效格式是 "省份城市 运营商" 或 "省份 运营商"
            $isValid = (strpos($cachedLoc, 'China - ') === 0
                     || strpos($cachedLoc, '中国 ') === 0
                     || strpos($cachedLoc, '省') !== false || strpos($cachedLoc, '市') !== false
                     || strpos($cachedLoc, '广东') !== false || strpos($cachedLoc, '北京') !== false
                     || strpos($cachedLoc, '上海') !== false || strpos($cachedLoc, '浙江') !== false
                     || strpos($cachedLoc, '江苏') !== false || strpos($cachedLoc, '四川') !== false);
            
            if (!$isValid && ($cachedLoc === '内网IP' || $cachedLoc === '查询失败' || $cachedLoc === '-' || $cachedLoc === '--')) {
                // 只有明确无效的才删除
                $delStmt = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
                $delStmt->bindValue(':ip', $ip, SQLITE3_TEXT);
                $delStmt->execute();
            }
            // 如果无法判断格式有效性，不做任何删除操作
        }
    } catch (Exception $e) {
        // 表不存在则忽略
    }
}

function queryIpLocation($ip) {
    if (!$ip || $ip === '-' || $ip === '127.0.0.1' || strpos($ip, '10.') === 0 || strpos($ip, '192.168.') === 0) {
        return ['location' => '内网IP'];
    }
    
    // 进程内内存缓存（static 变量，同一请求内不重复查API）
    static $memCache = [];
    if (isset($memCache[$ip])) {
        $cached = $memCache[$ip];
        @error_log("[IP_QUERY] Cache hit (memory): $ip -> " . ($cached['location'] ?? 'null'));
        return $cached;
    }
    
    // 按IP查询本地缓存表
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip LIMIT 1");
        $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && isset($row['location'])) {
            $cachedLoc = $row['location'];
            // ★ 使用统一格式校验
            $isValidFormat = isValidIpLocationFormat($cachedLoc);
            
            if (!$isValidFormat) {
                // 无效缓存，直接删除
                @error_log("[IP_QUERY] Invalid cache (will query API): $ip -> $cachedLoc");
                try {
                    $stmt2 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
                    $stmt2->bindValue(':ip', $ip, SQLITE3_TEXT);
                    $stmt2->execute();
                } catch (Exception $e) {}
            } else {
                // 格式正确，直接使用
                @error_log("[IP_QUERY] Cache hit (database): $ip -> $cachedLoc");
                $memCache[$ip] = ['location' => $cachedLoc, 'ip' => $ip];
                return $memCache[$ip];
            }
        }
    } catch (Exception $e) {
        // 表不存在则忽略
    }
    
    // ★ 直接调用 doQueryIpLocation（内含4-API链式查询 + 格式验证）
    @error_log("[IP_QUERY] Querying API: $ip");
    $location = doQueryIpLocation($ip);
    
    if ($location && isset($location['location'])) {
        @error_log("[IP_QUERY] API RESULT: $ip -> " . $location['location']);
    } else {
        @error_log("[IP_QUERY] API RESULT: $ip -> null/failure");
    }
    
    // 存入本地缓存表
    if ($location && $location['location'] !== '查询失败') {
        // ★ 格式验证和修复
        $loc = $location['location'];

        // 1. 过滤英文格式
        if (strpos($loc, 'China - ') === 0 || strpos($loc, 'China -') === 0) {
            @error_log("[IP_QUERY] Rejected English format: $ip -> $loc");
            $location = null;
        }

        // 2. 过滤不完整的中文格式
        if ($location && (strpos($loc, '中国 - -') === 0 || strpos($loc, '中国 -  -') === 0)) {
            @error_log("[IP_QUERY] Rejected incomplete Chinese format: $ip -> $loc");
            $location = null;
        }

        // 3. 格式修复：确保"位置"和"运营商"之间有空格
        // 仅在没有空格时修复（如"广东省移动"→"广东省 移动"）
        // 已有空格的（如"湖北省武汉市 移动"）不做修改
        if (strpos($loc, ' ') === false) {
            // 无空格，尝试添加空格
            // 匹配：省/市/州/盟/地区/区/县 后面紧跟汉字（运营商）
            if (preg_match('/^(.*?(?:省|市|州|盟|地区|区|县))(.+)$/u', $loc, $matches)) {
                $loc = $matches[1] . ' ' . $matches[2];
                @error_log("[IP_QUERY] Fixed format (no space): $ip -> " . $location['location'] . " => $loc");
                $location['location'] = $loc;
            }
        }

        // ★ 存储前格式验证
        $storeLoc = $location['location'];
        $shouldStore = isValidIpLocationFormat($storeLoc);
        if (!$shouldStore) {
            @error_log("[IP_QUERY] Rejected invalid format: $ip -> $storeLoc");
            $location = null; // ★ 关键：拒绝后必须置null，防止无效格式泄露到前端
        }

        if ($shouldStore) {
            try {
                $db = getDB();
                $stmt = $db->prepare("INSERT OR REPLACE INTO player_ip_locations (player_name, ip_address, location, updated_at) VALUES (:player, :ip, :loc, :time)");
                $stmt->bindValue(':player', 'global', SQLITE3_TEXT);
                $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
                $stmt->bindValue(':loc', $storeLoc, SQLITE3_TEXT);
                $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
                $stmt->execute();
            } catch (Exception $e) {
                // 表不存在则忽略
            }
        } else {
            @error_log("[IP_QUERY] Skipped storage (invalid format): $ip -> $storeLoc");
            // ★ doQueryIpLocation() 已经尝试过所有4个API（含IP9），无需再重复调用
        }
    }
    
    // 存入内存缓存
    $memCache[$ip] = $location;
    
    return $location;
}

/**
 * 重试查询失败IP的归属地（串行调用IP9，避免并发超限）
 * 只针对之前查询失败的IP
 */
function retryQueryFailedIpLocations() {
    static $retryDone = false;
    if ($retryDone) return;
    $retryDone = true;
    
    try {
        $db = getDB();
        // 查找所有标记为"查询失败"的IP
        $stmt = $db->prepare("SELECT ip_address FROM player_ip_locations WHERE location = '查询失败' LIMIT 50");
        $result = $stmt->execute();
        $failedIps = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $failedIps[] = $row['ip_address'];
        }
        
        if (empty($failedIps)) return;
        
        // 串行请求（免费版60次/分钟限制）
        foreach ($failedIps as $ip) {
            // 写日志
            @error_log('[IP9 Retry] Processing IP: ' . $ip);
            
            $location = queryIpLocationFromIp9($ip);
            @error_log('[IP9 Retry] IP: ' . $ip . ' -> ' . ($location['location'] ?? 'null'));
            
            if ($location && $location['location'] !== '查询失败' && $location['location'] !== '内网IP') {
                // ★ 格式验证（统一函数）
                $retryLoc = $location['location'];
                $retryValid = isValidIpLocationFormat($retryLoc);
                if ($retryValid) {
                    // 更新缓存表
                    try {
                        $stmt2 = $db->prepare("UPDATE player_ip_locations SET location = :loc, updated_at = :time WHERE ip_address = :ip");
                        $stmt2->bindValue(':loc', $retryLoc, SQLITE3_TEXT);
                        $stmt2->bindValue(':ip', $ip, SQLITE3_TEXT);
                        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
                        $stmt2->execute();
                    } catch (Exception $e) {}
                } else {
                    @error_log("[IP9 Retry] Skipped storage (invalid format): $ip -> $retryLoc");
                }
            }
            // 串行：每次请求间隔0.5秒
            usleep(500000);
        }
    } catch (Exception $e) {
        // 表不存在则忽略
    }
}

/**
 * 实际执行IP查询（四线国内免费大厂API + IP9兜底）
 * ★ 核心策略：每个接口返回结果后先验证格式，无效则继续尝试下一个
 * 避免太平洋返回"省份 运营商"（无城市）就直接结束
 */
function doQueryIpLocation($ip) {
    if (!$ip || $ip === '-' || $ip === '127.0.0.1' || strpos($ip, '10.') === 0 || strpos($ip, '192.168.') === 0) {
        return ['location' => '内网IP'];
    }

    static $cache = [];
    if (isset($cache[$ip])) return $cache[$ip];

    // 收集所有结果，优先选有效格式
    $bestResult = null;

    // ========== 线路1: 太平洋电脑网 ==========
    @error_log("[IP_QUERY_API] Trying PConline: $ip");
    $r1 = fetchPconline($ip);
    if ($r1 !== null) {
        $r1Loc = trim($r1);
        @error_log("[IP_QUERY_API] PConline RESULT: $ip -> $r1Loc");
        if (isValidIpLocationFormat($r1Loc)) {
            // 有效格式，直接返回
            @error_log("[IP_QUERY_API] PConline VALID: $ip -> $r1Loc");
            $cache[$ip] = ['location' => $r1Loc, 'ip' => $ip];
            return $cache[$ip];
        } else {
            @error_log("[IP_QUERY_API] PConline INVALID FORMAT (will try next): $ip -> $r1Loc");
            $bestResult = $r1Loc; // 暂存，万一后面都没结果就用这个
        }
    } else {
        @error_log("[IP_QUERY_API] PConline FAILED: $ip");
    }

    // ========== 线路2: 百度OpenData ==========
    @error_log("[IP_QUERY_API] Trying Baidu: $ip");
    $r2 = fetchIpApiWithTimeout('https://opendata.baidu.com/api.php?query=' . urlencode($ip) . '&resource_id=6006&oe=utf8&format=json', function($d) {
        if ($d && isset($d['data']) && is_array($d['data']) && !empty($d['data'])) {
            $loc = $d['data'][0]['location'] ?? null;
            @error_log("[IP_QUERY_API] Baidu RAW DATA: " . json_encode($d, JSON_UNESCAPED_UNICODE));
            if ($loc && $loc !== '--' && strpos($loc, '中国 ') !== 0) return $loc;
        }
        return null;
    }, 3);
    if ($r2 !== null) {
        @error_log("[IP_QUERY_API] Baidu RESULT: $ip -> $r2");
        if (isValidIpLocationFormat($r2)) {
            @error_log("[IP_QUERY_API] Baidu VALID: $ip -> $r2");
            $cache[$ip] = ['location' => $r2, 'ip' => $ip];
            return $cache[$ip];
        } else {
            @error_log("[IP_QUERY_API] Baidu INVALID FORMAT: $ip -> $r2");
            if (!$bestResult) $bestResult = $r2;
        }
    } else {
        @error_log("[IP_QUERY_API] Baidu FAILED: $ip");
    }

    // ========== 线路3: aa1.cn ==========
    @error_log("[IP_QUERY_API] Trying aa1.cn: $ip");
    $r3 = fetchIpApiWithTimeout('https://v.api.aa1.cn/api/ipcha-baidu/?ip=' . urlencode($ip), function($d) {
        if ($d && isset($d['code']) && $d['code'] == 1) {
            $loc = $d['ip_add'] ?? null;
            @error_log("[IP_QUERY_API] aa1.cn RAW DATA: " . json_encode($d, JSON_UNESCAPED_UNICODE));
            if ($loc && $loc !== '未知' && strpos($loc, '中国 ') !== 0) return $loc;
        }
        return null;
    }, 3);
    if ($r3 !== null) {
        @error_log("[IP_QUERY_API] aa1.cn RESULT: $ip -> $r3");
        if (isValidIpLocationFormat($r3)) {
            @error_log("[IP_QUERY_API] aa1.cn VALID: $ip -> $r3");
            $cache[$ip] = ['location' => $r3, 'ip' => $ip];
            return $cache[$ip];
        } else {
            @error_log("[IP_QUERY_API] aa1.cn INVALID FORMAT: $ip -> $r3");
            if (!$bestResult) $bestResult = $r3;
        }
    } else {
        @error_log("[IP_QUERY_API] aa1.cn FAILED: $ip");
    }

    // ========== 线路4: IP9（兜底，格式最准） ==========
    @error_log("[IP_QUERY_API] Trying IP9 (fallback): $ip");
    $ip9Result = queryIpLocationFromIp9($ip);
    if ($ip9Result && $ip9Result['location'] !== '查询失败' && $ip9Result['location'] !== '内网IP') {
        $r4Loc = $ip9Result['location'];
        @error_log("[IP_QUERY_API] IP9 RESULT: $ip -> $r4Loc");
        if (isValidIpLocationFormat($r4Loc)) {
            @error_log("[IP_QUERY_API] IP9 VALID: $ip -> $r4Loc");
            $cache[$ip] = ['location' => $r4Loc, 'ip' => $ip];
            return $cache[$ip];
        } else {
            @error_log("[IP_QUERY_API] IP9 INVALID FORMAT: $ip -> $r4Loc");
            if (!$bestResult) $bestResult = $r4Loc;
        }
    } else {
        @error_log("[IP_QUERY_API] IP9 FAILED: $ip -> " . ($ip9Result['location'] ?? 'null'));
    }

    // ★ 所有接口都没返回有效格式：返回"查询失败"，不返回无效格式
    @error_log("[IP_QUERY_API] ALL FAILED/INVALID: $ip (bestResult=" . ($bestResult ?: 'null') . ")");
    $cache[$ip] = ['location' => '查询失败'];
    return $cache[$ip];
}

/**
 * 带超时的IP归属地查询（缓存优先 + 超时降级）
 */
function queryIpLocationWithTimeout($ip, $timeout) {
    // 1. 查内存缓存（最快）
    static $memCache2 = [];
    if (isset($memCache2[$ip])) {
        $entry = $memCache2[$ip];
        // 检查格式是否有效
        if (strpos($entry['location'], 'China - ') === 0
            || strpos($entry['location'], '中国 ') === 0
            || $entry['location'] === '内网IP'
            || $entry['location'] === '查询失败'
            || $entry['location'] === '-'
            || $entry['location'] === '--') {
            // 无效缓存，删除
            unset($memCache2[$ip]);
            try {
                $db = getDB();
                $stmt = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
                $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
                $stmt->execute();
            } catch (Exception $e) {}
        } else {
            return $entry;
        }
    }
    
    // 2. 查数据库缓存
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT location FROM player_ip_locations WHERE ip_address = :ip LIMIT 1");
        $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && isset($row['location'])) {
            $cachedLoc = $row['location'];
            // 格式校验
            if (strpos($cachedLoc, 'China - ') === 0
                || strpos($cachedLoc, '中国 ') === 0
                || $cachedLoc === '内网IP'
                || $cachedLoc === '查询失败'
                || $cachedLoc === '-'
                || $cachedLoc === '--') {
                // 无效，删除
                try {
                    $stmt2 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address = :ip");
                    $stmt2->bindValue(':ip', $ip, SQLITE3_TEXT);
                    $stmt2->execute();
                } catch (Exception $e) {}
            } else {
                // 有效缓存，返回
                $memCache2[$ip] = ['location' => $cachedLoc, 'ip' => $ip];
                return $memCache2[$ip];
            }
        }
    } catch (Exception $e) {}
    
    // 3. 缓存未命中或无效，请求API（带超时）
    $result = fetchIpApiWithTimeout('https://whois.pconline.com.cn/ipJson.jsp?ip=' . urlencode($ip) . '&json=true', function($d) {
        if (isset($d['addr']) && trim($d['addr']) !== '' && strpos(trim($d['addr']), '中国 ') !== 0) {
            return trim($d['addr']);
        }
        if (isset($d['pro']) && trim($d['pro']) !== '') {
            $loc = trim($d['pro']);
            if (isset($d['city']) && trim($d['city']) !== '') {
                $loc .= trim($d['city']);
            }
            if (isset($d['addr'])) {
                $parts = preg_split('/\s+/', trim($d['addr']), -1, PREG_SPLIT_NO_EMPTY);
                if (!empty($parts)) {
                    $op = end($parts);
                    if ($op !== '中国') $loc .= ' ' . $op;
                }
            }
            return strlen($loc) > 1 ? $loc : null;
        }
        return null;
    }, $timeout);
    
    if ($result !== null) {
        $entry = ['location' => $result, 'ip' => $ip];
        $memCache2[$ip] = $entry;

        // ★ 存储前格式验证（统一函数）
        $storeLoc = $result;
        $shouldStore = isValidIpLocationFormat($storeLoc);

        if ($shouldStore) {
            // 存库
            try {
                $db = getDB();
                $stmt = $db->prepare("INSERT OR REPLACE INTO player_ip_locations (player_name, ip_address, location, updated_at) VALUES (:player, :ip, :loc, :time)");
                $stmt->bindValue(':player', 'global', SQLITE3_TEXT);
                $stmt->bindValue(':ip', $ip, SQLITE3_TEXT);
                $stmt->bindValue(':loc', $storeLoc, SQLITE3_TEXT);
                $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
                $stmt->execute();
            } catch (Exception $e) {}
        }

        return $entry;
    }
    
    // 4. API请求失败，返回查询失败
    return ['location' => '查询失败'];
}

/**
 * 带超时的通用HTTP请求封装
 */
function fetchIpApiWithTimeout($url, $parser, $timeout = 5) {
    $ch = curl_init();
    curl_setopt($ch, CURLOPT_URL, $url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_TIMEOUT, $timeout);
    curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 2);
    curl_setopt($ch, CURLOPT_USERAGENT, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 SDF1_IP_Lookup/1.0');
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 0);
    curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);
    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    
    if ($httpCode !== 200 || empty($response)) return null;
    
    $data = json_decode($response, true);
    if (!$data) return null;
    
    return $parser($data);
}

/**
 * 太平洋电脑网 API 专用解析（JSONP → 统一格式）
 * ★ 优先使用，最快最稳，返回 "省份城市 运营商" 或 "省份 运营商"（无城市时）
 */
function fetchPconline($ip) {
    $url = 'https://whois.pconline.com.cn/ipJson.jsp?ip=' . urlencode($ip) . '&json=true';
    // ★ 加 stream 超时，防止挂起
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 3,
            'ignore_errors' => true,
            'header' => "Accept: application/json\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 SDF1_IP_Lookup/1.0"
        ]
    ]);
    $response = @file_get_contents($url, false, $ctx);
    if ($response === false || empty(trim($response))) return null;
    
    // ★ 修复编码问题：太平洋返回GBK编码，需要转换为UTF-8
    $response = trim($response);
    if (mb_detect_encoding($response, 'UTF-8', true) === false) {
        $response = mb_convert_encoding($response, 'UTF-8', 'GBK');
    }
    
    // 去除 JSONP 前缀 (可能包含括号)
    $json = $response;
    if (preg_match('/^\s*\(?[\'"]?(.*?)[\'"]?\)?\s*$/s', $json, $matches)) {
        $json = $matches[1];
    }
    
    $data = json_decode($json, true);
    if (!$data || !is_array($data)) return null;
    
    // 优先使用 addr 字段
    if (isset($data['addr']) && trim($data['addr']) !== '') {
        $addr = trim($data['addr']);
        // ★ 过滤掉"中国 X"这种不完整格式
        if (strpos($addr, '中国 ') !== 0) {
            return $addr;
        }
    }
    
    // addr 不完整（如"中国 移动"或"湖南省郴州市 "），尝试用 pro + city 手动拼接
    if (isset($data['pro']) && trim($data['pro']) !== '') {
        $loc = trim($data['pro']);
        if (isset($data['city']) && trim($data['city']) !== '') {
            $loc .= trim($data['city']);
        }
        
        // 从 addr 提取运营商（addr 格式: "湖南省郴州市 电信" 或 "中国 移动"）
        if (isset($data['addr'])) {
            $parts = preg_split('/\s+/', trim($data['addr']), -1, PREG_SPLIT_NO_EMPTY);
            if (!empty($parts)) {
                // 取最后一个非"中国"的词作为运营商
                $op = end($parts);
                if ($op !== '中国') {
                    $loc .= ' ' . $op;
                }
            }
        }
        
        // ★ 如果 addr 有"中国 X"格式但 pro+city 能拼出省市，就用 pro+city+op
        // 如果 addr 本身已经是"省份城市 运营商"且完整，上面已经 return 了
        return strlen($loc) > 1 ? $loc : null;
    }
    
    return null;
}

/**
 * IP9 备用查询（串行调用，避免并发超限）
 * ★ 使用 ip9.com.cn/get 接口（与浏览器访问一致）
 */
function queryIpLocationFromIp9($ip) {
    // ★ 使用正确的 API 地址：ip9.com.cn/get
    $url = 'https://ip9.com.cn/get?ip=' . urlencode($ip);
    $ctx = stream_context_create([
        'http' => [
            'method' => 'GET',
            'timeout' => 5,
            'ignore_errors' => true,
            'header' => "Accept: application/json, text/plain, */*\r\nUser-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\nReferer: https://ip9.com.cn/\r\nOrigin: https://ip9.com.cn"
        ],
        'ssl' => [
            'verify_peer' => false,
            'verify_peer_name' => false
        ]
    ]);
    
    @error_log("[IP9] Requesting: $url");
    $response = @file_get_contents($url, false, $ctx);
    
    if ($response === false) {
        @error_log("[IP9] FAILED: No response for $ip");
        return ['location' => '查询失败'];
    }
    
    $response = trim($response);
    if (empty($response)) {
        @error_log("[IP9] FAILED: Empty response for $ip");
        return ['location' => '查询失败'];
    }
    
    @error_log("[IP9] Response: " . substr($response, 0, 500));
    
    $data = json_decode($response, true);
    if (!$data || !is_array($data)) {
        @error_log("[IP9] FAILED: Invalid JSON for $ip: " . substr($response, 0, 200));
        return ['location' => '查询失败'];
    }
    
    // IP9 返回格式有两种可能:
    //   旧版: {"ret":"200","data":[{"ip":"...","prov":"广东","city":"深圳","isp":"中国移动"}]}
    //   新版: {"ret":"200","data":{"ip":"...","prov":"广东","city":"深圳","isp":"中国移动"}}
    // ★ 兼容两种格式
    $item = null;
    if (isset($data['data'][0]) && is_array($data['data'][0])) {
        $item = $data['data'][0]; // 旧版：数组
    } elseif (isset($data['data']) && is_array($data['data']) && isset($data['data']['prov'])) {
        $item = $data['data']; // 新版：直接是对象
    }

    if ($item) {
        $loc = '';

        // ★ 优先使用 prov + city 格式
        if (isset($item['prov'])) $loc .= $item['prov'];
        if (isset($item['city'])) $loc .= $item['city'];
        if (isset($item['isp'])) $loc .= ' ' . $item['isp'];

        if (strlen($loc) > 0) {
            @error_log("[IP9] SUCCESS: $ip -> $loc");
            return ['location' => $loc, 'ip' => $ip];
        }
    }

    @error_log("[IP9] FAILED: No valid data for $ip. data keys: " . implode(',', array_keys($data)));
    return ['location' => '查询失败'];
}

/**
 * 通用HTTP请求封装（支持POST/GET）
 * 已弃用，改用 fetchIpApiWithTimeout
 */
function fetchIpApi($url, $parser) {
    return fetchIpApiWithTimeout($url, $parser, 5);
}

function adminListUsers() {
    requireAdminSession();
    $db = getDB();
    try {
        $result = $db->query("SELECT player_name, register_time, last_login_time, points, total_online_time, email FROM users ORDER BY register_time DESC");
        $users = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $users[] = $row;

        $playerNames = array_column($users, 'player_name');
        if (!empty($playerNames)) {
            $ipMap = batchGetPlayerIps($db, $playerNames);

            // ★ 批量读取数据库缓存（不调API，极快）
            $uniqueIps = array_unique(array_values($ipMap));
            $locCache = [];
            $invalidIps4 = [];
            try {
                $validIps = array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-');
                if (!empty($validIps)) {
                    $placeholders = implode(',', array_fill(0, count($validIps), '?'));
                    $locStmt = $db->prepare("SELECT ip_address, location FROM player_ip_locations WHERE ip_address IN ($placeholders) AND location != '查询失败'");
                    $idx = 1;
                    foreach ($validIps as $ip) { $locStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
                    $locResult = $locStmt->execute();
                    while ($row = $locResult->fetchArray(SQLITE3_ASSOC)) {
                        if (!isValidIpLocationFormat($row['location'])) {
                            $invalidIps4[] = $row['ip_address'];
                        } else {
                            $locCache[$row['ip_address']] = $row['location'];
                        }
                    }
                    if (!empty($invalidIps4)) {
                        $delP4 = implode(',', array_fill(0, count($invalidIps4), '?'));
                        $delS4 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address IN ($delP4)");
                        $di4 = 1;
                        foreach ($invalidIps4 as $dip) { $delS4->bindValue($di4++, $dip, SQLITE3_TEXT); }
                        $delS4->execute();
                    }
                }
            } catch (Exception $e) {}

            foreach ($users as &$user) {
                $key = strtolower($user['player_name']);
                $ip = $ipMap[$key] ?? '-';
                $user['ip_address'] = $ip;
                $user['ip_location'] = $locCache[$ip] ?? '-';
            }
            unset($user);
        }

        exit(json_encode(['success' => true, 'data' => $users], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminListUsers] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 分页API - 懒加载支持（搜索模式）
 * 搜索时不限制IP查询，一次性返回全部结果
 */
function adminListUsersPaginated() {
    requireAdminSession();
    $db = getDB();
    try {
        $page = max(1, (int)getParam('page', 1));
        $limit = min(50, max(10, (int)getParam('limit', 20))); // 默认15-20个
        $offset = ($page - 1) * $limit;
        $search = trim(getParam('search', ''));
        $includeIp = getParam('includeIp', '1') === '1'; // 默认包含IP，搜索时可设为0
        $isSearchMode = !empty($search); // 是否搜索模式（不限制并发）
        
        // 获取所有用户信息（本地查询，极快）
        $sql = "SELECT player_name, register_time, last_login_time, points, total_online_time, email FROM users";
        if ($search) {
            $searchBound = "%" . $search . "%";
            $stmt = $db->prepare($sql . " WHERE LOWER(player_name) LIKE :search ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
            $stmt->bindValue(':search', $searchBound, SQLITE3_TEXT);
            $result = $stmt->execute();
        } else {
            $result = $db->query($sql . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
        }
        $users = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $users[] = $row;

        // 获取总数
        $countSql = "SELECT COUNT(*) as cnt FROM users";
        if ($search) {
            $stmt = $db->prepare($countSql . " WHERE LOWER(player_name) LIKE :search");
            $stmt->bindValue(':search', $searchBound, SQLITE3_TEXT);
            $countResult = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        } else {
            $countResult = $db->query($countSql)->fetchArray(SQLITE3_ASSOC);
        }
        $totalUsers = $countResult['cnt'] ?? 0;
        $totalPages = ceil($totalUsers / $limit);

        // 如果有IP需求，只读数据库缓存，不调API
        if ($includeIp && !empty($users)) {
            $playerNames = array_column($users, 'player_name');

            // 1. 批量获取IP（本地查询，极快）
            $ipMap = batchGetPlayerIps($db, $playerNames);

            // 2. 收集唯一IP
            $uniqueIps = array_unique(array_values($ipMap));

            // 3. ★ 只从数据库批量读取缓存的IP归属地（零API调用，极快）
            $locCache = [];
            $invalidIps = []; // 需要清理的无效IP
            try {
                $validIps = array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-');
                if (!empty($validIps)) {
                    $placeholders = implode(',', array_fill(0, count($validIps), '?'));
                    $locStmt = $db->prepare("SELECT ip_address, location FROM player_ip_locations WHERE ip_address IN ($placeholders)");
                    $idx = 1;
                    foreach ($validIps as $ip) { $locStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
                    $locResult = $locStmt->execute();
                    while ($row = $locResult->fetchArray(SQLITE3_ASSOC)) {
                        // ★ 格式校验：无效格式直接标记清理，不返回给前端
                        if (!isValidIpLocationFormat($row['location'])) {
                            $invalidIps[] = $row['ip_address'];
                            @error_log("[IP_QUERY] adminListUsersPaginated: invalid format detected: " . $row['ip_address'] . " => " . $row['location']);
                        } else {
                            $locCache[$row['ip_address']] = $row['location'];
                        }
                    }
                    // ★ 批量清理无效记录
                    if (!empty($invalidIps)) {
                        $delPlaceholders = implode(',', array_fill(0, count($invalidIps), '?'));
                        $delStmt = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address IN ($delPlaceholders)");
                        $dIdx = 1;
                        foreach ($invalidIps as $dip) { $delStmt->bindValue($dIdx++, $dip, SQLITE3_TEXT); }
                        $delStmt->execute();
                        @error_log("[IP_QUERY] Cleaned " . count($invalidIps) . " invalid records from DB on read");
                    }
                }
            } catch (Exception $e) {}

            // 4. 收集未缓存的IP返回给前端（前端后台批量查）+ 刚清理的无效IP也需重新查询
            // ★ 过滤掉"-"和空值，只保留有效IP格式
            $uncachedIps = array_merge(
                array_values(array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-' && $ip !== '--' && preg_match('/^\d+\.\d+\.\d+\.\d+$/', $ip) && !isset($locCache[$ip]))),
                $invalidIps
            );
            $uncachedIps = array_unique($uncachedIps);

            // 5. 合并数据
            foreach ($users as &$user) {
                $key = strtolower($user['player_name']);
                if (isset($ipMap[$key])) {
                    $rawIp = $ipMap[$key];
                    // ★ 如果IP是"-"，显示为"-"但标记需要查询
                    if ($rawIp === '-' || $rawIp === '' || $rawIp === null) {
                        $user['ip_address'] = '-';
                        $user['ip_location'] = '查询中...';
                    } else {
                        $user['ip_address'] = $rawIp;
                        $user['ip_location'] = $locCache[$rawIp] ?? '查询中...';
                    }
                } else {
                    $user['ip_address'] = '-';
                    $user['ip_location'] = '-';
                }
            }
            unset($user);
        }

        exit(json_encode([
            'success' => true,
            'data' => $users,
            'uncached_ips' => $uncachedIps ?? [], // 未缓存的IP，供前端批量查询
            'pagination' => [
                'page' => $page,
                'limit' => $limit,
                'total' => $totalUsers,
                'total_pages' => $totalPages,
                'has_more' => $page < $totalPages
            ]
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        // 错误信息写日志，不暴露给前端
        @error_log('[adminListUsersPaginated] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 按 IP 分组查询玩家 =====
function adminListSameIp() {
    requireAdminSession();
    $db = getDB();
    try {
        // 从 player_ip_changes 获取所有玩家及其最新 IP
        $stmt = $db->prepare("SELECT LOWER(player_name) as pn, LOWER(new_ip) as ip FROM player_ip_changes ORDER BY changed_at DESC");
        $result = $stmt->execute();
        $ipPlayerMap = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $ip = $row['ip'];
            $player = $row['pn'];
            if (!isset($ipPlayerMap[$ip])) $ipPlayerMap[$ip] = [];
            if (!in_array($player, $ipPlayerMap[$ip])) {
                $ipPlayerMap[$ip][] = $player;
            }
        }

        // 筛选出同 IP 对应多个玩家的分组
        $groups = [];
        foreach ($ipPlayerMap as $ip => $players) {
            if (count($players) > 1) {
                // 获取 IP 归属地
                $locData = queryIpLocation($ip);
                $playerDetails = [];
                foreach ($players as $player) {
                    $playerDetails[] = [
                        'player_name' => $player,
                        'ip_address' => $ip,
                        'ip_location' => $locData['location'],
                        'login_time' => 0
                    ];
                }
                $groups[] = [
                    'ip' => $ip,
                    'ip_location' => $locData['location'],
                    'player_count' => count($players),
                    'players' => $playerDetails
                ];
            }
        }

        // 按玩家数降序
        usort($groups, function($a, $b) { return $b['player_count'] - $a['player_count']; });

        exit(json_encode(['success' => true, 'data' => $groups], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminApproveReset() {
    requireAdminSession();
    $reqId = (int)getParam('id', 0);
    if (!$reqId) exit(json_encode(['success' => false, 'message' => 'Missing ID'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT player_name FROM password_reset_requests WHERE id = :id AND status = 'pending'");
        $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        if (!$row) exit(json_encode(['success' => false, 'message' => 'Not found'], JSON_UNESCAPED_UNICODE));
        $tempPassword = bin2hex(random_bytes(4));
        $tempHash = password_hash($tempPassword, PASSWORD_DEFAULT);
        $tempExpire = time() + 3600;
        $stmt = $db->prepare("UPDATE password_reset_requests SET status='approved', processed_at=:t WHERE id=:id");
        $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
        $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
        $stmt->execute();
        $stmt = $db->prepare("INSERT OR REPLACE INTO web_login_credentials (player_name, temp_password_hash, temp_pw_expire) VALUES (:pn,:th,:te)");
        $stmt->bindValue(':pn', $row['player_name']);
        $stmt->bindValue(':th', $tempHash);
        $stmt->bindValue(':te', $tempExpire);
        $stmt->execute();
        exit(json_encode(['success' => true, 'data' => ['temp_password' => $tempPassword]], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminRejectReset() {
    requireAdminSession();
    $reqId = (int)getParam('id', 0);
    if (!$reqId) exit(json_encode(['success' => false, 'message' => 'Missing ID'], JSON_UNESCAPED_UNICODE));
    $db = getDB();
    try {
        $stmt = $db->prepare("UPDATE password_reset_requests SET status='rejected', processed_at=:t WHERE id=:id");
        $stmt->bindValue(':t', time(), SQLITE3_INTEGER);
        $stmt->bindValue(':id', $reqId, SQLITE3_INTEGER);
        $stmt->execute();
    } catch (Exception $e) {}
    exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
}

function adminListResetRequests() {
    requireAdminSession();
    $db = getDB();
    try {
        $result = $db->query("SELECT * FROM password_reset_requests WHERE status = 'pending' ORDER BY created_at DESC");
        $requests = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $requests[] = $row;
        exit(json_encode(['success' => true, 'data' => $requests], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 清理所有无效格式的IP归属地缓存（China-开头、仅省份、内网IP等）
 * 收集需要重新查询的IP列表返回给前端
 */
function adminCleanAndCollectIps() {
    requireAdminSession();
    $db = getDB();
    try {
        // 1. 删除所有无效格式的缓存
        $invalidPatterns = [
            "location = '内网IP'",
            "location = '查询失败'",
            "location = '-'",
            "location = '--'",
            "location LIKE 'China - %'",      // China - 开头（国外IP格式）
            "location LIKE '中国 %'",          // 中国 开头但无省份
            // 仅有省份的（如"广东省"、"北京市"但无城市和运营商）
            // 这些需要单独判断，先不删
        ];
        $totalDeleted = 0;
        foreach ($invalidPatterns as $pattern) {
            try {
                $delStmt = $db->exec("DELETE FROM player_ip_locations WHERE $pattern");
                $totalDeleted += $db->changes();
            } catch (Exception $e) {}
        }

        // 2. 删除内网IP（192.168.*.*、10.*.*.*、127.0.0.1）
        try {
            $db->exec("DELETE FROM player_ip_locations WHERE ip_address LIKE '192.168.%'");
            $totalDeleted += $db->changes();
            $db->exec("DELETE FROM player_ip_locations WHERE ip_address LIKE '10.%'");
            $totalDeleted += $db->changes();
            $db->exec("DELETE FROM player_ip_locations WHERE ip_address = '127.0.0.1'");
            $totalDeleted += $db->changes();
            $db->exec("DELETE FROM player_ip_locations WHERE ip_address = '-'");
            $totalDeleted += $db->changes();
        } catch (Exception $e) {}

        // 3. 收集所有玩家当前IP中尚未缓存的IP
        $uncachedIps = [];
        try {
            // 从 player_ip_changes 取最新IP
            $stmt = $db->query("SELECT DISTINCT new_ip FROM player_ip_changes WHERE new_ip != '-' AND new_ip != '' AND new_ip NOT LIKE '192.168.%' AND new_ip NOT LIKE '10.%' AND new_ip != '127.0.0.1'");
            $allIps = [];
            while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
                $allIps[] = $row['new_ip'];
            }
            // 从缓存表中找出已有的
            if (!empty($allIps)) {
                $placeholders = implode(',', array_fill(0, count($allIps), '?'));
                $cacheStmt = $db->prepare("SELECT ip_address FROM player_ip_locations WHERE ip_address IN ($placeholders) AND location != '查询失败'");
                $idx = 1;
                foreach ($allIps as $ip) { $cacheStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
                $cachedResult = $cacheStmt->execute();
                $cachedSet = [];
                while ($row = $cachedResult->fetchArray(SQLITE3_ASSOC)) {
                    $cachedSet[$row['ip_address']] = true;
                }
                foreach ($allIps as $ip) {
                    if (!isset($cachedSet[$ip])) {
                        $uncachedIps[] = $ip;
                    }
                }
            }
        } catch (Exception $e) {}

        @error_log("[adminCleanAndCollectIps] Deleted: $totalDeleted, Uncached: " . count($uncachedIps));

        exit(json_encode([
            'success' => true,
            'data' => [
                'deleted' => $totalDeleted,
                'uncached_ips' => $uncachedIps,
                'total_uncached' => count($uncachedIps)
            ]
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        exit(json_encode(['success' => false, 'message' => $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 批量查询IP归属地（每次最多3个，串行调API）
 * 前端轮询调用，直到所有IP都查完
 */
function adminBatchQueryIps() {
    requireAdminSession();
    $db = getDB();
    try {
        $ips = getParam('ips');
        
        // 调试日志
        @error_log('[adminBatchQueryIps] Received ips: ' . print_r($ips, true));
        
        if (!$ips) {
            exit(json_encode(['success' => false, 'message' => 'Missing ips parameter'], JSON_UNESCAPED_UNICODE));
        }
        
        // 确保是数组
        if (!is_array($ips)) {
            // 如果是逗号分隔的字符串，转换为数组
            $ips = explode(',', $ips);
            $ips = array_map('trim', $ips);
        }

        // 限制每次最多3个
        $batch = array_slice($ips, 0, 3);
        $results = [];

        foreach ($batch as $ip) {
            if (!$ip || $ip === '-' || $ip === '127.0.0.1' || strpos($ip, '192.168.') === 0 || strpos($ip, '10.') === 0) {
                $results[$ip] = '内网IP';
                continue;
            }
            $loc = queryIpLocation($ip);
            // ★ 返回有效结果或"查询失败"，不返回"-"
            $results[$ip] = ($loc && isset($loc['location']) && $loc['location'] !== '查询失败') ? $loc['location'] : '查询失败';
        }

        // 返回结果和剩余未查询的IP
        $remaining = array_slice($ips, 3);
        exit(json_encode([
            'success' => true,
            'data' => $results,
            'remaining' => $remaining,
            'remaining_count' => count($remaining)
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminBatchQueryIps] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 轻量级在线玩家名列表（不含IP查询，极快）
 * 用于用户管理页面初始化，只需知道谁在线
 */
function adminListOnlineNames() {
    requireAdminSession();
    $db = getDB();
    try {
        $cutoff = time() - 120;
        $stmt = $db->prepare("SELECT player_name FROM online_players WHERE login_time >= :cutoff");
        $stmt->bindValue(':cutoff', $cutoff, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $names = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $names[] = $row['player_name'];
        }
        exit(json_encode(['success' => true, 'data' => $names], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
    }
}

function adminListOnlinePlayers() {
    requireAdminSession();
    $db = getDB();
    try {
        // ★ 实时在线玩家：只查询最近2分钟内活跃的（±1分钟误差）
        $cutoff = time() - 120; // 2分钟窗口
        $now = time();

        $stmt = $db->prepare("SELECT player_name, login_time FROM online_players WHERE login_time >= :cutoff ORDER BY login_time DESC");
        $stmt->bindValue(':cutoff', $cutoff, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $players = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $players[] = $row;
        }

        // 获取 IP（只从数据库缓存读取，不调API）
        $playerNames = array_column($players, 'player_name');
        if (!empty($playerNames)) {
            $ipMap = batchGetPlayerIps($db, $playerNames);
            $ipCountMap = [];
            foreach ($players as &$p) {
                $key = strtolower($p['player_name']);
                $ip = $ipMap[$key] ?? '-';
                $p['ip_address'] = $ip;
                $ipCountMap[$ip] = ($ipCountMap[$ip] ?? 0) + 1;
            }
            unset($p);

            // ★ 批量读取数据库缓存的IP归属地（不调API）
            $uniqueIps = array_unique(array_values($ipMap));
            $locCache = [];
            $invalidIps2 = [];
            try {
                $validIps = array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-');
                if (!empty($validIps)) {
                    $placeholders = implode(',', array_fill(0, count($validIps), '?'));
                    $locStmt = $db->prepare("SELECT ip_address, location FROM player_ip_locations WHERE ip_address IN ($placeholders) AND location != '查询失败'");
                    $idx = 1;
                    foreach ($validIps as $ip) {
                        $locStmt->bindValue($idx++, $ip, SQLITE3_TEXT);
                    }
                    $locResult = $locStmt->execute();
                    while ($row = $locResult->fetchArray(SQLITE3_ASSOC)) {
                        // ★ 格式校验：无效格式不返回，标记清理
                        if (!isValidIpLocationFormat($row['location'])) {
                            $invalidIps2[] = $row['ip_address'];
                        } else {
                            $locCache[$row['ip_address']] = $row['location'];
                        }
                    }
                    if (!empty($invalidIps2)) {
                        $delP2 = implode(',', array_fill(0, count($invalidIps2), '?'));
                        $delS2 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address IN ($delP2)");
                        $di2 = 1;
                        foreach ($invalidIps2 as $dip) { $delS2->bindValue($di2++, $dip, SQLITE3_TEXT); }
                        $delS2->execute();
                    }
                }
            } catch (Exception $e) {}

            foreach ($players as &$p) {
                $ip = $p['ip_address'];
                $p['ip_location'] = $locCache[$ip] ?? '-';
                $p['ip_is_shared'] = ($ipCountMap[$ip] ?? 1) > 1;
            }
            unset($p);
        }

        exit(json_encode(['success' => true, 'data' => $players], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminListOnlinePlayers] Error: ' . $e->getMessage());
        exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 批量获取玩家 IP（一次SQL搞定，避免 N+1 查询）
 */
function batchGetPlayerIps($db, $playerNames) {
    $ipMap = [];

    // 1. 从 player_ip_changes 批量获取（★ 优先取有效IP，避免取到"-"）
    if (!empty($playerNames)) {
        $placeholders = implode(',', array_fill(0, count($playerNames), '?'));
        try {
            // ★ 优先取非"-"的最新IP；如果全是"-"，才取"-"的最新IP
            $ipStmt = $db->prepare("
                SELECT p.player_name, p.new_ip AS ip_address
                FROM player_ip_changes p
                INNER JOIN (
                    SELECT player_name,
                           MAX(CASE WHEN new_ip != '-' AND new_ip != '' THEN changed_at ELSE 0 END) as best_time,
                           MAX(changed_at) as latest_time
                    FROM player_ip_changes
                    WHERE player_name IN ($placeholders)
                    GROUP BY player_name
                ) latest ON p.player_name = latest.player_name
                    AND p.changed_at = CASE WHEN latest.best_time > 0 THEN latest.best_time ELSE latest.latest_time END
            ");
            $idx = 1;
            foreach ($playerNames as $pn) {
                $ipStmt->bindValue($idx++, $pn, SQLITE3_TEXT);
            }
            $ipResult = $ipStmt->execute();
            while ($row = $ipResult->fetchArray(SQLITE3_ASSOC)) {
                $key = strtolower($row['player_name']);
                if (!isset($ipMap[$key])) {
                    $ipMap[$key] = $row['ip_address'];
                }
            }
        } catch (Exception $e) {
            // player_ip_changes 表可能不存在，降级为简单查询
            try {
                $ipStmt = $db->prepare("SELECT player_name, new_ip AS ip_address FROM player_ip_changes WHERE player_name IN ($placeholders) ORDER BY changed_at DESC");
                $idx = 1;
                foreach ($playerNames as $pn) {
                    $ipStmt->bindValue($idx++, $pn, SQLITE3_TEXT);
                }
                $ipResult = $ipStmt->execute();
                while ($row = $ipResult->fetchArray(SQLITE3_ASSOC)) {
                    $key = strtolower($row['player_name']);
                    if (!isset($ipMap[$key])) {
                        $ipMap[$key] = $row['ip_address'];
                    }
                }
            } catch (Exception $e2) {}
        }
    }
    
    // 2. 缺失的去 web_session_log 批量补查
    $missing = [];
    foreach ($playerNames as $pn) {
        $key = strtolower($pn);
        if (!isset($ipMap[$key])) {
            $missing[] = $pn;
        }
    }
    
    if (!empty($missing)) {
        $placeholders2 = implode(',', array_fill(0, count($missing), '?'));
        try {
            // ★ 同样优先取有效IP
            $stmt2 = $db->prepare("
                SELECT w.player_name, w.ip_address
                FROM web_session_log w
                INNER JOIN (
                    SELECT player_name,
                           MAX(CASE WHEN ip_address != '-' AND ip_address != '' THEN login_time ELSE 0 END) as best_time,
                           MAX(login_time) as latest_time
                    FROM web_session_log
                    WHERE player_name IN ($placeholders2)
                    GROUP BY player_name
                ) latest ON w.player_name = latest.player_name
                    AND w.login_time = CASE WHEN latest.best_time > 0 THEN latest.best_time ELSE latest.latest_time END
            ");
            $idx2 = 1;
            foreach ($missing as $mn) {
                $stmt2->bindValue($idx2++, $mn, SQLITE3_TEXT);
            }
            $res2 = $stmt2->execute();
            while ($row = $res2->fetchArray(SQLITE3_ASSOC)) {
                $key = strtolower($row['player_name']);
                if (!isset($ipMap[$key])) {
                    $ipMap[$key] = $row['ip_address'];
                }
            }
        } catch (Exception $e) {
            // 降级为简单查询
            try {
                $stmt2 = $db->prepare("SELECT player_name, ip_address FROM web_session_log WHERE player_name IN ($placeholders2) ORDER BY login_time DESC");
                $idx2 = 1;
                foreach ($missing as $mn) {
                    $stmt2->bindValue($idx2++, $mn, SQLITE3_TEXT);
                }
                $res2 = $stmt2->execute();
                while ($row = $res2->fetchArray(SQLITE3_ASSOC)) {
                    $key = strtolower($row['player_name']);
                    if (!isset($ipMap[$key])) {
                        $ipMap[$key] = $row['ip_address'];
                    }
                }
            } catch (Exception $e2) {}
        }
    }
    
    return $ipMap;
}

function adminListActivePlayers() {
    requireAdminSession();
    $period = (int)getParam('period', 86400);
    if ($period <= 0) $period = 86400;
    $db = getDB();
    $cutoff = time() - $period;
    try {
        $stmt = $db->prepare("SELECT player_name, last_login_time, total_online_time FROM users WHERE last_login_time >= :cutoff ORDER BY last_login_time DESC");
        $stmt->bindValue(':cutoff', $cutoff, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $players = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $players[] = [
                'player_name' => $row['player_name'],
                'last_login_time' => $row['last_login_time'],
                'hours_online' => round(($row['total_online_time'] ?? 0) / 3600, 1)
            ];
        }

        // 获取 IP（只从数据库缓存读取）
        $playerNames = array_column($players, 'player_name');
        if (!empty($playerNames)) {
            $ipMap = batchGetPlayerIps($db, $playerNames);

            // ★ 批量读取数据库缓存的IP归属地（不调API）
            $uniqueIps = array_unique(array_values($ipMap));
            $locCache = [];
            $invalidIps3 = [];
            try {
                $validIps = array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-');
                if (!empty($validIps)) {
                    $placeholders = implode(',', array_fill(0, count($validIps), '?'));
                    $locStmt = $db->prepare("SELECT ip_address, location FROM player_ip_locations WHERE ip_address IN ($placeholders) AND location != '查询失败'");
                    $idx = 1;
                    foreach ($validIps as $ip) { $locStmt->bindValue($idx++, $ip, SQLITE3_TEXT); }
                    $locResult = $locStmt->execute();
                    while ($row = $locResult->fetchArray(SQLITE3_ASSOC)) {
                        if (!isValidIpLocationFormat($row['location'])) {
                            $invalidIps3[] = $row['ip_address'];
                        } else {
                            $locCache[$row['ip_address']] = $row['location'];
                        }
                    }
                    if (!empty($invalidIps3)) {
                        $delP3 = implode(',', array_fill(0, count($invalidIps3), '?'));
                        $delS3 = $db->prepare("DELETE FROM player_ip_locations WHERE ip_address IN ($delP3)");
                        $di3 = 1;
                        foreach ($invalidIps3 as $dip) { $delS3->bindValue($di3++, $dip, SQLITE3_TEXT); }
                        $delS3->execute();
                    }
                }
            } catch (Exception $e) {}

            foreach ($players as &$p) {
                $key = strtolower($p['player_name']);
                $ip = $ipMap[$key] ?? '-';
                $p['ip_address'] = $ip;
                $p['ip_location'] = $locCache[$ip] ?? '-';
            }
            unset($p);
        }

        exit(json_encode(['success' => true, 'data' => $players], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminListActivePlayers] Error: ' . $e->getMessage());
        exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
    }
}

function adminGetStatsEx() {
    requireAdminSession();
    $db = getDB();
    try {
        $onlineCount = 0;
        try {
            $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM online_players WHERE login_time >= :cutoff");
            $stmt->bindValue(':cutoff', time() - 60, SQLITE3_INTEGER);
            $r = $stmt->execute();
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $onlineCount = (int)($row['cnt'] ?? 0); }
        } catch (Exception $e) {
            @error_log('[adminGetStatsEx] online_players error: ' . $e->getMessage());
        }

        $activeCount = 0;
        try {
            $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE last_login_time >= :cutoff");
            $stmt->bindValue(':cutoff', time() - 86400, SQLITE3_INTEGER);
            $r = $stmt->execute();
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $activeCount = (int)($row['cnt'] ?? 0); }
        } catch (Exception $e) {
            @error_log('[adminGetStatsEx] users error: ' . $e->getMessage());
        }

        $userCount = 0;
        try {
            $r = $db->query("SELECT COUNT(*) as cnt FROM users");
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $userCount = (int)($row['cnt'] ?? 0); }
        } catch (Exception $e) {
            @error_log('[adminGetStatsEx] users count error: ' . $e->getMessage());
        }

        $totalBonds = 0;
        try {
            // ★ 检查 bond_cache 表是否存在
            $tableCheck = $db->query("SELECT name FROM sqlite_master WHERE type='table' AND name='bond_cache'");
            $hasBondCache = false;
            if ($tableCheck) {
                $tableRow = $tableCheck->fetchArray();
                $hasBondCache = ($tableRow && !empty($tableRow['name']));
            }
            
            if ($hasBondCache) {
                $r = $db->query("SELECT COALESCE(SUM(amount),0) as total FROM bond_cache");
                if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $totalBonds = (int)($row['total'] ?? 0); }
            }
        } catch (Exception $e) {
            @error_log('[adminGetStatsEx] bond_cache error: ' . $e->getMessage());
        }

        $todayCount = 0;
        try {
            $todayStart = strtotime('today');
            $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE register_time >= :cutoff");
            $stmt->bindValue(':cutoff', $todayStart, SQLITE3_INTEGER);
            $r = $stmt->execute();
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $todayCount = (int)($row['cnt'] ?? 0); }
        } catch (Exception $e) {
            @error_log('[adminGetStatsEx] today count error: ' . $e->getMessage());
        }

        @error_log('[adminGetStatsEx] Success: users=' . $userCount . ', online=' . $onlineCount . ', active=' . $activeCount);
        
        exit(json_encode([
            'success' => true,
            'data' => [
                'total_users' => $userCount,
                'online_count' => $onlineCount,
                'active_count_24h' => $activeCount,
                'total_bonds' => $totalBonds,
                'today_registered' => $todayCount
            ],
            'message' => 'ok'
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminGetStatsEx] Error: ' . $e->getMessage() . ' ' . $e->getTraceAsString());
        exit(json_encode(['success' => false, 'message' => 'Internal error: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}
