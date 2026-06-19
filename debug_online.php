<?php
/**
 * 调试页面 - 检查online_players表状态
 */
header('Content-Type: text/html; charset=utf-8');
require_once __DIR__ . '/core.php';

$db = getDB();
$result = [];

// 1. online_players表内容
try {
    $rows = $db->query("SELECT * FROM online_players ORDER BY login_time DESC")->fetchArray(SQLITE3_ASSOC);
    $onlinePlayers = [];
    while ($row = $db->query("SELECT * FROM online_players ORDER BY login_time DESC")->fetchArray(SQLITE3_ASSOC)) {
        $onlinePlayers[] = $row;
    }
    // 重新查询
    $stmt = $db->query("SELECT * FROM online_players ORDER BY login_time DESC");
    $onlinePlayers = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $onlinePlayers[] = $row;
    }
    $result['online_players'] = $onlinePlayers;
    $result['online_players_count'] = count($onlinePlayers);
} catch (Exception $e) {
    $result['online_players_error'] = $e->getMessage();
}

// 2. online_player_hb表（心跳）
try {
    $stmt = $db->query("SELECT * FROM online_player_hb");
    $hb = $stmt->fetchArray(SQLITE3_ASSOC);
    $result['heartbeat'] = $hb;
    if ($hb) {
        $result['heartbeat_ago'] = time() - (int)$hb['last_seen'] . '秒前';
    }
} catch (Exception $e) {
    $result['heartbeat_error'] = $e->getMessage();
}

// 3. weblogin_tokens表
try {
    $stmt = $db->query("SELECT player_name, created_at, expire_seconds, datetime(created_at, 'unixepoch', 'localtime') as created_time FROM weblogin_tokens ORDER BY created_at DESC LIMIT 20");
    $tokens = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $tokens[] = $row;
    }
    $result['weblogin_tokens'] = $tokens;
} catch (Exception $e) {
    $result['tokens_error'] = $e->getMessage();
}

// 4. web_session_log表
try {
    $stmt = $db->query("SELECT player_name, login_time, datetime(login_time, 'unixepoch', 'localtime') as login_time_str FROM web_session_log ORDER BY login_time DESC LIMIT 20");
    $sessions = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $sessions[] = $row;
    }
    $result['web_session_log'] = $sessions;
} catch (Exception $e) {
    $result['sessions_error'] = $e->getMessage();
}

// 5. web_login_verified表
try {
    $stmt = $db->query("SELECT * FROM web_login_verified ORDER BY verified_at DESC LIMIT 20");
    $verified = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $verified[] = $row;
    }
    $result['web_login_verified'] = $verified;
} catch (Exception $e) {
    $result['verified_error'] = $e->getMessage();
}

