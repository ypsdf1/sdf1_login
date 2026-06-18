<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - Web登录</title>
    <style>
        :root {
            --bg: #0d1117; --card: #161b22; --border: #30363d;
            --text: #e6edf3; --dim: #8b949e; --accent: #58a6ff;
            --green: #3fb950; --red: #f85149; --yellow: #d29922;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: var(--bg); color: var(--text);
            font-family: 'Segoe UI', system-ui, sans-serif;
            min-height: 100vh; display: flex; justify-content: center; align-items: center;
        }
        .login-card {
            background: var(--card); border: 1px solid var(--border);
            border-radius: 12px; padding: 40px; width: 400px; max-width: 90%;
            text-align: center;
        }
        .login-card h1 { color: var(--accent); margin-bottom: 8px; font-size: 24px; }
        .login-card .subtitle { color: var(--dim); margin-bottom: 32px; font-size: 14px; }
        .status-box {
            padding: 20px; border-radius: 8px; margin-bottom: 24px;
            font-size: 14px; line-height: 1.8;
        }
        .status-box.loading { background: rgba(88,166,255,0.1); border: 1px solid var(--accent); }
        .status-box.success { background: rgba(63,185,80,0.1); border: 1px solid var(--green); }
        .status-box.error { background: rgba(248,81,73,0.1); border: 1px solid var(--red); }
        .spinner {
            display: inline-block; width: 20px; height: 20px;
            border: 2px solid var(--dim); border-top-color: var(--accent);
            border-radius: 50%; animation: spin 0.8s linear infinite;
            vertical-align: middle; margin-right: 8px;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        .btn {
            padding: 12px 24px; border: none; border-radius: 6px;
            cursor: pointer; font-size: 14px; font-weight: 600;
            transition: all 0.2s; text-decoration: none; display: inline-block;
        }
        .btn-primary { background: var(--accent); color: #fff; }
        .btn-primary:hover { background: #79c0ff; }
        .btn-dim { background: var(--border); color: var(--dim); }
        .btn-dim:hover { background: #484f58; color: var(--text); }
        .actions { display: flex; gap: 8px; justify-content: center; }
        .player-name { color: var(--green); font-weight: 600; font-size: 16px; }
    </style>
</head>
<body>
    <div class="login-card">
        <h1>SDF1 Web登录</h1>
        <p class="subtitle" id="subtitle">Token验证中...</p>

        <!-- Token验证状态 -->
        <div id="tokenSection">
            <div id="statusBox" class="status-box loading">
                <span class="spinner"></span> 正在验证登录Token...
            </div>
        </div>

        <!-- 密码验证（Token通过后显示） -->
        <div id="passwordSection" style="display:none">
            <div id="pwdStatusBox" class="status-box" style="display:none"></div>
            <div style="text-align:left;margin-bottom:16px">
                <label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">玩家名</label>
                <input type="text" id="playerName" placeholder="玩家名" readonly
                    style="width:100%;padding:10px 14px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;outline:none;box-sizing:border-box;opacity:0.7">
            </div>
            <div style="text-align:left;margin-bottom:20px">
                <label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">游戏内密码</label>
                <input type="password" id="password" placeholder="输入游戏内密码" maxlength="32"
                    style="width:100%;padding:10px 14px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;outline:none;box-sizing:border-box">
                <p style="color:var(--dim);font-size:12px;margin-top:8px;text-align:left">密码由游戏服务器验证，Web端不存储密码</p>
            </div>
            <button class="btn btn-primary" style="width:100%" onclick="doPasswordLogin()">登录到Web端</button>
        </div>

        <div class="actions" id="actions" style="display:none"></div>
    </div>

    <script>
    const TOKEN = new URLSearchParams(location.search).get('token') || '';
    const statusBox = document.getElementById('statusBox');
    const actionsDiv = document.getElementById('actions');
    const tokenSection = document.getElementById('tokenSection');
    const passwordSection = document.getElementById('passwordSection');
    const subtitle = document.getElementById('subtitle');
    const pwdStatusBox = document.getElementById('pwdStatusBox');

    // ===== 安全拦截：无Token则拒绝访问 =====
    if (!TOKEN) {
        document.body.innerHTML = `
            <div style="text-align:center;padding:60px 20px;">
                <h1 style="color:#f85149;margin-bottom:20px">🚫 访问被拒绝</h1>
                <p style="color:var(--dim);font-size:16px;line-height:1.8">
                    Web登录必须通过游戏内Token验证。<br>
                    请在游戏中执行 <b style="color:var(--accent)">/sdf1_login weblogin</b> 或 <b>/web</b><br>
                    然后点击上方链接登录Web端。
                </p>
            </div>
        `;
        console.error('[登录页面] 无Token访问被拒绝');
        throw new Error('NoToken');
    }

    // ===== 有Token → 验证Token =====
    tokenSection.style.display = 'block';
    doTokenLogin();

    // ===== Token验证 =====
    async function doTokenLogin() {
        try {
            const apiUrl = './api/sync.php?action=validate_weblogin_token';
            const res = await fetch(apiUrl, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({web_token: TOKEN})
            });
            const text = await res.text();
            
            let data;
            try {
                data = JSON.parse(text);
            } catch (parseErr) {
                statusBox.className = 'status-box error';
                statusBox.innerHTML = '❌ 服务器响应异常<br><br><code style="font-size:11px;word-break:break-all;color:var(--dim)">' + text.substring(0, 300) + '</code>';
                actionsDiv.style.display = 'flex';
                actionsDiv.innerHTML = '<span style="color:var(--dim);font-size:13px">请在游戏中重新执行 <b>/sdf1_login weblogin</b></span>';
                return;
            }

            if (data.success && data.data) {
                const player = data.data.player;
                localStorage.setItem('sdf1_player', player);
                localStorage.setItem('sdf1_token', TOKEN);
                localStorage.setItem('sdf1_login_time', Date.now());

                // Token验证成功 → 显示密码输入框
                statusBox.className = 'status-box success';
                statusBox.innerHTML = '✅ Token验证成功!<br><br>欢迎, <span class="player-name">' + player + '</span>';
                
                // 延迟1.5秒后切换到密码输入
                setTimeout(() => {
                    tokenSection.style.display = 'none';
                    passwordSection.style.display = 'block';
                    subtitle.textContent = '验证玩家身份';
                    document.getElementById('playerName').value = player;
                    document.getElementById('password').focus();
                }, 1500);
            } else {
                statusBox.className = 'status-box error';
                statusBox.innerHTML = '❌ 登录失败<br><br>' + (data.message || 'Token无效或已过期');
                actionsDiv.style.display = 'flex';
                actionsDiv.innerHTML = '<span style="color:var(--dim);font-size:13px">请在游戏中重新执行 <b>/sdf1_login weblogin</b></span>';
            }
        } catch (e) {
            statusBox.className = 'status-box error';
            statusBox.innerHTML = '❌ 连接失败<br><br>' + e.message;
        }
    }

    // ===== 密码登录 =====
    async function doPasswordLogin() {
        const player = document.getElementById('playerName').value.trim();
        const password = document.getElementById('password').value;

        if (!password) { showPwdError('请输入密码'); return; }

        pwdStatusBox.className = 'status-box loading';
        pwdStatusBox.style.display = 'block';
        pwdStatusBox.innerHTML = '<span class="spinner"></span> 正在验证密码...';

        try {
            // 1. 提交登录请求
            const apiUrl = './api/sync.php';
            const reqRes = await fetch(apiUrl + '?action=web_login_request', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: player, password: password})
            });
            const reqData = await reqRes.json();

            if (!reqData.success) {
                showPwdError(reqData.message || '提交失败');
                return;
            }

            const requestId = reqData.data.request_id;

            // 2. 轮询结果
            pwdStatusBox.innerHTML = '<span class="spinner"></span> 等待游戏服务器验证密码...';
            let attempts = 0;
            const maxAttempts = 60; // 最多等60秒

            const pollInterval = setInterval(async () => {
                attempts++;
                if (attempts > maxAttempts) {
                    clearInterval(pollInterval);
                    showPwdError('验证超时，请稍后重试');
                    return;
                }

                try {
                    const pollRes = await fetch(apiUrl + '?action=check_web_login_result&player=' + encodeURIComponent(player) + '&request_id=' + requestId);
                    const pollText = await pollRes.text();
                    const pollData = JSON.parse(pollText);

                    console.log('[poll] status:', pollData.status || pollData.data?.status, 'data:', JSON.stringify(pollData.data).substring(0, 100));

                    if (pollData.success && pollData.data) {
                        const result = pollData.data;

                        if (result.status === 'success') {
                            clearInterval(pollInterval);
                            // 登录成功 → 进入玩家商城
                            pwdStatusBox.className = 'status-box success';
                            pwdStatusBox.innerHTML = '✅ 登录成功!<br><br>欢迎, <span class="player-name">' + player + '</span><br><small style="color:var(--dim)">正在跳转...</small>';

                            // ★ token已存入localStorage，直接跳转player.php（不带token参数，避免URL暴露）
                            setTimeout(() => {
                                window.location.href = 'player.php?login=' + encodeURIComponent(player);
                            }, 500);
                        } else if (result.status === 'failed') {
                            clearInterval(pollInterval);
                            showPwdError(result.message || '密码错误');
                        }
                        // status === 'pending' → 继续轮询
                    } else if (pollData.data && pollData.data.status === 'pending') {
                        // 仍在pending状态，可能是请求过期，继续轮询
                        console.log('[poll] 请求pending或过期，继续等待...');
                    } else {
                        console.warn('[poll] 轮询响应异常:', JSON.stringify(pollData).substring(0, 200));
                    }
                } catch (pollErr) {
                    console.error('[poll] 轮询异常:', pollErr);
                }
            }, 1000);

        } catch (e) {
            showPwdError('连接失败: ' + e.message);
        }
    }

    function showPwdError(msg) {
        pwdStatusBox.className = 'status-box error';
        pwdStatusBox.style.display = 'block';
        pwdStatusBox.innerHTML = '❌ ' + msg;
    }

    // 回车提交
    document.getElementById('password').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') doPasswordLogin();
    });
    </script>
</body>
</html>
