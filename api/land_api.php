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
    $syncActions = ['sync_lands', 'sync_shop', 'sync_permissions', 'get_pending_validations', 'validation_callback'];
    // 管理面板action：支持admin_token或secret
    $adminActions = ['list_lands', 'list_shop', 'get_config', 'update_config', 'delete_land', 'update_land_owner', 'delete_shop_item', 'list_user_groups', 'get_user_group', 'update_user_group', 'delete_user_group', 'list_group_members', 'add_group_member', 'remove_group_member'];
    // 玩家端action：需要token
    $playerActions = ['my_lands', 'land_detail', 'add_visitor', 'remove_visitor', 'list_visitors', 'land_shop', 'buy_permission', 'transfer_land', 'cancel_transfer', 'transfer_status', 'renew_group', 'list_available_groups', 'buy_group', 'get_player_groups'];
    // ★ 玩家端领地字段更新（效果管理、开关等）
    $playerFieldActions = ['update_land_field'];
    // ★ 玩家端权限操作action
    $playerPermActions = ['update_visitor_perm', 'get_visitor_perm', 'change_visitor_role'];
    // ★ 成员独立权限操作action（领地所有者编辑成员权限）
    $memberPermActions = ['get_member_perms', 'update_member_perm', 'clear_member_perm'];
    // ★ Java端轮询PHP管理员变更 + Java回调过户结果
    $syncFromPhpActions = ['poll_admin_changes', 'ack_admin_changes', 'transfer_callback', 'owner_change_callback', 'delete_land_callback', 'poll_group_renews', 'renew_group_callback'];

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
        } else {
            $isAdmin = true;
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

        // ===== 管理面板：更新领地所有者（含过户冷却机制）=====
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

        // ===== 玩家端：过户领地 =====
        case 'transfer_land':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleTransferLand($db, $playerName, $_POST);
            break;

        // ===== 玩家端：取消过户 =====
        case 'cancel_transfer':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleCancelTransfer($db, $playerName, $_POST);
            break;

        // ===== 玩家端：查询过户状态 =====
        case 'transfer_status':
            handleTransferStatus($db, $playerName, $_GET['land'] ?? '');
            break;

        // ===== 玩家端：查看可购买的用户组 =====
        case 'list_available_groups':
            handleListAvailableGroups($db);
            break;

        // ===== 玩家端：付费加入用户组 =====
        case 'buy_group':
            handleBuyGroup($db, $playerName, $_POST + $_GET);
            break;

        // ===== 玩家端：续费用户组 =====
        case 'renew_group':
            handleRenewGroup($db, $playerName, $_POST + $_GET);
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

        // ===== 玩家端：切换访客角色（admin/visitor）=====
        case 'change_visitor_role':
            if ($method !== 'POST') {
                echo json_encode(['success' => false, 'error' => 'POST only']);
                exit;
            }
            handleChangeVisitorRole($db, $playerName, $_POST);
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

        // ===== Java端：拉取待验证玩家列表 =====
        case 'get_pending_validations':
            handleGetPendingValidations($db);
            break;

        // ===== Java端：推送验证结果 =====
        case 'validation_callback':
            handleValidationCallback($db, $_POST + $_GET);
            break;

        // ===== Java端：过户验证结果回调 =====
        case 'transfer_callback':
            handleTransferCallback($db, $_POST + $_GET);
            break;

        // ===== Java端：管理面板改主结果回调 =====
        case 'owner_change_callback':
            handleOwnerChangeCallback($db, $_POST + $_GET);
            break;

        case 'delete_land_callback':
            handleDeleteLandCallback($db, $_GET);
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

        // ===== Java端：轮询PHP发起的用户组续费请求 =====
        case 'poll_group_renews':
            handlePollGroupRenews($db);
            break;

        // ===== Java端：续费结果回调 =====
        case 'renew_group_callback':
            handleRenewGroupCallback($db, $_POST + $_GET);
            break;

        // ===== 管理面板：查询改主状态 =====
        case 'get_owner_change_status':
            handleGetOwnerChangeStatus($db, $_GET);
            break;

        default:
            echo json_encode(['success' => false, 'error' => 'unknown action']);
    }
} catch (\Throwable $e) {
    echo json_encode(['success' => false, 'error' => $e->getMessage()]);
}

// ==================== 函数 ====================

function initLandTables($db) {
    // ★ 玩家验证缓存表（异步验证：PHP写入→Java拉取验证→写回结果）
    $db->exec("CREATE TABLE IF NOT EXISTS pending_player_validations (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        request_type TEXT NOT NULL,
        request_data TEXT DEFAULT '{}',
        status TEXT DEFAULT 'pending',
        result TEXT DEFAULT '',
        created_at INTEGER DEFAULT 0,
        validated_at INTEGER DEFAULT 0
    )");

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
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN clear_all_bad INTEGER DEFAULT 0"); } catch (\Throwable $e) {}
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
        acked_at INTEGER DEFAULT 0,
        status TEXT DEFAULT 'done'
    )");

    // ★ 为已有表添加status字段（如果不存在）
    try {
        $db->exec("ALTER TABLE web_admin_changes ADD COLUMN status TEXT DEFAULT 'done'");
    } catch (\Throwable $e) { /* 字段已存在 */ }

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

    // ★ 领地过户冷却表
    $db->exec("CREATE TABLE IF NOT EXISTS web_land_transfers (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        land_name TEXT NOT NULL,
        old_owner TEXT NOT NULL,
        new_owner TEXT NOT NULL,
        status TEXT DEFAULT 'pending',
        created_at INTEGER DEFAULT 0,
        completed_at INTEGER DEFAULT 0,
        expires_at INTEGER DEFAULT 0
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

    // ★ 查询领地信息
    $stmt = $db->prepare("SELECT id, owner FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }
    $landId = (int)$row['id'];

    // ★ 检查是否已有pending的land_delete（防止重复提交）
    $stmtCheck = $db->prepare("SELECT id FROM web_admin_changes WHERE change_type = 'land_delete' AND target_name = :name AND status = 'pending'");
    $stmtCheck->bindValue(':name', $name, SQLITE3_TEXT);
    $checkResult = $stmtCheck->execute();
    if ($checkResult->fetchArray(SQLITE3_ASSOC)) {
        echo json_encode(['success' => false, 'error' => '该领地已有待验证的删除请求，请等待Java端处理']);
        return;
    }

    // ★ 不再直接删除PHP本地副本！写入web_admin_changes让Java端验证后执行
    $stmt4 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at, status) VALUES ('land_delete', :id, :name, :data, :now, 'pending')");
    $stmt4->bindValue(':id', $landId, SQLITE3_INTEGER);
    $stmt4->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt4->bindValue(':data', json_encode(['source' => 'admin_panel']), SQLITE3_TEXT);
    $stmt4->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt4->execute();

    debugLog("handleDeleteLand: 管理面板删除领地 {$name} (已写入pending队列，等待Java验证)");
    echo json_encode(['success' => true, 'pending' => true, 'message' => "删除请求已提交: [{$name}]，等待Java端验证后生效"]);
}

