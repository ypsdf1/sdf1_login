<?php
/**
 * 第57批续17：一次性存量邮箱对账脚本
 *
 * 背景：邮箱权威权已交还 Java。新绑定走 PHP 投递队列 → Java 拉取写权威 db →
 *       syncUserRegistrations 收敛 PHP 镜像。但存量 PHP users.email 里已绑定、
 *       而 Java 权威 db 尚未对齐的邮箱，需要一次性对账兜底。
 *
 * 作用：遍历 users 表有 email 的记录，逐条 UPSERT 进 email_bind_return 队列
 *       （幂等：先作废全部旧待处理行，再逐条重新投递），Java 端 pullEmailBindings
 *       下一轮拉取即对齐。
 *
 * 安全：
 *  - SECRET_KEY 认证（与 sync.php 同款）
 *  - 幂等可重跑；只读 users.email（不写 users 表），Java 才是权威写
 *
 * 用法：curl ".../api/migrate_email_bindings.php?secret=<SECRET_KEY>"
 * 返回：{"success":true,"count":<N>,"players":[...]}
 */

ob_start();

// core.php 在 Web 根目录（与 sync.php 同款引入）
$coreFile = __DIR__ . '/../core.php';
if (!file_exists($coreFile)) {
    while (ob_get_level() > 0) ob_end_clean();
    header('Content-Type: application/json; charset=utf-8');
    http_response_code(500);
    echo json_encode(['success' => false, 'message' => 'core.php not found: ' . $coreFile], JSON_UNESCAPED_UNICODE);
    exit;
}
require_once $coreFile;
while (ob_get_level() > 0) ob_end_clean();
ob_start();

$secret = getParam('secret');
if (!$secret || $secret !== SECRET_KEY) {
    error('密钥验证失败', 403);
}

$db = getDB();

// 兜底建表（与 pullEmailBindings 同款，防竞态）
$db->exec("CREATE TABLE IF NOT EXISTS email_bind_return (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_name TEXT NOT NULL,
    email TEXT NOT NULL,
    source TEXT DEFAULT 'migrate',
    pending INTEGER DEFAULT 1,
    created_at INTEGER NOT NULL,
    processed_at INTEGER DEFAULT 0
)");

$now = time();

// 幂等：先作废全部旧待处理行（避免重跑堆积），再逐条重新投递
$db->exec("UPDATE email_bind_return SET pending = 0 WHERE pending = 1");

// 读存量 users.email（只读，不写 users 表）
$stmt = $db->query("SELECT player_name, email FROM users WHERE email IS NOT NULL AND email != ''");

$ins = $db->prepare("INSERT INTO email_bind_return (player_name, email, source, pending, created_at, processed_at)
                     VALUES (:p, :e, 'migrate', 1, :t, 0)");

$players = array();
$count = 0;
while ($row = $stmt->fetchArray(SQLITE3_ASSOC)) {
    $pname = (string)$row['player_name'];
    $email = (string)$row['email'];
    if ($pname === '' || $email === '') continue;
    $ins->reset();
    $ins->bindValue(':p', $pname, SQLITE3_TEXT);
    $ins->bindValue(':e', $email, SQLITE3_TEXT);
    $ins->bindValue(':t', $now, SQLITE3_INTEGER);
    $ins->execute();
    $players[] = $pname;
    $count++;
}

debugLog('[migrate_email_bindings] 存量邮箱对账已投递回推队列', [
    'count' => $count,
]);

success([
    'count' => $count,
    'players' => $players,
], '存量邮箱已投递回推队列，Java 下一轮拉取即对齐权威 db');
