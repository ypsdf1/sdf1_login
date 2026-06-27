<?php
/**
 * 领地系统API - 接收Java端领地数据 + 管理面板 + 玩家端
 * POST: Java端推送领地数据
 * GET: admin/玩家面板读取领地数据
 */
header('Content-Type: application/json; charset=utf-8');
error_reporting(E_ERROR | E_PARSE);

/**
 * ★ 将Java自定义格式转换为JSON数组
 * give_effects: "夜视:1:99999|SPEED:2:60" → [["夜视","1","99999"],["SPEED","2","60"]]
 * clear_effects: "POISON,WITHER" → ["POISON","WITHER"]
 */
function convertEffectsForFrontend($row) {
    if (!empty($row['give_effects']) && $row['give_effects'][0] !== '[') {
        $parts = explode('|', $row['give_effects']);
        $arr = [];
        foreach ($parts as $p) {
            $p = trim($p);
            if (empty($p)) continue;
            $pieces = explode(':', $p);
            if (count($pieces) >= 2) {
                $arr[] = $pieces;
            } else {
                $arr[] = [$pieces[0], '1', '99999'];
            }
        }
        $row['give_effects'] = json_encode($arr);
    }
    if (!empty($row['clear_effects']) && $row['clear_effects'][0] !== '[') {
        $names = array_filter(explode(',', $row['clear_effects']), function($n) { return !empty(trim($n)); });
        $row['clear_effects'] = json_encode(array_values(array_map('trim', $names)));
    }
    return $row;
}

