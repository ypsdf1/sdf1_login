<?php header('Cache-Control: no-cache, no-store, must-revalidate, max-age=0'); header('Pragma: no-cache'); ?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="0">
    <title>SDF1 - 管理后台 v<?php echo date('ymd_His', filemtime(__FILE__)); ?></title>
    <style>
        :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
        * { margin:0; padding:0; box-sizing:border-box; }
        body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; }
        .header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; }
        .header h1 { font-size:18px; color:var(--accent); }
        .main { display:flex; min-height:calc(100vh - 52px); }
        .sidebar { width:200px; background:var(--card); border-right:1px solid var(--border); padding:12px 0; flex-shrink:0; overflow-y:auto; max-height:calc(100vh - 52px); }
        .si { padding:10px 20px; cursor:pointer; color:var(--dim); transition:all 0.2s; font-size:13px; display:flex; align-items:center; gap:8px; }
        .si:hover { background:rgba(88,166,255,0.1); color:var(--text); }
        .si.active { color:var(--accent); border-right:3px solid var(--accent); background:rgba(88,166,255,0.05); }
        .content { flex:1; padding:20px; overflow-y:auto; max-height:calc(100vh - 52px); }
        .card { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:16px; margin-bottom:12px; }
        .card h2 { font-size:15px; color:var(--accent); margin-bottom:10px; }
        .stats { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; margin-bottom:16px; }
        .stat { background:var(--card); border:1px solid var(--border); border-radius:8px; padding:14px; text-align:center; }
        .stat .v { font-size:24px; font-weight:700; color:var(--accent); }
        .stat .l { font-size:11px; color:var(--dim); margin-top:4px; }
        .table { width:100%; border-collapse:collapse; font-size:12px; }
        .table th { text-align:left; padding:8px; color:var(--dim); border-bottom:1px solid var(--border); }
        .table td { padding:8px; border-bottom:1px solid var(--border); }
        .table tr:hover td { background:rgba(88,166,255,0.05); }
        .form-row { display:flex; gap:8px; margin-bottom:8px; align-items:center; }
        .form-row label { min-width:60px; font-size:12px; color:var(--dim); }
        .form-row input, .form-row select { padding:7px 10px; background:var(--bg); border:1px solid var(--border); border-radius:4px; color:var(--text); font-size:13px; outline:none; flex:1; }
        .form-row input:focus { border-color:var(--accent); }
        .btn { padding:7px 14px; border:none; border-radius:4px; cursor:pointer; font-size:12px; font-weight:600; transition:all 0.2s; }
        .btn-blue { background:var(--accent); color:#fff; }
        .btn-green { background:var(--green); color:#fff; }
        .btn-red { background:var(--red); color:#fff; }
        .btn-yellow { background:var(--yellow); color:#000; }
        .btn:hover { opacity:0.85; }
        .toast { position:fixed; top:16px; right:16px; padding:10px 16px; border-radius:6px; font-size:13px; z-index:200; animation:sIn 0.3s; }
        .toast.ok { background:var(--green); color:#fff; }
        .toast.err { background:var(--red); color:#fff; }
        @keyframes sIn { from{transform:translateX(100%);opacity:0} to{transform:translateX(0);opacity:1} }
        .tabs { display:flex; gap:4px; margin-bottom:12px; flex-wrap:wrap; }
        .tab { padding:6px 14px; background:var(--bg); border:1px solid var(--border); border-radius:4px; cursor:pointer; font-size:12px; color:var(--dim); }
        .tab.active { background:var(--accent); color:#fff; border-color:var(--accent); }
        .tag { display:inline-block; padding:2px 8px; border-radius:10px; font-size:11px; font-weight:600; }
        .tag-used { background:rgba(248,81,73,0.2); color:var(--red); }
        .tag-unused { background:rgba(63,185,80,0.2); color:var(--green); }
        .player-online { color:var(--green) !important; font-weight:bold; }
        .hamburger { display:none; background:none; border:none; color:var(--text); font-size:24px; cursor:pointer; }
        .sidebar-overlay { display:none; position:fixed; top:0; left:0; right:0; bottom:0; background:rgba(0,0,0,0.5); z-index:999; }
        @media(max-width:768px) {
            .hamburger { display:block; }
            .sidebar { position:fixed; left:-200px; top:52px; bottom:0; z-index:1000; transition:left 0.3s; }
            .sidebar.open { left:0; }
            .sidebar-overlay.show { display:block; }
            .stats { grid-template-columns:repeat(2,1fr); }
            .table { font-size:11px; }
        }
        .theme-picker { display:flex; gap:8px; align-items:center; margin-top:8px; }
        .color-btn { width:30px; height:30px; border-radius:4px; cursor:pointer; border:2px solid transparent; transition:all 0.2s; }
        .color-btn:hover { border-color:var(--accent); transform:scale(1.1); }
        .color-input { padding:7px 10px; background:var(--bg); border:1px solid var(--border); border-radius:4px; color:var(--text); font-size:13px; flex:1; }
        .glass-alert-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.45);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:5000;justify-content:center;align-items:center;animation:glassFadeIn 0.25s ease}
        .glass-alert-overlay.show{display:flex}
        .glass-alert-card{background:rgba(22,27,34,0.92);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);border:1px solid rgba(88,166,255,0.25);border-radius:16px;padding:28px 28px 20px;width:360px;max-width:88%;box-shadow:0 12px 48px rgba(0,0,0,0.5),inset 0 1px 0 rgba(255,255,255,0.05);animation:glassSlideUp 0.3s ease;text-align:center}
        .glass-alert-card .alert-icon{font-size:36px;margin-bottom:12px}
        .glass-alert-card .alert-msg{font-size:14px;color:#e6edf3;line-height:1.6;margin-bottom:20px;word-break:break-word}
        .glass-alert-card .alert-btns{display:flex;gap:10px;justify-content:center}
        .glass-alert-card .alert-btns button{padding:8px 24px;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;transition:all 0.2s}
        .glass-alert-card .alert-btns .ag-ok{background:#58a6ff;color:#fff}
        .glass-alert-card .alert-btns .ag-cancel{background:rgba(255,255,255,0.08);color:#8b949e}
        .glass-alert-card .alert-btns button:hover{opacity:0.85;transform:scale(1.03)}
        .glass-alert-card .alert-input{width:100%;padding:10px 12px;background:rgba(13,17,23,0.8);border:1px solid rgba(88,166,255,0.3);border-radius:8px;color:#e6edf3;font-size:13px;outline:none;box-sizing:border-box;transition:border 0.2s;text-align:center;margin-bottom:16px}
        .glass-alert-card .alert-input:focus{border-color:#58a6ff;box-shadow:0 0 0 2px rgba(88,166,255,0.15)}
        .glass-alert-card .alert-hint{font-size:11px;color:#8b949e;margin:-12px 0 16px;text-align:left}
        .glass-alert-card .alert-label{font-size:12px;color:#8b949e;margin-bottom:4px;text-align:left}
        @keyframes glassFadeIn{from{opacity:0}to{opacity:1}}
        @keyframes glassSlideUp{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)}}
    </style>
</head>
<body>
<div class="header">
    <div style="display:flex;align-items:center;gap:12px">
        <button class="hamburger" onclick="toggleSidebar()">☰</button>
        <h1>⚙️ SDF1 管理后台</h1>
    </div>
    <div style="display:flex;align-items:center;gap:12px">
        <button class="btn btn-yellow" onclick="showThemePicker()">🎨 主题</button>
        <button class="btn btn-red" onclick="doLogout()">登出</button>
    </div>
</div>
<div class="sidebar-overlay" id="sidebarOverlay" onclick="toggleSidebar()"></div>
<div class="main">
    <div class="sidebar" id="sidebar">
        <div class="si active" data-p="dashboard" onclick="go('dashboard')">📊 总览</div>
        <div class="si" data-p="bonds" onclick="go('bonds')">💰 债券管理</div>
        <div class="si" data-p="shop" onclick="go('shop')">🛒 商品管理</div>
        <div class="si" data-p="cdk" onclick="go('cdk')">🎁 CDK管理</div>
        <div class="si" data-p="transactions" onclick="go('transactions')">📋 流水记录</div>
        <div class="si" data-p="token" onclick="go('token')">🔑 Token生成</div>
        <div class="si" data-p="users" onclick="go('users')">👥 用户管理</div>
        <div class="si" data-p="online" onclick="go('online')">🟢 在线玩家</div>
        <div class="si" data-p="active" onclick="go('active')">⏱️ 活跃用户</div>
        <div class="si" data-p="reset_requests" onclick="go('reset_requests')">🔑 密码重置审核</div>
        <div class="si" data-p="tickets" onclick="go('tickets')">📋 工单管理</div>
        <div class="si" data-p="lands" onclick="go('lands')">🏡 领地管理</div>
        <div class="si" data-p="usergroups" onclick="go('usergroups')">👥 用户组</div>
    </div>
    <div class="content" id="C"></div>
</div>

<script data-cfasync="false">
// ★ 全局错误处理器：捕获所有未处理异常
window.onerror = function(msg, src, line, col, err) {
    console.error('[GlobalError]', msg, 'at', src, 'line', line + ':' + col, err);
    return false;
};
window.addEventListener('unhandledrejection', function(e) {
    console.error('[UnhandledPromise]', e.reason);
});

// ★ 强制清除Service Worker缓存（防止旧JS缓存）
if ('caches' in window) {
    caches.keys().then(names => names.forEach(n => caches.delete(n)));
}
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.getRegistrations().then(regs => regs.forEach(r => r.unregister()));
}

const A = 'api/admin.php';
const _BUILD_TS = 1782652854; // 版本号，用于缓存失效 - 用户组管理页面
console.log('[INIT] Admin panel loaded, build:', _BUILD_TS);

// ★ 自检：验证新代码是否加载
setTimeout(() => {
    const ok = typeof lazyLoadUsersPage === 'function' && typeof batchQueryIpLocations === 'function';
    console.log('[INIT] Code check:', ok ? '✓ All functions present' : '✗ Missing functions - possible old cache');
    if (!ok) {
        console.error('[INIT] WARNING: Old JavaScript may be cached. Please press Ctrl+Shift+R to force refresh.');
    }
}, 200);

let page = 'dashboard';
let onlineInterval = null;

function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('open');
    document.getElementById('sidebarOverlay').classList.toggle('show');
}

function go(p) {
    page = p;
    document.querySelectorAll('.si').forEach(e => e.classList.toggle('active', e.dataset.p === p));
    // 移动端关闭侧边栏
    if (window.innerWidth <= 768) toggleSidebar();
    // 停止之前的定时器
    if (onlineInterval) clearInterval(onlineInterval);
    const c = document.getElementById('C');
    if (p==='dashboard') loadDashboard(c);
    else if (p==='bonds') loadBonds(c);
    else if (p==='shop') loadShop(c);
    else if (p==='cdk') loadCDK(c);
    else if (p==='transactions') loadTx(c);
    else if (p==='token') loadToken(c);
    else if (p==='users') loadUsers(c);
    else if (p==='online') loadOnlinePlayers(c);
    else if (p==='active') loadActivePlayers(c);
    else if (p==='reset_requests') loadResetRequests(c);
    else if (p==='tickets') loadTickets(c);
    else if (p==='lands') loadLands(c);
    else if (p==='usergroups') loadUserGroups(c);
}

// 检查登录状态
(async function(){
    try {
        const s = await fetch('api/admin.php?action=status', {
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        });
        const d = await s.text().then(text => {
            text = text.replace(/^\uFEFF/, '').trim();
            if (!text) throw new Error('Empty response');
            try { return JSON.parse(text); } catch(e) {
                console.error('Status API returned invalid JSON:', text.substring(0, 200));
                throw new Error('服务器返回无效JSON: ' + text.substring(0, 50));
            }
        });
        if (!d.data || !d.data.logged_in) {
            location.href='admin_login.php'; 
            return;
        }
        go('dashboard');
    } catch (e) {
        console.error('Login check failed:', e);
        const c = document.getElementById('C');
        if (c) c.innerHTML = '<div class="card" style="text-align:center;padding:40px"><h2 style="color:var(--red)">服务器连接失败</h2><p style="color:var(--dim);margin-top:8px">请确保 api/admin.php 文件存在且可访问</p><p style="color:var(--red);margin-top:8px">错误: ' + e.message + '</p></div>';
    }
})();

// ===== 总览 =====
async function loadDashboard(el) {
    el.innerHTML = '<div class="card" style="text-align:center;padding:40px">加载中...</div>';
    try {
        // 先检查登录状态
        const s = await fetch('api/admin.php?action=status', {
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'XMLHttpRequest' }
        }).then(r => r.text().then(t => { try { return JSON.parse(t.replace(/^\uFEFF/,'').trim()); } catch(e) { console.error('Status JSON error:', t.substring(0,200)); throw e; } }));
        if (!s.success || !s.data || !s.data.logged_in) {
            el.innerHTML = '<div class="card" style="text-align:center;padding:40px"><h2>请先登录</h2><p style="color:var(--dim);margin-top:8px">尚未登录管理后台</p><p style="color:var(--dim);margin-top:4px">如已登录，请清除浏览器缓存后重试</p><p style="color:var(--red);margin-top:4px">调试: ' + JSON.stringify(s) + '</p></div>';
            return;
        }
        const [statsR, onlineR] = await Promise.all([
            fetch('api/admin.php?action=get_stats_ex', { credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
                .then(r => r.text().then(t => { 
                    console.log('[Stats] Response:', t.substring(0, 200)); 
                    try { 
                        return JSON.parse(t.replace(/^\uFEFF/,'').trim()); 
                    } catch(e) { 
                        console.error('Stats JSON error:', t.substring(0,500)); 
                        return {success:false,message:'JSON parse error: ' + t.substring(0, 100)}; 
                    } 
                })),
            fetch('api/admin.php?action=list_online_players', { credentials: 'same-origin', headers: { 'X-Requested-With': 'XMLHttpRequest' } })
                .then(r => r.text().then(t => { 
                    try { 
                        return JSON.parse(t.replace(/^\uFEFF/,'').trim()); 
                    } catch(e) { 
                        console.error('Online JSON error:', t.substring(0,200)); 
                        return {success:false,message:'JSON parse error'}; 
                    } 
                }))
        ]);
        let statsHtml = '';
        if (statsR.success && statsR.data) {
            const d = statsR.data;
            statsHtml = `
                <div class="stats">
                    <div class="stat"><div class="v">${d.total_users ?? 0}</div><div class="l">注册用户</div></div>
                    <div class="stat"><div class="v" style="color:var(--green)">${d.online_count ?? 0}</div><div class="l">在线玩家</div></div>
                    <div class="stat"><div class="v" style="color:var(--yellow)">${d.active_count_24h ?? 0}</div><div class="l">24h活跃</div></div>
                    <div class="stat"><div class="v" style="color:var(--purple)">${d.total_bonds ?? '-'}</div><div class="l">债券总和</div></div>
                    <div class="stat"><div class="v" style="color:#3fb950">${d.today_registered ?? '-'}</div><div class="l">今日注册</div></div>
                </div>`;
        } else {
            statsHtml = `<div class="card" style="text-align:center;padding:20px"><p style="color:var(--red)">统计数据加载失败: ${statsR.message || 'unknown'}</p></div>`;
        }
        
        // 在线玩家列表
        let onlineHtml = '';
        if (onlineR.success && onlineR.data && onlineR.data.length > 0) {
            const players = onlineR.data;
            // 同IP段折叠：按/24子网分组
            const subnetGroups = {};
            const subnetOrder = [];
            players.forEach(p => {
                const ip = p.ip_address || '-';
                let subnet = '_unknown_' + p.player_name; // 默认每个玩家独立一组
                // 只有有效IPv4地址才进行子网分组
                if (ip !== '-' && ip.indexOf('.') !== -1) {
                    const parts = ip.split('.');
                    if (parts.length === 4 && parts.every(part => part !== '' && !isNaN(part))) {
                        subnet = parts[0] + '.' + parts[1] + '.' + parts[2];
                    }
                }
                if (!subnetGroups[subnet]) {
                    subnetGroups[subnet] = [];
                    subnetOrder.push(subnet);
                }
                subnetGroups[subnet].push(p);
            });
            
            // 构建显示列表：每组只显示第一个，后面标注折叠数量
            const displayRows = [];
            subnetOrder.forEach(subnet => {
                const group = subnetGroups[subnet];
                const first = group[0];
                const hiddenCount = group.length - 1;
                displayRows.push({player: first, hiddenCount});
            });
            
            onlineHtml = `
                <div class="card">
                    <h2>实时在线玩家 <span style="color:var(--dim);font-size:12px">(${players.length}人)</span></h2>
                    <table class="table">
                        <tr><th>玩家名</th><th>IP地址</th><th>登录时间</th><th>在线时长</th></tr>
                        ${displayRows.map(({player: p, hiddenCount}) => {
                            const loginTime = p.login_time ? new Date(p.login_time*1000).toLocaleString() : '-';
                            const mins = Math.floor((Date.now()/1000 - p.login_time)/60);
                            const ip = p.ip_address || '-';
                            const suffix = hiddenCount > 0 ? ` <span style="color:var(--yellow);font-size:12px">(折叠同ip段玩家${hiddenCount}名)</span>` : '';
                            return `<tr><td class="player-online">🟢 ${p.player_name}${suffix}</td><td style="font-size:12px;font-family:monospace">${ip}</td><td>${loginTime}</td><td>${mins}分钟</td></tr>`;
                        }).join('')}
                    </table>
                </div>`;
        } else {
            onlineHtml = '<div class="card"><h2>实时在线玩家 <span style="color:var(--dim);font-size:12px">(0人)</span></h2><div class="empty">暂无在线玩家，请确认 Java 插件已推送在线数据</div></div>';
        }
        
        el.innerHTML = statsHtml + onlineHtml;
    } catch (e) {
        el.innerHTML = '<div class="card" style="color:var(--red);text-align:center">加载失败: '+e.message+'</div>';
    }
}

// ===== 债券管理 =====
async function loadBonds(el) {
    el.innerHTML = `
        <div class="card">
            <h2>给玩家增加债券</h2>
            <div class="form-row"><label>玩家</label><input id="bPlayer" placeholder="玩家名"></div>
            <div class="form-row"><label>金额</label><input id="bAmount" type="number" value="100" min="1"></div>
            <div class="form-row"><label>理由</label><input id="bReason" value="管理员充值"></div>
            <button class="btn btn-green" onclick="doAddBonds()">增加债券</button>
        </div>
        <div class="card">
            <h2>扣除玩家债券</h2>
            <div class="form-row"><label>玩家</label><input id="dPlayer" placeholder="玩家名"></div>
            <div class="form-row"><label>金额</label><input id="dAmount" type="number" value="100" min="1"></div>
            <div class="form-row"><label>理由</label><input id="dReason" value="管理员扣除"></div>
            <button class="btn btn-red" onclick="doDeduct()">扣除债券</button>
        </div>
        <div class="card">
            <h2>查询玩家债券</h2>
            <div class="form-row"><label>玩家</label><input id="qPlayer" placeholder="玩家名"><button class="btn btn-blue" onclick="doQueryBond()">查询</button></div>
            <div id="qResult"></div>
        </div>`;
}

async function doAddBonds() {
    const player = document.getElementById('bPlayer').value.trim();
    const amount = parseInt(document.getElementById('bAmount').value);
    const reason = document.getElementById('bReason').value;
    if (!player||!amount) { toast('请填写完整','err'); return; }
    const r = await postApi('add_bonds', {player, amount, reason});
    toast(r.message, r.success?'ok':'err');
}

async function doDeduct() {
    const player = document.getElementById('dPlayer').value.trim();
    const amount = parseInt(document.getElementById('dAmount').value);
    const reason = document.getElementById('dReason').value;
    if (!player||!amount) { toast('请填写完整','err'); return; }
    const r = await postApi('deduct', {player, amount, reason});
    toast(r.message, r.success?'ok':'err');
}

async function doQueryBond() {
    const player = document.getElementById('qPlayer').value.trim();
    if (!player) return;
    const r = await jsonApi('balance.php?action=query&player='+encodeURIComponent(player));
    const div = document.getElementById('qResult');
    if (r.success) div.innerHTML=`<div class="card" style="margin-top:8px"><p>债券: <b style="color:var(--green)">${r.data.bonds}</b> | 积分: <b style="color:var(--purple)">${r.data.points}</b></p></div>`;
    else div.innerHTML=`<p style="color:var(--red);margin-top:8px">${r.message}</p>`;
}

// ===== 商品管理 =====
let _shopItems = [];
let _shopCategories = [];
let _currentShopCat = '';

async function loadShop(el) {
    const r = await jsonApi('shop.php?action=list');
    _shopItems = r.data || [];
    _shopCategories = [...new Set(_shopItems.map(i => i.category || '默认'))];
    _currentShopCat = _shopCategories[0] || '';

    let catTabs = '';
    if (_shopCategories.length > 0) {
        catTabs = '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px">';
        _shopCategories.forEach((cat, idx) => {
            const active = idx === 0 ? 'color:#fff;background:var(--accent);border-color:var(--accent)' : '';
            const catCount = _shopItems.filter(i => (i.category || '默认') === cat).length;
            catTabs += `<div onclick="switchShopCat('${cat.replace(/'/g,"\\'")}')" style="padding:6px 14px;border:1px solid var(--border);border-radius:16px;cursor:pointer;font-size:13px;transition:all 0.2s;${active}" class="shop-cat-tab" data-cat="${cat}">${cat} <span style="opacity:0.7;font-size:11px">(${catCount})</span></div>`;
        });
        catTabs += '</div>';
    }

    el.innerHTML = `
        <div class="card">
            <h2>商品管理 <button class="btn btn-blue" style="float:right" onclick="showAddShop()">+ 添加商品</button></h2>
            ${catTabs}
            <div id="shopAdminContent"></div>
        </div>
        <div class="card" id="addShopForm" style="display:none">
            <h2>添加商品</h2>
            <div class="form-row"><label>ID</label><input id="asId" placeholder="如 STONE"></div>
            <div class="form-row"><label>名称</label><input id="asName" placeholder="显示名"></div>
            <div class="form-row"><label>分类</label><input id="asCat" value="默认"></div>
            <div class="form-row"><label>材质</label><input id="asMat" value="STONE"></div>
            <div class="form-row"><label>价格</label><input id="asPrice" type="number" value="100"></div>
            <div class="form-row"><label>库存</label><input id="asStock" type="number" value="-1" placeholder="-1无限"></div>
            <button class="btn btn-green" onclick="doAddShop()">确认添加</button>
        </div>`;
    renderShopAdminContent();
}

function switchShopCat(cat) {
    _currentShopCat = cat;
    document.querySelectorAll('.shop-cat-tab').forEach(tab => {
        const isActive = tab.dataset.cat === cat;
        tab.style.background = isActive ? 'var(--accent)' : '';
        tab.style.color = isActive ? '#fff' : '';
        tab.style.borderColor = isActive ? 'var(--accent)' : 'var(--border)';
    });
    renderShopAdminContent();
}

function renderShopAdminContent() {
    const div = document.getElementById('shopAdminContent');
    if (!div) return;
    const items = _shopItems.filter(i => (i.category || '默认') === _currentShopCat);
    if (items.length === 0) {
        div.innerHTML = '<div class="empty" style="padding:20px">该分类暂无商品</div>';
        return;
    }
    div.innerHTML = `<table class="table">
        <tr><th>ID</th><th>名称</th><th>分类</th><th>购入价</th><th>库存</th><th>销量</th><th>操作</th></tr>
        ${items.map(i=>`<tr>
            <td>${i.id}</td><td>${i.display_name}</td><td>${i.category || '默认'}</td>
            <td>${i.buy_price}</td><td>${i.stock==-1?'∞':i.stock}</td><td>${i.total_sales}</td>
            <td><button class="btn btn-yellow" onclick="editStock('${i.id}',${i.stock})">改库存</button>
            <button class="btn btn-red" onclick="removeShop('${i.id}')">删除</button></td>
        </tr>`).join('')}
    </table>`;
}

function showAddShop() { document.getElementById('addShopForm').style.display='block'; }

async function doAddShop() {
    const r = await postApi('shop_add', {
        id: document.getElementById('asId').value,
        display_name: document.getElementById('asName').value,
        category: document.getElementById('asCat').value,
        material: document.getElementById('asMat').value,
        buy_price: parseInt(document.getElementById('asPrice').value),
        stock: parseInt(document.getElementById('asStock').value)
    });
    toast(r.message, r.success?'ok':'err');
    if (r.success) loadShop(document.getElementById('C'));
}

async function editStock(id, current) {
    const newStock = await showModal('修改库存', '当前库存: ' + current + ' (-1=无限, 0=售罄)', current);
    if (newStock === null) return;
    const r = await postApi('shop_update', {id, stock: parseInt(newStock)});
    toast(r.message, r.success?'ok':'err');
    if (r.success) loadShop(document.getElementById('C'));
}

async function removeShop(id) {
    if (!await glassConfirm('确定删除商品 '+id+'?')) return;
    const r = await postApi('shop_remove', {id});
    toast(r.message, r.success?'ok':'err');
    if (r.success) loadShop(document.getElementById('C'));
}

// ===== CDK管理 =====
async function loadCDK(el) {
    const r = await jsonApi('cdk.php?action=list');
    const list = r.data || [];
    const now = Date.now();
    const threeMinAgo = now - 3 * 60 * 1000;
    
    // 计算可撤销的CDK（3分钟内创建且未使用）
    const recentCDKs = list.filter(c => c.created_at && (c.created_at * 1000) > threeMinAgo && !c.used);
    const canUndo = recentCDKs.length > 0;
    
    el.innerHTML = `
        <div class="card">
            <h2>CDK管理</h2>
            <div style="display:flex;gap:12px;margin-bottom:12px">
                <div class="card" style="flex:1;margin:0">
                    <h2>生成CDK</h2>
                    <div class="form-row"><label>金额</label><input id="cAmount" type="text" value="100" placeholder="固定金额或区间(如100-200)"></div>
                    <div class="form-row"><label>数量</label><input id="cCount" type="number" value="10" min="1" max="100"></div>
                    <div style="display:flex;gap:8px">
                        <button class="btn btn-green" onclick="doBatchCDK()">批量生成</button>
                        ${canUndo ? `<button class="btn btn-red" onclick="undoRecentCDK()">一键撤销 (${recentCDKs.length}个)</button>` : ''}
                    </div>
                </div>
            </div>
            <table class="table">
                <tr><th>兑换码</th><th>金额</th><th>状态</th><th>使用者</th><th>创建时间</th><th>操作</th></tr>
                ${list.map(c=>{
                    const deleteBtn = c.used ?
                        `<span style="color:var(--dim);font-size:12px">-</span>` :
                        `<button class="btn btn-red" style="padding:2px 8px;font-size:12px" onclick="deleteCDK('${c.code}')">删除</button>`;
                    return `<tr>
                        <td style="font-family:monospace">${c.code}</td>
                        <td>${c.amount}</td>
                        <td>${c.used?'<span class="tag tag-used">已使用</span>':'<span class="tag tag-unused">未使用</span>'}</td>
                        <td>${c.used_by||'-'}</td>
                        <td>${c.created_at?new Date(c.created_at*1000).toLocaleString():'-'}</td>
                        <td>${deleteBtn}</td>
                    </tr>`;
                }).join('')}
            </table>
        </div>`;
}

async function doBatchCDK() {
    const amountStr = document.getElementById('cAmount').value.trim();
    const count = parseInt(document.getElementById('cCount').value);
    if (!amountStr||!count) { toast('请填写完整','err'); return; }
    
    // 解析金额：支持固定金额或区间
    let amountData = {};
    const separators = [',', '，', '-', '－', '/', '／', '~', '～'];
    let foundSep = null;
    for (const sep of separators) {
        if (amountStr.includes(sep)) {
            foundSep = sep;
            break;
        }
    }
    if (foundSep) {
        const parts = amountStr.split(foundSep).map(s => parseInt(s.trim())).filter(n => !isNaN(n));
        if (parts.length === 2 && parts[0] <= parts[1]) {
            amountData = {min: parts[0], max: parts[1]};
        } else {
            toast('区间格式错误，如100-200','err'); return;
        }
    } else {
        const amount = parseInt(amountStr);
        if (isNaN(amount) || amount <= 0) { toast('金额必须为正整数','err'); return; }
        amountData = {amount: amount};
    }
    
    const r = await postApi('cdk_batch', {...amountData, count});
    if (r.success) {
        toast('生成了'+count+'个CDK','ok');
        loadCDK(document.getElementById('C'));
    } else { toast(r.message,'err'); }
}

// ===== CDK撤销和删除 =====
async function undoRecentCDK() {
    if (!await glassConfirm('确定要撤销最近3分钟内生成的CDK吗？')) return;
    
    const r = await jsonApi('cdk.php?action=list');
    const list = r.data || [];
    const now = Date.now();
    const threeMinAgo = now - 3 * 60 * 1000;
    
    // 筛选3分钟内创建且未使用的CDK
    const recentCDKs = list.filter(c => c.created_at && (c.created_at * 1000) > threeMinAgo && !c.used);
    
    if (recentCDKs.length === 0) {
        toast('没有可撤销的CDK','err');
        return;
    }
    
    let successCount = 0;
    let failCount = 0;
    
    for (const cdk of recentCDKs) {
        try {
            const result = await postApi('cdk_delete', {code: cdk.code});
            if (result.success) {
                successCount++;
            } else {
                failCount++;
            }
        } catch (e) {
            failCount++;
        }
    }
    
    if (successCount > 0) {
        toast(`成功撤销${successCount}个CDK${failCount > 0 ? `，${failCount}个失败` : ''}`, 'ok');
        loadCDK(document.getElementById('C'));
    } else {
        toast('撤销失败', 'err');
    }
}

async function deleteCDK(code) {
    if (!await glassConfirm(`确定要删除CDK ${code}吗？`)) return;
    
    const r = await postApi('cdk_delete', {code});
    if (r.success) {
        toast('CDK已删除', 'ok');
        loadCDK(document.getElementById('C'));
    } else {
        toast(r.message, 'err');
    }
}

// ===== 流水 =====
async function loadTx(el) {
    el.innerHTML = `
        <div class="card">
            <h2>流水记录</h2>
            <div class="tabs">
                <div class="tab active" onclick="loadAllTx(this)">全服流水</div>
                <div class="tab" onclick="loadPlayerTxTab(this)">指定玩家</div>
            </div>
            <div id="txContent"><div class="empty">点击上方标签加载</div></div>
        </div>`;
    loadAllTx(document.querySelector('.tab.active'));
}

async function loadAllTx(tab) {
    document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
    if(tab)tab.classList.add('active');
    const r = await jsonApi('admin.php?action=all_tx&limit=100');
    const div = document.getElementById('txContent');
    if (!r.success) { div.innerHTML=r.message; return; }
    const txs = r.data||[];
    div.innerHTML = txs.length ? `<table class="table"><tr><th>时间</th><th>玩家</th><th>类型</th><th>金额</th><th>操作人</th><th>备注</th></tr>
    ${txs.map(t=>`<tr><td>${new Date(t.created_at*1000).toLocaleString()}</td><td>${t.player_name}</td><td>${t.type}</td><td>${t.amount}</td><td>${t.operator||'-'}</td><td>${t.reason||'-'}</td></tr>`).join('')}</table>` : '<div class="empty">暂无记录</div>';
}

function loadPlayerTxTab(tab) {
    document.querySelectorAll('.tab').forEach(t=>t.classList.remove('active'));
    if(tab)tab.classList.add('active');
    document.getElementById('txContent').innerHTML = `
        <div class="form-row"><label>玩家</label><input id="txPlayer" placeholder="玩家名"><button class="btn btn-blue" onclick="loadPlayerTx()">查询</button></div>
        <div id="ptxResult"></div>`;
}

async function loadPlayerTx() {
    const player = document.getElementById('txPlayer').value.trim();
    if (!player) return;
    const r = await jsonApi('admin.php?action=player_tx&player='+encodeURIComponent(player)+'&limit=100');
    const div = document.getElementById('ptxResult');
    if (!r.success) { div.innerHTML='<p style="color:var(--red)">'+r.message+'</p>'; return; }
    const txs = r.data||[];
    div.innerHTML = txs.length ? `<table class="table"><tr><th>时间</th><th>类型</th><th>金额</th><th>操作人</th><th>备注</th></tr>
    ${txs.map(t=>`<tr><td>${new Date(t.created_at*1000).toLocaleString()}</td><td>${t.type}</td><td>${t.amount}</td><td>${t.operator||'-'}</td><td>${t.reason||'-'}</td></tr>`).join('')}</table>` : '<div class="empty">暂无记录</div>';
}

// ===== Token生成 =====
function loadToken(el) {
    el.innerHTML = `
        <div class="card">
            <h2>生成插件Token</h2>
            <p style="color:var(--dim);font-size:12px;margin-bottom:12px">生成后复制给插件，插件可使用此token访问Web API</p>
            <div class="form-row"><label>玩家</label><input id="tPlayer" value="admin"></div>
            <div class="form-row"><label>用途</label>
                <select id="tPurpose" style="flex:1;padding:7px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--text)">
                    <option value="all">全部权限</option>
                    <option value="admin">管理员</option>
                    <option value="sync">数据同步</option>
                    <option value="shop">商城</option>
                </select>
            </div>
            <div class="form-row"><label>有效期</label><input id="tExpire" type="number" value="600" placeholder="秒"> 秒</div>
            <button class="btn btn-green" onclick="doGenToken()">生成Token</button>
            <div id="tResult" style="margin-top:12px"></div>
        </div>`;
}

async function doGenToken() {
    const player = document.getElementById('tPlayer').value;
    const purpose = document.getElementById('tPurpose').value;
    const expire = parseInt(document.getElementById('tExpire').value);
    const r = await fetch(A, {
        method: 'POST', headers: {'Content-Type':'application/json'},
        body: JSON.stringify({action: 'gen_token', player, purpose, expire})
    });
    const res = await r.json();
    const div = document.getElementById('tResult');
    if (res.success) {
        div.innerHTML = `<div class="card" style="border-color:var(--green)">
            <p style="font-size:13px">Token:</p>
            <code style="word-break:break-all;font-size:12px;color:var(--green);display:block;margin:8px 0">${res.data.token}</code>
            <button class="btn btn-blue" onclick="navigator.clipboard.writeText('${res.data.token}');toast('已复制','ok')">复制</button>
        </div>`;
    } else {
        div.innerHTML = `<p style="color:var(--red)">${res.message||'生成失败'}</p>`;
    }
}

// ===== 用户管理 =====
async function loadUsers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    // ★ 不再调用 list_users（非分页），直接用轻量级接口获取在线状态
    // 分页数据由 lazyLoadUsersPage 单独获取
    try {
        const onlineR = await jsonApi('admin.php?action=list_online_names');
        const onlineMap = {};
        const onlineSet = new Set();
        if (onlineR.success && onlineR.data) {
            onlineR.data.forEach(name => {
                onlineMap[name.toLowerCase()] = true;
                onlineSet.add(name.toLowerCase());
            });
        }
        renderUserTabs(el, [], onlineSet, onlineMap);
    } catch(e) {
        renderUserTabs(el, [], new Set(), {});
    }
}

// 用户管理缓存
let cachedUsersData = null;
let cachedOnlineSet = null;
let cachedOnlineMap = null;

function reRenderAllUsers() {
    // 从其他标签切回来时，重新加载最新数据
    if (cachedUsersData !== null) {
        const el = document.getElementById('C');
        if (!el) return;
        loadUsers(el); // 重新加载数据
    }
}

function renderUserTabs(el, users, onlineSet, onlineMap) {
    const onlineCount = onlineSet.size;
    el.innerHTML = `
        <div class="card">
            <h2>用户管理</h2>
            <div class="tabs" id="userTabs">
                <div class="tab active" data-tab="all" onclick="renderAllUsers(this)">全部用户</div>
                <div class="tab" data-tab="online" onclick="renderOnlineUsers(this)">在线玩家</div>
                <div class="tab" data-tab="sameip" onclick="loadSameIpTab(this)">同IP玩家</div>
                <div class="tab" data-tab="active1h" onclick="loadActiveTab(this, 3600)">1小时活跃</div>
                <div class="tab" data-tab="active1d" onclick="loadActiveTab(this, 86400)">1天活跃</div>
                <div class="tab" data-tab="active1w" onclick="loadActiveTab(this, 604800)">1周活跃</div>
            </div>
            <div id="userTabContent"><div class="empty">点击标签页加载</div></div>
        </div>`;
    // 默认显示全部用户
    const allTab = el.querySelector('#userTabs .tab[data-tab="all"]');
    renderAllUsers(allTab, users, onlineSet, onlineMap);
}

// 注意：上面的缓存变量和 reRenderAllUsers() 已在第454-465行定义，这里不再重复
function renderAllUsers(tab, users, onlineSet, onlineMap) {
    if (!tab) return;
    document.querySelectorAll('#userTabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    
    // 缓存数据
    cachedUsersData = users;
    cachedOnlineSet = onlineSet;
    cachedOnlineMap = onlineMap;

    const div = document.getElementById('userTabContent');
    
    // 懒加载模式：初始只加载前50条，其余通过滚动/搜索加载
    const loadPage = 1;
    const limit = 50;
    const search = (window.currentSearch || '').toLowerCase();
    
    div.innerHTML = `
        <div class="form-row" style="margin-bottom:4px">
            <input id="userSearch" placeholder="智能搜索：玩家名 / IP / 日期(2026-06-18) / 地区(广东)" oninput="handleUserSearch()" style="flex:1">
            <button class="btn btn-blue" onclick="doLazyLoadSearch()">搜索</button>
        </div>
        <div style="font-size:11px;color:var(--dim);margin-bottom:12px">支持：纯文本→玩家名 | IP格式→IP搜索 | 日期格式→日期搜索 | 省/市名→地区搜索</div>
        <div id="userLazyContainer">
            <div class="empty">加载中...</div>
        </div>
        <div id="userLazyLoadMore" style="text-align:center;padding:16px">
            <button class="btn btn-blue" onclick="doLazyLoadMore()">加载更多</button>
        </div>`;
    
    lazyLoadUsersPage(loadPage, limit, search, onlineSet);
}

// 用户懒加载状态
let lazyLoadState = {
    currentPage: 1,
    totalPages: 1,
    isLoading: false,
    hasMore: true,
    currentSearch: ''
};
let isFirstLoad = true; // ★ 标记是否为首次加载

function lazyLoadUsersPage(page, limit, search, onlineSet) {
    if (lazyLoadState.isLoading) return;
    lazyLoadState.isLoading = true;
    lazyLoadState.currentPage = page;
    lazyLoadState.currentSearch = search;
    lazyLoadState.currentPage = page;
    
    // 搜索模式：不限制条数，一次加载全部
    // 浏览模式：每批15-20个
    const batchSize = search ? 200 : 20;
    
    const queryParams = {
        action: 'list_users_paginated',
        page: page,
        limit: batchSize,
        _t: Date.now(), // ★ 缓存失效：每次请求带时间戳
        ...(search ? { search: encodeURIComponent(search) } : {})
    };
    const queryString = new URLSearchParams(queryParams).toString();
    
    console.log('[LazyLoad] Fetching users page ' + page + ': api/admin.php?' + queryString);
    
    // 设置超时时间（IP查询可能需要较长时间）
    const timeoutMs = 15000;
    
    // 使用fetch的timeout（AbortController）
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
    
    fetch('api/admin.php?' + queryString, { 
        credentials: 'same-origin',
        signal: controller.signal,
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
        .finally(() => clearTimeout(timeoutId))
        .then(r => {
            clearTimeout(timeoutId);
            console.log('[LazyLoad] Response status: ' + r.status);
            // 先获取text再解析JSON，防止r.json()直接崩溃
            return r.text().then(text => {
                console.log('[LazyLoad] Raw response (' + text.length + ' bytes): ' + text.substring(0, 200));
                // 清理可能的BOM或不可见字符
                text = text.replace(/^\uFEFF/, '').trim();
                if (!text) throw new Error('Empty response');
                try {
                    const parsed = JSON.parse(text);
                    console.log('[LazyLoad] JSON parsed OK, keys:', Object.keys(parsed));
                    return parsed;
                } catch(e) {
                    console.error('[LazyLoad] JSON parse error:', e.message, 'text preview:', text.substring(0, 200));
                    throw new Error('服务器返回无效JSON: ' + text.substring(0, 50));
                }
            });
        })
        .then(r => {
            console.log('[LazyLoad] ★★★ 进入第二个then块, r:', typeof r, r ? Object.keys(r).join(',') : 'null');
            lazyLoadState.isLoading = false;
            
            try {
            if (!r || !r.success) {
                console.log('[LazyLoad] 请求失败或无success字段:', r);
                document.getElementById('userLazyContainer').innerHTML = '<div class="empty">加载失败: ' + (r?.message || '未知错误') + '</div>';
                return;
            }
            
            const users = r.data || [];
            const pag = r.pagination || {};
            lazyLoadState.totalPages = pag.total_pages || 1;
            lazyLoadState.hasMore = pag.has_more || false;
            
            // ★ 收集所有需要查询的IP（未缓存 + 查询失败 + 查询中）
            console.log('[LazyLoad] Backend uncached_ips:', JSON.stringify(r.uncached_ips || []));
            console.log('[LazyLoad] Users count:', users.length);
            users.forEach(u => {
                console.log('[LazyLoad] User:', u.player_name, 'IP:', u.ip_address, 'Location:', u.ip_location);
            });

            const allQueryIps = new Set();
            // 1. 后端返回的未缓存IP
            const uncachedIps = r.uncached_ips || [];
            uncachedIps.forEach(ip => {
                const valid = /^\d+\.\d+\.\d+\.\d+$/.test(ip);
                console.log('[LazyLoad] Uncached IP:', ip, 'valid:', valid);
                if (valid) allQueryIps.add(ip);
            });
            // 2. 前端发现的需要查询的IP（查询失败/查询中.../短杠位置）
            users.forEach(u => {
                const loc = u.ip_location;
                const ip = u.ip_address;
                const needQuery = loc === '查询失败' || loc === '-' || loc === '查询中...' || !loc;
                const ipValid = ip && ip !== '-' && /^\d+\.\d+\.\d+\.\d+$/.test(ip);
                console.log('[LazyLoad] User:', u.player_name, 'IP:', ip, 'Location:', JSON.stringify(loc), 'needQuery:', needQuery, 'ipValid:', ipValid);
                if (needQuery && ipValid) {
                    console.log('[LazyLoad] ★ Adding IP to query:', ip);
                    allQueryIps.add(ip);
                }
            });
            // 3. 批量查询
            const queryIps = Array.from(allQueryIps);
            console.log('[LazyLoad] Total IPs to query:', queryIps.length, queryIps);

            // ★ 渲染表格后再统一扫描DOM中的"查询中..."IP（解决uncached_ips为空的问题）

            if (page === 1) {
                // 第一页：清空并显示新数据
                const div = document.getElementById('userLazyContainer');
                div.innerHTML = `
                    <table class="table" id="lazyUserTable">
                        <tr><th>玩家名</th><th>注册时间</th><th>最后登录</th><th>积分</th><th>在线时长</th><th>邮箱</th><th>IP地址</th><th>IP属地</th><th>操作</th></tr>
                    </table>`;
                // ★ 首次加载完成后，标记为非首次
                if (isFirstLoad) {
                    isFirstLoad = false;
                    console.log('[LazyLoad] First load completed');
                }
            }
            
            const table = document.getElementById('lazyUserTable');
            
            if (users.length === 0 && page === 1) {
                table.innerHTML = '<tr><td colspan="9" style="text-align:center;padding:20px;color:var(--dim)">暂无用户数据</td></tr>';
                document.getElementById('userLazyLoadMore').style.display = 'none';
                return;
            }
            
            if (users.length === 0) {
                document.getElementById('userLazyLoadMore').style.display = 'none';
                return;
            }
            
            // ★ 判断是否为日期搜索：日期搜索时折叠同IP段玩家
            const s = (search || '').toLowerCase();
            const isDateSearch = /今天|昨日|today|yesterday|今天|当日|前一日/.test(s) || /^\d{4}[-\/]\d{1,2}[-\/]\d{1,2}$/.test(s);

            // 追加用户行（日期搜索时按/24子网折叠）
            let displayUsers = users;
            if (isDateSearch && users.length > 0) {
                // 按/24子网分组（只对有效IPv4地址进行分组）
                const subnetMap = {};
                const subnetOrder = [];
                users.forEach(u => {
                    const ip = u.ip_address || '-';
                    let subnet = '_unknown_' + u.player_name; // 默认每个玩家独立一组
                    // 只有有效IPv4地址才进行子网分组
                    if (ip !== '-' && ip.indexOf('.') !== -1) {
                        const parts = ip.split('.');
                        if (parts.length === 4 && parts.every(part => part !== '' && !isNaN(part))) {
                            subnet = parts[0] + '.' + parts[1] + '.' + parts[2];
                        }
                    }
                    if (!subnetMap[subnet]) { subnetMap[subnet] = []; subnetOrder.push(subnet); }
                    subnetMap[subnet].push(u);
                });
                displayUsers = [];
                subnetOrder.forEach(sub => {
                    const group = subnetMap[sub];
                    displayUsers.push({...group[0], _foldCount: group.length - 1});
                });
            }

            displayUsers.forEach(u => {
                const isOnline = onlineSet && onlineSet.has((u.player_name || '').toLowerCase());
                const playerNameClass = isOnline ? 'player-online' : '';
                const regTime = u.register_time ? new Date(u.register_time * 1000).toLocaleString() : '-';
                const loginTime = u.last_login_time ? new Date(u.last_login_time * 1000).toLocaleString() : '-';
                const hours = Math.floor((u.total_online_time || 0) / 3600);
                const ip = u.ip_address || '-';
                const ipLoc = u.ip_location || '-';
                const foldSuffix = u._foldCount > 0 ? ` <span style="color:var(--yellow);font-size:12px">(同ip段${u._foldCount}名)</span>` : '';

                const tr = document.createElement('tr');
                tr.setAttribute('data-name', (u.player_name || '').toLowerCase());
                tr.innerHTML = `
                    <td class="${playerNameClass}">${isOnline ? '🟢 ' : ''}${u.player_name}${foldSuffix}</td>
                    <td>${regTime}</td>
                    <td>${loginTime}</td>
                    <td>${u.points || 0}</td>
                    <td>${hours}h</td>
                    <td>${u.email || '-'}</td>
                    <td style="font-size:12px;font-family:monospace">${ip}</td>
                    <td style="font-size:12px">${ipLoc}</td>
                    <td>
                        <button class="btn btn-blue" onclick="showUserInfoAndReset('${u.player_name}','${u.email || ''}')">查看 & 重置密码</button>
                    </td>`;
                table.querySelector('tbody')?.appendChild(tr) || table.appendChild(tr);
            });
            
            // ★ 表格渲染完成后，扫描DOM中的"查询中..."IP并批量查询
            // （后端uncached_ips可能为空，但前端仍需查询未缓存的IP）
            setTimeout(() => {
                const domQueryIps = new Set();
                // 先加入后端和前端收集的IP
                queryIps.forEach(ip => domQueryIps.add(ip));
                // 再扫描DOM表格中的"查询中..."和"查询失败"
                document.querySelectorAll('#lazyUserTable tr[data-name]').forEach(row => {
                    const cells = row.querySelectorAll('td');
                    if (cells.length >= 8) {
                        const locText = cells[7].textContent.trim(); // IP属地列 (第8列)
                        const ipText = cells[6].textContent.trim(); // IP地址列 (第7列)
                        if ((locText === '查询中...' || locText === '查询失败' || locText === '⚠️ 重试中...') 
                            && /^\d+\.\d+\.\d+\.\d+$/.test(ipText)) {
                            domQueryIps.add(ipText);
                        }
                    }
                });
                const finalIps = Array.from(domQueryIps);
                console.log('[LazyLoad] ★ DOM scan found', finalIps.length, 'IPs to query:', finalIps);
                if (finalIps.length > 0) {
                    batchQueryIpLocations(finalIps);
                }
            }, 500); // 延迟500ms确保DOM渲染完成
            
            // ★ 3秒后自动重试：检查是否仍有"查询中..."的IP
            setTimeout(() => {
                const retryIps = [];
                document.querySelectorAll('#lazyUserTable tr[data-name]').forEach(row => {
                    const cells = row.querySelectorAll('td');
                    if (cells.length >= 8) {
                        const locText = cells[7].textContent.trim();
                        const ipText = cells[6].textContent.trim();
                        if ((locText === '查询中...' || locText === '查询失败' || locText === '⚠️ 重试中...') 
                            && /^\d+\.\d+\.\d+\.\d+$/.test(ipText)) {
                            retryIps.push(ipText);
                        }
                    }
                });
                if (retryIps.length > 0) {
                    console.log('[LazyLoad] ★ Retry: still', retryIps.length, 'IPs need query:', retryIps);
                    batchQueryIpLocations(retryIps);
                } else {
                    console.log('[LazyLoad] ✓ No more "查询中..." IPs');
                }
            }, 3000);
            
            // 更新加载更多按钮
            const loadMoreDiv = document.getElementById('userLazyLoadMore');
            if (lazyLoadState.hasMore) {
                loadMoreDiv.style.display = 'block';
                loadMoreDiv.innerHTML = `<button class="btn btn-blue" onclick="doLazyLoadMore()">加载更多 (还有${lazyLoadState.totalPages - lazyLoadState.currentPage}页)</button>`;
            } else {
                loadMoreDiv.style.display = 'block';
                loadMoreDiv.innerHTML = '<p style="color:var(--dim);font-size:12px">已加载全部内容 (共' + (pag.total || 0) + '人)</p>';
            }
            
            // ★ 更新未缓存IP的显示状态
            if (uncachedIps.length > 0) {
                updateUncachedIpDisplay(uncachedIps);
            }
            } catch(innerErr) {
                console.error('[LazyLoad] ★★★ 内部处理异常:', innerErr.message, innerErr.stack);
                lazyLoadState.isLoading = false;
                document.getElementById('userLazyContainer').innerHTML = '<div class="empty">数据处理异常: ' + innerErr.message + '</div>';
            }
        })
        .catch(e => {
            clearTimeout(timeoutId);
            lazyLoadState.isLoading = false;
            document.getElementById('userLazyContainer').innerHTML = '<div class="empty">加载失败: ' + (e.name === 'AbortError' ? '请求超时，请重试' : e.message) + '</div>';
        });
}

// ★ 批量查询未缓存的IP归属地（串行，避免并发问题）
function batchQueryIpLocations(ips) {
    if (!ips || ips.length === 0) {
        console.log('[BatchIP] Empty IP list, skipping');
        return;
    }

    // 去重
    const uniqueIps = [...new Set(ips)];
    console.log('[BatchIP] Starting batch query for ' + uniqueIps.length + ' IPs:', uniqueIps);

    const batchSize = 3;
    let idx = 0;
    let batchNum = 0;

    function queryNext() {
        if (idx >= uniqueIps.length) {
            console.log('[BatchIP] ✓ All ' + batchNum + ' batches completed');
            return;
        }
        const batch = uniqueIps.slice(idx, idx + batchSize);
        idx += batchSize;
        batchNum++;

        console.log('[BatchIP] Batch #' + batchNum + ' requesting:', batch);

        const startTime = Date.now();
        // ★ 前端超时15秒：防止PHP挂起后fetch永远不返回
        const controller = new AbortController();
        const abortTimer = setTimeout(() => controller.abort(), 15000);
        fetch('api/admin.php', {
            method: 'POST',
            credentials: 'same-origin',
            signal: controller.signal,
            headers: {
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            body: JSON.stringify({
                action: 'batch_query_ips',
                ips: batch
            })
        })
        .then(r => {
            clearTimeout(abortTimer);
            console.log('[BatchIP] Batch #' + batchNum + ' HTTP status:', r.status, 'time:', (Date.now() - startTime) + 'ms');
            return r.text();
        })
        .then(text => {
            console.log('[BatchIP] Batch #' + batchNum + ' raw response:', text.substring(0, 500));
            try {
                const r = JSON.parse(text);
                if (r.success && r.data) {
                    console.log('[BatchIP] Batch #' + batchNum + ' parsed results:', JSON.stringify(r.data));
                    updateIpLocationDisplay(r.data);
                } else {
                    console.error('[BatchIP] Batch #' + batchNum + ' failed:', r.message || 'unknown');
                }
            } catch(e) {
                console.error('[BatchIP] Batch #' + batchNum + ' JSON parse error:', e, 'text:', text.substring(0, 200));
            }
            // 串行：等当前批次完成后再查下一批
            setTimeout(queryNext, 300);
        })
        .catch(e => {
            clearTimeout(abortTimer);
            console.error('[BatchIP] Batch #' + batchNum + ' fetch error:', e.name === 'AbortError' ? 'TIMEOUT (15s)' : e);
            setTimeout(queryNext, 300);
        });
    }

    queryNext();
}

// ★ 更新未缓存IP的显示状态（显示"查询中..."）
function updateUncachedIpDisplay(ips) {
    const table = document.getElementById('lazyUserTable');
    if (!table) return;
    
    const rows = table.querySelectorAll('tr[data-name]');
    rows.forEach(row => {
        const ipCell = row.querySelector('td:nth-child(7)'); // IP地址列
        const locCell = row.querySelector('td:nth-child(8)'); // IP属地列
        if (ipCell && locCell) {
            const ip = ipCell.textContent.trim();
            if (ips.includes(ip)) {
                locCell.textContent = '查询中...';
                locCell.style.color = 'var(--yellow)';
            }
        }
    });
}

// ★ 更新IP归属地显示
function updateIpLocationDisplay(ipLocationMap) {
    const table = document.getElementById('lazyUserTable');
    if (!table) return;
    
    const rows = table.querySelectorAll('tr[data-name]');
    rows.forEach(row => {
        const ipCell = row.querySelector('td:nth-child(7)'); // IP地址列
        const locCell = row.querySelector('td:nth-child(8)'); // IP属地列
        if (ipCell && locCell) {
            const ip = ipCell.textContent.trim();
            const currentLoc = locCell.textContent.trim();
            
            // 只更新有效的IP归属地，不更新"查询失败"等无效值
            if (ipLocationMap[ip] && ipLocationMap[ip] !== '查询失败' && ipLocationMap[ip] !== '-') {
                locCell.textContent = ipLocationMap[ip];
                locCell.style.color = ''; // 恢复默认颜色
            } else if (ipLocationMap[ip] === '查询失败') {
                // 如果还是查询失败，显示为黄色提示
                locCell.textContent = '⚠️ 重试中...';
                locCell.style.color = 'var(--yellow)';
            }
        }
    });
}

function doLazyLoadMore() {
    if (!lazyLoadState.hasMore || lazyLoadState.isLoading) return;
    lazyLoadState.currentPage++;
    const queryParams = {
        action: 'list_users_paginated',
        page: lazyLoadState.currentPage,
        limit: 20,
        _t: Date.now(),
        ...(lazyLoadState.currentSearch ? { search: encodeURIComponent(lazyLoadState.currentSearch) } : {})
    };
    const queryString = new URLSearchParams(queryParams).toString();
    
    console.log('[LazyLoad More] Loading page ' + lazyLoadState.currentPage + ': api/admin.php?' + queryString);
    
    const loadMoreDiv = document.getElementById('userLazyLoadMore');
    loadMoreDiv.innerHTML = '<span style="color:var(--dim)">加载中...</span>';
    
    // 设置30秒超时（IP查询需要时间）
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 30000);
    
    fetch('api/admin.php?' + queryString, { 
        credentials: 'same-origin',
        signal: controller.signal,
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
        .finally(() => clearTimeout(timeoutId))
        .then(r => {
            clearTimeout(timeoutId);
            console.log('[LazyLoad More] Response status: ' + r.status);
            return r.text().then(text => {
                console.log('[LazyLoad More] Raw response (' + text.length + ' bytes)');
                text = text.replace(/^\uFEFF/, '').trim();
                if (!text) throw new Error('Empty response');
                try {
                    return JSON.parse(text);
                } catch(e) {
                    console.error('[LazyLoad More] JSON parse error:', e.message, text.substring(0, 200));
                    throw new Error('服务器返回无效JSON');
                }
            });
        })
        .then(r => {
            console.log('[LazyLoad More] ★ Parsed OK, success:', r.success, 'data count:', r.data?.length);
            lazyLoadState.isLoading = false;
            
            if (!r.success || !r.data || r.data.length === 0) {
                console.log('[LazyLoad More] No more data');
                lazyLoadState.hasMore = false;
                document.getElementById('userLazyLoadMore').innerHTML = '<p style="color:var(--dim);font-size:12px">已加载全部内容</p>';
                return;
            }
            
            const users = r.data;
            const pag = r.pagination || {};
            lazyLoadState.totalPages = pag.total_pages || 1;
            lazyLoadState.hasMore = pag.has_more || false;

            // ★ 收集需要查询的IP（与lazyLoadUsersPage一致）
            const moreQueryIps = new Set();
            // 1. 后端返回的未缓存IP
            const moreUncachedIps = r.uncached_ips || [];
            console.log('[LazyLoad More] Backend uncached_ips:', JSON.stringify(moreUncachedIps));
            moreUncachedIps.forEach(ip => {
                if (/^\d+\.\d+\.\d+\.\d+$/.test(ip)) moreQueryIps.add(ip);
            });
            // 2. 前端发现的需要查询的IP
            users.forEach(u => {
                const needQuery = u.ip_location === '查询失败' || u.ip_location === '-' || u.ip_location === '查询中...' || !u.ip_location;
                const ipValid = u.ip_address && u.ip_address !== '-' && /^\d+\.\d+\.\d+\.\d+$/.test(u.ip_address);
                if (needQuery && ipValid) {
                    console.log('[LazyLoad More] User IP needs query:', u.ip_address, 'location:', u.ip_location);
                    moreQueryIps.add(u.ip_address);
                }
            });
            // 3. 批量查询
            const moreQueryList = Array.from(moreQueryIps);
            console.log('[LazyLoad More] Total IPs to query:', moreQueryList.length, moreQueryList);
            if (moreQueryList.length > 0) {
                batchQueryIpLocations(moreQueryList);
            }
            
            const table = document.getElementById('lazyUserTable');
            const onlineSet = cachedOnlineSet;
            
            users.forEach(u => {
                const isOnline = onlineSet && onlineSet.has((u.player_name || '').toLowerCase());
                const playerNameClass = isOnline ? 'player-online' : '';
                const regTime = u.register_time ? new Date(u.register_time * 1000).toLocaleString() : '-';
                const loginTime = u.last_login_time ? new Date(u.last_login_time * 1000).toLocaleString() : '-';
                const hours = Math.floor((u.total_online_time || 0) / 3600);
                const ip = u.ip_address || '-';
                const ipLoc = u.ip_location || '-';
                
                const tr = document.createElement('tr');
                tr.setAttribute('data-name', (u.player_name || '').toLowerCase());
                tr.innerHTML = `
                    <td class="${playerNameClass}">${isOnline ? '🟢 ' : ''}${u.player_name}</td>
                    <td>${regTime}</td>
                    <td>${loginTime}</td>
                    <td>${u.points || 0}</td>
                    <td>${hours}h</td>
                    <td>${u.email || '-'}</td>
                    <td style="font-size:12px;font-family:monospace">${ip}</td>
                    <td style="font-size:12px">${ipLoc}</td>
                    <td>
                        <button class="btn btn-blue" onclick="showUserInfoAndReset('${u.player_name}','${u.email || ''}')">查看 & 重置密码</button>
                    </td>`;
                table.querySelector('tbody')?.appendChild(tr) || table.appendChild(tr);
            });
            
            // ★ 表格渲染完成后，扫描DOM中的"查询中..."IP并批量查询
            setTimeout(() => {
                const domQueryIps = new Set();
                moreQueryList.forEach(ip => domQueryIps.add(ip));
                document.querySelectorAll('#lazyUserTable tr[data-name]').forEach(row => {
                    const cells = row.querySelectorAll('td');
                    if (cells.length >= 8) {
                        const locText = cells[7].textContent.trim();
                        const ipText = cells[6].textContent.trim();
                        if ((locText === '查询中...' || locText === '查询失败' || locText === '⚠️ 重试中...') 
                            && /^\d+\.\d+\.\d+\.\d+$/.test(ipText)) {
                            domQueryIps.add(ipText);
                        }
                    }
                });
                const finalIps = Array.from(domQueryIps);
                console.log('[LazyLoad More] ★ DOM scan found', finalIps.length, 'IPs to query:', finalIps);
                if (finalIps.length > 0) {
                    batchQueryIpLocations(finalIps);
                }
            }, 500);
            
            // ★ 3秒后自动重试
            setTimeout(() => {
                const retryIps = [];
                document.querySelectorAll('#lazyUserTable tr[data-name]').forEach(row => {
                    const cells = row.querySelectorAll('td');
                    if (cells.length >= 8) {
                        const locText = cells[7].textContent.trim();
                        const ipText = cells[6].textContent.trim();
                        if ((locText === '查询中...' || locText === '查询失败' || locText === '⚠️ 重试中...') 
                            && /^\d+\.\d+\.\d+\.\d+$/.test(ipText)) {
                            retryIps.push(ipText);
                        }
                    }
                });
                if (retryIps.length > 0) {
                    console.log('[LazyLoad More] ★ Retry:', retryIps.length, 'IPs still need query:', retryIps);
                    batchQueryIpLocations(retryIps);
                }
            }, 3000);
            
            const loadMoreDiv = document.getElementById('userLazyLoadMore');
            if (lazyLoadState.hasMore) {
                loadMoreDiv.innerHTML = `<button class="btn btn-blue" onclick="doLazyLoadMore()">加载更多 (还有${lazyLoadState.totalPages - lazyLoadState.currentPage}页)</button>`;
            } else {
                loadMoreDiv.innerHTML = '<p style="color:var(--dim);font-size:12px">已加载全部内容 (共' + (pag.total || 0) + '人)</p>';
            }
        })
        .catch(e => {
            console.error('[LazyLoad More] ★ Error:', e.name, e.message);
            clearTimeout(timeoutId);
            lazyLoadState.isLoading = false;
            document.getElementById('userLazyLoadMore').innerHTML = '<button class="btn btn-red" onclick="doLazyLoadMore()">加载失败，点击重试</button>';
        });
}

function handleUserSearch() {
    // 防抖：200ms后触发搜索
    if (window.userSearchTimer) clearTimeout(window.userSearchTimer);
    window.userSearchTimer = setTimeout(() => {
        doLazyLoadSearch();
    }, 200);
}

function doLazyLoadSearch() {
    const searchInput = document.getElementById('userSearch');
    const search = searchInput ? searchInput.value.trim() : '';
    window.currentSearch = search;
    
    // 重置分页状态
    lazyLoadState.currentPage = 1;
    lazyLoadState.totalPages = 1;
    lazyLoadState.hasMore = true;
    lazyLoadState.currentSearch = search;
    
    lazyLoadUsersPage(1, 50, search, cachedOnlineSet);
}

// ===== 活跃用户（独立标签页）=====
async function loadActivePlayers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await jsonApi('admin.php?action=list_active_players');
    if (!r.success) { el.innerHTML='<div class="card">'+r.message+'</div>'; return; }
    const players = r.data || [];
    const totalBeforeDedup = r.total_before_dedup || players.length;
    const dedupCount = totalBeforeDedup - players.length;

    el.innerHTML = `
        <div class="card">
            <h2>24小时活跃用户 <span style="color:var(--accent);font-size:14px">(${players.length}人)</span>
                ${dedupCount > 0 ? `<span style="color:var(--yellow);font-size:12px;margin-left:8px">（已隐藏${dedupCount}个同子网用户）</span>` : ''}
            </h2>
            <table class="table">
                <tr><th>玩家名</th><th>最后活跃</th><th>总在线时长</th><th>IP</th><th>IP属地</th></tr>
                ${players.map(p => {
                    const loginTime = p.last_login_time ? new Date(p.last_login_time*1000).toLocaleString() : '-';
                    const ip = p.ip_address || '-';
                    const ipLoc = p.ip_location || '-';
                    return `<tr>
                        <td class="player-online">${p.player_name}</td>
                        <td>${loginTime}</td>
                        <td>${p.hours_online}小时</td>
                        <td style="font-size:12px;font-family:monospace">${ip}</td>
                        <td style="font-size:12px">${ipLoc}</td>
                    </tr>`;
                }).join('')}
            </table>
            ${players.length === 0 ? '<div class="empty">暂无活跃用户</div>' : ''}
        </div>`;
}

// ===== 活跃用户（标签页内）=====
function loadActiveTab(tab, seconds) {
    if (!tab) return;
    document.querySelectorAll('#userTabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    
    const div = document.getElementById('userTabContent');
    div.innerHTML = '<div class="empty">加载中...</div>';
    
    const label = seconds === 3600 ? '1小时' : seconds === 86400 ? '1天' : '1周';
    const r = jsonApi('admin.php?action=list_active_players&period=' + seconds);
    r.then(res => {
        if (!res.success) { div.innerHTML='<div class="card" style="border-color:var(--red)">'+res.message+'</div>'; return; }
        const players = res.data || [];
        const totalBeforeDedup = res.total_before_dedup || players.length;
        const dedupCount = totalBeforeDedup - players.length;

        // 统计同子网玩家（只对有效IPv4地址进行分组）
        const subnetCountMap = {};
        players.forEach(p => {
            const ip = p.ip_address || '-';
            // 只有有效IPv4地址才进行子网分组
            if (ip !== '-' && ip.indexOf('.') !== -1) {
                const parts = ip.split('.');
                if (parts.length === 4 && parts.every(part => part !== '' && !isNaN(part))) {
                    const subnet = parts[0] + '.' + parts[1] + '.' + parts[2];
                    subnetCountMap[subnet] = (subnetCountMap[subnet] || 0) + 1;
                }
            }
        });
        
        div.innerHTML = `
            <h3 style="color:var(--dim);font-size:14px;margin-bottom:12px">
                ${label}内活跃用户 <span style="color:var(--accent)">(${players.length}人)</span>
                ${dedupCount > 0 ? `<span style="color:var(--yellow);font-size:12px;margin-left:8px">（已隐藏${dedupCount}个同子网用户）</span>` : ''}
            </h3>
            <table class="table">
                <tr><th>玩家名</th><th>最后活跃</th><th>总在线时长</th><th>IP地址</th><th>IP属地</th></tr>
                ${players.map(p => {
                    const loginTime = p.last_login_time ? new Date(p.last_login_time*1000).toLocaleString() : '-';
                    const ip = p.ip_address || '-';
                    const ipLoc = p.ip_location || '-';
                    let subnet = '';
                    // 只有有效IPv4地址才进行子网分组
                    if (ip !== '-' && ip.indexOf('.') !== -1) {
                        const parts = ip.split('.');
                        if (parts.length === 4 && parts.every(part => part !== '' && !isNaN(part))) {
                            subnet = parts[0] + '.' + parts[1] + '.' + parts[2];
                        }
                    }
                    const isShared = subnet && (subnetCountMap[subnet] || 0) > 1;
                    const ipStyle = isShared ? 'color:var(--yellow);font-weight:700' : '';
                    const ipBadge = isShared ? ` <span style="font-size:10px;background:var(--yellow);color:#000;padding:1px 4px;border-radius:3px">同网段${subnetCountMap[subnet]}人</span>` : '';
                    return `<tr>
                        <td>${p.player_name}</td>
                        <td>${loginTime}</td>
                        <td>${p.hours_online}h</td>
                        <td style="font-size:12px;font-family:monospace;${ipStyle}">${ip}${ipBadge}</td>
                        <td style="font-size:12px">${ipLoc}</td>
                    </tr>`;
                }).join('')}
            </table>
            ${players.length === 0 ? '<div class="empty">暂无活跃用户</div>' : ''}`;
    }).catch(e => {
        div.innerHTML = '<div class="card" style="border-color:var(--red)">加载失败: ' + e.message + '</div>';
    });
}

// ===== 同IP玩家标签页 =====
async function loadSameIpTab(tab) {
    if (!tab) return;
    document.querySelectorAll('#userTabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    
    const div = document.getElementById('userTabContent');
    div.innerHTML = '<div class="empty">加载中...</div>';
    
    const r = await jsonApi('admin.php?action=list_same_ip');
    if (!r.success || !r.data || r.data.length === 0) {
        div.innerHTML = '<div class="card"><h3>同IP玩家</h3><p style="color:var(--dim)">暂无同IP多玩家记录</p></div>';
        return;
    }
    
    const groups = r.data;
    let html = `<h3 style="color:var(--dim);font-size:14px;margin-bottom:12px">同网段玩家 <span style="color:var(--dim)">(${groups.length}个网段组)</span></h3>`;
    groups.forEach(g => {
        html += `<div class="card" style="margin-bottom:12px">`;
        html += `<h4 style="color:var(--yellow);margin-bottom:8px">🌐 ${g.subnet || g.ip} <span style="font-size:12px;color:var(--dim)">(${g.player_count}人 | ${g.ip_location || '-'})</span></h4>`;
        html += `<table class="table">`;
        html += `<tr><th>玩家名</th><th>IP地址</th><th>IP属地</th><th>最后登录时间</th></tr>`;
        g.players.forEach(p => {
            const loginTime = p.login_time ? new Date(p.login_time*1000).toLocaleString() : '-';
            html += `<tr><td>${p.player_name}</td><td style="font-size:12px;font-family:monospace">${p.ip_address || '-'}</td><td style="font-size:12px">${p.ip_location || '-'}</td><td>${loginTime}</td></tr>`;
        });
        html += `</table></div>`;
    });
    div.innerHTML = html;
}

// ===== 在线玩家（独立标签页）=====
async function loadOnlinePlayers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    loadOnlinePlayersData(el);
    // 每10秒刷新一次
    onlineInterval = setInterval(() => loadOnlinePlayersData(el), 10000);
}

async function loadOnlinePlayersData(el) {
    const r = await jsonApi('admin.php?action=list_online_players');
    if (!r.success) { el.innerHTML = '<div class="card">加载失败: ' + r.message + '</div>'; return; }
    const players = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>在线玩家 <span style="color:var(--dim);font-size:12px">(${players.length}人在线)</span></h2>
            <table class="table">
                <tr><th>玩家名</th><th>登录时间</th><th>在线时长</th><th>IP地址</th><th>IP属地</th></tr>
                ${players.map(p => {
                    const loginTime = p.login_time ? new Date(p.login_time*1000).toLocaleString() : '-';
                    const minsOnline = Math.floor((Date.now()/1000 - p.login_time)/60);
                    const ip = p.ip_address || '-';
                    const ipLoc = p.ip_location || '-';
                    const isShared = p.ip_is_shared;
                    const ipStyle = isShared ? 'color:var(--yellow);font-weight:700' : '';
                    const ipBadge = isShared ? ` <span style="font-size:10px;background:var(--yellow);color:#000;padding:1px 4px;border-radius:3px">同IP多人</span>` : '';
                    return `<tr>
                        <td class="player-online">🟢 ${p.player_name}</td>
                        <td>${loginTime}</td>
                        <td>${minsOnline}分钟</td>
                        <td style="font-size:12px;font-family:monospace;${ipStyle}">${ip}${ipBadge}</td>
                        <td style="font-size:12px">${ipLoc}</td>
                    </tr>`;
                }).join('')}
            </table>
            ${players.length === 0 ? '<div class="empty">暂无在线玩家</div>' : ''}
        </div>`;
}

// ===== 活跃用户（24小时） =====
async function loadActivePlayers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await jsonApi('admin.php?action=list_active_players');
    if (!r.success) { el.innerHTML='<div class="card">'+r.message+'</div>'; return; }
    const players = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>24小时活跃用户 <span style="color:var(--dim);font-size:12px">(${players.length}人)</span></h2>
            <table class="table">
                <tr><th>玩家名</th><th>最后活跃</th><th>总在线时长</th></tr>
                ${players.map(p => {
                    const loginTime = p.last_login_time ? new Date(p.last_login_time*1000).toLocaleString() : '-';
                    return `<tr>
                        <td class="player-online">${p.player_name}</td>
                        <td>${loginTime}</td>
                        <td>${p.hours_online}小时</td>
                    </tr>`;
                }).join('')}
            </table>
            ${players.length === 0 ? '<div class="empty">暂无活跃用户</div>' : ''}
        </div>`;
}

// ===== 发送重置密码链接（管理员后台） =====
async function showUserInfoAndReset(player, email) {
    const res = await jsonApi('register.php?action=query&player='+encodeURIComponent(player));
    let infoHtml = '';
    if (res.success) {
        const d = res.data;
        const regTime = d.register_time ? new Date(d.register_time*1000).toLocaleString() : '-';
        const loginTime = d.last_login_time ? new Date(d.last_login_time*1000).toLocaleString() : '-';
        const hours = Math.floor((d.total_online_time||0)/3600);
        infoHtml = `<div style="background:rgba(88,166,255,0.1);border:1px solid var(--accent);border-radius:8px;padding:12px;margin-bottom:12px;font-size:13px">
            <p><b>玩家:</b> ${d.player_name}</p>
            <p><b>注册:</b> ${regTime} | <b>最后登录:</b> ${loginTime}</p>
            <p><b>积分:</b> ${d.points||0} | <b>礼包阶段:</b> ${d.gift_stage||0} | <b>在线时长:</b> ${hours}h</p>
            <p><b>邮箱:</b> ${d.email||'-'}</p>
        </div>`;
    }
    
    const overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:2000';
    overlay.innerHTML = `
        <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:500px;max-width:90%">
            <h3 style="margin-bottom:12px">查看 & 重置密码 - ${player}</h3>
            <div id="userInfoPanel">${infoHtml}</div>
            <div style="margin-top:12px">
                <label style="color:var(--dim);font-size:12px">绑定邮箱</label>
                <input type="text" id="resetPlayerEmail" value="${email}" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;box-sizing:border-box">
            </div>
            <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                <button class="btn" id="userInfoCancel">关闭</button>
                <button class="btn btn-blue" id="userInfoReset">发送重置链接</button>
            </div>
        </div>`;
    document.body.appendChild(overlay);
    document.getElementById('userInfoCancel').onclick = () => overlay.remove();
    document.getElementById('userInfoReset').onclick = async () => {
        const emailInput = document.getElementById('resetPlayerEmail').value.trim();
        overlay.remove();
        const r = await fetch('api/sync.php?action=send_reset_password_link', {
            method: 'POST', headers: {'Content-Type':'application/json'},
            body: JSON.stringify({player})
        });
        const data = await r.json();
        if (data.success) {
            toast('重置链接已发送到邮箱: ' + data.data.email, 'ok');
        } else {
            toast(data.message, 'err');
        }
        loadUsers(document.getElementById('C'));
    };
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
}

async function adminSendReset(player, email) {
    const emailInput = await showModal('发送重置密码链接', '为玩家 ' + player + ' 发送重置密码邮件', email);
    if (!emailInput) return;
    
    const r = await fetch('api/sync.php?action=send_reset_password_link', {
        method: 'POST', headers: {'Content-Type':'application/json'},
        body: JSON.stringify({player})
    });
    const data = await r.json();
    if (data.success) {
        toast('重置链接已发送到邮箱: ' + data.data.email, 'ok');
    } else {
        toast(data.message, 'err');
    }
}

// ===== 密码重置审核 =====
async function loadResetRequests(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await jsonApi('admin.php?action=list_reset_requests');
    const requests = r.data || [];
    
    if (requests.length === 0) {
        el.innerHTML = '<div class="card"><h2>密码重置审核 <span style="color:var(--dim);font-size:12px">(无待审核请求)</span></h2></div>';
        return;
    }
    
    el.innerHTML = `
        <div class="card">
            <h2>密码重置审核 <span style="color:var(--dim);font-size:12px">(${requests.length}个待审核)</span></h2>
            <table class="table" id="resetTable">
                <tr><th>ID</th><th>玩家名</th><th>请求邮箱</th><th>请求时间</th><th>操作</th></tr>
                ${requests.map(req => {
                    const reqTime = new Date(req.created_at * 1000).toLocaleString();
                    return `<tr data-id="${req.id}">
                        <td>#${req.id}</td>
                        <td><b style="color:var(--yellow)">${req.player_name}</b></td>
                        <td>${req.requested_email || '-'}</td>
                        <td>${reqTime}</td>
                        <td>
                            <button class="btn btn-green" onclick="adminApproveReset(${req.id})">批准</button>
                            <button class="btn btn-red" onclick="adminRejectReset(${req.id})">驳回</button>
                        </td>
                    </tr>`;
                }).join('')}
            </table>
        </div>`;
}

async function adminApproveReset(reqId) {
    const emailInput = await showModal('批准密码重置', '请输入要绑定的邮箱地址（将用于玩家后续验证）', '');
    if (!emailInput) return;
    if (!emailInput.includes('@')) { toast('请输入有效邮箱', 'err'); return; }
    
    const r = await fetch('api/admin.php?action=admin_approve_reset', {
        method: 'POST', headers: {'Content-Type':'application/json'},
        body: JSON.stringify({id: reqId, admin_email: emailInput})
    });
    const data = await r.json();
    if (data.success) {
        toast('已批准！临时密码: ' + data.data.temp_password, 'ok');
        loadResetRequests(document.getElementById('C'));
    } else {
        toast(data.message, 'err');
    }
}

async function adminRejectReset(reqId) {
    if (!await confirmAction('确定驳回该密码重置请求？')) return;
    
    const r = await fetch('api/admin.php?action=admin_reject_reset', {
        method: 'POST', headers: {'Content-Type':'application/json'},
        body: JSON.stringify({id: reqId})
    });
    const data = await r.json();
    if (data.success) {
        toast('已驳回', 'ok');
        loadResetRequests(document.getElementById('C'));
    } else {
        toast(data.message, 'err');
    }
}

function confirmAction(msg) {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:1000';
        overlay.innerHTML = `
            <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:400px;text-align:center">
                <p style="color:var(--text);font-size:14px;margin-bottom:20px">${msg}</p>
                <div style="display:flex;gap:8px;justify-content:center">
                    <button class="btn" id="confirmCancel">取消</button>
                    <button class="btn btn-red" id="confirmOk">确定</button>
                </div>
            </div>`;
        document.body.appendChild(overlay);
        document.getElementById('confirmCancel').onclick = () => { overlay.remove(); resolve(false); };
        document.getElementById('confirmOk').onclick = () => { overlay.remove(); resolve(true); };
        overlay.onclick = (e) => { if (e.target === overlay) { overlay.remove(); resolve(false); } };
    });
}

// ===== 通用 API 调用（直接返回 JSON）=====
function jsonApi(path) {
    console.log('[API] Fetching: ' + path);
    return fetch(path.startsWith('api/') ? path : ('api/' + path), {
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'XMLHttpRequest' }
    })
    .then(r => {
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return r.text().then(t => {
            try { return JSON.parse(t.replace(/^\uFEFF/,'').trim()); } catch(e) {
                console.error('Invalid JSON from ' + path + ':', t.substring(0, 200));
                return { success: false, message: '服务器返回无效JSON: ' + t.substring(0, 50) };
            }
        });
    })
    .catch(e => {
        console.error('Fetch error:', e);
        return { success: false, message: '网络错误: ' + e.message };
    });
}
async function postApi(action, data) {
    const r = await fetch(A, {
        method:'POST',
        credentials: 'same-origin',
        headers:{'Content-Type':'application/json'},
        body:JSON.stringify({action,...data})
    });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const text = await r.text();
    try {
        return JSON.parse(text);
    } catch(e) {
        console.error('Invalid JSON from POST api:', text.substring(0, 200));
        return { success: false, message: '服务器返回无效JSON: ' + text.substring(0, 50) };
    }
}
function toast(msg,type='ok') {
    const t=document.createElement('div'); t.className='toast '+type; t.textContent=msg;
    document.body.appendChild(t); setTimeout(()=>t.remove(),3000);
}
async function doLogout() {
    await postApi('logout',{});
    location.href='admin_login.php';
}

// ===== 模态框输入（替代prompt）=====
function showModal(title, message, defaultValue, customHtml) {
    return new Promise((resolve) => {
        // ★ 移除所有旧的modal overlay，防止重复id导致输入框读取失败
        document.querySelectorAll('[data-modal-overlay]').forEach(el => el.remove());
        const overlay = document.createElement('div');
        overlay.dataset.modalOverlay = '1';
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:1000';
        if (customHtml) {
            overlay.innerHTML = `
                <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:400px;max-width:90%">
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                        <h3 style="margin:0">${title}</h3>
                        <span class="modal-close" style="cursor:pointer;font-size:18px;color:var(--dim)" onclick="this.closest('div[style*=fixed]').remove()">✕</span>
                    </div>
                    ${customHtml}
                </div>`;
        } else {
            overlay.innerHTML = `
                <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:400px;max-width:90%">
                    <h3 style="margin-bottom:12px">${title}</h3>
                    <p style="color:var(--dim);font-size:13px;margin-bottom:12px">${message}</p>
                    <input type="text" id="modalInput" value="${defaultValue}" style="width:100%;padding:8px 12px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:14px;box-sizing:border-box">
                    <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                        <button class="btn" id="modalCancel">取消</button>
                        <button class="btn btn-blue" id="modalConfirm">确认</button>
                    </div>
                </div>`;
        }
        document.body.appendChild(overlay);
        if (!customHtml) {
            const input = document.getElementById('modalInput');
            input.focus();
            input.select();
            input.addEventListener('keydown', e => { if (e.key === 'Enter') { resolve(input.value); overlay.remove(); } });
            document.getElementById('modalCancel').onclick = () => { overlay.remove(); resolve(null); };
            document.getElementById('modalConfirm').onclick = () => { resolve(input.value); overlay.remove(); };
        }
        overlay.onclick = (e) => { if (e.target === overlay) { overlay.remove(); resolve(null); } };
    });
}

// ===== 主题选择器 =====
function showThemePicker() {
    const overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:2000';
    overlay.innerHTML = `
        <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:500px;max-width:90%">
            <h3 style="margin-bottom:12px">🎨 选择背景颜色</h3>
            <div class="theme-picker">
                <div class="color-btn" style="background:#0d1117" onclick="setTheme('#0d1117')"></div>
                <div class="color-btn" style="background:#1a1e2e" onclick="setTheme('#1a1e2e')"></div>
                <div class="color-btn" style="background:#0f4c75" onclick="setTheme('#0f4c75')"></div>
                <div class="color-btn" style="background:#1b2631" onclick="setTheme('#1b2631')"></div>
                <div class="color-btn" style="background:#2c3e50" onclick="setTheme('#2c3e50')"></div>
                <div class="color-btn" style="background:#23272a" onclick="setTheme('#23272a')"></div>
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
                <button class="btn" id="themeCancel">取消</button>
                <button class="btn btn-blue" onclick="applyCustomColor()">应用</button>
            </div>
        </div>`;
    document.body.appendChild(overlay);
    overlay.querySelector('#themeCancel').onclick = () => overlay.remove();
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
}

function setTheme(color) {
    document.documentElement.style.setProperty('--bg', color);
    document.body.style.background = color;
    localStorage.setItem('sdf1_theme', color);
    toast('主题已应用', 'ok');
}

function applyCustomColor() {
    let rVal = document.getElementById('rgbR').value.trim();
    let gVal = document.getElementById('rgbG').value.trim();
    let bVal = document.getElementById('rgbB').value.trim();
    const hexVal = document.getElementById('customColor').value.trim();
    
    let color = null;
    
    // 优先检查十六进制
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
    // 否则检查 RGB 三个输入框 - 如果某个为空则自动补 0
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
    } else {
        toast('请输入十六进制颜色（如 1a237e）或填写任意一个 RGB 值', 'err');
        return;
    }
    
    if (color) {
        setTheme(color);
        document.querySelector('[id="themeCancel"]').click();
    }
}

// 加载保存的主题
(function() {
    const savedTheme = localStorage.getItem('sdf1_theme');
    if (savedTheme) setTheme(savedTheme);
})();

// ===== 工单管理 =====
let ticketAdminState = { filter: 'all', view: 'list' };

async function loadTickets(el) {
    ticketAdminState.view = 'list';
    await loadTicketList(el);
}

async function loadTicketList(el) {
    el.innerHTML = '<div class="card"><p style="color:var(--dim)">加载中...</p></div>';
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'list_all');
        if (ticketAdminState.filter !== 'all') url.searchParams.set('status', ticketAdminState.filter);
        const res = await fetch(url, {credentials: 'same-origin'});
        const data = await res.json();
        if (!data.success) { el.innerHTML = `<div class="card"><p style="color:var(--red)">${data.message}</p></div>`; return; }

        const list = data.data.list;
        const stats = data.data.stats;
        const typeMap = {bug:'🐛 Bug',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
        const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},completed:{text:'✅ 已完结',color:'var(--green)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
        const f = ticketAdminState.filter;

        let html = `<div class="card"><h2>📋 工单管理</h2>
            <div style="margin-bottom:10px"><button class="btn btn-primary" style="padding:5px 14px;font-size:12px" onclick="adminCreateTicketUI()">➕ 新建工单</button></div>
            <div class="tabs">
                <div class="tab ${f==='all'?'active':''}" onclick="ticketAdminState.filter='all';loadTicketList(document.getElementById('C'))">全部 (${stats.all})</div>
                <div class="tab ${f==='submitted'?'active':''}" onclick="ticketAdminState.filter='submitted';loadTicketList(document.getElementById('C'))">⏳ 已提交 (${stats.submitted||0})</div>
                <div class="tab ${f==='replied'?'active':''}" onclick="ticketAdminState.filter='replied';loadTicketList(document.getElementById('C'))">💬 已回复 (${stats.replied||0})</div>
                <div class="tab ${f==='completed'?'active':''}" onclick="ticketAdminState.filter='completed';loadTicketList(document.getElementById('C'))">✅ 已完结 (${stats.completed||0})</div>
                <div class="tab ${f==='withdrawn'?'active':''}" onclick="ticketAdminState.filter='withdrawn';loadTicketList(document.getElementById('C'))">🔙 已撤销 (${stats.withdrawn||0})</div>
                <div class="tab ${f==='rejected'?'active':''}" onclick="ticketAdminState.filter='rejected';loadTicketList(document.getElementById('C'))">🚫 已驳回 (${stats.rejected||0})</div>
            </div>`;

        if (list.length === 0) {
            html += `<p style="color:var(--dim);text-align:center;padding:30px">暂无工单</p>`;
        } else {
            html += `<table class="table"><thead><tr><th>ID</th><th>类型</th><th>标题</th><th>提交者</th><th>处理人</th><th>状态</th><th>时间</th><th>操作</th></tr></thead><tbody>`;
            for (const t of list) {
                const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
                const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
                html += `<tr>
                    <td>#${t.id}</td>
                    <td>${typeMap[t.type]||t.type}</td>
                    <td>${escAdmHtml(t.title)}</td>
                    <td>${escAdmHtml(t.requester)}</td>
                    <td>${t.assigned_to ? escAdmHtml(t.assigned_to) : '<span style="color:var(--dim)">-</span>'}</td>
                    <td><span style="color:${s.color}">${s.text}</span></td>
                    <td style="font-size:11px">${date}</td>
                    <td>
                        <button class="btn" style="padding:3px 8px;font-size:11px" onclick="viewAdminTicket(${t.id})">查看</button>
                        ${t.status !== 'withdrawn' && t.status !== 'rejected' ? `<button class="btn" style="padding:3px 8px;font-size:11px;color:var(--yellow)" onclick="viewAdminTicket(${t.id})">回复</button>` : ''}
                    </td>
                </tr>`;
            }
            html += `</tbody></table>`;
        }
        html += `</div>`;
        el.innerHTML = html;
    } catch (e) {
        el.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function viewAdminTicket(id) {
    const c = document.getElementById('C');
    c.innerHTML = '<div class="card"><p style="color:var(--dim)">加载中...</p></div>';
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'detail');
        url.searchParams.set('id', id);
        url.searchParams.set('token', 'admin');
        const res = await fetch(url, {credentials: 'same-origin'});
        const data = await res.json();
        if (!data.success) { c.innerHTML = `<div class="card"><p style="color:var(--red)">${data.message}</p></div>`; return; }

        const t = data.data;
        const typeMap = {bug:'🐛 Bug反馈',help:'❓ 求助',report:'📢 举报',apply:'📝 申请',other:'📎 其他'};
        const statusMap = {submitted:{text:'⏳ 已提交',color:'var(--yellow)'},replied:{text:'💬 已回复',color:'var(--accent)'},withdrawn:{text:'🔙 已撤销',color:'var(--dim)'},rejected:{text:'🚫 已驳回',color:'var(--red)'}};
        const s = statusMap[t.status] || {text: t.status, color: 'var(--dim)'};
        const date = new Date(t.created_at * 1000).toLocaleString('zh-CN');
        const canOperate = !['withdrawn','rejected','completed'].includes(t.status);

        let html = `<div class="card">
            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
                <h2 style="margin:0">#${t.id} ${escAdmHtml(t.title)}</h2>
                <span style="color:${s.color};font-size:13px">${s.text}</span>
            </div>
            <div style="font-size:13px;color:var(--dim);margin-bottom:12px">
                类型: ${typeMap[t.type]||t.type} | 提交者: ${escAdmHtml(t.requester)} | 处理人: ${t.assigned_to ? escAdmHtml(t.assigned_to) : '未分配'} | 时间: ${date}
            </div>
            <div style="background:rgba(88,166,255,0.05);border:1px solid var(--border);border-radius:6px;padding:12px;margin-bottom:12px;font-size:13px;line-height:1.6">${adminRenderMd(t.description || '无描述')}</div>`;

        // 驳回原因
        if (t.reject_reason) {
            html += `<div style="background:rgba(248,81,73,0.1);border:1px solid var(--red);border-radius:6px;padding:12px;margin-bottom:12px">
                <div style="color:var(--red);font-size:12px;margin-bottom:4px">🚫 驳回原因</div>
                <div style="font-size:13px">${adminRenderMd(t.reject_reason)}</div></div>`;
        }

        // 回复列表
        html += `<div style="margin-bottom:12px"><div style="font-size:12px;color:var(--dim);margin-bottom:6px">💬 回复记录 (${t.replies ? t.replies.length : 0})</div>`;
        if (t.replies && t.replies.length > 0) {
            for (const r of t.replies) {
                const rDate = new Date(r.created_at * 1000).toLocaleString('zh-CN');
                const roleColors = {user:'var(--accent)',admin:'var(--red)',provider:'var(--green)'};
                const roleNames = {user:'玩家',admin:'管理员',provider:'服务商'};
                const bgColor = r.role === 'admin' ? 'rgba(248,81,73,0.08)' : r.role === 'provider' ? 'rgba(63,185,80,0.08)' : 'rgba(88,166,255,0.08)';
                html += `<div style="background:${bgColor};border-radius:6px;padding:10px 14px;margin-bottom:6px">
                    <div style="display:flex;justify-content:space-between;font-size:12px;margin-bottom:4px">
                        <span><b style="color:${roleColors[r.role]||'var(--text)'}">${escAdmHtml(r.sender)}</b> <span style="color:var(--dim)">(${roleNames[r.role]||r.role})</span></span>
                        <span style="color:var(--dim)">${rDate}</span>
                    </div>
                    <div style="font-size:13px;line-height:1.6">${adminRenderMd(r.message)}</div>
                </div>`;
            }
        } else {
            html += `<p style="color:var(--dim);font-size:12px">暂无回复</p>`;
        }
        html += `</div>`;

        // 操作区
        if (canOperate) {
            html += `<div style="border-top:1px solid var(--border);padding-top:12px">
                <div style="margin-bottom:8px"><textarea id="admTicketReply" rows="2" placeholder="输入回复内容（支持Markdown）" style="width:100%;padding:8px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;resize:vertical;font-family:monospace"></textarea></div>
                <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
                    <button class="btn btn-primary" style="padding:5px 14px;font-size:12px" onclick="adminReplyTicket(${t.id})">💬 回复</button>
                    <button class="btn" style="padding:5px 14px;font-size:12px;color:var(--red)" onclick="adminRejectTicket(${t.id})">🚫 驳回</button>
                    <button class="btn" style="padding:5px 14px;font-size:12px;color:var(--green)" onclick="adminCompleteTicket(${t.id})">✅ 完结</button>
                    <input id="admAssignProvider" placeholder="服务商名" style="padding:5px 8px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:12px;width:120px">
                    <button class="btn" style="padding:5px 14px;font-size:12px;color:var(--green)" onclick="adminAssignTicket(${t.id})">📤 分配</button>
                </div>
                <p id="admTicketErr" style="color:var(--red);font-size:12px;margin-top:6px"></p>
            </div>`;
        }

        html += `<div style="margin-top:12px"><button class="btn" style="padding:5px 14px;font-size:12px" onclick="loadTicketList(document.getElementById('C'))">← 返回列表</button></div></div>`;
        c.innerHTML = html;
    } catch (e) {
        c.innerHTML = `<div class="card"><p style="color:var(--red)">加载失败: ${e.message}</p></div>`;
    }
}

async function adminReplyTicket(id) {
    const msg = document.getElementById('admTicketReply').value.trim();
    const errEl = document.getElementById('admTicketErr');
    if (!msg) { errEl.textContent = '请输入回复内容'; return; }
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'admin_reply');
        url.searchParams.set('id', id);
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body: JSON.stringify({message: msg})});
        const data = await res.json();
        if (data.success) { viewAdminTicket(id); } else { errEl.textContent = data.message; }
    } catch (e) { errEl.textContent = '发送失败: ' + e.message; }
}

async function adminRejectTicket(id) {
    const reason = prompt('请输入驳回原因:');
    if (!reason) return;
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'reject');
        url.searchParams.set('id', id);
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body: JSON.stringify({reason: reason})});
        const data = await res.json();
        if (data.success) { loadTicketList(document.getElementById('C')); } else { glassAlert(data.message); }
    } catch (e) { glassAlert('操作失败: ' + e.message); }
}

