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
            border-radius: 12px; font-size: 14px; z-index: 200;
            animation: slideIn 0.3s ease;
            backdrop-filter: blur(16px) saturate(180%); -webkit-backdrop-filter: blur(16px) saturate(180%);
            border: 1px solid rgba(88,166,255,0.2);
            box-shadow: 0 8px 32px rgba(0,0,0,0.3);
        }
        .toast.success { background: rgba(63,185,80,0.85); color: #fff; }
        .toast.error { background: rgba(248,81,73,0.85); color: #fff; }
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
        /* ★ 毛玻璃Alert弹窗 */
        .glass-alert-overlay {
            display: none; position: fixed; top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(0,0,0,0.45);
            backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px);
            z-index: 5000; justify-content: center; align-items: center;
            animation: glassFadeIn 0.25s ease;
        }
        .glass-alert-overlay.show { display: flex; }
        .glass-alert-card {
            background: rgba(22,27,34,0.92);
            backdrop-filter: blur(24px); -webkit-backdrop-filter: blur(24px);
            border: 1px solid rgba(88,166,255,0.25);
            border-radius: 16px; padding: 28px 28px 20px; width: 360px; max-width: 88%;
            box-shadow: 0 12px 48px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.05);
            animation: glassSlideUp 0.3s ease;
            text-align: center;
        }
        .glass-alert-card .alert-icon { font-size: 36px; margin-bottom: 12px; }
        .glass-alert-card .alert-msg { font-size: 14px; color: #e6edf3; line-height: 1.6; margin-bottom: 20px; word-break: break-word; }
        .glass-alert-card .alert-btns { display: flex; gap: 10px; justify-content: center; }
        .glass-alert-card .alert-btns button {
            padding: 8px 24px; border: none; border-radius: 8px; font-size: 13px; font-weight: 600;
            cursor: pointer; transition: all 0.2s;
        }
        .glass-alert-card .alert-btns .ag-ok { background: #58a6ff; color: #fff; }
        .glass-alert-card .alert-btns .ag-cancel { background: rgba(255,255,255,0.08); color: #8b949e; }
        .glass-alert-card .alert-btns button:hover { opacity: 0.85; transform: scale(1.03); }
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
            <div class="sidebar-item" data-page="dashboard" onclick="switchPage('dashboard')">📊 个人中心</div>
            <div class="sidebar-item" data-page="shop" onclick="switchPage('shop')">🛒 商城</div>
            <div class="sidebar-item" data-page="lands" onclick="switchPage('lands')">🏡 领地管理</div>
            <div class="sidebar-item" data-page="cdk" onclick="switchPage('cdk')">🎁 CDK兑换</div>
            <div class="sidebar-item" data-page="balance" onclick="switchPage('balance')">💰 余额查询</div>
            <div class="sidebar-item" data-page="recharge" onclick="switchPage('recharge')">💳 在线充值</div>
            <div class="sidebar-item" data-page="groups" onclick="switchPage('groups')">👥 用户组</div>
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

    <!-- ★ 毛玻璃Alert弹窗 -->
    <div class="glass-alert-overlay" id="glassAlertOverlay">
        <div class="glass-alert-card" onclick="event.stopPropagation()">
            <div class="alert-icon" id="glassAlertIcon">⚠️</div>
            <div class="alert-msg" id="glassAlertMsg"></div>
            <div class="alert-label" id="glassAlertLabel" style="display:none;text-align:left;font-size:13px;color:var(--fg);margin-bottom:4px"></div>
            <input type="text" id="glassAlertInput" style="display:none;width:100%;box-sizing:border-box;padding:8px 12px;border:1px solid var(--border);border-radius:8px;background:var(--bg);color:var(--fg);font-size:14px;margin-bottom:8px;outline:none" onkeydown="if(event.key==='Enter')glassAlertResolve(true)">
            <div class="alert-hint" id="glassAlertHint" style="display:none;font-size:12px;color:var(--dim);margin-bottom:8px"></div>
            <div class="alert-btns" id="glassAlertBtns">
                <button class="ag-ok" onclick="glassAlertResolve(true)">确定</button>
            </div>
        </div>
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
                        // 支付回跳 ?paid=1 直接进入充值页确认到账
                        if (new URLSearchParams(location.search).get('paid') === '1') {
                            switchPage('recharge');
                        } else {
                            switchPage('dashboard');
                        }
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
        document.getElementById('authError').textContent = '正在提交登录请求...';
        try {
            // ★ 必须走web_login_request流程（Java验证密码），不能用verify_web_password（PHP直接验证=绕过Java）
            const reqRes = await fetch(API + 'sync.php?action=web_login_request', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({player: currentPlayer, password: password})
            });
            const reqData = await reqRes.json();
            if (!reqData.success) {
                document.getElementById('authError').textContent = reqData.message || '提交失败';
                return;
            }
            const requestId = reqData.data.request_id;
            document.getElementById('authError').textContent = '等待游戏服务器验证密码...';

            // 轮询结果
            let attempts = 0;
            const pollInterval = setInterval(async () => {
                attempts++;
                if (attempts > 60) {
                    clearInterval(pollInterval);
                    document.getElementById('authError').textContent = '验证超时，请稍后重试';
                    return;
                }
                try {
                    const pollRes = await fetch(API + 'sync.php?action=check_web_login_result&player=' + encodeURIComponent(currentPlayer) + '&request_id=' + requestId);
                    const pollData = await pollRes.json();
                    if (pollData.success && pollData.data) {
                        const result = pollData.data;
                        if (result.status === 'success') {
                            clearInterval(pollInterval);
                            AUTHENTICATED = true;
                            NEED_PASSWORD = false;
                            const newToken = result.token;
                            TOKEN = newToken;
                            localStorage.setItem('sdf1_token', newToken);
                            localStorage.setItem('sdf1_player', currentPlayer);
                            document.getElementById('playerInfo').textContent = '已登录: ' + currentPlayer;
                            closeModal();
                            toast('验证成功', 'success');
                            switchPage(currentPage);
                        } else if (result.status === 'failed') {
                            clearInterval(pollInterval);
                            document.getElementById('authError').textContent = result.message || '密码错误';
                        }
                    }
                } catch (e) { /* 继续轮询 */ }
            }, 1000);
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
        else if (page === 'lands') renderLands(c);
        else if (page === 'cdk') renderCDK(c);
        else if (page === 'balance') renderBalance(c);
        else if (page === 'groups') renderGroups(c);
        else if (page === 'account') renderAccount(c);
        else if (page === 'ticket') renderTicket(c);
        else if (page === 'recharge') renderRecharge(c);
    }

    // ==================== 债券在线充值（动态加载商品） ====================
    function renderRecharge(el) {
        // 先显示加载状态
        el.innerHTML = `
            <div class="card" style="max-width:520px;margin:0 auto">
                <h2 style="margin:0 0 6px">💳 债券在线充值</h2>
                <p style="color:var(--dim);font-size:13px;margin:0 0 18px">支付成功后游戏内即时到账。</p>
                <div id="shopProductsContainer" style="margin-bottom:16px">
                    <div style="text-align:center;padding:20px;color:var(--dim)">加载商品中...</div>
                </div>
                <button id="rechargeBtn" class="btn btn-primary" style="width:100%;font-size:15px;padding:12px;display:none" onclick="startRecharge()">支付</button>
                <div id="rechargeStatus" style="margin-top:14px;font-size:13px;min-height:20px"></div>
                <p style="color:var(--dim);font-size:12px;margin-top:14px;line-height:1.6">说明：点击后跳转至支付页面完成付款，浏览器会自动跳回本页并自动确认到账。若未自动到账，可点击下方按钮手动查询。</p>
                <button id="rechargeQueryBtn" class="btn" style="width:100%;margin-top:8px;display:none" onclick="pollRechargeOrder(true)">🔄 查询到账状态</button>
            </div>
        `;

        // 加载商品列表
        loadShopProducts();

        // 处理支付回跳 ?paid=1
        const paid = new URLSearchParams(location.search).get('paid');
        const lastNo = localStorage.getItem('sdf1_last_recharge_no');
        if (paid === '1') {
            const st = document.getElementById('rechargeStatus');
            if (lastNo) {
                st.innerHTML = '<span style="color:var(--accent)">检测到支付回跳，正在确认到账…</span>';
                document.getElementById('rechargeQueryBtn').style.display = 'block';
                pollRechargeOrder(false);
            } else {
                // ★ 兜底：localStorage丢失时，从服务器查最近订单
                st.innerHTML = '<span style="color:var(--accent)">已返回，正在从服务器查找订单…</span>';
                document.getElementById('rechargeQueryBtn').style.display = 'block';
                (async () => {
                    try {
                        const res = await api('pay.php', {action: 'find_recent_order'});
                        if (res.success && res.data && res.data.orders && res.data.orders.length > 0) {
                            const latest = res.data.orders[0];
                            localStorage.setItem('sdf1_last_recharge_no', latest.out_trade_no);
                            if (latest.status === 'paid') {
                                st.innerHTML = '<span style="color:var(--green)">✅ 此订单已支付完成，可以发起新订单。</span>';
                                localStorage.removeItem('sdf1_last_recharge_no');
                                const btn = document.getElementById('rechargeBtn');
                                btn.disabled = false; btn.textContent = '支付'; btn.onclick = startRecharge;
                            } else {
                                st.innerHTML = '<span style="color:var(--accent)">检测到订单 ' + latest.out_trade_no + '，正在确认到账…</span>';
                                pollRechargeOrder(false);
                            }
                        } else {
                            st.innerHTML = '<span style="color:var(--yellow)">已返回，但未找到本地订单记录。如已支付，请在「余额查询」确认或联系管理员。</span>';
                        }
                    } catch (e) {
                        st.innerHTML = '<span style="color:var(--yellow)">已返回，但未找到本地订单记录。如已支付，请在「余额查询」确认或联系管理员。</span>';
                    }
                })();
            }
            // 清除 URL 中的 paid 标记，避免刷新重复提示
            try {
                const u = new URL(location.href);
                u.searchParams.delete('paid');
                history.replaceState({}, '', u);
            } catch (e) {}
        } else if (lastNo) {
            // ★ 2026-07-15 续支付检测：有本地缓存订单号且无 paid 回跳，查是否仍在等待支付
            (async () => {
                try {
                    const res = await api('pay.php', {action: 'query_order', out_trade_no: lastNo});
                    if (res.success && res.data) {
                        const st = document.getElementById('rechargeStatus');
                        const btn = document.getElementById('rechargeBtn');
                        if (res.data.status === 'paid') {
                            // 已支付：清除本地记录，恢复按钮允许新订单
                            localStorage.removeItem('sdf1_last_recharge_no');
                            st.innerHTML = '<span style="color:var(--green)">✅ 此订单已支付完成，可以发起新订单。</span>';
                            btn.disabled = false;
                            btn.textContent = '支付';
                            btn.onclick = startRecharge;
                        } else if (res.data.status === 'created') {
                            // 检查订单是否已过期（5分钟）
                            const nowSec = Math.floor(Date.now() / 1000);
                            const createdAt = res.data.created_at || 0;
                            if (createdAt && (nowSec - createdAt) > 300) {
                                // 已过期：清除本地记录，重置按钮
                                localStorage.removeItem('sdf1_last_recharge_no');
                                st.innerHTML = '<span style="color:var(--yellow)">订单已过期（超过5分钟），请重新下单。</span>';
                                btn.disabled = false;
                                btn.textContent = '支付';
                                btn.onclick = startRecharge;
                            } else {
                            // 未支付：显示继续支付
                            btn.textContent = '🔄 继续支付 ¥' + (res.data.money || '0.01');
                            btn.onclick = async function() {
                                try {
                                    btn.disabled = true;
                                    btn.textContent = '⏳ 获取支付链接…';
                                    const r = await api('pay.php', {action: 'create_order'});
                                    if (r.success && r.data.pay_url) {
                                        localStorage.setItem('sdf1_last_recharge_no', r.data.out_trade_no);
                                        const payUrl = r.data.pay_url;
                                        st.innerHTML = '<span style="color:var(--green)">已打开支付页面…</span>';
                                        document.getElementById('rechargeQueryBtn').style.display = 'block';
                                        window.open(payUrl, '_blank');
                                        pollRechargeOrder(false);
                                    } else {
                                        st.innerHTML = '<span style="color:var(--red)">' + (r.message || '获取支付链接失败') + '</span>';
                                        btn.disabled = false;
                                        btn.textContent = '🔄 继续支付';
                                    }
                                } catch (e) {
                                    st.innerHTML = '<span style="color:var(--red)">请求异常: ' + e.message + '</span>';
                                    btn.disabled = false;
                                    btn.textContent = '🔄 继续支付';
                                }
                            };
                            st.innerHTML = '<span style="color:var(--yellow)">⚠️ 您有一笔未完成的订单，<a href="javascript:void(0)" onclick="document.getElementById(\'rechargeBtn\').click()" style="color:var(--accent)">点此继续支付</a></span>';
                            document.getElementById('rechargeQueryBtn').style.display = 'block';
                            }
                        }
                        // 如果是 expired 或其他状态，不处理，保持默认按钮
                    }
                } catch (e) {
                    localStorage.removeItem('sdf1_last_recharge_no');
                }
            })();
        }
    }

    // 加载商店商品列表
    async function loadShopProducts() {
        const container = document.getElementById('shopProductsContainer');
        // ★ 盲盒随机语录（带动消费情绪）
        const blindBoxQuotes = [
            '🎲 手气未知，欧皇可能就是你！',
            '✨ 下一发就是传说级债券！',
            '🔥 盲盒刺激，开箱见惊喜！',
            '💎 运气爆棚，开出大额债券不是梦！',
            '🍀 天选之人，?? 藏着大宝藏！',
            '🎁 未知才有趣，下单揭晓答案！',
            '⚡ 搏一搏，债券多一摞！',
            '🌟 你的专属幸运数字正在等你！'
        ];
        try {
            const res = await api('pay.php', {action: 'get_shop_products'});
            if (res.success && res.data && res.data.products) {
                const products = res.data.products;
                if (products.length === 0) {
                    container.innerHTML = '<div style="text-align:center;padding:20px;color:var(--dim)">暂无商品</div>';
                    return;
                }

                // 渲染商品列表
                let html = '';
                products.forEach((product, index) => {
                    const isSelected = index === 0; // 默认选中第一个
                    const isBlindBox = product.bond_min !== product.bond_max; // 盲盒：范围格式
                    const offerText = product.temporary_offer ? `<div style="font-size:12px;color:var(--yellow);margin-top:4px">🎁 ${product.temporary_offer}${product.expire_display ? ' (有效期: ' + product.expire_display + ')' : ''}</div>` : '';
                    const stockText = product.stock === -1 ? '无限库存' : (product.stock > 0 ? `库存: ${product.stock}` : '售罄');
                    const stockColor = product.stock === -1 ? 'var(--green)' : (product.stock > 0 ? 'var(--dim)' : 'var(--red)');
                    const isSoldOut = product.stock === 0; // 售罄：仍展示，但禁止购买
                    // ★ 盲盒不显示具体金额，用 ?? 代替
                    const bondDisplay = isBlindBox ? '🎲 ?? 债券' : `${product.bond_min} 债券`;
                    // ★ 盲盒随机语录
                    const quoteText = isBlindBox ? `<div style="font-size:12px;color:var(--accent);margin-top:6px;font-style:italic">${blindBoxQuotes[Math.floor(Math.random() * blindBoxQuotes.length)]}</div>` : '';

                    html += `
                        <div class="shop-product-card" data-product-id="${product.id}" data-price="${product.price}" data-bond-min="${product.bond_min}" data-bond-max="${product.bond_max}" data-stock="${product.stock}" onclick="selectShopProduct(this)" style="border:1px solid ${isSelected ? 'var(--accent)' : 'var(--border)'};border-radius:14px;padding:16px;margin-bottom:12px;background:${isSelected ? 'linear-gradient(135deg,rgba(63,185,80,0.08),rgba(88,166,255,0.06))' : 'transparent'};cursor:pointer;transition:all 0.2s ease;${isSoldOut ? 'opacity:0.72;' : ''}">
                            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                                <span style="font-size:14px;color:var(--fg)">${product.item_name}</span>
                                <span style="font-size:12px;color:${stockColor};border:1px solid ${stockColor};border-radius:999px;padding:2px 10px">${stockText}</span>
                            </div>
                            <div style="display:flex;justify-content:space-between;align-items:baseline">
                                <div><div style="font-size:28px;font-weight:800;color:var(--accent)">¥${product.price}</div><div style="font-size:12px;color:var(--dim)">支付金额</div></div>
                                <div style="font-size:22px;color:var(--green)">→ ${bondDisplay}</div>
                            </div>
                            ${offerText}
                            ${quoteText}
                        </div>
                    `;
                });

                container.innerHTML = html;

                // 选中第一个商品
                if (products.length > 0) {
                    selectShopProduct(document.querySelector('.shop-product-card'));
                }
            } else {
                container.innerHTML = '<div style="text-align:center;padding:20px;color:var(--dim)">加载商品失败</div>';
            }
        } catch (e) {
            container.innerHTML = '<div style="text-align:center;padding:20px;color:var(--red)">加载商品失败: ' + e.message + '</div>';
        }
    }

    // 选择商品
    function selectShopProduct(el) {
        // 取消所有选中
        document.querySelectorAll('.shop-product-card').forEach(card => {
            card.style.borderColor = 'var(--border)';
            card.style.background = 'transparent';
        });

        // 选中当前
        el.style.borderColor = 'var(--accent)';
        el.style.background = 'linear-gradient(135deg,rgba(63,185,80,0.08),rgba(88,166,255,0.06))';

        // 更新支付按钮
        const btn = document.getElementById('rechargeBtn');
        const price = el.dataset.price;
        const bondMin = el.dataset.bondMin;
        const bondMax = el.dataset.bondMax;
        const stock = parseInt(el.dataset.stock, 10) || 0;
        // ★ 盲盒不显示具体金额，用 ?? 代替
        const bondText = bondMin === bondMax ? bondMin + ' 债券' : '🎲 ?? 债券';
        btn.style.display = 'block';
        btn.dataset.productId = el.dataset.productId;

        // ★ 售罄(stock=0)：展示商品但禁止购买；只有下架(is_active=0)才不展示
        if (stock === 0) {
            btn.disabled = true;
            btn.textContent = '😔 已售罄';
            btn.onclick = null;
        } else {
            btn.disabled = false;
            btn.textContent = `支付宝支付 ¥${price} → ${bondText}`;
            btn.onclick = startRecharge;
        }

        // 保存选中商品信息
        window.selectedShopProduct = {
            id: el.dataset.productId,
            price: price,
            bondMin: bondMin,
            bondMax: bondMax
        };
    }

    async function startRecharge() {
        const btn = document.getElementById('rechargeBtn');
        const st = document.getElementById('rechargeStatus');
        if (!TOKEN) { toast('请先登录', 'error'); return; }
        btn.disabled = true;
        btn.textContent = '⏳ 正在创建订单…';
        st.innerHTML = '<span style="color:var(--dim)">正在请求支付订单…</span>';
        try {
            // 获取选中的商品ID
            const productId = window.selectedShopProduct ? window.selectedShopProduct.id : 0;
            const res = await api('pay.php', {action: 'create_order', product_id: productId});
            if (!res.success) {
                st.innerHTML = '<span style="color:var(--red)">创建订单失败：' + (res.message || '未知错误') + '</span>';
                btn.disabled = false;
                btn.textContent = '支付';
                return;
            }
            const { pay_url, out_trade_no, reused } = res.data;
            localStorage.setItem('sdf1_last_recharge_no', out_trade_no);
            st.innerHTML = '<span style="color:var(--green)">' + (reused ? '恢复支付链接' : '订单已创建') + '，正在跳转支付…（如未弹出，请允许浏览器打开新窗口）</span>';
            document.getElementById('rechargeQueryBtn').style.display = 'block';
            const w = window.open(pay_url, '_blank');
            if (!w) {
                st.innerHTML = '<span style="color:var(--yellow)">浏览器拦截了新窗口，<a href="' + pay_url + '" target="_blank" style="color:var(--accent)">点此手动打开支付页面</a></span>';
            }
            // ★ 防重复支付：创建订单后按钮永久锁定，只有支付成功后才恢复
            pollRechargeOrder(false);
        } catch (e) {
            st.innerHTML = '<span style="color:var(--red)">请求异常：' + e.message + '</span>';
            btn.disabled = false;
            btn.textContent = '支付';
        }
    }

    let rechargePollTimer = null;
    async function pollRechargeOrder(manual) {
        const st = document.getElementById('rechargeStatus');
        const no = localStorage.getItem('sdf1_last_recharge_no');
        if (!no) { if (manual) toast('没有可查询的订单', 'error'); return; }
        if (!TOKEN) { if (manual) toast('请先登录', 'error'); return; }
        if (rechargePollTimer && !manual) return; // 已在轮询

        const doPoll = async () => {
            try {
                const res = await api('pay.php', {action: 'query_order', out_trade_no: no});
                if (res.success && res.data) {
                    if (res.data.status === 'paid') {
                        st.innerHTML = '<span style="color:var(--green)">✅ 支付成功！游戏内债券发放中，请稍候刷新「余额查询」。可以发起新订单。</span>';
                        if (rechargePollTimer) { clearInterval(rechargePollTimer); rechargePollTimer = null; }
                        // 支付成功后清除本地记录
                        localStorage.removeItem('sdf1_last_recharge_no');
                        // ★ 刷新商品列表：库存可能变化（如本次购买导致售罄），并正确重置按钮状态
                        loadShopProducts();
                    } else {
                        st.innerHTML = '<span style="color:var(--dim)">⏳ 订单处理中（' + (res.message || '等待支付平台通知') + '）…</span>';
                    }
                } else {
                    st.innerHTML = '<span style="color:var(--orange)">' + (res.message || '查询失败') + '</span>';
                }
            } catch (e) {
                st.innerHTML = '<span style="color:var(--orange)">查询异常：' + e.message + '</span>';
            }
        };
        await doPoll();
        if (!manual && !rechargePollTimer) {
            rechargePollTimer = setInterval(doPoll, 3000);
            setTimeout(() => { if (rechargePollTimer) { clearInterval(rechargePollTimer); rechargePollTimer = null; } }, 120000);
        }
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

    function showToast(msg, type = 'info') {
        const t = document.createElement('div');
        t.className = 'toast ' + type;
        t.textContent = msg;
        document.body.appendChild(t);
        setTimeout(() => { t.style.opacity = '0'; t.style.transform = 'translateX(100%)'; }, 2500);
        setTimeout(() => { if (t.parentNode) t.parentNode.removeChild(t); }, 3000);
    }

    // ★ 自定义玻璃拟态确认对话框
    function showConfirm(msg) {
        return new Promise(resolve => {
            const overlay = document.createElement('div');
            overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:10000;display:flex;justify-content:center;align-items:center;animation:glassFadeIn 0.2s ease';
            overlay.innerHTML = '<div style="background:var(--card);border:1px solid var(--border);border-radius:16px;padding:28px;width:420px;max-width:90%;box-shadow:0 16px 64px rgba(0,0,0,0.5)">';
            overlay.innerHTML += '<p style="color:var(--text);white-space:pre-line;margin-bottom:20px;font-size:14px;line-height:1.6">' + escapeHtml(msg) + '</p>';
            overlay.innerHTML += '<div style="display:flex;gap:10px;justify-content:flex-end">';
            overlay.innerHTML += '<button id="_confirm_yes" class="btn btn-primary">确定</button>';
            overlay.innerHTML += '<button id="_confirm_no" class="btn btn-dim">取消</button>';
            overlay.innerHTML += '</div></div>';
            document.body.appendChild(overlay);
            const yesBtn = overlay.querySelector('#_confirm_yes');
            const noBtn = overlay.querySelector('#_confirm_no');
            const cleanup = () => { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); };
            yesBtn.onclick = () => { cleanup(); resolve(true); };
            noBtn.onclick = () => { cleanup(); resolve(false); };
        });
    }

    function escapeHtml(str) {
        return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
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
    let currentBuyItem = null; // 当前购买弹窗对应的商品，供"加入购物车"使用

    async function renderShop(el) {
        el.innerHTML = '<div class="empty">加载中...</div>';
        loadCart();
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
            ensureCartButton();
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
            html += `<div class="item-card" onclick="showBuyModal('${item.id}','${item.display_name.replace(/'/g,"\\'")}',${item.buy_price},${item.stock},'${item.material || ''}')"><div class="icon">${matIcon}</div><div class="name">${item.display_name}</div><div class="price">${item.buy_price} 债券</div><div class="stock ${stockClass}">库存: ${stockText}</div></div>`;
        });
        html += '</div>';
        contentEl.innerHTML = html;
    }

    function getMaterialIcon(mat) {
        const icons = {'STONE':'🪨','DIAMOND':'💎','GOLD_INGOT':'🥇','IRON_INGOT':'🥈','EMERALD':'💚','REDSTONE':'🔴','COAL':'⬛','DIAMOND_SWORD':'⚔️','GOLDEN_APPLE':'🍎','END_CRYSTAL':'🔮','TOTEM_UNDYING':'🛡️','NETHERITE_INGOT':'⬛','ENCHANTED_BOOK':'📖','SHULKER_BOX':'📦','PLAYER_HEAD':'👤','PAPER':'📄','ARROW':'🏹','BOW':'🏹','TRIDENT':'🔱','ELYTRA':'🪽','SADDLE':'🐴','NAME_TAG':'🏷️'};
        return icons[mat] || '🧱';
    }

    function showBuyModal(id, name, price, stock, material) {
        if (IS_PREVIEW) { toast('预览模式下无法购买', 'error'); return; }
        if (stock == 0) { toast('商品已售罄', 'error'); return; }
        currentBuyItem = {id, name, price, stock, material: material || ''};
        document.getElementById('modalTitle').textContent = '购买: ' + name;
        const needPwd = price > 1000;
        const maxQty = stock > 0 ? stock : 999;
        document.getElementById('modalBody').innerHTML = `
            <div class="row"><label>单价: ${price} 债券</label></div>
            <div class="row"><label>数量</label><input type="number" id="buyAmount" value="1" min="1" max="${maxQty}"></div>
            <div class="row"><label>小计: <span id="buyTotal">${price}</span> 债券</label></div>
            ${needPwd ? '<div class="row"><label>密码确认</label><input type="password" id="buyPassword" placeholder="输入游戏登录密码"></div>' : '<div style="color:var(--dim);font-size:12px;text-align:center;margin-top:4px">小额交易（≤1000债券），免密码确认</div>'}`;
        // 加入购物车按钮
        const cartBtn = document.createElement('div');
        cartBtn.style.cssText = 'margin-top:12px';
        cartBtn.innerHTML = '<button class="btn btn-green" style="width:100%" onclick="addToCartFromModal()">🛒 加入购物车</button>';
        document.getElementById('modalBody').appendChild(cartBtn);
        document.getElementById('modalConfirm').onclick = () => doBuy(id);
        document.getElementById('buyAmount').oninput = function() {
            const total = this.value * price;
            document.getElementById('buyTotal').textContent = total;
            // 动态切换密码框
            const pwdRow = document.getElementById('buyPassword');
            const hintEl = document.querySelector('#modalBody > div:last-child');
            if (total > 1000 && !pwdRow) {
                // 需要密码框
                const newDiv = document.createElement('div');
                newDiv.className = 'row';
                newDiv.innerHTML = '<label>密码确认</label><input type="password" id="buyPassword" placeholder="输入游戏登录密码">';
                document.getElementById('buyTotal').parentElement.parentElement.insertAdjacentElement('afterend', newDiv);
                if (hintEl) hintEl.remove();
            } else if (total <= 1000 && pwdRow) {
                // 不需要密码框
                pwdRow.parentElement.remove();
                const hint = document.createElement('div');
                hint.style.cssText = 'color:var(--dim);font-size:12px;text-align:center;margin-top:4px';
                hint.textContent = '小额交易（≤1000债券），免密码确认';
                document.getElementById('buyTotal').parentElement.parentElement.insertAdjacentElement('afterend', hint);
            }
        };
        document.getElementById('modalOverlay').style.display = 'flex';
    }

    async function doBuy(itemId) {
        const amount = parseInt(document.getElementById('buyAmount').value);
        const total = parseInt(document.getElementById('buyTotal').textContent);
        const password = document.getElementById('buyPassword')?.value || '';
        if (total > 1000 && !password) { toast('请输入密码确认', 'error'); return; }
        const res = await fetch(API + 'shop.php?action=buy', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: TOKEN, item_id: itemId, amount: amount, player: currentPlayer, password: password || undefined})
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

    // ===== 购物车系统 =====
    let cart = [];
    let cartCfg = {backpack_rate: 0.98, shulker_rate: 1.0};
    let cartSettlement = 'backpack';
    let cartSubtotalVal = 0;
    let cartShulkerColor = 'purple'; // 潜影盒颜色（purple免费，其它+2元）
    const SHULKER_COLORS = [
        {id:'purple', name:'§5紫色', price:0, css:'#AA00FF'},
        {id:'white',  name:'§f白色', price:2, css:'#FFFFFF'},
        {id:'black',  name:'§8黑色', price:2, css:'#1A1A1A'},
        {id:'red',    name:'§c红色', price:2, css:'#FF3333'},
        {id:'blue',   name:'§9蓝色', price:2, css:'#3366FF'},
        {id:'green',  name:'§a绿色', price:2, css:'#33FF66'},
        {id:'yellow', name:'§e黄色', price:2, css:'#FFFF33'},
        {id:'orange', name:'§6橙色', price:2, css:'#FF9933'}
    ];

    function loadCart() {
        try { cart = JSON.parse(localStorage.getItem('sdf1_cart') || '[]'); } catch (e) { cart = []; }
        if (!Array.isArray(cart)) cart = [];
        updateCartButton();
    }
    function saveCart() { try { localStorage.setItem('sdf1_cart', JSON.stringify(cart)); } catch (e) {} updateCartButton(); }

    function updateCartButton() {
        const btn = document.getElementById('cartFab');
        if (!btn) return;
        const count = cart.reduce((s, i) => s + (i.amount || 1), 0);
        const total = cart.reduce((s, i) => s + i.price * i.amount, 0);
        btn.querySelector('.cart-fab-count').textContent = count;
        btn.querySelector('.cart-fab-total').textContent = total;
        btn.style.display = count > 0 ? 'flex' : 'none';
    }

    function ensureCartButton() {
        if (document.getElementById('cartFab')) { updateCartButton(); return; }
        const btn = document.createElement('div');
        btn.id = 'cartFab';
        btn.style.cssText = 'position:fixed;right:18px;bottom:18px;z-index:9000;display:none;align-items:center;gap:8px;padding:12px 18px;background:var(--accent);color:#fff;border-radius:30px;cursor:pointer;box-shadow:0 8px 24px rgba(0,0,0,0.35);font-size:14px;transition:transform .2s cubic-bezier(0.16,1,0.3,1)';
        btn.innerHTML = '<span style="font-size:18px">🛒</span><span class="cart-fab-count">0</span><span style="opacity:.85;font-size:12px">件</span><span class="cart-fab-total" style="font-weight:700">0</span><span style="opacity:.85;font-size:12px">债券</span>';
        btn.onclick = openCartDrawer;
        btn.onmouseenter = () => btn.style.transform = 'scale(1.06)';
        btn.onmouseleave = () => btn.style.transform = 'scale(1)';
        document.body.appendChild(btn);
        updateCartButton();
    }

    function addToCartFromModal() {
        if (!currentBuyItem) return;
        const amount = parseInt(document.getElementById('buyAmount')?.value || '1') || 1;
        addToCart(currentBuyItem.id, currentBuyItem.name, currentBuyItem.price, currentBuyItem.material || '', currentBuyItem.stock, amount);
        closeModal();
    }

    function addToCart(id, name, price, material, stock, amount) {
        if (IS_PREVIEW) { toast('预览模式下无法加入购物车', 'error'); return; }
        amount = Math.max(1, parseInt(amount) || 1);
        if (stock > 0 && amount > stock) amount = stock;
        const exist = cart.find(i => i.item_id == id);
        if (exist) {
            const max = stock > 0 ? stock : 9999;
            exist.amount = Math.min((exist.amount || 1) + amount, max);
        } else {
            cart.push({item_id: id, name: name, price: parseInt(price), material: material, stock: parseInt(stock), amount: amount});
        }
        saveCart();
        toast('已加入购物车', 'success');
    }

    function changeCartQty(id, delta) {
        const i = cart.find(x => x.item_id == id);
        if (!i) return;
        const max = i.stock > 0 ? i.stock : 9999;
        i.amount = Math.min(Math.max((i.amount || 1) + delta, 1), max);
        saveCart();
        renderCartList();
    }
    function removeFromCart(id) {
        cart = cart.filter(x => x.item_id != id);
        saveCart();
        renderCartList();
    }
    function clearCart() {
        cart = [];
        saveCart();
        renderCartList();
    }

    function openCartDrawer() {
        if (cart.length === 0) { toast('购物车是空的', 'info'); return; }
        document.getElementById('cartDrawer')?.remove();
        const overlay = document.createElement('div');
        overlay.id = 'cartDrawer';
        overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.55);backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);z-index:9500;display:flex;justify-content:flex-end;animation:glassFadeIn .2s ease';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
        let html = '<div style="width:380px;max-width:92%;height:100%;background:var(--card);border-left:1px solid var(--border);display:flex;flex-direction:column;box-shadow:-16px 0 48px rgba(0,0,0,.4)">';
        html += '<div style="padding:18px 20px;border-bottom:1px solid var(--border);display:flex;justify-content:space-between;align-items:center"><h3 style="margin:0;color:var(--fg)">🛒 购物车</h3><button onclick="document.getElementById(\'cartDrawer\').remove()" style="background:none;border:none;color:var(--dim);font-size:20px;cursor:pointer">✕</button></div>';
        html += '<div id="cartList" style="flex:1;overflow-y:auto;padding:12px 16px"></div>';
        html += '<div style="padding:16px 20px;border-top:1px solid var(--border)">';
        html += '<div style="display:flex;justify-content:space-between;margin-bottom:6px;font-size:13px"><span style="color:var(--dim)">商品原价合计</span><span id="cartSubtotal">0 债券</span></div>';
        html += '<div style="display:flex;justify-content:space-between;margin-bottom:12px;font-size:18px;font-weight:700"><span>预估总价</span><span id="cartGrand" style="color:var(--accent)">0 债券</span></div>';
        html += '<div style="display:flex;gap:10px"><button class="btn btn-dim" style="flex:1" onclick="clearCart()">清空</button><button class="btn btn-primary" style="flex:2" onclick="openSettlement()">去结算</button></div>';
        html += '</div></div>';
        overlay.innerHTML = html;
        document.body.appendChild(overlay);
        renderCartList();
    }

    function renderCartList() {
        const list = document.getElementById('cartList');
        if (!list) return;
        if (cart.length === 0) {
            list.innerHTML = '<div class="empty" style="padding:30px">购物车空空如也</div>';
        } else {
            let h = '';
            cart.forEach(i => {
                const matIcon = getMaterialIcon(i.material);
                h += '<div style="display:flex;gap:10px;align-items:center;padding:10px;background:var(--bg);border:1px solid var(--border);border-radius:12px;margin-bottom:8px">';
                h += '<div style="font-size:24px">' + matIcon + '</div>';
                h += '<div style="flex:1;min-width:0"><div style="font-size:14px;color:var(--fg);overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + escapeHtml(i.name) + '</div>';
                h += '<div style="font-size:12px;color:var(--dim)">' + i.price + ' 债券/个</div></div>';
                h += '<div style="display:flex;align-items:center;gap:6px"><button onclick="changeCartQty(' + i.item_id + ',-1)" style="width:26px;height:26px;border-radius:8px;border:1px solid var(--border);background:var(--card);color:var(--fg);cursor:pointer;font-size:16px;line-height:1">−</button>';
                h += '<span style="min-width:28px;text-align:center;font-size:14px">' + i.amount + '</span>';
                h += '<button onclick="changeCartQty(' + i.item_id + ',1)" style="width:26px;height:26px;border-radius:8px;border:1px solid var(--border);background:var(--card);color:var(--fg);cursor:pointer;font-size:16px;line-height:1">+</button></div>';
                h += '<div style="text-align:right;min-width:64px"><div style="font-size:14px;color:var(--fg);font-weight:600">' + (i.price * i.amount) + '</div><button onclick="removeFromCart(' + i.item_id + ')" style="background:none;border:none;color:var(--red);font-size:11px;cursor:pointer;margin-top:2px">移除</button></div>';
                h += '</div>';
            });
            list.innerHTML = h;
        }
        const subtotal = cart.reduce((s, i) => s + i.price * i.amount, 0);
        cartSubtotalVal = subtotal;
        const grand = document.getElementById('cartGrand');
        if (grand) grand.textContent = subtotal + ' 债券';
        const sub = document.getElementById('cartSubtotal');
        if (sub) sub.textContent = subtotal + ' 债券';
        updateCartButton();
    }

    async function openSettlement() {
        if (cart.length === 0) { toast('购物车是空的', 'info'); return; }
        if (IS_PREVIEW) { toast('预览模式无法结算', 'error'); return; }
        try { const r = await api('shop.php', {action: 'cart_config'}); if (r.success && r.data) cartCfg = r.data; } catch (e) {}
        const subtotal = cart.reduce((s, i) => s + i.price * i.amount, 0);
        cartSubtotalVal = subtotal;
        const bpTotal = Math.round(subtotal * cartCfg.backpack_rate);
        const shTotal = Math.round(subtotal * cartCfg.shulker_rate);
        const overlay = document.createElement('div');
        overlay.id = 'cartSettle';
        overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.6);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:9800;display:flex;justify-content:center;align-items:center;animation:glassFadeIn .2s ease';
        overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
        let html = '<div style="width:440px;max-width:92%;max-height:88vh;overflow-y:auto;background:var(--card);border:1px solid var(--border);border-radius:18px;padding:24px;box-shadow:0 20px 64px rgba(0,0,0,.5)">';
        html += '<h3 style="margin:0 0 4px;color:var(--fg)">🧾 选择结算方式</h3>';
        html += '<p style="color:var(--dim);font-size:12px;margin:0 0 16px">共 ' + cart.reduce((s, i) => s + i.amount, 0) + ' 件 · 原价 ' + subtotal + ' 债券</p>';
        html += '<div id="settleModes" style="display:flex;flex-direction:column;gap:10px;margin-bottom:14px">';
        html += settleModeHtml('backpack', '🎒 塞背包', cartCfg.backpack_rate, bpTotal, subtotal, '直接发放到背包，享 ' + (cartCfg.backpack_rate * 10).toFixed(1) + ' 折');
        html += settleModeHtml('shulker', '📦 潜影盒打包', cartCfg.shulker_rate, shTotal, subtotal, shulkerDesc(cartCfg.shulker_rate, subtotal, shTotal));
        html += '</div>';
        // 潜影盒颜色选择（仅shulker模式显示）
        html += '<div id="shulkerColorWrap" style="display:none;margin-bottom:14px;padding:12px;border:1px solid var(--border);border-radius:10px;background:var(--bg)">';
        html += '<div style="font-size:13px;color:var(--fg);font-weight:600;margin-bottom:8px">🎨 选择潜影盒颜色 <span id="colorFeeHint" style="font-weight:400;color:var(--dim);font-size:12px"></span></div>';
        html += '<div style="display:flex;flex-wrap:wrap;gap:6px">';
        for (const c of SHULKER_COLORS) {
            const checked = c.id === 'purple' ? 'checked' : '';
            const extra = c.price > 0 ? ' (+' + c.price + '元)' : ' (免费)';
            html += '<label class="shulker-color-opt" data-color="' + c.id + '" onclick="selectShulkerColor(\'' + c.id + '\', ' + c.price + ')" style="display:inline-flex;align-items:center;gap:4px;padding:5px 10px;border:1px solid var(--border);border-radius:6px;cursor:pointer;font-size:12px;color:var(--fg);transition:all .15s"><input type="radio" name="shcolor" value="' + c.id + '" ' + checked + ' style="margin:0"><span style="width:16px;height:16px;border-radius:3px;display:inline-block;background:' + c.css + ';border:1px solid rgba(255,255,255,.2)"></span>' + c.name.replace(/§./g, '') + '<span style="color:var(--dim)">' + extra + '</span></label>';
        }
        html += '</div></div>';
        html += '<div id="settlePwdWrap"></div>';
        html += '<div style="display:flex;gap:10px;margin-top:6px"><button class="btn btn-dim" style="flex:1" onclick="document.getElementById(\'cartSettle\').remove()">取消</button><button class="btn btn-primary" style="flex:2" id="settleConfirmBtn" onclick="doSettle()">确认结算</button></div>';
        html += '</div>';
        overlay.innerHTML = html;
        document.body.appendChild(overlay);
        selectSettleMode('backpack');
    }

    function shulkerDesc(rate, subtotal, total) {
        const diff = total - subtotal;
        if (diff > 0) return '打包进潜影盒，加价 ' + diff + ' 债券';
        if (diff < 0) return '打包进潜影盒，优惠 ' + (-diff) + ' 债券';
        return '打包进潜影盒，原价结算';
    }

    function settleModeHtml(mode, title, rate, total, subtotal, desc) {
        const saved = subtotal - total;
        let badge;
        if (saved > 0) badge = '<span style="color:var(--green);font-size:12px">省 ' + saved + ' 债券</span>';
        else if (saved < 0) badge = '<span style="color:var(--orange);font-size:12px">加价 ' + (-saved) + ' 债券</span>';
        else badge = '<span style="color:var(--dim);font-size:12px">原价</span>';
        const priceId = mode === 'shulker' ? 'id="shulkerPriceDisplay"' : '';
        return '<div class="settle-mode" data-mode="' + mode + '" onclick="selectSettleMode(\'' + mode + '\')" style="padding:12px 14px;border:1px solid var(--border);border-radius:12px;cursor:pointer;transition:all .15s"><div style="display:flex;justify-content:space-between;align-items:center"><div style="font-size:14px;color:var(--fg);font-weight:600">' + title + '</div><div style="text-align:right"><div style="font-size:15px;font-weight:700;color:var(--accent)" ' + priceId + '>' + total + ' 债券</div>' + badge + '</div></div><div style="font-size:12px;color:var(--dim);margin-top:4px">' + desc + '</div></div>';
    }

    function selectSettleMode(mode) {
        cartSettlement = mode;
        document.querySelectorAll('.settle-mode').forEach(c => {
            const active = c.dataset.mode === mode;
            c.style.borderColor = active ? 'var(--accent)' : '';
            c.style.background = active ? 'color-mix(in srgb, var(--accent) 10%, transparent)' : '';
        });
        // 显示/隐藏潜影盒颜色选择器
        const colorWrap = document.getElementById('shulkerColorWrap');
        if (colorWrap) colorWrap.style.display = mode === 'shulker' ? '' : 'none';
        updateSettlePwd();
    }

    function selectShulkerColor(colorId, price) {
        cartShulkerColor = colorId;
        document.querySelectorAll('.shulker-color-opt').forEach(c => {
            const sel = c.dataset.color === colorId;
            c.style.borderColor = sel ? 'var(--accent)' : 'var(--border)';
            c.style.background = sel ? 'color-mix(in srgb, var(--accent) 8%, transparent)' : '';
            const radio = c.querySelector('input[type="radio"]');
            if (radio) radio.checked = sel;
        });
        const hint = document.getElementById('colorFeeHint');
        if (hint) hint.textContent = price > 0 ? '(额外收费 +' + price + ' 债券)' : '(免费)';
        // 更新价格显示
        updateSettleTotal();
    }

    function updateSettleTotal() {
        const priceEl = document.getElementById('shulkerPriceDisplay');
        if (priceEl && cartSettlement === 'shulker') {
            const base = cartSubtotalVal * cartCfg.shulker_rate;
            const colorExtra = SHULKER_COLORS.find(c => c.id === cartShulkerColor)?.price || 0;
            priceEl.textContent = Math.round(base + colorExtra) + ' 债券';
        }
    }

    function updateSettlePwd() {
        const wrap = document.getElementById('settlePwdWrap');
        if (!wrap) return;
        const total = Math.round(cartSubtotalVal * (cartSettlement === 'shulker' ? cartCfg.shulker_rate : cartCfg.backpack_rate));
        if (total > 1000) {
            wrap.innerHTML = '<div class="row" style="margin-top:8px"><label>密码确认</label><input type="password" id="settlePassword" placeholder="输入游戏登录密码" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px"></div>';
        } else {
            wrap.innerHTML = '<div style="color:var(--dim);font-size:12px;text-align:center;margin-top:8px">小额结算（≤1000债券），免密码确认</div>';
        }
    }

    async function doSettle() {
        const subtotal = cart.reduce((s, i) => s + i.price * i.amount, 0);
        let total = Math.round(subtotal * (cartSettlement === 'shulker' ? cartCfg.shulker_rate : cartCfg.backpack_rate));
        // 潜影盒颜色额外收费
        if (cartSettlement === 'shulker') {
            const colorExtra = SHULKER_COLORS.find(c => c.id === cartShulkerColor)?.price || 0;
            total += colorExtra;
        }
        const password = document.getElementById('settlePassword')?.value || '';
        if (total > 1000 && !password) { toast('请输入密码确认', 'error'); return; }
        const itemsPayload = cart.map(i => ({item_id: i.item_id, amount: i.amount}));
        const btn = document.getElementById('settleConfirmBtn');
        if (btn) { btn.disabled = true; btn.textContent = '结算中...'; }
        try {
            const bodyData = {token: TOKEN, items: JSON.stringify(itemsPayload), settlement: cartSettlement, player: currentPlayer, password: password || undefined};
            if (cartSettlement === 'shulker') bodyData.shulker_color = cartShulkerColor;
            const res = await fetch(API + 'shop.php?action=buy_cart', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(bodyData)
            });
            const data = await res.json();
            if (data.need_password) { showPasswordModal(data.message || '请输入游戏登录密码'); return; }
            document.getElementById('cartSettle')?.remove();
            if (data.success) {
                const paid = data.data.total_price;
                const modeName = data.data.mode_name;
                cart = [];
                saveCart();
                toast('结算成功（' + modeName + '）：' + paid + ' 债券', 'success');
                setTimeout(() => { notifyJavaSync(); switchPage('shop'); }, 500);
            } else {
                toast(data.message || '结算失败', 'error');
            }
        } catch (e) {
            toast('结算请求失败: ' + e.message, 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.textContent = '确认结算'; }
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
                    <button class="btn btn-green" onclick="doCDKExchange()">兑换</button>
                </div>
                <div id="cdkResult" style="margin-top:12px"></div>
            </div>`;
    }

    async function doCDKExchange() {
        const code = document.getElementById('cdkCode').value.trim();
        if (!code) { toast('请输入CDK码', 'error'); return; }
        const res = await fetch(API + 'cdk.php?action=exchange', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({token: TOKEN, code: code, player: currentPlayer})
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

    // ===== 用户组 =====
    function renderGroups(el) {
        if (!currentPlayer || (!AUTHENTICATED && NEED_PASSWORD)) {
            el.innerHTML = '<div class="card"><h2>👥 用户组</h2><div style="text-align:center;padding:40px 20px"><div style="font-size:48px;margin-bottom:16px">🔒</div><p style="color:var(--dim);font-size:14px;margin-bottom:20px">请先登录游戏</p></div></div>';
            return;
        }
        el.innerHTML = `
            <div class="card">
                <h2>👥 用户组</h2>
                <div id="myGroupsResult" style="margin-top:12px"><div style="color:var(--dim);font-size:13px">加载中...</div></div>
            </div>
            <div class="card">
                <h2>🛒 可购买的用户组</h2>
                <div id="availableGroupsResult" style="margin-top:12px"><div style="color:var(--dim);font-size:13px">加载中...</div></div>
            </div>`;
        loadMyGroups();
        loadAvailableGroups();
    }

    async function loadMyGroups() {
        const div = document.getElementById('myGroupsResult');
        try {
            const res = await api('land_api.php', {action: 'get_player_groups', player: currentPlayer, token: TOKEN});
            if (!res.success) { div.innerHTML = `<div style="color:var(--red);font-size:13px">${res.error || '加载失败'}</div>`; return; }
            const groups = res.groups || [];
            if (groups.length === 0) {
                div.innerHTML = '<div style="color:var(--dim);font-size:13px;padding:12px 0">你还没有加入任何用户组</div>';
                return;
            }
            let html = '';
            groups.forEach(g => {
                const now = Math.floor(Date.now() / 1000);
                const expiry = parseInt(g.expiry_time) || 0;
                let statusHtml = '';
                let colorStyle = g.display_color ? g.display_color.replace(/§/g, '') : '';
                // 将MC颜色代码转为CSS
                const mcColors = {'0':'#000','1':'#00a','2':'#0a0','3':'#0aa','4':'#a00','5':'#a0a','6':'#fa0','7':'#aaa','8':'#555','9':'#55f','a':'#5f5','b':'#5ff','c':'#f55','d':'#f5f','e':'#ff5','f':'#fff'};
                const mcBold = {'l':'font-weight:bold;'};
                let cssColor = '';
                let cssBold = '';
                for (let i = 0; i < colorStyle.length; i++) {
                    if (mcColors[colorStyle[i]]) cssColor = mcColors[colorStyle[i]];
                    if (colorStyle[i] === 'l') cssBold = 'font-weight:bold;';
                }
                const displayName = g.display_name || g.group_name;
                const displayNameHtml = `<span style="color:${cssColor};${cssBold}">${displayName}</span>`;

                // 格式化有效期
                const durMin = parseInt(g.duration_minutes) || 0;
                let durStr = '';
                if (durMin > 0) {
                    durStr = durMin >= 1440 ? '有效期:' + Math.floor(durMin/1440) + '天' : '有效期:' + Math.floor(durMin/60) + '小时' + (durMin%60 ? ' '+(durMin%60)+'分钟' : '');
                } else {
                    durStr = '永久有效';
                }
                const homeLimit = g.home_limit != null ? g.home_limit : '默认';
                const landPrice = parseInt(g.land_price_per_sqm) !== -1 ? g.land_price_per_sqm : -1;

                if (expiry > 0) {
                    const left = expiry - now;
                    if (left <= 0) {
                        statusHtml = '<span style="color:var(--red);font-size:12px">已过期</span>';
                    } else {
                        const days = Math.floor(left / 86400);
                        const hours = Math.floor((left % 86400) / 3600);
                        const mins = Math.floor((left % 3600) / 60);
                        let timeStr = '';
                        if (days > 0) timeStr += days + '天';
                        if (hours > 0) timeStr += hours + '小时';
                        timeStr += mins + '分钟';
                        statusHtml = `<span style="color:var(--green);font-size:12px">剩余 ${timeStr}</span>`;
                        if (g.auto_renew == 1) statusHtml += ' <span style="color:var(--purple);font-size:11px">[自动续费]</span>';
                    }
                } else {
                    statusHtml = '<span style="color:var(--dim);font-size:12px">永久</span>';
                }

                let renewBtn = '';
                if (expiry > 0 && expiry - now < 86400 && parseInt(g.renew_price) > 0) {
                    renewBtn = ` <button class="btn btn-blue" style="font-size:11px;padding:3px 8px;margin-left:8px" onclick="doRenewGroup('${g.group_name}')">续费 ${g.renew_price}张</button>`;
                }

                html += `<div style="display:flex;align-items:center;justify-content:space-between;padding:10px 0;border-bottom:1px solid var(--border);gap:8px;flex-wrap:wrap">
                    <div>
                        ${displayNameHtml}
                        <div style="font-size:11px;color:var(--dim);margin-top:4px">
                            ${durStr} · Home:${homeLimit} · 领地${landPrice === -1 ? '默认' : '('+landPrice+'张/格'+String.fromCharCode(178)+')'} · 加入${parseInt(g.join_price)>0?(g.join_price+'张'):((g.auto_renew==1?'可续费':'免费'))}
                        </div>
                    </div>
                    <div style="display:flex;align-items:center;gap:8px">${statusHtml}${renewBtn}</div>
                </div>`;
            });
            div.innerHTML = html;
        } catch(e) {
            div.innerHTML = `<div style="color:var(--red);font-size:13px">加载失败: ${e.message}</div>`;
        }
    }

    async function loadAvailableGroups() {
        const div = document.getElementById('availableGroupsResult');
        window._availGroupsMap = window._availGroupsMap || {};
        try {
            const res = await api('land_api.php', {action: 'list_available_groups', token: TOKEN});
            if (!res.success) { div.innerHTML = `<div style="color:var(--red);font-size:13px">${res.error || '加载失败'}</div>`; return; }
            const groups = res.groups || [];
            // Cache for buy dialog display
            groups.forEach(g => { window._availGroupsMap[g.group_name] = g; });
            if (groups.length === 0) {
                div.innerHTML = '<div style="color:var(--dim);font-size:13px;padding:12px 0">暂无可购买的用户组</div>';
                return;
            }
            let html = '<div class="grid">';
            groups.forEach(g => {
                const mcColors = {'0':'#000','1':'#00a','2':'#0a0','3':'#0aa','4':'#a00','5':'#a0a','6':'#fa0','7':'#aaa','8':'#555','9':'#55f','a':'#5f5','b':'#5ff','c':'#f55','d':'#f5f','e':'#ff5','f':'#fff'};
                const colorCode = (g.display_color || '§f').replace(/§/g, '');
                let cssColor = '#fff';
                for (let i = 0; i < colorCode.length; i++) { if (mcColors[colorCode[i]]) cssColor = mcColors[colorCode[i]]; }
                const dur = parseInt(g.duration_minutes) || 0;
                let durStr = dur >= 1440 ? Math.floor(dur/1440) + '天' : dur >= 60 ? Math.floor(dur/60) + '小时' : dur + '分钟';
                html += `<div class="item-card" style="border-color:var(--border)">
                    <div style="display:flex;justify-content:space-between;align-items:start">
                        <div class="name" style="color:${cssColor}">${g.display_emoji || ''} ${g.display_name || g.group_name}</div>
                        <div class="price" style="color:var(--green);font-weight:600">${g.join_price}张</div>
                    </div>
                    <div style="color:var(--dim);font-size:12px;margin-top:6px">有效期: ${durStr} · Home:${g.home_limit || 0} · 续费:${g.renew_price}张</div>
                    <div style="margin-top:10px"><button class="btn btn-blue" style="width:100%" onclick="doBuyGroup('${g.group_name}')">购买加入</button></div>
                </div>`;
            });
            html += '</div>';
            div.innerHTML = html;
        } catch(e) {
            div.innerHTML = `<div style="color:var(--red);font-size:13px">加载失败: ${e.message}</div>`;
        }
    }

    async function doBuyGroup(groupName) {
        const g = window._availGroupsMap?.[groupName];
        const price = g ? g.join_price + ' 张债券' : '（价格未知）';
        const dur = g ? (g.duration_minutes == 0 ? '永久' : (g.duration_minutes >= 1440 ? Math.floor(g.duration_minutes/1440) + '天' : Math.floor(g.duration_minutes/60) + '小时')) : '未知';
        if (!showConfirm(`确认付费加入用户组「${g ? (g.display_name || groupName) : groupName}」？\n价格: ${price}\n有效期: ${dur}\n将从债券余额扣费。`)) return;
        try {
            const res = await api('land_api.php', {action: 'buy_group', group: groupName, player: currentPlayer, token: TOKEN});
            if (res.success) {
                showToast(res.message || '加入成功', 'success');
                loadMyGroups();
                loadAvailableGroups();
            } else {
                showToast(res.error || '加入失败', 'error');
            }
        } catch(e) {
            showToast('请求失败: ' + e.message, 'error');
        }
    }

    async function doRenewGroup(groupName) {
        const g = window._availGroupsMap?.[groupName];
        const price = g ? (parseInt(g.renew_price) > 0 ? g.renew_price + ' 张债券' : '免费') : '未知';
        const dur = g && g.duration_minutes ? (g.duration_minutes >= 1440 ? Math.floor(g.duration_minutes/1440) + '天' : Math.floor(g.duration_minutes/60) + '小时') : '由用户组配置决定';
        if (!showConfirm(`确认续费用户组「${g ? (g.display_name || groupName) : groupName}」？\n续费价格: ${price}\n延长有效期: ${dur}\n将从债券余额扣费。`)) return;
        try {
            const res = await api('land_api.php', {action: 'renew_group', group: groupName, player: currentPlayer, token: TOKEN});
            if (res.success) {
                showToast(res.message || '续费请求已提交', 'success');
                loadMyGroups();
            } else {
                showToast(res.error || '续费失败', 'error');
            }
        } catch(e) {
            showToast('请求失败: ' + e.message, 'error');
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

    // ★ 毛玻璃Alert/Confirm弹窗（替代原生alert/confirm）
    let _glassAlertResolve = null;
    let _isGlassPrompt = false;
    function glassAlertResolve(val) {
        document.getElementById('glassAlertOverlay').classList.remove('show');
        // ★ glassPrompt：如果点了确定，返回输入框的值；取消返回false
        if (_isGlassPrompt && val === true) {
            val = document.getElementById('glassAlertInput').value;
        }
        _isGlassPrompt = false;
        if (_glassAlertResolve) { _glassAlertResolve(val); _glassAlertResolve = null; }
    }
    function glassAlert(msg, icon = '⚠️') {
        return new Promise(resolve => {
            _glassAlertResolve = resolve;
            document.getElementById('glassAlertIcon').textContent = icon;
            document.getElementById('glassAlertMsg').textContent = msg;
            document.getElementById('glassAlertMsg').style.display = '';
            document.getElementById('glassAlertLabel').style.display = 'none';
            document.getElementById('glassAlertInput').style.display = 'none';
            document.getElementById('glassAlertHint').style.display = 'none';
            document.getElementById('glassAlertBtns').innerHTML = '<button class="ag-ok" onclick="glassAlertResolve(true)">确定</button>';
            document.getElementById('glassAlertOverlay').classList.add('show');
        });
    }
    function glassConfirm(msg, icon = '❓') {
        return new Promise(resolve => {
            _glassAlertResolve = resolve;
            document.getElementById('glassAlertIcon').textContent = icon;
            document.getElementById('glassAlertMsg').textContent = msg;
            document.getElementById('glassAlertLabel').style.display = 'none';
            document.getElementById('glassAlertInput').style.display = 'none';
            document.getElementById('glassAlertHint').style.display = 'none';
            document.getElementById('glassAlertBtns').innerHTML = '<button class="ag-cancel" onclick="glassAlertResolve(false)">取消</button><button class="ag-ok" onclick="glassAlertResolve(true)">确定</button>';
            document.getElementById('glassAlertOverlay').classList.add('show');
        });
    }
    function glassPrompt(label, currentVal, hint = '') {
        return new Promise(resolve => {
            _glassAlertResolve = resolve;
            _isGlassPrompt = true;
            document.getElementById('glassAlertIcon').textContent = '✏️';
            document.getElementById('glassAlertMsg').style.display = 'none';
            document.getElementById('glassAlertLabel').textContent = label;
            document.getElementById('glassAlertLabel').style.display = '';
            const input = document.getElementById('glassAlertInput');
            input.value = currentVal;
            input.style.display = '';
            input.selectionStart = input.selectionEnd = input.value.length;
            const hintEl = document.getElementById('glassAlertHint');
            if (hint) { hintEl.textContent = '💡 ' + hint; hintEl.style.display = ''; }
            else { hintEl.style.display = 'none'; }
            document.getElementById('glassAlertBtns').innerHTML = '<button class="ag-cancel" onclick="glassAlertResolve(false)">取消</button><button class="ag-ok" onclick="glassAlertResolve(true)">确定</button>';
            document.getElementById('glassAlertOverlay').classList.add('show');
            setTimeout(() => input.focus(), 100);
        });
    }

    // ★ 毛玻璃选择器弹窗（替代文本输入，提供可选项列表）
    function glassSelect(label, options, hint = '') {
        // options: [{value, label, desc?}] 或 string[]（自动转为{value,label}）
        return new Promise(resolve => {
            _glassAlertResolve = resolve;
            _isGlassPrompt = false;
            const icon = document.getElementById('glassAlertIcon');
            icon.textContent = '📋';
            const msgEl = document.getElementById('glassAlertMsg');
            msgEl.style.display = 'none';
            const labelEl = document.getElementById('glassAlertLabel');
            labelEl.textContent = label;
            labelEl.style.display = '';
            const input = document.getElementById('glassAlertInput');
            input.style.display = 'none';
            const hintEl = document.getElementById('glassAlertHint');
            if (hint) { hintEl.textContent = '💡 ' + hint; hintEl.style.display = ''; }
            else { hintEl.style.display = 'none'; }
            // 构建选项列表
            const normalized = options.map(o => typeof o === 'string' ? {value: o, label: o} : o);
            let listHtml = '<div style="max-height:300px;overflow-y:auto;margin:8px 0;display:flex;flex-direction:column;gap:4px">';
            for (const opt of normalized) {
                listHtml += `<div onclick="glassAlertResolve('${opt.value.replace(/'/g, "\\'")}')"
                    style="padding:8px 12px;border:1px solid var(--border);border-radius:8px;cursor:pointer;transition:all 0.15s;font-size:13px;color:var(--fg);background:var(--bg)"
                    onmouseover="this.style.borderColor='var(--accent)';this.style.background='rgba(99,102,241,0.1)'"
                    onmouseout="this.style.borderColor='var(--border)';this.style.background='var(--bg)'">
                    <div style="font-weight:500">${opt.label}</div>
                    ${opt.desc ? `<div style="font-size:11px;color:var(--dim);margin-top:2px">${opt.desc}</div>` : ''}
                </div>`;
            }
            listHtml += '</div>';
            document.getElementById('glassAlertBtns').innerHTML = listHtml + '<button class="ag-cancel" onclick="glassAlertResolve(false)" style="margin-top:8px">取消</button>';
            document.getElementById('glassAlertOverlay').classList.add('show');
        });
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
        if (!await glassConfirm('确定要撤销此工单吗？')) return;
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
                glassAlert(data.message);
            }
        } catch (e) {
            glassAlert('撤销失败: ' + e.message);
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

// ==================== 领地管理 ====================

let landsState = { view: 'list', currentLand: null, currentMember: null };

async function renderLands(el) {
    if (!TOKEN) {
        el.innerHTML = `<div class="card" style="text-align:center;padding:40px">
            <div style="font-size:48px;margin-bottom:16px">🏡</div>
            <h2 style="margin:0 0 8px;color:var(--fg)">领地管理</h2>
            <p style="color:var(--dim);margin-bottom:16px">请先登录以管理你的领地</p>
            <button class="btn btn-blue" onclick="openGlassLogin()">🔑 登录</button>
        </div>`;
        return;
    }

    if (landsState.view === 'detail' && landsState.currentLand) {
        await renderLandDetail(el, landsState.currentLand);
        return;
    }

    if (landsState.view === 'member_perms' && landsState.currentLand) {
        await renderMemberPerms(el, landsState.currentLand);
        return;
    }

    if (landsState.view === 'member_perm_detail' && landsState.currentLand && landsState.currentMember) {
        await renderMemberPermDetail(el, landsState.currentLand, landsState.currentMember);
        return;
    }

    // 加载我的领地列表
    el.innerHTML = `<div class="card"><p style="color:var(--dim)">加载领地中...</p></div>`;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'my_lands');
        url.searchParams.set('token', TOKEN);
        const res = await fetch(url);
        const data = await res.json();

        if (data.needLogin) {
            el.innerHTML = `<div class="card" style="text-align:center;padding:40px">
                <div style="font-size:48px;margin-bottom:16px">🏡</div>
                <h2 style="margin:0 0 8px;color:var(--fg)">领地管理</h2>
                <p style="color:var(--dim);margin-bottom:16px">请先登录以管理你的领地</p>
                <button class="btn btn-blue" onclick="openGlassLogin()">🔑 登录</button>
            </div>`;
            return;
        }

        if (!data.success) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.error}</p></div>`;
            return;
        }

        const lands = data.lands || [];
        let html = `<div class="card">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                <h2 style="margin:0;color:var(--fg)">🏡 我的领地 (${lands.length})</h2>
                <span style="color:var(--dim);font-size:13px">玩家: ${escHtml(data.player)}</span>
            </div>`;

        if (lands.length === 0) {
            html += `<div style="text-align:center;padding:32px;color:var(--dim)">
                <div style="font-size:40px;margin-bottom:12px">🏗️</div>
                <p>你还没有领地</p>
                <p style="font-size:12px;margin-top:8px">在游戏中输入 <code style="background:rgba(88,166,255,0.1);padding:2px 6px;border-radius:4px;color:var(--accent)">/protect create <领地名></code> 来创建领地</p>
            </div>`;
        } else {
            html += `<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px">`;
            for (const land of lands) {
                const areaSize = land.area_size || 0;
                const createdAt = land.created_at ? new Date(land.created_at * 1000).toLocaleDateString('zh-CN') : '未知';
                const coordStr = `(${land.x1},${land.z1}) → (${land.x2},${land.z2})`;
                html += `<div onclick="landsState.view='detail';landsState.currentLand='${escHtml(land.name)}';renderLands(document.getElementById('content'))"
                    style="background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:16px;cursor:pointer;transition:all 0.2s"
                    onmouseover="this.style.borderColor='var(--accent)'" onmouseout="this.style.borderColor='var(--border)'">
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                        <h3 style="margin:0;color:var(--fg);font-size:16px">📍 ${escHtml(land.name)}</h3>
                        <span style="color:var(--green);font-size:11px">✦ 所有者</span>
                    </div>
                    <div style="font-size:13px;color:var(--dim);margin-bottom:4px">🌍 ${escHtml(land.world || '未知')}</div>
                    <div style="font-size:13px;color:var(--dim);margin-bottom:4px">📐 ${coordStr}</div>
                    <div style="display:flex;justify-content:space-between;font-size:12px;color:var(--dim);margin-top:8px">
                        <span>📏 ${areaSize} 格${String.fromCharCode(178)}</span>
                        <span>📅 ${createdAt}</span>
                    </div>
                </div>`;
            }
            html += `</div>`;
        }
        html += `</div>`;

        // 领地商店
        html += `<div class="card" style="margin-top:12px">
            <h3 style="margin:0 0 12px;color:var(--fg)">🛍️ 领地权限商店</h3>`;
        try {
            const shopUrl = new URL(API + 'land_api.php', location.href);
            shopUrl.searchParams.set('action', 'land_shop');
            const shopRes = await fetch(shopUrl);
            const shopData = await shopRes.json();
            if (shopData.success && shopData.items && shopData.items.length > 0) {
                html += `<table class="table"><thead><tr>
                    <th>领地</th><th>卖家</th><th>权限</th><th>价格(债券)</th><th>时长</th><th>操作</th>
                </tr></thead><tbody>`;
                for (const item of shopData.items) {
                    const dur = item.duration >= 86400 ? Math.floor(item.duration/86400)+'天' : Math.floor(item.duration/3600)+'小时';
                    html += `<tr>
                        <td>${escHtml(item.land_name)}</td>
                        <td>${escHtml(item.seller)}</td>
                        <td><span style="color:var(--accent)">${escHtml(item.permission)}</span></td>
                        <td style="color:var(--green)">${item.price}</td>
                        <td>${dur}</td>
                        <td><button class="btn btn-blue" style="font-size:11px;padding:3px 8px" onclick="buyLandPermission('${escHtml(item.land_name)}',${item.id})">购买</button></td>
                    </tr>`;
                }
                html += `</tbody></table>`;
            } else {
                html += `<p style="color:var(--dim);font-size:13px">暂无在售的领地权限</p>`;
            }
        } catch (e) {
            html += `<p style="color:var(--dim);font-size:13px">商店加载失败</p>`;
        }
        html += `</div>`;

        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function renderLandDetail(el, landName) {
    el.innerHTML = `<div class="card"><p style="color:var(--dim)">加载领地详情...</p></div>`;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'land_detail');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('name', landName);
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.error}</p><button class="btn" onclick="landsState.view='list';landsState.currentLand=null;renderLands(document.getElementById('content'))">← 返回</button></div>`;
            return;
        }

        const land = data.land;
        const visitors = data.visitors || [];
        const coordStr = `(${land.x1},${land.z1}) → (${land.x2},${land.z2})`;

        let html = `<div class="card">
            <button class="btn" onclick="landsState.view='list';landsState.currentLand=null;renderLands(document.getElementById('content'))" style="margin-bottom:12px;font-size:13px">← 返回我的领地</button>
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px">
                <h2 style="margin:0;color:var(--fg)">📍 ${escHtml(land.name)}</h2>
            </div>
            <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;margin-bottom:16px">
                <div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:12px">
                    <div style="color:var(--dim);font-size:12px;margin-bottom:4px">🌍 世界</div>
                    <div style="color:var(--fg);font-size:14px">${escHtml(land.world || '未知')}</div>
                </div>
                <div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:12px">
                    <div style="color:var(--dim);font-size:12px;margin-bottom:4px">📐 坐标</div>
                    <div style="color:var(--fg);font-size:14px">${coordStr}</div>
                </div>
                <div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:12px">
                    <div style="color:var(--dim);font-size:12px;margin-bottom:4px">📏 面积</div>
                    <div style="color:var(--fg);font-size:14px">${land.area_size || 0} 格${String.fromCharCode(178)}</div>
                </div>
                <div style="background:var(--bg);border:1px solid var(--border);border-radius:8px;padding:12px">
                    <div style="color:var(--dim);font-size:12px;margin-bottom:4px">📅 创建时间</div>
                    <div style="color:var(--fg);font-size:14px">${land.created_at ? new Date(land.created_at*1000).toLocaleDateString('zh-CN') : '未知'}</div>
                </div>
            </div>`;

        // 访客管理
        html += `<div style="margin-bottom:16px">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                <h3 style="margin:0;color:var(--fg)">👥 访客管理 (${visitors.length})</h3>
            </div>
            <div style="display:flex;gap:8px;margin-bottom:12px">
                <input type="text" id="newVisitorName" placeholder="输入玩家名" style="flex:1;padding:8px 12px;border:1px solid var(--border);border-radius:6px;background:var(--bg);color:var(--text);font-size:13px">
                <button class="btn btn-blue" onclick="addLandVisitor('${escHtml(land.name)}')">添加访客</button>
            </div>`;

        if (visitors.length === 0) {
            html += `<p style="color:var(--dim);font-size:13px">暂无访客</p>`;
        } else {
            html += `<table class="table"><thead><tr><th>玩家名</th><th>角色</th><th>授权时间</th><th>操作</th></tr></thead><tbody>`;
            for (const v of visitors) {
                const grantDate = v.granted_at ? new Date(v.granted_at * 1000).toLocaleString('zh-CN') : '未知';
                const roleMap = {admin: {label:'👑 管理员', color:'var(--accent)', bg:'rgba(99,102,241,0.15)'}, member: {label:'🔧 成员', color:'#f59e0b', bg:'rgba(245,158,11,0.15)'}, visitor: {label:'👤 访客', color:'var(--dim)', bg:'rgba(107,114,128,0.1)'}};
                const r = roleMap[v.role] || roleMap.member;
                html += `<tr>
                    <td><strong>${escHtml(v.player_name)}</strong> <button class="btn" style="font-size:10px;padding:1px 6px;color:var(--accent);border-color:var(--accent)" onclick="event.stopPropagation();landsState.view='member_perm_detail';landsState.currentLand='${escHtml(land.name)}';landsState.currentMember='${escHtml(v.player_name)}';renderLands(document.getElementById('content'))">权限</button></td>
                    <td><select onchange="if(this.value &&this.value!=='${v.role}')changeVisitorRole('${escHtml(land.name)}','${escHtml(v.player_name)}',this.value);this.value='${v.role}'"
                        style="cursor:pointer;padding:2px 6px;border-radius:12px;font-size:12px;background:${r.bg};color:${r.color};border:1px solid ${r.color};transition:all 0.2s;appearance:auto;-webkit-appearance:auto">
                        <option value="admin" ${v.role==='admin'?'selected':''}>👑 管理员</option>
                        <option value="member" ${v.role==='member'?'selected':''}>🔧 成员</option>
                    </select></td>
                    <td style="font-size:12px;color:var(--dim)">${grantDate}</td>
                    <td><button class="btn" style="color:var(--red);font-size:11px;padding:3px 8px" onclick="removeLandVisitor('${escHtml(land.name)}','${escHtml(v.player_name)}')">移除</button></td>
                </tr>`;
            }
            html += `</tbody></table>`;
        }
        html += `</div></div>`;

        // ★★★ 效果管理 ★★★
        let clearEffects = [];
        let giveEffects = [];
        try { clearEffects = land.clear_effects ? JSON.parse(land.clear_effects) : []; } catch(e) { clearEffects = []; }
        try { giveEffects = land.give_effects ? JSON.parse(land.give_effects) : []; } catch(e) { giveEffects = []; }
        const clearAllBad = !!parseInt(land.clear_all_bad || 0);
        const denyAll = !!parseInt(land.deny_all_effects || 0);

        html += `<div class="card" style="margin-top:12px">
            <h3 style="margin:0 0 12px;color:var(--fg)">✨ 效果管理</h3>
            <div style="display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap">
                <span onclick="toggleLandField('${escHtml(land.name)}','clear_all_bad')"
                    style="padding:6px 14px;border-radius:20px;font-size:12px;cursor:pointer;transition:all 0.2s;
                    background:${clearAllBad ? 'var(--green)' : 'var(--bg)'};color:${clearAllBad ? '#fff' : 'var(--dim)'};
                    border:1px solid ${clearAllBad ? 'var(--green)' : 'var(--border)'}">
                    🧹 全清负面 ${clearAllBad ? '✓' : ''}
                </span>
                <span onclick="toggleLandField('${escHtml(land.name)}','deny_all_effects')"
                    style="padding:6px 14px;border-radius:20px;font-size:12px;cursor:pointer;transition:all 0.2s;
                    background:${denyAll ? 'var(--accent)' : 'var(--bg)'};color:${denyAll ? '#fff' : 'var(--dim)'};
                    border:1px solid ${denyAll ? 'var(--accent)' : 'var(--border)'}">
                    🚫 禁止所有效果 ${denyAll ? '✓' : ''}
                </span>
            </div>`;

        // 清除效果列表
        html += `<div style="margin-bottom:12px">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                <span style="color:var(--fg);font-size:13px;font-weight:500">清除效果 (${clearEffects.length})</span>
                <button class="btn" style="font-size:11px;padding:2px 8px" onclick="addClearEffect('${escHtml(land.name)}')">+ 添加</button>
            </div>`;
        if (clearEffects.length === 0) {
            html += `<p style="color:var(--dim);font-size:12px">暂无清除效果</p>`;
        } else {
            html += `<div style="display:flex;gap:6px;flex-wrap:wrap">`;
            for (const eff of clearEffects) {
                html += `<span style="background:rgba(239,68,68,0.1);border:1px solid rgba(239,68,68,0.3);border-radius:12px;padding:3px 10px;font-size:12px;color:var(--fg)">
                    ${escHtml(eff)}
                    <span onclick="removeLandEffect('${escHtml(land.name)}','clear_effects','${escHtml(eff)}')" style="cursor:pointer;color:var(--red);margin-left:4px">✕</span>
                </span>`;
            }
            html += `</div>`;
        }
        html += `</div>`;

        // 增益效果列表
        html += `<div style="margin-bottom:12px">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                <span style="color:var(--fg);font-size:13px;font-weight:500">增益效果 (${giveEffects.length})</span>
                <button class="btn" style="font-size:11px;padding:2px 8px" onclick="addGiveEffect('${escHtml(land.name)}')">+ 添加</button>
            </div>`;
        if (giveEffects.length === 0) {
            html += `<p style="color:var(--dim);font-size:12px">暂无增益效果</p>`;
        } else {
            html += `<div style="display:flex;gap:6px;flex-wrap:wrap">`;
            for (const eff of giveEffects) {
                const effName = Array.isArray(eff) ? eff[0] : eff;
                const effLevel = Array.isArray(eff) && eff[1] ? ' Lv' + eff[1] : '';
                const effDur = Array.isArray(eff) && eff[2] ? ' ' + eff[2] + 's' : '';
                html += `<span style="background:rgba(34,197,94,0.1);border:1px solid rgba(34,197,94,0.3);border-radius:12px;padding:3px 10px;font-size:12px;color:var(--fg)">
                    ${escHtml(effName)}${effLevel}${effDur}
                    <span onclick="removeLandEffect('${escHtml(land.name)}','give_effects','${escHtml(Array.isArray(eff) ? JSON.stringify(eff) : eff)}')" style="cursor:pointer;color:var(--red);margin-left:4px">✕</span>
                </span>`;
            }
            html += `</div>`;
        }
        html += `</div>`;

        // ★★★ 领地权限开关面板（与Java端GUI访客权限列表完全对齐） ★★★
        const permDefs = [
            // [字段名, 显示名, 是否反转(allow类)]
            ['deny_move', '移动', false],
            ['deny_block_place', '放置方块', false],
            ['deny_block_break', '破坏方块', false],
            ['deny_entity_interact', '实体交互(船/矿车/盔甲架/展示框)', false],
            ['deny_container', '容器管理', false],
            ['deny_pvp', '玩家对战', false],
            ['deny_mount', '骑乘坐具', false],
            ['deny_ender_pearl', '投掷末影珍珠', false],
            ['deny_thrown_projectiles', '投掷物(三叉戟/雪球/风蛋)', false],
            ['deny_raid', '袭击', false],
            ['deny_bow', '弓箭射击', false],
            ['deny_potion', '药水效果', false],
            ['deny_fire', '点燃', false],
            ['deny_fire_spread', '火焰蔓延', false],
            ['deny_pickup', '拾取物品', true],   // allowPickup: true=允许
            ['deny_drop', '丢弃物品', true],     // allowDrop: true=允许
            ['deny_explosion', '爆炸', false],
            ['deny_fall_damage', '摔落伤害', false],
            ['deny_hunger', '饥饿', false],
            ['deny_all_damage', '所有伤害', false],
            ['deny_all_effects', '所有效果', false],
            ['deny_item_frame', '展示框交互', false],
            ['deny_glowing', '玩家发光', false],
            ['deny_redstone_interaction', '红石电路(按钮/压力板/中继器)', false],
            ['deny_door_interaction', '门禁交互(门/栅栏门)', false],
            ['deny_noteblock_jukebox', '音频(音符盒/唱片机)', false],
            ['deny_lead', '拴绳使用', false],
            ['deny_crop_harvest', '农作物收获', false],
            ['deny_wool_shear', '剪切羊毛/生物', false],
            ['deny_animal_feeding', '投喂动物', false],
            ['deny_mob_attack', '攻击生物', false],
            ['deny_fluid', '流体放置', false],
            ['allow_visitor_teleport', '允许传送', true],
            ['is_public_building', '公共建筑设施', true],
            ['peace_mode', '和平模式(禁生物)', true],
        ];

        html += `<div class="card" style="margin-top:12px">
            <h3 style="margin:0 0 12px;color:var(--fg)">🛡️ 领地权限</h3>
            <p style="color:var(--dim);font-size:12px;margin-bottom:12px">点击切换权限状态。启用=允许该行为，禁用=阻止该行为。</p>
            <div style="display:flex;gap:6px;flex-wrap:wrap">`;

        for (const [field, label, isInverted] of permDefs) {
            const rawVal = parseInt(land[field] || 0);
            // 反转类(allow_*/is_public)：1=启用；deny类：0=启用
            const isEnabled = isInverted ? (rawVal === 1) : (rawVal === 0);
            const bgColor = isEnabled ? 'rgba(34,197,94,0.15)' : 'rgba(239,68,68,0.1)';
            const borderColor = isEnabled ? 'rgba(34,197,94,0.4)' : 'rgba(239,68,68,0.3)';
            const textColor = isEnabled ? '#22c55e' : '#ef4444';
            const icon = isEnabled ? '✓' : '✕';

            html += `<span onclick="togglePerm('${escHtml(land.name)}','${field}',${isInverted})"
                style="padding:5px 12px;border-radius:16px;font-size:12px;cursor:pointer;transition:all 0.2s;
                background:${bgColor};color:${textColor};
                border:1px solid ${borderColor}">
                ${icon} ${escHtml(label)}
            </span>`;
        }

        html += `</div></div>`;

        html += `</div>`;

        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function renderMemberPerms(el, landName) {
    el.innerHTML = `<div class="card"><p style="color:var(--dim)">加载成员权限...</p></div>`;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'get_member_perms');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('land', landName);
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.error}</p><button class="btn" onclick="landsState.view='detail';renderLands(document.getElementById('content'))">← 返回</button></div>`;
            return;
        }

        const land = data.land;
        const members = data.members || [];
        const permTypes = data.perm_types || {};

        let html = `<div class="card">
            <button class="btn" onclick="landsState.view='detail';landsState.currentLand='${escHtml(landName)}';landsState.currentMember=null;renderLands(document.getElementById('content'))" style="margin-bottom:12px;font-size:13px">← 返回领地详情</button>
            <h2 style="margin:0 0 8px;color:var(--fg)">🎯 ${escHtml(landName)} 成员权限</h2>
            <p style="color:var(--dim);font-size:13px;margin-bottom:16px">为每个成员设置独立的权限覆盖。启用=覆盖默认值，禁用=使用领地默认值。</p>`;

        if (members.length === 0) {
            html += `<div style="text-align:center;padding:32px;color:var(--dim)">
                <div style="font-size:40px;margin-bottom:12px">👥</div>
                <p>暂无成员</p>
                <p style="font-size:12px;margin-top:8px">请先在访客管理中添加成员</p>
            </div>`;
        } else {
            html += `<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px">`;
            for (const member of members) {
                const permCount = member.perm_map ? Object.keys(member.perm_map).length : 0;
                const hasCustom = permCount > 0;
                html += `<div onclick="landsState.view='member_perm_detail';landsState.currentMember='${escHtml(member.player_name)}';renderLands(document.getElementById('content'))"
                    style="background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:16px;cursor:pointer;transition:all 0.2s"
                    onmouseover="this.style.borderColor='var(--accent)'" onmouseout="this.style.borderColor='var(--border)'">
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                        <h3 style="margin:0;color:var(--fg);font-size:15px">👤 ${escHtml(member.player_name)}</h3>
                        ${hasCustom ? '<span style="background:var(--accent);color:#fff;padding:2px 8px;border-radius:12px;font-size:11px">★ 自定义</span>' : '<span style="color:var(--dim);font-size:12px">默认权限</span>'}
                    </div>
                    <div style="font-size:12px;color:var(--dim)">${permCount} 项自定义权限</div>
                </div>`;
            }
            html += `</div>`;
        }
        html += `</div>`;
        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function addLandVisitor(landName) {
    const input = document.getElementById('newVisitorName');
    if (!input) return;
    const visitor = input.value.trim();
    if (!visitor) return;

    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'add_visitor');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('name', landName);
        url.searchParams.set('visitor', visitor);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success) {
            // ★ 参考改领地主流程：异步验证，轮询状态
            if (data.pending) {
                glassAlert('添加成员请求已提交，正在验证玩家...');
                // 轮询等待验证结果（65秒超时）
                let pollCount = 0;
                const maxPolls = 13; // 65秒 / 5秒
                const pollInterval = setInterval(async () => {
                    pollCount++;
                    try {
                        const statusUrl = new URL(API + 'land_api.php', location.href);
                        statusUrl.searchParams.set('action', 'get_add_visitor_status');
                        statusUrl.searchParams.set('name', landName);
                        statusUrl.searchParams.set('token', TOKEN);
                        const statusRes = await fetch(statusUrl);
                        const statusData = await statusRes.json();
                        if (statusData.status === 'completed') {
                            clearInterval(pollInterval);
                            glassAlert('添加成员成功！');
                            landsState.currentLand = landName;
                            await renderLandDetail(document.getElementById('content'), landName);
                        } else if (statusData.status === 'failed') {
                            clearInterval(pollInterval);
                            glassAlert('添加成员失败: ' + (statusData.reason || '玩家可能不存在'));
                        } else if (pollCount >= maxPolls) {
                            clearInterval(pollInterval);
                            glassAlert('验证超时，请稍后刷新查看');
                        }
                    } catch (e) {
                        // 继续轮询
                    }
                }, 5000);
            } else {
                landsState.currentLand = landName;
                await renderLandDetail(document.getElementById('content'), landName);
            }
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function changeVisitorRole(landName, player, newRole) {
    const roleLabels = {admin: '管理员', member: '成员'};
    const roleLabel = roleLabels[newRole] || newRole;
    if (!await glassConfirm(`确定将 ${player} 设为${roleLabel}吗？${newRole === 'admin' ? '\n管理员将拥有领地管理权限！' : ''}${newRole === 'member' ? '\n成员将降级为普通成员。' : ''}`)) return;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        const body = new URLSearchParams();
        body.set('action', 'change_visitor_role');
        body.set('token', TOKEN);
        body.set('name', landName);
        body.set('visitor', player);
        body.set('role', newRole);
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function removeLandVisitor(landName, player) {
    if (!await glassConfirm(`确定要移除访客 ${player} 吗？`)) return;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'remove_visitor');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('name', landName);
        url.searchParams.set('visitor', player);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

function buyLandPermission(landName, itemId) {
    glassAlert('请在游戏中使用 /protect shop 购买领地权限', '🎮');
}

// ★★★ 效果管理辅助函数 ★★★

async function toggleLandField(landName, field) {
    try {
        // 先读取当前状态
        const detailUrl = new URL(API + 'land_api.php', location.href);
        detailUrl.searchParams.set('action', 'land_detail');
        detailUrl.searchParams.set('token', TOKEN);
        detailUrl.searchParams.set('name', landName);
        const detailRes = await fetch(detailUrl);
        const detailData = await detailRes.json();
        if (!detailData.success) { glassAlert(detailData.error); return; }

        const currentVal = !!parseInt(detailData.land[field] || 0);
        const newVal = currentVal ? '0' : '1';

        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_land_field');
        url.searchParams.set('token', TOKEN);
        const body = new URLSearchParams();
        body.set('name', landName);
        body.set('field', field);
        body.set('value', newVal);
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

// ★ 权限开关切换（处理deny/allow反转逻辑）
// isInverted=true时：DB值1=禁用(显示红)，DB值0=启用(显示绿)
// isInverted=false时：DB值1=禁用(显示红)，DB值0=启用(显示绿) — 相同逻辑，反转体现在发送值
async function togglePerm(landName, field, isInverted) {
    try {
        // 读取当前值
        const detailUrl = new URL(API + 'land_api.php', location.href);
        detailUrl.searchParams.set('action', 'land_detail');
        detailUrl.searchParams.set('token', TOKEN);
        detailUrl.searchParams.set('name', landName);
        const detailRes = await fetch(detailUrl);
        const detailData = await detailRes.json();
        if (!detailData.success) { glassAlert(detailData.error); return; }

        const currentDbVal = parseInt(detailData.land[field] || 0);
        // deny类：当前0(允许)→发送1(禁止)；allow类：当前0(禁止)→发送1(允许)
        // 统一取反即可
        const newVal = currentDbVal ? '0' : '1';

        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_land_field');
        url.searchParams.set('token', TOKEN);
        const body = new URLSearchParams();
        body.set('name', landName);
        body.set('field', field);
        body.set('value', newVal);
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function addClearEffect(landName) {
    // MC药水效果列表（用户选择而非手动输入）
    const EFFECTS = [
        {value:'SPEED',label:'🏃 速度',desc:'SPEED - 提升移动速度'},
        {value:'SLOWNESS',label:'🐌 缓慢',desc:'SLOWNESS - 降低移动速度'},
        {value:'HASTE',label:'⛏️ 急迫',desc:'HASTE - 提升挖掘和攻击速度'},
        {value:'MINING_FATIGUE',label:'🔧 疲劳',desc:'MINING_FATIGUE - 降低挖掘速度'},
        {value:'STRENGTH',label:'💪 力量',desc:'STRENGTH - 提升近战伤害'},
        {value:'JUMP_BOOST',label:'🦘 跳跃提升',desc:'JUMP_BOOST - 提升跳跃高度'},
        {value:'NAUSEA',label:'🤢 反胃',desc:'NAUSEA - 视角扭曲'},
        {value:'REGENERATION',label:'❤️ 再生',desc:'REGENERATION - 缓慢恢复生命'},
        {value:'RESISTANCE',label:'🛡️ 抗性提升',desc:'RESISTANCE - 减少受到的伤害'},
        {value:'FIRE_RESISTANCE',label:'🔥 抗火',desc:'FIRE_RESISTANCE - 免疫火焰伤害'},
        {value:'WATER_BREATHING',label:'🐠 水下呼吸',desc:'WATER_BREATHING - 水下不消耗氧气'},
        {value:'INVISIBILITY',label:'👻 隐身',desc:'INVISIBILITY - 对其他玩家隐身'},
        {value:'BLINDNESS',label:' blinded 失明',desc:'BLINDNESS - 屏幕全黑，无法看到远处'},
        {value:'NIGHT_VISION',label:'🦉 夜视',desc:'NIGHT_VISION - 黑暗中看清一切'},
        {value:'WEAKNESS',label:'😵 虚弱',desc:'WEAKNESS - 降低近战伤害'},
        {value:'POISON',label:'☠️ 中毒',desc:'POISON - 持续失去生命'},
        {value:'WITHER',label:'💀 凋零',desc:'WITHER - 持续失去生命（更严重）'},
        {value:'HEALTH_BOOST',label:'💖 生命提升',desc:'HEALTH_BOOST - 增加最大生命值'},
        {value:'ABSORPTION',label:'🥇 吸收',desc:'ABSORPTION - 提供额外黄色生命值'},
        {value:'SATURATION',label:'🍖 饱和',desc:'SATURATION - 恢复饥饿值'},
        {value:'GLOWING',label:'✨ 发光',desc:'GLOWING - 对所有人高亮显示'},
        {value:'LEVITATION',label:'🎈 漂浮',desc:'LEVITATION - 向上飘浮'},
        {value:'SLOW_FALLING',label:'🪂 缓降',desc:'SLOW_FALLING - 下落速度减慢'},
        {value:'LUCK',label:'🍀 幸运',desc:'LUCK - 提升战利品品质'},
        {value:'UNLUCK',label:'🤕 厄运',desc:'UNLUCK - 降低战利品品质'},
        {value:'DOLPHINS_GRACE',label:'🐬 海豚的恩惠',desc:'DOLPHINS_GRACE - 水下加速游泳'},
        {value:'CONDUIT_POWER',label:'🔷 潮涌能量',desc:'CONDUIT_POWER - 水下夜视+呼吸+速掘'},
        {value:'BAD_OMEN',label:'💀 不祥之兆',desc:'BAD_OMEN - 进入村庄触发袭击'},
        {value:'HERO_OF_THE_VILLAGE',label:'🏆 村庄英雄',desc:'HERO_OF_THE_VILLAGE - 村民打折'}
    ];

    // 获取当前已有的清除效果，过滤掉已选的
    try {
        const detailUrl = new URL(API + 'land_api.php', location.href);
        detailUrl.searchParams.set('action', 'land_detail');
        detailUrl.searchParams.set('token', TOKEN);
        detailUrl.searchParams.set('name', landName);
        const detailRes = await fetch(detailUrl);
        const detailData = await detailRes.json();
        if (!detailData.success) { glassAlert(detailData.error); return; }
        let current = [];
        try { current = detailData.land.clear_effects ? JSON.parse(detailData.land.clear_effects) : []; } catch(e) { current = []; }

        // 过滤掉已添加的效果（兼容中文名和英文名）
        const EN_TO_CN = {SPEED:'速度',SLOWNESS:'缓慢',HASTE:'急迫',MINING_FATIGUE:'挖掘疲劳',STRENGTH:'力量',JUMP_BOOST:'跳跃提升',NAUSEA:'反胃',REGENERATION:'再生',RESISTANCE:'抗性提升',FIRE_RESISTANCE:'抗火',WATER_BREATHING:'水下呼吸',INVISIBILITY:'隐身',BLINDNESS:'失明',NIGHT_VISION:'夜视',WEAKNESS:'虚弱',POISON:'中毒',WITHER:'凋零',HEALTH_BOOST:'生命提升',ABSORPTION:'吸收',SATURATION:'饱和',GLOWING:'发光',LEVITATION:'漂浮',SLOW_FALLING:'缓降',LUCK:'幸运',UNLUCK:'厄运',DOLPHINS_GRACE:'海豚的恩惠',CONDUIT_POWER:'潮涌能量',BAD_OMEN:'不祥之兆',HERO_OF_THE_VILLAGE:'村庄英雄',INSTANT_HEALTH:'瞬间治疗',INSTANT_DAMAGE:'瞬间伤害',WORLD_BORDER:'边界',DARKNESS:'黑暗'};
        const available = EFFECTS.filter(e => !current.includes(e.value) && !current.includes(EN_TO_CN[e.value]));
        if (available.length === 0) { glassAlert('所有效果都已添加'); return; }

        const eff = await glassSelect('选择要清除的效果', available, '点击选择效果，将在领地内自动清除该效果');
        if (!eff || eff === false) return;

        const cnName = EN_TO_CN[eff] || eff;
        if (current.includes(cnName) || current.includes(eff)) { glassAlert('该效果已在清除列表中'); return; }
        current.push(cnName);

        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_land_field');
        url.searchParams.set('token', TOKEN);
        const body = new URLSearchParams();
        body.set('name', landName);
        body.set('field', 'clear_effects');
        body.set('value', JSON.stringify(current));
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function addGiveEffect(landName) {
    // MC药水效果列表
    const EFFECTS = [
        {value:'SPEED',label:'🏃 速度',desc:'SPEED - 提升移动速度'},
        {value:'SLOWNESS',label:'🐌 缓慢',desc:'SLOWNESS - 降低移动速度'},
        {value:'HASTE',label:'⛏️ 急迫',desc:'HASTE - 提升挖掘和攻击速度'},
        {value:'MINING_FATIGUE',label:'🔧 疲劳',desc:'MINING_FATIGUE - 降低挖掘速度'},
        {value:'STRENGTH',label:'💪 力量',desc:'STRENGTH - 提升近战伤害'},
        {value:'JUMP_BOOST',label:'🦘 跳跃提升',desc:'JUMP_BOOST - 提升跳跃高度'},
        {value:'NAUSEA',label:'🤢 反胃',desc:'NAUSEA - 视角扭曲'},
        {value:'REGENERATION',label:'❤️ 再生',desc:'REGENERATION - 缓慢恢复生命'},
        {value:'RESISTANCE',label:'🛡️ 抗性提升',desc:'RESISTANCE - 减少受到的伤害'},
        {value:'FIRE_RESISTANCE',label:'🔥 抗火',desc:'FIRE_RESISTANCE - 免疫火焰伤害'},
        {value:'WATER_BREATHING',label:'🐠 水下呼吸',desc:'WATER_BREATHING - 水下不消耗氧气'},
        {value:'INVISIBILITY',label:'👻 隐身',desc:'INVISIBILITY - 对其他玩家隐身'},
        {value:'BLINDNESS',label:'🌑 失明',desc:'BLINDNESS - 屏幕全黑，无法看到远处'},
        {value:'NIGHT_VISION',label:'🦉 夜视',desc:'NIGHT_VISION - 黑暗中看清一切'},
        {value:'WEAKNESS',label:'😵 虚弱',desc:'WEAKNESS - 降低近战伤害'},
        {value:'POISON',label:'☠️ 中毒',desc:'POISON - 持续失去生命'},
        {value:'WITHER',label:'💀 凋零',desc:'WITHER - 持续失去生命（更严重）'},
        {value:'HEALTH_BOOST',label:'💖 生命提升',desc:'HEALTH_BOOST - 增加最大生命值'},
        {value:'ABSORPTION',label:'🥇 吸收',desc:'ABSORPTION - 提供额外黄色生命值'},
        {value:'SATURATION',label:'🍖 饱和',desc:'SATURATION - 恢复饥饿值'},
        {value:'GLOWING',label:'✨ 发光',desc:'GLOWING - 对所有人高亮显示'},
        {value:'LEVITATION',label:'🎈 漂浮',desc:'LEVITATION - 向上飘浮'},
        {value:'SLOW_FALLING',label:'🪂 缓降',desc:'SLOW_FALLING - 下落速度减慢'},
        {value:'LUCK',label:'🍀 幸运',desc:'LUCK - 提升战利品品质'},
        {value:'UNLUCK',label:'🤕 厄运',desc:'UNLUCK - 降低战利品品质'},
        {value:'DOLPHINS_GRACE',label:'🐬 海豚的恩惠',desc:'DOLPHINS_GRACE - 水下加速游泳'},
        {value:'CONDUIT_POWER',label:'🔷 潮涌能量',desc:'CONDUIT_POWER - 水下夜视+呼吸+速掘'},
        {value:'BAD_OMEN',label:'💀 不祥之兆',desc:'BAD_OMEN - 进入村庄触发袭击'},
        {value:'HERO_OF_THE_VILLAGE',label:'🏆 村庄英雄',desc:'HERO_OF_THE_VILLAGE - 村民打折'}
    ];

    try {
        const detailUrl = new URL(API + 'land_api.php', location.href);
        detailUrl.searchParams.set('action', 'land_detail');
        detailUrl.searchParams.set('token', TOKEN);
        detailUrl.searchParams.set('name', landName);
        const detailRes = await fetch(detailUrl);
        const detailData = await detailRes.json();
        if (!detailData.success) { glassAlert(detailData.error); return; }
        let current = [];
        try { current = detailData.land.give_effects ? JSON.parse(detailData.land.give_effects) : []; } catch(e) { current = []; }

        // 过滤掉已添加的效果（兼容中文名和英文名）
        const EN_TO_CN = {SPEED:'速度',SLOWNESS:'缓慢',HASTE:'急迫',MINING_FATIGUE:'挖掘疲劳',STRENGTH:'力量',JUMP_BOOST:'跳跃提升',NAUSEA:'反胃',REGENERATION:'再生',RESISTANCE:'抗性提升',FIRE_RESISTANCE:'抗火',WATER_BREATHING:'水下呼吸',INVISIBILITY:'隐身',BLINDNESS:'失明',NIGHT_VISION:'夜视',WEAKNESS:'虚弱',POISON:'中毒',WITHER:'凋零',HEALTH_BOOST:'生命提升',ABSORPTION:'吸收',SATURATION:'饱和',GLOWING:'发光',LEVITATION:'漂浮',SLOW_FALLING:'缓降',LUCK:'幸运',UNLUCK:'厄运',DOLPHINS_GRACE:'海豚的恩惠',CONDUIT_POWER:'潮涌能量',BAD_OMEN:'不祥之兆',HERO_OF_THE_VILLAGE:'村庄英雄',INSTANT_HEALTH:'瞬间治疗',INSTANT_DAMAGE:'瞬间伤害',WORLD_BORDER:'边界',DARKNESS:'黑暗'};
        const existingNames = current.map(e => Array.isArray(e) ? e[0] : e);
        const available = EFFECTS.filter(e => !existingNames.includes(e.value) && !existingNames.includes(EN_TO_CN[e.value]));
        if (available.length === 0) { glassAlert('所有效果都已添加'); return; }

        const eff = await glassSelect('选择要添加的增益效果', available, '点击选择效果');
        if (!eff || eff === false) return;

        const levelStr = await glassPrompt('等级 (1-255)', '1', '默认1级，管理员最高255级');
        if (levelStr === null || levelStr === false) return;
        const level = parseInt(levelStr) || 1;

        const durStr = await glassPrompt('时长（秒）', '300', '默认300秒，最长3600秒');
        if (durStr === null || durStr === false) return;
        const duration = parseInt(durStr) || 300;

        // ★ 存储中文效果名（Java端resolveEffectType使用中文→英文映射）
        const cnName = EN_TO_CN[eff] || eff;
        current.push([cnName, Math.min(Math.max(level,1),255), Math.min(Math.max(duration,1),3600)]);

        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_land_field');
        url.searchParams.set('token', TOKEN);
        const body = new URLSearchParams();
        body.set('name', landName);
        body.set('field', 'give_effects');
        body.set('value', JSON.stringify(current));
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function removeLandEffect(landName, field, effValue) {
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_land_field');
        url.searchParams.set('token', TOKEN);
        const detailUrl = new URL(API + 'land_api.php', location.href);
        detailUrl.searchParams.set('action', 'land_detail');
        detailUrl.searchParams.set('token', TOKEN);
        detailUrl.searchParams.set('name', landName);
        const detailRes = await fetch(detailUrl);
        const detailData = await detailRes.json();
        if (!detailData.success) { glassAlert(detailData.error); return; }

        let current = [];
        try { current = detailData.land[field] ? JSON.parse(detailData.land[field]) : []; } catch(e) { current = []; }
        if (field === 'clear_effects') {
            // clear_effects: 效果名可能是中文或英文，两种都匹配
            const EN_TO_CN = {SPEED:'速度',SLOWNESS:'缓慢',HASTE:'急迫',MINING_FATIGUE:'挖掘疲劳',STRENGTH:'力量',JUMP_BOOST:'跳跃提升',NAUSEA:'反胃',REGENERATION:'再生',RESISTANCE:'抗性提升',FIRE_RESISTANCE:'抗火',WATER_BREATHING:'水下呼吸',INVISIBILITY:'隐身',BLINDNESS:'失明',NIGHT_VISION:'夜视',WEAKNESS:'虚弱',POISON:'中毒',WITHER:'凋零',HEALTH_BOOST:'生命提升',ABSORPTION:'吸收',SATURATION:'饱和',GLOWING:'发光',LEVITATION:'漂浮',SLOW_FALLING:'缓降',LUCK:'幸运',UNLUCK:'厄运',DOLPHINS_GRACE:'海豚的恩惠',CONDUIT_POWER:'潮涌能量',BAD_OMEN:'不祥之兆',HERO_OF_THE_VILLAGE:'村庄英雄',INSTANT_HEALTH:'瞬间治疗',INSTANT_DAMAGE:'瞬间伤害',WORLD_BORDER:'边界',DARKNESS:'黑暗'};
            const cnName = EN_TO_CN[effValue] || effValue;
            current = current.filter(e => e !== effValue && e !== cnName);
        } else {
            // give_effects: 通过名称或完整JSON匹配
            current = current.filter(e => {
                const name = Array.isArray(e) ? e[0] : e;
                const jsonStr = JSON.stringify(e);
                return jsonStr !== effValue && name !== effValue;
            });
        }

        const body = new URLSearchParams();
        body.set('name', landName);
        body.set('field', field);
        body.set('value', JSON.stringify(current));
        const res = await fetch(url, { method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body.toString() });
        const data = await res.json();
        if (data.success) {
            landsState.currentLand = landName;
            await renderLandDetail(document.getElementById('content'), landName);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function renderMemberPermDetail(el, landName, targetPlayer) {
    el.innerHTML = `<div class="card"><p style="color:var(--dim)">加载权限详情...</p></div>`;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'get_member_perms');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('land', landName);
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.error}</p><button class="btn" onclick="landsState.view='member_perms';renderLands(document.getElementById('content'))">← 返回</button></div>`;
            return;
        }

        const land = data.land;
        const permTypes = data.perm_types || {};
        const members = data.members || [];
        const member = members.find(m => m.player_name === targetPlayer);

        if (!member) {
            el.innerHTML = `<div class="card"><p style="color:var(--red)">成员不存在</p><button class="btn" onclick="landsState.view='member_perms';renderLands(document.getElementById('content'))">← 返回</button></div>`;
            return;
        }

        const permMap = member.perm_map || {};
        const defaultPerms = data.default_perms || {};
        const permEntries = Object.entries(permTypes);

        let html = `<div class="card">
            <button class="btn" onclick="landsState.view='member_perms';landsState.currentMember=null;renderLands(document.getElementById('content'))" style="margin-bottom:12px;font-size:13px">← 返回成员列表</button>
            <h2 style="margin:0 0 8px;color:var(--fg)">👤 ${escHtml(targetPlayer)} 权限</h2>
            <p style="color:var(--dim);font-size:13px;margin-bottom:16px">在 ${escHtml(landName)} 中的独立权限设置</p>
            <div style="display:flex;gap:8px;margin-bottom:16px">
                <button class="btn" onclick="clearAllMemberPerms('${escHtml(landName)}','${escHtml(targetPlayer)}')" style="color:var(--red)">🗑️ 清除所有自定义</button>
            </div>
            <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:8px">`;

        for (const [key, label] of permEntries) {
            // ★ 与游戏内 getEffectiveDeny 一致：有自定义覆盖用自定义，否则回退领地默认
            const hasCustom = Object.prototype.hasOwnProperty.call(permMap, key);
            const denied = hasCustom ? (permMap[key] === true) : (defaultPerms[key] === true);
            const isDefault = !hasCustom; // 继承领地默认

            html += `<div onclick="toggleMemberPerm('${escHtml(landName)}','${escHtml(targetPlayer)}','${key}',this)"
                style="background:${denied ? 'rgba(239,68,68,0.1)' : 'rgba(34,197,94,0.1)'};
                border:1px solid ${denied ? 'var(--red)' : 'var(--green)'};
                border-radius:8px;padding:12px;cursor:pointer;transition:all 0.2s;${isDefault ? 'opacity:.82' : ''}">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px">
                    <span style="color:var(--fg);font-size:13px;font-weight:500">${label}${isDefault ? ' <span style="font-size:9px;color:var(--dim)">默认</span>' : ''}</span>
                    <span style="font-size:11px;color:${denied ? 'var(--red)' : 'var(--green)'}">
                        ${denied ? '✗ 禁止' : '✓ 允许'}
                    </span>
                </div>
            </div>`;
        }
        html += `</div></div>`;
        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function toggleMemberPerm(landName, targetPlayer, permKey, el) {
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'update_member_perm');
        url.searchParams.set('token', TOKEN);

        const body = new URLSearchParams();
        body.set('land', landName);
        body.set('player', targetPlayer);
        body.set('perm', permKey);

        // 点击"允许"→切换为禁止(enabled=true)；点击"禁止"→切换为允许(enabled=false)
        const spans = el.querySelectorAll('span');
        const statusSpan = spans[spans.length - 1];
        const statusText = statusSpan ? statusSpan.textContent : '';
        const newDenied = statusText.includes('允许'); // 当前允许→新值禁止(true)；当前禁止→新值允许(false)
        body.set('enabled', newDenied.toString());

        const res = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body.toString()
        });
        const data = await res.json();
        if (data.success) {
            // 刷新页面
            await renderMemberPermDetail(document.getElementById('content'), landName, targetPlayer);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

async function clearAllMemberPerms(landName, targetPlayer) {
    if (!await glassConfirm(`确定要清除 ${targetPlayer} 的所有自定义权限吗？`)) return;
    try {
        const url = new URL(API + 'land_api.php', location.href);
        url.searchParams.set('action', 'clear_member_perm');
        url.searchParams.set('token', TOKEN);

        const body = new URLSearchParams();
        body.set('land', landName);
        body.set('player', targetPlayer);

        const res = await fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: body.toString()
        });
        const data = await res.json();
        if (data.success) {
            glassAlert('已清除所有自定义权限');
            await renderMemberPermDetail(document.getElementById('content'), landName, targetPlayer);
        } else {
            glassAlert(data.error || '操作失败');
        }
    } catch (e) {
        glassAlert('操作失败: ' + e.message);
    }
}

function escHtml(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

    </script>
</body>
</html>
