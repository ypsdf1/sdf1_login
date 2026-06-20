<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
    <meta http-equiv="Pragma" content="no-cache">
    <meta http-equiv="Expires" content="0">
    <title>SDF1 - 管理后台</title>
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
const _BUILD_TS = 1781928829; // 版本号，用于缓存失效
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
                let subnet = ip;
                if (ip !== '-') {
                    const parts = ip.split('.');
                    if (parts.length === 4) {
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
    if (!confirm('确定删除商品 '+id+'?')) return;
    const r = await postApi('shop_remove', {id});
    toast(r.message, r.success?'ok':'err');
    if (r.success) loadShop(document.getElementById('C'));
}

// ===== CDK管理 =====
async function loadCDK(el) {
    const r = await jsonApi('cdk.php?action=list');
    const list = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>CDK管理</h2>
            <div style="display:flex;gap:12px;margin-bottom:12px">
                <div class="card" style="flex:1;margin:0">
                    <h2>生成CDK</h2>
                    <div class="form-row"><label>金额</label><input id="cAmount" type="text" value="100" placeholder="固定金额或区间(如100-200)"></div>
                    <div class="form-row"><label>数量</label><input id="cCount" type="number" value="10" min="1" max="100"></div>
                    <button class="btn btn-green" onclick="doBatchCDK()">批量生成</button>
                </div>
            </div>
            <table class="table">
                <tr><th>兑换码</th><th>金额</th><th>状态</th><th>使用者</th><th>创建时间</th></tr>
                ${list.map(c=>`<tr>
                    <td style="font-family:monospace">${c.code}</td>
                    <td>${c.amount}</td>
                    <td>${c.used?'<span class="tag tag-used">已使用</span>':'<span class="tag tag-unused">未使用</span>'}</td>
                    <td>${c.used_by||'-'}</td>
                    <td>${c.created_at?new Date(c.created_at*1000).toLocaleString():'-'}</td>
                </tr>`).join('')}
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
                // 按/24子网分组
                const subnetMap = {};
                const subnetOrder = [];
                users.forEach(u => {
                    const ip = u.ip_address || '-';
                    let subnet = ip;
                    if (ip !== '-') {
                        const parts = ip.split('.');
                        if (parts.length === 4) subnet = parts[0] + '.' + parts[1] + '.' + parts[2];
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
        fetch('api/admin.php', {
            method: 'POST',
            credentials: 'same-origin',
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
            console.error('[BatchIP] Batch #' + batchNum + ' fetch error:', e);
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

        // 统计同子网玩家
        const subnetCountMap = {};
        players.forEach(p => {
            const ip = p.ip_address || '-';
            if (ip !== '-') {
                const parts = ip.split('.');
                const subnet = parts.length === 4 ? parts[0]+'.'+parts[1]+'.'+parts[2] : ip;
                subnetCountMap[subnet] = (subnetCountMap[subnet] || 0) + 1;
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
                    if (ip !== '-') {
                        const parts = ip.split('.');
                        subnet = parts.length === 4 ? parts[0]+'.'+parts[1]+'.'+parts[2] : ip;
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
function showModal(title, message, defaultValue) {
    return new Promise((resolve) => {
        const overlay = document.createElement('div');
        overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:1000';
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
        document.body.appendChild(overlay);
        const input = document.getElementById('modalInput');
        input.focus();
        input.select();
        input.addEventListener('keydown', e => { if (e.key === 'Enter') { resolve(input.value); overlay.remove(); } });
        document.getElementById('modalCancel').onclick = () => { overlay.remove(); resolve(null); };
        document.getElementById('modalConfirm').onclick = () => { resolve(input.value); overlay.remove(); };
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
</script>
</body>
</html>
