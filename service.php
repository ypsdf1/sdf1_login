<?php
header('Cache-Control: no-cache, no-store, must-revalidate, max-age=0');
header('Pragma: no-cache');
header('Expires: 0');
$BUILD_VERSION = 'v' . filemtime(__FILE__);
$currentVersion = isset($_GET['_v']) ? $_GET['_v'] : '';
if ($currentVersion !== $BUILD_VERSION) {
    $params = $_GET;
    unset($params['_v']);
    $queryString = http_build_query($params);
    $path = strtok($_SERVER['REQUEST_URI'], '?');
    $newUrl = $path . ($queryString ? '?' . $queryString . '&' : '?') . '_v=' . $BUILD_VERSION;
    header('Location: ' . $newUrl, true, 302);
    exit;
}
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>服务商面板 - 草原探险</title>
    <style>
        :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
        * { margin:0; padding:0; box-sizing:border-box; }
        body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
        .header { background:linear-gradient(135deg,#1a2e1a 0%,#0d1117 100%); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; }
        .header h1 { font-size:18px; color:var(--green); }
        .container { max-width:900px; margin:20px auto; padding:0 16px; }
        .card { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:20px; margin-bottom:16px; }
        .card h2 { font-size:16px; margin-bottom:12px; color:var(--green); }
        .btn { padding:8px 16px; border:none; border-radius:6px; cursor:pointer; font-size:13px; font-weight:600; transition:all 0.2s; }
        .btn-primary { background:var(--accent); color:#fff; }
        .btn-primary:hover { background:#79c0ff; }
        .btn-green { background:var(--green); color:#fff; }
        .btn-dim { background:var(--card); color:var(--dim); border:1px solid var(--border); }
        .btn-dim:hover { color:var(--text); border-color:var(--accent); }
        input, textarea, select { width:100%; padding:10px; border:1px solid var(--border); border-radius:6px; background:var(--bg); color:var(--text); font-size:14px; }
        textarea { resize:vertical; font-family:monospace; }
        .tabs { display:flex; gap:6px; margin-bottom:16px; flex-wrap:wrap; }
        .tab { padding:6px 14px; background:var(--bg); border:1px solid var(--border); border-radius:4px; cursor:pointer; font-size:12px; color:var(--dim); }
        .tab.active { background:var(--accent); color:#fff; border-color:var(--accent); }
        .ticket-item { background:rgba(63,185,80,0.05); border:1px solid var(--border); border-radius:8px; padding:14px 16px; margin-bottom:8px; cursor:pointer; transition:all 0.2s; }
        .ticket-item:hover { border-color:var(--green); }
        .status-dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:6px; }
        .md-content { font-size:14px; line-height:1.6; }
        .md-content pre { background:rgba(63,185,80,0.1); padding:10px; border-radius:4px; overflow-x:auto; font-size:12px; }
        .md-content code { background:rgba(63,185,80,0.15); padding:1px 4px; border-radius:2px; font-size:12px; }
        .reply-box { background:rgba(63,185,80,0.05); border-radius:6px; padding:10px 14px; margin-bottom:8px; }
        .login-box { max-width:400px; margin:100px auto; }
        .login-box h2 { text-align:center; margin-bottom:20px; color:var(--green); }
        .error { color:var(--red); font-size:13px; margin-top:8px; }
    </style>
</head>
<body>
    <div class="header">
        <h1>🧑‍💻 服务商面板</h1>
        <div style="display:flex;gap:8px;align-items:center">
            <span id="providerInfo" style="color:var(--dim);font-size:13px"></span>
            <button class="btn btn-dim" onclick="doLogout()" style="font-size:12px;padding:4px 12px">退出</button>
        </div>
    </div>

    <div class="container" id="mainContainer">
        <!-- 登录/面板 动态渲染 -->
    </div>

<script>
const API = 'api/';
let TOKEN = localStorage.getItem('sdf1_provider_token') || '';
let PLAYER = localStorage.getItem('sdf1_provider_player') || '';
let PROVIDER_ROLE = '';

(async function() {
    if (TOKEN && PLAYER) {
        await checkProviderAuth();
    } else {
        renderLogin();
    }
})();

function renderLogin() {
    document.getElementById('mainContainer').innerHTML = `
        <div class="card login-box">
            <h2>🧑‍💻 服务商登录</h2>
            <p style="color:var(--dim);text-align:center;font-size:13px;margin-bottom:16px">使用游戏账号密码登录（仅限已授权的服务商）</p>
            <div style="margin-bottom:12px">
                <label style="color:var(--dim);font-size:12px;display:block;margin-bottom:4px">玩家名</label>
                <input id="spUser" placeholder="游戏内玩家名" value="${escH(PLAYER)}">
            </div>
            <div style="margin-bottom:12px">
                <label style="color:var(--dim);font-size:12px;display:block;margin-bottom:4px">密码</label>
                <input id="spPass" type="password" placeholder="游戏登录密码" onkeydown="if(event.key==='Enter')doProviderLogin()">
            </div>
            <button class="btn btn-primary" style="width:100%;padding:12px" onclick="doProviderLogin()">登录</button>
            <p id="spError" class="error"></p>
        </div>`;
}

async function doProviderLogin() {
    const user = document.getElementById('spUser').value.trim();
    const pass = document.getElementById('spPass').value;
    const errEl = document.getElementById('spError');
    if (!user || !pass) { errEl.textContent = '请输入用户名和密码'; return; }

    try {
        // 0. 先清理可能残留的旧请求
        const cancelUrl = new URL(API + 'sync.php', location.href);
        cancelUrl.searchParams.set('action', 'cancel_web_login');
        cancelUrl.searchParams.set('player', user);
        await fetch(cancelUrl);

        // 1. 先创建weblogin token
        const regUrl = new URL(API + 'sync.php', location.href);
        regUrl.searchParams.set('action', 'web_login_request');
        const regRes = await fetch(regUrl, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({player: user, password: pass})
        });
        const regData = await regRes.json();
        if (!regData.success) { errEl.textContent = regData.message || '登录请求失败'; return; }

        // 2. 等待Java端处理（轮询）
        const requestId = regData.data && regData.data.request_id;
        if (!requestId) { errEl.textContent = '未获取请求ID'; return; }

        let verified = false;
        for (let i = 0; i < 30; i++) {
            await new Promise(r => setTimeout(r, 1000));
            const checkUrl = new URL(API + 'sync.php', location.href);
            checkUrl.searchParams.set('action', 'check_web_login_result');
            checkUrl.searchParams.set('request_id', requestId);
            checkUrl.searchParams.set('player', user);
            const checkRes = await fetch(checkUrl);
            const checkData = await checkRes.json();
            if (checkData.success && checkData.data && checkData.data.status === 'success') {
                TOKEN = checkData.data.token || checkData.data.web_token || '';
                verified = true;
                break;
            }
            if (checkData.data && checkData.data.status === 'failed') {
                // 密码错误，清理pending请求
                const cancelUrl3 = new URL(API + 'sync.php', location.href);
                cancelUrl3.searchParams.set('action', 'cancel_web_login');
                cancelUrl3.searchParams.set('player', user);
                await fetch(cancelUrl3);
                errEl.textContent = checkData.data.message || '密码错误';
                return;
            }
        }
        if (!verified) {
            // 超时，清理pending请求
            const cancelUrl2 = new URL(API + 'sync.php', location.href);
            cancelUrl2.searchParams.set('action', 'cancel_web_login');
            cancelUrl2.searchParams.set('player', user);
            await fetch(cancelUrl2);
            errEl.textContent = '验证超时，请确认游戏服务器在线';
            return;
        }

        // 3. 验证服务商身份
        PLAYER = user;
        localStorage.setItem('sdf1_provider_token', TOKEN);
        localStorage.setItem('sdf1_provider_player', PLAYER);
        await checkProviderAuth();
    } catch (e) {
        errEl.textContent = '登录失败: ' + e.message;
    }
}

async function checkProviderAuth() {
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'provider_check');
        url.searchParams.set('token', TOKEN);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success && data.data.is_provider) {
            PROVIDER_ROLE = data.data.role || 'waiter';
            document.getElementById('providerInfo').textContent = `${PLAYER} (${PROVIDER_ROLE})`;
            renderPanel();
        } else {
            TOKEN = '';
            localStorage.removeItem('sdf1_provider_token');
            renderLogin();
            setTimeout(() => {
                const errEl = document.getElementById('spError');
                if (errEl) errEl.textContent = '你不是授权的服务商，请联系管理员';
            }, 100);
        }
    } catch (e) {
        TOKEN = '';
        localStorage.removeItem('sdf1_provider_token');
        renderLogin();
    }
}

let spView = 'available';
function renderPanel() {
    const c = document.getElementById('mainContainer');
    c.innerHTML = `
        <div class="tabs">
            <div class="tab ${spView==='available'?'active':''}" onclick="spView='available';renderPanel()">📋 抢单大厅</div>
            <div class="tab ${spView==='my'?'active':''}" onclick="spView='my';renderPanel()">👤 我的工单</div>
        </div>
        <div id="spContent"><p style="color:var(--dim)">加载中...</p></div>`;
    if (spView === 'available') loadAvailableTickets();
    else loadMyProviderTickets();
}

async function loadAvailableTickets() {
    const el = document.getElementById('spContent');
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'available');
        url.searchParams.set('token', TOKEN);
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) { el.innerHTML = `<p style="color:var(--red)">${data.message}</p>`; return; }
        const list = data.data.list;
        const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
        if (list.length === 0) {
            el.innerHTML = `<div class="card"><p style="color:var(--dim);text-align:center;padding:30px">暂无待接工单</p></div>`;
            return;
        }
        let html = '';
        for (const t of list) {
            const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
            html += `<div class="ticket-item" onclick="viewSPTicket(${t.id},'available')">
                <div style="display:flex;justify-content:space-between;margin-bottom:4px">
                    <span style="font-weight:600;font-size:14px">#${t.id} ${escH(t.title)}</span>
                    <span style="font-size:12px;color:var(--yellow)">⏳ 待接</span>
                </div>
                <div style="font-size:12px;color:var(--dim)">${typeMap[t.type]||t.type} | 👤 ${escH(t.requester)} | 📅 ${date}</div>
            </div>`;
        }
        el.innerHTML = html;
    } catch (e) { el.innerHTML = `<p style="color:var(--red)">加载失败: ${e.message}</p>`; }
}