async function adminCompleteTicket(id) {
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'admin_complete');
        url.searchParams.set('id', id);
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin'});
        const data = await res.json();
        if (data.success) { viewAdminTicket(id); } else { glassAlert(data.message); }
    } catch (e) { glassAlert('操作失败: ' + e.message); }
}

async function adminAssignTicket(id) {
    const provider = document.getElementById('admAssignProvider').value.trim();
    if (!provider) { document.getElementById('admTicketErr').textContent = '请输入服务商名称'; return; }
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'assign');
        url.searchParams.set('id', id);
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body: JSON.stringify({provider: provider})});
        const data = await res.json();
        if (data.success) { viewAdminTicket(id); } else { document.getElementById('admTicketErr').textContent = data.message; }
    } catch (e) { document.getElementById('admTicketErr').textContent = '分配失败: ' + e.message; }
}

function adminCreateTicketUI() {
    const c = document.getElementById('C');
    let html = `<div class="card"><h2>➕ 新建工单</h2>
        <div style="margin-bottom:10px">
            <label style="font-size:12px;color:var(--dim)">类型</label>
            <select id="admNewType" style="width:100%;padding:6px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;margin-top:4px">
                <option value="bug">🐛 Bug</option>
                <option value="help">❓ 求助</option>
                <option value="report">📢 举报</option>
                <option value="apply">📝 申请</option>
                <option value="other">📎 其他</option>
            </select>
        </div>
        <div style="margin-bottom:10px">
            <label style="font-size:12px;color:var(--dim)">标题</label>
            <input id="admNewTitle" style="width:100%;padding:6px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;margin-top:4px" placeholder="工单标题">
        </div>
        <div style="margin-bottom:10px">
            <label style="font-size:12px;color:var(--dim)">描述（支持Markdown）</label>
            <textarea id="admNewDesc" rows="4" style="width:100%;padding:6px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;margin-top:4px;resize:vertical;font-family:monospace" placeholder="工单描述"></textarea>
        </div>
        <div style="margin-bottom:10px">
            <label style="font-size:12px;color:var(--dim)">派发服务商（可选）</label>
            <input id="admNewProvider" style="width:100%;padding:6px;border-radius:4px;border:1px solid var(--border);background:var(--bg);color:var(--text);font-size:13px;margin-top:4px" placeholder="服务商玩家名（留空则进入抢单大厅）">
        </div>
        <div style="display:flex;gap:8px">
            <button class="btn btn-primary" style="padding:5px 14px;font-size:12px" onclick="adminSubmitCreateTicket()">创建</button>
            <button class="btn" style="padding:5px 14px;font-size:12px" onclick="loadTicketList(document.getElementById('C'))">取消</button>
        </div>
        <p id="admCreateErr" style="color:var(--red);font-size:12px;margin-top:6px"></p>
    </div>`;
    c.innerHTML = html;
}

