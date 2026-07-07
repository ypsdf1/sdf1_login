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

// ===== 购物车配置读取（批量优化：单次查询读取所有需要的配置） =====
function getShopConfig($key, $default = null) {
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT cfg_value FROM shop_config WHERE cfg_key = :k");
        $stmt->bindValue(':k', $key, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        return $row ? $row['cfg_value'] : $default;
    } catch (Exception $e) {
        return $default;
    }
}

/**
 * 批量读取多个shop_config键（一次查询替代N次getShopConfig调用）
 * @return array  key => value (不存在的key不返回)
 */
function getShopConfigs(array $keys) {
    if (empty($keys)) return [];
    try {
        $db = getDB();
        $placeholders = implode(',', array_fill(0, count($keys), '?'));
        $stmt = $db->prepare("SELECT cfg_key, cfg_value FROM shop_config WHERE cfg_key IN ($placeholders)");
        foreach ($keys as $i => $k) {
            $stmt->bindValue($i + 1, $k, SQLITE3_TEXT);
        }
        $result = $stmt->execute();
        $map = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $map[$row['cfg_key']] = $row['cfg_value'];
        }
        return $map;
    } catch (Exception $e) {
        return [];
    }
}

// 返回购物车折扣/加价配置（前端展示用，★ 批量读取）
function cartConfig() {
    $cfg = getShopConfigs(['cart_backpack_rate', 'cart_shulker_rate', 'packmoney', 'green_discount']);
    success([
        'backpack_rate' => (float)($cfg['cart_backpack_rate'] ?? '0.98'),
        'shulker_rate' => (float)($cfg['cart_shulker_rate'] ?? '1.00'),
        'packmoney' => (int)($cfg['packmoney'] ?? '5'),
        'green_discount' => (float)($cfg['green_discount'] ?? '2')
    ]);
}

