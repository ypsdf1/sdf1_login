<?php
/**
 * 密码重置页面
 * 玩家通过邮件链接访问此页面重置密码
 */
header('Cache-Control: no-cache, no-store, must-revalidate');
header('Pragma: no-cache');
header('Expires: 0');

require_once __DIR__ . '/core.php';
require_once __DIR__ . '/api/sync.php';

// 获取token参数
$token = isset($_GET['token']) ? $_GET['token'] : '';

if (!$token) {
    die('无效的重置链接');
}

// 验证token
$db = getDB();
$stmt = $db->prepare("SELECT * FROM password_reset_tokens WHERE token = :token");
$stmt->bindValue(':token', $token, SQLITE3_TEXT);
$result = $stmt->execute();
$row = $result->fetchArray(SQLITE3_ASSOC);

if (!$row) {
    die('重置链接无效或已过期');
}

if (time() > $row['expire_at']) {
    die('重置链接已过期，请重新申请');
}

$player = $row['player_name'];

// 处理表单提交
$message = '';
$error = '';
$success = false;

if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $newPassword = isset($_POST['password']) ? $_POST['password'] : '';
    $confirmPassword = isset($_POST['confirm_password']) ? $_POST['confirm_password'] : '';
    
    if (!$newPassword || strlen($newPassword) < 6) {
        $error = '密码长度至少6位';
    } elseif ($newPassword !== $confirmPassword) {
        $error = '两次输入的密码不一致';
    } else {
        // 生成新的密码凭证
        $saltBytes = random_bytes(16);
        $salt = base64_encode($saltBytes);
        $passwordHash = base64_encode(hash('sha256', $saltBytes . $newPassword, true));
        
        // 更新密码凭证
        storeWebLoginCredentials($player, $passwordHash, $salt);
        
        // 删除重置token
        $stmt = $db->prepare("DELETE FROM password_reset_tokens WHERE token = :token");
        $stmt->bindValue(':token', $token, SQLITE3_TEXT);
        $stmt->execute();
        
        $success = true;
        $message = '密码重置成功！请在游戏中使用新密码登录';
    }
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>重置密码 - Sdf1 Minecraft</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            min-height: 100vh;
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 20px;
        }
        .container {
            background: white;
            border-radius: 12px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.2);
            max-width: 420px;
            width: 100%;
            padding: 40px;
        }
        h1 {
            color: #333;
            font-size: 28px;
            text-align: center;
            margin-bottom: 8px;
        }
        .subtitle {
            color: #666;
            font-size: 14px;
            text-align: center;
            margin-bottom: 32px;
        }
        .player-info {
            background: #f5f5f5;
            border-radius: 8px;
            padding: 16px;
            text-align: center;
            margin-bottom: 24px;
        }
        .player-info strong {
            color: #667eea;
            font-size: 18px;
        }
        .form-group {
            margin-bottom: 20px;
        }
        label {
            display: block;
            color: #333;
            font-size: 14px;
            font-weight: 600;
            margin-bottom: 8px;
        }
        input[type="password"] {
            width: 100%;
            padding: 12px 16px;
            border: 2px solid #e0e0e0;
            border-radius: 8px;
            font-size: 14px;
            transition: border-color 0.3s;
        }
        input[type="password"]:focus {
            outline: none;
            border-color: #667eea;
        }
        .btn {
            width: 100%;
            padding: 14px;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
            color: white;
            border: none;
            border-radius: 8px;
            font-size: 16px;
            font-weight: 600;
            cursor: pointer;
            transition: transform 0.2s, box-shadow 0.2s;
        }
        .btn:hover {
            transform: translateY(-2px);
            box-shadow: 0 6px 20px rgba(102, 126, 234, 0.4);
        }
        .btn:active {
            transform: translateY(0);
        }
        .alert {
            padding: 12px 16px;
            border-radius: 8px;
            margin-bottom: 20px;
            font-size: 14px;
        }
        .alert-error {
            background: #fee;
            color: #c33;
            border: 1px solid #fcc;
        }
        .alert-success {
            background: #efe;
            color: #363;
            border: 1px solid #cfc;
        }
        .success-container {
            text-align: center;
        }
        .success-icon {
            font-size: 64px;
            margin-bottom: 20px;
        }
        .success-text {
            color: #363;
            font-size: 18px;
            margin-bottom: 24px;
        }
        .info-text {
            color: #666;
            font-size: 13px;
            line-height: 1.6;
            margin-top: 24px;
            padding-top: 24px;
            border-top: 1px solid #e0e0e0;
        }
    </style>
</head>
<body>
    <div class="container">
        <?php if ($success): ?>
            <div class="success-container">
                <div class="success-icon">✅</div>
                <h1>重置成功</h1>
                <p class="success-text"><?php echo htmlspecialchars($message); ?></p>
                <div class="info-text">
                    <strong>提示：</strong><br>
                    请在Minecraft游戏中使用新密码重新登录Web端
                </div>
            </div>
        <?php else: ?>
            <h1>重置密码</h1>
            <p class="subtitle">为您的账号设置新密码</p>
            
            <div class="player-info">
                <div style="color: #666; font-size: 13px; margin-bottom: 4px;">玩家</div>
                <strong><?php echo htmlspecialchars($player); ?></strong>
            </div>
            
            <?php if ($error): ?>
                <div class="alert alert-error">
                    <?php echo htmlspecialchars($error); ?>
                </div>
            <?php endif; ?>
            
            <form method="POST" action="">
                <div class="form-group">
                    <label for="password">新密码</label>
                    <input type="password" id="password" name="password" required minlength="6" placeholder="至少6位字符">
                </div>
                
                <div class="form-group">
                    <label for="confirm_password">确认密码</label>
                    <input type="password" id="confirm_password" name="confirm_password" required minlength="6" placeholder="再次输入密码">
                </div>
                
                <button type="submit" class="btn">重置密码</button>
            </form>
            
            <div class="info-text">
                <strong>注意：</strong><br>
                • 密码重置后，请使用新密码登录Web端<br>
                • 此链接30分钟内有效，使用后自动失效<br>
                • 如果这不是您的操作，请忽略此邮件
            </div>
        <?php endif; ?>
    </div>
    
    <script>
        // 密码确认
        const form = document.querySelector('form');
        if (form) {
            form.addEventListener('submit', function(e) {
                const password = document.getElementById('password').value;
                const confirmPassword = document.getElementById('confirm_password').value;
                
                if (password !== confirmPassword) {
                    e.preventDefault();
                    alert('两次输入的密码不一致');
                }
            });
        }
    </script>
</body>
</html>