// 6. users表（部分）
try {
    $stmt = $db->query("SELECT player_name, logged_in FROM users LIMIT 20");
    $users = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $users[] = $row;
    }
    $result['users'] = $users;
} catch (Exception $e) {
    $result['users_error'] = $e->getMessage();
}
?>
<!DOCTYPE html>
<html>
<head>
    <title>调试 - Online Players</title>
    <style>
        body { font-family: monospace; background: #1a1a2e; color: #e0e0e0; padding: 20px; }
        h1 { color: #00d4ff; }
        h2 { color: #ff6b6b; margin-top: 30px; border-bottom: 1px solid #333; padding-bottom: 5px; }
        table { border-collapse: collapse; width: 100%; margin: 10px 0; }
        th, td { border: 1px solid #444; padding: 8px 12px; text-align: left; }
        th { background: #16213e; color: #00d4ff; }
        td { background: #0f3460; }
        .empty { color: #ff6b6b; font-weight: bold; }
        .ok { color: #4ecca3; }
        .warn { color: #ffd93d; }
        pre { background: #0a0a23; padding: 15px; border-radius: 5px; overflow-x: auto; }
        .refresh { background: #00d4ff; color: #000; padding: 10px 20px; border: none; cursor: pointer; font-size: 16px; border-radius: 5px; }
        .refresh:hover { background: #00b8d4; }
    </style>
</head>
<body>
    <h1>🔍 Online Players 调试页面</h1>
    <button class="refresh" onclick="location.reload()">🔄 刷新</button>
    <p>当前时间: <?php echo date('Y-m-d H:i:s') . ' (Unix: ' . time() . ')'; ?></p>

    <h2>1. online_players 表 (Java推送的游戏登录状态)</h2>
    <?php if (empty($result['online_players'])): ?>
        <p class="empty">⚠️ 表为空！没有玩家被标记为游戏中已登录</p>
    <?php else: ?>
        <table>
            <tr><th>玩家名</th><th>登录时间</th><th>时间戳</th></tr>
            <?php foreach ($result['online_players'] as $p): ?>
            <tr>
                <td><?= htmlspecialchars($p['player_name']) ?></td>
                <td><?= date('Y-m-d H:i:s', (int)$p['login_time']) ?></td>
                <td><?= $p['login_time'] ?></td>
            </tr>
            <?php endforeach; ?>
        </table>
    <?php endif; ?>
    <p>总数: <span class="<?= $result['online_players_count'] > 0 ? 'ok' : 'empty' ?>"><?= $result['online_players_count'] ?></span></p>

    <h2>2. 心跳 (online_player_hb)</h2>
    <?php if (isset($result['heartbeat']) && $result['heartbeat']): ?>
        <p>上次心跳: <?= date('Y-m-d H:i:s', (int)$result['heartbeat']['last_seen']) ?> (<span class="warn"><?= $result['heartbeat_ago'] ?></span>)</p>
        <?php
        $ago = time() - (int)$result['heartbeat']['last_seen'];
        if ($ago > 120) echo '<p class="empty">⚠️ 心跳超过120秒，下次同步时PHP会清空online_players表！</p>';
        elseif ($ago > 60) echo '<p class="warn">⚠️ 心跳超过60秒</p>';
        else echo '<p class="ok">✅ 心跳正常</p>';
        ?>
    <?php else: ?>
        <p class="empty">⚠️ 无心跳记录</p>
    <?php endif; ?>

    <h2>3. weblogin_tokens (Web登录Token)</h2>
    <?php if (empty($result['weblogin_tokens'])): ?>
        <p class="empty">⚠️ 无Token记录</p>
    <?php else: ?>
        <table>
            <tr><th>玩家</th><th>创建时间</th><th>有效期(秒)</th></tr>
            <?php foreach ($result['weblogin_tokens'] as $t): ?>
            <tr>
                <td><?= htmlspecialchars($t['player_name']) ?></td>
                <td><?= $t['created_time'] ?></td>
                <td><?= $t['expire_seconds'] ?></td>
            </tr>
            <?php endforeach; ?>
        </table>
    <?php endif; ?>

    <h2>4. web_session_log (Web会话记录)</h2>
    <?php if (empty($result['web_session_log'])): ?>
        <p class="empty">⚠️ 无会话记录</p>
    <?php else: ?>
        <table>
            <tr><th>玩家</th><th>登录时间</th><th>时间戳</th></tr>
            <?php foreach ($result['web_session_log'] as $s): ?>
            <tr>
                <td><?= htmlspecialchars($s['player_name']) ?></td>
                <td><?= $s['login_time_str'] ?></td>
                <td><?= $s['login_time'] ?></td>
            </tr>
            <?php endforeach; ?>
        </table>
    <?php endif; ?>

    <h2>5. web_login_verified (Web密码验证)</h2>
    <?php if (empty($result['web_login_verified'])): ?>
        <p class="empty">⚠️ 无验证记录</p>
    <?php else: ?>
        <table>
            <tr><th>玩家</th><th>验证时间</th></tr>
            <?php foreach ($result['web_login_verified'] as $v): ?>
            <tr>
                <td><?= htmlspecialchars($v['player_name']) ?></td>
                <td><?= date('Y-m-d H:i:s', (int)$v['verified_at']) ?></td>
            </tr>
            <?php endforeach; ?>
        </table>
    <?php endif; ?>

    <h2>6. users表 (玩家注册状态)</h2>
    <?php if (empty($result['users'])): ?>
        <p class="empty">⚠️ 无注册玩家</p>
    <?php else: ?>
        <table>
            <tr><th>玩家名</th><th>logged_in</th></tr>
            <?php foreach ($result['users'] as $u): ?>
            <tr>
                <td><?= htmlspecialchars($u['player_name']) ?></td>
                <td class="<?= $u['logged_in'] ? 'ok' : 'warn' ?>"><?= $u['logged_in'] ? '✅ 已登录' : '❌ 未登录' ?></td>
            </tr>
            <?php endforeach; ?>
        </table>
    <?php endif; ?>

    <h2>原始JSON</h2>
    <pre><?= json_encode($result, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE) ?></pre>
</body>
</html>