// ★ 29种权限类型定义（必须在try之前，否则handleGetMemberPerms调用时变量未定义）
$PERM_TYPES = [
    'denyMove' => '移动',
    'denyBlockPlace' => '放置方块',
    'denyBlockBreak' => '破坏方块',
    'denyContainer' => '容器管理',
    'denyPVP' => 'PVP战斗',
    'denyMount' => '骑乘',
    'denyEnderPearl' => '末影珍珠',
    'denyThrownProjectiles' => '投掷物',
    'denyRaid' => '袭击',
    'denyBow' => '弓箭',
    'denyPotion' => '药水',
    'denyFire' => '点火',
    'denyFireSpread' => '火焰蔓延',
    'denyPickup' => '拾取物品',
    'denyDrop' => '丢弃物品',
    'denyExplosion' => '爆炸',
    'denyFallDamage' => '摔落伤害',
    'denyHunger' => '饥饿',
    'denyAllDamage' => '所有伤害',
    'denyAllEffects' => '所有效果',
    'denyItemFrame' => '物品展示框',
    'denyRedstoneInteraction' => '红石交互',
    'denyDoorInteraction' => '门禁交互',
    'denyNoteblockJukebox' => '音符盒/唱片机',
    'denyLead' => '拴绳',
    'denyCropHarvest' => '收割作物',
    'denyWoolShear' => '剪羊毛',
    'denyAnimalFeeding' => '投喂动物',
    'denyGlowing' => '发光效果',
    'denyPeaceMode' => '和平模式'
];

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
    $adminActions = ['list_lands', 'list_shop', 'get_config', 'update_config', 'delete_land', 'update_land_owner', 'delete_shop_item', 'list_user_groups', 'get_user_group', 'update_user_group', 'delete_user_group', 'list_group_members', 'add_group_member', 'remove_group_member', 'get_player_groups'];
    // 玩家端action：需要token
    $playerActions = ['my_lands', 'land_detail', 'add_visitor', 'remove_visitor', 'list_visitors', 'land_shop', 'buy_permission'];
    // ★ 玩家端领地字段更新（效果管理、开关等）
    $playerFieldActions = ['update_land_field'];
    // ★ 玩家端权限操作action
    $playerPermActions = ['update_visitor_perm', 'get_visitor_perm'];
    // ★ 成员独立权限操作action（领地所有者编辑成员权限）
    $memberPermActions = ['get_member_perms', 'update_member_perm', 'clear_member_perm'];
    // ★ Java端轮询PHP管理员变更
    $syncFromPhpActions = ['poll_admin_changes', 'ack_admin_changes'];

    if (in_array($action, $syncActions) || in_array($action, $syncFromPhpActions)) {
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
    } elseif (in_array($action, $playerActions) || in_array($action, $playerPermActions) || in_array($action, $memberPermActions) || in_array($action, $playerFieldActions)) {
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

        // ===== 玩家端：获取访客权限 =====
        case 'get_visitor_perm':
            handleGetVisitorPerm($db, $playerName, $_GET['land'] ?? '');
            break;

        // ===== 玩家端：更新访客权限 =====
        case 'update_visitor_perm':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleUpdateVisitorPerm($db, $playerName, $_POST);
            break;

        // ===== 成员独立权限：获取成员权限列表 =====
        case 'get_member_perms':
            handleGetMemberPerms($db, $playerName, $_GET['land'] ?? '');
            break;

        // ===== 成员独立权限：更新单个权限 =====
        case 'update_member_perm':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleUpdateMemberPerm($db, $playerName, $_POST);
            break;

        // ===== 成员独立权限：清除所有自定义权限 =====
        case 'clear_member_perm':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleClearMemberPerm($db, $playerName, $_POST);
            break;

        // ===== 玩家端：更新领地字段（效果管理、开关等）=====
        case 'update_land_field':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleUpdateLandField($db, $playerName, $_POST);
            break;

        // ===== Java端轮询PHP管理员变更 =====
        case 'poll_admin_changes':
            handlePollAdminChanges($db, $_GET);
            break;

        // ===== Java端确认已处理变更（GET/POST兼容）=====
        case 'ack_admin_changes':
            handleAckAdminChanges($db, $_POST + $_GET);
            break;

        // ===== 用户组管理 =====
        case 'list_user_groups':
            handleListUserGroups($db);
            break;
        case 'get_user_group':
            handleGetUserGroup($db, $_GET['name'] ?? '');
            break;
        case 'update_user_group':
            handleUpdateUserGroup($db, $_POST + $_GET);
            break;
        case 'delete_user_group':
            handleDeleteUserGroup($db, $_GET['name'] ?? '');
            break;
        case 'list_group_members':
            handleListGroupMembers($db, $_GET['group'] ?? '');
            break;
        case 'add_group_member':
            handleAddGroupMember($db, $_POST + $_GET);
            break;
        case 'remove_group_member':
            handleRemoveGroupMember($db, $_POST + $_GET);
            break;
        case 'get_player_groups':
            handleGetPlayerGroups($db, $_GET['player'] ?? '');
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

    // ★ 效果管理字段迁移（兼容已有表）
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN clear_effects TEXT DEFAULT ''"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN give_effects TEXT DEFAULT ''"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN clear_all_bad_effects INTEGER DEFAULT 0"); } catch (\Throwable $e) {}
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN deny_all_effects INTEGER DEFAULT 0"); } catch (\Throwable $e) {}
    // ★ 管理变更标记
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN admin_changed INTEGER DEFAULT 0"); } catch (\Throwable $e) {}

    // ★ 权限字段迁移（Java→PHP完整同步）
    $permColumns = [
        'confiscate_items' => "TEXT DEFAULT ''",
        'deny_use_items' => "TEXT DEFAULT ''",
        'punish_commands' => "TEXT DEFAULT ''",
        'deny_block_place' => "INTEGER DEFAULT 0",
        'deny_block_break' => "INTEGER DEFAULT 0",
        'deny_pvp' => "INTEGER DEFAULT 0",
        'deny_fall_damage' => "INTEGER DEFAULT 0",
        'deny_hunger' => "INTEGER DEFAULT 0",
        'deny_all_damage' => "INTEGER DEFAULT 0",
        'deny_drop' => "INTEGER DEFAULT 0",
        'deny_mount' => "INTEGER DEFAULT 0",
        'deny_ender_pearl' => "INTEGER DEFAULT 0",
        'deny_bow' => "INTEGER DEFAULT 0",
        'deny_potion' => "INTEGER DEFAULT 0",
        'deny_explosion' => "INTEGER DEFAULT 0",
        'deny_raid' => "INTEGER DEFAULT 0",
        'deny_fire_spread' => "INTEGER DEFAULT 0",
        'deny_item_frame' => "INTEGER DEFAULT 0",
        'deny_move' => "INTEGER DEFAULT 0",
        'deny_pickup' => "INTEGER DEFAULT 0",
        'deny_fire' => "INTEGER DEFAULT 0",
        'deny_thrown_projectiles' => "INTEGER DEFAULT 0",
        'deny_glowing' => "INTEGER DEFAULT 0",
        'deny_redstone_interaction' => "INTEGER DEFAULT 0",
        'deny_door_interaction' => "INTEGER DEFAULT 0",
        'deny_noteblock_jukebox' => "INTEGER DEFAULT 0",
        'deny_lead' => "INTEGER DEFAULT 0",
        'deny_crop_harvest' => "INTEGER DEFAULT 0",
        'deny_wool_shear' => "INTEGER DEFAULT 0",
        'deny_animal_feeding' => "INTEGER DEFAULT 0",
        'deny_mob_attack' => "INTEGER DEFAULT 0",
        'deny_container' => "INTEGER DEFAULT 0",
        'peace_mode' => "INTEGER DEFAULT 0",
        'peace_mode_duration' => "INTEGER DEFAULT 0",
        'peace_whitelist' => "TEXT DEFAULT ''",
        'enforce_game_mode' => "TEXT DEFAULT ''",
        'mode_exempt' => "TEXT DEFAULT ''",
        'enter_msg' => "TEXT DEFAULT ''",
        'leave_msg' => "TEXT DEFAULT ''",
        'confiscate_msg' => "TEXT DEFAULT ''",
        'warp_x' => "REAL DEFAULT 0",
        'warp_y' => "REAL DEFAULT 0",
        'warp_z' => "REAL DEFAULT 0",
        'warp_yaw' => "REAL DEFAULT 0",
        'warp_pitch' => "REAL DEFAULT 0",
        'warp_world' => "TEXT DEFAULT ''",
        'enable_announce' => "INTEGER DEFAULT 0",
        'announce_template' => "TEXT DEFAULT ''",
        'txt_content' => "TEXT DEFAULT ''",
        'deny_fluid' => "INTEGER DEFAULT 0",
        'clear_all_bad' => "INTEGER DEFAULT 0",
        'is_public_building' => "INTEGER DEFAULT 0",
    ];
    foreach ($permColumns as $col => $type) {
        try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN $col $type"); } catch (\Throwable $e) {}
    }

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

    // ★ 管理员变更队列表（PHP→Java同步）
    $db->exec("CREATE TABLE IF NOT EXISTS web_admin_changes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        change_type TEXT NOT NULL,
        target_id TEXT NOT NULL,
        target_name TEXT DEFAULT '',
        change_data TEXT DEFAULT '',
        created_at INTEGER DEFAULT 0,
        acknowledged INTEGER DEFAULT 0,
        acked_at INTEGER DEFAULT 0
    )");

    // ★ 领地所有者变更表（记录PHP端修改的所有者变更）
    $db->exec("CREATE TABLE IF NOT EXISTS web_land_owner_changes (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        land_id INTEGER NOT NULL,
        land_name TEXT NOT NULL,
        old_owner TEXT DEFAULT '',
        new_owner TEXT NOT NULL,
        created_at INTEGER DEFAULT 0,
        synced INTEGER DEFAULT 0
    )");
}

