<?php
/**
 * 领地系统API - 接收Java端领地数据 + 管理面板
 * POST: Java端推送领地数据
 * GET: admin面板读取领地数据
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
    if ($method === 'POST') {
        if (!validateSecret($secret)) {
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

function validateSecret($secret) {
    if (empty($secret)) return false;
    $validSecrets = [
        'sdf1_web_comm_2026_ypshidifu'
    ];
    return in_array($secret, $validSecrets);
}
