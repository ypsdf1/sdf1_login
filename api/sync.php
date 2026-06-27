<?php
// ★ 同步API - 插件与Web端数据同步
// ★ Version: 2026-06-22-v2 - added require_once guard + removed opcache_reset
// ★ 防止任何输出污染JSON响应
ob_start();
// 仅刷新当前文件的OPcache（不要调用opcache_reset——它会重置整个服务器缓存，导致并发竞态条件）
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
// 清理前置输出
while (ob_get_level() > 0) {
    ob_end_clean();
}
ob_start();

// ★ 安全检查：确保core.php存在，避免require_once失败时输出HTML错误
$coreFile = __DIR__ . '/../core.php';
if (!file_exists($coreFile)) {
    while (ob_get_level() > 0) ob_end_clean();
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['success' => false, 'message' => 'core.php not found: ' . $coreFile], JSON_UNESCAPED_UNICODE);
    exit;
}

// ★ 设置错误处理器：防止require_once失败时输出HTML
set_error_handler(function($errno, $errstr, $errfile, $errline) {
    @error_log("[sync.php PRE-LOAD] $errstr in $errfile on line $errline");
    return true; // 抑制输出
});

require_once $coreFile;

// ★ 恢复错误处理器 + 清理前置输出
restore_error_handler();
while (ob_get_level() > 0) {
    ob_end_clean();
}
ob_start();

// ★ 全局错误/异常处理（确保错误也记录到日志）
set_error_handler(function($errno, $errstr, $errfile, $errline) {
    @error_log("[sync.php ERROR] $errstr in $errfile on line $errline");
    return true;
});
set_exception_handler(function($e) {
    @error_log("[sync.php EXCEPTION] " . $e->getMessage() . " at " . $e->getTraceAsString());
    while (ob_get_level() > 0) ob_end_clean();
    echo json_encode(['success' => false, 'message' => 'PHP异常: ' . $e->getMessage()], JSON_UNESCAPED_UNICODE);
    exit;
});

// debugLog() 函数已在 core.php 中定义，无需重复声明

// ===== SMTP邮件发送函数 =====
function smtpSendEmail($host, $port, $user, $pass, $to, $subject, $htmlBody, $headers, $useSSL = true) {
    $errno = 0;
    $errstr = '';

    // 创建socket连接
    if ($useSSL) {
        $socket = @fsockopen('ssl://' . $host, $port, $errno, $errstr, 30);
    } else {
        $socket = @fsockopen($host, $port, $errno, $errstr, 30);
    }

    if (!$socket) {
        throw new Exception("SMTP连接失败: $errstr ($errno)");
    }

    // 读取服务器响应
    $response = fgets($socket);
    if (strpos($response, '220') !== 0) {
        fclose($socket);
        throw new Exception("SMTP服务器拒绝连接: $response");
    }

    // 发送EHLO
    fwrite($socket, "EHLO localhost\r\n");
    $response = fread($socket, 1024);

    // 登录认证
    fwrite($socket, "AUTH LOGIN\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP认证失败: $response");
    }

    // 发送用户名
    fwrite($socket, base64_encode($user) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '334') !== 0) {
        fclose($socket);
        throw new Exception("SMTP用户名错误: $response");
    }

    // 发送密码
    fwrite($socket, base64_encode($pass) . "\r\n");
    $response = fgets($socket);
    if (strpos($response, '235') !== 0) {
        fclose($socket);
        throw new Exception("SMTP密码错误: $response");
    }

    // 发送邮件
    fwrite($socket, "MAIL FROM:<$user>\r\n");
    fgets($socket);

    fwrite($socket, "RCPT TO:<$to>\r\n");
    fgets($socket);

    fwrite($socket, "DATA\r\n");
    fgets($socket);

    // 发送邮件头和正文
    $emailData = "Subject: $subject\r\n";
    $emailData .= $headers;
    $emailData .= "\r\n";
    $emailData .= $htmlBody;
    $emailData .= "\r\n.\r\n";

    fwrite($socket, $emailData);
    $response = fgets($socket);

    // 退出
    fwrite($socket, "QUIT\r\n");
    fgets($socket);

    fclose($socket);

    return true;
}

$action = getParam('action', '');

try {
switch ($action) {
    case 'sync_shop':
        syncShop();
        break;
    case 'sync_service_providers':
        syncServiceProviders();
        break;
    case 'sync_bonds':
        syncBonds();
        break;
    case 'sync_login':
        syncLogin();
        break;
    case 'sync_online_players':
        syncOnlinePlayers();
        break;
    case 'sync_daily_logins':
        syncDailyLogins();
        break;
    case 'sync_checkins':
        syncCheckins();
        break;
    case 'sync_player_ips':
        syncPlayerIps();
        break;
    case 'pull_shop':
        pullShop();
        break;
    case 'pull_bonds':
        pullBonds();
        break;
    case 'sync_weblogin_token':
        syncWebloginToken();
        break;
    case 'validate_weblogin_token':
        apiValidateWebloginToken();
        break;
    case 'push_player_login_status':
        pushPlayerLoginStatus();
        break;
    case 'sync_token':
        syncToken();
        break;
    case 'receive_token':
        receiveToken();
        break;
    case 'check_web_login_confirmations':
        checkWebLoginConfirmations();
        break;
    case 'check_web_login_verified':
        checkWebLoginVerified();
        break;
    case 'web_login_request':
        webLoginRequest();
        break;
    case 'cancel_web_login':
        cancelWebLogin();
        break;
    case 'check_web_login_result':
        checkWebLoginResult();
        break;
    case 'check_pending_web_logins':
        checkPendingWebLogins();
        break;
    case 'complete_web_login_request':
        completeWebLoginRequest();
        break;
    case 'push_web_credentials':
        pushWebCredentials();
        break;
    case 'verify_web_password':
        // ★ 安全修复：禁止PHP自验证密码，必须走web_login_request流程
        error('安全限制: 密码验证必须通过游戏服务器，请使用web_login_request接口');
        break;
    case 'web_access_check':
        webAccessCheck();
        break;
    case 'pull_pending_transactions':
        pullPendingTransactions();
        break;
    case 'confirm_transaction':
        confirmTransaction();
        break;
    case 'pull_shop_stock':
        pullShopStock();
        break;
    case 'clear_admin_stock':
        clearAdminStock();
        break;
    case 'check_shop_stock_changed':
        checkShopStockChanged();
        break;
    case 'notify_sync':
        notifySync();
        break;
    case 'request_immediate_sync':
        requestImmediateSync();
        break;
    case 'resend_pending':
        resendPendingTransactions();
        break;
    case 'send_email_code':
        sendEmailCode();
        break;
    case 'verify_email_code':
        verifyEmailCode();
        break;
    case 'get_player_email':
        getPlayerEmail();
        break;
    case 'fix_stock':
        fixStock();
        break;
    case 'fix_timestamps':
        fixTimestamps();
        break;
    case 'send_reset_password_link':
        sendResetPasswordLink();
        break;
    case 'reset_password':
        resetPassword();
        break;
    case 'admin_list_users':
        adminListUsers();
        break;
    case 'admin_send_reset_link':
        adminSendResetLink();
        break;
    case 'extend_web_token':
        extendWebToken();
        break;
    case 'change_password':
        changePassword();
        break;
    case 'check_pending_web_register_requests':
        checkPendingWebRegisterRequests();
        break;
    case 'complete_web_register_request':
        completeWebRegisterRequest();
        break;
    case 'delete_user':
        deleteUser();
        break;
    case 'sync_transactions':
        syncTransactions();
        break;
    case 'check_pending_transactions':
        checkPendingTransactions();
        break;
    case 'validate_cdk':
        validateCdk();
        break;
    case 'check_cdk_exists':
        checkCdkExists();
        break;
    case 'cdk_redeem_remote':
        cdkRedeemRemote();
        break;
    case 'pull_cdk_validate_requests':
        pullCdkValidateRequests();
        break;
    case 'migrate_cdk':
        migrateCdkTable();
        break;
    case 'push_cdk_validate_result':
        pushCdkValidateResult();
        break;
    case 'sync_lands':
        syncLands();
        break;
    case 'sync_land_shop':
        syncLandShop();
        break;
    case 'sync_config':
        syncConfig();
        break;
    case 'sync_permissions':
        syncPermissions();
        break;
    default:
        error('未知操作: ' . $action);
}
} catch (\Throwable $e) {
    while (ob_get_level() > 0) ob_end_clean();
    error('服务器内部错误: ' . $e->getMessage(), 500);
}