function handleUpdateLandOwner($db, $post) {
    $name = $post['name'] ?? '';
    $owner = $post['owner'] ?? '';
    if (empty($name)) {
        echo json_encode(['success' => false, 'error' => 'missing name']);
        return;
    }

    if (empty($owner)) {
        echo json_encode(['success' => false, 'error' => '新所有者不能为空']);
        return;
    }
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $owner)) {
        echo json_encode(['success' => false, 'error' => '玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）']);
        return;
    }

    // ★ 查询领地信息
    $stmt = $db->prepare("SELECT id, owner FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }
    $landId = (int)$row['id'];
    $oldOwner = $row['owner'] ?? '';

    if ($oldOwner === $owner) {
        echo json_encode(['success' => false, 'error' => '新旧所有者相同，无需更改']);
        return;
    }

    // ★ 检查是否已有pending的owner_change（防止重复提交）
    $stmtCheck = $db->prepare("SELECT id FROM web_admin_changes WHERE change_type = 'owner_change' AND target_name = :name AND status = 'pending'");
    $stmtCheck->bindValue(':name', $name, SQLITE3_TEXT);
    $checkResult = $stmtCheck->execute();
    if ($checkResult->fetchArray(SQLITE3_ASSOC)) {
        echo json_encode(['success' => false, 'error' => '该领地已有待验证的改主请求，请等待Java端处理']);
        return;
    }

    // ★ 不再直接更新PHP本地副本！等Java端验证并回调后再更新
    // 写入web_admin_changes让Java端pollAdminChanges拉取并验证执行
    $stmt4 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at, status) VALUES ('owner_change', :id, :name, :data, :now, 'pending')");
    $stmt4->bindValue(':id', $landId, SQLITE3_INTEGER);
    $stmt4->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt4->bindValue(':data', json_encode(['old_owner' => $oldOwner, 'new_owner' => $owner, 'source' => 'admin_panel']), SQLITE3_TEXT);
    $stmt4->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt4->execute();

    debugLog("handleUpdateLandOwner: 管理面板改主 {$name}: {$oldOwner} → {$owner} (已写入pending队列，等待Java验证)");
    echo json_encode(['success' => true, 'pending' => true, 'message' => "改主请求已提交: [{$name}] {$oldOwner} → {$owner}，等待Java端验证后生效"]);
}

