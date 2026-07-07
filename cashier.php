<?php
/**
 * 独立收银台页面
 * 支持管理员登录或收银员登录；具备商品选择、购物车、手动折扣（服务端强制上限）、订单历史。
 */
require_once __DIR__ . '/core.php';

if (session_status() === PHP_SESSION_NONE) session_start();

// 读取收银台配置（★ 单次查询同时获取packmoney和green_discount）
function cashierGetConfig() {
    try {
        $db = getDB();
        $stmt = $db->prepare("SELECT cfg_key, cfg_value FROM shop_config WHERE cfg_key IN ('packmoney','green_discount')");
        $result = $stmt->execute();
        $map = ['packmoney' => 5, 'green_discount' => 2]; // 默认值
        while ($row = $result->fetchArray(SQLITE3_ASSOC)) {
            $map[$row['cfg_key']] = $row['cfg_value'];
        }
        return $map;
    } catch (Exception $e) {
        return ['packmoney' => 5, 'green_discount' => 2];
    }
}
$cfg = cashierGetConfig();
$packMoney = (int)$cfg['packmoney'];
$greenDiscount = (float)$cfg['green_discount'];

$loggedIn = isAdminLoggedIn() || isCashierLoggedIn();
$initialRole = 'guest';
$initialName = '';
$initialLimit = 0;
if (isAdminLoggedIn()) {
    $initialRole = 'admin';
    $initialName = 'admin';
    $initialLimit = 100;
    $initialCanCash = 1; // 管理员始终可进行现金收款
} elseif (isCashierLoggedIn()) {
    $c = getCurrentCashier();
    $initialRole = 'cashier';
    $initialName = $c['username'];
    $initialLimit = $c['discount_limit_percent'];
    $initialCanCash = (int)($c['can_cash'] ?? 0);
}
$initialState = json_encode([
    'logged_in' => $loggedIn,
    'role' => $initialRole,
    'username' => $initialName,
    'discount_limit' => $initialLimit,
    'cashier_can_cash' => $initialCanCash,
    'packmoney' => $packMoney,
    'green_discount' => $greenDiscount
], JSON_UNESCAPED_UNICODE);
?>
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta http-equiv="Cache-Control" content="no-store, no-cache, must-revalidate">
<meta http-equiv="Pragma" content="no-cache">
<meta http="Expires" content="0">
<title>SDF1 - 收银台</title>
<style>
    :root { --bg:#0d1117; --card:#161b22; --border:#30363d; --text:#e6edf3; --dim:#8b949e; --accent:#58a6ff; --green:#3fb950; --red:#f85149; --yellow:#d29922; --purple:#bc8cff; }
    * { margin:0; padding:0; box-sizing:border-box; }
    body { background:var(--bg); color:var(--text); font-family:'Segoe UI',system-ui,sans-serif; min-height:100vh; transition:background .25s ease; }
    .header { background:linear-gradient(135deg,#1a1e2e,#0d1117); border-bottom:1px solid var(--border); padding:14px 24px; display:flex; justify-content:space-between; align-items:center; position:sticky; top:0; z-index:50; }
    .header h1 { font-size:18px; color:var(--accent); letter-spacing:.5px; }
    .header .who { font-size:13px; color:var(--dim); }
    .header .who b { color:var(--text); }
    .tag { display:inline-block; padding:2px 10px; border-radius:999px; font-size:11px; font-weight:600; line-height:1.5; vertical-align:middle; }
    .tag-admin { background:rgba(188,140,255,.15); color:var(--purple); border:1px solid rgba(188,140,255,.35); }
    .tag-cashier { background:rgba(88,166,255,.15); color:var(--accent); border:1px solid rgba(88,166,255,.35); }
    .btn { padding:8px 16px; border:none; border-radius:6px; cursor:pointer; font-size:13px; font-weight:600; transition:all .2s cubic-bezier(.16,1,.3,1); }
    .btn-blue { background:var(--accent); color:#fff; }
    .btn-green { background:var(--green); color:#fff; }
    .btn-red { background:var(--red); color:#fff; }
    .btn-yellow { background:var(--yellow); color:#000; }
    .btn-ghost { background:transparent; border:1px solid var(--border); color:var(--dim); }
    .btn:hover { opacity:.88; transform:translateY(-1px); box-shadow:0 4px 14px rgba(0,0,0,.35); }
    .btn:active { transform:translateY(0); }
    .btn:disabled { opacity:.4; cursor:not-allowed; transform:none; box-shadow:none; }

    /* 登录页 */
    .login-wrap { min-height:100vh; display:flex; align-items:center; justify-content:center; padding:20px;
        background:radial-gradient(1200px 600px at 50% -10%, rgba(88,166,255,.12), transparent); }
    .login-card { background:var(--card); border:1px solid var(--border); border-radius:16px; padding:36px 32px; width:360px; max-width:100%; box-shadow:0 20px 60px rgba(0,0,0,.5); }
    .login-card h2 { text-align:center; margin-bottom:6px; color:var(--accent); font-size:22px; }
    .login-card .sub { text-align:center; color:var(--dim); font-size:12px; margin-bottom:24px; }
    .login-card label { display:block; font-size:12px; color:var(--dim); margin:14px 0 6px; }
    .login-card input { width:100%; padding:11px 12px; background:var(--bg); border:1px solid var(--border); border-radius:8px; color:var(--text); font-size:14px; outline:none; transition:border .2s; }
    .login-card input:focus { border-color:var(--accent); box-shadow:0 0 0 3px rgba(88,166,255,.15); }
    .login-card .btn { width:100%; margin-top:22px; padding:12px; font-size:15px; }

    /* 主界面 */
    .app { display:grid; grid-template-columns: 380px 1fr; gap:16px; padding:16px; align-items:start; }
    @media(max-width:900px){ .app { grid-template-columns:1fr; } }
    .panel { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:16px; }
    .panel h3 { font-size:14px; color:var(--accent); margin-bottom:12px; display:flex; align-items:center; gap:8px; }
    .field { margin-bottom:12px; }
    .field label { font-size:12px; color:var(--dim); display:block; margin-bottom:5px; }
    .field input, .field select { width:100%; padding:9px 11px; background:var(--bg); border:1px solid var(--border); border-radius:7px; color:var(--text); font-size:13px; outline:none; }
    .field input:focus, .field select:focus { border-color:var(--accent); }
    .player-bar { display:flex; gap:8px; }
    .player-bar input { flex:1; }
    .balances { display:flex; gap:10px; margin:12px 0; }
    .balances .b { flex:1; background:var(--bg); border:1px solid var(--border); border-radius:8px; padding:10px; text-align:center; }
    .balances .b .v { font-size:20px; font-weight:700; color:var(--green); }
    .balances .b .l { font-size:11px; color:var(--dim); margin-top:3px; }
    .online-dot { display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:5px; }
    .online-dot.on { background:var(--green); box-shadow:0 0 8px var(--green); }
    .online-dot.off { background:var(--dim); }

    /* 统一主界面输入框/下拉框样式：修复深色主题下浏览器默认浅色控件"样式不搭"与布局错位问题 */
    .app input, .app select, .app textarea {
        padding:9px 11px; background:var(--bg); border:1px solid var(--border);
        border-radius:7px; color:var(--text); font-size:13px; outline:none;
        font-family:inherit; transition:border .2s, box-shadow .2s;
    }
    .app select { width:100%; }
    .app input:focus, .app select:focus, .app textarea:focus {
        border-color:var(--accent); box-shadow:0 0 0 3px rgba(88,166,255,.12);
    }
    .player-bar input { flex:1; }
    .catalog-head input { flex:1; }

    .cart-item { display:flex; align-items:center; gap:10px; padding:10px; background:var(--bg); border:1px solid var(--border); border-radius:9px; margin-bottom:8px; transition:all .2s; }
    .cart-item:hover { border-color:var(--accent); }
    .cart-item .ci-name { flex:1; font-size:13px; }
    .cart-item .ci-name small { color:var(--dim); display:block; font-size:11px; }
    .qty { display:flex; align-items:center; gap:6px; }
    .qty button { width:26px; height:26px; border-radius:6px; border:1px solid var(--border); background:var(--card); color:var(--text); cursor:pointer; font-size:15px; line-height:1; }
    .qty input { width:46px; text-align:center; padding:5px; background:var(--bg); border:1px solid var(--border); border-radius:6px; color:var(--text); font-size:13px; }
    .ci-del { color:var(--red); cursor:pointer; font-size:16px; padding:4px; }

    .settlement-row { display:flex; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
    .seg { padding:8px 14px; border:1px solid var(--border); border-radius:8px; cursor:pointer; font-size:12px; color:var(--dim); background:var(--bg); transition:all .2s; }
    .seg.active { background:var(--accent); color:#fff; border-color:var(--accent); }

    .summary-line { display:flex; justify-content:space-between; font-size:13px; padding:6px 0; border-bottom:1px dashed var(--border); }
    .summary-line.total { font-size:17px; font-weight:700; border-bottom:none; margin-top:8px; }
    .summary-line.total .v { color:var(--green); }
    .summary-line .disc { color:var(--yellow); }

    .catalog-head { display:flex; gap:8px; margin-bottom:12px; flex-wrap:wrap; }
    .cat-tabs { display:flex; gap:6px; flex-wrap:wrap; margin-bottom:12px; }
    .prod-grid { display:grid; grid-template-columns:repeat(auto-fill,minmax(150px,1fr)); gap:10px; max-height:62vh; overflow-y:auto; padding-right:4px; }
    .prod { background:var(--bg); border:1px solid var(--border); border-radius:10px; padding:12px; cursor:pointer; transition:all .2s cubic-bezier(.16,1,.3,1); position:relative; overflow:hidden; }
    .prod:hover { transform:translateY(-3px); border-color:var(--accent); box-shadow:0 8px 24px rgba(0,0,0,.4); }
    .prod .pname { font-size:13px; font-weight:600; margin-bottom:4px; }
    .prod .pcat { font-size:10px; color:var(--dim); margin-bottom:8px; }
    .prod .pprice { font-size:15px; color:var(--accent); font-weight:700; }
    .prod .pstock { font-size:10px; color:var(--dim); position:absolute; top:10px; right:10px; }
    .prod.soldout { opacity:.45; cursor:not-allowed; }
    .prod.soldout:hover { transform:none; border-color:var(--border); box-shadow:none; }

    .toast { position:fixed; top:16px; right:16px; padding:11px 18px; border-radius:8px; font-size:13px; z-index:200; animation:sIn .3s; box-shadow:0 8px 24px rgba(0,0,0,.4); }
    .toast.ok { background:var(--green); color:#fff; }
    .toast.err { background:var(--red); color:#fff; }
    @keyframes sIn { from{transform:translateX(110%);opacity:0} to{transform:translateX(0);opacity:1} }

    .orders { margin-top:14px; }
    .order-row { font-size:12px; padding:10px; background:var(--bg); border:1px solid var(--border); border-radius:8px; margin-bottom:7px; }
    .order-row .ot { display:flex; justify-content:space-between; }
    .order-row .ono { color:var(--accent); font-weight:600; }
    .order-row .od { color:var(--dim); margin-top:4px; }
    .order-row .od .disc { color:var(--yellow); }

    .theme-picker { display:flex; gap:8px; align-items:center; margin-top:8px; }
    .color-btn { width:28px; height:28px; border-radius:4px; cursor:pointer; border:2px solid transparent; transition:all .2s; }
    .color-btn:hover { border-color:var(--accent); transform:scale(1.12); }

    .glass-alert-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.45);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:5000;justify-content:center;align-items:center;animation:glassFadeIn .25s ease}
    .glass-alert-overlay.show{display:flex}
    .glass-alert-card{background:rgba(22,27,34,0.94);backdrop-filter:blur(24px);-webkit-backdrop-filter:blur(24px);border:1px solid rgba(88,166,255,0.25);border-radius:16px;padding:26px;width:340px;max-width:88%;box-shadow:0 12px 48px rgba(0,0,0,0.5),inset 0 1px 0 rgba(255,255,255,0.05);animation:glassSlideUp .3s ease;text-align:center}
    .glass-alert-card .alert-icon{font-size:34px;margin-bottom:10px}
    .glass-alert-card .alert-msg{font-size:14px;color:#e6edf3;line-height:1.6;margin-bottom:18px;word-break:break-word}
    .glass-alert-card .alert-input{width:100%;padding:10px 12px;background:rgba(13,17,23,0.8);border:1px solid rgba(88,166,255,0.3);border-radius:8px;color:#e6edf3;font-size:13px;outline:none;box-sizing:border-box;text-align:center;margin-bottom:16px}
    .glass-alert-card .alert-btns{display:flex;gap:10px;justify-content:center}
    .glass-alert-card .alert-btns button{padding:8px 24px;border:none;border-radius:8px;font-size:13px;font-weight:600;cursor:pointer;transition:all .2s}
    .glass-alert-card .alert-btns .ag-ok{background:#58a6ff;color:#fff}
    .glass-alert-card .alert-btns .ag-cancel{background:rgba(255,255,255,0.08);color:#8b949e}
    @keyframes glassFadeIn{from{opacity:0}to{opacity:1}}
    @keyframes glassSlideUp{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)}}
    .hidden { display:none !important; }

    /* 自定义滚动条：统一深色质感，避免浏览器默认浅色滚动条破坏整体观感 */
    *::-webkit-scrollbar { width:10px; height:10px; }
    *::-webkit-scrollbar-track { background:transparent; }
    *::-webkit-scrollbar-thumb { background:var(--border); border-radius:6px; }
    *::-webkit-scrollbar-thumb:hover { background:var(--dim); }
    * { scrollbar-width:thin; scrollbar-color:var(--border) transparent; }
</style>
</head>
<body>

<?php if (!$loggedIn): ?>
<!-- 登录页 -->
<div class="login-wrap" id="loginWrap">
    <div class="login-card">
        <h2>🧾 SDF1 收银台</h2>
        <div class="sub">管理员或收银员登录</div>
        <form id="loginForm" onsubmit="return doLogin(event)">
            <label>账号</label>
            <input type="text" id="loginUser" autocomplete="username" placeholder="收银员账号 / admin" required>
            <label>密码</label>
            <input type="password" id="loginPass" autocomplete="current-password" placeholder="登录密码" required>
            <button type="submit" class="btn btn-blue" id="loginBtn">登 录</button>
        </form>
    </div>
</div>
<?php else: ?>
<!-- 主界面 -->
<div class="header">
    <div style="display:flex;align-items:center;gap:12px">
        <h1>🧾 SDF1 收银台</h1>
        <span class="who">操作员：<b id="whoName"></b> <span id="whoRole" class="tag tag-admin"></span></span>
    </div>
    <div style="display:flex;align-items:center;gap:10px">
        <button class="btn btn-yellow" onclick="showThemePicker()">🎨 主题</button>
        <button class="btn btn-red" onclick="doLogout()">退出</button>
    </div>
</div>

<div class="app">
    <!-- 左：玩家 + 购物车 -->
    <div>
        <div class="panel">
            <h3>👤 目标玩家</h3>
            <div class="player-bar">
                <input type="text" id="playerName" placeholder="输入玩家名" onkeydown="if(event.key==='Enter')checkPlayer()">
                <button class="btn btn-blue" onclick="checkPlayer()">查询</button>
            </div>
            <div class="balances">
                <div class="b"><div class="v" id="balVal">-</div><div class="l">债券余额</div></div>
                <div class="b"><div class="v" style="font-size:13px;padding-top:5px"><span class="online-dot off" id="onlineDot"></span><span id="onlineTxt">未知</span></div><div class="l">在线状态</div></div>
            </div>
        </div>

        <div class="panel" style="margin-top:16px">
            <h3>🛒 购物车</h3>
            <div id="cartList"><div style="color:var(--dim);font-size:12px;text-align:center;padding:18px">点击右侧商品加入购物车</div></div>

            <div style="margin-top:12px">
                <label style="font-size:12px;color:var(--dim);display:block;margin-bottom:6px">结算方式</label>
                <div class="settlement-row">
                    <div class="seg active" data-set="backpack" onclick="setSettlement('backpack')">🎒 塞背包 (98折)</div>
                    <div class="seg" data-set="shulker" onclick="setSettlement('shulker')">📦 潜影盒（原色免费）</div>
                </div>

                <!-- ★ 收款模式切换（管理员可用 / 收银员看权限） -->
                <div id="payModeWrap" style="margin-top:10px">
                    <label style="font-size:12px;color:var(--dim);display:block;margin-bottom:6px">收款模式</label>
                    <div class="settlement-row" id="payModeRow">
                        <div class="seg active" data-pm="bond" onclick="setPayMode('bond')">💰 债券收款（扣余额）</div>
                        <div class="seg" data-pm="cash" onclick="setPayMode('cash')" id="cashModeBtn">💵 现金收款（仅记账）</div>
                    </div>
                </div>
                <div id="shulkerColors" class="hidden" style="margin-bottom:10px">
                    <label style="font-size:12px;color:var(--dim);display:block;margin-bottom:6px">潜影盒颜色（原色免费，其它颜色+2元）</label>
                    <select id="shulkerColor" onchange="recalc()">
                        <option value="default">原色（免费）</option>
                        <option value="white">白色</option>
                        <option value="black">黑色</option>
                        <option value="red">红色</option>
                        <option value="blue">蓝色</option>
                        <option value="green">绿色</option>
                        <option value="yellow">黄色</option>
                        <option value="orange">橙色</option>
                    </select>
                </div>

                <label style="font-size:12px;color:var(--dim);display:block;margin-bottom:6px">手动折扣（%）— 上限 <b id="discLimit">0</b>%</label>
                <div style="display:flex;gap:8px;align-items:center">
                    <input type="number" id="discount" min="0" value="0" oninput="recalc()" style="flex:1">
                    <span style="font-size:12px;color:var(--dim)">off</span>
                </div>

                <div id="summary" style="margin-top:14px"></div>

                <button class="btn btn-green" id="payBtn" style="width:100%;margin-top:14px;padding:12px;font-size:15px" onclick="confirmPay()" disabled>确认收款</button>
                <div id="adminPwWrap" class="field hidden" style="margin-top:12px">
                    <label>管理员密码（≥1000债券需确认）</label>
                    <input type="password" id="adminPw" placeholder="输入管理员密码">
                </div>
            </div>
        </div>
    </div>

    <!-- 右：商品目录 -->
    <div class="panel">
        <h3>🛍️ 商品目录</h3>
        <div class="catalog-head">
            <input type="text" id="prodSearch" placeholder="搜索商品名…" oninput="renderCatalog()" style="flex:1">
        </div>
        <div class="cat-tabs" id="catTabs"></div>
        <div class="prod-grid" id="prodGrid"><div style="color:var(--dim);font-size:12px;padding:18px">加载中…</div></div>

        <div class="orders">
            <h3 style="margin-top:6px">📜 订单记录 <button class="btn btn-ghost" style="float:right;padding:4px 10px;font-size:11px" onclick="loadOrders()">刷新</button></h3>
            <div id="orderList"><div style="color:var(--dim);font-size:12px;padding:10px">暂无订单</div></div>
        </div>
    </div>
</div>
<?php endif; ?>

<div id="toastHost"></div>

<script>
const STATE = <?php echo $initialState; ?>;
// 动态更新打包费提示（与后端 packmoney 配置一致，避免硬编码 +2 元）
(function() {
    const lbl = document.querySelector('#shulkerColors label');
    if (lbl) lbl.textContent = '潜影盒颜色（原色免费，其它颜色+ ' + (STATE.packmoney || 2) + ' 元打包费）';
})();
let products = [];
let categories = [];
let cart = {}; // id -> qty
let settlement = 'backpack';
let payMode = 'bond'; // bond=债券收款(扣余额), cash=现金收款(仅记账)
let currentPlayer = null;
let playerBalance = 0;

// ===== 工具 =====
function toast(msg, type) {
    const t = document.createElement('div');
    t.className = 'toast ' + (type || 'ok');
    t.textContent = msg;
    document.getElementById('toastHost').appendChild(t);
    setTimeout(() => t.remove(), 2600);
}
async function api(url, opts) {
    const r = await fetch(url, Object.assign({headers:{'X-Requested-With':'cashier'}}, opts));
    let data;
    try { data = await r.json(); } catch(e) { throw new Error('服务器响应异常'); }
    return data;
}
function esc(s){ return String(s==null?'':s).replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }

// ===== 主题 =====
function showThemePicker() {
    const overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.6);display:flex;justify-content:center;align-items:center;z-index:2000';
    overlay.innerHTML = `<div style="background:var(--card);border:1px solid var(--border);border-radius:12px;padding:24px;width:460px;max-width:90%">
        <h3 style="margin-bottom:12px">🎨 选择背景颜色</h3>
        <div class="theme-picker">
            <div class="color-btn" style="background:#0d1117" onclick="setTheme('#0d1117')"></div>
            <div class="color-btn" style="background:#1a1e2e" onclick="setTheme('#1a1e2e')"></div>
            <div class="color-btn" style="background:#0f4c75" onclick="setTheme('#0f4c75')"></div>
            <div class="color-btn" style="background:#1b2631" onclick="setTheme('#1b2631')"></div>
            <div class="color-btn" style="background:#2c3e50" onclick="setTheme('#2c3e50')"></div>
            <div class="color-btn" style="background:#23272a" onclick="setTheme('#23272a')"></div>
        </div>
        <div style="margin-top:16px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
            <input type="text" id="customColor" placeholder="#1a237e" class="color-input" style="flex:1;min-width:120px;padding:7px 10px;background:var(--bg);border:1px solid var(--border);border-radius:4px;color:var(--text);font-size:13px">
            <button class="btn btn-blue" onclick="applyCustomColor()">应用</button>
        </div>
        <div style="text-align:right;margin-top:14px"><button class="btn btn-ghost" onclick="this.closest('div').parentElement.remove()">关闭</button></div>
    </div>`;
    document.body.appendChild(overlay);
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
}
function setTheme(color) {
    document.documentElement.style.setProperty('--bg', color);
    document.body.style.background = color;
    localStorage.setItem('sdf1_cashier_theme', color);
    toast('主题已应用', 'ok');
}
function applyCustomColor() {
    const v = document.getElementById('customColor').value.trim();
    if (/^#?[0-9A-Fa-f]{6}$/.test(v)) { setTheme(v[0]==='#'?v:'#'+v); document.querySelector('.theme-picker')?.closest('div').parentElement.remove(); }
    else toast('请输入有效的十六进制颜色', 'err');
}

// ===== 登录/登出 =====
async function doLogin(e) {
    e.preventDefault();
    const btn = document.getElementById('loginBtn');
    btn.disabled = true;
    try {
        const data = await api('api/cashier.php?action=login', {
            method:'POST',
            headers:{'Content-Type':'application/x-www-form-urlencoded'},
            body:'username=' + encodeURIComponent(document.getElementById('loginUser').value) + '&password=' + encodeURIComponent(document.getElementById('loginPass').value)
        });
        if (data.success) { location.reload(); }
        else { toast(data.message || '登录失败', 'err'); btn.disabled = false; }
    } catch(err) { toast(err.message, 'err'); btn.disabled = false; }
    return false;
}
async function doLogout() {
    await api('api/cashier.php?action=logout');
    location.reload();
}

// ===== 初始化（已登录）=====
async function initApp() {
    document.getElementById('whoName').textContent = STATE.username;
    const roleEl = document.getElementById('whoRole');
    roleEl.textContent = STATE.role === 'admin' ? '管理员' : '收银员';
    roleEl.className = 'tag ' + (STATE.role === 'admin' ? 'tag-admin' : 'tag-cashier');
    document.getElementById('discLimit').textContent = STATE.discount_limit;
    // 管理员需密码确认大额
    if (STATE.role === 'admin') document.getElementById('adminPwWrap').classList.remove('hidden');

    // ★ 收款模式权限控制
    const cashModeBtn = document.getElementById('cashModeBtn');
    if (STATE.role === 'admin') {
        // 管理员始终可用现金模式
        cashModeBtn.style.display = '';
        document.getElementById('payModeWrap').style.display = '';
    } else if (STATE.cashier_can_cash) {
        // 收银员：管理员授权了现金收款权限
        cashModeBtn.style.display = '';
        document.getElementById('payModeWrap').style.display = '';
    } else {
        // 收银员无现金权限 → 隐藏现金按钮，锁定债券模式
        cashModeBtn.style.display = 'none';
        // 不隐藏整个 payModeWrap，但只显示债券模式
    }

    await Promise.all([loadProducts(), loadOrders()]);
}

async function loadProducts() {
    try {
        const d = await api('api/shop.php?action=list');
        products = (d.data && Array.isArray(d.data)) ? d.data : [];
        const dc = await api('api/shop.php?action=categories');
        categories = (dc.data && Array.isArray(dc.data)) ? dc.data : [];
        buildCatTabs();
        renderCatalog();
    } catch(e) { toast('商品加载失败: ' + e.message, 'err'); }
}
function buildCatTabs() {
    const wrap = document.getElementById('catTabs');
    let html = '<div class="seg active" data-cat="" onclick="setCat(\'\')">全部</div>';
    categories.forEach(c => {
        html += '<div class="seg" data-cat="'+esc(c.category)+'" onclick="setCat(\''+esc(c.category)+'\')">'+esc(c.category)+'</div>';
    });
    wrap.innerHTML = html;
}
let curCat = '';
function setCat(cat) {
    curCat = cat;
    document.querySelectorAll('#catTabs .seg').forEach(s => s.classList.toggle('active', s.dataset.cat === cat));
    renderCatalog();
}
function renderCatalog() {
    const q = (document.getElementById('prodSearch').value || '').toLowerCase();
    const grid = document.getElementById('prodGrid');
    const list = products.filter(p => {
        if (curCat && p.category !== curCat) return false;
        if (q && !(p.display_name||'').toLowerCase().includes(q)) return false;
        return true;
    });
    if (!list.length) { grid.innerHTML = '<div style="color:var(--dim);font-size:12px;padding:18px">无匹配商品</div>'; return; }
    grid.innerHTML = list.map(p => {
        const sold = (p.stock === 0);
        return `<div class="prod ${sold?'soldout':''}" ${sold?'':`onclick="addToCart('${esc(p.id)}')"`}>
            <div class="pstock">${sold?'售罄':('库存'+esc(p.stock))}</div>
            <div class="pname">${esc(p.display_name)}</div>
            <div class="pcat">${esc(p.category||'')}</div>
            <div class="pprice">${esc(p.buy_price)} 债券</div>
        </div>`;
    }).join('');
}

function addToCart(id) {
    cart[id] = (cart[id] || 0) + 1;
    renderCart(); recalc();
}
function changeQty(id, d) {
    cart[id] = (cart[id] || 0) + d;
    if (cart[id] <= 0) delete cart[id];
    renderCart(); recalc();
}
function removeFromCart(id) { delete cart[id]; renderCart(); recalc(); }
function renderCart() {
    const el = document.getElementById('cartList');
    const ids = Object.keys(cart);
    if (!ids.length) { el.innerHTML = '<div style="color:var(--dim);font-size:12px;text-align:center;padding:18px">点击右侧商品加入购物车</div>'; return; }
    el.innerHTML = ids.map(id => {
        const p = products.find(x => String(x.id) === String(id));
        if (!p) return '';
        return `<div class="cart-item">
            <div class="ci-name">${esc(p.display_name)}<small>${esc(p.buy_price)} 债券/个</small></div>
            <div class="qty">
                <button onclick="changeQty('${esc(id)}',-1)">−</button>
                <input type="number" min="1" value="${cart[id]}" onchange="cart['${esc(id)}']=Math.max(1,parseInt(this.value)||1);renderCart();recalc()">
                <button onclick="changeQty('${esc(id)}',1)">+</button>
            </div>
            <span class="ci-del" onclick="removeFromCart('${esc(id)}')">✕</span>
        </div>`;
    }).join('');
}

function setSettlement(s) {
    settlement = s;
    document.querySelectorAll('.settlement-row .seg').forEach(x => x.classList.toggle('active', x.dataset.set === s));
    document.getElementById('shulkerColors').classList.toggle('hidden', s !== 'shulker');
    recalc();
}

// ★ 收款模式切换
function setPayMode(pm) {
    payMode = pm;
    document.querySelectorAll('#payModeRow .seg').forEach(x => x.classList.toggle('active', x.dataset.pm === pm));

    // 现金模式下提示"仅记账不扣债券"
    if (pm === 'cash') {
        toast('现金收款模式：仅记账，不扣除玩家债券', 'ok');
    }
    recalc();
}

// ===== 计算 + 预览 =====
const RATES = { backpack: 0.98, shulker: 1.00 };
async function recalc() {
    const ids = Object.keys(cart);
    const payBtn = document.getElementById('payBtn');
    if (!ids.length || !currentPlayer) {
        document.getElementById('summary').innerHTML = '';
        payBtn.disabled = true;
        return;
    }
    let subtotal = 0;
    ids.forEach(id => {
        const p = products.find(x => String(x.id) === String(id));
        if (p) subtotal += (parseInt(p.buy_price)||0) * cart[id];
    });
    const rate = RATES[settlement];
    let afterRate = Math.round(subtotal * rate);
    // 环保单减免：不打包/塞背包时按配置比例减免（与游戏内"不打包"一致）
    let ecoPct = 0;
    let ecoAmt = 0;
    if (settlement === 'backpack') ecoPct = STATE.green_discount || 0;
    if (ecoPct > 0) {
        ecoAmt = afterRate - Math.round(afterRate * (100 - ecoPct) / 100);
        afterRate -= ecoAmt;
    }
    let colorFee = 0;
    const sc = document.getElementById('shulkerColor').value;
    if (settlement === 'shulker' && sc !== 'default' && sc !== 'purple') colorFee = STATE.packmoney || 2;
    let total = afterRate + colorFee;

    let discount = parseFloat(document.getElementById('discount').value) || 0;
    const limit = STATE.discount_limit;
    if (discount > limit) { discount = limit; document.getElementById('discount').value = limit; }
    if (discount < 0) discount = 0;
    const discAmt = Math.round(total * discount / 100);
    const final = total - discAmt;

    document.getElementById('summary').innerHTML = `
        <div class="summary-line"><span>商品小计</span><span>${subtotal} 债券</span></div>
        <div class="summary-line"><span>${settlement==='shulker'?'潜影盒费率':'背包费率'} (${rate*100}%)</span><span>${colorFee?('+'+colorFee):''}</span></div>
        ${ecoPct>0?`<div class="summary-line"><span class="disc">环保单减免 ${ecoPct}%</span><span class="disc">-${ecoAmt} 债券</span></div>`:''}
        <div class="summary-line"><span>应收原价</span><span>${total} 债券</span></div>
        <div class="summary-line"><span class="disc">手动折扣 ${discount}%</span><span class="disc">-${discAmt} 债券</span></div>
        ${payMode === 'cash' ? '<div class="summary-line" style="border-color:var(--orange);color:var(--orange)"><span>💵 现金收款模式</span><span>仅记账，不扣债券</span></div>' : ''}
        <div class="summary-line total"><span>实收${payMode==='cash'?'(记账)':''}</span><span class="v">${final} ${payMode==='cash'?'债券':''}</span></div>`;
    payBtn.disabled = false;
}

// ===== 玩家查询 =====
async function checkPlayer() {
    const name = document.getElementById('playerName').value.trim();
    if (!name) { toast('请输入玩家名', 'err'); return; }
    try {
        const d = await api('api/cashier.php?action=player_check&player=' + encodeURIComponent(name));
        if (!d.success) { toast(d.message, 'err'); return; }
        currentPlayer = name;
        playerBalance = d.data.balance;
        document.getElementById('balVal').textContent = playerBalance;
        const dot = document.getElementById('onlineDot');
        const txt = document.getElementById('onlineTxt');
        if (d.data.online) { dot.className = 'online-dot on'; txt.textContent = '在线'; }
        else { dot.className = 'online-dot off'; txt.textContent = '离线'; }
        recalc();
    } catch(e) { toast('查询失败: ' + e.message, 'err'); }
}

// ===== 确认收款 =====
async function confirmPay() {
    const ids = Object.keys(cart);
    if (!ids.length) { toast('购物车为空', 'err'); return; }
    if (!currentPlayer) { toast('请先查询目标玩家', 'err'); return; }
    const discount = parseFloat(document.getElementById('discount').value) || 0;
    if (discount > STATE.discount_limit) { toast('折扣超过权限上限('+STATE.discount_limit+'%)', 'err'); return; }

    const items = ids.map(id => ({ item_id: id, amount: cart[id] }));
    const body = new URLSearchParams();
    body.set('items', JSON.stringify(items));
    body.set('settlement', settlement);
    body.set('pay_mode', payMode); // ★ 新增：收款模式（bond/cash）
    body.set('shulker_color', document.getElementById('shulkerColor').value);
    body.set('player', currentPlayer);
    body.set('discount_percent', String(discount));
    if (STATE.role === 'admin') body.set('password', document.getElementById('adminPw').value || '');

    const btn = document.getElementById('payBtn');
    btn.disabled = true;
    try {
        const d = await api('api/shop.php?action=buy_cart', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body.toString() });
        if (d.need_password) {
            // 管理员大额需密码：弹窗输入后重试
            promptAdminPassword().then(pw => {
                if (pw === null) { btn.disabled = false; return; }
                body.set('password', pw);
                api('api/shop.php?action=buy_cart', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: body.toString() })
                    .then(d2 => finishPay(d2)).catch(e => { toast(e.message,'err'); btn.disabled=false; });
            });
            return;
        }
        finishPay(d);
    } catch(e) { toast('收款失败: ' + e.message, 'err'); btn.disabled = false; }
}
function finishPay(d) {
    const btn = document.getElementById('payBtn');
    if (d.success) {
        if (payMode === 'cash') {
            toast('✅ 现金收款记账成功（订单 #' + (d.data.order_id || '') + '，未扣玩家债券）', 'ok');
        } else {
            toast('收款成功，实收 ' + (d.data.total_price) + ' 债券', 'ok');
        }
        cart = {}; renderCart();
        document.getElementById('summary').innerHTML = '';
        document.getElementById('discount').value = 0;
        btn.disabled = true;
        checkPlayer(); loadOrders();
    } else {
        toast(d.message || '收款失败', 'err');
        btn.disabled = false;
    }
}
function promptAdminPassword() {
    return new Promise(resolve => {
        const overlay = document.createElement('div');
        overlay.className = 'glass-alert-overlay show';
        overlay.innerHTML = `<div class="glass-alert-card">
            <div class="alert-icon">🔐</div>
            <div class="alert-msg">金额 ≥ 1000 债券，需确认管理员密码</div>
            <input class="alert-input" type="password" id="apw" placeholder="管理员密码" autofocus>
            <div class="alert-btns">
                <button class="ag-cancel" onclick="this.closest('.glass-alert-overlay').remove();window.__apwResolve(null)">取消</button>
                <button class="ag-ok" onclick="const v=document.getElementById('apw').value;this.closest('.glass-alert-overlay').remove();window.__apwResolve(v)">确认</button>
            </div>
        </div>`;
        document.body.appendChild(overlay);
        window.__apwResolve = resolve;
        document.getElementById('apw').addEventListener('keydown', e => { if (e.key==='Enter'){ const v=e.target.value; overlay.remove(); resolve(v);} });
        setTimeout(()=>document.getElementById('apw').focus(), 50);
    });
}

// ===== 订单记录 =====
async function loadOrders() {
    try {
        const d = await api('api/cashier.php?action=order_list&limit=50');
        const el = document.getElementById('orderList');
        if (!d.success || !d.data || !d.data.length) { el.innerHTML = '<div style="color:var(--dim);font-size:12px;padding:10px">暂无订单</div>'; return; }
        el.innerHTML = d.data.map(o => {
            const items = (o.items_detail && Array.isArray(o.items_detail)) ? o.items_detail.map(i=>esc(i.name)+'x'+i.amount).join('、') : '';
            const disc = (parseInt(o.discount_percent)>0) ? ` <span class="disc">(${o.discount_percent}% off -${o.discount_amount})</span>` : '';
            return `<div class="order-row">
                <div class="ot"><span class="ono">${esc(o.order_no)}</span><span>${o.operator_type==='admin'?'管理员':'收银员'}:${esc(o.operator_name)}</span></div>
                <div class="od">玩家 ${esc(o.player_name)} · ${items}${disc}</div>
                <div class="od">实收 <b style="color:var(--green)">${o.total_price}</b> 债券 · ${new Date((o.created_at||0)*1000).toLocaleString('zh-CN')}</div>
            </div>`;
        }).join('');
    } catch(e) { /* 忽略 */ }
}

// ===== 启动 =====
(function(){
    const saved = localStorage.getItem('sdf1_cashier_theme');
    if (saved) { document.documentElement.style.setProperty('--bg', saved); document.body.style.background = saved; }
    if (STATE.logged_in) initApp();
})();
</script>
</body>
</html>