function handleSyncLands($db, $post) {
    $lands = json_decode($post['lands'] ?? '[]', true);
    if (!is_array($lands)) {
        echo json_encode(['success' => false, 'error' => 'invalid lands data']);
        return;
    }

    $now = time();

    // ★ 完整字段INSERT（与Java端area_lands表全部列对应）
    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_lands
        (id, name, owner, world, x1, z1, x2, z2, y_min, y_max,
         area_size, created_at, synced_at,
         confiscate_items, deny_use_items, give_effects, clear_effects, clear_all_bad,
         punish_commands, deny_block_place, deny_block_break, deny_pvp, deny_fall_damage,
         deny_hunger, deny_all_damage, deny_drop, deny_mount, deny_ender_pearl,
         deny_bow, deny_potion, deny_explosion, deny_raid, deny_fire_spread,
         deny_all_effects, deny_item_frame, deny_move, deny_pickup, deny_fire,
         peace_mode, peace_mode_duration,
         peace_whitelist, enforce_game_mode, mode_exempt, enter_msg, leave_msg,
         confiscate_msg, deny_thrown_projectiles, deny_glowing, deny_redstone_interaction,
         deny_door_interaction, deny_noteblock_jukebox, deny_lead, deny_crop_harvest,
         deny_wool_shear, deny_animal_feeding,
         warp_x, warp_y, warp_z, warp_yaw, warp_pitch, warp_world,
         deny_container, deny_mob_attack,
         enable_announce, announce_template, txt_content, deny_fluid, is_public_building)
        VALUES (:id, :name, :owner, :world, :x1, :z1, :x2, :z2, :ymin, :ymax,
                :size, :created, :synced,
                :confiscate_items, :deny_use_items, :give_effects, :clear_effects, :clear_all_bad,
                :punish_commands, :deny_block_place, :deny_block_break, :deny_pvp, :deny_fall_damage,
                :deny_hunger, :deny_all_damage, :deny_drop, :deny_mount, :deny_ender_pearl,
                :deny_bow, :deny_potion, :deny_explosion, :deny_raid, :deny_fire_spread,
                :deny_all_effects, :deny_item_frame, :deny_move, :deny_pickup, :deny_fire,
                :peace_mode, :peace_mode_duration,
                :peace_whitelist, :enforce_game_mode, :mode_exempt, :enter_msg, :leave_msg,
                :confiscate_msg, :deny_thrown_projectiles, :deny_glowing, :deny_redstone_interaction,
                :deny_door_interaction, :deny_noteblock_jukebox, :deny_lead, :deny_crop_harvest,
                :deny_wool_shear, :deny_animal_feeding,
                :warp_x, :warp_y, :warp_z, :warp_yaw, :warp_pitch, :warp_world,
                :deny_container, :deny_mob_attack,
                :enable_announce, :announce_template, :txt_content, :deny_fluid, :is_public_building)");

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
        // ★ 权限字段
        $stmt->bindValue(':confiscate_items', $land['confiscate_items'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':deny_use_items', $land['deny_use_items'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':give_effects', $land['give_effects'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':clear_effects', $land['clear_effects'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':clear_all_bad', (int)($land['clear_all_bad'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':punish_commands', $land['punish_commands'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':deny_block_place', (int)($land['deny_block_place'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_block_break', (int)($land['deny_block_break'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_pvp', (int)($land['deny_pvp'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fall_damage', (int)($land['deny_fall_damage'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_hunger', (int)($land['deny_hunger'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_all_damage', (int)($land['deny_all_damage'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_drop', (int)($land['deny_drop'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_mount', (int)($land['deny_mount'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_ender_pearl', (int)($land['deny_ender_pearl'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_bow', (int)($land['deny_bow'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_potion', (int)($land['deny_potion'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_explosion', (int)($land['deny_explosion'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_raid', (int)($land['deny_raid'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fire_spread', (int)($land['deny_fire_spread'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_all_effects', (int)($land['deny_all_effects'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_item_frame', (int)($land['deny_item_frame'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_move', (int)($land['deny_move'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_pickup', (int)($land['deny_pickup'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_fire', (int)($land['deny_fire'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':peace_mode', (int)($land['peace_mode'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':peace_mode_duration', (int)($land['peace_mode_duration'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':peace_whitelist', $land['peace_whitelist'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':enforce_game_mode', $land['enforce_game_mode'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':mode_exempt', $land['mode_exempt'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':enter_msg', $land['enter_msg'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':leave_msg', $land['leave_msg'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':confiscate_msg', $land['confiscate_msg'] ?? '', SQLITE3_TEXT);
        // ★ 新增权限字段
        $stmt->bindValue(':deny_thrown_projectiles', (int)($land['deny_thrown_projectiles'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_glowing', (int)($land['deny_glowing'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_redstone_interaction', (int)($land['deny_redstone_interaction'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_door_interaction', (int)($land['deny_door_interaction'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_noteblock_jukebox', (int)($land['deny_noteblock_jukebox'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_lead', (int)($land['deny_lead'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_crop_harvest', (int)($land['deny_crop_harvest'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_wool_shear', (int)($land['deny_wool_shear'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_animal_feeding', (int)($land['deny_animal_feeding'] ?? 0), SQLITE3_INTEGER);
        // ★ 传送点
        $stmt->bindValue(':warp_x', (float)($land['warp_x'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_y', (float)($land['warp_y'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_z', (float)($land['warp_z'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_yaw', (float)($land['warp_yaw'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_pitch', (float)($land['warp_pitch'] ?? 0), SQLITE3_FLOAT);
        $stmt->bindValue(':warp_world', $land['warp_world'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':deny_container', (int)($land['deny_container'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':deny_mob_attack', (int)($land['deny_mob_attack'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':enable_announce', (int)($land['enable_announce'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':announce_template', $land['announce_template'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':txt_content', $land['txt_content'] ?? '', SQLITE3_TEXT);
        $stmt->bindValue(':deny_fluid', (int)($land['deny_fluid'] ?? 0), SQLITE3_INTEGER);
        $stmt->bindValue(':is_public_building', (int)($land['is_public_building'] ?? 0), SQLITE3_INTEGER);
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
        $lands[] = convertEffectsForFrontend($row);
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

    // ★ 去除前导零：01 → 1，防止静默失败
    if (is_numeric($value)) $value = (string)(int)$value;

    $stmt = $db->prepare("INSERT OR REPLACE INTO web_area_config (key, value) VALUES (:key, :val)");
    $stmt->bindValue(':key', $key, SQLITE3_TEXT);
    $stmt->bindValue(':val', $value, SQLITE3_TEXT);
    $stmt->execute();

    // ★ 写入变更队列，通知Java同步配置
    try {
        $changeStmt = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('config_change', 0, :key, :data, :now)");
        $changeStmt->bindValue(':key', $key, SQLITE3_TEXT);
        $changeStmt->bindValue(':data', json_encode(['key' => $key, 'value' => $value]), SQLITE3_TEXT);
        $changeStmt->bindValue(':now', time(), SQLITE3_INTEGER);
        $changeStmt->execute();
    } catch (\Throwable $e) {
        // 非致命
    }

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

    // ★ 验证新所有者
    if (empty($owner)) {
        echo json_encode(['success' => false, 'error' => '新所有者不能为空']);
        return;
    }
    if (!preg_match('/^[a-zA-Z0-9_]{2,16}$/', $owner)) {
        echo json_encode(['success' => false, 'error' => '玩家名格式无效：仅允许英文字母、数字和下划线，2-16位']);
        return;
    }
    if (!playerExists($db, $owner)) {
        echo json_encode(['success' => false, 'error' => "玩家 §e{$owner} §f未注册，请确认后再试"]);
        return;
    }

    // 获取旧所有者
    $stmt = $db->prepare("SELECT owner FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    $oldOwner = $row ? ($row['owner'] ?? '') : '';

    if ($oldOwner === $owner) {
        echo json_encode(['success' => false, 'error' => '新旧所有者相同，无需更改']);
        return;
    }

    // 更新所有者
    $stmt2 = $db->prepare("UPDATE web_area_lands SET owner = :owner WHERE name = :name");
    $stmt2->bindValue(':owner', $owner, SQLITE3_TEXT);
    $stmt2->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt2->execute();

    // 记录变更（用于同步到Java）
    if ($oldOwner !== $owner) {
        $landId = (int)($post['id'] ?? 0);
        $stmt3 = $db->prepare("INSERT INTO web_land_owner_changes (land_id, land_name, old_owner, new_owner, created_at) VALUES (:lid, :name, :old, :new, :now)");
        $stmt3->bindValue(':lid', $landId, SQLITE3_INTEGER);
        $stmt3->bindValue(':name', $name, SQLITE3_TEXT);
        $stmt3->bindValue(':old', $oldOwner, SQLITE3_TEXT);
        $stmt3->bindValue(':new', $owner, SQLITE3_TEXT);
        $stmt3->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmt3->execute();

        // 同时添加到变更队列
        $stmt4 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('owner_change', :id, :name, :data, :now)");
        $stmt4->bindValue(':id', (int)($post['id'] ?? 0), SQLITE3_INTEGER);
        $stmt4->bindValue(':name', $name, SQLITE3_TEXT);
        $stmt4->bindValue(':data', json_encode(['old_owner' => $oldOwner, 'new_owner' => $owner]), SQLITE3_TEXT);
        $stmt4->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmt4->execute();
    }

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
        $lands[] = convertEffectsForFrontend($row);
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

    echo json_encode(['success' => true, 'land' => convertEffectsForFrontend($land), 'visitors' => $visitors]);
}

// ==================== 玩家端：添加访客 ====================

function handleAddVisitor($db, $playerName, $req) {
    $landName = $req['name'] ?? $req['land'] ?? '';
    $visitor = $req['visitor'] ?? $req['player'] ?? '';

    if (empty($landName) || empty($visitor)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名或玩家名']);
        return;
    }

    // ★ 校验玩家名格式（至少3字符，只含字母数字下划线）
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $visitor)) {
        echo json_encode(['success' => false, 'error' => '无效的玩家名: ' . $visitor]);
        return;
    }

    // ★ 校验玩家是否存在于数据库（login.db或web用户表）
    if (!playerExists($db, $visitor)) {
        echo json_encode(['success' => false, 'error' => '玩家不存在: ' . $visitor . '，请确认玩家已注册']);
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

/**
 * ★ 检查玩家是否存在于数据库
 */
function playerExists($db, $playerName) {
    // 检查web用户表
    try {
        $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM weblogin_tokens WHERE player = :player");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && $row['cnt'] > 0) return true;
    } catch (\Throwable $e) {
        // 表可能不存在，忽略
    }

    // 检查users表
    try {
        $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM users WHERE player_name = :player");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && $row['cnt'] > 0) return true;
    } catch (\Throwable $e) {
        // 表可能不存在，忽略
    }

    // 检查tokens表
    try {
        $stmt = $db->prepare("SELECT COUNT(*) as cnt FROM tokens WHERE player = :player");
        $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        if ($row && $row['cnt'] > 0) return true;
    } catch (\Throwable $e) {
        // 表可能不存在，忽略
    }

    return false;
}

// ==================== 玩家端：更新领地字段（效果管理）====================

function handleUpdateLandField($db, $playerName, $post) {
    $name = $post['name'] ?? '';
    $field = $post['field'] ?? '';
    $value = $post['value'] ?? '';

    if (empty($name) || empty($field)) {
        echo json_encode(['success' => false, 'error' => '缺少参数']);
        return;
    }

    // 允许的字段白名单
    $allowedFields = ['clear_effects', 'give_effects', 'clear_all_bad_effects', 'deny_all_effects'];
    if (!in_array($field, $allowedFields)) {
        echo json_encode(['success' => false, 'error' => '不允许的字段: ' . $field]);
        return;
    }

    // 校验领地所有权
    $stmt = $db->prepare("SELECT * FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $result = $stmt->execute();
    $land = $result->fetchArray(SQLITE3_ASSOC);

    if (!$land) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '你不是此领地的所有者']);
        return;
    }

    // 确保字段存在（容错）
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN {$field} TEXT DEFAULT ''"); } catch (\Throwable $e) {}

    // ★ 将JSON数组转回Java格式再存储
    $storeValue = $value;
    if (($field === 'give_effects' || $field === 'clear_effects') && !empty($value) && $value[0] === '[') {
        $arr = json_decode($value, true);
        if (is_array($arr)) {
            if ($field === 'give_effects') {
                // [["夜视","1","99999"]] → "夜视:1:99999"
                $parts = [];
                foreach ($arr as $e) {
                    if (is_array($e)) {
                        $parts[] = implode(':', $e);
                    } else {
                        $parts[] = (string)$e;
                    }
                }
                $storeValue = implode('|', $parts);
            } else {
                // clear_effects: ["POISON","WITHER"] → "POISON,WITHER"
                $storeValue = implode(',', array_map(function($e) {
                    return is_array($e) ? $e[0] : (string)$e;
                }, $arr));
            }
        }
    }

    // 更新
    $stmt2 = $db->prepare("UPDATE web_area_lands SET {$field} = :value, admin_changed = 1 WHERE name = :name");
    $stmt2->bindValue(':value', $storeValue, SQLITE3_TEXT);
    $stmt2->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt2->execute();

    // 记录变更到变更队列（Java端轮询）
    $stmt3 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('land_field_change', :lid, :name, :data, :now)");
    $stmt3->bindValue(':lid', (int)$land['id'], SQLITE3_INTEGER);
    $stmt3->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt3->bindValue(':data', json_encode(['field' => $field, 'value' => $value]), SQLITE3_TEXT);
    $stmt3->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt3->execute();

    echo json_encode(['success' => true]);
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

// ==================== 玩家端：获取访客权限 ====================

function handleGetVisitorPerm($db, $playerName, $landName) {
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

    // 获取该玩家的权限
    $stmt2 = $db->prepare("SELECT * FROM web_area_permissions WHERE land_id = :land_id AND player_name = :player");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':player', $playerName, SQLITE3_TEXT);
    $result2 = $stmt2->execute();
    $perm = $result2->fetchArray(SQLITE3_ASSOC);

    echo json_encode(['success' => true, 'permission' => $perm]);
}

// ==================== 玩家端：更新访客权限 ====================

function handleUpdateVisitorPerm($db, $playerName, $post) {
    $landName = $post['land'] ?? '';
    $visitor = $post['visitor'] ?? '';
    $permissions = $post['permissions'] ?? '';
    $role = $post['role'] ?? 'visitor';

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

    // 权限检查：只有所有者能更新权限
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能更新权限']);
        return;
    }

    // 更新权限
    $stmt2 = $db->prepare("INSERT OR REPLACE INTO web_area_permissions
        (land_id, land_name, player_name, role, permissions, granted_at, expires_at, synced_at)
        VALUES (:land_id, :land_name, :player, :role, :perms, :now, 0, :now)");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':land_name', $landName, SQLITE3_TEXT);
    $stmt2->bindValue(':player', $visitor, SQLITE3_TEXT);
    $stmt2->bindValue(':role', $role, SQLITE3_TEXT);
    $stmt2->bindValue(':perms', $permissions, SQLITE3_TEXT);
    $stmt2->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt2->execute();

    // ★ 写入web_admin_changes以便Java同步
    $stmt4 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('perm_change', :id, :name, :data, :now)");
    $stmt4->bindValue(':id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt4->bindValue(':name', $visitor, SQLITE3_TEXT);
    $stmt4->bindValue(':data', json_encode(['land_name' => $landName, 'permissions' => $permissions, 'role' => $role]), SQLITE3_TEXT);
    $stmt4->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt4->execute();

    echo json_encode(['success' => true, 'message' => "已更新 $visitor 的权限"]);
}

// ==================== 成员独立权限操作 ====================

function handleGetMemberPerms($db, $playerName, $landName) {
    global $PERM_TYPES;

    if (empty($landName)) {
        echo json_encode(['success' => false, 'error' => '缺少领地名称']);
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

    // 权限检查：只有所有者能编辑成员权限
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能编辑成员权限']);
        return;
    }

    // 获取所有成员及其权限
    $stmt2 = $db->prepare("SELECT * FROM web_area_permissions WHERE land_id = :land_id ORDER BY player_name");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $result2 = $stmt2->execute();

    $members = [];
    while ($row = $result2->fetchArray(SQLITE3_ASSOC)) {
        // 解析权限JSON
        $permJson = $row['permissions'] ?? '';
        $perms = [];
        if (!empty($permJson)) {
            $decoded = json_decode($permJson, true);
            if (is_array($decoded)) {
                $perms = $decoded;
            }
        }
        $row['perm_map'] = $perms;
        $members[] = $row;
    }

    echo json_encode([
        'success' => true,
        'land' => $land,
        'members' => $members,
        'perm_types' => $PERM_TYPES
    ]);
}

function handleUpdateMemberPerm($db, $playerName, $post) {
    $landName = $post['land'] ?? '';
    $targetPlayer = $post['player'] ?? '';
    $permKey = $post['perm'] ?? '';
    $enabled = isset($post['enabled']) ? (bool)$post['enabled'] : false;

    if (empty($landName) || empty($targetPlayer) || empty($permKey)) {
        echo json_encode(['success' => false, 'error' => '缺少参数']);
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

    // 权限检查
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能编辑成员权限']);
        return;
    }

    // 获取当前权限
    $stmt2 = $db->prepare("SELECT * FROM web_area_permissions WHERE land_id = :land_id AND player_name = :player");
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':player', $targetPlayer, SQLITE3_TEXT);
    $result2 = $stmt2->execute();
    $perm = $result2->fetchArray(SQLITE3_ASSOC);

    if (!$perm) {
        echo json_encode(['success' => false, 'error' => '该玩家不是领地成员']);
        return;
    }

    // 解析现有权限
    $permMap = [];
    $permJson = $perm['permissions'] ?? '';
    if (!empty($permJson)) {
        $decoded = json_decode($permJson, true);
        if (is_array($decoded)) {
            $permMap = $decoded;
        }
    }

    // 更新权限
    if ($enabled) {
        $permMap[$permKey] = true;
    } else {
        unset($permMap[$permKey]);
    }

    // 写回数据库
    $newJson = json_encode($permMap, JSON_UNESCAPED_UNICODE);
    $stmt3 = $db->prepare("UPDATE web_area_permissions SET permissions = :perms, synced_at = :now WHERE land_id = :land_id AND player_name = :player");
    $stmt3->bindValue(':perms', $newJson, SQLITE3_TEXT);
    $stmt3->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt3->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt3->bindValue(':player', $targetPlayer, SQLITE3_TEXT);
    $stmt3->execute();

    // ★ 记录变更到变更队列（Java端轮询同步回本地）
    try {
        $changeStmt = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('perm_change', :lid, :name, :data, :now)");
        $changeStmt->bindValue(':lid', (int)$land['id'], SQLITE3_INTEGER);
        $changeStmt->bindValue(':name', $targetPlayer, SQLITE3_TEXT);
        $changeStmt->bindValue(':data', json_encode(['land_name' => $landName, 'permissions' => $newJson], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
        $changeStmt->bindValue(':now', time(), SQLITE3_INTEGER);
        $changeStmt->execute();
    } catch (\Throwable $e) {
        // 非致命，只是Java同步会延迟
    }

    echo json_encode(['success' => true, 'message' => "已更新 $targetPlayer 的 $permKey 权限"]);
}

function handleClearMemberPerm($db, $playerName, $post) {
    $landName = $post['land'] ?? '';
    $targetPlayer = $post['player'] ?? '';

    if (empty($landName) || empty($targetPlayer)) {
        echo json_encode(['success' => false, 'error' => '缺少参数']);
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

    // 权限检查
    if ($land['owner'] !== $playerName) {
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能清除成员权限']);
        return;
    }

    // 获取旧权限
    $stmt_old = $db->prepare("SELECT permissions FROM web_area_permissions WHERE land_id = :land_id AND player_name = :player");
    $stmt_old->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt_old->bindValue(':player', $targetPlayer, SQLITE3_TEXT);
    $result_old = $stmt_old->execute();
    $old_perm = $result_old->fetchArray(SQLITE3_ASSOC);
    $old_perms = $old_perm ? ($old_perm['permissions'] ?? '') : '';

    // 清除权限
    $stmt2 = $db->prepare("UPDATE web_area_permissions SET permissions = '', synced_at = :now WHERE land_id = :land_id AND player_name = :player");
    $stmt2->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':player', $targetPlayer, SQLITE3_TEXT);
    $stmt2->execute();

    // 记录变更
    if (!empty($old_perms)) {
        $stmt3 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('perm_clear', :id, :name, :data, :now)");
        $stmt3->bindValue(':id', (int)$land['id'], SQLITE3_INTEGER);
        $stmt3->bindValue(':name', $targetPlayer, SQLITE3_TEXT);
        $stmt3->bindValue(':data', json_encode(['land_name' => $landName, 'old_permissions' => $old_perms]), SQLITE3_TEXT);
        $stmt3->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmt3->execute();
    }

    echo json_encode(['success' => true, 'message' => "已清除 $targetPlayer 的所有自定义权限"]);
}

// ==================== Java端轮询PHP管理员变更 ====================

function handlePollAdminChanges($db, $get) {
    $lastId = (int)($get['last_id'] ?? 0);
    $limit = min((int)($get['limit'] ?? 50), 100);

    // 获取未确认的变更
    $stmt = $db->prepare("SELECT * FROM web_admin_changes WHERE id > :last_id AND acknowledged = 0 ORDER BY id ASC LIMIT :limit");
    $stmt->bindValue(':last_id', $lastId, SQLITE3_INTEGER);
    $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    $result = $stmt->execute();

    $changes = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $changes[] = $row;
    }

    echo json_encode(['success' => true, 'changes' => $changes, 'count' => count($changes)], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

function handleAckAdminChanges($db, $post) {
    $ids = $post['ids'] ?? ($_GET['ids'] ?? '');
    if (empty($ids)) {
        echo json_encode(['success' => false, 'error' => 'missing ids']);
        return;
    }

    $idList = array_filter(array_map('intval', explode(',', $ids)));
    if (empty($idList)) {
        echo json_encode(['success' => false, 'error' => 'invalid ids']);
        return;
    }

    $now = time();
    $placeholders = implode(',', array_fill(0, count($idList), '?'));
    $stmt = $db->prepare("UPDATE web_admin_changes SET acknowledged = 1, acked_at = :now WHERE id IN ($placeholders)");
    $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
    $i = 1;
    foreach ($idList as $id) {
        $stmt->bindValue($i++, $id, SQLITE3_INTEGER);
    }
    $stmt->execute();

    echo json_encode(['success' => true, 'acked' => count($idList)]);
}

// ===== 用户组管理函数 =====

function handleListUserGroups($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
        group_name TEXT PRIMARY KEY,
        display_name TEXT DEFAULT '',
        display_color TEXT DEFAULT '§f',
        display_emoji TEXT DEFAULT '',
        priority INTEGER DEFAULT 0,
        land_price_per_sqm INTEGER DEFAULT -1,
        max_lands INTEGER DEFAULT -1,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");
    $rs = $db->query("SELECT * FROM web_user_groups ORDER BY priority DESC");
    $groups = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $groups[] = $row;
    }
    echo json_encode(['success' => true, 'groups' => $groups]);
}

function handleGetUserGroup($db, $name) {
    if (empty($name)) { echo json_encode(['success' => false, 'error' => 'missing name']); return; }
    $stmt = $db->prepare("SELECT * FROM web_user_groups WHERE group_name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$row) { echo json_encode(['success' => false, 'error' => 'group not found']); return; }
    echo json_encode(['success' => true, 'group' => $row]);
}

function handleUpdateUserGroup($db, $data) {
    $name = $data['name'] ?? $data['group_name'] ?? '';
    if (empty($name)) { echo json_encode(['success' => false, 'error' => 'missing name']); return; }

    $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
        group_name TEXT PRIMARY KEY,
        display_name TEXT DEFAULT '',
        display_color TEXT DEFAULT '§f',
        display_emoji TEXT DEFAULT '',
        priority INTEGER DEFAULT 0,
        land_price_per_sqm INTEGER DEFAULT -1,
        max_lands INTEGER DEFAULT -1,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");

    $displayName = $data['display_name'] ?? '';
    $displayColor = $data['display_color'] ?? '§f';
    $displayEmoji = $data['display_emoji'] ?? '';
    $priority = (int)($data['priority'] ?? 0);
    $pricePerSqm = (int)($data['land_price_per_sqm'] ?? -1);
    $maxLands = (int)($data['max_lands'] ?? -1);
    $defaultPerms = $data['default_perms'] ?? '{}';

    $stmt = $db->prepare("INSERT OR REPLACE INTO web_user_groups
        (group_name, display_name, display_color, display_emoji, priority,
         land_price_per_sqm, max_lands, default_perms, synced_at)
        VALUES (:name, :display, :color, :emoji, :priority,
                :price, :maxlands, :perms, :synced)");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->bindValue(':display', $displayName, SQLITE3_TEXT);
    $stmt->bindValue(':color', $displayColor, SQLITE3_TEXT);
    $stmt->bindValue(':emoji', $displayEmoji, SQLITE3_TEXT);
    $stmt->bindValue(':priority', $priority, SQLITE3_INTEGER);
    $stmt->bindValue(':price', $pricePerSqm, SQLITE3_INTEGER);
    $stmt->bindValue(':maxlands', $maxLands, SQLITE3_INTEGER);
    $stmt->bindValue(':perms', $defaultPerms, SQLITE3_TEXT);
    $stmt->bindValue(':synced', time(), SQLITE3_INTEGER);
    $stmt->execute();

    // ★ 写入变更队列，通知Java端重新加载用户组
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS web_admin_changes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            change_type TEXT NOT NULL,
            target_id TEXT DEFAULT '',
            target_name TEXT DEFAULT '',
            change_data TEXT DEFAULT '{}',
            created_at INTEGER DEFAULT 0,
            acknowledged INTEGER DEFAULT 0,
            acked_at INTEGER DEFAULT 0
        )");
        $stmt2 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at)
            VALUES ('group_change', :id, :name, :data, :time)");
        $stmt2->bindValue(':id', $name, SQLITE3_TEXT);
        $stmt2->bindValue(':name', $name, SQLITE3_TEXT);
        $stmt2->bindValue(':data', json_encode(['action' => 'update', 'group_name' => $name]), SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();
    } catch (\Throwable $e) { /* 静默 */ }

    echo json_encode(['success' => true, 'message' => "用户组 {$name} 已更新"]);
}

function handleDeleteUserGroup($db, $name) {
    if (empty($name)) { echo json_encode(['success' => false, 'error' => 'missing name']); return; }
    $stmt = $db->prepare("DELETE FROM web_user_groups WHERE group_name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->execute();

    // ★ 写入变更队列，通知Java端重新加载用户组
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS web_admin_changes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            change_type TEXT NOT NULL,
            target_id TEXT DEFAULT '',
            target_name TEXT DEFAULT '',
            change_data TEXT DEFAULT '{}',
            created_at INTEGER DEFAULT 0,
            acknowledged INTEGER DEFAULT 0,
            acked_at INTEGER DEFAULT 0
        )");
        $stmt2 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at)
            VALUES ('group_change', :id, :name, :data, :time)");
        $stmt2->bindValue(':id', $name, SQLITE3_TEXT);
        $stmt2->bindValue(':name', $name, SQLITE3_TEXT);
        $stmt2->bindValue(':data', json_encode(['action' => 'delete', 'group_name' => $name]), SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();
    } catch (\Throwable $e) { /* 静默 */ }

    echo json_encode(['success' => true, 'message' => "用户组 {$name} 已删除"]);
}

function handleListGroupMembers($db, $group) {
    if (empty($group)) { echo json_encode(['success' => false, 'error' => 'missing group']); return; }
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    $stmt = $db->prepare("SELECT * FROM web_user_group_members WHERE group_name = :group");
    $stmt->bindValue(':group', $group, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $members = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $members[] = $row;
    }
    echo json_encode(['success' => true, 'members' => $members]);
}

function handleAddGroupMember($db, $data) {
    $player = $data['player'] ?? $data['player_name'] ?? '';
    $group = $data['group'] ?? $data['group_name'] ?? '';
    if (empty($player) || empty($group)) {
        echo json_encode(['success' => false, 'error' => 'missing player or group']);
        return;
    }
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    $addedBy = $data['added_by'] ?? 'admin';
    $now = time();
    $expiry = (int)($data['expiry_time'] ?? 0);

    $stmt = $db->prepare("INSERT OR REPLACE INTO web_user_group_members
        (player_name, group_name, added_by, added_time, expiry_time)
        VALUES (:player, :group, :addedby, :added, :expiry)");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':group', $group, SQLITE3_TEXT);
    $stmt->bindValue(':addedby', $addedBy, SQLITE3_TEXT);
    $stmt->bindValue(':added', $now, SQLITE3_INTEGER);
    $stmt->bindValue(':expiry', $expiry, SQLITE3_INTEGER);
    $stmt->execute();

    echo json_encode(['success' => true, 'message' => "{$player} 已加入用户组 {$group}"]);
}

function handleRemoveGroupMember($db, $data) {
    $player = $data['player'] ?? $data['player_name'] ?? '';
    $group = $data['group'] ?? $data['group_name'] ?? '';
    if (empty($player) || empty($group)) {
        echo json_encode(['success' => false, 'error' => 'missing player or group']);
        return;
    }
    $stmt = $db->prepare("DELETE FROM web_user_group_members WHERE player_name = :player AND group_name = :group");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':group', $group, SQLITE3_TEXT);
    $stmt->execute();
    echo json_encode(['success' => true, 'message' => "{$player} 已移出用户组 {$group}"]);
}

function handleGetPlayerGroups($db, $player) {
    if (empty($player)) { echo json_encode(['success' => false, 'error' => 'missing player']); return; }
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    $stmt = $db->prepare("SELECT m.*, g.display_name, g.display_color, g.priority
        FROM web_user_group_members m
        LEFT JOIN web_user_groups g ON m.group_name = g.group_name
        WHERE m.player_name = :player");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $groups = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $groups[] = $row;
    }
    echo json_encode(['success' => true, 'groups' => $groups]);
}

function validateSecret($secret) {
    if (empty($secret)) return false;
    $validSecrets = [
        'sdf1_web_comm_2026_ypshidifu'
    ];
    return in_array($secret, $validSecrets);
}
