<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
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
        <div class="si" data-p="users" onclick="go('users')">👥 全部用户</div>
        <div class="si" data-p="online" onclick="go('online')">🟢 在线玩家</div>
        <div class="si" data-p="active" onclick="go('active')">⏱️ 活跃用户</div>
        <div class="si" data-p="reset_requests" onclick="go('reset_requests')">🔑 密码重置审核</div>
    </div>
    <div class="content" id="C"></div>
</div>

<script>
const A = 'api/admin.php';
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
}

// 检查登录状态
(async function(){
    const r = await fetch(A+'?action=status');
    const d = await r.json();
    if (!d.data.logged_in) { location.href='admin_login.php'; return; }
    go('dashboard');
})();

// ===== 总览 =====
async function loadDashboard(el) {
    el.innerHTML = '<div class="empty">加载中...</div>';
    const r = await api('admin.php?action=get_stats_ex');
    if (!r.success) { el.innerHTML='<div class="card">'+r.message+'</div>'; return; }
    const d = r.data;
    el.innerHTML = `
        <div class="stats">
            <div class="stat"><div class="v">${d.total_users}</div><div class="l">注册用户</div></div>
            <div class="stat"><div class="v" style="color:var(--green)">${d.online_count}</div><div class="l">在线玩家</div></div>
            <div class="stat"><div class="v" style="color:var(--yellow)">${d.active_count_24h}</div><div class="l">24h活跃</div></div>
        </div>
        <div class="card">
            <h2>快捷统计</h2>
            <div class="stats">
                <div class="stat"><div class="v">${d.all_users || '-'}</div><div class="l">总用户</div></div>
                <div class="stat"><div class="v" style="color:var(--green)">${d.online || 0}</div><div class="l">在线</div></div>
            </div>
        </div>`;
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
    const r = await api('balance.php?action=query&player='+encodeURIComponent(player));
    const div = document.getElementById('qResult');
    if (r.success) div.innerHTML=`<div class="card" style="margin-top:8px"><p>债券: <b style="color:var(--green)">${r.data.bonds}</b> | 积分: <b style="color:var(--purple)">${r.data.points}</b></p></div>`;
    else div.innerHTML=`<p style="color:var(--red);margin-top:8px">${r.message}</p>`;
}

// ===== 商品管理 =====
async function loadShop(el) {
    const r = await api('shop.php?action=list');
    const items = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>商品管理 <button class="btn btn-blue" style="float:right" onclick="showAddShop()">+ 添加商品</button></h2>
            <table class="table">
                <tr><th>ID</th><th>名称</th><th>分类</th><th>购入价</th><th>库存</th><th>销量</th><th>操作</th></tr>
                ${items.map(i=>`<tr>
                    <td>${i.id}</td><td>${i.display_name}</td><td>${i.category}</td>
                    <td>${i.buy_price}</td><td>${i.stock==-1?'∞':i.stock}</td><td>${i.total_sales}</td>
                    <td><button class="btn btn-yellow" onclick="editStock('${i.id}',${i.stock})">改库存</button>
                    <button class="btn btn-red" onclick="removeShop('${i.id}')">删除</button></td>
                </tr>`).join('')}
            </table>
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
    const r = await api('cdk.php?action=list');
    const list = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>CDK管理</h2>
            <div style="display:flex;gap:12px;margin-bottom:12px">
                <div class="card" style="flex:1;margin:0">
                    <h2>生成CDK</h2>
                    <div class="form-row"><label>金额</label><input id="cAmount" type="number" value="100"></div>
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
    const amount = parseInt(document.getElementById('cAmount').value);
    const count = parseInt(document.getElementById('cCount').value);
    if (!amount||!count) { toast('请填写完整','err'); return; }
    const r = await postApi('cdk_batch', {amount, count});
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
    const r = await api('admin.php?action=all_tx&limit=100');
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
    const r = await api('admin.php?action=player_tx&player='+encodeURIComponent(player)+'&limit=100');
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

// ===== 全部用户 =====
async function loadUsers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await api('admin.php?action=list_users');
    const users = r.data || [];
    
    // 加载在线玩家数据
    const onlineR = await api('admin.php?action=list_online_players');
    const onlinePlayers = onlineR.success ? new Set(onlineR.data.map(p => p.player_name.toLowerCase())) : new Set();
    
    el.innerHTML = `
        <div class="card">
            <h2>全部用户 <span style="color:var(--dim);font-size:12px">(${users.length}个用户${onlinePlayers.size > 0 ? '| 在线: '+onlinePlayers.size : ''})</span></h2>
            <div class="form-row" style="margin-bottom:12px">
                <input id="userSearch" placeholder="搜索玩家名..." oninput="filterUsers()" style="flex:1">
            </div>
            <table class="table" id="userTable">
                <tr><th>玩家名</th><th>注册时间</th><th>最后登录</th><th>积分</th><th>在线时长</th><th>邮箱</th><th>操作</th></tr>
                ${users.map(u => {
                    const isOnline = onlinePlayers.has((u.player_name||'').toLowerCase());
                    const playerNameClass = isOnline ? 'player-online' : '';
                    const regTime = u.register_time ? new Date(u.register_time*1000).toLocaleString() : '-';
                    const loginTime = u.last_login_time ? new Date(u.last_login_time*1000).toLocaleString() : '-';
                    const hours = Math.floor((u.total_online_time||0)/3600);
                    return `<tr data-name="${(u.player_name||'').toLowerCase()}">
                        <td class="${playerNameClass}">${isOnline ? '🟢 ' : ''}${u.player_name}</td>
                        <td>${regTime}</td>
                        <td>${loginTime}</td>
                        <td>${u.points||0}</td>
                        <td>${hours}h</td>
                        <td>${u.email||'-'}</td>
                        <td>
                            <button class="btn btn-blue" onclick="adminSendReset('${u.player_name}','${u.email||''}')">重置密码</button>
                        </td>
                    </tr>`;
                }).join('')}
            </table>
            ${users.length === 0 ? '<div class="empty">暂无用户数据，确保插件已同步用户数据</div>' : ''}
        </div>`;
}