async function loadMyProviderTickets() {
    const el = document.getElementById('spContent');
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'provider_list');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('type', 'processing');
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) { el.innerHTML = `<p style="color:var(--red)">${data.message}</p>`; return; }
        const list = data.data.list;
        const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
        const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},completed:{text:'✅ 已完结',color:'var(--green)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
        if (list.length === 0) {
            el.innerHTML = `<div class="card"><p style="color:var(--dim);text-align:center;padding:30px">暂无处理中的工单</p></div>`;
            return;
        }
        let html = '';
        for (const t of list) {
            const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
            const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
            html += `<div class="ticket-item" onclick="viewSPTicket(${t.id},'my')">
                <div style="display:flex;justify-content:space-between;margin-bottom:4px">
                    <span style="font-weight:600;font-size:14px">#${t.id} ${escH(t.title)}</span>
                    <span style="font-size:12px;color:${s.color}">${s.text}</span>
                </div>
                <div style="font-size:12px;color:var(--dim)">${typeMap[t.type]||t.type} | 👤 ${escH(t.requester)} | 📅 ${date}</div>
            </div>`;
        }
        el.innerHTML = html;
    } catch (e) { el.innerHTML = `<p style="color:var(--red)">加载失败: ${e.message}</p>`; }
}

async function viewSPTicket(id, from) {
    const el = document.getElementById('spContent');
    el.innerHTML = '<p style="color:var(--dim)">加载中...</p>';
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'detail');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('id', id);
        const res = await fetch(url);
        const data = await res.json();
        if (!data.success) { el.innerHTML = `<p style="color:var(--red)">${data.message}</p>`; return; }

        const t = data.data;
        const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
        const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},completed:{text:'✅ 已完结',color:'var(--green)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
        const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
        const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
        const isMyTicket = t.assigned_to === PLAYER;
        const canReply = !['withdrawn','rejected','completed'].includes(t.status) && isMyTicket;
        const canGrab = t.status === 'submitted' && !t.assigned_to;

        let html = `<div class="card">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                <h2 style="margin:0">#${t.id} ${escH(t.title)}</h2>
                <span style="color:${s.color};font-size:13px">${s.text}</span>
            </div>
            <div style="font-size:12px;color:var(--dim);margin-bottom:12px">
                ${typeMap[t.type]||t.type} | 提交者: ${escH(t.requester)} | 处理人: ${t.assigned_to ? escH(t.assigned_to) : '未分配'} | ${date}
            </div>
            <div style="background:rgba(63,185,80,0.05);border:1px solid var(--border);border-radius:6px;padding:12px;margin-bottom:12px">
                <div style="color:var(--dim);font-size:11px;margin-bottom:4px">描述</div>
                <div class="md-content">${spRenderMd(t.description || '无描述')}</div>
            </div>`;

        if (t.reject_reason) {
            html += `<div style="background:rgba(248,81,73,0.1);border:1px solid var(--red);border-radius:6px;padding:12px;margin-bottom:12px">
                <div style="color:var(--red);font-size:11px;margin-bottom:4px">🚫 驳回原因</div>
                <div class="md-content">${spRenderMd(t.reject_reason)}</div></div>`;
        }

        // 回复列表
        html += `<div style="margin-bottom:12px"><div style="font-size:11px;color:var(--dim);margin-bottom:6px">💬 回复 (${t.replies ? t.replies.length : 0})</div>`;
        if (t.replies && t.replies.length > 0) {
            for (const r of t.replies) {
                const rDate = new Date(r.created_at * 1000).toLocaleString('zh-CN');
                const roleColors = {user:'var(--accent)',admin:'var(--red)',provider:'var(--green)'};
                const roleNames = {user:'玩家',admin:'管理员',provider:'服务商'};
                const bgColor = r.role === 'admin' ? 'rgba(248,81,73,0.08)' : r.role === 'provider' ? 'rgba(63,185,80,0.08)' : 'rgba(88,166,255,0.08)';
                html += `<div class="reply-box" style="background:${bgColor}">
                    <div style="display:flex;justify-content:space-between;font-size:11px;margin-bottom:4px">
                        <span><b style="color:${roleColors[r.role]||'var(--text)'}">${escH(r.sender)}</b> <span style="color:var(--dim)">(${roleNames[r.role]||r.role})</span></span>
                        <span style="color:var(--dim)">${rDate}</span>
                    </div>
                    <div class="md-content">${spRenderMd(r.message)}</div>
                </div>`;
            }
        } else {
            html += `<p style="color:var(--dim);font-size:12px">暂无回复</p>`;
        }
        html += `</div>`;

        // 操作
        html += `<div style="display:flex;gap:8px;flex-wrap:wrap">`;
        if (canGrab) {
            html += `<button class="btn btn-green" onclick="grabSPTicket(${t.id})">📩 接单</button>`;
        }
        if (canReply) {
            html += `<div style="width:100%;margin-top:8px">
                <textarea id="spReplyMsg" rows="2" placeholder="输入回复内容（支持Markdown）" style="font-family:monospace"></textarea>
                <div style="margin-top:6px;display:flex;gap:8px">
                    <button class="btn btn-primary" onclick="spReply(${t.id})">💬 回复</button>
                    <button class="btn btn-green" onclick="spComplete(${t.id})">✅ 标记完结</button>
                </div>
            </div>`;
        }
        html += `<button class="btn btn-dim" onclick="spView='${from}';renderPanel()" style="margin-top:8px">← 返回</button></div>`;
        html += `</div>`;

        el.innerHTML = html;
    } catch (e) { el.innerHTML = `<p style="color:var(--red)">加载失败: ${e.message}</p>`; }
}

