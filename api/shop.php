<?php
/**
 * 商城API
 * GET ?action=list              - 商品列表（无需token，公开接口）
 * POST ?action=buy              - 购买商品（需token）
 * GET ?action=categories        - 分类列表（无需token，公开接口）
 */
// 防止任何输出污染JSON响应
ob_start();
require_once __DIR__ . '/../core.php';
// 清理可能的前置输出
ob_end_clean();

$action = getParam('action', 'list');
$token = getParam('token');

switch ($action) {
    case 'list':
        shopList($token);
        break;
    case 'buy':
        shopBuy($token);
        break;
    case 'categories':
        shopCategories($token);
        break;
    default:
        error('未知操作: ' . $action);
}

// ===== 商品列表（公开，无需token） =====
function shopList($token = null) {
    try {
        $db = getDB();
        $category = getParam('category');

        if ($category) {
            $stmt = $db->prepare("SELECT * FROM shop_items WHERE category = :cat ORDER BY id");
            $stmt->bindValue(':cat', $category, SQLITE3_TEXT);
            $result = $stmt->execute();
        } else {
            $result = $db->query("SELECT * FROM shop_items ORDER BY category, id");
        }

        $items = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $items[] = $row;
        }

        // 直接返回数据，不验证token（公开接口）
        $isPreview = !$token;
        if ($token && validateTokenSilent($token)) {
            $isPreview = false;
        }

        if ($isPreview) {
            preview($items, '预览模式 - 无有效token');
        } else {
            success($items);
        }
    } catch (Exception $e) {
        error('商城加载失败: ' . $e->getMessage(), 500);
    }
}

// validateTokenSilent() 已在 core.php 中定义，不再重复声明

// ===== 分类列表 =====
function shopCategories() {
    $db = getDB();
    $result = $db->query("SELECT DISTINCT category, COUNT(*) as item_count FROM shop_items GROUP BY category");
    $cats = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $cats[] = $row;
    }
    success($cats);
}

