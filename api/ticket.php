<?php
/**
 * 工单系统API
 * 
 * 用户端：提交工单、查看我的工单、查看详情、回复(MD)、撤销
 * 管理员端：查看所有工单、回复、驳回、分配服务商
 * 服务商端：抢单大厅、我处理的工单、回复、标记完结
 */

while (ob_get_level() > 0) { @ob_end_clean(); }
ob_start();
header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store, no-cache, must-revalidate');

error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');

// 仅刷新当前文件的OPcache（不要opcache_reset——会重置整个服务器缓存导致竞态条件）
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }

register_shutdown_function(function() {
    while (ob_get_level() > 0) { @ob_end_flush(); }
});

require_once __DIR__ . '/../core.php';
// 工单附件图：兰空图床客户端（本文件不含敏感值，凭据在 lsky_keys.php）
require_once __DIR__ . '/../lsky.php';

if (session_status() === PHP_SESSION_NONE) { session_start(); }

$action = getParam('action', '');

try {
switch ($action) {
    // ===== 用户端 =====
    case 'create':     ticketCreate(); break;
    case 'my_list':    ticketMyList(); break;
    case 'detail':     ticketDetail(); break;
    case 'reply':      ticketReply(); break;
    case 'withdraw':   ticketWithdraw(); break;

    // ===== 管理员端 =====
    case 'list_all':   ticketListAll(); break;
    case 'admin_reply': ticketAdminReply(); break;
    case 'reject':     ticketReject(); break;
    case 'assign':     ticketAssign(); break;
    case 'admin_create': ticketAdminCreate(); break;
    case 'admin_complete': ticketAdminComplete(); break;

    // ===== 服务商端 =====
    case 'provider_list':   ticketProviderList(); break;
    case 'available':   ticketAvailable(); break;
    case 'grab':        ticketGrab(); break;
    case 'provider_reply': ticketProviderReply(); break;
    case 'complete':    ticketProviderComplete(); break;

    // ===== 通用 =====
    case 'upload_image': ticketUploadImage(); break;
    case 'providers':   ticketProviders(); break;
    case 'provider_check': ticketProviderCheck(); break;

    default:
        error('未知操作: ' . $action);
}

// ★ WAL checkpoint：释放WAL锁，减少database is locked概率
try { walCheckpoint(); } catch (\Throwable $ignored) {}

} catch (\Throwable $e) {
    error('服务器错误: ' . $e->getMessage());
}

// ============================================================
// 工具函数
// ============================================================

function requireWebToken() {
    $token = getParam('token');
    if (!$token) error('缺少token', 401);
    $info = validateTokenSilent($token);
    if (!$info) error('token无效或已过期', 401);
    return $info;
}

function requireAdmin() {
    if (!isAdminLoggedIn()) error('未登录管理后台', 401);
}

/**
 * 检查是否为管理员或授权的服务商用户
 * 管理员通过session认证，用户通过token认证
 */
function getAuthUser() {
    if (isAdminLoggedIn()) return ['player' => 'admin', 'role' => 'admin'];
    return requireWebToken();
}

