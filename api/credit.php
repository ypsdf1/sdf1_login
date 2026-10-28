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
    if (session_status() === PHP_SESSION_NONE) session_start();
    // ★ 与 core.php requireAdminSession() 保持一致：admin_auth
    if (empty($_SESSION['admin_auth']) || !$_SESSION['admin_auth']) {
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

    // ====== 管理员：查询玩家当前积分 ======
    case 'admin_player_score':
        creditRequireAdmin();
        $target = getParam('target', '');
        if (!$target) error('缺少玩家名');
        $stmt = $db->prepare("SELECT credit_score FROM users WHERE player_name = ?");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $res = $stmt->execute();
        $score = 100;
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (int)$row['credit_score'];
        success(['player' => $target, 'credit_score' => $score]);
        break;

    // ====== 管理员：手动加减积分（直接调整玩家积分） ======
    case 'admin_adjust_score':
        creditRequireAdmin();
        $target = getParam('target', '');
        $amount = (int)getParam('amount', 0);
        $reason = getParam('reason', '管理员手动调整');
        if (!$target) error('缺少玩家名');
        if ($amount == 0) error('积分变动值不能为0');

        // 查当前积分
        $stmt = $db->prepare("SELECT credit_score FROM users WHERE player_name = ?");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $res = $stmt->execute();
        $oldScore = 100;
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $oldScore = (int)$row['credit_score'];

        // 更新积分
        $newScore = $oldScore + $amount;
        if ($newScore < 0) $newScore = 0;
        if ($newScore > 1000) $newScore = 1000; // 上限防溢出
        $stmt = $db->prepare("UPDATE users SET credit_score = ? WHERE player_name = ?");
        $stmt->bindValue(1, $newScore, SQLITE3_INTEGER);
        $stmt->bindValue(2, $target, SQLITE3_TEXT);
        $stmt->execute();

        // 记录一条 credit_record（source='admin' 标记管理员手动调整）
        $stmt = $db->prepare("INSERT INTO credit_records (target_player, reporter_player, type, content, status, score_change, created_at, approved_at, approve_reason, source) VALUES (?, ?, ?, ?, 'approved', ?, ?, ?, ?, 'admin')");
        $type = $amount > 0 ? 'good' : 'bad';
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $stmt->bindValue(2, $_SESSION['admin_user'] ?? 'admin', SQLITE3_TEXT);
        $stmt->bindValue(3, $type, SQLITE3_TEXT);
        $stmt->bindValue(4, $reason, SQLITE3_TEXT);
        $stmt->bindValue(5, $amount, SQLITE3_INTEGER);
        $stmt->bindValue(6, time(), SQLITE3_INTEGER);
        $stmt->bindValue(7, time(), SQLITE3_INTEGER);
        $stmt->bindValue(8, $reason, SQLITE3_TEXT);
        $stmt->execute();

        success(['target' => $target, 'old_score' => $oldScore, 'new_score' => $newScore, 'amount' => $amount],
            '积分已调整: ' . $target . ' ' . ($amount > 0 ? '+' : '') . $amount . ' (当前: ' . $newScore . ')');
        break;

    // ====== Java 推送同步：接收 Java 端推送的积分数据（覆盖 PHP 副本） ======
    case 'sync_push':
        // 接收 Java 端 SECRET_KEY 验证
        $syncSecret = getParam('secret', '');
        if (!$syncSecret || $syncSecret !== SECRET_KEY) error('同步密钥无效', 401);

        // ★ getParam 对 JSON body 直接返回已解码数组，无需再 json_decode（PHP8 json_decode(数组) 抛 TypeError）
        $playerData = getParam('players', null);
        if (is_string($playerData)) $playerData = json_decode($playerData, true);
        if (!is_array($playerData)) $playerData = [];

        $recordData = getParam('records', null);
        if (is_string($recordData)) $recordData = json_decode($recordData, true);
        if (!is_array($recordData)) $recordData = [];

        // 批量更新玩家积分
        // ★ 不用 beginTransaction：SdfMySqlDb(MySQL适配器) 未实现事务，__call 直接抛异常
        if (!empty($playerData)) {
            foreach ($playerData as $pname => $score) {
                // 确保玩家存在
                $stmt = $db->prepare("SELECT 1 FROM users WHERE player_name = ?");
                $stmt->bindValue(1, $pname, SQLITE3_TEXT);
                $res = $stmt->execute();
                if ($row = $res->fetchArray()) {
                    $stmt = $db->prepare("UPDATE users SET credit_score = ? WHERE player_name = ?");
                    $stmt->bindValue(1, (int)$score, SQLITE3_INTEGER);
                    $stmt->bindValue(2, $pname, SQLITE3_TEXT);
                    $stmt->execute();
                } else {
                    // 玩家不存在则插入
                    $stmt = $db->prepare("INSERT INTO users (player_name, credit_score) VALUES (?, ?)");
                    $stmt->bindValue(1, $pname, SQLITE3_TEXT);
                    $stmt->bindValue(2, (int)$score, SQLITE3_INTEGER);
                    $stmt->execute();
                }
            }
        }

        // 批量插入/更新积分记录（去重：同 target+source+created_at 的记录跳过）
        if (!empty($recordData)) {
            foreach ($recordData as $rec) {
                $target = $rec['target_player'] ?? '';
                $reporter = $rec['reporter_player'] ?? '';
                $type = $rec['type'] ?? 'good';
                $content = $rec['content'] ?? '';
                $status = $rec['status'] ?? 'approved';
                $scoreChange = (int)($rec['score_change'] ?? 0);
                $createdAt = (int)($rec['created_at'] ?? 0);
                $approvedAt = (int)($rec['approved_at'] ?? 0);
                $reason = $rec['approve_reason'] ?? '';
                $source = $rec['source'] ?? 'game';

                if (!$target || !$createdAt) continue;

                // 去重检查
                $stmt = $db->prepare("SELECT 1 FROM credit_records WHERE target_player = ? AND source = ? AND created_at = ? AND type = ?");
                $stmt->bindValue(1, $target, SQLITE3_TEXT);
                $stmt->bindValue(2, $source, SQLITE3_TEXT);
                $stmt->bindValue(3, $createdAt, SQLITE3_INTEGER);
                $stmt->bindValue(4, $type, SQLITE3_TEXT);
                $res = $stmt->execute();
                if ($row = $res->fetchArray()) continue; // 已存在，跳过

                $stmt = $db->prepare("INSERT INTO credit_records (target_player, reporter_player, type, content, status, score_change, created_at, approved_at, approve_reason, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                $stmt->bindValue(1, $target, SQLITE3_TEXT);
                $stmt->bindValue(2, $reporter, SQLITE3_TEXT);
                $stmt->bindValue(3, $type, SQLITE3_TEXT);
                $stmt->bindValue(4, $content, SQLITE3_TEXT);
                $stmt->bindValue(5, $status, SQLITE3_TEXT);
                $stmt->bindValue(6, $scoreChange, SQLITE3_INTEGER);
                $stmt->bindValue(7, $createdAt, SQLITE3_INTEGER);
                $stmt->bindValue(8, $approvedAt, SQLITE3_INTEGER);
                $stmt->bindValue(9, $reason, SQLITE3_TEXT);
                $stmt->bindValue(10, $source, SQLITE3_TEXT);
                $stmt->execute();
            }
        }

        success(['synced' => count($playerData ?? []) . ' players, ' . count($recordData ?? []) . ' records'], 'Java同步推送成功');
        break;

    // ====== Java 拉回同步：返回 PHP 端所有积分数据（管理员手动调整的部分） ======
    case 'sync_pull':
        $syncSecret = getParam('secret', '');
        if (!$syncSecret || $syncSecret !== SECRET_KEY) error('同步密钥无效', 401);

        // 返回所有玩家积分
        $stmt = $db->prepare("SELECT player_name, credit_score FROM users WHERE credit_score IS NOT NULL ORDER BY player_name");
        $res = $stmt->execute();
        $players = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $players[$row['player_name']] = (int)$row['credit_score'];
        }

        // 返回所有积分记录（标记 source='admin' 的是管理员手动调整的）
        $stmt = $db->prepare("SELECT * FROM credit_records ORDER BY created_at DESC LIMIT 500");
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $records[] = $row;
        }

        success(['players' => $players, 'records' => $records], 'Java同步拉回成功');
        break;

    default:
        error('未知操作: ' . $action);
}