async function adminSubmitCreateTicket() {
    const type = document.getElementById('admNewType').value;
    const title = document.getElementById('admNewTitle').value.trim();
    const desc = document.getElementById('admNewDesc').value.trim();
    const provider = document.getElementById('admNewProvider').value.trim();
    const errEl = document.getElementById('admCreateErr');
    if (!title) { errEl.textContent = '请输入标题'; return; }
    try {
        const url = new URL('api/ticket.php', location.href);
        url.searchParams.set('action', 'admin_create');
        const res = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'}, credentials:'same-origin', body: JSON.stringify({type, title, description: desc, provider})});
        const data = await res.json();
        if (data.success) { loadTicketList(document.getElementById('C')); } else { errEl.textContent = data.message; }
    } catch (e) { errEl.textContent = '创建失败: ' + e.message; }
}

function escAdmHtml(s) { return (s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;'); }

function adminProcessInlineMd(text) {
    let s = text;
    s = s.replace(/^######\s+(.+)$/, '<h6 style="margin:2px 0;font-size:11px">$1</h6>');
    s = s.replace(/^#####\s+(.+)$/, '<h5 style="margin:3px 0;font-size:12px">$1</h5>');
    s = s.replace(/^####\s+(.+)$/, '<h4 style="margin:3px 0 2px;font-size:12px">$1</h4>');
    s = s.replace(/^###\s+(.+)$/, '<h4 style="margin:4px 0 3px;font-size:13px">$1</h4>');
    s = s.replace(/^##\s+(.+)$/, '<h3 style="margin:5px 0 3px;font-size:14px">$1</h3>');
    s = s.replace(/^#\s+(.+)$/, '<h2 style="margin:6px 0 4px;font-size:15px">$1</h2>');
    s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
    s = s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    s = s.replace(/\*(.+?)\*/g, '<i>$1</i>');
    s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;border-radius:6px;margin:4px 0">');
    s = s.replace(/(?<!!)\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" style="color:var(--accent)">$1</a>');
    return s;
}

function adminRenderMd(text) {
    if (!text) return '';
    text = text.replace(/\r/g, '');
    try {
    let s = escAdmHtml(text);
    // 1. 代码块提取保护
    let cbs = [];
    s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, function(m, lang, code) {
        let idx = cbs.length;
        cbs.push('<pre style="background:rgba(88,166,255,0.1);padding:8px;border-radius:4px;overflow-x:auto;font-size:12px;white-space:pre-wrap">' + code.replace(/\n$/, '') + '</pre>');
        return '\x00CB' + idx + '\x00';
    });
    // 2. 行内代码保护
    s = s.replace(/`([^`]+)`/g, '<code style="background:rgba(88,166,255,0.15);padding:1px 4px;border-radius:2px;font-size:12px">$1</code>');
    // 3. 引用块处理：先提取连续引用行，构建嵌套结构
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
            let text = adminProcessInlineMd(content);
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
    // 4. 非引用行的MD处理（标题、粗体、斜体、图片、链接等）
    s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
    s = s.replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    s = s.replace(/\*(.+?)\*/g, '<i>$1</i>');
    s = s.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '<img src="$2" alt="$1" style="max-width:100%;border-radius:6px;margin:6px 0">');
    s = s.replace(/(?<!!)\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" style="color:var(--accent)">$1</a>');
    s = s.replace(/^######\s+(.+)$/gm, '<h6 style="margin:3px 0;font-size:11px">$1</h6>');
    s = s.replace(/^#####\s+(.+)$/gm, '<h5 style="margin:4px 0;font-size:12px">$1</h5>');
    s = s.replace(/^####\s+(.+)$/gm, '<h4 style="margin:4px 0 2px;font-size:12px">$1</h4>');
    s = s.replace(/^###\s+(.+)$/gm, '<h4 style="margin:6px 0 4px;font-size:13px">$1</h4>');
    s = s.replace(/^##\s+(.+)$/gm, '<h3 style="margin:8px 0 4px;font-size:14px">$1</h3>');
    s = s.replace(/^#\s+(.+)$/gm, '<h2 style="margin:10px 0 6px;font-size:15px">$1</h2>');
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
                tableHtml = '<table style="border-collapse:collapse;width:100%;margin:6px 0;font-size:12px"><tr style="background:rgba(88,166,255,0.15)">';
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
            tResult.push(tableHtml);
            tableHtml = '';
            inTable = false;
        }
        tResult.push(tLines[i]);
    }
    if (tableHtml) { tableHtml += '</table>'; tResult.push(tableHtml); }
    s = tResult.join('\n');
    // 6. 列表、分割线、换行
    s = s.replace(/^- (.+)$/gm, '<li style="margin-left:16px;font-size:12px">$1</li>');
    s = s.replace(/^---$/gm, '<hr style="border:none;border-top:1px solid var(--border);margin:10px 0">');
    s = s.replace(/\n/g, '<br>');
    // 7. 还原代码块
    for (let i = 0; i < cbs.length; i++) {
        s = s.replace('\x00CB' + i + '\x00', cbs[i]);
    }
    return s;
    } catch(e) { console.error('[MD_RENDER] Error:', e); return escAdmHtml(text).replace(/\n/g, '<br>'); }
}

// ==================== 领地管理 ====================
async function loadLands(el) {
    el.innerHTML = '<div style="text-align:center;padding:40px;color:var(--dim)">加载中...</div>';
    try {
        const [landsRes, shopRes, configRes] = await Promise.all([
            fetch('api/land_api.php?action=list_lands&secret=sdf1_web_comm_2026_ypshidifu').then(r => r.json()),
            fetch('api/land_api.php?action=list_shop&secret=sdf1_web_comm_2026_ypshidifu').then(r => r.json()),
            fetch('api/land_api.php?action=get_config&secret=sdf1_web_comm_2026_ypshidifu').then(r => r.json())
        ]);

        let html = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:8px">';
        html += '<h2 style="margin:0;color:var(--fg)">🏡 领地管理</h2>';
        html += '<div style="display:flex;gap:8px">';
        html += `<button onclick="showLandConfig()" style="padding:6px 12px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:12px">⚙️ 配置</button>`;
        html += `<button onclick="loadLands(document.getElementById('C'))" style="padding:6px 12px;background:var(--card);color:var(--dim);border:1px solid var(--border);border-radius:4px;cursor:pointer;font-size:12px">🔄 刷新</button>`;
        html += '</div></div>';

        // 配置信息
        const cfg = configRes.config || {};
        window._landCfgData = cfg; // 缓存供showLandConfig使用
        html += `<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(180px,1fr));gap:12px;margin-bottom:20px">`;
        const lands = landsRes.lands || [];
        html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;text-align:center">
            <div style="font-size:24px;font-weight:bold;color:var(--accent)">${lands.length}</div>
            <div style="font-size:12px;color:var(--dim);margin-top:4px">总领地数</div>
        </div>`;
        const shopItems = (shopRes.items || []).filter(i => i.status === 'active' && !i.buyer);
        html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;text-align:center">
            <div style="font-size:24px;font-weight:bold;color:#4caf50">${shopItems.length}</div>
            <div style="font-size:12px;color:var(--dim);margin-top:4px">在售权限</div>
        </div>`;
        const pricePerSqM = cfg.create_price_per_sqm || '10';
        const maxLands = cfg.max_lands_per_player || '5';
        html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;text-align:center">
            <div style="font-size:24px;font-weight:bold;color:#ff9800">${pricePerSqM}</div>
            <div style="font-size:12px;color:var(--dim);margin-top:4px">每平米价格(债券)</div>
        </div>`;
        html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;text-align:center">
            <div style="font-size:24px;font-weight:bold;color:#e91e63">${maxLands}</div>
            <div style="font-size:12px;color:var(--dim);margin-top:4px">最大领地数/人</div>
        </div>`;
        html += '</div>';

        // 领地列表
        html += '<h3 style="color:var(--fg);margin-bottom:8px">📍 领地列表</h3>';
        if (lands.length === 0) {
            html += '<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:24px;text-align:center;color:var(--dim)">暂无领地数据<br><span style="font-size:11px">Java端会自动同步领地数据到此</span></div>';
        } else {
            html += '<div style="overflow-x:auto"><table class="table"><tr>';
            html += '<th>ID</th><th>名称</th><th>所有者</th><th>世界</th><th>坐标</th><th>面积</th><th>操作</th>';
            html += '</tr>';
            for (const l of lands) {
                const size = l.area_size || Math.abs((l.x2-l.x1+1)*(l.z2-l.z1+1));
                html += `<tr>
                    <td>${l.id}</td>
                    <td><strong>${escAdmHtml(l.name)}</strong></td>
                    <td>${escAdmHtml(l.owner || '无')}</td>
                    <td>${escAdmHtml(l.world)}</td>
                    <td style="font-size:11px">${l.x1},${l.z1} → ${l.x2},${l.z2}</td>
                    <td>${size} 格²</td>
                    <td>
                        <button onclick="adminTransferLand('${escAdmHtml(l.name)}','${escAdmHtml(l.owner)}')" style="padding:2px 8px;background:#ff9800;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">改主</button>
                        <button onclick="deleteLand('${escAdmHtml(l.name)}')" style="padding:2px 8px;background:#f44336;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">删除</button>
                    </td>
                </tr>`;
            }
            html += '</table></div>';
        }

        // 权限商店
        html += '<h3 style="color:var(--fg);margin:20px 0 8px">🛒 权限商店</h3>';
        const allShop = shopRes.items || [];
        if (allShop.length === 0) {
            html += '<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:24px;text-align:center;color:var(--dim)">暂无权限商品</div>';
        } else {
            html += '<div style="overflow-x:auto"><table class="table"><tr>';
            html += '<th>ID</th><th>领地</th><th>卖家</th><th>价格</th><th>时长</th><th>状态</th><th>买家</th><th>操作</th>';
            html += '</tr>';
            for (const s of allShop) {
                const dur = s.duration >= 86400 ? Math.floor(s.duration/86400)+'天' : s.duration >= 3600 ? Math.floor(s.duration/3600)+'小时' : Math.floor(s.duration/60)+'分钟';
                const statusColor = s.status === 'active' ? '#4caf50' : s.status === 'sold' ? '#ff9800' : '#f44336';
                html += `<tr>
                    <td>${s.id}</td>
                    <td>${escAdmHtml(s.land_name)}</td>
                    <td>${escAdmHtml(s.seller)}</td>
                    <td style="color:#ff9800">${s.price}💰</td>
                    <td>${dur}</td>
                    <td style="color:${statusColor}">${s.status === 'active' ? '在售' : s.status === 'sold' ? '已售' : '已下架'}</td>
                    <td>${s.buyer ? escAdmHtml(s.buyer) : '-'}</td>
                    <td>
                        ${s.status === 'active' ? `<button onclick="deleteShopItem(${s.id})" style="padding:2px 8px;background:#f44336;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">下架</button>` : ''}
                    </td>
                </tr>`;
            }
            html += '</table></div>';
        }

        el.innerHTML = html;
    } catch(e) {
        console.error('[LANDS] Error:', e);
        el.innerHTML = `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:24px;text-align:center;color:var(--dim)">加载失败: ${escAdmHtml(e.message)}</div>`;
    }
}

function showLandConfig() {
    const cfg = window._landCfgData || {};
    const price = cfg.create_price_per_sqm || '10';
    const maxLands = cfg.max_lands_per_player || '5';
    const html = `<div style="padding:16px">
        <h3 style="margin:0 0 12px;color:var(--fg)">⚙️ 领地配置</h3>
        <div style="margin-bottom:12px">
            <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">每平米价格(债券)</label>
            <input id="cfgPrice" type="number" value="${price}" min="1" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
        </div>
        <div style="margin-bottom:12px">
            <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">每人最大领地数</label>
            <input id="cfgMaxLands" type="number" value="${maxLands}" min="1" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
        </div>
        <button onclick="saveLandConfig()" style="width:100%;padding:8px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer">保存</button>
    </div>`;
    showModal('领地配置', '', null, html);
}

async function saveLandConfig() {
    const price = document.getElementById('cfgPrice')?.value || '10';
    const maxLands = document.getElementById('cfgMaxLands')?.value || '5';
    try {
        await fetch('api/land_api.php?action=update_config', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'key=create_price_per_sqm&value=' + encodeURIComponent(price) + '&secret=sdf1_web_comm_2026_ypshidifu'
        });
        await fetch('api/land_api.php?action=update_config', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'key=max_lands_per_player&value=' + encodeURIComponent(maxLands) + '&secret=sdf1_web_comm_2026_ypshidifu'
        });
        document.querySelector('.modal-close')?.click();
        loadLands(document.getElementById('C'));
    } catch(e) {
        glassAlert('保存失败: ' + e.message);
    }
}

async function deleteLand(name) {
    if (!await glassConfirm('确定删除领地 [' + name + '] ?')) return;
    fetch('api/land_api.php?action=delete_land', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'name=' + encodeURIComponent(name) + '&secret=sdf1_web_comm_2026_ypshidifu'
    }).then(r => r.json()).then(d => {
        if (d.success) loadLands(document.getElementById('C'));
        else glassAlert('失败: ' + (d.error||''));
    });
}

async function adminTransferLand(name, currentOwner) {
    const newOwner = await glassPrompt('将领地 [' + name + '] 改主给谁？', '', '当前所有者: ' + (currentOwner||'无'));
    if (!newOwner || !newOwner.trim()) return;
    const trimmed = newOwner.trim();
    if (!/^[a-zA-Z0-9_]{3,16}$/.test(trimmed)) {
        glassAlert('玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）');
        return;
    }
    if (trimmed === currentOwner) {
        glassAlert('新旧所有者相同，无需更改');
        return;
    }
    if (!await glassConfirm('确定将领地 [' + name + '] 改主为 [' + trimmed + '] ?\n（将写入待验证队列，等Java端确认后生效）')) return;
    try {
        const btn = event.target;
        btn.disabled = true;
        btn.textContent = '验证中...';
        const res = await fetch('api/land_api.php?action=update_land_owner', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'name=' + encodeURIComponent(name) + '&owner=' + encodeURIComponent(trimmed) + '&secret=sdf1_web_comm_2026_ypshidifu'
        });
        const d = await res.json();
        if (d.success && d.pending) {
            glassAlert(d.message || '已提交改主请求，等待Java端验证');
            // 启动轮询，每5秒查询状态，最多等待1.05分钟
            let elapsed = 0;
            const maxWait = 65000; // 1.05分钟 = 65秒
            const interval = 5000; // 5秒轮询一次
            const poll = async () => {
                try {
                    const pollRes = await fetch('api/land_api.php?action=get_owner_change_status&name=' + encodeURIComponent(name) + '&secret=sdf1_web_comm_2026_ypshidifu');
                    const pollData = await pollRes.json();
                    if (pollData.success) {
                        if (pollData.status === 'completed') {
                            glassAlert('改主成功！领地 [' + name + '] 所有者已变更为 [' + trimmed + ']');
                            btn.textContent = '改主';
                            btn.disabled = false;
                            loadLands(document.getElementById('C'));
                            return;
                        } else if (pollData.status === 'failed') {
                            glassAlert('改主失败: ' + (pollData.reason || 'Java端验证失败'));
                            btn.textContent = '改主';
                            btn.disabled = false;
                            loadLands(document.getElementById('C'));
                            return;
                        }
                    }
                    elapsed += interval;
                    if (elapsed < maxWait) {
                        setTimeout(poll, interval);
                    } else {
                        // 超时，检查最终状态
                        const finalRes = await fetch('api/land_api.php?action=get_owner_change_status&name=' + encodeURIComponent(name) + '&secret=sdf1_web_comm_2026_ypshidifu');
                        const finalData = await finalRes.json();
                        if (finalData.success && finalData.status === 'completed') {
                            glassAlert('改主成功！领地 [' + name + '] 所有者已变更为 [' + trimmed + ']');
                        } else if (finalData.success && finalData.status === 'failed') {
                            glassAlert('改主失败: ' + (finalData.reason || 'Java端验证失败'));
                        } else {
                            glassAlert('改主超时，可能是Java端处理延迟，请稍后刷新查看');
                        }
                        btn.textContent = '改主';
                        btn.disabled = false;
                        loadLands(document.getElementById('C'));
                    }
                } catch (pollErr) {
                    console.error('轮询失败:', pollErr);
                    elapsed += interval;
                    if (elapsed < maxWait) {
                        setTimeout(poll, interval);
                    } else {
                        glassAlert('轮询超时，请稍后刷新查看');
                        btn.textContent = '改主';
                        btn.disabled = false;
                        loadLands(document.getElementById('C'));
                    }
                }
            };
            setTimeout(poll, interval);
        } else if (d.success) {
            glassAlert(d.message || '改主成功');
            btn.textContent = '改主';
            btn.disabled = false;
            loadLands(document.getElementById('C'));
        } else {
            glassAlert('失败: ' + (d.error || ''));
            btn.textContent = '改主';
            btn.disabled = false;
        }
    } catch(e) {
        glassAlert('请求失败: ' + e.message);
        const btn = event.target;
        if (btn) { btn.textContent = '改主'; btn.disabled = false; }
    }
}

async function deleteShopItem(id) {
    if (!await glassConfirm('确定下架商品 #' + id + ' ?')) return;
    fetch('api/land_api.php?action=delete_shop_item', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: 'id=' + id + '&secret=sdf1_web_comm_2026_ypshidifu'
    }).then(r => r.json()).then(d => {
        if (d.success) loadLands(document.getElementById('C'));
        else glassAlert('失败: ' + (d.error||''));
    });
}

// ==================== 用户组管理 ====================
const SECRET = 'sdf1_web_comm_2026_ypshidifu';
const LAND_API = 'api/land_api.php';

// 权限名称映射（中文名→Java key）
const PERM_NAMES = {
    denyBlockBreak: '破坏方块', denyBlockPlace: '放置方块', denyPVP: 'PvP',
    denyFireSpread: '火势蔓延', denyExplosion: '爆炸', denyMobGrief: '怪物破坏',
    denyMobAttack: '怪物攻击', denyLeavesDecay: '树叶消退', denyWeather: '天气影响',
    denyCropTrample: '踩踏作物', denyItemDrop: '物品丢弃', denyItemPickup: '物品拾取',
    denyXpPickup: '经验拾取', denyEnderPearl: '末影珍珠', denyEnderChest: '末影箱',
    denyAnvil: '铁砧使用', denyCraftingTable: '工作台', denyFurnace: '熔炉',
    denyBrewing: '炼药锅', denyBeacon: '信标', denyJukebox: '唱片机',
    denyNoteblock: '音符盒', denyBed: '床使用', denySpawn: '怪物刷新',
    denyProjectileLaunch: '投掷物发射', denyThrownProjectiles: '投掷物',
    denyGlowing: '发光效果', denyRedstoneInteraction: '红石交互',
    denyDoorInteraction: '门交互', denyNoteblockJukebox: '音符盒/唱片机',
    denyLead: '拴绳', denyCropHarvest: '作物收获', denyWoolShear: '剪羊毛',
    denyAnimalFeeding: '动物喂养', denyContainer: '容器访问',
    denyEffects: '药水效果', denyAllEffects: '禁止所有效果',
    isPublicBuilding: '公共建筑设施'
};

async function apiCall(action, params = {}, method = 'GET') {
    const url = new URL(LAND_API, window.location.href);
    url.searchParams.set('action', action);
    url.searchParams.set('secret', SECRET);
    if (method === 'GET') {
        for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
        const r = await fetch(url);
        return r.json();
    } else {
        const body = new URLSearchParams(params);
        body.set('secret', SECRET);
        const r = await fetch(url, {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: body.toString()
        });
        return r.json();
    }
}

async function loadUserGroups(el) {
    el.innerHTML = '<div style="text-align:center;padding:40px;color:var(--dim)">加载中...</div>';
    try {
        const res = await apiCall('list_user_groups');
        const groups = res.groups || [];

        let html = '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;flex-wrap:wrap;gap:8px">';
        html += '<h2 style="margin:0;color:var(--fg)">👥 用户组管理</h2>';
        html += '<div style="display:flex;gap:8px">';
        html += `<button onclick="showAddUserGroup()" style="padding:6px 12px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:12px">+ 新建用户组</button>`;
        html += `<button onclick="loadUserGroups(document.getElementById('C'))" style="padding:6px 12px;background:var(--card);color:var(--dim);border:1px solid var(--border);border-radius:4px;cursor:pointer;font-size:12px">🔄 刷新</button>`;
        html += '</div></div>';

        // 统计卡片
        html += '<div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:20px">';
        html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:12px;text-align:center">
            <div style="font-size:24px;font-weight:bold;color:var(--accent)">${groups.length}</div>
            <div style="font-size:12px;color:var(--dim);margin-top:4px">用户组总数</div>
        </div>`;
        html += '</div>';

        // 用户组列表
        if (groups.length === 0) {
            html += '<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:24px;text-align:center;color:var(--dim)">暂无用户组<br><span style="font-size:11px">点击上方"新建用户组"来创建第一个用户组</span></div>';
        } else {
            for (const g of groups) {
                const perms = JSON.parse(g.default_perms || '{}');
                const permCount = Object.keys(perms).filter(k => perms[k] === true).length;
                const hasCustomPrice = g.land_price_per_sqm >= 0;
                const hasCustomMax = g.max_lands >= 0;

                html += `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:16px;margin-bottom:12px">`;
                html += `<div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:8px">`;
                html += `<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">`;
                html += `<span style="font-size:18px;font-weight:bold;color:${escAdmHtml(g.display_color || '#fff')}">${escAdmHtml(g.display_emoji || '👤')} ${escAdmHtml(g.display_name || g.group_name)}</span>`;
                html += `<span style="font-size:11px;color:var(--dim);background:var(--bg);padding:2px 8px;border-radius:10px">ID: ${escAdmHtml(g.group_name)}</span>`;
                html += `<span style="font-size:11px;color:var(--dim)">优先级: ${g.priority}</span>`;
                html += `</div>`;
                html += `<div style="display:flex;gap:6px;flex-wrap:wrap">`;
                html += `<button onclick="showEditUserGroup('${escAdmHtml(g.group_name)}')" style="padding:4px 10px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">编辑</button>`;
                html += `<button onclick="showGroupMembers('${escAdmHtml(g.group_name)}')" style="padding:4px 10px;background:#4caf50;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">成员</button>`;
                html += `<button onclick="deleteUserGroup('${escAdmHtml(g.group_name)}')" style="padding:4px 10px;background:#f44336;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">删除</button>`;
                html += `</div>`;
                html += `</div>`;
                // 详情行
                html += `<div style="display:flex;gap:12px;margin-top:8px;flex-wrap:wrap;font-size:12px;color:var(--dim)">`;
                html += `<span>💰 ${hasCustomPrice ? g.land_price_per_sqm+'债券/格²' : '默认'}</span>`;
                html += `<span>🏗️ ${hasCustomMax ? '最多'+g.max_lands+'块' : '默认'}</span>`;
                html += `<span>🔑 ${permCount}项默认权限</span>`;
                html += `</div>`;
                // 默认权限预览
                if (permCount > 0) {
                    const permTags = Object.entries(perms).filter(([,v]) => v === true).map(([k]) => {
                        const name = PERM_NAMES[k] || k;
                        return `<span style="display:inline-block;padding:1px 6px;background:rgba(76,175,80,0.15);color:#4caf50;border-radius:4px;font-size:10px;margin:1px">${name}</span>`;
                    }).join('');
                    html += `<div style="margin-top:6px;line-height:1.8">${permTags}</div>`;
                }
                html += `</div>`;
            }
        }

        el.innerHTML = html;
    } catch(e) {
        console.error('[USERGROUPS] Error:', e);
        el.innerHTML = `<div style="background:var(--card);border:1px solid var(--border);border-radius:8px;padding:24px;text-align:center;color:var(--dim)">加载失败: ${escAdmHtml(e.message)}</div>`;
    }
}