function isServiceProviderInGame($playerName) {
    // 优先从Web.db的web_service_providers表读取（Java同步数据）
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT 1 FROM web_service_providers WHERE player_name = :p AND active = 1");
        $stmt->bindValue(':p', $playerName, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray();
        if ($row !== false) return true;
    } catch (\Throwable $e) { /* 表可能不存在 */ }

    // 回退：尝试读取Java的login.db（本地开发环境）
    $loginDbPath = GAME_LOGIN_DB;
    if (!file_exists($loginDbPath)) return false;
    try {
        $loginDb = new SQLite3($loginDbPath);
        $loginDb->enableExceptions(true);
        $stmt = $loginDb->prepare("SELECT 1 FROM service_providers WHERE player_name = :p AND active = 1");
        $stmt->bindValue(':p', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray();
        $loginDb->close();
        return $row !== false;
    } catch (Exception $e) {
        return false;
    }
}

function getServiceProviderRole($playerName) {
    // 优先从Web.db读取
    $db = getDB();
    try {
        $stmt = $db->prepare("SELECT role FROM web_service_providers WHERE player_name = :p AND active = 1");
        $stmt->bindValue(':p', $playerName, SQLITE3_TEXT);
        $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
        if ($row) return $row['role'];
    } catch (\Throwable $e) { /* 表可能不存在 */ }

    // 回退：读取Java的login.db
    $loginDbPath = GAME_LOGIN_DB;
    if (!file_exists($loginDbPath)) return null;
    try {
        $loginDb = new SQLite3($loginDbPath);
        $loginDb->enableExceptions(true);
        $stmt = $loginDb->prepare("SELECT role FROM service_providers WHERE player_name = :p AND active = 1");
        $stmt->bindValue(':p', $playerName, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        $loginDb->close();
        return $row ? $row['role'] : null;
    } catch (Exception $e) {
        return null;
    }
}

// ★ 单张工单附件图上限
define('TICKET_MAX_IMAGES', 3);

/**
 * 读取并校验请求里的 images 参数（数组或 JSON 字符串均可），返回 URL 列表。
 * 每个 URL 必须以自家图床前缀开头（LSKY_ALLOWED_HOST_PREFIX），最多 3 张。
 */
function normalizeTicketImages() {
    $raw = getParam('images', []);
    if (is_string($raw)) $raw = json_decode($raw, true);
    if ($raw === null || $raw === '') $raw = [];
    if (!is_array($raw)) error('images 参数格式错误');
    $prefix = LSKY_ALLOWED_HOST_PREFIX;
    $out = [];
    foreach ($raw as $u) {
        if (!is_string($u)) error('图片地址格式错误');
        $u = trim($u);
        if ($u === '') continue;
        if (strpos($u, $prefix) !== 0) error('不支持的图片地址（仅接受自家图床）');
        $out[] = $u;
        if (count($out) >= TICKET_MAX_IMAGES) break;
    }
    return $out;
}

function encodeTicketImages(array $list) {
    return json_encode(array_values($list), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
}

function decodeTicketImages($raw) {
    if (!is_string($raw) || $raw === '') return [];
    $d = json_decode($raw, true);
    return is_array($d) ? $d : [];
}

/**
 * 上传工单附件图片：转存自家兰空图床，返回可直接引用的 URL。
 * 认证：管理后台 session 或用户 token（getAuthUser）。
 * 限制：仅 png/jpg、单张 ≤2048K、内容必须与后缀一致；同会话每分钟最多 12 次（防刷）。
 */
function ticketUploadImage() {
    getAuthUser();

    // 简单限流：同一会话 1 分钟内最多 12 张
    $now = time();
    $slot = isset($_SESSION['lsky_up']) && is_array($_SESSION['lsky_up']) ? $_SESSION['lsky_up'] : ['t' => 0, 'n' => 0];
    if ($now - (int)$slot['t'] > 60) $slot = ['t' => $now, 'n' => 0];
    if ((int)$slot['n'] >= 12) error('图片上传太频繁，请稍后再试');
    $slot['n']++;
    $_SESSION['lsky_up'] = $slot;

    if (empty($_FILES) || empty($_FILES['image'])) {
        error('未收到图片文件（表单字段名必须是 image）');
    }
    $f = $_FILES['image'];
    if ((int)$f['error'] !== UPLOAD_ERR_OK) {
        $msgs = [
            UPLOAD_ERR_INI_SIZE   => '图片超过服务器单文件大小上限',
            UPLOAD_ERR_FORM_SIZE  => '图片超过表单大小上限',
            UPLOAD_ERR_PARTIAL    => '图片上传中断，请重试',
            UPLOAD_ERR_NO_FILE    => '未选择文件',
            UPLOAD_ERR_NO_TMP_DIR => '服务器临时目录缺失',
            UPLOAD_ERR_CANT_WRITE => '服务器写入临时文件失败',
        ];
        $m = isset($msgs[$f['error']]) ? $msgs[$f['error']] : ('上传错误码 ' . $f['error']);
        error($m);
    }
    // 后缀：仅 png / jpg
    $orig = basename((string)$f['name']);
    $ext = strtolower(pathinfo($orig, PATHINFO_EXTENSION));
    if ($ext !== 'png' && $ext !== 'jpg') error('仅支持 png / jpg 格式');
    // 大小：≤2048K
    if ((int)$f['size'] > 2048 * 1024) error('图片不能超过 2048K');
    if (!is_uploaded_file($f['tmp_name'])) error('非法上传');
    // 内容真实校验：防伪造后缀上传任意文件
    $info = @getimagesize($f['tmp_name']);
    if ($info === false) error('无法识别的图片内容');
    $itype = (int)$info[2];
    if ($ext === 'png' && $itype !== IMAGETYPE_PNG) error('文件内容与 .png 后缀不符');
    if ($ext === 'jpg' && $itype !== IMAGETYPE_JPEG) error('文件内容与 .jpg 后缀不符');

    $r = lskyUploadFile($f['tmp_name'], $orig);
    if (empty($r['ok'])) error('图片上传失败: ' . (isset($r['msg']) && $r['msg'] !== '' ? $r['msg'] : '未知错误'));

    success(['url' => $r['url'], 'thumbnail' => isset($r['thumb']) ? $r['thumb'] : $r['url']], '上传成功');
}

function ticketCreate() {
    $info = requireWebToken();
    $player = $info['player'];
    
    $type = trim(getParam('type', ''));
    $title = trim(getParam('title', ''));
    $description = trim(getParam('description', ''));
    
    if (empty($type)) error('请选择工单类型');
    if (empty($title)) error('请输入工单标题');
    if (mb_strlen($title) > 100) error('标题不能超过100字');
    if (mb_strlen($description) > 5000) error('描述不能超过5000字');
    
    $validTypes = ['bug', 'help', 'report', 'apply', 'other'];
    if (!in_array($type, $validTypes)) error('无效的工单类型');
    
    $images = normalizeTicketImages();   // 附件图（≤3，必须来自自家图床）

    $db = getDB();
    $now = time();
    
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("INSERT INTO web_tickets (type, status, requester, title, description, images, created_at, updated_at) VALUES (:type, 'submitted', :player, :title, :desc, :images, :now, :now)");
        $stmt->bindValue(':type', $type, SQLITE3_TEXT);
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $stmt->bindValue(':title', $title, SQLITE3_TEXT);
        $stmt->bindValue(':desc', $description, SQLITE3_TEXT);
        $stmt->bindValue(':images', encodeTicketImages($images), SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $ticketId = $db->lastInsertRowID();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('创建失败: ' . $e->getMessage());
    }
    
    success(['id' => $ticketId], '工单提交成功');
}

function ticketMyList() {
    $info = requireWebToken();
    $player = $info['player'];
    $db = getDB();
    
    $status = getParam('status', '');
    $page = max(1, (int)getParam('page', 1));
    $limit = 20;
    $offset = ($page - 1) * $limit;
    
    $where = "WHERE requester = :player";
    $params = [':player' => $player];
    
    if ($status && $status !== 'all') {
        $where .= " AND status = :status";
        $params[':status'] = $status;
    }
    
    $countStmt = $db->prepare("SELECT COUNT(*) as total FROM web_tickets $where");
    foreach ($params as $k => $v) $countStmt->bindValue($k, $v, SQLITE3_TEXT);
    $total = $countStmt->execute()->fetchArray(SQLITE3_ASSOC)['total'];
    
    $stmt = $db->prepare("SELECT * FROM web_tickets $where ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
    foreach ($params as $k => $v) $stmt->bindValue($k, $v, SQLITE3_TEXT);
    $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    $stmt->bindValue(':offset', $offset, SQLITE3_INTEGER);
    $result = $stmt->execute();
    
    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }
    
    success([
        'list' => $list,
        'total' => (int)$total,
        'page' => $page,
        'pages' => ceil($total / $limit)
    ]);
}

function ticketDetail() {
    $id = (int)getParam('id', 0);
    if ($id <= 0) error('无效的工单ID');

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$row) error('工单不存在');

    // 权限检查：管理员可以直接查看，普通用户只能看自己的工单
    if (isAdminLoggedIn()) {
        // 管理员，直接放行
    } else {
        $info = requireWebToken();
        $player = $info['player'];
        if ($row['requester'] !== $player && $row['assigned_to'] !== $player) {
            error('无权查看此工单');
        }
    }

    // 获取回复列表
    $replies = getTicketReplies($id);

    $row['replies'] = $replies;
    // 附件图：老库/老工单没有 images 列时兜底为空数组
    $row['images'] = decodeTicketImages(isset($row['images']) ? $row['images'] : null);
    success($row);
}

function ticketReply() {
    $info = requireWebToken();
    $player = $info['player'];
    $id = (int)getParam('id', 0);
    $message = trim(getParam('message', ''));
    
    if ($id <= 0) error('无效的工单ID');
    if (empty($message)) error('回复内容不能为空');
    if (mb_strlen($message) > 5000) error('回复内容不能超过5000字');
    
    $db = getDB();
    
    // 检查工单状态和权限
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if ($ticket['requester'] !== $player) error('只能回复自己的工单');
    if (in_array($ticket['status'], ['withdrawn', 'rejected'])) error('该工单已关闭，无法回复');
    
    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        // 插入回复
        $stmt = $db->prepare("INSERT INTO web_ticket_replies (ticket_id, sender, role, message, created_at) VALUES (:tid, :sender, 'user', :msg, :now)");
        $stmt->bindValue(':tid', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':sender', $player, SQLITE3_TEXT);
        $stmt->bindValue(':msg', $message, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();
        
        // 更新工单状态为已回复
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'replied', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('回复失败');
    }
    
    success(null, '回复成功');
}

function ticketWithdraw() {
    $info = requireWebToken();
    $player = $info['player'];
    $id = (int)getParam('id', 0);
    
    if ($id <= 0) error('无效的工单ID');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if ($ticket['requester'] !== $player) error('无权操作此工单');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');
    
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'withdrawn', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', time(), SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('撤销失败');
    }
    
    success(null, '工单已撤销');
}

// ===== 管理员端 =====

function ticketListAll() {
    requireAdmin();
    $db = getDB();

    // 兜底建表（服务器core.php可能版本旧）
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS web_tickets (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            type TEXT NOT NULL,
            status TEXT DEFAULT 'submitted',
            requester TEXT NOT NULL,
            assigned_to TEXT DEFAULT '',
            title TEXT NOT NULL,
            description TEXT DEFAULT '',
            images TEXT DEFAULT '[]',
            reject_reason TEXT DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL
        )");
        // ★ 迁移：老库补 images 列（工单附件图 URL 数组 JSON）
        try {
            $db->exec("ALTER TABLE web_tickets ADD COLUMN images TEXT DEFAULT '[]'");
        } catch (Exception $e) { /* 列已存在 */ }
        $db->exec("CREATE TABLE IF NOT EXISTS web_ticket_replies (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ticket_id INTEGER NOT NULL,
            sender TEXT NOT NULL,
            role TEXT DEFAULT 'user',
            message TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_wt_requester ON web_tickets(requester)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_wt_status ON web_tickets(status)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_wt_assigned ON web_tickets(assigned_to)");
        $db->exec("CREATE INDEX IF NOT EXISTS idx_wtr_ticket ON web_ticket_replies(ticket_id)");
    } catch (\Throwable $e) {
        error('工单表初始化失败: ' . $e->getMessage());
    }

    try {
    $status = getParam('status', 'all');
    $page = max(1, (int)getParam('page', 1));
    $search = trim(getParam('search', ''));
    $limit = 20;
    $offset = ($page - 1) * $limit;

    $where = "WHERE 1=1";
    $params = [];

    if ($status && $status !== 'all') {
        $where .= " AND status = :status";
        $params[':status'] = $status;
    }
    if ($search) {
        $where .= " AND (requester LIKE :search OR title LIKE :search2)";
        $params[':search'] = "%$search%";
        $params[':search2'] = "%$search%";
    }

    $countStmt = $db->prepare("SELECT COUNT(*) as total FROM web_tickets $where");
    foreach ($params as $k => $v) $countStmt->bindValue($k, $v, SQLITE3_TEXT);
    $total = $countStmt->execute()->fetchArray(SQLITE3_ASSOC)['total'];

    $stmt = $db->prepare("SELECT * FROM web_tickets $where ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
    foreach ($params as $k => $v) $stmt->bindValue($k, $v, SQLITE3_TEXT);
    $stmt->bindValue(':limit', $limit, SQLITE3_INTEGER);
    $stmt->bindValue(':offset', $offset, SQLITE3_INTEGER);
    $result = $stmt->execute();

    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }

    // 统计各状态数量
    $stats = [];
    foreach (['submitted', 'replied', 'completed', 'withdrawn', 'rejected'] as $s) {
        $cnt = $db->querySingle("SELECT COUNT(*) FROM web_tickets WHERE status = '$s'");
        $stats[$s] = (int)$cnt;
    }
    $stats['all'] = (int)$db->querySingle("SELECT COUNT(*) FROM web_tickets");

    success([
        'list' => $list,
        'total' => (int)$total,
        'page' => $page,
        'pages' => ceil($total / $limit),
        'stats' => $stats
    ]);
    } catch (\Throwable $e) {
        error('查询工单失败: ' . $e->getMessage());
    }
}

function ticketAdminReply() {
    requireAdmin();
    $id = (int)getParam('id', 0);
    $message = trim(getParam('message', ''));
    
    if ($id <= 0) error('无效的工单ID');
    if (empty($message)) error('回复内容不能为空');
    if (mb_strlen($message) > 5000) error('回复内容不能超过5000字');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');

    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("INSERT INTO web_ticket_replies (ticket_id, sender, role, message, created_at) VALUES (:tid, 'admin', 'admin', :msg, :now)");
        $stmt->bindValue(':tid', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':msg', $message, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();
        
        // 管理员回复 → 状态更新为已回复（不改变assigned_to，保留原服务商所有权）
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'replied', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('回复失败');
    }
    
    success(null, '回复成功');
}

function ticketReject() {
    requireAdmin();
    $id = (int)getParam('id', 0);
    $reason = trim(getParam('reason', ''));
    
    if ($id <= 0) error('无效的工单ID');
    if (empty($reason)) error('请输入驳回原因');
    if (mb_strlen($reason) > 500) error('驳回原因不能超过500字');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if ($ticket['status'] === 'withdrawn' || $ticket['status'] === 'rejected') error('该工单已关闭');
    
    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'rejected', reject_reason = :reason, updated_at = :now WHERE id = :id");
        $stmt->bindValue(':reason', $reason, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('驳回失败');
    }
    
    success(null, '工单已驳回');
}

function ticketAssign() {
    requireAdmin();
    $id = (int)getParam('id', 0);
    $provider = trim(getParam('provider', ''));
    
    if ($id <= 0) error('无效的工单ID');
    if (empty($provider)) error('请输入服务商名称');
    
    // 验证是否为服务商
    if (!isServiceProviderInGame($provider)) error("$provider 不是服务商");

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');

    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("UPDATE web_tickets SET assigned_to = :provider, status = 'replied', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':provider', $provider, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('分配失败');
    }
    
    success(null, "已分配给 $provider");
}

function ticketAdminCreate() {
    requireAdmin();
    $type = trim(getParam('type', ''));
    $title = trim(getParam('title', ''));
    $description = trim(getParam('description', ''));
    $provider = trim(getParam('provider', ''));

    if (empty($type)) error('请选择工单类型');
    if (empty($title)) error('请输入工单标题');
    if (mb_strlen($title) > 100) error('标题不能超过100字');
    if (mb_strlen($description) > 5000) error('描述不能超过5000字');

    $validTypes = ['bug', 'help', 'report', 'apply', 'other'];
    if (!in_array($type, $validTypes)) error('无效的工单类型');

    // 如果指定了服务商，验证身份
    if ($provider && !isServiceProviderInGame($provider)) {
        error("$provider 不是服务商");
    }

    $images = normalizeTicketImages();   // 附件图（≤3，必须来自自家图床）

    $db = getDB();
    $now = time();

    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("INSERT INTO web_tickets (type, status, requester, assigned_to, title, description, images, created_at, updated_at) VALUES (:type, 'replied', 'admin', :provider, :title, :desc, :images, :now, :now)");
        $stmt->bindValue(':type', $type, SQLITE3_TEXT);
        $stmt->bindValue(':provider', $provider, SQLITE3_TEXT);
        $stmt->bindValue(':title', $title, SQLITE3_TEXT);
        $stmt->bindValue(':desc', $description, SQLITE3_TEXT);
        $stmt->bindValue(':images', encodeTicketImages($images), SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();
        $ticketId = $db->lastInsertRowID();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('创建失败: ' . $e->getMessage());
    }

    $msg = '工单已创建';
    if ($provider) $msg .= "，已派发给 $provider";
    success(['id' => $ticketId], $msg);
}

function ticketAdminComplete() {
    requireAdmin();
    $id = (int)getParam('id', 0);

    if ($id <= 0) error('无效的工单ID');

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');

    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("INSERT INTO web_ticket_replies (ticket_id, sender, role, message, created_at) VALUES (:tid, 'admin', 'admin', '✅ 工单已由管理员标记完结', :now)");
        $stmt->bindValue(':tid', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();

        $stmt = $db->prepare("UPDATE web_tickets SET status = 'completed', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();

        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('操作失败');
    }

    success(null, '已标记完结');
}

// ===== 服务商端 =====

function ticketProviderList() {
    $info = requireWebToken();
    $player = $info['player'];

    // 验证服务商身份
    $role = getServiceProviderRole($player);
    if (!$role) error('你不是服务商');

    $db = getDB();
    $type = getParam('type', 'all');
    // processing: 活跃(已提交/已回复)
    // completed: 已完结/已撤销/已驳回
    // all: 全部跟过的工单

    $where = "WHERE assigned_to = :player";
    $params = [':player' => $player];

    if ($type === 'processing') {
        $where .= " AND status IN ('submitted', 'replied')";
    } elseif ($type === 'completed') {
        $where .= " AND status IN ('completed', 'withdrawn', 'rejected')";
    }
    // 'all' 不加status过滤

    $stmt = $db->prepare("SELECT * FROM web_tickets $where ORDER BY updated_at DESC LIMIT 100");
    foreach ($params as $k => $v) $stmt->bindValue($k, $v, SQLITE3_TEXT);
    $result = $stmt->execute();

    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }

    success(['list' => $list, 'role' => $role]);
}

function ticketAvailable() {
    $info = requireWebToken();
    $player = $info['player'];
    
    if (!isServiceProviderInGame($player)) error('你不是服务商');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE status = 'submitted' AND assigned_to = '' ORDER BY created_at DESC LIMIT 50");
    $result = $stmt->execute();
    
    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }
    
    success(['list' => $list]);
}

