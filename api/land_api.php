<?php
/**
 * 领地系统API - 接收Java端领地数据 + 管理面板 + 玩家端
 * POST: Java端推送领地数据
 * GET: admin/玩家面板读取领地数据
 */
header('Content-Type: application/json; charset=utf-8');
error_reporting(E_ERROR | E_PARSE);

try {
    // ★ 加载core.php（与sync.php相同的加载方式）
    $coreFile = dirname(__DIR__) . '/core.php';
    if (!file_exists($coreFile)) {
        $coreFile = dirname(__DIR__) . '/plugin/core.php';
    }
    require_once $coreFile;

    $action = $_GET['action'] ?? $_POST['action'] ?? '';
    $secret = $_GET['secret'] ?? $_POST['secret'] ?? '';
    $method = $_SERVER['REQUEST_METHOD'];

    // ===== 验证 =====
    // 三种认证方式：
    // 1. Java端推送: POST + secret
    // 2. 管理面板: GET/POST + admin_token (admin.php已登录的session)
    // 3. 玩家端: GET + player_token
    $playerName = '';
    $isAdmin = false;

    // 同步类action只接受secret验证（Java端推送）
    $syncActions = ['sync_lands', 'sync_shop', 'sync_permissions'];
    // 管理面板action：支持admin_token或secret
    $adminActions = ['list_lands', 'list_shop', 'get_config', 'update_config', 'delete_land', 'update_land_owner', 'delete_shop_item'];
    // 玩家端action：需要token
    $playerActions = ['my_lands', 'land_detail', 'add_visitor', 'remove_visitor', 'list_visitors', 'land_shop', 'buy_permission'];

    if (in_array($action, $syncActions)) {
        // 同步类：必须有secret
        if (!validateSecret($secret)) {
            echo json_encode(['success' => false, 'error' => 'invalid secret']);
            exit;
        }
    } elseif (in_array($action, $adminActions)) {
        // 管理面板：验证admin_token或secret
        $adminToken = $_GET['admin_token'] ?? $_POST['admin_token'] ?? '';
        if (!empty($adminToken)) {
            // 用admin_token验证（admin.php的登录态）
            $adminInfo = validateTokenSilent($adminToken);
            if (!$adminInfo) {
                echo json_encode(['success' => false, 'error' => '管理员认证失败', 'needLogin' => true]);
                exit;
            }
            $isAdmin = true;
        } elseif (!validateSecret($secret)) {
            // 没有admin_token，尝试secret
            echo json_encode(['success' => false, 'error' => '需要管理员认证']);
            exit;
        }
    } elseif (in_array($action, $playerActions)) {
        // 玩家端：需要token
        $token = $_GET['token'] ?? '';
        if (empty($token)) {
            echo json_encode(['success' => false, 'error' => '需要登录', 'needLogin' => true]);
            exit;
        }
        $tokenInfo = validateTokenSilent($token);
        if (!$tokenInfo) {
            echo json_encode(['success' => false, 'error' => 'token无效或已过期', 'needLogin' => true]);
            exit;
        }
        $playerName = is_array($tokenInfo) ? $tokenInfo['player'] : $tokenInfo;
    } else {
        // 未知action：默认放行（部分公开GET读取）
        if ($method === 'POST' && !validateSecret($secret)) {
            echo json_encode(['success' => false, 'error' => 'invalid secret']);
            exit;
        }
    }

    $db = getDB();

    // 建表
    initLandTables($db);

    switch ($action) {
        // ===== Java端推送领地数据 =====
        case 'sync_lands':
            handleSyncLands($db, $_POST);
            break;

        // ===== Java端推送权限商店数据 =====
        case 'sync_shop':
            handleSyncShop($db, $_POST);
            break;

        // ===== Java端推送访客权限数据 =====
        case 'sync_permissions':
            handleSyncPermissions($db, $_POST);
            break;

        // ===== 管理面板：获取所有领地 =====
        case 'list_lands':
            if ($method !== 'GET') {
                echo json_encode(['success' => false, 'error' => 'GET only']);
                exit;
            }
            handleListLands($db);
            break;

        // ===== 管理面板：获取权限商店 =====
        case 'list_shop':
            if ($method !== 'GET') {
                echo json_encode(['success' => false, 'error' => 'GET only']);
                exit;
            }
            handleListShop($db);
            break;

        // ===== 管理面板：获取配置 =====
        case 'get_config':
            handleGetConfig($db);
            break;

        // ===== 管理面板：更新配置 =====
        case 'update_config':
            handleUpdateConfig($db, $_POST);
            break;

        // ===== 管理面板：删除领地 =====
        case 'delete_land':
            handleDeleteLand($db, $_POST);
            break;

        // ===== 管理面板：更新领地所有者 =====
        case 'update_land_owner':
            handleUpdateLandOwner($db, $_POST);
            break;

        // ===== 管理面板：删除权限商品 =====
        case 'delete_shop_item':
            handleDeleteShopItem($db, $_POST);
            break;

        // ===== 玩家端：我拥有的领地 =====
        case 'my_lands':
            handleMyLands($db, $playerName);
            break;

        // ===== 玩家端：领地详情（含访客列表）=====
        case 'land_detail':
            handleLandDetail($db, $playerName, $_GET['name'] ?? '');
            break;

        // ===== 玩家端：添加访客 =====
        case 'add_visitor':
            if ($method !== 'GET' && $method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'GET/POST only']);
                exit;
            }
            $req = $method === 'POST' ? $_POST : $_GET;
            handleAddVisitor($db, $playerName, $req);
            break;

        // ===== 玩家端：移除访客 =====
        case 'remove_visitor':
            if ($method !== 'GET' && $method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'GET/POST only']);
                exit;
            }
            $req = $method === 'POST' ? $_POST : $_GET;
            handleRemoveVisitor($db, $playerName, $req);
            break;

        // ===== 玩家端：访客列表 =====
        case 'list_visitors':
            handleListVisitors($db, $_GET['name'] ?? '');
            break;

        // ===== 玩家端：领地商店（购买访客权限）=====
        case 'land_shop':
            handleLandShop($db, $_GET['name'] ?? '');
            break;

        default:
            echo json_encode(['success' => false, 'error' => 'unknown action']);
    }
} catch (\Throwable $e) {
    echo json_encode(['success' => false, 'error' => $e->getMessage()]);
}