async function grabSPTicket(id) {
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'grab');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('id', id);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success) { viewSPTicket(id, 'available'); } else { alert(data.message); }
    } catch (e) { alert('接单失败: ' + e.message); }
}

async function spReply(id) {
    const msg = document.getElementById('spReplyMsg').value.trim();
    if (!msg) { alert('请输入回复内容'); return; }
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'provider_reply');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('id', id);
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({message: msg})});
        const data = await res.json();
        if (data.success) { viewSPTicket(id, 'my'); } else { alert(data.message); }
    } catch (e) { alert('回复失败: ' + e.message); }
}

async function spComplete(id) {
    if (!confirm('确定标记此工单已完结？')) return;
    try {
        const url = new URL(API + 'ticket.php', location.href);
        url.searchParams.set('action', 'complete');
        url.searchParams.set('token', TOKEN);
        url.searchParams.set('id', id);
        const res = await fetch(url);
        const data = await res.json();
        if (data.success) { viewSPTicket(id, 'my'); } else { alert(data.message); }
    } catch (e) { alert('操作失败: ' + e.message); }
}

function doLogout() {
    TOKEN = '';
    PLAYER = '';
    localStorage.removeItem('sdf1_provider_token');
    localStorage.removeItem('sdf1_provider_player');
    renderLogin();
}