function ticketGrab() {
    $info = requireWebToken();
    $player = $info['player'];
    $id = (int)getParam('id', 0);
    
    if ($id <= 0) error('无效的工单ID');
    if (!isServiceProviderInGame($player)) error('你不是服务商');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    
    if (!$ticket) error('工单不存在');
    if ($ticket['status'] !== 'submitted') error('该工单已被接单');
    if (!empty($ticket['assigned_to'])) error('该工单已被分配');
    
    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("UPDATE web_tickets SET assigned_to = :provider, updated_at = :now WHERE id = :id");
        $stmt->bindValue(':provider', $player, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('抢单失败');
    }
    
    success(null, '抢单成功');
}

function ticketProviderReply() {
    $info = requireWebToken();
    $player = $info['player'];
    $id = (int)getParam('id', 0);
    $message = trim(getParam('message', ''));
    
    if ($id <= 0) error('无效的工单ID');
    if (empty($message)) error('回复内容不能为空');
    if (mb_strlen($message) > 5000) error('回复内容不能超过5000字');
    if (!isServiceProviderInGame($player)) error('你不是服务商');
    
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if ($ticket['assigned_to'] !== $player) error('这不是分配给你的工单');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');
    
    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        $stmt = $db->prepare("INSERT INTO web_ticket_replies (ticket_id, sender, role, message, created_at) VALUES (:tid, :sender, 'provider', :msg, :now)");
        $stmt->bindValue(':tid', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':sender', $player, SQLITE3_TEXT);
        $stmt->bindValue(':msg', $message, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();
        
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'replied', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();
        
        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('回复失败');
    }
    
    success(null, '回复成功');
}