// ===== 购物车结算（多商品一次性购买） =====
function shopBuyCart($token) {
    $rawItems = getParam('items');
    $settlement = getParam('settlement', 'backpack'); // backpack=塞背包, shulker=潜影盒打包
    $shulkerColor = getParam('shulker_color', 'default'); // 潜影盒颜色（default原色免费,其它+2元）
    $payMode = getParam('pay_mode', 'bond'); // bond=债券扣款, cash=现金仅记账
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
    $isAdminToken = false;
    $isOnline = false;
    $isRegistered = false;
    $isCashier = false;
    $isAdminSession = false;
    $operatorDiscountLimit = 0;
    $cashierRow = null;
    $orderNo = '';

    if ($token) {
        $tokenInfo = validateToken($token);
        if ($tokenInfo && ($tokenInfo['purpose'] === 'admin' || $tokenInfo['purpose'] === 'all')) {
            $isPreview = false;
            $isAdminToken = true; // ★ 记录代购令牌身份，避免下方大额复检重复校验已消费的token导致误判
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
    } else {
        // 收银员/管理员会话代购通路（独立收银台，无需token）
        if (isCashierLoggedIn()) {
            $isPreview = false;
            $isCashier = true;
            $cashierRow = getCurrentCashier();
            $operatorDiscountLimit = (int)($cashierRow['discount_limit_percent'] ?? 0);
        } elseif (isAdminLoggedIn()) {
            $isPreview = false;
            $isAdminSession = true;
        }
    }

    // 结算模式对应的折扣/加价系数（★ 批量读取所有配置键，1次查询替代4次）
    $cfg = getShopConfigs(['cart_shulker_rate', 'packmoney', 'cart_backpack_rate', 'green_discount']);
    if ($settlement === 'shulker') {
        $rate = (float)($cfg['cart_shulker_rate'] ?? '1.00');
        $modeName = '潜影盒打包';
        // 潜影盒颜色额外收费（原色/紫色免费，其它加收打包费，打包费来自配置 packmoney）
        $packMoney = (int)($cfg['packmoney'] ?? '5');
        $colorFee = ($shulkerColor !== 'default' && $shulkerColor !== 'purple') ? $packMoney : 0;
        // 颜色名称映射
        $colorNames = ['default'=>'原色','purple'=>'原色','white'=>'白色','black'=>'黑色','red'=>'红色','blue'=>'蓝色','green'=>'绿色','yellow'=>'黄色','orange'=>'橙色'];
        $colorName = $colorNames[$shulkerColor] ?? $shulkerColor;
        $ecoPct = 0;
    } else {
        $settlement = 'backpack';
        $rate = (float)($cfg['cart_backpack_rate'] ?? '0.98');
        $modeName = '塞背包（环保单）';
        $colorFee = 0;
        $colorName = '';
        // 环保单减免：不打包/塞背包时按配置比例减免（与游戏内"不打包"一致）
        $ecoPct = (float)($cfg['green_discount'] ?? '2');
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

    // 先按费率计算，再叠加环保单减免（不打包/塞背包），最后加颜色打包费
    $baseAfterRate = (int)round($subtotal * $rate);
    if ($ecoPct > 0) $baseAfterRate = (int)round($baseAfterRate * (100 - $ecoPct) / 100);
    $totalPrice = $baseAfterRate + $colorFee;
    $saved = $subtotal - $totalPrice; // 折扣省下的（潜影盒加价+颜色费时为负）

    // ===== 收银员/管理员手动折扣（服务端强制上限，前端声称不可信）=====
    $discountPercent = 0;
    $discountAmount = 0;
    $finalPrice = $totalPrice;
    $canDiscount = ($isCashier || $isAdminToken || $isAdminSession);
    if ($canDiscount) {
        $reqDiscount = (float)getParam('discount_percent', 0);
        if ($reqDiscount < 0) $reqDiscount = 0;
        if ($reqDiscount > 100) $reqDiscount = 100;
        // 收银员受自身折扣上限限制；管理员无限制（最大100）
        $limit = $isCashier ? $operatorDiscountLimit : 100;
        if ($reqDiscount > $limit) {
            error('折扣超过权限上限（收银员最大可打' . $limit . '% off）', 403);
        }
        $discountPercent = $reqDiscount;
        if ($discountPercent > 0) {
            $discountAmount = (int)round($totalPrice * $discountPercent / 100);
            $finalPrice = $totalPrice - $discountAmount;
        }
    }

    // ★ 安全检查：服务端计算的实际扣款额≥1000时，必须已通过密码验证（防止前端绕过）
    // 管理员代购令牌(admin/all)已在上方标记$isAdminToken并消费，此处不再重新校验token，
    // 而是校验代购操作者（管理员）登录密码；普通玩家通路走validateWebAccess已在上方完成验证；
    // 收银员通路已通过收银员会话登录，视为已授权，无需额外密码
    if ($finalPrice >= 1000 && !$isPreview) {
        if ($isCashier) {
            // 收银员已登录即授权，无需额外密码
        } elseif ($isAdminToken || $isAdminSession) {
            // 代购通路：校验管理员登录密码（代购操作者的密码），而非目标玩家游戏密码
            if (!$password || trim($password) === '' || $password !== ADMIN_PASS) {
                jsonResponse(['success' => false, 'need_password' => true,
                    'player' => $player, 'message' => '金额≥1000债券需确认管理员密码'], 401);
            }
        } elseif (!isset($accessResult['ok'])) {
            // 非管理员且未通过密码验证 → 拒绝
            error('大额交易需验证游戏登录密码', 401);
        }
    }

    // ★ 现金收款权限校验：收银员需管理员授予 can_cash 权限
    if ($payMode === 'cash') {
        if ($isCashier && empty($cashierRow['can_cash'])) {
            error('该收银员无现金收款权限（需管理员在收银员管理中开启）', 403);
        }
        // 现金模式：仅记账，不扣玩家债券（跳过余额校验与扣款）
    }

    // 检查余额（现金模式跳过：仅记账）
    if (!$isPreview && $finalPrice > 0 && $payMode !== 'cash') {
        $balanceStmt = $db->prepare("SELECT amount FROM bond_cache WHERE player_name = :player");
        $balanceStmt->bindValue(':player', $player, SQLITE3_TEXT);
        $balanceResult = $balanceStmt->execute();
        $balanceRow = $balanceResult->fetchArray(SQLITE3_ASSOC);
        if (!$balanceRow) error('玩家余额查询失败');
        $balance = (int)$balanceRow['amount'];
        if ($balance < $finalPrice) {
            error('余额不足，当前余额: ' . $balance . ' 债券，需要: ' . $finalPrice . ' 债券');
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
        $stmt->bindValue(':amount', $finalPrice, SQLITE3_INTEGER);
        $stmt->bindValue(':reason', "购物车结算({$modeName}" . ($colorName ? "-{$colorName}潜影盒" : '') . "): " . implode('、', $reasonItems), SQLITE3_TEXT);
        $detailData = [
            'settlement' => $settlement,
            'rate' => $rate,
            'subtotal' => $subtotal,
            'total_price' => $finalPrice,
            'discount_percent' => (int)$discountPercent,
            'discount_amount' => (int)$discountAmount,
            'pay_mode' => $payMode,
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

        // 记录收银台订单（代购/收银员操作，写入同一事务，复用已开启的$db连接避免触发getDB安全网ROLLBACK）
        if ($isCashier || $isAdminToken || $isAdminSession) {
            $operatorType = $isCashier ? 'cashier' : 'admin';
            $operatorName = $isCashier ? ($cashierRow['username'] ?? '') : 'admin';
            $orderNo = recordCashierOrder([
                'operator_type' => $operatorType,
                'operator_name' => $operatorName,
                'player_name' => $player,
                'items_detail' => $lines,
                'subtotal' => $subtotal,
                'total_price' => $finalPrice,
                'discount_percent' => (int)$discountPercent,
                'discount_amount' => (int)$discountAmount,
                'settlement' => $settlement,
                'payment_method' => $payMode
            ], $db);
        }

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
        'total_price' => $finalPrice,
        'original_price' => $totalPrice,
        'discount_percent' => (int)$discountPercent,
        'discount_amount' => (int)$discountAmount,
        'saved' => $saved,
        'player' => $player,
        'order_no' => $orderNo ?? ''
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
