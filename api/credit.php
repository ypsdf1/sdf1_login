<?php
// ★ 信誉积分API - 用户上报 / 管理员审批 / 查询积分
// ★ Version: 2026-10-28
ob_start();
if (function_exists('opcache_invalidate')) { @opcache_invalidate(__FILE__); }
while (ob_get_level() > 0) ob_end_clean();
ob_start();

$coreFile = __DIR__ . '/../core.php';
if (!file_exists($coreFile)) {
    while (ob_get_level() > 0) ob_end_clean();
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['success' => false, 'message' => 'core.php not found'], JSON_UNESCAPED_UNICODE);
    exit;
}
set_error_handler(function($errno, $errstr, $errfile, $errline) {
    @error_log("[credit.php PRE-LOAD] $errstr in $errfile on line $errline");
    return true;
});
require_once $coreFile;

$action = $_GET['action'] ?? '';
$db = getDB();

// ====== 初始化表结构 ======
function ensureCreditTables($db) {
    $db->exec("CREATE TABLE IF NOT EXISTS credit_records (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        target_player TEXT NOT NULL,
        reporter_player TEXT NOT NULL,
        type TEXT NOT NULL,
        content TEXT DEFAULT '',
        status TEXT DEFAULT 'pending',
        score_change INTEGER DEFAULT 0,
        created_at INTEGER DEFAULT 0,
        approved_at INTEGER DEFAULT 0,
        approve_reason TEXT DEFAULT '',
        source TEXT DEFAULT 'web'
    )");
    $db->exec("CREATE TABLE IF NOT EXISTS credit_rules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        rule_name TEXT NOT NULL UNIQUE,
        min_score INTEGER DEFAULT 0,
        max_score INTEGER DEFAULT 0,
        restriction_type TEXT DEFAULT '',
        restriction_duration INTEGER DEFAULT 0,
        enabled INTEGER DEFAULT 1,
        description TEXT DEFAULT ''
    )");
    // users 表加 credit_score 列（如果不存在）
    $ci = $db->query("PRAGMA table_info(users)");
    $cols = [];
    while ($c = $ci->fetchArray(SQLITE3_ASSOC)) $cols[] = $c['name'];
    if (!in_array('credit_score', $cols)) {
        $db->exec("ALTER TABLE users ADD COLUMN credit_score INTEGER DEFAULT 100");
    }
    // 默认规则
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) VALUES ('禁止交易', 60, 0, 'trade', 0, '低于60分时禁止与NPC交易')");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) VALUES ('禁止PVP', 40, 0, 'pvp', 0, '低于40分时禁止进入PVP竞技场')");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, description) VALUES ('禁止传送', 20, 0, 'teleport', 0, '低于20分时禁止使用传送功能')");
}

ensureCreditTables($db);

/** 玩家端 token 校验，返回玩家名 */
function creditRequirePlayer() {
    $token = getParam('token', '');
    if (empty($token)) {
        error('未登录', 401);
    }
    $info = validateTokenSilent($token);
    if (!$info || empty($info['player'])) {
        error('Token 无效或已过期', 401);
    }
    return $info['player'];
}

/** 管理端 session 校验 */
function creditRequireAdmin() {
    if (empty($_SESSION['admin_logged_in']) || !$_SESSION['admin_logged_in']) {
        error('需要管理员权限', 401);
    }
}