function showAddUserGroup() {
    const html = `<div style="padding:16px">
        <h3 style="margin:0 0 12px;color:var(--fg)">+ 新建用户组</h3>
        <div style="margin-bottom:10px">
            <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">组ID（英文标识）</label>
            <input id="ugName" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)" placeholder="如 vip, mvp, builder">
        </div>
        <div style="margin-bottom:10px">
            <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">显示名称</label>
            <input id="ugDisplayName" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)" placeholder="如 VIP玩家">
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px">
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">显示颜色</label>
                <select id="ugColor" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
                    <option value="§f">白色</option><option value="§a">绿色</option><option value="§b">青色</option>
                    <option value="§e">黄色</option><option value="§6">金色</option><option value="§c">红色</option>
                    <option value="§5">紫色</option><option value="§9">蓝色</option><option value="§d">粉色</option>
                </select>
            </div>
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">优先级</label>
                <input id="ugPriority" type="number" value="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px">
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">领地单价(债券/格², -1=默认)</label>
                <input id="ugPrice" type="number" value="-1" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">最大领地数(-1=默认)</label>
                <input id="ugMaxLands" type="number" value="-1" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px">
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">Home上限(0=跟随默认)</label>
                <input id="ugHomeLimit" type="number" value="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">有效期(分钟, 0=永久)</label>
                <input id="ugDuration" type="number" value="0" min="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px">
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">加入价格(债券, 0=免费)</label>
                <input id="ugJoinPrice" type="number" value="0" min="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
            <div>
                <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">续费价格(债券, 0=免费)</label>
                <input id="ugRenewPrice" type="number" value="0" min="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            </div>
        </div>
        <div style="margin-bottom:10px">
            <label style="display:flex;align-items:center;gap:8px;font-size:12px;color:var(--dim);cursor:pointer">
                <input type="checkbox" id="ugAutoRenew" style="accent-color:#4caf50;width:14px;height:14px">
                启用自动续费（到期时从债券余额扣除续费价格自动延长）
            </label>
        </div>
        <button onclick="doAddUserGroup()" style="width:100%;padding:8px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer">创建用户组</button>
    </div>`;
    showModal('新建用户组', '', null, html);
}

