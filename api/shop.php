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
    case 'buy_cart':
        shopBuyCart($token);
        break;
    case 'cart_config':
        cartConfig();
        break;
    case 'categories':
        shopCategories($token);
        break;
    default:
        error('未知操作: ' . $action);
}

// ★ WAL checkpoint：释放WAL锁，减少database is locked概率
try { walCheckpoint(); } catch (\Throwable $ignored) {}

} catch (\Throwable $e) {
    // ★ 强制回滚可能残留的事务（防止database is locked连锁故障）
    try { $db = getDB(); $db->exec('ROLLBACK'); } catch (\Throwable $_) {}
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

// ===== 购物车配置读取 =====
function getShopConfig($key, $default = null) {
    try {
        $db = getDB();
        $db->exec("CREATE TABLE IF NOT EXISTS shop_config (cfg_key TEXT PRIMARY KEY, cfg_value TEXT NOT NULL)");
        $stmt = $db->prepare("SELECT cfg_value FROM shop_config WHERE cfg_key = :k");
        $stmt->bindValue(':k', $key, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        return $row ? $row['cfg_value'] : $default;
    } catch (Exception $e) {
        return $default;
    }
}

// 返回购物车折扣/加价配置（前端展示用）
function cartConfig() {
    success([
        'backpack_rate' => (float)getShopConfig('cart_backpack_rate', '0.98'),
        'shulker_rate' => (float)getShopConfig('cart_shulker_rate', '1.00')
    ]);
}

// ===== 购物车结算（多商品一次性购买） =====
function shopBuyCart($token) {
    $rawItems = getParam('items');
    $settlement = getParam('settlement', 'backpack'); // backpack=塞背包, shulker=潜影盒打包
    $shulkerColor = getParam('shulker_color', 'purple'); // 潜影盒颜色（purple免费,其它+2元）
    $player = getParam('player');
    $password = getParam('password');
    $ipAddress = $_SERVER['REMOTE_ADDR'] ?? '0.0.0.0';

    if (!$rawItems) error('购物车为空');
    $items = json_decode($rawItems, true);
    if (!is_array($items) || count($items) === 0) error('购物车格式错误');

    // 校验每一项
    $parsed = [];
    foreach ($items as $it) {
        if (!isset($it['item_id']) || !isset($it['amount'])) error('商品项缺少字段');
        $pid = (string)$it['item_id'];
        $amt = (int)$it['amount'];
        if ($amt < 1) error('数量无效: ' . $pid);
        $parsed[] = ['item_id' => $pid, 'amount' => $amt];
    }
    if (!$player) error('缺少player');

    // ★ 双保险验证（与单件购买一致）
    $isPreview = true;
    $isOnline = false;
    $isRegistered = false;

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            if (validateToken($token)) validateAndUseToken($token);
        } else {
            $accessResult = validateWebAccess($token, 'buy', $password, $ipAddress);
            if ($accessResult['ok']) {
                $isPreview = false;
                $player = $accessResult['player'];
                $isOnline = $accessResult['online'] ?? false;
                $isRegistered = $accessResult['registered'] ?? false;
            } elseif (($accessResult['mode'] ?? '') === 'need_password') {
                jsonResponse(['success' => false, 'need_password' => true, 'player' => $accessResult['player'], 'message' => $accessResult['message']], 401);
            } elseif (($accessResult['mode'] ?? '') === 'need_game_login') {
                jsonResponse(['success' => false, 'need_game_login' => true, 'player' => $accessResult['player'], 'message' => $accessResult['message']], 401);
            } elseif (($accessResult['mode'] ?? '') === 'need_register') {
                jsonResponse(['success' => false, 'need_register' => true, 'player' => $accessResult['player'], 'message' => $accessResult['message']], 401);
            } else {
                error($accessResult['message'], 401);
            }
        }
    }

    // 结算模式对应的折扣/加价系数
    if ($settlement === 'shulker') {
        $rate = (float)getShopConfig('cart_shulker_rate', '1.00');
        $modeName = '潜影盒打包';
        // 潜影盒颜色额外收费（紫色免费，其它+2元）
        $colorFee = ($shulkerColor !== 'purple') ? 2 : 0;
        // 颜色名称映射
        $colorNames = ['purple'=>'紫色','white'=>'白色','black'=>'黑色','red'=>'红色','blue'=>'蓝色','green'=>'绿色','yellow'=>'黄色','orange'=>'橙色'];
        $colorName = $colorNames[$shulkerColor] ?? $shulkerColor;
    } else {
        $settlement = 'backpack';
        $rate = (float)getShopConfig('cart_backpack_rate', '0.98');
        $modeName = '塞背包';
        $colorFee = 0;
        $colorName = '';
    }

    $db = getDB();
    $lines = [];
    $subtotal = 0;
    foreach ($parsed as &$p) {
        $stmt = $db->prepare("SELECT * FROM shop_items WHERE id = :id");
        $stmt->bindValue(':id', $p['item_id'], SQLITE3_TEXT);
        $result = $stmt->execute();
        $item = $result->fetchArray(SQLITE3_ASSOC);
        if (!$item) error('商品不存在: ' . $p['item_id']);
        if ($item['stock'] == 0) error('商品已售罄: ' . $item['display_name']);
        if ($item['stock'] > 0 && $item['stock'] < $p['amount']) error('库存不足: ' . $item['display_name'] . '（剩余' . $item['stock'] . '）');
        $lineTotal = (int)$item['buy_price'] * $p['amount'];
        $subtotal += $lineTotal;
        $p['item'] = $item;
        $lines[] = [
            'item_id' => $p['item_id'],
            'name' => $item['display_name'],
            'unit_price' => (int)$item['buy_price'],
            'amount' => $p['amount'],
            'line_total' => $lineTotal
        ];
    }
    unset($p);

    $totalPrice = (int)round($subtotal * $rate) + $colorFee;
    $saved = $subtotal - $totalPrice; // 折扣省下的（潜影盒加价+颜色费时为负）

    // ★ 安全检查：服务端计算的总额≥1000时，必须已通过密码验证（防止前端绕过）
    // 管理员token(admin/all)在上方已跳过密码验证，此处补检
    if ($totalPrice >= 1000 && !$isPreview) {
        $tokenInfo = validateToken($token);
        $isAdminToken = $tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all');
        if ($isAdminToken && (!$password || trim($password) === '')) {
            // 管理员代购大额也需密码确认
            jsonResponse(['success' => false, 'need_password' => true,
                'player' => $player, 'message' => '金额≥1000债券需确认密码'], 401);
        }
        if (!$isAdminToken && !isset($accessResult['ok'])) {
            // 非管理员且未通过密码验证 → 拒绝
            error('大额交易需验证游戏登录密码', 401);
        }
    }

    // 检查余额
    if (!$isPreview && $totalPrice > 0) {
        $balanceStmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :player");
        $balanceStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $balanceResult = $balanceStmt->execute();
        $balanceRow = $balanceResult->fetchArray(SQLITE3_ASSOC);
        if (!$balanceRow) error('玩家余额查询失败');
        $balance = (int)$balanceRow['amount'];
        if ($balance < $totalPrice) {
            error('余额不足，当前余额: ' . $balance . ' 债券，需要: ' . $totalPrice . ' 债券');
        }
    }

    if ($isPreview) {
        preview([
            'settlement' => $settlement,
            'mode_name' => $modeName,
            'rate' => $rate,
            'items' => $lines,
            'subtotal' => $subtotal,
            'total_price' => $totalPrice,
            'saved' => $saved,
            'player' => $player
        ], '预览模式 - 结算不会生效');
    }

    // ★ 实际购买 — 包装在事务中
    $db->exec('BEGIN IMMEDIATE');
    try {
        foreach ($parsed as $p) {
            $item = $p['item'];
            if ($item['stock'] > 0) {
                $newStock = $item['stock'] - $p['amount'];
                $stmt = $db->prepare("UPDATE shop_items SET stock = :ns, admin_stock = :ns, hourly_sales = hourly_sales + :amount, total_sales = total_sales + :amount WHERE id = :id");
                $stmt->bindValue(':ns', $newStock, SQLITE3_INTEGER);
                $stmt->bindValue(':amount', $p['amount'], SQLITE3_INTEGER);
                $stmt->bindValue(':id', $p['item_id'], SQLITE3_TEXT);
                $stmt->execute();
            } else {
                $stmt = $db->prepare("UPDATE shop_items SET hourly_sales = hourly_sales + :amount, total_sales = total_sales + :amount WHERE id = :id");
                $stmt->bindValue(':amount', $p['amount'], SQLITE3_INTEGER);
                $stmt->bindValue(':id', $p['item_id'], SQLITE3_TEXT);
                $stmt->execute();
            }
        }

        // 记录一条合并交易
        $reasonItems = array_map(function ($l) { return $l['name'] . ' x' . $l['amount']; }, $lines);
        $stmt = $db->prepare("INSERT INTO web_transactions (player_name, type, amount, reason, detail, status, created_at) VALUES (:player, 'shop_cart', :amount, :reason, :detail, 'pending', :time)");
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':amount', $totalPrice, SQLITE3_INTEGER);
        $stmt->bindValue(':reason', "购物车结算({$modeName}" . ($colorName ? "-{$colorName}潜影盒" : '') . "): " . implode('、', $reasonItems), SQLITE3_TEXT);
        $detailData = [
            'settlement' => $settlement,
            'rate' => $rate,
            'subtotal' => $subtotal,
            'total_price' => $totalPrice,
            'items' => $lines
        ];
        if ($settlement === 'shulker') {
            $detailData['shulker_color'] = $shulkerColor;
            $detailData['color_name'] = $colorName;
            $detailData['color_fee'] = $colorFee;
        }
        $stmt->bindValue(':detail', json_encode($detailData, JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();

        // 写入sync_requests
        $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        $stmt2 = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
        $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();

        $db->exec('COMMIT');
    } catch (Exception $e) {
        try { $db->exec('ROLLBACK'); } catch (Exception $e2) {}
        error('购物车结算失败: ' . $e->getMessage(), 500);
    }

    success([
        'settlement' => $settlement,
        'mode_name' => $modeName,
        'rate' => $rate,
        'items' => $lines,
        'subtotal' => $subtotal,
        'total_price' => $totalPrice,
        'saved' => $saved,
        'player' => $player
    ], '购物车结算成功');
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