function handleGetOwnerChangeStatus($db, $get) {
    $name = $get['name'] ?? '';
    if (empty($name)) {
        echo json_encode(['success' => false, 'error' => '缺少name参数']);
        return;
    }

    $stmt = $db->prepare("SELECT status, change_data FROM web_admin_changes WHERE change_type = 'owner_change' AND target_name = :name ORDER BY id DESC LIMIT 1");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        echo json_encode(['success' => true, 'status' => 'none', 'message' => '没有改主记录']);
        return;
    }

    $status = $row['status'] ?? 'pending';
    $changeData = json_decode($row['change_data'] ?? '{}', true);
    $newOwner = $changeData['new_owner'] ?? '';

    // 检查是否超时（1.05分钟 = 65秒）
    $stmtTime = $db->prepare("SELECT created_at FROM web_admin_changes WHERE change_type = 'owner_change' AND target_name = :name ORDER BY id DESC LIMIT 1");
    $stmtTime->bindValue(':name', $name, SQLITE3_TEXT);
    $rsTime = $stmtTime->execute();
    $rowTime = $rsTime->fetchArray(SQLITE3_ASSOC);

    if ($rowTime && $status === 'pending') {
        $createdAt = (int)($rowTime['created_at'] ?? 0);
        $now = time();
        if ($now - $createdAt > 65) {
            // 超时，自动回滚
            $stmtRollback = $db->prepare("UPDATE web_admin_changes SET status = 'failed', acknowledged = 1, acked_at = :now WHERE change_type = 'owner_change' AND target_name = :name AND status = 'pending'");
            $stmtRollback->bindValue(':now', $now, SQLITE3_INTEGER);
            $stmtRollback->bindValue(':name', $name, SQLITE3_TEXT);
            $stmtRollback->execute();

            debugLog("handleGetOwnerChangeStatus: 改主 {$name} 超时（超过65秒未处理），已自动回滚");
            echo json_encode(['success' => true, 'status' => 'failed', 'reason' => 'Java端处理超时', 'message' => '改主超时，已自动回滚']);
            return;
        }
    }

    if ($status === 'completed') {
        echo json_encode(['success' => true, 'status' => 'completed', 'new_owner' => $newOwner, 'message' => '改主成功']);
    } elseif ($status === 'failed') {
        echo json_encode(['success' => true, 'status' => 'failed', 'message' => '改主失败']);
    } else {
        echo json_encode(['success' => true, 'status' => 'pending', 'message' => '等待Java端验证']);
    }
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
        echo json_encode(['success' => false, 'error' => '玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）']);
        return;
    }

    // ★ 校验玩家是否存在于login.db（异步验证）
    $visitorValid = validatePlayerViaJava($db, $visitor, 'add_visitor', ['land' => $landName]);
    if ($visitorValid === null) {
        echo json_encode(['success' => true, 'pending' => true, 'message' => "玩家 {$visitor} 的验证请求已提交，系统将在1-2分钟内自动完成验证"]);
        return;
    }
    if (!$visitorValid) {
        echo json_encode(['success' => false, 'error' => "玩家 {$visitor} 尚未注册，请确认玩家名是否正确"]);
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
 * ★ 异步验证玩家是否存在
 * 流程：先查本地缓存 → 有缓存直接返回 → 无缓存写入pending表等待Java验证
 * 返回: true=存在, false=不存在, null=已提交异步验证(需等待)
 */
function validatePlayerViaJava($db, $playerName, $requestType = 'general', $extraData = []) {
    // 1. 先查本地缓存（1小时内有效）
    $cacheValidUntil = time() - 3600; // 1小时前
    $stmt = $db->prepare("SELECT status, validated_at FROM pending_player_validations
        WHERE player_name = :player AND validated_at > :cutoff ORDER BY id DESC LIMIT 1");
    $stmt->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt->bindValue(':cutoff', $cacheValidUntil, SQLITE3_INTEGER);
    $rs = $stmt->execute();
    $cached = $rs->fetchArray(SQLITE3_ASSOC);

    if ($cached) {
        // 有缓存结果
        if ($cached['status'] === 'valid') return true;
        if ($cached['status'] === 'invalid') return false;
        // status='pending' → 还在验证中
    }

    // 2. 无缓存或已过期 → 写入pending表，触发Java异步验证
    $stmt2 = $db->prepare("INSERT INTO pending_player_validations (player_name, request_type, request_data, status, created_at)
        VALUES (:player, :type, :data, 'pending', :now)");
    $stmt2->bindValue(':player', $playerName, SQLITE3_TEXT);
    $stmt2->bindValue(':type', $requestType, SQLITE3_TEXT);
    $stmt2->bindValue(':data', json_encode($extraData), SQLITE3_TEXT);
    $stmt2->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt2->execute();

    debugLog("validatePlayerViaJava: 提交异步验证请求", ['player' => $playerName, 'type' => $requestType]);
    return null; // null = 已提交异步验证
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

// ==================== 异步验证：Java端拉取待验证列表 ====================

/**
 * Java定时器调用：拉取待验证的玩家列表
 */
function handleGetPendingValidations($db) {
    $stmt = $db->prepare("SELECT id, player_name, request_type, request_data, created_at
        FROM pending_player_validations WHERE status = 'pending' ORDER BY id ASC LIMIT 20");
    $rs = $stmt->execute();
    $list = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }
    echo json_encode(['success' => true, 'pending' => $list]);
}

/**
 * Java验证完成后推送结果
 * POST id, status('valid'|'invalid')
 */
function handleValidationCallback($db, $data) {
    $id = (int)($data['id'] ?? 0);
    $status = $data['status'] ?? '';
    if ($id <= 0 || !in_array($status, ['valid', 'invalid'])) {
        echo json_encode(['success' => false, 'error' => 'missing id or invalid status']);
        return;
    }

    $stmt = $db->prepare("UPDATE pending_player_validations SET status = :status, validated_at = :now WHERE id = :id");
    $stmt->bindValue(':status', $status, SQLITE3_TEXT);
    $stmt->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $stmt->execute();

    // 获取玩家名用于日志
    $stmt2 = $db->prepare("SELECT player_name FROM pending_player_validations WHERE id = :id");
    $stmt2->bindValue(':id', $id, SQLITE3_INTEGER);
    $rs = $stmt2->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    $player = $row ? $row['player_name'] : '?';

    debugLog("validation_callback: 玩家 {$player} 验证结果={$status}");
    echo json_encode(['success' => true]);
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
    $allowedFields = ['clear_effects', 'give_effects', 'clear_all_bad', 'deny_all_effects'];
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
    $colType = ($field === 'clear_all_bad' || $field === 'deny_all_effects') ? 'INTEGER DEFAULT 0' : "TEXT DEFAULT ''";
    try { $db->exec("ALTER TABLE web_area_lands ADD COLUMN {$field} $colType"); } catch (\Throwable $e) {}

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

// ==================== 玩家端：切换访客角色 ====================

function handleChangeVisitorRole($db, $playerName, $post) {
    $landName = $post['name'] ?? $post['land'] ?? '';
    $visitor = $post['visitor'] ?? $post['player'] ?? '';
    $newRole = $post['role'] ?? '';

    if (empty($landName) || empty($visitor) || empty($newRole)) {
        echo json_encode(['success' => false, 'error' => '缺少参数']);
        return;
    }

    if (!in_array($newRole, ['admin', 'visitor'])) {
        echo json_encode(['success' => false, 'error' => '无效角色: ' . $newRole]);
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
        echo json_encode(['success' => false, 'error' => '只有领地所有者才能修改角色']);
        return;
    }

    // 更新角色
    $stmt2 = $db->prepare("UPDATE web_area_permissions SET role = :role WHERE land_id = :land_id AND player_name = :player");
    $stmt2->bindValue(':role', $newRole, SQLITE3_TEXT);
    $stmt2->bindValue(':land_id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt2->bindValue(':player', $visitor, SQLITE3_TEXT);
    $stmt2->execute();

    // 写入变更队列通知Java同步
    $now = time();
    $changeData = json_encode(['player' => $visitor, 'role' => $newRole, 'land' => $landName]);
    $stmt3 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('perm_change', :id, :name, :data, :now)");
    $stmt3->bindValue(':id', (int)$land['id'], SQLITE3_INTEGER);
    $stmt3->bindValue(':name', $landName, SQLITE3_TEXT);
    $stmt3->bindValue(':data', $changeData, SQLITE3_TEXT);
    $stmt3->bindValue(':now', $now, SQLITE3_INTEGER);
    $stmt3->execute();

    $roleLabel = $newRole === 'admin' ? '管理员' : '访客';
    echo json_encode(['success' => true, 'message' => "已将 $visitor 设为$roleLabel"]);
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
        home_limit INTEGER DEFAULT 0,
        join_price INTEGER DEFAULT 0,
        auto_renew INTEGER DEFAULT 0,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");
    // 迁移：添加新列
    $columns = [];
    $rs = $db->query("PRAGMA table_info(web_user_groups)");
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $columns[] = $row['name'];
    }
    $migrations = [
        'home_limit' => 'ALTER TABLE web_user_groups ADD COLUMN home_limit INTEGER DEFAULT 0',
        'join_price' => 'ALTER TABLE web_user_groups ADD COLUMN join_price INTEGER DEFAULT 0',
        'auto_renew' => 'ALTER TABLE web_user_groups ADD COLUMN auto_renew INTEGER DEFAULT 0',
        'renew_price' => 'ALTER TABLE web_user_groups ADD COLUMN renew_price INTEGER DEFAULT 0',
        'duration_minutes' => 'ALTER TABLE web_user_groups ADD COLUMN duration_minutes INTEGER DEFAULT 0',
    ];
    foreach ($migrations as $col => $sql) {
        if (!in_array($col, $columns)) {
            try { $db->exec($sql); } catch (\Throwable $e) { /* 已存在 */ }
        }
    }

    $rs = $db->query("SELECT * FROM web_user_groups ORDER BY priority DESC");
    $groups = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $groups[] = $row;
    }
    echo json_encode(['success' => true, 'groups' => $groups], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
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
        home_limit INTEGER DEFAULT 0,
        join_price INTEGER DEFAULT 0,
        auto_renew INTEGER DEFAULT 0,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");

    $displayName = $data['display_name'] ?? '';
    $displayColor = $data['display_color'] ?? '§f';
    $displayEmoji = $data['display_emoji'] ?? '';
    $priority = (int)($data['priority'] ?? 0);
    $pricePerSqm = (int)($data['land_price_per_sqm'] ?? -1);
    $maxLands = (int)($data['max_lands'] ?? -1);
    $homeLimit = (int)($data['home_limit'] ?? 0);
    $joinPrice = (int)($data['join_price'] ?? 0);
    $renewPrice = (int)($data['renew_price'] ?? 0);
    $durationMinutes = (int)($data['duration_minutes'] ?? 0);
    $autoRenew = (int)($data['auto_renew'] ?? 0);
    $defaultPerms = $data['default_perms'] ?? '{}';

    $stmt = $db->prepare("INSERT OR REPLACE INTO web_user_groups
        (group_name, display_name, display_color, display_emoji, priority,
         land_price_per_sqm, max_lands, home_limit, join_price, auto_renew, renew_price, duration_minutes, default_perms, synced_at)
        VALUES (:name, :display, :color, :emoji, :priority,
                :price, :maxlands, :homeLimit, :joinPrice, :autoRenew, :renewPrice, :duration, :perms, :synced)");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->bindValue(':display', $displayName, SQLITE3_TEXT);
    $stmt->bindValue(':color', $displayColor, SQLITE3_TEXT);
    $stmt->bindValue(':emoji', $displayEmoji, SQLITE3_TEXT);
    $stmt->bindValue(':priority', $priority, SQLITE3_INTEGER);
    $stmt->bindValue(':price', $pricePerSqm, SQLITE3_INTEGER);
    $stmt->bindValue(':maxlands', $maxLands, SQLITE3_INTEGER);
    $stmt->bindValue(':homeLimit', $homeLimit, SQLITE3_INTEGER);
    $stmt->bindValue(':joinPrice', $joinPrice, SQLITE3_INTEGER);
    $stmt->bindValue(':autoRenew', $autoRenew, SQLITE3_INTEGER);
    $stmt->bindValue(':renewPrice', $renewPrice, SQLITE3_INTEGER);
    $stmt->bindValue(':duration', $durationMinutes, SQLITE3_INTEGER);
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
    // ★ 玩家名格式校验：3-16位，仅字母数字下划线
    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $player)) {
        echo json_encode(['success' => false, 'error' => "玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）"]);
        return;
    }
    // ★ 校验玩家是否存在（异步验证：写入pending表，Java定时器拉取验证）
    $playerValid = validatePlayerViaJava($db, $player, 'add_group_member', ['group' => $group]);
    if ($playerValid === null) {
        echo json_encode(['success' => true, 'pending' => true, 'message' => "玩家 {$player} 的验证请求已提交，系统将在1-2分钟内自动完成验证"]);
        return;
    }
    if (!$playerValid) {
        echo json_encode(['success' => false, 'error' => "玩家 {$player} 尚未注册，请确认玩家名是否正确"]);
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

    // ★ 写入变更队列，通知Java端同步成员
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
        $stmt2->bindValue(':id', $group, SQLITE3_TEXT);
        $stmt2->bindValue(':name', $group, SQLITE3_TEXT);
        $stmt2->bindValue(':data', json_encode(['action' => 'add_member', 'group_name' => $group, 'player' => $player]), SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();
    } catch (\Throwable $e) { /* 静默 */ }

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

    // ★ 写入变更队列，通知Java端同步成员
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
        $stmt2->bindValue(':id', $group, SQLITE3_TEXT);
        $stmt2->bindValue(':name', $group, SQLITE3_TEXT);
        $stmt2->bindValue(':data', json_encode(['action' => 'remove_member', 'group_name' => $group, 'player' => $player]), SQLITE3_TEXT);
        $stmt2->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt2->execute();
    } catch (\Throwable $e) { /* 静默 */ }

    echo json_encode(['success' => true, 'message' => "{$player} 已移出用户组 {$group}"]);
}

function handleGetPlayerGroups($db, $player) {
    if (empty($player)) { echo json_encode(['success' => false, 'error' => 'missing player']); return; }
    // ★ 先确保 web_user_groups 存在（Java同步可能还没推送）
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
        group_name TEXT PRIMARY KEY,
        display_name TEXT DEFAULT '',
        display_color TEXT DEFAULT '§f',
        display_emoji TEXT DEFAULT '',
        priority INTEGER DEFAULT 0,
        land_price_per_sqm INTEGER DEFAULT -1,
        max_lands INTEGER DEFAULT -1,
        home_limit INTEGER DEFAULT 0,
        join_price INTEGER DEFAULT 0,
        auto_renew INTEGER DEFAULT 0,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");
    // 迁移：添加新列（如果缺失）
    $columns = [];
    $rs_cols = $db->query("PRAGMA table_info(web_user_groups)");
    while ($row = $rs_cols->fetchArray(SQLITE3_ASSOC)) { $columns[] = $row['name']; }
    $migrations = [
        'home_limit' => 'ALTER TABLE web_user_groups ADD COLUMN home_limit INTEGER DEFAULT 0',
        'join_price' => 'ALTER TABLE web_user_groups ADD COLUMN join_price INTEGER DEFAULT 0',
        'auto_renew' => 'ALTER TABLE web_user_groups ADD COLUMN auto_renew INTEGER DEFAULT 0',
        'renew_price' => 'ALTER TABLE web_user_groups ADD COLUMN renew_price INTEGER DEFAULT 0',
        'duration_minutes' => 'ALTER TABLE web_user_groups ADD COLUMN duration_minutes INTEGER DEFAULT 0',
    ];
    foreach ($migrations as $col => $sql) {
        if (!in_array($col, $columns)) {
            try { $db->exec($sql); } catch (\Throwable $e) { /* 已存在 */ }
        }
    }
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    // ★ 先确保 web_user_groups 存在（Java同步可能还没推送）
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
        group_name TEXT PRIMARY KEY,
        display_name TEXT DEFAULT '',
        display_color TEXT DEFAULT '§f',
        display_emoji TEXT DEFAULT '',
        priority INTEGER DEFAULT 0,
        land_price_per_sqm INTEGER DEFAULT -1,
        max_lands INTEGER DEFAULT -1,
        home_limit INTEGER DEFAULT 0,
        join_price INTEGER DEFAULT 0,
        auto_renew INTEGER DEFAULT 0,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");
    $stmt = $db->prepare("SELECT m.*, g.display_name, g.display_color, g.priority,
        g.join_price, g.renew_price, g.auto_renew, g.duration_minutes, g.home_limit
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

/**
 * 玩家端：查看可购买的用户组
 */
function handleListAvailableGroups($db) {
    // ★ 确保 web_user_groups 存在 + 迁移缺失列
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_groups (
        group_name TEXT PRIMARY KEY,
        display_name TEXT DEFAULT '',
        display_color TEXT DEFAULT '§f',
        display_emoji TEXT DEFAULT '',
        priority INTEGER DEFAULT 0,
        land_price_per_sqm INTEGER DEFAULT -1,
        max_lands INTEGER DEFAULT -1,
        home_limit INTEGER DEFAULT 0,
        join_price INTEGER DEFAULT 0,
        auto_renew INTEGER DEFAULT 0,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        default_perms TEXT DEFAULT '{}',
        synced_at INTEGER DEFAULT 0
    )");
    $columns = [];
    $rs_cols = $db->query("PRAGMA table_info(web_user_groups)");
    while ($row = $rs_cols->fetchArray(SQLITE3_ASSOC)) { $columns[] = $row['name']; }
    $migrations = [
        'join_price' => 'ALTER TABLE web_user_groups ADD COLUMN join_price INTEGER DEFAULT 0',
        'auto_renew' => 'ALTER TABLE web_user_groups ADD COLUMN auto_renew INTEGER DEFAULT 0',
        'renew_price' => 'ALTER TABLE web_user_groups ADD COLUMN renew_price INTEGER DEFAULT 0',
        'duration_minutes' => 'ALTER TABLE web_user_groups ADD COLUMN duration_minutes INTEGER DEFAULT 0',
    ];
    foreach ($migrations as $col => $sql) {
        if (!in_array($col, $columns)) {
            try { $db->exec($sql); } catch (\Throwable $e) { /* 已存在 */ }
        }
    }
    // 返回开放付费加入的用户组（join_price > 0）
    $rs = $db->query("SELECT group_name, display_name, display_color, display_emoji,
        join_price, renew_price, duration_minutes
        FROM web_user_groups WHERE join_price > 0 ORDER BY priority DESC");
    $groups = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $groups[] = $row;
    }
    echo json_encode(['success' => true, 'groups' => $groups], JSON_UNESCAPED_UNICODE);
}

/**
 * 玩家端：付费加入用户组（写入web_admin_changes，Java轮询拉取执行）
 */
function handleBuyGroup($db, $player, $data) {
    $group = $data['group'] ?? $data['group_name'] ?? '';
    if (empty($player) || empty($group)) {
        echo json_encode(['success' => false, 'error' => '缺少玩家名或用户组名']);
        return;
    }

    // 检查用户组是否存在且开放付费加入
    $stmt = $db->prepare("SELECT * FROM web_user_groups WHERE group_name = :group");
    $stmt->bindValue(':group', $group, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $cfg = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$cfg) {
        echo json_encode(['success' => false, 'error' => '用户组不存在: ' . $group]);
        return;
    }
    if ((int)$cfg['join_price'] <= 0) {
        echo json_encode(['success' => false, 'error' => '该用户组不开放付费加入']);
        return;
    }
    if ((int)$cfg['duration_minutes'] <= 0) {
        echo json_encode(['success' => false, 'error' => '该用户组配置异常（有效时长未设置）']);
        return;
    }
    if (!isset($cfg['join_price'])) {
        echo json_encode(['success' => false, 'error' => '数据库缺少 join_price 列，请重新同步']);
        return;
    }

    // 检查该玩家是否已在组内
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    $stmt2 = $db->prepare("SELECT * FROM web_user_group_members WHERE player_name = :player AND group_name = :group");
    $stmt2->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt2->bindValue(':group', $group, SQLITE3_TEXT);
    $rs2 = $stmt2->execute();
    $member = $rs2->fetchArray(SQLITE3_ASSOC);
    if ($member) {
        echo json_encode(['success' => false, 'error' => '你已在该用户组中']);
        return;
    }

    // 写入 web_admin_changes 等待Java拉取执行
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS web_admin_changes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            change_type TEXT NOT NULL,
            target_id TEXT DEFAULT '',
            target_name TEXT DEFAULT '',
            change_data TEXT DEFAULT '{}',
            status TEXT DEFAULT 'pending',
            created_at INTEGER DEFAULT 0,
            processed_at INTEGER DEFAULT 0
        )");
        $now = time();
        $stmt3 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at)
            VALUES ('group_buy', :id, :name, :data, :time)");
        $stmt3->bindValue(':id', $group, SQLITE3_TEXT);
        $stmt3->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt3->bindValue(':data', json_encode([
            'action' => 'buy',
            'group_name' => $group,
            'player' => $player,
            'join_price' => (int)$cfg['join_price'],
            'duration_minutes' => (int)$cfg['duration_minutes']
        ], JSON_UNESCAPED_UNICODE), SQLITE3_TEXT);
        $stmt3->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt3->execute();
        echo json_encode(['success' => true, 'message' => '购买请求已提交，请在游戏中确认扣费']);
    } catch (\Throwable $e) {
        echo json_encode(['success' => false, 'error' => '购买请求写入失败: ' . $e->getMessage()]);
    }
}

/**
 * 玩家端：续费用户组（写入web_admin_changes，Java轮询拉取执行）
 */
function handleRenewGroup($db, $player, $data) {
    $group = $data['group'] ?? $data['group_name'] ?? '';
    if (empty($player) || empty($group)) {
        echo json_encode(['success' => false, 'error' => '缺少玩家名或用户组名']);
        return;
    }

    // 检查该玩家是否在该组内
    $db->exec("CREATE TABLE IF NOT EXISTS web_user_group_members (
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        added_by TEXT DEFAULT 'system',
        added_time INTEGER DEFAULT 0,
        expiry_time INTEGER DEFAULT 0,
        PRIMARY KEY(player_name, group_name)
    )");
    $stmt = $db->prepare("SELECT * FROM web_user_group_members WHERE player_name = :player AND group_name = :group");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $stmt->bindValue(':group', $group, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $member = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$member) {
        echo json_encode(['success' => false, 'error' => '你不在该用户组中']);
        return;
    }

    // 获取用户组配置
    $stmt2 = $db->prepare("SELECT * FROM web_user_groups WHERE group_name = :group");
    $stmt2->bindValue(':group', $group, SQLITE3_TEXT);
    $rs2 = $stmt2->execute();
    $cfg = $rs2->fetchArray(SQLITE3_ASSOC);
    if (!$cfg || (int)$cfg['renew_price'] <= 0) {
        echo json_encode(['success' => false, 'error' => '该用户组不支持续费']);
        return;
    }

    // ★ 写入 web_group_renew 表，供 Java TimerE 拉取执行
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS web_group_renew (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_name TEXT NOT NULL,
            group_name TEXT NOT NULL,
            renew_price INTEGER DEFAULT 0,
            duration_minutes INTEGER DEFAULT 0,
            status TEXT DEFAULT 'pending',
            req_id TEXT DEFAULT '',
            created_at INTEGER DEFAULT 0
        )");
        $now = time();
        $uuid = uniqid('gr_', true);
        $stmt3 = $db->prepare("INSERT INTO web_group_renew (player_name, group_name, renew_price, duration_minutes, req_id, created_at)
            VALUES (:player, :group, :price, :duration, :req_id, :time)");
        $stmt3->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt3->bindValue(':group', $group, SQLITE3_TEXT);
        $stmt3->bindValue(':price', (int)$cfg['renew_price'], SQLITE3_INTEGER);
        $stmt3->bindValue(':duration', (int)$cfg['duration_minutes'], SQLITE3_INTEGER);
        $stmt3->bindValue(':req_id', $uuid, SQLITE3_TEXT);
        $stmt3->bindValue(':time', $now, SQLITE3_INTEGER);
        $stmt3->execute();
        echo json_encode(['success' => true, 'message' => '续费请求已提交，请在游戏中确认扣费', 'req_id' => $uuid]);
    } catch (\Throwable $e) {
        echo json_encode(['success' => false, 'error' => '续费请求写入失败: ' . $e->getMessage()]);
    }
}

// ==================== 用户组续费轮询 & 回调 ====================

/**
 * Java端轮询：获取PHP发起的续费请求
 * PHP handleRenewGroup 写入 web_group_renew 表（status=pending）
 * Java拉取后执行扣费+加入用户组，完成后回调 renew_group_callback
 */
function handlePollGroupRenews($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS web_group_renew (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        player_name TEXT NOT NULL,
        group_name TEXT NOT NULL,
        renew_price INTEGER DEFAULT 0,
        duration_minutes INTEGER DEFAULT 0,
        status TEXT DEFAULT 'pending',
        req_id TEXT DEFAULT '',
        created_at INTEGER DEFAULT 0
    )");

    $stmt = $db->prepare("SELECT id, player_name, group_name, req_id, renew_price, duration_minutes
        FROM web_group_renew WHERE status = 'pending' ORDER BY id ASC LIMIT 50");
    $rs = $stmt->execute();
    $renews = [];
    while ($row = $rs->fetchArray(SQLITE3_ASSOC)) {
        $renews[] = [
            'player_name' => $row['player_name'],
            'group_name' => $row['group_name'],
            'req_id' => $row['req_id'],
            'renew_price' => (int)$row['renew_price'],
            'duration_minutes' => (int)$row['duration_minutes']
        ];
    }
    echo json_encode(['success' => true, 'renews' => $renews]);
}

/**
 * Java端续费结果回调
 * POST secret, req_id, result('success'/'failed:reason')
 * PHP标记对应记录为 done/failed
 */
function handleRenewGroupCallback($db, $data) {
    $secret = $data['secret'] ?? '';
    if (!validateSecret($secret)) {
        echo json_encode(['success' => false, 'error' => 'invalid secret']);
        return;
    }

    $reqId = $data['req_id'] ?? '';
    $result = $data['result'] ?? '';

    if (empty($reqId) || empty($result)) {
        echo json_encode(['success' => false, 'error' => 'missing req_id or result']);
        return;
    }

    $status = ($result === 'success') ? 'done' : 'failed';
    $remark = ($result !== 'success') ? substr($result, strlen('failed:')) : '';

    $stmt = $db->prepare("UPDATE web_group_renew SET status = :status, remark = :remark WHERE req_id = :req_id AND status = 'pending'");
    $stmt->bindValue(':status', $status, SQLITE3_TEXT);
    $stmt->bindValue(':remark', $remark, SQLITE3_TEXT);
    $stmt->bindValue(':req_id', $reqId, SQLITE3_TEXT);
    $stmt->execute();

    echo json_encode(['success' => true]);
}

// ==================== 领地过户功能 ====================

/**
 * 过户领地：领地主将自己的领地转让给其他玩家
 * 流程：PHP暂存pending → Java轮询验证 → 成功回调PHP才改owner → 冷却1分钟
 */
function handleTransferLand($db, $playerName, $post) {
    $landName = $post['land'] ?? '';
    $newOwner = $post['new_owner'] ?? '';

    if (empty($landName) || empty($newOwner)) {
        echo json_encode(['success' => false, 'error' => '参数不完整']);
        return;
    }

    if (!preg_match('/^[a-zA-Z0-9_]{3,16}$/', $newOwner)) {
        echo json_encode(['success' => false, 'error' => '玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）']);
        return;
    }

    if (strtolower($newOwner) === strtolower($playerName)) {
        echo json_encode(['success' => false, 'error' => '不能将领地转让给自己']);
        return;
    }

    // 验证领地存在且操作者是领地主
    $stmt = $db->prepare("SELECT id, owner FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $landName, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '领地不存在']);
        return;
    }
    if (strtolower($row['owner'] ?? '') !== strtolower($playerName)) {
        echo json_encode(['success' => false, 'error' => '只有领地主才能转让领地']);
        return;
    }

    // 检查是否已有进行中的过户（pending或cooldown）
    $stmt2 = $db->prepare("SELECT id, status, expires_at FROM web_land_transfers WHERE land_name = :land AND status IN ('pending', 'cooldown') ORDER BY id DESC LIMIT 1");
    $stmt2->bindValue(':land', $landName, SQLITE3_TEXT);
    $rs2 = $stmt2->execute();
    $pending = $rs2->fetchArray(SQLITE3_ASSOC);
    if ($pending) {
        if ($pending['expires_at'] > time()) {
            $remain = $pending['expires_at'] - time();
            echo json_encode(['success' => false, 'error' => "该领地正在过户中（{$pending['status']}），还需 {$remain} 秒"]);
            return;
        }
        // 已过期，清理
        $db->query("UPDATE web_land_transfers SET status = 'expired' WHERE id = " . (int)$pending['id']);
    }

    // ★ 不再立即改owner，也不做异步验证 → 暂存为pending，等Java轮询拉走验证
    $now = time();
    $cooldown = 60; // 冷却时间（秒），Java验证通过后开始计时

    // 写入过户记录（status=pending，等Java验证）
    $stmt3 = $db->prepare("INSERT INTO web_land_transfers (land_name, old_owner, new_owner, status, created_at, completed_at, expires_at) VALUES (:land, :old, :new, 'pending', :now, 0, :expires)");
    $stmt3->bindValue(':land', $landName, SQLITE3_TEXT);
    $stmt3->bindValue(':old', $playerName, SQLITE3_TEXT);
    $stmt3->bindValue(':new', $newOwner, SQLITE3_TEXT);
    $stmt3->bindValue(':now', $now, SQLITE3_INTEGER);
    $stmt3->bindValue(':expires', $now + $cooldown, SQLITE3_INTEGER);
    $stmt3->execute();
    $transferId = $db->lastInsertRowID();

    // 写入web_admin_changes让Java端验证+执行
    $landId = (int)$row['id'];
    $stmt5 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('owner_change', :id, :name, :data, :now)");
    $stmt5->bindValue(':id', $landId, SQLITE3_INTEGER);
    $stmt5->bindValue(':name', $landName, SQLITE3_TEXT);
    $stmt5->bindValue(':data', json_encode([
        'old_owner' => $playerName,
        'new_owner' => $newOwner,
        'source' => 'player_transfer',
        'cooldown' => $cooldown,
        'transfer_id' => (int)$transferId,
    ]), SQLITE3_TEXT);
    $stmt5->bindValue(':now', $now, SQLITE3_INTEGER);
    $stmt5->execute();

    debugLog("handleTransferLand: {$playerName} 过户 {$landName} → {$newOwner}，等待Java验证");
    echo json_encode(['success' => true, 'message' => "过户请求已提交，等待系统验证玩家 {$newOwner}...", 'pending' => true, 'cooldown' => $cooldown]);
}

/**
 * 取消过户：冷却期间领地主可撤回转让
 */
function handleCancelTransfer($db, $playerName, $post) {
    $landName = $post['land'] ?? '';
    if (empty($landName)) {
        echo json_encode(['success' => false, 'error' => '参数不完整']);
        return;
    }

    // 查找进行中的过户（pending=待验证, cooldown=冷却中）
    $stmt = $db->prepare("SELECT id, status, old_owner, new_owner, expires_at FROM web_land_transfers WHERE land_name = :land AND status IN ('pending', 'cooldown') ORDER BY id DESC LIMIT 1");
    $stmt->bindValue(':land', $landName, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '没有进行中的过户']);
        return;
    }

    if ($row['status'] === 'cooldown' && time() > $row['expires_at']) {
        echo json_encode(['success' => false, 'error' => '冷却期已过，无法取消']);
        return;
    }

    if (strtolower($row['old_owner'] ?? '') !== strtolower($playerName)) {
        echo json_encode(['success' => false, 'error' => '只有原领地主才能取消过户']);
        return;
    }

    $oldOwner = $row['old_owner'];
    $newOwner = $row['new_owner'];
    $status = $row['status'];

    // 标记过户为已取消
    $db->query("UPDATE web_land_transfers SET status = 'cancelled' WHERE id = " . (int)$row['id']);

    if ($status === 'cooldown') {
        // cooldown状态：Java已改了owner，需要回退
        $stmt2 = $db->prepare("UPDATE web_area_lands SET owner = :owner WHERE name = :name");
        $stmt2->bindValue(':owner', $oldOwner, SQLITE3_TEXT);
        $stmt2->bindValue(':name', $landName, SQLITE3_TEXT);
        $stmt2->execute();

        // 通知Java回退
        $stmt3 = $db->prepare("SELECT id FROM web_area_lands WHERE name = :name");
        $stmt3->bindValue(':name', $landName, SQLITE3_TEXT);
        $r3 = $stmt3->execute();
        $r3Row = $r3->fetchArray(SQLITE3_ASSOC);
        $landId = $r3Row ? (int)$r3Row['id'] : 0;

        $stmt4 = $db->prepare("INSERT INTO web_admin_changes (change_type, target_id, target_name, change_data, created_at) VALUES ('owner_change', :id, :name, :data, :now)");
        $stmt4->bindValue(':id', $landId, SQLITE3_INTEGER);
        $stmt4->bindValue(':name', $landName, SQLITE3_TEXT);
        $stmt4->bindValue(':data', json_encode(['old_owner' => $newOwner, 'new_owner' => $oldOwner, 'source' => 'transfer_cancelled']), SQLITE3_TEXT);
        $stmt4->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmt4->execute();

        debugLog("handleCancelTransfer: {$playerName} 取消过户(cooldown回退) {$landName}: {$newOwner} → {$oldOwner}");
        echo json_encode(['success' => true, 'message' => "过户已取消，领地 [{$landName}] 已恢复为 {$oldOwner} 的所有"]);
    } else {
        // pending状态：Java还没验证，只需取消即可，不需要回退owner（PHP没改过）
        debugLog("handleCancelTransfer: {$playerName} 取消过户(pending撤回) {$landName}");
        echo json_encode(['success' => true, 'message' => "过户请求已撤回"]);
    }
}

/**
 * Java端过户验证回调：验证通过→改owner+进入cooldown；验证失败→标记failed
 */
function handleTransferCallback($db, $post) {
    $transferId = (int)($post['transfer_id'] ?? 0);
    $result = $post['result'] ?? '';  // 'success' or 'failed'
    $reason = $post['reason'] ?? '';

    if ($transferId <= 0 || empty($result)) {
        echo json_encode(['success' => false, 'error' => '参数不完整']);
        return;
    }

    // 查找过户记录
    $stmt = $db->prepare("SELECT * FROM web_land_transfers WHERE id = :id AND status = 'pending'");
    $stmt->bindValue(':id', $transferId, SQLITE3_INTEGER);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '过户记录不存在或已处理']);
        return;
    }

    $landName = $row['land_name'];
    $oldOwner = $row['old_owner'];
    $newOwner = $row['new_owner'];
    $now = time();
    $cooldown = 60;

    if ($result === 'success') {
        // ★ Java验证通过：更新PHP端owner
        $stmtUpd = $db->prepare("UPDATE web_area_lands SET owner = :owner WHERE name = :name");
        $stmtUpd->bindValue(':owner', $newOwner, SQLITE3_TEXT);
        $stmtUpd->bindValue(':name', $landName, SQLITE3_TEXT);
        $stmtUpd->execute();

        // 标记过户为cooldown（冷却期开始）
        $db->query("UPDATE web_land_transfers SET status = 'cooldown', completed_at = {$now}, expires_at = " . ($now + $cooldown) . " WHERE id = {$transferId}");

        // 记录变更
        $stmtLand = $db->prepare("SELECT id FROM web_area_lands WHERE name = :name");
        $stmtLand->bindValue(':name', $landName, SQLITE3_TEXT);
        $rLand = $stmtLand->execute();
        $landRow = $rLand->fetchArray(SQLITE3_ASSOC);
        $landId = $landRow ? (int)$landRow['id'] : 0;

        $stmtChg = $db->prepare("INSERT INTO web_land_owner_changes (land_id, land_name, old_owner, new_owner, created_at) VALUES (:lid, :name, :old, :new, :now)");
        $stmtChg->bindValue(':lid', $landId, SQLITE3_INTEGER);
        $stmtChg->bindValue(':name', $landName, SQLITE3_TEXT);
        $stmtChg->bindValue(':old', $oldOwner, SQLITE3_TEXT);
        $stmtChg->bindValue(':new', $newOwner, SQLITE3_TEXT);
        $stmtChg->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmtChg->execute();

        debugLog("handleTransferCallback: 过户 {$landName} {$oldOwner}→{$newOwner} 验证通过，进入cooldown");
        echo json_encode(['success' => true, 'message' => "验证通过，领地 [{$landName}] 已转让给 {$newOwner}，冷却 {$cooldown} 秒"]);
    } else {
        // ★ Java验证失败：标记过户失败
        $db->query("UPDATE web_land_transfers SET status = 'failed' WHERE id = {$transferId}");
        debugLog("handleTransferCallback: 过户 {$landName} {$oldOwner}→{$newOwner} 验证失败: {$reason}");
        echo json_encode(['success' => true, 'message' => "过户验证失败: {$reason}"]);
    }
}