async function doAddUserGroup() {
    const name = document.getElementById('ugName')?.value?.trim();
    const displayName = document.getElementById('ugDisplayName')?.value?.trim() || name;
    const color = document.getElementById('ugColor')?.value || '§f';
    const priority = document.getElementById('ugPriority')?.value || '0';
    const price = document.getElementById('ugPrice')?.value || '-1';
    const maxLands = document.getElementById('ugMaxLands')?.value || '-1';
    const homeLimit = document.getElementById('ugHomeLimit')?.value || '0';
    const joinPrice = document.getElementById('ugJoinPrice')?.value || '0';
    const renewPrice = document.getElementById('ugRenewPrice')?.value || '0';
    const duration = document.getElementById('ugDuration')?.value || '0';
    const autoRenew = document.getElementById('ugAutoRenew')?.checked ? '1' : '0';

    if (!name || !/^[a-zA-Z0-9_]{2,20}$/.test(name)) {
        glassAlert('组ID仅允许英文字母、数字和下划线，2-20位');
        return;
    }

    try {
        const res = await apiCall('update_user_group', {
            name, display_name: displayName, display_color: color,
            priority, land_price_per_sqm: price, max_lands: maxLands,
            home_limit: homeLimit, join_price: joinPrice, renew_price: renewPrice,
            duration_minutes: duration, auto_renew: autoRenew,
            default_perms: '{}'
        }, 'POST');
        if (res.success) {
            document.querySelector('.modal-close')?.click();
            loadUserGroups(document.getElementById('C'));
        } else {
            glassAlert('创建失败: ' + (res.error || ''));
        }
    } catch(e) {
        glassAlert('创建失败: ' + e.message);
    }
}

