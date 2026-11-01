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
// ★ 第65批：未捕获异常（如 DB 连接断开、SQL 执行失败抛出的 Exception）
//   默认会被 PHP 顶层处理器输出成 HTML 错误页，污染 JSON 响应导致前端崩溃。
//   这里把所有未捕获异常兜底转为 JSON，保证 API 任何路径都返回合法 JSON。
set_exception_handler(function($ex) {
    @error_log('[credit.php UNCAUGHT] ' . $ex->getMessage()
        . ' @ ' . $ex->getFile() . ':' . $ex->getLine());
    while (ob_get_level() > 0) ob_end_clean();
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'message' => '服务器内部错误: ' . $ex->getMessage(),
    ], JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_SUBSTITUTE);
    exit;
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
        score_change REAL DEFAULT 0,
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
        enabled INTEGER DEFAULT 0,
        description TEXT DEFAULT '',
        limit_amount REAL DEFAULT 0,
        multiplier REAL DEFAULT 0,
        amount REAL DEFAULT 0
    )");
    // ★ 第63批：已有表扩展新列
    $ci2 = $db->query("PRAGMA table_info(credit_rules)");
    $crCols = [];
    while ($c = $ci2->fetchArray(SQLITE3_ASSOC)) $crCols[] = $c['name'];
    if (!in_array('limit_amount', $crCols)) {
        $db->exec("ALTER TABLE credit_rules ADD COLUMN limit_amount REAL DEFAULT 0");
    }
    if (!in_array('multiplier', $crCols)) {
        $db->exec("ALTER TABLE credit_rules ADD COLUMN multiplier REAL DEFAULT 0");
    }
    // ★ 第64批：amount 列
    if (!in_array('amount', $crCols)) {
        $db->exec("ALTER TABLE credit_rules ADD COLUMN amount REAL DEFAULT 0");
    }
    // users 表加 credit_score 列（如果不存在）
    $ci = $db->query("PRAGMA table_info(users)");
    $cols = [];
    while ($c = $ci->fetchArray(SQLITE3_ASSOC)) $cols[] = $c['name'];
    if (!in_array('credit_score', $cols)) {
        // ★ 第58批-问题4：REAL 支持小数积分
        $db->exec("ALTER TABLE users ADD COLUMN credit_score REAL DEFAULT 100");
    }
    // ★ 第63批：6条内置规则，默认全部禁用
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('债券交易限额5000', 60, 0, 'bond_trade_limit', 0, 0, '低于60分时债券每小时交易限额5000元(买入/卖出全算)', 5000, 0)");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('禁止群系传送', 20, 0, 'block_biome_tp', 0, 0, '低于20分时禁止使用群系传送', 0, 0)");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('二手商店低信誉警示', 0, 0, 'shop_low_warning', 0, 0, '低于0分时二手商店商品标记为低信誉卖家，提示买家谨慎购买', 0, 0)");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('禁止CDK+债券限额1000', -10, 0, 'block_cdk_and_bond', 0, 0, '低于-10分时禁止使用CDK兑换码，债券每小时交易限额1000元(买入/卖出全算)', 1000, 0)");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('债券交易额1.2倍', 0, 80, 'bond_trade_bonus', 0, 0, '高于80分时债券每小时交易额1.2倍(接入债券出售限额)', 0, 1.2)");
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier) VALUES ('二手商店高信誉推荐', 0, 100, 'shop_high_recommend', 0, 0, '高于100分时二手商店商品标记为信誉极好卖家，可以放心购买', 0, 0)");
    // ★ 第64批：新规则7 — 每日登录自动恢复积分（默认启用，amount=0.1）
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) VALUES ('每日登录恢复0.1分', 0, 0, 'login_restore', 0, 1, '每日登录自动恢复信誉积分(默认0.1分，可配置)', 0, 0, 0.1)");
    // ★ 第64批：新规则8 — 聊天违规扣分（默认禁用，amount=0.5）
    $db->exec("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) VALUES ('聊天违规扣0.5分', 0, 0, 'chat_penalty', 0, 0, '发送脏话或违规链接扣除信誉积分(默认0.5分，可配置)', 0, 0, 0.5)");
    // ★ 第63批：清理旧规则
    $db->exec("DELETE FROM credit_rules WHERE rule_name IN ('禁止交易', '禁止PVP', '禁止传送')");
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