// ==================== 函数 ====================

function initLandTables($db) {
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

    $db->exec("CREATE TABLE IF NOT EXISTS web_area_config (
        key TEXT PRIMARY KEY,
        value TEXT DEFAULT ''
    )");

    // ★ 访客权限表（Java端同步过来）
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
}

function handleSyncLands($db, $post) {
    $lands = json_decode($post['lands'] ?? '[]', true);
    if (!is_array($lands)) {
        echo json_encode(['success' => false, 'error' => 'invalid lands data']);
        return;
    }

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_lands
        (id, name, owner, world, x1, z1, x2, z2, y_min, y_max, area_size, created_at, synced_at)
        VALUES (:id, :name, :owner, :world, :x1, :z1, :x2, :z2, :ymin, :ymax, :size, :created, :synced)");

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
        $stmt->execute();
    }

    echo json_encode(['success' => true, 'count' => count($lands)]);
}

function handleSyncShop($db, $post) {
    $items = json_decode($post['items'] ?? '[]', true);
    if (!is_array($items)) {
        echo json_encode(['success' => false, 'error' => 'invalid shop data']);
        return;
    }

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_shop
        (id, land_id, land_name, seller, permission, price, duration, status, buyer, bought_at, created_at, synced_at)
        VALUES (:id, :land_id, :land_name, :seller, :perm, :price, :dur, :status, :buyer, :bought, :created, :synced)");

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
    }

    echo json_encode(['success' => true, 'count' => count($items)]);
}