/**
 * ★ Java端管理面板改主结果回调
 * 验证通过→更新PHP本地副本+记录变更；验证失败→标记失败
 */
function handleOwnerChangeCallback($db, $post) {
    $changeId = (int)($post['change_id'] ?? 0);
    $success = (int)($post['success'] ?? 0);
    $reason = $post['reason'] ?? '';

    if ($changeId <= 0) {
        echo json_encode(['success' => false, 'error' => '缺少change_id']);
        return;
    }

    // 查找pending的owner_change记录
    $stmt = $db->prepare("SELECT * FROM web_admin_changes WHERE id = :id AND change_type = 'owner_change' AND (status = 'pending' OR status IS NULL OR status = 'done')");
    $stmt->bindValue(':id', $changeId, SQLITE3_INTEGER);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);
    if (!$row) {
        echo json_encode(['success' => false, 'error' => '变更记录不存在或已处理']);
        return;
    }

    $targetName = $row['target_name'];
    $changeData = json_decode($row['change_data'] ?? '{}', true);
    $newOwner = $changeData['new_owner'] ?? '';
    $oldOwner = $changeData['old_owner'] ?? '';

    if ($success) {
        // ★ Java验证通过：更新PHP本地副本
        $stmtUpd = $db->prepare("UPDATE web_area_lands SET owner = :owner WHERE name = :name");
        $stmtUpd->bindValue(':owner', $newOwner, SQLITE3_TEXT);
        $stmtUpd->bindValue(':name', $targetName, SQLITE3_TEXT);
        $stmtUpd->execute();

        // 记录变更历史
        $landId = (int)($row['target_id'] ?? 0);
        $stmtChg = $db->prepare("INSERT INTO web_land_owner_changes (land_id, land_name, old_owner, new_owner, created_at) VALUES (:lid, :name, :old, :new, :now)");
        $stmtChg->bindValue(':lid', $landId, SQLITE3_INTEGER);
        $stmtChg->bindValue(':name', $targetName, SQLITE3_TEXT);
        $stmtChg->bindValue(':old', $oldOwner, SQLITE3_TEXT);
        $stmtChg->bindValue(':new', $newOwner, SQLITE3_TEXT);
        $stmtChg->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmtChg->execute();

        // 标记处理完成
        $db->query("UPDATE web_admin_changes SET status = 'completed', acknowledged = 1, acked_at = " . time() . " WHERE id = " . $changeId);
        debugLog("handleOwnerChangeCallback: 改主 {$targetName} {$oldOwner}→{$newOwner} Java验证通过，已更新PHP副本");
        echo json_encode(['success' => true, 'message' => '改主验证通过，已更新']);
    } else {
        // ★ Java验证失败：更新PHP副本为原始所有者（回退）+ 标记失败
        $stmtRb = $db->prepare("UPDATE web_area_lands SET owner = :old_owner WHERE name = :name");
        $stmtRb->bindValue(':old_owner', $oldOwner, SQLITE3_TEXT);
        $stmtRb->bindValue(':name', $targetName, SQLITE3_TEXT);
        $stmtRb->execute();

        $db->query("UPDATE web_admin_changes SET status = 'failed', acknowledged = 1, acked_at = " . time() . " WHERE id = " . $changeId);
        debugLog("handleOwnerChangeCallback: 改主 {$targetName} {$oldOwner}→{$newOwner} Java验证失败: {$reason}，已回退为{$oldOwner}");
        echo json_encode(['success' => true, 'message' => "改主验证失败: {$reason}，已回退"]);
    }
}

