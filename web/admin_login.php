<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - 管理后台登录</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: #0d1117; color: #e6edf3;
            font-family: 'Segoe UI', system-ui, sans-serif;
            display: flex; justify-content: center; align-items: center;
            min-height: 100vh;
        }
        .login-box {
            background: #161b22; border: 1px solid #30363d; border-radius: 12px;
            padding: 40px; width: 380px; max-width: 90%;
        }
        .login-box h1 { text-align: center; color: #58a6ff; margin-bottom: 8px; font-size: 24px; }
        .login-box .sub { text-align: center; color: #8b949e; font-size: 13px; margin-bottom: 28px; }
        .field { margin-bottom: 16px; }
        .field label { display: block; font-size: 13px; color: #8b949e; margin-bottom: 6px; }
        .field input {
            width: 100%; padding: 10px 14px; background: #0d1117; border: 1px solid #30363d;
            border-radius: 6px; color: #e6edf3; font-size: 14px; outline: none; transition: border-color 0.2s;
        }
        .field input:focus { border-color: #58a6ff; }
        .login-btn {
            width: 100%; padding: 12px; background: #58a6ff; color: #fff; border: none;
            border-radius: 6px; font-size: 15px; font-weight: 600; cursor: pointer;
            transition: background 0.2s; margin-top: 8px;
        }
        .login-btn:hover { background: #79c0ff; }
        .login-btn:disabled { background: #30363d; cursor: not-allowed; }
        .error { color: #f85149; font-size: 13px; text-align: center; margin-top: 12px; display: none; }
        .links { text-align: center; margin-top: 20px; }
        .links a { color: #58a6ff; text-decoration: none; font-size: 13px; }
        .links a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="login-box">
        <h1>🔐 管理后台</h1>
        <div class="sub">SDF1 插件管理系统</div>
        <div class="field">
            <label>管理密码</label>
            <input type="password" id="password" placeholder="请输入管理密码" autofocus>
        </div>
        <button class="login-btn" id="loginBtn" onclick="doLogin()">登 录</button>
        <div class="error" id="errorMsg"></div>
        <div class="links"><a href="player.php">← 返回玩家商城</a></div>
    </div>

    <script>
    document.getElementById('password').addEventListener('keydown', e => {
        if (e.key === 'Enter') doLogin();
    });

    async function doLogin() {
        const pw = document.getElementById('password').value;
        if (!pw) return;
        document.getElementById('loginBtn').disabled = true;
        document.getElementById('loginBtn').textContent = '登录中...';

        try {
            const res = await fetch('api/admin.php?action=login', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({password: pw})
            });
            const data = await res.json();
            if (data.success) {
                // 设置session cookie后跳转
                window.location.href = 'admin.php';
            } else {
                document.getElementById('errorMsg').textContent = data.message;
                document.getElementById('errorMsg').style.display = 'block';
            }
        } catch (e) {
            document.getElementById('errorMsg').textContent = '网络错误';
            document.getElementById('errorMsg').style.display = 'block';
        }
        document.getElementById('loginBtn').disabled = false;
        document.getElementById('loginBtn').textContent = '登 录';
    }
    </script>
</body>
</html>
