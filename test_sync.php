<?php
/**
 * 测试脚本 - 模拟Java推送online_players数据
 * 用于验证PHP端syncOnlinePlayers是否正常工作
 */
header('Content-Type: application/json; charset=utf-8');
require_once __DIR__ . '/core.php';

$db = getDB();
$action = $_GET['action'] ?? '';

if ($action === 'simulate_push') {
    // 模拟Java推送一个已登录的玩家
    $player = $_GET['player'] ?? 'yupaishidifu';
    $secret = $_GET['secret'] ?? SECRET_KEY;

    $players = [
        ['name' => $player, 'login_time' => time()]
    ];

    $db->exec("CREATE TABLE IF NOT EXISTS online_players (player_name TEXT PRIMARY KEY, login_time INTEGER NOT NULL)");
    $db->exec("CREATE TABLE IF NOT EXISTS online_player_hb (id INTEGER PRIMARY KEY DEFAULT 1, last_seen INTEGER DEFAULT 0)");

    $db->exec("BEGIN IMMEDIATE");
    try {
        $db->exec("DELETE FROM online_players");
        $stmt = $db->prepare("INSERT OR REPLACE INTO online_players (player_name, login_time) VALUES (:name, :time)");
        $stmt->bindValue(':name', $player, SQLITE3_TEXT);
        $stmt->bindValue(':time', time(), SQLITE3_INTEGER);
        $stmt->execute();

        $hbSql = $db->prepare("INSERT OR REPLACE INTO online_player_hb (id, last_seen) VALUES (1, :time)");
        $hbSql->bindValue(':time', time(), SQLITE3_INTEGER);
        $hbSql->execute();

        $db->exec("COMMIT");
    } catch (Exception $e) {
        $db->exec("ROLLBACK");
        echo json_encode(['success' => false, 'error' => $e->getMessage()]);
        exit;
    }

    // 验证
    $stmt = $db->query("SELECT * FROM online_players");
    $rows = [];
    while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
        $rows[] = $row;
    }

    echo json_encode([
        'success' => true,
        'message' => "模拟推送玩家 $player 成功",
        'online_players' => $rows,
        'timestamp' => time()
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($action === 'check_token') {
    // 检查指定玩家的token是否存在
    $player = $_GET['player'] ?? 'yupaishidifu';
    $stmt = $db->prepare("SELECT * FROM weblogin_tokens WHERE player_name = :player ORDER BY created_at DESC LIMIT 1");
    $stmt->bindValue(':player', $player, SQLITE3_TEXT);
    $result = $stmt->execute();
    $row = $result->fetchArray(SQLITE3_ASSOC);

    echo json_encode([
        'player' => $player,
        'token_found' => $row !== false,
        'token_data' => $row,
        'timestamp' => time()
    ], JSON_UNESCAPED_UNICODE);
    exit;
}

if ($action === 'validate') {
    // 模拟validateWebAccess调用
    $player = $_GET['player'] ?? 'yupaishidifu';
    $token = $_GET['token'] ?? '';

    if (!$token) {
        // 查找最新token
        $stmt = $db->prepare("SELECT web_token FROM weblogin_tokens WHERE player_name = :player ORDER BY created_at DESC LIMIT 1");
        $stmt->bindValue(':player', $player, SQLITE3_TEXT);
        $result = $stmt->execute();
        $row = $result->fetchArray(SQLITE3_ASSOC);
        $token = $row ? $row['web_token'] : '';
    }

    $result = validateWebAccess($token, 'view');
    echo json_encode($result, JSON_UNESCAPED_UNICODE);
    exit;
}

echo json_encode([
    'error' => '未知操作',
    'usage' => [
        'test_sync.php?action=simulate_push&player=yupaishidifu',
        'test_sync.php?action=check_token&player=yupaishidifu',
        'test_sync.php?action=validate&player=yupaishidifu'
    ]
], JSON_UNESCAPED_UNICODE);
