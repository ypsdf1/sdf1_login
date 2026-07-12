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

// 仅刷新当前文件的OPcache（不要opcache_reset——会重置整个服务器缓存导致竞态条件）
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

// ★ 注册shutdown函数：确保ob缓冲区在致命错误时也能刷新
register_shutdown_function(function() {
    while (ob_get_level() > 0) {
        @ob_end_flush();
    }
});

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

try {
switch ($action) {
    case 'login':        adminDoLogin(); break;
    case 'logout':       adminDoLogout(); break;
    case 'status':       adminStatus(); break;
    case 'deduct':       adminDeduct(); break;
    case 'add_bonds':    adminAddBonds(); break;
    case 'shop_update':  adminShopUpdate(); break;
    case 'shop_add':     adminShopAdd(); break;
    case 'shop_remove':  adminShopRemove(); break;
    case 'save_shop_config': adminSaveShopConfig(); break;
    case 'stats':        adminStats(); break;
    case 'all_tx':       adminAllTx(); break;
    case 'player_tx':    adminPlayerTx(); break;
    case 'gen_token':    adminGenToken(); break;
    case 'cashier_player_check': adminCashierPlayerCheck(); break;
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
    case 'cdk_delete':          adminDeleteCDK(); break;
    case 'list_bans':           adminListBans(); break;
    default:              exit(json_encode(['success' => false, 'message' => 'Unknown action: ' . $action], JSON_UNESCAPED_UNICODE));
}
} catch (\Throwable $e) {
    http_response_code(500);
    header('Content-Type: application/json; charset=utf-8');
    echo json_encode(['success' => false, 'message' => 'Internal error: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE);
    exit;
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
    $reason = getParam('reason', '管理员扣减');
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

        // ★ 写入web_transactions表，供Java插件拉取
        $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT DEFAULT '', detail TEXT DEFAULT '', status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");
        $txStmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:player, :type, :amount, :operator, :reason, :detail, 'pending', :time)");
        $txStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $txStmt->bindValue(':type', 'admin_deduct', SQLITE3_TEXT);
        $txStmt->bindValue(':amount', -$amount, SQLITE3_INTEGER);  // 负数表示扣减
        $txStmt->bindValue(':operator', 'admin', SQLITE3_TEXT);
        $txStmt->bindValue(':reason', $reason, SQLITE3_TEXT);
        $txStmt->bindValue(':detail', json_encode(['admin_action' => 'deduct', 'original' => $current, 'new' => $newAmount]), SQLITE3_TEXT);
        $txStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $txStmt->execute();

        // ★ 通知Java插件立即拉取
        notifyJavaPluginImmediatePull();

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
    $reason = getParam('reason', '管理员充值');
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

        // ★ 写入web_transactions表，供Java插件拉取
        $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT DEFAULT '', detail TEXT DEFAULT '', status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");
        $txStmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:player, :type, :amount, :operator, :reason, :detail, 'pending', :time)");
        $txStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $txStmt->bindValue(':type', 'admin_add', SQLITE3_TEXT);
        $txStmt->bindValue(':amount', $amount, SQLITE3_INTEGER);  // 正数表示增加
        $txStmt->bindValue(':operator', 'admin', SQLITE3_TEXT);
        $txStmt->bindValue(':reason', $reason, SQLITE3_TEXT);
        $txStmt->bindValue(':detail', json_encode(['admin_action' => 'add', 'original' => $current, 'new' => $newAmount]), SQLITE3_TEXT);
        $txStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $txStmt->execute();

        // ★ 通知Java插件立即拉取
        notifyJavaPluginImmediatePull();

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
        $itemId = getParam('id');
        if (!$itemId) error('缺少商品ID');
        $stock = getParam('stock');
        $buyPrice = getParam('buy_price');
        $sellPrice = getParam('sell_price');
        // ★ 库存改动写入admin_stock（防止Java全量同步覆盖）
        // admin_stock非NULL时pullShopStock优先返回此值给Java
        // ★ 价格改动写入admin_buy_price/admin_sell_price（同理）
        $sql = "UPDATE shop_items SET last_modified=:lm";
        $params = [':lm' => time(), ':id' => $itemId];
        $types = [':lm' => SQLITE3_INTEGER, ':id' => SQLITE3_TEXT];
        if ($stock !== null) {
            $sql .= ", stock=:st, admin_stock=:ast";
            $params[':st'] = $stock;
            $params[':ast'] = $stock;
            $types[':st'] = SQLITE3_INTEGER;
            $types[':ast'] = SQLITE3_INTEGER;
        }
        if ($buyPrice !== null) {
            $sql .= ", buy_price=:bp, admin_buy_price=:abp";
            $params[':bp'] = $buyPrice;
            $params[':abp'] = $buyPrice;
            $types[':bp'] = SQLITE3_INTEGER;
            $types[':abp'] = SQLITE3_INTEGER;
        }
        if ($sellPrice !== null) {
            $sql .= ", sell_price=:sp, admin_sell_price=:asp";
            $params[':sp'] = $sellPrice;
            $params[':asp'] = $sellPrice;
            $types[':sp'] = SQLITE3_INTEGER;
            $types[':asp'] = SQLITE3_INTEGER;
        }
        $sql .= " WHERE id=:id";
        $stmt = $db->prepare($sql);
        foreach ($params as $key => $value) {
            $stmt->bindValue($key, $value, $types[$key] ?? SQLITE3_TEXT);
        }
        $stmt->execute();
        $rowsChanged = $db->changes();
        // ★ 同步通知Java插件立即拉取库存改动
        if ($rowsChanged > 0) {
            notifyJavaPluginImmediatePull();
        }
        exit(json_encode(['success' => true, 'message' => 'OK', 'updated' => $rowsChanged], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminShopAdd() {
    requireAdminSession();
    $db = getDB();
    try {
        $itemId = getParam('id');
        if (!$itemId) error('缺少商品ID');
        $stmt = $db->prepare("INSERT OR REPLACE INTO shop_items (id, category, display_name, material, buy_price, sell_price, stock, hourly_sales, total_sales, last_sync, last_modified) VALUES (:id,:cat,:dn,:mat,:bp,:sp,:st,0,0,0,:lm)");
        $stmt->bindValue(':id', $itemId, SQLITE3_TEXT);
        $stmt->bindValue(':cat', getParam('category', 'weapon'));
        $stmt->bindValue(':dn', getParam('display_name', ''));
        $stmt->bindValue(':mat', getParam('material', 'stone_sword'));
        $stmt->bindValue(':bp', getParam('buy_price', 0));
        $stmt->bindValue(':sp', getParam('sell_price', -1));
        $stmt->bindValue(':st', getParam('stock', -1));
        $stmt->bindValue(':lm', time(), SQLITE3_INTEGER);
        $stmt->execute();
        exit(json_encode(['success' => true, 'message' => 'OK'], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

function adminShopRemove() {
    requireAdminSession();
    $db = getDB();
    try {
        $itemId = getParam('id');
        if (!$itemId) error('缺少商品ID');
        $stmt = $db->prepare("DELETE FROM shop_items WHERE id=:id");
        $stmt->bindValue(':id', $itemId, SQLITE3_TEXT);
        $stmt->execute();
        $rowsChanged = $db->changes();
        exit(json_encode(['success' => true, 'message' => 'OK', 'deleted' => $rowsChanged], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        @error_log('[Function] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 商店配置（折扣/加价系数 + 打包费 + 环保减免）=====
function adminSaveShopConfig() {
    requireAdminSession();
    $db = getDB();
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS shop_config (cfg_key TEXT PRIMARY KEY, cfg_value TEXT NOT NULL)");
        $upsert = $db->prepare("INSERT OR REPLACE INTO shop_config (cfg_key, cfg_value) VALUES (:k, :v)");
        $saved = [];

        // 购物车折扣/加价系数（需成对提交）
        $bp = getParam('cart_backpack_rate');
        $sh = getParam('cart_shulker_rate');
        if ($bp !== null || $sh !== null) {
            if ($bp === null || $sh === null) error('塞背包与潜影盒系数需同时提交');
            $bp = (float)$bp;
            $sh = (float)$sh;
            if ($bp <= 0 || $bp > 1.0) error('塞背包折扣系数必须介于 0.01 ~ 1.00 之间');
            if ($sh < 1.0 || $sh > 3.0) error('潜影盒加价系数必须介于 1.00 ~ 3.00 之间');
            $upsert->bindValue(':k', 'cart_backpack_rate', SQLITE3_TEXT);
            $upsert->bindValue(':v', number_format($bp, 2, '.', ''), SQLITE3_TEXT);
            $upsert->execute();
            $upsert->bindValue(':k', 'cart_shulker_rate', SQLITE3_TEXT);
            $upsert->bindValue(':v', number_format($sh, 2, '.', ''), SQLITE3_TEXT);
            $upsert->execute();
            $saved['backpack_rate'] = $bp;
            $saved['shulker_rate'] = $sh;
        }

        // 彩色潜影盒打包费
        $pm = getParam('packmoney');
        if ($pm !== null) {
            $pm = (int)$pm;
            if ($pm < 0 || $pm > 999) error('打包费需介于 0 ~ 999 之间');
            $upsert->bindValue(':k', 'packmoney', SQLITE3_TEXT);
            $upsert->bindValue(':v', (string)$pm, SQLITE3_TEXT);
            $upsert->execute();
            $saved['packmoney'] = $pm;
        }

        // 环保单折扣率（折数）：10=不打折，9.9=9.9折；命令 setgreen 10 亦为不打折
        $gd = getParam('green_discount');
        if ($gd !== null) {
            $gd = (float)$gd;
            if ($gd < 0 || $gd > 10) error('环保单折扣（折数）需介于 0 ~ 10 之间');
            $upsert->bindValue(':k', 'green_discount', SQLITE3_TEXT);
            $upsert->bindValue(':v', number_format($gd, 2, '.', ''), SQLITE3_TEXT);
            $upsert->execute();
            $saved['green_discount'] = $gd;
        }

        if (empty($saved)) error('没有可保存的配置项');

        exit(json_encode([
            'success' => true,
            'message' => '商店配置已保存',
            'data' => $saved
        ], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        @error_log('[save_shop_config] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => '保存失败: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
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
        // 合并web_transactions（Web端交易）和game_transactions（游戏内交易）
        // 统一字段名：source区分来源（web/game）
        $txs = [];

        // 查询Web端交易
        try {
            $result = $db->query("SELECT *, 'web' as source FROM web_transactions ORDER BY created_at DESC LIMIT 100");
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        } catch (Exception $e) {
            // web_transactions表可能不存在，忽略
        }

        // 查询游戏内交易
        try {
            $result = $db->query("SELECT
                java_id as id,
                player_name,
                type,
                amount,
                operator,
                reason,
                '' as detail,
                tx_time as created_at,
                'game' as source,
                balance_before,
                balance_after
                FROM game_transactions ORDER BY tx_time DESC LIMIT 100");
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        } catch (Exception $e) {
            // game_transactions表可能不存在，忽略
        }

        // 按时间排序（倒序），取前100条
        usort($txs, function($a, $b) {
            return ($b['created_at'] ?? 0) - ($a['created_at'] ?? 0);
        });
        $txs = array_slice($txs, 0, 100);

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
        $txs = [];

        // 查询Web端交易
        try {
            $stmt = $db->prepare("SELECT *, 'web' as source FROM web_transactions WHERE player_name=:p ORDER BY created_at DESC LIMIT 50");
            $stmt->bindValue(':p', $player);
            $result = $stmt->execute();
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        } catch (Exception $e) {
            // web_transactions表可能不存在，忽略
        }

        // 查询游戏内交易
        try {
            $stmt = $db->prepare("SELECT
                java_id as id,
                player_name,
                type,
                amount,
                operator,
                reason,
                '' as detail,
                tx_time as created_at,
                'game' as source,
                balance_before,
                balance_after
                FROM game_transactions WHERE player_name=:p ORDER BY tx_time DESC LIMIT 50");
            $stmt->bindValue(':p', $player);
            $result = $stmt->execute();
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) $txs[] = $row;
        } catch (Exception $e) {
            // game_transactions表可能不存在，忽略
        }

        // 按时间排序（倒序）
        usort($txs, function($a, $b) {
            return ($b['created_at'] ?? 0) - ($a['created_at'] ?? 0);
        });
        $txs = array_slice($txs, 0, 50);

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

// ===== 收银台：查询目标玩家余额/在线状态 =====
function adminCashierPlayerCheck() {
    requireAdminSession();
    $player = trim(getParam('player'));
    if (!$player) exit(json_encode(['success' => false, 'message' => '请输入玩家名'], JSON_UNESCAPED_UNICODE));
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $player)) {
        exit(json_encode(['success' => false, 'message' => '玩家名格式不正确（3-16位英文/数字/下划线）'], JSON_UNESCAPED_UNICODE));
    }
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");
    try {
        // 确保玩家有余额记录（不存在则建0余额行，便于后续代购扣减）
        $db->exec("INSERT OR IGNORE INTO bond_cache (player_name, amount, updated_at) VALUES ('" . str_replace("'", "''", $player) . "', 0, " . time() . ")");
        $stmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        $balance = $row ? (int)$row['amount'] : 0;

        // 在线状态（表不存在则忽略）
        $online = false;
        try {
            $ostmt = $db->prepare("SELECT player_name FROM online_players WHERE player_name = :name");
            $ostmt->bindValue(':name', $player, SQLITE3_TEXT);
            $orow = $ostmt->execute()->fetchArray(SQLITE3_ASSOC);
            $online = $orow ? true : false;
        } catch (\Throwable $e) {}

        exit(json_encode([
            'success' => true,
            'data' => ['player' => $player, 'exists' => true, 'balance' => $balance, 'online' => $online]
        ], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[Cashier] Player check error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => '查询失败: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 批量生成CDK =====
function adminGenCDK() {
    requireAdminSession();
    $amount = (int)getParam('amount', 0);
    $min = (int)getParam('min', 0);
    $max = (int)getParam('max', 0);
    $count = (int)getParam('count', 0);
    
    // 验证参数
    if ($count <= 0 || $count > 100) {
        exit(json_encode(['success' => false, 'message' => '数量必须在1-100之间'], JSON_UNESCAPED_UNICODE));
    }
    
    if ($min > 0 && $max > 0 && $min <= $max) {
        // 区间模式
    } elseif ($amount > 0) {
        // 固定金额模式
    } else {
        exit(json_encode(['success' => false, 'message' => '缺少金额参数(amount或min+max)'], JSON_UNESCAPED_UNICODE));
    }

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
            // 根据模式生成金额
            if ($min > 0 && $max > 0 && $min <= $max) {
                $genAmount = random_int($min, $max);
            } else {
                $genAmount = $amount;
            }
            $stmt = $db->prepare("INSERT OR IGNORE INTO cdk (code, amount, used, created_at) VALUES (:code, :amount, 0, :time)");
            $stmt->bindValue(':code', $code, SQLITE3_TEXT);
            $stmt->bindValue(':amount', $genAmount, SQLITE3_INTEGER);
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

// ★ 通知Java插件立即拉取交易（管理员操作后调用）
// PHP和MC服务器可能不在同一台机器，127.0.0.1回调不可靠
// 交易已写入web_transactions表，通过sync_requests标记让Java快速检测
function notifyJavaPluginImmediatePull() {
    $db = getDB();
    try {
        // 写入sync_requests标记，Java的activeSync轮询会检测到并立即拉取
        $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        $stmt = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
        $stmt->bindValue(':player', '__admin_tx__', SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        @error_log('[Admin] 已写入sync_requests标记');
    } catch (Exception $e) {
        @error_log('[Admin] sync_requests写入失败: ' . $e->getMessage());
    }
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
                } catch (\Throwable $e) {}
            } else {
                // 格式正确，直接使用
                @error_log("[IP_QUERY] Cache hit (database): $ip -> $cachedLoc");
                $memCache[$ip] = ['location' => $cachedLoc, 'ip' => $ip];
                return $memCache[$ip];
            }
        }
    } catch (\Throwable $e) {
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

    // ★ 单IP总超时控制：5秒内必须完成，防止4个API依次超时导致PHP挂起
    $startTime = microtime(true);
    $maxTimePerIp = 5; // 秒

    // 收集所有结果，优先选有效格式
    $bestResult = null;

    // ========== 线路1: 太平洋电脑网 ==========
    @error_log("[IP_QUERY_API] Trying PConline: $ip");
    $r1 = fetchPconline($ip);
    if ($r1 !== null) {
        $r1Loc = trim($r1);
        @error_log("[IP_QUERY_API] PConline RESULT: $ip -> $r1Loc");
        if (isValidIpLocationFormat($r1Loc)) {
            @error_log("[IP_QUERY_API] PConline VALID: $ip -> $r1Loc");
            $cache[$ip] = ['location' => $r1Loc, 'ip' => $ip];
            return $cache[$ip];
        } else {
            @error_log("[IP_QUERY_API] PConline INVALID FORMAT (will try next): $ip -> $r1Loc");
            $bestResult = $r1Loc;
        }
    } else {
        @error_log("[IP_QUERY_API] PConline FAILED: $ip");
    }

    // ★ 总超时检查
    if (microtime(true) - $startTime > $maxTimePerIp) {
        @error_log("[IP_QUERY_API] TIMEOUT after PConline: $ip (elapsed=" . round(microtime(true) - $startTime, 1) . "s)");
        $cache[$ip] = ['location' => $bestResult ?: '查询失败'];
        return $cache[$ip];
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
    }, 2);
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

    // ★ 总超时检查
    if (microtime(true) - $startTime > $maxTimePerIp) {
        @error_log("[IP_QUERY_API] TIMEOUT after Baidu: $ip (elapsed=" . round(microtime(true) - $startTime, 1) . "s)");
        $cache[$ip] = ['location' => $bestResult ?: '查询失败'];
        return $cache[$ip];
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
    }, 2);
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

    // ★ 总超时检查
    if (microtime(true) - $startTime > $maxTimePerIp) {
        @error_log("[IP_QUERY_API] TIMEOUT after aa1.cn: $ip (elapsed=" . round(microtime(true) - $startTime, 1) . "s)");
        $cache[$ip] = ['location' => $bestResult ?: '查询失败'];
        return $cache[$ip];
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
    @error_log("[IP_QUERY_API] ALL FAILED/INVALID: $ip (bestResult=" . ($bestResult ?: 'null') . ", elapsed=" . round(microtime(true) - $startTime, 1) . "s)");
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
function fetchIpApiWithTimeout($url, $parser, $timeout = 3) {
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
            'timeout' => 2,
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
            'timeout' => 3,
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

            // ★ 批量读取数据库缓存 + 自动填充缺失IP归属地
            $uniqueIps = array_unique(array_values($ipMap));
            $locCache = [];
            $invalidIps4 = [];
            $uncachedIps = [];
            try {
                $validIps = array_filter($uniqueIps, fn($ip) => $ip && $ip !== '-');
                if (!empty($validIps)) {
                    // 先读缓存
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
                    // ★ 找出未缓存的IP，逐个查询并写入数据库（限制10个避免超时）
                    foreach ($validIps as $ip) {
                        if (!isset($locCache[$ip]) && !in_array($ip, $invalidIps4)) {
                            $uncachedIps[] = $ip;
                        }
                    }
                    $queryLimit = min(count($uncachedIps), 10);
                    for ($qi = 0; $qi < $queryLimit; $qi++) {
                        $ip = $uncachedIps[$qi];
                        $locData = queryIpLocationWithTimeout($ip, 2);
                        if ($locData && $locData['location'] && $locData['location'] !== '查询失败' && isValidIpLocationFormat($locData['location'])) {
                            $locCache[$ip] = $locData['location'];
                            try {
                                $insStmt = $db->prepare("INSERT OR REPLACE INTO player_ip_locations (player_name, ip_address, location, updated_at) VALUES ('global', :ip, :loc, :time)");
                                $insStmt->bindValue(':ip', $ip, SQLITE3_TEXT);
                                $insStmt->bindValue(':loc', $locData['location'], SQLITE3_TEXT);
                                $insStmt->bindValue(':time', time(), SQLITE3_INTEGER);
                                $insStmt->execute();
                            } catch (\Throwable $e) {}
                        }
                    }
                }
            } catch (\Throwable $e) {}

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
 * ★ 智能搜索类型检测
 * 纯英文/数字 → 玩家名 (name)
 * IP格式 (x.x.x.x) → IP搜索 (ip)
 * 日期格式 (2026年6月18日 / 20260618 / 2026-06-18) → 日期搜索 (date)
 * 省名/地名 → 地区搜索 (region)
 */
function detectSearchType($search) {
    // 1. IP格式检测：x.x.x.x (支持部分匹配如 192.168)
    if (preg_match('/^\d{1,3}\.\d{1,3}(\.\d{1,3}(\.\d{1,3})?)?$/', $search)) {
        return 'ip';
    }

    // 2. 日期关键词（中英文）
    if (in_array($search, ['今天', '今日', '今天', '当日', 'today', 'Today', 'TODAY'])) {
        return 'date_keyword';
    }
    if (in_array($search, ['昨天', '昨日', '前一日', 'yesterday', 'Yesterday', 'YESTERDAY'])) {
        return 'date_keyword';
    }

    // 3. 日期格式检测
    // 中文格式：2026年6月18日、2026年06月18日
    if (preg_match('/^\d{4}年\d{1,2}月\d{1,2}日$/', $search)) {
        return 'date';
    }
    // 紧凑格式：20260618
    if (preg_match('/^\d{8}$/', $search)) {
        return 'date';
    }
    // 横线格式：2026-06-18
    if (preg_match('/^\d{4}-\d{1,2}-\d{1,2}$/', $search)) {
        return 'date';
    }

    // 3. 地区名检测（省名/市名/区名）
    $regions = [
        '北京', '天津', '上海', '重庆',
        '河北', '山西', '辽宁', '吉林', '黑龙江', '江苏', '浙江', '安徽', '福建', '江西', '山东',
        '河南', '湖北', '湖南', '广东', '海南', '四川', '贵州', '云南', '陕西', '甘肃', '青海',
        '台湾', '内蒙古', '广西', '西藏', '宁夏', '新疆',
        '香港', '澳门',
        // 常见城市
        '石家庄', '太原', '呼和浩特', '沈阳', '长春', '哈尔滨', '南京', '杭州', '合肥', '福州',
        '南昌', '济南', '郑州', '武汉', '长沙', '广州', '南宁', '海口', '成都', '贵阳', '昆明',
        '拉萨', '西安', '兰州', '西宁', '银川', '乌鲁木齐',
        '深圳', '珠海', '汕头', '佛山', '东莞', '中山', '惠州', '江门', '湛江', '茂名',
        '苏州', '无锡', '常州', '宁波', '温州', '嘉兴', '绍兴', '金华', '台州',
        '厦门', '泉州', '漳州', '青岛', '烟台', '潍坊', '临沂',
        '洛阳', '开封', '新乡', '南阳', '许昌',
        '株洲', '岳阳', '常德', '衡阳',
        '绵阳', '德阳', '宜宾', '泸州',
    ];
    foreach ($regions as $region) {
        if (mb_strpos($search, $region) !== false || $search === $region) {
            return 'region';
        }
    }

    // 4. 默认：纯英文/数字 → 玩家名
    return 'name';
}

/**
 * ★ 日期搜索解析：将搜索文本转换为时间戳范围
 */
function parseSearchDate($search) {
    $year = $month = $day = 0;

    // 中英文关键词：今天/昨天
    $searchLower = mb_strtolower($search);
    if (in_array($searchLower, ['今天', '今日', '当日', 'today'])) {
        $year = (int)date('Y'); $month = (int)date('m'); $day = (int)date('d');
    } elseif (in_array($searchLower, ['昨天', '昨日', '前一日', 'yesterday'])) {
        $year = (int)date('Y', strtotime('-1 day')); $month = (int)date('m', strtotime('-1 day')); $day = (int)date('d', strtotime('-1 day'));
    }
    // 中文格式：2026年6月18日
    elseif (preg_match('/(\d{4})年(\d{1,2})月(\d{1,2})日/', $search, $m)) {
        $year = (int)$m[1]; $month = (int)$m[2]; $day = (int)$m[3];
    }
    // 横线格式：2026-06-18
    elseif (preg_match('/^(\d{4})-(\d{1,2})-(\d{1,2})$/', $search, $m)) {
        $year = (int)$m[1]; $month = (int)$m[2]; $day = (int)$m[3];
    }
    // 紧凑格式：20260618
    elseif (preg_match('/^(\d{4})(\d{2})(\d{2})$/', $search, $m)) {
        $year = (int)$m[1]; $month = (int)$m[2]; $day = (int)$m[3];
    }

    if ($year > 0 && $month > 0 && $day > 0) {
        $start = mktime(0, 0, 0, $month, $day, $year);
        $end = mktime(0, 0, 0, $month, $day + 1, $year);
        if ($start !== false && $end !== false) {
            return ['start' => $start, 'end' => $end];
        }
    }
    return null;
}

/**
 * 分页API - 懒加载支持（搜索模式）
 * 搜索时不限制IP查询，一次性返回全部结果
 */
function adminListUsersPaginated() {
    requireAdminSession();
    try {
    $db = getDB();
        $page = max(1, (int)getParam('page', 1));
        $limit = min(50, max(10, (int)getParam('limit', 20))); // 默认15-20个
        $offset = ($page - 1) * $limit;
        $search = rawurldecode(trim(getParam('search', '')));
        $includeIp = getParam('includeIp', '1') === '1'; // 默认包含IP，搜索时可设为0
        $isSearchMode = !empty($search); // 是否搜索模式（不限制并发）
        $regionDebugEnabled = false;
        $regionDebug = [];
        $dateRange = null; // ★ 提前声明$dateRange变量，防止后续分支未定义报错

        // ★ 智能搜索：自动识别搜索类型
        $searchType = 'name'; // 默认：玩家名
        $searchBound = '';
        $whereClause = '';
        $uncachedIps = []; // ★ 确保始终定义
        $matchedPlayers = []; // ★ 提前声明matchedPlayers

        if ($search) {
            $searchType = detectSearchType($search);

            // ★ 特别处理：todayreg/yesterdayreg → 按register_time过滤（仅注册）
            $searchLower = mb_strtolower($search);
            if ($searchLower === 'todayreg') {
                $searchType = 'date_reg';
            } elseif ($searchLower === 'yesterdayreg') {
                $searchType = 'date_reg';
            } elseif ($searchType === 'date_keyword') {
                // 日期关键词：转换为时间戳范围
                $dateRange = parseSearchDate($search);
            }

            if ($searchType === 'ip') {
                // IP搜索：匹配玩家IP归属地或IP本身
                $ipSearch = $search;
                $whereClause = "WHERE player_name IN (SELECT player_name FROM player_ip_changes WHERE new_ip LIKE :ip) OR player_name IN (SELECT player_name FROM player_ip_locations WHERE ip_address LIKE :ip2)";
                $searchBound = "%$ipSearch%";
            } elseif ($searchType === 'date_reg') {
                // 今日注册/昨日注册：仅按 register_time 过滤
                if ($searchLower === 'todayreg') {
                    $year = (int)date('Y'); $month = (int)date('m'); $day = (int)date('d');
                } else {
                    $year = (int)date('Y', strtotime('-1 day')); $month = (int)date('m', strtotime('-1 day')); $day = (int)date('d', strtotime('-1 day'));
                }
                $start = mktime(0, 0, 0, $month, $day, $year);
                $end = mktime(0, 0, 0, $month, $day + 1, $year);
                $dateRange = ['start' => $start, 'end' => $end];
                $whereClause = "WHERE register_time >= :dateRegStart AND register_time < :dateRegEnd";
            } elseif ($searchType === 'date' || $searchType === 'date_keyword') {
                // 日期搜索：转换为时间戳范围（支持"今天""昨天"关键词）
                $dateRange = parseSearchDate($search);
                if ($dateRange) {
                    $whereClause = "WHERE (last_login_time >= :dateStart AND last_login_time < :dateEnd) OR (register_time >= :dateStart AND register_time < :dateEnd)";
                } else {
                    // 日期解析失败，回退到模糊搜索
                    $searchType = 'name';
                    $whereClause = "WHERE LOWER(player_name) LIKE :search";
                    $searchBound = "%" . strtolower($search) . "%";
                }
            } elseif ($searchType === 'region') {
                // ★★★ 地区搜索：使用 SQL JOIN 直接查找（诊断证明可靠）★★★
                $regionKey = $search;
                $regionDebug = ['search' => $search, 'tables' => [], 'method' => 'sql_join'];

                // 检查表数据量
                foreach (['player_ip_locations', 'player_ip_changes', 'web_session_log', 'users'] as $t) {
                    try {
                        $cnt = $db->query("SELECT COUNT(*) as c FROM $t")->fetchArray(SQLITE3_ASSOC)['c'];
                        $regionDebug['tables'][$t] = $cnt;
                    } catch (\Throwable $e) {
                        $regionDebug['tables'][$t] = 'error:' . $e->getMessage();
                    }
                }

                // ★ 用 SQL JOIN 直接找到匹配地区的玩家名
                $matchedPlayers = [];
                try {
                    $joinSql = "SELECT DISTINCT c.player_name
                        FROM player_ip_changes c
                        INNER JOIN player_ip_locations l ON c.new_ip = l.ip_address
                        WHERE l.location LIKE :region AND c.new_ip != '' AND c.new_ip != '-'
                        UNION
                        SELECT DISTINCT w.player_name
                        FROM web_session_log w
                        INNER JOIN player_ip_locations l ON w.ip_address = l.ip_address
                        WHERE l.location LIKE :region2 AND w.ip_address != '' AND w.ip_address != '-'";
                    $joinStmt = $db->prepare($joinSql);
                    $joinStmt->bindValue(':region', "%$regionKey%", SQLITE3_TEXT);
                    $joinStmt->bindValue(':region2', "%$regionKey%", SQLITE3_TEXT);
                    $joinResult = $joinStmt->execute();
                    while ($jRow = $joinResult->fetchArray(SQLITE3_ASSOC)) {
                        $matchedPlayers[] = $jRow['player_name'];
                    }
                } catch (\Throwable $e) {
                    $regionDebug['error'] = $e->getMessage();
                    @error_log("[REGION_SEARCH] JOIN query error: " . $e->getMessage());
                }

                $regionDebug['matched'] = count($matchedPlayers);
                $regionDebug['players'] = array_slice($matchedPlayers, 0, 20);

                // ★ 构建WHERE子句
                if (!empty($matchedPlayers)) {
                    $namePlaceholders = implode(',', array_fill(0, count($matchedPlayers), '?'));
                    $whereClause = "WHERE player_name IN ($namePlaceholders)";
                    $searchBound = $matchedPlayers;
                } else {
                    $whereClause = "WHERE 1=0";
                    $searchBound = [];
                }

                // ★ 始终启用调试输出
                $regionDebugEnabled = true;
            } else {
                // 纯文本：按玩家名搜索
                $whereClause = "WHERE LOWER(player_name) LIKE :search";
                $searchBound = "%" . strtolower($search) . "%";
            }
        }

        // 获取所有用户信息（本地查询，极快）
        $sql = "SELECT player_name, register_time, last_login_time, points, total_online_time, email FROM users";

        if ($search) {
            if ($searchType === 'date_reg' && $dateRange) {
                $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                $stmt->bindValue(':dateRegStart', $dateRange['start'], SQLITE3_INTEGER);
                $stmt->bindValue(':dateRegEnd', $dateRange['end'], SQLITE3_INTEGER);
            } elseif (in_array($searchType, ['date', 'date_keyword']) && $dateRange) {
                $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY last_login_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                $stmt->bindValue(':dateStart', $dateRange['start'], SQLITE3_INTEGER);
                $stmt->bindValue(':dateEnd', $dateRange['end'], SQLITE3_INTEGER);
            } elseif ($searchType === 'ip') {
                $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                $stmt->bindValue(':ip', $searchBound, SQLITE3_TEXT);
                $stmt->bindValue(':ip2', $searchBound, SQLITE3_TEXT);
            } elseif ($searchType === 'region') {
                // ★ 地区搜索：已用PHP两步法获取匹配玩家列表，直接绑定参数
                try {
                    $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                    if (!empty($matchedPlayers)) {
                        $idx = 1;
                        foreach ($matchedPlayers as $pn) {
                            $stmt->bindValue($idx++, $pn, SQLITE3_TEXT);
                        }
                    }
                } catch (\Throwable $e) {
                    @error_log('[adminListUsersPaginated] Region search prepare failed: ' . $e->getMessage());
                    $regionDebug['prepare_error'] = $e->getMessage();
                    // ★ 不改变 searchType，保持 'region' 以便输出调试信息
                    // 回退：用简单查询
                    $whereClause = "WHERE 1=0";
                    $searchBound = [];
                    $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                }
            } else {
                $stmt = $db->prepare($sql . " " . $whereClause . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
                $stmt->bindValue(':search', $searchBound, SQLITE3_TEXT);
            }
            $result = $stmt->execute();
        } else {
            $result = $db->query($sql . " ORDER BY register_time DESC LIMIT " . (int)$limit . " OFFSET " . (int)$offset);
        }
        $users = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) $users[] = $row;

        // 获取总数
        $countSql = "SELECT COUNT(*) as cnt FROM users";
        if ($search) {
            if ($searchType === 'date_reg' && $dateRange) {
                $countStmt = $db->prepare($countSql . " " . $whereClause);
                $countStmt->bindValue(':dateRegStart', $dateRange['start'], SQLITE3_INTEGER);
                $countStmt->bindValue(':dateRegEnd', $dateRange['end'], SQLITE3_INTEGER);
            } elseif (in_array($searchType, ['date', 'date_keyword']) && $dateRange) {
                $countStmt = $db->prepare($countSql . " " . $whereClause);
                $countStmt->bindValue(':dateStart', $dateRange['start'], SQLITE3_INTEGER);
                $countStmt->bindValue(':dateEnd', $dateRange['end'], SQLITE3_INTEGER);
            } elseif ($searchType === 'ip') {
                $countStmt = $db->prepare($countSql . " " . $whereClause);
                $countStmt->bindValue(':ip', $searchBound, SQLITE3_TEXT);
                $countStmt->bindValue(':ip2', $searchBound, SQLITE3_TEXT);
            } elseif ($searchType === 'region') {
                try {
                    $countStmt = $db->prepare($countSql . " " . $whereClause);
                    if (!empty($matchedPlayers)) {
                        $idx = 1;
                        foreach ($matchedPlayers as $pn) {
                            $countStmt->bindValue($idx++, $pn, SQLITE3_TEXT);
                        }
                    }
                    $countResult = $countStmt->execute()->fetchArray(SQLITE3_ASSOC);
                } catch (\Throwable $e) {
                    @error_log('[adminListUsersPaginated] Region count prepare failed: ' . $e->getMessage());
                    $regionDebug['count_prepare_error'] = $e->getMessage();
                    // ★ 不改变 searchType
                    $countResult = ['cnt' => 0];
                }
            } else {
                $countStmt = $db->prepare($countSql . " " . $whereClause);
                $countStmt->bindValue(':search', $searchBound, SQLITE3_TEXT);
            }
            $countResult = $countStmt->execute()->fetchArray(SQLITE3_ASSOC);
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
            } catch (\Throwable $e) {}

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

        $response = [
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
        ];
        // ★ 地区搜索调试信息（始终附加，帮助诊断）
        if ($searchType === 'region' || (!empty($regionDebugEnabled))) {
            $response['_region_debug'] = $regionDebug ?? [];
        }
        // ★ 版本标记：用于确认服务器运行的是最新代码
        $response['_v'] = '20260619-v3';
        $response['_search_type'] = $searchType;
        $response['_search'] = $search;
        exit(json_encode($response, JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        // 错误信息写日志，不暴露给前端
        @error_log('[adminListUsersPaginated] Error: ' . $e->getMessage() . ' in ' . $e->getFile() . ':' . $e->getLine());
        @error_log('[adminListUsersPaginated] Stack: ' . $e->getTraceAsString());
        @ob_end_flush();
        exit(json_encode(['success' => false, 'message' => 'Internal error: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
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
        $ipPlayerMap = [];      // exact IP → [players]
        $subnetPlayerMap = [];  // /24 subnet → [players]
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $ip = $row['ip'];
            $player = $row['pn'];

            // 精确IP映射
            if (!isset($ipPlayerMap[$ip])) $ipPlayerMap[$ip] = [];
            if (!in_array($player, $ipPlayerMap[$ip])) {
                $ipPlayerMap[$ip][] = $player;
            }

            // /24子网映射：取前三段
            $parts = explode('.', $ip);
            $subnet = count($parts) === 4 ? $parts[0].'.'.$parts[1].'.'.$parts[2] : $ip;
            if (!isset($subnetPlayerMap[$subnet])) $subnetPlayerMap[$subnet] = [];
            if (!in_array($player, $subnetPlayerMap[$subnet])) {
                $subnetPlayerMap[$subnet][] = $player;
            }
        }

        // ★ 按/24子网分组（同一子网内的所有玩家视为同一组）
        $groups = [];
        foreach ($subnetPlayerMap as $subnet => $players) {
            if (count($players) > 1) {
                // 从该子网内取一个代表IP查归属地
                $representIp = '';
                foreach ($ipPlayerMap as $ip => $pArr) {
                    $parts = explode('.', $ip);
                    if (count($parts) === 4 && $parts[0].'.'.$parts[1].'.'.$parts[2] === $subnet) {
                        $representIp = $ip;
                        break;
                    }
                }
                $locData = queryIpLocation($representIp);
                $playerDetails = [];
                foreach ($players as $player) {
                    // 找该玩家的实际IP
                    $playerIp = '-';
                    foreach ($ipPlayerMap as $ip => $pArr) {
                        if (in_array($player, $pArr)) { $playerIp = $ip; break; }
                    }
                    $playerDetails[] = [
                        'player_name' => $player,
                        'ip_address' => $playerIp,
                        'ip_location' => $locData['location'],
                        'login_time' => 0
                    ];
                }
                $groups[] = [
                    'ip' => $subnet . '.x',
                    'subnet' => $subnet,
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
        @error_log('[adminListSameIp] Error: ' . $e->getMessage());
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

        // 限制每次最多2个（避免单批超时：每个IP最多5秒 × 2 = 10秒，安全于30秒PHP限制）
        $batch = array_slice($ips, 0, 2);
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
        $remaining = array_slice($ips, 2);
        exit(json_encode([
            'success' => true,
            'data' => $results,
            'remaining' => $remaining,
            'remaining_count' => count($remaining)
        ], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
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
    $db->exec('PRAGMA busy_timeout=15000');
    try {
        // ★ 先检查心跳
        $hbCheck = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1");
        $hbRow = $hbCheck ? $hbCheck->fetchArray(SQLITE3_ASSOC) : null;
        $heartbeatFresh = false;
        if ($hbRow !== false && isset($hbRow['last_seen']) && $hbRow['last_seen'] > 0) {
            $lastHb = (int)$hbRow['last_seen'];
            if ((time() - $lastHb) <= 120) {
                $heartbeatFresh = true;
            }
        }

        if (!$heartbeatFresh) {
            exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
        }

        // ★ 修复：心跳有效时直接查询全部在线玩家（online_players表每次同步全量重建，无需login_time过滤）
        $result = $db->query("SELECT player_name FROM online_players");
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
    $db->exec('PRAGMA busy_timeout=15000');
    try {
        // ★ 先检查心跳：如果超过120秒没收到Java推送，返回空列表（防止残留旧数据）
        $hbCheck = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1");
        $hbRow = $hbCheck ? $hbCheck->fetchArray(SQLITE3_ASSOC) : null;
        $heartbeatFresh = false;
        if ($hbRow !== false && isset($hbRow['last_seen']) && $hbRow['last_seen'] > 0) {
            $lastHb = (int)$hbRow['last_seen'];
            if ((time() - $lastHb) <= 120) {
                $heartbeatFresh = true;
            }
        }

        if (!$heartbeatFresh) {
            // 心跳过期或无心跳，强制在线人数为0
            exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
        }

        // ★ 修复：心跳有效时直接查询全部在线玩家（online_players表每次同步全量重建，无需login_time过滤）
        $now = time();

        $result = $db->query("SELECT player_name, login_time FROM online_players ORDER BY login_time DESC");
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

    if (empty($playerNames)) return $ipMap;
    $placeholders = implode(',', array_fill(0, count($playerNames), '?'));

    // 1. 从 player_ip_changes 获取最新IP（简单查询，兼容所有SQLite版本）
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
    } catch (\Throwable $e) {
        @error_log("[batchGetPlayerIps] player_ip_changes error: " . $e->getMessage());
    }

    // 2. 缺失的去 web_session_log 补查
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
        } catch (\Throwable $e2) {
            @error_log("[batchGetPlayerIps] web_session_log error: " . $e2->getMessage());
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

        // ★ 同IP去重：/24子网视为同一人，只保留最后活跃的玩家
        $deduped = [];
        $seenSubnets = []; // subnet => player_name (保留第一个)
        foreach ($players as $p) {
            $ip = $p['ip_address'] ?? '-';
            if ($ip && $ip !== '-') {
                $subnet = getSubnet24($ip);
                if (isset($seenSubnets[$subnet])) {
                    // 同子网已有玩家，跳过这个（保留先出现的/活跃度最高的）
                    $p['hidden_by_dedup'] = true;
                    $p['dedup_group'] = $seenSubnets[$subnet];
                    continue;
                }
                $seenSubnets[$subnet] = $p['player_name'];
            }
            $deduped[] = $p;
        }

        exit(json_encode(['success' => true, 'data' => $deduped, 'total_before_dedup' => count($players)], JSON_UNESCAPED_UNICODE));
    } catch (Exception $e) {
        @error_log('[adminListActivePlayers] Error: ' . $e->getMessage());
        exit(json_encode(['success' => true, 'data' => []], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * ★ 获取IP的/24子网（如 192.168.1.100 → 192.168.1）
 * 同一/24子网的设备视为同一人
 */
function getSubnet24($ip) {
    $parts = explode('.', $ip);
    if (count($parts) === 4) {
        return $parts[0] . '.' . $parts[1] . '.' . $parts[2];
    }
    return $ip; // 非标准IP，原样返回
}

function adminGetStatsEx() {
    requireAdminSession();
    $db = getDB();
    $db->exec('PRAGMA busy_timeout=15000');

    try {
        $onlineCount = 0;
        try {
            $hbCheck = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1");
            $hbRow = $hbCheck ? $hbCheck->fetchArray(SQLITE3_ASSOC) : null;
            $heartbeatFresh = false;
            if ($hbRow !== false && isset($hbRow['last_seen']) && $hbRow['last_seen'] > 0) {
                $lastHb = (int)$hbRow['last_seen'];
                if ((time() - $lastHb) <= 120) {
                    $heartbeatFresh = true;
                }
            }

            if ($heartbeatFresh) {
                $r = $db->query("SELECT COUNT(*) as cnt FROM online_players");
                if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $onlineCount = (int)($row['cnt'] ?? 0); }
            } else {
                $onlineCount = 0;
            }
        } catch (\Throwable $e) {
            @error_log('[adminGetStatsEx] online_players error: ' . $e->getMessage());
        }

        $activeCount = 0;
        try {
            $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE last_login_time >= :cutoff");
            $stmt->bindValue(':cutoff', time() - 86400, SQLITE3_INTEGER);
            $r = $stmt->execute();
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $activeCount = (int)($row['cnt'] ?? 0); }
        } catch (\Throwable $e) {
            @error_log('[adminGetStatsEx] users error: ' . $e->getMessage());
        }

        $userCount = 0;
        try {
            $r = $db->query("SELECT COUNT(*) as cnt FROM users");
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $userCount = (int)($row['cnt'] ?? 0); }
        } catch (\Throwable $e) {
            @error_log('[adminGetStatsEx] users count error: ' . $e->getMessage());
        }

        $totalBonds = 0;
        try {
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
        } catch (\Throwable $e) {
            @error_log('[adminGetStatsEx] bond_cache error: ' . $e->getMessage());
        }

        $todayCount = 0;
        try {
            $todayStart = strtotime('today');
            $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE register_time >= :cutoff");
            $stmt->bindValue(':cutoff', $todayStart, SQLITE3_INTEGER);
            $r = $stmt->execute();
            if ($r) { $row = $r->fetchArray(SQLITE3_ASSOC); $todayCount = (int)($row['cnt'] ?? 0); }
        } catch (\Throwable $e) {
            @error_log('[adminGetStatsEx] today count error: ' . $e->getMessage());
        }

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
    } catch (\Throwable $e) {
        @error_log('[adminGetStatsEx] Error: ' . $e->getMessage() . ' ' . $e->getTraceAsString());
        exit(json_encode(['success' => false, 'message' => 'Internal error: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE));
    }
}

// ===== 删除CDK（管理员操作） =====
function adminDeleteCDK() {
    requireAdminSession();
    $code = getParam('code');
    if (!$code) exit(json_encode(['success' => false, 'message' => '缺少code'], JSON_UNESCAPED_UNICODE));
    
    $db = getDB();
    try {
        $stmt = $db->prepare("DELETE FROM cdk WHERE code = :code");
        $stmt->bindValue(':code', strtoupper(trim($code)), SQLITE3_TEXT);
        $result = $stmt->execute();
        
        // 检查是否有行被删除
        $changes = $db->changes();
        if ($changes > 0) {
            exit(json_encode(['success' => true, 'message' => 'CDK已删除'], JSON_UNESCAPED_UNICODE));
        } else {
            exit(json_encode(['success' => false, 'message' => 'CDK不存在'], JSON_UNESCAPED_UNICODE));
        }
    } catch (Exception $e) {
        @error_log('[adminDeleteCDK] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}

/**
 * 获取封禁名单（供用户管理页面显示封禁状态）
 */
function adminListBans() {
    requireAdminSession();
    try {
        $db = getDB();
        $result = $db->query("SELECT target, ban_type, reason, source, expire_time FROM web_player_bans");
        $bans = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $bans[] = $row;
        }
        exit(json_encode(['success' => true, 'data' => $bans], JSON_UNESCAPED_UNICODE));
    } catch (\Throwable $e) {
        @error_log('[adminListBans] Error: ' . $e->getMessage());
        exit(json_encode(['success' => false, 'message' => 'Internal error'], JSON_UNESCAPED_UNICODE));
    }
}
