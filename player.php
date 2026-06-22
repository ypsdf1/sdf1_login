<?php
// ★ 强制缓存失效：用文件修改时间作为版本号
// 每次修改player.php后，文件时间戳变化 → URL不同 → 浏览器必须获取新内容
header('Cache-Control: no-cache, no-store, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Expires: 0');
header('X-Accel-Expires: 0');  // Nginx反代也禁缓存

$BUILD_VERSION = 'v' . filemtime(__FILE__);  // 文件修改时间 = 版本号
$currentVersion = isset($_GET['_v']) ? $_GET['_v'] : '';
if ($currentVersion !== $BUILD_VERSION) {
    // 版本不匹配 → 重定向到带正确版本号的URL（浏览器视为全新请求）
    // 先移除旧的 _v 参数
    $params = $_GET;
    unset($params['_v']);
    $queryString = http_build_query($params);
    $path = strtok($_SERVER['REQUEST_URI'], '?');
    $newUrl = $path . ($queryString ? '?' . $queryString . '&' : '?') . '_v=' . $BUILD_VERSION;
    header('Cache-Control: no-store, no-cache, must-revalidate');
    header('Pragma: no-cache');
    header('Expires: 0');
    header('Location: ' . $newUrl, true, 302);
    exit;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="0">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>SDF1 - 玩家商城</title>
    <style>
        :root {
            --bg: #0d1117; --card: #161b22; --border: #30363d;
            --text: #e6edf3; --dim: #8b949e; --accent: #58a6ff;
            --green: #3fb950; --red: #f85149; --yellow: #d29922;
            --purple: #bc8cff;
        }
        .light-theme {
            --bg: #f6f8fa; --card: #ffffff; --border: #d0d7de;
            --text: #1f2328; --dim: #656d76; --accent: #0969da;
            --green: #1a7f37; --red: #cf222e; --yellow: #9a6700;
            --purple: #8250df;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: var(--bg); color: var(--text); font-family: 'Segoe UI', system-ui, sans-serif; min-height: 100vh; transition: background 0.3s, color 0.3s; }
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
            padding: 12px 0; flex-shrink: 0; transition: background 0.3s;
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
        .btn-dim { background: var(--card); color: var(--dim); border: 1px solid var(--border); }
        .btn-dim:hover { color: var(--text); border-color: var(--accent); }
        .table { width: 100%; border-collapse: collapse; font-size: 13px; }
        .table th { text-align: left; padding: 10px 12px; color: var(--dim); border-bottom: 1px solid var(--border); }
        .table td { padding: 10px 12px; border-bottom: 1px solid var(--border); }
        .table tr:hover td { background: rgba(88,166,255,0.05); }
        .empty { text-align: center; color: var(--dim); padding: 40px; }
        .modal-overlay {
            position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.6); display: flex; justify-content: center;
            align-items: center; z-index: 999; display: none;
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
        /* ★ 游客模式C位登录按钮 */
        .guest-center-login {
            display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            z-index: 50; pointer-events: none;
        }
        .guest-center-login.show { display: flex; justify-content: center; align-items: center; }
        .guest-center-login .glow-btn {
            pointer-events: auto;
            background: linear-gradient(135deg, var(--accent), #79c0ff);
            color: #fff; border: none; border-radius: 16px;
            padding: 20px 48px; font-size: 18px; font-weight: 700;
            cursor: pointer; box-shadow: 0 8px 32px rgba(88,166,255,0.4);
            animation: glowPulse 2s ease-in-out infinite;
            transition: all 0.3s; text-decoration: none;
        }
        .guest-center-login .glow-btn:hover { transform: scale(1.08); box-shadow: 0 12px 48px rgba(88,166,255,0.6); }
        @keyframes glowPulse {
            0%,100% { box-shadow: 0 8px 32px rgba(88,166,255,0.4); }
            50% { box-shadow: 0 8px 48px rgba(88,166,255,0.7); }
        }
        /* ★ 毛玻璃登录弹窗 */
        .glass-login-overlay {
            display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.35);
            backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
            z-index: 2000; justify-content: center; align-items: center;
            animation: glassFadeIn 0.3s ease;
        }
        .glass-login-overlay.show { display: flex; }
        .glass-card {
            background: rgba(22,27,34,0.88);
            backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px);
            border: 1px solid rgba(88,166,255,0.25);
            border-radius: 20px; padding: 36px 32px; width: 400px; max-width: 90%;
            box-shadow: 0 16px 64px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.05);
            animation: glassSlideUp 0.35s ease;
            position: relative;
        }
        .glass-card .x-btn {
            position: absolute; top: 12px; right: 16px; background: none; border: none;
            color: var(--dim); font-size: 22px; cursor: pointer; padding: 4px 8px;
            border-radius: 6px; transition: all 0.2s; line-height: 1;
        }
        .glass-card .x-btn:hover { color: var(--text); background: rgba(255,255,255,0.1); }
        .glass-card .logo { text-align: center; font-size: 48px; margin-bottom: 16px; }
        .glass-card h2 { text-align: center; color: var(--text); margin-bottom: 6px; font-size: 20px; }
        .glass-card .subtitle { text-align: center; color: var(--dim); font-size: 13px; margin-bottom: 24px; }
        .glass-card input[type=text], .glass-card input[type=password] {
            width: 100%; padding: 12px 16px; background: rgba(13,17,23,0.6);
            border: 1px solid rgba(88,166,255,0.2); border-radius: 10px;
            color: var(--text); font-size: 14px; outline: none; margin-bottom: 12px;
            transition: border-color 0.2s; box-sizing: border-box;
        }
        .glass-card input:focus { border-color: var(--accent); }
        .glass-card .login-btn {
            width: 100%; padding: 14px; background: linear-gradient(135deg, var(--accent), #79c0ff);
            color: #fff; border: none; border-radius: 10px; font-size: 15px;
            font-weight: 700; cursor: pointer; transition: all 0.2s; margin-top: 4px;
        }
        .glass-card .login-btn:hover { opacity: 0.9; transform: translateY(-1px); }
        .glass-card .login-btn:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }
        .glass-card .hint { text-align: center; color: var(--dim); font-size: 12px; margin-top: 16px; }
        .glass-card .hint code {
            background: rgba(88,166,255,0.1); padding: 1px 5px; border-radius: 4px;
            color: var(--accent); font-size: 11px;
        }
        .glass-card .status-msg {
            text-align: center; padding: 8px; border-radius: 8px; font-size: 13px;
            margin-bottom: 12px; display: none;
        }
        @keyframes glassFadeIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes glassSlideUp {
            from { opacity: 0; transform: translateY(30px) scale(0.95); }
            to { opacity: 1; transform: translateY(0) scale(1); }
        }
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
            <button class="btn btn-yellow" onclick="showThemePicker()" style="margin-left:16px;font-size:12px;padding:4px 8px">🎨 主题</button>
        </div>
    </div>

    <div class="main">
        <div class="sidebar">
            <div class="sidebar-item active" data-page="dashboard" onclick="switchPage('dashboard')">📊 个人中心</div>
            <div class="sidebar-item" data-page="shop" onclick="switchPage('shop')">🛒 商城</div>
            <div class="sidebar-item" data-page="cdk" onclick="switchPage('cdk')">🎁 CDK兑换</div>
            <div class="sidebar-item" data-page="balance" onclick="switchPage('balance')">💰 余额查询</div>
            <div class="sidebar-item" data-page="ticket" onclick="switchPage('ticket')">📋 工单系统</div>
        </div>

        <div class="content" id="content">
            <!-- 动态内容 -->
        </div>
    </div>

    <!-- ★ 游客模式C位登录按钮 -->
    <div class="guest-center-login" id="guestCenterLogin">
        <button class="glow-btn" onclick="openGlassLogin()">🔑 立即登录</button>
    </div>

    <!-- ★ 毛玻璃登录弹窗 -->
    <div class="glass-login-overlay" id="glassLoginOverlay" onclick="closeGlassLogin(event)">
        <div class="glass-card" onclick="event.stopPropagation()">
            <button class="x-btn" onclick="closeGlassLogin()">&times;</button>
            <div class="logo">🔐</div>
            <h2>登录到SDF1</h2>
            <p class="subtitle">使用游戏内密码登录，无需在游戏里敲指令</p>
            <div class="status-msg" id="glassLoginStatus"></div>
            <input type="text" id="glassPlayerName" placeholder="玩家名" maxlength="16" autocomplete="username">
            <input type="password" id="glassPassword" placeholder="游戏内密码" maxlength="32" autocomplete="current-password">
            <button class="login-btn" id="glassLoginBtn" onclick="doGlassLogin()">登 录</button>
            <p class="hint">也可在游戏中输入 <code>/sdf1_login weblogin</code> 获取链接</p>
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
    // ★ 防止bfcache（前进/后退缓存）绕过安全检查
    window.addEventListener('pageshow', function(e) {
        if (e.persisted) {
            // 页面从bfcache恢复 → 强制刷新以获取最新代码
            console.log('[BFCACHE] Page restored from bfcache, forcing reload');
            location.reload();
        }
    });
    // ★ 版本检测：强制清除浏览器缓存的旧版JS
    (function() {
        const buildVersion = '<?php echo $BUILD_VERSION; ?>';
        const lastVersion = localStorage.getItem('sdf1_build_version');
        if (lastVersion && lastVersion !== buildVersion) {
            // 版本变化 → 清除所有缓存，强制刷新
            localStorage.removeItem('sdf1_token');
            localStorage.removeItem('sdf1_player');
            localStorage.removeItem('sdf1_build_version');
            localStorage.setItem('sdf1_build_version', buildVersion);
            console.log('[VERSION] Old version detected, clearing cache and reloading. Old:', lastVersion, 'New:', buildVersion);
            location.reload();
            return;
        }
        localStorage.setItem('sdf1_build_version', buildVersion);
    })();
    const API = 'api/';
    let TOKEN = new URLSearchParams(location.search).get('token') || localStorage.getItem('sdf1_token') || '';
    let IS_PREVIEW = !TOKEN;
    let currentPlayer = localStorage.getItem('sdf1_player') || new URLSearchParams(location.search).get('login') || '';
    let tokenPollTimer = null; // ★ 新增：token轮询定时器（用于续期检查）
    let AUTH_TOKEN = TOKEN; // ★ 使用独立的认证token，避免localStorage被意外覆盖
    let authVerified = false; // ★ 记录是否已通过首次验证
    let currentPage = 'dashboard';
    let AUTHENTICATED = false;
    let NEED_PASSWORD = false;

    // 初始化 — 回档9bae160版本的token处理逻辑
    (function() {
        const urlToken = new URLSearchParams(location.search).get('token');

        if (urlToken) {
            localStorage.setItem('sdf1_token', urlToken);
            TOKEN = urlToken;
            IS_PREVIEW = false;
            // ★ 从URL中移除token参数，避免URL暴露
            const params = new URLSearchParams(location.search);
            params.delete('token');
            const newUrl = location.pathname + (params.toString() ? '?' + params.toString() : '');
            window.history.replaceState({}, '', newUrl);
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
                authVerified = true; // ★ 标记已通过验证
                if (callback) callback({ok: true, mode: data.data.mode});
                // ★ 如果不需要密码，直接加载个人中心
                if (typeof NEED_PASSWORD !== 'undefined' && !NEED_PASSWORD) {
                    setTimeout(() => {
                        switchPage('dashboard');
                        // ★ Token跟随Java端配置过期时间自动过期，不续费
                        // startTokenPolling() 不再调用，仅保留变量声明
                    }, 100);
                }
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
                document.querySelector('.guest-center-login').classList.add('show');
                showLoginPrompt();
                startGuestLoginTimer();
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
            document.querySelector('.guest-center-login').classList.add('show');
            showLoginPrompt();
            startGuestLoginTimer();
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

    async function loadBoundEmail() {
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'get_player_email');
            url.searchParams.set('player', currentPlayer);
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'}
            });
            const data = await res.json();
            if (data.success && data.data) {
                document.getElementById('authEmail').value = data.data.masked_email || data.data.email || '';
                if (data.data.masked_email) {
                    document.getElementById('authEmail').placeholder = '已绑定邮箱';
                } else {
                    document.getElementById('authEmail').placeholder = '未绑定邮箱';
                }
            } else {
                document.getElementById('authEmail').value = '未绑定邮箱';
                document.getElementById('authEmail').placeholder = '未绑定邮箱';
            }
        } catch (e) {
            document.getElementById('authEmail').value = '加载失败';
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
                <div class="row"><label>绑定邮箱</label>
                    <input type="text" id="authEmail" readonly style="background:#21262d;cursor:not-allowed;opacity:0.7" placeholder="加载中...">
                </div>
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
        setTimeout(() => {
            document.getElementById('authPassword').focus();
            loadBoundEmail();
        }, 100);
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
                body: JSON.stringify({player: currentPlayer, web_token: TOKEN})
            });
            const data = await res.json();
            if (data.success) {
                const maskedEmail = data.data.masked_email || '**@*';
                document.getElementById('authError').innerHTML = '<span style="color:var(--green)">验证码已发送至 ' + maskedEmail + '</span>';
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
        const code = document.getElementById('authCode').value.trim();
        if (!code || code.length !== 6) { document.getElementById('authError').textContent = '请输入6位验证码'; return; }
        document.getElementById('authError').textContent = '验证中...';
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'verify_email_code');
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: currentPlayer, code: code, web_token: TOKEN})
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
        const urlLogin = new URLSearchParams(location.search).get('login') || currentPlayer || '';
        c.innerHTML = `
            <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:60vh;text-align:center;padding:40px">
                <div style="font-size:64px;margin-bottom:24px">🔐</div>
                <h2 style="color:var(--text);margin-bottom:12px">登录到SDF1玩家商城</h2>
                <p style="color:var(--dim);font-size:14px;margin-bottom:24px;max-width:400px">
                    输入游戏内密码登录，无需在游戏里敲指令
                </p>
                <!-- 密码登录表单 -->
                <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;margin-bottom:24px;width:360px;max-width:100%">
                    <div style="text-align:left;margin-bottom:16px">
                        <label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">玩家名</label>
                        <input type="text" id="loginPlayerName" value="${urlLogin}" placeholder="输入玩家名" maxlength="16"
                            style="width:100%;padding:10px 14px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;outline:none;box-sizing:border-box">
                    </div>
                    <div style="text-align:left;margin-bottom:20px">
                        <label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">游戏内密码</label>
                        <input type="password" id="loginPassword" placeholder="输入密码" maxlength="32"
                            style="width:100%;padding:10px 14px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;outline:none;box-sizing:border-box">
                    </div>
                    <div id="loginStatusBox" style="display:none;margin-bottom:16px"></div>
                    <button id="loginSubmitBtn" class="btn btn-primary" style="width:100%;padding:12px" onclick="doDirectLogin()">登录</button>
                    <p style="color:var(--dim);font-size:12px;margin-top:12px">密码由游戏服务器验证，Web端不存储密码</p>
                </div>
                <!-- 备用方案 -->
                <div style="background:rgba(59,130,246,0.08);border:1px solid rgba(59,130,246,0.3);border-radius:8px;padding:16px;margin-bottom:24px;max-width:400px;text-align:left">
                    <h3 style="color:var(--accent);margin:0 0 8px 0;font-size:14px">📱 也可以在游戏中获取链接</h3>
                    <p style="color:var(--dim);font-size:12px;margin:0;line-height:1.6">
                        在游戏聊天栏输入 <code style="background:rgba(255,255,255,0.1);padding:1px 4px;border-radius:3px">/sdf1_login weblogin</code>
                    </p>
                </div>
                <button class="btn" onclick="showGuestMode()" style="padding:10px 20px;font-size:13px">👁️ 先逛逛（游客模式）</button>
            </div>`;
        document.querySelector('.sidebar').style.display = 'none';
        // 自动聚焦密码框
        setTimeout(() => { const pwd = document.getElementById('loginPassword'); if (pwd) pwd.focus(); }, 100);
    }

    // ★ 直接密码登录（不需要游戏内token）
    async function doDirectLogin() {
        const player = document.getElementById('loginPlayerName').value.trim();
        const password = document.getElementById('loginPassword').value;
        const statusBox = document.getElementById('loginStatusBox');
        const submitBtn = document.getElementById('loginSubmitBtn');

        if (!player) { showLoginStatus('请输入玩家名', 'error'); return; }
        if (!password) { showLoginStatus('请输入密码', 'error'); return; }

        submitBtn.disabled = true;
        showLoginStatus('正在提交登录请求...', 'loading');

        try {
            // 1. 提交登录请求
            const reqRes = await fetch(API + 'sync.php?action=web_login_request', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player, password})
            });
            const reqData = await reqRes.json();
            if (!reqData.success) {
                showLoginStatus(reqData.message || '提交失败', 'error');
                submitBtn.disabled = false;
                return;
            }
            const requestId = reqData.data.request_id;
            showLoginStatus('等待游戏服务器验证密码...', 'loading');

            // 2. 轮询结果
            let attempts = 0;
            const pollInterval = setInterval(async () => {
                attempts++;
                if (attempts > 60) {
                    clearInterval(pollInterval);
                    showLoginStatus('验证超时，请稍后重试', 'error');
                    submitBtn.disabled = false;
                    return;
                }
                try {
                    const pollRes = await fetch(API + 'sync.php?action=check_web_login_result&player=' + encodeURIComponent(player) + '&request_id=' + requestId);
                    const pollData = await pollRes.json();
                    if (pollData.success && pollData.data) {
                        const result = pollData.data;
                        if (result.status === 'success') {
                            clearInterval(pollInterval);
                            showLoginStatus('✅ 登录成功！正在跳转...', 'success');
                            // 存储token和玩家名
                            const token = result.token;
                            localStorage.setItem('sdf1_token', token);
                            localStorage.setItem('sdf1_player', player);
                            // 跳转到player.php带token
                            setTimeout(() => {
                                window.location.href = 'player.php?login=' + encodeURIComponent(player) + '&token=' + encodeURIComponent(token);
                            }, 500);
                        } else if (result.status === 'failed') {
                            clearInterval(pollInterval);
                            showLoginStatus('❌ ' + (result.message || '密码错误'), 'error');
                            submitBtn.disabled = false;
                        }
                    }
                } catch (e) { /* 继续轮询 */ }
            }, 1000);
        } catch (e) {
            showLoginStatus('连接失败: ' + e.message, 'error');
            submitBtn.disabled = false;
        }
    }

    function showLoginStatus(msg, type) {
        const box = document.getElementById('loginStatusBox');
        if (!box) return;
        const colors = { loading: 'var(--accent)', success: 'var(--green)', error: 'var(--red)' };
        const bgs = { loading: 'rgba(88,166,255,0.1)', success: 'rgba(63,185,80,0.1)', error: 'rgba(248,81,73,0.1)' };
        box.style.display = 'block';
        box.style.background = bgs[type] || bgs.loading;
        box.style.border = '1px solid ' + (colors[type] || colors.loading);
        box.style.borderRadius = '6px';
        box.style.padding = '10px 14px';
        box.style.fontSize = '13px';
        box.style.color = colors[type] || 'var(--text)';
        box.innerHTML = type === 'loading' ? '<span style="display:inline-block;width:14px;height:14px;border:2px solid var(--dim);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;vertical-align:middle;margin-right:6px"></span> ' + msg : msg;
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
        document.querySelector('.guest-center-login').classList.add('show');
        switchPage('dashboard');
        // ★ 启动60秒定时弹窗
        startGuestLoginTimer();
    }

    // ==================== 毛玻璃登录弹窗 ====================
    let guestLoginTimer = null;
    let glassLoginDismissed = false;

    function openGlassLogin() {
        const overlay = document.getElementById('glassLoginOverlay');
        overlay.classList.add('show');
        glassLoginDismissed = false;
        // 隐藏C位登录按钮
        document.querySelector('.guest-center-login').classList.remove('show');
        // 停止定时弹窗
        if (guestLoginTimer) { clearInterval(guestLoginTimer); guestLoginTimer = null; }
        setTimeout(() => {
            const nameInput = document.getElementById('glassPlayerName');
            const savedPlayer = localStorage.getItem('sdf1_player') || currentPlayer || '';
            if (nameInput) { nameInput.value = savedPlayer; nameInput.focus(); }
        }, 100);
    }

    function closeGlassLogin(e) {
        if (e && e.target !== document.getElementById('glassLoginOverlay')) return;
        document.getElementById('glassLoginOverlay').classList.remove('show');
        glassLoginDismissed = true;
        glassLoginReset();
        // 重新显示C位登录按钮（如果还是游客模式）
        if (IS_PREVIEW) document.querySelector('.guest-center-login').classList.add('show');
        // 重新启动定时弹窗
        if (IS_PREVIEW) startGuestLoginTimer();
    }

    function glassLoginReset() {
        const btn = document.getElementById('glassLoginBtn');
        const st = document.getElementById('glassLoginStatus');
        const pwd = document.getElementById('glassPassword');
        if (btn) { btn.disabled = false; btn.textContent = '登 录'; }
        if (st) { st.style.display = 'none'; st.textContent = ''; }
        if (pwd) pwd.value = '';
    }

    function setGlassStatus(msg, type) {
        const st = document.getElementById('glassLoginStatus');
        if (!st) return;
        const colors = { loading: 'var(--accent)', success: 'var(--green)', error: 'var(--red)' };
        const bgs = { loading: 'rgba(88,166,255,0.12)', success: 'rgba(63,185,80,0.12)', error: 'rgba(248,81,73,0.12)' };
        st.style.display = 'block';
        st.style.background = bgs[type] || bgs.loading;
        st.style.border = '1px solid ' + (colors[type] || colors.loading);
        st.style.borderRadius = '8px';
        st.style.color = colors[type] || 'var(--text)';
        st.innerHTML = type === 'loading'
            ? '<span style="display:inline-block;width:14px;height:14px;border:2px solid var(--dim);border-top-color:var(--accent);border-radius:50%;animation:spin 0.8s linear infinite;vertical-align:middle;margin-right:6px"></span> ' + msg
            : msg;
    }

    async function doGlassLogin() {
        const player = document.getElementById('glassPlayerName').value.trim();
        const password = document.getElementById('glassPassword').value;
        const btn = document.getElementById('glassLoginBtn');
        if (!player) { setGlassStatus('请输入玩家名', 'error'); return; }
        if (!password) { setGlassStatus('请输入密码', 'error'); return; }
        btn.disabled = true; btn.textContent = '登录中...';
        setGlassStatus('正在提交登录请求...', 'loading');
        try {
            const reqRes = await fetch(API + 'sync.php?action=web_login_request', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player, password})
            });
            const reqData = await reqRes.json();
            if (!reqData.success) { setGlassStatus(reqData.message || '提交失败', 'error'); btn.disabled = false; btn.textContent = '登 录'; return; }
            const requestId = reqData.data.request_id;
            setGlassStatus('等待游戏服务器验证...', 'loading');
            let attempts = 0;
            const pollInterval = setInterval(async () => {
                attempts++;
                if (attempts > 60) { clearInterval(pollInterval); setGlassStatus('验证超时，请稍后重试', 'error'); btn.disabled = false; btn.textContent = '登 录'; return; }
                try {
                    const pollRes = await fetch(API + 'sync.php?action=check_web_login_result&player=' + encodeURIComponent(player) + '&request_id=' + requestId);
                    const pollData = await pollRes.json();
                    if (pollData.success && pollData.data) {
                        const result = pollData.data;
                        if (result.status === 'success') {
                            clearInterval(pollInterval);
                            setGlassStatus('登录成功！正在跳转...', 'success');
                            const token = result.token;
                            localStorage.setItem('sdf1_token', token);
                            localStorage.setItem('sdf1_player', player);
                            setTimeout(() => { window.location.href = 'player.php?login=' + encodeURIComponent(player) + '&token=' + encodeURIComponent(token); }, 600);
                        } else if (result.status === 'failed') {
                            clearInterval(pollInterval);
                            setGlassStatus(result.message || '密码错误', 'error');
                            btn.disabled = false; btn.textContent = '登 录';
                        }
                    }
                } catch (e) { /* 继续轮询 */ }
            }, 1000);
        } catch (e) {
            setGlassStatus('连接失败: ' + e.message, 'error');
            btn.disabled = false; btn.textContent = '登 录';
        }
    }

    // ★ 60秒定时弹窗（游客模式专用）
    function startGuestLoginTimer() {
        if (guestLoginTimer) clearInterval(guestLoginTimer);
        guestLoginTimer = setInterval(() => {
            if (!IS_PREVIEW) { clearInterval(guestLoginTimer); guestLoginTimer = null; return; }
            // 只要游客模式就弹窗，忽略glassLoginDismissed
            openGlassLogin();
        }, 60000);
    }

    function switchPage(page) {
        currentPage = page;
        document.querySelectorAll('.sidebar-item').forEach(el => {
            el.classList.toggle('active', el.dataset.page === page);
        });
        document.querySelector('.sidebar').style.display = 'block';
        const c = document.getElementById('content');
        if (!c) return;
        if (page === 'dashboard') renderDashboard(c);
        else if (page === 'shop') renderShop(c);
        else if (page === 'cdk') renderCDK(c);
        else if (page === 'balance') renderBalance(c);
        else if (page === 'account') renderAccount(c);
        else if (page === 'ticket') renderTicket(c);
    }

    // ★ 新增：Token轮询检查（不续期，只检查是否被Java端主动销毁）
    function startTokenPolling() {
        if (tokenPollTimer) clearInterval(tokenPollTimer);
        
        // 每1分钟检查一次token是否仍然有效
        tokenPollTimer = setInterval(() => {
            if (TOKEN && !IS_PREVIEW && authVerified) {
                checkTokenValidity();
            }
        }, 60 * 1000); // 1分钟
    }

    // ★ 检查token有效性（不续期，只检查）
    async function checkTokenValidity() {
        if (!TOKEN || IS_PREVIEW) return;
        
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'web_access_check');
            url.searchParams.set('web_token', TOKEN);
            url.searchParams.set('access_action', 'view');
            
            const res = await fetch(url);
            const data = await res.json();
            
            if (!data.success) {
                // Token可能已失效或过期
                if (data.need_password || data.need_game_login || data.need_register) {
                    // 需要进一步验证，不视为token无效
                    return;
                }
                
                // Token确实无效（可能被Java端主动销毁）
                console.log('[Token] Token验证失败:', data.message);
                TOKEN = '';
                AUTHENTICATED = false;
                localStorage.removeItem('sdf1_token');
                localStorage.removeItem('sdf1_player');
                currentPlayer = '';
                IS_PREVIEW = true;
                authVerified = false;
                document.getElementById('playerInfo').textContent = '游客模式';
                document.getElementById('previewBadge').style.display = 'inline';
                showLoginPrompt();
            }
        } catch (e) {
            console.log('[Token] Token检查异常:', e.message);
        }
    }

    async function api(endpoint, params = {}) {
        if (TOKEN) params.token = TOKEN;
        const url = new URL(API + endpoint, location.href);
        Object.entries(params).forEach(([k,v]) => url.searchParams.set(k, v));
        const res = await fetch(url);
        return await res.json();
    }

    // 通知Java插件立即同步数据
    async function notifyJavaSync() {
        try {
            const url = new URL(API + 'sync.php', location.href);
            url.searchParams.set('action', 'request_immediate_sync');
            url.searchParams.set('player', currentPlayer);
            await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/json'} });
        } catch(e) {
            // 静默失败，不影响用户体验
        }
    }

    // ===== 商城（分类标签页） =====
    let shopItems = [];
    let shopCategories = [];
    let currentShopCat = '';

    async function renderShop(el) {
        el.innerHTML = '<div class="empty">加载中...</div>';
        try {
            const res = await api('shop.php', {action: 'list'});
            if (!res.success) { el.innerHTML = '<div class="empty" style="color:var(--red)">' + (res.message || '加载失败') + '</div>'; return; }
            shopItems = res.data || [];
            shopCategories = [...new Set(shopItems.map(i => i.category))];
            currentShopCat = shopCategories[0] || '';

            let html = '<div class="card">';
            html += '<h2>商城</h2>';
            if (res.preview) html += '<div class="preview-badge" style="margin-bottom:12px">预览模式 - 无法购买</div>';

            if (shopCategories.length === 0) {
                html += '<div class="empty">暂无商品</div>';
            } else {
                // 分类标签
                html += '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px">';
                shopCategories.forEach((cat, idx) => {
                    const active = idx === 0 ? 'color:#fff;background:var(--accent);border-color:var(--accent)' : '';
                    const catCount = shopItems.filter(i => i.category === cat).length;
                    html += `<div onclick="switchShopCat('${cat.replace(/'/g,"\\'")}')" style="padding:6px 14px;border:1px solid var(--border);border-radius:16px;cursor:pointer;font-size:13px;transition:all 0.2s;${active}" class="shop-cat-tab" data-cat="${cat}">${cat} <span style="opacity:0.7;font-size:11px">(${catCount})</span></div>`;
                });
                html += '</div>';

                // 商品内容区
                html += '<div id="shopContent"></div>';
            }

            html += '</div>';
            el.innerHTML = html;

            // 渲染当前分类的商品
            renderShopContent();
        } catch (e) {
            el.innerHTML = '<div class="empty" style="color:var(--red)">商城加载失败: ' + e.message + '</div>';
        }
    }

    function switchShopCat(cat) {
        currentShopCat = cat;
        // 更新标签样式
        document.querySelectorAll('.shop-cat-tab').forEach(tab => {
            const isActive = tab.dataset.cat === cat;
            tab.style.background = isActive ? 'var(--accent)' : '';
            tab.style.color = isActive ? '#fff' : '';
            tab.style.borderColor = isActive ? 'var(--accent)' : 'var(--border)';
        });
        renderShopContent();
    }

    function renderShopContent() {
        const contentEl = document.getElementById('shopContent');
        if (!contentEl) return;

        const items = shopItems.filter(i => i.category === currentShopCat);
        if (items.length === 0) {
            contentEl.innerHTML = '<div class="empty" style="padding:20px">该分类暂无商品</div>';
            return;
        }

        let html = '<div class="grid">';
        items.forEach(item => {
            const matIcon = getMaterialIcon(item.material);
            const stockText = item.stock == -1 ? '∞ 无限' : (item.stock == 0 ? '售罄' : item.stock + ' 个');
            const stockClass = item.stock == 0 ? 'out' : '';
            html += `<div class="item-card" onclick="showBuyModal('${item.id}','${item.display_name.replace(/'/g,"\\'")}',${item.buy_price},${item.stock})"><div class="icon">${matIcon}</div><div class="name">${item.display_name}</div><div class="price">${item.buy_price} 债券</div><div class="stock ${stockClass}">库存: ${stockText}</div></div>`;
        });
        html += '</div>';
        contentEl.innerHTML = html;
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
        if (data.success) {
            // 购买成功 → 通知Java插件立即同步 + 刷新当前页
            setTimeout(() => {
                notifyJavaSync();
                document.getElementById('balanceResult')?.parentElement && switchPage('balance');
                setTimeout(() => switchPage('shop'), 500);
            }, 500);
        }
    }

    // ===== 个人中心仪表盘 =====
    async function renderDashboard(el) {
        if (IS_PREVIEW || !currentPlayer || (!AUTHENTICATED && NEED_PASSWORD)) {
            el.innerHTML = `
                <div class="card" style="text-align:center;padding:48px 20px">
                    <div style="font-size:64px;margin-bottom:16px">📊</div>
                    <h2 style="color:var(--accent);margin-bottom:8px">个人中心</h2>
                    <p style="color:var(--dim);font-size:14px;margin-bottom:20px">游客模式下无法查看个人信息</p>
                    <p style="color:var(--dim);font-size:12px">请先在游戏内登录，或输入密码登录</p>
                </div>`;
            return;
        }

        el.innerHTML = '<div class="card" style="text-align:center;padding:40px"><div class="empty">加载中...</div></div>';

        try {
            const res = await api('register.php', {action: 'query', player: currentPlayer, token: TOKEN});
            if (!res.success) {
                el.innerHTML = `<div class="card" style="border-color:var(--red)"><h2>加载失败</h2><p style="color:var(--dim);margin-top:8px">${res.message || '未知错误'}</p></div>`;
                return;
            }
            const d = res.data;
            const loginTime = d.last_login_time ? new Date(d.last_login_time * 1000).toLocaleString() : '暂无';
            const regTime = d.register_time ? new Date(d.register_time * 1000).toLocaleString() : '暂无';
            const hours = Math.floor((d.total_online_time || 0) / 3600);
            const mins = Math.floor(((d.total_online_time || 0) % 3600) / 60);
            const onlineText = hours > 0 ? `${hours}小时${mins}分钟` : `${mins}分钟`;

            el.innerHTML = `
                <div class="card" style="margin-bottom:0">
                    <div style="display:flex;align-items:center;gap:16px;margin-bottom:20px">
                        <div style="width:56px;height:56px;border-radius:50%;background:linear-gradient(135deg,var(--accent),var(--purple));display:flex;align-items:center;justify-content:center;font-size:28px;color:#fff;font-weight:700">${d.player_name.charAt(0).toUpperCase()}</div>
                        <div>
                            <h2 style="margin:0;font-size:20px">${d.player_name}</h2>
                            <p style="color:var(--dim);font-size:12px;margin-top:2px">注册于 ${regTime}</p>
                        </div>
                    </div>
                </div>

                <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px;margin-top:12px">
                    <div class="stat-box" style="border-left:3px solid var(--accent)">
                        <div class="value" style="font-size:24px;color:var(--accent)">📧 ${d.masked_email || '未绑定'}</div>
                        <div class="label">绑定邮箱</div>
                    </div>
                    <div class="stat-box" style="border-left:3px solid var(--green)">
                        <div class="value" style="font-size:24px;color:var(--green)">${d.bonds ?? 0}</div>
                        <div class="label">债券余额</div>
                    </div>
                    <div class="stat-box" style="border-left:3px solid var(--yellow)">
                        <div class="value" style="font-size:24px;color:var(--yellow)">${d.points ?? 0}</div>
                        <div class="label">积分</div>
                    </div>
                    <div class="stat-box" style="border-left:3px solid var(--purple)">
                        <div class="value" style="font-size:24px;color:var(--purple)">${loginTime}</div>
                        <div class="label">最后登录时间</div>
                    </div>
                    <div class="stat-box" style="border-left:3px solid #58a6ff">
                        <div class="value" style="font-size:24px;color:#58a6ff">${d.total_online_days ?? 0} 天</div>
                        <div class="label">累计在线天数</div>
                    </div>
                    <div class="stat-box" style="border-left:3px solid #3fb950">
                        <div class="value" style="font-size:24px;color:#3fb950">${d.total_checkin_days ?? 0} 天</div>
                        <div class="label">累计签到天数</div>
                    </div>
                </div>

                <div class="card" style="margin-top:12px">
                    <h2>详细信息</h2>
                    <table class="table" style="margin-top:8px">
                        <tr><td style="color:var(--dim);width:120px">累计在线时长</td><td>${onlineText}</td></tr>
                        <tr><td style="color:var(--dim)">礼包阶段</td><td>${d.gift_stage ?? 0}</td></tr>
                    </table>
                </div>`;
        } catch (e) {
            el.innerHTML = `<div class="card" style="border-color:var(--red)"><h2>加载异常</h2><p style="color:var(--dim);margin-top:8px">${e.message}</p></div>`;
        }
    }

    // ===== CDK =====
    function renderCDK(el) {
        el.innerHTML = `
            <div class="card">
                <h2>CDK兑换</h2>
                <p style="color:var(--dim);font-size:13px;margin-bottom:12px">输入CDK兑换码，获得债券奖励</p>
                ${IS_PREVIEW ? '<div class="preview-badge" style="margin-bottom:12px">预览模式 - 兑换不会生效</div>' : ''}
                <div class="cdk-input" style="flex-direction:column;gap:8px">
                    <input type="text" id="cdkCode" placeholder="请输入CDK兑换码..." maxlength="64">
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
            document.getElementById('cdkResult').innerHTML = `<div class="card" style="border-color:var(--green)"><h2 style="color:var(--green)">兑换成功!</h2><p>获得 ${data.data.amount} 债券</p><p>当前余额: ${data.data.balance_after} 债券</p><p style="color:var(--dim);font-size:12px;margin-top:8px">数据已同步，请刷新页面查看最新余额</p></div>`;
            // CDK兑换成功后 → 通知Java同步 + 3秒刷新余额
            setTimeout(() => notifyJavaSync(), 500);
            setTimeout(() => doQueryBalance(), 3000);
        } else {
            document.getElementById('cdkResult').innerHTML = `<div class="card" style="border-color:var(--red)"><h2 style="color:var(--red)">兑换失败</h2><p>${data.message}</p></div>`;
        }
    }

    // ===== 余额 =====
    function renderBalance(el) {
        if (!currentPlayer || (!AUTHENTICATED && NEED_PASSWORD)) {
            el.innerHTML = '<div class="card"><h2>余额查询</h2><div style="text-align:center;padding:40px 20px"><div style="font-size:48px;margin-bottom:16px">🔒</div><p style="color:var(--dim);font-size:14px;margin-bottom:20px">请先登录游戏</p></div></div>';
            return;
        }
        el.innerHTML = `
            <div class="card">
                <h2>余额查询</h2>
                <div id="balanceResult" style="margin-top:16px"></div>
            </div>`;
        doQueryBalance();
    }

    async function doQueryBalance() {
        if (!currentPlayer) return;
        try {
            const url = new URL(API + 'balance.php', location.href);
            url.searchParams.set('action', 'query');
            url.searchParams.set('player', currentPlayer);
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
        } catch(e) {
            document.getElementById('balanceResult').innerHTML = '<div class="card" style="border-color:var(--red)">查询失败: '+e.message+'</div>';
        }
    }

    // ===== 账号 =====
    function renderAccount(el) {
        if (IS_PREVIEW || (!AUTHENTICATED && NEED_PASSWORD)) {
            el.innerHTML = `<div class="card"><h2>账号信息</h2><div style="text-align:center;padding:40px 20px"><div style="font-size:48px;margin-bottom:16px">🔒</div><p style="color:var(--dim);font-size:14px;margin-bottom:20px">游客模式下无法查看账号信息</p><p style="color:var(--dim);font-size:12px">请先登录游戏获取Web访问令牌</p></div></div>`;
            return;
        }
        if (!currentPlayer) {
            el.innerHTML = '<div class="card"><h2>账号信息</h2><div style="text-align:center;padding:40px 20px"><div style="font-size:48px;margin-bottom:16px">🔒</div><p style="color:var(--dim);font-size:14px;margin-bottom:20px">请先登录游戏</p></div></div>';
            return;
        }
        el.innerHTML = `
            <div class="card">
                <h2>账号信息</h2>
                <div id="accountResult" style="margin-top:16px"></div>
            </div>`;
        doQueryAccount();
    }

    async function doQueryAccount() {
        if (!currentPlayer) return;
        const res = await api('register.php', {action: 'query', player: currentPlayer, token: TOKEN});
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

    // ===== 主题切换 =====
    function toggleLightTheme() {
        document.body.classList.toggle('light-theme');
        const isLight = document.body.classList.contains('light-theme');
        localStorage.setItem('sdf1_player_theme', isLight ? 'light' : 'dark');
        toast(isLight ? '浅色主题' : '深色主题', 'success');
    }

    function showThemePicker() {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:2000';
        overlay.id = 'themePickerOverlay';
        overlay.innerHTML = `
            <div id="themePickerModal" style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:500px;max-width:90%;position:relative">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                    <h3 style="margin:0">🎨 选择背景颜色</h3>
                    <button onclick="document.getElementById('themePickerOverlay').remove()" style="background:none;border:none;color:var(--dim);font-size:20px;cursor:pointer">✕</button>
                </div>
                <div class="theme-picker" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px">
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#0d1117;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#0d1117')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#1a1e2e;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#1a1e2e')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#0f4c75;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#0f4c75')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#1b2631;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#1b2631')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#2c3e50;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#2c3e50')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#23272a;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#23272a')"></div>
                    <div class="color-btn" style="width:36px;height:36px;border-radius:6px;background:#ffffff;border:2px solid var(--border);cursor:pointer" onclick="setPlayerTheme('#ffffff')"></div>
                </div>
                <div style="margin-top:16px">
                    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
                        <span style="color:var(--dim);font-size:12px;white-space:nowrap">R</span>
                        <input type="number" id="rgbR" min="0" max="255" placeholder="0-255" style="width:70px;padding:6px 8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--text);font-size:13px;text-align:center">
                        <span style="color:var(--dim);font-size:12px;white-space:nowrap">G</span>
                        <input type="number" id="rgbG" min="0" max="255" placeholder="0-255" style="width:70px;padding:6px 8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--text);font-size:13px;text-align:center">
                        <span style="color:var(--dim);font-size:12px;white-space:nowrap">B</span>
                        <input type="number" id="rgbB" min="0" max="255" placeholder="0-255" style="width:70px;padding:6px 8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--text);font-size:13px;text-align:center">
                        <span style="color:var(--dim);font-size:11px">或</span>
                        <input type="text" id="customColor" placeholder="#1a237e" class="color-input" style="flex:1;min-width:120px">
                    </div>
                </div>
                <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                    <button class="btn" onclick="document.getElementById('themePickerOverlay').remove()">取消</button>
                    <button class="btn btn-blue" onclick="applyPlayerCustomColor()">应用</button>
                </div>
            </div>`;
        document.body.appendChild(overlay);
        overlay.addEventListener('click', (e) => { if (e.target === overlay) overlay.remove(); });
    }

    function setPlayerTheme(color) {
        document.documentElement.style.setProperty('--bg', color);
        document.body.style.background = color;
        localStorage.setItem('sdf1_player_bg', color);
        toast('背景颜色已应用', 'success');
    }

    // 浅色主题下的卡片/边框颜色也自动调整
    document.querySelectorAll('.light-theme').forEach(el => {});

    // 自动切换card/border颜色配合自定义背景
    function applyBgToTheme(bgColor) {
        // 如果背景是浅色，自动切换card颜色
        document.body.classList.add('light-theme');
        document.body.style.background = bgColor;
        document.documentElement.style.setProperty('--bg', bgColor);
    }

    function applyPlayerCustomColor() {
        let rVal = document.getElementById('rgbR').value.trim();
        let gVal = document.getElementById('rgbG').value.trim();
        let bVal = document.getElementById('rgbB').value.trim();
        const hexVal = document.getElementById('customColor').value.trim();
        
        let color = null;
        
        if (hexVal) {
            if (/^[0-9A-Fa-f]{6}$/.test(hexVal)) {
                color = '#' + hexVal;
            } else if (/^#[0-9A-Fa-f]{6}$/.test(hexVal)) {
                color = hexVal;
            } else {
                toast('请输入有效的十六进制颜色，如 1a237e 或 #1a237e', 'err');
                return;
            }
        }
        else if (rVal !== '' || gVal !== '' || bVal !== '') {
            if (rVal === '') rVal = 0;
            if (gVal === '') gVal = 0;
            if (bVal === '') bVal = 0;
            const r = parseInt(rVal);
            const g = parseInt(gVal);
            const b = parseInt(bVal);
            if (r >= 0 && r <= 255 && g >= 0 && g <= 255 && b >= 0 && b <= 255) {
                color = 'rgb(' + r + ',' + g + ',' + b + ')';
            } else {
                toast('RGB 值必须在 0-255 之间', 'err');
                return;
            }
        }
        else {
            toast('请输入十六进制颜色（如 1a237e）或填写任意一个 RGB 值', 'err');
            return;
        }
        
        if (color) {
            setPlayerTheme(color);
            document.querySelector('[onclick*="overlay.remove"]').click();
        }
    }

    // 加载保存的主题
    (function() {
        const savedBg = localStorage.getItem('sdf1_player_bg');
        if (savedBg) {
            document.documentElement.style.setProperty('--bg', savedBg);
            document.body.style.background = savedBg;
        }
    })();

    function closeModal() {
        document.getElementById('modalOverlay').style.display = 'none';
        // ★ 密码验证被取消 → 清除token，进入游客模式（防止未认证玩家数据泄露）
        if (NEED_PASSWORD && !AUTHENTICATED) {
            showGuestMode();
        }
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

    // ===== 工单系统 =====
    let ticketState = { view: 'list', filter: 'all' };

    async function renderTicket(el) {
        if (IS_PREVIEW || !AUTHENTICATED) {
            el.innerHTML = `<div class="card"><h2>📋 工单系统</h2><p style="color:var(--dim)">请先登录后使用工单功能</p></div>`;
            return;
        }
        if (ticketState.view === 'list') await renderTicketList(el);
        else if (ticketState.view === 'create') renderTicketCreate(el);
        else if (ticketState.view === 'detail') await renderTicketDetail(el, ticketState.ticketId);
    }

    function renderTicketNav() {
        const f = ticketState.filter;
        return `<div style="display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap;align-items:center">
            <button class="btn btn-primary" onclick="ticketState.view='create';renderTicket(document.getElementById('content'))" style="font-size:13px">✏️ 提交工单</button>
            <button class="btn ${f==='all'?'':'btn-dim'}" onclick="ticketState.filter='all';ticketState.view='list';renderTicket(document.getElementById('content'))" style="font-size:12px;padding:5px 12px">全部</button>
            <button class="btn ${f==='submitted'?'':'btn-dim'}" onclick="ticketState.filter='submitted';ticketState.view='list';renderTicket(document.getElementById('content'))" style="font-size:12px;padding:5px 12px">⏳ 已提交</button>
            <button class="btn ${f==='replied'?'':'btn-dim'}" onclick="ticketState.filter='replied';ticketState.view='list';renderTicket(document.getElementById('content'))" style="font-size:12px;padding:5px 12px">💬 已回复</button>
            <button class="btn ${f==='withdrawn'?'':'btn-dim'}" onclick="ticketState.filter='withdrawn';ticketState.view='list';renderTicket(document.getElementById('content'))" style="font-size:12px;padding:5px 12px">🔙 已撤销</button>
            <button class="btn ${f==='rejected'?'':'btn-dim'}" onclick="ticketState.filter='rejected';ticketState.view='list';renderTicket(document.getElementById('content'))" style="font-size:12px;padding:5px 12px">🚫 已驳回</button>
        </div>`;
    }

    async function renderTicketList(el) {
        el.innerHTML = `<div class="card"><h2>📋 工单系统</h2>${renderTicketNav()}<p style="color:var(--dim)">加载中...</p></div>`;
        try {
            const url = new URL(API + 'ticket.php', location.href);
            url.searchParams.set('action', 'my_list');
            url.searchParams.set('token', TOKEN);
            if (ticketState.filter !== 'all') url.searchParams.set('status', ticketState.filter);
            const res = await fetch(url);
            const data = await res.json();
            if (!data.success) { el.innerHTML = `<div class="card"><h2>📋 工单系统</h2><p style="color:var(--red)">${data.message}</p></div>`; return; }

            const list = data.data.list;
            let html = `<div class="card"><h2>📋 我的工单 (${data.data.total})</h2>${renderTicketNav()}`;
            if (list.length === 0) {
                html += `<p style="color:var(--dim);text-align:center;padding:40px 0">暂无工单</p>`;
            } else {
                const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
                const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},completed:{text:'✅ 已完结',color:'var(--green)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
                html += `<div style="display:flex;flex-direction:column;gap:8px">`;
                for (const t of list) {
                    const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
                    const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
                    html += `<div onclick="ticketState.view='detail';ticketState.ticketId=${t.id};renderTicket(document.getElementById('content'))" style="background:rgba(88,166,255,0.05);border:1px solid var(--border);border-radius:8px;padding:14px 16px;cursor:pointer;transition:all 0.2s" onmouseover="this.style.borderColor='var(--accent)'" onmouseout="this.style.borderColor='var(--border)'">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
                            <span style="font-weight:600;font-size:14px">#${t.id} ${escHtml(t.title)}</span>
                            <span style="color:${s.color};font-size:12px">${s.text}</span>
                        </div>
                        <div style="display:flex;gap:16px;font-size:12px;color:var(--dim)">
                            <span>${typeMap[t.type]||t.type}</span>
                            <span>${date}</span>
                            ${t.assigned_to ? '<span>👤 '+escHtml(t.assigned_to)+'</span>' : ''}
                        </div>
                    </div>`;
                }
                html += `</div>`;
            }
            html += `</div>`;
            el.innerHTML = html;
        } catch (e) {
            el.innerHTML = `<div class="card"><h2>📋 工单系统</h2><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
        }
    }

    function renderTicketCreate(el) {
        const types = [{v:'bug',l:'🐛 Bug反馈'},{v:'help',l:'❓ 求助'},{v:'report',l:'📢 举报'},{v:'apply',l:'📝 申请'},{v:'other',l:'📎 其他'}];
        let options = types.map(t => `<option value="${t.v}">${t.l}</option>`).join('');
        el.innerHTML = `<div class="card">
            <h2>✏️ 提交工单</h2>
            <div style="margin-bottom:12px"><label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">工单类型</label>
                <select id="ticketType" style="width:100%;padding:10px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:14px">${options}</select></div>
            <div style="margin-bottom:12px"><label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">标题</label>
                <input id="ticketTitle" placeholder="简要描述您的问题" style="width:100%;padding:10px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:14px"></div>
            <div style="margin-bottom:12px"><label style="color:var(--dim);font-size:13px;display:block;margin-bottom:4px">详细描述</label>
                <textarea id="ticketDesc" rows="6" placeholder="详细描述您的问题，支持Markdown格式" style="width:100%;padding:10px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:14px;resize:vertical;font-family:monospace"></textarea></div>
            <p style="color:var(--dim);font-size:12px;margin-bottom:12px">💡 支持标准Markdown语法：**粗体**、*斜体*、\`代码\`、列表、标题等</p>
            <div style="display:flex;gap:8px">
                <button class="btn btn-primary" onclick="submitTicket()">提交工单</button>
                <button class="btn" onclick="ticketState.view='list';renderTicket(document.getElementById('content'))">取消</button>
            </div>
            <p id="ticketError" style="color:var(--red);margin-top:8px;font-size:13px"></p>
        </div>`;
    }

    async function submitTicket() {
        const type = document.getElementById('ticketType').value;
        const title = document.getElementById('ticketTitle').value.trim();
        const desc = document.getElementById('ticketDesc').value.trim();
        const errEl = document.getElementById('ticketError');
        if (!title) { errEl.textContent = '请输入标题'; return; }
        if (!desc) { errEl.textContent = '请输入描述'; return; }

        try {
            const url = new URL(API + 'ticket.php', location.href);
            url.searchParams.set('action', 'create');
            url.searchParams.set('token', TOKEN);
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({type, title, description: desc})
            });
            const data = await res.json();
            if (data.success) {
                ticketState.view = 'list';
                renderTicket(document.getElementById('content'));
            } else {
                errEl.textContent = data.message;
            }
        } catch (e) {
            errEl.textContent = '提交失败: ' + e.message;
        }
    }

    async function renderTicketDetail(el, id) {
        el.innerHTML = `<div class="card"><p style="color:var(--dim)">加载中...</p></div>`;
        try {
            const url = new URL(API + 'ticket.php', location.href);
            url.searchParams.set('action', 'detail');
            url.searchParams.set('token', TOKEN);
            url.searchParams.set('id', id);
            const res = await fetch(url);
            const data = await res.json();
            if (!data.success) { el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.message}</p><button class="btn" onclick="ticketState.view='list';renderTicket(document.getElementById('content'))">返回</button></div>`; return; }

            const t = data.data;
            const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
            const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},completed:{text:'✅ 已完结',color:'var(--green)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
            const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
            const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
            const canReply = !['withdrawn','rejected','completed'].includes(t.status);

            let html = `<div class="card">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                    <h2 style="margin:0">#${t.id} ${escHtml(t.title)}</h2>
                    <span style="color:${s.color};font-size:13px">${s.text}</span>
                </div>
                <div style="display:flex;gap:16px;font-size:13px;color:var(--dim);margin-bottom:16px">
                    <span>${typeMap[t.type]||t.type}</span>
                    <span>👤 ${escHtml(t.requester)}</span>
                    <span>📅 ${date}</span>
                    ${t.assigned_to ? '<span>🧑‍💻 '+escHtml(t.assigned_to)+'</span>' : ''}
                </div>`;

            // 描述
            html += `<div style="background:rgba(88,166,255,0.05);border:1px solid var(--border);border-radius:8px;padding:16px;margin-bottom:16px">
                <div style="color:var(--dim);font-size:12px;margin-bottom:8px">描述</div>
                <div style="font-size:14px;line-height:1.6">${renderMarkdown(t.description || '无描述')}</div>
            </div>`;

            // 驳回原因
            if (t.reject_reason) {
                html += `<div style="background:rgba(248,81,73,0.1);border:1px solid var(--red);border-radius:8px;padding:16px;margin-bottom:16px">
                    <div style="color:var(--red);font-size:12px;margin-bottom:8px">🚫 驳回原因</div>
                    <div style="font-size:14px">${renderMarkdown(t.reject_reason)}</div>
                </div>`;
            }

            // 回复列表
            html += `<div style="margin-bottom:16px"><div style="color:var(--dim);font-size:12px;margin-bottom:8px">💬 回复记录 (${t.replies ? t.replies.length : 0})</div>`;
            if (t.replies && t.replies.length > 0) {
                for (const r of t.replies) {
                    const rDate = new Date(r.created_at * 1000).toLocaleString('zh-CN');
                    const roleColors = {user:'var(--accent)',admin:'var(--red)',provider:'var(--green)'};
                    const roleNames = {user:'玩家',admin:'管理员',provider:'服务商'};
                    const bgColor = r.role === 'admin' ? 'rgba(248,81,73,0.08)' : r.role === 'provider' ? 'rgba(63,185,80,0.08)' : 'rgba(88,166,255,0.08)';
                    html += `<div style="background:${bgColor};border-radius:8px;padding:12px 16px;margin-bottom:8px">
                        <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:6px">
                            <span style="font-size:13px"><span style="color:${roleColors[r.role]||'var(--text)'};font-weight:600">${escHtml(r.sender)}</span> <span style="color:var(--dim);font-size:11px">(${roleNames[r.role]||r.role})</span></span>
                            <span style="color:var(--dim);font-size:11px">${rDate}</span>
                        </div>
                        <div style="font-size:14px;line-height:1.6">${renderMarkdown(r.message)}</div>
                    </div>`;
                }
            } else {
                html += `<p style="color:var(--dim);font-size:13px">暂无回复</p>`;
            }
            html += `</div>`;

            // 回复框
            if (canReply) {
                html += `<div style="margin-bottom:16px">
                    <textarea id="ticketReplyMsg" rows="3" placeholder="输入回复内容（支持Markdown）" style="width:100%;padding:10px;border-radius:6px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:14px;resize:vertical;font-family:monospace"></textarea>
                    <p id="ticketReplyError" style="color:var(--red);font-size:12px;margin-top:4px"></p>
                </div>`;
            }

            // 操作按钮
            html += `<div style="display:flex;gap:8px;flex-wrap:wrap">
                <button class="btn" onclick="ticketState.view='list';renderTicket(document.getElementById('content'))">← 返回列表</button>`;
            if (canReply) {
                html += `<button class="btn btn-primary" onclick="submitTicketReply(${t.id})">发送回复</button>`;
            }
            if (t.status === 'submitted' || t.status === 'replied') {
                html += `<button class="btn" style="color:var(--yellow);border-color:var(--yellow)" onclick="withdrawTicket(${t.id})">撤销工单</button>`;
            }
            html += `</div></div>`;

            el.innerHTML = html;
        } catch (e) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p><button class="btn" onclick="ticketState.view='list';renderTicket(document.getElementById('content'))">返回</button></div>`;
        }
    }

    async function submitTicketReply(id) {
        const msg = document.getElementById('ticketReplyMsg').value.trim();
        const errEl = document.getElementById('ticketReplyError');
        if (!msg) { errEl.textContent = '请输入回复内容'; return; }

        try {
            const url = new URL(API + 'ticket.php', location.href);
            url.searchParams.set('action', 'reply');
            url.searchParams.set('token', TOKEN);
            url.searchParams.set('id', id);
            const res = await fetch(url, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({message: msg})
            });
            const data = await res.json();
            if (data.success) {
                renderTicket(document.getElementById('content'));
            } else {
                errEl.textContent = data.message;
            }
        } catch (e) {
            errEl.textContent = '发送失败: ' + e.message;
        }
    }

    async function withdrawTicket(id) {
        if (!confirm('确定要撤销此工单吗？')) return;
        try {
            const url = new URL(API + 'ticket.php', location.href);
            url.searchParams.set('action', 'withdraw');
            url.searchParams.set('token', TOKEN);
            url.searchParams.set('id', id);
            const res = await fetch(url);
            const data = await res.json();
            if (data.success) {
                ticketState.view = 'list';
                renderTicket(document.getElementById('content'));
            } else {
                alert(data.message);
            }
        } catch (e) {
            alert('撤销失败: ' + e.message);
        }
    }

    // ★ 简易Markdown渲染器
    function renderInlineMd(text) {
        let s = text;
        s = s.replace(/^######\s+(.+)$/, '<h6 style="margin:2px 0;font-size:12px">$1</h6>');
        s = s.replace(/^#####\s+(.+)$/, '<h5 style="margin:3px 0;font-size:13px">$1</h5>');
        s = s.replace(/^####\s+(.+)$/, '<h4 style="margin:3px 0 2px;font-size:14px">$1</h4>');
        s = s.replace(/^###\s+(.+)$/, '<h4 style="margin:4px 0 3px;font-size:14px">$1</h4>');
        s = s.replace(/^##\s+(.+)$/, '<h3 style="margin:6px 0 4px;font-size:15px">$1</h3>');
        s = s.replace(/^#\s+(.+)$/, '<h2 style="margin:8px 0 5px;font-size:16px">$1</h2>');
        s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
        s = s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
        s = s.replace(/\*(.+?)\*/g, '<i>$1</i>');
        s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;border-radius:6px;margin:4px 0">');
        s = s.replace(/(?<!!)\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" style="color:var(--accent)">$1</a>');
        return s;
    }

    function renderMarkdown(text) {
        if (!text) return '';
        text = text.replace(/\r/g, '');
        try {
        let s = escHtml(text);
        // 1. 代码块提取保护
        let cbs = [];
        s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, function(m, lang, code) {
            let idx = cbs.length;
            cbs.push('<pre style="background:rgba(88,166,255,0.1);padding:12px;border-radius:6px;overflow-x:auto;font-size:13px;white-space:pre-wrap">' + code.replace(/\n$/, '') + '</pre>');
            return '\x00CB' + idx + '\x00';
        });
        // 2. 行内代码保护
        s = s.replace(/`([^`]+)`/g, '<code style="background:rgba(88,166,255,0.15);padding:2px 6px;border-radius:3px;font-size:13px">$1</code>');
        // 3. 引用块处理：提取连续引用行，构建嵌套结构
        let lines = s.split('\n');
        let result = [];
        let bqBuf = [];
        function flushBq() {
            if (bqBuf.length === 0) return;
            let html = '';
            let openDivs = 0;
            for (let bi = 0; bi < bqBuf.length; bi++) {
                let {level, content} = bqBuf[bi];
                let prevLevel = (bi > 0) ? bqBuf[bi - 1].level : 0;
                if (level > prevLevel) {
                    for (let j = prevLevel; j < level; j++) {
                        let pad = Math.min((j + 1) * 8, 32);
                        html += '<div style="border-left:3px solid var(--accent);padding:4px 10px;margin:2px 0 2px ' + pad + 'px;background:rgba(255,255,255,0.03)">';
                        openDivs++;
                    }
                } else if (level < prevLevel) {
                    for (let j = prevLevel; j > level; j--) { html += '</div>'; openDivs--; }
                } else if (bi > 0) {
                    html += '<br>';
                }
                let text = renderInlineMd(content);
                if (!text.trim()) text = '&nbsp;';
                html += text;
            }
            for (let j = 0; j < openDivs; j++) html += '</div>';
            result.push(html);
            bqBuf = [];
        }
        for (let i = 0; i < lines.length; i++) {
            let m = lines[i].match(/^((?:&gt;[ \t]*)+)(.*)$/);
            if (m) {
                bqBuf.push({ level: (m[1].match(/&gt;/g) || []).length, content: m[2] });
            } else if (bqBuf.length > 0 && !lines[i].trim()) {
                // ★ 空行在引用块内：向前看下一行是否仍是引用
                let isContinuation = false;
                for (let j = i + 1; j < lines.length; j++) {
                    if (lines[j].trim()) {
                        isContinuation = /^((?:&gt;[ \t]*)+)(.*)$/.test(lines[j]);
                        break;
                    }
                }
                if (isContinuation) {
                    bqBuf.push({ level: bqBuf[bqBuf.length - 1].level, content: '' });
                } else {
                    flushBq();
                }
            } else {
                flushBq();
                result.push(lines[i]);
            }
        }
        flushBq();
        s = result.join('\n');
        // 4. 非引用行的MD处理
        s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
        s = s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
        s = s.replace(/\*(.+?)\*/g, '<i>$1</i>');
        s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;border-radius:6px;margin:6px 0">');
        s = s.replace(/(?<!!)\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" style="color:var(--accent)">$1</a>');
        s = s.replace(/^######\s+(.+)$/gm, '<h6 style="margin:4px 0;font-size:12px">$1</h6>');
        s = s.replace(/^#####\s+(.+)$/gm, '<h5 style="margin:5px 0;font-size:13px">$1</h5>');
        s = s.replace(/^####\s+(.+)$/gm, '<h4 style="margin:6px 0 4px;font-size:14px">$1</h4>');
        s = s.replace(/^###\s+(.+)$/gm, '<h4 style="margin:8px 0 4px;font-size:14px">$1</h4>');
        s = s.replace(/^##\s+(.+)$/gm, '<h3 style="margin:10px 0 6px;font-size:15px">$1</h3>');
        s = s.replace(/^#\s+(.+)$/gm, '<h2 style="margin:12px 0 8px;font-size:16px">$1</h2>');
        // 5. 表格
        let inTable = false;
        let tableHtml = '';
        let tLines = s.split('\n');
        let tResult = [];
        for (let i = 0; i < tLines.length; i++) {
            let line = tLines[i].trim();
            if (line.match(/^\|(.+)\|$/)) {
                if (line.match(/^\|[\s\-:|]+\|$/)) { inTable = true; continue; }
                if (!inTable && i + 1 < tLines.length && tLines[i+1].trim().match(/^\|[\s\-:|]+\|$/)) {
                    inTable = true;
                    let cells = line.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
                    tableHtml = '<table style="border-collapse:collapse;width:100%;margin:8px 0;font-size:13px"><tr style="background:rgba(88,166,255,0.15)">';
                    for (let c of cells) tableHtml += '<th style="border:1px solid var(--border);padding:6px 10px;text-align:left">' + c + '</th>';
                    tableHtml += '</tr>';
                    continue;
                }
                if (inTable) {
                    let cells = line.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
                    tableHtml += '<tr>';
                    for (let c of cells) tableHtml += '<td style="border:1px solid var(--border);padding:6px 10px">' + c + '</td>';
                    tableHtml += '</tr>';
                    continue;
                }
            }
            if (inTable && tableHtml) {
                tableHtml += '</table>';
                tResult.push(tableHtml);
                tableHtml = '';
                inTable = false;
            }
            tResult.push(tLines[i]);
        }
        if (tableHtml) { tableHtml += '</table>'; tResult.push(tableHtml); }
        s = tResult.join('\n');
        // 6. 列表、分割线、换行
        s = s.replace(/^- (.+)$/gm, '<li style="margin-left:20px;font-size:13px">$1</li>');
        s = s.replace(/^---$/gm, '<hr style="border:none;border-top:1px solid var(--border);margin:12px 0">');
        s = s.replace(/\n/g, '<br>');
        // 7. 还原代码块
        for (let i = 0; i < cbs.length; i++) {
            s = s.replace('\x00CB' + i + '\x00', cbs[i]);
        }
        return s;
        } catch(e) { console.error('[MD_RENDER] Error:', e); return escHtml(text).replace(/\n/g, '<br>'); }
    }

function escHtml(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

    </script>
</body>
</html>