function handleListLands($db) {
    $result = $db->query("SELECT * FROM web_area_lands ORDER BY synced_at DESC");
    $lands = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $lands[] = $row;
    }
    echo json_encode(['success' => true, 'lands' => $lands]);
}

function handleListShop($db) {
    $result = $db->query("SELECT * FROM web_area_shop ORDER BY created_at DESC LIMIT 100");
    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = $row;
    }
    echo json_encode(['success' => true, 'items' => $items]);
}

function handleGetConfig($db) {
    $config = [];
    $result = $db->query("SELECT * FROM web_area_config");
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $config[$row['key']] = $row['value'];
    }
    echo json_encode(['success' => true, 'config' => $config]);
}

function handleUpdateConfig($db, $post) {
    $key = $post['key'] ?? '';
    $value = $post['value'] ?? '';
    if (empty($key)) {
        echo json_encode(['success' => false, 'error' => 'missing key']);
        return;
    }

    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_config (key, value) VALUES (:key, :val)");
    $stmt->bindValue(':key', $key, SQLITE3_TEXT);
    $stmt->bindValue(':val', $value, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function handleDeleteLand($db, $post) {
    $name = $post['name'] ?? '';
    if (empty($name)) {
        echo json_encode(['success' => false, 'error' => 'missing name']);
        return;
    }

    $stmt = $db->prepare("DELETE FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function handleUpdateLandOwner($db, $post) {
    $name = $post['name'] ?? '';
    $owner = $post['owner'] ?? '';
    if (empty($name)) {
        echo json_encode(['success' => false, 'error' => 'missing name']);
        return;
    }

    $stmt = $db->prepare("UPDATE web_area_lands SET owner = :owner WHERE name = :name");
    $stmt->bindValue(':owner', $owner, SQLITE3_TEXT);
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

function handleDeleteShopItem($db, $post) {
    $id = (int)($post['id'] ?? 0);
    if ($id <= 0) {
        echo json_encode(['success' => false, 'error' => 'invalid id']);
        return;
    }

    $stmt = $db->prepare("UPDATE web_area_shop SET status = 'removed' WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ==================== Java端同步：访客权限 ====================

function handleSyncPermissions($db, $post) {
    $perms = json_decode($post['permissions'] ?? '[]', true);
    if (!is_array($perms)) {
        echo json_encode(['success' => false, 'error' => 'invalid permissions data']);
        return;
    }

    $now = time();
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_permissions
        (land_id, land_name, player_name, role, permissions, granted_at, expires_at, synced_at)
        VALUES (:land_id, :land_name, :player, :role, :perms, :granted, :expires, :synced)");

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
    }

    echo json_encode(['success' => true, 'count' => count($perms)]);
}

// ==================== 玩家端：我拥有的领地 ====================

function handleMyLands($db, $playerName) {
    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE owner = :owner ORDER BY created_at DESC");
    $stmt->bindValue(':owner', $playerName, SQLITE3_TEXT);
    $result = $stmt->execute();

    $lands = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $lands[] = $row;
    }

    echo json_encode(['success' => true, 'lands' => $lands, 'player' => $playerName]);
}

// ==================== 玩家端：领地详情（含访客） ====================

function handleLandDetail($db, $playerName, $landName) {
    if (empty($landName)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名称']);
        return;
    }

    // 获取领地信息
    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $landName, SQLITE3_TEXT);
    $result = $stmt->execute();
    $land = $result->fetchArray(SQLITE3_ASSOC);

    if (!$land) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }

    // 权限检查：只有所有者或管理员能查看详情
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '你不是此领地的所有者']);
        return;
    }

    // 获取访客列表
    $stmt2 = $db->prepare("SELECT * FROM web_area_permissions WHERE land_id = :land_id ORDER BY granted_at DESC");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $result2 = $stmt2->execute();

    $visitors = [];
    while ($row = $result2->fetchArray(SQLITE3_ASSOC)) {
        $visitors[] = $row;
    }

    echo json_encode(['success' => true, 'land' => $land, 'visitors' => $visitors]);
}