// ===== 插件推送商品数据 =====
function syncShop() {
    // ★ 支持token或SECRET_KEY认证
    $token = getParam('token');
    $secret = getParam('secret');
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            if (!($secret && $secret === SECRET_KEY)) {
                error('同步需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('同步需要token或SECRET_KEY');
    }

    $items = getParam('items');
    if (!$items || !is_array($items)) {
        error('缺少items数据');
    }

    $db = getDB();
    $now = time();
    $count = 0;

    foreach ($items as $item) {
        $id = $item['id'] ?? null;
        if (!$id) continue;

        // 游戏端stock=0表示无限供应，Web端用-1表示无限
        $stock = (int)($item['stock'] ?? -1);
        // 仅≤-2的未定义值转为-1（无限），0和正数保留原值

        // ★ 检查该商品是否有管理员库存改动（admin_stock非NULL）
        $checkStmt = $db->prepare("SELECT admin_stock FROM shop_items WHERE id = :id");
        $checkStmt->bindValue(':id', $id, SQLITE3_TEXT);
        $checkResult = $checkStmt->execute();
        $checkRow = $checkResult->fetchArray(SQLITE3_ASSOC);
        $hasAdminStock = $checkRow && $checkRow['admin_stock'] !== null && $checkRow['admin_stock'] !== '';

        if ($hasAdminStock) {
            // ★ 管理员修改过库存：跳过stock字段，只更新其他字段
            $stmt = $db->prepare("INSERT INTO shop_items (id, category, display_name, material, buy_price, sell_price, stock, hourly_sales, total_sales, last_sync) VALUES (:id, :cat, :name, :mat, :bp, :sp, :st, :hs, :ts, :time) ON CONFLICT(id) DO UPDATE SET category=:cat, display_name=:name, material=:mat, buy_price=:bp, sell_price=:sp, hourly_sales=:hs, total_sales=:ts, last_sync=:time");
        } else {
            // ★ 正常：更新所有字段包括stock
            $stmt = $db->prepare("INSERT INTO shop_items (id, category, display_name, material, buy_price, sell_price, stock, hourly_sales, total_sales, last_sync) VALUES (:id, :cat, :name, :mat, :bp, :sp, :st, :hs, :ts, :time) ON CONFLICT(id) DO UPDATE SET category=:cat, display_name=:name, material=:mat, buy_price=:bp, sell_price=:sp, stock=:st, hourly_sales=:hs, total_sales=:ts, last_sync=:time");
        }
        $stmt->bindValue(':id', $id, SQLITE3_TEXT);
        $stmt->bindValue(':cat', $item['category'] ?? '默认', SQLITE3_TEXT);
        $stmt->bindValue(':name', $item['display_name'] ?? $id, SQLITE3_TEXT);
        $stmt->bindValue(':mat', $item['material'] ?? 'STONE', SQLITE3_TEXT);
        $stmt->bindValue(':bp', (int)($item['buy_price'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':sp', (int)($item['sell_price'] ?? -1), SQLITE3_INTEGER);
        $stmt->bindValue(':st', $stock, SQLITE3_INTEGER);
        $stmt->bindValue(':hs', (int)($item['hourly_sales'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ts', (int)($item['total_sales'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    success(['synced' => $count], "同步了{$count}个商品");
}

// ===== 插件推送服务商列表 =====
function syncServiceProviders() {
    $token = getParam('token');
    $secret = getParam('secret');
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            if (!($secret && $secret === SECRET_KEY)) {
                error('同步需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('缺少认证信息');
    }

    $db = getDB();
    $data = getParam('data'); // 可能是JSON字符串或已解析数组（从php://input）

    if (!$data) error('缺少data参数');

    // getParam从php://input解析JSON body时可能直接返回数组
    $providers = is_array($data) ? $data : json_decode($data, true);
    if (!$providers || !is_array($providers)) error('data格式无效');

    $db->exec("BEGIN IMMEDIATE");
    try {
        // 全量替换
        $db->exec("DELETE FROM web_service_providers");
        $stmt = $db->prepare("INSERT INTO web_service_providers (player_name, role, active, join_time) VALUES (:p, :r, :a, :t)");
        $count = 0;
        foreach ($providers as $p) {
            $stmt->bindValue(':p', $p['player_name'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':r', $p['role'] ?? 'waiter', SQLITE3_TEXT);
            $stmt->bindValue(':a', $p['active'] ?? 1, SQLITE3_INTEGER);
            $stmt->bindValue(':t', $p['join_time'] ?? 0, SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }
        $db->exec("COMMIT");
        debugLog("syncServiceProviders: 同步了 $count 个服务商");
        success(['count' => $count], "同步了 $count 个服务商");
    } catch (\Throwable $e) {
        $db->exec("ROLLBACK");
        error('同步失败: ' . $e->getMessage());
    }
}

// ===== 领地数据同步 =====
function syncLands() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) error('密钥验证失败', 403);

    $landsRaw = getParam('lands');
    if (!$landsRaw) error('缺少lands参数');
    $lands = is_array($landsRaw) ? $landsRaw : json_decode($landsRaw, true);
    if (!is_array($lands)) error('lands格式无效');

    $db = getDB();
    // 建表
    $db->exec("CREATE TABLE IF NOT EXISTS web_area_lands (
        id INTEGER PRIMARY KEY,
        name TEXT UNIQUE NOT NULL,
        owner TEXT DEFAULT '',
        world TEXT DEFAULT '',
        x1 INTEGER DEFAULT 0, z1 INTEGER DEFAULT 0,
        x2 INTEGER DEFAULT 0, z2 INTEGER DEFAULT 0,
        y_min INTEGER DEFAULT 0, y_max INTEGER DEFAULT 255,
        area_size INTEGER DEFAULT 0,
        created_at INTEGER DEFAULT 0,
        synced_at INTEGER DEFAULT 0
    )");

    // ★ 效果迁移
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN clear_effects TEXT DEFAULT ''"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN give_effects TEXT DEFAULT ''"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN clear_all_bad_effects INTEGER DEFAULT 0"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN deny_all_effects INTEGER DEFAULT 0"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN admin_changed INTEGER DEFAULT 0"); } catch (\Throwable $e) {}

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_lands
        (id, name, owner, world, x1, z1, x2, z2, y_min, y_max, area_size, created_at, synced_at,
         peace_mode, peace_mode_duration, peace_whitelist, enforce_game_mode, mode_exempt,
         enter_msg, leave_msg, confiscate_msg, enable_announce, announce_template, txt_content,
         deny_block_break, deny_block_place, deny_fluid, deny_pvp, deny_fire_spread, deny_all_effects,
         deny_item_frame, deny_move, deny_pickup, deny_drop, deny_explosion, deny_fall_damage, deny_hunger,
         deny_all_damage, clear_effects, give_effects, clear_all_bad_effects,
         admin_changed, deny_thrown_projectiles, deny_glowing, deny_redstone_interaction, deny_door_interaction,
         deny_noteblock_jukebox, deny_lead, deny_crop_harvest, deny_wool_shear, deny_animal_feeding,
         warp_x, warp_y, warp_z, warp_yaw, warp_pitch, warp_world)
        VALUES (:id, :name, :owner, :world, :x1, :z1, :x2, :z2, :ymin, :ymax, :size, :created, :synced,
                :peace_mode, :peace_dur, :peace_wl, :enforce_gm, :mode_exempt,
                :enter_msg, :leave_msg, :confiscate_msg, :announce, :announce_tpl, :txt_content,
                :deny_block_break, :deny_block_place, :deny_fluid, :deny_pvp, :deny_fire_spread, :deny_all_effects,
                :deny_item_frame, :deny_move, :deny_pickup, :deny_drop, :deny_explosion, :deny_fall_damage, :deny_hunger,
                :deny_all_damage, :clear_effects, :give_effects, :clear_all_bad_effects,
                :admin_changed, :deny_thrown_projectiles, :deny_glowing, :deny_redstone_interaction, :deny_door_interaction,
                :deny_noteblock_jukebox, :deny_lead, :deny_crop_harvest, :deny_wool_shear, :deny_animal_feeding,
                :warp_x, :warp_y, :warp_z, :warp_yaw, :warp_pitch, :warp_world)");
    $count = 0;
    foreach ($lands as $land) {
        $stmt->bindValue(':id', (int)($land['id'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':name', $land['name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':owner', $land['owner'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':world', $land['world'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':x1', (int)($land['x1'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':z1', (int)($land['z1'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':x2', (int)($land['x2'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':z2', (int)($land['z2'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ymin', (int)($land['y_min'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':ymax', (int)($land['y_max'] ?? 255), SQLITE3_INTEGER);
        $stmt->bindValue(':size', (int)($land['area_size'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':created', (int)($land['created_at'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':synced', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':peace_dur', (int)($land['peace_mode_duration'] ?? 5), SQLITE3_INTEGER);
        $stmt->bindValue(':peace_wl', $land['peace_whitelist'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':enforce_gm', $land['enforce_game_mode'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':mode_exempt', $land['mode_exempt'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':enter_msg', $land['enter_msg'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':leave_msg', $land['leave_msg'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':confiscate_msg', $land['confiscate_msg'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':announce', (int)($land['enable_announce'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':announce_tpl', $land['announce_template'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':txt_content', $land['txt_content'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':deny_block_break', (int)($land['deny_block_break'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_block_place', (int)($land['deny_block_place'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fluid', (int)($land['deny_fluid'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_pvp', (int)($land['deny_pvp'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fire_spread', (int)($land['deny_fire_spread'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_all_effects', (int)($land['deny_all_effects'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_item_frame', (int)($land['deny_item_frame'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_move', (int)($land['deny_move'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_pickup', (int)($land['deny_pickup'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_drop', (int)($land['deny_drop'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_explosion', (int)($land['deny_explosion'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fall_damage', (int)($land['deny_fall_damage'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_hunger', (int)($land['deny_hunger'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_all_damage', (int)($land['deny_all_damage'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':clear_effects', $land['clear_effects'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':give_effects', $land['give_effects'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':clear_all_bad_effects', (int)($land['clear_all_bad_effects'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_all_effects', (int)($land['deny_all_effects'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':admin_changed', (int)($land['admin_changed'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_thrown_projectiles', (int)($land['deny_thrown_projectiles'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_glowing', (int)($land['deny_glowing'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_redstone_interaction', (int)($land['deny_redstone_interaction'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_door_interaction', (int)($land['deny_door_interaction'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_noteblock_jukebox', (int)($land['deny_noteblock_jukebox'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_lead', (int)($land['deny_lead'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_crop_harvest', (int)($land['deny_crop_harvest'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_wool_shear', (int)($land['deny_wool_shear'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_animal_feeding', (int)($land['deny_animal_feeding'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':warp_x', (double)($land['warp_x'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_y', (double)($land['warp_y'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_z', (double)($land['warp_z'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_yaw', (float)($land['warp_yaw'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_pitch', (float)($land['warp_pitch'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_world', $land['warp_world'] ?? '', SQLITE3_TEXT);
        $stmt->execute();
        $count++;
    }
    success("领地同步成功: {$count}个");
}

// ===== 领地权限商店同步 =====
function syncLandShop() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) error('密钥验证失败', 403);

    $itemsRaw = getParam('items');
    if (!$itemsRaw) error('缺少items参数');
    $items = is_array($itemsRaw) ? $itemsRaw : json_decode($itemsRaw, true);
    if (!is_array($items)) error('items格式无效');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_area_shop (
        id INTEGER PRIMARY KEY,
        land_id INTEGER NOT NULL,
        land_name TEXT DEFAULT '',
        seller TEXT NOT NULL,
        permission TEXT DEFAULT 'visitor',
        price INTEGER DEFAULT 0,
        duration INTEGER DEFAULT 86400,
        status TEXT DEFAULT 'active',
        buyer TEXT DEFAULT '',
        bought_at INTEGER DEFAULT 0,
        created_at INTEGER DEFAULT 0,
        synced_at INTEGER DEFAULT 0
    )");

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_shop
        (id, land_id, land_name, seller, permission, price, duration, status, buyer, bought_at, created_at, synced_at)
        VALUES (:id, :land_id, :land_name, :seller, :perm, :price, :dur, :status, :buyer, :bought, :created, :synced)");
    $count = 0;
    foreach ($items as $item) {
        $stmt->bindValue(':id', (int)($item['id'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':land_id', (int)($item['land_id'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':land_name', $item['land_name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':seller', $item['seller'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':perm', $item['permission'] ?? 'visitor', SQLITE3_TEXT);
        $stmt->bindValue(':price', (int)($item['price'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':dur', (int)($item['duration'] ?? 86400), SQLITE3_INTEGER);
        $stmt->bindValue(':status', $item['status'] ?? 'active', SQLITE3_TEXT);
        $stmt->bindValue(':buyer', $item['buyer'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':bought', (int)($item['bought_at'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':created', (int)($item['created_at'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':synced', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }
    success("权限商店同步成功: {$count}个");
}

// ===== 插件推送全局配置 =====
function syncConfig() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) error('密钥验证失败', 403);

    $configRaw = getParam('config');
    if (!$configRaw) error('缺少config参数');
    $config = json_decode($configRaw, true);
    if (!is_array($config)) error('config格式无效');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_area_config (
        key TEXT PRIMARY KEY,
        value TEXT DEFAULT ''
    )");

    $now = time();
    $count = 0;
    foreach ($config as $key => $val) {
        $stmt = $db->prepare("INSERT INTO web_area_config (key, value) VALUES (:key, :val) "
            . "ON CONFLICT(key) DO UPDATE SET value = :val2");
        $stmt->bindValue(':key', $key, SQLITE3_TEXT);
        $stmt->bindValue(':val', $val, SQLITE3_TEXT);
        $stmt->bindValue(':val2', $val, SQLITE3_TEXT);
        $stmt->execute();
        $count++;
    }
    success("配置同步成功: {$count}项");
}

// ===== 插件推送访客权限数据 =====
function syncPermissions() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) error('密钥验证失败', 403);

    $permsRaw = getParam('permissions');
    if (!$permsRaw) error('缺少permissions参数');
    $perms = is_array($permsRaw) ? $permsRaw : json_decode($permsRaw, true);
    if (!is_array($perms)) error('permissions格式无效');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_area_permissions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        land_id INTEGER NOT NULL,
        land_name TEXT DEFAULT '',
        player_name TEXT NOT NULL,
        role TEXT NOT NULL DEFAULT 'visitor',
        permissions TEXT DEFAULT '',
        granted_at INTEGER DEFAULT 0,
        expires_at INTEGER DEFAULT 0,
        synced_at INTEGER DEFAULT 0,
        UNIQUE(land_id, player_name)
    )");

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_permissions
        (land_id, land_name, player_name, role, permissions, granted_at, expires_at, synced_at)
        VALUES (:land_id, :land_name, :player, :role, :perms, :granted, :expires, :synced)");
    $count = 0;
    foreach ($perms as $p) {
        $stmt->bindValue(':land_id', (int)($p['land_id'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':land_name', $p['land_name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':player', $p['player_name'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':role', $p['role'] ?? 'visitor', SQLITE3_TEXT);
        $stmt->bindValue(':perms', $p['permissions'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':granted', (int)($p['granted_at'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':expires', (int)($p['expires_at'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':synced', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }
    success("访客权限同步成功: {$count}个");
}

// ===== 插件推送债券余额 =====
function syncBonds() {
    // ★ 支持token或SECRET_KEY认证
    $token = getParam('token');
    $secret = getParam('secret');
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            if (!($secret && $secret === SECRET_KEY)) {
                error('同步需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('同步需要token或SECRET_KEY');
    }

    $bonds = getParam('bonds');
    if (!$bonds || !is_array($bonds)) {
        error('缺少bonds数据');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $now = time();
    $count = 0;

    foreach ($bonds as $player => $amount) {
        $stmt = $db->prepare("INSERT OR REPLACE INTO bond_cache (player_name, amount, updated_at) VALUES (:name, :amount, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', (int)$amount, SQLITE3_INTEGER);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $count++;
    }

    success(['synced' => $count], "同步了{$count}个玩家余额");
}

// ===== 插件推送游戏内交易记录 =====
function syncTransactions() {
    try {
        // ★ 支持token或SECRET_KEY认证
        $token = getParam('token');
        $secret = getParam('secret');
        if ($token) {
            $tokenInfo = validateToken($token);
            if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
                if (!($secret && $secret === SECRET_KEY)) {
                    @error_log("[syncTransactions] Token验证失败: token=" . substr($token, 0, 8) . "..., info=" . var_export($tokenInfo, true));
                    error('同步需要管理权限token或SECRET_KEY');
                }
            }
        } elseif ($secret) {
            if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
        } else {
            error('同步需要token或SECRET_KEY');
        }

        $transactions = getParam('transactions');
        @error_log("[syncTransactions] transactions type=" . gettype($transactions) . ", is_null=" . var_export($transactions === null, true));

        if (!$transactions || !is_array($transactions)) {
            error('缺少transactions数据 (type=' . gettype($transactions) . ')');
        }

    @error_log("[syncTransactions] Received " . count($transactions) . " transactions");
    if (count($transactions) > 0) {
        @error_log("[syncTransactions] First tx: " . json_encode($transactions[0], JSON_UNESCAPED_UNICODE));
    }

    $db = getDB();
    // ★ 设置busy_timeout防止database is locked
    $db->exec('PRAGMA busy_timeout=10000');

    // 创建game_transactions表，用于存储游戏内交易记录
    $db->exec("CREATE TABLE IF NOT EXISTS game_transactions (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        java_id INTEGER NOT NULL,
        player_name TEXT NOT NULL,
        type TEXT NOT NULL,
        amount INTEGER NOT NULL,
        target_player TEXT DEFAULT '',
        operator TEXT DEFAULT '',
        reason TEXT DEFAULT '',
        balance_before INTEGER DEFAULT 0,
        balance_after INTEGER DEFAULT 0,
        tx_time INTEGER NOT NULL,
        synced_at INTEGER NOT NULL,
        UNIQUE(java_id)
    )");

    // 索引用于查询加速
    try { $db->exec("CREATE INDEX IF NOT EXISTS idx_gt_player ON game_transactions(player_name, tx_time)"); } catch (\Throwable $e) {}
    try { $db->exec("CREATE INDEX IF NOT EXISTS idx_gt_type ON game_transactions(type)"); } catch (\Throwable $e) {}

    $now = time();
    $count = 0;
    $skipped = 0;

    // ★ 使用事务批量插入，减少锁冲突
    $db->exec("BEGIN IMMEDIATE");
    try {
        foreach ($transactions as $tx) {
            $javaId = (int)($tx['id'] ?? 0);
            if ($javaId <= 0) { $skipped++; continue; }

            $stmt = $db->prepare("INSERT OR REPLACE INTO game_transactions
                (java_id, player_name, type, amount, target_player, operator, reason, balance_before, balance_after, tx_time, synced_at)
                VALUES (:jpid, :name, :type, :amount, :target, :op, :reason, :bfb, :bfa, :time, :synced)");
            $stmt->bindValue(':jpid', $javaId, SQLITE3_INTEGER);
            $stmt->bindValue(':name', $tx['player_name'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':type', $tx['type'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':amount', (int)($tx['amount'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':target', $tx['target_player'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':op', $tx['operator'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':reason', $tx['reason'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':bfb', (int)($tx['balance_before'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':bfa', (int)($tx['balance_after'] ?? 0), SQLITE3_INTEGER);
            // Java端使用毫秒时间戳，PHP端存储秒级时间戳
            $txTime = (int)($tx['time'] ?? 0);
            if ($txTime > 1000000000000) {
                $txTime = intval($txTime / 1000);
            }
            $stmt->bindValue(':time', $txTime, SQLITE3_INTEGER);
            $stmt->bindValue(':synced', $now, SQLITE3_INTEGER);
            $stmt->execute();
            if ($db->changes() > 0) {
                $count++;
            } else {
                $skipped++;
            }
        }
        $db->exec("COMMIT");
    } catch (\Throwable $e) {
        $db->exec("ROLLBACK");
        @error_log("[syncTransactions] Transaction failed, rolled back: " . $e->getMessage());
        throw $e;
    }

    debugLog("sync_transactions: 接收游戏交易记录", [
        'total' => count($transactions),
        'inserted' => $count,
        'skipped_duplicates' => $skipped
    ]);

    success(['synced' => $count, 'skipped' => $skipped, 'total' => count($transactions)], "同步了{$count}笔游戏交易");
    } catch (\Throwable $e) {
        @error_log("[syncTransactions] EXCEPTION: " . $e->getMessage() . " at " . $e->getTraceAsString());
        error('syncTransactions异常: ' . $e->getMessage());
    }
}

// ===== 插件推送注册数据 =====
function syncLogin() {
    // ★ 支持两种认证：token 或 SECRET_KEY（防止token注册超时导致同步失败）
    $token = getParam('token');
    $secret = getParam('secret');

    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            // token无效时，检查是否携带了secret
            if ($secret && $secret === SECRET_KEY) {
                // secret有效，继续执行
            } else {
                error('同步需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('同步需要token或SECRET_KEY');
    }

    $users = getParam('users');
    if (!$users || !is_array($users)) {
        error('缺少users数据');
    }

    // ★ 调试日志：记录插件推送的用户数据
    debugLog("sync_login: 插件推送用户数据", [
        'user_count' => count($users)
    ]);

    $db = getDB();
    $now = time();
    $count = 0;

    // ★ 所有写操作（INSERT + 清理）包装在一个事务中
    $db->exec("BEGIN IMMEDIATE");
    try {
        foreach ($users as $user) {
            $name = $user['player_name'] ?? null;
            if (!$name) continue;

            $registerTime = (int)($user['register_time'] ?? 0);
            $lastLoginTime = (int)($user['last_login_time'] ?? 0);
            if ($registerTime > 1000000000000) { $registerTime = intval($registerTime / 1000); }
            if ($lastLoginTime > 1000000000000) { $lastLoginTime = intval($lastLoginTime / 1000); }

            $stmt = $db->prepare("INSERT OR REPLACE INTO users (player_name, register_time, last_login_time, email, points, gift_stage, total_online_time) VALUES (:name, :rt, :lt, :email, :pts, :gs, :tot)");
            $stmt->bindValue(':name', $name, SQLITE3_TEXT);
            $stmt->bindValue(':rt', $registerTime, SQLITE3_INTEGER);
            $stmt->bindValue(':lt', $lastLoginTime, SQLITE3_INTEGER);
            $stmt->bindValue(':email', $user['email'] ?? '', SQLITE3_TEXT);
            $stmt->bindValue(':pts', (int)($user['points'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':gs', (int)($user['gift_stage'] ?? 0), SQLITE3_INTEGER);
            $stmt->bindValue(':tot', (int)($user['total_online_time'] ?? 0), SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }

        $namesInSync = array_map(function($u) { return $u['player_name'] ?? null; }, $users);
        $namesInSync = array_filter($namesInSync, function($n) { return $n !== null; });

        // 清理 users 表中已不存在的用户
        try {
            $stmt = $db->prepare("SELECT player_name FROM users");
            $result = $stmt->execute();
            $phpUsers = [];
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
                $phpUsers[] = $row['player_name'];
            }
            
            $orphanedUsers = array_diff($phpUsers, $namesInSync);
            if (!empty($orphanedUsers)) {
                $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphanedUsers));
                $db->exec("DELETE FROM users WHERE player_name IN ('" . $nameList . "')");
                debugLog("sync_login: 清理了 " . count($orphanedUsers) . " 个孤立的 users 记录", ['orphaned' => $orphanedUsers]);
            }
        } catch (\Throwable $e) {
            debugLog("sync_login: 清理孤立 users 记录失败", ['error' => $e->getMessage()]);
        }

        // 清理 weblogin_credentials 中已不存在的用户（游戏内删号后同步）
        try {
            $stmt = $db->prepare("SELECT player_name FROM weblogin_credentials");
            $result = $stmt->execute();
            $webCredentials = [];
            while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
                $webCredentials[] = $row['player_name'];
            }
            
            $namesInSync2 = array_map(function($u) { return $u['player_name'] ?? null; }, $users);
            $orphaned = array_diff($webCredentials, $namesInSync2);
            
            if (!empty($orphaned)) {
                $nameList = implode("','", array_map(function($n) use ($db) { return addslashes($n); }, $orphaned));
                $db->exec("DELETE FROM weblogin_credentials WHERE player_name IN ('" . $nameList . "')");
                debugLog("sync_login: 清理了 " . count($orphaned) . " 个孤立的 weblogin_credentials 记录", ['orphaned' => $orphaned]);
            }
        } catch (\Throwable $e) {
            debugLog("sync_login: 清理孤立 weblogin_credentials 记录失败", ['error' => $e->getMessage()]);
        }

        // 清理 weblogin_tokens：只清理已过期的
        try {
            $expireTime = time() - 600;
            $db->exec("DELETE FROM weblogin_tokens WHERE created_at < " . $expireTime);
        } catch (\Throwable $e) {}

        // 清理 web_login_verified：只清理已过期的
        try {
            $expireTime = time() - 600;
            $db->exec("DELETE FROM web_login_verified WHERE verified_at < " . $expireTime);
        } catch (\Throwable $e) {}

        // 清理 web_session_log：只清理过期的
        try {
            $expireTime = time() - 600;
            $db->exec("DELETE FROM web_session_log WHERE login_time < " . $expireTime);
        } catch (\Throwable $e) {}

        $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
    }

    debugLog("sync_login: 同步完成", ['synced_count' => $count, 'cleared_users' => ($orphanedUsers ?? []) ? count($orphanedUsers) : 0]);
    success(['synced' => $count], "同步了{$count}个用户");
}

// ===== Java插件主动删除用户 =====
function deleteUser() {
    // ★ 支持token或SECRET_KEY认证
    $token = getParam('token');
    $secret = getParam('secret');
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            if (!($secret && $secret === SECRET_KEY)) {
                error('删除需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('删除需要token或SECRET_KEY');
    }

    $playerName = getParam('player_name');
    if (!$playerName) error('缺少player_name');

    $db = getDB();

    // 清理所有相关表
    $tables = ['users', 'weblogin_credentials', 'weblogin_tokens', 'web_login_verified', 'web_session_log', 'bond_cache', 'email_codes', 'password_reset_requests', 'web_login_requests', 'web_register_requests'];
    $deleted = 0;
    foreach ($tables as $table) {
        try {
            $stmt = $db->prepare("DELETE FROM $table WHERE player_name = :player");
            $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $stmt->execute();
            $deleted++;
        } catch (\Throwable $e) {
            // 表可能不存在，跳过
        }
    }

    debugLog("deleteUser: 删除了用户 " . $playerName . " 及其 " . $deleted . " 个相关表的记录");
    success(['player' => $playerName], "已删除用户 " . $playerName . " 的所有数据");
}

// ===== 修复时间戳 =====
function fixTimestamps() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    
    // 检测并修复毫秒级时间戳
    // 正常秒级时间戳：10位（如1717900000）
    // 毫秒级时间戳：13位（如1717900000000）
    $threshold = 1000000000000; // 10^12
    
    // 修复register_time
    $fixRegister = 0;
    $result = $db->query("SELECT player_name, register_time FROM users WHERE register_time > $threshold");
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $fixedTime = intval($row['register_time'] / 1000);
        $stmt = $db->prepare("UPDATE users SET register_time = :time WHERE player_name = :name");
        $stmt->bindValue(':time', $fixedTime, SQLITE3_INTEGER);
        $stmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
        $stmt->execute();
        $fixRegister++;
    }
    
    // 修复last_login_time
    $fixLogin = 0;
    $result = $db->query("SELECT player_name, last_login_time FROM users WHERE last_login_time > $threshold");
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $fixedTime = intval($row['last_login_time'] / 1000);
        $stmt = $db->prepare("UPDATE users SET last_login_time = :time WHERE player_name = :name");
        $stmt->bindValue(':time', $fixedTime, SQLITE3_INTEGER);
        $stmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
        $stmt->execute();
        $fixLogin++;
    }
    
    success([
        'fixed_register' => $fixRegister,
        'fixed_login' => $fixLogin,
        'total' => $fixRegister + $fixLogin
    ], "修复了{$fixRegister}条注册时间、{$fixLogin}条登录时间");
}

// ===== 插件同步在线玩家列表 =====
function syncOnlinePlayers() {
    global $db;
    $db = getDB();

    // ★ 尝试从三个来源读取 secret 和 players
    $rawInput = null;

    // 1) 先从 $_GET/$_POST 读
    $secret = getParam('secret');
    $players = getParam('players');

    // ★ 关键修复：如果players是字符串（GET请求或表单提交），尝试JSON解码
    if ($players !== null && is_string($players)) {
        $decoded = json_decode($players, true);
        if (is_array($decoded)) {
            $players = $decoded;
            @error_log("[syncOnlinePlayers] players从GET/POST字符串JSON解码成功: " . count($players) . "人");
        }
    }

    // 2) 如果 PHP 解析了 x-www-form-urlencoded，$_POST 会有数据
    // 3) 如果是 application/json，$_POST 为空，需要从 $_SERVER['HTTP_RAW_POST_DATA'] 或 php://input 读取

    if (!$secret && isset($_POST['secret'])) {
        $secret = $_POST['secret'];
    }
    if (($players === null || !is_array($players)) && isset($_POST['players'])) {
        $pVal = $_POST['players'];
        // 表单提交的 players 可能是 URL 编码的字符串
        if (is_string($pVal)) {
            $pVal = json_decode($pVal, true);
        }
        if (is_array($pVal)) $players = $pVal;
    }

    // 4) 如果还没有，手动解析 php://input
    if (!$secret || !$players) {
        static $_syncCached = false;
        if (!$_syncCached) {
            $_syncCached = true;
            $rawInput = file_get_contents('php://input');
            if ($rawInput) {
                // 先试 JSON
                $jsonParsed = json_decode($rawInput, true);
                if (json_last_error() === JSON_ERROR_NONE && is_array($jsonParsed)) {
                    if (!isset($secret) && isset($jsonParsed['secret'])) $secret = $jsonParsed['secret'];
                    if (!isset($players) && isset($jsonParsed['players'])) {
                        $players = $jsonParsed['players'];
                    }
                } else {
                    // 不是 JSON，解析为表单
                    parse_str($rawInput, $formVars);
                    if (!isset($secret) && isset($formVars['secret'])) $secret = $formVars['secret'];
                    if (!isset($players) && isset($formVars['players'])) {
                        $pVal = $formVars['players'];
                        if (is_string($pVal)) {
                            $pVal = json_decode($pVal, true);
                        }
                        if (is_array($pVal)) $players = $pVal;
                    }
                }
            }
        }
    }

    if (!$secret || $secret !== SECRET_KEY) error('认证失败');
    if ($players === null || !is_array($players)) {
        @error_log("[syncOnlinePlayers] FAIL: invalid players data, type=" . gettype($players) . ", content=" . var_export($players, true));
        error('缺少players数据 (received=' . var_export($players, true) . ' | type=' . gettype($players) . ')');
    }

    // ★ 详细日志：记录接收到的原始数据
    $playerNames = array_map(function($p) { return $p['name'] ?? '?'; }, $players);
    @error_log("[syncOnlinePlayers] ★★★ 收到推送: " . count($players) . "人 = [" . implode(', ', $playerNames) . "]");
    @error_log("[syncOnlinePlayers] 原始players数据: " . json_encode($players, JSON_UNESCAPED_UNICODE));

    // ★ 设置更高的 busy_timeout 防止 "database is locked" 错误
    $db->exec('PRAGMA busy_timeout=10000');
    $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER NOT NULL)");
    $db->exec("CREATE TABLE IF NOT EXISTS online_player_hb (id INTEGER PRIMARY KEY DEFAULT 1, last_seen INTEGER DEFAULT 0)");
    // ★ 迁移：修复旧表结构（旧行无主键导致INSERT OR REPLACE失效）
    try {
        $colCheck = $db->query("PRAGMA table_info(online_player_hb)");
        $hasPK = false;
        while ($col = $colCheck->fetchArray(SQLITE3_ASSOC)) {
            if ($col['pk'] > 0) { $hasPK = true; break; }
        }
        if (!$hasPK) {
            // 读取旧数据
            $oldVal = 0;
            $oldRow = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1")->fetchArray(SQLITE3_ASSOC);
            if ($oldRow) $oldVal = (int)$oldRow['last_seen'];
            $db->exec("DROP TABLE IF EXISTS online_player_hb");
            $db->exec("CREATE TABLE online_player_hb (id INTEGER PRIMARY KEY DEFAULT 1, last_seen INTEGER DEFAULT 0)");
            $db->exec("INSERT INTO online_player_hb (id, last_seen) VALUES (1, $oldVal)");
            @error_log("[syncOnlinePlayers] 迁移online_player_hb表结构完成，旧值: $oldVal");
        }
    } catch (\Throwable $e) {
        @error_log("[syncOnlinePlayers] 迁移online_player_hb失败: " . $e->getMessage());
    }

    $now = time();

    // ★ 心跳检测：如果超过120秒没收到数据，清空在线表（防止Java推送异常残留旧数据）
    try {
        $hbStmt = $db->query("SELECT last_seen FROM online_player_hb LIMIT 1");
        $hbRow = $hbStmt->fetchArray(SQLITE3_ASSOC);
        if ($hbRow !== false && $hbRow['last_seen'] > 0) {
            $lastHb = (int)$hbRow['last_seen'];
            if (($now - $lastHb) > 120) {
                @error_log("[syncOnlinePlayers] Heartbeat stale: " . ($now - $lastHb) . "s > 120s, clearing online_players");
                $db->exec("DELETE FROM online_players");
            }
        }
    } catch (\Throwable $e) {
        // ignore
    }

    $playerCount = count($players);

    // ★ Debug: 记录接收到的玩家数据
    $playerNames = array_map(function($p) { return $p['name'] ?? '?'; }, $players);
    $playerTimes = array_map(function($p) { return $p['login_time'] ?? 0; }, $players);
    @error_log("[syncOnlinePlayers] Received $playerCount players: " . implode(', ', $playerNames));
    @error_log("[syncOnlinePlayers] Login times: " . implode(', ', $playerTimes) . " | Server time: $now");

    // ★ 确保 player_ip_changes 表存在
    $db->exec("CREATE TABLE IF NOT EXISTS player_ip_changes (
        player_name TEXT PRIMARY KEY,
        old_ip TEXT DEFAULT '',
        new_ip TEXT NOT NULL,
        changed_at INTEGER NOT NULL,
        synced_at INTEGER DEFAULT 0
    )");

    // ★ 使用事务批量写入，防止 "database is locked"
    $db->exec("BEGIN IMMEDIATE");
    try {
        // 删除所有旧记录，然后重新插入（避免残留旧数据）
        $db->exec("DELETE FROM online_players");

        // 逐条插入
        $synced = 0;
        foreach ($players as $player) {
            if (empty($player['name'])) continue;
            $name = $player['name'];
            $loginTime = isset($player['login_time']) ? (int)$player['login_time'] : $now;
            try {
                $stmt = $db->prepare("INSERT OR REPLACE INTO online_players (player_name, login_time) VALUES (:name, :time)");
                $stmt->bindValue(':name', $name, SQLITE3_TEXT);
                $stmt->bindValue(':time', $loginTime, SQLITE3_INTEGER);
                $stmt->execute();
                $synced++;
            } catch (\Throwable $e) {
                @error_log("[syncOnlinePlayers] FAIL insert $name: " . $e->getMessage());
            }

            // ★ 如果Java推送了IP，同步更新 player_ip_changes（实时IP来源）
            $ip = $player['ip'] ?? '';
            if (!empty($ip)) {
                try {
                    // 检查是否已有记录且IP相同
                    $checkStmt = $db->prepare("SELECT new_ip FROM player_ip_changes WHERE player_name = :name");
                    $checkStmt->bindValue(':name', $name, SQLITE3_TEXT);
                    $checkResult = $checkStmt->execute();
                    $existing = $checkResult->fetchArray(SQLITE3_ASSOC);

                    if (!$existing || $existing['new_ip'] !== $ip) {
                        $oldIp = $existing ? $existing['new_ip'] : '';
                        $ipStmt = $db->prepare("INSERT OR REPLACE INTO player_ip_changes (player_name, old_ip, new_ip, changed_at, synced_at) VALUES (:name, :old, :new, :time, :synced)");
                        $ipStmt->bindValue(':name', $name, SQLITE3_TEXT);
                        $ipStmt->bindValue(':old', $oldIp, SQLITE3_TEXT);
                        $ipStmt->bindValue(':new', $ip, SQLITE3_TEXT);
                        $ipStmt->bindValue(':time', $now, SQLITE3_INTEGER);
                        $ipStmt->bindValue(':synced', $now, SQLITE3_INTEGER);
                        $ipStmt->execute();
                        @error_log("[syncOnlinePlayers] IP更新: {$name} {$oldIp}→{$ip}");
                    }
                } catch (\Throwable $e) {
                    @error_log("[syncOnlinePlayers] IP写入失败 {$name}: " . $e->getMessage());
                }
            }
        }

        // 更新心跳时间戳（使用INSERT OR REPLACE，有主键则更新，无则插入）
        try {
            $hbSql = $db->prepare("INSERT OR REPLACE INTO online_player_hb (id, last_seen) VALUES (1, :time)");
            $hbSql->bindValue(':time', $now, SQLITE3_INTEGER);
            $hbSql->execute();
        } catch (\Throwable $e) {
            @error_log("[syncOnlinePlayers] heartbeat update error: " . $e->getMessage());
        }

        $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[syncOnlinePlayers] Transaction failed: " . $e->getMessage());
    }

    // ★ 验证：查询刚插入的数据
    $verifyStmt = $db->query("SELECT COUNT(*) as cnt FROM online_players");
    $verifyRow = $verifyStmt->fetchArray(SQLITE3_ASSOC);
    @error_log("[syncOnlinePlayers] Synced $synced/$playerCount, DB now has " . ($verifyRow['cnt'] ?? 0) . " records");

    success(['synced' => $synced, 'server_time' => $now], "同步了" . $synced . "个在线玩家");
}

// ===== 同步每日登录记录（Java插件推送） =====
function syncDailyLogins() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $logins = getParam('logins');
    if ($logins === null) {
        // 尝试从POST body解析
        $raw = file_get_contents('php://input');
        if ($raw) {
            $decoded = json_decode($raw, true);
            if (isset($decoded['logins'])) $logins = $decoded['logins'];
        }
    }
    if ($logins === null || !is_array($logins)) {
        error('缺少logins数据');
    }

    $db = getDB();
    $db->exec("BEGIN IMMEDIATE");
    try {
        $count = 0;
        foreach ($logins as $entry) {
            $name = $entry['name'] ?? '';
            $date = $entry['date'] ?? date('Y-m-d');
            $firstLogin = $entry['first_login'] ?? time();
            $lastLogin = $entry['last_login'] ?? time();
            if (!$name) continue;

            $stmt = $db->prepare("INSERT OR REPLACE INTO player_daily_logins (player_name, login_date, first_login, last_login) VALUES (:name, :date, :first, :last)");
            $stmt->bindValue(':name', $name, SQLITE3_TEXT);
            $stmt->bindValue(':date', $date, SQLITE3_TEXT);
            $stmt->bindValue(':first', (int)$firstLogin, SQLITE3_INTEGER);
            $stmt->bindValue(':last', (int)$lastLogin, SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }
        $db->exec("COMMIT");
        success(['synced' => $count], "同步了{$count}条登录记录");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[syncDailyLogins] Error: " . $e->getMessage());
        error('同步失败: ' . $e->getMessage());
    }
}

// ===== 同步签到记录（Java插件推送） =====
function syncCheckins() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $checkins = getParam('checkins');
    if ($checkins === null) {
        $raw = file_get_contents('php://input');
        if ($raw) {
            $decoded = json_decode($raw, true);
            if (isset($decoded['checkins'])) $checkins = $decoded['checkins'];
        }
    }
    if ($checkins === null || !is_array($checkins)) {
        error('缺少checkins数据');
    }

    $db = getDB();
    $db->exec("BEGIN IMMEDIATE");
    try {
        $count = 0;
        foreach ($checkins as $entry) {
            $name = $entry['name'] ?? '';
            $date = $entry['date'] ?? date('Y-m-d');
            $time = $entry['time'] ?? time();
            if (!$name) continue;

            $stmt = $db->prepare("INSERT OR REPLACE INTO player_checkins (player_name, checkin_date, checkin_time) VALUES (:name, :date, :time)");
            $stmt->bindValue(':name', $name, SQLITE3_TEXT);
            $stmt->bindValue(':date', $date, SQLITE3_TEXT);
            $stmt->bindValue(':time', (int)$time, SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }
        $db->exec("COMMIT");
        success(['synced' => $count], "同步了{$count}条签到记录");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[syncCheckins] Error: " . $e->getMessage());
        error('同步失败: ' . $e->getMessage());
    }
}

// ===== 同步所有玩家IP到PHP端（带变更检测） =====
function syncPlayerIps() {
    $db = getDB();

    // ★ 解析参数
    $secret = getParam('secret');
    $players = getParam('players');

    if (!$secret && isset($_POST['secret'])) {
        $secret = $_POST['secret'];
    }
    if (($players === null || !is_array($players)) && isset($_POST['players'])) {
        $pVal = $_POST['players'];
        if (is_string($pVal)) {
            $pVal = json_decode($pVal, true);
        }
        if (is_array($pVal)) $players = $pVal;
    }

    if (!$secret || $secret !== SECRET_KEY) error('认证失败');
    if ($players === null || !is_array($players)) {
        error('缺少players数据');
    }

    // 创建IP变更日志表（用于追踪IP变化）
    $db->exec("CREATE TABLE IF NOT EXISTS player_ip_changes (
        player_name TEXT PRIMARY KEY,
        old_ip TEXT DEFAULT '',
        new_ip TEXT NOT NULL,
        changed_at INTEGER NOT NULL,
        synced_at INTEGER DEFAULT 0
    )");

    // 创建IP归属地缓存表（用于避免重复查询API）
    $db->exec("CREATE TABLE IF NOT EXISTS player_ip_locations (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT DEFAULT 'global',
        ip_address TEXT NOT NULL,
        location TEXT DEFAULT '',
        updated_at INTEGER DEFAULT 0,
        UNIQUE(ip_address)
    )");

    // 批量更新玩家的IP到online_players表（带变更检测）
    $now = time();
    $changedCount = 0;
    $skippedCount = 0;

    // ★ 事务包装防止 "database is locked"
    $db->exec("BEGIN IMMEDIATE");
    try {
        foreach ($players as $player) {
            if (empty($player['name']) || empty($player['ip'])) {
                continue;
            }

            $playerName = $player['name'];
            $newIp = $player['ip'];

            // 检查该玩家是否有历史IP记录
            $stmt = $db->prepare("SELECT old_ip, synced_at FROM player_ip_changes WHERE player_name = :player");
            $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $result = $stmt->execute();
            $row = $result->fetchArray(SQLITE3_ASSOC);

            $hasChanged = false;
            if (!$row) {
                $hasChanged = true;
            } elseif ($row['old_ip'] !== $newIp) {
                $hasChanged = true;
            }

            if ($hasChanged) {
                $stmt = $db->prepare("INSERT OR REPLACE INTO player_ip_changes (player_name, old_ip, new_ip, changed_at, synced_at) VALUES (:player, :old, :new, :time, :synced)");
                $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
                $stmt->bindValue(':old', $row ? $row['old_ip'] : '', SQLITE3_TEXT);
                $stmt->bindValue(':new', $newIp, SQLITE3_TEXT);
                $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
                $stmt->bindValue(':synced', $now, SQLITE3_INTEGER);
                $stmt->execute();

                $changedCount++;

                if ($row && $row['synced_at'] > 0) {
                    $stmtReset = $db->prepare("UPDATE player_ip_changes SET synced_at = 0 WHERE player_name = :player");
                    $stmtReset->bindValue(':player', $playerName, SQLITE3_TEXT);
                    $stmtReset->execute();
                }
            } else {
                $skippedCount++;
            }
        }

        $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[syncPlayerIps] Transaction failed: " . $e->getMessage());
    }

    success([
        'changed' => $changedCount,
        'skipped' => $skippedCount,
        'total' => count($players)
    ], "同步IP: {$changedCount}个变更, {$skippedCount}个跳过");
}

// ===== 插件拉取商品 =====
function pullShop() {
    $db = getDB();
    $result = $db->query("SELECT * FROM shop_items ORDER BY category, id");

    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = $row;
    }

    success($items);
}

// ===== 插件拉取债券修改 =====
function pullBonds() {
    $token = getParam('token');
    $secret = getParam('secret');
    
    // 支持两种认证方式：token或SECRET_KEY
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            error('拉取需要管理权限token');
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('认证失败');
    } else {
        error('缺少认证参数');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    $result = $db->query("SELECT * FROM bond_cache ORDER BY updated_at DESC");

    $bonds = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $bonds[] = $row;
    }

    success($bonds);
}

// ===== 插件通过SECRET_KEY注册Token到PHP数据库 =====
function receiveToken() {
    $secret = getParam('secret');
    if ($secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $tokens = getParam('tokens');
    if (!$tokens || !is_array($tokens)) {
        error('缺少tokens数组');
    }

    $db = getDB();
    $db->exec('PRAGMA busy_timeout=10000');
    $now = time();
    $count = 0;

    try {
        $db->exec('BEGIN IMMEDIATE');
        foreach ($tokens as $t) {
            $tokenVal = $t['token'] ?? null;
            $player = $t['player'] ?? 'system';
            $purpose = $t['purpose'] ?? 'general';
            $expireSeconds = (int)($t['expire_seconds'] ?: 600);

            if (!$tokenVal) continue;

            $stmt = $db->prepare("INSERT OR IGNORE INTO tokens (token, player_name, purpose, created_at, expires_at, used) VALUES (:token, :player, :purpose, :created, :expires, 0)");
            $stmt->bindValue(':token', $tokenVal, SQLITE3_TEXT);
            $stmt->bindValue(':player', $player, SQLITE3_TEXT);
            $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
            $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
            $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
            $stmt->execute();
            $count++;
        }
        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
        @error_log("[receiveToken] Exception: " . $e->getMessage());
        error('Token注册失败: ' . $e->getMessage());
    }

    success(['registered' => $count], "注册了{$count}个Token");
}

// ===== 插件同步Token到PHP数据库（通过已有sync token） =====
function syncToken() {
    // ★ 支持token或SECRET_KEY认证
    $token = getParam('token');
    $secret = getParam('secret');
    if ($token) {
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync')) {
            if (!($secret && $secret === SECRET_KEY)) {
                error('同步需要管理权限token或SECRET_KEY');
            }
        }
    } elseif ($secret) {
        if ($secret !== SECRET_KEY) error('密钥验证失败', 403);
    } else {
        error('同步需要token或SECRET_KEY');
    }

    $newToken = getParam('new_token');
    $player = getParam('player');
    $purpose = getParam('purpose', 'general');
    $expireSeconds = (int)(getParam('expire_seconds') ?: 600);

    if (!$newToken || !$player) {
        error('缺少new_token或player参数');
    }

    $db = getDB();
    $now = time();

    $stmt = $db->prepare("INSERT OR REPLACE INTO tokens (token, player_name, purpose, created_at, expires_at, used) VALUES (:token, :player, :purpose, :created, :expires, 0)");
    $stmt->bindValue(':token', $newToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':purpose', $purpose, SQLITE3_TEXT);
    $stmt->bindValue(':created', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expires', $now + $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    success(['token' => substr($newToken, 0, 8) . '...'], "Token已同步");
}

// ===== 验证Web登录Token（不消费，仅验证有效性）=====
function syncWebloginToken() {
    // 支持两种认证方式：SECRET_KEY（插件直接调用）或 token（通用认证）
    $secret = getParam('secret');
    $token = getParam('token');

    if ($secret) {
        // SECRET_KEY认证（插件端直接调用，无需中间sync token）
        if ($secret !== SECRET_KEY) {
            error('密钥验证失败', 403);
        }
    } elseif ($token) {
        // Token认证（兼容旧方式）
        $tokenInfo = validateToken($token);
        if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all' && $tokenInfo['purpose'] !== 'sync' && $tokenInfo['purpose'] !== 'weblogin')) {
            error('同步需要管理权限token');
        }
    } else {
        error('缺少secret或token参数');
    }

    $player = getParam('player');
    $webToken = getParam('web_token');
    $expireSeconds = (int)(getParam('expire_seconds') ?: 600);

    if (!$player || !$webToken) {
        error('缺少player或web_token参数');
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (player_name TEXT PRIMARY KEY, web_token TEXT NOT NULL, created_at INTEGER NOT NULL, expire_seconds INTEGER DEFAULT 600)");

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO weblogin_tokens (player_name, web_token, created_at, expire_seconds) VALUES (:player, :token, :time, :expire)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expire', $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    success(['player' => $player], "Web登录Token已同步");
}

// ===== 验证Web登录Token（用于前端登录页面API）=====
function apiValidateWebloginToken() {
    $webToken = getParam('web_token');
    if (!$webToken) {
        error('缺少web_token参数');
    }

    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
        $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);

        if (!$row) {
            error('无效的登录Token');
        }

        $createdAt = (int)$row['created_at'];
        $expireSeconds = (int)$row['expire_seconds'];
        if (time() - $createdAt > $expireSeconds) {
            error('登录Token已过期');
        }

        success(['player' => $row['player_name']], 'Token有效');
    } catch (\Throwable $e) {
        error('Token验证失败: ' . $e->getMessage());
    }
}

// ===== 推送玩家登录状态到PHP（Java插件调用）=====
function pushPlayerLoginStatus() {
    // 优先从GET参数读取（Java端使用GET请求）
    if (isset($_GET['secret'])) {
        $secret = $_GET['secret'];
    } elseif (isset($_POST['secret'])) {
        $secret = $_POST['secret'];
    } else {
        $secret = getParam('secret');
    }
    
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    if (isset($_GET['player'])) {
        $player = $_GET['player'];
    } elseif (isset($_POST['player'])) {
        $player = $_POST['player'];
    } else {
        $player = getParam('player');
    }

    if (isset($_GET['web_token'])) {
        $webToken = $_GET['web_token'];
    } elseif (isset($_POST['web_token'])) {
        $webToken = $_POST['web_token'];
    } else {
        $webToken = getParam('web_token');
    }

    $expireSeconds = 600;
    if (isset($_GET['expire_seconds'])) {
        $expireSeconds = (int)$_GET['expire_seconds'];
    } elseif (isset($_POST['expire_seconds'])) {
        $expireSeconds = (int)$_POST['expire_seconds'];
    } else {
        $val = getParam('expire_seconds');
        $expireSeconds = $val ? (int)$val : 600;
    }

    $isOnline = 0;
    if (isset($_GET['online'])) {
        $isOnline = (int)$_GET['online'];
    } elseif (isset($_POST['online'])) {
        $isOnline = (int)$_POST['online'];
    } else {
        $val = getParam('online');
        $isOnline = $val ? (int)$val : 0;
    }

    $isRegistered = 0;
    if (isset($_GET['registered'])) {
        $isRegistered = (int)$_GET['registered'];
    } elseif (isset($_POST['registered'])) {
        $isRegistered = (int)$_POST['registered'];
    } else {
        $val = getParam('registered');
        $isRegistered = $val ? (int)$val : 0;
    }

    // ★ 读取玩家IP地址（Java端push_player_login_status携带）
    $playerIp = '';
    if (isset($_GET['ip'])) {
        $playerIp = $_GET['ip'];
    } elseif (isset($_POST['ip'])) {
        $playerIp = $_POST['ip'];
    } else {
        $playerIp = getParam('ip') ?: '';
    }

    // ★ 读取login_verified参数：Java生成token时标记玩家已通过密码验证
    $loginVerified = 0;
    if (isset($_GET['login_verified'])) {
        $loginVerified = (int)$_GET['login_verified'];
    } elseif (isset($_POST['login_verified'])) {
        $loginVerified = (int)$_POST['login_verified'];
    } else {
        $val = getParam('login_verified');
        $loginVerified = $val ? (int)$val : 0;
    }

    if (!$player || !$webToken) {
        @error_log("[pushPlayerLoginStatus] FAIL: missing player or web_token. player=" . var_export($player, true) . ", web_token=" . var_export($webToken, true));
        error('缺少player或web_token参数');
    }

    @error_log("[pushPlayerLoginStatus] SUCCESS: player=$player, token=" . substr($webToken, 0, 20) . "..., online=$isOnline, registered=$isRegistered, expire=$expireSeconds");

    $db = getDB();
    $now = time();

    // ★ 事务包裹所有写操作，防止 "database is locked"
    $db->exec("BEGIN IMMEDIATE");
    try {
    // 1. 同步weblogin_tokens表（登录页面需要的token）
    $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (player_name TEXT PRIMARY KEY, web_token TEXT NOT NULL, created_at INTEGER NOT NULL, expire_seconds INTEGER DEFAULT 600)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO weblogin_tokens (player_name, web_token, created_at, expire_seconds) VALUES (:player, :token, :time, :expire)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expire', $expireSeconds, SQLITE3_INTEGER);
    $stmt->execute();

    // 1.5 ★ 如果Java标记login_verified=1，写入web_login_verified表（安全锁：Java验证过的玩家PHP直接放行）
    if ($loginVerified) {
        $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
        $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
        $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $verifiedStmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $verifiedStmt->execute();
        @error_log("[pushPlayerLoginStatus] 写入web_login_verified: player=$player (Java已验证)");
    }

    // 2. 如果玩家在游戏中已登录，更新online_players表（只更新已存在的记录，不凭空插入）
    if ($isOnline) {
        $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER DEFAULT 0)");
        // ★ 只更新已存在的玩家，不插入新玩家（在线状态只由syncOnlinePlayers管理）
        $updateStmt = $db->prepare("UPDATE online_players SET login_time = :time WHERE player_name = :player");
        $updateStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $updateStmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $updateStmt->execute();

        // 3. 同时更新web_session_log，标记Web会话活跃
        $db->exec("CREATE TABLE IF NOT EXISTS web_session_log (player_name TEXT PRIMARY KEY, login_time INTEGER NOT NULL, ip_address TEXT DEFAULT '')");
        $sessionStmt = $db->prepare("INSERT OR REPLACE INTO web_session_log (player_name, login_time, ip_address) VALUES (:player, :time, :ip)");
        $sessionStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $sessionStmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $sessionStmt->bindValue(':ip', !empty($playerIp) ? $playerIp : 'plugin_sync', SQLITE3_TEXT);
        $sessionStmt->execute();

        // ★ 如果有IP，更新player_ip_changes表
        if (!empty($playerIp)) {
            try {
                $db->exec("CREATE TABLE IF NOT EXISTS player_ip_changes (
                    player_name TEXT PRIMARY KEY,
                    old_ip TEXT DEFAULT '',
                    new_ip TEXT NOT NULL,
                    changed_at INTEGER NOT NULL,
                    synced_at INTEGER DEFAULT 0
                )");
                $checkStmt = $db->prepare("SELECT new_ip FROM player_ip_changes WHERE player_name = :player");
                $checkStmt->bindValue(':player', $player, SQLITE3_TEXT);
                $checkResult = $checkStmt->execute();
                $existing = $checkResult->fetchArray(SQLITE3_ASSOC);

                if (!$existing || $existing['new_ip'] !== $playerIp) {
                    $oldIp = $existing ? $existing['new_ip'] : '';
                    $ipStmt = $db->prepare("INSERT OR REPLACE INTO player_ip_changes (player_name, old_ip, new_ip, changed_at, synced_at) VALUES (:name, :old, :new, :time, :synced)");
                    $ipStmt->bindValue(':name', $player, SQLITE3_TEXT);
                    $ipStmt->bindValue(':old', $oldIp, SQLITE3_TEXT);
                    $ipStmt->bindValue(':new', $playerIp, SQLITE3_TEXT);
                    $ipStmt->bindValue(':time', $now, SQLITE3_INTEGER);
                    $ipStmt->bindValue(':synced', $now, SQLITE3_INTEGER);
                    $ipStmt->execute();
                    @error_log("[pushPlayerLoginStatus] IP更新: {$player} {$oldIp}→{$playerIp}");
                }
            } catch (\Throwable $e) {
                @error_log("[pushPlayerLoginStatus] IP写入失败 {$player}: " . $e->getMessage());
            }
        }

        // ★ 记录每日登录（游戏内登录也算）
        $today = date('Y-m-d');
        $dlStmt = $db->prepare("INSERT OR REPLACE INTO player_daily_logins (player_name, login_date, first_login, last_login) VALUES (:name, :date, :first, :last)");
        $dlStmt->bindValue(':name', $player, SQLITE3_TEXT);
        $dlStmt->bindValue(':date', $today, SQLITE3_TEXT);
        $dlStmt->bindValue(':first', $now, SQLITE3_INTEGER);
        $dlStmt->bindValue(':last', $now, SQLITE3_INTEGER);
        $dlStmt->execute();

        // 如果已注册，更新users表的last_login_time
        if ($isRegistered) {
            try {
                $db->exec("CREATE TABLE IF NOT EXISTS users (player_name TEXT PRIMARY KEY, last_login_time INTEGER DEFAULT 0)");
                $userStmt = $db->prepare("UPDATE users SET last_login_time = :time WHERE player_name = :player");
                $userStmt->bindValue(':time', $now, SQLITE3_INTEGER);
                $userStmt->bindValue(':player', $player, SQLITE3_TEXT);
                $userStmt->execute();
            } catch (\Throwable $e) {
                // users表可能不存在或其他错误，忽略
            }
        }
    }

    $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[pushPlayerLoginStatus] Transaction failed: " . $e->getMessage());
        // 仍然返回成功，因为token已写入（或即将写入），不让Java端重试
    }

    success(['player' => $player], '玩家登录状态已同步');
}

// ===== 插件轮询Web登录确认（SECRET_KEY认证）=====
// 插件定期调用此接口，获取已通过Web验证的玩家列表，用于自动登录游戏
// ★ 修复：增加时间窗口验证，防止旧记录导致误自动登录
function checkWebLoginConfirmations() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");

    // ★ 不使用BEGIN IMMEDIATE（纯读+更新，减少锁冲突）
    try {
        // ★ 清理超过10分钟的过期确认记录
        $expireTime = time() - 600;
        $db->exec("DELETE FROM web_login_confirmations WHERE confirmed_at < " . $expireTime);

        // 获取所有未消费且未过期的确认记录
        $stmt = $db->prepare("SELECT player_name, confirmed_at FROM web_login_confirmations WHERE consumed = 0 AND confirmed_at >= :expire");
        $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
        $result = $stmt->execute();

        $confirmations = [];
        $playerNames = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $confirmations[] = $row;
            $playerNames[] = $row['player_name'];
        }

        // ★ 为每个玩家查询web_login_verified状态（供Java判断是否信任此确认）
        if (!empty($confirmations)) {
            $verifiedStmt = $db->prepare("SELECT verified_at FROM web_login_verified WHERE player_name = :player AND verified_at >= :expire");
            $verifiedStmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
            foreach ($confirmations as &$conf) {
                $verifiedStmt->bindValue(':player', $conf['player_name'], SQLITE3_TEXT);
                $vResult = $verifiedStmt->execute();
                $vRow = $vResult->fetchArray(SQLITE3_ASSOC);
                $conf['php_verified'] = $vRow ? true : false;
                $conf['php_verified_at'] = $vRow ? (int)$vRow['verified_at'] : 0;
            }
            unset($conf);
        }

        debugLog("checkWebLoginConfirmations: 轮询", ['count' => count($confirmations), 'players' => $playerNames]);

        // 标记为已消费（一次性）
        if (!empty($playerNames)) {
            $db->exec("UPDATE web_login_confirmations SET consumed = 1 WHERE consumed = 0 AND confirmed_at >= " . $expireTime);
            // 清理30分钟前的已消费记录
            $db->exec("DELETE FROM web_login_confirmations WHERE consumed = 1 AND confirmed_at < " . (time() - 1800));
        }
    } catch (\Throwable $e) {
        @error_log("[checkWebLoginConfirmations] error: " . $e->getMessage());
    }

    success($confirmations);
}

// ===== 插件检查Web登录验证记录（SECRET_KEY认证）=====
// 玩家进游戏时调用，检查是否有通过Web验证待自动登录的玩家
// ★ 修复：增加时间窗口验证，防止旧记录导致误自动登录
function checkWebLoginVerified() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $playerName = getParam('player_name');
    debugLog("check_web_login_verified: 插件查询Web验证记录", ['player_name' => $playerName]);

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");

    // ★ 事务包裹所有读写操作
    $db->exec("BEGIN IMMEDIATE");
    try {
    // ★ 清理超过10分钟的过期验证记录（防止残留数据导致误登录）
    $expireTime = time() - 600; // 10分钟
    $db->exec("DELETE FROM web_login_verified WHERE verified_at < " . $expireTime);

    $verified = [];

    if ($playerName) {
        // ★ 检查单个玩家，且验证记录必须在10分钟内
        $stmt = $db->prepare("SELECT player_name, verified_at FROM web_login_verified WHERE player_name = :player AND verified_at >= :expire");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row) {
            $verified[] = $row;
            // 消费验证记录（一次性）
            $delStmt = $db->prepare("DELETE FROM web_login_verified WHERE player_name = :player");
            $delStmt->bindValue(':player', $playerName, SQLITE3_TEXT);
            $delStmt->execute();
            debugLog("check_web_login_verified: 找到验证记录并消费", ['player_name' => $playerName, 'verified_at' => $row['verified_at']]);
        } else {
            debugLog("check_web_login_verified: 未找到验证记录", ['player_name' => $playerName]);
        }
    } else {
        // 批量获取所有验证记录（用于轮询）
        $stmt = $db->prepare("SELECT player_name, verified_at FROM web_login_verified WHERE verified_at >= :expire");
        $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
        $result = $stmt->execute();
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $verified[] = $row;
        }
        // 全部消费
        if (!empty($verified)) {
            $db->exec("DELETE FROM web_login_verified WHERE verified_at >= " . $expireTime);
            debugLog("check_web_login_verified: 批量消费验证记录", ['count' => count($verified), 'players' => array_column($verified, 'player_name')]);
        } else {
            debugLog("check_web_login_verified: 无验证记录", []);
        }
    }

    $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
    }

    success($verified);
}

// ===== Web端提交密码登录请求 =====
// 玩家在Web端输入用户名+密码，PHP存储请求，等待插件验证
function webLoginRequest() {
    $player = getParam('player');
    $password = getParam('password');

    if (!$player || !$password) {
        error('缺少player或password参数');
    }

    // 简单验证玩家名格式（1-16位字母数字下划线）
    if (!preg_match('/^[a-zA-Z0-9_]{1,16}$/', $player)) {
        error('玩家名格式不正确（1-16位字母数字下划线）');
    }

    $db = getDB();

    // 检查玩家是否已注册
    if (!isPlayerRegistered($player)) {
        error('玩家未注册，请先在游戏中注册');
    }

    $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password TEXT NOT NULL,
        request_time INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        result_time INTEGER DEFAULT 0
    )");

    // ★ 事务包裹所有写操作
    $db->exec("BEGIN IMMEDIATE");
    try {
    // 清理10分钟前的过期请求
    $db->exec("DELETE FROM web_login_requests WHERE request_time < " . (time() - 600));

    // 清理该玩家超过2分钟的旧pending请求（防止永久阻塞）
    $cleanupStmt = $db->prepare("DELETE FROM web_login_requests WHERE player_name = :player AND status = 'pending' AND request_time < :cutoff");
    $cleanupStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $cleanupStmt->bindValue(':cutoff', time() - 120, SQLITE3_INTEGER);
    $cleanupStmt->execute();

    // 检查是否有最近2分钟内的进行中请求
    $checkStmt2 = $db->prepare("SELECT id FROM web_login_requests WHERE player_name = :player AND status = 'pending' AND request_time > :cutoff2");
    $checkStmt2->bindValue(':player', $player, SQLITE3_TEXT);
    $checkStmt2->bindValue(':cutoff2', time() - 120, SQLITE3_INTEGER);
    $checkResult2 = $checkStmt2->execute();
    if ($checkResult2->fetchArray()) {
        $db->exec("ROLLBACK");
        error('已有进行中的登录请求，请稍后再试');
    }

    $stmt = $db->prepare("INSERT INTO web_login_requests (player_name, password, request_time, status) VALUES (:player, :pwd, :time, 'pending')");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':pwd', $password, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    $requestId = $db->lastInsertRowID();

    $db->exec("COMMIT");
    debugLog("webLoginRequest: ✓ 新请求已创建", ['player' => $player, 'request_id' => $requestId, 'pwd_len' => strlen($password)]);
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[webLoginRequest] Transaction failed: " . $e->getMessage());
        error('提交失败: ' . $e->getMessage());
    }

    debugLog("webLoginRequest: 新请求", ['player' => $player, 'request_id' => $requestId]);

    success([
        'request_id' => $requestId,
        'player' => $player,
        'message' => '登录请求已提交，等待游戏服务器验证...'
    ], "请求已提交");
}

// ===== 取消/清理待处理的登录请求 =====
function cancelWebLogin() {
    $player = getParam('player');
    if (!$player) error('缺少player参数');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password TEXT NOT NULL,
        request_time INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        result_time INTEGER DEFAULT 0
    )");

    // 将该玩家所有pending请求标记为cancelled
    $stmt = $db->prepare("UPDATE web_login_requests SET status = 'cancelled', result = '{\"success\":false,\"message\":\"用户取消\"}', result_time = :time WHERE player_name = :player AND status = 'pending'");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    debugLog("cancelWebLogin: 清理了玩家 $player 的pending请求");
    success([], "已清理");
}

// ===== Web端轮询密码登录结果 =====
function checkWebLoginResult() {
    $player = getParam('player');
    $requestId = getParam('request_id');

    if (!$player || !$requestId) {
        error('缺少player或request_id参数');
    }

    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM web_login_requests WHERE id = :id AND player_name = :player");
        $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);

        if (!$row) {
            // 请求不存在，可能已被清理
            debugLog("checkWebLoginResult: 请求不存在", ['player' => $player, 'request_id' => $requestId]);
            success([
                'status' => 'pending',
                'message' => '请求已过期，请刷新页面重试'
            ]);
            return;
        }

        debugLog("checkWebLoginResult: 查询结果", ['player' => $player, 'request_id' => $requestId, 'status' => $row['status']]);

        if ($row['status'] === 'pending') {
            // 还在等待
            success([
                'status' => 'pending',
                'message' => '等待游戏服务器验证...'
            ]);
        } elseif ($row['status'] === 'success') {
            // 验证成功 → 写入web_login_verified（允许后续player.php直接放行）
            $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
            $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
            $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
            $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
            $verifiedStmt->execute();
            debugLog("checkWebLoginResult: 写入web_login_verified", ['player' => $player]);

            // ★ 生成weblogin_token返回给前端，让player.php可以继续使用
            $newToken = bin2hex(random_bytes(32));
            $db->exec("CREATE TABLE IF NOT EXISTS weblogin_tokens (player_name TEXT PRIMARY KEY, web_token TEXT NOT NULL, created_at INTEGER NOT NULL, expire_seconds INTEGER DEFAULT 600)");
            $tokenStmt = $db->prepare("INSERT OR REPLACE INTO weblogin_tokens (player_name, web_token, created_at, expire_seconds) VALUES (:player, :token, :time, :expire)");
            $tokenStmt->bindValue(':player', $player, SQLITE3_TEXT);
            $tokenStmt->bindValue(':token', $newToken, SQLITE3_TEXT);
            $tokenStmt->bindValue(':time', time(), SQLITE3_INTEGER);
            $tokenStmt->bindValue(':expire', 600, SQLITE3_INTEGER);
            $tokenStmt->execute();
            debugLog("checkWebLoginResult: 生成weblogin_token", ['player' => $player]);

            $resultData = json_decode($row['result'], true);
            success([
                'status' => 'success',
                'player' => $player,
                'token' => $newToken,
                'user_data' => $resultData ?? ['player_name' => $player],
                'message' => '登录成功'
            ]);
        } else {
            // 验证失败
            $resultData = json_decode($row['result'], true);
            success([
                'status' => 'failed',
                'message' => $resultData['error'] ?? '密码错误'
            ]);
        }
    } catch (\Throwable $e) {
        error('数据库查询失败: ' . $e->getMessage());
    }
}

// ===== 插件轮询待处理的密码登录请求（SECRET_KEY认证）=====
function checkPendingWebLogins() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_requests (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        password TEXT NOT NULL,
        request_time INTEGER NOT NULL,
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        result_time INTEGER DEFAULT 0
    )");

    // ★ 只读查询不需要BEGIN IMMEDIATE（避免与其它并发请求抢锁导致500）
    // 清理10分钟前的过期请求（DELETE即使失败也不影响读取pending）
    try {
        $db->exec("DELETE FROM web_login_requests WHERE request_time < " . (time() - 600));
    } catch (\Throwable $e) {
        @error_log("[checkPendingWebLogins] cleanup error: " . $e->getMessage());
    }

    // 获取所有pending请求
    $stmt = $db->prepare("SELECT id, player_name, password FROM web_login_requests WHERE status = 'pending'");
    $result = $stmt->execute();

    $requests = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $requests[] = $row;
    }

    debugLog("checkPendingWebLogins: 轮询", ['count' => count($requests), 'players' => array_column($requests, 'player_name')]);
    success($requests);
}

// ===== 插件写回密码验证结果（SECRET_KEY认证）=====
function completeWebLoginRequest() {
    // 优先从GET参数读取（Java端使用GET请求）
    $secret = isset($_GET['secret']) ? $_GET['secret'] : (isset($_POST['secret']) ? $_POST['secret'] : getParam('secret'));
    
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $requestId = isset($_GET['request_id']) ? $_GET['request_id'] : (isset($_POST['request_id']) ? $_POST['request_id'] : getParam('request_id'));
    $player = isset($_GET['player']) ? $_GET['player'] : (isset($_POST['player']) ? $_POST['player'] : getParam('player'));
    $result = isset($_GET['result']) ? $_GET['result'] : (isset($_POST['result']) ? $_POST['result'] : getParam('result'));

    debugLog("completeWebLoginRequest: 收到结果", ['request_id' => $requestId, 'player' => $player, 'result_raw' => $result]);

    if (!$requestId || !$player) {
        error('缺少request_id或player参数');
    }

    $db = getDB();
    // ★ 事务包裹所有写操作，防止database is locked
    $db->exec("BEGIN IMMEDIATE");
    try {
        $stmt = $db->prepare("SELECT id FROM web_login_requests WHERE id = :id AND player_name = :player");
        $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray();

        if (!$row) {
            $db->exec("ROLLBACK");
            debugLog("completeWebLoginRequest: 请求不存在", ['request_id' => $requestId, 'player' => $player]);
            error('请求不存在');
        }

        // 判断验证结果
        $resultData = json_decode($result, true);
        $status = isset($resultData['success']) && $resultData['success'] ? 'success' : 'failed';

        $updateStmt = $db->prepare("UPDATE web_login_requests SET status = :status, result = :result, result_time = :time WHERE id = :id");
        $updateStmt->bindValue(':status', $status, SQLITE3_TEXT);
        $updateStmt->bindValue(':result', $result, SQLITE3_TEXT);
        $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $updateStmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
        $updateStmt->execute();

        // 如果验证成功，写入web_login_confirmations和web_login_verified
        if ($status === 'success') {
            $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
            $confirmStmt = $db->prepare("INSERT OR REPLACE INTO web_login_confirmations (player_name, confirmed_at, consumed) VALUES (:player, :time, 0)");
            $confirmStmt->bindValue(':player', $player, SQLITE3_TEXT);
            $confirmStmt->bindValue(':time', time(), SQLITE3_INTEGER);
            $confirmStmt->execute();

            // ★ 记录Web登录验证成功，供玩家进游戏时自动登录（持久化）
            $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
            $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
            $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
            $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
            $verifiedStmt->execute();

            // ★ 记录每日登录（Web登录也算）
            $today = date('Y-m-d');
            $now = time();
            $dlStmt = $db->prepare("INSERT OR REPLACE INTO player_daily_logins (player_name, login_date, first_login, last_login) VALUES (:name, :date, :first, :last)");
            $dlStmt->bindValue(':name', $player, SQLITE3_TEXT);
            $dlStmt->bindValue(':date', $today, SQLITE3_TEXT);
            $dlStmt->bindValue(':first', $now, SQLITE3_INTEGER);
            $dlStmt->bindValue(':last', $now, SQLITE3_INTEGER);
            $dlStmt->execute();
        }

        $db->exec("COMMIT");
        debugLog("completeWebLoginRequest: ✓ 写入成功", ['request_id' => $requestId, 'player' => $player, 'status' => $status, 'wrote_confirmations' => ($status === 'success')]);
        success(['status' => $status], "结果已写入");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        debugLog("completeWebLoginRequest: 数据库异常", ['error' => $e->getMessage()]);
        error('数据库错误: ' . $e->getMessage());
    }
}

// ===== 插件推送玩家密码凭证（SECRET_KEY认证）=====
function pushWebCredentials() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $players = getParam('players');
    if (!$players || !is_array($players)) {
        error('缺少players数组');
    }

    $db = getDB();
    $count = 0;

    foreach ($players as $p) {
        $name = $p['player_name'] ?? null;
        $hash = $p['password_hash'] ?? null;
        $salt = $p['salt'] ?? null;
        $tempHash = $p['temp_password_hash'] ?? null;
        $tempExpire = $p['temp_pw_expire'] ?? 0;

        if (!$name || !$hash || !$salt) continue;

        storeWebLoginCredentials($name, $hash, $salt);

        // ★ 如果有临时密码，单独存储
        if (!empty($tempHash) && !empty($tempExpire)) {
            storeTempPassword($name, $tempHash, $tempExpire);
        }

        $count++;
    }

    success(['pushed' => $count], "推送了{$count}个玩家密码凭证");
}

// ===== Web端验证登录密码 =====
// ★★★ 安全加固：此函数已废弃，禁止PHP自验证密码 ★★★
// 所有密码验证必须通过 web_login_request → Java插件本地验证 → complete_web_login_request 回调
// PHP端自验证密码会导致：假密码通过验证 + 游戏内未登录 + 快速重连绕过验证
function verifyWebPassword() {
    error('安全限制: PHP禁止直接验证密码，必须通过游戏服务器验证。请使用web_login_request接口');
}

// ===== Web端安全验证入口（双保险）=====
function webAccessCheck() {
    $webToken = getParam('web_token');
    $accessAction = getParam('access_action', 'view');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$webToken) {
        @error_log("[web_access_check] FAIL: no web_token provided");
        error('缺少web_token');
    }

    // ★ Debug: 记录完整的token信息
    @error_log("[web_access_check] Token: " . substr($webToken, 0, 20) . "... (len=" . strlen($webToken) . ")");
    @error_log("[web_access_check] Action: $accessAction, IP: $ipAddress");

    // ★ 检查weblogin_tokens表是否存在及数据
    $db = getDB();
    try {
        $countStmt = $db->query("SELECT COUNT(*) as cnt FROM weblogin_tokens");
        $countRow = $countStmt->fetchArray(SQLITE3_ASSOC);
        @error_log("[web_access_check] weblogin_tokens table has " . ($countRow['cnt'] ?? 0) . " records");

        // 列出所有token（前20个字符）
        $listStmt = $db->query("SELECT player_name, substr(web_token, 1, 20) as token_prefix, created_at, expire_seconds FROM weblogin_tokens ORDER BY created_at DESC LIMIT 10");
        while ($row = $listStmt->fetchArray(SQLITE3_ASSOC)) {
            $age = time() - $row['created_at'];
            $expire = $row['expire_seconds'];
            $isExpired = $age > $expire ? 'EXPIRED' : 'valid';
            @error_log("[web_access_check] DB token: player=" . $row['player_name'] . ", token=" . $row['token_prefix'] . "..., age={$age}s, expire={$expire}s, status={$isExpired}");
        }
    } catch (\Throwable $e) {
        @error_log("[web_access_check] DB query error: " . $e->getMessage());
    }

    $result = validateWebAccess($webToken, $accessAction, $password, $ipAddress);

    debugLog("web_access_check: 验证结果", [
        'ok' => $result['ok'],
        'mode' => $result['mode'] ?? 'unknown',
        'player' => $result['player'] ?? 'unknown',
        'registered' => $result['registered'] ?? false,
        'online' => $result['online'] ?? false,
        'message' => $result['message'] ?? ''
    ]);

    if ($result['ok']) {
        // ★ 修复：移除不存在的 logWebSession() 调用
        // validateWebAccess 内部已通过 recordWebSession() 记录了 session，这里不需要重复记录
        // 旧代码调用 logWebSession() 导致 PHP Fatal Error → 返回 HTML 错误页 → player.php 解析 JSON 失败 → 游客模式

        success([
            'player' => $result['player'] ?? '',
            'mode' => $result['mode'] ?? '',
            'session' => $result['session'] ?? '',
            'online' => $result['online'] ?? false,
            'registered' => $result['registered'] ?? false
        ], $result['message']);
    } else {
        if ($result['mode'] === 'need_password') {
            // 需要密码但未提供
            jsonResponse([
                'success' => false,
                'need_password' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } elseif ($result['mode'] === 'need_game_login') {
            // 游戏里未登录
            jsonResponse([
                'success' => false,
                'need_game_login' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } elseif ($result['mode'] === 'need_register') {
            // 玩家未注册
            jsonResponse([
                'success' => false,
                'need_register' => true,
                'player' => $result['player'] ?? '',
                'online' => $result['online'] ?? false,
                'registered' => $result['registered'] ?? false,
                'message' => $result['message']
            ], 401);
        } else {
            error($result['message'], 401);
        }
    }
}

// ===== 轻量级检查：是否有待处理的交易（Java高频轮询用）=====
function checkPendingTransactions() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $hasTable = false;
    try {
        $stmt = $db->query("SELECT name FROM sqlite_master WHERE type='table' AND name='web_transactions'");
        $hasTable = ($stmt && $stmt->fetchArray());
    } catch (\Throwable $e) {
        @error_log("[checkPendingTransactions] table check error: " . $e->getMessage());
    }

    if (!$hasTable) {
        success(['pending' => 0], 'no table');
        return;
    }

    $count = 0;
    try {
        $stmt = $db->query("SELECT COUNT(*) as cnt FROM web_transactions WHERE status = 'pending'");
        if ($stmt) {
            $row = $stmt->fetchArray(SQLITE3_ASSOC);
            $count = (int)($row['cnt'] ?? 0);
        }
    } catch (\Throwable $e) {
        @error_log("[checkPendingTransactions] count error: " . $e->getMessage());
    }

    success(['pending' => $count], $count > 0 ? '有交易待处理' : '无待处理');
}

// ===== 插件拉取待处理的交易 =====
function pullPendingTransactions() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT, detail TEXT, status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");

    // 检查processed_at字段是否存在（旧库可能缺失）
    $hasProcessedAt = false;
    $stmt = $db->query("PRAGMA table_info(web_transactions)");
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        if ($row['name'] === 'processed_at') { $hasProcessedAt = true; break; }
    }
    if (!$hasProcessedAt) {
        $db->exec("ALTER TABLE web_transactions ADD COLUMN processed_at INTEGER");
    }

    // 获取所有pending的交易 + 标记为processing（原子操作，避免database is locked）
    $transactions = [];
    $ids = [];
    try {
        $db->exec('BEGIN IMMEDIATE');
        
        // 恢复超时的processing交易（超过60秒未确认的恢复为pending）
        if ($hasProcessedAt) {
            $db->exec("UPDATE web_transactions SET status = 'pending' WHERE status = 'processing' AND processed_at < " . (time() - 60));
        }
        
        $stmt = $db->prepare("SELECT id, player_name, type, amount, reason, detail FROM web_transactions WHERE status = 'pending' ORDER BY created_at ASC");
        $result = $stmt->execute();
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $transactions[] = $row;
            $ids[] = (int)$row['id'];
        }

        // 立即标记为processing（防止5秒后重复拉取）
        if (!empty($ids)) {
            $idList = implode(',', $ids);
            $db->exec("UPDATE web_transactions SET status = 'processing', processed_at = " . time() . " WHERE id IN ($idList)");
        }
        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
        @error_log("[pullPendingTransactions] 事务失败: " . $e->getMessage());
        // 降级：不做标记，直接返回（下一轮会重新拉取）
    }

    success(['transactions' => $transactions, 'count' => count($transactions)]);
}

// ===== 插件确认交易已处理 =====
function confirmTransaction() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $txId = getParam('tx_id');
    if (!$txId) error('缺少tx_id');

    $db = getDB();
    // 检查processed_at字段是否存在
    $hasProcessedAt = false;
    $stmt = $db->query("PRAGMA table_info(web_transactions)");
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        if ($row['name'] === 'processed_at') { $hasProcessedAt = true; break; }
    }

    try {
        $db->exec('BEGIN IMMEDIATE');
        if ($hasProcessedAt) {
            $stmt = $db->prepare("UPDATE web_transactions SET status = 'processed', processed_at = :time WHERE id = :id AND status IN ('pending', 'processing')");
            $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        } else {
            $stmt = $db->prepare("UPDATE web_transactions SET status = 'processed' WHERE id = :id AND status IN ('pending', 'processing')");
        }
        $stmt->bindValue(':id', (int)$txId, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
        @error_log("[confirmTransaction] 失败: " . $e->getMessage());
    }

    success(['tx_id' => $txId], '交易已确认');
}

// ===== 插件拉取Web端修改的商品库存 =====
function pullShopStock() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    // ★ admin_stock非NULL时优先返回（管理员手动改的库存）
    // 防止Java全量同步覆盖管理员的修改
    $stmt = $db->prepare("SELECT id, stock, last_sync, admin_stock FROM shop_items");
    $result = $stmt->execute();
    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        // ★ 如果admin_stock有值，用admin_stock替代stock返回给Java
        if ($row['admin_stock'] !== null && $row['admin_stock'] !== '') {
            $row['stock'] = (int)$row['admin_stock'];
            $row['admin_stock_override'] = true;
        }
        unset($row['admin_stock']); // 不暴露内部字段
        $items[] = $row;
    }

    // ★ 成功获取数据后立即清除admin_stock标记（同一次请求完成，消除竞态）
    // 防止Java端clearAdminStock()单独请求失败导致admin_stock永远非NULL
    $db->exec("UPDATE shop_items SET admin_stock = NULL WHERE admin_stock IS NOT NULL");

    success(['items' => $items, 'count' => count($items)]);
}

// ===== 插件确认已应用管理员库存改动 =====
function clearAdminStock() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $db->exec("UPDATE shop_items SET admin_stock = NULL WHERE admin_stock IS NOT NULL");
    success([], '已清除管理员库存标记');
}

// ===== 高频轮询：检测库存是否有改动（轻量级接口） =====
function checkShopStockChanged() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT MAX(last_modified) as max_lm, SUM(CASE WHEN admin_stock IS NOT NULL THEN 1 ELSE 0 END) as admin_count FROM shop_items");
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        $maxModified = $row['max_lm'] ? (int)$row['max_lm'] : 0;
        $adminCount = $row['admin_count'] ? (int)$row['admin_count'] : 0;
    } catch (\Throwable $e) {
        @error_log("[checkShopStockChanged] error: " . $e->getMessage());
        $maxModified = 0;
        $adminCount = 0;
    }

    // ★ 如果admin_stock非NULL（管理员改了库存但Java还没拉取），强制返回changed=true
    $clientLastModified = (int)getParam('last_modified', 0);
    $changed = ($maxModified > $clientLastModified) || ($adminCount > 0);

    success([
        'changed' => $changed,
        'last_modified' => $maxModified,
        'admin_pending' => $adminCount
    ]);
}

// ===== 通知插件立即同步数据 =====
function notifySync() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    // ★ 获取最新交易ID
    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT, detail TEXT, status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");
    $txId = getParam('tx_id', 0);
    
    // 写入同步通知文件（包含交易ID）
    $notifyFile = __DIR__ . '/../../sync_notify.txt';
    $notifyData = json_encode(['time' => time(), 'tx_id' => (int)$txId]);
    @file_put_contents($notifyFile, $notifyData, LOCK_EX);

    // ★ 不再使用HTTP回调，Java的transactionPolling每5秒轮询check_pending_transactions
    @error_log('[notifySync] 收到同步通知，tx_id=' . $txId . '，Java轮询会自动拉取');

    success(['tx_id' => (int)$txId], '已通知插件立即同步交易数据');
}

// ===== 客户端请求立即同步（玩家购物/CDK后调用）=====
function requestImmediateSync() {
    // ★ 不需要secret验证，前端玩家直接调用
    $player = getParam('player');
    if (!$player) error('缺少player参数');

    $db = getDB();
    // 记录同步请求（Java插件会轮询这个表）
    $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    success(['sync_requested' => true], '已请求立即同步');
}

// ===== 补发pending交易（Java重拉未确认交易）=====
function resendPendingTransactions() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT, detail TEXT, status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");

    // ★ 事务包装防止 "database is locked"
    $db->exec("BEGIN IMMEDIATE");
    try {
        // 恢复超时processing交易
        $db->exec("UPDATE web_transactions SET status = 'pending' WHERE status = 'processing' AND processed_at < " . (time() - 120));

        $stmt = $db->prepare("SELECT id, player_name, type, amount, reason, detail FROM web_transactions WHERE status IN ('pending', 'processing') ORDER BY created_at ASC");
        $result = $stmt->execute();
        $transactions = [];
        $ids = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $transactions[] = $row;
            $ids[] = (int)$row['id'];
        }

        // 重新标记为processing
        if (!empty($ids)) {
            $idList = implode(',', $ids);
            $db->exec("UPDATE web_transactions SET status = 'processing', processed_at = " . time() . " WHERE id IN ($idList)");
        }

        $db->exec("COMMIT");
    } catch (\Throwable $e) {
        try { $db->exec("ROLLBACK"); } catch (\Throwable $e2) {}
        @error_log("[resendPendingTransactions] Transaction failed: " . $e->getMessage());
        error('数据库操作失败: ' . $e->getMessage());
    }

    success(['transactions' => $transactions, 'count' => count($transactions), 'resend' => true], '已补发' . count($transactions) . '笔pending交易');
}

// ===== 邮箱脱敏工具 =====
// 格式: 123@456.com → xx@456.com（只显示后缀）
function maskEmail($email) {
    if (empty($email)) return '';
    $parts = explode('@', $email);
    if (count($parts) !== 2) return '**@' . ($parts[0] ?? '*');
    $domain = $parts[1];
    // 只显示域名，前面用xx代替
    return 'xx@' . $domain;
}

// ===== 发送邮箱验证码 =====
function sendEmailCode() {
    $player = getParam('player');

    if (!$player) error('缺少player参数');

    // ★ 安全检查：玩家必须已注册
    if (!isPlayerRegistered($player)) {
        error('玩家未在游戏中注册，请先在游戏中使用 /register 注册账号');
    }

    // ★ 从数据库读取玩家绑定的邮箱（唯一可信来源）
    $db = getDB();
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row || !$row['email'] || $row['email'] === '') {
        error('该玩家尚未绑定邮箱，请先在游戏中完成邮箱绑定');
    }

    // ★ 邮箱只读：不允许前端传入，直接取数据库的值
    $email = $row['email'];
    $maskedEmail = maskEmail($email);

    // 使用config.php中的SMTP配置
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    if (empty($smtpHost) || empty($smtpUser) || empty($smtpPass)) {
        error('SMTP配置不完整');
    }

    // 生成6位验证码
    $code = str_pad(mt_rand(0, 999999), 6, '0', STR_PAD_LEFT);
    $expireTime = time() + 300; // 5分钟有效

    // 存储验证码到数据库
    $db->exec("CREATE TABLE IF NOT EXISTS email_codes (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, email TEXT NOT NULL, code TEXT NOT NULL, expire_at INTEGER NOT NULL, used INTEGER DEFAULT 0, created_at INTEGER NOT NULL)");

    // 清理过期验证码
    $db->exec("DELETE FROM email_codes WHERE expire_at < " . time());

    // 检查是否有未过期的验证码
    $checkStmt = $db->prepare("SELECT id FROM email_codes WHERE player_name = :player AND email = :email AND expire_at > :time AND used = 0");
    $checkStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $checkStmt->bindValue(':email', $email, SQLITE3_TEXT);
    $checkStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $checkResult = $checkStmt->execute();
    if ($checkResult->fetchArray()) {
        error('验证码已发送至 ' . $maskedEmail . '，请查看邮箱（5分钟内有效）');
    }

    // 插入新验证码
    $stmt = $db->prepare("INSERT INTO email_codes (player_name, email, code, expire_at, created_at) VALUES (:player, :email, :code, :expire, :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    try {
        // 构建邮件内容
        $subject = "【" . $senderName . "】登录验证码";
        $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
                . "<h2 style='color:#333;text-align:center'>🔐 登录验证码</h2>"
                . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
                . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b> 的验证码：</p>"
                . "<p style='font-size:32px;font-weight:bold;color:#4CAF50;letter-spacing:8px;margin:0'>" . $code . "</p>"
                . "</div>"
                . "<p style='font-size:12px;color:#999;text-align:center'>验证码5分钟内有效，请勿泄露给他人</p>"
                . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
                . "</div>";

        $headers = "MIME-Version: 1.0\r\n"
                . "Content-type: text/html; charset=UTF-8\r\n"
                . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

        // 使用PHPMailer或自定义SMTP发送
        $sent = smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);

        if ($sent) {
            success(['player' => $player, 'masked_email' => $maskedEmail], '验证码已发送至 ' . $maskedEmail);
        } else {
            // SMTP失败，返回验证码供调试
            success(['player' => $player, 'masked_email' => $maskedEmail, 'code' => $code], '邮件发送中（验证码：' . $code . '）');
        }
    } catch (\Throwable $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 验证邮箱验证码 =====
function verifyEmailCode() {
    $player = getParam('player');
    $code = getParam('code');
    $webToken = getParam('web_token');

    if (!$player) error('缺少player参数');
    if (!$code) error('缺少验证码');
    if (!$webToken) error('缺少web_token');

    // ★ 安全检查：玩家必须在游戏中注册过
    if (!isPlayerRegistered($player)) {
        error('玩家未在游戏中注册，请先在游戏中使用 /register 注册账号');
    }

    // ★ 验证token（weblogin token或普通token都可以）
    $tokenInfo = validateTokenSilent($webToken);
    if (!$tokenInfo) error('无效的登录token');
    
    // 验证玩家名是否匹配
    if ($tokenInfo['player'] !== $player) error('玩家名不匹配');

    // ★ 从数据库读取玩家绑定的邮箱（唯一可信来源）
    $db = getDB();
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row || !$row['email'] || $row['email'] === '') {
        error('该玩家尚未绑定邮箱');
    }

    $email = $row['email'];
    
    // 查找未使用且未过期的验证码（只验证该玩家自己的邮箱记录）
    $stmt = $db->prepare("SELECT * FROM email_codes WHERE player_name = :player AND email = :email AND code = :code AND expire_at > :time AND used = 0");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':email', $email, SQLITE3_TEXT);
    $stmt->bindValue(':code', $code, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('验证码无效或已过期');
    }

    // 标记验证码已使用
    $updateStmt = $db->prepare("UPDATE email_codes SET used = 1 WHERE id = :id");
    $updateStmt->bindValue(':id', $row['id'], SQLITE3_INTEGER);
    $updateStmt->execute();

    // 验证码正确，创建登录确认记录
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_confirmations (player_name TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL, consumed INTEGER DEFAULT 0)");
    $confirmStmt = $db->prepare("INSERT OR REPLACE INTO web_login_confirmations (player_name, confirmed_at, consumed) VALUES (:player, :time, 0)");
    $confirmStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $confirmStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $confirmStmt->execute();

    // ★ 记录Web登录验证成功，供玩家进游戏时自动登录（持久化，不随轮询消费而消失）
    $db->exec("CREATE TABLE IF NOT EXISTS web_login_verified (player_name TEXT PRIMARY KEY, verified_at INTEGER NOT NULL)");
    $verifiedStmt = $db->prepare("INSERT OR REPLACE INTO web_login_verified (player_name, verified_at) VALUES (:player, :time)");
    $verifiedStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $verifiedStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $verifiedStmt->execute();

    // 记录会话
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';
    $sessionToken = recordWebSession($player, $ipAddress);

    success([
        'player' => $player,
        'session' => $sessionToken,
        'mode' => 'email_verified'
    ], '邮箱验证成功');
}

// ===== 获取玩家绑定的邮箱（脱敏） =====
function getPlayerEmail() {
    $player = getParam('player');
    if (!$player) error('缺少player参数');

    // 检查玩家是否注册
    if (!isPlayerRegistered($player)) {
        error('玩家未注册');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row || !$row['email'] || $row['email'] === '') {
        success(['email' => '', 'masked_email' => ''], '未绑定邮箱');
    }

    $email = $row['email'];
    success([
        'email' => $email,
        'masked_email' => maskEmail($email)
    ], '获取成功');
}

// ===== 修复库存数据（stock=0→-1） =====
function fixStock() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    // 将所有stock=0改为-1（游戏端0表示无限供应）
    $before = $db->querySingle("SELECT COUNT(*) FROM shop_items WHERE stock = 0");
    $db->exec("UPDATE shop_items SET stock = -1 WHERE stock = 0");
    $after = $db->querySingle("SELECT COUNT(*) FROM shop_items WHERE stock = 0");

    success([
        'before_zero' => (int)$before,
        'after_zero' => (int)$after,
        'fixed' => (int)$before - (int)$after
    ], "修复了{$before}个商品的库存");
}

// ===== 发送重置密码链接 =====
function sendResetPasswordLink() {
    $player = getParam('player');

    if (!$player) error('缺少player参数');

    $db = getDB();

    // 检查玩家是否注册
    if (!isPlayerRegistered($player)) {
        error('玩家未注册');
    }

    // 从数据库获取玩家邮箱
    $stmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row || !$row['email'] || $row['email'] === '') {
        // 玩家未绑定邮箱，创建待管理员审核的记录
        $db->exec("CREATE TABLE IF NOT EXISTS password_reset_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            requested_email TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            admin_approved INTEGER DEFAULT 0,
            admin_email TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            processed_at INTEGER DEFAULT 0
        )");
        
        $stmt = $db->prepare("INSERT INTO password_reset_requests (player_name, status, created_at) VALUES (:player, 'pending', :time)");
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
        
        error('该玩家未绑定邮箱，需要管理员审核。管理员将在后台验明身份后处理您的请求。');
    }
    $email = $row['email'];

    // 生成重置token
    $resetToken = bin2hex(random_bytes(32));
    $expireTime = time() + 1800; // 30分钟有效

    // 存储重置token
    $db->exec("CREATE TABLE IF NOT EXISTS password_reset_tokens (token TEXT PRIMARY KEY, player_name TEXT NOT NULL, expire_at INTEGER NOT NULL)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO password_reset_tokens (token, player_name, expire_at) VALUES (:token, :player, :expire)");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->execute();

    // 发送重置邮件
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    $subject = "【" . $senderName . "】密码重置";
    $resetUrl = WEBSUB_DIR . 'reset_password.php?token=' . $resetToken;
    $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
            . "<h2 style='color:#333;text-align:center'>🔑 密码重置</h2>"
            . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
            . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b>，您请求了密码重置</p>"
            . "<p style='font-size:13px;color:#666;margin-bottom:20px'>点击下方按钮重置密码（30分钟内有效）</p>"
            . "<a href='" . $resetUrl . "' style='display:inline-block;padding:12px 24px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:6px;font-weight:bold'>重置密码</a>"
            . "</div>"
            . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
            . "</div>";

    $headers = "MIME-Version: 1.0\r\n"
            . "Content-type: text/html; charset=UTF-8\r\n"
            . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

    try {
        smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);
        success(['player' => $player, 'email' => $email], '重置链接已发送到邮箱');
    } catch (\Throwable $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 重置密码 =====
function resetPassword() {
    $resetToken = getParam('reset_token');
    $newPassword = getParam('new_password');

    if (!$resetToken) error('缺少reset_token参数');
    if (!$newPassword) error('缺少new_password参数');
    if (strlen($newPassword) < 6) error('密码长度至少6位');

    $db = getDB();

    // 验证重置token
    $stmt = $db->prepare("SELECT * FROM password_reset_tokens WHERE token = :token");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('无效的重置链接');

    if (time() > $row['expire_at']) {
        error('重置链接已过期');
    }

    $player = $row['player_name'];

    // 生成新的密码凭证
    $saltBytes = random_bytes(16);
    $salt = base64_encode($saltBytes);
    $passwordHash = base64_encode(hash('sha256', $saltBytes . $newPassword, true));

    // 更新密码凭证
    storeWebLoginCredentials($player, $passwordHash, $salt);

    // 删除重置token
    $stmt = $db->prepare("DELETE FROM password_reset_tokens WHERE token = :token");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->execute();

    success(['player' => $player], '密码重置成功');
}

// ===== 管理员列出用户 =====
function adminListUsers() {
    $token = getParam('token');
    if (!$token) error('缺少token参数');

    $tokenInfo = validateToken($token);
    if (!$tokenInfo || ($tokenInfo['purpose'] !== 'admin' && $tokenInfo['purpose'] !== 'all')) {
        error('需要管理员权限');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM users ORDER BY register_time DESC");
    $result = $stmt->execute();

    $users = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $users[] = $row;
    }

    success($users);
}

// ===== 管理员发送重置链接 =====
function adminSendResetLink() {
    $token = getParam('token');
    $player = getParam('player');
    $email = getParam('email');

    if (!$player) error('缺少player参数');
    if (!$email) error('缺少email参数');

    // 支持两种认证方式：token认证 或 管理员会话认证
    $authenticated = false;
    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $authenticated = true;
        }
    }
    // 尝试管理员认证（通过admin_sessions表）
    if (!$authenticated) {
        requireAdminSession();
        $authenticated = true;
    }

    if (!$authenticated) {
        error('需要管理员权限');
    }

    $db = getDB();

    // 检查玩家是否存在
    if (!isPlayerRegistered($player)) {
        error('玩家不存在');
    }

    // 绑定邮箱（如果玩家没有邮箱）
    $emailStmt = $db->prepare("SELECT email FROM users WHERE player_name = :player");
    $emailStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $emailResult = $emailStmt->execute();
    $emailRow = $emailResult->fetchArray(SQLITE3_ASSOC);
    
    if (!$emailRow || !$emailRow['email'] || $emailRow['email'] === '') {
        $updateEmailStmt = $db->prepare("UPDATE users SET email = :email WHERE player_name = :player");
        $updateEmailStmt->bindValue(':email', $email, SQLITE3_TEXT);
        $updateEmailStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $updateEmailStmt->execute();
    }

    // 生成重置token
    $resetToken = bin2hex(random_bytes(32));
    $expireTime = time() + 1800; // 30分钟有效

    // 存储重置token
    $db->exec("CREATE TABLE IF NOT EXISTS password_reset_tokens (token TEXT PRIMARY KEY, player_name TEXT NOT NULL, expire_at INTEGER NOT NULL)");
    $stmt = $db->prepare("INSERT OR REPLACE INTO password_reset_tokens (token, player_name, expire_at) VALUES (:token, :player, :expire)");
    $stmt->bindValue(':token', $resetToken, SQLITE3_TEXT);
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':expire', $expireTime, SQLITE3_INTEGER);
    $stmt->execute();

    // 发送重置邮件
    $smtpHost = SMTP_HOST;
    $smtpPort = SMTP_PORT;
    $smtpUser = SMTP_USER;
    $smtpPass = SMTP_PASS;
    $senderName = SMTP_SENDER_NAME;

    $subject = "【" . $senderName . "】密码重置";
    $resetUrl = WEBSUB_DIR . 'reset_password.php?token=' . $resetToken;
    $htmlBody = "<div style='font-family:Arial,sans-serif;max-width:400px;margin:0 auto;padding:20px;background:#f5f5f5;border-radius:10px'>"
            . "<h2 style='color:#333;text-align:center'>🔑 密码重置</h2>"
            . "<div style='background:#fff;padding:20px;border-radius:8px;text-align:center;margin:20px 0'>"
            . "<p style='font-size:14px;color:#666;margin-bottom:10px'>玩家 <b>" . htmlspecialchars($player) . "</b>，您请求了密码重置</p>"
            . "<p style='font-size:13px;color:#666;margin-bottom:20px'>点击下方按钮重置密码（30分钟内有效）</p>"
            . "<a href='" . $resetUrl . "' style='display:inline-block;padding:12px 24px;background:#4CAF50;color:#fff;text-decoration:none;border-radius:6px;font-weight:bold'>重置密码</a>"
            . "</div>"
            . "<p style='font-size:12px;color:#999;text-align:center'>如果这不是您的操作，请忽略此邮件</p>"
            . "</div>";

    $headers = "MIME-Version: 1.0\r\n"
            . "Content-type: text/html; charset=UTF-8\r\n"
            . "From: " . $senderName . " <" . $smtpUser . ">\r\n";

    try {
        smtpSendEmail($smtpHost, $smtpPort, $smtpUser, $smtpPass, $email, $subject, $htmlBody, $headers, SMTP_USE_SSL);
        success(['player' => $player, 'email' => $email], '重置链接已发送到邮箱');
    } catch (\Throwable $e) {
        error('邮件发送失败：' . $e->getMessage());
    }
}

// ===== 修改密码 =====
function changePassword() {
    $player = getParam('player');
    $oldPwd = getParam('old_password');
    $newPwd = getParam('new_password');

    if (!$player || !$oldPwd || !$newPwd) {
        error('缺少必要参数');
    }

    // 验证新密码格式
    if (strlen($newPwd) < 6 || strlen($newPwd) > 20) {
        error('密码长度必须为6-20位');
    }

    $db = getDB();
    $stmt = $db->prepare("SELECT password_hash, password_salt FROM weblogin_credentials WHERE player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        error('玩家不存在');
    }

    // 验证旧密码
    $saltBytes = base64_decode($row['password_salt'], true);
    if ($saltBytes === false || $saltBytes === '') {
        error('密码盐值无效');
    }

    $oldHashComputed = base64_encode(hash('sha256', $saltBytes . $oldPwd, true));
    if ($oldHashComputed !== $row['password_hash']) {
        error('当前密码错误');
    }

    // 生成新盐值
    $newSaltBytes = random_bytes(16);
    $newSalt = base64_encode($newSaltBytes);

    // 计算新密码哈希
    $newHash = base64_encode(hash('sha256', $saltBytes . $newPwd, true));
    
    // 实际上应该用新盐值，但为了简单先用旧盐值计算
    // 正确做法：$newHash = base64_encode(hash('sha256', $newSaltBytes . $newPwd, true));
    $newHash = base64_encode(hash('sha256', $newSaltBytes . $newPwd, true));

    // 更新密码
    $updateStmt = $db->prepare("UPDATE weblogin_credentials SET password_hash = :hash, salt = :salt, temp_password_hash = '', temp_pw_expire = 0 WHERE player_name = :player");
    $updateStmt->bindValue(':hash', $newHash, SQLITE3_TEXT);
    $updateStmt->bindValue(':salt', $newSalt, SQLITE3_TEXT);
    $updateStmt->bindValue(':player', $player, SQLITE3_TEXT);
    $updateStmt->execute();

    success(['message' => '密码修改成功'], '密码修改成功');
}

// ===== 延长Web登录Token有效期 =====
function extendWebToken() {
    $webToken = getParam('web_token');

    if (!$webToken) error('缺少web_token参数');

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
    $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    if (!$row) error('无效的登录Token');

    $playerName = $row['player_name'];

    // 延长有效期：重置created_at为当前时间
    $updateStmt = $db->prepare("UPDATE weblogin_tokens SET created_at = :time WHERE web_token = :token");
    $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $updateStmt->bindValue(':token', $webToken, SQLITE3_TEXT);
    $updateStmt->execute();

    success(['player' => $playerName], 'Token有效期已延长');
}

// ===== 插件轮询待处理的Web注册请求（SECRET_KEY认证）=====
function checkPendingWebRegisterRequests() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $db = getDB();
    try {
        $db->exec("SELECT id FROM web_register_requests LIMIT 0");
    } catch (\Throwable $e) {
        $db->exec("CREATE TABLE IF NOT EXISTS web_register_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            salt TEXT NOT NULL,
            email TEXT DEFAULT '',
            ip_address TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            status TEXT DEFAULT 'pending',
            processed_at INTEGER DEFAULT 0
        )");
    }

    // ★ 只读查询不需要BEGIN IMMEDIATE（避免与其它并发请求抢锁导致500）
    // 清理过期请求 + 标记processing（非原子操作也OK，最坏情况是多拉一次）
    try {
        $db->exec("DELETE FROM web_register_requests WHERE created_at < " . (time() - 1800));
    } catch (\Throwable $e) {
        @error_log("[checkPendingWebRegisterRequests] cleanup error: " . $e->getMessage());
    }

    $stmt = $db->prepare("SELECT id, player_name, password_hash, salt, email, ip_address, created_at FROM web_register_requests WHERE status = 'pending' ORDER BY created_at ASC");
    $result = $stmt->execute();

    $requests = [];
    $ids = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $requests[] = $row;
        $ids[] = (int)$row['id'];
    }

    // 标记为processing（允许失败，下次还会拉到）
    if (!empty($ids)) {
        try {
            $idList = implode(',', $ids);
            $db->exec("UPDATE web_register_requests SET status = 'processing', processed_at = " . time() . " WHERE id IN ($idList)");
        } catch (\Throwable $e) {
            @error_log("[checkPendingWebRegisterRequests] mark processing error: " . $e->getMessage());
        }
    }

    success(['requests' => $requests, 'count' => count($requests)]);
}

// ===== 插件确认注册请求已处理（SECRET_KEY认证）=====
function completeWebRegisterRequest() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) {
        error('密钥验证失败', 403);
    }

    $requestId = getParam('request_id');
    $result = getParam('result'); // 'success' 或 'failed'
    $errorMsg = getParam('error', '');

    if (!$requestId) error('缺少request_id');

    $db = getDB();
    $status = ($result === 'success') ? 'completed' : 'failed';

    $stmt = $db->prepare("UPDATE web_register_requests SET status = :status, processed_at = :time WHERE id = :id");
    $stmt->bindValue(':status', $status, SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
    $stmt->execute();

    // 如果注册成功，将用户数据写入users表
    if ($result === 'success') {
        // 获取注册请求的详细信息
        $stmt = $db->prepare("SELECT player_name, email, ip_address, created_at FROM web_register_requests WHERE id = :id");
        $stmt->bindValue(':id', (int)$requestId, SQLITE3_INTEGER);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        
        if ($row) {
            // 检查users表中是否已存在该用户
            $checkStmt = $db->prepare("SELECT 1 FROM users WHERE player_name = :name");
            $checkStmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
            $checkResult = $checkStmt->execute();
            
            if (!$checkResult->fetchArray()) {
                // 用户不存在，插入新记录
                $insertStmt = $db->prepare("INSERT INTO users (player_name, register_time, email) VALUES (:name, :time, :email)");
                $insertStmt->bindValue(':name', $row['player_name'], SQLITE3_TEXT);
                $insertStmt->bindValue(':time', $row['created_at'], SQLITE3_INTEGER);
                $insertStmt->bindValue(':email', $row['email'], SQLITE3_TEXT);
                $insertStmt->execute();
                
                debugLog("completeWebRegisterRequest: 将用户写入users表", [
                    'player' => $row['player_name'],
                    'register_time' => $row['created_at'],
                    'email' => $row['email']
                ]);
            }
        }
    }

    success(['request_id' => $requestId, 'status' => $status]);
}

// ===== CDK验证（供sdf1插件调用）=====
// sdf1插件本地CDK未找到时，请求Web后端验证
function validateCdk() {
    $secret = getParam('secret');
    $code = getParam('code', '');
    $player = getParam('player', ''); // ★ 必须传player，否则无法加债券
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');
    if (empty($code)) error('缺少CDK码');
    if (empty($player)) error('缺少player参数');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (code TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, used INTEGER DEFAULT 0, used_by TEXT DEFAULT '', created_at INTEGER DEFAULT 0, used_at INTEGER DEFAULT 0)");
    $db->exec("CREATE TABLE IF NOT EXISTS bond_cache (player_name TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)");

    try {
        $stmt = $db->prepare("SELECT code, amount, used, used_by FROM cdk WHERE code = :code");
        $stmt->bindValue(':code', $code, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);

        if (!$row) {
            success(['found' => false, 'status' => 'not_found']);
            return;
        }

        if ($row['used'] == 1) {
            success([
                'found' => true,
                'status' => 'already_used',
                'used_by' => $row['used_by'] ?? ''
            ]);
            return;
        }

        // ★ 原子标记：防止并发双重兑换
        $updateStmt = $db->prepare("UPDATE cdk SET used = 1, used_by = :player, used_at = :time WHERE code = :code AND used = 0");
        $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $updateStmt->bindValue(':code', $code, SQLITE3_TEXT);
        $updateStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $updateStmt->execute();

        if ($db->changes() == 0) {
            // 并发竞争：已被其他请求使用
            success(['found' => true, 'status' => 'already_used']);
            return;
        }

        // ★ CDK有效：加债券 + 写流水 + 写sync_requests
        $amount = (int)$row['amount'];
        $now = time();

        $db->exec('BEGIN IMMEDIATE');
        try {
            // 1. 加债券到bond_cache
            $chk = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :name");
            $chk->bindValue(':name', $player, SQLITE3_TEXT);
            $chkR = $chk->execute();
            $existing = $chkR->fetchArray(SQLITE3_ASSOC);
            $curBal = $existing ? (int)$existing['amount'] : 0;
            $newBal = $curBal + $amount;

            if ($existing) {
                $u = $db->prepare("UPDATE bond_cache SET amount = :amt, updated_at = :t WHERE player_name = :n");
                $u->bindValue(':amt', $newBal, SQLITE3_INTEGER);
                $u->bindValue(':t', $now, SQLITE3_INTEGER);
                $u->bindValue(':n', $player, SQLITE3_TEXT);
                $u->execute();
            } else {
                $ins = $db->prepare("INSERT INTO bond_cache (player_name, amount, updated_at) VALUES (:n, :amt, :t)");
                $ins->bindValue(':n', $player, SQLITE3_TEXT);
                $ins->bindValue(':amt', $newBal, SQLITE3_INTEGER);
                $ins->bindValue(':t', $now, SQLITE3_INTEGER);
                $ins->execute();
            }

            // 2. 写web_transactions流水（completed，因为Java端已标记used）
            $tx = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'cdk_redeem_remote', :amount, :reason, :detail, 'completed', :time)");
            $tx->bindValue(':player', $player, SQLITE3_TEXT);
            $tx->bindValue(':amount', $amount, SQLITE3_INTEGER);
            $tx->bindValue(':reason', "CDK兑换: {$code}", SQLITE3_TEXT);
            $tx->bindValue(':detail', json_encode(['code' => $code, 'source' => 'web_validate'], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
            $tx->bindValue(':time', $now, SQLITE3_INTEGER);
            $tx->execute();

            // 3. 写sync_requests触发Java立即拉取
            $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
            $sr = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
            $sr->bindValue(':player', $player, SQLITE3_TEXT);
            $sr->bindValue(':time', $now, SQLITE3_INTEGER);
            $sr->execute();

            $db->exec('COMMIT');
        } catch (\Throwable $e) {
            try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
            @error_log("[validateCdk] 事务失败: " . $e->getMessage());
        }

        @error_log("[validateCdk] 成功: code={$code} player={$player} amount={$amount}");
        success([
            'found' => true,
            'status' => 'success',
            'amount' => $amount
        ]);
    } catch (\Throwable $e) {
        @error_log('[validateCdk] Error: ' . $e->getMessage());
        error('验证失败: ' . $e->getMessage());
    }
}

// ===== CDK存在性检查（只检查不消耗）=====
// 供Sdf1_login轮询时判断CDK是否存在于Web，不标记已使用
function checkCdkExists() {
    $secret = getParam('secret');
    $code = getParam('code', '');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');
    if (empty($code)) error('缺少CDK码');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (code TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, used INTEGER DEFAULT 0, used_by TEXT DEFAULT '', created_at INTEGER DEFAULT 0, used_at INTEGER DEFAULT 0)");

    try {
        $stmt = $db->prepare("SELECT code, amount, used, used_by FROM cdk WHERE code = :code");
        $stmt->bindValue(':code', $code, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);

        // ★ 返回扁平JSON（不用success()包装），found用字符串（Java extractJsonStr只支持字符串值）
        if (!$row) {
            jsonResponse(['found' => 'false', 'status' => 'not_found']);
            return;
        }

        if ($row['used'] == 1) {
            jsonResponse([
                'found' => 'true',
                'status' => 'already_used',
                'used_by' => $row['used_by'] ?? ''
            ]);
            return;
        }

        // CDK存在且未使用，只返回信息，不标记已使用
        // ★ amount必须返回字符串（Java extractJsonStr只解析字符串值，数字会被跳过）
        @error_log("[checkCdkExists] found: code={$code} amount={$row['amount']}");
        jsonResponse([
            'found' => 'true',
            'status' => 'available',
            'amount' => (string)$row['amount']
        ]);
    } catch (\Throwable $e) {
        @error_log('[checkCdkExists] Error: ' . $e->getMessage());
        error('检查失败: ' . $e->getMessage());
    }
}

// ===== 远程CDK兑换：标记已使用 + 写流水（不加bond_cache，由Java本地处理） =====
function cdkRedeemRemote() {
    $secret = getParam('secret');
    $code = getParam('code', '');
    $player = getParam('player', '');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');
    if (empty($code)) error('缺少CDK码');
    if (empty($player)) error('缺少player参数');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk (code TEXT PRIMARY KEY, amount INTEGER DEFAULT 0, used INTEGER DEFAULT 0, used_by TEXT DEFAULT '', created_at INTEGER DEFAULT 0, used_at INTEGER DEFAULT 0)");
    // ★ web_transactions表结构必须与其他函数一致
    $db->exec("CREATE TABLE IF NOT EXISTS web_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, operator TEXT DEFAULT '', reason TEXT, detail TEXT, status TEXT DEFAULT 'pending', created_at INTEGER NOT NULL, processed_at INTEGER)");

    try {
        $db->exec('BEGIN IMMEDIATE');

        // 1. 检查CDK是否存在且未使用
        $stmt = $db->prepare("SELECT code, amount, used FROM cdk WHERE code = :code");
        $stmt->bindValue(':code', $code, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

        if (!$row) {
            $db->exec('ROLLBACK');
            jsonResponse(['success' => 'false', 'status' => 'not_found']);
            return;
        }

        if ($row['used'] == 1) {
            $db->exec('ROLLBACK');
            jsonResponse(['success' => 'true', 'status' => 'already_used']);
            return;
        }

        // 2. 原子标记为已使用
        $updateStmt = $db->prepare("UPDATE cdk SET used = 1, used_by = :player, used_at = :time WHERE code = :code AND used = 0");
        $updateStmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $updateStmt->bindValue(':code', $code, SQLITE3_TEXT);
        $updateStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $updateStmt->execute();

        if ($db->changes() == 0) {
            // 并发竞争：已被其他请求使用
            $db->exec('ROLLBACK');
            jsonResponse(['success' => 'true', 'status' => 'already_used']);
            return;
        }

        // 3. 写入流水记录（与validateCdk格式一致）
        $amount = (int)$row['amount'];
        $now = time();
        $ins = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, operator, reason, detail, status, created_at) VALUES (:player, :type, :amount, :operator, :reason, :detail, :status, :time)");
        $ins->bindValue(':player', $player, SQLITE3_TEXT);
        $ins->bindValue(':type', 'cdk_redeem_remote', SQLITE3_TEXT);
        $ins->bindValue(':amount', $amount, SQLITE3_INTEGER);
        $ins->bindValue(':operator', '', SQLITE3_TEXT);
        $ins->bindValue(':reason', "CDK兑换: {$code}", SQLITE3_TEXT);
        $ins->bindValue(':detail', json_encode(['code' => $code, 'source' => 'game_remote'], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
        $ins->bindValue(':status', 'completed', SQLITE3_TEXT);
        $ins->bindValue(':time', $now, SQLITE3_INTEGER);
        $ins->execute();

        $db->exec('COMMIT');

        @error_log("[cdkRedeemRemote] success: code={$code} amount={$amount} player={$player}");
        jsonResponse([
            'success' => 'true',
            'status' => 'success',
            'amount' => (string)$amount
        ]);
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $ignored) {}
        @error_log('[cdkRedeemRemote] Error: ' . $e->getMessage());
        error('兑换失败: ' . $e->getMessage());
    }
}

// ===== Java拉取CDK验证请求 =====
// ===== CDK表迁移修复 =====
function migrateCdkTable() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    $log = [];

    // 1. 确保cdk_validate_requests表存在且有player_name列
    $r = $db->querySingle("SELECT name FROM sqlite_master WHERE type='table' AND name='cdk_validate_requests'");
    if (!$r) {
        $db->exec("CREATE TABLE cdk_validate_requests (
            request_id TEXT PRIMARY KEY,
            code TEXT NOT NULL,
            player_name TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            created_at INTEGER NOT NULL
        )");
        $log[] = 'Created cdk_validate_requests table';
    } else {
        $cols = [];
        $colResult = $db->query("PRAGMA table_info(cdk_validate_requests)");
        while ($col = $colResult->fetchArray(SQLITE3_ASSOC)) { $cols[] = $col['name']; }
        $log[] = 'Existing columns: ' . implode(', ', $cols);
        if (!in_array('player_name', $cols)) {
            $db->exec("ALTER TABLE cdk_validate_requests ADD COLUMN player_name TEXT DEFAULT ''");
            $log[] = 'Added player_name column';
        } else {
            $log[] = 'player_name column already exists';
        }
    }

    // 2. 确保cdk_validate_results表存在
    $r2 = $db->querySingle("SELECT name FROM sqlite_master WHERE type='table' AND name='cdk_validate_results'");
    if (!$r2) {
        $db->exec("CREATE TABLE cdk_validate_results (
            request_id TEXT PRIMARY KEY,
            code TEXT NOT NULL,
            status TEXT NOT NULL,
            amount INTEGER DEFAULT 0,
            message TEXT DEFAULT '',
            used INTEGER DEFAULT 0,
            used_by TEXT DEFAULT '',
            created_at INTEGER NOT NULL
        )");
        $log[] = 'Created cdk_validate_results table';
    } else {
        $log[] = 'cdk_validate_results table exists';
    }

    // 3. 确保cdk表存在
    $r3 = $db->querySingle("SELECT name FROM sqlite_master WHERE type='table' AND name='cdk'");
    if (!$r3) {
        $db->exec("CREATE TABLE cdk (
            code TEXT PRIMARY KEY,
            amount INTEGER DEFAULT 0,
            type TEXT DEFAULT 'bond',
            used INTEGER DEFAULT 0,
            used_by TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0,
            used_at INTEGER DEFAULT 0
        )");
        $log[] = 'Created cdk table';
    } else {
        $log[] = 'cdk table exists';
    }

    success(['log' => $log], '迁移完成');
}

function pullCdkValidateRequests() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $db = getDB();
    // ★ 迁移操作用事务包装防锁
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS cdk_validate_requests (
            request_id TEXT PRIMARY KEY,
            code TEXT NOT NULL,
            player_name TEXT DEFAULT '',
            status TEXT DEFAULT 'pending',
            created_at INTEGER NOT NULL
        )");
        $colResult = $db->query("PRAGMA table_info(cdk_validate_requests)");
        $cols = [];
        while ($col = $colResult->fetchArray(SQLITE3_ASSOC)) { $cols[] = $col['name']; }
        if (!in_array('player_name', $cols)) {
            $db->exec('BEGIN IMMEDIATE');
            $db->exec("ALTER TABLE cdk_validate_requests ADD COLUMN player_name TEXT DEFAULT ''");
            $db->exec('COMMIT');
        }
    } catch (\Throwable $e) {
        try { $db->exec('ROLLBACK'); } catch (\Throwable $e2) {}
    }

    try {
        // ★ 返回player_name
        $result = $db->query("SELECT request_id, code, player_name FROM cdk_validate_requests WHERE status = 'pending' ORDER BY created_at ASC LIMIT 10");
        $requests = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $requests[] = $row;
        }
        success(['requests' => $requests]);
    } catch (\Throwable $e) {
        @error_log('[pullCdkValidateRequests] Error: ' . $e->getMessage());
        error('拉取失败: ' . $e->getMessage());
    }
}

// ===== Java推送CDK验证结果 =====
function pushCdkValidateResult() {
    $secret = getParam('secret');
    if (!$secret || $secret !== SECRET_KEY) error('认证失败');

    $requestId = getParam('request_id', '');
    $status = getParam('status', 'not_found'); // success / not_found / already_used
    $code = getParam('code', '');
    $amount = (int)getParam('amount', 0);

    if (empty($requestId)) error('缺少request_id');

    $db = getDB();
    $db->exec("CREATE TABLE IF NOT EXISTS cdk_validate_results (
        request_id TEXT PRIMARY KEY,
        code TEXT NOT NULL,
        status TEXT NOT NULL,
        amount INTEGER DEFAULT 0,
        message TEXT DEFAULT '',
        used INTEGER DEFAULT 0,
        used_by TEXT DEFAULT '',
        created_at INTEGER NOT NULL
    )");

    try {
        // ★ BEGIN IMMEDIATE防database is locked
        $db->exec('BEGIN IMMEDIATE');
        $now = time();
        $stmt = $db->prepare("INSERT OR REPLACE INTO cdk_validate_results (request_id, code, status, amount, message, created_at) VALUES (:id, :code, :status, :amount, :msg, :time)");
        $stmt->bindValue(':id', $requestId, SQLITE3_TEXT);
        $stmt->bindValue(':code', $code, SQLITE3_TEXT);
        $stmt->bindValue(':status', $status, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
        $stmt->bindValue(':msg', '', SQLITE3_TEXT);
        $stmt->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt->execute();

        // 同时标记请求为已完成
        $stmt2 = $db->prepare("UPDATE cdk_validate_requests SET status = 'done' WHERE request_id = :id");
        $stmt2->bindValue(':id', $requestId, SQLITE3_TEXT);
        $stmt2->execute();
        $db->exec('COMMIT');

        @error_log("[pushCdkValidateResult] request_id={$requestId} code={$code} status={$status} amount={$amount}");
        success(['request_id' => $requestId]);
    } catch (\Throwable $e) {
        $db->exec('ROLLBACK');
        @error_log('[pushCdkValidateResult] Error: ' . $e->getMessage());
        error('推送失败: ' . $e->getMessage());
    }
}