/**
 * Java端删除领地回调：删除PHP本地副本 + 标记admin_change完成
 */
function handleDeleteLandCallback($db, $get) {
    $name = $get['name'] ?? '';
    $success = ($get['success'] ?? '') === 'true';

    if (empty($name)) {
        echo json_encode(['success' => false, 'error' => '缺少name参数']);
        return;
    }

    // 删除PHP本地副本
    $stmt = $db->prepare("DELETE FROM web_area_lands WHERE name = :name");
    $stmt->bindValue(':name', $name, SQLITE3_TEXT);
    $stmt->execute();

    // 标记对应的admin_change为completed
    $stmtUpd = $db->prepare("UPDATE web_admin_changes SET status = 'completed', acknowledged = 1, acked_at = :now WHERE change_type = 'land_delete' AND target_name = :name AND status = 'pending'");
    $stmtUpd->bindValue(':now', time(), SQLITE3_INTEGER);
    $stmtUpd->bindValue(':name', $name, SQLITE3_TEXT);
    $stmtUpd->execute();

    debugLog("handleDeleteLandCallback: 删除领地 {$name} Java端处理成功，已删除PHP副本");
    echo json_encode(['success' => true, 'message' => "领地 [{$name}] 已删除"]);
}

/**
 * 查询过户状态
 */