async function showEditUserGroup(groupName) {
    try {
        const res = await apiCall('get_user_group', {name: groupName});
        if (!res.success || !res.group) { glassAlert('获取失败: ' + (res.error || '')); return; }
        const g = res.group;
        const perms = JSON.parse(g.default_perms || '{}');

        let html = `<div style="padding:16px;max-height:60vh;overflow-y:auto">`;
        html += `<h3 style="margin:0 0 12px;color:var(--fg)">编辑用户组: ${escAdmHtml(g.group_name)}</h3>`;
        html += `<div style="margin-bottom:10px">
            <label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">显示名称</label>
            <input id="eugDisplayName" value="${escAdmHtml(g.display_name || '')}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
        </div>`;
        html += `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:10px">
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">显示颜色</label>
            <select id="eugColor" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
                ${['§f|白色','§a|绿色','§b|青色','§e|黄色','§6|金色','§c|红色','§5|紫色','§9|蓝色','§d|粉色'].map(c => {
                    const [v,l] = c.split('|');
                    return `<option value="${v}" ${g.display_color === v ? 'selected' : ''}>${l}</option>`;
                }).join('')}
            </select></div>
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">优先级</label>
            <input id="eugPriority" type="number" value="${g.priority}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
        </div>`;
        html += `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px">
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">领地单价(债券/格²)</label>
            <input id="eugPrice" type="number" value="${g.land_price_per_sqm}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">最大领地数</label>
            <input id="eugMaxLands" type="number" value="${g.max_lands}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
        </div>`;
        html += `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px">
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">Home上限(0=默认)</label>
            <input id="eugHomeLimit" type="number" value="${g.home_limit || 0}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">有效期(分钟, 0=永久)</label>
            <input id="eugDuration" type="number" value="${g.duration_minutes || 0}" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
        </div>`;
        html += `<div style="display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px">
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">加入价格(债券)</label>
            <input id="eugJoinPrice" type="number" value="${g.join_price || 0}" min="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
            <div><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:4px">续费价格(债券)</label>
            <input id="eugRenewPrice" type="number" value="${g.renew_price || 0}" min="0" style="width:100%;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)"></div>
        </div>`;
        html += `<div style="margin-bottom:12px">
            <label style="display:flex;align-items:center;gap:8px;font-size:12px;color:var(--dim);cursor:pointer">
                <input type="checkbox" id="eugAutoRenew" ${g.auto_renew == 1 ? 'checked' : ''} style="accent-color:#4caf50;width:14px;height:14px">
                启用自动续费
            </label>
        </div>`;
        // 默认权限列表
        html += `<div style="margin-bottom:12px"><label style="display:block;font-size:12px;color:var(--dim);margin-bottom:6px">默认权限</label>`;
        html += '<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(140px,1fr));gap:4px">';
        for (const [key, label] of Object.entries(PERM_NAMES)) {
            const checked = perms[key] === true;
            html += `<label style="display:flex;align-items:center;gap:4px;padding:4px 6px;border:1px solid var(--border);border-radius:4px;cursor:pointer;font-size:11px;${checked ? 'background:rgba(76,175,80,0.15);border-color:#4caf50;color:#4caf50' : 'color:var(--dim)'}">
                <input type="checkbox" data-perm="${key}" ${checked ? 'checked' : ''} style="accent-color:#4caf50;width:12px;height:12px">
                ${label}
            </label>`;
        }
        html += '</div></div>';
        html += `<button onclick="doEditUserGroup('${escAdmHtml(g.group_name)}')" style="width:100%;padding:8px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer">保存修改</button>`;
        html += '</div>';

        showModal('编辑用户组', '', null, html);
    } catch(e) {
        glassAlert('加载失败: ' + e.message);
    }
}

