<?php
/**
 * 商城API
 * GET ?action=list              - 商品列表（无需token，公开接口）
 * POST ?action=buy              - 购买商品（需token）
 * GET ?action=categories        - 分类列表（无需token，公开接口）
 */
// 防止任何输出污染JSON响应
while (ob_get_level() > 0) { ob_end_clean(); }
require_once __DIR__ . '/../core.php';
// 再次确保清理前置输出
while (ob_get_level() > 0) { ob_end_clean(); }

$action = getParam('action', 'list');
$token = getParam('token');

try {
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
} catch (\Throwable $e) {
    while (ob_get_level() > 0) ob_end_clean();
    error('服务器内部错误: ' . $e->getMessage(), 500);
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
    if ($amount < 1) error('数量无效');

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

    // ★ 实际购买 — 包装在事务中，防止database is locked
    $db->exec('BEGIN IMMEDIATE');
    try {
        // 更新库存（同时写admin_stock确保Java同步）
        if ($item['stock'] > 0) {
            $newStock = $item['stock'] - $amount;
            $stmt = $db->prepare("UPDATE shop_items SET stock = :ns, admin_stock = :ns, hourly_sales = hourly_sales + :amount, total_sales = total_sales + :amount WHERE id = :id");
            $stmt->bindValue(':ns', $newStock, SQLITE3_INTEGER);
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

        // ★ 写入sync_requests（必须在success/exit之前！）
        $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        $stmt2 = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
        $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();

        $db->exec('COMMIT');
    } catch (Exception $e) {
        try { $db->exec('ROLLBACK'); } catch (Exception $e2) {}
        error('购买失败: ' . $e->getMessage(), 500);
    }

    success([
        'item_id' => $itemId,
        'item_name' => $item['display_name'],
        'amount' => $amount,
        'total_price' => $totalPrice,
        'player' => $player
    ], '购买成功');
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