function handleTransferStatus($db, $playerName, $landName) {
    if (empty($landName)) {
        echo json_encode(['success' => false, 'error' => '缺少land参数']);
        return;
    }

    $stmt = $db->prepare("SELECT * FROM web_land_transfers WHERE land_name = :land AND status IN ('pending', 'cooldown') ORDER BY id DESC LIMIT 1");
    $stmt->bindValue(':land', $landName, SQLITE3_TEXT);
    $rs = $stmt->execute();
    $row = $rs->fetchArray(SQLITE3_ASSOC);

    if (!$row) {
        echo json_encode(['success' => true, 'transfer' => null]);
        return;
    }

    $now = time();
    $expires = (int)$row['expires_at'];
    $remain = max(0, $expires - $now);
    $status = $row['status'];
    if ($status === 'cooldown' && $remain <= 0) $status = 'expired';

    echo json_encode(['success' => true, 'transfer' => [
        'id' => (int)$row['id'],
        'status' => $status,
        'old_owner' => $row['old_owner'],
        'new_owner' => $row['new_owner'],
        'remaining' => $remain,
        'expires_at' => $expires,
    ]]);
}

function validateSecret($secret) {
    if (empty($secret)) return false;
    $validSecrets = [
        'sdf1_web_comm_2026_ypshidifu'
    ];
    return in_array($secret, $validSecrets);
}