// ==================== 玩家端：添加访客 ====================

function handleAddVisitor($db, $playerName, $req) {
    $landName = $req['name'] ?? $req['land'] ?? '';
    $visitor = $req['visitor'] ?? $req['player'] ?? '';

    if (empty($landName) || empty($visitor)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名或玩家名']);
        return;
    }

    // 获取领地
    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $landName, SQLITE3_TEXT);
    $result = $stmt->execute();
    $land = $result->fetchArray(SQLITE3_ASSOC);

    if (!$land) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }

    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能添加访客']);
        return;
    }

    // 插入访客
    $stmt2 = $db->prepare("INSERT OR REPLACE INTO web_area_permissions
        (land_id, land_name, player_name, role, permissions, granted_at, expires_at, synced_at)
        VALUES (:land_id, :land_name, :player, 'visitor', '', :now, 0, :now)");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':land_name', $landName, SQLITE3_TEXT);
    $stmt2->bindValue(':player', $visitor, SQLITE3_TEXT);
    $stmt2->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt2->execute();

    echo json_encode(['success' => true, 'message' => "已添加访客: $visitor"]);
}

// ==================== 玩家端：移除访客 ====================

function handleRemoveVisitor($db, $playerName, $req) {
    $landName = $req['name'] ?? $req['land'] ?? '';
    $visitor = $req['visitor'] ?? $req['player'] ?? '';

    if (empty($landName) || empty($visitor)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名或玩家名']);
        return;
    }

    // 获取领地
    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $landName, SQLITE3_TEXT);
    $result = $stmt->execute();
    $land = $result->fetchArray(SQLITE3_ASSOC);

    if (!$land) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }

    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能移除访客']);
        return;
    }

    $stmt2 = $db->prepare("DELETE FROM web_area_permissions WHERE land_id = :land_id AND player_name = :player");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':player', $visitor, SQLITE3_TEXT);
    $stmt2->execute();

    echo json_encode(['success' => true, 'message' => "已移除访客: $visitor"]);
}

// ==================== 玩家端：访客列表 ====================

function handleListVisitors($db, $landName) {
    if (empty($landName)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名称']);
        return;
    }

    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $landName, SQLITE3_TEXT);
    $result = $stmt->execute();
    $land = $result->fetchArray(SQLITE3_ASSOC);

    if (!$land) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }

    $stmt2 = $db->prepare("SELECT * FROM web_area_permissions WHERE land_id = :land_id ORDER BY granted_at DESC");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $result2 = $stmt2->execute();

    $visitors = [];
    while ($row = $result2->fetchArray(SQLITE3_ASSOC)) {
        $visitors[] = $row;
    }

    echo json_encode(['success' => true, 'visitors' => $visitors]);
}

// ==================== 玩家端：领地商店 ====================

function handleLandShop($db, $landName) {
    $sql = "SELECT * FROM web_area_shop WHERE status = 'active'";
    $params = [];

    if (!empty($landName)) {
        $sql .= " AND land_name = :name";
        $params[':name'] = $landName;
    }
    $sql .= " ORDER BY created_at DESC LIMIT 50";

    $stmt = $db->prepare($sql);
    foreach ($params as $k => $v) {
        $stmt->bindValue($k, $v, SQLITE3_TEXT);
    }
    $result = $stmt->execute();

    $items = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $items[] = $row;
    }

    echo json_encode(['success' => true, 'items' => $items]);
}

function validateSecret($secret) {
    if (empty($secret)) return false;
    $validSecrets = [
        'sdf1_web_comm_2026_ypshidifu'
    ];
    return in_array($secret, $validSecrets);
}