async function doEditUserGroup(groupName) {
    const displayName = document.getElementById('eugDisplayName')?.value?.trim() || '';
    const color = document.getElementById('eugColor')?.value || '§f';
    const priority = document.getElementById('eugPriority')?.value || '0';
    const price = document.getElementById('eugPrice')?.value || '-1';
    const maxLands = document.getElementById('eugMaxLands')?.value || '-1';
    const homeLimit = document.getElementById('eugHomeLimit')?.value || '0';
    const duration = document.getElementById('eugDuration')?.value || '0';
    const joinPrice = document.getElementById('eugJoinPrice')?.value || '0';
    const renewPrice = document.getElementById('eugRenewPrice')?.value || '0';
    const autoRenew = document.getElementById('eugAutoRenew')?.checked ? '1' : '0';

    // 收集权限
    const perms = {};
    document.querySelectorAll('#glassAlertOverlay input[type=checkbox][data-perm]').forEach(cb => {
        perms[cb.dataset.perm] = cb.checked;
    });

    try {
        const res = await apiCall('update_user_group', {
            name: groupName, display_name: displayName, display_color: color,
            priority, land_price_per_sqm: price, max_lands: maxLands,
            home_limit: homeLimit, duration_minutes: duration,
            join_price: joinPrice, renew_price: renewPrice, auto_renew: autoRenew,
            default_perms: JSON.stringify(perms)
        }, 'POST');
        if (res.success) {
            document.querySelector('.modal-close')?.click();
            loadUserGroups(document.getElementById('C'));
        } else {
            glassAlert('保存失败: ' + (res.error || ''));
        }
    } catch(e) {
        glassAlert('保存失败: ' + e.message);
    }
}

