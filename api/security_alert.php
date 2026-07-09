<?php
/**
 * 异地登录检测回调（Java 插件在玩家加入服务器时异步调用）
 *
 * 调用方式：GET api/security_alert.php
 *   ?action=login_location_alert&secret=...&name=<玩家>&ip=<登录IP>
 *
 * 职责：
 *  1. 用 ip9.com.cn 查询本次登录 IP 归属
 *  2. 与上次登录归属比较，不一致且非首次 → 向绑定邮箱发送提醒邮件
 *  3. 邮件内含「冻结账号」与「修改密码」两个链接
 *  4. 更新 users.last_login_location / last_login_ip
 */
require_once __DIR__ . '/../core.php';

header('Content-Type: application/json; charset=utf-8');

$action = $_GET['action'] ?? '';
if ($action !== 'login_location_alert') {
    echo json_encode(['success' => false, 'message' => '未知操作']);
    exit;
}
$secret = $_GET['secret'] ?? '';
if (!$secret || $secret !== SECRET_KEY) {
    echo json_encode(['success' => false, 'message' => '密钥错误']);
    exit;
}
$name = $_GET['name'] ?? '';
$ip   = $_GET['ip'] ?? '';
if (!$name || !$ip) {
    echo json_encode(['success' => false, 'message' => '参数缺失']);
    exit;
}

$db = getDB();

// 查询账号信息
$stmt = $db->prepare(
    "SELECT email, last_login_location, last_login_ip, frozen
     FROM users WHERE player_name = :name");
$stmt->bindValue(':name', $name, SQLITE3_TEXT);
$row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

// 查询本次 IP 归属
$location = queryLoginIpLocation($ip);

if ($location === null) {
    // 归属查询失败：仅更新 IP，不阻断、不提醒
    $upd = $db->prepare(
        "UPDATE users SET last_login_ip = :ip WHERE player_name = :name");
    $upd->bindValue(':ip', $ip, SQLITE3_TEXT);
    $upd->bindValue(':name', $name, SQLITE3_TEXT);
    $upd->execute();
    echo json_encode(['success' => true, 'message' => 'IP归属查询失败，仅更新IP']);
    exit;
}

if (!$row) {
    // 理论上 Java 已 syncUserOnJoin，不会走到这里
    echo json_encode(['success' => true, 'message' => '无用户记录']);
    exit;
}

$lastLoc = $row['last_login_location'] ?? '';
$lastIp  = $row['last_login_ip'] ?? '';
$email   = $row['email'] ?? '';

// 更新归属与 IP
$upd = $db->prepare(
    "UPDATE users SET last_login_ip = :ip,
            last_login_location = :loc WHERE player_name = :name");
$upd->bindValue(':ip', $ip, SQLITE3_TEXT);
$upd->bindValue(':loc', $location, SQLITE3_TEXT);
$upd->bindValue(':name', $name, SQLITE3_TEXT);
$upd->execute();

// 首次登录（无任何历史记录）不提醒
if ($lastLoc === '' && $lastIp === '') {
    echo json_encode(['success' => true, 'message' => '首次登录，已记录']);
    exit;
}

// 归属变化才发送提醒邮件
if ($lastLoc !== $location && !empty($email)) {
    // 生成冻结 token 并持久化，供邮件中的冻结链接使用
    $freezeToken = bin2hex(random_bytes(16));
    $ut = $db->prepare("UPDATE users SET freeze_token = :t WHERE player_name = :name");
    $ut->bindValue(':t', $freezeToken, SQLITE3_TEXT);
    $ut->bindValue(':name', $name, SQLITE3_TEXT);
    $ut->execute();
    sendLocationAlertEmail($name, $email, $ip, $location, $lastLoc, $freezeToken);
}

echo json_encode(['success' => true, 'message' => 'ok']);