/**
 * ★ 第58批续29-问题2：审批/调整积分后通知 Java 立即拉取信誉积分数据
 *   写入 sync_requests 标记，Java 的 doActiveSyncBatch 检测到后会执行 pullCreditFromPhp
 */
function creditNotifyJavaSync($db, $playerName) {
    if (!$playerName) return;
    try {
        $db->exec("CREATE TABLE IF NOT EXISTS sync_requests (player_name TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        $stmt = $db->prepare("INSERT OR REPLACE INTO sync_requests (player_name, created_at) VALUES (:player, :time)");
        $stmt->bindValue(':player', 'credit_sync_' . $playerName, SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();
    } catch (Exception $e) {
        @error_log('[credit.php] sync_requests写入失败: ' . $e->getMessage());
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
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (float)$row['credit_score'];
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
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (float)$row['credit_score'];
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

    // ====== 用户端：查看自己提交的上报（我的上报） ======
    // ★ 第59批-问题3：查询自己作为 reporter_player 的记录
    case 'my_reports':
        $player = creditRequirePlayer();
        $stmt = $db->prepare("SELECT * FROM credit_records WHERE reporter_player = ? ORDER BY created_at DESC LIMIT 50");
        $stmt->bindValue(1, $player, SQLITE3_TEXT);
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) $records[] = $row;
        success(['records' => $records]);
        break;

    // ====== 用户端：撤回自己提交的未批准/驳回的上报 ======
    // ★ 第59批-问题3：撤回未批准(pending)或已驳回(rejected)的上报
    //   联动Java端：写 sync_requests 标记，Java 端 pullCreditFromPhp 拉回后同步删除/标记
    case 'revoke':
        $player = creditRequirePlayer();
        $recordId = (int)getParam('id', 0);
        if ($recordId <= 0) error('缺少记录ID');

        // 验证：只能撤回自己提交的记录
        $stmt = $db->prepare("SELECT reporter_player, status FROM credit_records WHERE id = ?");
        $stmt->bindValue(1, $recordId, SQLITE3_INTEGER);
        $res = $stmt->execute();
        $row = $res->fetchArray(SQLITE3_ASSOC);
        if (!$row) error('记录不存在');
        if ($row['reporter_player'] !== $player) error('只能撤回自己提交的上报');

        // 只能撤回未批准(pending)或已驳回(rejected)的记录
        $status = $row['status'];
        if ($status === 'approved') error('已通过的上报不能撤回');

        // ★ 第59批-问题3：把 status 改为 'revoked'（不删除，保留审计痕迹）
        $stmt = $db->prepare("UPDATE credit_records SET status = 'revoked', approve_reason = '上报人自行撤回' WHERE id = ?");
        $stmt->bindValue(1, $recordId, SQLITE3_INTEGER);
        $stmt->execute();

        // ★ 联动Java端：写 sync_requests 标记，Java 端 pullCreditFromPhp 拉回后同步标记 revoked
        creditNotifyJavaSync($db, $row['target_player'] ?? '');
        // 也通知上报人自己（确保 Java 端也更新）
        creditNotifyJavaSync($db, $player);

        success(['id' => $recordId], '上报已撤回');
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
        $amount = (float)getParam('amount', 10);
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
        $stmt->bindValue(1, $amount, SQLITE3_FLOAT);
        $stmt->bindValue(2, time(), SQLITE3_INTEGER);
        $stmt->bindValue(3, $reason, SQLITE3_TEXT);
        $stmt->bindValue(4, $recordId, SQLITE3_INTEGER);
        $stmt->execute();
        // 更新积分
        $stmt = $db->prepare("UPDATE users SET credit_score = credit_score + ? WHERE player_name = ?");
        $stmt->bindValue(1, $amount, SQLITE3_FLOAT);
        $stmt->bindValue(2, $target, SQLITE3_TEXT);
        $stmt->execute();

        // ★ 第58批续29-问题2：审批通过后通知Java立即拉取信誉积分
        creditNotifyJavaSync($db, $target);
        success(['target' => $target, 'amount' => $amount], '审批通过，积分已更新');
        break;

    // ====== 管理员：审批驳回 ======
    case 'reject':
        creditRequireAdmin();
        $recordId = (int)getParam('id', 0);
        $reason = getParam('reason', '管理员驳回');
        if ($recordId <= 0) error('缺少记录ID');
        // 查目标玩家（驳回也要通知Java，可能玩家正在线需要刷新权限）
        $stmt = $db->prepare("SELECT target_player FROM credit_records WHERE id = ?");
        $stmt->bindValue(1, $recordId, SQLITE3_INTEGER);
        $res = $stmt->execute();
        $rejectTarget = '';
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $rejectTarget = $row['target_player'];
        $stmt = $db->prepare("UPDATE credit_records SET status = 'rejected', approved_at = ?, approve_reason = ? WHERE id = ?");
        $stmt->bindValue(1, time(), SQLITE3_INTEGER);
        $stmt->bindValue(2, $reason, SQLITE3_TEXT);
        $stmt->bindValue(3, $recordId, SQLITE3_INTEGER);
        $stmt->execute();

        // ★ 第58批续29-问题2：审批驳回后通知Java刷新该玩家权限状态
        creditNotifyJavaSync($db, $rejectTarget);
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

    // ====== 管理员：更新规则（支持完整参数） ======
    case 'update_rule':
        creditRequireAdmin();
        $ruleId = (int)getParam('id', 0);
        if ($ruleId <= 0) error('缺少规则ID');
        // 构建动态 UPDATE
        $sets = [];
        $params = [];
        $paramTypes = [];
        $intFields = ['min_score', 'max_score', 'enabled', 'restriction_duration'];
        $floatFields = ['limit_amount', 'multiplier', 'amount'];
        $strFields = ['restriction_type', 'description'];
        foreach ($intFields as $f) {
            $v = getParam($f, null);
            if ($v !== null) { $sets[] = "$f = ?"; $params[] = (int)$v; $paramTypes[] = SQLITE3_INTEGER; }
        }
        foreach ($floatFields as $f) {
            $v = getParam($f, null);
            if ($v !== null) { $sets[] = "$f = ?"; $params[] = (float)$v; $paramTypes[] = SQLITE3_FLOAT; }
        }
        foreach ($strFields as $f) {
            $v = getParam($f, null);
            if ($v !== null) { $sets[] = "$f = ?"; $params[] = $v; $paramTypes[] = SQLITE3_TEXT; }
        }
        if (empty($sets)) error('没有要更新的字段');
        $sql = "UPDATE credit_rules SET " . implode(', ', $sets) . " WHERE id = ?";
        $params[] = $ruleId;
        $paramTypes[] = SQLITE3_INTEGER;
        try {
            $stmt = $db->prepare($sql);
            for ($i = 0; $i < count($params); $i++) {
                $stmt->bindValue($i + 1, $params[$i], $paramTypes[$i]);
            }
            $stmt->execute();
        } catch (\Throwable $e) {
            @error_log('[credit.php] update_rule 失败: ' . $e->getMessage());
            error('规则更新失败: ' . $e->getMessage(), 500);
        }
        // ★ 第64批：规则变更后通知 Java 立即拉取
        creditNotifyJavaSync($db, '__rules_sync__');
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
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $score = (float)$row['credit_score'];
        success(['player' => $target, 'credit_score' => $score]);
        break;

    // ====== 管理员：手动加减积分（直接调整玩家积分） ======
    case 'admin_adjust_score':
        creditRequireAdmin();
        $target = getParam('target', '');
        $amount = (float)getParam('amount', 0);
        $reason = getParam('reason', '管理员手动调整');
        if (!$target) error('缺少玩家名');
        if ($amount == 0) error('积分变动值不能为0');

        // 查当前积分
        $stmt = $db->prepare("SELECT credit_score FROM users WHERE player_name = ?");
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $res = $stmt->execute();
        $oldScore = 100;
        if ($row = $res->fetchArray(SQLITE3_ASSOC)) $oldScore = (float)$row['credit_score'];

        // 更新积分
        $newScore = $oldScore + $amount;
        if ($newScore < 0) $newScore = 0;
        if ($newScore > 1000) $newScore = 1000; // 上限防溢出
        $stmt = $db->prepare("UPDATE users SET credit_score = ? WHERE player_name = ?");
        $stmt->bindValue(1, $newScore, SQLITE3_FLOAT);
        $stmt->bindValue(2, $target, SQLITE3_TEXT);
        $stmt->execute();

        // 记录一条 credit_record（source='admin' 标记管理员手动调整）
        $stmt = $db->prepare("INSERT INTO credit_records (target_player, reporter_player, type, content, status, score_change, created_at, approved_at, approve_reason, source) VALUES (?, ?, ?, ?, 'approved', ?, ?, ?, ?, 'admin')");
        $type = $amount > 0 ? 'good' : 'bad';
        $stmt->bindValue(1, $target, SQLITE3_TEXT);
        $stmt->bindValue(2, $_SESSION['admin_user'] ?? 'admin', SQLITE3_TEXT);
        $stmt->bindValue(3, $type, SQLITE3_TEXT);
        $stmt->bindValue(4, $reason, SQLITE3_TEXT);
        $stmt->bindValue(5, $amount, SQLITE3_FLOAT);
        $stmt->bindValue(6, time(), SQLITE3_INTEGER);
        $stmt->bindValue(7, time(), SQLITE3_INTEGER);
        $stmt->bindValue(8, $reason, SQLITE3_TEXT);
        $stmt->execute();

        // ★ 第58批续29-问题2：管理员手动调整积分后通知Java立即拉取
        creditNotifyJavaSync($db, $target);
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
                    $stmt->bindValue(1, (float)$score, SQLITE3_FLOAT);
                    $stmt->bindValue(2, $pname, SQLITE3_TEXT);
                    $stmt->execute();
                } else {
                    // 玩家不存在则插入
                    $stmt = $db->prepare("INSERT INTO users (player_name, credit_score) VALUES (?, ?)");
                    $stmt->bindValue(1, $pname, SQLITE3_TEXT);
                    $stmt->bindValue(2, (float)$score, SQLITE3_FLOAT);
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
                $scoreChange = (float)($rec['score_change'] ?? 0);
                // ★ 第65批修复：Java 推送毫秒时间戳，PHP 统一用秒
                $createdAt = (int)(($rec['created_at'] ?? 0) / 1000);
                $approvedAt = (int)(($rec['approved_at'] ?? 0) / 1000);
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
                $stmt->bindValue(6, $scoreChange, SQLITE3_FLOAT);
                $stmt->bindValue(7, $createdAt, SQLITE3_INTEGER);
                $stmt->bindValue(8, $approvedAt, SQLITE3_INTEGER);
                $stmt->bindValue(9, $reason, SQLITE3_TEXT);
                $stmt->bindValue(10, $source, SQLITE3_TEXT);
                $stmt->execute();
            }
        }

        // ★ 第65批修复（#341）：接收 Java 端推送的规则（双向交换）
        //   Java 推规则时把本地完整状态推回，PHP 端按 rule_name 匹配更新
        //   开关状态由 Java 端决定（Java 最后改的说了算）
        $ruleData = getParam('rules', null);
        if (is_string($ruleData)) $ruleData = json_decode($ruleData, true);
        if (!is_array($ruleData)) $ruleData = [];

        if (!empty($ruleData)) {
            foreach ($ruleData as $r) {
                $ruleName = $r['rule_name'] ?? '';
                if (!$ruleName) continue;
                $minScore = (int)($r['min_score'] ?? 0);
                $maxScore = (int)($r['max_score'] ?? 0);
                $restrictionType = $r['restriction_type'] ?? '';
                $restrictionDuration = (int)($r['restriction_duration'] ?? 0);
                $enabled = (int)($r['enabled'] ?? 0);
                $description = $r['description'] ?? '';
                $limitAmount = (float)($r['limit_amount'] ?? 0);
                $multiplier = (float)($r['multiplier'] ?? 0);
                $amount = (float)($r['amount'] ?? 0);

                // 按 rule_name 匹配更新
                $stmt = $db->prepare("UPDATE credit_rules SET min_score=?, max_score=?, restriction_type=?, restriction_duration=?, enabled=?, description=?, limit_amount=?, multiplier=?, amount=? WHERE rule_name=?");
                $stmt->bindValue(1, $minScore, SQLITE3_INTEGER);
                $stmt->bindValue(2, $maxScore, SQLITE3_INTEGER);
                $stmt->bindValue(3, $restrictionType, SQLITE3_TEXT);
                $stmt->bindValue(4, $restrictionDuration, SQLITE3_INTEGER);
                $stmt->bindValue(5, $enabled, SQLITE3_INTEGER);
                $stmt->bindValue(6, $description, SQLITE3_TEXT);
                $stmt->bindValue(7, $limitAmount, SQLITE3_FLOAT);
                $stmt->bindValue(8, $multiplier, SQLITE3_FLOAT);
                $stmt->bindValue(9, $amount, SQLITE3_FLOAT);
                $stmt->bindValue(10, $ruleName, SQLITE3_TEXT);
                $stmt->execute();
                $changed = $db->changes();
                // PHP 没有的规则则插入
                if ($changed === 0) {
                    $stmt2 = $db->prepare("INSERT OR IGNORE INTO credit_rules (rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                    $stmt2->bindValue(1, $ruleName, SQLITE3_TEXT);
                    $stmt2->bindValue(2, $minScore, SQLITE3_INTEGER);
                    $stmt2->bindValue(3, $maxScore, SQLITE3_INTEGER);
                    $stmt2->bindValue(4, $restrictionType, SQLITE3_TEXT);
                    $stmt2->bindValue(5, $restrictionDuration, SQLITE3_INTEGER);
                    $stmt2->bindValue(6, $enabled, SQLITE3_INTEGER);
                    $stmt2->bindValue(7, $description, SQLITE3_TEXT);
                    $stmt2->bindValue(8, $limitAmount, SQLITE3_FLOAT);
                    $stmt2->bindValue(9, $multiplier, SQLITE3_FLOAT);
                    $stmt2->bindValue(10, $amount, SQLITE3_FLOAT);
                    $stmt2->execute();
                }
            }
        }

        success(['synced' => count($playerData ?? []) . ' players, ' . count($recordData ?? []) . ' records, ' . count($ruleData ?? []) . ' rules'], 'Java同步推送成功');
        break;

    // ====== Java 拉回同步：返回 PHP 端所有积分数据 + 规则数据 ======
    case 'sync_pull':
        $syncSecret = getParam('secret', '');
        if (!$syncSecret || $syncSecret !== SECRET_KEY) error('同步密钥无效', 401);

        // 返回所有玩家积分
        $stmt = $db->prepare("SELECT player_name, credit_score FROM users WHERE credit_score IS NOT NULL ORDER BY player_name");
        $res = $stmt->execute();
        $players = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $players[$row['player_name']] = (float)$row['credit_score'];
        }

        // 返回所有积分记录
        $stmt = $db->prepare("SELECT * FROM credit_records ORDER BY created_at DESC LIMIT 500");
        $res = $stmt->execute();
        $records = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $records[] = $row;
        }

        // ★ 第64批：返回所有规则（Java 端同步用）
        $stmt = $db->prepare("SELECT id, rule_name, min_score, max_score, restriction_type, restriction_duration, enabled, description, limit_amount, multiplier, amount FROM credit_rules ORDER BY id");
        $res = $stmt->execute();
        $rules = [];
        while ($row = $res->fetchArray(SQLITE3_ASSOC)) {
            $rules[] = $row;
        }

        success(['players' => $players, 'records' => $records, 'rules' => $rules], 'Java同步拉回成功');
        break;

    default:
        error('未知操作: ' . $action);
}
