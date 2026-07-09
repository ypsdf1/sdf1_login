<?php
/**
 * 账号冻结页面（玩家从「异地登录提醒」邮件点击进入）
 * 验证 freeze_token → 标记账号冻结(frozen=1) → 写入 web_admin_changes(freeze)
 * 由 Java 插件 pollAdminChanges 拉取后执行 Bukkit 原生封禁。
 * 解冻需在 reset_password.php 修改密码后由 PHP 写 web_admin_changes(unfreeze)。
 */
require_once __DIR__ . '/core.php';

$token   = $_GET['token'] ?? '';
$error   = '';
$success = false;
$player  = '';
$resetLink = '';

if (!$token) {
    die('无效链接');
}

$db = getDB();
$stmt = $db->prepare(
    "SELECT player_name, frozen FROM users WHERE freeze_token = :t");
$stmt->bindValue(':t', $token, SQLITE3_TEXT);
$row = $stmt->execute()->fetchArray(SQLITE3_ASSOC);

if (!$row) {
    die('链接无效或已使用');
}

$player = $row['player_name'];

if ($row['frozen'] == 1) {
    // 已冻结，直接展示已冻结页
    $success = true;
} else {
    // 执行冻结
    $db->exec("UPDATE users SET frozen = 1, freeze_token = '' WHERE player_name = "
        . SQLite3::escapeString($player));
    // 通知 Java 插件封禁该玩家（Bukkit 原生 ban）
    $now = time();
    $stmt2 = $db->prepare(
        "INSERT INTO web_admin_changes
         (change_type, target_id, target_name, change_data, created_at)
         VALUES ('freeze', 0, :name, '{}', :now)");
    $stmt2->bindValue(':name', $player, SQLITE3_TEXT);
    $stmt2->bindValue(':now', $now, SQLITE3_INTEGER);
    $stmt2->execute();
    $success = true;
}

// 取一个有效改密 token 构造「去改密」链接
$stmt3 = $db->prepare(
    "SELECT token FROM password_reset_tokens
     WHERE player_name = :name AND expire_at > :now
     ORDER BY expire_at DESC LIMIT 1");
$stmt3->bindValue(':name', $player, SQLITE3_TEXT);
$stmt3->bindValue(':now', time(), SQLITE3_INTEGER);
$r3 = $stmt3->execute()->fetchArray(SQLITE3_ASSOC);
if ($r3) {
    $resetLink = getBaseUrl() . '/reset_password.php?token=' . $r3['token'];
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>账号已冻结 - Sdf1 Minecraft</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: 'Segoe UI', Tahoma, sans-serif;
            background: linear-gradient(135deg, #2c3e50 0%, #4b6584 100%);
            min-height: 100vh; display: flex; align-items: center;
            justify-content: center; padding: 20px; }
        .container { background: white; border-radius: 12px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.3);
            max-width: 440px; width: 100%; padding: 40px; text-align: center; }
        h1 { color: #e0533d; font-size: 26px; margin-bottom: 16px; }
        .player { background: #f5f5f5; border-radius: 8px; padding: 14px;
            margin: 20px 0; font-size: 18px; }
        .player strong { color: #e0533d; }
        .tip { color: #666; font-size: 14px; line-height: 1.7; margin: 12px 0; }
        .btn { display: inline-block; width: 100%; padding: 14px;
            background: linear-gradient(135deg, #e0533d, #c0392b);
            color: white; border: none; border-radius: 8px;
            font-size: 16px; font-weight: 600; text-decoration: none;
            transition: transform 0.2s; }
        .btn:hover { transform: translateY(-2px); }
    </style>
</head>
<body>
    <div class="container">
        <h1>✅ 账号已冻结</h1>
        <p class="tip">为防范账号被盗用，玩家 <b><?php echo htmlspecialchars($player); ?></b> 的账号已被临时冻结。</p>
        <div class="player">玩家：<strong><?php echo htmlspecialchars($player); ?></strong></div>
        <p class="tip">冻结期间将无法进入游戏。如需解冻，请<b>修改密码</b>，系统将自动解除冻结。</p>
        <?php if ($resetLink): ?>
            <a class="btn" href="<?php echo htmlspecialchars($resetLink); ?>">前往修改密码并解冻</a>
        <?php else: ?>
            <p class="tip">未找到有效的改密链接，请返回异地登录提醒邮件点击「修改密码」。</p>
        <?php endif; ?>
    </div>
</body>
</html>