function escH(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

function spRenderMd(text) {
    if (!text) return '';
    text = text.replace(/\r/g, '');
    try {
    let s = escH(text);
    // 代码块（提取保护，防止<br>污染）
    let cbs = [];
    s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, function(m, lang, code) {
        let idx = cbs.length;
        cbs.push('<pre style="background:rgba(63,185,80,0.1);padding:10px;border-radius:4px;overflow-x:auto;font-size:12px;white-space:pre-wrap">' + code.replace(/\n$/, '') + '</pre>');
        return '\x00CB' + idx + '\x00';
    });
    // 行内代码
    s = s.replace(/`([^`]+)`/g, '<code style="background:rgba(63,185,80,0.15);padding:1px 4px;border-radius:2px;font-size:12px">$1</code>');
    // 删除线 ~~text~~
    s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
    // 粗体+斜体
    s = s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    s = s.replace(/\*(.+?)\*/g, '<i>$1</i>');
    // 图片 ![alt](url)
    s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;border-radius:6px;margin:6px 0">');
    // 链接（排除图片）
    s = s.replace(/(?<!!)\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" style="color:var(--accent)">$1</a>');
    // 标题（从多到少匹配，严格匹配空格分隔）
    s = s.replace(/^######\s+(.+)$/gm, '<h6 style="margin:3px 0;font-size:11px">$1</h6>');
    s = s.replace(/^#####\s+(.+)$/gm, '<h5 style="margin:4px 0;font-size:12px">$1</h5>');
    s = s.replace(/^####\s+(.+)$/gm, '<h5 style="margin:4px 0 2px;font-size:12px">$1</h5>');
    s = s.replace(/^###\s+(.+)$/gm, '<h4 style="margin:6px 0 2px;font-size:13px">$1</h4>');
    s = s.replace(/^##\s+(.+)$/gm, '<h3 style="margin:8px 0 4px;font-size:14px">$1</h3>');
    s = s.replace(/^#\s+(.+)$/gm, '<h2 style="margin:10px 0 6px;font-size:15px">$1</h2>');
    // 引用块（支持嵌套）
    s = s.replace(/^(?:&gt;[ \t]*)+(.*)$/gm, function(m, content) {
        let nestLevel = (m.match(/&gt;/g) || []).length;
        let leftPad = Math.min(nestLevel * 6, 24);
        let text = content || '&nbsp;';
        return '<div style="border-left:3px solid var(--accent);padding:2px 8px;margin:4px 0 4px ' + leftPad + 'px;color:#aaa;font-style:italic;background:rgba(255,255,255,0.03)">' + text + '</div>';
    });
    // 表格处理
    let tableLines = [];
    let inTable = false;
    let tableHtml = '';
    let lines = s.split('\n');
    let result = [];
    for (let i = 0; i < lines.length; i++) {
        let line = lines[i].trim();
        if (line.match(/^\|(.+)\|$/)) {
            // 分隔行 |---|---|
            if (line.match(/^\|[\s\-:|]+\|$/)) {
                inTable = true;
                continue;
            }
            if (!inTable && i + 1 < lines.length && lines[i+1].trim().match(/^\|[\s\-:|]+\|$/)) {
                // 下一行是分隔行，这是表头
                inTable = true;
                let cells = line.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
                tableHtml = '<table style="border-collapse:collapse;width:100%;margin:6px 0;font-size:12px"><tr style="background:rgba(63,185,80,0.15)">';
                for (let c of cells) tableHtml += '<th style="border:1px solid var(--border);padding:4px 8px;text-align:left">' + c + '</th>';
                tableHtml += '</tr>';
                continue;
            }
            if (inTable) {
                let cells = line.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
                tableHtml += '<tr>';
                for (let c of cells) tableHtml += '<td style="border:1px solid var(--border);padding:4px 8px">' + c + '</td>';
                tableHtml += '</tr>';
                continue;
            }
        }
        if (inTable && tableHtml) {
            tableHtml += '</table>';
            result.push(tableHtml);
            tableHtml = '';
            inTable = false;
        }
        result.push(lines[i]);
    }
    if (tableHtml) {
        tableHtml += '</table>';
        result.push(tableHtml);
    }
    s = result.join('\n');
    // 无序列表
    s = s.replace(/^- (.+)$/gm, '<li style="margin-left:16px;font-size:12px">$1</li>');
    // 分割线
    s = s.replace(/^---$/gm, '<hr style="border:none;border-top:1px solid var(--border);margin:8px 0">');
    // 换行
    s = s.replace(/\n/g, '<br>');
    // 还原代码块占位符
    for (let i = 0; i < cbs.length; i++) {
        s = s.replace('\x00CB' + i + '\x00', cbs[i]);
    }
    return s;
    } catch(e) { console.error('[MD_RENDER] Error:', e); return escH(text).replace(/\n/g, '<br>'); }
}
</script>
</body>
</html>