function ticketProviderComplete() {
    $info = requireWebToken();
    $player = $info['player'];
    $id = (int)getParam('id', 0);

    if ($id <= 0) error('无效的工单ID');
    if (!isServiceProviderInGame($player)) error('你不是服务商');

    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_tickets WHERE id = :id");
    $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
    $ticket = $stmt->execute()->fetchArray(SQLITE3_ASSOC);
    if (!$ticket) error('工单不存在');
    if ($ticket['assigned_to'] !== $player) error('这不是分配给你的工单');
    if (in_array($ticket['status'], ['completed', 'withdrawn', 'rejected'])) error('该工单已关闭');

    $now = time();
    $db->exec('BEGIN IMMEDIATE');
    try {
        // 插入完结回复
        $stmt = $db->prepare("INSERT INTO web_ticket_replies (ticket_id, sender, role, message, created_at) VALUES (:tid, :sender, 'provider', '✅ 工单已处理完毕', :now)");
        $stmt->bindValue(':tid', $id, SQLITE3_INTEGER);
        $stmt->bindValue(':sender', $player, SQLITE3_TEXT);
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->execute();

        // 状态改为completed
        $stmt = $db->prepare("UPDATE web_tickets SET status = 'completed', updated_at = :now WHERE id = :id");
        $stmt->bindValue(':now', $now, SQLITE3_INTEGER);
        $stmt->bindValue(':id', $id, SQLITE3_INTEGER);
        $stmt->execute();

        $db->exec('COMMIT');
    } catch (Exception $e) {
        $db->exec('ROLLBACK');
        error('操作失败');
    }

    success(null, '已标记完结');
}