async function deleteUserGroup(groupName) {
    if (!await glassConfirm('确定删除用户组 [' + groupName + '] ?\n已分配的成员将被移出该组。')) return;
    try {
        const res = await apiCall('delete_user_group', {name: groupName});
        if (res.success) {
            loadUserGroups(document.getElementById('C'));
        } else {
            glassAlert('删除失败: ' + (res.error || ''));
        }
    } catch(e) {
        glassAlert('删除失败: ' + e.message);
    }
}

// ===== 用户组成员管理 =====
async function showGroupMembers(groupName) {
    try {
        const [membersRes, groupRes] = await Promise.all([
            apiCall('list_group_members', {group: groupName}),
            apiCall('get_user_group', {name: groupName})
        ]);
        const members = membersRes.members || [];
        const g = groupRes.group || {};
        const permCount = Object.keys(JSON.parse(g.default_perms || '{}')).filter(k => JSON.parse(g.default_perms || '{}')[k] === true).length;

        let html = `<div style="padding:16px;max-height:60vh;overflow-y:auto">`;
        html += `<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;flex-wrap:wrap;gap:8px">`;
        html += `<h3 style="margin:0;color:var(--fg)">${escAdmHtml(g.display_emoji || '👥')} ${escAdmHtml(g.display_name || groupName)} 成员管理</h3>`;
        html += `<span style="font-size:12px;color:var(--dim)">${members.length} 人</span>`;
        html += `</div>`;

        // 添加成员
        html += `<div style="display:flex;gap:8px;margin-bottom:12px">
            <input id="ugNewMember" placeholder="玩家名" style="flex:1;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--fg)">
            <button onclick="doAddGroupMember('${escAdmHtml(groupName)}')" style="padding:8px 16px;background:var(--accent);color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:12px">添加</button>
        </div>`;

        // 成员列表
        if (members.length === 0) {
            html += '<div style="text-align:center;padding:20px;color:var(--dim);font-size:13px">暂无成员</div>';
        } else {
            html += '<div style="overflow-x:auto"><table class="table"><tr><th>玩家名</th><th>添加者</th><th>操作</th></tr>';
            for (const m of members) {
                html += `<tr>
                    <td><strong>${escAdmHtml(m.player_name)}</strong></td>
                    <td style="font-size:12px;color:var(--dim)">${escAdmHtml(m.added_by || '-')}</td>
                    <td><button onclick="doRemoveGroupMember('${escAdmHtml(groupName)}','${escAdmHtml(m.player_name)}')" style="padding:2px 8px;background:#f44336;color:#fff;border:none;border-radius:4px;cursor:pointer;font-size:11px">移除</button></td>
                </tr>`;
            }
            html += '</table></div>';
        }
        html += '</div>';

        showModal('成员管理', '', null, html);
    } catch(e) {
        glassAlert('加载失败: ' + e.message);
    }
}

async function doAddGroupMember(groupName) {
    const inputEl = document.getElementById('ugNewMember');
    const player = inputEl ? inputEl.value.trim() : '';
    if (!player) { glassAlert('请输入玩家名'); return; }
    if (!/^[a-zA-Z0-9_]{3,16}$/.test(player)) { glassAlert('玩家名格式不正确，仅支持英文字母、数字和下划线（3-16位）'); return; }

    try {
        const res = await apiCall('add_group_member', {group: groupName, player}, 'POST');
        if (res.success) {
            // pending=true 是异步验证响应，需要特殊处理
            if (res.pending) {
                glassAlert(res.message || '验证请求已提交，系统将在1-2分钟内自动完成验证');
                showGroupMembers(groupName); // 刷新
            } else {
                showGroupMembers(groupName); // 刷新
            }
        } else {
            glassAlert(res.error || '添加失败');
        }
    } catch(e) {
        glassAlert('添加失败: ' + e.message);
    }
}

async function doRemoveGroupMember(groupName, playerName) {
    if (!await glassConfirm('确定将玩家 [' + playerName + '] 从用户组 [' + groupName + '] 中移除?')) return;
    try {
        const res = await apiCall('remove_group_member', {group: groupName, player: playerName}, 'POST');
        if (res.success) {
            showGroupMembers(groupName); // 刷新
        } else {
            glassAlert('移除失败: ' + (res.error || ''));
        }
    } catch(e) {
        glassAlert('移除失败: ' + e.message);
    }
}
</script>
<!-- 毛玻璃弹窗 -->
<div class="glass-alert-overlay" id="glassAlertOverlay" onclick="glassAlertResolve(false)">
    <div class="glass-alert-card" onclick="event.stopPropagation()">
        <div class="alert-icon" id="glassAlertIcon">⚠️</div>
        <div class="alert-msg" id="glassAlertMsg"></div>
        <div class="alert-label" id="glassAlertLabel" style="display:none"></div>
        <input class="alert-input" id="glassAlertInput" style="display:none" onkeydown="if(event.key==='Enter')glassAlertResolve(true)">
        <div class="alert-hint" id="glassAlertHint" style="display:none"></div>
        <div class="alert-btns" id="glassAlertBtns">
            <button class="ag-ok" onclick="glassAlertResolve(true)">确定</button>
        </div>
    </div>
</div>
<script>
let _glassAlertResolve = null;
function glassAlertResolve(val) {
    const overlay = document.getElementById('glassAlertOverlay');
    overlay.classList.remove('show');
    if (_glassAlertResolve) {
        const input = document.getElementById('glassAlertInput');
        // 如果输入框可见且点了确定，返回输入值
        const result = (val && input.style.display !== 'none') ? input.value : val;
        _glassAlertResolve(result);
        _glassAlertResolve = null;
    }
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
        document.getElementById('glassAlertMsg').style.display = '';
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
</script>
</body>
</html>