// ===== 购买商品 =====
function shopBuy($token) {
    $itemId = getParam('item_id');
    $amount = (int)getParam('amount', 1);
    $player = getParam('player');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$itemId) error('缺少item_id');
    if (!$player) error('缺少player');
    if ($amount < 1 || $amount > 64) error('数量无效');

    // ★ 双保险验证：购买操作需要密码确认
    $isPreview = true;
    $isOnline = false;
    $isRegistered = false;

    if ($token) {
        // 先检查是否为普通管理token
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            if (validateToken($token)) {
                validateAndUseToken($token);
            }
        } else {
            // weblogin token → 走双保险验证
            $accessResult = validateWebAccess($token, 'buy', $password, $ipAddress);
            if ($accessResult['ok']) {
                $isPreview = false;
                $player = $accessResult['player'];
                $isOnline = $accessResult['online'] ?? false;
                $isRegistered = $accessResult['registered'] ?? false;
            } elseif ($accessResult['mode'] === 'need_password') {
                jsonResponse([
                    'success' => false,
                    'need_password' => true,
                    'player' => $accessResult['player'],
                    'message' => $accessResult['message']
                ], 401);
            } elseif ($accessResult['mode'] === 'need_game_login') {
                jsonResponse([
                    'success' => false,
                    'need_game_login' => true,
                    'player' => $accessResult['player'],
                    'message' => $accessResult['message']
                ], 401);
            } elseif ($accessResult['mode'] === 'need_register') {
                jsonResponse([
                    'success' => false,
                    'need_register' => true,
                    'player' => $accessResult['player'],
                    'message' => $accessResult['message']
                ], 401);
            } else {
                error($accessResult['message'], 401);
            }
        }
    }

    // 查询商品
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM shop_items WHERE id = :id");
    $stmt->bindValue(':id', $itemId, SQLITE3_TEXT);
    $result = $stmt->execute();
    $item = $result->fetchArray(SQLITE3_ASSOC);

    if (!$item) error('商品不存在: ' . $itemId);

    // 检查库存
    if ($item['stock'] == 0) error('商品已售罄');
    if ($item['stock'] > 0 && $item['stock'] < $amount) error('库存不足');

    $totalPrice = $item['buy_price'] * $amount;

    // 检查玩家余额
    if (!$isPreview && $totalPrice > 0) {
        $balanceStmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :player");
        $balanceStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $balanceResult = $balanceStmt->execute();
        $balanceRow = $balanceResult->fetchArray(SQLITE3_ASSOC);

        if (!$balanceRow) {
            error('玩家余额查询失败');
        }

        $balance = (int)$balanceRow['amount'];
        if ($balance < $totalPrice) {
            error('余额不足，当前余额: ' . $balance . ' 债券，需要: ' . $totalPrice . ' 债券');
        }
    }

    if ($isPreview) {
        // 预览模式：返回计算结果但不执行
        preview([
            'item_id' => $itemId,
            'item_name' => $item['display_name'],
            'amount' => $amount,
            'unit_price' => $item['buy_price'],
            'total_price' => $totalPrice,
            'stock_before' => $item['stock'],
            'stock_after' => $item['stock'] > 0 ? $item['stock'] - $amount : -1,
            'player' => $player
        ], '预览模式 - 购买不会生效');
    }

    // 实际购买
    // 更新库存
    if ($item['stock'] > 0) {
        $stmt = $db->prepare("UPDATE shop_items SET stock = stock - :amount, hourly_sales = hourly_sales + :amount, total_sales = total_sales + :amount WHERE id = :id");
        $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $itemId, SQLITE3_TEXT);
        $stmt->execute();
    } else {
        // 无限库存，只更新销量
        $stmt = $db->prepare("UPDATE shop_items SET hourly_sales = hourly_sales + :amount, total_sales = total_sales + :amount WHERE id = :id");
        $stmt->bindValue(':amount', $amount, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $itemId, SQLITE3_TEXT);
        $stmt->execute();
    }

    // 记录交易
    $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'shop_buy', :amount, :reason, :detail, 'pending', :time)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':amount', $totalPrice, SQLITE3_INTEGER);
    $stmt->bindValue(':reason', "购买: {$item['display_name']} x{$amount}", SQLITE3_TEXT);
    $stmt->bindValue(':detail', json_encode(['item_id' => $itemId, 'amount' => $amount, 'unit_price' => $item['buy_price']], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
    $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
    $stmt->execute();

    // ★ 记录交易创建
    $txId = $db->lastInsertRowID();

    success([
        'item_id' => $itemId,
        'item_name' => $item['display_name'],
        'amount' => $amount,
        'total_price' => $totalPrice,
        'player' => $player
    ], '购买成功');

    // ★ 立即通知Java插件拉取交易（3种方式）
    // 方式1：直接HTTP请求到Java插件（回调端口）
    $notifyUrl = "http://127.0.0.1:" . CALLBACK_PORT . "/api/notify_sync?secret=" . SECRET_KEY . "&tx_id=" . $txId;
    @file_get_contents($notifyUrl, false, stream_context_create(['http' => ['method' => 'POST', 'timeout' => 2]]));
    
    // 方式2：通过Web URL触发（如果回环不通）
    $baseUrl = (isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] === 'on' ? 'https' : 'http') . '://' . $_SERVER['HTTP_HOST'] . WEBSUB_DIR;
    $notifyUrl2 = $baseUrl . "/api/sync.php?action=notify_sync&secret=" . SECRET_KEY . "&tx_id=" . $txId;
    @file_get_contents($notifyUrl2, false, stream_context_create(['http' => ['method' => 'POST', 'timeout' => 2]]));
    
    // 方式3：写入通知文件（供插件文件系统检测）
    $notifyFile = __DIR__ . '/../db/tx_notify.json';
    $notifyData = json_encode(['tx_id' => (int)$txId, 'time' => time()], JSON_UNESCAPED_UNICODE);
    @file_put_contents($notifyFile, $notifyData, LOCK_EX);
}

// ===== Weblogin Token验证（用于商城购买）=====
function validateWebloginTokenForShop($webToken) {
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE web_token = :token");
        $stmt->bindValue(':token', $webToken, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if (!$row) return false;

        $createdAt = (int)$row['created_at'];
        $expireSeconds = (int)$row['expire_seconds'];
        if (time() - $createdAt > $expireSeconds) return false;

        return [
            'player' => $row['player_name'],
            'purpose' => 'weblogin',
            'created_at' => $createdAt,
            'expires_at' => $createdAt + $expireSeconds
        ];
    } catch (Exception $e) {
        return false;
    }
}