// ===== 通用 =====

function ticketProviders() {
    if (!isAdminLoggedIn()) error('未登录管理后台');
    
    $loginDbPath = GAME_LOGIN_DB;
    if (!file_exists($loginDbPath)) success(['list' => []]);
    
    try {
        $loginDb = new SQLite3($loginDbPath);
        $result = $loginDb->query("SELECT player_name, role, join_time FROM service_providers WHERE active = 1");
        $list = [];
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $list[] = $row;
        }
        $loginDb->close();
        success(['list' => $list]);
    } catch (Exception $e) {
        success(['list' => []]);
    }
}

function ticketProviderCheck() {
    $info = requireWebToken();
    $player = $info['player'];
    $isProvider = isServiceProviderInGame($player);
    $role = $isProvider ? getServiceProviderRole($player) : null;
    success(['is_provider' => $isProvider, 'role' => $role]);
}

// ===== 辅助函数 =====

function getTicketReplies($ticketId) {
    $db = getDB();
    $stmt = $db->prepare("SELECT * FROM web_ticket_replies WHERE ticket_id = :tid ORDER BY created_at ASC");
    $stmt->bindValue(':tid', $ticketId, SQLITE3_INTEGER);
    $result = $stmt->execute();
    
    $list = [];
    while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
        $list[] = $row;
    }
    return $list;
}