switch ($action) {
    // ====== 用户端：上报好人好事 ======
    case 'submit_good':
        $player = creditRequirePlayer();
        $target = getParam('target', '');
        $content = getParam('content', '');
        if (!$target) error('缺少目标玩家名');
        $stmt = $db->prepare("INSERT INTO credit_records (target_player, reporter_player, type, content, created_at, source) VALUES (?, ?, 'good', ?, ?, 'web')");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $stmt->bindValue(2, $player, SQLITE3_TEXT);
        $stmt->bindValue(3, $content, SQLITE3_TEXT);
        $stmt->bindValue(4, time(), SQLITE3_INTEGER);
        $stmt->execute();
        success(['id' => $db->lastInsertRowID()], '好人好事上报成功，等待管理员审批');
        break;

    // ====== 用户端：举报违规 ======
    case 'submit_bad':
        $player = creditRequirePlayer();
        $target = getParam('target', '');
        $content = getParam('content', '');
        if (!$target) error('缺少目标玩家名');
        $stmt = $db->prepare("INSERT INTO credit_records (target_player, reporter_player, type, content, created_at, source) VALUES (?, ?, 'bad', ?, ?, 'web')");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $stmt->bindValue(2, $player, SQLITE3_TEXT);
        $stmt->bindValue(3, $content, SQLITE3_TEXT);
        $stmt->bindValue(4, time(), SQLITE3_INTEGER);
        $stmt->execute();
        success(['id' => $db->lastInsertRowID()], '举报已提交，等待管理员审批');
        break;

    // ====== 用户端：查询自己的积分 ======
    case 'my_score':
        $player = creditRequirePlayer();
        $stmt = $db->prepare("SELECT credit_score FROM users WHERE player_name = ?");
        $stmt->bindValue(1, $player, SQLITE3_TEXT);
        $res = $stmt->execute();
        $score = 100;
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (int)$row['credit_score'];
        success(['player' => $player, 'credit_score' => $score]);
        break;

    // ====== 用户端：查询他人积分 ======
    case 'user_score':
        $player = creditRequirePlayer();
        $target = getParam('target', '');
        if (!$target) error('缺少目标玩家名');
        $stmt = $db->prepare("SELECT credit_score FROM users WHERE player_name = ?");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $res = $stmt->execute();
        $score = 100;
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (int)$row['credit_score'];
        success(['player' => $target, 'credit_score' => $score]);
        break;

    // ====== 用户端：查看自己的积分记录 ======
    case 'my_records':
        $player = creditRequirePlayer();
        $stmt = $db->prepare("SELECT * FROM credit_records WHERE target_player = ? ORDER BY created_at DESC LIMIT 50");
        $stmt->bindValue(1, $player, SQLITE3_TEXT);
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) $records[] = $row;
        success(['records' => $records]);
        break;

    // ====== 管理员：查看待审批列表 ======
    case 'pending_list':
        creditRequireAdmin();
        $stmt = $db->prepare("SELECT * FROM credit_records WHERE status = 'pending' ORDER BY created_at DESC LIMIT 100");
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) $records[] = $row;
        success(['records' => $records]);
        break;

    // ====== 管理员：审批通过 ======
    case 'approve':
        creditRequireAdmin();
        $recordId = (int)getParam('id', 0);
        $amount = (int)getParam('amount', 10);
        $reason = getParam('reason', '信誉积分审批通过');
        if ($recordId <= 0) error('缺少记录ID');
        // 查目标玩家
        $stmt = $db->prepare("SELECT target_player FROM credit_records WHERE id = ?");
        $stmt->bindValue(1, $recordId, SQLITE3_INTEGER);
        $res = $stmt->execute();
        $target = '';
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $target = $row['target_player'];
        if (!$target) error('记录不存在');
        // 更新记录
        $stmt = $db->prepare("UPDATE credit_records SET status = 'approved', score_change = ?, approved_at = ?, approve_reason = ? WHERE id = ?");
        $stmt->bindValue(1, $amount, SQLITE3_INTEGER);
        $stmt->bindValue(2, time(), SQLITE3_INTEGER);
        $stmt->bindValue(3, $reason, SQLITE3_TEXT);
        $stmt->bindValue(4, $recordId, SQLITE3_INTEGER);
        $stmt->execute();
        // 更新积分
        $stmt = $db->prepare("UPDATE users SET credit_score = credit_score + ? WHERE player_name = ?");
        $stmt->bindValue(1, $amount, SQLITE3_INTEGER);
        $stmt->bindValue(2, $target, SQLITE3_TEXT);
        $stmt->execute();
        success(['target' => $target, 'amount' => $amount], '审批通过，积分已更新');
        break;

    // ====== 管理员：审批驳回 ======
    case 'reject':
        creditRequireAdmin();
        $recordId = (int)getParam('id', 0);
        $reason = getParam('reason', '管理员驳回');
        if ($recordId <= 0) error('缺少记录ID');
        $stmt = $db->prepare("UPDATE credit_records SET status = 'rejected', approved_at = ?, approve_reason = ? WHERE id = ?");
        $stmt->bindValue(1, time(), SQLITE3_INTEGER);
        $stmt->bindValue(2, $reason, SQLITE3_TEXT);
        $stmt->bindValue(3, $recordId, SQLITE3_INTEGER);
        $stmt->execute();
        success(null, '已驳回，不扣分不加分');
        break;

    // ====== 管理员：查看审批记录 ======
    case 'approved_list':
        creditRequireAdmin();
        $stmt = $db->prepare("SELECT * FROM credit_records ORDER BY created_at DESC LIMIT 100");
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) $records[] = $row;
        success(['records' => $records]);
        break;

    // ====== 管理员：查看所有规则 ======
    case 'rules':
        creditRequireAdmin();
        $stmt = $db->prepare("SELECT * FROM credit_rules ORDER BY min_score ASC");
        $res = $stmt->execute();
        $rules = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) $rules[] = $row;
        success(['rules' => $rules]);
        break;

    // ====== 管理员：更新规则 ======
    case 'update_rule':
        creditRequireAdmin();
        $ruleId = (int)getParam('id', 0);
        $enabled = (int)getParam('enabled', 1);
        if ($ruleId <= 0) error('缺少规则ID');
        $stmt = $db->prepare("UPDATE credit_rules SET enabled = ? WHERE id = ?");
        $stmt->bindValue(1, $enabled, SQLITE3_INTEGER);
        $stmt->bindValue(2, $ruleId, SQLITE3_INTEGER);
        $stmt->execute();
        success(null, '规则已更新');
        break;

    default:
        error('未知操作: ' . $action);
}