// ===== 在线玩家 =====
async function loadOnlinePlayers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    loadOnlinePlayersData(el);
    // 每10秒刷新一次
    onlineInterval = setInterval(() => loadOnlinePlayersData(el), 10000);
}

async function loadOnlinePlayersData(el) {
    const r = await api('admin.php?action=list_online_players');
    if (!r.success) return;
    const players = r.data || [];
    el.innerHTML = `
        <div class="card">
            <h2>在线玩家 <span style="color:var(--dim);font-size:12px">(${players.length}人在线)</span></h2>
            <table class="table">
                <tr><th>玩家名</th><th>登录时间</th><th>在线时长</th></tr>
                ${players.map(p => {
                    const loginTime = p.login_time ? new Date(p.login_time*1000).toLocaleString() : '-';
                    const minsOnline = Math.floor((Date.now()/1000 - p.login_time)/60);
                    return `<tr>
                        <td class="player-online">🟢 ${p.player_name}</td>
                        <td>${loginTime}</td>
                        <td>${minsOnline}分钟</td>
                    </tr>`;
                }).join('')}
            </table>
            ${players.length === 0 ? '<div class="empty">暂无在线玩家</div>' : ''}
        </div>`;
}

// ===== 活跃用户（24小时） =====
async function loadActivePlayers(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await api('admin.php?action=list_active_players');
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

function filterUsers() {
    const search = document.getElementById('userSearch').value.toLowerCase();
    const rows = document.querySelectorAll('#userTable tr[data-name]');
    rows.forEach(r => {
        r.style.display = r.dataset.name.includes(search) ? '' : 'none';
    });
}

// ===== 密码重置审核 =====
async function loadResetRequests(el) {
    el.innerHTML = '<div class="card">加载中...</div>';
    const r = await api('admin.php?action=list_reset_requests');
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

// ===== 通用 =====
async function api(url) { const r=await fetch('api/' + url); return await r.json(); }
async function postApi(action, data) {
    const r = await fetch(A, {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({action,...data})});
    return await r.json();
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
        <div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:400px;max-width:90%">
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
                <input type="text" id="customColor" placeholder="或输入十六进制颜色代码，如 #1a237e" class="color-input">
            </div>
            <div style="display:flex;gap:8px;justify-content:flex-end;margin-top:16px">
                <button class="btn" id="themeCancel">取消</button>
                <button class="btn btn-blue" id="themeConfirm" onclick="applyCustomColor()">应用</button>
            </div>
        </div>`;
    document.body.appendChild(overlay);
    document.getElementById('themeCancel').onclick = () => overlay.remove();
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
}

function setTheme(color) {
    document.documentElement.style.setProperty('--bg', color);
    document.body.style.background = color;
    localStorage.setItem('sdf1_theme', color);
    toast('主题已应用', 'ok');
}

function applyCustomColor() {
    const color = document.getElementById('customColor').value.trim();
    if (color && /^#[0-9A-Fa-f]{6}$/.test(color)) {
        setTheme(color);
        document.querySelector('[id="themeCancel"]').click();
    } else {
        toast('请输入有效的十六进制颜色代码，如 #1a237e', 'err');
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
