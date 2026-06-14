<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - 玩家商城</title>
    <style>
        :root {
            --bg: #0d1117; --card: #161b22; --border: #30363d;
            --text: #e6edf3; --dim: #8b949e; --accent: #58a6ff;
            --green: #3fb950; --red: #f85149; --yellow: #d29922;
            --purple: #bc8cff;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: var(--bg); color: var(--text); font-family: 'Segoe UI', system-ui, sans-serif; min-height: 100vh; }
        .header {
            background: linear-gradient(135deg, #1a1e2e 0%, #0d1117 100%);
            border-bottom: 1px solid var(--border);
            padding: 16px 24px;
            display: flex; justify-content: space-between; align-items: center;
        }
        .header h1 { font-size: 20px; color: var(--accent); }
        .header .status { font-size: 13px; }
        .preview-badge {
            background: var(--yellow); color: #000; padding: 4px 12px;
            border-radius: 20px; font-size: 12px; font-weight: 600;
            animation: pulse 2s infinite;
        }
        @keyframes pulse { 0%,100% { opacity:1; } 50% { opacity:0.6; } }
        .main { display: flex; gap: 0; min-height: calc(100vh - 56px); }
        .sidebar {
            width: 220px; background: var(--card); border-right: 1px solid var(--border);
            padding: 12px 0; flex-shrink: 0;
        }
        .sidebar-item {
            padding: 10px 20px; cursor: pointer; color: var(--dim);
            transition: all 0.2s; font-size: 14px; display: flex; align-items: center; gap: 8px;
        }
        .sidebar-item:hover { background: rgba(88,166,255,0.1); color: var(--text); }
        .sidebar-item.active { color: var(--accent); border-right: 3px solid var(--accent); background: rgba(88,166,255,0.05); }
        .content { flex: 1; padding: 24px; overflow-y: auto; max-height: calc(100vh - 56px); }
        .card {
            background: var(--card); border: 1px solid var(--border); border-radius: 8px;
            padding: 20px; margin-bottom: 16px;
        }
        .card h2 { font-size: 16px; margin-bottom: 12px; color: var(--accent); }
        .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; }
        .item-card {
            background: rgba(88,166,255,0.05); border: 1px solid var(--border);
            border-radius: 8px; padding: 16px; cursor: pointer;
            transition: all 0.2s; position: relative;
        }
        .item-card:hover { border-color: var(--accent); transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
        .item-card .icon { font-size: 32px; margin-bottom: 8px; }
        .item-card .name { font-size: 14px; font-weight: 600; margin-bottom: 4px; }
        .item-card .price { color: var(--green); font-size: 13px; }
        .item-card .stock { color: var(--dim); font-size: 12px; }
        .item-card .stock.out { color: var(--red); }
        .stat-row { display: flex; gap: 16px; margin-bottom: 16px; }
        .stat-box {
            flex: 1; background: var(--card); border: 1px solid var(--border);
            border-radius: 8px; padding: 16px; text-align: center;
        }
        .stat-box .value { font-size: 28px; font-weight: 700; color: var(--accent); }
        .stat-box .label { font-size: 12px; color: var(--dim); margin-top: 4px; }
        .cdk-input {
            display: flex; gap: 8px; margin-top: 12px;
        }
        .cdk-input input {
            flex: 1; padding: 10px 14px; background: var(--bg); border: 1px solid var(--border);
            border-radius: 6px; color: var(--text); font-size: 14px; outline: none;
        }
        .cdk-input input:focus { border-color: var(--accent); }
        .btn {
            padding: 10px 20px; border: none; border-radius: 6px; cursor: pointer;
            font-size: 14px; font-weight: 600; transition: all 0.2s;
        }
        .btn-primary { background: var(--accent); color: #fff; }
        .btn-primary:hover { background: #79c0ff; }
        .btn-green { background: var(--green); color: #fff; }
        .btn-green:hover { background: #56d364; }
        .btn-red { background: var(--red); color: #fff; }
        .btn-orange { background: var(--yellow); color: #000; }
        .table { width: 100%; border-collapse: collapse; font-size: 13px; }
        .table th { text-align: left; padding: 10px 12px; color: var(--dim); border-bottom: 1px solid var(--border); }
        .table td { padding: 10px 12px; border-bottom: 1px solid var(--border); }
        .table tr:hover td { background: rgba(88,166,255,0.05); }
        .empty { text-align: center; color: var(--dim); padding: 40px; }
        .modal-overlay {
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.6); display: flex; justify-content: center;
            align-items: center; z-index: 100; display: none;
        }
        .modal {
            background: var(--card); border: 1px solid var(--border); border-radius: 12px;
            padding: 24px; width: 400px; max-width: 90%;
        }
        .modal h3 { margin-bottom: 16px; }
        .modal .row { margin-bottom: 12px; }
        .modal label { display: block; font-size: 13px; color: var(--dim); margin-bottom: 4px; }
        .modal input, .modal select {
            width: 100%; padding: 8px 12px; background: var(--bg); border: 1px solid var(--border);
            border-radius: 6px; color: var(--text); font-size: 14px;
        }
        .modal .actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
        .toast {
            position: fixed; top: 20px; right: 20px; padding: 12px 20px;
            border-radius: 8px; font-size: 14px; z-index: 200;
            animation: slideIn 0.3s ease;
        }
        .toast.success { background: var(--green); color: #fff; }
        .toast.error { background: var(--red); color: #fff; }
        @keyframes slideIn { from { transform: translateX(100%); opacity: 0; } to { transform: translateX(0); opacity: 1; } }
        @media (max-width: 768px) {
            .sidebar { display: none; }
            .grid { grid-template-columns: 1fr; }
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>SDF1 玩家商城</h1>
        <div class="status">
            <span id="previewBadge" class="preview-badge" style="display:none">预览模式</span>
            <span id="playerInfo" style="margin-left:12px;color:var(--dim)"></span>
        </div>
    </div>

    <div class="main">
        <div class="sidebar">
            <div class="sidebar-item active" data-page="shop" onclick="switchPage('shop')">🛒 商城</div>
            <div class="sidebar-item" data-page="cdk" onclick="switchPage('cdk')">🎁 CDK兑换</div>
            <div class="sidebar-item" data-page="balance" onclick="switchPage('balance')">💰 余额查询</div>
            <div class="sidebar-item" data-page="account" onclick="switchPage('account')">👤 账号信息</div>
        </div>

        <div class="content" id="content">
            <!-- 动态内容 -->
        </div>
    </div>

    <div class="modal-overlay" id="modalOverlay" onclick="closeModal()">
        <div class="modal" onclick="event.stopPropagation()">
            <h3 id="modalTitle">确认</h3>
            <div id="modalBody"></div>
            <div class="actions">
                <button class="btn" onclick="closeModal()">取消</button>
                <button class="btn btn-primary" id="modalConfirm" onclick="confirmModal()">确认</button>
            </div>
        </div>
    </div>

    <script>
    const API = 'api/';
    let TOKEN = new URLSearchParams(location.search).get('token') || localStorage.getItem('sdf1_token') || '';
    let IS_PREVIEW = !TOKEN;
    let currentPlayer = localStorage.getItem('sdf1_player') || new URLSearchParams(location.search).get('login') || '';
    let currentPage = 'shop';
    let AUTHENTICATED = false;
    let NEED_PASSWORD = false;

    // 初始化
    (function() {
        const urlToken = new URLSearchParams(location.search).get('token');
        if (urlToken) {
            localStorage.setItem('sdf1_token', urlToken);
            TOKEN = urlToken;
            IS_PREVIEW = false;
            const cleanUrl = location.pathname + location.search;
            window.history.replaceState({}, '', cleanUrl);
        }

        // ★ 安全机制：没有token → 进入游客模式（预览）
        if (!TOKEN) {
            IS_PREVIEW = true;
            currentPlayer = '';
            AUTHENTICATED = false;
            document.getElementById('playerInfo').textContent = '游客模式';
            document.getElementById('previewBadge').style.display = 'inline';
            showLoginPrompt();
            return;
        }

        // 有token → 验证token
        document.getElementById('playerInfo').textContent = '验证中...';
        checkAccess('view');
    })();

    // ★ 安全验证入口
    async function checkAccess(action, callback) {
        if (IS_PREVIEW || !TOKEN) {
            document.getElementById('playerInfo').textContent = '游客模式';
            if (callback) callback({ok: false, mode: 'preview'});
            return;
        }
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'web_access_check');
            url.searchParams.set('web_token', TOKEN);
            url.searchParams.set('access_action', action);
            const res = await fetch(url);
            const data = await res.json();

            if (data.success) {
                AUTHENTICATED = true;
                NEED_PASSWORD = false;
                currentPlayer = data.data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);
                document.getElementById('playerInfo').textContent = '已登录: ' + currentPlayer;
                if (callback) callback({ok: true, mode: data.data.mode});
            } else if (data.need_password) {
                NEED_PASSWORD = true;
                currentPlayer = data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);
                document.getElementById('playerInfo').textContent = '等待登录';
                if (callback) callback({ok: false, need_password: true, player: currentPlayer});
                showPasswordModal(data.message || '请输入游戏登录密码');
            } else if (data.need_game_login) {
                AUTHENTICATED = false;
                currentPlayer = data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);
                document.getElementById('playerInfo').textContent = '请先在游戏内登录';
                if (callback) callback({ok: false, need_game_login: true, player: currentPlayer});
                showGameLoginMessage(data.message || '请先在游戏内登录');
            } else if (data.need_register) {
                AUTHENTICATED = false;
                currentPlayer = data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);
                document.getElementById('playerInfo').textContent = '请先在游戏内注册';
                if (callback) callback({ok: false, need_register: true, player: currentPlayer});
                showRegisterMessage(data.message || '请先在游戏内注册账号');
            } else {
                // Token无效 → 清除缓存，进入游客模式
                AUTHENTICATED = false;
                TOKEN = '';
                IS_PREVIEW = true;
                localStorage.removeItem('sdf1_token');
                localStorage.removeItem('sdf1_player');
                currentPlayer = '';
                document.getElementById('playerInfo').textContent = '游客模式';
                document.getElementById('previewBadge').style.display = 'inline';
                showLoginPrompt();
                if (callback) callback({ok: false, message: data.message});
            }
        } catch (e) {
            AUTHENTICATED = false;
            TOKEN = '';
            IS_PREVIEW = true;
            localStorage.removeItem('sdf1_token');
            localStorage.removeItem('sdf1_player');
            currentPlayer = '';
            document.getElementById('playerInfo').textContent = '游客模式';
            document.getElementById('previewBadge').style.display = 'inline';
            showLoginPrompt();
            if (callback) callback({ok: false, message: e.message});
        }
    }

    function showGameLoginMessage(message) {
        document.getElementById('modalTitle').textContent = '需要游戏登录';
        document.getElementById('modalBody').innerHTML = `
            <div style="text-align:center;padding:20px">
                <div style="font-size:48px;margin-bottom:16px">🎮</div>
                <p style="color:var(--dim);font-size:14px;margin-bottom:20px">${message}</p>
                <div style="background:rgba(59,130,246,0.1);border:1px solid var(--accent);border-radius:8px;padding:16px;margin-bottom:20px">
                    <p style="color:var(--text);font-size:13px;margin:0">请在Minecraft游戏中使用 <code style="background:rgba(255,255,255,0.1);padding:2px 6px;border-radius:3px">/sdf1_login</code> 命令登录后再访问Web端</p>
                </div>
                <button class="btn" onclick="location.reload()" style="margin-top:8px">刷新页面</button>
            </div>`;
        document.getElementById('modalOverlay').style.display = 'flex';
    }

    function showRegisterMessage(message) {
        document.getElementById('modalTitle').textContent = '注册新账号';
        document.getElementById('modalBody').innerHTML = `
            <p style="color:var(--dim);font-size:13px;margin-bottom:12px">玩家 <b style="color:var(--accent)">${currentPlayer}</b> 尚未注册，请设置密码完成注册。</p>
            <div class="row"><label>设置密码</label><input type="password" id="regPassword" placeholder="至少6位" autofocus></div>
            <div class="row"><label>确认密码</label><input type="password" id="regPassword2" placeholder="再次输入密码"></div>
            <div class="row"><label>绑定邮箱（可选）</label><input type="email" id="regEmail" placeholder="用于找回密码"></div>
            <div id="regError" style="color:var(--red);font-size:12px;margin-top:4px"></div>
            <div style="background:rgba(245,158,11,0.1);border:1px solid #f59e0b;border-radius:8px;padding:12px;margin-top:8px">
                <p style="color:var(--text);font-size:12px;margin:0">💡 注册后需等待游戏服务器同步创建账号（通常几秒内完成）</p>
            </div>`;
        document.getElementById('modalConfirm').textContent = '提交注册';
        document.getElementById('modalConfirm').onclick = () => doWebRegister();
        document.getElementById('regPassword').addEventListener('keydown', e => { if (e.key === 'Enter') document.getElementById('regPassword2').focus(); });
        document.getElementById('regPassword2').addEventListener('keydown', e => { if (e.key === 'Enter') doWebRegister(); });
        document.getElementById('modalOverlay').style.display = 'flex';
        setTimeout(() => document.getElementById('regPassword').focus(), 100);
    }

    async function doWebRegister() {
        const pwd = document.getElementById('regPassword').value;
        const pwd2 = document.getElementById('regPassword2').value;
        const email = document.getElementById('regEmail').value.trim();
        const errEl = document.getElementById('regError');

        if (!pwd) { errEl.textContent = '请输入密码'; return; }
        if (pwd.length < 6) { errEl.textContent = '密码至少6位'; return; }
        if (pwd !== pwd2) { errEl.textContent = '两次密码不一致'; return; }

        errEl.textContent = '注册中...';
        document.getElementById('modalConfirm').disabled = true;

        try {
            const url = new URL(API + 'register.php', location.href);
            url.searchParams.set('action', 'web_register');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({web_token: TOKEN, password: pwd, email: email})
            });
            const data = await res.json();

            if (data.success) {
                document.getElementById('modalConfirm').disabled = false;
                closeModal();
                document.getElementById('modalTitle').textContent = '注册请求已提交';
                document.getElementById('modalBody').innerHTML = `
                    <div style="text-align:center;padding:20px">
                        <div style="font-size:48px;margin-bottom:16px">✅</div>
                        <p style="color:var(--dim);font-size:14px;margin-bottom:16px">注册请求已提交到游戏服务器</p>
                        <div style="background:rgba(59,130,246,0.1);border:1px solid var(--accent);border-radius:8px;padding:16px;margin-bottom:16px">
                            <p style="color:var(--text);font-size:13px;margin:0">请在游戏内输入 <code style="background:rgba(255,255,255,0.1);padding:2px 6px;border-radius:3px">/sdf1_login</code> 完成登录</p>
                        </div>
                        <p style="color:var(--dim);font-size:12px">系统将自动检测注册完成，届时页面将自动刷新</p>
                        <button class="btn btn-primary" onclick="location.reload()" style="margin-top:12px">刷新页面</button>
                    </div>`;
                document.getElementById('modalOverlay').style.display = 'flex';
                toast('注册请求已提交', 'success');
            } else {
                errEl.textContent = data.message || '注册失败';
                document.getElementById('modalConfirm').disabled = false;
            }
        } catch (e) {
            errEl.textContent = '连接失败: ' + e.message;
            document.getElementById('modalConfirm').disabled = false;
        }
    }

    function showPasswordModal(message) {
        document.getElementById('modalTitle').textContent = '安全验证';
        document.getElementById('modalBody').innerHTML = `
            <p style="color:var(--dim);font-size:13px;margin-bottom:12px">${message}</p>
            <div class="row"><label>玩家名</label><input type="text" id="authPlayer" value="${currentPlayer}" readonly style="background:#21262d;cursor:not-allowed"></div>
            <div style="display:flex;gap:8px;margin-bottom:12px">
                <button class="btn btn-blue" id="tabPassword" onclick="switchAuthTab('password')" style="flex:1">游戏密码</button>
                <button class="btn" id="tabEmail" onclick="switchAuthTab('email')" style="flex:1">邮箱验证码</button>
            </div>
            <div id="authPasswordTab">
                <div class="row"><label>游戏登录密码</label><input type="password" id="authPassword" placeholder="输入游戏内密码" autofocus></div>
                <div style="text-align:right;margin-top:4px"><a href="javascript:void(0)" onclick="showResetPasswordModal()" style="color:var(--accent);font-size:12px">忘记密码？</a></div>
            </div>
            <div id="authEmailTab" style="display:none">
                <div class="row"><label>绑定邮箱</label><input type="email" id="authEmail" placeholder="输入注册时绑定的邮箱"></div>
                <div class="row" style="display:flex;gap:8px">
                    <div style="flex:1"><label>验证码</label><input type="text" id="authCode" placeholder="6位验证码" maxlength="6"></div>
                    <div style="display:flex;align-items:flex-end"><button class="btn btn-blue" id="sendCodeBtn" onclick="sendEmailCode()" style="white-space:nowrap">发送验证码</button></div>
                </div>
            </div>
            <div id="authError" style="color:var(--red);font-size:12px;margin-top:4px"></div>`;
        document.getElementById('modalConfirm').onclick = () => doAuth();
        document.getElementById('authPassword').addEventListener('keydown', e => { if (e.key === 'Enter') doAuth(); });
        document.getElementById('authCode').addEventListener('keydown', e => { if (e.key === 'Enter') doAuth(); });
        document.getElementById('modalOverlay').style.display = 'flex';
        setTimeout(() => document.getElementById('authPassword').focus(), 100);
        switchAuthTab('password');
    }

    let currentAuthTab = 'password';
    function switchAuthTab(tab) {
        currentAuthTab = tab;
        document.getElementById('authPasswordTab').style.display = tab === 'password' ? 'block' : 'none';
        document.getElementById('authEmailTab').style.display = tab === 'email' ? 'block' : 'none';
        document.getElementById('tabPassword').className = tab === 'password' ? 'btn btn-blue' : 'btn';
        document.getElementById('tabEmail').className = tab === 'email' ? 'btn btn-blue' : 'btn';
        document.getElementById('authError').textContent = '';
    }

    async function sendEmailCode() {
        const email = document.getElementById('authEmail').value.trim();
        if (!email) { document.getElementById('authError').textContent = '请输入邮箱'; return; }
        const btn = document.getElementById('sendCodeBtn');
        btn.disabled = true;
        btn.textContent = '发送中...';
        document.getElementById('authError').textContent = '';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'send_email_code');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: currentPlayer, email: email, web_token: TOKEN})
            });
            const data = await res.json();
            if (data.success) {
                document.getElementById('authError').innerHTML = '<span style="color:var(--green)">验证码已发送到邮箱</span>';
                let countdown = 60;
                const timer = setInterval(() => {
                    btn.textContent = countdown + '秒后重发';
                    countdown--;
                    if (countdown <= 0) {
                        clearInterval(timer);
                        btn.disabled = false;
                        btn.textContent = '发送验证码';
                    }
                }, 1000);
            } else {
                document.getElementById('authError').textContent = data.message || '发送失败';
                btn.disabled = false;
                btn.textContent = '发送验证码';
            }
        } catch (e) {
            document.getElementById('authError').textContent = '连接失败: ' + e.message;
            btn.disabled = false;
            btn.textContent = '发送验证码';
        }
    }

    async function doAuth() {
        if (currentAuthTab === 'password') {
            await doPasswordAuth();
        } else {
            await doEmailAuth();
        }
    }

    async function doPasswordAuth() {
        const password = document.getElementById('authPassword').value;
        if (!password) { document.getElementById('authError').textContent = '请输入密码'; return; }
        document.getElementById('authError').textContent = '验证中...';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'verify_web_password');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({web_token: TOKEN, password: password})
            });
            const data = await res.json();
            if (data.success) {
                AUTHENTICATED = true;
                NEED_PASSWORD = false;
                currentPlayer = data.data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);

                // 检查是否需要修改密码（临时密码）
                if (data.data.need_password_change === 1) {
                    document.getElementById('playerInfo').textContent = '已登录: ' + currentPlayer + ' ⚠️ 请使用临时密码登录后立即修改密码';
                    setTimeout(() => {
                        alert('您正在使用临时密码登录。为了您的账号安全，请在登录后立即修改密码！');
                    }, 500);
                } else {
                    document.getElementById('playerInfo').textContent = '已登录: ' + currentPlayer;
                }
                closeModal();
                toast('验证成功', 'success');
                try {
                    const extUrl = new URL(API + 'sync.php', location.href);
                    extUrl.searchParams.set('action', 'extend_web_token');
                    extUrl.searchParams.set('web_token', TOKEN);
                    await fetch(extUrl);
                } catch(e) {}
                switchPage(currentPage);
            } else {
                document.getElementById('authError').textContent = data.message || '验证失败';
            }
        } catch (e) {
            document.getElementById('authError').textContent = '连接失败: ' + e.message;
        }
    }

    async function doEmailAuth() {
        const email = document.getElementById('authEmail').value.trim();
        const code = document.getElementById('authCode').value.trim();
        if (!email) { document.getElementById('authError').textContent = '请输入邮箱'; return; }
        if (!code || code.length !== 6) { document.getElementById('authError').textContent = '请输入6位验证码'; return; }
        document.getElementById('authError').textContent = '验证中...';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'verify_email_code');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: currentPlayer, email: email, code: code, web_token: TOKEN})
            });
            const data = await res.json();
            if (data.success) {
                AUTHENTICATED = true;
                NEED_PASSWORD = false;
                currentPlayer = data.data.player || currentPlayer;
                localStorage.setItem('sdf1_player', currentPlayer);
                document.getElementById('playerInfo').textContent = '已登录: ' + currentPlayer;
                closeModal();
                toast('邮箱验证成功', 'success');
                switchPage(currentPage);
            } else {
                document.getElementById('authError').textContent = data.message || '验证失败';
            }
        } catch (e) {
            document.getElementById('authError').textContent = '连接失败: ' + e.message;
        }
    }

    async function requirePasswordForAction(action) {
        if (AUTHENTICATED) return true;
        return new Promise((resolve) => {
            checkAccess(action, (result) => {
                if (result.ok) resolve(true);
                else if (result.need_password) resolve(false);
                else { toast(result.message || '验证失败', 'error'); resolve(false); }
            });
        });
    }

    function showLoginPrompt() {
        const c = document.getElementById('content');
        c.innerHTML = `
            <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:60vh;text-align:center;padding:40px">
                <div style="font-size:64px;margin-bottom:24px">🎮</div>
                <h2 style="color:var(--text);margin-bottom:12px">欢迎来到SDF1玩家商城</h2>
                <p style="color:var(--dim);font-size:14px;margin-bottom:32px;max-width:400px">
                    请先登录游戏获取Web访问令牌<br>
                    然后通过令牌链接访问此页面
                </p>
                <div style="background:rgba(59,130,246,0.1);border:1px solid var(--accent);border-radius:8px;padding:20px;margin-bottom:32px;max-width:500px;text-align:left">
                    <h3 style="color:var(--accent);margin:0 0 12px 0;font-size:16px">📱 如何获取访问令牌？</h3>
                    <ol style="color:var(--dim);font-size:13px;margin:0;padding-left:20px;line-height:1.8">
                        <li>登录Minecraft游戏服务器</li>
                        <li>在聊天栏输入 <code style="background:rgba(255,255,255,0.1);padding:2px 6px;border-radius:3px">/sdf1_login weblogin</code></li>
                        <li>复制返回的Web访问链接</li>
                        <li>在浏览器中打开该链接</li>
                    </ol>
                </div>
                <div style="display:flex;gap:12px;flex-wrap:wrap;justify-content:center">
                    <button class="btn" onclick="location.reload()" style="padding:12px 24px">🔄 刷新页面</button>
                    <button class="btn btn-primary" onclick="showGuestMode()" style="padding:12px 24px">👁️ 预览模式</button>
                </div>
                <p style="color:var(--dim);font-size:12px;margin-top:24px">游客模式下可以浏览商品，但无法购买</p>
            </div>`;
        document.querySelector('.sidebar').style.display = 'none';
    }

    function showGuestMode() {
        IS_PREVIEW = true;
        TOKEN = '';
        currentPlayer = '';
        AUTHENTICATED = false;
        localStorage.removeItem('sdf1_token');
        localStorage.removeItem('sdf1_player');
        document.getElementById('playerInfo').textContent = '游客模式';
        document.getElementById('previewBadge').style.display = 'inline';
        document.querySelector('.sidebar').style.display = 'block';
        switchPage('shop');
    }

    function switchPage(page) {
        currentPage = page;
        document.querySelectorAll('.sidebar-item').forEach(el => {
            el.classList.toggle('active', el.dataset.page === page);
        });
        document.querySelector('.sidebar').style.display = 'block';
        const c = document.getElementById('content');
        if (page === 'shop') renderShop(c);
        else if (page === 'cdk') renderCDK(c);
        else if (page === 'balance') renderBalance(c);
        else if (page === 'account') renderAccount(c);
    }

    async function api(endpoint, params = {}) {
        if (TOKEN) params.token = TOKEN;
        const url = new URL(API + endpoint, location.href);
        Object.entries(params).forEach(([k,v]) => url.searchParams.set(k, v));
        const res = await fetch(url);
        return await res.json();
    }

    // ===== 商城 =====
    async function renderShop(el) {
        el.innerHTML = '<div class="empty">加载中...</div>';
        try {
            const res = await api('shop.php', {action: 'list'});
            if (!res.success) { el.innerHTML = '<div class="empty" style="color:var(--red)">' + (res.message || '加载失败') + '</div>'; return; }
            const items = res.data || [];
            const categories = [...new Set(items.map(i => i.category))];
            let html = '<div class="card"><h2>商品列表</h2>';
            if (res.preview) html += '<div class="preview-badge" style="margin-bottom:12px">预览模式 - 无法购买</div>';
            if (categories.length === 0) {
                html += '<div class="empty">暂无商品</div>';
            } else {
                categories.forEach(cat => {
                    html += `<h3 style="color:var(--dim);margin:16px 0 8px;font-size:14px">${cat}</h3>`;
                    html += '<div class="grid">';
                    items.filter(i => i.category === cat).forEach(item => {
                        const matIcon = getMaterialIcon(item.material);
                        const stockText = item.stock == -1 ? '∞ 无限' : (item.stock == 0 ? '售罄' : item.stock + ' 个');
                        const stockClass = item.stock == 0 ? 'out' : '';
                        html += `<div class="item-card" onclick="showBuyModal('${item.id}','${item.display_name.replace(/'/g,"\\'")}',${item.buy_price},${item.stock})"><div class="icon">${matIcon}</div><div class="name">${item.display_name}</div><div class="price">${item.buy_price} 债券</div><div class="stock ${stockClass}">库存: ${stockText}</div></div>`;
                    });
                    html += '</div>';
                });
            }
            html += '</div>';
            el.innerHTML = html;
        } catch (e) {
            el.innerHTML = '<div class="empty" style="color:var(--red)">商城加载失败: ' + e.message + '</div>';
        }
    }

    function getMaterialIcon(mat) {
        const icons = {'STONE':'🪨','DIAMOND':'💎','GOLD_INGOT':'🥇','IRON_INGOT':'🥈','EMERALD':'💚','REDSTONE':'🔴','COAL':'⬛','DIAMOND_SWORD':'⚔️','GOLDEN_APPLE':'🍎','END_CRYSTAL':'🔮','TOTEM_UNDYING':'🛡️','NETHERITE_INGOT':'⬛','ENCHANTED_BOOK':'📖','SHULKER_BOX':'📦','PLAYER_HEAD':'👤','PAPER':'📄','ARROW':'🏹','BOW':'🏹','TRIDENT':'🔱','ELYTRA':'🪽','SADDLE':'🐴','NAME_TAG':'🏷️'};
        return icons[mat] || '🧱';
    }

    function showBuyModal(id, name, price, stock) {
        if (IS_PREVIEW) { toast('预览模式下无法购买', 'error'); return; }
        if (stock == 0) { toast('商品已售罄', 'error'); return; }
        document.getElementById('modalTitle').textContent = '购买: ' + name;
        document.getElementById('modalBody').innerHTML = `
            <div class="row"><label>单价: ${price} 债券</label></div>
            <div class="row"><label>数量</label><input type="number" id="buyAmount" value="1" min="1" max="${stock > 0 ? stock : 64}"></div>
            <div class="row"><label>小计: <span id="buyTotal">${price}</span> 债券</label></div>
            <div class="row"><label>密码确认</label><input type="password" id="buyPassword" placeholder="输入游戏登录密码"></div>`;
        document.getElementById('modalConfirm').onclick = () => doBuy(id);
        document.getElementById('buyAmount').oninput = function() { document.getElementById('buyTotal').textContent = this.value * price; };
        document.getElementById('modalOverlay').style.display = 'flex';
    }

    async function doBuy(itemId) {
        const amount = parseInt(document.getElementById('buyAmount').value);
        const password = document.getElementById('buyPassword').value;
        if (!password) { toast('请输入密码确认', 'error'); return; }
        const res = await fetch(API + 'shop.php?action=buy', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: TOKEN, item_id: itemId, amount: amount, player: currentPlayer, password: password})
        });
        const data = await res.json();
        if (data.need_password) { showPasswordModal(data.message || '请输入游戏登录密码'); return; }
        closeModal();
        toast(data.message, data.success ? 'success' : 'error');
        if (data.success) switchPage('shop');
    }

    // ===== CDK =====
    function renderCDK(el) {
        el.innerHTML = `
            <div class="card">
                <h2>CDK兑换</h2>
                <p style="color:var(--dim);font-size:13px;margin-bottom:12px">输入CDK兑换码，获得债券奖励</p>
                ${IS_PREVIEW ? '<div class="preview-badge" style="margin-bottom:12px">预览模式 - 兑换不会生效</div>' : ''}
                <div class="cdk-input" style="flex-direction:column;gap:8px">
                    <input type="text" id="cdkCode" placeholder="请输入CDK兑换码..." maxlength="20">
                    <input type="password" id="cdkPassword" placeholder="输入游戏登录密码" style="width:100%;padding:10px 14px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;outline:none;box-sizing:border-box">
                    <button class="btn btn-green" onclick="doCDKExchange()">兑换</button>
                </div>
                <div id="cdkResult" style="margin-top:12px"></div>
            </div>`;
    }

    async function doCDKExchange() {
        const code = document.getElementById('cdkCode').value.trim();
        const password = document.getElementById('cdkPassword').value;
        if (!code) { toast('请输入CDK码', 'error'); return; }
        if (!password) { toast('请输入密码确认', 'error'); return; }
        const res = await fetch(API + 'cdk.php?action=exchange', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: TOKEN, code: code, player: currentPlayer, password: password})
        });
        const data = await res.json();
        if (data.need_password) { showPasswordModal(data.message || '请输入游戏登录密码'); return; }
        if (data.success) {
            document.getElementById('cdkResult').innerHTML = `<div class="card" style="border-color:var(--green)"><h2 style="color:var(--green)">兑换成功!</h2><p>获得 ${data.data.amount} 债券</p><p>当前余额: ${data.data.balance_after} 债券</p></div>`;
        } else {
            document.getElementById('cdkResult').innerHTML = `<div class="card" style="border-color:var(--red)"><h2 style="color:var(--red)">兑换失败</h2><p>${data.message}</p></div>`;
        }
    }

    // ===== 余额 =====
    function renderBalance(el) {
        el.innerHTML = `
            <div class="card">
                <h2>余额查询</h2>
                ${IS_PREVIEW ? '<div class="preview-badge" style="margin-bottom:12px">预览模式 - 需要token才能查询</div>' : ''}
                <div style="margin-top:12px">
                    <label style="color:var(--dim);font-size:13px">玩家名</label>
                    <div style="display:flex;gap:8px;margin-top:4px">
                        <input type="text" id="queryPlayer" value="${currentPlayer}" style="flex:1;padding:10px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px">
                        <button class="btn btn-primary" onclick="doQueryBalance()">查询</button>
                    </div>
                </div>
                <div id="balanceResult" style="margin-top:16px"></div>
            </div>`;
    }

    async function doQueryBalance() {
        const player = document.getElementById('queryPlayer').value.trim();
        if (!player) { toast('请输入玩家名', 'error'); return; }
        const url = new URL(API + 'balance.php', location.href);
        url.searchParams.set('action', 'query');
        url.searchParams.set('player', player);
        if (TOKEN) url.searchParams.set('token', TOKEN);
        const res = await fetch(url);
        const data = await res.json();
        const div = document.getElementById('balanceResult');
        if (data.need_password) { showPasswordModal(data.message || '请输入游戏登录密码'); return; }
        if (data.success) {
            const d = data.data;
            div.innerHTML = `<div class="stat-row"><div class="stat-box"><div class="value" style="color:var(--green)">${d.bonds}</div><div class="label">债券</div></div><div class="stat-box"><div class="value" style="color:var(--purple)">${d.points}</div><div class="label">积分</div></div></div><div style="text-align:center;color:var(--dim);font-size:12px;margin-top:8px">数据${d.freshness || '状态未知'} | 以游戏内实际数据为准</div>`;
        } else {
            div.innerHTML = `<div class="card" style="border-color:var(--red)">${data.message}</div>`;
        }
    }

    // ===== 账号 =====
    function renderAccount(el) {
        if (IS_PREVIEW) {
            el.innerHTML = `<div class="card"><h2>账号信息</h2><div style="text-align:center;padding:40px 20px"><div style="font-size:48px;margin-bottom:16px">🔒</div><p style="color:var(--dim);font-size:14px;margin-bottom:20px">游客模式下无法查看账号信息</p><p style="color:var(--dim);font-size:12px">请先登录游戏获取Web访问令牌</p></div></div>`;
            return;
        }
        el.innerHTML = `
            <div class="card">
                <h2>账号信息</h2>
                <div style="margin-top:12px">
                    <label style="color:var(--dim);font-size:13px">玩家名</label>
                    <div style="display:flex;gap:8px;margin-top:4px">
                        <input type="text" id="accountPlayer" value="${currentPlayer}" style="flex:1;padding:10px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px">
                        <button class="btn btn-primary" onclick="doQueryAccount()">查询</button>
                    </div>
                </div>
                <div id="accountResult" style="margin-top:16px"></div>
            </div>`;
    }

    async function doQueryAccount() {
        const player = document.getElementById('accountPlayer').value.trim();
        if (!player) { toast('请输入玩家名', 'error'); return; }
        const res = await api('register.php', {action: 'query', player: player, token: TOKEN});
        const div = document.getElementById('accountResult');
        if (res.success) {
            const d = res.data;
            const regTime = d.register_time ? new Date(d.register_time * 1000).toLocaleString() : '未知';
            const loginTime = d.last_login_time ? new Date(d.last_login_time * 1000).toLocaleString() : '未知';
            div.innerHTML = `<table class="table"><tr><td style="color:var(--dim)">用户名</td><td>${d.player_name}</td></tr><tr><td style="color:var(--dim)">注册时间</td><td>${regTime}</td></tr><tr><td style="color:var(--dim)">最后登录</td><td>${loginTime}</td></tr><tr><td style="color:var(--dim)">积分</td><td>${d.points || 0}</td></tr><tr><td style="color:var(--dim)">礼包阶段</td><td>${d.gift_stage || 0}</td></tr><tr><td style="color:var(--dim)">在线时长</td><td>${Math.floor((d.total_online_time||0)/3600)}小时</td></tr></table>`;
        } else {
            div.innerHTML = `<div class="card" style="border-color:var(--red)">${res.message}</div>`;
        }
    }

    // ===== 通用 =====
    function toast(msg, type = 'success') {
        const t = document.createElement('div');
        t.className = 'toast ' + type;
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => t.remove(), 3000);
    }

    function closeModal() {
        document.getElementById('modalOverlay').style.display = 'none';
    }

    function confirmModal() {}

    // ===== 忘记密码 =====
    function showResetPasswordModal() {
        closeModal();
        document.getElementById('modalTitle').textContent = '重置密码';
        document.getElementById('modalBody').innerHTML = `
            <p style="color:var(--dim);font-size:13px;margin-bottom:12px">系统会自动读取您注册时绑定的邮箱发送重置链接</p>
            <div class="row"><label>玩家名</label><input type="text" id="resetPlayer" value="${currentPlayer}" readonly style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px"></div>
            <div id="resetError" style="color:var(--red);font-size:12px;margin-top:4px"></div>`;
        document.getElementById('modalConfirm').textContent = '发送重置链接';
        document.getElementById('modalConfirm').onclick = () => doResetPassword();
        document.getElementById('modalOverlay').style.display = 'flex';
    }

    // ===== 修改密码 =====
    function showChangePasswordModal() {
        closeModal();
        document.getElementById('modalTitle').textContent = '修改密码';
        document.getElementById('modalBody').innerHTML = `
            <p style="color:var(--dim);font-size:13px;margin-bottom:12px">修改您的登录密码</p>
            <div class="row"><label>当前密码</label><input type="password" id="changePwdOld" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px"></div>
            <div class="row"><label>新密码</label><input type="password" id="changePwdNew" placeholder="6-20位" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px"></div>
            <div class="row"><label>确认新密码</label><input type="password" id="changePwdConfirm" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px"></div>
            <div id="changePwdError" style="color:var(--red);font-size:12px;margin-top:4px"></div>`;
        document.getElementById('modalConfirm').textContent = '修改密码';
        document.getElementById('modalConfirm').onclick = () => doChangePassword();
        document.getElementById('modalOverlay').style.display = 'flex';
    }

    async function doChangePassword() {
        const oldPwd = document.getElementById('changePwdOld').value.trim();
        const newPwd = document.getElementById('changePwdNew').value.trim();
        const confirmPwd = document.getElementById('changePwdConfirm').value.trim();

        if (!oldPwd || !newPwd || !confirmPwd) { document.getElementById('changePwdError').textContent = '请填写所有字段'; return; }
        if (newPwd !== confirmPwd) { document.getElementById('changePwdError').textContent = '两次输入的密码不一致'; return; }
        if (newPwd.length < 6 || newPwd.length > 20) { document.getElementById('changePwdError').textContent = '密码长度必须为6-20位'; return; }

        document.getElementById('changePwdError').textContent = '处理中...';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'change_password');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: currentPlayer, old_password: oldPwd, new_password: newPwd})
            });
            const data = await res.json();
            if (data.success) {
                document.getElementById('changePwdError').innerHTML = '<span style="color:var(--green)">密码修改成功，请重新登录</span>';
                setTimeout(() => { closeModal(); location.reload(); }, 2000);
            } else {
                document.getElementById('changePwdError').textContent = data.message || '修改失败';
            }
        } catch (e) {
            document.getElementById('changePwdError').textContent = '连接失败: ' + e.message;
        }
    }

    // ===== 重置密码（后端判断有无邮箱） =====
    async function doResetPassword() {
        const player = document.getElementById('resetPlayer').value.trim();
        if (!player) { document.getElementById('resetError').textContent = '请输入玩家名'; return; }
        document.getElementById('resetError').textContent = '发送中...';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'send_reset_password_link');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: player})
            });
            const data = await res.json();
            if (data.success) {
                document.getElementById('resetError').innerHTML = '<span style="color:var(--green)">✅ 重置链接已发送到邮箱，请查收</span>';
                setTimeout(() => { closeModal(); }, 2000);
            } else {
                // 根据错误信息判断情况
                if (data.message && data.message.includes('未绑定邮箱')) {
                    // 玩家没有绑定邮箱 → 显示管理员审核提示
                    document.getElementById('resetError').innerHTML = `
                        <div style="text-align:center">
                            <div style="font-size:32px;margin-bottom:12px">📧</div>
                            <p style="color:var(--text);font-size:14px;margin-bottom:8px">该账号未绑定邮箱</p>
                            <p style="color:var(--dim);font-size:13px;margin-bottom:16px">
                                您的账号还没有绑定邮箱，无法通过邮件重置密码。<br>
                                已自动提交申请，<b style="color:var(--accent)">请联系管理员</b>验证身份后帮您重置密码。<br>
                                <span style="color:var(--yellow);font-size:12px">审核通常需要 1-24 小时</span>
                            </p>
                            <div style="background:rgba(59,130,246,0.1);border:1px solid var(--accent);border-radius:8px;padding:12px;text-align:left">
                                <p style="color:var(--text);font-size:12px;margin:0"><b>管理员联系方式：</b></p>
                                <p style="color:var(--dim);font-size:12px;margin:4px 0 0 0">请联系服务器管理群或在线管理获取帮助</p>
                            </div>
                        </div>`;
                } else {
                    document.getElementById('resetError').textContent = data.message || '发送失败';
                }
            }
        } catch (e) {
            document.getElementById('resetError').textContent = '连接失败: ' + e.message;
        }
    }
    </script>
</body>
</html>
